//! The dev server process and its state machine.
//!
//! ```text
//! stopped/crashed ──start──▶ preparing ──launch──▶ starting ──"Done ("──▶ running
//!        ▲                      │ stop (cancels)       │ stop                │ stop
//!        │                      ▼                      ▼                     ▼
//!        └──────── stopped ◀── exit ◀──────────────── stopping ◀────────────┘
//!   unexpected exit from starting/running ──▶ crashed (with a message)
//! ```
//!
//! Every start gets a new *generation*. Tasks (output readers, the exit
//! monitor, bridge callbacks) carry theirs and are ignored once it's stale, so
//! a late line from a previous run can never flip the state of the next one.
//!
//! Locks: `state` before `running` when both are needed; never hold either
//! across an await. `preparing` is async and held by a start for its whole
//! preparation: `stop` during `preparing` reports `stopped` at once, but the
//! cancelled preparation only notices at its next check, so a new start waits
//! on this lock until the old one has left the server folder.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::process::{ChildStdin, Command};
use tokio::sync::{oneshot, watch};

use crate::app::dirs::AppDirs;
use crate::app::events::{BridgeMessage, EventSink};
use crate::app::plugins;
use crate::app::tools::{PaperSource, Tools};
use crate::bridge::protocol::{self, Hello};
use crate::bridge::{self, Bridge, BridgeHandler};
use crate::error::{Context, Error, ErrorCode, Result, bail};
use crate::minecraft::cache;
use crate::settings::Settings;

use super::leftover;
use super::network::setup::{self as network_setup, ProxyPlan, Secrets};
use super::{
    PrepareProgress, PrepareStep, ServerOutputEvent, ServerPhase, ServerState, Stream, java, paper,
    progress, setup,
};

/// How long `stop` waits after sending `stop` before killing the JVM.
pub const STOP_TIMEOUT: Duration = Duration::from_secs(30);

pub struct StartConfig {
    /// The project the server runs (a peer's own; the open project's for the
    /// proxy, which runs none but is recognised by it as a leftover).
    pub project_root: PathBuf,
    pub project_name: String,
    /// From netherforge.json.
    pub minecraft: Option<String>,
    pub settings: Settings,
    pub eula_accepted: bool,
    /// Where the plugin, Java and Paper come from.
    pub tools: Arc<Tools>,
    pub role: Role,
}

/// Which of a project's servers this is.
pub enum Role {
    /// The open project's own server, in its server folder: alone, or, with a
    /// [Membership], in its dev network (players then join the proxy on
    /// Settings' port).
    Main(Option<Membership>),
    /// Another server of the open project's dev network, running its own
    /// project in its own folder.
    Peer {
        membership: Membership,
        dir: PathBuf,
    },
    /// The dev network's proxy (Velocity), in its folder.
    Proxy(ProxyRole),
}

/// A Paper server's place in a dev network.
#[derive(Debug, Clone)]
pub struct Membership {
    /// Its name there.
    pub name: String,
    /// Where it listens, on 127.0.0.1, for the proxy.
    pub port: u16,
    pub hub_port: u16,
    pub secrets: Secrets,
}

pub struct ProxyRole {
    pub dir: PathBuf,
    pub hub_port: u16,
    /// Each server's name and port; players join the first.
    pub servers: Vec<(String, u16)>,
    pub secrets: Secrets,
}

impl Role {
    fn membership(&self) -> Option<&Membership> {
        match self {
            Self::Main(membership) => membership.as_ref(),
            Self::Peer { membership, .. } => Some(membership),
            Self::Proxy(_) => None,
        }
    }
}

/// The proxy's heap: Velocity needs far less than a server.
const PROXY_MEMORY_MB: u32 = 512;

struct Running {
    generation: u64,
    stdin: Arc<tokio::sync::Mutex<ChildStdin>>,
    kill: Option<oneshot::Sender<()>>,
    exited: watch::Receiver<bool>,
    /// None for the proxy, which has no plugin of ours to talk to.
    bridge: Option<Bridge>,
}

pub struct ServerManager {
    dirs: AppDirs,
    sink: Arc<dyn EventSink>,
    state: Mutex<ServerState>,
    /// Held from a change to its state's emit, so states are heard in the order they happened.
    /// Not [state] itself: a network member's sink reads the state back while it emits.
    emitting: Mutex<()>,
    generation: AtomicU64,
    running: Mutex<Option<Running>>,
    preparing: tokio::sync::Mutex<()>,
    exporting: AtomicBool,
    http: OnceLock<reqwest::Client>,
    /// The folder it last ran in: its world, where a map capture copies from.
    folder: Mutex<Option<PathBuf>>,
}

impl ServerManager {
    pub fn new(dirs: AppDirs, sink: Arc<dyn EventSink>) -> Arc<Self> {
        Arc::new(Self {
            dirs,
            sink,
            state: Mutex::new(ServerState::default()),
            emitting: Mutex::new(()),
            generation: AtomicU64::new(0),
            running: Mutex::new(None),
            preparing: tokio::sync::Mutex::new(()),
            exporting: AtomicBool::new(false),
            http: OnceLock::new(),
            folder: Mutex::new(None),
        })
    }

    pub fn state(&self) -> ServerState {
        self.state.lock().unwrap().clone()
    }

    /// The folder it last ran (or runs) in, once it has.
    pub fn folder(&self) -> Option<PathBuf> {
        self.folder.lock().unwrap().clone()
    }

