//! Atomic file writes: write a temp file next to the target, flush it to disk,
//! rename it over the target. A reader (the plugin, git, another editor) sees
//! the old file or the new one, never half of one.
//!
//! Windows: `std::fs::rename` is `MoveFileExW(MOVEFILE_REPLACE_EXISTING)`,
//! which replaces an existing file, but fails with "access denied" / a sharing
//! violation while another process (an antivirus scan, an editor, the indexer)
//! holds the target open without `FILE_SHARE_DELETE`. Those holds are brief,
//! so we retry for about a second before giving up.

use std::io::Write;
use std::path::{Path, PathBuf};

use crate::error::{Context, Result};

/// Temp files are `.<name>.nftmp-<random>`; the watcher ignores them.
pub const TEMP_MARKER: &str = ".nftmp-";

pub fn is_temp_file_name(name: &str) -> bool {
    name.starts_with('.') && name.contains(TEMP_MARKER)
}

pub fn write_atomic(path: &Path, contents: &[u8]) -> Result<()> {
    let dir = path
        .parent()
        .context(|| format!("{} has no parent folder", path.display()))?;
    std::fs::create_dir_all(dir).context(|| format!("Couldn't create {}", dir.display()))?;
    let name = path
        .file_name()
        .context(|| format!("{} has no file name", path.display()))?
        .to_string_lossy();
    let temp = dir.join(format!(".{name}{TEMP_MARKER}{}", random_suffix()));

    let result = (|| -> Result<()> {
        let mut file = std::fs::File::create(&temp)
            .context(|| format!("Couldn't write {}", temp.display()))?;
        file.write_all(contents)
            .context(|| format!("Couldn't write {}", path.display()))?;
        file.sync_all()
            .context(|| format!("Couldn't flush {}", path.display()))?;
        drop(file);
        rename_replacing(&temp, path).context(|| format!("Couldn't save {}", path.display()))
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    result
}

/// Renames [from] over [to], retrying briefly on Windows sharing violations.
pub fn rename_replacing(from: &Path, to: &Path) -> std::io::Result<()> {
    let attempts = if cfg!(windows) { 20 } else { 1 };
    let mut last = None;
    for attempt in 0..attempts {
        match std::fs::rename(from, to) {
            Ok(()) => return Ok(()),
            Err(e)
                if e.kind() == std::io::ErrorKind::PermissionDenied && attempt + 1 < attempts =>
            {
                last = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(e) => return Err(e),
        }
    }
    Err(last.unwrap_or_else(|| std::io::Error::other("rename failed")))
}

pub(crate) fn random_suffix() -> String {
    let mut bytes = [0u8; 6];
    if getrandom::fill(&mut bytes).is_err() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(0);
        bytes[..4].copy_from_slice(&nanos.to_le_bytes());
    }
    hex::encode(bytes)
}

/// A temp path next to [path] for building a folder before renaming it into place.
pub fn sibling_temp(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    path.with_file_name(format!(".{name}{TEMP_MARKER}{}", random_suffix()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_and_replaces_without_leftovers() {
        let tmp = tempfile::tempdir().unwrap();
        let target = tmp.path().join("deep/folder/file.json");
        write_atomic(&target, b"one").unwrap();
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "one");
        write_atomic(&target, b"two").unwrap();
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "two");
        let names: Vec<_> = std::fs::read_dir(target.parent().unwrap())
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        assert_eq!(names, ["file.json"]);
    }

    #[test]
    fn failed_write_leaves_the_old_file() {
        let tmp = tempfile::tempdir().unwrap();
        let target = tmp.path().join("dir-not-file");
        std::fs::create_dir(&target).unwrap();
        std::fs::write(target.join("child"), "x").unwrap();
        // Renaming a file over a non-empty folder fails on every OS.
        assert!(write_atomic(&target, b"new").is_err());
        assert!(target.is_dir());
        let leftovers = std::fs::read_dir(tmp.path())
            .unwrap()
            .filter(|e| is_temp_file_name(&e.as_ref().unwrap().file_name().to_string_lossy()))
            .count();
        assert_eq!(leftovers, 0);
    }

    #[test]
    fn recognises_temp_names() {
        assert!(is_temp_file_name(".centity.json.nftmp-a1b2c3"));
        assert!(!is_temp_file_name("centity.json"));
        assert!(!is_temp_file_name(".gitignore"));
    }
}
