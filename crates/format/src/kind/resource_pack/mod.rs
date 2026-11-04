//! `resource_packs/<namespace>/pack.json`: named pictures, looks and sounds,
//! built into the one Minecraft resource pack. The folder name is the pack's
//! asset namespace; every reference is `<pack>:<key>`. Format's
//! `ResourcePackFile`, `ResourcePackKind.validate` and
//! `ResourcePackValidator`: its keys, paths and numbers are their types' and
//! fields' rules; what needs its folder's files, and what depends on several
//! fields, is checked here. Pictures' sizes, the game's own names (from the
//! client), and what other packs replace are checks across resources. (Its
//! build comes with the runtime's pack.)

pub mod hud;
pub mod models;
pub mod sounds;

use std::collections::{BTreeSet, HashSet};

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Entries, Map};
use crate::document::{Context, Document};
use crate::json::child_path;
use crate::kind::menu::MenuType;
use crate::problem::{ProblemSink, codes};
use hud::HudDef;
use models::ModelPath;
use sounds::SoundPath;

pub const FILE_NAME: &str = "pack.json";
pub const TEXTURES: &str = "textures";
pub const TEXTURE_EXTENSION: &str = ".png";
/// The private use characters glyphs and skins are numbered in (`PackFonts.PUA_COUNT`).
pub const PUA_COUNT: usize = 0xF800 - 0xE000;
/// How far a font can move the cursor either way (`PackFonts.MAX_OFFSET`).
pub const MAX_OFFSET: i32 = (1 << 11) - 1;
/// A texture's path under `textures/`: a relative path to a PNG inside the folder.
pub const TEXTURE_PATH_PATTERN: &str =
    r"^([A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*\.png$";
pub const TEXTURE_PATH_RULE: &str =
    "a .png path of letters, digits, _, - and . inside this resource pack's textures/ folder";

crate::ruled_string!(
    /// A key of one of a pack's maps (`coin`, the `ui:coin` a reference names).
    PackKey,
    crate::names::ID_PATTERN,
    "resource_pack.key",
    crate::names::ID_RULE
);

crate::ruled_string!(
    /// A texture's path under a pack's `textures/`.
    TexturePath,
    TEXTURE_PATH_PATTERN,
    "resource_pack.texture-path",
    TEXTURE_PATH_RULE,
    "x-ref" = "texture"
);

pub fn is_texture_path(path: &str) -> bool {
    crate::rules::matches_pattern(TEXTURE_PATH_PATTERN, path)
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResourcePackFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Shown in the client's pack screen.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// GUI backgrounds drawn behind menus.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub skins: Entries<PackKey, SkinDef>,
    /// Small pictures usable in any text.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub glyphs: Entries<PackKey, GlyphDef>,
    /// Item looks (`itemModel` on an item).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub items: Entries<PackKey, ItemModelDef>,
    /// Tooltip frames (`tooltipStyle` on an item).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub tooltips: Entries<PackKey, TooltipDef>,
    /// What a worn item is drawn as (`equipment` on an item).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub equipment: Entries<PackKey, EquipmentAssetDef>,
    /// Cube looks for blocks (`model` on a block).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub blocks: Entries<PackKey, BlockModelDef>,
    /// Sound events beyond their one file, by key; one in the game's namespace replaces the game's.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub sounds: Map<SoundDef>,
    /// The game's textures (`block/stone`) to draw with the pack's instead.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub textures: Map<TexturePath>,
    /// The game's models (`block/red_mushroom`) to draw with the pack's model files instead.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub models: Map<ModelPath>,
    /// The game's HUD elements it hides, and its own.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hud: Option<HudDef>,
    /// Built into a pack of its own that scripts send, not the one players get as they join.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub on_demand: Option<bool>,
}

