use tauri::State;

use super::blocking;
use crate::app::test_runner::{self, TestReport};
use crate::error::{Error, ErrorCode, Result};
use crate::minecraft::cache;
use crate::server::java;
use crate::state::AppState;

/// Runs the open project's script tests (`*_test.lua`: the runner's text says what they
/// are), those whose "file: name" contains [filter] when given, with the editor's Java and the
/// runner jar it carries, on the game data of the project's Minecraft version from the per-user
/// cache (the dev server's export): without it the tests don't run, and the error says how to
/// get it. Each test runs on a fake server of its own; nothing touches the dev
/// server or the project's files. The project runs its scripts, so it has to be trusted.
///
/// With [member], the tests are another project's of the open project's workspace (its path
/// there, `servers/chess`), on its own Minecraft version's game data; it has to be trusted too
/// (trusting the workspace trusts it).
#[tauri::command]
#[specta::specta]
pub async fn tests_run(
    state: State<'_, AppState>,
    filter: Option<String>,
    member: Option<String>,
) -> Result<TestReport> {
    let (root, minecraft) = match member {
        None => (
            state.trusted_root()?,
            state.opened().and_then(|opened| opened.minecraft),
        ),
        Some(member) => {
            let root = super::workspace::member_root(&state, &member)?;
            let opened = crate::project::inspect(root.path())?;
            if !crate::trust::is_trusted(&state.dirs.trust_file(), root.path()) {
                return Err(crate::state::untrusted(&opened.info.name));
            }
            (root, opened.minecraft)
        }
    };
    let minecraft = minecraft
        .ok_or_else(|| {
            Error::new(
                ErrorCode::Invalid,
                "netherforge.json doesn't name a Minecraft release, so there is no game data to test on",
            )
        })?;
    let dirs = state.dirs.clone();
    let game_data = blocking({
        let minecraft = minecraft.clone();
        move || cache::game_data_file(&dirs, &minecraft)
    })
    .await?
    .ok_or_else(|| {
        Error::new(
            ErrorCode::Unavailable,
            format!(
                "Running tests needs the game data of Minecraft {minecraft}, which isn't exported yet. Start the dev server once for this project (Run in the toolbar) and run the tests again"
            ),
        )
    })?;
    let jar = test_runner::find(&state.tools.test_runner_dirs).ok_or_else(|| {
        Error::new(
            ErrorCode::Unavailable,
            "This editor has no test runner (NetherForgeTest-<version>.jar)",
        )
    })?;
    let (java, _) = java::find(&state.tools.java)
        .await
        .ok_or_else(|| {
            Error::new(
                ErrorCode::Unavailable,
                "Running tests needs Java 25: none was found. Starting the dev server once downloads one",
            )
        })?;
    test_runner::run(
        &java,
        &jar,
        root.path(),
        &state.dirs.packages(),
        &game_data,
        filter.as_deref(),
    )
    .await
}
