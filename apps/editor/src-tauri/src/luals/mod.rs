//! lua-language-server (LuaLS) for the open project, spoken to over stdio and
//! bridged to the webview, where `monaco-languageclient` is the LSP client.
//!
//! The backend only moves messages: `luals_send` frames one onto the server's
//! stdin, and each message the server writes is emitted as `luals://message`.
//! What they say (initialize, didOpen, completion…) is the client's business.
//!
//! LuaLS isn't one executable: `bin/lua-language-server` runs `bin/main.lua`,
//! which loads `script/`, `meta/` and `locale/` beside it. So the whole
//! release folder is a bundle resource (`tauri.bundle.conf.json`, from
//! `tools/luals.mjs`, which pins and checksums it), and dev builds run the
//! copy that script fetched into `src-tauri/lua-language-server/`. What it
//! writes (generated std-library stubs, logs) goes in the data folder, never
//! the app's own folder.
//!
//! One server runs at a time, for the open project; every start gets a new
//! *generation*, and messages and the exit event carry theirs so the UI can
//! ignore a previous server's last words.

pub mod framing;

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot};

use crate::app::events::EventSink;
use crate::error::{Error, ErrorCode, Result};
use crate::server::process::hide_console;

/// The folder `tools/luals.mjs` fills and the bundle copies, under resources and `src-tauri/`.
pub const FOLDER: &str = "lua-language-server";

const EXECUTABLE: &str = if cfg!(windows) {
    "lua-language-server.exe"
} else {
    "lua-language-server"
};

/// Where LuaLS may be, best first: the app's resources, then (dev builds) the fetched copy.
pub fn search_paths(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut found = Vec::new();
    if let Some(resources) = resource_dir {
        found.push(resources.join(FOLDER).join("bin").join(EXECUTABLE));
    }
    if cfg!(debug_assertions) {
        found.push(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join(FOLDER)
                .join("bin")
                .join(EXECUTABLE),
        );
    }
    found
}

/// The first of [candidates] that exists.
pub fn locate(candidates: &[PathBuf]) -> Option<PathBuf> {
    candidates.iter().find(|path| path.is_file()).cloned()
}

#[derive(Debug, Clone, Serialize, PartialEq, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "luals://message")]
pub struct LualsMessage {
    pub generation: u64,
    /// One JSON-RPC message, as the server wrote it.
    pub message: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "luals://exit")]
pub struct LualsExit {
    pub generation: u64,
    pub code: Option<i32>,
}

struct Running {
    generation: u64,
    stdin: mpsc::UnboundedSender<String>,
    /// Dropping it (or sending) kills the process.
    _kill: oneshot::Sender<()>,
}

pub struct LanguageServer {
    sink: Arc<dyn EventSink>,
    /// Where LuaLS writes its generated stubs (`meta/`) and logs (`log/`).
    data_dir: PathBuf,
    state: Mutex<(u64, Option<Running>)>,
}

impl LanguageServer {
    pub fn new(sink: Arc<dyn EventSink>, data_dir: PathBuf) -> Arc<Self> {
        Arc::new(Self {
            sink,
            data_dir,
            state: Mutex::new((0, None)),
        })
    }

    /// Starts [program] (LuaLS, or anything speaking LSP on stdio) in
    /// [project], replacing a server already running. Returns its generation.
    /// Call it inside the async runtime (a command is).
    pub fn start(self: &Arc<Self>, program: &Path, project: &Path) -> Result<u64> {
        let mut command = Command::new(program);
        command
            .arg(format!(
                "--metapath={}",
                self.data_dir.join("meta").display()
            ))
            .arg(format!("--logpath={}", self.data_dir.join("log").display()))
            .current_dir(project)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        hide_console(&mut command);
        let mut child = command.spawn().map_err(|e| {
            Error::msg(format!(
                "Couldn't start lua-language-server ({}): {e}",
                program.display()
            ))
        })?;
        let (Some(mut stdin), Some(stdout), Some(stderr)) =
            (child.stdin.take(), child.stdout.take(), child.stderr.take())
        else {
            return Err(Error::msg("Couldn't attach to lua-language-server"));
        };

        let (stdin_tx, mut stdin_rx) = mpsc::unbounded_channel::<String>();
        let (kill_tx, kill_rx) = oneshot::channel::<()>();
        let generation = {
            let mut state = self.state.lock().unwrap();
            state.0 += 1;
            // The previous server (if any) is killed as its Running drops.
            state.1 = Some(Running {
                generation: state.0,
                stdin: stdin_tx,
                _kill: kill_tx,
            });
            state.0
        };

        tokio::spawn(async move {
            while let Some(message) = stdin_rx.recv().await {
                if framing::write_message(&mut stdin, &message).await.is_err() {
                    break;
                }
            }
        });

        let sink = self.sink.clone();
        tokio::spawn(async move {
            let mut reader = BufReader::new(stdout);
            loop {
                match framing::read_message(&mut reader).await {
                    Ok(Some(message)) => sink.emit(&LualsMessage {
                        generation,
                        message,
                    }),
                    Ok(None) => break,
                    Err(error) => {
                        eprintln!("[netherforge] lua-language-server: {error}");
                        break;
                    }
                }
            }
        });

        tokio::spawn(async move {
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                eprintln!("[lua-language-server] {line}");
            }
        });

