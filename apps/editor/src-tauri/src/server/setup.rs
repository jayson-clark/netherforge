//! The dev server's folder ([AppDirs::server_dir], in the editor's data
//! folder and never in the project): `eula.txt`, `server.properties`,
//! `bukkit.yml`'s main-terrain, the plugin jar, and the JVM command
//! line.
//!
//! Everything that runs comes from the editor: Paper straight from its cache
//! (`-jar <data>/paper/…`, never a copy in the server folder), the plugin from
//! the editor's own build. The project only gives its path to the plugin, which
//! reads its files as data. So a project can't put a jar on the classpath or in
//! `plugins/` (see the tauri-backend skill's threat notes).
//!
//! The EULA: `eula.txt` is written only after the user accepted in the UI
//! (`server_eula_accept`); `server_start` refuses before that. We never write
//! `eula=true` on anyone's behalf.
//!
//! [AppDirs::server_dir]: crate::app::dirs::AppDirs::server_dir

use std::path::Path;

use crate::app::plugins::{PluginJar, is_ours};
use crate::error::{Context, Result};
use crate::fs::atomic::write_atomic;
use crate::settings::Settings;

/// Names the project a server folder belongs to, for a person looking through
/// `<data>/servers/`.
pub const PROJECT_FILE: &str = "netherforge-project.txt";

/// Fills the server folder for a start: the EULA (already accepted, checked by
/// the caller; written every time so a deleted folder needs no second
/// acceptance), `server.properties`, `bukkit.yml`'s main-terrain, the
/// plugin, and which project it's for.
pub fn prepare(
    dir: &Path,
    project_root: &Path,
    plugin: &PluginJar,
    port: u16,
    motd: &str,
) -> Result<()> {
    std::fs::create_dir_all(dir).context(|| format!("Couldn't create {}", dir.display()))?;
    write_atomic(
        &dir.join(PROJECT_FILE),
        format!("{}\n", project_root.display()).as_bytes(),
    )?;
    write_eula(dir)?;
    write_properties(dir, port, motd)?;
    write_main_world_generator(dir, project_root)?;
    install_plugin(dir, plugin)
}

pub fn write_eula(dir: &Path) -> Result<()> {
    let text = format!(
        "# Accepted in the NetherForge editor ({}): {}\neula=true\n",
        crate::project::now_iso(),
        crate::settings::EULA_URL
    );
    write_atomic(&dir.join("eula.txt"), text.as_bytes())
}

/// `server.properties` with our keys set. Existing files keep everything
/// else (the user may have tuned them); only the port is forced every start,
/// since it comes from Settings. A new file also gets online mode, a motd, no
/// spawn protection (so you can build at spawn without op) and no whitelist.
///
/// The whitelist needs more than a default: Minecraft 26.x writes
/// `white-list=true` into any file missing the key, and an empty whitelist
/// locks everyone out of a server whose only purpose is to be joined. So when
/// [whitelist_empty] (nobody is on it), an existing `white-list=true` is
/// turned off too. A whitelist somebody actually filled in is left alone.
pub fn properties(existing: Option<&str>, port: u16, motd: &str, whitelist_empty: bool) -> String {
    let mut forced = vec![("server-port", port.to_string())];
    if whitelist_empty {
        forced.push(("white-list", "false".to_string()));
    }
    let defaults = [
        ("online-mode", "true".to_string()),
        ("motd", escape_property(motd)),
        ("spawn-protection", "0".to_string()),
        ("white-list", "false".to_string()),
    ];
    let mut lines: Vec<String> = existing
        .map(|text| text.lines().map(String::from).collect())
        .unwrap_or_default();
    for (key, value) in forced {
        let line = format!("{key}={value}");
        match lines
            .iter()
            .position(|l| l.split_once('=').is_some_and(|(k, _)| k.trim() == key))
        {
            Some(i) => lines[i] = line,
            None => lines.push(line),
        }
    }
    if existing.is_none() {
        lines.insert(
            0,
            "# Written by the NetherForge editor; edit freely (the port comes from Settings)"
                .into(),
        );
        for (key, value) in defaults {
            let present = lines
                .iter()
                .any(|l| l.split_once('=').is_some_and(|(k, _)| k.trim() == key));
            if !present {
                lines.push(format!("{key}={value}"));
            }
        }
    }
    let mut text = lines.join("\n");
    text.push('\n');
    text
}

