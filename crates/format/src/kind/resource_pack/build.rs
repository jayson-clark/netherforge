//! The files of the built resource pack, from every pack of a project (format's
//! `PackLayout`, `VideoSound` and the display glyphs' pictures). Every pack's
//! assets go into its namespace under the pack's id: pack `ui`'s glyph texture
//! `coin.png` is `assets/<namespace>/textures/ui/coin.png`, its item look `ruby`
//! the item `<namespace>:ui/ruby`, its sound `menu/open` the event
//! `<namespace>:ui/menu/open`, so a reference resolved (`shop:ui/ruby`) is the
//! key the client looks up. The server zips this (with fixed entry dates, so
//! the hash only changes when the content does) and the editor shows it; a
//! texture's bytes never pass through here.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value, json};

use super::compiled::{CompiledResourcePack, texture_path};
use super::models::Model;
use super::sounds::{self, Sound};
use super::{BlockModelDef, ItemModelParent, fonts};
use crate::kind::carriers::Plan;
use crate::project::Project;
use crate::vanilla::VanillaFile;

/// One file of the built pack.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Entry {
    Text(String),
    /// A project file (a PNG) copied as it is, by project or package path.
    Copy(String),
    /// Bytes of the build's own (a blank picture, the pixel glyphs).
    Bytes(Vec<u8>),
}

fn text(value: &Value) -> Entry {
    Entry::Text(crate::json::print(value) + "\n")
}

/// A one-pixel PNG, fully transparent: what a hidden HUD sprite draws.
pub const BLANK_PNG: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41, 0x54, 0x78, 0xDA, 0x63, 0x60, 0x00, 0x02, 0x00,
    0x00, 0x05, 0x00, 0x01, 0xE9, 0xFA, 0xDC, 0xD8, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
    0xAE, 0x42, 0x60, 0x82,
];

/// The border vanilla's own tooltip sprites use.
pub const TOOLTIP_BORDER: i32 = 9;
/// The look an invisible item is drawn as (`ItemDef.invisible`).
pub const INVISIBLE_MODEL: &str = "netherforge:invisible";
/// What follows a block entry's key in the model of a block with no cube.
const HIDDEN: &str = "hidden";
const BLOCK_MODELS: &str = "block/";

/// A video's converted sound for the pack: the video `id` of `namespace`, whose whole sound is `project_path`
/// and whose pieces are `chunks`, in order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VideoSound {
    pub namespace: String,
    pub id: String,
    pub project_path: String,
    pub chunks: Vec<String>,
}

impl VideoSound {
    /// Every running video of the project and its packages that keeps a sound and has one converted.
    pub fn of(project: &Project) -> Vec<VideoSound> {
        use crate::kind::video::{self, VideoFile};
        let mut out = Vec::new();
        for (name, resource) in project.everywhere("video") {
            if resource.errors {
                continue;
            }
            let Some(file) = resource.model::<VideoFile>() else {
                continue;
            };
            if !file.audio.unwrap_or(true) || !resource.found.inside.contains(video::AUDIO) {
                continue;
            }
            let (namespace, id) = match name.split_once(':') {
                Some((ns, id)) => (ns.to_owned(), id.to_owned()),
                None => (project.namespace().to_owned(), name.clone()),
            };
            let Some(place) = crate::startup::location(project, "video", &name) else {
                continue;
            };
            // Every piece from the first on, stopping at a gap: a piece after a missing one could never be reached.
            let pieces: BTreeSet<u32> = resource
                .found
                .inside
                .iter()
                .filter_map(|f| video::chunk_of(f))
                .collect();
            let chunks = (0..)
                .take_while(|i| pieces.contains(i))
                .map(|i| format!("{place}/{}", video::chunk_file(i)))
                .collect();
            out.push(VideoSound {
                namespace,
                id,
                project_path: format!("{place}/{}", video::AUDIO),
                chunks,
            });
        }
        out
    }
}

