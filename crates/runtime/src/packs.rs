//! The project's resource pack (Kotlin's `Packs`, `PackZip` and `PackService`): every pack under `resource_packs/`
//! built into one zip with format's `PackLayout` (`build::build`, so the editor's preview and the zip agree on every
//! file), zipped so the same files always make the same bytes, and the glyph and skin characters scripts and menus
//! draw with. A pack with errors keeps the last good build (and its characters) running. `onDemand` packs each go
//! into a zip of their own, for scripts to push.
//!
//! The build lives in `Shared`, which outlives a full restart of the session's project, so a reload that changes
//! nothing changes no hash. Serving and sending the zips (the plugin's HTTP server, an owner's URL, an S3 store) come
//! with the next slice.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Write as _;
use std::rc::Rc;

use netherforge_format::Problem;
use netherforge_format::kind::resource_pack::build::{self, Entry, VideoSound};
use netherforge_format::kind::resource_pack::compiled::CompiledResourcePack;
use netherforge_format::reference::{RefKind, ResourceKey, resolve_any};
use netherforge_platform::Platform;
use sha1::Digest as _;

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// 2000-01-01 00:00 as DOS time and date: comfortably inside the DOS range in every time zone.
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = (20 << 9) | (1 << 5) | 1;

/// Zips a resource pack so the same files always make the same bytes (Kotlin's `PackZip`): entries in path
/// order, every one with the same date, deflated at one level, no extra fields, no comment. The SHA-1 is what tells
/// a client it already has a pack, so anything in the zip that isn't content would cost every player a download.
pub fn zip(files: &BTreeMap<String, Vec<u8>>) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    for (path, bytes) in files {
        let crc = crc32fast::hash(bytes);
        let mut deflater =
            flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        deflater.write_all(bytes).expect("writing to memory");
        let deflated = deflater.finish().expect("writing to memory");
        let offset = out.len() as u32;
        let name = path.as_bytes();
        let header = |sig: u32, central: bool| {
            let mut h = Vec::new();
            h.extend_from_slice(&sig.to_le_bytes());
            if central {
                h.extend_from_slice(&20u16.to_le_bytes()); // made by
            }
            h.extend_from_slice(&20u16.to_le_bytes()); // needed to extract
            h.extend_from_slice(&0x0800u16.to_le_bytes()); // names are UTF-8
            h.extend_from_slice(&8u16.to_le_bytes()); // deflated
            h.extend_from_slice(&DOS_TIME.to_le_bytes());
            h.extend_from_slice(&DOS_DATE.to_le_bytes());
            h.extend_from_slice(&crc.to_le_bytes());
            h.extend_from_slice(&(deflated.len() as u32).to_le_bytes());
            h.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            h.extend_from_slice(&(name.len() as u16).to_le_bytes());
            h.extend_from_slice(&0u16.to_le_bytes()); // extra
            if central {
                h.extend_from_slice(&0u16.to_le_bytes()); // comment
                h.extend_from_slice(&0u16.to_le_bytes()); // disk
                h.extend_from_slice(&0u16.to_le_bytes()); // internal attributes
                h.extend_from_slice(&0u32.to_le_bytes()); // external attributes
                h.extend_from_slice(&offset.to_le_bytes());
            }
            h.extend_from_slice(name);
            h
        };
        out.extend(header(0x0403_4b50, false));
        out.extend_from_slice(&deflated);
        central.extend(header(0x0201_4b50, true));
    }
    let start = out.len() as u32;
    let count = files.len() as u16;
    out.extend_from_slice(&central);
    out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&(central.len() as u32).to_le_bytes());
    out.extend_from_slice(&start.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

/// A SHA-1 as lowercase hex, as Minecraft's pack hash is written.
pub fn sha1_hex(bytes: &[u8]) -> String {
    sha1::Sha1::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// One build: the zip and its hash.
#[derive(Clone, Debug)]
pub struct Built {
    pub bytes: Rc<Vec<u8>>,
    pub sha1: String,
}

/// The pack as last built, and what scripts and menus read of it.
#[derive(Default)]
pub struct Packs {
    pub built: Option<Built>,
    /// Each `onDemand` pack's own build, by the pack's name as the server knows it (`featured`, `library:featured`).
    pub on_demand: BTreeMap<String, Built>,
    compiled: BTreeMap<String, CompiledResourcePack>,
    /// The project's namespace, the last load's: what a pack entry's name resolves in.
    home: Option<String>,
    /// Unknown `<glyph:…>` references already reported, each once per build.
    reported_glyphs: BTreeSet<String>,
    /// Every glyph character's advance by code point, worked out from the compiled packs.
    glyph_chars: Option<BTreeMap<u32, i32>>,
}

impl Packs {
    /// Rebuilds from `project`: what's wrong with the build that the format didn't already say (the server not
    /// knowing its pack format). With no packs, blocks or videos in the tree there's no pack.
    pub fn rebuild(&mut self, platform: &dyn Platform, project: &Define) -> Vec<Problem> {
        let loaded = project.project;
        self.home = Some(loaded.namespace().to_owned());
        self.reported_glyphs.clear();
        if !build::needed(loaded) {
            self.compiled.clear();
            self.glyph_chars = None;
            self.built = None;
            self.on_demand.clear();
            return Vec::new();
        }
        // A pack with errors isn't compiled: keep running the last good one.
        if !loaded.present("resource_pack").is_empty() && loaded.compiled_resource_packs.is_empty()
        {
            return Vec::new();
        }
        self.compiled = loaded.compiled_resource_packs.clone();
        self.glyph_chars = None;
        let Some(format) = platform.game().resource_pack_format() else {
            let message = "This server doesn't say which resource pack format it uses, so no resource pack was built";
            platform.log().warn(message.to_owned());
            return vec![netherforge_format::codes::RUNTIME_PACK_FORMAT.at(
                "resource_packs",
                message,
                None,
            )];
        };
        let description = loaded
            .manifest
            .as_ref()
            .map(|m| m.name.clone())
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| "NetherForge".into());
        let plan = blocks_plan(platform, project);
        let videos = VideoSound::of(loaded);
        let (separate, joined): (Vec<&CompiledResourcePack>, Vec<&CompiledResourcePack>) =
            self.compiled.values().partition(|p| p.on_demand);
        let read = |path: &str| read_bytes(project, path);
        let size = |path: &str| read(path).and_then(|b| png_size(&b));
        let bytes = zip(&files(
            &build::build(
                &joined,
                format,
                &description,
                plan.as_ref(),
                loaded.vanilla.as_ref(),
                &videos,
                &size,
            ),
            &read,
        ));
        let mut on_demand = BTreeMap::new();
        for pack in separate {
            let own_description = pack
                .description
                .clone()
                .or(pack.name.clone())
                .unwrap_or(pack.key.clone());
            let own = zip(&files(
                &build::build(&[pack], format, &own_description, None, None, &[], &size),
                &read,
            ));
            let sha1 = sha1_hex(&own);
            let kept = self
                .on_demand
                .get(&pack.key)
                .filter(|b| b.sha1 == sha1)
                .cloned();
            on_demand.insert(
                pack.key.clone(),
                kept.unwrap_or(Built {
                    bytes: Rc::new(own),
                    sha1,
                }),
            );
        }
        self.on_demand = on_demand;
        let sha1 = sha1_hex(&bytes);
        if self.built.as_ref().is_some_and(|b| b.sha1 == sha1) {
            return Vec::new();
        }
        platform.log().info(format!(
            "Built the resource pack from {} resource pack(s), {} bytes, SHA-1 {sha1}",
            joined.len(),
            bytes.len()
        ));
        self.built = Some(Built {
            bytes: Rc::new(bytes),
            sha1,
        });
        Vec::new()
    }

    /// `reference`, an entry of `kind`'s name, as a key; none when it isn't shaped like one.
    fn key_of(&self, kind: RefKind, reference: &str) -> Option<ResourceKey> {
        let key = resolve_any(self.home.as_deref()?, reference)?;
        kind.is_path(&key.path).then_some(key)
    }

    /// The built pack `key` (an entry's, `shop:ui/coin`) is in: the project's by id, a package's as `acme:ui`.
    fn pack_of(&self, key: &ResourceKey) -> Option<&CompiledResourcePack> {
        let home = self.home.as_deref()?;
        let pack = ResourceKey {
            namespace: key.namespace.clone(),
            path: key.pack().to_owned(),
        }
        .relative_to(home);
        self.compiled.get(&pack)
    }

    /// The character glyph `key` is drawn as; none while the packs aren't built.
    pub fn glyph(&self, key: &ResourceKey) -> Option<String> {
        Some(self.pack_of(key)?.glyphs.get(key.key())?.char.clone())
    }

    /// What a `<glyph:ui/coin>` tag inserts: the glyph's character, or none (nothing) while the packs aren't built
    /// or for a glyph no pack has, which is reported once as a warning.
    pub fn text_glyph(&mut self, platform: &dyn Platform, reference: &str) -> Option<String> {
        let glyph = self
            .key_of(RefKind::Glyph, reference)
            .and_then(|k| self.glyph(&k));
        if glyph.is_none()
            && self.home.is_some()
            && self.reported_glyphs.insert(reference.to_owned())
        {
            platform.log().warn(format!(
                "<glyph:{reference}> names no glyph in the project's resource packs (a glyph is <pack>/<key>, like ui/coin), so it shows nothing"
            ));
        }
        glyph
    }

    /// The MiniMessage that draws a skin at the front of a title: the skin's characters in the pack's `gui` font,
    /// in white so the picture isn't tinted by the title's colour. None when the packs don't have it built.
    pub fn skin_title(&self, reference: &str, shift: i32) -> Option<String> {
        let key = self.key_of(RefKind::Skin, reference)?;
        let pack = self.pack_of(&key)?;
        let skin = pack.skins.get(key.key())?;
        Some(format!(
            "<white><font:{}>{}</font></white>",
            pack.gui_font(),
            skin.title_prefix(shift)
        ))
    }

    /// A glyph's advance in text (`<glyph:ui/coin>`), for measuring it; none when unknown.
    pub fn glyph_advance(&self, reference: &str) -> Option<i32> {
        let key = self.key_of(RefKind::Glyph, reference)?;
        self.pack_of(&key)?.glyphs.get(key.key())?.advance
    }

    /// How far a glyph's bare character moves the cursor, for measuring text that holds it rather than its tag;
    /// none for a character that isn't one. Every pack's, `onDemand` ones' too: glyphs are numbered across them.
    pub fn glyph_char_advance(&mut self, code_point: u32) -> Option<i32> {
        if self.glyph_chars.is_none() {
            let mut chars = BTreeMap::new();
            for pack in self.compiled.values() {
                for glyph in pack.glyphs.values() {
                    let Some(advances) = &glyph.cell_advances else {
                        continue;
                    };
                    for (c, advance) in glyph.cells.chars().zip(advances) {
                        chars.insert(c as u32, *advance);
                    }
                }
            }
            self.glyph_chars = Some(chars);
        }
        self.glyph_chars.as_ref()?.get(&code_point).copied()
    }
}

/// The states the project's blocks are held as: drawn as their models, everything else as the game draws it.
fn blocks_plan(
    platform: &dyn Platform,
    project: &Define,
) -> Option<netherforge_format::kind::carriers::Plan> {
    use netherforge_format::kind::block::{BlockFile, BlockRotation};
    let loaded = project.project;
    let every = loaded.present("block");
    if every.is_empty() {
        return None;
    }
    let running: BTreeMap<String, &BlockFile> = loaded.running::<BlockFile>().into_iter().collect();
    let mut blocks = BTreeMap::new();
    let mut carriers = BTreeMap::new();
    let mut rotations = BTreeMap::new();
    for (id, resource) in &every {
        if let Some(model) = resource.model::<BlockFile>() {
            carriers.insert(id.clone(), model.carrier.unwrap_or_default());
            rotations.insert(id.clone(), model.rotation.unwrap_or(BlockRotation::None));
        }
        blocks.insert(id.clone(), running.get(id).copied());
    }
    netherforge_format::kind::carriers::plan(
        &blocks,
        loaded.namespace(),
        Some(platform.game()),
        &carriers,
        &rotations,
    )
}

/// A file's bytes by project path, a package's (`ns:path`) from its package.
fn read_bytes(project: &Define, path: &str) -> Option<Vec<u8>> {
    match netherforge_format::kinds::split_package(path) {
        (Some(namespace), local) => project
            .project
            .packages
            .get(namespace)?
            .resolved
            .source
            .bytes(local),
        (None, _) => project.source.bytes(path),
    }
}

/// A PNG's width and height, from its header.
fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let word =
        |at: usize| -> Option<u32> { Some(u32::from_be_bytes(bytes[at..at + 4].try_into().ok()?)) };
    Some((word(16)?, word(20)?))
}

