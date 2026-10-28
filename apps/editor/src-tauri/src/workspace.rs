//! Workspaces: several projects kept together under one `netherforge-workspace.json`
//! (docs/format/workspace.md). Opening one of its projects opens the workspace: the backend finds
//! the file in a folder above the project, reads it leniently (format, in the UI, says what's
//! wrong with it) and answers its members and its dev network.
//!
//! **Its members are under it.** A pattern is a relative folder or one followed by `/*`, never
//! `..`, absolute or a backslash, and every member's canonical folder must be inside the
//! workspace's canonical root, so a workspace file (untrusted, like everything in a repository)
//! can't name a folder elsewhere, through a link or otherwise. That's what lets trusting a
//! workspace mean trusting its folder ([crate::trust]).

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{Result, bail};
use crate::project::MANIFEST;

pub const FILE: &str = "netherforge-workspace.json";

/// How deep under a workspace a project may be (`servers/hub` is 2).
const DEPTH: usize = 4;
/// How many folders above a project the search for a workspace looks.
const SEARCH: usize = 6;

/// The file, as much of it as the backend needs. Unknown keys are format's to complain about.
#[derive(Debug, Clone, Default, Deserialize)]
struct File {
    #[serde(default)]
    name: String,
    #[serde(default)]
    projects: Vec<String>,
    #[serde(default)]
    network: Option<FileNetwork>,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct FileNetwork {
    #[serde(default)]
    arrival: String,
    #[serde(default)]
    servers: std::collections::BTreeMap<String, String>,
}

/// One of a workspace's projects.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceMember {
    /// Its folder relative to the workspace, as the file names it (`servers/hub`).
    pub path: String,
    /// Its folder, absolute and canonical.
    pub root: String,
    /// Its `netherforge.json`'s name (or its folder's).
    pub name: String,
    /// It exports something: a package other projects depend on.
    pub package: bool,
}

/// A server of a workspace's dev network.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceServer {
    pub name: String,
    /// The member it runs, as the file names it.
    pub project: String,
}

/// A workspace's dev network, from its file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceNetworkInfo {
    /// Where players arrive.
    pub arrival: String,
    /// In name order.
    pub servers: Vec<WorkspaceServer>,
}

/// The workspace a project is in, as the UI is told on open.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceInfo {
    /// Its folder, absolute and canonical: where `netherforge-workspace.json` is.
    pub root: String,
    pub name: String,
    /// Whether the user trusts it (and so every project in it).
    pub trusted: bool,
    /// Its projects, in the file's order.
    pub members: Vec<WorkspaceMember>,
    pub network: Option<WorkspaceNetworkInfo>,
}

impl WorkspaceInfo {
    /// The member whose folder is [root] (canonical), if any.
    pub fn member(&self, root: &Path) -> Option<&WorkspaceMember> {
        // As paths, not strings: on Windows `C:\w\servers/hub` is the member `C:\w\servers\hub`.
        self.members.iter().find(|m| Path::new(&m.root) == root)
    }

    /// The member a server of the network runs.
    pub fn server_root(&self, server: &WorkspaceServer) -> Option<PathBuf> {
        let wanted = normalize(&server.project);
        self.members
            .iter()
            .find(|m| m.path == wanted)
            .map(|m| PathBuf::from(&m.root))
    }
}

/// A pattern with no `./` in front and no `/` behind, as it's compared.
fn normalize(pattern: &str) -> String {
    pattern
        .trim()
        .trim_start_matches("./")
        .trim_end_matches('/')
        .to_string()
}

/// Whether [pattern] names folders under the workspace: format's `Workspaces.isPattern`.
pub fn is_pattern(pattern: &str) -> bool {
    let pattern = normalize(pattern);
    if pattern.is_empty() {
        return false;
    }
    let parts: Vec<&str> = pattern.split('/').collect();
    let folders = if parts.last() == Some(&"*") {
        &parts[..parts.len() - 1]
    } else {
        &parts[..]
    };
    folders.iter().all(|part| {
        !part.is_empty()
            && part
                .chars()
                .next()
                .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_')
            && part
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | ' ' | '-'))
            && !part.ends_with('.')
            && !part.ends_with(' ')
    })
}

/// Every folder under [root] (relative, `/`-separated) holding a `netherforge.json`, skipping
/// hidden folders and build output.
fn projects_under(root: &Path) -> Vec<String> {
    fn walk(root: &Path, dir: &str, depth: usize, found: &mut Vec<String>) {
        let Ok(entries) = std::fs::read_dir(root.join(dir)) else {
            return;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let is_dir = entry.file_type().is_ok_and(|t| t.is_dir());
            if !is_dir
                || name.starts_with('.')
                || matches!(name.as_str(), "node_modules" | "build" | "dist" | "target")
            {
                continue;
            }
            let child = if dir.is_empty() {
                name
            } else {
                format!("{dir}/{name}")
            };
            if root.join(&child).join(MANIFEST).is_file() {
                found.push(child.clone());
            }
            if depth < DEPTH {
                walk(root, &child, depth + 1, found);
            }
        }
    }
    let mut found = Vec::new();
    walk(root, "", 1, &mut found);
    found.sort();
    found
}

