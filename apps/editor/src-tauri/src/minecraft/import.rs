//! Extracting a version's client assets from the player's own client jar into
//! the per-user cache (`minecraft/<version>/client/`).
//!
//! What we take (paths in the jar, kept as-is under `client/`):
//! - `assets/minecraft/textures/` (with their `.mcmeta` animation files)
//! - `assets/minecraft/models/`, `blockstates/`, `items/` (item definitions,
//!   1.21.4+), `font/` (font providers; the bitmaps are under textures/font),
//!   `particles/` (each particle's sprites, for the particle effect preview)
//! - `assets/minecraft/lang/en_us.json` (the only language inside the jar)
//!
//! Plus three files we write: `index.json` (ids of blockstates, item
//! definitions and fonts, the HUD's GUI sprites and every texture with its size, so the UI can fill pickers without listing folders),
//! `glyph-advances.json` (the default font's advances, see [super::fonts]) and
//! `.import.json` (the marker `mc_cache_status` checks). The import goes to
//! a temp folder first and is swapped in at the end, so a failed import never
//! leaves half a cache behind.

use std::io::Read;
use std::path::Path;

use serde_json::json;

use crate::error::{Context, Result, bail};
use crate::fs::atomic::{sibling_temp, write_atomic};

use super::version;

const FOLDERS: &[&str] = &[
    "assets/minecraft/textures/",
    "assets/minecraft/models/",
    "assets/minecraft/blockstates/",
    "assets/minecraft/items/",
    "assets/minecraft/font/",
    "assets/minecraft/particles/",
];
const FILES: &[&str] = &["assets/minecraft/lang/en_us.json"];

pub const MARKER: &str = ".import.json";
pub const INDEX: &str = "index.json";

pub fn is_wanted(name: &str) -> bool {
    !name.ends_with('/')
        && (FILES.contains(&name) || FOLDERS.iter().any(|folder| name.starts_with(folder)))
}

/// The Minecraft version a client jar says it is (its `version.json` `id`).
pub fn jar_version<R: std::io::Read + std::io::Seek>(
    archive: &mut zip::ZipArchive<R>,
) -> Option<String> {
    let mut file = archive.by_name("version.json").ok()?;
    let mut text = String::new();
    file.read_to_string(&mut text).ok()?;
    let json: serde_json::Value = serde_json::from_str(&text).ok()?;
    json.get("id")?.as_str().map(String::from)
}

/// Extracts into [client_dir], replacing what was there. [progress] gets
/// (done, total) every so often and at the end. Returns the number of files.
pub fn import_client(
    jar: &Path,
    minecraft: &str,
    client_dir: &Path,
    mut progress: impl FnMut(usize, usize),
) -> Result<usize> {
    let file = std::fs::File::open(jar).context(|| format!("Couldn't open {}", jar.display()))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file))
        .context(|| format!("{} isn't a jar", jar.display()))?;

    if let Some(found) = jar_version(&mut archive)
        && version::is_release(&found)
        && !version::compare(&found, minecraft).is_eq()
    {
        bail!("That jar is Minecraft {found}, not {minecraft}");
    }

    let wanted: Vec<usize> = (0..archive.len())
        .filter(|&i| archive.name_for_index(i).is_some_and(is_wanted))
        .collect();
    if wanted.is_empty() {
        bail!(
            "{} has no Minecraft assets; is it a client jar?",
            jar.display()
        );
    }
    let total = wanted.len();
    progress(0, total);

    let temp = sibling_temp(client_dir);
    let parent = client_dir
        .parent()
        .context(|| "The cache folder has no parent".into())?;
    std::fs::create_dir_all(parent).context(|| format!("Couldn't create {}", parent.display()))?;

    let result = (|| -> Result<()> {
        let mut index = Index::default();
        for (done, &i) in wanted.iter().enumerate() {
            let mut entry = archive.by_index(i)?;
            // enclosed_name refuses `..` and absolute names (zip slip).
            let Some(relative) = entry.enclosed_name() else {
                continue;
            };
            let name = entry.name().to_string();
            index.note(&name);
            let out = temp.join(relative);
            if let Some(dir) = out.parent() {
                std::fs::create_dir_all(dir)?;
            }
            let mut file = std::fs::File::create(&out)?;
            std::io::copy(&mut entry, &mut file).context(|| format!("Couldn't extract {name}"))?;
            index.note_size(&name, &out);
            if (done + 1) % 200 == 0 {
                progress(done + 1, total);
            }
        }
        index.write(&temp, minecraft)?;
        // Text measuring is a nicety; an odd font must not fail the import.
        if let Err(error) = super::fonts::write(&temp) {
            eprintln!("[netherforge] no glyph advances for {minecraft}: {error}");
        }
        let marker = json!({
            "minecraft": minecraft,
            "jar": jar.display().to_string(),
            "files": total,
            "importedAt": crate::project::now_iso(),
        });
        write_atomic(
            &temp.join(MARKER),
            serde_json::to_string_pretty(&marker)?.as_bytes(),
        )?;

        if client_dir.exists() {
            std::fs::remove_dir_all(client_dir)
                .context(|| format!("Couldn't replace {}", client_dir.display()))?;
        }
        std::fs::rename(&temp, client_dir)
            .context(|| format!("Couldn't move the import into {}", client_dir.display()))
    })();
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&temp);
    }
    result?;
    progress(total, total);
    Ok(total)
}

