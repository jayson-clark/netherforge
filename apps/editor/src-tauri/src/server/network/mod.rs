//! The dev network: the open project's server behind a Velocity proxy that
//! runs NetherForge's proxy plugin (the network's hub), beside other servers
//! each running a project folder of its own (see [layout]).
//!
//! The project's own server is still the [ServerManager] in `AppState`, with
//! a [Role::Main] membership; [NetworkManager] runs the rest: the proxy and
//! each other server, each a [ServerManager] of its own (the same lifecycle,
//! generations, stop and leftovers), whose events a [MemberSink] turns into
//! `network://member` and `network://output`, tagged with the server's name.
//! The UI's bridge stays the project's server's; another server's plugin
//! connects to a bridge of its own here, which keeps it alive (a plugin stops
//! its server once its editor is gone) and takes its reloads: a watcher on its
//! project sends `reload` with what changed, as the UI does for the project's.

pub mod layout;
pub mod setup;

use std::path::PathBuf;
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tauri_specta::Event;

use crate::app::dirs::AppDirs;
use crate::app::events::{BridgeMessage, EventSink};
use crate::app::plugins;
use crate::app::tools::Tools;
use crate::error::{Error, ErrorCode, Result, bail};
use crate::settings::Settings;
use crate::watcher::{self, WatchHandle};
use crate::{project, trust};

use super::process::{Membership, ProxyRole, Role, ServerManager, StartConfig};
use super::{PrepareProgress, ServerOutputEvent, ServerState, Stream};
use layout::NetworkLayout;

/// How long a peer's reload may take before the editor stops waiting.
const RELOAD_TIMEOUT: Duration = Duration::from_secs(60);

/// What a network member is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum MemberKind {
    Proxy,
    Server,
}

/// One of the dev network's processes besides the project's own server.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "network://member")]
pub struct NetworkMember {
    /// The server's name in the network; `proxy` for the proxy.
    pub name: String,
    pub kind: MemberKind,
    /// The project folder a server runs; None for the proxy.
    pub project: Option<String>,
    pub state: ServerState,
    /// It's the server the editor is attached to (a workspace's network: the one running the open
    /// project), whose state, console and bridge are the editor's own `server://` and `bridge://`.
    pub attached: bool,
}

/// A console line of a network member.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "network://output")]
pub struct NetworkOutputEvent {
    pub name: String,
    pub stream: Stream,
    pub line: String,
}

/// Every member of the network, all at once: what `network://members` says
/// when a network starts or goes away.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "network://members")]
pub struct NetworkMembers {
    pub members: Vec<NetworkMember>,
}

/// One of the layout's other servers as Start would find it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct NetworkServerCheck {
    pub name: String,
    /// Its project folder, resolved; None when there's none there.
    pub root: Option<String>,
    /// Its project's name.
    pub project_name: Option<String>,
    /// The Minecraft version it targets.
    pub minecraft: Option<String>,
    /// Whether the user trusts its folder (Start refuses one they don't).
    pub trusted: bool,
    /// What keeps it from starting, as a sentence (untrusted aside).
    pub problem: Option<String>,
}

/// Each of [layout]'s other servers, from the open project's [root].
pub fn check(
    dirs: &AppDirs,
    tools: &Tools,
    root: &std::path::Path,
    layout: &NetworkLayout,
) -> Vec<NetworkServerCheck> {
    layout
        .servers
        .iter()
        .map(|server| {
            let mut check = NetworkServerCheck {
                name: server.name.clone(),
                root: None,
                project_name: None,
                minecraft: None,
                trusted: false,
                problem: None,
            };
            let resolved = match layout::resolve_project(root, &server.project) {
                Ok(resolved) => resolved,
                Err(error) => {
                    check.problem = Some(error.message().to_string());
                    return check;
                }
            };
            check.root = Some(resolved.display().to_string());
            check.trusted = trust::is_trusted(&dirs.trust_file(), &resolved);
            match project::inspect(&resolved) {
                Ok(opened) => {
                    check.project_name = Some(opened.info.name);
                    match opened.minecraft {
                        Some(minecraft) => {
                            if plugins::find(&tools.plugin_dirs, &minecraft).is_none() {
                                check.problem = Some(format!(
                                    "This NetherForge build has no plugin for Minecraft {minecraft}"
                                ));
                            }
                            check.minecraft = Some(minecraft);
                        }
                        None => {
                            check.problem =
                                Some("Its netherforge.json names no Minecraft version".into())
                        }
                    }
                }
                Err(error) => check.problem = Some(error.message().to_string()),
            }
            check
        })
        .collect()
}

/// What Start runs for a project whose layout puts it in a network.
pub struct NetworkPlan {
    /// The project's own server's place in it.
    pub main: Membership,
    /// The proxy, then each other server.
    pub members: Vec<MemberPlan>,
}

