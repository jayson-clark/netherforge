//! References from one project file to something else: format's `Ref`,
//! `RefKind`, `ResourceRef` and `RefWalker`.
//!
//! **A reference is marked on its field, once**: `extend("x-ref" = "item")` in
//! the model's schema (a ruled string carries its own: a texture's path is a
//! `texture` reference wherever it's used), and MiniMessage text is marked
//! `x-minimessage`, where `<glyph:ui/coin>` tags name a pack's glyphs. A mark on
//! a list or a map holds for its items or values (never a map's keys). So
//! validation, find usages, rename and delete follow a new reference by its
//! being marked, and the editor reads the same marks in the schema.

pub mod index;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::schema::Visit;

pub use index::{BrokenRef, Defined, ReferenceIndex, not_exported, unknown_namespace};

/// Where what a [`RefKind`] names lives.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RefScope {
    /// A resource of its own (`items/ruby`), named by id: `ruby`, `acme:ruby`.
    Resource,
    /// An entry of a pack (a skin, a glyph…), named by pack and key: `ui/coin`, `acme:ui/coin`.
    ResourcePack,
    /// A file inside the referring resource's own folder, by its path there: `gui/shop.png`.
    File,
    /// An entry of one of the game's registries a project datapack defines, by its id there.
    Datapack,
}

/// Everything a kind of reference says about itself (see [`RefKind`]).
pub struct Spec {
    /// What it names, in a sentence: `loot table`.
    pub noun: &'static str,
    pub scope: RefScope,
    /// For a resource: its kind's id (`loot_table`).
    pub resource_kind: Option<&'static str>,
    /// For a pack's entry: the map of `pack.json` it's in (`glyphs`), or `sounds`.
    pub pack_entries: Option<&'static str>,
    /// For a file: the folder in the resource it's under (`textures/`).
    pub folder: Option<&'static str>,
    /// It also names the game's own (`minecraft:plains`, `#minecraft:logs`), which the kind's checks look at.
    pub game: bool,
    /// The game's registry a datapack defines its entries in (`worldgen/biome`).
    pub registry: Option<&'static str>,
    /// The registry whose `#tags` a datapack defines and it names (`block`).
    pub tag_registry: Option<&'static str>,
    /// The word for anything of the kind (`*`), the game's to understand.
    pub any: Option<&'static str>,
}

const fn spec(noun: &'static str, scope: RefScope) -> Spec {
    Spec {
        noun,
        scope,
        resource_kind: None,
        pack_entries: None,
        folder: None,
        game: false,
        registry: None,
        tag_registry: None,
        any: None,
    }
}

const fn resource(noun: &'static str, kind: &'static str) -> Spec {
    Spec {
        resource_kind: Some(kind),
        ..spec(noun, RefScope::Resource)
    }
}

const fn entry(noun: &'static str, entries: &'static str) -> Spec {
    Spec {
        pack_entries: Some(entries),
        ..spec(noun, RefScope::ResourcePack)
    }
}

const fn file(noun: &'static str, folder: &'static str) -> Spec {
    Spec {
        folder: Some(folder),
        ..spec(noun, RefScope::File)
    }
}

