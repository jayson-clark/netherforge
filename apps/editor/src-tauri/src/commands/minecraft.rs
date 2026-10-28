use std::path::PathBuf;
use std::sync::Arc;

use std::collections::BTreeMap;
use tauri::{AppHandle, Runtime, State};

use super::{Json, blocking};
use crate::app::events::{EventSink, ImportProgress};
use crate::error::{Result, bail};
use crate::minecraft::cache::{self, CacheStatus};
use crate::minecraft::import;
use crate::minecraft::installs::{self, MinecraftInstall};
use crate::minecraft::launcher::{self, LauncherEnv};
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn mc_installs(state: State<'_, AppState>) -> Result<Vec<MinecraftInstall>> {
    let env = state.tools.installs.clone();
    blocking(move || Ok(env.map(|env| installs::detect(&env)).unwrap_or_default())).await
}

#[tauri::command]
#[specta::specta]
pub async fn mc_cache_status(state: State<'_, AppState>, version: String) -> Result<CacheStatus> {
    cache::status(&state.dirs, &version)
}

#[tauri::command]
#[specta::specta]
pub async fn mc_import_client<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    version: String,
    jar: String,
) -> Result<()> {
    cache::check_version(&version)?;
    let jar = PathBuf::from(jar);
    if !jar.is_file() {
        bail!(NotFound, "{} doesn't exist", jar.display());
    }
    let dirs = state.dirs.clone();
    let sink: Arc<dyn EventSink> = Arc::new(app);
    let progress_sink = sink.clone();
    let progress_version = version.clone();
    let client_dir = dirs.client_dir(&version);
    let import_version = version.clone();
    blocking(move || {
        import::import_client(&jar, &import_version, &client_dir, |done, total| {
            progress_sink.emit(&ImportProgress {
                version: progress_version.clone(),
                done: done as u32,
                total: total as u32,
            });
        })
    })
    .await?;
    sink.emit(&cache::status(&dirs, &version)?);
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn mc_game_data(state: State<'_, AppState>, version: String) -> Result<Option<Json>> {
    let dirs = state.dirs.clone();
    let data = blocking(move || cache::read_game_data(&dirs, &version)).await?;
    Ok(data.map(Json))
}

#[tauri::command]
#[specta::specta]
pub async fn mc_glyph_advances(
    state: State<'_, AppState>,
    version: String,
) -> Result<Option<BTreeMap<String, u32>>> {
    let dirs = state.dirs.clone();
    let advances = blocking(move || cache::glyph_advances(&dirs, &version)).await?;
    Ok(advances.map(serde_json::from_value).transpose()?)
}

#[tauri::command]
#[specta::specta]
pub async fn mc_open_launcher() -> Result<()> {
    blocking(|| {
        let env = LauncherEnv::current().ok_or_else(|| {
            crate::error::Error::new(
                crate::error::ErrorCode::Unavailable,
                "This OS has no home folder",
            )
        })?;
        launcher::open(&env)
    })
    .await
}
