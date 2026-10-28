//! Where the editor keeps its own files.
//!
//! - **config** (`settings.json`, `recent.json`, `eula.json`, `trusted.json`,
//!   `networks.json`): the OS config
//!   folder + `NetherForge/`.
//! - **data** (JDKs, Paper jars, the Minecraft cache, each project's dev
//!   server): the OS *local* data folder + `NetherForge/`. On Windows that's
//!   `%LOCALAPPDATA%`, not the roaming `%APPDATA%`, because a JDK, a Paper jar
//!   and a world are hundreds of MB that must not sync with a roaming profile.
//!   On macOS both are `~/Library/Application Support`.
//!
//! `NETHERFORGE_DATA_DIR` overrides both (one folder), for tests and for running
//! a second, isolated editor.

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::error::{Context, Result};

/// How much of the root's hash names its server folder: 64 bits is plenty
/// for the projects on one machine, and keeps the path short (Windows).
const SERVER_ID_BYTES: usize = 8;

/// The hex of the first [SERVER_ID_BYTES] of the canonical root's SHA-256.
fn project_id(project_root: &Path) -> String {
    let root = dunce::canonicalize(project_root).unwrap_or_else(|_| project_root.into());
    let digest = Sha256::digest(root.to_string_lossy().as_bytes());
    hex::encode(&digest[..SERVER_ID_BYTES])
}

#[derive(Debug, Clone)]
pub struct AppDirs {
    pub config: PathBuf,
    pub data: PathBuf,
}

impl AppDirs {
    pub fn from_env() -> Result<Self> {
        if let Some(dir) = std::env::var_os("NETHERFORGE_DATA_DIR").filter(|v| !v.is_empty()) {
            let dir = PathBuf::from(dir);
            return Ok(Self::in_one(&dir));
        }
        let config = dirs::config_dir().context(|| "This OS has no config folder".into())?;
        let data = dirs::data_local_dir().context(|| "This OS has no data folder".into())?;
        Ok(Self {
            config: config.join("NetherForge"),
            data: data.join("NetherForge"),
        })
    }

    /// Everything under one folder (tests, `NETHERFORGE_DATA_DIR`).
    pub fn in_one(dir: &Path) -> Self {
        Self {
            config: dir.join("config"),
            data: dir.to_path_buf(),
        }
    }

    pub fn settings_file(&self) -> PathBuf {
        self.config.join("settings.json")
    }

    pub fn recent_file(&self) -> PathBuf {
        self.config.join("recent.json")
    }

    pub fn eula_file(&self) -> PathBuf {
        self.config.join("eula.json")
    }

    /// The project roots the user trusted (see [crate::trust]).
    pub fn trust_file(&self) -> PathBuf {
        self.config.join("trusted.json")
    }

    /// Each project's dev network layout (see [crate::server::network::layout]).
    pub fn networks_file(&self) -> PathBuf {
        self.config.join("networks.json")
    }

    /// What lua-language-server writes (its generated std-library stubs, logs)
    /// and the editor's plugin for it.
    pub fn luals(&self) -> PathBuf {
        self.data.join("luals")
    }

    /// JDKs the editor downloaded, one folder per release.
    pub fn jdks(&self) -> PathBuf {
        self.data.join("jdks")
    }

    /// Paper jars, by Minecraft version and build.
    pub fn paper(&self) -> PathBuf {
        self.data.join("paper")
    }

    /// The package cache: git packages fetched for any project (`git/db/`,
    /// `git/checkouts/<commit>/`; see [crate::fs::git]), shared with the
    /// `netherforge` command and read by dev servers.
    pub fn packages(&self) -> PathBuf {
        self.data.join("packages")
    }

    /// The script test runner's jar, copied here for the `netherforge test` command (apps/cli finds
    /// it as `<data>/test-runner/NetherForgeTest-<version>.jar`).
    pub fn test_runner(&self) -> PathBuf {
        self.data.join("test-runner")
    }

    /// The dev servers, one folder per project.
    pub fn servers(&self) -> PathBuf {
        self.data.join("servers")
    }

    /// The dev server's folder for the project at [project_root]:
    /// `servers/<hash of the canonical root>/`. Paper's working directory: the
    /// world, `server.properties`, `plugins/` and the plugin's own state.
    ///
    /// It lives here and not in the project because everything in a project is
    /// untrusted: a project that shipped its own `paper.jar` or a jar in
    /// `plugins/` would have it run on Start. Nothing in this folder comes
    /// from the project; the editor fills it from its own caches each start.
    pub fn server_dir(&self, project_root: &Path) -> PathBuf {
        self.servers().join(project_id(project_root))
    }

    /// The dev network of the project at [project_root]:
    /// `networks/<hash of the canonical root>/`, with `proxy/` (Velocity's
    /// folder) and `servers/<name>/` (each other server's). The project's own
    /// server keeps its [server_dir], network or not. Filled by the editor,
    /// like a server folder, never from a project.
    pub fn network_dir(&self, project_root: &Path) -> PathBuf {
        self.data.join("networks").join(project_id(project_root))
    }

    /// The per-user game-data cache root: `minecraft/<version>/{client,server}/`.
    pub fn minecraft(&self) -> PathBuf {
        self.data.join("minecraft")
    }

    pub fn client_dir(&self, version: &str) -> PathBuf {
        self.minecraft().join(version).join("client")
    }

    pub fn game_data_file(&self, version: &str) -> PathBuf {
        self.minecraft()
            .join(version)
            .join("server")
            .join("game-data.json")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_projects_server_lives_in_the_data_folder_named_by_its_root() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(&tmp.path().join("data"));
        let project = tmp.path().join("work/project");
        let other = tmp.path().join("work/other");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::create_dir_all(&other).unwrap();

        let dir = dirs.server_dir(&project);
        assert_eq!(dir.parent().unwrap(), dirs.servers());
        assert_eq!(dir.file_name().unwrap().len(), SERVER_ID_BYTES * 2);
        assert!(!dir.starts_with(&project));
        // The same folder however the root is spelled; another project gets its own.
        assert_eq!(dirs.server_dir(&project.join("../project")), dir);
        assert_ne!(dirs.server_dir(&other), dir);
        #[cfg(unix)]
        {
            let link = tmp.path().join("link");
            std::os::unix::fs::symlink(&project, &link).unwrap();
            assert_eq!(dirs.server_dir(&link), dir);
        }
    }
}
