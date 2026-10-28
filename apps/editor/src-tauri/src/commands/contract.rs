//! The backend contract's Rust side. The cases are
//! `apps/editor/src/core/backend/contract.json`, in the UI's terms: calls to
//! `Backend` methods, events it listens to, and what the outside world does.
//! `contract.test.ts` runs each on `MemoryBackend`, and on `TauriBackend`
//! with `@tauri-apps/api`'s IPC mocked to forward every command to this host
//! (`contract.tauri.node.test.ts` starts it: `cargo test … commands::contract::host
//! -- --ignored --exact`, connected back over `NETHERFORGE_CONTRACT_HOST`).
//! So the UI's argument mapping, the real commands and the events they emit
//! are checked together, and the fake the UI's tests use can't promise
//! anything the app doesn't do.
//!
//! Each command goes through Tauri's own IPC on its mock runtime
//! (`tauri::test::get_ipc_response`), built with the app's real context
//! (`crate::context()`, so the capability and the generated permission are
//! checked too): the invoke handler, argument deserialization, the managed
//! `AppState` and the serialized `{ code, message }` rejection are all the
//! app's. Only the window is fake, and what the app would find on the
//! computer ([Tools]) are stand-ins in a temp folder: the fake server
//! (`examples/fake_server.rs`) as Java, an empty Paper jar and plugin jar, no
//! lua-language-server, and a home folder for Minecraft installs.
//!
//! The protocol is JSON lines. The suite sends `{"id", "op", …}` and gets
//! `{"id", "ok"}` or `{"id", "err"}`; every event the app emits arrives as
//! `{"event", "payload"}` whenever it happens.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::JoinHandle;
use std::time::Duration;

use serde_json::{Map, Value, json};
use tauri::ipc::{CallbackFn, InvokeBody};
use tauri::test::{INVOKE_KEY, MockRuntime, get_ipc_response, mock_builder};
use tauri::webview::InvokeRequest;
use tauri::{App, Listener, Manager, WebviewWindow, WebviewWindowBuilder};

use super::bindings;
use crate::app::dirs::AppDirs;
use crate::app::events::EventSink;
use crate::app::tools::{PaperSource, Tools};
use crate::minecraft::installs::InstallEnv;
use crate::platform::Os;
use crate::server::java::JavaEnv;
use crate::state::AppState;

/// Where the suite listens for this host: `127.0.0.1:<port>`.
const HOST: &str = "NETHERFORGE_CONTRACT_HOST";

/// The probe file the host writes to know the watcher is live (see [crate::watcher::until_watching]).
const PROBE: &str = "nf-probe";

/// The contract suite's host. Without `NETHERFORGE_CONTRACT_HOST` (a plain
/// `cargo test -- --ignored`) it has nothing to serve and passes.
#[test]
#[ignore = "the backend contract's host: contract.tauri.node.test.ts starts it"]
fn host() {
    let Ok(address) = std::env::var(HOST) else {
        return;
    };
    let stream = TcpStream::connect(&address).expect("the contract suite listens");
    let writer = Writer(Arc::new(Mutex::new(stream.try_clone().unwrap())));
    let mut case: Option<Case> = None;
    for line in BufReader::new(stream).lines() {
        let Ok(line) = line else { break };
        let request: Value = serde_json::from_str(&line).expect("a request is JSON");
        let id = request["id"].clone();
        let outcome = match request["op"].as_str().unwrap_or_default() {
            "begin" => {
                if let Some(old) = case.take() {
                    old.end();
                }
                let (begun, vars) = Case::begin(&request, writer.clone());
                case = Some(begun);
                Ok(vars)
            }
            // Answered when the agent is: the UI's reply (an `mcp_send`) comes
            // through this loop meanwhile.
            "agentAnswer" => {
                let handle = request["handle"].as_u64().unwrap();
                let agent = case
                    .as_mut()
                    .and_then(|case| case.agents.remove(&handle))
                    .expect("an agent's request");
                let writer = writer.clone();
                std::thread::spawn(move || {
                    let answer = agent.join().unwrap();
                    writer.send(&json!({ "id": id, "ok": answer }));
                });
                continue;
            }
            "end" => {
                if let Some(old) = case.take() {
                    old.end();
                }
                Ok(Value::Null)
            }
            op => match case.as_mut() {
                Some(case) => case.handle(op, &request),
                None => Err(json!({ "code": "other", "message": "no case has begun" })),
            },
        };
        writer.send(&match outcome {
            Ok(ok) => json!({ "id": id, "ok": ok }),
            Err(err) => json!({ "id": id, "err": err }),
        });
    }
    if let Some(case) = case {
        case.end();
    }
}

/// The connection to the suite, shared by the replies and the event listeners.
#[derive(Clone)]
struct Writer(Arc<Mutex<TcpStream>>);