#[derive(Default)]
struct Index {
    blockstates: Vec<String>,
    items: Vec<String>,
    fonts: Vec<String>,
    /// The HUD's sprites (`hud/heart/full`), which a resource pack's `hud.hide` blanks.
    sprites: Vec<String>,
    /// Every texture (`gui/container/inventory`, `block/stone`) with its pixel size, which a resource pack's
    /// `textures` redraws and must match.
    textures: std::collections::BTreeMap<String, [u32; 2]>,
}

/// A PNG's width and height, from the header's first 24 bytes.
fn png_size(bytes: &[u8]) -> Option<[u32; 2]> {
    if bytes.len() < 24 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let number = |at: usize| u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap());
    Some([number(16), number(20)])
}

impl Index {
    /// Notes the size of the texture just extracted to [path], when it is one.
    fn note_size(&mut self, name: &str, path: &Path) {
        let Some(texture) = name
            .strip_prefix("assets/minecraft/textures/")
            .and_then(|rest| rest.strip_suffix(".png"))
        else {
            return;
        };
        let mut header = [0u8; 24];
        let read = std::fs::File::open(path).and_then(|mut file| file.read_exact(&mut header));
        if let (Ok(()), Some(size)) = (read, png_size(&header)) {
            self.textures.insert(texture.to_string(), size);
        }
    }

    fn note(&mut self, name: &str) {
        let id = |folder: &str| {
            name.strip_prefix(folder)
                .and_then(|rest| rest.strip_suffix(".json"))
                .map(|id| format!("minecraft:{id}"))
        };
        if let Some(id) = id("assets/minecraft/blockstates/") {
            self.blockstates.push(id);
        } else if let Some(id) = id("assets/minecraft/items/") {
            self.items.push(id);
        } else if let Some(id) = id("assets/minecraft/font/") {
            self.fonts.push(id);
        } else if let Some(sprite) = name
            .strip_prefix("assets/minecraft/textures/gui/sprites/")
            .filter(|rest| rest.starts_with("hud/"))
            .and_then(|rest| rest.strip_suffix(".png"))
        {
            self.sprites.push(sprite.to_string());
        }
    }

