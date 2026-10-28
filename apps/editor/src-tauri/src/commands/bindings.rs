//! The one list of the app's commands and events, as tauri-specta sees them.
//! Everything else is derived from it:
//!
//! - the invoke handler (`lib.rs` hands [builder]'s to Tauri);
//! - the UI's typed bindings, `apps/editor/src/core/backend/generated/bindings.ts`
//!   (every command, event and payload type, with `Error`'s code);
//! - the permission that lets the window call them,
//!   `permissions/generated/commands.toml`, which `capabilities/default.json`
//!   grants as `commands`.
//!
//! Both files are committed. `pnpm generate` writes them
//! (`cargo run --example bindings`), and `pnpm lint`'s generated-file check
//! fails when they're stale. A new command is a `#[tauri::command]
//! #[specta::specta]` function added to [builder]'s list, then `pnpm generate`.

use std::path::Path;

use specta_typescript::Typescript;
use tauri_specta::{BuilderConfiguration, LanguageExt, collect_commands, collect_events};

use super::{app, fs, luals, minecraft, network, project, server, tests, video, workspace};
use crate::app::events::{BridgeMessage, FsChangedEvent, ImportProgress};
use crate::error::Error;
use crate::fs::map::CaptureProgress;
use crate::luals::{LualsExit, LualsMessage};
use crate::mcp::McpMessage;
use crate::minecraft::cache::CacheStatus;
use crate::server::network::{NetworkMember, NetworkMembers, NetworkOutputEvent};
use crate::server::{PrepareProgress, ServerOutputEvent, ServerState};
use crate::video::ConvertProgress;

/// Where [export] writes, relative to the repo root.
pub const BINDINGS: &str = "apps/editor/src/core/backend/generated/bindings.ts";
pub const PERMISSIONS: &str = "apps/editor/src-tauri/permissions/generated/commands.toml";

/// The builder for a runtime: the app's, or Tauri's mock one in tests. A
/// macro because `collect_commands!` can't name an outer generic parameter.
macro_rules! builder {
    ($runtime:ty) => {
        tauri_specta::Builder::<$runtime>::new()
            .commands(collect_commands![
                app::app_info::<$runtime>,
                app::settings_get,
                app::settings_set,
                app::mcp_status,
                app::mcp_send,
                project::project_recent,
                project::project_open::<$runtime>,
                project::project_create::<$runtime>,
                project::project_close,
                project::project_trust,
                workspace::workspace_trust,
                workspace::workspace_read,
                workspace::workspace_write,
                workspace::workspace_info,
                workspace::workspace_write_schema,
                workspace::workspace_member_files,
                workspace::workspace_member_read_text,
                workspace::workspace_member_write_text,
                workspace::workspace_create_member,
                workspace::workspace_network_enabled,
                workspace::workspace_set_network_enabled,
                fs::fs_list,
                fs::fs_read_text,
                fs::fs_write_text,
                fs::fs_write_bytes,
                fs::fs_delete,
                fs::fs_rename,
                fs::package_files,
                fs::package_read_text,
                fs::package_copy,
                fs::package_fetch_git,
                luals::luals_start,
                luals::luals_send,
                luals::luals_stop,
                server::server_state,
                server::server_eula_status,
                server::server_eula_accept,
                server::server_start,
                server::server_stop,
                server::server_command,
                server::bridge_send,
                server::map_capture::<$runtime>,
                network::network_layout,
                network::network_set_layout,
                network::network_check,
                network::network_trust,
                network::network_members,
                network::network_command,
                tests::tests_run,
                video::video_convert::<$runtime>,
                video::video_info,
                minecraft::mc_installs,
                minecraft::mc_cache_status,
                minecraft::mc_import_client::<$runtime>,
                minecraft::mc_game_data,
                minecraft::mc_glyph_advances,
                minecraft::mc_open_launcher,
            ])
            .events(collect_events![
                FsChangedEvent,
                ServerState,
                ServerOutputEvent,
                PrepareProgress,
                NetworkMembers,
                NetworkMember,
                NetworkOutputEvent,
                BridgeMessage,
                CaptureProgress,
                ConvertProgress,
                ImportProgress,
                CacheStatus,
                McpMessage,
                LualsMessage,
                LualsExit,
            ])
            // Rejections are `{ code, message }`, the same for every command.
            .typ::<Error>()
            // Sizes, timestamps and generations are u64 in Rust; JavaScript's
            // numbers hold them exactly up to 2^53, far past any value we send.
            .dangerously_cast_bigints_to_number()
            // A command throws its `Error` rather than returning a result union:
            // `TauriBackend` turns it into a `BackendError`.
            .error_handling(tauri_specta::ErrorHandlingMode::Throw)
    };
}

/// Every command and event, for the app.
pub fn builder() -> tauri_specta::Builder<tauri::Wry> {
    builder!(tauri::Wry)
}

/// The same, on Tauri's mock runtime, for the contract suite.
#[cfg(test)]
pub fn mock_builder() -> tauri_specta::Builder<tauri::test::MockRuntime> {
    builder!(tauri::test::MockRuntime)
}

const HEADER: &str = "// Generated from the editor's Rust commands by tauri-specta \
(apps/editor/src-tauri/src/commands/bindings.rs). Don't edit: run `pnpm generate`.";

/// Writes the bindings and the permission under [root], the repo root (or
/// a copy of the generated folders, for the generated-file check).
pub fn export(root: &Path) -> Result<(), String> {
    let builder = builder();
    let bindings = root.join(BINDINGS);
    let permissions = root.join(PERMISSIONS);
    for file in [&bindings, &permissions] {
        let dir = file.parent().unwrap();
        std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    }
    builder
        .export(Typescript::default().header(HEADER), &bindings)
        .map_err(|e| e.to_string())?;
    std::fs::write(&permissions, permission(&command_names()))
        .map_err(|e| format!("{}: {e}", permissions.display()))
}

/// Every command's name, sorted.
pub fn command_names() -> Vec<String> {
    names().0
}

/// Every event's name (`fs://changed`), sorted.
pub fn event_names() -> Vec<String> {
    names().1
}

fn names() -> (Vec<String>, Vec<String>) {
    let mut names = (Vec::new(), Vec::new());
    builder()
        .export(Names(&mut names), Path::new(""))
        .expect("collecting names can't fail");
    names.0.sort_unstable();
    names.1.sort_unstable();
    names
}

/// An "exporter" that only collects the command and event names: the
/// builder keeps its configuration private and hands it to exporters alone.
struct Names<'a>(&'a mut (Vec<String>, Vec<String>));

impl LanguageExt for Names<'_> {
    type Error = std::io::Error;

    fn export(self, cfg: &BuilderConfiguration, _: &Path) -> Result<(), Self::Error> {
        let (commands, events) = self.0;
        commands.extend(cfg.commands.iter().map(|c| c.name().to_string()));
        events.extend(cfg.events.keys().map(|name| name.to_string()));
        Ok(())
    }
}

/// One Tauri permission allowing every command.
fn permission(names: &[String]) -> String {
    let list = names
        .iter()
        .map(|name| format!("  \"{name}\",\n"))
        .collect::<String>();
    format!(
        "# Generated from the editor's command list (src/commands/bindings.rs). Don't edit: run `pnpm generate`.\n\
         \n\
         [[permission]]\n\
         identifier = \"commands\"\n\
         description = \"Every command the editor's backend registers.\"\n\
         commands.allow = [\n{list}]\n"
    )
}