impl Writer {
    fn send(&self, message: &Value) {
        let mut stream = self.0.lock().unwrap();
        let _ = writeln!(stream, "{message}");
        let _ = stream.flush();
    }
}

/// One case's app, over its own temp folders.
struct Case {
    _temp: tempfile::TempDir,
    app: App<MockRuntime>,
    window: WebviewWindow<MockRuntime>,
    /// Paths of each `fs://changed`, for knowing when the watcher is live.
    changes: mpsc::Receiver<Vec<String>>,
    /// Whether opening a project waits for its watcher to be live.
    watch: bool,
    /// Agents' MCP requests in flight, by handle.
    agents: HashMap<u64, JoinHandle<Value>>,
    next_agent: u64,
}

impl Case {
    fn begin(request: &Value, writer: Writer) -> (Self, Value) {
        let temp = tempfile::tempdir().unwrap();
        let base = dunce::canonicalize(temp.path()).unwrap();
        let project = base.join("project");
        write_files(&project, &request["project"]);
        for (name, files) in object(&request["packages"]) {
            write_files(&base.join(name), files);
        }
        let repositories = base.join("git");
        std::fs::create_dir_all(&repositories).unwrap();
        for (name, files) in object(&request["repositories"]) {
            publish(&repositories, name, files);
        }

        let dirs = AppDirs::in_one(&base.join("app"));
        let setup = &request["setup"];
        let tools = tools(&base, &dirs, setup);
        for (version, data) in object(&setup["gameData"]) {
            crate::minecraft::cache::write_game_data(&dirs, version, data)
                .expect("the case's game data is today's schema");
        }
        write_files(&dirs.server_dir(&project), &setup["serverFiles"]);
        if let Some(workspace) = setup["workspace"].as_str() {
            std::fs::write(base.join(crate::workspace::FILE), workspace).unwrap();
        }
        let client_jar = base.join("client.jar");
        if let Some(client) = setup.get("client") {
            client_jar_with(&client_jar, client);
        }

        let (app, window) = app(dirs, tools);
        let (changes_tx, changes) = mpsc::channel();
        for name in bindings::event_names() {
            let writer = writer.clone();
            let changes = changes_tx.clone();
            let event = name.clone();
            app.listen_any(name, move |emitted| {
                let payload: Value = serde_json::from_str(emitted.payload()).unwrap();
                if event == "fs://changed" {
                    let paths: Vec<String> =
                        serde_json::from_value(payload["paths"].clone()).unwrap();
                    let _ = changes.send(paths.clone());
                    // The host's own probe isn't the case's business.
                    if !paths.is_empty() && paths.iter().all(|p| p.ends_with(PROBE)) {
                        return;
                    }
                }
                writer.send(&json!({ "event": event, "payload": payload }));
            });
        }

        let git = format!(
            "file://{}{}",
            if cfg!(windows) { "/" } else { "" },
            repositories.to_str().unwrap().replace('\\', "/")
        );
        let vars = json!({
            "ROOT": project.to_str().unwrap(),
            "GIT": git,
            "CLIENT_JAR": client_jar.to_str().unwrap(),
            "PORT": free_port(),
            "MCP_PORT": free_port(),
        });
        let case = Self {
            _temp: temp,
            app,
            window,
            changes,
            watch: request["watch"] == json!(true),
            agents: HashMap::new(),
            next_agent: 0,
        };
        (case, vars)
    }

    fn handle(&mut self, op: &str, request: &Value) -> Result<Value, Value> {
        match op {
            "invoke" => {
                let command = request["cmd"].as_str().unwrap();
                let result = call(&self.window, command, request["args"].clone());
                if result.is_ok()
                    && self.watch
                    && matches!(command, "project_open" | "project_create")
                {
                    self.until_watching();
                }
                result
            }
            "outside" => self.outside(&request["action"]),
            "fetch" => Ok(self.fetch(request["url"].as_str().unwrap())),
            _ => Err(json!({ "code": "other", "message": format!("no op {op}") })),
        }
    }