        let this = Arc::downgrade(self);
        tokio::spawn(async move {
            let code = tokio::select! {
                status = child.wait() => status.ok().and_then(|s| s.code()),
                _ = kill_rx => {
                    let _ = child.kill().await;
                    return;
                }
            };
            let Some(this) = this.upgrade() else { return };
            let current = {
                let mut state = this.state.lock().unwrap();
                let current = state.1.as_ref().is_some_and(|r| r.generation == generation);
                if current {
                    state.1 = None;
                }
                current
            };
            if current {
                this.sink.emit(&LualsExit { generation, code });
            }
        });

        Ok(generation)
    }

    /// Sends one JSON-RPC message to the server of [generation].
    pub fn send(&self, generation: u64, message: String) -> Result<()> {
        let state = self.state.lock().unwrap();
        match &state.1 {
            Some(running) if running.generation == generation => running
                .stdin
                .send(message)
                .map_err(|_| Error::new(ErrorCode::Unavailable, "lua-language-server has stopped")),
            _ => Err(Error::new(
                ErrorCode::Unavailable,
                "lua-language-server has stopped",
            )),
        }
    }

    /// Stops the running server, if any. Its exit isn't reported: it was asked for.
    pub fn stop(&self) {
        self.state.lock().unwrap().1 = None;
    }

    pub fn running(&self) -> Option<u64> {
        self.state.lock().unwrap().1.as_ref().map(|r| r.generation)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::events::RecordingSink;
    use crate::testing;

    #[test]
    fn locates_the_first_that_exists() {
        let dir = tempfile::tempdir().unwrap();
        let resources = dir.path().join("resources");
        let bundled = resources.join(FOLDER).join("bin").join(EXECUTABLE);
        let candidates = search_paths(Some(&resources));
        assert_eq!(candidates[0], bundled);
        assert_eq!(locate(&candidates[..1]), None);
        std::fs::create_dir_all(bundled.parent().unwrap()).unwrap();
        std::fs::write(&bundled, "").unwrap();
        assert_eq!(locate(&candidates), Some(bundled));
    }

    /// The fake server stands in for LuaLS (it's given LuaLS's arguments):
    /// whatever goes in comes back out, framed, until it's told to exit.
    #[tokio::test]
    async fn bridges_messages_both_ways_and_reports_only_its_own_exit() {
        let dir = tempfile::tempdir().unwrap();
        let sink = Arc::new(RecordingSink::default());
        let server = LanguageServer::new(sink.clone(), dir.path().join("luals"));
        let fake = testing::fake_server();

        let first = server.start(&fake, dir.path()).unwrap();
        server.send(first, r#"{"id":1}"#.into()).unwrap();
        testing::until("the echo", || !sink.named::<LualsMessage>().is_empty()).await;
        assert_eq!(
            sink.named::<LualsMessage>(),
            vec![serde_json::json!({ "generation": first, "message": r#"{"id":1}"# })]
        );

        // A restart replaces it: the old generation is refused and its exit isn't reported.
        let second = server.start(&fake, dir.path()).unwrap();
        assert!(second > first);
        assert!(server.send(first, "{}".into()).is_err());
        server.send(second, r#"{"id":2}"#.into()).unwrap();
        testing::until("the second echo", || {
            sink.named::<LualsMessage>().len() == 2
        })
        .await;

        server.stop();
        assert_eq!(server.running(), None);
        assert!(server.send(second, "{}".into()).is_err());

        // Neither the replaced server nor the stopped one says it exited. Their
        // monitors return the moment their kill channel drops, without a word;
        // "never" can only be checked up to a point, and that point is a third
        // server exiting by itself (a process start and a message later): its
        // exit is reported, and it's the only one.
        let third = server.start(&fake, dir.path()).unwrap();
        server
            .send(third, r#"{"method":"exit","params":{"code":3}}"#.into())
            .unwrap();
        testing::until("the third server's exit", || {
            !sink.named::<LualsExit>().is_empty()
        })
        .await;
        assert_eq!(
            sink.named::<LualsExit>(),
            vec![serde_json::json!({ "generation": third, "code": 3 })]
        );
        assert_eq!(server.running(), None);
    }

    #[tokio::test]
    async fn a_missing_program_is_a_sentence() {
        let dir = tempfile::tempdir().unwrap();
        let server = LanguageServer::new(Arc::new(RecordingSink::default()), dir.path().into());
        let error = server
            .start(&dir.path().join("nothing-here"), dir.path())
            .unwrap_err();
        assert!(
            error
                .message()
                .starts_with("Couldn't start lua-language-server")
        );
    }
}