/// Whether `project` puts anything in the resource pack: a pack, a block (the states it's held as are drawn by
/// the pack) or a video's sound, the packages' included.
pub fn needed(project: &Project) -> bool {
    !project.everywhere("resource_pack").is_empty()
        || !project.everywhere("block").is_empty()
        || !VideoSound::of(project).is_empty()
}

/// Where a pack's sound file (under `sounds/`) is in the project, or for pack `ns:id` at its package path.
pub fn sound_path(pack: &str, file: &str) -> String {
    let (package, id) = match pack.split_once(':') {
        Some((ns, id)) => (Some(ns), id),
        None => (None, pack),
    };
    let local = format!("resource_packs/{id}/{}/{file}", sounds::FOLDER);
    package.map_or_else(
        || local.clone(),
        |ns| crate::kinds::package_path(ns, &local),
    )
}

impl CompiledResourcePack {
    /// Every texture (under `textures/`) this pack refers to, which the built pack keeps at `textures/<id>/…`:
    /// the equipment layers aren't among them, they're copied to where the game draws them from.
    pub fn textures(&self) -> BTreeSet<String> {
        let mut out = BTreeSet::new();
        let model = |name: &str, out: &mut BTreeSet<String>| {
            if let Some(m) = self.models.get(name) {
                out.extend(m.pack_textures().into_iter().map(str::to_owned));
            }
        };
        for skin in self.skins.values() {
            out.insert(skin.def.texture.0.clone());
        }
        for glyph in self.glyphs.values() {
            out.insert(glyph.def.texture.0.clone());
        }
        for item in self.items.values() {
            out.extend(
                item.texture
                    .iter()
                    .chain(&item.gui_texture)
                    .map(|t| t.0.clone()),
            );
            if let Some(m) = &item.model {
                model(m, &mut out);
            }
            for drawn in item.drawn.iter().flatten() {
                model(drawn, &mut out);
            }
        }
        for tooltip in self.tooltips.values() {
            out.extend(
                tooltip
                    .background
                    .iter()
                    .chain(&tooltip.frame)
                    .map(|t| t.0.clone()),
            );
        }
        for block in self.blocks.values() {
            out.extend(
                block
                    .fields()
                    .into_iter()
                    .filter_map(|(_, t)| t.map(|t| t.0.clone())),
            );
            if let Some(m) = &block.model {
                model(m, &mut out);
            }
        }
        if let Some(hud) = &self.hud {
            out.extend(hud.pieces().into_iter().map(|p| p.texture.clone()));
        }
        for file in self.model_replacements.values() {
            model(file, &mut out);
        }
        out
    }

    /// The texture breaking block look `block` throws up: a model's particle, or the faces'.
    pub fn particle_of(&self, block: &BlockModelDef) -> Option<String> {
        match block.model.as_ref().and_then(|m| self.models.get(&m.0)) {
            Some(model) => model.particle().map(str::to_owned),
            None => block.particle().map(|t| t.0.clone()),
        }
    }

    /// Every sound file (under `sounds/`) this pack's events play, and the game's events it replaces.
    pub fn sound_files(&self) -> BTreeSet<String> {
        self.sounds
            .values()
            .chain(self.sound_replacements.values())
            .flat_map(|s| s.files.iter().cloned())
            .collect()
    }
}

/// `model` as the built pack holds it: every texture variable naming a pack texture written as the client's
/// resource, the rest as written.
pub fn write_model(model: &Model, resource: &dyn Fn(&str) -> String) -> Value {
    if model.textures.is_empty() {
        return model.json.clone();
    }
    let mut out = model.json.clone();
    if let Some(vars) = out.get_mut("textures").and_then(Value::as_object_mut) {
        for (name, value) in vars.iter_mut() {
            if let Some(texture) = model.textures.get_str(name) {
                *value = json!(resource(texture));
            }
        }
    }
    out
}

