//! Where things are in a datapack, as the game lays one out (format's
//! `DatapackLayout` and `DatapackPath`): `data/<namespace>/<registry>/<path>.json`
//! for an entry, `data/<namespace>/tags/<registry>/<path>.json` for a tag, and
//! the same under an overlay's folder. Only worldgen passes through, and the
//! tags of a few other registries (`TAG_REGISTRIES`).

use std::collections::BTreeSet;
use std::sync::LazyLock;

use regex::Regex;

use super::PackMeta;

/// The folder every pack and overlay keeps its files in.
pub const DATA: &str = "data";
/// The folder of the game's worldgen registries, under a namespace.
pub const WORLDGEN: &str = "worldgen";
const TAGS: &str = "tags";
const JSON: &str = ".json";
/// Registries outside worldgen whose tags pass through: blocks, for items' `canBreak`.
pub const TAG_REGISTRIES: &[&str] = &["block"];
/// A namespace, a folder of a path and an overlay's folder, as the game takes them.
pub const NAME_PATTERN: &str = "^[a-z0-9_.-]+$";

static NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(NAME_PATTERN).unwrap());

pub fn is_name(text: &str) -> bool {
    NAME.is_match(text)
}

/// One file of a project datapack, placed: the overlay it's in, and the registry entry or tag it is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DatapackPath {
    /// Its path inside the datapack's folder.
    pub file: String,
    pub overlay: Option<String>,
    pub namespace: String,
    /// The registry, as its folder under `data/<namespace>/` (`worldgen/placed_feature`).
    pub registry: String,
    /// The entry's (or tag's) path in its namespace: `trees/oak`.
    pub path: String,
    pub tag: bool,
}

impl DatapackPath {
    /// `namespace:path`.
    pub fn key(&self) -> String {
        format!("{}:{}", self.namespace, self.path)
    }

    /// Where it goes in a datapack without overlays.
    pub fn target(&self) -> String {
        entry_file(&self.namespace, &self.path, &self.registry, self.tag)
    }
}

/// `data/<namespace>/<registry>/<path>.json`, or under `tags/` for a tag.
pub fn entry_file(namespace: &str, path: &str, registry: &str, tag: bool) -> String {
    let tags = if tag { "tags/" } else { "" };
    format!("{DATA}/{namespace}/{tags}{registry}/{path}{JSON}")
}

/// `file`, a path inside a datapack's folder, as one of its files, with
/// `overlays` its overlays' folders: where it is, or why it can't be one.
pub fn place(file: &str, overlays: &BTreeSet<String>) -> Result<DatapackPath, String> {
    let parts: Vec<&str> = file.split('/').collect();
    let (overlay, rest) = if parts.first() == Some(&DATA) {
        (None, &parts[1..])
    } else if parts.len() > 1 && overlays.contains(parts[0]) && parts[1] == DATA {
        (Some(parts[0].to_owned()), &parts[2..])
    } else {
        let listed = if overlays.is_empty() {
            String::new()
        } else {
            format!(
                " ({})",
                overlays.iter().cloned().collect::<Vec<_>>().join(", ")
            )
        };
        return Err(format!(
            "Only {DATA}/ and the overlays pack.mcmeta lists{listed} hold a datapack's files"
        ));
    };
    let namespace = rest.first().copied().unwrap_or_default();
    if !is_name(namespace) {
        return Err(format!(
            "\"{namespace}\" isn't a namespace: lowercase letters, digits, _, - and ."
        ));
    }
    let in_namespace = &rest[1.min(rest.len())..];
    let tag = in_namespace.first() == Some(&TAGS);
    let registry_at = if tag {
        &in_namespace[1..]
    } else {
        in_namespace
    };
    // A tag of one of TAG_REGISTRIES is `tags/<registry>/<path>.json`; everything else is worldgen's, a folder deeper.
    let other = tag
        && registry_at
            .first()
            .is_some_and(|r| TAG_REGISTRIES.contains(r))
        && registry_at.len() >= 2;
    if !other && (registry_at.first() != Some(&WORLDGEN) || registry_at.len() < 3) {
        let others: Vec<String> = TAG_REGISTRIES
            .iter()
            .map(|r| format!("{r} tags in {DATA}/{namespace}/{TAGS}/{r}/"))
            .collect();
        return Err(format!(
            "Only worldgen passes through, and tags of blocks: entries go in {DATA}/{namespace}/{WORLDGEN}/<registry>/, \
             tags of them in {DATA}/{namespace}/{TAGS}/{WORLDGEN}/<registry>/, and {}",
            others.join(", ")
        ));
    }
    let depth = if other { 1 } else { 2 };
    let registry = if other {
        registry_at[0].to_owned()
    } else {
        format!("{WORLDGEN}/{}", registry_at[1])
    };
    let last = registry_at[registry_at.len() - 1];
    let Some(stem) = last.strip_suffix(JSON).filter(|s| !s.is_empty()) else {
        return Err("A datapack's files are .json".into());
    };
    let mut segments: Vec<&str> = registry_at[depth..registry_at.len() - 1].to_vec();
    segments.push(stem);
    let path = segments.join("/");
    if !is_name(registry_at[depth - 1]) || !segments.iter().all(|s| is_name(s)) {
        return Err(format!(
            "\"{path}\" isn't an id the game takes: lowercase letters, digits, _, - and ., in folders"
        ));
    }
    Ok(DatapackPath {
        file: file.to_owned(),
        overlay,
        namespace: namespace.to_owned(),
        registry,
        path,
        tag,
    })
}

/// The folders `meta`'s overlays are in.
pub fn overlays_of(meta: &PackMeta) -> BTreeSet<String> {
    meta.overlays
        .iter()
        .flat_map(|o| &o.entries)
        .map(|e| e.directory.clone())
        .collect()
}

/// Every file of a datapack with `files` (paths in its folder) and `meta` that is one, placed.
pub fn entries<'a>(
    meta: &PackMeta,
    files: impl IntoIterator<Item = &'a String>,
) -> Vec<DatapackPath> {
    let overlays = overlays_of(meta);
    files
        .into_iter()
        .filter(|f| f.as_str() != super::FILE_NAME)
        .filter_map(|f| place(f, &overlays).ok())
        .collect()
}
