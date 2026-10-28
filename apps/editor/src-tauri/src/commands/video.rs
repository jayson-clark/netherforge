use std::sync::Arc;

use tauri::{AppHandle, Runtime, State};

use super::blocking;
use crate::app::events::EventSink;
use crate::error::{Error, ErrorCode, Result};
use crate::minecraft::cache;
use crate::settings::{self, Settings};
use crate::state::AppState;
use crate::video::{self, VideoConverted, VideoInfo};

/// Converts the video in [folder] (`videos/trailer`) for screens: `video.nfv` and its sound, reporting
/// `video://convert-progress` as it goes. ffmpeg (the user's `ffmpegPath`, or the first on `PATH`) reads the
/// project's source file, so the project must be trusted; the colours are the game data's of the project's
/// Minecraft version, which the dev server exports the first time it starts.
#[tauri::command]
#[specta::specta]
pub async fn video_convert<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    folder: String,
) -> Result<VideoConverted> {
    let root = state.trusted_root()?;
    let minecraft = state.opened().and_then(|opened| opened.minecraft).ok_or_else(|| {
        Error::new(
            ErrorCode::Invalid,
            "netherforge.json doesn't name a Minecraft release, so there are no map colours to convert to",
        )
    })?;
    let settings: Settings = settings::load(&state.dirs.settings_file());
    let ffmpeg = video::find_ffmpeg(&settings.ffmpeg_path).ok_or_else(|| {
        Error::new(
            ErrorCode::Unavailable,
            if settings.ffmpeg_path.trim().is_empty() {
                "Converting a video needs ffmpeg, and there's none on PATH: install it, or set where it is in Settings".to_string()
            } else {
                format!("Converting a video needs ffmpeg, and there's none at {}: fix it in Settings", settings.ffmpeg_path)
            },
        )
    })?;
    let dirs = state.dirs.clone();
    let sink: Arc<dyn EventSink> = Arc::new(app);
    blocking(move || {
        let file = cache::game_data_file(&dirs, &minecraft)?.ok_or_else(|| {
            Error::new(
                ErrorCode::Unavailable,
                format!(
                    "Converting a video needs Minecraft {minecraft}'s map colours, which aren't exported yet. Start the dev server once for this project (Run in the toolbar) and convert again"
                ),
            )
        })?;
        let json = std::fs::read_to_string(&file)?;
        let palette = video::palette_from_game_data(&json).ok_or_else(|| {
            Error::new(
                ErrorCode::Unavailable,
                format!("The game data of Minecraft {minecraft} has no map colours: start the dev server again to export them"),
            )
        })?;
        video::convert(&root, &folder, &ffmpeg, palette, &mut |progress| sink.emit(&progress))
    })
    .await
}

/// What the converted frames of the video in [folder] say, and whether its source changed since.
#[tauri::command]
#[specta::specta]
pub async fn video_info(state: State<'_, AppState>, folder: String) -> Result<VideoInfo> {
    let root = state.root()?;
    blocking(move || video::info(&root, &folder)).await
}