    fn state(&self) -> tauri::State<'_, AppState> {
        self.app.state::<AppState>()
    }

    /// The open project's watcher reports changes from here on.
    fn until_watching(&self) {
        let root = self.state().root().unwrap().path().to_path_buf();
        while self.changes.try_recv().is_ok() {}
        crate::watcher::until_watching(&root, PROBE, |wait| self.changes.recv_timeout(wait).ok());
    }

    /// Another program changes the project, or an agent calls the MCP server.
    fn outside(&mut self, action: &Value) -> Result<Value, Value> {
        let root = self.state().root().map(|root| root.path().to_path_buf());
        let at = |key: &str| root.as_ref().unwrap().join(action[key].as_str().unwrap());
        match action["do"].as_str().unwrap() {
            "write" => {
                let file = at("path");
                std::fs::create_dir_all(file.parent().unwrap()).unwrap();
                std::fs::write(file, action["text"].as_str().unwrap()).unwrap();
            }
            "delete" => {
                let path = at("path");
                if path.is_dir() {
                    std::fs::remove_dir_all(path).unwrap();
                } else {
                    std::fs::remove_file(path).unwrap();
                }
            }
            "rename" => {
                let to = at("to");
                std::fs::create_dir_all(to.parent().unwrap()).unwrap();
                std::fs::rename(at("from"), to).unwrap();
            }
            "agent" => {
                let status = self.state().mcp.status();
                let port = status.port;
                let body = action["message"].to_string();
                self.next_agent += 1;
                let handle = self.next_agent;
                self.agents.insert(
                    handle,
                    std::thread::spawn(move || post(port, &status.token, &body)),
                );
                return Ok(json!(handle));
            }
            other => panic!("no outside action {other}"),
        }
        Ok(Value::Null)
    }

    /// What the webview gets for [url], one of the app's own schemes, as the app serves it.
    fn fetch(&self, url: &str) -> Value {
        let uri: tauri::http::Uri = url.parse().unwrap();
        let host = uri.host().unwrap_or_default();
        let response = if host.starts_with("nfproject") || uri.scheme_str() == Some("nfproject") {
            let state = self.state();
            crate::protocols::project_response(
                state.root().ok().as_ref(),
                &state.dirs.packages(),
                uri.path(),
            )
        } else if host.starts_with("nfasset") || uri.scheme_str() == Some("nfasset") {
            crate::protocols::asset_response(&self.state().dirs.minecraft(), uri.path())
        } else {
            panic!("the app serves no {url}");
        };
        json!({
            "status": response.status().as_u16(),
            "bytes": response.body(),
        })
    }

    fn end(self) {
        {
            let state = self.state();
            tauri::async_runtime::block_on(state.close_project());
            tauri::async_runtime::block_on(state.mcp.apply(false, 0));
        }
        for (_, agent) in self.agents {
            let _ = agent.join();
        }
    }
}

/// The editor's backend on the mock runtime, as the app sets it up.
fn app(dirs: AppDirs, tools: Tools) -> (App<MockRuntime>, WebviewWindow<MockRuntime>) {
    let builder = bindings::mock_builder();
    let app = mock_builder()
        .invoke_handler(builder.invoke_handler())
        .build(crate::context())
        .expect("the mock app builds");
    let sink: Arc<dyn EventSink> = Arc::new(app.handle().clone());
    app.manage(AppState::new(dirs, tools, sink));
    let window = WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("the mock window opens");
    (app, window)
}

/// What the app would find on the computer, as stand-ins under [base]: the
/// fake server as the only Java (a JDK home in the data folder, Java 25 by its
/// `release` file), an empty Paper jar, a plugin jar for Minecraft 26.3 and the proxy plugin, the
/// test runner when the case says what it prints (`testReport`), no
/// lua-language-server, and a home folder with what `installs` lists.
fn tools(base: &Path, dirs: &AppDirs, setup: &Value) -> Tools {
    let jdk = dirs.jdks().join("fake");
    let java = jdk
        .join("bin")
        .join(crate::server::java::executable_name(Os::current()));
    std::fs::create_dir_all(java.parent().unwrap()).unwrap();
    std::fs::copy(crate::testing::fake_server(), &java).unwrap();
    std::fs::write(jdk.join("release"), "JAVA_VERSION=\"25.0.1\"\n").unwrap();

    let paper = base.join("paper").join("paper-26.3-1.jar");
    let plugins = base.join("plugins");
    let runner = base.join("test-runner");
    for (file, text) in [
        (paper.clone(), String::new()),
        (
            plugins.join("NetherForge-0.0.0-paper-26.3.jar"),
            String::new(),
        ),
        // A dev network's proxy runs the same "Paper" (the fake server).
        (
            plugins.join("NetherForge-0.0.0-velocity.jar"),
            String::new(),
        ),
    ] {
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text).unwrap();
    }
    std::fs::create_dir_all(&runner).unwrap();
    if let Some(report) = setup.get("testReport") {
        std::fs::write(
            runner.join("NetherForgeTest-0.0.0.jar"),
            runner_output(report),
        )
        .unwrap();
    }

    let home = base.join("home");
    for path in setup["installs"]["files"].as_array().into_iter().flatten() {
        let file = home.join(path.as_str().unwrap());
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, "").unwrap();
    }
    std::fs::create_dir_all(&home).unwrap();
    Tools {
        plugin_dirs: vec![plugins],
        test_runner_dirs: vec![runner],
        luals: vec![],
        java: JavaEnv {
            os: Os::current(),
            home: home.clone(),
            system_root: base.join("system"),
            java_home: None,
            path_dirs: vec![],
            managed_jdks: dirs.jdks(),
            program_files: None,
            local_appdata: None,
        },
        paper: PaperSource::Jar(paper),
        // The Linux launcher's layout on every OS: the case's paths are `/`-separated.
        installs: Some(InstallEnv {
            os: Os::Linux,
            home,
            system_root: base.join("system"),
            appdata: None,
            local_appdata: None,
            xdg_data_home: None,
        }),
    }
}

