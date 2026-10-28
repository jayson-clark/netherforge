//! The open project's workspace (`netherforge-workspace.json` above it; see `workspace`): trusting
//! it, and reading and writing its file, which format (in the UI) parses, checks and writes
//! canonically.

use std::path::PathBuf;

use tauri::State;

use super::blocking;
use crate::error::{Error, ErrorCode, Result};
use crate::project::ProjectInfo;
use crate::server::network::layout::{self, NetworkLayout};
use crate::state::AppState;
use crate::trust;
use crate::workspace::{self, WorkspaceInfo};

fn workspace_root(state: &AppState) -> Result<PathBuf> {
    state
        .opened()
        .ok_or_else(|| Error::new(ErrorCode::NoProject, "No project is open"))?
        .info
        .workspace
        .map(|w| PathBuf::from(w.root))
        .ok_or_else(|| Error::new(ErrorCode::NotFound, "The open project isn't in a workspace"))
}

/// Trusts the open project's workspace, and so every project in it (or, `false`, stops trusting
/// it, stopping the dev server and lua-language-server), and answers the project as opened.
#[tauri::command]
#[specta::specta]
pub async fn workspace_trust(state: State<'_, AppState>, trusted: bool) -> Result<ProjectInfo> {
    let root = workspace_root(&state)?;
    let file = state.dirs.trust_file();
    let project = state.root()?.path().to_path_buf();
    let (project_trusted, info) = blocking(move || {
        trust::set_workspace(&file, &root, trusted)?;
        let info = workspace::read(&root, trusted);
        Ok((trust::is_trusted(&file, &project), info))
    })
    .await?;
    if !project_trusted {
        state.luals.stop();
        state.stop_servers().await?;
    }
    state.set_workspace(info);
    state.set_trusted(project_trusted);
    state
        .opened()
        .map(|opened| opened.info)
        .ok_or_else(|| Error::new(ErrorCode::NoProject, "No project is open"))
}

/// The workspace file's text, for format to read.
#[tauri::command]
#[specta::specta]
pub async fn workspace_read(state: State<'_, AppState>) -> Result<String> {
    let root = workspace_root(&state)?;
    blocking(move || workspace::read_text(&root)).await
}

/// Writes the workspace file (format's canonical text), and answers the workspace as it reads now.
#[tauri::command]
#[specta::specta]
pub async fn workspace_write(state: State<'_, AppState>, text: String) -> Result<WorkspaceInfo> {
    let root = workspace_root(&state)?;
    let file = state.dirs.trust_file();
    let info = blocking(move || {
        workspace::write_text(&root, &text)?;
        Ok(workspace::read(
            &root,
            trust::is_workspace_trusted(&file, &root),
        ))
    })
    .await?
    .ok_or_else(|| Error::msg("The workspace file couldn't be read back"))?;
    state.set_workspace(Some(info.clone()));
    Ok(info)
}

/// Writes the workspace file's JSON Schema beside it (`.netherforge/schema/workspace.schema.json`).
#[tauri::command]
#[specta::specta]
pub async fn workspace_write_schema(state: State<'_, AppState>, text: String) -> Result<()> {
    let root = workspace_root(&state)?;
    blocking(move || workspace::write_schema(&root, &text)).await
}

/// The open project's workspace as it reads now (its members may have changed on disk), or null.
#[tauri::command]
#[specta::specta]
pub async fn workspace_info(state: State<'_, AppState>) -> Result<Option<WorkspaceInfo>> {
    let Ok(root) = workspace_root(&state) else {
        return Ok(None);
    };
    let file = state.dirs.trust_file();
    let info = blocking(move || {
        Ok(workspace::read(
            &root,
            trust::is_workspace_trusted(&file, &root),
        ))
    })
    .await?;
    state.set_workspace(info.clone());
    Ok(info)
}

