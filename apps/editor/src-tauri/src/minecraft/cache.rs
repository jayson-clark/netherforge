//! The per-user game-data cache: `<data>/minecraft/<version>/{client,server}/`.
//! The version is always checked to be a release number first, since it
//! becomes part of a path.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::app::dirs::AppDirs;
use crate::error::{Context, Result, bail};
use crate::fs::atomic::write_atomic;

use super::{fonts, import, version};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "mc://cache-changed")]
pub struct CacheStatus {
    pub version: String,
    pub client: bool,
    pub server: bool,
}

pub fn check_version(minecraft: &str) -> Result<()> {
    if !version::is_release(minecraft) {
        bail!(Invalid, "\"{minecraft}\" isn't a Minecraft release version");
    }
    Ok(())
}

pub fn status(dirs: &AppDirs, minecraft: &str) -> Result<CacheStatus> {
    check_version(minecraft)?;
    Ok(CacheStatus {
        version: minecraft.to_string(),
        client: dirs.client_dir(minecraft).join(import::MARKER).is_file(),
        server: schema_of(&dirs.game_data_file(minecraft)) == Some(GAME_DATA_SCHEMA),
    })
}

/// The shape of game data this editor reads: format's `GameDataBundle.SCHEMA`,
/// which the export carries as `schema`. A cache with any other (or none) is
/// treated as missing, so the next dev server start exports it again.
pub const GAME_DATA_SCHEMA: u64 = 3;

/// Just the `schema` of a cached export, read without building the rest.
#[derive(Deserialize)]
struct Header {
    schema: Option<u64>,
}

fn schema_of(path: &Path) -> Option<u64> {
    let bytes = std::fs::read(path).ok()?;
    serde_json::from_slice::<Header>(&bytes).ok()?.schema
}

/// The cached `GameDataBundle`, or None if no dev server exported it yet (or
/// the cache is of another [`GAME_DATA_SCHEMA`]).
pub fn read_game_data(dirs: &AppDirs, minecraft: &str) -> Result<Option<Value>> {
    check_version(minecraft)?;
    let path = dirs.game_data_file(minecraft);
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => bail!("Couldn't read {}: {e}", path.display()),
    };
    match serde_json::from_slice::<Value>(&bytes) {
        Ok(value) if value.get("schema").and_then(Value::as_u64) == Some(GAME_DATA_SCHEMA) => {
            Ok(Some(value))
        }
        // A corrupt cache, or one of another shape, is the same as no cache:
        // the next server start re-exports it.
        _ => Ok(None),
    }
}

/// Where the game data of [minecraft] is, when it is cached in the shape this editor reads: what
/// the script test runner is given. None when nothing (usable) is cached yet.
pub fn game_data_file(dirs: &AppDirs, minecraft: &str) -> Result<Option<PathBuf>> {
    check_version(minecraft)?;
    let path = dirs.game_data_file(minecraft);
    Ok((schema_of(&path) == Some(GAME_DATA_SCHEMA)).then_some(path))
}

/// The default font's glyph advances (`{ "<code point>": advance }`), or
/// None when no client is imported. An import from before advances existed
/// gets them computed now from its cached font files, and stored.
pub fn glyph_advances(dirs: &AppDirs, minecraft: &str) -> Result<Option<Value>> {
    check_version(minecraft)?;
    let client = dirs.client_dir(minecraft);
    if !client.join(import::MARKER).is_file() {
        return Ok(None);
    }
    let path = client.join(fonts::ADVANCES);
    if let Ok(bytes) = std::fs::read(&path)
        && let Ok(value @ Value::Object(_)) = serde_json::from_slice::<Value>(&bytes)
    {
        return Ok(Some(value));
    }
    let advances = fonts::write(&client)?;
    Ok(Some(Value::Object(
        advances
            .into_iter()
            .map(|(code, advance)| (code.to_string(), Value::from(advance)))
            .collect(),
    )))
}

/// Whether the dev server should export its game data for this version: none
/// is cached yet, or the cache is of another [`GAME_DATA_SCHEMA`], so an old
/// cache heals itself on the next server start.
pub fn needs_export(dirs: &AppDirs, minecraft: &str) -> Result<bool> {
    Ok(!status(dirs, minecraft)?.server)
}

