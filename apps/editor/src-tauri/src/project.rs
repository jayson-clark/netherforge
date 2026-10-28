//! Opening and creating projects, and the recent list.
//!
//! The backend reads only three things of `netherforge.json`, leniently:
//! `name` (for display), `minecraft` (which Paper to run) and, as it prepares
//! the dev server, whether `worlds.<main world>.generator` names anything
//! (`server::setup`: whether `bukkit.yml` asks the plugin for the main
//! world's generator). Everything else about the manifest is `format`'s
//! business, in the UI.

use std::collections::BTreeMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Context, Result, bail};
use crate::fs::{ProjectRoot, atomic::write_atomic, scope};

pub const MANIFEST: &str = "netherforge.json";
const RECENT_LIMIT: usize = 12;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct ProjectInfo {
    pub root: String,
    pub name: String,
    /// Whether the user trusts it: until they do, its dev server, LuaLS, git
    /// fetches and agents' tools are refused (see `trust`).
    pub trusted: bool,
    /// The workspace it's one of the projects of, when it is (see `workspace`).
    pub workspace: Option<crate::workspace::WorkspaceInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct RecentProject {
    pub root: String,
    pub name: String,
    pub opened_at: String,
}

/// What the backend knows about a project it opened.
#[derive(Debug, Clone)]
pub struct Opened {
    pub root: ProjectRoot,
    pub info: ProjectInfo,
    /// The target Minecraft version, if the manifest names a valid one.
    pub minecraft: Option<String>,
}

/// Checks [root] is a project and reads its manifest.
pub fn inspect(root: &Path) -> Result<Opened> {
    let root = ProjectRoot::new(root)?;
    let manifest_path = root.path().join(MANIFEST);
    if !manifest_path.is_file() {
        bail!(
            Invalid,
            "{} isn't a NetherForge project (it has no {MANIFEST})",
            root.path().display()
        );
    }
    let manifest: Option<serde_json::Value> = std::fs::read(&manifest_path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok());
    let field = |key: &str| {
        manifest
            .as_ref()
            .and_then(|m| m.get(key))
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
    };
    let folder_name = root
        .path()
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| root.path().display().to_string());
    let name = field("name").unwrap_or(folder_name);
    let minecraft = field("minecraft").filter(|v| crate::minecraft::version::is_release(v));
    Ok(Opened {
        info: ProjectInfo {
            root: root.path().display().to_string(),
            name,
            trusted: false,
            workspace: None,
        },
        root,
        minecraft,
    })
}

/// Writes [files] into [root], which must be missing or an empty folder.
/// Validates every path before writing anything.
pub fn create(root: &Path, files: &BTreeMap<String, String>) -> Result<()> {
    if !files.contains_key(MANIFEST) {
        bail!(Invalid, "A new project needs a {MANIFEST}");
    }
    for path in files.keys() {
        scope::segments(path)?;
    }
    if root.exists() {
        if !root.is_dir() {
            bail!(Invalid, "{} isn't a folder", root.display());
        }
        let mut entries =
            std::fs::read_dir(root).context(|| format!("Couldn't read {}", root.display()))?;
        if entries.any(|e| e.is_ok_and(|e| e.file_name() != ".DS_Store")) {
            bail!(
                AlreadyExists,
                "{} isn't empty; pick an empty or new folder",
                root.display()
            );
        }
    } else {
        std::fs::create_dir_all(root).context(|| format!("Couldn't create {}", root.display()))?;
    }
    let scoped = ProjectRoot::new(root)?;
    for (path, text) in files {
        let abs = scoped.resolve(path)?;
        write_atomic(&abs, text.as_bytes())?;
    }
    Ok(())
}

/// [list] with [opened] moved to the front, deduplicated by root, capped.
pub fn add_recent(mut list: Vec<RecentProject>, opened: RecentProject) -> Vec<RecentProject> {
    list.retain(|r| r.root != opened.root);
    list.insert(0, opened);
    list.truncate(RECENT_LIMIT);
    list
}

/// The recent list without projects that no longer exist.
pub fn existing_recent(list: Vec<RecentProject>) -> Vec<RecentProject> {
    list.into_iter()
        .filter(|r| Path::new(&r.root).join(MANIFEST).is_file())
        .collect()
}

pub fn now_iso() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}

impl From<&Opened> for RecentProject {
    fn from(opened: &Opened) -> Self {
        RecentProject {
            root: opened.info.root.clone(),
            name: opened.info.name.clone(),
            opened_at: now_iso(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inspects_projects() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("my-project");
        std::fs::create_dir_all(&root).unwrap();
        assert!(
            inspect(&root)
                .unwrap_err()
                .message()
                .contains("isn't a NetherForge project")
        );

        std::fs::write(
            root.join(MANIFEST),
            r#"{"formatVersion":1,"name":"Basic","minecraft":"26.3"}"#,
        )
        .unwrap();
        let opened = inspect(&root).unwrap();
        assert_eq!(opened.info.name, "Basic");
        assert_eq!(opened.minecraft.as_deref(), Some("26.3"));

        std::fs::write(root.join(MANIFEST), "{ not json").unwrap();
        let opened = inspect(&root).unwrap();
        assert_eq!(opened.info.name, "my-project");
        assert_eq!(opened.minecraft, None);
    }

    #[test]
    fn creates_into_empty_or_new_folders_only() {
        let tmp = tempfile::tempdir().unwrap();
        let files = BTreeMap::from([
            (MANIFEST.to_string(), r#"{"name":"New"}"#.to_string()),
            (
                "modules/hello/init.lua".to_string(),
                "print('hi')".to_string(),
            ),
        ]);
        let root = tmp.path().join("new/project");
        create(&root, &files).unwrap();
        assert_eq!(
            std::fs::read_to_string(root.join("modules/hello/init.lua")).unwrap(),
            "print('hi')"
        );
        assert!(
            create(&root, &files)
                .unwrap_err()
                .message()
                .contains("isn't empty")
        );

        let bad = BTreeMap::from([
            (MANIFEST.to_string(), "{}".to_string()),
            ("../escape.txt".to_string(), "x".to_string()),
        ]);
        let other = tmp.path().join("other");
        assert!(create(&other, &bad).is_err());
        assert!(!tmp.path().join("escape.txt").exists());
        assert!(!other.exists());

        let no_manifest = BTreeMap::from([("a.txt".to_string(), "x".to_string())]);
        assert!(create(&tmp.path().join("third"), &no_manifest).is_err());
    }

    #[test]
    fn recent_list_dedupes_and_caps() {
        let entry = |root: &str| RecentProject {
            root: root.into(),
            name: root.into(),
            opened_at: "2026-10-02T00:00:00Z".into(),
        };
        let mut list = Vec::new();
        for i in 0..20 {
            list = add_recent(list, entry(&format!("/p{i}")));
        }
        list = add_recent(list, entry("/p10"));
        assert_eq!(list.len(), RECENT_LIMIT);
        assert_eq!(list[0].root, "/p10");
        assert_eq!(list.iter().filter(|r| r.root == "/p10").count(), 1);
    }
}
