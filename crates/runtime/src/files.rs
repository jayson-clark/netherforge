//! The one directory scripts may keep things in (Kotlin's `ScriptFiles`, `FileImpl`, `NfFilesImpl`).
//!
//! Paths are checked as strings before anything touches the disk, and a path that fails is refused (`nil` in Lua)
//! rather than clamped. The rules are stricter than any one filesystem needs so a project behaves the same on
//! every OS: no `..`, no backslashes, no names that start with a dot or a space, none of Windows' reserved device
//! names. **Each package has a folder of its own**, `<root>/<namespace>/`: a `File` handle's key is
//! `<namespace>/<path>` (`nf.files.get` puts the calling package's in front) and never climbs above it, so no package
//! reads or writes another's files unless it's handed one. Each package's folder is held to its limits (bytes and
//! entries), counted once and kept up to date by every change made here. Files from before folders were per package
//! are moved into the project's (`adopt`).

use std::collections::HashMap;
use std::fs;
use std::io::Write as _;
use std::path::{Path, PathBuf};

use base64::Engine as _;
use mlua::{Function, Value};
use netherforge_api::api::{FileApi, NfFilesApi};
use netherforge_api::{ApiError, ApiResult, Caller, handles};

use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

pub const MAX_BYTES: u64 = 1 << 20;
const MAX_NAME: usize = 64;
const MAX_PATH: usize = 200;
const MAX_DEPTH: usize = 8;
const MAX_LIST: usize = 500;
/// In the root: the files are in a folder per package (`adopt` has run). Not a name a script can use.
pub const LAYOUT_MARKER: &str = ".netherforge-files";
const RESERVED: &[&str] = &[
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
    "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// What the server owner lets each package keep (`files:` in `config.yml`).
#[derive(Clone, Copy, Debug)]
pub struct FilesConfig {
    pub max_bytes_per_package: u64,
    pub max_entries_per_package: u64,
}

impl Default for FilesConfig {
    fn default() -> FilesConfig {
        FilesConfig {
            max_bytes_per_package: 64 * 1024 * 1024,
            max_entries_per_package: 10_000,
        }
    }
}

/// What one package's folder holds.
#[derive(Clone, Copy)]
struct Usage {
    bytes: u64,
    entries: u64,
}

pub struct ScriptFiles {
    root: PathBuf,
    limits: FilesConfig,
    /// By namespace, counted the first time it's needed, then kept up to date.
    usages: HashMap<String, Usage>,
}

fn is_name(name: &str) -> bool {
    if name.is_empty() || name.len() > MAX_NAME {
        return false;
    }
    let allowed = name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | ' ' | '-'));
    if !allowed || name.starts_with(['.', ' ']) || name.ends_with([' ', '.']) {
        return false;
    }
    let stem = name.split('.').next().unwrap_or(name).to_ascii_lowercase();
    !RESERVED.contains(&stem.as_str())
}

/// The canonical form of `path` (`""` for the root), or none when it isn't allowed.
pub fn normalize(path: &str) -> Option<String> {
    let trimmed = path.trim_matches('/');
    if trimmed.is_empty() {
        return Some(String::new());
    }
    if path.len() > MAX_PATH {
        return None;
    }
    let parts: Vec<&str> = trimmed.split('/').collect();
    if parts.len() > MAX_DEPTH || !parts.iter().all(|p| is_name(p)) {
        return None;
    }
    Some(parts.join("/"))
}

/// The package folder (first segment) of a normalized path.
fn namespace_of(normalized: &str) -> &str {
    normalized.split('/').next().unwrap_or(normalized)
}

fn walk(path: &Path, each: &mut dyn FnMut(&Path, &fs::Metadata)) {
    let Ok(entries) = fs::read_dir(path) else {
        return;
    };
    for entry in entries.flatten() {
        let Ok(meta) = fs::symlink_metadata(entry.path()) else {
            continue;
        };
        each(&entry.path(), &meta);
        if meta.is_dir() {
            walk(&entry.path(), each);
        }
    }
}

impl ScriptFiles {
    pub fn new(root: PathBuf, limits: FilesConfig) -> ScriptFiles {
        ScriptFiles {
            root,
            limits,
            usages: HashMap::new(),
        }
    }

