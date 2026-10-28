//! App-level pieces: where the editor keeps its files, events, bundled plugin
//! jars, the script test runner, the tools the editor runs and `app_info`.

pub mod dirs;
pub mod events;
pub mod plugins;
pub mod test_runner;
pub mod tools;

use serde::Serialize;

use crate::platform::Os;

#[derive(Debug, Clone, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: String,
    /// Minecraft versions the available plugin adapters support, newest first.
    pub minecraft_versions: Vec<String>,
    pub os: Os,
}
