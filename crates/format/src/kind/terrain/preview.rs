//! What the editor draws of a generator (format's `TerrainPreview`): a
//! top-down map of heights and biome areas, and a vertical slice of real
//! chunks. Both ask the same `TerrainGenerator` the server runs, so a preview
//! is the world a server with that seed makes. The map reads only each
//! column's height and area (the two questions a column answers by itself);
//! the slice generates whole chunks. Their JSON is the editor's
//! `TerrainPreviewResult` (`map`, `slice`).

use serde::ser::SerializeStruct;
use serde::{Serialize, Serializer};

use super::DecorationPlacement;
use super::compiled::TerrainBlock;
use super::generate::{ChunkBuffer, TerrainGenerator};
use super::scripts::ScriptFailure;

/// The widest map asked for in one call: the editor draws far less, and a mistake can't take long.
pub const MAX_CELLS: i32 = 256;

/// The widest slice asked for in one call: 256 chunks, what `netherforge preview --width` allows.
pub const MAX_SLICE: i32 = 4096;

/// The most decoration tries a map works out: a close map's (a few hundred chunks); a far one shows none.
pub const MAX_DECORATION_TRIES: i64 = 100_000;

/// The terrain seen from above: `cells` by `cells` columns, a cell every
/// `step` blocks from the column (`x0`, `z0`), row by row (`z` rows, `x` across).
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "type", rename = "map", rename_all = "camelCase")]
pub struct TerrainMap {
    pub x0: i32,
    pub z0: i32,
    pub cells: i32,
    pub step: i32,
    /// The y of each column's top block.
    pub heights: Vec<i32>,
    /// The index in `area_names` of each column's biome area.
    pub areas: Vec<usize>,
    pub area_names: Vec<String>,
    /// The vanilla biome of each area, in the same order.
    pub biomes: Vec<String>,
    pub sea_level: i32,
    pub min_y: i32,
    pub max_y: i32,
    pub decoration_names: Vec<String>,
    /// Where decorations on the surface and the sea floor start, as pairs of
    /// a cell (an index of `heights`) and a decoration (an index of
    /// `decoration_names`): empty unless `decorations_shown`.
    pub decorations: Vec<usize>,
    pub decorations_shown: bool,
    pub script_errors: Vec<ScriptFailure>,
}

/// The ground along a line (the x axis at z `at` when `axis` is "x", else the
/// z axis at x `at`): `width` columns from block `from` along it. A column is
/// its blocks from `min_y` up, as pairs of an index of `palette` and how many
/// in a row (index 0 is air).
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "type", rename = "slice", rename_all = "camelCase")]
pub struct TerrainSlice {
    pub axis: &'static str,
    pub from: i32,
    pub at: i32,
    pub width: i32,
    pub min_y: i32,
    pub max_y: i32,
    pub palette: Vec<PaletteEntry>,
    pub columns: Vec<Vec<i32>>,
    pub sea_level: i32,
    pub script_errors: Vec<ScriptFailure>,
}

/// A block of the generator's palette: a block id, or a project block's name when `custom`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PaletteEntry {
    pub label: String,
    pub custom: bool,
}

/// A script's failure as the editor shows it (its `TerrainScriptError`): the
/// stage, Lua's message, and the file and line the message names.
impl Serialize for ScriptFailure {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let (file, line) = self.location();
        let mut out = serializer.serialize_struct("TerrainScriptError", 4)?;
        out.serialize_field("stage", &self.stage)?;
        out.serialize_field("message", &self.message)?;
        out.serialize_field("file", &file)?;
        out.serialize_field("line", &line)?;
        out.end()
    }
}