/// The folders [patterns] name, in order: format's `Workspaces.expand`.
fn expand(patterns: &[String], projects: &[String]) -> Vec<String> {
    let mut found: Vec<String> = Vec::new();
    for raw in patterns {
        let pattern = normalize(raw);
        if !is_pattern(&pattern) {
            continue;
        }
        let named: Vec<String> = if pattern == "*" || pattern.ends_with("/*") {
            let parent = pattern.trim_end_matches('*').trim_end_matches('/');
            projects
                .iter()
                .filter(|p| {
                    if parent.is_empty() {
                        !p.contains('/')
                    } else {
                        p.strip_prefix(&format!("{parent}/"))
                            .is_some_and(|rest| !rest.contains('/'))
                    }
                })
                .cloned()
                .collect()
        } else if projects.contains(&pattern) {
            vec![pattern]
        } else {
            vec![]
        };
        for folder in named {
            if !found.contains(&folder) {
                found.push(folder);
            }
        }
    }
    found
}

/// The workspace whose folder is [root], read: None when there's no file there.
pub fn read(root: &Path, trusted: bool) -> Option<WorkspaceInfo> {
    let root = dunce::canonicalize(root).ok()?;
    let text = std::fs::read_to_string(root.join(FILE)).ok()?;
    let file: File = serde_json::from_str(&text).unwrap_or_default();
    let projects = projects_under(&root);
    let members = expand(&file.projects, &projects)
        .into_iter()
        .filter_map(|path| {
            let folder = dunce::canonicalize(root.join(&path)).ok()?;
            // A link out of the workspace isn't one of its projects.
            if !folder.starts_with(&root) {
                return None;
            }
            let opened = crate::project::inspect(&folder).ok()?;
            let package = std::fs::read(folder.join(MANIFEST))
                .ok()
                .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
                .and_then(|m| m.get("exports").map(|e| e.is_object()))
                .unwrap_or(false);
            Some(WorkspaceMember {
                path,
                root: folder.display().to_string(),
                name: opened.info.name,
                package,
            })
        })
        .collect();
    let name = if file.name.trim().is_empty() {
        root.file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    } else {
        file.name.trim().to_string()
    };
    Some(WorkspaceInfo {
        root: root.display().to_string(),
        name,
        trusted,
        members,
        network: file.network.map(|network| WorkspaceNetworkInfo {
            arrival: network.arrival,
            servers: network
                .servers
                .into_iter()
                .map(|(name, project)| WorkspaceServer { name, project })
                .collect(),
        }),
    })
}

/// The workspace the project at [project_root] (canonical) is in: the nearest folder above it
/// holding a workspace file that lists it. None when there's none.
pub fn find(project_root: &Path, trusted: impl Fn(&Path) -> bool) -> Option<WorkspaceInfo> {
    let mut dir = project_root.parent();
    for _ in 0..SEARCH {
        let folder = dir?;
        if folder.join(FILE).is_file() {
            let workspace = read(folder, trusted(folder))?;
            return workspace
                .member(project_root)
                .is_some()
                .then_some(workspace);
        }
        dir = folder.parent();
    }
    None
}

/// The workspace file's text at [root].
pub fn read_text(root: &Path) -> Result<String> {
    match std::fs::read_to_string(root.join(FILE)) {
        Ok(text) => Ok(text),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            bail!(NotFound, "{} has no {FILE}", root.display())
        }
        Err(e) => Err(e.into()),
    }
}

/// Writes the workspace file at [root] (the UI's canonical text, from format).
pub fn write_text(root: &Path, text: &str) -> Result<()> {
    crate::fs::atomic::write_atomic(&root.join(FILE), text.as_bytes())
}

/// Where the workspace file's `$schema` points, from its folder; the only file the editor writes
/// in a workspace's own `.netherforge/`.
pub const SCHEMA: &str = ".netherforge/schema/workspace.schema.json";

/// Writes the workspace file's JSON Schema (format's, which the UI bundles) where its `$schema`
/// points, so other editors validate it too.
pub fn write_schema(root: &Path, text: &str) -> Result<()> {
    let path = root.join(SCHEMA);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    crate::fs::atomic::write_atomic(&path, text.as_bytes())
}

