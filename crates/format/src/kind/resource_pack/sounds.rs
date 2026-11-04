//! A pack's sounds (format's `PackSounds`): every `.ogg` under `sounds/` is
//! the sound event of its path, and `pack.json`'s `sounds` refines events,
//! adds ones that pick among several files, or (keyed in the game's
//! namespace) replaces one of the game's.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::LazyLock;

use regex::Regex;

use super::ResourcePackFile;
use crate::game;
use crate::problem::{Problem, codes};

pub const FOLDER: &str = "sounds";
pub const EXTENSION: &str = ".ogg";
pub const KEY_RULE: &str = "path segments of lowercase letters, digits and _, joined by /";
/// A sound event's key: id segments joined by `/`.
pub const KEY_PATTERN: &str = r"^[a-z0-9][a-z0-9_]{0,63}(/[a-z0-9][a-z0-9_]{0,63})*$";
/// A sound file's path under `sounds/` that can be an event: a key and `.ogg`.
pub const FILE_PATTERN: &str = r"^[a-z0-9][a-z0-9_]{0,63}(/[a-z0-9][a-z0-9_]{0,63})*\.ogg$";
/// What a sound file's path must be, in words.
pub const FILE_RULE: &str = "a .ogg path inside this resource pack's sounds/ folder (path segments of lowercase letters, digits and _, joined by /)";

static KEY: LazyLock<Regex> = LazyLock::new(|| Regex::new(KEY_PATTERN).unwrap());
static FILE: LazyLock<Regex> = LazyLock::new(|| Regex::new(FILE_PATTERN).unwrap());
static GAME_EVENT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-z0-9_.-]+(/[a-z0-9_.-]+)*$").unwrap());

crate::ruled_string!(
    /// A sound file's path under `sounds/`.
    SoundPath,
    FILE_PATTERN,
    "resource_pack.sound-path",
    FILE_RULE,
    "x-ref" = "sound_file"
);

pub fn is_key(key: &str) -> bool {
    KEY.is_match(key)
}

pub fn is_file(path: &str) -> bool {
    FILE.is_match(path)
}

/// Whether a `sounds` key names one of the game's events, to replace it.
pub fn is_game_key(key: &str) -> bool {
    key.strip_prefix(game::NAMESPACE)
        .is_some_and(|rest| rest.starts_with(':'))
}

/// Whether `key` is one of the game's events written in full: `minecraft:block.stone.break`.
pub fn is_game_event(key: &str) -> bool {
    is_game_key(key) && GAME_EVENT.is_match(&key[game::NAMESPACE.len() + 1..])
}

/// The event a sound file is: its path without `.ogg`.
pub fn key_of(file: &str) -> &str {
    file.strip_suffix(EXTENSION).unwrap_or(file)
}

/// The usable sound files among `files` (paths under a pack's `sounds/`), and
/// the problems with the rest, each in the file itself (`location` the pack's
/// folder). Hidden files are nobody's business.
pub fn files<'a>(
    location: &str,
    files: impl IntoIterator<Item = &'a str>,
) -> (BTreeSet<String>, Vec<Problem>) {
    let mut usable = BTreeSet::new();
    let mut problems = Vec::new();
    let mut sorted: Vec<&str> = files.into_iter().collect();
    sorted.sort();
    for file in sorted {
        if file.split('/').any(|s| s.starts_with('.')) {
            continue;
        }
        let at = format!("{location}/{FOLDER}/{file}");
        if is_file(file) {
            usable.insert(file.to_owned());
        } else if file.to_lowercase().ends_with(EXTENSION) {
            problems.push(codes::RESOURCE_PACK_SOUND_NAME.at(
                &at,
                format!("\"{file}\" can't be a sound: its path must be {KEY_RULE}, ending in {EXTENSION}"),
                None,
            ));
        } else {
            problems.push(codes::RESOURCE_PACK_SOUND_FILE.at(
                &at,
                format!("Minecraft only plays Ogg Vorbis files, so this isn't a sound: convert it to {EXTENSION}"),
                None,
            ));
        }
    }
    (usable, problems)
}

/// One sound event as it's built: the files (under `sounds/`) it picks among, and how it plays them.
#[derive(Clone, Debug, PartialEq)]
pub struct Sound {
    pub key: String,
    pub files: Vec<String>,
    pub volume: Option<f64>,
    pub pitch: Option<f64>,
    pub subtitle: Option<String>,
    pub stream: bool,
}

/// Every sound event of a pack by key, from its usable `files` and its
/// `pack.json`: each file is an event, and an entry in `sounds` replaces or
/// adds the event of its key.
pub fn events(file: &ResourcePackFile, files: &BTreeSet<String>) -> BTreeMap<String, Sound> {
    let mut out = BTreeMap::new();
    for path in files {
        let key = key_of(path).to_owned();
        out.insert(
            key.clone(),
            Sound {
                key,
                files: vec![path.clone()],
                volume: None,
                pitch: None,
                subtitle: None,
                stream: false,
            },
        );
    }
    for (key, def) in file.sounds.iter() {
        if is_game_key(key) {
            continue;
        }
        let files = def.files.as_ref().map_or_else(
            || vec![format!("{key}{EXTENSION}")],
            |f| f.iter().map(|p| p.0.clone()).collect(),
        );
        out.insert(key.0.clone(), def.sound(&key.0, files));
    }
    out
}

/// The game's sound events a pack replaces, each playing its `files` instead.
pub fn replacements(file: &ResourcePackFile) -> BTreeMap<String, Sound> {
    file.sounds
        .iter()
        .filter(|(key, def)| {
            is_game_event(key) && def.files.as_ref().is_some_and(|f| !f.is_empty())
        })
        .map(|(key, def)| {
            let files = def.files.iter().flatten().map(|p| p.0.clone()).collect();
            (key.0.clone(), def.sound(&key.0, files))
        })
        .collect()
}
