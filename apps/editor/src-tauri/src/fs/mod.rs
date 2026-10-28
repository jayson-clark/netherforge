//! File operations on the open project. Every function takes a [ProjectRoot]
//! and project paths; see [scope] for what's allowed.

pub mod atomic;
pub mod git;
pub mod map;
pub mod package;
pub mod scope;
pub mod serve;

use std::path::Path;
use std::time::UNIX_EPOCH;

use serde::Serialize;

use crate::error::{Context, Error, ErrorCode, Result, bail};
pub use scope::ProjectRoot;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub path: String,
    pub size: u64,
    /// Milliseconds since the epoch.
    pub modified: u64,
}

/// Folders `fs_list` and the watcher never show: `.git` anywhere, and the
/// project's own `.netherforge/` working folder at the top.
pub fn is_hidden(project_path: &str) -> bool {
    let mut parts = project_path.split('/');
    if parts
        .next()
        .is_some_and(|top| scope::is_named(top, ".netherforge"))
    {
        return true;
    }
    project_path
        .split('/')
        .any(|part| scope::is_named(part, ".git"))
}

/// Every file in the project except [is_hidden] ones and our temp files,
/// sorted by path. Symlinks are listed only when they resolve inside the root.
pub fn list(root: &ProjectRoot) -> Result<Vec<FileEntry>> {
    let mut entries = Vec::new();
    let walker = walkdir::WalkDir::new(root.path())
        .follow_links(false)
        .min_depth(1)
        .into_iter()
        .filter_entry(|entry| match root.to_project_path(entry.path()) {
            Some(path) => !is_hidden(&path),
            None => false,
        });
    for entry in walker {
        let entry = match entry {
            Ok(entry) => entry,
            // A folder we can't read shouldn't hide the rest of the project.
            Err(_) => continue,
        };
        let Some(path) = root.to_project_path(entry.path()) else {
            continue;
        };
        if entry
            .file_name()
            .to_str()
            .is_some_and(atomic::is_temp_file_name)
        {
            continue;
        }
        let file_type = entry.file_type();
        if file_type.is_dir() {
            continue;
        }
        if file_type.is_symlink() && root.resolve(&path).is_err() {
            continue;
        }
        let Ok(meta) = std::fs::metadata(entry.path()) else {
            continue;
        };
        if !meta.is_file() {
            continue;
        }
        entries.push(FileEntry {
            path,
            size: meta.len(),
            modified: modified_ms(&meta),
        });
    }
    entries.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(entries)
}

/// The parts of `.netherforge/` the UI writes, all of it safe to lose: the
/// JSON Schemas every file's `$schema` points at, what coding agents read
/// (docs, LuaLS stubs) and run (the `netherforge` command), regenerated on
/// every open, the project's names for lua-language-server (`luals/`), and
/// the explorer's cached resource pictures ([THUMBNAILS]).
pub const UI_FOLDERS: [&str; 5] = ["schema", "docs", "bin", "thumbnails", "luals"];

/// Where the UI caches resource pictures: the one hidden folder
/// `nfproject://` serves, since they're shown as images.
pub const THUMBNAILS: &str = ".netherforge/thumbnails";

/// Whether the UI may write, delete or rename [project_path]. Never `.git`
/// (a hook there runs on the user's next commit) and nothing in `.netherforge/`
/// but [UI_FOLDERS] (the rest is the editor's: staging for captured worlds).
/// The webview is the boundary this guards: reads stay unrestricted. The dev
/// server isn't in the project at all (`AppDirs::server_dir`).
pub fn is_writable(project_path: &str) -> bool {
    // Segments compare as `scope::is_named` does: `.GIT` and `.git.` are `.git`
    // on macOS and Windows.
    let mut parts = project_path.split('/');
    if project_path
        .split('/')
        .any(|part| scope::is_named(part, ".git"))
    {
        return false;
    }
    if !parts
        .next()
        .is_some_and(|top| scope::is_named(top, ".netherforge"))
    {
        return true;
    }
    match (parts.next(), parts.next()) {
        (Some(folder), Some(_)) => UI_FOLDERS.contains(&folder),
        _ => false,
    }
}

pub(crate) fn writable(root: &ProjectRoot, path: &str) -> Result<std::path::PathBuf> {
    if !is_writable(path) {
        bail!(ReadOnly, "{path} can't be changed from the editor");
    }
    root.resolve(path)
}