    fn is_current(&self, generation: u64) -> bool {
        self.generation.load(Ordering::SeqCst) == generation
    }

    /// Applies [change] if [generation] is still current (or None), and emits
    /// `server://state` when something changed.
    fn update(&self, generation: Option<u64>, change: impl FnOnce(&mut ServerState)) {
        let _in_order = self.emitting.lock().unwrap();
        let snapshot = {
            let mut state = self.state.lock().unwrap();
            if generation.is_some_and(|g| !self.is_current(g)) {
                return;
            }
            let before = state.clone();
            change(&mut state);
            if *state == before {
                return;
            }
            state.clone()
        };
        self.sink.emit(&snapshot);
    }

    fn report(&self, progress: PrepareProgress) {
        self.sink.emit(&progress);
    }

    fn output(&self, stream: Stream, line: String) {
        self.sink.emit(&ServerOutputEvent { stream, line });
    }

    fn http(&self) -> Result<&reqwest::Client> {
        if let Some(client) = self.http.get() {
            return Ok(client);
        }
        let client = super::download::http_client()?;
        Ok(self.http.get_or_init(|| client))
    }

    /// Shows which version the open project targets while nothing runs.
    pub fn set_target(&self, minecraft: Option<String>) {
        self.update(None, |state| {
            if !state.phase.is_active() {
                *state = ServerState {
                    minecraft,
                    ..ServerState::default()
                };
            }
        });
    }

    /// Moves to `preparing` with a new generation, or fails if a server is
    /// active. Waits first for any earlier preparation (one just cancelled) to
    /// finish; the guard is this preparation's, held until it launches or fails.
    async fn begin(
        &self,
        minecraft: Option<&str>,
    ) -> Result<(u64, tokio::sync::MutexGuard<'_, ()>)> {
        let preparing = self.preparing.lock().await;
        let snapshot;
        let generation;
        {
            let mut state = self.state.lock().unwrap();
            if state.phase.is_active() {
                bail!(Busy, "The dev server is already running");
            }
            generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
            *state = ServerState {
                phase: ServerPhase::Preparing,
                minecraft: minecraft.map(String::from),
                port: None,
                bridge_connected: false,
                message: Some("Preparing the dev server".into()),
            };
            snapshot = state.clone();
        }
        self.sink.emit(&snapshot);
        Ok((generation, preparing))
    }

    /// Prepares and launches the server; returns once the JVM is running
    /// (phase `starting`). `server://state` reports the rest.
    pub async fn start(self: &Arc<Self>, config: StartConfig) -> Result<()> {
        if !config.eula_accepted {
            bail!(
                EulaRequired,
                "Accept the Minecraft EULA ({}) before starting the dev server",
                crate::settings::EULA_URL
            );
        }
        if self.state().phase.is_active() {
            bail!(Busy, "The dev server is already running");
        }
        let minecraft = match &config.role {
            Role::Proxy(_) => None,
            _ => {
                let minecraft = config.minecraft.clone().context(|| {
                    "netherforge.json has no usable Minecraft version: \"minecraft\" must name a release, like \"26.3\"".into()
                })?;
                check_plugin(&config.tools, &minecraft)?;
                Some(minecraft)
            }
        };
        let (generation, _preparing) = self.begin(minecraft.as_deref()).await?;
        let result = match &minecraft {
            Some(minecraft) => {
                self.prepare_and_launch(generation, &config, minecraft)
                    .await
            }
            None => self.prepare_and_launch_proxy(generation, &config).await,
        };
        if let Err(error) = &result {
            let message = error.to_string();
            self.update(Some(generation), |state| {
                state.phase = ServerPhase::Stopped;
                state.message = Some(message);
            });
        }
        result
    }