/// A whole GUI background: a tall character at the front of a window's title.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SkinDef {
    pub texture: TexturePath,
    /// GUI pixels; a six-row chest is 222 tall.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 256, "x-problem" = "resource_pack.height"))]
    pub height: Option<i32>,
    /// Pixels from the picture's top to its baseline; at most its height.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = 13))]
    pub ascent: Option<i32>,
    /// How far left drawing starts. Default -8: flush with the window's edge.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2047, max = 2047), extend("default" = -8, "x-problem" = "resource_pack.offset"))]
    pub offset: Option<i32>,
    /// Editor hint: the container it was drawn for.
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<MenuType>,
    /// Editor hint: the chest rows it was drawn for.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rows: Option<i32>,
}

pub const SKIN_HEIGHT: i32 = 256;
pub const SKIN_ASCENT: i32 = 13;
pub const SKIN_OFFSET: i32 = -8;
pub const GLYPH_HEIGHT: i32 = 8;
pub const GLYPH_ASCENT: i32 = 7;

/// A small picture merged into the default font.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GlyphDef {
    pub texture: TexturePath,
    /// GUI pixels; 8 is a line of text.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 8, "x-problem" = "resource_pack.height"))]
    pub height: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = 7))]
    pub ascent: Option<i32>,
}

/// An item look, through the `item_model` component: a picture, a pack block's look, or a model file.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ItemModelDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub texture: Option<TexturePath>,
    /// A pack's block look (`ui/ruby_ore`) it's drawn as.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "block_model"))]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<ModelPath>,
    /// How a texture is held. Default generated.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<ItemModelParent>,
    /// A different picture used only in GUIs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gui_texture: Option<TexturePath>,
    /// Model files for the stages of drawing it, as a bow is drawn.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drawn: Option<Vec<ModelPath>>,
}

/// The game's `use_duration` scaled so one second (20 ticks) is 1.
pub const DRAW_SCALE: f64 = 0.05;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub enum ItemModelParent {
    /// A flat sprite.
    #[serde(rename = "minecraft:item/generated")]
    Generated,
    /// Held like a tool.
    #[serde(rename = "minecraft:item/handheld")]
    Handheld,
}

/// A block's cube: one texture for every face, or some faces' own.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BlockModelDef {
    /// A model file, for a block that isn't a plain cube, instead of textures.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<ModelPath>,
    /// Every face with no texture of its own.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub texture: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bottom: Option<TexturePath>,
    /// The four sides with no texture of their own.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub side: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub north: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub south: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub east: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub west: Option<TexturePath>,
}

impl BlockModelDef {
    /// Its textures by field, in the file's order.
    pub fn fields(&self) -> [(&'static str, Option<&TexturePath>); 8] {
        [
            ("texture", self.texture.as_ref()),
            ("top", self.top.as_ref()),
            ("bottom", self.bottom.as_ref()),
            ("side", self.side.as_ref()),
            ("north", self.north.as_ref()),
            ("south", self.south.as_ref()),
            ("east", self.east.as_ref()),
            ("west", self.west.as_ref()),
        ]
    }

    /// The texture on `face` (one of the game's), or `None` when nothing says.
    pub fn face_texture(&self, face: &str) -> Option<&TexturePath> {
        let side = || self.side.as_ref().or(self.texture.as_ref());
        match face {
            "up" => self.top.as_ref().or(self.texture.as_ref()),
            "down" => self.bottom.as_ref().or(self.texture.as_ref()),
            "north" => self.north.as_ref().or_else(side),
            "south" => self.south.as_ref().or_else(side),
            "east" => self.east.as_ref().or_else(side),
            "west" => self.west.as_ref().or_else(side),
            _ => None,
        }
    }

    /// The texture breaking it throws up.
    pub fn particle(&self) -> Option<&TexturePath> {
        let order = [
            &self.texture,
            &self.side,
            &self.top,
            &self.bottom,
            &self.north,
            &self.south,
            &self.east,
            &self.west,
        ];
        order.into_iter().flatten().next()
    }
}

/// A tooltip frame and/or backing, both nine-slice sprites.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct TooltipDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub background: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frame: Option<TexturePath>,
}

/// How an item looks worn: a texture per kind of wearer.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EquipmentAssetDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub humanoid: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub humanoid_leggings: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wings: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub horse_body: Option<TexturePath>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wolf_body: Option<TexturePath>,
}

