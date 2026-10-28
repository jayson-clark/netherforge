//! Project paths and the rule that nothing escapes the project root.
//!
//! A project path is relative, `/`-separated on every OS, with no empty, `.`
//! or `..` segments, no `\` and no `:` (which rules out Windows drive letters
//! and NTFS alternate streams). Syntax alone can't catch a symlink inside the
//! project that points outside it, so [ProjectRoot::resolve] also canonicalizes
//! the deepest part of the target that exists and checks it's still under the
//! canonical root.

use std::path::{Component, Path, PathBuf};

use crate::error::{Context, Error, ErrorCode, Result, bail};

/// Whether [segment] is a device name Windows reserves: `CON`, `PRN`, `AUX`,
/// `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9` (also the superscript digits), in any
/// case, with or without an extension (`con.txt`, `NUL.tar.gz`). Refused on
/// every OS so a project opens the same everywhere.
pub fn is_reserved_name(segment: &str) -> bool {
    let stem = segment
        .split('.')
        .next()
        .unwrap_or("")
        .trim_end_matches(' ');
    let stem = stem.to_lowercase();
    if matches!(stem.as_str(), "con" | "prn" | "aux" | "nul") {
        return true;
    }
    for prefix in ["com", "lpt"] {
        if let Some(digit) = stem.strip_prefix(prefix) {
            let mut chars = digit.chars();
            if let (Some(c), None) = (chars.next(), chars.next()) {
                return matches!(c, '1'..='9' | '\u{b9}' | '\u{b2}' | '\u{b3}');
            }
        }
    }
    false
}

/// Whether [segment] names [name] (lowercase) on a case-insensitive file
/// system, as macOS and Windows have: Unicode lowercase (`to_lowercase`,
/// which maps `.GIT` and the Kelvin sign alike) after dropping the trailing
/// dots and spaces Windows ignores (`.git.` is `.git` there). Guards compare
/// this way on every OS, since a project travels.
pub fn is_named(segment: &str, name: &str) -> bool {
    segment.trim_end_matches(['.', ' ']).to_lowercase() == name
}

/// The segments of a valid project path.
pub fn segments(path: &str) -> Result<Vec<&str>> {
    if path.is_empty() {
        bail!(InvalidPath, "A project path can't be empty");
    }
    if path.starts_with('/') {
        bail!(
            InvalidPath,
            "\"{path}\" is absolute; project paths are relative to the project folder"
        );
    }
    if path.contains(['\\', ':', '\0']) {
        bail!(
            InvalidPath,
            "\"{path}\" isn't a project path (use / and no drive letters)"
        );
    }
    let parts: Vec<&str> = path.split('/').collect();
    for part in &parts {
        match *part {
            "" => bail!(InvalidPath, "\"{path}\" has an empty segment"),
            "." | ".." => bail!(InvalidPath, "\"{path}\" leaves the project folder"),
            _ => {}
        }
        if part.ends_with(['.', ' ']) {
            bail!(
                InvalidPath,
                "\"{path}\" has a segment ending in a dot or a space, which Windows drops (so it names another file there)"
            );
        }
        if is_reserved_name(part) {
            bail!(
                InvalidPath,
                "\"{path}\" uses a name Windows reserves (CON, PRN, AUX, NUL, COM1-9, LPT1-9)"
            );
        }
    }
    Ok(parts)
}

#[derive(Debug, Clone)]
pub struct ProjectRoot {
    /// Canonical, without Windows' `\\?\` prefix.
    root: PathBuf,
}

impl ProjectRoot {
    pub fn new(path: &Path) -> Result<Self> {
        let root =
            dunce::canonicalize(path).context(|| format!("Couldn't open {}", path.display()))?;
        if !root.is_dir() {
            bail!(Invalid, "{} isn't a folder", root.display());
        }
        Ok(Self { root })
    }

    pub fn path(&self) -> &Path {
        &self.root
    }

    /// The absolute path of a project path, refusing anything outside the root.
    pub fn resolve(&self, project_path: &str) -> Result<PathBuf> {
        let mut target = self.root.clone();
        for part in segments(project_path)? {
            target.push(part);
        }
        self.check_inside(&target, project_path)?;
        Ok(target)
    }

