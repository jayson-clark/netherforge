use tauri::{AppHandle, Runtime, State};

use super::Json;
use crate::app::{AppInfo, plugins};
use crate::error::Result;
use crate::mcp::McpStatus;
use crate::platform::Os;
use crate::settings::{self, Settings};
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn app_info<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> Result<AppInfo> {
    let jars = plugins::scan(&state.tools.plugin_dirs);
    Ok(AppInfo {
        version: app.package_info().version.to_string(),
        minecraft_versions: jars.into_iter().map(|j| j.minecraft).collect(),
        os: Os::current(),
    })
}

#[tauri::command]
#[specta::specta]
pub async fn settings_get(state: State<'_, AppState>) -> Result<Settings> {
    Ok(settings::load(&state.dirs.settings_file()))
}

#[tauri::command]
#[specta::specta]
pub async fn settings_set(state: State<'_, AppState>, settings: Settings) -> Result<()> {
    settings.validate()?;
    let before: Settings = settings::load(&state.dirs.settings_file());
    settings::save(&state.dirs.settings_file(), &settings)?;
    let status = state.mcp.status();
    let mcp_changed =
        (before.mcp_enabled, before.mcp_port) != (settings.mcp_enabled, settings.mcp_port);
    // Also retry a port that was taken at startup.
    if mcp_changed || (settings.mcp_enabled && status.url.is_none()) {
        state
            .mcp
            .apply(settings.mcp_enabled, settings.mcp_port)
            .await;
    }
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn mcp_status(state: State<'_, AppState>) -> Result<McpStatus> {
    Ok(state.mcp.status())
}

/// A JSON-RPC message from the UI's MCP server. A response goes back to the
/// agent whose request it answers; anything else can't reach an agent over
/// stateless HTTP and is dropped (see `mcp::UiPipe::deliver`).
#[tauri::command]
#[specta::specta]
pub async fn mcp_send(state: State<'_, AppState>, message: Json) -> Result<()> {
    state.mcp.pipe.deliver(message.0);
    Ok(())
}