pub fn write_game_data(dirs: &AppDirs, minecraft: &str, data: &Value) -> Result<()> {
    check_version(minecraft)?;
    if !data.is_object() {
        bail!("The plugin's game data export isn't an object");
    }
    // Stored, it would be asked for again on every start.
    let schema = data.get("schema").and_then(Value::as_u64);
    if schema != Some(GAME_DATA_SCHEMA) {
        bail!(
            "The plugin exported game data of schema {}, but this editor reads schema {GAME_DATA_SCHEMA}",
            schema.map_or_else(|| "(none)".to_string(), |it| it.to_string())
        );
    }
    let path = dirs.game_data_file(minecraft);
    let json = serde_json::to_vec(data)?;
    write_atomic(&path, &json).context(|| "Couldn't save the game data".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_and_game_data() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        assert_eq!(
            status(&dirs, "26.3").unwrap(),
            CacheStatus {
                version: "26.3".into(),
                client: false,
                server: false
            }
        );
        assert_eq!(read_game_data(&dirs, "26.3").unwrap(), None);
        assert!(needs_export(&dirs, "26.3").unwrap());

        let bundle = serde_json::json!({
            "minecraft": "26.3",
            "registries": {"minecraft:item": ["minecraft:stone"]},
            "schema": GAME_DATA_SCHEMA
        });
        write_game_data(&dirs, "26.3", &bundle).unwrap();
        assert_eq!(read_game_data(&dirs, "26.3").unwrap(), Some(bundle));
        assert!(!needs_export(&dirs, "26.3").unwrap());
        assert!(status(&dirs, "26.3").unwrap().server);
        assert_eq!(
            game_data_file(&dirs, "26.3").unwrap(),
            Some(dirs.game_data_file("26.3"))
        );
        assert!(
            tmp.path()
                .join("minecraft/26.3/server/game-data.json")
                .is_file()
        );
    }

    #[test]
    fn a_cache_of_another_schema_is_exported_again() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let file = dirs.game_data_file("26.3");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        for stale in [
            // From before exports carried a schema.
            serde_json::json!({"minecraft": "26.3", "items": ["minecraft:stone"]}),
            serde_json::json!({"minecraft": "26.3", "schema": GAME_DATA_SCHEMA + 1}),
        ] {
            std::fs::write(&file, serde_json::to_vec(&stale).unwrap()).unwrap();
            assert_eq!(read_game_data(&dirs, "26.3").unwrap(), None);
            assert_eq!(game_data_file(&dirs, "26.3").unwrap(), None);
            assert!(!status(&dirs, "26.3").unwrap().server);
            assert!(needs_export(&dirs, "26.3").unwrap());
        }
    }

    #[test]
    fn an_export_of_another_schema_is_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let old = serde_json::json!({"minecraft": "26.3", "items": []});
        assert!(write_game_data(&dirs, "26.3", &old).is_err());
        assert!(needs_export(&dirs, "26.3").unwrap());
    }

    /// Format's fixture bundle is today's shape (format's own test checks it
    /// against `GameDataBundle.SCHEMA`), so this side reads the schema format writes.
    #[test]
    fn reads_the_schema_format_writes() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../packages/format/testdata/game-data/bundle.json");
        assert_eq!(schema_of(&path), Some(GAME_DATA_SCHEMA));
    }

    #[test]
    fn glyph_advances_are_computed_for_older_imports() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        assert_eq!(glyph_advances(&dirs, "26.3").unwrap(), None);

        let client = dirs.client_dir("26.3");
        fonts::tests::synthetic_font(&client);
        std::fs::write(client.join(import::MARKER), "{}").unwrap();
        let advances = glyph_advances(&dirs, "26.3").unwrap().unwrap();
        assert_eq!(advances["65"], 6);
        // Stored, so the next call reads the file.
        assert!(client.join(fonts::ADVANCES).is_file());
        std::fs::write(client.join(fonts::ADVANCES), r#"{"65":7}"#).unwrap();
        assert_eq!(glyph_advances(&dirs, "26.3").unwrap().unwrap()["65"], 7);
        assert!(glyph_advances(&dirs, "../x").is_err());
    }

    #[test]
    fn refuses_versions_that_are_paths() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        for bad in ["../../etc", "26.3/../..", "", "26w14a"] {
            assert!(status(&dirs, bad).is_err());
            assert!(read_game_data(&dirs, bad).is_err());
        }
    }
}