/// A `cells` by `cells` map of the terrain, a cell every `step` blocks, whose
/// first is the column (`x0`, `z0`). `script_errors` says, once it's drawn,
/// how the file's script failed while it was.
pub fn map(
    generator: &TerrainGenerator,
    x0: i32,
    z0: i32,
    cells: i32,
    step: i32,
    script_errors: impl FnOnce() -> Vec<ScriptFailure>,
) -> TerrainMap {
    let n = cells.clamp(1, MAX_CELLS);
    let s = step.max(1);
    let terrain = &generator.terrain;
    let (heights, areas, decorations, decorations_shown) = generator.sampling(|sampler| {
        let mut heights = Vec::with_capacity((n * n) as usize);
        let mut areas = Vec::with_capacity((n * n) as usize);
        for row in 0..n {
            for col in 0..n {
                let (x, z) = (x0 + col * s, z0 + row * s);
                heights.push(sampler.surface_at(x, z));
                areas.push(sampler.area_at(x, z));
            }
        }
        // Where the surface's and the sea floor's decorations start, in the chunks the map covers, when there
        // are few enough tries. A structure that wasn't linked in isn't placed, so it isn't marked either.
        let shown: Vec<usize> = (terrain.decorations.iter().enumerate())
            .filter(|(_, d)| {
                matches!(
                    d.placement,
                    DecorationPlacement::Surface | DecorationPlacement::Underwater
                ) && d
                    .structure
                    .as_ref()
                    .is_none_or(|s| terrain.templates.contains_key(s))
            })
            .map(|(i, _)| i)
            .collect();
        let from = (x0.div_euclid(16), z0.div_euclid(16));
        let to = (
            (x0 + n * s - 1).div_euclid(16),
            (z0 + n * s - 1).div_euclid(16),
        );
        let chunks = i64::from(to.0 - from.0 + 1) * i64::from(to.1 - from.1 + 1);
        let tries = chunks
            * shown
                .iter()
                .map(|&i| i64::from(terrain.decorations[i].count))
                .sum::<i64>();
        let decorations_shown = tries <= MAX_DECORATION_TRIES;
        let mut decorations = Vec::new();
        if decorations_shown {
            for &index in &shown {
                for cx in from.0..=to.0 {
                    for cz in from.1..=to.1 {
                        for site in generator.decoration_sites(index, cx, cz, sampler) {
                            let col = (site.x - x0).div_euclid(s);
                            let row = (site.z - z0).div_euclid(s);
                            if (0..n).contains(&col) && (0..n).contains(&row) {
                                decorations.extend([(row * n + col) as usize, index]);
                            }
                        }
                    }
                }
            }
        }
        (heights, areas, decorations, decorations_shown)
    });
    TerrainMap {
        x0,
        z0,
        cells: n,
        step: s,
        heights,
        areas,
        area_names: terrain.areas.iter().map(|a| a.name.clone()).collect(),
        biomes: terrain.areas.iter().map(|a| a.biome.clone()).collect(),
        sea_level: terrain.sea_level,
        min_y: generator.min_y,
        max_y: generator.max_y,
        decoration_names: terrain.decorations.iter().map(|d| d.name.clone()).collect(),
        decorations,
        decorations_shown,
        script_errors: script_errors(),
    }
}

/// The blocks along a line: `width` columns from block `from` along the x
/// axis at z `at` (when `along_x`), or along z at x `at`. Each column is its
/// blocks from the bottom as pairs of a palette index and how many in a row,
/// air included. `script_errors` is as `map`'s.
pub fn slice(
    generator: &TerrainGenerator,
    along_x: bool,
    at: i32,
    from: i32,
    width: i32,
    script_errors: impl FnOnce() -> Vec<ScriptFailure>,
) -> TerrainSlice {
    let w = width.clamp(1, MAX_SLICE);
    // A line's columns go through each chunk once, one after another, so only the chunk they're in is kept.
    let mut chunk: Option<(i32, ChunkBuffer)> = None;
    let mut columns = Vec::with_capacity(w as usize);
    for i in 0..w {
        let along = from + i;
        let (x, z) = if along_x { (along, at) } else { (at, along) };
        if chunk.as_ref().is_none_or(|(c, _)| *c != along >> 4) {
            chunk = Some((along >> 4, generator.generate(x >> 4, z >> 4)));
        }
        let buffer = &chunk.as_ref().expect("made above").1;
        let start = buffer.column_start(x & 15, z & 15);
        let column = &buffer.blocks[start..start + buffer.height as usize];
        let mut runs = Vec::new();
        for block in column.chunk_by(|a, b| a == b) {
            runs.extend([block[0], block.len() as i32]);
        }
        columns.push(runs);
    }
    let terrain = &generator.terrain;
    TerrainSlice {
        axis: if along_x { "x" } else { "z" },
        from,
        at,
        width: w,
        min_y: generator.min_y,
        max_y: generator.max_y,
        palette: (terrain.palette.iter())
            .map(|b| PaletteEntry {
                label: b.label().to_owned(),
                custom: matches!(b, TerrainBlock::Custom { .. }),
            })
            .collect(),
        columns,
        sea_level: terrain.sea_level,
        script_errors: script_errors(),
    }
}