/// The network [layout] makes of the open project at [root] (canonical): its
/// secrets (kept in the proxy's folder), a free port for the hub and each
/// server, and how to start each member. Refuses before anything starts when
/// a server's folder isn't a project or isn't trusted, or there's no proxy
/// plugin.
pub fn plan(
    dirs: &AppDirs,
    tools: &Arc<Tools>,
    root: &std::path::Path,
    project_name: &str,
    layout: &NetworkLayout,
    settings: &Settings,
    eula_accepted: bool,
) -> Result<NetworkPlan> {
    layout.validate()?;
    if plugins::find_proxy(&tools.plugin_dirs).is_none() {
        bail!(
            Unavailable,
            "This NetherForge build has no proxy plugin. In a dev checkout, build it with node tools/gradle.mjs :proxy:velocity:assemble"
        );
    }
    let network = dirs.network_dir(root);
    let proxy_dir = network.join("proxy");
    let secrets = setup::secrets(&proxy_dir)?;
    let hub_port = setup::free_port()?;
    let main = Membership {
        name: layout.name.clone(),
        port: setup::free_port()?,
        hub_port,
        secrets: secrets.clone(),
    };
    let mut servers = vec![(main.name.clone(), main.port)];
    let mut members = Vec::new();
    // Every folder first, then trust: a server that isn't there is the first thing to fix.
    let projects = layout
        .servers
        .iter()
        .map(|server| {
            layout::resolve_project(root, &server.project).map_err(|error| {
                Error::new(
                    error.code(),
                    format!("Server \"{}\": {}", server.name, error.message()),
                )
            })
        })
        .collect::<Result<Vec<_>>>()?;
    for (server, project) in layout.servers.iter().zip(projects) {
        if !trust::is_trusted(&dirs.trust_file(), &project) {
            bail!(
                Untrusted,
                "Server \"{}\" runs {}, which isn't trusted yet: trust it in the Network panel first",
                server.name,
                project.display()
            );
        }
        let opened = project::inspect(&project)?;
        let membership = Membership {
            name: server.name.clone(),
            port: setup::free_port()?,
            hub_port,
            secrets: secrets.clone(),
        };
        servers.push((membership.name.clone(), membership.port));
        members.push(MemberPlan {
            name: server.name.clone(),
            kind: MemberKind::Server,
            project: Some(project.clone()),
            config: StartConfig {
                project_root: project,
                project_name: opened.info.name,
                minecraft: opened.minecraft,
                settings: settings.clone(),
                eula_accepted,
                tools: tools.clone(),
                role: Role::Peer {
                    membership,
                    dir: network.join("servers").join(&server.name),
                },
            },
        });
    }
    members.insert(
        0,
        MemberPlan {
            name: layout::PROXY.into(),
            kind: MemberKind::Proxy,
            project: None,
            config: StartConfig {
                project_root: root.to_path_buf(),
                project_name: project_name.into(),
                minecraft: None,
                settings: settings.clone(),
                eula_accepted,
                tools: tools.clone(),
                role: Role::Proxy(ProxyRole {
                    dir: proxy_dir,
                    hub_port,
                    servers,
                    secrets,
                }),
            },
        },
    );
    Ok(NetworkPlan { main, members })
}

/// What Start runs for a workspace's dev network: every server it names, and which one the editor
/// attaches to.
pub struct WorkspacePlan {
    /// The proxy, then each server.
    pub members: Vec<MemberPlan>,
    /// The server running [focused]: the arrival server if it does, else the first by name.
    pub attach: Option<String>,
}

