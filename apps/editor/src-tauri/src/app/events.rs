//! Events the backend pushes to the webview. Each is a payload type deriving
//! `tauri_specta::Event` with its name (`#[tauri_specta(event_name = "fs://changed")]`),
//! listed in `commands::bindings`, so the UI's listeners and payload types are
//! generated with the commands.
//!
//! Modules emit through [EventSink] rather than a Tauri handle, so the server
//! and bridge can be tested with a recording sink and no window.

use serde::Serialize;
use tauri::{AppHandle, Emitter, Runtime};
use tauri_specta::Event;

/// A frame from the plugin (a JSON-RPC message or batch: responses to the
/// UI's requests, notifications), as the plugin sent it. The UI's
/// JSON-RPC connection reads it, typed by format's generated
/// `BridgeRequests` and `BridgeEvents`.
#[derive(Debug, Clone, Serialize, specta::Type, tauri_specta::Event)]
#[tauri_specta(event_name = "bridge://message")]
pub struct BridgeMessage {
    pub message: String,
}

/// Files changed on disk, whoever changed them (the editor's own writes
/// included), debounced over ~100 ms.
#[derive(Debug, Clone, Serialize, specta::Type, tauri_specta::Event)]
#[tauri_specta(event_name = "fs://changed")]
pub struct FsChangedEvent {
    /// Project paths, deduplicated.
    pub paths: Vec<String>,
    /// Changes may have been lost (the watcher overflowed or erred): re-read everything.
    pub rescan: bool,
}

/// How far `mc_import_client` has got.
#[derive(Debug, Clone, Serialize, specta::Type, tauri_specta::Event)]
#[tauri_specta(event_name = "mc://import-progress")]
pub struct ImportProgress {
    pub version: String,
    pub done: u32,
    pub total: u32,
}

pub trait EventSink: Send + Sync + 'static {
    fn emit_value(&self, event: &str, payload: serde_json::Value);
}

impl dyn EventSink {
    /// Emits [payload] under its event's name.
    pub fn emit<E: Event + Serialize>(&self, payload: &E) {
        match serde_json::to_value(payload) {
            Ok(value) => self.emit_value(E::NAME, value),
            Err(error) => eprintln!("[netherforge] couldn't serialize {}: {error}", E::NAME),
        }
    }
}

impl<R: Runtime> EventSink for AppHandle<R> {
    fn emit_value(&self, event: &str, payload: serde_json::Value) {
        if let Err(error) = Emitter::emit(self, event, payload) {
            eprintln!("[netherforge] couldn't emit {event}: {error}");
        }
    }
}

/// Records events, for tests.
#[cfg(test)]
#[derive(Default)]
pub struct RecordingSink(pub std::sync::Mutex<Vec<(String, serde_json::Value)>>);

#[cfg(test)]
impl EventSink for RecordingSink {
    fn emit_value(&self, event: &str, payload: serde_json::Value) {
        self.0.lock().unwrap().push((event.to_string(), payload));
    }
}

#[cfg(test)]
impl RecordingSink {
    /// The payloads of every [E] emitted so far.
    pub fn named<E: Event>(&self) -> Vec<serde_json::Value> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .filter(|(name, _)| name == E::NAME)
            .map(|(_, value)| value.clone())
            .collect()
    }
}