/// The faces of a block look by the game's name, when every face has a texture.
fn faces(block: &BlockModelDef) -> Option<Vec<(&'static str, String)>> {
    super::models::FACES
        .iter()
        .map(|f| block.face_texture(f).map(|t| (*f, t.0.clone())))
        .collect()
}

fn object(entries: impl IntoIterator<Item = (String, Value)>) -> Value {
    Value::Object(entries.into_iter().collect::<Map<String, Value>>())
}

/// A bitmap provider: `cells` are the characters of its one row, the picture cut into that many equal pieces.
fn bitmap(file: &str, height: i32, ascent: i32, cells: &str) -> Value {
    json!({ "type": "bitmap", "file": file, "height": height, "ascent": ascent, "chars": [cells] })
}

/// The `space` provider: every space character's move.
fn space() -> Value {
    json!({ "type": "space", "advances": object(fonts::advances().into_iter().map(|(c, px)| (c, json!(px)))) })
}

/// A `sounds.json` event: its files as `<location>/<path without .ogg>`, a plain name unless volume, pitch or
/// stream needs the object form.
fn sound_event(location: &str, sound: &Sound) -> Vec<(String, Value)> {
    let files: Vec<Value> = sound
        .files
        .iter()
        .map(|file| {
            let name = format!("{location}/{}", sounds::key_of(file));
            if sound.volume.is_none() && sound.pitch.is_none() && !sound.stream {
                return json!(name);
            }
            let mut out = vec![("name".to_owned(), json!(name))];
            if let Some(v) = sound.volume {
                out.push(("volume".into(), json!(v)));
            }
            if let Some(p) = sound.pitch {
                out.push(("pitch".into(), json!(p)));
            }
            if sound.stream {
                out.push(("stream".into(), json!(true)));
            }
            object(out)
        })
        .collect();
    let mut out = vec![("sounds".to_owned(), Value::Array(files))];
    if let Some(subtitle) = &sound.subtitle {
        out.push(("subtitle".into(), json!(subtitle)));
    }
    out
}

fn item_model(parent: Option<ItemModelParent>, layer0: &str) -> Value {
    json!({ "parent": crate::canonical::name_of(&parent.unwrap_or(ItemModelParent::Generated)), "textures": { "layer0": layer0 } })
}

/// An atlas file adding `sprites` to one of the game's: a model's textures are looked up in an atlas, and the
/// game's own only stitch `textures/block/` and `textures/item/`.
fn atlas(sprites: &BTreeSet<String>) -> Entry {
    let sources: Vec<Value> = sprites
        .iter()
        .map(|s| json!({ "type": "minecraft:single", "resource": s }))
        .collect();
    text(&json!({ "sources": sources }))
}