fn modified_ms(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn read_text(root: &ProjectRoot, path: &str) -> Result<String> {
    let abs = root.resolve(path)?;
    let bytes = std::fs::read(&abs).context(|| format!("Couldn't read {path}"))?;
    String::from_utf8(bytes)
        .map_err(|_| Error::new(ErrorCode::Invalid, format!("{path} isn't text")))
}

/// Atomic, creating parent folders. Only [is_writable] paths.
pub fn write_text(root: &ProjectRoot, path: &str, text: &str) -> Result<()> {
    let abs = writable(root, path)?;
    if abs.is_dir() {
        bail!(Invalid, "{path} is a folder");
    }
    atomic::write_atomic(&abs, text.as_bytes())
}

/// Like [write_text], for binary files (textures imported into a resource pack).
pub fn write_bytes(root: &ProjectRoot, path: &str, bytes: &[u8]) -> Result<()> {
    let abs = writable(root, path)?;
    if abs.is_dir() {
        bail!(Invalid, "{path} is a folder");
    }
    atomic::write_atomic(&abs, bytes)
}

/// Deletes a file or a whole folder.
pub fn delete(root: &ProjectRoot, path: &str) -> Result<()> {
    let abs = writable(root, path)?;
    let meta = std::fs::symlink_metadata(&abs).context(|| format!("Couldn't find {path}"))?;
    if meta.is_dir() {
        std::fs::remove_dir_all(&abs)
    } else {
        std::fs::remove_file(&abs)
    }
    .context(|| format!("Couldn't delete {path}"))
}

/// Renames a file or folder, creating the destination's parents. Refuses to
/// overwrite, except a case-only rename (`a.lua` → `A.lua`) on a
/// case-insensitive filesystem, where the "existing" destination is the source.
pub fn rename(root: &ProjectRoot, from: &str, to: &str) -> Result<()> {
    let source = writable(root, from)?;
    let target = writable(root, to)?;
    std::fs::symlink_metadata(&source).context(|| format!("Couldn't find {from}"))?;
    if std::fs::symlink_metadata(&target).is_ok() && !same_file(&source, &target) {
        bail!(AlreadyExists, "{to} already exists");
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)
            .context(|| format!("Couldn't create the folder for {to}"))?;
    }
    atomic::rename_replacing(&source, &target).context(|| format!("Couldn't rename {from} to {to}"))
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (dunce::canonicalize(a), dunce::canonicalize(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn project() -> (tempfile::TempDir, ProjectRoot) {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("p");
        for (path, text) in [
            ("netherforge.json", "{}"),
            ("centities/tower/centity.json", "{}"),
            ("centities/tower/root.lua", "-- hi"),
            (".git/HEAD", "ref"),
            (".netherforge/server/eula.txt", "eula=true"),
            ("modules/tools/.git/config", "x"),
            (
                "modules/tools/.netherforge/keep.txt",
                "nested is not special",
            ),
            ("centities/tower/.root.lua.nftmp-abc", "temp"),
        ] {
            let abs = root.join(path);
            std::fs::create_dir_all(abs.parent().unwrap()).unwrap();
            std::fs::write(abs, text).unwrap();
        }
        let scoped = ProjectRoot::new(&root).unwrap();
        (tmp, scoped)
    }

    #[test]
    fn list_skips_git_netherforge_and_temp_files() {
        let (_tmp, root) = project();
        let paths: Vec<_> = list(&root).unwrap().into_iter().map(|e| e.path).collect();
        assert_eq!(
            paths,
            [
                "centities/tower/centity.json",
                "centities/tower/root.lua",
                "modules/tools/.netherforge/keep.txt",
                "netherforge.json",
            ]
        );
        let entry = list(&root).unwrap().remove(1);
        assert_eq!(entry.size, 5);
        assert!(entry.modified > 0);
    }

    #[test]
    fn write_read_rename_delete() {
        let (_tmp, root) = project();
        write_text(&root, "modules/new/init.lua", "print('x')").unwrap();
        assert_eq!(
            read_text(&root, "modules/new/init.lua").unwrap(),
            "print('x')"
        );
        write_text(&root, ".netherforge/schema/centity.schema.json", "{}").unwrap();
        write_text(&root, ".netherforge/docs/format/centity.md", "# x").unwrap();
        write_text(&root, ".netherforge/bin/netherforge.mjs", "").unwrap();
        write_bytes(&root, ".netherforge/thumbnails/tower.png", b"png").unwrap();
        assert!(
            root.path()
                .join(".netherforge/schema/centity.schema.json")
                .exists()
        );

        rename(&root, "modules/new", "modules/renamed").unwrap();
        assert_eq!(
            read_text(&root, "modules/renamed/init.lua").unwrap(),
            "print('x')"
        );
        assert!(rename(&root, "modules/renamed/init.lua", "netherforge.json").is_err());
        assert!(rename(&root, "missing.lua", "other.lua").is_err());

        let png = [0x89, b'P', b'N', b'G', 0, 0xff];
        write_bytes(&root, "resource_packs/ui/textures/gui/a.png", &png).unwrap();
        assert_eq!(
            std::fs::read(root.path().join("resource_packs/ui/textures/gui/a.png")).unwrap(),
            png
        );
        assert!(read_text(&root, "resource_packs/ui/textures/gui/a.png").is_err());
        assert!(write_bytes(&root, "../outside.png", &png).is_err());
        assert!(write_bytes(&root, "resource_packs", &png).is_err());

        delete(&root, "modules/renamed").unwrap();
        assert!(!root.path().join("modules/renamed").exists());
        delete(&root, "netherforge.json").unwrap();
        assert!(read_text(&root, "netherforge.json").is_err());
    }

    #[test]
    fn refuses_escapes_in_every_operation() {
        let (_tmp, root) = project();
        assert!(read_text(&root, "../outside").is_err());
        assert!(write_text(&root, "../outside", "x").is_err());
        assert!(delete(&root, "..").is_err());
        assert!(rename(&root, "netherforge.json", "../moved.json").is_err());
        assert!(!root.path().parent().unwrap().join("outside").exists());
        assert!(!root.path().parent().unwrap().join("moved.json").exists());
    }

    #[test]
    fn writes_stay_out_of_git_and_the_working_folder() {
        let (_tmp, root) = project();
        for path in [
            ".git/hooks/pre-commit",
            "modules/tools/.git/config",
            ".netherforge/server/plugins/evil.jar",
            ".netherforge/server/server.properties",
            ".netherforge",
            ".netherforge/schemax/a.json",
            ".netherforge/docs",
            ".netherforge/binary/x",
            ".netherforge/thumbnails",
            ".GIT/hooks/pre-commit",
            ".Git/config",
            ".git./hooks/x",
            ".git /hooks/x",
            "sub/.GiT/x",
            ".NetherForge/server/plugins/evil.jar",
            ".netherforge./server/x",
            ".NETHERFORGE",
        ] {
            assert!(!is_writable(path), "{path}");
            assert!(write_text(&root, path, "x").is_err(), "{path}");
            assert!(write_bytes(&root, path, b"x").is_err(), "{path}");
            assert!(delete(&root, path).is_err(), "{path}");
        }
        assert!(rename(&root, ".git/HEAD", "HEAD").is_err());
        assert!(rename(&root, "netherforge.json", ".git/hooks/post-checkout").is_err());
        assert!(std::fs::read(root.path().join(".git/HEAD")).is_ok());

        write_text(&root, ".netherforge/schema/centity.schema.json", "{}").unwrap();
        write_text(&root, ".netherforge/docs/format/centity.md", "# x").unwrap();
        write_text(&root, ".netherforge/bin/netherforge.mjs", "").unwrap();
        write_text(&root, ".netherforge/luals/library/project.lua", "---@meta").unwrap();
        write_bytes(&root, ".netherforge/thumbnails/tower.png", b"png").unwrap();
        assert!(is_writable("modules/tools/.netherforge/keep.txt"));
        assert!(is_writable(".gitignore"));
        assert!(is_writable("a/.git-keep/x"));
    }

    #[test]
    fn hidden_paths() {
        assert!(is_hidden(".git/HEAD"));
        assert!(is_hidden(".netherforge/server/x"));
        assert!(is_hidden("a/.git/config"));
        assert!(!is_hidden("a/.netherforge/x"));
        assert!(is_hidden(".GIT/HEAD"));
        assert!(is_hidden(".NetherForge/server/x"));
        assert!(is_hidden("a/.git./config"));
        assert!(!is_hidden(".gitignore"));
        assert!(!is_hidden(".netherforgerc"));
    }
}
