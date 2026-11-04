//! The kinds ported so far, each beside its model, and reading a scanned
//! resource of one.

pub mod advancement;
pub mod biome;
pub mod block;
pub mod buff;
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

/// A scanned resource read and checked on its own, if its kind is ported:
/// its problems. `None` is a kind not ported yet: this match is the one list
/// of the kinds that are.
pub fn read(found: &Found, source: &Source) -> Option<Vec<Problem>> {
    let path = document_path(found);
    let text = || (source.read)(&path).unwrap_or_default();
    let location = found.kind.location_of(&found.id, &found.group);
    let inside = |file: &str| (source.read)(&format!("{location}/{file}"));
    let cx = crate::document::Context {
        id: &found.id,
        location: &location,
        read: Some(&inside),
        files: Some(&found.inside),
        game: source.game,
    };
    Some(match found.kind.id {
        "buff" => crate::document::load::<buff::BuffFile>(&text(), &path, &cx).1,
        "menu" => crate::document::load::<menu::MenuFile>(&text(), &path, &cx).1,
        "item" => crate::document::load::<item::ItemFile>(&text(), &path, &cx).1,
        "dialog" => crate::document::load::<dialog::DialogFile>(&text(), &path, &cx).1,
        "recipe" => crate::document::load::<recipe::RecipeFile>(&text(), &path, &cx).1,
        "loot_table" => crate::document::load::<loot::LootTableFile>(&text(), &path, &cx).1,
        "sky" => crate::document::load::<sky::SkyFile>(&text(), &path, &cx).1,
        "biome" => crate::document::load::<biome::BiomeFile>(&text(), &path, &cx).1,
        "game_block" => crate::document::load::<game_block::GameBlockFile>(&text(), &path, &cx).1,
        "cutscene" => crate::document::load::<cutscene::CutsceneFile>(&text(), &path, &cx).1,
        "block" => crate::document::load::<block::BlockFile>(&text(), &path, &cx).1,
        "centity" => crate::document::load::<centity::CentityFile>(&text(), &path, &cx).1,
        "datapack" => crate::document::load::<datapack::PackMeta>(&text(), &path, &cx).1,
        "resource_pack" => {
            crate::document::load::<resource_pack::ResourcePackFile>(&text(), &path, &cx).1
        }
        // A structure generates by itself only with its document beside it.
        "structure" if !source.files.contains(&path) => Vec::new(),
        "structure" => {
            crate::document::load::<structure::StructureGeneration>(&text(), &path, &cx).1
        }
        "particle_effect" => {
            crate::document::load::<particle::ParticleEffectFile>(&text(), &path, &cx).1
        }
        "screen" => crate::document::load::<screen::ScreenFile>(&text(), &path, &cx).1,
        "video" => crate::document::load::<video::VideoFile>(&text(), &path, &cx).1,
        "screen_font" => {
            crate::document::load::<screen_font::ScreenFontFile>(&text(), &path, &cx).1
        }
        "dimension_type" => {
            crate::document::load::<dimension_type::DimensionTypeFile>(&text(), &path, &cx).1
        }
        "advancement" => {
            crate::document::load::<advancement::AdvancementFile>(&text(), &path, &cx).1
        }
        "module" => module::check(found, source),
        "build" => files::build(found),
        "migration" => files::migration(found),
        // A map is its world's files; a WASM module's checks read its binary.
        "map" | "wasm" => Vec::new(),
        _ => return None,
    })
}

/// A scanned resource read and written again, canonically, if it's a document
/// of a ported kind: the text, or the problem it didn't parse with.
pub fn write_back(found: &Found, text: &str) -> Option<Result<String, Box<Problem>>> {
    let path = document_path(found);
    fn again<T: crate::document::Document>(text: &str, path: &str) -> Result<String, Box<Problem>> {
        crate::read::parse::<T>(text, path).map(|v| crate::document::write(&v, path))
    }
    Some(match found.kind.id {
        "buff" => again::<buff::BuffFile>(text, &path),
        "menu" => again::<menu::MenuFile>(text, &path),
        "item" => again::<item::ItemFile>(text, &path),
        "dialog" => again::<dialog::DialogFile>(text, &path),
        "recipe" => again::<recipe::RecipeFile>(text, &path),
        "loot_table" => again::<loot::LootTableFile>(text, &path),
        "sky" => again::<sky::SkyFile>(text, &path),
        "biome" => again::<biome::BiomeFile>(text, &path),
        "game_block" => again::<game_block::GameBlockFile>(text, &path),
        "cutscene" => again::<cutscene::CutsceneFile>(text, &path),
        "block" => again::<block::BlockFile>(text, &path),
        "centity" => again::<centity::CentityFile>(text, &path),
        "datapack" => again::<datapack::PackMeta>(text, &path),
        "resource_pack" => again::<resource_pack::ResourcePackFile>(text, &path),
        "structure" => again::<structure::StructureGeneration>(text, &path),
        "particle_effect" => again::<particle::ParticleEffectFile>(text, &path),
        "screen" => again::<screen::ScreenFile>(text, &path),
        "video" => again::<video::VideoFile>(text, &path),
        "screen_font" => again::<screen_font::ScreenFontFile>(text, &path),
        "dimension_type" => again::<dimension_type::DimensionTypeFile>(text, &path),
        "advancement" => again::<advancement::AdvancementFile>(text, &path),
        _ => return None,
    })
}
