//! Finding the player's Minecraft client jars, across launchers and OSes.
//!
//! [detect] is a pure function of an [InstallEnv] (home and the OS's data
//! folders), so tests run it against fake trees for every OS on any OS.
//!
//! Every candidate folder is scanned for all known jar layouts, because
//! launchers move things between releases:
//! - vanilla style: `versions/<v>/<v>.jar` (vanilla, CurseForge's `Install/`, ATLauncher)
//! - Modrinth App: `meta/versions/<v>/<v>.jar`
//! - Prism / MultiMC: `libraries/com/mojang/minecraft/<v>/minecraft-<v>-client.jar`
//! - `libraries/net/minecraft/client/<v>/client-<v>.jar` (seen in ATLauncher)
//!
//! Only release versions are reported (`1.21.11`, `26.3`), never snapshots.

use std::path::{Path, PathBuf};

use serde::Serialize;

use super::version;
use crate::platform::{Os, env_path};

#[derive(Debug, Clone)]
pub struct InstallEnv {
    pub os: Os,
    pub home: PathBuf,
    /// `/` for real; a temp folder in tests (macOS `/Applications`).
    pub system_root: PathBuf,
    /// Windows `%APPDATA%` (Roaming).
    pub appdata: Option<PathBuf>,
    /// Windows `%LOCALAPPDATA%`.
    pub local_appdata: Option<PathBuf>,
    /// Linux `$XDG_DATA_HOME` (defaults to `~/.local/share`).
    pub xdg_data_home: Option<PathBuf>,
}