/// Whether Start runs the workspace's dev network: this computer's choice, not the file's.
#[tauri::command]
#[specta::specta]
pub async fn workspace_network_enabled(state: State<'_, AppState>) -> Result<bool> {
    let root = workspace_root(&state)?;
    let file = state.dirs.networks_file();
    blocking(move || Ok(layout::load(&file, &root).enabled)).await
}

/// Turns the workspace's dev network on or off for the next start, on this computer.
#[tauri::command]
#[specta::specta]
pub async fn workspace_set_network_enabled(
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<()> {
    let root = workspace_root(&state)?;
    let file = state.dirs.networks_file();
    blocking(move || {
        let current = layout::load(&file, &root);
        layout::save(&file, &root, &NetworkLayout { enabled, ..current })
    })
    .await
}

/// Makes a new project of the workspace at [path] (relative to the workspace's folder, scoped as a
/// project path is: `packages/chess`) from [files], without opening it, and answers the workspace
/// as it reads now. The folder must be missing or empty. The workspace file names it only if the
/// UI wrote it there (`projects`); it's trusted as everything in the workspace is.
#[tauri::command]
#[specta::specta]
pub async fn workspace_create_member(
    state: State<'_, AppState>,
    path: String,
    files: std::collections::BTreeMap<String, String>,
) -> Result<WorkspaceInfo> {
    let root = workspace_root(&state)?;
    let file = state.dirs.trust_file();
    let info = blocking(move || {
        if !crate::fs::is_writable(&path) {
            return Err(Error::new(
                ErrorCode::ReadOnly,
                format!("{path} is the editor's or git's: a project can't go there"),
            ));
        }
        let target = crate::fs::ProjectRoot::new(&root)?.resolve(&path)?;
        crate::project::create(&target, &files)?;
        Ok(workspace::read(
            &root,
            trust::is_workspace_trusted(&file, &root),
        ))
    })
    .await?
    .ok_or_else(|| Error::msg("The workspace file couldn't be read back"))?;
    state.set_workspace(Some(info.clone()));
    Ok(info)
}

/// The member [member] (its path in the workspace, `packages/chess`) of the open project's
/// workspace, as a project root: every path inside it is scoped as a project's.
pub(crate) fn member_root(state: &AppState, member: &str) -> Result<crate::fs::ProjectRoot> {
    let workspace = state
        .workspace()
        .ok_or_else(|| Error::new(ErrorCode::NotFound, "The open project isn't in a workspace"))?;
    let found = workspace
        .members
        .iter()
        .find(|m| m.path == member)
        .ok_or_else(|| {
            Error::new(
                ErrorCode::NotFound,
                format!("\"{member}\" isn't one of the workspace's projects"),
            )
        })?;
    crate::fs::ProjectRoot::new(std::path::Path::new(&found.root))
}

/// Every file of a workspace member, with its SHA-256, as `package_files` lists a package's.
#[tauri::command]
#[specta::specta]
pub async fn workspace_member_files(
    state: State<'_, AppState>,
    member: String,
) -> Result<crate::fs::package::PackageFiles> {
    let root = member_root(&state, &member)?;
    blocking(move || crate::fs::package::files(&root)).await
}

/// A file of a workspace member.
#[tauri::command]
#[specta::specta]
pub async fn workspace_member_read_text(
    state: State<'_, AppState>,
    member: String,
    path: String,
) -> Result<String> {
    let root = member_root(&state, &member)?;
    blocking(move || crate::fs::read_text(&root, &path)).await
}

/// Writes a file of a workspace member (a package edited in place from a project that depends on
/// it, or a dependent's lock), as `fs_write_text` writes the open project's.
#[tauri::command]
#[specta::specta]
pub async fn workspace_member_write_text(
    state: State<'_, AppState>,
    member: String,
    path: String,
    text: String,
) -> Result<()> {
    let root = member_root(&state, &member)?;
    blocking(move || crate::fs::write_text(&root, &path, &text)).await
}
