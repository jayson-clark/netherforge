use serde::Serialize;
use tauri::State;

use super::blocking;
use crate::error::{Error, ErrorCode, Result};
use crate::luals;
use crate::state::AppState;

/// NetherForge's LuaLS plugin (`require` as the server resolves it), written
/// into the data folder for LuaLS to load.
const PLUGIN: &str = include_str!("../luals/plugin.lua");

#[derive(Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct LualsStarted {
    generation: u64,
    /// The plugin's absolute path, for the client's `Lua.runtime.plugin`.
    plugin: String,
}

/// Starts lua-language-server for the open project (restarting one that runs).
#[tauri::command]
#[specta::specta]
pub async fn luals_start(state: State<'_, AppState>) -> Result<LualsStarted> {
    // LuaLS reads the project's .luarc.json, which can name a plugin for it to run.
    let root = state.trusted_root()?;
    let program = luals::locate(&state.tools.luals).ok_or_else(|| {
        Error::new(
            ErrorCode::Unavailable,
            "lua-language-server isn't installed with this editor (in a dev build, run node tools/luals.mjs)",
        )
    })?;
    let plugin = state.dirs.luals().join("plugin.lua");
    let written = plugin.clone();
    blocking(move || {
        std::fs::create_dir_all(written.parent().unwrap())?;
        if std::fs::read_to_string(&written).ok().as_deref() != Some(PLUGIN) {
            std::fs::write(&written, PLUGIN)?;
        }
        Ok(())
    })
    .await?;
    let generation = state.luals.start(&program, root.path())?;
    Ok(LualsStarted {
        generation,
        plugin: plugin.to_string_lossy().into_owned(),
    })
}

/// Sends one JSON-RPC message to the server of [generation].
#[tauri::command]
#[specta::specta]
pub async fn luals_send(
    state: State<'_, AppState>,
    generation: u64,
    message: String,
) -> Result<()> {
    state.luals.send(generation, message)
}

#[tauri::command]
#[specta::specta]
pub async fn luals_stop(state: State<'_, AppState>) -> Result<()> {
    state.luals.stop();
    Ok(())
}