/// Java properties files are ISO-8859-1 with `\uXXXX` escapes.
fn escape_property(value: &str) -> String {
    value
        .chars()
        .map(|c| match c {
            '\\' => "\\\\".to_string(),
            '\n' | '\r' => " ".to_string(),
            c if (c as u32) < 0x20 || (c as u32) > 0x7e => {
                let mut units = [0u16; 2];
                c.encode_utf16(&mut units)
                    .iter()
                    .map(|u| format!("\\u{u:04x}"))
                    .collect()
            }
            c => c.to_string(),
        })
        .collect()
}

pub fn write_properties(dir: &Path, port: u16, motd: &str) -> Result<()> {
    let path = dir.join("server.properties");
    let existing = std::fs::read_to_string(&path).ok();
    let whitelist = std::fs::read_to_string(dir.join("whitelist.json")).ok();
    write_atomic(
        &path,
        properties(
            existing.as_deref(),
            port,
            motd,
            whitelist_is_empty(whitelist.as_deref()),
        )
        .as_bytes(),
    )
}

/// What `bukkit.yml` names to have a world generated by a plugin: ours.
const GENERATOR: &str = "NetherForge";

/// The main world's name: `level-name` in `server.properties`, or the
/// server's own default.
fn level_name(properties: Option<&str>) -> String {
    properties
        .into_iter()
        .flat_map(str::lines)
        .filter_map(|l| l.split_once('='))
        .find(|(k, _)| k.trim() == "level-name")
        .map(|(_, v)| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "world".into())
}

/// Whether the project's `netherforge.json` names a terrain for world
/// [world] (`worlds.<world>.terrain`), read leniently: which terrain,
/// and whether the project has it, is the plugin's to say.
fn names_terrain(manifest: Option<&[u8]>, world: &str) -> bool {
    manifest
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(bytes).ok())
        .and_then(|m| {
            m.get("worlds")?
                .get(world)?
                .get("terrain")?
                .as_str()
                .map(|g| !g.trim().is_empty())
        })
        .unwrap_or(false)
}

/// `bukkit.yml` with the main world [world] asking NetherForge for its
/// generator when [wanted] and not when not, or None when the file stays as
/// it is (it already says so, or isn't YAML we can read, which the server
/// will say itself). Only that one key is ours: every other setting is left
/// alone, and so is another plugin's generator for a world the project names
/// none for. The server writes its own defaults around what's there.
pub fn bukkit(existing: Option<&str>, world: &str, wanted: bool) -> Option<String> {
    use serde_norway::{Mapping, Value};
    let mut root = match existing.filter(|t| !t.trim().is_empty()) {
        Some(text) => match serde_norway::from_str::<Value>(text).ok()? {
            Value::Mapping(map) => map,
            Value::Null => Mapping::new(),
            _ => return None,
        },
        None => Mapping::new(),
    };
    let current = root
        .get("worlds")
        .and_then(|w| w.get(world))
        .and_then(|w| w.get("generator"))
        .and_then(Value::as_str);
    let ours = current.is_some_and(|g| {
        g == GENERATOR
            || g.strip_prefix(GENERATOR)
                .is_some_and(|id| id.starts_with(':'))
    });
    if wanted == ours {
        return None;
    }
    if wanted {
        let worlds = root
            .entry("worlds".into())
            .or_insert_with(|| Value::Mapping(Mapping::new()));
        if !worlds.is_mapping() {
            *worlds = Value::Mapping(Mapping::new());
        }
        let entry = worlds
            .as_mapping_mut()?
            .entry(world.into())
            .or_insert_with(|| Value::Mapping(Mapping::new()));
        if !entry.is_mapping() {
            *entry = Value::Mapping(Mapping::new());
        }
        entry
            .as_mapping_mut()?
            .insert("generator".into(), GENERATOR.into());
    } else {
        let worlds = root.get_mut("worlds")?.as_mapping_mut()?;
        let entry = worlds.get_mut(world)?.as_mapping_mut()?;
        entry.remove("generator");
        if entry.is_empty() {
            worlds.remove(world);
        }
        if worlds.is_empty() {
            root.remove("worlds");
        }
    }
    serde_norway::to_string(&Value::Mapping(root)).ok()
}

/// Makes the dev server's `bukkit.yml` ask NetherForge for the main world's
/// generator exactly when the project names one (see [bukkit]). The server
/// asks only as it starts, so naming one, or another, takes a restart, which
/// the plugin asks for.
pub fn write_main_world_generator(dir: &Path, project_root: &Path) -> Result<()> {
    let properties = std::fs::read_to_string(dir.join("server.properties")).ok();
    let world = level_name(properties.as_deref());
    let manifest = std::fs::read(project_root.join(crate::project::MANIFEST)).ok();
    let wanted = names_terrain(manifest.as_deref(), &world);
    let path = dir.join("bukkit.yml");
    let existing = std::fs::read_to_string(&path).ok();
    match bukkit(existing.as_deref(), &world, wanted) {
        Some(text) => write_atomic(&path, text.as_bytes()),
        None => Ok(()),
    }
}

