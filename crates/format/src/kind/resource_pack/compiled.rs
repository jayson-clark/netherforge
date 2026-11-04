//! A pack as it builds (format's `ResourcePackRead` and
//! `CompiledResourcePack`): each pack read with its model files, then every
//! pack of the project and its packages compiled together, every character
//! settled. Skins are in a pack's own font, numbered from the start of the
//! private-use block; glyphs all merge into the game's default font, so
//! they're numbered across the whole project, the project's packs first.

use std::collections::{BTreeMap, BTreeSet};

use serde::Serialize;

use super::hud::CompiledHud;
use super::models::Model;
use super::sounds::{self, Sound};
use super::{
    BlockModelDef, EquipmentAssetDef, GlyphDef, ItemModelDef, ResourcePackFile, SkinDef, TEXTURES,
    TooltipDef, fonts, models, under,
};
use crate::project::ImageInfo;

/// A pack as it builds: its `pack.json`, and the model files its looks name,
/// read, by path under `models/`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ResourcePackRead {
    pub file: ResourcePackFile,
    pub models: BTreeMap<String, Model>,
}

/// `file` with the model files of its folder (`read`, `files` its folder's paths).
pub fn read(
    file: &ResourcePackFile,
    files: &BTreeSet<String>,
    read: &dyn Fn(&str) -> Option<String>,
) -> ResourcePackRead {
    let textures: BTreeSet<String> = under(files, TEXTURES)
        .into_iter()
        .map(str::to_owned)
        .collect();
    let models = file
        .models_named()
        .into_iter()
        .filter_map(|path| {
            let text = read(&format!("{}/{path}", models::FOLDER))?;
            let json: serde_json::Value = serde_json::from_str(&text)
                .ok()
                .filter(serde_json::Value::is_object)?;
            Some((path.to_owned(), models::read(&json, &textures)))
        })
        .collect();
    ResourcePackRead {
        file: file.clone(),
        models,
    }
}

/// A game sprite or texture drawn with the pack's `texture` (under
/// `textures/`), and its `.mcmeta` when it has one.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Replacement {
    pub texture: String,
    pub meta: bool,
}

/// A skin: what draws it (`char`), and how far that moves the cursor (none when its picture couldn't be read).
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Skin {
    pub key: String,
    pub def: SkinDef,
    pub char: String,
    pub advance: Option<i32>,
    pub cells: String,
}

impl Skin {
    pub fn height(&self) -> i32 {
        self.def.height.unwrap_or(super::SKIN_HEIGHT)
    }

    pub fn ascent(&self) -> i32 {
        self.def.ascent.unwrap_or(super::SKIN_ASCENT)
    }

    pub fn offset(&self) -> i32 {
        self.def.offset.unwrap_or(super::SKIN_OFFSET)
    }

    /// What goes at the front of a window title to draw this skin, in the
    /// pack's GUI font: move its offset, draw it, then move back by everything
    /// that moved the cursor, so the prefix is no width and the title's words
    /// start where they would without it. `shift` moves the picture more, for a
    /// window that centres its title.
    pub fn title_prefix(&self, shift: i32) -> String {
        let back = self
            .advance
            .map_or_else(String::new, |a| fonts::offset(-(self.offset() + shift + a)));
        format!(
            "{}{}{back}",
            fonts::offset(self.offset() + shift),
            self.char
        )
    }
}

/// A glyph: what draws it in text, its advance, and each of its pieces' own.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Glyph {
    pub key: String,
    pub def: GlyphDef,
    pub char: String,
    pub advance: Option<i32>,
    pub cells: String,
    pub cell_advances: Option<Vec<i32>>,
}

impl Glyph {
    pub fn height(&self) -> i32 {
        self.def.height.unwrap_or(super::GLYPH_HEIGHT)
    }

    pub fn ascent(&self) -> i32 {
        self.def.ascent.unwrap_or(super::GLYPH_ASCENT)
    }
}

