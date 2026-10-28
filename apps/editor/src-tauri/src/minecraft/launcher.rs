//! Opening the player's Minecraft launcher, for "the target version isn't
//! installed: play it once, then import it". We never download Minecraft
//! ourselves (see the game-data skill); the launcher does, with the player's
//! own account.
//!
//! [find] is pure over a [LauncherEnv], so every OS's rules are tested on any
//! OS. The vanilla launcher comes first, then Prism (the most common
//! third-party one); the first that exists wins.

use std::path::{Path, PathBuf};
use std::process::Command;

use crate::error::{Context, Result, bail};

use crate::platform::{Os, env_path, path_dirs};

/// The Microsoft Store / Xbox app package of the vanilla launcher on Windows.
const WINDOWS_STORE_PACKAGE: &str = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

#[derive(Debug, Clone)]
pub struct LauncherEnv {
    pub os: Os,
    pub home: PathBuf,
    /// `/` for real; a temp folder in tests.
    pub system_root: PathBuf,
    /// Windows `%ProgramFiles%` and `%ProgramFiles(x86)%`.
    pub program_files: Vec<PathBuf>,
    /// Windows `%LOCALAPPDATA%`.
    pub local_appdata: Option<PathBuf>,
    /// The folders of `PATH`.
    pub path: Vec<PathBuf>,
}

impl LauncherEnv {
    pub fn current() -> Option<Self> {
        let os = Os::current();
        Some(Self {
            os,
            home: dirs::home_dir()?,
            system_root: os.system_root(),
            program_files: ["ProgramFiles(x86)", "ProgramFiles"]
                .iter()
                .filter_map(|key| env_path(key))
                .collect(),
            local_appdata: env_path("LOCALAPPDATA"),
            path: path_dirs(),
        })
    }
}

/// A program and its arguments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Launch {
    pub program: PathBuf,
    pub args: Vec<String>,
}

impl Launch {
    fn new(program: impl Into<PathBuf>, args: &[&str]) -> Self {
        Self {
            program: program.into(),
            args: args.iter().map(|a| a.to_string()).collect(),
        }
    }
}

/// How to start a launcher on this machine, or None if none is installed.
pub fn find(env: &LauncherEnv) -> Option<Launch> {
    let open_app = |app: PathBuf| {
        app.is_dir().then(|| {
            Launch::new(
                env.system_root.join("usr/bin/open"),
                &[&app.to_string_lossy()],
            )
        })
    };
    let on_path = |name: &str| {
        env.path
            .iter()
            .map(|dir| dir.join(name))
            .find(|p| p.is_file())
            .map(|p| Launch::new(p, &[]))
    };
    match env.os {
        Os::MacOs => ["Minecraft.app", "PrismLauncher.app"]
            .iter()
            .flat_map(|app| {
                [
                    env.system_root.join("Applications").join(app),
                    env.home.join("Applications").join(app),
                ]
            })
            .find_map(open_app),
        Os::Windows => {
            let installed = env.program_files.iter().find_map(|dir| {
                let exe = dir.join("Minecraft Launcher").join("MinecraftLauncher.exe");
                exe.is_file().then(|| Launch::new(exe, &[]))
            });
            let store = env.local_appdata.as_ref().and_then(|local| {
                local
                    .join("Packages")
                    .join(WINDOWS_STORE_PACKAGE)
                    .is_dir()
                    .then(|| {
                        Launch::new(
                            "explorer.exe",
                            &[&format!(
                                "shell:AppsFolder\\{WINDOWS_STORE_PACKAGE}!Minecraft"
                            )],
                        )
                    })
            });
            let prism = env.local_appdata.as_ref().and_then(|local| {
                let exe = local.join("Programs/PrismLauncher/prismlauncher.exe");
                exe.is_file().then(|| Launch::new(exe, &[]))
            });
            installed.or(store).or(prism)
        }
        Os::Linux => {
            let flatpak = |id: &str| {
                [
                    env.home.join(".local/share/flatpak/app").join(id),
                    env.system_root.join("var/lib/flatpak/app").join(id),
                ]
                .iter()
                .any(|p| p.is_dir())
                .then(|| on_path("flatpak").map(|f| Launch::new(f.program, &["run", id])))
                .flatten()
            };
            on_path("minecraft-launcher")
                .or_else(|| {
                    let opt = env
                        .system_root
                        .join("opt/minecraft-launcher/minecraft-launcher");
                    opt.is_file().then(|| Launch::new(opt, &[]))
                })
                .or_else(|| flatpak("com.mojang.Minecraft"))
                .or_else(|| on_path("prismlauncher"))
                .or_else(|| flatpak("org.prismlauncher.PrismLauncher"))
        }
    }
}

