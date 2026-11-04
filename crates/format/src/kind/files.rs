//! The kinds that hold files format doesn't read as documents: a build's
//! parts, a map's world, a WASM module, a migration's SQL. Format's
//! `BuildKind`, `MapKind`, `WasmModuleKind` and `MigrationKind`, as far as
//! their checks of themselves go (a WASM module's are of its binary, which
//! come with the WASM reader).

use std::sync::LazyLock;

use regex::Regex;

use crate::problem::{Problem, codes};
use crate::snapshot::Found;

/// A build's part's side, in blocks.
pub const PART_SIDE: u32 = 48;
/// The most parts a build has along each axis.
pub const MAX_PARTS: u32 = 22;
const MISSING_SHOWN: usize = 5;

static PART: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(0|[1-9][0-9]{0,3})_(0|[1-9][0-9]{0,3})_(0|[1-9][0-9]{0,3})\.nbt$")
        .expect("a pattern")
});
static MIGRATION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(\d{3})_[a-z0-9_]+$").expect("a pattern"));

/// A build part's place in the grid, from its file's name (`1_0_2.nbt`).
pub fn part_of(file: &str) -> Option<(u32, u32, u32)> {
    let c = PART.captures(file)?;
    Some((c[1].parse().ok()?, c[2].parse().ok()?, c[3].parse().ok()?))
}

/// A build's checks: its parts' names, its size, and the parts its grid needs.
pub fn build(found: &Found) -> Vec<Problem> {
    let main = found.kind.path_of(&found.id, &found.group);
    let location = found.kind.location_of(&found.id, &found.group);
    let mut problems = Vec::new();
    for file in &found.inside {
        if part_of(file).is_none() {
            problems.push(codes::BUILD_PART_NAME.at(
                &format!("{location}/{file}"),
                format!("{file} isn't a part of the build: parts are <x>_<y>_<z>.nbt, their place in the grid (0_0_0.nbt, 1_0_0.nbt…)"),
                None,
            ));
        }
    }
    let parts: Vec<(u32, u32, u32)> = found.inside.iter().filter_map(|f| part_of(f)).collect();
    let size = |axis: fn(&(u32, u32, u32)) -> u32| parts.iter().map(axis).max().unwrap_or(0) + 1;
    let (x, y, z) = (size(|p| p.0), size(|p| p.1), size(|p| p.2));
    let over: Vec<String> = [("x", x), ("y", y), ("z", z)]
        .into_iter()
        .filter(|(_, n)| *n > MAX_PARTS)
        .map(|(axis, n)| format!("{n} parts along {axis}"))
        .collect();
    if !over.is_empty() {
        problems.push(codes::BUILD_SIZE.at(
            &main,
            format!(
                "The build has {}; a build has at most {MAX_PARTS} along each ({} blocks)",
                over.join(" and "),
                MAX_PARTS * PART_SIDE
            ),
            None,
        ));
        return problems;
    }
    let mut missing = Vec::new();
    for j in 0..y {
        for k in 0..z {
            for i in 0..x {
                let file = format!("{i}_{j}_{k}.nbt");
                if !found.inside.contains(&file) {
                    missing.push(file);
                }
            }
        }
    }
    if !missing.is_empty() {
        let mut shown = missing
            .iter()
            .take(MISSING_SHOWN)
            .cloned()
            .collect::<Vec<_>>()
            .join(", ");
        if missing.len() > MISSING_SHOWN {
            shown += &format!(" and {} more", missing.len() - MISSING_SHOWN);
        }
        let verb = if missing.len() == 1 {
            "isn't"
        } else {
            "aren't"
        };
        problems.push(codes::BUILD_PART_MISSING.at(
            &main,
            format!("The build's parts make a {x} × {y} × {z} grid, but {shown} {verb} there"),
            None,
        ));
    }
    problems
}

/// The databases a migration may have its own version for, beside SQLite's.
pub const DIALECTS: &[&str] = &["mysql", "postgres"];

/// A migration's number (`001` of `001_init`), when it's named as one.
pub fn migration_number(id: &str) -> Option<u32> {
    MIGRATION.captures(id).and_then(|c| c[1].parse().ok())
}

