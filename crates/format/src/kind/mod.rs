//! The kinds ported so far, each beside its model, and reading a scanned
//! resource of one.

pub mod advancement;
pub mod biome;
pub mod block;
pub mod buff;
pub mod carriers;
pub mod centity;
pub mod cutscene;
pub mod datapack;
pub mod dialog;
pub mod dimension_type;
pub mod files;
pub mod game_block;
pub mod item;
pub mod loot;
pub mod menu;
pub mod module;
pub mod particle;
pub mod recipe;
pub mod resource_pack;
pub mod screen;
pub mod screen_font;
pub mod sky;
pub mod structure;
pub mod terrain;
pub mod video;

use std::collections::BTreeSet;

use crate::problem::Problem;
use crate::snapshot::Found;

/// What a resource is read from: the project's files, a way to read one, and
/// what's worked out across them once (the modules scripts require).
pub struct Source<'a> {
    pub files: &'a BTreeSet<String>,
    pub read: crate::document::ReadFile<'a>,
    pub required_modules: BTreeSet<String>,
    pub game: Option<&'a dyn crate::game::GameData>,
    /// The project's target Minecraft version, and the feature table it's held to.
    pub minecraft: Option<&'a str>,
    pub features: Option<crate::features::FeatureTable<'a>>,
}

impl<'a> Source<'a> {
    pub fn new(
        files: &'a BTreeSet<String>,
        read: &'a dyn Fn(&str) -> Option<String>,
    ) -> Source<'a> {
        let required_modules = module::required(files, read);
        Source {
            files,
            read,
            required_modules,
            game: None,
            minecraft: None,
            features: None,
        }
    }
}

/// The document a resource is read from: its main file, or the document
/// beside it for a file that has one (a structure's `.json`).
pub fn document_path(found: &Found) -> String {
    let kind = found.kind;
    match kind.companion_document {
        Some(_) => kind
            .companion_path_of(&found.id, &found.group)
            .expect("a companion document has a path"),
        None => kind.path_of(&found.id, &found.group),
    }
}

/// The model of each document kind ported: the one list of them, which
/// reading, writing back and the checks across the project all go by.
pub fn document_kind(id: &str) -> Option<&'static dyn DocumentKind> {
    fn of<T: crate::document::Document>() -> &'static dyn DocumentKind {
        &Of::<T>(std::marker::PhantomData)
    }
    Some(match id {
        "advancement" => of::<advancement::AdvancementFile>(),
        "biome" => of::<biome::BiomeFile>(),
        "block" => of::<block::BlockFile>(),
        "buff" => of::<buff::BuffFile>(),
        "centity" => of::<centity::CentityFile>(),
        "cutscene" => of::<cutscene::CutsceneFile>(),
        "datapack" => of::<datapack::PackMeta>(),
        "dialog" => of::<dialog::DialogFile>(),
        "dimension_type" => of::<dimension_type::DimensionTypeFile>(),
        "game_block" => of::<game_block::GameBlockFile>(),
        "item" => of::<item::ItemFile>(),
        "loot_table" => of::<loot::LootTableFile>(),
        "menu" => of::<menu::MenuFile>(),
        "particle_effect" => of::<particle::ParticleEffectFile>(),
        "recipe" => of::<recipe::RecipeFile>(),
        "resource_pack" => of::<resource_pack::ResourcePackFile>(),
        "screen" => of::<screen::ScreenFile>(),
        "screen_font" => of::<screen_font::ScreenFontFile>(),
        "sky" => of::<sky::SkyFile>(),
        // A structure's document is its companion: how it generates by itself.
        "structure" => of::<structure::StructureGeneration>(),
        "terrain" => of::<terrain::TerrainFile>(),
        "video" => of::<video::VideoFile>(),
        _ => return None,
    })
}

/// A document kind's model, behind one face.
pub trait DocumentKind: Sync {
    /// The document at `path` read from `text` and checked on its own.
    fn read(&self, text: &str, path: &str, cx: &crate::document::Context) -> Read;
    /// The document read from `text` and written again, canonically.
    fn write_back(&self, text: &str, path: &str) -> Result<String, Box<Problem>>;
    fn schema(&self) -> &'static crate::schema::Schema;
    /// The model `json` (a document of the kind) reads as, if it does.
    fn model(&self, json: &serde_json::Value) -> Option<Box<dyn Model>>;
    /// What the kind's running resources put in the start-up datapack (`Document::datapack`).
    fn datapack(&self, cx: &crate::startup::Context) -> Vec<(String, crate::startup::Entry)>;
}