    async fn prepare_and_launch(
        self: &Arc<Self>,
        generation: u64,
        config: &StartConfig,
        minecraft: &str,
    ) -> Result<()> {
        let cancelled = || !self.is_current(generation);
        let check = || -> Result<()> {
            if cancelled() {
                bail!(Cancelled, "Cancelled");
            }
            Ok(())
        };
        let report = |p: PrepareProgress| self.report(p);
        let status = |message: &str| self.status(generation, message);

        let java = self.java(generation, config).await?;
        check()?;

        // The plugin first: it names the Paper build to run.
        let plugin = plugins::find(&config.tools.plugin_dirs, minecraft)
            .context(|| format!("No NetherForge plugin for Minecraft {minecraft}"))?;
        status(&format!("Getting Paper {minecraft}"));
        let paper_jar = match &config.tools.paper {
            PaperSource::Fill => {
                let build = plugins::paper_build(&plugin)?;
                paper::ensure(
                    &self.dirs,
                    self.http()?,
                    minecraft,
                    build,
                    &report,
                    &cancelled,
                )
                .await?
            }
            PaperSource::Jar(jar) => jar.clone(),
        };
        check()?;

        status("Setting up the server folder");
        let membership = config.role.membership().cloned();
        let dir = match &config.role {
            Role::Peer { dir, .. } => dir.clone(),
            _ => self.dirs.server_dir(&config.project_root),
        };
        // Players join the proxy on Settings' port; a server behind it has its own.
        let port = membership
            .as_ref()
            .map_or(config.settings.server_port, |m| m.port);
        // Copying the plugin is blocking file work: off the async workers.
        tokio::task::spawn_blocking({
            let dir = dir.clone();
            let root = config.project_root.clone();
            let plugin = plugin.clone();
            let motd = format!("NetherForge dev server: {}", config.project_name);
            let forwarding = membership.as_ref().map(|m| m.secrets.forwarding.clone());
            move || {
                setup::prepare(&dir, &root, &plugin, port, &motd)?;
                network_setup::paper_member(&dir, forwarding.as_deref())
            }
        })
        .await
        .map_err(|e| Error::msg(e.to_string()))??;
        if let Role::Peer { .. } = config.role {
            // The open project's git packages are the UI's to fetch; a peer's are fetched here, at the lock's pins.
            status("Fetching git packages");
            let cache = self.dirs.packages();
            let root = config.project_root.clone();
            let failures = tokio::task::spawn_blocking(move || fetch_locked_git(&cache, &root))
                .await
                .map_err(|e| Error::msg(e.to_string()))?;
            for failure in failures {
                self.output(Stream::Stderr, format!("[NetherForge editor] {failure}"));
            }
            check()?;
        }
        report(progress(
            PrepareStep::Plugin,
            format!(
                "Installed NetherForge {} for Minecraft {minecraft}",
                plugin.plugin_version
            ),
            1,
            Some(1),
        ));
        // A server an earlier session left behind (the editor crashed or was
        // killed) would hold the port; stop it rather than fail on it.
        self.stop_leftover(generation, &dir, &config.project_root)
            .await?;
        check_port_free(port)?;
        check()?;

        let token = random_token();
        let handler = Arc::new(Handler {
            manager: Arc::downgrade(self),
            generation,
        });
        let bridge = Bridge::listen(token.clone(), handler).await?;
        let mut args = setup::java_args(
            &config.settings,
            &config.project_root,
            &self.dirs.packages(),
            &paper_jar,
            bridge.port(),
        );
        let mut envs = vec![("NETHERFORGE_BRIDGE_TOKEN".to_string(), token)];
        if let Some(membership) = &membership {
            // Before `-jar`: they're the JVM's.
            let at = args.len() - 3;
            args.splice(
                at..at,
                [
                    format!(
                        "-Dnetherforge.network.hub=127.0.0.1:{}",
                        membership.hub_port
                    ),
                    format!("-Dnetherforge.network.server={}", membership.name),
                ],
            );
            envs.push((
                "NETHERFORGE_NETWORK_SECRET".to_string(),
                membership.secrets.hub.clone(),
            ));
        }
        // What the editor shows players join: the proxy's, in a network.
        let shown = match &config.role {
            Role::Peer { .. } => port,
            _ => config.settings.server_port,
        };
        self.launch(generation, &java, &args, &dir, &envs, Some(bridge), shown)
            .await
    }

    /// The dev network's proxy: Velocity (the build the proxy plugin names)
    /// with the proxy plugin, in its folder.
    async fn prepare_and_launch_proxy(
        self: &Arc<Self>,
        generation: u64,
        config: &StartConfig,
    ) -> Result<()> {
        let Role::Proxy(proxy) = &config.role else {
            bail!("Not a proxy");
        };
        let cancelled = || !self.is_current(generation);
        let check = || -> Result<()> {
            if cancelled() {
                bail!(Cancelled, "Cancelled");
            }
            Ok(())
        };
        let report = |p: PrepareProgress| self.report(p);
        let java = self.java(generation, config).await?;
        check()?;

        let plugin = plugins::find_proxy(&config.tools.plugin_dirs).context(|| {
            "This NetherForge build has no proxy plugin. In a dev checkout, build it with node tools/gradle.mjs :proxy:velocity:assemble".into()
        })?;
        self.status(generation, "Getting Velocity");
        let velocity = match &config.tools.paper {
            PaperSource::Fill => {
                let (version, build) = plugins::velocity_build(&plugin)?;
                paper::ensure_project(
                    &self.dirs,
                    self.http()?,
                    paper::Project::Velocity,
                    &version,
                    build,
                    &report,
                    &cancelled,
                )
                .await?
            }
            PaperSource::Jar(jar) => jar.clone(),
        };
        check()?;

        self.status(generation, "Setting up the proxy's folder");
        let port = config.settings.server_port;
        tokio::task::spawn_blocking({
            let dir = proxy.dir.clone();
            let servers = proxy.servers.clone();
            let secrets = proxy.secrets.clone();
            let hub_port = proxy.hub_port;
            let motd = format!("NetherForge dev network: {}", config.project_name);
            move || {
                let plan = ProxyPlan {
                    port,
                    hub_port,
                    servers: &servers,
                    motd: &motd,
                };
                network_setup::proxy(&dir, &plugin, &plan, &secrets)
            }
        })
        .await
        .map_err(|e| Error::msg(e.to_string()))??;
        report(progress(
            PrepareStep::Proxy,
            format!("Set up {}", velocity.display()),
            1,
            Some(1),
        ));
        self.stop_leftover(generation, &proxy.dir, &config.project_root)
            .await?;
        check_port_free(port)?;
        check()?;

        let args = vec![
            format!("-Xmx{PROXY_MEMORY_MB}M"),
            // Recognises it as this project's when an earlier session left it running.
            leftover::project_arg(&config.project_root),
            "-jar".into(),
            velocity.display().to_string(),
        ];
        self.launch(generation, &java, &args, &proxy.dir, &[], None, port)
            .await
    }

    fn status(&self, generation: u64, message: &str) {
        let message = message.to_string();
        self.update(Some(generation), |s| s.message = Some(message));
    }

