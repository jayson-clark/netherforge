use tauri::{AppHandle, Runtime, State};

use super::blocking;
use std::sync::Arc;

use crate::app::events::EventSink;
use crate::error::{Error, ErrorCode, Result, bail};
use crate::fs::{ProjectRoot, map};
use crate::project;
use crate::server::network;
use crate::server::network::layout;
use crate::server::process::{Role, StartConfig};
use crate::server::{EulaStatus, ServerState};
use crate::settings::{self, EULA_URL, EulaRecord, Settings};
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn server_state(state: State<'_, AppState>) -> Result<ServerState> {
    // A workspace's network: the server the editor is attached to is the editor's server.
    Ok(match state.network.attached() {
        Some(member) => member.state(),
        None => state.server.state(),
    })
}

#[tauri::command]
#[specta::specta]
pub async fn server_eula_status(state: State<'_, AppState>) -> Result<EulaStatus> {
    let record: EulaRecord = settings::load(&state.dirs.eula_file());
    Ok(EulaStatus {
        accepted: record.accepted,
        url: EULA_URL.into(),
    })
}

/// Records that the user accepted the EULA in the UI. Each start writes
/// `eula.txt` into the server's folder from this record.
#[tauri::command]
#[specta::specta]
pub async fn server_eula_accept(state: State<'_, AppState>) -> Result<()> {
    let record = EulaRecord {
        accepted: true,
        accepted_at: Some(project::now_iso()),
    };
    settings::save(&state.dirs.eula_file(), &record)
}

#[tauri::command]
#[specta::specta]
pub async fn server_start(state: State<'_, AppState>) -> Result<()> {
    // The server runs the project's scripts: only a project the user trusts.
    let root = state.trusted_root()?;
    let dirs = state.dirs.clone();
    let tools = state.tools.clone();
    if let Some(workspace) = state.workspace()
        && workspace.network.is_some()
        && layout::load(&dirs.networks_file(), std::path::Path::new(&workspace.root)).enabled
    {
        return start_workspace(&state, workspace, root.path().to_path_buf()).await;
    }
    // Re-read the manifest: the target version may have changed since opening.
    let (opened, settings, eula, network) = blocking(move || {
        let settings: Settings = settings::load(&dirs.settings_file());
        let eula: EulaRecord = settings::load(&dirs.eula_file());
        // The file may have been edited by hand: the allowlist applies at start too.
        settings.validate()?;
        let opened = project::inspect(root.path())?;
        // A network is planned (and refused) whole before anything starts.
        let layout = layout::load(&dirs.networks_file(), root.path());
        let network = if layout.enabled {
            Some(network::plan(
                &dirs,
                &tools,
                root.path(),
                &opened.info.name,
                &layout,
                &settings,
                eula.accepted,
            )?)
        } else {
            None
        };
        Ok((opened, settings, eula, network))
    })
    .await?;
    let (membership, members) = match network {
        Some(plan) => (Some(plan.main), plan.members),
        None => (None, Vec::new()),
    };
    let config = StartConfig {
        project_root: opened.root.path().to_path_buf(),
        project_name: opened.info.name.clone(),
        minecraft: opened.minecraft.clone(),
        settings,
        eula_accepted: eula.accepted,
        tools: state.tools.clone(),
        role: Role::Main(membership),
    };
    if state.network.is_running_any() {
        bail!(Busy, "The dev network is already running");
    }
    state.server.start(config).await?;
    if members.is_empty() {
        state.network.clear().await;
    } else {
        state.network.start(members, None).await;
    }
    Ok(())
}

/// Starts the workspace's dev network (its file's `network`), attached to the server that runs the
/// open project [focused].
async fn start_workspace(
    state: &AppState,
    workspace: crate::workspace::WorkspaceInfo,
    focused: std::path::PathBuf,
) -> Result<()> {
    if state.server.state().phase.is_active() || state.network.is_running_any() {
        bail!(Busy, "The dev server is already running");
    }
    let dirs = state.dirs.clone();
    let tools = state.tools.clone();
    let plan = blocking(move || {
        let settings: Settings = settings::load(&dirs.settings_file());
        let eula: EulaRecord = settings::load(&dirs.eula_file());
        settings.validate()?;
        if !eula.accepted {
            bail!(
                EulaRequired,
                "Accept the Minecraft EULA ({EULA_URL}) before starting the dev server"
            );
        }
        network::plan_workspace(
            &dirs,
            &tools,
            &workspace,
            &focused,
            &settings,
            eula.accepted,
        )
    })
    .await?;
    let attach = plan.attach.clone();
    state.network.start(plan.members, plan.attach).await;
    state.network.attach(attach);
    Ok(())
}

/// Stops the dev server, and the rest of its network when it runs one.
#[tauri::command]
#[specta::specta]
pub async fn server_stop(state: State<'_, AppState>) -> Result<()> {
    state.stop_servers().await
}

#[tauri::command]
#[specta::specta]
pub async fn server_command(state: State<'_, AppState>, line: String) -> Result<()> {
    match state.network.attached() {
        Some(member) => member.command(&line).await,
        None => state.server.command(&line).await,
    }
}

/// Relays one JSON-RPC message (or batch) from the UI's bridge connection to
/// the plugin; its answers come back as `bridge://message`.
#[tauri::command]
#[specta::specta]
pub async fn bridge_send(state: State<'_, AppState>, message: String) -> Result<()> {
    match state.network.attached() {
        Some(member) => member.bridge_send(&message),
        None => state.server.bridge_send(&message),
    }
}

/// Copies a dev-server world the plugin just saved (`save_world` answered
/// [level] and [dimension], relative to the server's folder) into the project
/// as `maps/<id>/`, reporting `map://capture-progress` as it goes.
#[tauri::command]
#[specta::specta]
pub async fn map_capture<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    level: String,
    dimension: String,
    id: String,
    replace: bool,
) -> Result<()> {
    let root = state.root()?;
    // The world is where the server the editor is attached to runs: a workspace network's own folder.
    let server_dir = state
        .network
        .attached()
        .and_then(|member| member.folder())
        .unwrap_or_else(|| state.dirs.server_dir(root.path()));
    let sink: Arc<dyn EventSink> = Arc::new(app);
    blocking(move || {
        let server = ProjectRoot::new(&server_dir).map_err(|_| {
            Error::new(
                ErrorCode::NotFound,
                "The dev server has no folder yet: start it once first",
            )
        })?;
        map::capture(
            &root,
            &server,
            &level,
            &dimension,
            &id,
            replace,
            &mut |progress| sink.emit(&progress),
        )
    })
    .await
}