    fn write(mut self, dir: &Path, minecraft: &str) -> Result<()> {
        for list in [
            &mut self.blockstates,
            &mut self.items,
            &mut self.fonts,
            &mut self.sprites,
        ] {
            list.sort();
        }
        let index = json!({
            "minecraft": minecraft,
            "blockstates": self.blockstates,
            "items": self.items,
            "fonts": self.fonts,
            "sprites": self.sprites,
            "textures": self.textures,
        });
        write_atomic(&dir.join(INDEX), serde_json::to_string(&index)?.as_bytes())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::Write;

    /// A tiny synthetic client jar: no Mojang data, just the shape.
    pub fn fake_client_jar(path: &Path, minecraft: &str) {
        let file = std::fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        let entries: &[(&str, &[u8])] = &[
            ("net/minecraft/client/Main.class", b"\xca\xfe"),
            (
                "assets/minecraft/textures/block/test_block.png",
                b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR\0\0\0\x10\0\0\0\x20\x08\x06",
            ),
            ("assets/minecraft/textures/block/broken.png", b"\x89PNG"),
            (
                "assets/minecraft/textures/gui/sprites/hud/heart/full.png",
                b"\x89PNG",
            ),
            (
                "assets/minecraft/textures/gui/container/inventory.png",
                b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR\0\0\x01\0\0\0\x01\x80\x08\x06",
            ),
            (
                "assets/minecraft/textures/block/test_block.png.mcmeta",
                b"{}",
            ),
            ("assets/minecraft/models/block/test_block.json", b"{}"),
            ("assets/minecraft/blockstates/test_block.json", b"{}"),
            ("assets/minecraft/items/test_item.json", b"{}"),
            ("assets/minecraft/font/default.json", b"{}"),
            ("assets/minecraft/particles/flame.json", b"{}"),
            ("assets/minecraft/lang/en_us.json", b"{}"),
            ("assets/minecraft/lang/deprecated.json", b"{}"),
            ("assets/minecraft/shaders/core/x.vsh", b""),
            ("data/minecraft/recipe/x.json", b"{}"),
        ];
        zip.add_directory("assets/minecraft/textures/", options)
            .unwrap();
        zip.start_file("version.json", options).unwrap();
        write!(zip, "{{\"id\":\"{minecraft}\"}}").unwrap();
        for (name, bytes) in entries {
            zip.start_file(*name, options).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn extracts_only_the_assets_we_need() {
        let tmp = tempfile::tempdir().unwrap();
        let jar = tmp.path().join("26.3.jar");
        fake_client_jar(&jar, "26.3");
        let client = tmp.path().join("cache/minecraft/26.3/client");
        std::fs::create_dir_all(&client).unwrap();
        std::fs::write(client.join("stale.txt"), "old import").unwrap();

        let mut calls = Vec::new();
        let count = import_client(&jar, "26.3", &client, |d, t| calls.push((d, t))).unwrap();
        assert_eq!(count, 11);
        assert_eq!(calls.first(), Some(&(0, 11)));
        assert_eq!(calls.last(), Some(&(11, 11)));

        let mut files: Vec<String> = walkdir::WalkDir::new(&client)
            .into_iter()
            .flatten()
            .filter(|e| e.file_type().is_file())
            .map(|e| {
                e.path()
                    .strip_prefix(&client)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/")
            })
            .collect();
        files.sort();
        assert_eq!(
            files,
            [
                ".import.json",
                "assets/minecraft/blockstates/test_block.json",
                "assets/minecraft/font/default.json",
                "assets/minecraft/items/test_item.json",
                "assets/minecraft/lang/en_us.json",
                "assets/minecraft/models/block/test_block.json",
                "assets/minecraft/particles/flame.json",
                "assets/minecraft/textures/block/broken.png",
                "assets/minecraft/textures/block/test_block.png",
                "assets/minecraft/textures/block/test_block.png.mcmeta",
                "assets/minecraft/textures/gui/container/inventory.png",
                "assets/minecraft/textures/gui/sprites/hud/heart/full.png",
                "glyph-advances.json",
                "index.json",
            ]
        );
        let index: serde_json::Value =
            serde_json::from_slice(&std::fs::read(client.join(INDEX)).unwrap()).unwrap();
        assert_eq!(index["blockstates"], json!(["minecraft:test_block"]));
        assert_eq!(index["items"], json!(["minecraft:test_item"]));
        assert_eq!(index["sprites"], json!(["hud/heart/full"]));
        assert_eq!(
            index["textures"],
            json!({ "block/test_block": [16, 32], "gui/container/inventory": [256, 384] })
        );
        // The fake font has no providers, so no advances, but the file is there.
        assert_eq!(
            std::fs::read_to_string(client.join(super::super::fonts::ADVANCES)).unwrap(),
            "{}"
        );
        // No temp folders left next to the cache.
        assert_eq!(
            std::fs::read_dir(client.parent().unwrap()).unwrap().count(),
            1
        );
    }

    #[test]
    fn refuses_the_wrong_version_or_a_non_client_jar() {
        let tmp = tempfile::tempdir().unwrap();
        let jar = tmp.path().join("client.jar");
        fake_client_jar(&jar, "1.21.11");
        let client = tmp.path().join("client");
        let error = import_client(&jar, "26.3", &client, |_, _| {}).unwrap_err();
        assert!(error.message().contains("is Minecraft 1.21.11"));
        assert!(!client.exists());

        let empty = tmp.path().join("empty.jar");
        zip::ZipWriter::new(std::fs::File::create(&empty).unwrap())
            .finish()
            .unwrap();
        assert!(import_client(&empty, "26.3", &client, |_, _| {}).is_err());
        std::fs::write(tmp.path().join("not.jar"), "nope").unwrap();
        assert!(import_client(&tmp.path().join("not.jar"), "26.3", &client, |_, _| {}).is_err());
    }
}