    /// Java 25: found, or downloaded into the data folder.
    async fn java(&self, generation: u64, config: &StartConfig) -> Result<PathBuf> {
        let cancelled = || !self.is_current(generation);
        let report = |p: PrepareProgress| self.report(p);
        self.status(generation, "Finding Java");
        let java = match java::find(&config.tools.java).await {
            Some((java, _)) => java,
            None => {
                self.status(generation, "Downloading Java");
                java::install(&self.dirs, self.http()?, &report, &cancelled).await?
            }
        };
        report(progress(
            PrepareStep::Java,
            format!("Using {}", java.display()),
            1,
            Some(1),
        ));
        Ok(java)
    }

    /// A server an earlier session left behind in [dir] (the editor crashed or
    /// was killed) would hold the port; stop it rather than fail on it.
    async fn stop_leftover(&self, generation: u64, dir: &Path, project_root: &Path) -> Result<()> {
        if let Some(pid) = leftover::find(dir, project_root) {
            self.status(
                generation,
                "Stopping the server an earlier session left running",
            );
            self.output(
                Stream::Stdout,
                format!("[NetherForge] Stopping the dev server left running by an earlier session (pid {pid})"),
            );
            leftover::stop(dir, pid, leftover::GRACE).await;
            if !self.is_current(generation) {
                bail!(Cancelled, "Cancelled");
            }
        }
        Ok(())
    }