/// Every file of the pack built from `packs`, for resource pack `format`: `description` in its `pack.mcmeta`,
/// `blocks` the states the project's blocks are held in, `vanilla` how the client draws the game's own, `videos`
/// the sounds videos play, and `texture_size` a texture's pixel size (for nine-slice tooltips), by project path.
pub fn build(
    packs: &[&CompiledResourcePack],
    format: [i32; 2],
    description: &str,
    blocks: Option<&Plan>,
    vanilla: Option<&VanillaFile>,
    videos: &[VideoSound],
    texture_size: &dyn Fn(&str) -> Option<(u32, u32)>,
) -> BTreeMap<String, Entry> {
    let mut out: BTreeMap<String, Entry> = BTreeMap::new();
    // The game's models the packs may redraw: only those the client says it has.
    let known_models: BTreeSet<&str> = vanilla
        .iter()
        .flat_map(|v| v.models.iter())
        .map(String::as_str)
        .collect();
    out.insert(
        "pack.mcmeta".into(),
        text(&json!({ "pack": { "description": description, "min_format": format, "max_format": format } })),
    );
    let mut default_font: Vec<Value> = Vec::new();
    // One sounds.json per namespace, holding every pack's events.
    let mut sound_events: BTreeMap<String, BTreeMap<String, Value>> = BTreeMap::new();
    let (mut block_sprites, mut item_sprites) = (BTreeSet::new(), BTreeSet::new());
    let mut ordered: Vec<&&CompiledResourcePack> = packs.iter().collect();
    ordered.sort_by(|a, b| (&a.namespace, &a.id).cmp(&(&b.namespace, &b.id)));
    for pack in &ordered {
        let (ns, id) = (pack.namespace.as_str(), pack.id.as_str());
        let source = |texture: &str| texture_path(&pack.key, texture);
        let resource = |texture: &str| {
            format!(
                "{ns}:{id}/{}",
                texture.strip_suffix(".png").unwrap_or(texture)
            )
        };
        for texture in pack.textures() {
            out.insert(
                format!("assets/{ns}/textures/{id}/{texture}"),
                Entry::Copy(source(&texture)),
            );
        }
        if !pack.skins.is_empty() {
            let mut providers: Vec<Value> = pack
                .skins
                .values()
                .map(|s| {
                    bitmap(
                        &format!("{ns}:{id}/{}", s.def.texture.0),
                        s.height(),
                        s.ascent(),
                        &s.cells,
                    )
                })
                .collect();
            providers.push(space());
            out.insert(
                format!("assets/{ns}/font/{id}/{}.json", fonts::GUI_FONT),
                text(&json!({ "providers": providers })),
            );
        }
        for glyph in pack.glyphs.values() {
            default_font.push(bitmap(
                &format!("{ns}:{id}/{}", glyph.def.texture.0),
                glyph.height(),
                glyph.ascent(),
                &glyph.cells,
            ));
        }
        // The HUD's pictures, each at its height on the screen, and the moves that put them across it.
        if let Some(hud) = pack.hud.as_ref().filter(|h| !h.pieces().is_empty()) {
            let mut providers: Vec<Value> = hud
                .pieces()
                .into_iter()
                .map(|p| {
                    bitmap(
                        &format!("{ns}:{id}/{}", p.texture),
                        p.height,
                        p.ascent,
                        &p.cells,
                    )
                })
                .collect();
            providers.push(space());
            out.insert(
                format!("assets/{ns}/font/{id}/{}.json", super::hud::FONT),
                text(&json!({ "providers": providers })),
            );
        }
        for (key, item) in &pack.items {
            let model = format!("{ns}:item/{id}/{key}");
            // A block look is the block's model as the item's parent; a model file is as written.
            let shaped = item.model.as_ref().and_then(|m| pack.models.get(&m.0));
            let written = if let Some(shaped) = shaped {
                item_sprites.extend(shaped.pack_textures().into_iter().map(&resource));
                write_model(shaped, &resource)
            } else if let Some(block) = &item.block {
                let key = crate::reference::resolve_any(ns, block).expect("a checked block look");
                json!({ "parent": format!("{}:block/{}", key.namespace, key.path) })
            } else {
                let texture = &item
                    .texture
                    .as_ref()
                    .expect("an item look with a picture")
                    .0;
                item_sprites.insert(resource(texture));
                item_model(item.parent, &resource(texture))
            };
            out.insert(
                format!("assets/{ns}/models/item/{id}/{key}.json"),
                text(&written),
            );
            if let Some(gui) = &item.gui_texture {
                item_sprites.insert(resource(gui));
            }
            // The look a stack shows at rest: the model, or the GUI's picture in inventories and item frames.
            let look = match &item.gui_texture {
                None => json!({ "type": "minecraft:model", "model": model }),
                Some(gui) => {
                    let gui_model = format!("{ns}:item/{id}/{key}_gui");
                    out.insert(
                        format!("assets/{ns}/models/item/{id}/{key}_gui.json"),
                        text(&item_model(
                            Some(ItemModelParent::Generated),
                            &resource(gui),
                        )),
                    );
                    json!({
                        "type": "minecraft:select",
                        "property": "minecraft:display_context",
                        "cases": [{ "when": ["gui", "fixed"], "model": { "type": "minecraft:model", "model": gui_model } }],
                        "fallback": { "type": "minecraft:model", "model": model },
                    })
                }
            };
            let definition = match item.drawn.as_ref().filter(|d| !d.is_empty()) {
                None => json!({ "model": look }),
                Some(drawn) => {
                    // Each stage is a model of its own, in the item's folder, as its GUI picture is.
                    let stages: Vec<Value> = drawn
                        .iter()
                        .enumerate()
                        .map(|(i, name)| {
                            let stage = format!("{key}_drawn{i}");
                            let m = pack.models.get(&name.0).expect("a drawn stage's model");
                            out.insert(format!("assets/{ns}/models/item/{id}/{stage}.json"), text(&write_model(m, &resource)));
                            item_sprites.extend(m.pack_textures().into_iter().map(&resource));
                            json!({ "type": "minecraft:model", "model": format!("{ns}:item/{id}/{stage}") })
                        })
                        .collect();
                    // Stage i starts at i / (last) of a full draw (a second's use, at the game's scale).
                    let last = (stages.len() - 1).max(1) as f64;
                    let entries: Vec<Value> = stages[1..]
                        .iter()
                        .enumerate()
                        .map(|(i, stage)| json!({ "threshold": (i + 1) as f64 / last, "model": stage }))
                        .collect();
                    json!({ "model": {
                        "type": "minecraft:condition",
                        "property": "minecraft:using_item",
                        "on_false": look,
                        "on_true": {
                            "type": "minecraft:range_dispatch",
                            "property": "minecraft:use_duration",
                            "scale": super::DRAW_SCALE,
                            "entries": entries,
                            "fallback": stages[0],
                        },
                    } })
                }
            };
            out.insert(
                format!("assets/{ns}/items/{id}/{key}.json"),
                text(&definition),
            );
        }
        if !pack.sounds.is_empty() || !pack.sound_replacements.is_empty() {
            for file in pack.sound_files() {
                out.insert(
                    format!("assets/{ns}/sounds/{id}/{file}"),
                    Entry::Copy(sound_path(&pack.key, &file)),
                );
            }
            let location = format!("{ns}:{id}");
            for sound in pack.sounds.values() {
                sound_events.entry(ns.to_owned()).or_default().insert(
                    format!("{id}/{}", sound.key),
                    object(sound_event(&location, sound)),
                );
            }
            // The game's own events, in its sounds.json: `replace` drops the game's files, so only the pack's play.
            for (event, sound) in &pack.sound_replacements {
                let (namespace, path) = event.split_once(':').expect("a game event in full");
                let mut entry = vec![("replace".to_owned(), json!(true))];
                entry.extend(sound_event(&location, sound));
                sound_events
                    .entry(namespace.to_owned())
                    .or_default()
                    .insert(path.to_owned(), object(entry));
            }
        }
        // The game's models the pack redraws, written over the game's.
        for (name, file) in &pack.model_replacements {
            if !known_models.contains(name.as_str()) {
                continue;
            }
            let Some(model) = pack.models.get(file) else {
                continue;
            };
            out.insert(
                format!("assets/minecraft/models/{name}.json"),
                text(&write_model(model, &resource)),
            );
            let sprites = model.pack_textures().into_iter().map(&resource);
            if name.starts_with(BLOCK_MODELS) {
                block_sprites.extend(sprites);
            } else {
                item_sprites.extend(sprites);
            }
        }
        for (key, block) in &pack.blocks {
            let shaped = block.model.as_ref().and_then(|m| pack.models.get(&m.0));
            let model = match shaped {
                Some(m) => write_model(m, &resource),
                None => {
                    let mut textures = Vec::new();
                    if let Some(p) = block.particle() {
                        textures.push(("particle".to_owned(), json!(resource(p))));
                    }
                    for (face, texture) in faces(block).unwrap_or_default() {
                        textures.push((face.to_owned(), json!(resource(&texture))));
                    }
                    json!({ "parent": "minecraft:block/cube", "textures": object(textures) })
                }
            };
            out.insert(
                format!("assets/{ns}/models/block/{id}/{key}.json"),
                text(&model),
            );
            if let Some(p) = pack.particle_of(block) {
                block_sprites.insert(resource(&p));
            }
            for (_, texture) in faces(block).unwrap_or_default() {
                block_sprites.insert(resource(&texture));
            }
            if let Some(m) = shaped {
                block_sprites.extend(m.pack_textures().into_iter().map(&resource));
            }
            let hidden = blocks.is_some_and(|plan| {
                plan.uses.iter().any(|u| {
                    u.hidden
                        && u.pack.as_deref() == Some(&pack.key)
                        && u.entry.as_deref() == Some(key)
                })
            });
            if hidden {
                let particle = pack
                    .particle_of(block)
                    .map(|p| json!({ "particle": resource(&p) }))
                    .unwrap_or_else(|| json!({}));
                out.insert(
                    format!("assets/{ns}/models/block/{id}/{key}_{HIDDEN}.json"),
                    text(&json!({ "textures": particle })),
                );
            }
        }
        for (key, look) in &pack.equipment {
            let layers = look.layers();
            for (_, layer, texture) in &layers {
                out.insert(
                    format!("assets/{ns}/textures/entity/equipment/{layer}/{id}/{key}.png"),
                    Entry::Copy(source(texture)),
                );
            }
            let entries = layers.iter().map(|(_, layer, _)| {
                (
                    (*layer).to_owned(),
                    json!([{ "texture": format!("{ns}:{id}/{key}") }]),
                )
            });
            out.insert(
                format!("assets/{ns}/equipment/{id}/{key}.json"),
                text(&json!({ "layers": object(entries) })),
            );
        }
        for (key, tooltip) in &pack.tooltips {
            for (part, texture) in [
                ("background", &tooltip.background),
                ("frame", &tooltip.frame),
            ] {
                let Some(texture) = texture else { continue };
                let sprite =
                    format!("assets/{ns}/textures/gui/sprites/tooltip/{id}/{key}_{part}.png");
                out.insert(sprite.clone(), Entry::Copy(source(texture)));
                let (width, height) = texture_size(&source(texture)).unwrap_or((100, 100));
                let meta = json!({ "gui": { "scaling": { "type": "nine_slice", "width": width, "height": height, "border": TOOLTIP_BORDER } } });
                out.insert(format!("{sprite}.mcmeta"), text(&meta));
            }
        }
    }
    // The game's sprites and textures the packs redraw, which only the client lists; a later pack's wins.
    let sprites: BTreeSet<&str> = vanilla
        .iter()
        .flat_map(|v| v.sprites.iter())
        .map(String::as_str)
        .collect();
    let textures: BTreeSet<&str> = vanilla
        .iter()
        .flat_map(|v| v.textures.iter())
        .map(|(k, _)| k.0.as_str())
        .collect();
    for (is_sprite, known) in [(true, &sprites), (false, &textures)] {
        for pack in &ordered {
            let replaced = if is_sprite {
                &pack.sprite_replacements
            } else {
                &pack.texture_replacements
            };
            for (name, replacement) in replaced {
                if !known.contains(name.as_str()) {
                    continue;
                }
                let target = format!(
                    "assets/minecraft/textures/{}{name}.png",
                    if is_sprite { "gui/sprites/" } else { "" }
                );
                let source = texture_path(&pack.key, &replacement.texture);
                out.insert(target.clone(), Entry::Copy(source.clone()));
                if replacement.meta {
                    out.insert(
                        format!("{target}.mcmeta"),
                        Entry::Copy(format!("{source}.mcmeta")),
                    );
                } else {
                    out.remove(&format!("{target}.mcmeta"));
                }
            }
        }
    }
    // The game's own HUD elements the packs hide: every sprite that draws one, blank.
    let hidden: Vec<super::hud::HudElement> = packs
        .iter()
        .flat_map(|p| p.hud.iter().flat_map(|h| h.hide.iter().copied()))
        .collect();
    for sprite in &sprites {
        let path = format!("assets/minecraft/textures/gui/sprites/{sprite}.png");
        if hidden.iter().any(|e| e.draws(sprite)) && !out.contains_key(&path) {
            out.insert(path, Entry::Bytes(BLANK_PNG.to_vec()));
        }
    }
    if let Some(plan) = blocks {
        out.extend(block_states(plan, packs, vanilla));
        // A block drawn by a centity that has no look of its own shows nothing and throws up no particles.
        let unlooked = plan.uses.iter().any(|u| {
            u.hidden
                && !packs.iter().any(|p| {
                    Some(&p.key) == u.pack.as_ref()
                        && u.entry.as_ref().is_some_and(|e| p.blocks.contains_key(e))
                })
        });
        if unlooked {
            out.insert(
                format!("assets/{}/models/block/{HIDDEN}.json", plan.home),
                text(&json!({ "textures": {} })),
            );
        }
    }
    if !block_sprites.is_empty() {
        out.insert(
            "assets/minecraft/atlases/blocks.json".into(),
            atlas(&block_sprites),
        );
    }
    // A sprite in both atlases is drawn from one only, so item models take the blocks atlas's, as vanilla's do.
    let items_only: BTreeSet<String> = item_sprites.difference(&block_sprites).cloned().collect();
    if !items_only.is_empty() {
        out.insert(
            "assets/minecraft/atlases/items.json".into(),
            atlas(&items_only),
        );
    }
    // Each video's sound, streamed as music is: `video.<id>`, which no pack's `<pack>/<key>` can be.
    let mut videos: Vec<&VideoSound> = videos.iter().collect();
    videos.sort_by(|a, b| (&a.namespace, &a.id).cmp(&(&b.namespace, &b.id)));
    for video in videos {
        let events = sound_events.entry(video.namespace.clone()).or_default();
        let mut sound = |key: String, file: String, source: &str| {
            out.insert(
                format!("assets/{}/sounds/{file}.ogg", video.namespace),
                Entry::Copy(source.to_owned()),
            );
            events.insert(key, json!({ "sounds": [{ "name": format!("{}:{file}", video.namespace), "stream": true }] }));
        };
        let key = crate::kind::video::sound_key(&video.id);
        sound(key.clone(), key.clone(), &video.project_path);
        for (index, source) in video.chunks.iter().enumerate() {
            sound(
                crate::kind::video::chunk_key(&video.id, index as u32),
                format!("{key}/{index}"),
                source,
            );
        }
    }
    for (ns, events) in sound_events {
        out.insert(format!("assets/{ns}/sounds.json"), text(&object(events)));
    }
    let (invisible_ns, invisible) = INVISIBLE_MODEL.split_once(':').expect("a key in full");
    out.insert(
        format!("assets/{invisible_ns}/items/{invisible}.json"),
        text(&json!({ "model": { "type": "minecraft:empty" } })),
    );
    // The engine's pixel glyphs, which draw image displays.
    for (path, bytes) in super::display::pictures() {
        out.insert(path, Entry::Bytes(bytes));
    }
    for (file, ch) in super::display::providers() {
        default_font.push(bitmap(
            &file,
            super::display::PIXEL_HEIGHT,
            super::display::TOP_ASCENT,
            &ch.to_string(),
        ));
    }
    // The space characters too, so any text can move the cursor (`<space:N>`, `<at:N>`).
    default_font.push(space());
    out.insert(
        "assets/minecraft/font/default.json".into(),
        text(&json!({ "providers": default_font })),
    );
    out
}

