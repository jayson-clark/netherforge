//! User settings and the EULA acceptance, as small JSON files in the config
//! folder. A missing or unreadable file means defaults: settings are never
//! worth failing to start over.

use std::path::Path;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::error::{Result, bail};
use crate::fs::atomic::write_atomic;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub server_jvm_args: Vec<String>,
    pub server_memory_mb: u32,
    pub server_port: u16,
    /// The MCP server for coding agents (`mcp/`), on 127.0.0.1.
    pub mcp_enabled: bool,
    pub mcp_port: u16,
    /// ffmpeg, for converting videos for screens (`video/`): a path to it, or empty to find it on `PATH`.
    pub ffmpeg_path: String,
}

/// Fixed, so a project's `.mcp.json` keeps working across restarts.
pub const DEFAULT_MCP_PORT: u16 = 47615;

impl Default for Settings {
    fn default() -> Self {
        Self {
            server_jvm_args: Vec::new(),
            server_memory_mb: 2048,
            server_port: 25565,
            mcp_enabled: true,
            mcp_port: DEFAULT_MCP_PORT,
            ffmpeg_path: String::new(),
        }
    }
}

impl Settings {
    pub fn validate(&self) -> Result<()> {
        if self.server_memory_mb < 512 {
            bail!(Invalid, "The dev server needs at least 512 MB of memory");
        }
        if self.server_port == 0 {
            bail!(Invalid, "Pick a server port between 1 and 65535");
        }
        if self.mcp_port == 0 {
            bail!(Invalid, "Pick an MCP port between 1 and 65535");
        }
        if self.mcp_enabled && self.mcp_port == self.server_port {
            bail!(Invalid, "The MCP port can't be the dev server's port");
        }
        crate::server::jvm_args::check_all(&self.server_jvm_args)?;
        Ok(())
    }
}

/// Whether the user accepted the Minecraft EULA, remembered across projects.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EulaRecord {
    pub accepted: bool,
    /// ISO 8601, when they accepted.
    pub accepted_at: Option<String>,
}

pub const EULA_URL: &str = "https://aka.ms/MinecraftEULA";

/// Reads [path] over `T::default()`: a field an object in the file lacks (it
/// was written before the field existed) keeps its default, and a missing or
/// unreadable file is all defaults. (So `Settings` needs no
/// `#[serde(default)]`, and the UI's generated type has every field.)
pub fn load<T: DeserializeOwned + Serialize + Default>(path: &Path) -> T {
    use serde_json::Value;
    let Some(saved) = std::fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
    else {
        return T::default();
    };
    let value = match (serde_json::to_value(T::default()), saved) {
        (Ok(Value::Object(mut defaults)), Value::Object(saved)) => {
            defaults.extend(saved);
            Value::Object(defaults)
        }
        (_, saved) => saved,
    };
    serde_json::from_value(value).unwrap_or_default()
}

pub fn save<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let mut json = serde_json::to_vec_pretty(value)?;
    json.push(b'\n');
    write_atomic(path, &json)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_and_round_trip() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("config/settings.json");
        let settings: Settings = load(&path);
        assert_eq!(settings, Settings::default());
        assert_eq!(settings.server_memory_mb, 2048);
        assert_eq!(settings.server_port, 25565);

        let changed = Settings {
            server_jvm_args: vec!["-XX:+UseZGC".into()],
            server_memory_mb: 4096,
            server_port: 25570,
            mcp_enabled: false,
            mcp_port: 4000,
            ffmpeg_path: "/opt/ffmpeg/bin/ffmpeg".into(),
        };
        save(&path, &changed).unwrap();
        assert_eq!(load::<Settings>(&path), changed);
        let json: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(json["serverMemoryMb"], 4096);
    }

    #[test]
    fn missing_fields_and_garbage_fall_back_to_defaults() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("settings.json");
        std::fs::write(&path, r#"{"serverPort": 25599}"#).unwrap();
        let settings: Settings = load(&path);
        assert_eq!(settings.server_port, 25599);
        assert_eq!(settings.server_memory_mb, 2048);
        // Settings saved before the MCP server existed turn it on, on the fixed port.
        assert!(settings.mcp_enabled);
        assert_eq!(settings.mcp_port, DEFAULT_MCP_PORT);
        std::fs::write(&path, "not json").unwrap();
        assert_eq!(load::<Settings>(&path), Settings::default());
    }

    #[test]
    fn validates() {
        assert!(Settings::default().validate().is_ok());
        let low = Settings {
            server_memory_mb: 100,
            ..Settings::default()
        };
        assert!(low.validate().is_err());
        let port = Settings {
            server_port: 0,
            ..Settings::default()
        };
        assert!(port.validate().is_err());
        let clash = Settings {
            mcp_port: 25565,
            ..Settings::default()
        };
        assert!(clash.validate().is_err());
        let off = Settings {
            mcp_enabled: false,
            ..clash
        };
        assert!(off.validate().is_ok());
    }
}