    fn usage(&mut self, namespace: &str) -> Usage {
        if let Some(usage) = self.usages.get(namespace) {
            return *usage;
        }
        let (mut bytes, mut entries) = (0, 0);
        walk(&self.root.join(namespace), &mut |_, meta| {
            entries += 1;
            if meta.is_file() {
                bytes += meta.len();
            }
        });
        let usage = Usage { bytes, entries };
        self.usages.insert(namespace.to_owned(), usage);
        usage
    }

    fn grow(&mut self, namespace: &str, bytes: i64, entries: i64) {
        let usage = self.usage(namespace);
        self.usages.insert(
            namespace.to_owned(),
            Usage {
                bytes: (usage.bytes as i64 + bytes).max(0) as u64,
                entries: (usage.entries as i64 + entries).max(0) as u64,
            },
        );
    }

    /// Whether `namespace`'s folder has room for `bytes` more and `entries` more.
    fn room(&mut self, namespace: &str, bytes: i64, entries: u64) -> bool {
        let usage = self.usage(namespace);
        usage.bytes as i64 + bytes <= self.limits.max_bytes_per_package as i64
            && usage.entries + entries <= self.limits.max_entries_per_package
    }

    /// Folders `target` needs made above it, inside its package's folder: each an entry.
    fn missing_folders(&self, target: &Path, namespace: &str) -> u64 {
        let top = self.root.join(namespace);
        let mut count = 0;
        let mut at = target.parent();
        while let Some(dir) = at {
            if !dir.starts_with(&top) || dir == top || dir.exists() {
                break;
            }
            count += 1;
            at = dir.parent();
        }
        count
    }