/// The blockstate file of each of the game's blocks a custom block is held in: every state as the game draws it
/// but the ones a custom block holds, which are that block's model. A carrier whose states the game draws with
/// more than one model takes how from `vanilla`; without it there, its file is left out.
fn block_states(
    plan: &Plan,
    packs: &[&CompiledResourcePack],
    vanilla: Option<&VanillaFile>,
) -> Vec<(String, Entry)> {
    let models: BTreeMap<String, (String, Option<crate::kind::block::BlockFacing>)> = plan
        .uses
        .iter()
        .filter_map(|u| {
            let pack = packs.iter().find(|p| {
                Some(&p.key) == u.pack.as_ref()
                    && u.entry.as_ref().is_some_and(|e| p.blocks.contains_key(e))
            });
            let model = match pack {
                Some(p) => format!(
                    "{}:block/{}/{}{}",
                    p.namespace,
                    p.id,
                    u.entry.as_deref().unwrap_or(""),
                    if u.hidden { "_hidden" } else { "" }
                ),
                None if u.hidden => format!("{}:block/{HIDDEN}", plan.home),
                None => return None,
            };
            Some((u.state.text(), (model, u.facing)))
        })
        .collect();
    // Only the game's blocks some custom block is held in.
    let used: BTreeSet<&str> = plan.uses.iter().map(|u| u.state.id.as_str()).collect();
    let mut out = Vec::new();
    for pool in plan.pools.values() {
        for (block, states) in &pool.blocks {
            if !used.contains(block.as_str()) {
                continue;
            }
            let single = pool.vanilla_model(block);
            let client = vanilla.and_then(|v| v.blockstates.get_str(block));
            if single.is_none() && client.is_none() {
                continue;
            }
            let mut variants = Map::new();
            for state in states {
                let key = state
                    .properties
                    .iter()
                    .map(|(k, v)| format!("{k}={v}"))
                    .collect::<Vec<_>>()
                    .join(",");
                if let Some((model, facing)) = models.get(&state.text()) {
                    let mut variant = vec![("model".to_owned(), json!(model))];
                    // A block that turns: its north-facing model, turned as the game turns its own.
                    if let Some(facing) = facing {
                        let (x, y) = facing.rotation();
                        if x != 0 {
                            variant.push(("x".into(), json!(x)));
                        }
                        if y != 0 {
                            variant.push(("y".into(), json!(y)));
                        }
                    }
                    variants.insert(key, object(variant));
                } else if let Some(single) = &single {
                    variants.insert(key, json!({ "model": single }));
                } else if let Some(variant) = client.and_then(|c| vanilla_variant(c, state)) {
                    // As the client draws it; a state its file has nothing for is left to the game's missing model.
                    variants.insert(key, variant);
                }
            }
            let (namespace, path) = block.split_once(':').expect("a game block in full");
            out.push((
                format!("assets/{namespace}/blockstates/{path}.json"),
                text(&json!({ "variants": variants })),
            ));
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// The variant the client's blockstate `file` draws `state` with, as the game picks it: the last `variants`
/// entry whose key names only properties `state` has, with those values (`""` matches every state). None when no
/// entry does, or the file is `multipart` (format's `VanillaBlockStates.variant`).
pub fn vanilla_variant(file: &Value, state: &crate::kind::carriers::State) -> Option<Value> {
    let variants = file.get("variants")?.as_object()?;
    let matches = |key: &str| {
        key.is_empty()
            || key.split(',').all(|condition| {
                let (name, value) = condition.split_once('=').unwrap_or((condition, ""));
                state.properties.get(name).map(String::as_str) == Some(value)
            })
    };
    variants
        .iter()
        .rev()
        .find(|(k, v)| matches(k) && (v.is_object() || v.is_array()))
        .map(|(_, v)| v.clone())
}