    /// Spawns the process and the tasks that watch it; moves to `starting`.
    #[allow(clippy::too_many_arguments)]
    async fn launch(
        self: &Arc<Self>,
        generation: u64,
        program: &Path,
        args: &[String],
        cwd: &Path,
        envs: &[(String, String)],
        bridge: Option<Bridge>,
        port: u16,
    ) -> Result<()> {
        let mut command = Command::new(program);
        command
            .args(args)
            .current_dir(cwd)
            .envs(envs.iter().map(|(k, v)| (k, v)))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        hide_console(&mut command);
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                shutdown(&bridge);
                bail!("Couldn't start {}: {e}", program.display());
            }
        };
        let (Some(stdin), Some(stdout), Some(stderr)) =
            (child.stdin.take(), child.stdout.take(), child.stderr.take())
        else {
            shutdown(&bridge);
            bail!("Couldn't attach to the server's console");
        };

        if let Some(pid) = child.id() {
            // Best effort: without it, a leftover is still stopped by the plugin itself.
            let _ = leftover::record(cwd, pid);
        }
        *self.folder.lock().unwrap() = Some(cwd.to_path_buf());
        let server_dir = cwd.to_path_buf();

        let (kill_tx, kill_rx) = oneshot::channel::<()>();
        let (exit_tx, exit_rx) = watch::channel(false);
        {
            let mut running = self.running.lock().unwrap();
            if !self.is_current(generation) {
                drop(running);
                let _ = child.start_kill();
                shutdown(&bridge);
                bail!(Cancelled, "Cancelled");
            }
            *running = Some(Running {
                generation,
                stdin: Arc::new(tokio::sync::Mutex::new(stdin)),
                kill: Some(kill_tx),
                exited: exit_rx,
                bridge,
            });
        }
        self.update(Some(generation), |state| {
            state.phase = ServerPhase::Starting;
            state.port = Some(port);
            state.message = Some("Starting Paper".into());
        });

        tokio::spawn(self.clone().pump(generation, stdout, Stream::Stdout));
        tokio::spawn(self.clone().pump(generation, stderr, Stream::Stderr));
        let manager = self.clone();
        tokio::spawn(async move {
            let status = tokio::select! {
                status = child.wait() => status,
                Ok(()) = kill_rx => {
                    let _ = child.kill().await;
                    child.wait().await
                }
            };
            leftover::clear(&server_dir);
            manager.on_exit(generation, status);
            let _ = exit_tx.send(true);
        });
        Ok(())
    }

    async fn pump(self: Arc<Self>, generation: u64, stream: impl AsyncRead + Unpin, kind: Stream) {
        let mut reader = BufReader::new(stream);
        let mut buffer = Vec::new();
        loop {
            buffer.clear();
            match reader.read_until(b'\n', &mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            }
            if !self.is_current(generation) {
                continue;
            }
            let text = String::from_utf8_lossy(&buffer);
            let line = strip_ansi_escapes::strip_str(text.trim_end_matches(['\r', '\n']));
            let done = kind == Stream::Stdout && is_done_line(&line);
            self.output(kind, line);
            if done {
                self.update(Some(generation), |state| {
                    if state.phase == ServerPhase::Starting {
                        state.phase = ServerPhase::Running;
                        state.message = None;
                    }
                });
            }
        }
    }

    fn on_exit(&self, generation: u64, status: std::io::Result<std::process::ExitStatus>) {
        let running = {
            let mut running = self.running.lock().unwrap();
            if running.as_ref().is_some_and(|r| r.generation == generation) {
                running.take()
            } else {
                None
            }
        };
        if let Some(running) = running {
            shutdown(&running.bridge);
        }
        let code = match &status {
            Ok(status) => match status.code() {
                Some(code) => format!("exit code {code}"),
                None => "killed by a signal".into(),
            },
            Err(e) => e.to_string(),
        };
        self.update(Some(generation), |state| {
            if state.phase == ServerPhase::Stopping {
                state.phase = ServerPhase::Stopped;
                state.message = None;
            } else {
                state.phase = ServerPhase::Crashed;
                state.message = Some(format!(
                    "The server stopped unexpectedly ({code}). The console has the details."
                ));
            }
            state.port = None;
            state.bridge_connected = false;
        });
    }

    /// Sends `stop`, waits up to [STOP_TIMEOUT], then kills. Cancels a start
    /// that's still preparing.
    pub async fn stop(self: &Arc<Self>) -> Result<()> {
        self.stop_with(STOP_TIMEOUT).await
    }

    async fn stop_with(self: &Arc<Self>, timeout: Duration) -> Result<()> {
        let handles = {
            let mut state = self.state.lock().unwrap();
            match state.phase {
                ServerPhase::Stopped | ServerPhase::Crashed => return Ok(()),
                ServerPhase::Preparing => {
                    // Invalidate the start in flight; its next check bails.
                    self.generation.fetch_add(1, Ordering::SeqCst);
                    state.phase = ServerPhase::Stopped;
                    state.message = Some("Cancelled".into());
                    None
                }
                _ => {
                    let mut running = self.running.lock().unwrap();
                    match running.as_mut() {
                        Some(r) => {
                            state.phase = ServerPhase::Stopping;
                            state.message = Some("Stopping the server".into());
                            Some((r.stdin.clone(), r.exited.clone(), r.kill.take()))
                        }
                        None => {
                            state.phase = ServerPhase::Stopped;
                            state.message = None;
                            None
                        }
                    }
                }
            }
        };
        {
            let _in_order = self.emitting.lock().unwrap();
            self.sink.emit(&self.state());
        }
        let Some((stdin, exited, kill)) = handles else {
            return Ok(());
        };
        let _ = write_line(&stdin, "stop").await;
        if !wait_exited(exited.clone(), timeout).await {
            if let Some(kill) = kill {
                let _ = kill.send(());
            }
            wait_exited(exited, Duration::from_secs(10)).await;
        }
        Ok(())
    }

    /// Runs a console command. `stop` is treated as a requested stop.
    pub async fn command(self: &Arc<Self>, line: &str) -> Result<()> {
        let line = line.trim();
        if line.is_empty() {
            return Ok(());
        }
        if matches!(line.trim_start_matches('/'), "stop" | "end") {
            return self.stop().await;
        }
        let stdin = self
            .running
            .lock()
            .unwrap()
            .as_ref()
            .map(|r| r.stdin.clone())
            .ok_or_else(|| Error::new(ErrorCode::NotConnected, "The dev server isn't running"))?;
        write_line(&stdin, line).await
    }

    fn bridge(&self) -> Option<Bridge> {
        self.running
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|r| r.bridge.clone())
    }

    /// The backend's own request [method] with [params] to the plugin (a
    /// peer's `reload`).
    pub async fn bridge_request(
        &self,
        method: &str,
        params: serde_json::Value,
        timeout: Duration,
    ) -> Result<serde_json::Value> {
        self.running_bridge()?
            .request_with(method, Some(params), timeout)
            .await
    }

    /// Writes a line into the console the UI shows, as the editor's.
    pub fn note(&self, stream: Stream, line: String) {
        self.output(stream, line);
    }

    fn running_bridge(&self) -> Result<Bridge> {
        self.bridge()
            .ok_or_else(|| Error::new(ErrorCode::NotConnected, "The dev server isn't running"))
    }

    /// Relays one JSON-RPC frame from the UI to the plugin.
    pub fn bridge_send(&self, message: &str) -> Result<()> {
        self.running_bridge()?.send(message)
    }

    /// On the first hello for a version we have no server data for (or only
    /// an export of another schema than the editor reads), ask the plugin to export
    /// it and cache it.
    fn after_hello(self: &Arc<Self>, generation: u64, minecraft: String) {
        let Ok(needed) = cache::needs_export(&self.dirs, &minecraft) else {
            return;
        };
        if !needed || self.exporting.swap(true, Ordering::SeqCst) {
            return;
        }
        let manager = self.clone();
        tokio::spawn(async move {
            let result = match manager.running_bridge() {
                Ok(bridge) => {
                    bridge
                        .request(protocol::EXPORT_GAME_DATA, bridge::EXPORT_TIMEOUT)
                        .await
                }
                Err(error) => Err(error),
            };
            manager.exporting.store(false, Ordering::SeqCst);
            if !manager.is_current(generation) {
                return;
            }
            let saved =
                result.and_then(|data| cache::write_game_data(&manager.dirs, &minecraft, &data));
            match saved {
                Ok(()) => {
                    manager.output(
                        Stream::Stdout,
                        format!("[NetherForge editor] Cached game data for Minecraft {minecraft}"),
                    );
                    if let Ok(status) = cache::status(&manager.dirs, &minecraft) {
                        manager.sink.emit(&status);
                    }
                }
                Err(error) => manager.output(
                    Stream::Stderr,
                    format!("[NetherForge editor] Game data export failed: {error}"),
                ),
            }
        });
    }
}

struct Handler {
    manager: Weak<ServerManager>,
    generation: u64,
}

impl BridgeHandler for Handler {
    fn connected(&self, hello: &Hello) {
        if let Some(manager) = self.manager.upgrade() {
            manager.update(Some(self.generation), |s| s.bridge_connected = true);
            manager.after_hello(self.generation, hello.minecraft.clone());
        }
    }

    fn disconnected(&self) {
        if let Some(manager) = self.manager.upgrade() {
            manager.update(Some(self.generation), |s| s.bridge_connected = false);
        }
    }

