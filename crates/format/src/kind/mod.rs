//! The kinds ported so far, each beside its model, and reading a scanned
//! resource of one.

pub mod buff;
pub mod dialog;
pub mod item;
pub mod menu;
pub mod module;

use std::collections::BTreeSet;

use crate::problem::Problem;
use crate::snapshot::Found;

/// The kinds whose resources this crate reads so far.
pub const PORTED: &[&str] = &["buff", "dialog", "item", "menu", "module"];

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
/// its problems.
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
        "module" => module::check(found, source),
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
        _ => return None,
    })
}