/// A validated pack with every character settled. `id` is its folder and
/// `namespace` the project's (or its package's): its assets are the keys
/// `<namespace>:<id>/<key>`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledResourcePack {
    pub namespace: String,
    pub id: String,
    pub name: Option<String>,
    pub description: Option<String>,
    pub skins: BTreeMap<String, Skin>,
    pub glyphs: BTreeMap<String, Glyph>,
    pub items: BTreeMap<String, ItemModelDef>,
    pub tooltips: BTreeMap<String, TooltipDef>,
    pub equipment: BTreeMap<String, EquipmentAssetDef>,
    pub blocks: BTreeMap<String, BlockModelDef>,
    /// Every sound event by key.
    pub sounds: BTreeMap<String, Sound>,
    /// How the project names the pack: its id, or `ns:id` for a package's.
    pub key: String,
    /// The model files its looks name, read, by path under `models/`.
    pub models: BTreeMap<String, Model>,
    pub hud: Option<CompiledHud>,
    /// The game's GUI sprites it redraws (`hud.replace`), by name.
    pub sprite_replacements: BTreeMap<String, Replacement>,
    /// The game's other textures it redraws (`textures`), by name.
    pub texture_replacements: BTreeMap<String, Replacement>,
    /// The game's sound events it replaces, by the event in full.
    pub sound_replacements: BTreeMap<String, Sound>,
    /// The game's models it redraws, by name, each a model file of the pack.
    pub model_replacements: BTreeMap<String, String>,
    /// Built into a pack of its own, sent by scripts.
    pub on_demand: bool,
}

impl CompiledResourcePack {
    /// The font its skins are in: `<namespace>:<id>/gui`.
    pub fn gui_font(&self) -> String {
        format!("{}:{}/{}", self.namespace, self.id, fonts::GUI_FONT)
    }
}

/// What one pack brings to the build: its read form, its usable sound files
/// (under `sounds/`) and the files under its `textures/`.
pub struct PackInput<'a> {
    pub read: &'a ResourcePackRead,
    pub sound_files: BTreeSet<String>,
    pub texture_files: BTreeSet<String>,
}

/// Where a pack's texture is in the project, or for pack `ns:id` at its package path.
pub fn texture_path(pack: &str, texture: &str) -> String {
    super::across::texture_path(pack, texture)
}

/// Every pack of a project whose namespace is `namespace`, by pack key, glyphs
/// numbered across all of them: the project's own (keyed by id) first, then
/// the packages' (`ns:id`, each built in its own namespace). `image` answers a
/// texture's pixels by project (or package) path.
pub fn compile_all(
    namespace: &str,
    packs: &BTreeMap<String, PackInput>,
    image: &dyn Fn(&str) -> Option<ImageInfo>,
) -> BTreeMap<String, CompiledResourcePack> {
    let mut next_glyph = 0;
    // A glyph cut into pieces takes a character per further piece, after every glyph's own.
    let mut next_piece: usize = packs.values().map(|p| p.read.file.glyphs.len()).sum();
    let mut ordered: Vec<(&String, &PackInput)> = packs.iter().collect();
    ordered.sort_by(|a, b| (a.0.contains(':'), a.0).cmp(&(b.0.contains(':'), b.0)));
    let mut out = BTreeMap::new();
    for (key, input) in ordered {
        let (own, id) = match key.split_once(':') {
            Some((ns, id)) => (ns, id),
            None => (namespace, key.as_str()),
        };
        let mut piece = || {
            (next_piece < fonts::PUA_COUNT).then(|| {
                next_piece += 1;
                fonts::char_at(next_piece - 1)
            })
        };
        let pack = compile(own, id, key, input, next_glyph, &mut piece, image);
        next_glyph += input.read.file.glyphs.len();
        out.insert(key.clone(), pack);
    }
    out
}

fn sorted<K: AsRef<str>, V: Clone>(
    entries: impl IntoIterator<Item = (K, V)>,
) -> BTreeMap<String, V> {
    entries
        .into_iter()
        .map(|(k, v)| (k.as_ref().to_owned(), v))
        .collect()
}