    fn refused(&self, reason: &str) {
        if let Some(manager) = self.manager.upgrade()
            && manager.is_current(self.generation)
        {
            manager.output(Stream::Stderr, format!("[NetherForge editor] {reason}"));
        }
    }

    fn message(&self, frame: String) {
        if let Some(manager) = self.manager.upgrade()
            && manager.is_current(self.generation)
        {
            manager.sink.emit(&BridgeMessage { message: frame });
        }
    }
}

fn shutdown(bridge: &Option<Bridge>) {
    if let Some(bridge) = bridge {
        bridge.shutdown();
    }
}

fn check_plugin(tools: &Tools, minecraft: &str) -> Result<()> {
    if plugins::find(&tools.plugin_dirs, minecraft).is_none() {
        bail!(
            Unavailable,
            "This NetherForge build has no plugin for Minecraft {minecraft}. In a dev checkout, build it with node tools/gradle.mjs :plugin:paper-{minecraft}:build"
        );
    }
    Ok(())
}

/// Fetches the git packages [root]'s `netherforge.lock` pins into the
/// package [cache] (a checkout already there needs no network), answering
/// what couldn't be. The lock is read leniently: what's wrong with it is the
/// plugin's to report.
pub fn fetch_locked_git(cache: &Path, root: &Path) -> Vec<String> {
    let Some(lock) = std::fs::read(root.join("netherforge.lock"))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
    else {
        return Vec::new();
    };
    let Some(packages) = lock.get("packages").and_then(|p| p.as_object()) else {
        return Vec::new();
    };
    let mut failures = Vec::new();
    for (name, package) in packages {
        let Some(source) = package.get("source") else {
            continue;
        };
        if source.get("type").and_then(|t| t.as_str()) != Some("git") {
            continue;
        }
        let text = |key: &str| source.get(key).and_then(|v| v.as_str());
        let (Some(url), Some(commit)) = (text("url"), text("commit")) else {
            continue;
        };
        if let Err(error) = crate::fs::git::fetch(cache, url, text("rev"), Some(commit), Ok(())) {
            failures.push(format!("Couldn't fetch package {name}: {error}"));
        }
    }
    failures
}

async fn write_line(stdin: &tokio::sync::Mutex<ChildStdin>, line: &str) -> Result<()> {
    let mut stdin = stdin.lock().await;
    stdin
        .write_all(format!("{line}\n").as_bytes())
        .await
        .map_err(|e| Error::msg(format!("Couldn't send to the server console: {e}")))?;
    stdin.flush().await.map_err(Error::from)
}

async fn wait_exited(mut exited: watch::Receiver<bool>, timeout: Duration) -> bool {
    tokio::time::timeout(timeout, exited.wait_for(|done| *done))
        .await
        .is_ok_and(|r| r.is_ok())
}

/// No console window flashes up for child processes on Windows.
pub fn hide_console(command: &mut Command) {
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = command;
}

/// Paper's ready line: `[12:00:00 INFO]: Done (3.123s)! For help, type "help"`.
pub fn is_done_line(line: &str) -> bool {
    line.match_indices("Done (")
        .any(|(i, m)| line[i + m.len()..].starts_with(|c: char| c.is_ascii_digit()))
}

