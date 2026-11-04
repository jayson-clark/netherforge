//! The value types the bindings cross by hand (`RUNTIME_TYPES` in
//! `packages/api/scripts/emit/bindings.ts`). They're the API's own; each is
//! filled in as the area that uses it is ported.

use crate::handles;

/// A point or a direction: three numbers. Under Luau a script holds it as a
/// native `vector` (see "Numbers" in the rust-runtime skill).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// A place in a world, and which way it faces when that's set.
#[derive(Clone, Debug, PartialEq)]
pub struct Location {
    pub world: handles::World,
    pub position: Vec3,
    pub yaw: Option<f64>,
    pub pitch: Option<f64>,
}

/// An item stack as scripts describe it. Ported with items: until then it
/// holds the script's table as JSON (the codec's text).
#[derive(Clone, Debug, PartialEq)]
pub struct Item(pub String);

/// What an item must be to match (a partial item). Ported with items, like
/// [`Item`].
#[derive(Clone, Debug, PartialEq)]
pub struct ItemMatch(pub String);

/// Text with glyph tags, spelled in the server's terms.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Text(pub String);