    /// Moves files kept from before each package had a folder into `namespace`'s (the project's), once. A marker
    /// file (a name scripts can't use) says it's done.
    pub fn adopt(&mut self, namespace: &str) {
        let marker = self.root.join(LAYOUT_MARKER);
        if marker.exists() {
            return;
        }
        let moved = (|| -> std::io::Result<()> {
            fs::create_dir_all(&self.root)?;
            let old: Vec<PathBuf> = fs::read_dir(&self.root)?
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.file_name().is_some_and(|n| n != LAYOUT_MARKER))
                .collect();
            if !old.is_empty() {
                let moving = self.root.join(format!(".moving-{}", std::process::id()));
                fs::create_dir_all(&moving)?;
                for entry in old {
                    let name = entry.file_name().expect("a name").to_owned();
                    fs::rename(&entry, moving.join(name))?;
                }
                fs::rename(&moving, self.root.join(namespace))?;
            }
            fs::write(
                &marker,
                "Each package's nf.files are in the folder named after its namespace.\n",
            )
        })();
        // Left as it was on a failure: the next start tries again.
        let _ = moved;
        self.usages.clear();
    }

    /// `path`'s place on disk, once it's allowed and stays inside the root with every link followed.
    fn resolve(&self, path: &str) -> Option<PathBuf> {
        let normalized = normalize(path)?;
        let resolved = if normalized.is_empty() {
            self.root.clone()
        } else {
            self.root.join(&normalized)
        };
        // normalize() rules out `..`; a link placed in the folder by hand could still point out of it.
        let real = real(&resolved)?;
        let real_root = real_path(&self.root)?;
        real.starts_with(real_root).then_some(resolved)
    }

    pub fn exists(&self, path: &str) -> bool {
        self.resolve(path).is_some_and(|p| p.exists())
    }

    pub fn is_directory(&self, path: &str) -> bool {
        normalize(path).as_deref() == Some("") || self.resolve(path).is_some_and(|p| p.is_dir())
    }

    pub fn size(&self, path: &str) -> Option<u64> {
        let p = self.resolve(path)?;
        p.is_file()
            .then(|| p.metadata().map(|m| m.len()).ok())
            .flatten()
    }

    /// Names directly inside, sorted; empty for a file or nothing.
    pub fn list(&self, path: &str) -> Vec<String> {
        let Some(dir) = self.resolve(path).filter(|p| p.is_dir()) else {
            return Vec::new();
        };
        let mut names: Vec<String> = fs::read_dir(dir)
            .map(|entries| {
                entries
                    .flatten()
                    .filter_map(|e| e.file_name().to_str().map(str::to_owned))
                    .filter(|n| is_name(n))
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names.truncate(MAX_LIST);
        names
    }

    /// The file's bytes, or none when there's no file.
    pub fn read_bytes(&self, path: &str) -> Option<Vec<u8>> {
        let p = self.resolve(path).filter(|p| p.is_file())?;
        fs::read(p).ok()
    }

    pub fn read(&self, path: &str) -> Option<String> {
        self.read_bytes(path)
            .map(|b| String::from_utf8_lossy(&b).into_owned())
    }

    /// Replaces or appends `bytes`. False for a directory, past `MAX_BYTES`, past the package's room, or on an I/O
    /// failure.
    pub fn write(&mut self, path: &str, bytes: &[u8], append: bool) -> bool {
        let Some(target) = self.resolve(path) else {
            return false;
        };
        let normalized = normalize(path).expect("resolved");
        if normalized.is_empty() || target.is_dir() {
            return false;
        }
        let before = target
            .is_file()
            .then(|| target.metadata().map_or(0, |m| m.len()));
        let existing = if append { before.unwrap_or(0) } else { 0 };
        if existing + bytes.len() as u64 > MAX_BYTES {
            return false;
        }
        let namespace = namespace_of(&normalized).to_owned();
        let grows = (existing + bytes.len() as u64) as i64 - before.unwrap_or(0) as i64;
        let made = u64::from(before.is_none()) + self.missing_folders(&target, &namespace);
        if !self.room(&namespace, grows, made) {
            return false;
        }
        let written = (|| -> std::io::Result<()> {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            if append {
                fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&target)?
                    .write_all(bytes)
            } else {
                fs::write(&target, bytes)
            }
        })();
        match written {
            Ok(()) => {
                self.grow(&namespace, grows, made as i64);
                true
            }
            Err(_) => {
                self.usages.remove(&namespace);
                false
            }
        }
    }

    pub fn make_directory(&mut self, path: &str) -> bool {
        let Some(target) = self.resolve(path) else {
            return false;
        };
        let normalized = normalize(path).expect("resolved");
        if !normalized.contains('/') {
            return target.is_dir() || fs::create_dir_all(&target).is_ok();
        }
        let namespace = namespace_of(&normalized).to_owned();
        let made = u64::from(!target.exists()) + self.missing_folders(&target, &namespace);
        if !self.room(&namespace, 0, made) {
            return false;
        }
        match fs::create_dir_all(&target) {
            Ok(()) => {
                self.grow(&namespace, 0, made as i64);
                true
            }
            Err(_) => {
                self.usages.remove(&namespace);
                false
            }
        }
    }

    /// When it last changed, in Unix milliseconds; none when nothing is there.
    pub fn modified(&self, path: &str) -> Option<i64> {
        let p = self.resolve(path).filter(|p| p.exists())?;
        let time = p.metadata().ok()?.modified().ok()?;
        let since = time.duration_since(std::time::UNIX_EPOCH).ok()?;
        Some(since.as_millis() as i64)
    }

    /// Both ends of a move or copy, resolved: something at the first, nothing at the second, neither the root.
    fn pair(&self, from: &str, to: &str) -> Option<(PathBuf, PathBuf)> {
        if normalize(from).is_none_or(|p| p.is_empty())
            || normalize(to).is_none_or(|p| p.is_empty())
        {
            return None;
        }
        let source = self
            .resolve(from)
            .filter(|p| fs::symlink_metadata(p).is_ok())?;
        let target = self
            .resolve(to)
            .filter(|p| fs::symlink_metadata(p).is_err())?;
        Some((source, target))
    }

    /// Moves a file or a directory (with everything in it), creating directories above the target. False when
    /// there's nothing at `from`, something already at `to`, either is the root or not allowed, a directory would
    /// go inside itself, or the move fails.
    pub fn rename(&mut self, from: &str, to: &str) -> bool {
        let Some((source, target)) = self.pair(from, to) else {
            return false;
        };
        if target.starts_with(&source) {
            return false;
        }
        let from_package = namespace_of(&normalize(from).expect("paired")).to_owned();
        let into_package = namespace_of(&normalize(to).expect("paired")).to_owned();
        // Across packages only through a handle another package was given: recounted, both.
        if from_package != into_package {
            let (mut bytes, mut entries) = (0u64, 0u64);
            if source.is_dir() {
                walk(&source, &mut |_, meta| {
                    entries += 1;
                    if meta.is_file() {
                        bytes += meta.len();
                    }
                });
                entries += 1;
            } else {
                bytes = source.metadata().map_or(0, |m| m.len());
                entries = 1;
            }
            let made = entries + self.missing_folders(&target, &into_package);
            if !self.room(&into_package, bytes as i64, made) {
                return false;
            }
        }
        let moved = target
            .parent()
            .map_or(Ok(()), fs::create_dir_all)
            .and_then(|()| fs::rename(&source, &target));
        self.usages.remove(&from_package);
        self.usages.remove(&into_package);
        moved.is_ok()
    }

    /// Copies a file, creating directories above the target. False for a directory, and as for `rename`.
    pub fn copy(&mut self, from: &str, to: &str) -> bool {
        let Some((source, target)) = self.pair(from, to) else {
            return false;
        };
        if !source.is_file() {
            return false;
        }
        let namespace = namespace_of(&normalize(to).expect("paired")).to_owned();
        let size = source.metadata().map_or(0, |m| m.len());
        let made = 1 + self.missing_folders(&target, &namespace);
        if !self.room(&namespace, size as i64, made) {
            return false;
        }
        let copied = target
            .parent()
            .map_or(Ok(()), fs::create_dir_all)
            .and_then(|()| fs::copy(&source, &target).map(|_| ()));
        match copied {
            Ok(()) => {
                self.grow(&namespace, size as i64, made as i64);
                true
            }
            Err(_) => {
                self.usages.remove(&namespace);
                false
            }
        }
    }

    /// Removes a file, or a directory with nothing in it. Never the root.
    pub fn delete(&mut self, path: &str) -> bool {
        let Some(normalized) = normalize(path).filter(|p| !p.is_empty()) else {
            return false;
        };
        let Some(target) = self.resolve(path) else {
            return false;
        };
        let namespace = namespace_of(&normalized).to_owned();
        let size = if target.is_file() {
            target.metadata().map_or(0, |m| m.len())
        } else {
            0
        };
        let deleted = if target.is_dir() {
            fs::remove_dir(&target)
        } else {
            fs::remove_file(&target)
        };
        match deleted {
            Ok(()) => {
                self.grow(&namespace, -(size as i64), -1);
                true
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
            Err(_) => {
                self.usages.remove(&namespace);
                false
            }
        }
    }
}

fn real_path(path: &Path) -> Option<PathBuf> {
    fs::canonicalize(path).ok().or_else(|| real(path))
}

/// `path` with every link along it followed: the deepest part that exists resolved for real, the rest appended.
/// None when that part can't be resolved (a dangling link).
fn real(path: &Path) -> Option<PathBuf> {
    let absolute = std::path::absolute(path).ok()?;
    let mut existing = absolute.clone();
    while fs::symlink_metadata(&existing).is_err() {
        match existing.parent() {
            Some(parent) => existing = parent.to_path_buf(),
            None => return Some(absolute),
        }
    }
    let rest = absolute.strip_prefix(&existing).ok()?.to_path_buf();
    Some(fs::canonicalize(&existing).ok()?.join(rest))
}

impl GameContext {
    fn with_files<R>(&self, f: impl FnOnce(&mut ScriptFiles) -> R) -> Option<R> {
        self.shared.files.borrow_mut().as_mut().map(f)
    }

    /// The handle's full path, checked again: handles are only made from normalized paths.
    fn checked(this: &handles::File) -> ApiResult<String> {
        normalize(&this.path)
            .filter(|p| !p.is_empty())
            .ok_or_else(|| err("not a usable file path"))
    }

    fn namespace_of_file(this: &handles::File) -> &str {
        namespace_of(&this.path)
    }

    /// The path a script sees: the handle's, past its package folder.
    fn relative(this: &handles::File) -> &str {
        this.path.split_once('/').map_or("", |(_, rest)| rest)
    }

    /// A handle in `this`'s package folder for `path` (relative to it), or none when it isn't allowed.
    fn sibling(this: &handles::File, path: &str) -> Option<handles::File> {
        let relative = normalize(path)?;
        let namespace = Self::namespace_of_file(this);
        Some(handles::File {
            path: if relative.is_empty() {
                namespace.to_owned()
            } else {
                format!("{namespace}/{relative}")
            },
        })
    }
}

impl NfFilesApi for GameContext {
    /// A file in the calling package's own folder.
    fn get(&self, _caller: &Caller, path: Option<String>) -> ApiResult<Option<handles::File>> {
        let Some(relative) = normalize(path.as_deref().unwrap_or("")) else {
            return Ok(None);
        };
        let namespace = self.calling_namespace();
        Ok(Some(handles::File {
            path: if relative.is_empty() {
                namespace
            } else {
                format!("{namespace}/{relative}")
            },
        }))
    }
}

impl FileApi for GameContext {
    fn path(&self, this: &handles::File) -> ApiResult<String> {
        Ok(Self::relative(this).to_owned())
    }

    fn name(&self, this: &handles::File) -> ApiResult<String> {
        let relative = Self::relative(this);
        Ok(relative.rsplit('/').next().unwrap_or(relative).to_owned())
    }

    fn parent(&self, this: &handles::File) -> ApiResult<Option<handles::File>> {
        if Self::relative(this).is_empty() {
            return Ok(None);
        }
        Ok(this.path.rsplit_once('/').map(|(parent, _)| handles::File {
            path: parent.to_owned(),
        }))
    }

    fn child(&self, this: &handles::File, name: String) -> ApiResult<Option<handles::File>> {
        if name.contains('/') {
            return Ok(None);
        }
        let relative = Self::relative(this);
        let path = if relative.is_empty() {
            name
        } else {
            format!("{relative}/{name}")
        };
        Ok(Self::sibling(this, &path))
    }

    fn exists(&self, this: &handles::File) -> ApiResult<bool> {
        let path = Self::checked(this)?;
        Ok(self.with_files(|f| f.exists(&path)).unwrap_or(false))
    }

    fn is_folder(&self, this: &handles::File) -> ApiResult<bool> {
        let path = Self::checked(this)?;
        Ok(self.with_files(|f| f.is_directory(&path)).unwrap_or(false))
    }

    fn size(&self, this: &handles::File) -> ApiResult<Option<i64>> {
        let path = Self::checked(this)?;
        Ok(self
            .with_files(|f| f.size(&path))
            .flatten()
            .map(|s| s as i64))
    }

    fn children(&self, this: &handles::File) -> ApiResult<Vec<handles::File>> {
        let path = Self::checked(this)?;
        let names = self.with_files(|f| f.list(&path)).unwrap_or_default();
        Ok(names
            .into_iter()
            .map(|n| handles::File {
                path: format!("{path}/{n}"),
            })
            .collect())
    }

    fn read(&self, this: &handles::File) -> ApiResult<Option<String>> {
        let path = Self::checked(this)?;
        Ok(self.with_files(|f| f.read(&path)).flatten())
    }

    fn write(&self, this: &handles::File, text: String) -> ApiResult<bool> {
        if Self::relative(this).is_empty() {
            return Ok(false);
        }
        let path = Self::checked(this)?;
        Ok(self
            .with_files(|f| f.write(&path, text.as_bytes(), false))
            .unwrap_or(false))
    }

    fn append(&self, this: &handles::File, text: String) -> ApiResult<bool> {
        if Self::relative(this).is_empty() {
            return Ok(false);
        }
        let path = Self::checked(this)?;
        Ok(self
            .with_files(|f| f.write(&path, text.as_bytes(), true))
            .unwrap_or(false))
    }

    fn read_base64(&self, this: &handles::File) -> ApiResult<Option<String>> {
        let path = Self::checked(this)?;
        Ok(self
            .with_files(|f| f.read_bytes(&path))
            .flatten()
            .map(|b| base64::engine::general_purpose::STANDARD.encode(b)))
    }

    /// Base64 in either alphabet, padded or not, spaces and line breaks ignored.
    fn write_base64(&self, this: &handles::File, data: String) -> ApiResult<bool> {
        if Self::relative(this).is_empty() {
            return Ok(false);
        }
        let path = Self::checked(this)?;
        let compact: String = data
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .trim_end_matches('=')
            .replace('-', "+")
            .replace('_', "/");
        let Ok(bytes) = base64::engine::general_purpose::STANDARD_NO_PAD.decode(compact) else {
            return Ok(false);
        };
        Ok(self
            .with_files(|f| f.write(&path, &bytes, false))
            .unwrap_or(false))
    }

    /// A save someone edited by hand isn't a reason to take a script out of service: nil when it isn't JSON.
    fn read_json(&self, this: &handles::File) -> ApiResult<Value> {
        let path = Self::checked(this)?;
        let Some(text) = self.with_files(|f| f.read(&path)).flatten() else {
            return Ok(Value::Nil);
        };
        let host = self.scripts.host();
        Ok(netherforge_luau::json::decode(
            host.lua(),
            &text,
            &netherforge_api::handle::SavedHandles,
        )
        .unwrap_or(Value::Nil))
    }

    fn write_json(&self, this: &handles::File, value: Value) -> ApiResult<bool> {
        if Self::relative(this).is_empty() {
            return Ok(false);
        }
        let path = Self::checked(this)?;
        let host = self.scripts.host();
        let Ok(encoded) = netherforge_luau::json::encode(
            &host,
            &value,
            "value",
            "written",
            &netherforge_api::handle::SavedHandles,
        ) else {
            return Ok(false);
        };
        let Some(text) = encoded.text.filter(|_| encoded.problems.is_empty()) else {
            return Ok(false);
        };
        Ok(self
            .with_files(|f| f.write(&path, text.as_bytes(), false))
            .unwrap_or(false))
    }

    fn modified_time(&self, this: &handles::File) -> ApiResult<Option<i64>> {
        let path = Self::checked(this)?;
        Ok(self.with_files(|f| f.modified(&path)).flatten())
    }

    /// Each line without its ending (`\n` or `\r\n`); nothing for a file that isn't there.
    fn lines(&self, this: &handles::File) -> ApiResult<Function> {
        let path = Self::checked(this)?;
        let text = self
            .with_files(|f| f.read(&path))
            .flatten()
            .unwrap_or_default();
        let mut lines: Vec<String> = text
            .split('\n')
            .map(|l| l.strip_suffix('\r').unwrap_or(l).to_owned())
            .collect();
        // The text after the last line ending is a line only when there's something in it.
        if lines.last().is_some_and(String::is_empty) {
            lines.pop();
        }
        let lines = std::cell::RefCell::new(lines.into_iter());
        self.scripts
            .host()
            .lua()
            .create_function(move |_, ()| Ok(lines.borrow_mut().next()))
            .map_err(|e| err(e.to_string()))
    }

    fn rename(&self, this: &handles::File, path: String) -> ApiResult<Option<handles::File>> {
        if Self::relative(this).is_empty() {
            return Ok(None);
        }
        let Some(to) = Self::sibling(this, &path).filter(|t| !Self::relative(t).is_empty()) else {
            return Ok(None);
        };
        let from = Self::checked(this)?;
        let moved = self
            .with_files(|f| f.rename(&from, &to.path))
            .unwrap_or(false);
        Ok(moved.then_some(to))
    }

    fn copy_to(&self, this: &handles::File, path: String) -> ApiResult<Option<handles::File>> {
        if Self::relative(this).is_empty() {
            return Ok(None);
        }
        let Some(to) = Self::sibling(this, &path).filter(|t| !Self::relative(t).is_empty()) else {
            return Ok(None);
        };
        let from = Self::checked(this)?;
        let copied = self
            .with_files(|f| f.copy(&from, &to.path))
            .unwrap_or(false);
        Ok(copied.then_some(to))
    }

    fn create_folder(&self, this: &handles::File) -> ApiResult<bool> {
        let path = Self::checked(this)?;
        Ok(self
            .with_files(|f| f.make_directory(&path))
            .unwrap_or(false))
    }

    fn delete(&self, this: &handles::File) -> ApiResult<bool> {
        if Self::relative(this).is_empty() {
            return Ok(false);
        }
        let path = Self::checked(this)?;
        Ok(self.with_files(|f| f.delete(&path)).unwrap_or(false))
    }
}
