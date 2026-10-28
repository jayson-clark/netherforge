//! Default-font glyph advances, computed from an imported client's font files.
//!
//! `format`'s `TextMetrics` measures text with these (fitting a fixed text
//! display's hitbox, dialog previews). They're client-only facts, so they're
//! never baked into anything: the import computes them from the player's own
//! jar and stores them next to the assets as `glyph-advances.json`
//! (`{ "<code point>": advance }`), which the UI merges into the
//! `GameDataBundle` it hands to `format` as `glyphAdvances`.
//!
//! How Minecraft does it, and so how this does:
//! - A font is `assets/<ns>/font/<path>.json`: `{ providers: [...] }`. The
//!   first provider that defines a character wins.
//! - `reference` providers include another font by id. A provider's `filter`
//!   (`{ "uniform": false }`, `{ "jp": true }`) applies it only when the client's
//!   font options match; with the default options every option is off, so a
//!   provider is used unless its filter asks for an option to be on.
//! - `space` providers give advances directly (`{ " ": 4 }`).
//! - `bitmap` providers cut a PNG (`<ns>:<path>` under `textures/`) into a
//!   grid: one row per string in `chars`, one column per code point in it
//!   (`\0` marks an empty cell). A glyph's width is its rightmost column with
//!   any non-transparent pixel, plus one; its advance is that width scaled by
//!   `height / cellHeight`, rounded, plus one pixel of gap.
//! - `unihex` and `ttf` providers are skipped: `TextMetrics` falls back to its
//!   own estimate for anything not covered.

use std::collections::{BTreeMap, HashSet};
use std::io::BufReader;
use std::path::Path;

use serde_json::Value;

use crate::error::{Context, Result, bail};
use crate::fs::atomic::write_atomic;

/// The file the import writes under `client/`.
pub const ADVANCES: &str = "glyph-advances.json";

/// The font Minecraft draws ordinary text with.
pub const DEFAULT_FONT: &str = "minecraft:default";

/// Fonts nest through `reference` providers; this stops a cycle.
const MAX_DEPTH: usize = 8;

/// Code point → advance in pixels, for [DEFAULT_FONT] in the client cache
/// at [client_dir] (the folder holding `assets/`).
pub fn compute(client_dir: &Path) -> Result<BTreeMap<u32, i32>> {
    let mut advances = BTreeMap::new();
    let mut visiting = HashSet::new();
    load_font(client_dir, DEFAULT_FONT, 0, &mut visiting, &mut advances)?;
    Ok(advances)
}

/// Computes and writes [ADVANCES] into [client_dir].
pub fn write(client_dir: &Path) -> Result<BTreeMap<u32, i32>> {
    let advances = compute(client_dir)?;
    let json: serde_json::Map<String, Value> = advances
        .iter()
        .map(|(code, advance)| (code.to_string(), Value::from(*advance)))
        .collect();
    write_atomic(
        &client_dir.join(ADVANCES),
        serde_json::to_string(&json)?.as_bytes(),
    )?;
    Ok(advances)
}

/// `minecraft:include/default` → (`minecraft`, `include/default`).
fn split_id(id: &str) -> (&str, &str) {
    id.split_once(':').unwrap_or(("minecraft", id))
}

/// A namespace or path from a font file must stay a plain relative path.
fn safe(part: &str) -> bool {
    !part.is_empty()
        && !part.contains(['\\', ':', '\0'])
        && !part.starts_with('/')
        && part
            .split('/')
            .all(|s| !s.is_empty() && s != "." && s != "..")
}

fn load_font(
    client_dir: &Path,
    id: &str,
    depth: usize,
    visiting: &mut HashSet<String>,
    out: &mut BTreeMap<u32, i32>,
) -> Result<()> {
    if depth > MAX_DEPTH || !visiting.insert(id.to_string()) {
        return Ok(());
    }
    let (namespace, path) = split_id(id);
    if !safe(namespace) || !safe(path) {
        bail!("\"{id}\" isn't a font id");
    }
    let file = client_dir.join(format!("assets/{namespace}/font/{path}.json"));
    let text = match std::fs::read(&file) {
        Ok(bytes) => bytes,
        // A missing included font is skipped, as the game does.
        Err(e) if e.kind() == std::io::ErrorKind::NotFound && depth > 0 => {
            visiting.remove(id);
            return Ok(());
        }
        Err(e) => bail!("Couldn't read the font {id}: {e}"),
    };
    let json: Value =
        serde_json::from_slice(&text).context(|| format!("The font {id} isn't JSON"))?;
    let providers = json
        .get("providers")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    for provider in &providers {
        if !applies(provider) {
            continue;
        }
        match provider.get("type").and_then(Value::as_str) {
            Some("reference") => {
                if let Some(child) = provider.get("id").and_then(Value::as_str) {
                    load_font(client_dir, child, depth + 1, visiting, out)?;
                }
            }
            Some("space") => {
                if let Some(advances) = provider.get("advances").and_then(Value::as_object) {
                    for (chars, advance) in advances {
                        let Some(advance) = advance.as_f64() else {
                            continue;
                        };
                        if let Some(code) = single_code_point(chars) {
                            out.entry(code).or_insert(advance.round() as i32);
                        }
                    }
                }
            }
            Some("bitmap") => {
                // A broken bitmap shouldn't hide every other provider.
                if let Err(error) = bitmap(client_dir, provider, out) {
                    eprintln!("[netherforge] skipped a bitmap in the font {id}: {error}");
                }
            }
            _ => {}
        }
    }
    visiting.remove(id);
    Ok(())
}

