//! Finding the NetherForge plugin jars the editor can install into a dev server.
//!
//! - **Bundled app**: `tauri.bundle.conf.json` copies
//!   `apps/plugin/paper-*/build/libs/NetherForge*-paper-*.jar` into the app's
//!   resources under `plugins/`.
//! - **Dev (debug builds)**: also looks in the repo's
//!   `apps/plugin/paper-*/build/libs/`, so `node tools/gradle.mjs :plugin:paper-26.3:assemble`
//!   is all it takes to try a plugin change.
//!
//! One plugin jar per Minecraft version, named
//! `NetherForge-<pluginVersion>-paper-<minecraft>.jar`; the project's target
//! picks it. The bots coding agents and tests drive over the bridge are in it.
//! It also names the Paper build it was tested against ([paper_build]), which
//! is the one the dev server runs.
//!
//! The proxy plugin, `NetherForge-<pluginVersion>-velocity.jar` (a dev
//! network's hub), is found in the same folders (bundled beside the Paper
//! jars; `apps/proxy/velocity/build/libs/` in dev) and names the Velocity
//! build its proxy runs ([velocity_build]).

use std::io::Read;
use std::path::{Path, PathBuf};

use crate::error::{Context, Result, bail};
use crate::minecraft::version;

/// Where a plugin jar names its Paper build (the adapter's, written by the
/// `paperBuildProperties` task): `minecraft=26.3` and `build=152`.
pub const PAPER_PROPERTIES: &str = "META-INF/netherforge/paper.properties";

/// Where the proxy plugin names its Velocity release and Fill build
/// (`velocityBuildProperties`): `version=4.3.0` and `build=44`.
pub const VELOCITY_PROPERTIES: &str = "META-INF/netherforge/velocity.properties";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginJar {
    pub minecraft: String,
    pub plugin_version: String,
    pub path: PathBuf,
}

/// Whether a file in a server's `plugins/` is one of ours: the plugin, or the
/// bots companion jar older builds installed beside it (the bots are in the
/// plugin now), which a new install takes away.
pub fn is_ours(name: &str) -> bool {
    (name.starts_with("NetherForge-") || name.starts_with("NetherForgeBots-"))
        && name.ends_with(".jar")
}

/// `NetherForge-0.1.0-paper-26.3.jar` → (`0.1.0`, `26.3`).
pub fn parse_jar_name(name: &str) -> Option<(String, String)> {
    let stem = name.strip_prefix("NetherForge-")?.strip_suffix(".jar")?;
    let (plugin_version, minecraft) = stem.rsplit_once("-paper-")?;
    if plugin_version.is_empty() || !version::is_release(minecraft) {
        return None;
    }
    Some((plugin_version.to_string(), minecraft.to_string()))
}

/// The proxy plugin: `NetherForge-<pluginVersion>-velocity.jar`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyJar {
    pub plugin_version: String,
    pub path: PathBuf,
}

/// `NetherForge-0.1.0-velocity.jar` → `0.1.0`.
pub fn parse_proxy_jar_name(name: &str) -> Option<String> {
    let version = name
        .strip_prefix("NetherForge-")?
        .strip_suffix("-velocity.jar")?;
    (!version.is_empty()).then(|| version.to_string())
}

/// The folders to search, best first.
pub fn search_dirs(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(resources) = resource_dir {
        dirs.push(resources.join("plugins"));
    }
    if cfg!(debug_assertions) {
        let plugin_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../plugin");
        if let Ok(entries) = std::fs::read_dir(&plugin_root) {
            let mut adapters: Vec<PathBuf> = entries
                .flatten()
                .filter(|e| e.file_name().to_string_lossy().starts_with("paper-"))
                .map(|e| e.path().join("build").join("libs"))
                .collect();
            adapters.sort();
            dirs.extend(adapters);
        }
        dirs.push(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../proxy/velocity/build/libs"));
    }
    dirs
}

/// Every plugin jar in [dirs]. For one Minecraft version, a jar from an
/// earlier folder wins; within a folder, the most recently built one.
pub fn scan(dirs: &[PathBuf]) -> Vec<PluginJar> {
    let mut found: Vec<PluginJar> = Vec::new();
    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        let mut here: Vec<(PluginJar, std::time::SystemTime)> = entries
            .flatten()
            .filter_map(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                let (plugin_version, minecraft) = parse_jar_name(&name)?;
                let meta = entry.metadata().ok().filter(|m| m.is_file())?;
                let modified = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                Some((
                    PluginJar {
                        minecraft,
                        plugin_version,
                        path: entry.path(),
                    },
                    modified,
                ))
            })
            .collect();
        here.sort_by_key(|entry| std::cmp::Reverse(entry.1));
        for (jar, _) in here {
            if !found.iter().any(|f| f.minecraft == jar.minecraft) {
                found.push(jar);
            }
        }
    }
    found.sort_by(|a, b| version::compare(&b.minecraft, &a.minecraft));
    found
}

