use std::collections::BTreeMap;
use std::path::PathBuf;

use tauri::{AppHandle, Runtime, State};

use super::blocking;
use crate::app::events::{EventSink, FsChangedEvent};
use crate::error::Result;
use crate::project::{self, ProjectInfo, RecentProject};
use crate::settings;
use crate::state::{AppState, OpenProject};
use crate::trust;
use crate::watcher;
use crate::workspace;

#[tauri::command]
#[specta::specta]
pub async fn project_recent(state: State<'_, AppState>) -> Result<Vec<RecentProject>> {
    let list: Vec<RecentProject> = settings::load(&state.dirs.recent_file());
    Ok(project::existing_recent(list))
}

#[tauri::command]
#[specta::specta]
pub async fn project_open<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    root: String,
) -> Result<ProjectInfo> {
    open(app, &state, PathBuf::from(root)).await
}

#[tauri::command]
#[specta::specta]
pub async fn project_create<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    root: String,
    files: BTreeMap<String, String>,
) -> Result<ProjectInfo> {
    let path = PathBuf::from(&root);
    let file = state.dirs.trust_file();
    // Made here by the user: theirs to run.
    blocking(move || {
        project::create(&path, &files)?;
        trust::set(&file, crate::fs::ProjectRoot::new(&path)?.path(), true)
    })
    .await?;
    open(app, &state, PathBuf::from(root)).await
}

#[tauri::command]
#[specta::specta]
pub async fn project_close(state: State<'_, AppState>) -> Result<()> {
    state.close_project().await;
    Ok(())
}

/// Trusts the open project (or, `false`, stops trusting it, stopping its dev
/// server and lua-language-server), and answers it as opened.
#[tauri::command]
#[specta::specta]
pub async fn project_trust(state: State<'_, AppState>, trusted: bool) -> Result<ProjectInfo> {
    let root = state.root()?;
    let file = state.dirs.trust_file();
    let path = root.path().to_path_buf();
    blocking(move || trust::set(&file, &path, trusted)).await?;
    if !trusted {
        state.luals.stop();
        state.stop_servers().await?;
    }
    state.set_trusted(trusted);
    state.opened().map(|opened| opened.info).ok_or_else(|| {
        crate::error::Error::new(crate::error::ErrorCode::NoProject, "No project is open")
    })
}

/// For a workspace's own folder (a `netherforge-workspace.json` and no `netherforge.json`), the
/// project to open instead ([workspace::entry_project]); None for any other folder.
fn workspace_entry(root: &std::path::Path) -> Result<Option<PathBuf>> {
    if root.join(project::MANIFEST).is_file() || !root.join(workspace::FILE).is_file() {
        return Ok(None);
    }
    let info = workspace::read(root, false).ok_or_else(|| {
        crate::error::Error::new(
            crate::error::ErrorCode::Invalid,
            format!("{} couldn't be read", root.join(workspace::FILE).display()),
        )
    })?;
    let member = workspace::entry_project(&info).ok_or_else(|| {
        crate::error::Error::new(
            crate::error::ErrorCode::Invalid,
            format!(
                "The workspace {} has no projects yet: name some in its {}",
                info.name,
                workspace::FILE
            ),
        )
    })?;
    Ok(Some(PathBuf::from(&member.root)))
}

async fn open<R: Runtime>(
    app: AppHandle<R>,
    state: &AppState,
    root: PathBuf,
) -> Result<ProjectInfo> {
    let file = state.dirs.trust_file();
    let mut opened = blocking(move || {
        let root = workspace_entry(&root)?.unwrap_or(root);
        let mut opened = project::inspect(&root)?;
        opened.info.trusted = trust::is_trusted(&file, opened.root.path());
        opened.info.workspace = workspace::find(opened.root.path(), |folder| {
            trust::is_workspace_trusted(&file, folder)
        });
        Ok(opened)
    })
    .await?;
    // Re-opening the open project (a webview reload) keeps its watcher and
    // server; opening another one stops them first.
    if let Some(current) = state.opened()
        && current.root.path() == opened.root.path()
    {
        opened.info.trusted = current.info.trusted;
        return Ok(opened.info);
    }
    // Another project of the same workspace while its network runs: the network keeps running,
    // and the editor attaches to the server that runs this one (or to none).
    let workspace_of = |info: &ProjectInfo| info.workspace.as_ref().map(|w| w.root.clone());
    let same_workspace = workspace_of(&opened.info).is_some()
        && state.opened().map(|c| workspace_of(&c.info)) == Some(workspace_of(&opened.info));
    let keep_network = same_workspace && state.network.is_running_any();
    if keep_network {
        state.luals.stop();
    } else {
        state.close_project().await;
    }

    let sink: std::sync::Arc<dyn EventSink> = std::sync::Arc::new(app);
    let watch = watcher::watch(opened.root.path(), move |batch| {
        let event = FsChangedEvent {
            paths: batch.paths,
            rescan: batch.rescan,
        };
        sink.emit(&event);
    })?;

    let recent_file = state.dirs.recent_file();
    let recent: Vec<RecentProject> = settings::load(&recent_file);
    let recent = project::add_recent(recent, RecentProject::from(&opened));
    if let Err(error) = settings::save(&recent_file, &recent) {
        eprintln!("[netherforge] couldn't save the recent list: {error}");
    }

    state.server.set_target(opened.minecraft.clone());
    let info = opened.info.clone();
    let focused = opened.root.path().to_path_buf();
    *state.project.lock().unwrap() = Some(OpenProject {
        opened,
        _watch: watch,
    });
    if keep_network {
        let members = state.network.members();
        state
            .network
            .attach(crate::server::network::server_running(&members, &focused));
    }
    state.refresh_agents();
    Ok(info)
}
