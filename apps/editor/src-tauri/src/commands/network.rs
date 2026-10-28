//! The open project's dev network: its layout (the editor's, per project),
//! the other servers' projects as Start would find them, trusting one, and the
//! running members. Starting and stopping are `server_start`/`server_stop`.

use tauri::State;

use super::blocking;
use crate::error::Result;
use crate::server::network::layout::{self, NetworkLayout};
use crate::server::network::{self, NetworkMember, NetworkServerCheck};
use crate::state::AppState;
use crate::trust;

/// The open project's layout (the default when none was saved).
#[tauri::command]
#[specta::specta]
pub async fn network_layout(state: State<'_, AppState>) -> Result<NetworkLayout> {
    let root = state.root()?;
    let file = state.dirs.networks_file();
    blocking(move || Ok(layout::load(&file, root.path()))).await
}

/// Saves the open project's layout; the next start uses it.
#[tauri::command]
#[specta::specta]
pub async fn network_set_layout(state: State<'_, AppState>, layout: NetworkLayout) -> Result<()> {
    let root = state.root()?;
    let file = state.dirs.networks_file();
    blocking(move || layout::save(&file, root.path(), &layout)).await
}

/// Each of the layout's other servers: its project, whether it's trusted,
/// and what would keep it from starting.
#[tauri::command]
#[specta::specta]
pub async fn network_check(state: State<'_, AppState>) -> Result<Vec<NetworkServerCheck>> {
    let root = state.root()?;
    let dirs = state.dirs.clone();
    let tools = state.tools.clone();
    blocking(move || {
        let layout = layout::load(&dirs.networks_file(), root.path());
        Ok(network::check(&dirs, &tools, root.path(), &layout))
    })
    .await
}

/// Trusts the project folder the layout's server [name] runs, as opening it
/// and trusting it would. Only from a trusted project.
#[tauri::command]
#[specta::specta]
pub async fn network_trust(state: State<'_, AppState>, name: String) -> Result<()> {
    let root = state.trusted_root()?;
    let dirs = state.dirs.clone();
    blocking(move || {
        let layout = layout::load(&dirs.networks_file(), root.path());
        let Some(server) = layout.servers.iter().find(|s| s.name == name) else {
            crate::error::bail!(NotFound, "The dev network has no server \"{name}\"");
        };
        let project = layout::resolve_project(root.path(), &server.project)?;
        trust::set(&dirs.trust_file(), &project, true)
    })
    .await
}

/// The proxy and the other servers of the network last started.
#[tauri::command]
#[specta::specta]
pub async fn network_members(state: State<'_, AppState>) -> Result<Vec<NetworkMember>> {
    Ok(state.network.members())
}

/// Runs a console command on the network's member [name] (`proxy`, or a server).
#[tauri::command]
#[specta::specta]
pub async fn network_command(state: State<'_, AppState>, name: String, line: String) -> Result<()> {
    state.network.command(&name, &line).await
}
