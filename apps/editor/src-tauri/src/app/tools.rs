//! Where the editor finds what it runs, and what it reads outside a project:
//! the plugin jars, the test runner, lua-language-server, Java, Paper and the
//! player's Minecraft installs. Worked out once when the app starts (from its
//! resources and the environment) and kept in `AppState`, so a command never
//! looks anything up for itself, and the contract suite can hand the real
//! commands stand-ins in temp folders (`commands::contract`).

use std::path::{Path, PathBuf};

use crate::app::dirs::AppDirs;
use crate::app::{plugins, test_runner};
use crate::luals;
use crate::minecraft::installs::InstallEnv;
use crate::server::java::JavaEnv;

/// Where the dev server's Paper jar comes from.
#[derive(Debug, Clone)]
pub enum PaperSource {
    /// The newest build Fill (PaperMC's download API) has, kept in the cache;
    /// a cached one when Fill can't be reached.
    Fill,
    /// This jar, as it is: what the contract suite's fake server is given.
    Jar(PathBuf),
}

#[derive(Debug, Clone)]
pub struct Tools {
    /// Where `NetherForge-<v>-paper-<mc>.jar` may be, best first.
    pub plugin_dirs: Vec<PathBuf>,
    /// Where `NetherForgeTest-<v>.jar` may be, best first.
    pub test_runner_dirs: Vec<PathBuf>,
    /// Where lua-language-server may be, best first.
    pub luals: Vec<PathBuf>,
    /// Where to look for Java 25 (the dev server's, and the test runner's).
    pub java: JavaEnv,
    pub paper: PaperSource,
    /// Where to look for the player's Minecraft installs; None without a home folder.
    pub installs: Option<InstallEnv>,
}

impl Tools {
    /// The app's: what it bundles in [resource_dir] (and, in debug builds, the
    /// repo's own builds), and this computer's environment.
    pub fn for_app(resource_dir: Option<&Path>, dirs: &AppDirs) -> Self {
        Self {
            plugin_dirs: plugins::search_dirs(resource_dir),
            test_runner_dirs: test_runner::search_dirs(resource_dir),
            luals: luals::search_paths(resource_dir),
            java: JavaEnv::current(dirs),
            paper: PaperSource::Fill,
            installs: InstallEnv::current(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_apps_tools_come_from_its_resources_first() {
        let tmp = tempfile::tempdir().unwrap();
        let resources = tmp.path().join("resources");
        let tools = Tools::for_app(Some(&resources), &AppDirs::in_one(tmp.path()));
        assert_eq!(tools.plugin_dirs[0], resources.join("plugins"));
        assert_eq!(tools.test_runner_dirs[0], resources.join("test-runner"));
        assert!(tools.luals[0].starts_with(resources.join(luals::FOLDER)));
        assert_eq!(tools.java.managed_jdks, tmp.path().join("jdks"));
        assert!(matches!(tools.paper, PaperSource::Fill));
    }
}