/// The dev network the workspace's file names: a proxy and every server, each in the workspace's
/// own folders (`<data>/networks/<workspace>/servers/<name>/`, so moving a project keeps its
/// world), players arriving on its `arrival`. The editor attaches to the server that runs
/// [focused] (the open project). Refuses before anything starts when the file names no network, a
/// server's project isn't one of the workspace's, or there's no proxy plugin. The workspace is
/// trusted (the caller checked), so every project in it is.
pub fn plan_workspace(
    dirs: &AppDirs,
    tools: &Arc<Tools>,
    workspace: &crate::workspace::WorkspaceInfo,
    focused: &std::path::Path,
    settings: &Settings,
    eula_accepted: bool,
) -> Result<WorkspacePlan> {
    let Some(network) = &workspace.network else {
        bail!(
            Invalid,
            "{} names no network: add its servers in the Network panel",
            crate::workspace::FILE
        );
    };
    if network.servers.is_empty() {
        bail!(Invalid, "The workspace's network has no servers yet");
    }
    if plugins::find_proxy(&tools.plugin_dirs).is_none() {
        bail!(
            Unavailable,
            "This NetherForge build has no proxy plugin. In a dev checkout, build it with node tools/gradle.mjs :proxy:velocity:assemble"
        );
    }
    let root = std::path::PathBuf::from(&workspace.root);
    let base = dirs.network_dir(&root);
    let proxy_dir = base.join("proxy");
    let secrets = setup::secrets(&proxy_dir)?;
    let hub_port = setup::free_port()?;
    // Players join the arrival server first: it leads Velocity's list.
    let mut ordered = network.servers.clone();
    ordered.sort_by_key(|s| s.name != network.arrival);
    let mut servers = Vec::new();
    let mut members = Vec::new();
    for server in &ordered {
        if !layout::is_server_name(&server.name) || server.name == layout::PROXY {
            bail!(
                Invalid,
                "\"{}\" can't name a server of the network",
                server.name
            );
        }
        let project = workspace.server_root(server).ok_or_else(|| {
            Error::new(
                ErrorCode::NotFound,
                format!(
                    "Server \"{}\" runs {}, which isn't one of the workspace's projects",
                    server.name, server.project
                ),
            )
        })?;
        let opened = project::inspect(&project)?;
        let membership = Membership {
            name: server.name.clone(),
            port: setup::free_port()?,
            hub_port,
            secrets: secrets.clone(),
        };
        servers.push((membership.name.clone(), membership.port));
        members.push(MemberPlan {
            name: server.name.clone(),
            kind: MemberKind::Server,
            project: Some(project.clone()),
            config: StartConfig {
                project_root: project,
                project_name: opened.info.name,
                minecraft: opened.minecraft,
                settings: settings.clone(),
                eula_accepted,
                tools: tools.clone(),
                role: Role::Peer {
                    membership,
                    dir: base.join("servers").join(&server.name),
                },
            },
        });
    }
    let attach = members
        .iter()
        .find(|m| m.project.as_deref() == Some(focused))
        .map(|m| m.name.clone());
    members.insert(
        0,
        MemberPlan {
            name: layout::PROXY.into(),
            kind: MemberKind::Proxy,
            project: None,
            config: StartConfig {
                project_root: root,
                project_name: workspace.name.clone(),
                minecraft: None,
                settings: settings.clone(),
                eula_accepted,
                tools: tools.clone(),
                role: Role::Proxy(ProxyRole {
                    dir: proxy_dir,
                    hub_port,
                    servers,
                    secrets,
                }),
            },
        },
    );
    Ok(WorkspacePlan { members, attach })
}

/// The server of a running network that runs [project]: what focusing it attaches to.
pub fn server_running(members: &[NetworkMember], project: &std::path::Path) -> Option<String> {
    let project = project.display().to_string();
    members
        .iter()
        .find(|m| m.kind == MemberKind::Server && m.project.as_deref() == Some(project.as_str()))
        .map(|m| m.name.clone())
}

/// What to start: each member's name, kind, project and how to start it.
pub struct MemberPlan {
    pub name: String,
    pub kind: MemberKind,
    pub project: Option<PathBuf>,
    pub config: StartConfig,
}

struct Member {
    name: String,
    kind: MemberKind,
    project: Option<PathBuf>,
    manager: Arc<ServerManager>,
    /// A server's project watcher, which reloads it; dropping it stops it.
    _watch: Option<WatchHandle>,
}

impl Member {
    fn snapshot(&self, attached: Option<&str>) -> NetworkMember {
        NetworkMember {
            name: self.name.clone(),
            kind: self.kind,
            project: self.project.as_ref().map(|p| p.display().to_string()),
            state: self.manager.state(),
            attached: attached == Some(self.name.as_str()),
        }
    }
}

pub struct NetworkManager {
    dirs: AppDirs,
    sink: Arc<dyn EventSink>,
    members: Mutex<Vec<Member>>,
    /// The member the editor is attached to (see [NetworkMember::attached]).
    attached: Mutex<Option<String>>,
}

impl NetworkManager {
    pub fn new(dirs: AppDirs, sink: Arc<dyn EventSink>) -> Arc<Self> {
        Arc::new(Self {
            dirs,
            sink,
            members: Mutex::new(Vec::new()),
            attached: Mutex::new(None),
        })
    }

    /// Every member of the network last started, as it is now.
    pub fn members(&self) -> Vec<NetworkMember> {
        let attached = self.attached.lock().unwrap().clone();
        self.members
            .lock()
            .unwrap()
            .iter()
            .map(|m| m.snapshot(attached.as_deref()))
            .collect()
    }

    /// Whether [name] is the member the editor is attached to.
    fn is_attached(&self, name: &str) -> bool {
        self.attached.lock().unwrap().as_deref() == Some(name)
    }

    /// The member the editor is attached to, while the network has one.
    pub fn attached(&self) -> Option<Arc<ServerManager>> {
        let name = self.attached.lock().unwrap().clone()?;
        self.manager(&name)
    }

    /// Whether any member is up (or on its way up or down).
    pub fn is_running_any(&self) -> bool {
        self.members
            .lock()
            .unwrap()
            .iter()
            .any(|m| m.manager.state().phase.is_active())
    }