/// A `TestReport` as the test runner prints it with `--json`.
fn runner_output(report: &Value) -> String {
    let mut lines = vec![json!({ "event": "start" })];
    for result in report["results"].as_array().into_iter().flatten() {
        let mut line = result.as_object().unwrap().clone();
        line.insert("event".into(), json!("result"));
        lines.push(Value::Object(line));
    }
    if let Some(message) = report["error"].as_str() {
        lines.push(json!({ "event": "error", "message": message, "problems": report["problems"] }));
    }
    lines.push(json!({
        "event": "end",
        "passed": report["passed"],
        "failed": report["failed"],
        "errored": report["errored"],
        "durationMillis": report["durationMillis"],
    }));
    lines.iter().map(|line| format!("{line}\n")).collect()
}

/// A client jar for [client]'s `version` holding its `files` (path → text).
fn client_jar_with(path: &Path, client: &Value) {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    zip.start_file("version.json", options).unwrap();
    write!(zip, "{}", json!({ "id": client["version"] })).unwrap();
    for (name, text) in object(&client["files"]) {
        zip.start_file(name, options).unwrap();
        zip.write_all(text.as_str().unwrap().as_bytes()).unwrap();
    }
    zip.finish().unwrap();
}

fn object(value: &Value) -> impl Iterator<Item = (&String, &Value)> {
    value.as_object().into_iter().flat_map(Map::iter)
}

fn write_files(dir: &Path, files: &Value) {
    std::fs::create_dir_all(dir).unwrap();
    for (path, text) in object(files) {
        let file = dir.join(path);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text.as_str().unwrap()).unwrap();
    }
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

/// An agent's MCP request: the JSON it's answered with, or null for none (202).
fn post(port: u16, token: &str, body: &str) -> Value {
    let request = format!(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Type: application/json\r\n\
         Authorization: Bearer {token}\r\nAccept: application/json, text/event-stream\r\n\
         Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(150)))
        .unwrap();
    stream.write_all(request.as_bytes()).unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    let body = response.split_once("\r\n\r\n").map_or("", |(_, body)| body);
    serde_json::from_str(body).unwrap_or(Value::Null)
}

/// Calls [command] as the UI would: `Ok(answer)` or `Err(rejection)`.
fn call(window: &WebviewWindow<MockRuntime>, command: &str, args: Value) -> Result<Value, Value> {
    let request = InvokeRequest {
        cmd: command.into(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        // The app's own origin, so the call is checked against its capability.
        url: if cfg!(windows) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .unwrap(),
        body: InvokeBody::Json(args),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.into(),
    };
    get_ipc_response(window, request).map(|body| body.deserialize::<Value>().unwrap())
}

/// Runs git in [cwd] as a test author.
fn git(cwd: &Path, args: &[&str]) {
    let output = std::process::Command::new("git")
        .args(["-c", "user.name=Test", "-c", "user.email=test@example.com"])
        .args(args)
        .current_dir(cwd)
        .output()
        .expect("git runs");
    assert!(
        output.status.success(),
        "git {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

/// [files] committed on main and tagged v1, pushed to a bare repository at
/// `<dir>/<name>`: what `$GIT/<name>` names.
fn publish(dir: &Path, name: &str, files: &Value) {
    let work: PathBuf = dir.join(format!("{name}-work"));
    write_files(&work, files);
    git(&work, &["init", "--quiet", "--initial-branch=main"]);
    git(&work, &["add", "-A"]);
    git(&work, &["commit", "--quiet", "-m", name]);
    git(&work, &["tag", "v1"]);
    git(
        dir,
        &["clone", "--quiet", "--bare", &format!("{name}-work"), name],
    );
}

#[test]
fn rejections_carry_a_code_and_a_message() {
    let temp = tempfile::tempdir().unwrap();
    let dirs = AppDirs::in_one(temp.path());
    let tools = Tools::for_app(None, &dirs);
    let (_app, window) = app(dirs, tools);
    let error = call(&window, "fs_list", json!({})).unwrap_err();
    assert_eq!(error["code"], "noProject");
    assert_eq!(error["message"], "No project is open");
}

#[test]
fn every_command_and_event_is_named() {
    assert!(bindings::command_names().contains(&"fs_list".to_string()));
    assert!(bindings::event_names().contains(&"fs://changed".to_string()));
}