/// The project opening a workspace's own folder opens: the one players arrive on (the network's
/// arrival server's), else the first that isn't a package, else the first.
pub fn entry_project(info: &WorkspaceInfo) -> Option<&WorkspaceMember> {
    let arrival = info.network.as_ref().and_then(|network| {
        let project = &network
            .servers
            .iter()
            .find(|s| s.name == network.arrival)?
            .project;
        info.members.iter().find(|m| &m.path == project)
    });
    arrival
        .or_else(|| info.members.iter().find(|m| !m.package))
        .or_else(|| info.members.first())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn project(root: &Path, folder: &str, manifest: &str) {
        std::fs::create_dir_all(root.join(folder)).unwrap();
        std::fs::write(root.join(folder).join(MANIFEST), manifest).unwrap();
    }

    fn polsar() -> (tempfile::TempDir, PathBuf) {
        let tmp = tempfile::tempdir().unwrap();
        let root = dunce::canonicalize(tmp.path()).unwrap();
        project(&root, "servers/hub", r#"{"name":"Hub"}"#);
        project(&root, "servers/chess", r#"{"name":"Chess"}"#);
        project(
            &root,
            "packages/chess",
            r#"{"name":"Chess pkg","exports":{"modules":["chess"]}}"#,
        );
        project(&root, "node_modules/x", "{}");
        std::fs::write(
            root.join(FILE),
            r#"{"name":"Polsar","projects":["servers/*","packages/chess","../outside","missing"],
                "network":{"arrival":"hub","servers":{"hub":"servers/hub","chess":"servers/chess"}}}"#,
        )
        .unwrap();
        (tmp, root)
    }

    #[test]
    fn its_folder_opens_where_players_arrive() {
        let (_tmp, root) = polsar();
        let info = read(&root, false).unwrap();
        assert_eq!(entry_project(&info).unwrap().path, "servers/hub");
        let no_network = WorkspaceInfo {
            network: None,
            ..info.clone()
        };
        // servers/chess comes first (servers/* is sorted), and isn't a package.
        assert_eq!(entry_project(&no_network).unwrap().path, "servers/chess");
        let packages_only = WorkspaceInfo {
            members: info.members.iter().filter(|m| m.package).cloned().collect(),
            ..no_network
        };
        assert_eq!(
            entry_project(&packages_only).unwrap().path,
            "packages/chess"
        );
    }

    #[test]
    fn patterns_name_folders_under_the_workspace() {
        for good in ["servers/hub", "servers/*", "*", "a b/v2.1", "./x/"] {
            assert!(is_pattern(good), "{good}");
        }
        for bad in [
            "", "..", "../x", "/abs", "a/../b", "a\\b", "x/*/y", "C:/x", "a/.",
        ] {
            assert!(!is_pattern(bad), "{bad}");
        }
    }

    #[test]
    fn reads_a_workspace_and_finds_it_from_a_project() {
        let (_tmp, root) = polsar();
        let workspace = read(&root, false).unwrap();
        assert_eq!(workspace.name, "Polsar");
        let paths: Vec<&str> = workspace.members.iter().map(|m| m.path.as_str()).collect();
        assert_eq!(paths, ["servers/chess", "servers/hub", "packages/chess"]);
        assert!(workspace.members[2].package && !workspace.members[0].package);
        assert_eq!(workspace.members[1].name, "Hub");
        let network = workspace.network.clone().unwrap();
        assert_eq!(network.arrival, "hub");
        let chess = &network.servers[0];
        assert_eq!(chess.name, "chess");
        assert_eq!(
            workspace.server_root(chess).unwrap(),
            root.join("servers/chess")
        );

        let found = find(&root.join("servers/hub"), |_| true).unwrap();
        assert!(found.trusted);
        assert_eq!(found.root, root.display().to_string());
        // A project the file doesn't list is on its own.
        project(&root, "loose/thing", "{}");
        assert!(find(&root.join("loose/thing"), |_| true).is_none());
        assert!(find(&root.join("node_modules/x"), |_| true).is_none());
    }

    #[cfg(unix)]
    #[test]
    fn a_link_out_of_the_workspace_isnt_a_member() {
        let (_tmp, root) = polsar();
        let elsewhere = tempfile::tempdir().unwrap();
        project(elsewhere.path(), "evil", "{}");
        std::os::unix::fs::symlink(elsewhere.path().join("evil"), root.join("servers/evil"))
            .unwrap();
        let workspace = read(&root, false).unwrap();
        assert!(workspace.members.iter().all(|m| m.path != "servers/evil"));
    }

    #[test]
    fn reads_and_writes_the_file() {
        let (_tmp, root) = polsar();
        assert!(read_text(&root).unwrap().contains("Polsar"));
        write_text(&root, "{\"name\":\"Renamed\",\"projects\":[]}\n").unwrap();
        assert_eq!(read(&root, false).unwrap().name, "Renamed");
        let empty = tempfile::tempdir().unwrap();
        assert_eq!(
            read_text(empty.path()).unwrap_err().code(),
            crate::error::ErrorCode::NotFound
        );
    }
}