macro_rules! ref_kinds {
    ($($(#[$m:meta])* $name:ident => $spec:expr,)*) => {
        /// What a reference names. Its name in a schema's `x-ref` and in
        /// `RefUse` is serde's (`loot_table`).
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize, JsonSchema)]
        #[serde(rename_all = "snake_case")]
        pub enum RefKind {
            $($(#[$m])* $name,)*
        }

        impl RefKind {
            pub const ALL: &[RefKind] = &[$(RefKind::$name,)*];

            pub fn spec(self) -> &'static Spec {
                match self {
                    $(RefKind::$name => {
                        const SPEC: Spec = $spec;
                        &SPEC
                    })*
                }
            }
        }
    };
}

ref_kinds! {
    Item => resource("item", "item"),
    Dialog => resource("dialog", "dialog"),
    LootTable => resource("loot table", "loot_table"),
    Buff => resource("buff", "buff"),
    Advancement => resource("advancement", "advancement"),
    Block => resource("block", "block"),
    /// A block an item's `canBreak` or `canPlaceOn` names: the project's, the game's, a `#tag`, or `*`.
    AdventureBlock => Spec { game: true, tag_registry: Some("block"), any: Some("*"), ..resource("block", "block") },
    Centity => resource("centity", "centity"),
    Terrain => resource("terrain", "terrain"),
    Structure => resource("structure", "structure"),
    Biome => Spec { game: true, registry: Some("worldgen/biome"), ..resource("biome", "biome") },
    /// A placed feature: the game's, or one a project datapack defines.
    PlacedFeature => Spec { game: true, registry: Some("worldgen/placed_feature"), ..spec("placed feature", RefScope::Datapack) },
    DimensionType => resource("dimension type", "dimension_type"),
    Video => resource("video", "video"),
    Skin => entry("skin", "skins"),
    Glyph => entry("glyph", "glyphs"),
    ItemModel => entry("item model", "items"),
    Tooltip => entry("tooltip", "tooltips"),
    Equipment => entry("equipment look", "equipment"),
    BlockModel => entry("block model", "blocks"),
    Sound => entry("sound", "sounds"),
    /// An element of a pack's `hud` (a bar, a row of icons, a line of text), which scripts fill in.
    Hud => entry("HUD element", "hud"),
    Texture => file("texture", "textures/"),
    SoundFile => file("sound file", "sounds/"),
    /// A model file of a resource pack, by its path under `models/`.
    Model => file("model", "models/"),
    Script => file("script", ""),
}

/// Id segments joined by `/`: what may follow a namespace (`ResourceRef.RESOURCE_PATH`).
pub const RESOURCE_PATH: &str = r"^[a-z0-9][a-z0-9_]{0,63}(/[a-z0-9][a-z0-9_]{0,63})*$";

pub fn is_resource_path(path: &str) -> bool {
    crate::rules::matches_pattern(RESOURCE_PATH, path)
}

impl RefKind {
    /// Its name as `x-ref` and files write it: `loot_table`.
    pub fn name(self) -> String {
        crate::canonical::name_of(&self)
    }

    pub fn of(name: &str) -> Option<RefKind> {
        serde_json::from_value(Value::String(name.to_owned())).ok()
    }

    /// Whether `path` (what follows the namespace) has the shape this kind names.
    pub fn is_path(self, path: &str) -> bool {
        let spec = self.spec();
        match spec.scope {
            RefScope::Resource if spec.registry.is_some() => is_resource_path(path),
            RefScope::Resource => crate::names::is_id(path),
            RefScope::Datapack => is_resource_path(path),
            RefScope::ResourcePack => {
                let (pack, key) = path.split_once('/').unwrap_or(("", ""));
                crate::names::is_id(pack)
                    && if self == RefKind::Sound {
                        crate::kind::resource_pack::sounds::is_key(key)
                    } else {
                        crate::names::is_id(key)
                    }
            }
            RefScope::File => true,
        }
    }

    /// Whether `text`, a reference of this kind, names one of the game's own.
    pub fn is_game(self, text: &str) -> bool {
        let spec = self.spec();
        if !spec.game {
            return false;
        }
        if spec.any == Some(text) {
            return true;
        }
        if let Some(tag) = text.strip_prefix('#') {
            return spec.tag_registry.is_none()
                || namespace_of(tag).is_none_or(|n| n == crate::game::NAMESPACE);
        }
        namespace_of(text) == Some(crate::game::NAMESPACE)
    }

    /// Whether `text` is a `#tag` a project datapack defines (not the game's).
    pub fn is_datapack_tag(self, text: &str) -> bool {
        self.spec().tag_registry.is_some() && text.starts_with('#') && !self.is_game(text)
    }

    /// Its noun with its article: `an item model`.
    pub fn a_noun(self) -> String {
        let noun = self.spec().noun;
        let article = if noun.starts_with(['a', 'e', 'i', 'o', 'u']) {
            "an"
        } else {
            "a"
        };
        format!("{article} {noun}")
    }

    /// How a reference of this kind is written, for messages.
    pub fn shape(self) -> String {
        let spec = self.spec();
        match spec.scope {
            RefScope::Resource | RefScope::Datapack => {
                let mut out = "<id>, or <namespace>:<id> for another package's".to_owned();
                if spec.game {
                    out += &format!(", or {}:<id> for the game's", crate::game::NAMESPACE);
                }
                if spec.tag_registry.is_some() {
                    out += ", or a #tag";
                }
                if let Some(any) = spec.any {
                    out += &format!(", or {any} for any");
                }
                out
            }
            RefScope::ResourcePack => {
                "<pack>/<key>, or <namespace>:<pack>/<key> for another package's".into()
            }
            RefScope::File => "a path inside the resource's folder".into(),
        }
    }

    /// The problem a reference of this kind that names nothing is: `reference.loot-table`.
    pub fn missing(self) -> crate::ProblemCode {
        let spec = self.spec();
        let name = match spec.scope {
            RefScope::Datapack => spec.noun.replace(' ', "-"),
            _ => spec
                .resource_kind
                .expect("only a resource or a datapack's entry is missing")
                .replace('_', "-"),
        };
        crate::ProblemCode::of(&format!("reference.{name}"))
            .unwrap_or_else(|| panic!("no reference code for {}", spec.noun))
    }
}

/// The namespace written before `:`, if any.
fn namespace_of(text: &str) -> Option<&str> {
    text.split_once(':').map(|(n, _)| n)
}

/// A reference resolved: always namespaced, `shop:ruby`, `shop:ui/coin`.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ResourceKey {
    pub namespace: String,
    pub path: String,
}

impl ResourceKey {
    /// How a file in namespace `home` writes this: without the namespace when it's `home`.
    pub fn relative_to(&self, home: &str) -> String {
        if self.namespace == home {
            self.path.clone()
        } else {
            self.to_string()
        }
    }

    /// For a pack's entry: the pack (`ui`).
    pub fn pack(&self) -> &str {
        self.path.split_once('/').map_or(&self.path, |(p, _)| p)
    }

