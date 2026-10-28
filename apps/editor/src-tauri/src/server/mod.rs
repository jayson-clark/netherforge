//! The dev server: finding or downloading Java, downloading Paper, setting up
//! each project's server folder (`<data>/servers/<hash>/`, outside the
//! project), and running it (see [process] for the lifecycle).

pub mod download;
pub mod java;
pub mod jvm_args;
pub mod leftover;
pub mod network;
pub mod paper;
pub mod process;
pub mod setup;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum ServerPhase {
    Stopped,
    Preparing,
    Starting,
    Running,
    Stopping,
    Crashed,
}

impl ServerPhase {
    /// Whether a server is (or is about to be) running.
    pub fn is_active(self) -> bool {
        matches!(
            self,
            Self::Preparing | Self::Starting | Self::Running | Self::Stopping
        )
    }
}

#[derive(
    Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type, tauri_specta::Event,
)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "server://state")]
pub struct ServerState {
    pub phase: ServerPhase,
    pub minecraft: Option<String>,
    pub port: Option<u16>,
    pub bridge_connected: bool,
    pub message: Option<String>,
}

impl Default for ServerState {
    fn default() -> Self {
        Self {
            phase: ServerPhase::Stopped,
            minecraft: None,
            port: None,
            bridge_connected: false,
            message: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "server://progress")]
pub struct PrepareProgress {
    pub step: PrepareStep,
    pub label: String,
    pub done: u64,
    pub total: Option<u64>,
}

/// What a preparation is doing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum PrepareStep {
    Java,
    Paper,
    Plugin,
    /// A dev network's proxy (Velocity) and its folder.
    Proxy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum Stream {
    Stdout,
    Stderr,
}

#[derive(
    Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type, tauri_specta::Event,
)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "server://output")]
pub struct ServerOutputEvent {
    pub stream: Stream,
    pub line: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct EulaStatus {
    pub accepted: bool,
    pub url: String,
}

/// Progress reports from the preparation steps.
pub type Progress<'a> = &'a (dyn Fn(PrepareProgress) + Send + Sync);

pub fn progress(
    step: PrepareStep,
    label: impl Into<String>,
    done: u64,
    total: Option<u64>,
) -> PrepareProgress {
    PrepareProgress {
        step,
        label: label.into(),
        done,
        total,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_like_types_ts() {
        let state = ServerState {
            phase: ServerPhase::Running,
            minecraft: Some("26.3".into()),
            port: Some(25565),
            bridge_connected: true,
            message: None,
        };
        assert_eq!(
            serde_json::to_value(&state).unwrap(),
            serde_json::json!({
                "phase": "running",
                "minecraft": "26.3",
                "port": 25565,
                "bridgeConnected": true,
                "message": null
            })
        );
        let output = ServerOutputEvent {
            stream: Stream::Stderr,
            line: "x".into(),
        };
        assert_eq!(
            serde_json::to_value(&output).unwrap(),
            serde_json::json!({"stream": "stderr", "line": "x"})
        );
    }
}
