//! The NetherForge editor's backend: project files, the file watcher, the dev
//! server and its bridge, and Minecraft installs. The UI's bindings are
//! generated from `commands::bindings`; the tauri-backend skill explains how
//! the pieces fit.

pub mod app;
pub mod bridge;
pub mod commands;
pub mod error;
pub mod fs;
pub mod luals;
pub mod mcp;
pub mod minecraft;
pub mod platform;
pub mod project;
mod protocols;
pub mod server;
pub mod settings;
pub mod state;
#[cfg(test)]
mod testing;
pub mod trust;
pub mod video;
pub mod watcher;
pub mod workspace;

use std::sync::Arc;

use tauri::{Manager, RunEvent};

use app::dirs::AppDirs;
use protocols::{asset_response, project_response};
use state::AppState;

/// The app's context (config, assets, the resolved capabilities), for the
/// app and for the contract suite, which checks commands against the same
/// capability on Tauri's mock runtime.
pub(crate) fn context<R: tauri::Runtime>() -> tauri::Context<R> {
    tauri::generate_context!()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let dirs = AppDirs::from_env().expect("NetherForge needs a config and a data folder");
    let minecraft_root = dirs.minecraft();
    let packages_root = dirs.packages();
    let commands = commands::bindings::builder();
    let invoke_handler = commands.invoke_handler();

    // The menu bar is the UI's (src/workbench/menus/): it replaces Tauri's default at start.
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        // Auto-update: the release skill says where the key and latest.json come from.
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .register_asynchronous_uri_scheme_protocol("nfasset", move |_ctx, request, responder| {
            let root = minecraft_root.clone();
            let path = request.uri().path().to_string();
            tauri::async_runtime::spawn_blocking(move || {
                responder.respond(asset_response(&root, &path));
            });
        })
        .register_asynchronous_uri_scheme_protocol("nfproject", move |ctx, request, responder| {
            let state = ctx.app_handle().try_state::<AppState>();
            let root = state.as_ref().and_then(|state| state.root().ok());
            let cache = packages_root.clone();
            let path = request.uri().path().to_string();
            tauri::async_runtime::spawn_blocking(move || {
                responder.respond(project_response(root.as_ref(), &cache, &path));
            });
        })
        .setup(move |app| {
            commands.mount_events(app);
            let sink: Arc<dyn app::events::EventSink> = Arc::new(app.handle().clone());
            let resources = app.path().resource_dir().ok();
            let tools = app::tools::Tools::for_app(resources.as_deref(), &dirs);
            // The `netherforge test` command finds the runner jar in the data folder: a copy of the one this editor carries.
            if let Some(jar) = app::test_runner::find(&tools.test_runner_dirs) {
                let _ = app::test_runner::install_for_cli(&jar, &dirs.test_runner());
            }
            let state = AppState::new(dirs.clone(), tools, sink);
            // The MCP server for coding agents, as settings say. A taken port shows in Settings.
            let mcp = state.mcp.clone();
            let saved: settings::Settings = settings::load(&dirs.settings_file());
            tauri::async_runtime::spawn(async move {
                mcp.apply(saved.mcp_enabled, saved.mcp_port).await;
            });
            app.manage(state);
            Ok(())
        })
        .invoke_handler(invoke_handler)
        .build(context())
        .expect("error while building NetherForge");

    app.run(|handle, event| {
        // The dev server and lua-language-server must not outlive the editor.
        if let RunEvent::Exit = event
            && let Some(state) = handle.try_state::<AppState>()
        {
            state.luals.stop();
            tauri::async_runtime::block_on(state.stop_servers()).ok();
        }
    });
}