    /// Attaches the editor to the member [name] (or to none): its state, console and bridge
    /// become the editor's own. Says so at once: `server://state` with its state (stopped, saying
    /// why, for none), and `network://members` with the flag moved.
    pub fn attach(&self, name: Option<String>) {
        let manager = name.as_deref().and_then(|n| self.manager(n));
        *self.attached.lock().unwrap() = manager.as_ref().and(name);
        let state = match &manager {
            Some(manager) => manager.state(),
            None => ServerState {
                message: Some("No server of the dev network runs this project".into()),
                ..ServerState::default()
            },
        };
        self.sink.emit(&state);
        self.sink.emit(&NetworkMembers {
            members: self.members(),
        });
    }

    fn manager(&self, name: &str) -> Option<Arc<ServerManager>> {
        self.members
            .lock()
            .unwrap()
            .iter()
            .find(|m| m.name == name)
            .map(|m| m.manager.clone())
    }

    fn changed(&self, name: &str) {
        let attached = self.attached.lock().unwrap().clone();
        let snapshot = self
            .members
            .lock()
            .unwrap()
            .iter()
            .find(|m| m.name == name)
            .map(|m| m.snapshot(attached.as_deref()));
        if let Some(snapshot) = snapshot {
            self.sink.emit(&snapshot);
        }
    }

    /// Starts every member of [plans] (stopping what ran before), attached to [attach], and
    /// returns without waiting for them: each one's preparation, and failure, is its own
    /// `network://member` (and the editor's own events, for the attached one).
    pub async fn start(self: &Arc<Self>, plans: Vec<MemberPlan>, attach: Option<String>) {
        self.stop().await;
        *self.attached.lock().unwrap() = attach;
        let mut starts = Vec::new();
        {
            let mut members = self.members.lock().unwrap();
            members.clear();
            for plan in plans {
                let sink: Arc<dyn EventSink> = Arc::new(MemberSink {
                    name: plan.name.clone(),
                    network: Arc::downgrade(self),
                    inner: self.sink.clone(),
                });
                let manager = ServerManager::new(self.dirs.clone(), sink);
                let watch = match (&plan.kind, &plan.project) {
                    (MemberKind::Server, Some(project)) => {
                        reloads(project, &manager, self, &plan.name)
                            .map_err(|error| {
                                manager.note(
                                Stream::Stderr,
                                format!(
                                    "[NetherForge editor] Saves won't reload this server: {error}"
                                ),
                            )
                            })
                            .ok()
                    }
                    _ => None,
                };
                members.push(Member {
                    name: plan.name.clone(),
                    kind: plan.kind,
                    project: plan.project.clone(),
                    manager: manager.clone(),
                    _watch: watch,
                });
                starts.push((plan.name, manager, plan.config));
            }
        }
        self.sink.emit(&NetworkMembers {
            members: self.members(),
        });
        for (name, manager, config) in starts {
            let network = Arc::downgrade(self);
            tokio::spawn(async move {
                if let Err(error) = manager.start(config).await {
                    manager.note(
                        Stream::Stderr,
                        format!("[NetherForge editor] {name} didn't start: {error}"),
                    );
                    if let Some(network) = network.upgrade() {
                        network.changed(&name);
                    }
                }
            });
        }
    }

    /// Stops every member at once, waiting for each.
    pub async fn stop(&self) {
        let managers: Vec<Arc<ServerManager>> = self
            .members
            .lock()
            .unwrap()
            .iter()
            .map(|m| m.manager.clone())
            .collect();
        futures_util::future::join_all(managers.iter().map(|m| m.stop())).await;
    }

    /// Stops every member and forgets them: the project's server runs alone
    /// now, or the project closed.
    pub async fn clear(&self) {
        self.stop().await;
        *self.attached.lock().unwrap() = None;
        let had = !std::mem::take(&mut *self.members.lock().unwrap()).is_empty();
        if had {
            self.sink.emit(&NetworkMembers {
                members: Vec::new(),
            });
        }
    }

    /// Runs a console command on the member [name].
    pub async fn command(&self, name: &str, line: &str) -> Result<()> {
        let manager = self.manager(name).ok_or_else(|| {
            Error::new(
                ErrorCode::NotFound,
                format!("The dev network has no server \"{name}\""),
            )
        })?;
        manager.command(line).await
    }
}