/// Whether a provider applies with the client's default font options (all off).
fn applies(provider: &Value) -> bool {
    match provider.get("filter").and_then(Value::as_object) {
        None => true,
        Some(filter) => filter
            .values()
            .all(|wanted| wanted.as_bool() == Some(false)),
    }
}

fn single_code_point(text: &str) -> Option<u32> {
    let mut chars = text.chars();
    let first = chars.next()?;
    chars.next().is_none().then_some(first as u32)
}

fn bitmap(client_dir: &Path, provider: &Value, out: &mut BTreeMap<u32, i32>) -> Result<()> {
    let file = provider
        .get("file")
        .and_then(Value::as_str)
        .context(|| "A bitmap provider has no file".into())?;
    let (namespace, path) = split_id(file);
    if !safe(namespace) || !safe(path) {
        bail!("\"{file}\" isn't a texture id");
    }
    let rows: Vec<Vec<u32>> = provider
        .get("chars")
        .and_then(Value::as_array)
        .context(|| "A bitmap provider has no chars".into())?
        .iter()
        .filter_map(Value::as_str)
        .map(|row| row.chars().map(|c| c as u32).collect())
        .collect();
    let columns = rows.first().map(Vec::len).unwrap_or(0);
    if rows.is_empty() || columns == 0 || rows.iter().any(|row| row.len() != columns) {
        bail!("{file}: every row of chars must have the same length");
    }
    let image = Alpha::decode(&client_dir.join(format!("assets/{namespace}/textures/{path}")))?;
    let cell_width = image.width / columns;
    let cell_height = image.height / rows.len();
    if cell_width == 0 || cell_height == 0 {
        bail!("{file} is smaller than its grid");
    }
    let height = provider
        .get("height")
        .and_then(Value::as_f64)
        .unwrap_or(8.0);
    for (row, codes) in rows.iter().enumerate() {
        for (column, &code) in codes.iter().enumerate() {
            if code == 0 {
                continue;
            }
            let width = image.glyph_width(
                column * cell_width,
                row * cell_height,
                cell_width,
                cell_height,
            );
            let advance = bitmap_advance(width, cell_height, height);
            out.entry(code).or_insert(advance);
        }
    }
    Ok(())
}

/// How far a bitmap glyph moves the cursor: its opaque width scaled to the
/// drawn height, rounded half up, plus a pixel of gap. Format's
/// `PackFonts.bitmapAdvance` is the same rule; both are held to
/// `packages/format/testdata/text/bitmap-advances.json`.
pub fn bitmap_advance(opaque_width: usize, image_height: usize, drawn_height: f64) -> i32 {
    (0.5 + opaque_width as f64 * drawn_height / image_height as f64).floor() as i32 + 1
}

/// Just the alpha channel of a PNG, which is all a glyph's width depends on.
struct Alpha {
    width: usize,
    height: usize,
    alpha: Vec<u8>,
}

impl Alpha {
    fn decode(path: &Path) -> Result<Self> {
        let file =
            std::fs::File::open(path).context(|| format!("Couldn't open {}", path.display()))?;
        let mut decoder = png::Decoder::new(BufReader::new(file));
        // Palettes and tRNS become real alpha; 16-bit becomes 8-bit.
        decoder.set_transformations(png::Transformations::normalize_to_color8());
        let mut reader = decoder
            .read_info()
            .context(|| format!("{} isn't a PNG", path.display()))?;
        let size = reader
            .output_buffer_size()
            .context(|| format!("{} is too large", path.display()))?;
        let mut buffer = vec![0; size];
        let info = reader
            .next_frame(&mut buffer)
            .context(|| format!("Couldn't decode {}", path.display()))?;
        let (width, height) = (info.width as usize, info.height as usize);
        let channels = match info.color_type {
            png::ColorType::Rgba => Some((4, 3)),
            png::ColorType::GrayscaleAlpha => Some((2, 1)),
            _ => None,
        };
        let mut alpha = vec![255u8; width * height];
        if let Some((stride, offset)) = channels {
            for y in 0..height {
                let line = &buffer[y * info.line_size..];
                for x in 0..width {
                    alpha[y * width + x] = line[x * stride + offset];
                }
            }
        }
        Ok(Self {
            width,
            height,
            alpha,
        })
    }

