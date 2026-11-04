//! The kinds ported so far, each beside its model, and reading a scanned
//! resource of one.

pub mod advancement;
pub mod biome;
pub mod buff;
pub mod dialog;
pub mod dimension_type;
pub mod files;
pub mod game_block;
pub mod item;
pub mod loot;
pub mod menu;
pub mod module;
pub mod recipe;
pub mod sky;

use std::collections::BTreeSet;

use crate::problem::Problem;
use crate::snapshot::Found;

/// What a resource is read from: the project's files, a way to read one, and
/// what's worked out across them once (the modules scripts require).
pub struct Source<'a> {
    pub files: &'a BTreeSet<String>,
    pub read: &'a dyn Fn(&str) -> Option<String>,
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

/// A scanned resource read and checked on its own, if its kind is ported:
/// its problems. `None` is a kind not ported yet: this match is the one list
/// of the kinds that are.
pub fn read(found: &Found, source: &Source) -> Option<Vec<Problem>> {
    let path = found.kind.path_of(&found.id, &found.group);
    let text = || (source.read)(&path).unwrap_or_default();
    let cx = crate::document::Context {
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
    let path = found.kind.path_of(&found.id, &found.group);
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
        "dimension_type" => again::<dimension_type::DimensionTypeFile>(text, &path),
        "advancement" => again::<advancement::AdvancementFile>(text, &path),
        _ => return None,
    })
}