/// True when `whitelist.json` is missing, unreadable or lists nobody.
fn whitelist_is_empty(text: Option<&str>) -> bool {
    match text.map(serde_json::from_str::<serde_json::Value>) {
        Some(Ok(serde_json::Value::Array(entries))) => entries.is_empty(),
        Some(Ok(_)) => false,
        _ => true,
    }
}

/// Copies [plugin] into `plugins/`, removing any other NetherForge jar there:
/// another version's, another build's, or an older build's bots companion.
pub fn install_plugin(dir: &Path, plugin: &PluginJar) -> Result<()> {
    let plugins = dir.join("plugins");
    std::fs::create_dir_all(&plugins)?;
    let name = plugin
        .path
        .file_name()
        .context(|| "The plugin jar has no name".into())?
        .to_os_string();
    for entry in std::fs::read_dir(&plugins)?.flatten() {
        let other = entry.file_name();
        if other != name && is_ours(&other.to_string_lossy()) {
            std::fs::remove_file(entry.path())?;
        }
    }
    let bytes = std::fs::read(&plugin.path)
        .context(|| format!("Couldn't read {}", plugin.path.display()))?;
    write_atomic(&plugins.join(&name), &bytes)?;
    Ok(())
}

/// `-Xmx… --enable-native-access=ALL-UNNAMED <extra> -Dnetherforge.project=… -Dnetherforge.packages=… -Dnetherforge.bridge.port=… -Dnetherforge.dev=true -jar <cached paper> --nogui`
///
/// `netherforge.packages` is the package cache ([AppDirs::packages]), where
/// the plugin finds the checkouts of the git packages the lock pins (the
/// editor fetched them; the server never fetches).
///
/// Native access is for the plugin's Lua 5.4, which is a JNI library; without
/// the flag Java 25 prints a warning into the console every time it loads.
/// [paper_jar] is the editor's cached download: Paper unpacks itself into the
/// working directory (the server folder) and checks those files' hashes.
pub fn java_args(
    settings: &Settings,
    project_root: &Path,
    package_cache: &Path,
    paper_jar: &Path,
    bridge_port: u16,
) -> Vec<String> {
    let mut args = vec![
        format!("-Xmx{}M", settings.server_memory_mb),
        "--enable-native-access=ALL-UNNAMED".into(),
    ];
    args.extend(settings.server_jvm_args.iter().cloned());
    args.push(super::leftover::project_arg(project_root));
    args.push(format!(
        "-Dnetherforge.packages={}",
        package_cache.display()
    ));
    args.push(format!("-Dnetherforge.bridge.port={bridge_port}"));
    args.push("-Dnetherforge.dev=true".into());
    args.extend([
        "-jar".into(),
        paper_jar.display().to_string(),
        "--nogui".into(),
    ]);
    args
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_new_properties() {
        let text = properties(None, 25570, "NetherForge: Basic example", true);
        assert!(text.contains("\nserver-port=25570\n"));
        assert!(text.contains("\nwhite-list=false\n"));
        assert_eq!(text.matches("white-list=").count(), 1);
        assert!(text.contains("\nonline-mode=true\n"));
        assert!(text.contains("\nmotd=NetherForge: Basic example\n"));
    }

    #[test]
    fn keeps_user_edits_and_updates_the_port() {
        let existing = "#Minecraft server properties\nserver-port=25565\nonline-mode=false\nmotd=Mine\nview-distance=6\n";
        let text = properties(Some(existing), 25599, "ignored", false);
        assert_eq!(
            text,
            "#Minecraft server properties\nserver-port=25599\nonline-mode=false\nmotd=Mine\nview-distance=6\n"
        );
        let without_port = properties(Some("view-distance=6\n"), 25565, "x", false);
        assert_eq!(without_port, "view-distance=6\nserver-port=25565\n");
    }

    #[test]
    fn turns_off_a_whitelist_nobody_is_on() {
        // What Minecraft 26.3 writes on first boot: whitelist on, nobody on it.
        let existing = "#Minecraft server properties\nwhite-list=true\nserver-port=25565\n";
        let text = properties(Some(existing), 25565, "x", whitelist_is_empty(Some("[]")));
        assert_eq!(
            text,
            "#Minecraft server properties\nwhite-list=false\nserver-port=25565\n"
        );
        assert!(whitelist_is_empty(None));
        assert!(whitelist_is_empty(Some("not json")));

        // Somebody filled it in: that's a choice, so it stays on.
        let filled = r#"[{"uuid":"6f1c2a0e-4b7d-4c43-9a51-0d2f6f6a4f10","name":"Steve"}]"#;
        assert!(!whitelist_is_empty(Some(filled)));
        let kept = properties(Some(existing), 25565, "x", whitelist_is_empty(Some(filled)));
        assert!(kept.contains("\nwhite-list=true\n"));
    }

    #[test]
    fn asks_netherforge_for_the_main_world_only_when_the_project_names_a_terrain() {
        let manifest = br#"{"worlds":{"world":{"terrain":"ruby_hills"},"lobby":{"spawnLimits":{"monster":0}}}}"#;
        assert!(names_terrain(Some(manifest), "world"));
        assert!(!names_terrain(Some(manifest), "lobby"));
        assert!(!names_terrain(Some(b"not json"), "world"));
        assert!(!names_terrain(None, "world"));
        assert_eq!(
            level_name(Some("motd=x\nlevel-name=survival\n")),
            "survival"
        );
        assert_eq!(level_name(Some("motd=x\n")), "world");

        // A new server folder: just the key, the server writes its defaults around it.
        let fresh = bukkit(None, "world", true).unwrap();
        assert_eq!(fresh, "worlds:\n  world:\n    generator: NetherForge\n");
        // Said already, or nothing to say: the file isn't touched.
        assert_eq!(bukkit(Some(&fresh), "world", true), None);
        assert_eq!(
            bukkit(Some("settings:\n  allow-end: true\n"), "world", false),
            None
        );

        // What the server wrote stays; the key goes in beside it, and out again with the sections it made.
        let written =
            "settings:\n  allow-end: true\nworlds:\n  world_nether:\n    generator: Other\n";
        let with = bukkit(Some(written), "world", true).unwrap();
        assert!(with.contains("allow-end: true"), "{with}");
        assert!(
            with.contains("world_nether:\n    generator: Other"),
            "{with}"
        );
        assert!(
            with.contains("  world:\n    generator: NetherForge"),
            "{with}"
        );
        assert_eq!(bukkit(Some(&with), "world", false).unwrap(), written);
        let without = bukkit(Some(&fresh), "world", false).unwrap();
        assert!(!without.contains("worlds"), "{without}");

        // Another plugin's generator for a main world the project names none for is the user's.
        let theirs = "worlds:\n  world:\n    generator: Terra\n";
        assert_eq!(bukkit(Some(theirs), "world", false), None);
        assert_eq!(
            bukkit(
                Some("worlds:\n  world:\n    generator: NetherForgeX\n"),
                "world",
                false
            ),
            None
        );
        // A file the server couldn't read either is left for it to say so.
        assert_eq!(bukkit(Some("worlds: [unclosed"), "world", true), None);
    }

    #[test]
    fn prepares_the_main_world_generator_from_the_project() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("server");
        let project = tmp.path().join("project");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(dir.join("server.properties"), "level-name=realm\n").unwrap();
        std::fs::write(
            project.join("netherforge.json"),
            r#"{"worlds":{"realm":{"terrain":"hills"}}}"#,
        )
        .unwrap();
        write_main_world_generator(&dir, &project).unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.join("bukkit.yml")).unwrap(),
            "worlds:\n  realm:\n    generator: NetherForge\n"
        );
        std::fs::write(project.join("netherforge.json"), r#"{"name":"x"}"#).unwrap();
        write_main_world_generator(&dir, &project).unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.join("bukkit.yml")).unwrap(),
            "{}\n"
        );
    }

    #[test]
    fn escapes_non_ascii_motd() {
        assert_eq!(escape_property("Café ✓"), "Caf\\u00e9 \\u2713");
        assert_eq!(escape_property("a\nb"), "a b");
    }

    #[test]
    fn installs_the_plugin_and_builds_the_command_line() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("servers/0123456789abcdef");
        std::fs::create_dir_all(dir.join("plugins")).unwrap();
        std::fs::write(dir.join("plugins/NetherForge-0.0.9-paper-26.3.jar"), "old").unwrap();
        std::fs::write(
            dir.join("plugins/NetherForgeBots-0.0.9-paper-26.3.jar"),
            "old",
        )
        .unwrap();
        std::fs::write(dir.join("plugins/OtherPlugin.jar"), "keep").unwrap();
        let jar = tmp.path().join("NetherForge-0.1.0-paper-26.3.jar");
        std::fs::write(&jar, "new").unwrap();
        // The old build and its bots companion go; another plugin stays.
        install_plugin(&dir, &plugin_jar(&jar)).unwrap();
        assert_eq!(
            jar_names(&dir.join("plugins")),
            ["NetherForge-0.1.0-paper-26.3.jar", "OtherPlugin.jar"]
        );

        let settings = Settings {
            server_jvm_args: vec!["-XX:+UseZGC".into()],
            server_memory_mb: 3072,
            server_port: 25565,
            ..Settings::default()
        };
        let paper = Path::new("/data/paper/paper-26.3-143.jar");
        let args = java_args(
            &settings,
            Path::new("/p"),
            Path::new("/data/packages"),
            paper,
            41234,
        );
        assert_eq!(
            args,
            [
                "-Xmx3072M",
                "--enable-native-access=ALL-UNNAMED",
                "-XX:+UseZGC",
                &format!("-Dnetherforge.project={}", Path::new("/p").display()),
                &format!(
                    "-Dnetherforge.packages={}",
                    Path::new("/data/packages").display()
                ),
                "-Dnetherforge.bridge.port=41234",
                "-Dnetherforge.dev=true",
                "-jar",
                &paper.display().to_string(),
                "--nogui"
            ]
        );
    }

    fn plugin_jar(path: &Path) -> PluginJar {
        PluginJar {
            minecraft: "26.3".into(),
            plugin_version: "0.1.0".into(),
            path: path.to_path_buf(),
        }
    }

    fn jar_names(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .filter(|name| name.ends_with(".jar"))
            .collect();
        names.sort();
        names
    }

    /// A project that ships the old in-project server folder, with its own
    /// Paper (and the marker that once made the editor keep it) and plugins,
    /// gets none of it run: the server folder is elsewhere, Paper comes from
    /// the cache, and the project's folder is left as it was.
    #[test]
    fn nothing_in_the_project_reaches_the_server() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = crate::app::dirs::AppDirs::in_one(&tmp.path().join("data"));
        let project = tmp.path().join("project");
        let shipped = project.join(".netherforge/server");
        std::fs::create_dir_all(shipped.join("plugins")).unwrap();
        std::fs::write(shipped.join("paper.jar"), "evil paper").unwrap();
        std::fs::write(shipped.join(".paper-build"), "paper-26.3-143.jar").unwrap();
        std::fs::write(shipped.join("plugins/Evil.jar"), "evil").unwrap();
        std::fs::write(
            shipped.join("plugins/NetherForge-0.1.0-paper-26.3.jar"),
            "evil",
        )
        .unwrap();

        let cached = dirs.paper().join("paper-26.3-143.jar");
        std::fs::create_dir_all(cached.parent().unwrap()).unwrap();
        std::fs::write(&cached, "paper").unwrap();
        let plugin = tmp.path().join("build/NetherForge-0.1.0-paper-26.3.jar");
        std::fs::create_dir_all(plugin.parent().unwrap()).unwrap();
        std::fs::write(&plugin, "ours").unwrap();

        let dir = dirs.server_dir(&project);
        prepare(&dir, &project, &plugin_jar(&plugin), 25565, "x").unwrap();
        let args = java_args(&Settings::default(), &project, &dirs.packages(), &cached, 1);

        assert!(dir.starts_with(dirs.servers()) && !dir.starts_with(&project));
        let jar = &args[args.iter().position(|a| a == "-jar").unwrap() + 1];
        assert_eq!(Path::new(jar), cached, "Paper runs from the cache");
        assert!(!dir.join("paper.jar").exists());
        assert_eq!(
            jar_names(&dir.join("plugins")),
            ["NetherForge-0.1.0-paper-26.3.jar"]
        );
        assert_eq!(
            std::fs::read_to_string(dir.join("plugins/NetherForge-0.1.0-paper-26.3.jar")).unwrap(),
            "ours"
        );
        assert!(
            std::fs::read_to_string(dir.join(PROJECT_FILE))
                .unwrap()
                .starts_with(&project.display().to_string())
        );
        // The project's own copy is untouched, and unused.
        assert_eq!(
            std::fs::read_to_string(shipped.join("paper.jar")).unwrap(),
            "evil paper"
        );
        assert_eq!(jar_names(&shipped.join("plugins")).len(), 2);
    }
}