    /// Rightmost column with a visible pixel in the cell, plus one; 0 if empty.
    fn glyph_width(&self, left: usize, top: usize, width: usize, height: usize) -> usize {
        for x in (0..width).rev() {
            for y in 0..height {
                if self.alpha[(top + y) * self.width + left + x] != 0 {
                    return x + 1;
                }
            }
        }
        0
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// The table format's `PackFonts.bitmapAdvance` is held to as well.
    #[test]
    fn bitmap_advance_matches_the_shared_table() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../packages/format/testdata/text/bitmap-advances.json");
        let table: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        for case in table["cases"].as_array().unwrap() {
            let int = |key: &str| case[key].as_u64().unwrap() as usize;
            assert_eq!(
                bitmap_advance(
                    int("opaqueWidth"),
                    int("imageHeight"),
                    int("drawnHeight") as f64
                ),
                case["advance"].as_i64().unwrap() as i32,
                "{case}"
            );
        }
    }

    /// Writes an RGBA PNG whose opaque pixels are where [opaque] says.
    pub fn write_png(path: &Path, width: u32, height: u32, opaque: impl Fn(u32, u32) -> bool) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let file = std::fs::File::create(path).unwrap();
        let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().unwrap();
        let mut data = Vec::new();
        for y in 0..height {
            for x in 0..width {
                let a = if opaque(x, y) { 255 } else { 0 };
                data.extend_from_slice(&[255, 255, 255, a]);
            }
        }
        writer.write_image_data(&data).unwrap();
    }

    /// A made-up font laid out like the real one: default → space + include.
    pub fn synthetic_font(client: &Path) {
        let font = client.join("assets/minecraft/font");
        std::fs::create_dir_all(font.join("include")).unwrap();
        std::fs::write(
            font.join("default.json"),
            r#"{"providers":[
                {"type":"reference","id":"minecraft:include/space"},
                {"type":"reference","id":"minecraft:include/default","filter":{"uniform":false}},
                {"type":"reference","id":"minecraft:include/uniform","filter":{"uniform":true}},
                {"type":"reference","id":"minecraft:include/missing"},
                {"type":"unihex","hex_file":"minecraft:font/unifont.zip","size_overrides":[]}
            ]}"#,
        )
        .unwrap();
        std::fs::write(
            font.join("include/space.json"),
            r#"{"providers":[{"type":"space","advances":{" ":4,"‌":0}}]}"#,
        )
        .unwrap();
        // Two rows of two 8×8 cells: "AB" / "C\u0000". A is 5 wide, B 1 wide,
        // C empty (advance 1). B also appears in a later provider, which loses.
        std::fs::write(
            font.join("include/default.json"),
            r#"{"providers":[
                {"type":"bitmap","file":"minecraft:font/test.png","ascent":7,"chars":["AB","C\u0000"]},
                {"type":"bitmap","file":"minecraft:font/big.png","height":16,"ascent":7,"chars":["BD"]}
            ]}"#,
        )
        .unwrap();
        std::fs::write(
            font.join("include/uniform.json"),
            r#"{"providers":[{"type":"space","advances":{"Z":99}}]}"#,
        )
        .unwrap();
        let textures = client.join("assets/minecraft/textures/font");
        write_png(&textures.join("test.png"), 16, 16, |x, y| {
            (y < 8 && x < 5 && x == 4) || (y < 8 && x == 8)
        });
        // 2 cells of 4×4 drawn at height 16 (scale 4): D is 3 wide → 12 + 1.
        write_png(&textures.join("big.png"), 8, 4, |x, _| x == 6);
    }

    #[test]
    fn computes_advances_like_the_game() {
        let tmp = tempfile::tempdir().unwrap();
        synthetic_font(tmp.path());
        let advances = compute(tmp.path()).unwrap();
        let expected: BTreeMap<u32, i32> = [
            (' ' as u32, 4),
            (0x200c, 0),
            ('A' as u32, 6),
            ('B' as u32, 2),
            ('C' as u32, 1),
            ('D' as u32, 13),
        ]
        .into_iter()
        .collect();
        assert_eq!(advances, expected);
    }

    #[test]
    fn writes_the_cache_file() {
        let tmp = tempfile::tempdir().unwrap();
        synthetic_font(tmp.path());
        write(tmp.path()).unwrap();
        let json: Value =
            serde_json::from_slice(&std::fs::read(tmp.path().join(ADVANCES)).unwrap()).unwrap();
        assert_eq!(json["65"], 6);
        assert_eq!(json["32"], 4);
    }

    #[test]
    fn a_missing_font_is_an_error_and_cycles_stop() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(compute(tmp.path()).is_err());
        let font = tmp.path().join("assets/minecraft/font");
        std::fs::create_dir_all(&font).unwrap();
        std::fs::write(
            font.join("default.json"),
            r#"{"providers":[{"type":"reference","id":"minecraft:default"},{"type":"reference","id":"../../../x"},{"type":"space","advances":{"a":3}}]}"#,
        )
        .unwrap();
        // The bad reference is an error rather than a read outside the cache.
        assert!(compute(tmp.path()).is_err());
        std::fs::write(
            font.join("default.json"),
            r#"{"providers":[{"type":"reference","id":"minecraft:default"},{"type":"space","advances":{"a":3}}]}"#,
        )
        .unwrap();
        assert_eq!(compute(tmp.path()).unwrap().get(&('a' as u32)), Some(&3));
    }
}