fn check_port_free(port: u16) -> Result<()> {
    match std::net::TcpListener::bind(("0.0.0.0", port)) {
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => bail!(
            Busy,
            "Port {port} is already in use (another Minecraft server?). Stop it or pick another port in Settings."
        ),
        Err(_) => Ok(()),
    }
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("the OS random number generator failed");
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::events::RecordingSink;
    use crate::testing;

    fn manager() -> (tempfile::TempDir, Arc<RecordingSink>, Arc<ServerManager>) {
        let tmp = tempfile::tempdir().unwrap();
        let sink = Arc::new(RecordingSink::default());
        let manager = ServerManager::new(AppDirs::in_one(tmp.path()), sink.clone());
        (tmp, sink, manager)
    }

    fn config(tmp: &Path, eula: bool) -> StartConfig {
        config_with(tmp, eula, vec![])
    }

    fn config_with(tmp: &Path, eula: bool, plugin_dirs: Vec<PathBuf>) -> StartConfig {
        StartConfig {
            project_root: tmp.join("project"),
            project_name: "Test".into(),
            minecraft: Some("26.3".into()),
            settings: Settings::default(),
            eula_accepted: eula,
            tools: Arc::new(Tools {
                plugin_dirs,
                test_runner_dirs: vec![],
                luals: vec![],
                java: java::JavaEnv::current(&AppDirs::in_one(tmp)),
                paper: PaperSource::Fill,
                installs: None,
            }),
            role: Role::Main(None),
        }
    }

    /// Two threads changing the state at once (Paper's "Done" line and the plugin's hello, on a
    /// real server about the same moment): the last state the UI hears is the state.
    #[test]
    fn states_are_heard_in_the_order_they_happened() {
        /// Tauri's emit serializes and crosses IPC: a sink that takes its time.
        struct Slow(RecordingSink);
        impl EventSink for Slow {
            fn emit_value(&self, event: &str, payload: serde_json::Value) {
                std::thread::sleep(Duration::from_micros(20));
                self.0.emit_value(event, payload);
            }
        }
        for _ in 0..20 {
            let tmp = tempfile::tempdir().unwrap();
            let sink = Arc::new(Slow(RecordingSink::default()));
            let manager = ServerManager::new(AppDirs::in_one(tmp.path()), sink.clone());
            let threads: Vec<_> = (0..2)
                .map(|thread| {
                    let manager = manager.clone();
                    std::thread::spawn(move || {
                        for i in 0..200u16 {
                            manager.update(None, |s| {
                                if thread == 0 {
                                    s.bridge_connected = i % 2 == 0;
                                } else {
                                    s.port = Some(i);
                                }
                            });
                        }
                    })
                })
                .collect();
            for thread in threads {
                thread.join().unwrap();
            }
            let heard = sink.0.named::<ServerState>();
            // Only the second thread moves the port, and only up: heard in order, it never goes back.
            let ports: Vec<u64> = heard.iter().filter_map(|s| s["port"].as_u64()).collect();
            assert!(
                ports.is_sorted(),
                "a stale state came after a newer one: {ports:?}"
            );
            assert_eq!(
                heard.last().unwrap(),
                &serde_json::to_value(manager.state()).unwrap()
            );
        }
    }

    #[tokio::test]
    async fn refuses_to_start_without_the_eula() {
        let (tmp, sink, manager) = manager();
        let error = manager.start(config(tmp.path(), false)).await.unwrap_err();
        assert!(error.message().contains("EULA"), "{error}");
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(sink.named::<ServerState>().is_empty());
        assert!(!tmp.path().join("servers").exists());
    }

    #[tokio::test]
    async fn refuses_without_a_version_or_a_plugin() {
        let (tmp, _sink, manager) = manager();
        let mut no_version = config(tmp.path(), true);
        no_version.minecraft = None;
        assert!(
            manager
                .start(no_version)
                .await
                .unwrap_err()
                .message()
                .contains("netherforge.json")
        );
        let error = manager.start(config(tmp.path(), true)).await.unwrap_err();
        assert!(
            error.message().contains("no plugin for Minecraft 26.3"),
            "{error}"
        );
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
    }

    #[test]
    fn detects_the_ready_line() {
        assert!(is_done_line(
            "[12:00:00 INFO]: Done (3.123s)! For help, type \"help\""
        ));
        assert!(!is_done_line("[12:00:00 INFO]: Done (whatever)"));
        assert!(!is_done_line("[12:00:00 INFO]: Preparing spawn area: 50%"));
    }

    #[test]
    fn strip_ansi_escapes_removes_paper_colours_and_titles() {
        assert_eq!(
            strip_ansi_escapes::strip_str("\u{1b}[0;32m[INFO]\u{1b}[m Done\u{1b}]0;title\u{7}!"),
            "[INFO] Done!"
        );
        assert_eq!(strip_ansi_escapes::strip_str("plain"), "plain");
    }

    /// Launches the fake server (`examples/fake_server.rs`) with [args] as a
    /// start would launch Java, past the preparation.
    async fn launch_fake(
        manager: &Arc<ServerManager>,
        tmp: &Path,
        args: &[String],
        envs: &[(String, String)],
        token: &str,
    ) -> (u64, u16) {
        let (generation, _preparing) = manager.begin(Some("26.3")).await.unwrap();
        let bridge = Bridge::listen(
            token.into(),
            Arc::new(Handler {
                manager: Arc::downgrade(manager),
                generation,
            }),
        )
        .await
        .unwrap();
        let port = bridge.port();
        let args: Vec<String> = args
            .iter()
            .map(|arg| arg.replace("$PORT", &port.to_string()))
            .collect();
        manager
            .launch(
                generation,
                &testing::fake_server(),
                &args,
                tmp,
                envs,
                Some(bridge),
                25565,
            )
            .await
            .unwrap();
        (generation, port)
    }

    async fn launch(manager: &Arc<ServerManager>, tmp: &Path, args: &[&str]) {
        let args: Vec<String> = args.iter().map(|it| it.to_string()).collect();
        launch_fake(manager, tmp, &args, &[], "t").await;
    }

    async fn until_phase(manager: &ServerManager, phase: ServerPhase) {
        testing::until(&format!("{phase:?}"), || manager.state().phase == phase).await;
    }

    #[tokio::test]
    async fn a_new_start_waits_for_a_cancelled_preparation() {
        let (_tmp, _sink, manager) = manager();
        let (first, preparing) = manager.begin(Some("26.3")).await.unwrap();
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(!manager.is_current(first));

        // The cancelled preparation hasn't noticed yet: the next start waits
        // for it, on the lock it still holds (polled once: nothing else can
        // make it ready while that guard lives).
        let next = manager.begin(Some("26.3"));
        tokio::pin!(next);
        assert!(futures_util::poll!(&mut next).is_pending());
        assert_eq!(manager.state().phase, ServerPhase::Stopped);

        drop(preparing);
        let (second, _preparing) = next.await.unwrap();
        assert!(manager.is_current(second));
        assert_eq!(manager.state().phase, ServerPhase::Preparing);
    }

    #[tokio::test]
    async fn runs_a_server_through_its_lifecycle_and_hears_its_plugin() {
        let (tmp, sink, manager) = manager();
        let token = "the-token";
        launch_fake(
            &manager,
            tmp.path(),
            &["serve".into(), "-Dnetherforge.bridge.port=$PORT".into()],
            &[("NETHERFORGE_BRIDGE_TOKEN".into(), token.into())],
            token,
        )
        .await;
        until_phase(&manager, ServerPhase::Running).await;
        assert_eq!(manager.state().port, Some(25565));
        testing::until("the plugin's hello", || manager.state().bridge_connected).await;
        // What the plugin sends once connected is relayed as it came.
        testing::until("the plugin's console notification", || {
            sink.named::<BridgeMessage>()
                .iter()
                .any(|m| m["message"].as_str().unwrap().contains("\"console\""))
        })
        .await;
        // There's no game data cached: the backend asked for the export, which
        // this plugin can't do, and says so in the console.
        testing::until("the failed export", || {
            sink.named::<ServerOutputEvent>().iter().any(|o| {
                o["stream"] == "stderr" && o["line"].as_str().unwrap().contains("export failed")
            })
        })
        .await;

        manager.command("say hi").await.unwrap();
        let echoed = serde_json::json!({"stream": "stdout", "line": "> say hi"});
        testing::until("the echoed command", || {
            sink.named::<ServerOutputEvent>().contains(&echoed)
        })
        .await;
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert_eq!(manager.state().message, None);
        assert!(!manager.state().bridge_connected);

        let mut phases: Vec<String> = sink
            .named::<ServerState>()
            .iter()
            .map(|s| s["phase"].as_str().unwrap().to_string())
            .collect();
        phases.dedup();
        assert_eq!(
            phases,
            ["preparing", "starting", "running", "stopping", "stopped"]
        );
        let output = sink.named::<ServerOutputEvent>();
        assert!(output.contains(&serde_json::json!({
            "stream": "stdout",
            "line": "[00:00:00 INFO]: Starting minecraft server",
        })));
        assert!(output.contains(&serde_json::json!({
            "stream": "stderr",
            "line": "[00:00:00 WARN]: a warning on stderr",
        })));
    }

    #[tokio::test]
    async fn a_plugin_with_the_wrong_token_is_never_connected() {
        let (tmp, sink, manager) = manager();
        launch_fake(
            &manager,
            tmp.path(),
            &["serve".into(), "-Dnetherforge.bridge.port=$PORT".into()],
            &[("NETHERFORGE_BRIDGE_TOKEN".into(), "a guess".into())],
            "the-token",
        )
        .await;
        // The bridge closes on a stranger without a word, which the fake plugin reports.
        testing::until("the refused hello", || {
            sink.named::<ServerOutputEvent>().iter().any(|o| {
                o["line"]
                    .as_str()
                    .unwrap()
                    .contains("closed the dev bridge without answering")
            })
        })
        .await;
        assert_eq!(manager.state().phase, ServerPhase::Running);
        assert!(!manager.state().bridge_connected);
        assert!(sink.named::<BridgeMessage>().is_empty());
        manager.stop().await.unwrap();
    }

    #[tokio::test]
    async fn an_unexpected_exit_is_a_crash() {
        let (tmp, _sink, manager) = manager();
        launch(&manager, tmp.path(), &["exit", "3"]).await;
        until_phase(&manager, ServerPhase::Crashed).await;
        let state = manager.state();
        assert!(state.message.unwrap().contains("exit code 3"));
        assert_eq!(state.port, None);
        assert!(manager.command("list").await.is_err());
        // A crashed server can start again.
        launch(&manager, tmp.path(), &["sleep", "30"]).await;
        assert_eq!(manager.state().phase, ServerPhase::Starting);
    }

    #[tokio::test]
    async fn kills_a_server_that_ignores_stop() {
        let (tmp, _sink, manager) = manager();
        launch(&manager, tmp.path(), &["ignore-stop"]).await;
        until_phase(&manager, ServerPhase::Running).await;
        manager.stop_with(Duration::from_millis(200)).await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
    }

    /// Boots real Paper (downloads it, finds Java) with a placeholder plugin
    /// jar, waits for "Done (", then stops it: `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "network, Java 25 and ~1 minute"]
    async fn boots_real_paper() {
        let (tmp, sink, manager) = manager();
        let plugins = tmp.path().join("plugins");
        std::fs::create_dir_all(&plugins).unwrap();
        let jar = plugins.join("NetherForge-0.0.0-paper-26.3.jar");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
        zip.start_file(
            plugins::PAPER_PROPERTIES,
            zip::write::SimpleFileOptions::default(),
        )
        .unwrap();
        std::io::Write::write_all(&mut zip, b"minecraft=26.3\nbuild=152\n").unwrap();
        zip.finish().unwrap();
        std::fs::create_dir_all(tmp.path().join("project")).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let mut config = config_with(tmp.path(), true, vec![plugins]);
        config.settings.server_port = port;
        manager.start(config).await.unwrap();
        for _ in 0..1800 {
            if matches!(
                manager.state().phase,
                ServerPhase::Running | ServerPhase::Crashed
            ) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        let state = manager.state();
        if state.phase != ServerPhase::Running {
            for line in sink.named::<ServerOutputEvent>() {
                println!("{}", line["line"]);
            }
        }
        assert_eq!(state.phase, ServerPhase::Running, "{state:?}");
        let dir = AppDirs::in_one(tmp.path()).server_dir(&tmp.path().join("project"));
        assert!(
            dir.join("plugins/NetherForge-0.0.0-paper-26.3.jar")
                .is_file()
        );
        assert!(
            std::fs::read_to_string(dir.join("eula.txt"))
                .unwrap()
                .contains("eula=true")
        );
        assert!(
            std::fs::read_to_string(dir.join("server.properties"))
                .unwrap()
                .contains(&format!("server-port={port}"))
        );
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(!sink.named::<PrepareProgress>().is_empty());
    }
}