impl EquipmentAssetDef {
    /// Each layer set, by its field and the folder the game draws it from, in a fixed order.
    pub fn layers(&self) -> Vec<(&'static str, &'static str, &TexturePath)> {
        let all = [
            ("humanoid", "humanoid", &self.humanoid),
            (
                "humanoidLeggings",
                "humanoid_leggings",
                &self.humanoid_leggings,
            ),
            ("wings", "wings", &self.wings),
            ("horseBody", "horse_body", &self.horse_body),
            ("wolfBody", "wolf_body", &self.wolf_body),
        ];
        all.into_iter()
            .filter_map(|(f, folder, t)| t.as_ref().map(|t| (f, folder, t)))
            .collect()
    }
}

/// One sound event beyond what its file alone says.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SoundDef {
    /// Paths under `sounds/`, picked from at random.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub files: Option<Vec<SoundPath>>,
    /// 1 is as recorded; above 1 it carries further.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "default" = 1, "x-problem" = "resource_pack.sound-volume"))]
    pub volume: Option<f64>,
    /// 1 is as recorded; the game clamps it to 0.5–2.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "default" = 1, "x-problem" = "resource_pack.sound-pitch"))]
    pub pitch: Option<f64>,
    /// Stream the files instead of loading them whole: for long tracks.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream: Option<bool>,
    /// What players with subtitles on read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subtitle: Option<String>,
}

impl SoundDef {
    fn sound(&self, key: &str, files: Vec<String>) -> sounds::Sound {
        sounds::Sound {
            key: key.to_owned(),
            files,
            volume: self.volume,
            pitch: self.pitch,
            subtitle: self.subtitle.clone(),
            stream: self.stream == Some(true),
        }
    }
}

/// The files under one of a resource's folders, relative to it.
fn under<'a>(files: &'a BTreeSet<String>, folder: &str) -> BTreeSet<&'a str> {
    let prefix = format!("{folder}/");
    files
        .iter()
        .filter_map(|f| f.strip_prefix(&prefix))
        .collect()
}

impl ResourcePackFile {
    /// The model files its looks name, each once, by path under `models/`.
    pub fn models_named(&self) -> Vec<&str> {
        let items = self
            .items
            .iter()
            .flat_map(|(_, i)| i.model.iter().chain(i.drawn.iter().flatten()));
        let blocks = self.blocks.iter().filter_map(|(_, b)| b.model.as_ref());
        let replaced = self.models.iter().map(|(_, m)| m);
        let mut seen = HashSet::new();
        items
            .chain(blocks)
            .chain(replaced)
            .map(|m| m.0.as_str())
            .filter(|m| models::is_model_path(m) && seen.insert(*m))
            .collect()
    }
}

/// What a pack's `pack.json` says against its folder's files (`ResourcePackValidator`).
struct Check<'a> {
    sink: &'a mut ProblemSink,
    textures: Option<BTreeSet<&'a str>>,
    models: Option<BTreeSet<&'a str>>,
}

impl Check<'_> {
    fn texture(&mut self, path: &TexturePath, at: &str) {
        if is_texture_path(path)
            && self
                .textures
                .as_ref()
                .is_some_and(|t| !t.contains(path.0.as_str()))
        {
            self.sink.report(
                codes::RESOURCE_PACK_TEXTURE_MISSING,
                format!("textures/{path} doesn't exist"),
                Some(at.into()),
            );
        }
    }

    fn model(&mut self, path: &ModelPath, at: &str) {
        if models::is_model_path(path)
            && self
                .models
                .as_ref()
                .is_some_and(|m| !m.contains(path.0.as_str()))
        {
            self.sink.report(
                codes::RESOURCE_PACK_MODEL_FILE,
                format!("{}/{path} doesn't exist", models::FOLDER),
                Some(at.into()),
            );
        }
    }

    fn ascent(&mut self, ascent: i32, height: i32, at: &str) {
        if ascent > height {
            self.sink.report(
                codes::RESOURCE_PACK_ASCENT,
                "ascent can't exceed height; Minecraft refuses to load it",
                Some(format!("{at}.ascent")),
            );
        }
    }
}

