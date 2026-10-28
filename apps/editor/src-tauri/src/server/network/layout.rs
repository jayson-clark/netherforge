//! A project's dev network layout: whether Start runs a network, the open
//! project's server's name in it, and the other servers, each running a
//! project folder of its own (or the open project again).
//!
//! It's the user's setup on this computer, not part of the project: kept in
//! `<config>/networks.json` (`{ projects: [{ root, layout }] }`), keyed by the
//! canonical project root as trust is, so a moved project starts with none.
//! Peer projects are written as the user gave them (`../lobby`, absolute, or
//! `.`), relative to the open project's folder, and resolved at each start.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{Result, bail};
use crate::settings;

/// The proxy's name among the network's members, which no server may have.
pub const PROXY: &str = "proxy";

/// The most other servers a dev network runs: each is a JVM with a world.
pub const MAX_SERVERS: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct NetworkLayout {
    /// Whether Start runs the whole network (a proxy, the project's server and
    /// every other one) rather than the project's server alone.
    pub enabled: bool,
    /// The open project's server's name in the network.
    pub name: String,
    /// The network's other servers.
    pub servers: Vec<NetworkServer>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct NetworkServer {
    /// Its name in the network (`lobby`), which scripts see as `nf.network.server_name()`.
    pub name: String,
    /// The project folder it runs: relative to the open project's (`../lobby`),
    /// absolute, or `.` for the open project itself.
    pub project: String,
}

impl Default for NetworkLayout {
    fn default() -> Self {
        Self {
            enabled: false,
            name: "main".into(),
            servers: Vec::new(),
        }
    }
}

/// Whether [name] can name a dev network's server: lowercase letters, digits,
/// `_` and `-`, at most 32. Narrower than a network's names (no dots, no
/// capitals): it's also a folder name and a key in `velocity.toml`.
pub fn is_server_name(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

impl NetworkLayout {
    /// Refuses a layout Start couldn't run: a bad or repeated name, a server
    /// with no project, too many servers.
    pub fn validate(&self) -> Result<()> {
        let mut names = vec![self.name.as_str()];
        for server in &self.servers {
            names.push(&server.name);
            if server.project.trim().is_empty() {
                bail!(
                    Invalid,
                    "Server \"{}\" runs no project: pick its folder",
                    server.name
                );
            }
        }
        for (i, name) in names.iter().enumerate() {
            if !is_server_name(name) {
                bail!(
                    Invalid,
                    "\"{name}\" can't name a server: use lowercase letters, digits, _ and - (at most 32)"
                );
            }
            if names[..i].contains(name) {
                bail!(Invalid, "Two servers are named \"{name}\"");
            }
            if *name == PROXY {
                bail!(
                    Invalid,
                    "\"{PROXY}\" is the network's proxy: name the server something else"
                );
            }
        }
        if self.servers.len() > MAX_SERVERS {
            bail!(
                Invalid,
                "A dev network runs at most {MAX_SERVERS} other servers"
            );
        }
        Ok(())
    }
}

/// Where [project] (as a [NetworkServer] says it) is, from the open project's
/// [root]: canonical, and holding a `netherforge.json`.
pub fn resolve_project(root: &Path, project: &str) -> Result<PathBuf> {
    let project = project.trim();
    let path = if Path::new(project).is_absolute() {
        PathBuf::from(project)
    } else {
        root.join(project)
    };
    let Some(canonical) = dunce::canonicalize(&path).ok().filter(|p| p.is_dir()) else {
        bail!(NotFound, "There's no folder {}", path.display());
    };
    if !canonical.join(crate::project::MANIFEST).is_file() {
        bail!(
            NotFound,
            "{} isn't a NetherForge project: it has no {}",
            canonical.display(),
            crate::project::MANIFEST
        );
    }
    Ok(canonical)
}

/// `networks.json`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NetworksFile {
    projects: Vec<Entry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    root: String,
    layout: serde_json::Value,
}

fn key(root: &Path) -> String {
    root.to_string_lossy().into_owned()
}

/// The layout of the project at [root] (canonical): the default when none
/// was saved, or one that no longer reads.
pub fn load(file: &Path, root: &Path) -> NetworkLayout {
    let list: NetworksFile = settings::load(file);
    let key = key(root);
    list.projects
        .into_iter()
        .find(|entry| entry.root == key)
        .and_then(|entry| serde_json::from_value(entry.layout).ok())
        .unwrap_or_default()
}

/// Saves [layout] for the project at [root] (canonical), after checking it.
pub fn save(file: &Path, root: &Path, layout: &NetworkLayout) -> Result<()> {
    layout.validate()?;
    let mut list: NetworksFile = settings::load(file);
    let key = key(root);
    list.projects.retain(|entry| entry.root != key);
    if *layout != NetworkLayout::default() {
        list.projects.push(Entry {
            root: key,
            layout: serde_json::to_value(layout)?,
        });
    }
    settings::save(file, &list)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout(name: &str, servers: &[(&str, &str)]) -> NetworkLayout {
        NetworkLayout {
            enabled: true,
            name: name.into(),
            servers: servers
                .iter()
                .map(|(name, project)| NetworkServer {
                    name: (*name).into(),
                    project: (*project).into(),
                })
                .collect(),
        }
    }

    #[test]
    fn names_are_lowercase_folder_names() {
        for good in ["main", "lobby-1", "game_2", "a"] {
            assert!(is_server_name(good), "{good}");
        }
        for bad in ["", ".", "..", "Lobby", "a.b", "a b", "a/b", &"x".repeat(33)] {
            assert!(!is_server_name(bad), "{bad}");
        }
    }

    #[test]
    fn validates_names_and_projects() {
        assert!(
            layout("main", &[("lobby", "../lobby"), ("game", ".")])
                .validate()
                .is_ok()
        );
        let repeated = layout("main", &[("main", ".")]).validate().unwrap_err();
        assert!(repeated.message().contains("Two servers"), "{repeated}");
        let bad = layout("main", &[("Lobby", ".")]).validate().unwrap_err();
        assert!(bad.message().contains("can't name a server"), "{bad}");
        assert!(layout("proxy", &[]).validate().is_err());
        let empty = layout("main", &[("lobby", " ")]).validate().unwrap_err();
        assert!(empty.message().contains("runs no project"), "{empty}");
        let many: Vec<(String, &str)> = (0..=MAX_SERVERS).map(|i| (format!("s{i}"), ".")).collect();
        let many: Vec<(&str, &str)> = many.iter().map(|(n, p)| (n.as_str(), *p)).collect();
        assert!(layout("main", &many).validate().is_err());
    }

    #[test]
    fn keeps_a_layout_per_project() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join("networks.json");
        let (a, b) = (tmp.path().join("a"), tmp.path().join("b"));
        assert_eq!(load(&file, &a), NetworkLayout::default());
        let saved = layout("hub", &[("lobby", "../lobby")]);
        save(&file, &a, &saved).unwrap();
        assert_eq!(load(&file, &a), saved);
        assert_eq!(load(&file, &b), NetworkLayout::default());
        assert!(save(&file, &b, &layout("Bad", &[])).is_err());
        // Back to the default: nothing kept for it.
        save(&file, &a, &NetworkLayout::default()).unwrap();
        assert!(!std::fs::read_to_string(&file).unwrap().contains("lobby"));
    }

    #[test]
    fn resolves_peer_projects_from_the_open_one() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("main");
        let lobby = tmp.path().join("lobby");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::create_dir_all(&lobby).unwrap();
        std::fs::write(root.join("netherforge.json"), "{}").unwrap();
        let canonical_root = dunce::canonicalize(&root).unwrap();
        assert_eq!(
            resolve_project(&canonical_root, ".").unwrap(),
            canonical_root
        );
        let error = resolve_project(&canonical_root, "../lobby").unwrap_err();
        assert!(
            error.message().contains("isn't a NetherForge project"),
            "{error}"
        );
        std::fs::write(lobby.join("netherforge.json"), "{}").unwrap();
        let found = resolve_project(&canonical_root, "../lobby").unwrap();
        assert_eq!(found, dunce::canonicalize(&lobby).unwrap());
        assert_eq!(
            resolve_project(&canonical_root, &lobby.display().to_string()).unwrap(),
            found
        );
        assert_eq!(
            resolve_project(&canonical_root, "../missing")
                .unwrap_err()
                .code(),
            crate::error::ErrorCode::NotFound
        );
    }
}