/// A migration's checks: its name, and SQLite's version beside a server's.
pub fn migration(found: &Found) -> Vec<Problem> {
    let main = found.kind.path_of(&found.id, &found.group);
    let mut problems = Vec::new();
    if migration_number(&found.id).is_none() {
        problems.push(codes::MIGRATION_NAME.at(
            &main,
            format!(
                "\"{}.sql\" isn't named NNN_name.sql, like 001_init.sql",
                found.id
            ),
            None,
        ));
    }
    // A single file's resource lists no files when it's only the file: then it's only SQLite's.
    let base = found.inside.is_empty() || found.inside.contains(&format!("{}.sql", found.id));
    let mut dialects: Vec<&str> = found
        .inside
        .iter()
        .filter_map(|f| f.split_once('/').map(|(d, _)| d))
        .filter(|d| DIALECTS.contains(d))
        .collect();
    dialects.sort_unstable();
    if !base && let Some(dialect) = dialects.first() {
        let (folder, file) = main.rsplit_once('/').expect("a migration is in its folder");
        let at = format!("{folder}/{dialect}/{file}");
        problems.push(codes::MIGRATION_DIALECTS.at(
            &at,
            format!(
                "There's no {main} beside {at}: every migration is SQLite's first (every package database starts on SQLite), and a server's version goes beside it"
            ),
            None,
        ));
    }
    problems
}

/// A files kind's checks across the project: a build whose id a structure has
/// too, a migration's number against the others'.
pub fn cross_check(found: &Found, cx: &crate::project::Across) -> Vec<Problem> {
    match found.kind.id {
        "build" if cx.has("structure", &found.id) => vec![codes::BUILD_ID_TAKEN.at(
            &found.kind.path_of(&found.id, &found.group),
            format!(
                "{} has the id \"{}\" too, and scripts name structures and builds alike: rename one",
                cx.path_of("structure", &found.id),
                found.id
            ),
            None,
        )],
        "migration" => migrations(found, cx),
        _ => Vec::new(),
    }
}

/// The dialects a migration has a version for.
fn dialects_of(found: &Found) -> std::collections::BTreeSet<&str> {
    found
        .inside
        .iter()
        .filter_map(|f| f.split_once('/').map(|(d, _)| d))
        .filter(|d| DIALECTS.contains(d))
        .collect()
}

fn pad(number: u32) -> String {
    format!("{number:03}")
}

/// A number used twice, one that doesn't follow the one before it, or a dialect others have and it hasn't.
fn migrations(found: &Found, cx: &crate::project::Across) -> Vec<Problem> {
    let main = found.kind.path_of(&found.id, &found.group);
    let Some(number) = migration_number(&found.id) else {
        return Vec::new();
    };
    let mut numbered: Vec<(u32, &Found)> = cx
        .read("migration")
        .filter_map(|r| Some((migration_number(&r.found.id)?, &r.found)))
        .collect();
    numbered.sort_by(|a, b| (a.0, &a.1.id).cmp(&(b.0, &b.1.id)));
    let same: Vec<&Found> = numbered
        .iter()
        .filter(|(n, _)| *n == number)
        .map(|(_, f)| *f)
        .collect();
    if same.len() > 1 {
        if same[0].id != found.id {
            return vec![codes::MIGRATION_DUPLICATE.at(
                &main,
                format!(
                    "Migration {} is also {}; the numbers must be unique",
                    pad(number),
                    cx.path_of("migration", &same[0].id)
                ),
                None,
            )];
        }
        return Vec::new();
    }
    let mut problems = Vec::new();
    // A dialect some migration has a version for, which this one hasn't: the steps would differ between servers.
    let mine = dialects_of(found);
    let all: std::collections::BTreeSet<&str> =
        numbered.iter().flat_map(|(_, f)| dialects_of(f)).collect();
    for dialect in all.difference(&mine) {
        let (folder, file) = main.rsplit_once('/').expect("a migration is in its folder");
        problems.push(codes::MIGRATION_DIALECTS.at(
            &main,
            format!(
                "Other migrations have a {dialect} version and this one hasn't: add {folder}/{dialect}/{file}, so every server runs the same steps"
            ),
            None,
        ));
    }
    let before = numbered
        .iter()
        .rev()
        .find(|(n, _)| *n < number)
        .map_or(0, |(n, _)| *n);
    if before + 1 != number {
        problems.push(codes::MIGRATION_GAP.at(
            &main,
            format!(
                "Migration {} follows {}: the numbers run {}, {}, {} with none skipped",
                pad(number),
                pad(before),
                pad(1),
                pad(2),
                pad(3)
            ),
            None,
        ));
    }
    problems
}
