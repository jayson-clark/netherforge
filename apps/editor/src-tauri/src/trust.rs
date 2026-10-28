//! Workspace trust, as VS Code has it: a project opened from a folder the
//! user hasn't trusted opens and edits, but nothing in it runs and nothing
//! acts on it unattended until they say so.
//!
//! **What waits for trust** (each refused here, in the backend, with
//! [crate::error::ErrorCode::Untrusted], never only hidden in the UI):
//!
//! - the dev server (`server_start`): it runs the project's scripts, its
//!   packages' too, on a JVM with the user's files and network;
//! - lua-language-server (`luals_start`): it reads the project's
//!   `.luarc.json`, which can name a Lua plugin for LuaLS to run as the user;
//! - fetching its git packages (`package_fetch_git` past the cache): network
//!   and `git` aimed at URLs the project chose;
//! - agents' MCP tools (`mcp::UiPipe`): an agent driving an editor whose
//!   project nobody vetted could start that server or run its commands.
//!
//! Reading and writing its files, validating it, previews and the explorer
//! need no trust: format only reads data, and the webview never runs a
//! project's Lua.
//!
//! **Where it's kept**: `<config>/trusted.json`, keyed by the canonical
//! project root ([crate::fs::ProjectRoot]), so the same folder reached
//! through a link is the same project, and a project moved or copied
//! elsewhere is untrusted again. A project the user creates in the editor is
//! trusted (they made it). The webview only asks; it never decides.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::settings;

/// `trusted.json`: every project root the user trusted, and when.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct TrustFile {
    projects: Vec<TrustedProject>,
    /// Trusted workspaces (`netherforge-workspace.json`'s folders): each trusts its folder and
    /// everything in it.
    #[serde(default)]
    workspaces: Vec<TrustedProject>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct TrustedProject {
    root: String,
    trusted_at: String,
}

fn key(root: &Path) -> String {
    root.to_string_lossy().into_owned()
}

/// Whether [root] (a canonical project root) is trusted, by the list in [file]: trusted itself,
/// or inside a trusted workspace's folder. A missing or unreadable list trusts nothing.
pub fn is_trusted(file: &Path, root: &Path) -> bool {
    let list: TrustFile = settings::load(file);
    let key = key(root);
    list.projects.iter().any(|p| p.root == key)
        || list
            .workspaces
            .iter()
            .any(|w| root.starts_with(Path::new(&w.root)))
}

/// Whether the workspace whose folder is [root] (canonical) is trusted.
pub fn is_workspace_trusted(file: &Path, root: &Path) -> bool {
    let list: TrustFile = settings::load(file);
    let key = key(root);
    list.workspaces.iter().any(|w| w.root == key)
}

/// Trusts the workspace whose folder is [root] (canonical), and so everything in it (or, with
/// `false`, stops trusting it).
pub fn set_workspace(file: &Path, root: &Path, trusted: bool) -> Result<()> {
    let mut list: TrustFile = settings::load(file);
    let key = key(root);
    list.workspaces.retain(|w| w.root != key);
    if trusted {
        list.workspaces.push(TrustedProject {
            root: key,
            trusted_at: crate::project::now_iso(),
        });
    }
    settings::save(file, &list)
}

/// Trusts [root] (or, with `false`, stops trusting it) in the list in [file].
pub fn set(file: &Path, root: &Path, trusted: bool) -> Result<()> {
    let mut list: TrustFile = settings::load(file);
    let key = key(root);
    list.projects.retain(|p| p.root != key);
    if trusted {
        list.projects.push(TrustedProject {
            root: key,
            trusted_at: crate::project::now_iso(),
        });
    }
    settings::save(file, &list)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trusts_by_root_until_told_otherwise() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join("config/trusted.json");
        let a = tmp.path().join("a");
        let b = tmp.path().join("b");
        assert!(!is_trusted(&file, &a), "nothing is trusted to begin with");
        set(&file, &a, true).unwrap();
        assert!(is_trusted(&file, &a));
        assert!(!is_trusted(&file, &b));
        set(&file, &a, true).unwrap();
        let list: TrustFile = settings::load(&file);
        assert_eq!(list.projects.len(), 1, "trusted once");
        set(&file, &a, false).unwrap();
        assert!(!is_trusted(&file, &a));

        std::fs::write(&file, "{ not json").unwrap();
        assert!(!is_trusted(&file, &a), "an unreadable list trusts nothing");
    }

    #[test]
    fn a_trusted_workspace_trusts_its_folder_and_only_that() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join("config/trusted.json");
        let workspace = tmp.path().join("polsar");
        let hub = workspace.join("servers/hub");
        let neighbour = tmp.path().join("polsar-other/servers/hub");
        assert!(!is_trusted(&file, &hub));
        set_workspace(&file, &workspace, true).unwrap();
        assert!(is_workspace_trusted(&file, &workspace));
        assert!(is_trusted(&file, &hub) && is_trusted(&file, &workspace.join("packages/chess")));
        assert!(
            !is_trusted(&file, &neighbour),
            "a folder that only starts with the same letters isn't in it"
        );
        assert!(!is_workspace_trusted(&file, &hub));
        set_workspace(&file, &workspace, false).unwrap();
        assert!(!is_trusted(&file, &hub));
    }
}