/// Reloads [manager]'s server with what changes in [project], as the UI does for the project's
/// own: except while it's the member the editor is attached to, whose saves the UI reloads itself.
fn reloads(
    project: &std::path::Path,
    manager: &Arc<ServerManager>,
    network: &Arc<NetworkManager>,
    name: &str,
) -> Result<WatchHandle> {
    let manager = Arc::downgrade(manager);
    let network = Arc::downgrade(network);
    let name = name.to_string();
    let runtime = tokio::runtime::Handle::current();
    watcher::watch(project, move |batch| {
        let Some(manager) = manager.upgrade() else {
            return;
        };
        if network.upgrade().is_some_and(|n| n.is_attached(&name)) {
            return;
        }
        let paths = if batch.rescan {
            vec![crate::project::MANIFEST.to_string()]
        } else {
            batch.paths
        };
        if paths.is_empty() {
            return;
        }
        runtime.spawn(async move {
            // Not running, or its plugin not connected yet: it loads everything when it is.
            let result = manager
                .bridge_request("reload", json!({ "paths": paths }), RELOAD_TIMEOUT)
                .await;
            match result {
                Ok(result) => manager.note(Stream::Stdout, reload_summary(&result)),
                Err(error) if error.code() == ErrorCode::NotConnected => {}
                Err(error) => manager.note(
                    Stream::Stderr,
                    format!("[NetherForge editor] Reload failed: {error}"),
                ),
            }
        });
    })
}

/// A peer's `ReloadResult` as a console line.
fn reload_summary(result: &Value) -> String {
    let names: Vec<String> = result
        .get("resources")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .map(|resource| {
            let text = |key: &str| resource.get(key).and_then(Value::as_str);
            match (text("kind"), text("id")) {
                (Some(kind), Some(id)) => format!("{kind} {id}"),
                _ => text("package").unwrap_or("the project").to_string(),
            }
        })
        .collect();
    if names.is_empty() {
        "[NetherForge editor] Saved; nothing to reload".into()
    } else {
        format!("[NetherForge editor] Reloaded {}", names.join(", "))
    }
}

/// A member's [ServerManager]'s events, as the network's.
struct MemberSink {
    name: String,
    network: Weak<NetworkManager>,
    inner: Arc<dyn EventSink>,
}

impl EventSink for MemberSink {
    fn emit_value(&self, event: &str, payload: Value) {
        // The attached member's events are the editor's own, as well as the network's.
        let attached = self
            .network
            .upgrade()
            .is_some_and(|n| n.is_attached(&self.name));
        if attached
            && (event == ServerState::NAME
                || event == ServerOutputEvent::NAME
                || event == BridgeMessage::NAME
                || event == PrepareProgress::NAME)
        {
            self.inner.emit_value(event, payload.clone());
        }
        if event == ServerState::NAME {
            if let Some(network) = self.network.upgrade() {
                network.changed(&self.name);
            }
        } else if event == ServerOutputEvent::NAME {
            if let Ok(output) = serde_json::from_value::<ServerOutputEvent>(payload) {
                self.inner.emit(&NetworkOutputEvent {
                    name: self.name.clone(),
                    stream: output.stream,
                    line: output.line,
                });
            }
        } else if event == BridgeMessage::NAME || event == PrepareProgress::NAME {
            // The UI's bridge is the project's server's; a download's progress shows in its state's message.
        } else {
            // The game-data cache filling, say: everyone's.
            self.inner.emit_value(event, payload);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::events::RecordingSink;

    #[tokio::test]
    async fn runs_a_workspaces_network_attached_to_the_open_project() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let tools = Arc::new(fake_tools(tmp.path(), &dirs));
        let root = dunce::canonicalize(tmp.path()).unwrap().join("net");
        let hub = project(&root.join("servers/hub"), "Hub");
        let game = project(&root.join("servers/game"), "Game");
        std::fs::write(
            root.join(crate::workspace::FILE),
            r#"{"name":"Net","projects":["servers/*"],"network":{"arrival":"hub","servers":{"game":"servers/game","hub":"servers/hub"}}}"#,
        )
        .unwrap();
        let workspace = crate::workspace::read(&root, true).unwrap();
        let settings = Settings {
            server_port: setup::free_port().unwrap(),
            ..Settings::default()
        };

        let plan = plan_workspace(&dirs, &tools, &workspace, &game, &settings, true).unwrap();
        let names: Vec<&str> = plan.members.iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["proxy", "hub", "game"], "the arrival server leads");
        assert_eq!(plan.attach.as_deref(), Some("game"));

        let sink = Arc::new(RecordingSink::default());
        let network = NetworkManager::new(dirs.clone(), sink.clone());
        network.start(plan.members, plan.attach).await;
        crate::testing::until("every member running", || {
            let members = network.members();
            members.len() == 3
                && members
                    .iter()
                    .all(|m| m.state.phase == crate::server::ServerPhase::Running)
        })
        .await;
        // The attached member's console and state are the editor's own.
        crate::testing::until("game's output as the editor's", || {
            sink.named::<ServerOutputEvent>()
                .iter()
                .any(|e| e["line"].as_str().unwrap().contains("In network as game"))
        })
        .await;
        assert!(
            !sink
                .named::<ServerOutputEvent>()
                .iter()
                .any(|e| e["line"].as_str().unwrap().contains("In network as hub")),
            "hub's console stays the network's"
        );
        let attached = network.members().into_iter().find(|m| m.attached).unwrap();
        assert_eq!(attached.name, "game");
        // Worlds live under the server's name, in the workspace's folder.
        let base = dirs.network_dir(&root);
        assert!(base.join("servers/game/eula.txt").is_file());
        assert_eq!(
            network.attached().unwrap().folder(),
            Some(base.join("servers/game"))
        );
        let toml = std::fs::read_to_string(base.join("proxy/velocity.toml")).unwrap();
        assert!(toml.contains("try = [\"hub\"]"), "{toml}");

        // Focusing the hub's project attaches to it.
        let members = network.members();
        assert_eq!(server_running(&members, &hub).as_deref(), Some("hub"));
        network.attach(server_running(&members, &hub));
        let last = sink.named::<ServerState>().pop().unwrap();
        assert_eq!(last["phase"], "running");
        assert!(
            network
                .members()
                .iter()
                .any(|m| m.name == "hub" && m.attached)
        );
        network.attach(None);
        let none = sink.named::<ServerState>().pop().unwrap();
        assert_eq!(none["phase"], "stopped");
        assert!(none["message"].as_str().unwrap().contains("No server"));
        network.clear().await;
    }