pub fn find(dirs: &[PathBuf], minecraft: &str) -> Option<PluginJar> {
    scan(dirs)
        .into_iter()
        .find(|jar| version::compare(&jar.minecraft, minecraft).is_eq())
}

/// The proxy plugin to install on a dev network's proxy: from the earliest
/// folder that has one, the most recently built there.
pub fn find_proxy(dirs: &[PathBuf]) -> Option<ProxyJar> {
    dirs.iter().find_map(|dir| {
        std::fs::read_dir(dir)
            .ok()?
            .flatten()
            .filter_map(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                let plugin_version = parse_proxy_jar_name(&name)?;
                let meta = entry.metadata().ok().filter(|m| m.is_file())?;
                let modified = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                Some((
                    ProxyJar {
                        plugin_version,
                        path: entry.path(),
                    },
                    modified,
                ))
            })
            .max_by_key(|(_, modified)| *modified)
            .map(|(jar, _)| jar)
    })
}

/// The Paper build [jar] was tested against, for its Minecraft version.
pub fn paper_build(jar: &PluginJar) -> Result<u64> {
    let (name, text) = read_properties(&jar.path, PAPER_PROPERTIES, "Paper")?;
    parse_paper_properties(&text, &jar.minecraft)
        .context(|| format!("{PAPER_PROPERTIES} in {name}"))
}

/// The Velocity release and build [jar] is made for.
pub fn velocity_build(jar: &ProxyJar) -> Result<(String, u64)> {
    let (name, text) = read_properties(&jar.path, VELOCITY_PROPERTIES, "Velocity")?;
    parse_velocity_properties(&text).context(|| format!("{VELOCITY_PROPERTIES} in {name}"))
}

/// The jar's name and its [entry]'s text.
fn read_properties(path: &Path, entry: &str, what: &str) -> Result<(String, String)> {
    let name = path
        .file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();
    let file = std::fs::File::open(path).context(|| format!("Couldn't open {name}"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file))
        .context(|| format!("{name} isn't a jar"))?;
    let Ok(found) = archive.by_name(entry) else {
        bail!(
            "{name} doesn't say which {what} build it's for: it's from an older build, so build it again"
        );
    };
    let mut text = String::new();
    found
        .take(64 * 1024)
        .read_to_string(&mut text)
        .context(|| format!("Couldn't read {entry} in {name}"))?;
    Ok((name, text))
}

/// [key]'s value in a `.properties` file.
fn property<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    text.lines()
        .filter(|line| !line.trim_start().starts_with('#'))
        .find_map(|line| Some(line.split_once('=')?).filter(|(k, _)| k.trim() == key))
        .map(|(_, v)| v.trim())
}

/// The `version` and `build` of a `velocity.properties`. The version becomes
/// a file name in the cache, so it's held to what Velocity's look like.
fn parse_velocity_properties(text: &str) -> Result<(String, u64)> {
    let version = property(text, "version")
        .filter(|v| {
            !v.is_empty()
                && v.len() <= 32
                && v.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
                && v.starts_with(|c: char| c.is_ascii_digit())
        })
        .context(|| "names no Velocity version".into())?;
    match property(text, "build").and_then(|build| build.parse().ok()) {
        Some(build) => Ok((version.to_string(), build)),
        None => bail!("names no Velocity build number"),
    }
}

