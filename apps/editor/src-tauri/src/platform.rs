//! The operating system and the environment the search code reads, in one
//! place. Each search (Java, Minecraft installs, launchers) takes an
//! environment struct built from these, so tests can hand it a fake OS and a
//! temp folder for the filesystem root.

use std::path::PathBuf;

/// Serialized as `macos`, `windows` or `linux` (`AppInfo.os`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum Os {
    MacOs,
    Windows,
    Linux,
}

impl Os {
    pub fn current() -> Self {
        if cfg!(target_os = "macos") {
            Os::MacOs
        } else if cfg!(windows) {
            Os::Windows
        } else {
            Os::Linux
        }
    }

    /// `macos`, `windows` or `linux`, as types.ts spells them.
    pub fn name(self) -> &'static str {
        match self {
            Os::MacOs => "macos",
            Os::Windows => "windows",
            Os::Linux => "linux",
        }
    }

    /// Where absolute system paths start: `C:\` on Windows, `/` elsewhere.
    pub fn system_root(self) -> PathBuf {
        match self {
            Os::Windows => PathBuf::from("C:\\"),
            _ => PathBuf::from("/"),
        }
    }
}

/// An environment variable holding a path; unset and empty are both None.
pub fn env_path(key: &str) -> Option<PathBuf> {
    std::env::var_os(key)
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
}

/// The folders on `PATH`.
pub fn path_dirs() -> Vec<PathBuf> {
    std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).collect())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_each_os_as_the_ui_spells_it() {
        for os in [Os::MacOs, Os::Windows, Os::Linux] {
            assert_eq!(
                serde_json::to_value(os).unwrap(),
                serde_json::json!(os.name())
            );
        }
        assert_eq!(Os::MacOs.name(), "macos");
        assert_eq!(Os::Windows.name(), "windows");
        assert_eq!(Os::Linux.name(), "linux");
    }

    #[test]
    fn the_current_os_is_the_one_compiled_for() {
        let expected = if cfg!(target_os = "macos") {
            Os::MacOs
        } else if cfg!(windows) {
            Os::Windows
        } else {
            Os::Linux
        };
        assert_eq!(Os::current(), expected);
    }

    #[test]
    fn system_paths_start_at_the_os_root() {
        assert_eq!(Os::Windows.system_root(), PathBuf::from("C:\\"));
        assert_eq!(Os::MacOs.system_root(), PathBuf::from("/"));
        assert_eq!(Os::Linux.system_root(), PathBuf::from("/"));
    }

    /// Read only: setting a variable would race the other tests' threads.
    #[test]
    fn reads_the_environment() {
        assert_eq!(env_path("NETHERFORGE_SURELY_UNSET_VARIABLE"), None);
        let path = std::env::var_os("PATH").unwrap_or_default();
        assert_eq!(
            path_dirs(),
            std::env::split_paths(&path).collect::<Vec<_>>()
        );
        assert_eq!(env_path("PATH").is_some(), !path.is_empty());
    }
}