    #[test]
    fn a_workspace_without_a_network_or_with_a_stranger_cant_start() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let tools = Arc::new(fake_tools(tmp.path(), &dirs));
        let root = dunce::canonicalize(tmp.path()).unwrap().join("net");
        let hub = project(&root.join("servers/hub"), "Hub");
        let file = root.join(crate::workspace::FILE);
        std::fs::write(&file, r#"{"name":"Net","projects":["servers/*"]}"#).unwrap();
        let settings = Settings::default();
        let none = crate::workspace::read(&root, true).unwrap();
        let error = plan_workspace(&dirs, &tools, &none, &hub, &settings, true)
            .err()
            .unwrap();
        assert_eq!(error.code(), ErrorCode::Invalid);
        std::fs::write(
            &file,
            r#"{"name":"Net","projects":["servers/*"],"network":{"arrival":"hub","servers":{"hub":"servers/elsewhere"}}}"#,
        )
        .unwrap();
        let stranger = crate::workspace::read(&root, true).unwrap();
        let error = plan_workspace(&dirs, &tools, &stranger, &hub, &settings, true)
            .err()
            .unwrap();
        assert_eq!(error.code(), ErrorCode::NotFound, "{error}");
    }

    #[test]
    fn summarises_a_peers_reload() {
        let result = json!({"resources": [
            {"package": "lobby", "kind": "module", "id": "chat"},
            {"package": "lobby"}
        ]});
        assert_eq!(
            reload_summary(&result),
            "[NetherForge editor] Reloaded module chat, lobby"
        );
        assert_eq!(
            reload_summary(&json!({"resources": []})),
            "[NetherForge editor] Saved; nothing to reload"
        );
    }

    #[test]
    fn a_members_output_is_tagged_and_its_bridge_kept_from_the_ui() {
        let recording = Arc::new(RecordingSink::default());
        let sink = MemberSink {
            name: "lobby".into(),
            network: Weak::new(),
            inner: recording.clone(),
        };
        let sink: &dyn EventSink = &sink;
        sink.emit(&ServerOutputEvent {
            stream: Stream::Stdout,
            line: "Done".into(),
        });
        sink.emit(&BridgeMessage {
            message: "{}".into(),
        });
        assert_eq!(
            recording.named::<NetworkOutputEvent>(),
            [json!({"name": "lobby", "stream": "stdout", "line": "Done"})]
        );
        assert!(recording.named::<BridgeMessage>().is_empty());
        assert!(recording.named::<ServerOutputEvent>().is_empty());
    }

    /// What the app would find, as stand-ins: the fake server as the only
    /// Java, an empty "Paper" (which the proxy runs too), and plugin jars for
    /// 26.3 and the proxy.
    fn fake_tools(base: &std::path::Path, dirs: &AppDirs) -> Tools {
        use crate::app::tools::PaperSource;
        use crate::platform::Os;
        use crate::server::java::{JavaEnv, executable_name};
        let jdk = dirs.jdks().join("fake");
        let java = jdk.join("bin").join(executable_name(Os::current()));
        std::fs::create_dir_all(java.parent().unwrap()).unwrap();
        std::fs::copy(crate::testing::fake_server(), &java).unwrap();
        std::fs::write(jdk.join("release"), "JAVA_VERSION=\"25.0.1\"\n").unwrap();
        let paper = base.join("paper").join("paper-26.3-1.jar");
        let plugins = base.join("plugins");
        std::fs::create_dir_all(paper.parent().unwrap()).unwrap();
        std::fs::create_dir_all(&plugins).unwrap();
        std::fs::write(&paper, "").unwrap();
        std::fs::write(plugins.join("NetherForge-0.0.0-paper-26.3.jar"), "").unwrap();
        std::fs::write(plugins.join("NetherForge-0.0.0-velocity.jar"), "").unwrap();
        Tools {
            plugin_dirs: vec![plugins],
            test_runner_dirs: vec![],
            luals: vec![],
            java: JavaEnv {
                os: Os::current(),
                home: base.join("home"),
                system_root: base.join("system"),
                java_home: None,
                path_dirs: vec![],
                managed_jdks: dirs.jdks(),
                program_files: None,
                local_appdata: None,
            },
            paper: PaperSource::Jar(paper),
            installs: None,
        }
    }