fn compile(
    namespace: &str,
    id: &str,
    key: &str,
    input: &PackInput,
    glyph_start: usize,
    glyph_piece: &mut dyn FnMut() -> Option<String>,
    image: &dyn Fn(&str) -> Option<ImageInfo>,
) -> CompiledResourcePack {
    let file = &input.read.file;
    let replacements = |replaced: &crate::canonical::Map<super::TexturePath>| {
        replaced
            .iter()
            .map(|(name, texture)| {
                let meta = input
                    .texture_files
                    .contains(&format!("{}.mcmeta", texture.0));
                (
                    name.0.clone(),
                    Replacement {
                        texture: texture.0.clone(),
                        meta,
                    },
                )
            })
            .collect::<BTreeMap<_, _>>()
    };
    let picture =
        |ch: String, texture: &str, drawn: i32, piece: &mut dyn FnMut() -> Option<String>| {
            fonts::picture(
                ch,
                image(&texture_path(key, texture)).as_ref(),
                drawn,
                piece,
            )
        };
    let mut skin_keys: Vec<(&super::PackKey, &SkinDef)> = file.skins.iter().collect();
    skin_keys.sort_by(|a, b| a.0.0.cmp(&b.0.0));
    let mut glyph_keys: Vec<(&super::PackKey, &GlyphDef)> = file.glyphs.iter().collect();
    glyph_keys.sort_by(|a, b| a.0.0.cmp(&b.0.0));
    // Skins are in the pack's own font: a skin's further pieces are numbered after every skin.
    let mut next_skin_piece = skin_keys.len();
    let mut skin_piece = || {
        (next_skin_piece < fonts::PUA_COUNT).then(|| {
            next_skin_piece += 1;
            fonts::char_at(next_skin_piece - 1)
        })
    };
    let skins = skin_keys
        .iter()
        .enumerate()
        .map(|(i, (k, def))| {
            let def = (*def).clone();
            let drawn = picture(
                fonts::char_at(i),
                &def.texture,
                def.height.unwrap_or(super::SKIN_HEIGHT),
                &mut skin_piece,
            );
            let skin = Skin {
                key: k.0.clone(),
                def,
                char: drawn.text,
                advance: drawn.advance,
                cells: drawn.cells,
            };
            (k.0.clone(), skin)
        })
        .collect();
    let glyphs = glyph_keys
        .iter()
        .enumerate()
        .map(|(i, (k, def))| {
            let def = (*def).clone();
            let drawn = picture(
                fonts::char_at(glyph_start + i),
                &def.texture,
                def.height.unwrap_or(super::GLYPH_HEIGHT),
                glyph_piece,
            );
            let glyph = Glyph {
                key: k.0.clone(),
                def,
                char: drawn.text,
                advance: drawn.advance,
                cells: drawn.cells,
                cell_advances: drawn.cell_advances,
            };
            (k.0.clone(), glyph)
        })
        .collect();
    let pack_image = |texture: &str| image(&texture_path(key, texture)).filter(|i| i.height > 0);
    CompiledResourcePack {
        namespace: namespace.to_owned(),
        id: id.to_owned(),
        name: file.name.clone(),
        description: file.description.clone(),
        skins,
        glyphs,
        items: sorted(file.items.iter().map(|(k, v)| (&k.0, v.clone()))),
        tooltips: sorted(file.tooltips.iter().map(|(k, v)| (&k.0, v.clone()))),
        equipment: sorted(file.equipment.iter().map(|(k, v)| (&k.0, v.clone()))),
        blocks: sorted(file.blocks.iter().map(|(k, v)| (&k.0, v.clone()))),
        sounds: sounds::events(file, &input.sound_files),
        key: key.to_owned(),
        models: input.read.models.clone(),
        hud: file
            .hud
            .as_ref()
            .map(|hud| CompiledHud::compile(namespace, id, hud, &pack_image)),
        sprite_replacements: file
            .hud
            .as_ref()
            .map(|h| replacements(&h.replace))
            .unwrap_or_default(),
        texture_replacements: replacements(&file.textures),
        sound_replacements: sounds::replacements(file),
        model_replacements: sorted(file.models.iter().map(|(k, v)| (&k.0, v.0.clone()))),
        on_demand: file.on_demand == Some(true),
    }
}