impl Document for ResourcePackFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/resource_pack.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let none = BTreeSet::new();
        let files = cx.files.unwrap_or(&none);
        // Sound files are checked in themselves; a bad one keeps the packs from building.
        let (sound_files, problems) = sounds::files(cx.location, under(files, sounds::FOLDER));
        sink.problems.extend(problems);
        let known = cx.files.is_some();
        let models = under(files, models::FOLDER);
        let mut check = Check {
            sink,
            textures: known.then(|| under(files, TEXTURES)),
            models: known.then(|| models.clone()),
        };
        self.check(&mut check, known.then_some(&sound_files));
        // Each model file a look names, checked in itself, once however many name it.
        for path in self
            .models_named()
            .into_iter()
            .filter(|m| models.contains(m))
        {
            let file = format!("{}/{path}", models::FOLDER);
            let at = format!("{}/{file}", cx.location);
            let Some(text) = cx.read.and_then(|read| read(&file)) else {
                continue;
            };
            match crate::read::parse::<serde_json::Value>(&text, &at) {
                Err(problem) => sink.problems.push(*problem),
                Ok(json) => sink.problems.extend(
                    models::validate(&json)
                        .into_iter()
                        .map(|m| m.code.at(&at, m.message, Some(m.path))),
                ),
            }
        }
    }
}

