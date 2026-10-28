//! The Tauri commands: a thin layer that unpacks arguments, finds the open
//! project, and calls into the modules. [bindings] lists them all, and the
//! UI's typed calls are generated from that list.
//!
//! Every command is `async` so it runs on the async runtime, not the main
//! (UI) thread, and blocking work goes through [blocking]. A command that
//! needs the app takes `AppHandle<R>` for any `R: Runtime`, so the contract
//! suite can run it on Tauri's mock runtime.

pub mod app;
pub mod bindings;
#[cfg(test)]
mod contract;
pub mod fs;
pub mod luals;
pub mod minecraft;
pub mod network;
pub mod project;
pub mod server;
pub mod tests;
pub mod video;
pub mod workspace;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

/// JSON the backend passes through without reading it (bridge requests and
/// answers, game data, MCP messages): `unknown` to the UI, which types it
/// with format's generated contract. (specta's own `serde_json::Value` type
/// is recursive and overflows the exporter's stack.)
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Json(pub serde_json::Value);

impl specta::Type for Json {
    fn definition(types: &mut specta::Types) -> specta::datatype::DataType {
        specta_typescript::Unknown::<()>::definition(types)
    }
}

/// Runs filesystem-heavy work off the async workers.
pub async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> Result<T> + Send + 'static,
) -> Result<T> {
    tauri::async_runtime::spawn_blocking(f)
        .await
        .map_err(|e| Error::msg(e.to_string()))?
}