impl InstallEnv {
    pub fn current() -> Option<Self> {
        let os = Os::current();
        Some(Self {
            os,
            home: dirs::home_dir()?,
            system_root: os.system_root(),
            appdata: env_path("APPDATA"),
            local_appdata: env_path("LOCALAPPDATA"),
            xdg_data_home: env_path("XDG_DATA_HOME"),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct InstallVersion {
    pub version: String,
    pub jar: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct MinecraftInstall {
    pub launcher: String,
    pub path: String,
    pub versions: Vec<InstallVersion>,
}

/// (launcher, folder reported to the user, folder scanned for jars).
fn candidates(env: &InstallEnv) -> Vec<(&'static str, PathBuf, PathBuf)> {
    let home = &env.home;
    let same = |launcher: &'static str, path: PathBuf| (launcher, path.clone(), path);
    let mut list = Vec::new();
    match env.os {
        Os::MacOs => {
            let support = home.join("Library/Application Support");
            list.push(same("vanilla", support.join("minecraft")));
            list.push(same("prism", support.join("PrismLauncher")));
            list.push(same("multimc", support.join("MultiMC")));
            list.push(same(
                "multimc",
                env.system_root.join("Applications/MultiMC.app/Data"),
            ));
            list.push(same("multimc", home.join("Applications/MultiMC.app/Data")));
            list.push(same("modrinth", support.join("ModrinthApp")));
            list.push(same("modrinth", support.join("com.modrinth.theseus")));
            let curseforge = home.join("Documents/curseforge/minecraft");
            list.push(("curseforge", curseforge.clone(), curseforge.join("Install")));
            list.push(same("atlauncher", support.join("ATLauncher")));
        }
        Os::Windows => {
            let roaming = env
                .appdata
                .clone()
                .unwrap_or_else(|| home.join("AppData/Roaming"));
            let local = env
                .local_appdata
                .clone()
                .unwrap_or_else(|| home.join("AppData/Local"));
            list.push(same("vanilla", roaming.join(".minecraft")));
            list.push(same("prism", roaming.join("PrismLauncher")));
            list.push(same("prism", local.join("Programs/PrismLauncher")));
            list.push(same("multimc", home.join("MultiMC")));
            list.push(same("multimc", roaming.join("MultiMC")));
            list.push(same("modrinth", roaming.join("ModrinthApp")));
            list.push(same("modrinth", roaming.join("com.modrinth.theseus")));
            let curseforge = home.join("curseforge/minecraft");
            list.push(("curseforge", curseforge.clone(), curseforge.join("Install")));
            list.push(same("atlauncher", roaming.join("ATLauncher")));
        }
        Os::Linux => {
            let data = env
                .xdg_data_home
                .clone()
                .unwrap_or_else(|| home.join(".local/share"));
            let flatpak = home.join(".var/app");
            list.push(same("vanilla", home.join(".minecraft")));
            list.push(same(
                "vanilla",
                flatpak.join("com.mojang.Minecraft/.minecraft"),
            ));
            list.push(same("prism", data.join("PrismLauncher")));
            list.push(same(
                "prism",
                flatpak.join("org.prismlauncher.PrismLauncher/data/PrismLauncher"),
            ));
            list.push(same("multimc", data.join("multimc")));
            list.push(same("multimc", home.join("MultiMC")));
            list.push(same("modrinth", data.join("ModrinthApp")));
            list.push(same("modrinth", data.join("com.modrinth.theseus")));
            list.push(same(
                "modrinth",
                flatpak.join("com.modrinth.ModrinthApp/data/ModrinthApp"),
            ));
            let curseforge = home.join("curseforge/minecraft");
            list.push(("curseforge", curseforge.clone(), curseforge.join("Install")));
            list.push(same("atlauncher", data.join("ATLauncher")));
            list.push(same("atlauncher", data.join("atlauncher")));
            list.push(same(
                "atlauncher",
                flatpak.join("com.atlauncher.ATLauncher/data"),
            ));
        }
    }
    list
}

/// Release-version client jars under [root], in any known layout.
pub fn scan_root(root: &Path) -> Vec<InstallVersion> {
    let mut found: Vec<InstallVersion> = Vec::new();
    let mut add = |version: String, jar: PathBuf| {
        if jar.is_file() && !found.iter().any(|f| f.version == version) {
            found.push(InstallVersion {
                version,
                jar: jar.display().to_string(),
            });
        }
    };
    type JarName = fn(&str) -> String;
    let layouts: [(&str, JarName); 4] = [
        ("versions", |v| format!("{v}.jar")),
        ("meta/versions", |v| format!("{v}.jar")),
        ("libraries/com/mojang/minecraft", |v| {
            format!("minecraft-{v}-client.jar")
        }),
        ("libraries/net/minecraft/client", |v| {
            format!("client-{v}.jar")
        }),
    ];
    for (folder, jar_name) in layouts {
        let Ok(entries) = std::fs::read_dir(root.join(folder)) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if version::is_release(&name) {
                add(name.clone(), entry.path().join(jar_name(&name)));
            }
        }
    }
    found.sort_by(|a, b| version::compare(&b.version, &a.version));
    found
}

/// Identifies a folder regardless of how its name is spelled, so `ATLauncher`
/// and `atlauncher` on a case-insensitive filesystem count once.
#[cfg(unix)]
fn folder_id(path: &Path) -> Option<String> {
    use std::os::unix::fs::MetadataExt;
    let meta = std::fs::metadata(path).ok()?;
    Some(format!("{}:{}", meta.dev(), meta.ino()))
}

#[cfg(not(unix))]
fn folder_id(path: &Path) -> Option<String> {
    Some(
        dunce::canonicalize(path)
            .ok()?
            .to_string_lossy()
            .to_lowercase(),
    )
}

pub fn detect(env: &InstallEnv) -> Vec<MinecraftInstall> {
    let mut installs: Vec<MinecraftInstall> = Vec::new();
    let mut seen: Vec<String> = Vec::new();
    for (launcher, shown, scanned) in candidates(env) {
        if !scanned.is_dir() {
            continue;
        }
        let path = shown.display().to_string();
        let id = folder_id(&scanned).unwrap_or_else(|| path.clone());
        if seen.contains(&id) {
            continue;
        }
        seen.push(id);
        installs.push(MinecraftInstall {
            launcher: launcher.to_string(),
            path,
            versions: scan_root(&scanned),
        });
    }
    installs
}

#[cfg(test)]
mod tests {
    use super::*;

    fn touch(path: &Path) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, b"PK").unwrap();
    }

    fn env(os: Os, home: &Path) -> InstallEnv {
        InstallEnv {
            os,
            home: home.to_path_buf(),
            system_root: home.join("system-root"),
            appdata: (os == Os::Windows).then(|| home.join("AppData/Roaming")),
            local_appdata: (os == Os::Windows).then(|| home.join("AppData/Local")),
            xdg_data_home: None,
        }
    }

    fn summary(installs: &[MinecraftInstall], home: &Path) -> Vec<(String, String, Vec<String>)> {
        installs
            .iter()
            .map(|i| {
                let rel = Path::new(&i.path)
                    .strip_prefix(home)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/");
                (
                    i.launcher.clone(),
                    rel,
                    i.versions.iter().map(|v| v.version.clone()).collect(),
                )
            })
            .collect()
    }

    fn vanilla_jar(root: &Path, v: &str) -> PathBuf {
        root.join("versions").join(v).join(format!("{v}.jar"))
    }

    fn mmc_jar(root: &Path, v: &str) -> PathBuf {
        root.join("libraries/com/mojang/minecraft")
            .join(v)
            .join(format!("minecraft-{v}-client.jar"))
    }

    #[test]
    fn macos_layouts() {
        let tmp = tempfile::tempdir().unwrap();
        let home = tmp.path();
        let support = home.join("Library/Application Support");
        touch(&vanilla_jar(&support.join("minecraft"), "26.3"));
        touch(&vanilla_jar(&support.join("minecraft"), "1.21.11"));
        touch(&vanilla_jar(&support.join("minecraft"), "26w14a"));
        touch(&vanilla_jar(&support.join("minecraft"), "26.3-pre2"));
        // A version folder without its jar (e.g. a modded profile) is skipped.
        std::fs::create_dir_all(support.join("minecraft/versions/1.21.10")).unwrap();
        touch(&mmc_jar(&support.join("PrismLauncher"), "26.3"));
        touch(&vanilla_jar(&support.join("ModrinthApp/meta"), "26.3.1"));
        touch(&vanilla_jar(
            &home.join("Documents/curseforge/minecraft/Install"),
            "1.21.11",
        ));
        touch(&vanilla_jar(&support.join("ATLauncher"), "26.3"));

        let installs = detect(&env(Os::MacOs, home));
        assert_eq!(
            summary(&installs, home),
            [
                (
                    "vanilla".into(),
                    "Library/Application Support/minecraft".into(),
                    vec!["26.3".into(), "1.21.11".into()]
                ),
                (
                    "prism".into(),
                    "Library/Application Support/PrismLauncher".into(),
                    vec!["26.3".into()]
                ),
                (
                    "modrinth".into(),
                    "Library/Application Support/ModrinthApp".into(),
                    vec!["26.3.1".into()]
                ),
                (
                    "curseforge".into(),
                    "Documents/curseforge/minecraft".into(),
                    vec!["1.21.11".into()]
                ),
                (
                    "atlauncher".into(),
                    "Library/Application Support/ATLauncher".into(),
                    vec!["26.3".into()]
                ),
            ]
        );
        let jar = Path::new(&installs[1].versions[0].jar);
        assert!(jar.ends_with("minecraft-26.3-client.jar"));
    }

    #[test]
    fn windows_layouts() {
        let tmp = tempfile::tempdir().unwrap();
        let home = tmp.path();
        let roaming = home.join("AppData/Roaming");
        touch(&vanilla_jar(&roaming.join(".minecraft"), "26.3"));
        touch(&mmc_jar(&home.join("MultiMC"), "1.21.11"));
        touch(&mmc_jar(&roaming.join("PrismLauncher"), "26.3"));
        touch(&vanilla_jar(
            &roaming.join("com.modrinth.theseus/meta"),
            "26.3",
        ));
        touch(&vanilla_jar(
            &home.join("curseforge/minecraft/Install"),
            "26.3",
        ));
        touch(&roaming.join("ATLauncher/libraries/net/minecraft/client/26.3/client-26.3.jar"));

        let found = summary(&detect(&env(Os::Windows, home)), home);
        let launchers: Vec<_> = found.iter().map(|f| f.0.as_str()).collect();
        assert_eq!(
            launchers,
            [
                "vanilla",
                "prism",
                "multimc",
                "modrinth",
                "curseforge",
                "atlauncher"
            ]
        );
        assert_eq!(found[0].1, "AppData/Roaming/.minecraft");
        assert_eq!(found[2].2, ["1.21.11"]);
        assert!(found.iter().all(|f| !f.2.is_empty()));
    }

    #[test]
    fn linux_layouts() {
        let tmp = tempfile::tempdir().unwrap();
        let home = tmp.path();
        touch(&vanilla_jar(&home.join(".minecraft"), "26.3"));
        touch(&vanilla_jar(
            &home.join(".var/app/com.mojang.Minecraft/.minecraft"),
            "1.21.11",
        ));
        touch(&mmc_jar(&home.join(".local/share/PrismLauncher"), "26.3"));
        touch(&mmc_jar(&home.join(".local/share/multimc"), "26.3"));
        touch(&vanilla_jar(
            &home.join(".local/share/ModrinthApp/meta"),
            "26.3",
        ));
        touch(&vanilla_jar(&home.join(".local/share/ATLauncher"), "26.3"));
        // An empty launcher folder is still reported, with no versions.
        std::fs::create_dir_all(home.join("curseforge/minecraft/Install")).unwrap();

        let found = summary(&detect(&env(Os::Linux, home)), home);
        assert_eq!(
            found
                .iter()
                .map(|f| (f.0.as_str(), f.1.as_str()))
                .collect::<Vec<_>>(),
            [
                ("vanilla", ".minecraft"),
                ("vanilla", ".var/app/com.mojang.Minecraft/.minecraft"),
                ("prism", ".local/share/PrismLauncher"),
                ("multimc", ".local/share/multimc"),
                ("modrinth", ".local/share/ModrinthApp"),
                ("curseforge", "curseforge/minecraft"),
                ("atlauncher", ".local/share/ATLauncher"),
            ]
        );
        assert!(found[5].2.is_empty());
    }

    #[test]
    fn honours_xdg_data_home() {
        let tmp = tempfile::tempdir().unwrap();
        let home = tmp.path().join("home");
        let xdg = tmp.path().join("xdg");
        touch(&mmc_jar(&xdg.join("PrismLauncher"), "26.3"));
        let mut e = env(Os::Linux, &home);
        e.xdg_data_home = Some(xdg.clone());
        let installs = detect(&e);
        assert_eq!(installs.len(), 1);
        assert_eq!(
            installs[0].path,
            xdg.join("PrismLauncher").display().to_string()
        );
    }

    #[test]
    fn nothing_installed() {
        let tmp = tempfile::tempdir().unwrap();
        for os in [Os::MacOs, Os::Windows, Os::Linux] {
            assert!(detect(&env(os, tmp.path())).is_empty());
        }
    }
}