/// What each path of a built layout holds, the project's files read (one that can't be is left out).
fn files(
    layout: &BTreeMap<String, Entry>,
    read: &dyn Fn(&str) -> Option<Vec<u8>>,
) -> BTreeMap<String, Vec<u8>> {
    layout
        .iter()
        .filter_map(|(path, entry)| {
            let bytes = match entry {
                Entry::Text(text) => text.clone().into_bytes(),
                Entry::Copy(project_path) => read(project_path)?,
                Entry::Bytes(bytes) => bytes.clone(),
            };
            Some((path.clone(), bytes))
        })
        .collect()
}

/// The resource pack's part in a session: built first (menus' skins and scripts' glyphs read it), again when a
/// pack, a block, `client/vanilla.json` or a video changes.
pub struct PacksService {
    pub shared: Rc<Shared>,
    pub problems: std::cell::RefCell<Vec<Problem>>,
}

impl PacksService {
    fn rebuild(&self, project: &Define) {
        let problems = self
            .shared
            .packs
            .borrow_mut()
            .rebuild(&*self.shared.platform, project);
        *self.problems.borrow_mut() = problems;
    }
}

impl Service for PacksService {
    fn name(&self) -> &'static str {
        "resource pack"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["resource_pack"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        self.rebuild(project);
    }

    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, project: &Define) {
        let had = self
            .shared
            .packs
            .borrow()
            .built
            .as_ref()
            .map(|b| b.sha1.clone());
        self.rebuild(project);
        let loaded = project.project;
        if !loaded.present("resource_pack").is_empty() && loaded.compiled_resource_packs.is_empty()
        {
            self.shared.platform.log().warn(
                "A resource pack has errors; players keep the last good resource pack".into(),
            );
        }
        let _ = had;
    }

    /// The blocks the pack draws change with the project's blocks, the game's own looks of their carriers with
    /// `client/vanilla.json`, and the sounds it carries with videos.
    fn reloaded(&self, _room: &Room, kinds: &BTreeSet<&str>, project: &Define) {
        if ["block", "vanilla", "video"]
            .iter()
            .any(|k| kinds.contains(k))
        {
            self.rebuild(project);
        }
    }

    fn problems(&self) -> Vec<Problem> {
        self.problems.borrow().clone()
    }
}