    fn project(dir: &std::path::Path, name: &str) -> PathBuf {
        std::fs::create_dir_all(dir.join("modules")).unwrap();
        std::fs::write(
            dir.join("netherforge.json"),
            format!(
                r#"{{"formatVersion":1,"name":"{name}","namespace":"{}","version":"0.1.0","minecraft":"26.3"}}"#,
                name.to_lowercase()
            ),
        )
        .unwrap();
        dunce::canonicalize(dir).unwrap()
    }

    fn output_of(sink: &RecordingSink, name: &str) -> Vec<String> {
        sink.named::<NetworkOutputEvent>()
            .into_iter()
            .filter(|e| e["name"] == name)
            .map(|e| e["line"].as_str().unwrap().to_string())
            .collect()
    }

    #[tokio::test]
    async fn runs_a_network_and_reloads_its_other_servers() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let tools = Arc::new(fake_tools(tmp.path(), &dirs));
        let root = project(&tmp.path().join("main"), "Main");
        let lobby = project(&tmp.path().join("lobby"), "Lobby");
        let layout = NetworkLayout {
            enabled: true,
            name: "main".into(),
            servers: vec![layout::NetworkServer {
                name: "lobby".into(),
                project: "../lobby".into(),
            }],
        };
        let settings = Settings {
            server_port: setup::free_port().unwrap(),
            ..Settings::default()
        };
        let plan_it = || plan(&dirs, &tools, &root, "Main", &layout, &settings, true);

        let untrusted = plan_it().err().unwrap();
        assert_eq!(untrusted.code(), ErrorCode::Untrusted, "{untrusted}");
        let checked = check(&dirs, &tools, &root, &layout);
        assert_eq!(
            checked[0].root.as_deref(),
            Some(lobby.display().to_string().as_str())
        );
        assert_eq!(checked[0].project_name.as_deref(), Some("Lobby"));
        assert!(
            !checked[0].trusted && checked[0].problem.is_none(),
            "{checked:?}"
        );
        trust::set(&dirs.trust_file(), &lobby, true).unwrap();
        assert!(check(&dirs, &tools, &root, &layout)[0].trusted);