    /// Checks that whatever of [target] exists resolves inside the root.
    fn check_inside(&self, target: &Path, shown: &str) -> Result<()> {
        let mut probe = target;
        loop {
            match std::fs::symlink_metadata(probe) {
                Ok(_) => break,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    probe = probe.parent().ok_or_else(|| {
                        Error::new(ErrorCode::InvalidPath, format!("\"{shown}\" has no parent"))
                    })?;
                }
                Err(e) => bail!("Couldn't check \"{shown}\": {e}"),
            }
        }
        let real = dunce::canonicalize(probe).map_err(|_| {
            Error::new(
                ErrorCode::InvalidPath,
                format!("\"{shown}\" is a broken link"),
            )
        })?;
        if !real.starts_with(&self.root) {
            bail!(InvalidPath, "\"{shown}\" leads outside the project folder");
        }
        Ok(())
    }

    /// The project path of an absolute path under the root, or None (the root
    /// itself, or anything outside it).
    pub fn to_project_path(&self, absolute: &Path) -> Option<String> {
        relative_project_path(&self.root, absolute)
    }
}

/// `/`-joined components of [absolute] relative to [root].
pub fn relative_project_path(root: &Path, absolute: &Path) -> Option<String> {
    let relative = absolute.strip_prefix(root).ok()?;
    let mut parts = Vec::new();
    for component in relative.components() {
        match component {
            Component::Normal(part) => parts.push(part.to_str()?.to_string()),
            _ => return None,
        }
    }
    if parts.is_empty() {
        None
    } else {
        Some(parts.join("/"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn project() -> (tempfile::TempDir, ProjectRoot) {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("project");
        std::fs::create_dir_all(root.join("centities/tower")).unwrap();
        std::fs::write(root.join("netherforge.json"), "{}").unwrap();
        let scoped = ProjectRoot::new(&root).unwrap();
        (tmp, scoped)
    }

    #[test]
    fn accepts_plain_relative_paths() {
        let (_tmp, root) = project();
        assert_eq!(
            root.resolve("centities/tower/centity.json").unwrap(),
            root.path()
                .join("centities")
                .join("tower")
                .join("centity.json")
        );
        // Paths that don't exist yet are fine; their parents are checked.
        assert!(root.resolve("new/deep/file.lua").is_ok());
        assert!(root.resolve(".netherforge/schema/x.json").is_ok());
        assert!(root.resolve("a..b/c...lua").is_ok());
        assert!(root.resolve("console/com10.txt/lpt.lua").is_ok());
    }

    #[test]
    fn refuses_syntactic_escapes() {
        let (_tmp, root) = project();
        for bad in [
            "",
            "..",
            "../outside.txt",
            "centities/../../outside.txt",
            "./netherforge.json",
            "/etc/passwd",
            "C:/Windows/win.ini",
            "C:\\Windows\\win.ini",
            "centities\\..\\..\\x",
            "\\\\server\\share",
            "a//b",
            "a/",
            "file.txt:stream",
            "a/b.",
            "a./b",
            "a/b ",
            "con",
            "a/CON.txt",
            "Nul.tar.gz",
            "x/com1",
            "x/LPT9.lua",
            "x/com\u{b9}.txt",
            "aux /x",
        ] {
            assert!(root.resolve(bad).is_err(), "should refuse {bad:?}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn refuses_symlink_escapes() {
        let (tmp, root) = project();
        let outside = tmp.path().join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("secret.txt"), "x").unwrap();
        std::os::unix::fs::symlink(&outside, root.path().join("link")).unwrap();
        std::os::unix::fs::symlink(outside.join("secret.txt"), root.path().join("file-link"))
            .unwrap();
        std::os::unix::fs::symlink(tmp.path().join("nowhere"), root.path().join("dangling"))
            .unwrap();

        assert!(root.resolve("link/secret.txt").is_err());
        assert!(root.resolve("link/new-file.txt").is_err());
        assert!(root.resolve("link").is_err());
        assert!(root.resolve("file-link").is_err());
        assert!(root.resolve("dangling").is_err());

        // A link that stays inside the project is fine.
        std::os::unix::fs::symlink(root.path().join("centities"), root.path().join("inner"))
            .unwrap();
        assert!(root.resolve("inner/tower").is_ok());
    }

    #[test]
    fn maps_absolute_paths_back() {
        let (_tmp, root) = project();
        let abs = root.path().join("centities").join("tower").join("root.lua");
        assert_eq!(
            root.to_project_path(&abs).as_deref(),
            Some("centities/tower/root.lua")
        );
        assert_eq!(root.to_project_path(root.path()), None);
        assert_eq!(root.to_project_path(Path::new("/somewhere/else")), None);
    }
}
