use tauri::State;

use super::blocking;
use crate::error::Result;
use crate::fs::git;
use crate::fs::package::{self, PackageFiles};
use crate::fs::{self, FileEntry};
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn fs_list(state: State<'_, AppState>) -> Result<Vec<FileEntry>> {
    let root = state.root()?;
    blocking(move || fs::list(&root)).await
}

#[tauri::command]
#[specta::specta]
pub async fn fs_read_text(state: State<'_, AppState>, path: String) -> Result<String> {
    let root = state.root()?;
    blocking(move || fs::read_text(&root, &path)).await
}

#[tauri::command]
#[specta::specta]
pub async fn fs_write_text(state: State<'_, AppState>, path: String, text: String) -> Result<()> {
    let root = state.root()?;
    blocking(move || fs::write_text(&root, &path, &text)).await
}

#[tauri::command]
#[specta::specta]
pub async fn fs_write_bytes(
    state: State<'_, AppState>,
    path: String,
    bytes: Vec<u8>,
) -> Result<()> {
    let root = state.root()?;
    blocking(move || fs::write_bytes(&root, &path, &bytes)).await
}

#[tauri::command]
#[specta::specta]
pub async fn fs_delete(state: State<'_, AppState>, path: String) -> Result<()> {
    let root = state.root()?;
    blocking(move || fs::delete(&root, &path)).await
}

#[tauri::command]
#[specta::specta]
pub async fn fs_rename(state: State<'_, AppState>, from: String, to: String) -> Result<()> {
    let root = state.root()?;
    blocking(move || fs::rename(&root, &from, &to)).await
}

#[tauri::command]
#[specta::specta]
pub async fn package_files(state: State<'_, AppState>, location: String) -> Result<PackageFiles> {
    let (root, cache) = (state.root()?, state.dirs.packages());
    blocking(move || package::files(&package::open(&root, &cache, &location)?)).await
}

#[tauri::command]
#[specta::specta]
pub async fn package_read_text(
    state: State<'_, AppState>,
    location: String,
    path: String,
) -> Result<String> {
    let (root, cache) = (state.root()?, state.dirs.packages());
    blocking(move || fs::read_text(&package::open(&root, &cache, &location)?, &path)).await
}

#[tauri::command]
#[specta::specta]
pub async fn package_copy(
    state: State<'_, AppState>,
    location: String,
    from: String,
    to: String,
) -> Result<()> {
    let (root, cache) = (state.root()?, state.dirs.packages());
    blocking(move || package::copy(&package::open(&root, &cache, &location)?, &root, &from, &to))
        .await
}

/// Fetches a git dependency into the package cache: [url] at [rev] (its
/// default branch when null), or exactly [commit] when the lock pins one (no
/// network once it's there). Answers the commit, whose files are then the
/// package at `git:<commit>`. Needs no open project: the cache is the user's.
#[tauri::command]
#[specta::specta]
pub async fn package_fetch_git(
    state: State<'_, AppState>,
    url: String,
    rev: Option<String>,
    commit: Option<String>,
) -> Result<String> {
    let cache = state.dirs.packages();
    // A checkout already in the cache is read as it is; reaching a repository
    // the project names (the network, git) needs the project trusted.
    let allowed = state.trusted_root().map(|_| ());
    blocking(move || git::fetch(&cache, &url, rev.as_deref(), commit.as_deref(), allowed)).await
}