        let planned = plan_it().unwrap();
        assert_eq!(planned.main.name, "main");
        let names: Vec<&str> = planned.members.iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["proxy", "lobby"]);

        let sink = Arc::new(RecordingSink::default());
        let network = NetworkManager::new(dirs.clone(), sink.clone());
        network.start(planned.members, None).await;
        crate::testing::until(
            "the proxy and lobby running, lobby's plugin connected",
            || {
                let members = network.members();
                members.len() == 2
                    && members
                        .iter()
                        .all(|m| m.state.phase == crate::server::ServerPhase::Running)
                    && members[1].state.bridge_connected
            },
        )
        .await;
        let lobby_lines = output_of(&sink, "lobby");
        let joined = format!("hub 127.0.0.1:{}, secret true", planned.main.hub_port);
        assert!(
            lobby_lines
                .iter()
                .any(|l| l.contains("In network as lobby") && l.contains(&joined)),
            "{lobby_lines:?}"
        );
        // The proxy alone is in no network; it's told each server.
        assert!(
            !output_of(&sink, "proxy")
                .iter()
                .any(|l| l.contains("In network"))
        );
        let toml =
            std::fs::read_to_string(dirs.network_dir(&root).join("proxy/velocity.toml")).unwrap();
        assert!(
            toml.contains(&format!("\"main\" = \"127.0.0.1:{}\"", planned.main.port)),
            "{toml}"
        );
        assert!(toml.contains("\"lobby\" = \"127.0.0.1:"), "{toml}");
        assert!(
            dirs.network_dir(&root)
                .join("servers/lobby/eula.txt")
                .is_file()
        );
        assert!(sink.named::<NetworkMembers>().len() == 1);

        // Saving a file of lobby's project reloads lobby. The watcher starts
        // asynchronously, so write until it's heard.
        let file = lobby.join("modules/chat.lua");
        let deadline = std::time::Instant::now() + crate::testing::PATIENCE;
        let mut n = 0;
        while !output_of(&sink, "lobby")
            .iter()
            .any(|l| l.contains("Reloaded file modules/chat.lua"))
        {
            assert!(
                std::time::Instant::now() < deadline,
                "lobby was never reloaded"
            );
            n += 1;
            std::fs::write(&file, format!("-- {n}")).unwrap();
            tokio::time::sleep(Duration::from_millis(300)).await;
        }

        network.command("lobby", "say hi").await.unwrap();
        crate::testing::until("lobby echoing its command", || {
            output_of(&sink, "lobby").iter().any(|l| l == "> say hi")
        })
        .await;
        assert_eq!(
            network.command("nobody", "x").await.unwrap_err().code(),
            ErrorCode::NotFound
        );

        network.clear().await;
        assert!(network.members().is_empty());
        let last = sink.named::<NetworkMembers>().pop().unwrap();
        assert_eq!(last["members"], json!([]));
    }

    /// A real network: Java 25, Paper and Velocity from Fill, and the repo's
    /// own NetherForge jars (`:plugin:paper-26.3:assemble` and
    /// `:proxy:velocity:assemble` first). The project's server and a peer start
    /// behind the proxy, and the hub says both connected: `cargo test
    /// runs_a_real_network -- --ignored --nocapture`.
    #[tokio::test]
    #[ignore = "network, Java 25, the repo's jars and ~2 minutes"]
    async fn runs_a_real_network() {
        use crate::server::ServerPhase;
        use crate::server::process::Role;
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let tools = Arc::new(Tools::for_app(None, &dirs));
        let root = project(&tmp.path().join("main"), "Main");
        let lobby = project(&tmp.path().join("lobby"), "Lobby");
        trust::set(&dirs.trust_file(), &lobby, true).unwrap();
        let layout = NetworkLayout {
            enabled: true,
            name: "main".into(),
            servers: vec![layout::NetworkServer {
                name: "lobby".into(),
                project: "../lobby".into(),
            }],
        };
        let settings = Settings {
            server_port: setup::free_port().unwrap(),
            server_memory_mb: 1024,
            ..Settings::default()
        };
        let planned = plan(&dirs, &tools, &root, "Main", &layout, &settings, true).unwrap();

        let main_sink = Arc::new(RecordingSink::default());
        let main = ServerManager::new(dirs.clone(), main_sink.clone());
        let sink = Arc::new(RecordingSink::default());
        let network = NetworkManager::new(dirs.clone(), sink.clone());
        let dump = |sink: &RecordingSink, network_sink: &RecordingSink| {
            for line in sink.named::<ServerOutputEvent>() {
                println!("[main] {}", line["line"].as_str().unwrap());
            }
            for line in network_sink.named::<NetworkOutputEvent>() {
                println!(
                    "[{}] {}",
                    line["name"].as_str().unwrap(),
                    line["line"].as_str().unwrap()
                );
            }
        };
        main.start(StartConfig {
            project_root: root.clone(),
            project_name: "Main".into(),
            minecraft: Some("26.3".into()),
            settings: settings.clone(),
            eula_accepted: true,
            tools: tools.clone(),
            role: Role::Main(Some(planned.main.clone())),
        })
        .await
        .unwrap();
        network.start(planned.members, None).await;

        let deadline = std::time::Instant::now() + Duration::from_secs(300);
        loop {
            let members = network.members();
            let up = main.state().phase == ServerPhase::Running
                && members
                    .iter()
                    .all(|m| m.state.phase == ServerPhase::Running);
            if up {
                break;
            }
            let down = main.state().phase == ServerPhase::Crashed
                || members.iter().any(|m| {
                    // Stopped before it began is just not started yet; with a message, it failed.
                    m.state.phase == ServerPhase::Crashed
                        || (m.state.phase == ServerPhase::Stopped && m.state.message.is_some())
                });
            if down || std::time::Instant::now() > deadline {
                dump(&main_sink, &sink);
                panic!("the network didn't come up: {:?} {members:?}", main.state());
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }

        // The hub knows both servers once their plugins connect.
        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        loop {
            network.command("proxy", "nfhub").await.unwrap();
            tokio::time::sleep(Duration::from_secs(1)).await;
            let said = output_of(&sink, "proxy").join("\n");
            if said.contains("main: connected") && said.contains("lobby: connected") {
                break;
            }
            if std::time::Instant::now() > deadline {
                dump(&main_sink, &sink);
                panic!("the hub never had both servers");
            }
        }
        let server = dirs.server_dir(&root);
        let properties = std::fs::read_to_string(server.join("server.properties")).unwrap();
        assert!(properties.contains("online-mode=false"), "{properties}");
        let global = std::fs::read_to_string(server.join("config/paper-global.yml")).unwrap();
        assert!(
            global.contains("_version"),
            "Paper wrote its defaults around ours: {global}"
        );

        let (_, _) = tokio::join!(main.stop(), network.stop());
        assert_eq!(main.state().phase, ServerPhase::Stopped);
        dump(&main_sink, &sink);
    }
}
