//! What the backend holds while the app runs.

use std::sync::{Arc, Mutex};

use crate::app::dirs::AppDirs;
use crate::app::events::EventSink;
use crate::app::tools::Tools;
use crate::error::{Error, ErrorCode, Result};
use crate::fs::ProjectRoot;
use crate::luals::LanguageServer;
use crate::mcp::{self, McpServer};
use crate::project::Opened;
use crate::server::network::NetworkManager;
use crate::server::process::ServerManager;
use crate::watcher::WatchHandle;

pub struct OpenProject {
    /// Its `info.trusted` is the trust list's answer, kept in step by [AppState::set_trusted].
    pub opened: Opened,
    /// Dropping it stops the watcher.
    pub _watch: WatchHandle,
}

pub struct AppState {
    pub dirs: AppDirs,
    /// Where the editor finds what it runs (plugins, Java, Paper, LuaLS) and the player's installs.
    pub tools: Arc<Tools>,
    pub project: Mutex<Option<OpenProject>>,
    pub server: Arc<ServerManager>,
    /// The rest of the open project's dev network (its proxy and other servers), while it runs one.
    pub network: Arc<NetworkManager>,
    pub mcp: Arc<McpServer>,
    /// lua-language-server for the open project, while the UI has one running.
    pub luals: Arc<LanguageServer>,
}

impl AppState {
    pub fn new(dirs: AppDirs, tools: Tools, sink: Arc<dyn EventSink>) -> Self {
        let token = mcp::token::load_or_create(&dirs.config.join(mcp::token::FILE)).unwrap_or_else(
            |error| {
                // Still guarded, just not for longer than this run.
                eprintln!("[netherforge] {error}; the MCP token lasts until the editor quits");
                mcp::token::generate()
            },
        );
        Self {
            mcp: McpServer::new(sink.clone(), token),
            luals: LanguageServer::new(sink.clone(), dirs.luals()),
            server: ServerManager::new(dirs.clone(), sink.clone()),
            network: NetworkManager::new(dirs.clone(), sink),
            tools: Arc::new(tools),
            dirs,
            project: Mutex::new(None),
        }
    }

    /// The open project's root, or an error a command can return as-is.
    pub fn root(&self) -> Result<ProjectRoot> {
        self.project
            .lock()
            .unwrap()
            .as_ref()
            .map(|p| p.opened.root.clone())
            .ok_or_else(|| Error::new(ErrorCode::NoProject, "No project is open"))
    }

    /// The open project's root if the user trusts it: what anything that runs
    /// or reaches out on the project's behalf asks for (see `trust`).
    pub fn trusted_root(&self) -> Result<ProjectRoot> {
        let project = self.project.lock().unwrap();
        let open = project
            .as_ref()
            .ok_or_else(|| Error::new(ErrorCode::NoProject, "No project is open"))?;
        if !open.opened.info.trusted {
            return Err(untrusted(&open.opened.info.name));
        }
        Ok(open.opened.root.clone())
    }

    /// Records whether the open project is trusted, and lets agents' tools in or keeps them out.
    pub fn set_trusted(&self, trusted: bool) {
        if let Some(open) = self.project.lock().unwrap().as_mut() {
            open.opened.info.trusted = trusted;
        }
        self.refresh_agents();
    }

    /// Records the open project's workspace as it reads now.
    pub fn set_workspace(&self, workspace: Option<crate::workspace::WorkspaceInfo>) {
        if let Some(open) = self.project.lock().unwrap().as_mut() {
            open.opened.info.workspace = workspace;
        }
    }

    /// The open project's workspace, if it's in one.
    pub fn workspace(&self) -> Option<crate::workspace::WorkspaceInfo> {
        self.opened().and_then(|opened| opened.info.workspace)
    }

    /// Agents' tools act on the open project: refused while it's untrusted.
    pub fn refresh_agents(&self) {
        let refusal = self
            .project
            .lock()
            .unwrap()
            .as_ref()
            .filter(|open| !open.opened.info.trusted)
            .map(|open| untrusted(&open.opened.info.name).message);
        self.mcp.pipe.set_refusal(refusal);
    }

    pub fn opened(&self) -> Option<Opened> {
        self.project
            .lock()
            .unwrap()
            .as_ref()
            .map(|p| p.opened.clone())
    }

    /// Stops the project's server and the rest of its dev network, all at once.
    pub async fn stop_servers(&self) -> Result<()> {
        let (server, _) = tokio::join!(self.server.stop(), self.network.stop());
        server
    }

    /// Stops the servers, lua-language-server and the watcher, and forgets the project.
    pub async fn close_project(&self) {
        self.luals.stop();
        let _ = self.server.stop().await;
        self.network.clear().await;
        let previous = self.project.lock().unwrap().take();
        drop(previous);
        self.server.set_target(None);
        self.refresh_agents();
    }
}

/// The refusal for something an untrusted project [name] can't do.
pub fn untrusted(name: &str) -> Error {
    Error::new(
        ErrorCode::Untrusted,
        format!(
            "\"{name}\" isn't trusted yet: its dev server, Lua language features, git packages and agents' tools stay off until you trust it"
        ),
    )
}