impl ResourcePackFile {
    fn check(&self, check: &mut Check, sound_files: Option<&BTreeSet<String>>) {
        if self.on_demand == Some(true) {
            // What every player's game must draw can't wait for a script to send it.
            let everyone: Vec<&str> = [
                (!self.blocks.is_empty()).then_some("blocks"),
                self.hud.is_some().then_some("hud"),
                (!self.textures.is_empty()).then_some("textures"),
                (!self.models.is_empty()).then_some("models"),
                self.sounds
                    .iter()
                    .any(|(k, _)| sounds::is_game_event(k))
                    .then_some("the game's sounds"),
            ]
            .into_iter()
            .flatten()
            .collect();
            if !everyone.is_empty() {
                check.sink.report(
                    codes::RESOURCE_PACK_ON_DEMAND,
                    format!(
                        "an onDemand pack can't have {}: every player's game needs them, so they go in a pack players get as they join",
                        everyone.join(" or ")
                    ),
                    Some("$.onDemand".into()),
                );
            }
        }
        for (key, skin) in self.skins.iter() {
            let at = child_path("$.skins", key);
            check.texture(&skin.texture, &format!("{at}.texture"));
            check.ascent(
                skin.ascent.unwrap_or(SKIN_ASCENT),
                skin.height.unwrap_or(SKIN_HEIGHT),
                &at,
            );
        }
        for (key, glyph) in self.glyphs.iter() {
            let at = child_path("$.glyphs", key);
            check.texture(&glyph.texture, &format!("{at}.texture"));
            check.ascent(
                glyph.ascent.unwrap_or(GLYPH_ASCENT),
                glyph.height.unwrap_or(GLYPH_HEIGHT),
                &at,
            );
        }
        for (key, item) in self.items.iter() {
            let at = child_path("$.items", key);
            if let Some(t) = &item.texture {
                check.texture(t, &format!("{at}.texture"));
            }
            if let Some(t) = &item.gui_texture {
                check.texture(t, &format!("{at}.guiTexture"));
            }
            if let Some(m) = &item.model {
                check.model(m, &format!("{at}.model"));
            }
            for (i, stage) in item.drawn.iter().flatten().enumerate() {
                check.model(stage, &format!("{at}.drawn[{i}]"));
            }
            let looks: Vec<&str> = [
                item.texture.is_some().then_some("texture"),
                item.block.is_some().then_some("block"),
                item.model.is_some().then_some("model"),
            ]
            .into_iter()
            .flatten()
            .collect();
            let (message, path) = if looks.is_empty() {
                (
                    "Give this item a texture, a block it's drawn as, or a model".to_owned(),
                    at.clone(),
                )
            } else if item.drawn.as_ref().is_some_and(Vec::is_empty) {
                (
                    "`drawn` lists the models of each draw stage, so it needs at least one"
                        .to_owned(),
                    format!("{at}.drawn"),
                )
            } else if looks.len() > 1 {
                (
                    format!(
                        "An item is drawn as one of a texture, a block and a model: it has {}",
                        looks.join(" and ")
                    ),
                    format!("{at}.{}", looks[looks.len() - 1]),
                )
            } else if item.parent.is_some() && item.texture.is_none() {
                ("Only a texture is held as `parent` says: a block or model is held as it is, so drop `parent`".to_owned(), format!("{at}.parent"))
            } else {
                continue;
            };
            check
                .sink
                .report(codes::RESOURCE_PACK_ITEM_LOOK, message, Some(path));
        }
        for (key, tooltip) in self.tooltips.iter() {
            let at = child_path("$.tooltips", key);
            if let Some(t) = &tooltip.background {
                check.texture(t, &format!("{at}.background"));
            }
            if let Some(t) = &tooltip.frame {
                check.texture(t, &format!("{at}.frame"));
            }
            if tooltip.background.is_none() && tooltip.frame.is_none() {
                check.sink.report(
                    codes::RESOURCE_PACK_TOOLTIP_EMPTY,
                    "This tooltip has neither a background nor a frame, so it looks like vanilla",
                    Some(at),
                );
            }
        }
        for (key, look) in self.equipment.iter() {
            let at = child_path("$.equipment", key);
            let layers = look.layers();
            for (field, _, path) in &layers {
                check.texture(path, &format!("{at}.{field}"));
            }
            if layers.is_empty() {
                check.sink.report(
                    codes::RESOURCE_PACK_EQUIPMENT_EMPTY,
                    "This equipment look has no layer, so nothing is drawn when it's worn",
                    Some(at),
                );
            }
        }
        for (key, block) in self.blocks.iter() {
            let at = child_path("$.blocks", key);
            for (field, path) in block.fields() {
                if let Some(path) = path {
                    check.texture(path, &format!("{at}.{field}"));
                }
            }
            if let Some(model) = &block.model {
                check.model(model, &format!("{at}.model"));
                if block.fields().iter().any(|(_, t)| t.is_some()) {
                    check.sink.report(
                        codes::RESOURCE_PACK_BLOCK_FACES,
                        "A block look is a model or textures, not both: the model's own textures are what it's drawn with",
                        Some(format!("{at}.model")),
                    );
                }
            } else {
                let missing: Vec<&str> = models::FACES
                    .into_iter()
                    .filter(|f| block.face_texture(f).is_none())
                    .collect();
                if !missing.is_empty() {
                    let s = if missing.len() == 1 { "" } else { "s" };
                    check.sink.report(
                        codes::RESOURCE_PACK_BLOCK_FACES,
                        format!("This block look has no texture for its {} face{s}: give it a texture, or a texture for each face", missing.join(", ")),
                        Some(at),
                    );
                }
            }
        }
        // A replaced sprite, texture or model is written to a path in the built pack, so its name is a path's shape.
        let named = |check: &mut Check, at: &str, name: &str, what: &str| {
            if !is_texture_path(&format!("{name}{TEXTURE_EXTENSION}")) {
                check.sink.report(
                    codes::RESOURCE_PACK_REPLACE_NAME,
                    format!("\"{name}\" isn't the name of {what}"),
                    Some(at.into()),
                );
            }
        };
        const TEXTURE: &str = "a game texture: a path like hud/hotbar, without .png";
        let sprites = self
            .hud
            .iter()
            .flat_map(|h| h.replace.iter())
            .map(|e| ("$.hud.replace", e));
        for (map, (name, picture)) in self
            .textures
            .iter()
            .map(|e| ("$.textures", e))
            .chain(sprites)
        {
            let at = child_path(map, name);
            named(check, &at, name, TEXTURE);
            check.texture(picture, &at);
        }
        for (name, model) in self.models.iter() {
            let at = child_path("$.models", name);
            named(
                check,
                &at,
                name,
                "a game model: a path like block/red_mushroom, without .json",
            );
            check.model(model, &at);
        }
        if let Some(hud) = &self.hud {
            let mut seen = HashSet::new();
            for (map, key) in hud
                .bars
                .iter()
                .map(|(k, _)| ("bars", k))
                .chain(hud.icons.iter().map(|(k, _)| ("icons", k)))
                .chain(hud.texts.iter().map(|(k, _)| ("texts", k)))
            {
                if !seen.insert(key.0.as_str()) {
                    check.sink.report(
                        codes::RESOURCE_PACK_HUD,
                        format!("\"{key}\" names another of this pack's HUD elements too: scripts name them by key"),
                        Some(child_path(&format!("$.hud.{map}"), key)),
                    );
                }
            }
            for (key, bar) in hud.bars.iter() {
                let at = child_path("$.hud.bars", key);
                check.texture(&bar.full, &format!("{at}.full"));
                check.texture(&bar.empty, &format!("{at}.empty"));
                if let Some(half) = &bar.half {
                    check.texture(half, &format!("{at}.half"));
                }
            }
            for (key, row) in hud.icons.iter() {
                let at = child_path("$.hud.icons", key);
                if row.icons.is_empty() {
                    check.sink.report(
                        codes::RESOURCE_PACK_HUD,
                        "A row of icons needs an icon to show",
                        Some(format!("{at}.icons")),
                    );
                }
                for (icon, texture) in row.icons.iter() {
                    check.texture(texture, &child_path(&format!("{at}.icons"), icon));
                }
            }
        }
        for (key, sound) in self.sounds.iter() {
            let at = child_path("$.sounds", key);
            let game = sounds::is_game_key(key);
            if game && !sounds::is_game_event(key) {
                check.sink.report(
                    codes::RESOURCE_PACK_SOUND_KEY,
                    format!("\"{key}\" isn't one of the game's sound events: they're written like \"minecraft:block.stone.break\""),
                    Some(at.clone()),
                );
            } else if !game && !sounds::is_key(key) {
                let hint = if key.0.contains(':') {
                    "; the game's own sound events are replaced by their name in the minecraft namespace"
                } else {
                    ""
                };
                check.sink.report(
                    codes::RESOURCE_PACK_SOUND_KEY,
                    format!(
                        "\"{key}\" isn't a usable sound key ({}){hint}",
                        sounds::KEY_RULE
                    ),
                    Some(at.clone()),
                );
            }
            match &sound.files {
                None if game => check.sink.report(
                    codes::RESOURCE_PACK_SOUND_REPLACE,
                    format!("Replacing the game's {key} takes files to play instead: \"files\": [\"block/break_1.ogg\"]"),
                    Some(at),
                ),
                None => {
                    if sound_files.is_some_and(|s| sounds::is_key(key) && !s.contains(&format!("{key}{}", sounds::EXTENSION))) {
                        check.sink.report(
                            codes::RESOURCE_PACK_SOUND_MISSING,
                            format!("sounds/{key}{} doesn't exist, and this sound lists no files to play instead", sounds::EXTENSION),
                            Some(at),
                        );
                    }
                }
                Some(files) => {
                    if files.is_empty() {
                        check.sink.report(codes::RESOURCE_PACK_SOUND_FILES, "files lists no sound files", Some(format!("{at}.files")));
                    }
                    for (index, path) in files.iter().enumerate() {
                        if sounds::is_file(path) && sound_files.is_some_and(|s| !s.contains(&path.0)) {
                            check.sink.report(codes::RESOURCE_PACK_SOUND_MISSING, format!("sounds/{path} doesn't exist"), Some(format!("{at}.files[{index}]")));
                        }
                    }
                }
            }
        }
        if self.skins.0.len() > PUA_COUNT {
            check.sink.report(
                codes::RESOURCE_PACK_TOO_MANY,
                format!("A resource pack holds at most {PUA_COUNT} skins"),
                Some("$.skins".into()),
            );
        }
    }
}