/// Starts the launcher and returns without waiting for it.
pub fn open(env: &LauncherEnv) -> Result<()> {
    let Some(launch) = find(env) else {
        bail!(
            Unavailable,
            "Couldn't find a Minecraft launcher. Open yours, play the version once, then import it here."
        );
    };
    spawn(&launch.program, &launch.args)
}

fn spawn(program: &Path, args: &[String]) -> Result<()> {
    Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(drop)
        .context(|| format!("Couldn't start {}", program.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env(os: Os, root: &Path) -> LauncherEnv {
        LauncherEnv {
            os,
            home: root.join("home"),
            system_root: root.to_path_buf(),
            program_files: vec![root.join("pf86"), root.join("pf")],
            local_appdata: Some(root.join("local")),
            path: vec![root.join("bin")],
        }
    }

    fn touch(path: &Path) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, "").unwrap();
    }

    #[test]
    fn macos_prefers_the_vanilla_app() {
        let tmp = tempfile::tempdir().unwrap();
        let env = env(Os::MacOs, tmp.path());
        assert_eq!(find(&env), None);
        std::fs::create_dir_all(tmp.path().join("home/Applications/PrismLauncher.app")).unwrap();
        assert!(
            find(&env).unwrap().args[0].ends_with("PrismLauncher.app"),
            "falls back to Prism"
        );
        std::fs::create_dir_all(tmp.path().join("Applications/Minecraft.app")).unwrap();
        let launch = find(&env).unwrap();
        assert_eq!(launch.program, tmp.path().join("usr/bin/open"));
        assert!(launch.args[0].ends_with("Minecraft.app"));
    }

    #[test]
    fn windows_installed_then_store_then_prism() {
        let tmp = tempfile::tempdir().unwrap();
        let env = env(Os::Windows, tmp.path());
        assert_eq!(find(&env), None);
        touch(
            &tmp.path()
                .join("local/Programs/PrismLauncher/prismlauncher.exe"),
        );
        assert!(find(&env).unwrap().program.ends_with("prismlauncher.exe"));
        std::fs::create_dir_all(
            tmp.path()
                .join("local/Packages")
                .join(WINDOWS_STORE_PACKAGE),
        )
        .unwrap();
        assert_eq!(find(&env).unwrap().program, PathBuf::from("explorer.exe"));
        touch(
            &tmp.path()
                .join("pf/Minecraft Launcher/MinecraftLauncher.exe"),
        );
        assert!(
            find(&env)
                .unwrap()
                .program
                .ends_with("MinecraftLauncher.exe")
        );
    }

    #[test]
    fn linux_path_opt_and_flatpak() {
        let tmp = tempfile::tempdir().unwrap();
        let env = env(Os::Linux, tmp.path());
        assert_eq!(find(&env), None);
        // A flatpak app without flatpak on PATH can't be started.
        std::fs::create_dir_all(tmp.path().join("var/lib/flatpak/app/com.mojang.Minecraft"))
            .unwrap();
        assert_eq!(find(&env), None);
        touch(&tmp.path().join("bin/flatpak"));
        assert_eq!(
            find(&env).unwrap().args,
            ["run".to_string(), "com.mojang.Minecraft".to_string()]
        );
        touch(&tmp.path().join("opt/minecraft-launcher/minecraft-launcher"));
        assert!(find(&env).unwrap().program.ends_with("minecraft-launcher"));
        touch(&tmp.path().join("bin/minecraft-launcher"));
        assert_eq!(
            find(&env).unwrap().program,
            tmp.path().join("bin/minecraft-launcher")
        );
    }

    #[test]
    fn nothing_found_is_a_sentence() {
        let tmp = tempfile::tempdir().unwrap();
        let error = open(&env(Os::Linux, tmp.path())).unwrap_err();
        assert!(
            error
                .message()
                .contains("Couldn't find a Minecraft launcher")
        );
    }
}
