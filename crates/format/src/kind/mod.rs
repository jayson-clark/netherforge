//! The kinds ported so far, each beside its model, and reading a scanned
//! resource of one.

pub mod buff;

use crate::problem::Problem;
use crate::snapshot::Found;

/// The kinds whose resources this crate reads so far.
pub const PORTED: &[&str] = &["buff"];

/// A scanned resource read from its main file's `text` and checked on its
/// own, if its kind is ported: its problems.
pub fn read(found: &Found, text: &str) -> Option<Vec<Problem>> {
    let path = found.kind.path_of(&found.id, &found.group);
    Some(match found.kind.id {
        "buff" => crate::document::load::<buff::BuffFile>(text, &path).1,
        _ => return None,
    })
}

/// A scanned resource read and written again, canonically, if its kind is
/// ported: the text, or the problem it didn't parse with.
pub fn write_back(found: &Found, text: &str) -> Option<Result<String, Box<Problem>>> {
    let path = found.kind.path_of(&found.id, &found.group);
    fn again<T: crate::document::Document>(text: &str, path: &str) -> Result<String, Box<Problem>> {
        crate::read::parse::<T>(text, path).map(|v| crate::document::write(&v, path))
    }
    Some(match found.kind.id {
        "buff" => again::<buff::BuffFile>(text, &path),
        _ => return None,
    })
}