    /// For a pack's entry: the key inside its pack (`coin`, `menu/open`).
    pub fn key(&self) -> &str {
        self.path.split_once('/').map_or(&self.path, |(_, k)| k)
    }
}

impl std::fmt::Display for ResourceKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.namespace, self.path)
    }
}

/// The key `text` names from inside namespace `home` (`ResourceRef.resolve`),
/// if it's shaped like one.
pub fn resolve_any(home: &str, text: &str) -> Option<ResourceKey> {
    if text.matches(':').count() > 1 {
        return None;
    }
    let (namespace, path) = match text.split_once(':') {
        Some((n, p)) => (n, p),
        None => (home, text),
    };
    if namespace != home && !crate::names::is_namespace(namespace) {
        return None;
    }
    if !is_resource_path(path) {
        return None;
    }
    Some(ResourceKey {
        namespace: namespace.to_owned(),
        path: path.to_owned(),
    })
}

/// `text` as a reference of `kind` from `home`, resolved, if it's shaped like one.
pub fn resolve(home: &str, kind: RefKind, text: &str) -> Option<ResourceKey> {
    resolve_any(home, text).filter(|key| kind.is_path(&key.path))
}

/// One reference a document makes: what it names, as written, and the JSON
/// path of the string holding it. A glyph tag is inside MiniMessage text:
/// `path` is the text's, and `start` and `end` where the whole tag is in it,
/// in UTF-16 code units (as the editor's strings count).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub kind: RefKind,
    pub path: String,
    pub text: String,
    pub span: Option<(usize, usize)>,
}

/// What a string's schemas say it is.
#[derive(Clone, Copy)]
enum Mark {
    Reference(RefKind),
    Text,
}

/// Every reference in `value`, a document of `T` as its file has it, in
/// document order: each string marked `x-ref`, and each glyph tag in a string
/// marked `x-minimessage`.
pub fn find<T: JsonSchema + 'static>(value: &Value) -> Vec<Found> {
    find_in(crate::schema::of::<T>(), value)
}

pub fn find_in(schema: &crate::schema::Schema, value: &Value) -> Vec<Found> {
    let mut out = Vec::new();
    schema.walk(
        value,
        None,
        &mut |visit, path, mark: &mut Option<Mark>| match visit {
            Visit::Schema(schema, _) => {
                if let Some(kind) = schema.get("x-ref").and_then(Value::as_str) {
                    *mark =
                        Some(Mark::Reference(RefKind::of(kind).unwrap_or_else(|| {
                            panic!("x-ref \"{kind}\" isn't a RefKind")
                        })));
                } else if schema.get("x-minimessage") == Some(&Value::Bool(true)) {
                    *mark = Some(Mark::Text);
                }
            }
            Visit::Value(Value::String(text)) => match *mark {
                Some(Mark::Reference(kind)) => out.push(Found {
                    kind,
                    path: path.to_owned(),
                    text: text.clone(),
                    span: None,
                }),
                Some(Mark::Text) => {
                    out.extend(crate::text::glyph::find(text).into_iter().map(|g| Found {
                        kind: RefKind::Glyph,
                        path: path.to_owned(),
                        text: g.reference,
                        span: Some((g.start, g.end)),
                    }))
                }
                None => {}
            },
            _ => {}
        },
    );
    out
}

/// `json`, a document of `schema`, with every reference `replace` answers new
/// text for rewritten to it (a glyph tag inside its text), and the rest as it
/// was (`RefWalker.rewrite`).
pub fn rewrite(
    schema: &crate::schema::Schema,
    json: &Value,
    replace: &mut dyn FnMut(RefKind, &str) -> Option<String>,
) -> Value {
    let mut out = json.clone();
    // Each string that holds references once: a reference, or text with glyph tags.
    let mut strings: Vec<(String, RefKind, bool)> = Vec::new();
    for found in find_in(schema, json) {
        if strings
            .last()
            .is_none_or(|(path, _, _)| *path != found.path)
        {
            strings.push((found.path, found.kind, found.span.is_some()));
        }
    }
    for (path, kind, text) in strings {
        let Some(Value::String(string)) = crate::json::at_path_mut(&mut out, &path) else {
            continue;
        };
        let next = if text {
            crate::text::glyph::rewrite(string, &mut |r| replace(RefKind::Glyph, r))
        } else {
            replace(kind, string)
        };
        if let Some(next) = next {
            *string = next;
        }
    }
    out
}

/// `json` (a document of `schema` written in namespace `home`) with every
/// reference to a resource or a pack's entry written in full (`ruby` is
/// `shop:ruby`), so it names the same read from anywhere: how a package's
/// resources run beside the project's. Files inside a resource are left be.
pub fn qualify(schema: &crate::schema::Schema, json: &Value, home: &str) -> Value {
    rewrite(schema, json, &mut |kind, text| {
        if kind.spec().scope == RefScope::File {
            return None;
        }
        resolve(home, kind, text)
            .map(|k| k.to_string())
            .filter(|full| full != text)
    })
}