/// The `build` of a `paper.properties`, which must be for [minecraft].
fn parse_paper_properties(text: &str, minecraft: &str) -> Result<u64> {
    let value = |key: &str| property(text, key);
    if value("minecraft") != Some(minecraft) {
        bail!("names no Paper build for Minecraft {minecraft}");
    }
    match value("build").and_then(|build| build.parse().ok()) {
        Some(build) => Ok(build),
        None => bail!("names no Paper build number"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_jar_names() {
        assert_eq!(
            parse_jar_name("NetherForge-0.1.0-paper-26.3.jar"),
            Some(("0.1.0".into(), "26.3".into()))
        );
        assert_eq!(
            parse_jar_name("NetherForge-0.2.0-SNAPSHOT-paper-1.21.11.jar"),
            Some(("0.2.0-SNAPSHOT".into(), "1.21.11".into()))
        );
        assert_eq!(
            parse_jar_name("NetherForge-0.1.0-paper-26.3-sources.jar"),
            None
        );
        assert_eq!(parse_jar_name("NetherForge-0.1.0.jar"), None);
        assert_eq!(parse_jar_name("NetherForgeBots-0.1.0-paper-26.3.jar"), None);
        assert_eq!(parse_jar_name("paper-26.3.jar"), None);
    }

    #[test]
    fn finds_the_proxy_plugin_and_its_velocity_build() {
        assert_eq!(
            parse_proxy_jar_name("NetherForge-0.1.0-velocity.jar"),
            Some("0.1.0".into())
        );
        assert_eq!(
            parse_proxy_jar_name("NetherForge-0.1.0-paper-26.3.jar"),
            None
        );
        assert_eq!(parse_proxy_jar_name("NetherForge--velocity.jar"), None);
        assert_eq!(
            parse_velocity_properties("# written by gradle\nbuild=44\nversion=4.3.0\n").unwrap(),
            ("4.3.0".into(), 44)
        );
        assert!(parse_velocity_properties("version=../x\nbuild=1").is_err());
        assert!(parse_velocity_properties("version=4.3.0\n").is_err());

        let tmp = tempfile::tempdir().unwrap();
        let bundled = tmp.path().join("bundled");
        let dev = tmp.path().join("dev");
        std::fs::create_dir_all(&bundled).unwrap();
        std::fs::create_dir_all(&dev).unwrap();
        std::fs::write(bundled.join("NetherForge-0.1.0-paper-26.3.jar"), "a").unwrap();
        assert_eq!(find_proxy(std::slice::from_ref(&bundled)), None);
        std::fs::write(dev.join("NetherForge-0.1.1-velocity.jar"), "b").unwrap();
        let found = find_proxy(&[bundled, dev.clone()]).unwrap();
        assert_eq!(found.path, dev.join("NetherForge-0.1.1-velocity.jar"));
        assert_eq!(found.plugin_version, "0.1.1");
        assert!(
            velocity_build(&found)
                .unwrap_err()
                .message()
                .contains("isn't a jar")
        );
    }

    #[test]
    fn scans_folders_in_order() {
        let tmp = tempfile::tempdir().unwrap();
        let bundled = tmp.path().join("bundled");
        let dev = tmp.path().join("dev");
        std::fs::create_dir_all(&bundled).unwrap();
        std::fs::create_dir_all(&dev).unwrap();
        std::fs::write(bundled.join("NetherForge-0.1.0-paper-26.3.jar"), "a").unwrap();
        std::fs::write(dev.join("NetherForge-0.1.1-paper-26.3.jar"), "b").unwrap();
        std::fs::write(dev.join("NetherForge-0.1.1-paper-1.21.11.jar"), "c").unwrap();
        // An older build's bots companion isn't a plugin jar.
        std::fs::write(bundled.join("NetherForgeBots-0.0.9-paper-26.3.jar"), "e").unwrap();
        std::fs::write(dev.join("README.txt"), "").unwrap();

        let jars = scan(&[bundled.clone(), dev.clone(), tmp.path().join("missing")]);
        let versions: Vec<_> = jars.iter().map(|j| j.minecraft.as_str()).collect();
        assert_eq!(versions, ["26.3", "1.21.11"]);
        assert_eq!(
            jars[0].path,
            bundled.join("NetherForge-0.1.0-paper-26.3.jar")
        );
        let old = find(&[bundled, dev.clone()], "1.21.11").unwrap();
        assert_eq!(old.path, dev.join("NetherForge-0.1.1-paper-1.21.11.jar"));
        assert!(is_ours("NetherForgeBots-0.1.1-paper-1.21.11.jar"));
        assert!(!is_ours("NetherForgeExtras.zip") && !is_ours("OtherPlugin.jar"));
    }

    fn jar_with(dir: &Path, entries: &[(&str, &str)]) -> PluginJar {
        use std::io::Write;
        let path = dir.join("NetherForge-0.1.0-paper-26.3.jar");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        for (name, text) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        PluginJar {
            minecraft: "26.3".into(),
            plugin_version: "0.1.0".into(),
            path,
        }
    }

    #[test]
    fn reads_the_paper_build_a_jar_was_tested_against() {
        let tmp = tempfile::tempdir().unwrap();
        // As Gradle's WriteProperties writes it.
        let jar = jar_with(
            tmp.path(),
            &[(PAPER_PROPERTIES, "build=152\nminecraft=26.3\n")],
        );
        assert_eq!(paper_build(&jar).unwrap(), 152);

        let older = jar_with(tmp.path(), &[("paper-plugin.yml", "name: NetherForge")]);
        let error = paper_build(&older).unwrap_err();
        assert!(error.message().contains("build it again"), "{error}");
    }

    #[test]
    fn paper_properties_must_be_for_the_jars_version() {
        assert_eq!(
            parse_paper_properties("# a comment\nminecraft = 26.3\nbuild = 7\n", "26.3").unwrap(),
            7
        );
        assert!(parse_paper_properties("minecraft=26.2\nbuild=7", "26.3").is_err());
        assert!(parse_paper_properties("minecraft=26.3\nbuild=latest", "26.3").is_err());
        assert!(parse_paper_properties("minecraft=26.3", "26.3").is_err());
    }
}