struct Of<T>(std::marker::PhantomData<fn() -> T>);

impl<T: crate::document::Document> DocumentKind for Of<T> {
    fn read(&self, text: &str, path: &str, cx: &crate::document::Context) -> Read {
        let read = crate::document::load::<T>(text, path, cx);
        Read {
            problems: read.problems,
            document: read.value.zip(read.json).map(|(value, json)| Doc {
                path: path.to_owned(),
                model: Box::new(value),
                json,
                schema: self.schema(),
            }),
        }
    }

    fn write_back(&self, text: &str, path: &str) -> Result<String, Box<Problem>> {
        crate::read::parse::<T>(text, path).map(|v| crate::document::write(&v, path))
    }

    fn schema(&self) -> &'static crate::schema::Schema {
        crate::schema::of::<T>()
    }

    fn model(&self, json: &serde_json::Value) -> Option<Box<dyn Model>> {
        serde_json::from_value::<T>(json.clone())
            .ok()
            .map(|m| Box::new(m) as Box<dyn Model>)
    }

    fn datapack(&self, cx: &crate::startup::Context) -> Vec<(String, crate::startup::Entry)> {
        T::datapack(&crate::startup::keyed(cx, cx.project.running::<T>()), cx)
    }
}

/// A scanned resource read and checked on its own, if its kind is ported
/// (`None` for one that isn't yet).
pub fn read(found: &Found, source: &Source) -> Option<Read> {
    let path = document_path(found);
    let location = found.kind.location_of(&found.id, &found.group);
    let inside = |file: &str| (source.read)(&format!("{location}/{file}"));
    let cx = crate::document::Context {
        id: &found.id,
        location: &location,
        read: Some(&inside),
        files: Some(&found.inside),
        game: source.game,
        minecraft: source.minecraft,
        features: source.features,
    };
    // A file with a companion document is only its file without it (a structure that doesn't generate).
    let companion_missing =
        found.kind.companion_document.is_some() && !source.files.contains(&path);
    if let Some(kind) = document_kind(found.kind.id).filter(|_| !companion_missing) {
        return Some(kind.read(&(source.read)(&path).unwrap_or_default(), &path, &cx));
    }
    Some(Read::files(match found.kind.id {
        "structure" => Vec::new(),
        "module" => module::check(found, source),
        "build" => files::build(found),
        "migration" => files::migration(found),
        // A map is its world's files; a WASM module's checks read its binary.
        "map" | "wasm" => Vec::new(),
        _ => return None,
    }))
}

/// A resource read and checked on its own: its problems, and its document
/// when it has one that parsed.
pub struct Read {
    pub problems: Vec<Problem>,
    pub document: Option<Doc>,
}

impl Read {
    /// A resource format doesn't read as a document: its files are what it is.
    fn files(problems: Vec<Problem>) -> Read {
        Read {
            problems,
            document: None,
        }
    }
}

/// A document that parsed: its model, its file's JSON and its schema, which
/// the checks across the project read it by.
pub struct Doc {
    pub path: String,
    pub model: Box<dyn Model>,
    pub json: serde_json::Value,
    pub schema: &'static crate::schema::Schema,
}

/// A kind's model, whichever it is: what the project holds of a resource.
pub trait Model: std::any::Any {
    fn as_any(&self) -> &dyn std::any::Any;
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut crate::ProblemSink);
    fn datapack_entries(&self, id: &str) -> Vec<(&'static str, String)>;
    fn compile(
        &self,
        id: &str,
        cx: &crate::document::Context,
    ) -> Option<Box<dyn crate::document::Compiled>>;
}

impl<T: crate::document::Document> Model for T {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn cross_check(&self, cx: &crate::project::Across, sink: &mut crate::ProblemSink) {
        crate::document::Document::cross_check(self, cx, sink);
    }

    fn datapack_entries(&self, id: &str) -> Vec<(&'static str, String)> {
        crate::document::Document::datapack_entries(self, id)
    }

    fn compile(
        &self,
        id: &str,
        cx: &crate::document::Context,
    ) -> Option<Box<dyn crate::document::Compiled>> {
        crate::document::Document::compile(self, id, cx)
    }
}

/// A scanned resource read and written again, canonically, if it's a document
/// of a ported kind: the text, or the problem it didn't parse with.
pub fn write_back(found: &Found, text: &str) -> Option<Result<String, Box<Problem>>> {
    document_kind(found.kind.id).map(|kind| kind.write_back(text, &document_path(found)))
}
