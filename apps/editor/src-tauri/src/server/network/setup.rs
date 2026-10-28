//! A dev network's folders: the proxy's (Velocity's `velocity.toml`, its
//! forwarding secret, the hub's `hub.properties` and the proxy plugin) and
//! what puts a Paper server behind it (`config/paper-global.yml`'s Velocity
//! section, `server.properties`' `online-mode` and `server-ip`), or takes it
//! out again when the project's server runs alone.
//!
//! Like a server folder, everything here comes from the editor: Velocity from
//! the cache, the plugin from the editor's build. The secrets are made here
//! once and kept in the proxy's folder, so a world's players and the hub's
//! shared maps (`plugins/netherforge/maps.json`) survive a restart.

use std::path::Path;

use crate::app::plugins::{ProxyJar, is_ours};
use crate::error::{Context, Result};
use crate::fs::atomic::write_atomic;

/// What Velocity reads its forwarding secret from, in its folder.
pub const FORWARDING_SECRET: &str = "forwarding.secret";
/// The proxy plugin's folder in Velocity's `plugins/` (its id).
const HUB_FOLDER: &str = "netherforge";
const HUB_FILE: &str = "hub.properties";
/// In a Paper server's folder while the editor has it behind a dev network's proxy.
pub const MEMBER_MARKER: &str = "netherforge-network.txt";

/// The secrets a dev network's servers share with its proxy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Secrets {
    /// Velocity's modern forwarding secret, which each Paper server checks.
    pub forwarding: String,
    /// The hub's, which each NetherForge server proves it knows.
    pub hub: String,
}

/// What the proxy is told.
pub struct ProxyPlan<'a> {
    /// Where players join: Settings' server port, on every interface.
    pub port: u16,
    /// Where the hub listens, on 127.0.0.1.
    pub hub_port: u16,
    /// Each server's name and port on 127.0.0.1; players join the first.
    pub servers: &'a [(String, u16)],
    pub motd: &'a str,
}

/// Where the hub's secret is kept in the proxy's folder; `hub.properties` gets a copy each start.
const HUB_SECRET: &str = "netherforge-hub.secret";

/// The network's secrets, kept in the proxy's folder [dir] (made the first time).
pub fn secrets(dir: &Path) -> Result<Secrets> {
    std::fs::create_dir_all(dir).context(|| format!("Couldn't create {}", dir.display()))?;
    Ok(Secrets {
        forwarding: keep_or_make_secret(&dir.join(FORWARDING_SECRET))?,
        hub: keep_or_make_secret(&dir.join(HUB_SECRET))?,
    })
}

/// Fills the proxy's folder for a start: `velocity.toml`, the hub's
/// `hub.properties` with [secrets]' hub secret, and the proxy plugin.
pub fn proxy(dir: &Path, plugin: &ProxyJar, plan: &ProxyPlan, secrets: &Secrets) -> Result<()> {
    std::fs::create_dir_all(dir).context(|| format!("Couldn't create {}", dir.display()))?;
    let hub_file = dir.join("plugins").join(HUB_FOLDER).join(HUB_FILE);
    let existing = std::fs::read_to_string(&hub_file).ok();
    std::fs::create_dir_all(hub_file.parent().unwrap_or(dir))?;
    write_atomic(
        &hub_file,
        hub_properties(existing.as_deref(), plan.hub_port, &secrets.hub).as_bytes(),
    )?;
    write_atomic(&dir.join("velocity.toml"), velocity_toml(plan).as_bytes())?;
    install_proxy_plugin(dir, plugin)
}

/// The secret in [file], or a new one written there.
fn keep_or_make_secret(file: &Path) -> Result<String> {
    if let Some(secret) = std::fs::read_to_string(file)
        .ok()
        .map(|text| text.trim().to_string())
        .filter(|text| text.len() >= 16)
    {
        return Ok(secret);
    }
    let secret = random_secret();
    write_atomic(file, secret.as_bytes())?;
    Ok(secret)
}

fn random_secret() -> String {
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).expect("the OS random number generator failed");
    hex::encode(bytes)
}

/// [text] (a `.properties` file, or nothing) with each of [forced] set, the
/// rest kept as it was.
pub fn set_properties(text: Option<&str>, forced: &[(&str, String)]) -> String {
    let mut lines: Vec<String> = text
        .map(|text| text.lines().map(String::from).collect())
        .unwrap_or_default();
    for (key, value) in forced {
        let line = format!("{key}={value}");
        match lines.iter().position(|l| {
            !l.trim_start().starts_with('#')
                && l.split_once('=').is_some_and(|(k, _)| k.trim() == *key)
        }) {
            Some(i) => lines[i] = line,
            None => lines.push(line),
        }
    }
    let mut text = lines.join("\n");
    text.push('\n');
    text
}

/// `hub.properties`: the hub on 127.0.0.1 at [port] with [secret]; the
/// limits as the user left them (the plugin's defaults when absent).
fn hub_properties(existing: Option<&str>, port: u16, secret: &str) -> String {
    let header = "# The NetherForge editor's dev network hub. bind, port and secret are the editor's (set each start); the limits are yours.";
    let base = existing
        .map(String::from)
        .unwrap_or_else(|| format!("{header}\n"));
    set_properties(
        Some(&base),
        &[
            ("bind", "127.0.0.1".into()),
            ("port", port.to_string()),
            ("secret", secret.into()),
        ],
    )
}

/// TOML's basic string.
fn toml_string(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 || c == '\u{7f}' => {
                out.push_str(&format!("\\u{:04x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `velocity.toml`, the editor's every start: only what a dev network needs,
/// Velocity's defaults for the rest. Players join on [ProxyPlan::port] with
/// their accounts (online mode) and are forwarded to the servers the modern
/// way, so each server sees them as they are.
pub fn velocity_toml(plan: &ProxyPlan) -> String {
    let mut text = String::from(
        "# Written by the NetherForge editor every time the dev network starts: edits here don't last.\n\
         config-version = \"2.9\"\n",
    );
    text.push_str(&format!("bind = \"0.0.0.0:{}\"\n", plan.port));
    text.push_str(&format!("motd = {}\n", toml_string(plan.motd)));
    text.push_str("online-mode = true\n");
    text.push_str("player-info-forwarding-mode = \"modern\"\n");
    text.push_str(&format!(
        "forwarding-secret-file = \"{FORWARDING_SECRET}\"\n"
    ));
    text.push_str("\n[servers]\n");
    for (name, port) in plan.servers {
        text.push_str(&format!("{} = \"127.0.0.1:{port}\"\n", toml_string(name)));
    }
    let first = plan
        .servers
        .first()
        .map(|(name, _)| toml_string(name))
        .unwrap_or_default();
    text.push_str(&format!("try = [{first}]\n"));
    text.push_str("\n[forced-hosts]\n");
    text
}

/// Copies the proxy plugin into Velocity's `plugins/`, removing any other
/// NetherForge jar there.
fn install_proxy_plugin(dir: &Path, plugin: &ProxyJar) -> Result<()> {
    let plugins = dir.join("plugins");
    std::fs::create_dir_all(&plugins)?;
    let name = plugin
        .path
        .file_name()
        .context(|| "The proxy plugin jar has no name".into())?
        .to_os_string();
    for entry in std::fs::read_dir(&plugins)?.flatten() {
        let other = entry.file_name();
        if other != name && is_ours(&other.to_string_lossy()) {
            std::fs::remove_file(entry.path())?;
        }
    }
    let bytes = std::fs::read(&plugin.path)
        .context(|| format!("Couldn't read {}", plugin.path.display()))?;
    write_atomic(&plugins.join(&name), &bytes)
}

/// Puts the Paper server in [dir] behind a dev network's proxy ([forwarding]
/// is the proxy's secret), or, with None, takes it out again if the editor
/// had put it there. Behind the proxy it listens on 127.0.0.1 only, in
/// offline mode (the proxy logs players in) with Velocity's forwarding on;
/// out again, `online-mode` is back on and `server-ip` empty. A server the
/// editor never put in a network is left as the user made it.
pub fn paper_member(dir: &Path, forwarding: Option<&str>) -> Result<()> {
    let marker = dir.join(MEMBER_MARKER);
    let member = forwarding.is_some();
    if !member && !marker.exists() {
        return Ok(());
    }
    let properties = dir.join("server.properties");
    let existing = std::fs::read_to_string(&properties).ok();
    let forced: [(&str, String); 2] = if member {
        [
            ("online-mode", "false".into()),
            ("server-ip", "127.0.0.1".into()),
        ]
    } else {
        [("online-mode", "true".into()), ("server-ip", String::new())]
    };
    write_atomic(
        &properties,
        set_properties(existing.as_deref(), &forced).as_bytes(),
    )?;

    let global = dir.join("config").join("paper-global.yml");
    let existing = std::fs::read_to_string(&global).ok();
    if let Some(text) = paper_global(existing.as_deref(), forwarding) {
        std::fs::create_dir_all(dir.join("config"))?;
        write_atomic(&global, text.as_bytes())?;
    }
    if member {
        write_atomic(
            &marker,
            b"The NetherForge editor runs this server behind its dev network's proxy.\n",
        )
    } else {
        std::fs::remove_file(&marker).or_else(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                Ok(())
            } else {
                Err(e)
            }
        })?;
        Ok(())
    }
}

/// `paper-global.yml` with `proxies.velocity` on with [forwarding] (or off,
/// with None), or None when it already says so or isn't YAML we can read.
/// Only that section is ours; Paper writes its defaults around it.
pub fn paper_global(existing: Option<&str>, forwarding: Option<&str>) -> Option<String> {
    use serde_norway::{Mapping, Value};
    let mut root = match existing.filter(|t| !t.trim().is_empty()) {
        Some(text) => match serde_norway::from_str::<Value>(text).ok()? {
            Value::Mapping(map) => map,
            Value::Null => Mapping::new(),
            _ => return None,
        },
        None => Mapping::new(),
    };
    let velocity = root
        .get("proxies")
        .and_then(|p| p.get("velocity"))
        .and_then(Value::as_mapping);
    let enabled = velocity
        .and_then(|v| v.get("enabled"))
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let secret = velocity
        .and_then(|v| v.get("secret"))
        .and_then(Value::as_str);
    let online = velocity
        .and_then(|v| v.get("online-mode"))
        .and_then(Value::as_bool)
        .unwrap_or(false);
    match forwarding {
        Some(wanted) if enabled && online && secret == Some(wanted) => return None,
        None if !enabled => return None,
        _ => {}
    }
    let proxies = root
        .entry("proxies".into())
        .or_insert_with(|| Value::Mapping(Mapping::new()));
    if !proxies.is_mapping() {
        *proxies = Value::Mapping(Mapping::new());
    }
    let velocity = proxies
        .as_mapping_mut()?
        .entry("velocity".into())
        .or_insert_with(|| Value::Mapping(Mapping::new()));
    if !velocity.is_mapping() {
        *velocity = Value::Mapping(Mapping::new());
    }
    let velocity = velocity.as_mapping_mut()?;
    velocity.insert("enabled".into(), forwarding.is_some().into());
    velocity.insert("online-mode".into(), true.into());
    velocity.insert("secret".into(), forwarding.unwrap_or("").into());
    serde_norway::to_string(&Value::Mapping(root)).ok()
}

/// A port on 127.0.0.1 nothing listens on now: for the hub and each server
/// behind the proxy, which nobody joins directly.
pub fn free_port() -> Result<u16> {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0))
        .map_err(|e| crate::error::Error::msg(format!("Couldn't find a free port: {e}")))?;
    Ok(listener.local_addr()?.port())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan(servers: &[(String, u16)]) -> ProxyPlan<'_> {
        ProxyPlan {
            port: 25565,
            hub_port: 40100,
            servers,
            motd: "NetherForge \"dev\" network",
        }
    }

    #[test]
    fn velocity_is_told_the_servers_and_forwards_players() {
        let servers = vec![("main".to_string(), 40001), ("lobby".to_string(), 40002)];
        let text = velocity_toml(&plan(&servers));
        assert!(text.contains("bind = \"0.0.0.0:25565\"\n"), "{text}");
        assert!(
            text.contains("motd = \"NetherForge \\\"dev\\\" network\"\n"),
            "{text}"
        );
        assert!(text.contains("player-info-forwarding-mode = \"modern\"\n"));
        assert!(text.contains("[servers]\n\"main\" = \"127.0.0.1:40001\"\n\"lobby\" = \"127.0.0.1:40002\"\ntry = [\"main\"]\n"), "{text}");
        assert!(text.ends_with("[forced-hosts]\n"));
    }

    #[test]
    fn fills_the_proxy_folder_and_keeps_its_secrets() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("proxy");
        let jar = tmp.path().join("NetherForge-0.1.0-velocity.jar");
        std::fs::write(&jar, "jar").unwrap();
        let plugin = ProxyJar {
            plugin_version: "0.1.0".into(),
            path: jar,
        };
        std::fs::create_dir_all(dir.join("plugins")).unwrap();
        std::fs::write(dir.join("plugins/NetherForge-0.0.9-velocity.jar"), "old").unwrap();
        std::fs::write(dir.join("plugins/LuckPerms.jar"), "theirs").unwrap();
        let servers = vec![("main".to_string(), 40001)];

        let secrets = secrets(&dir).unwrap();
        proxy(&dir, &plugin, &plan(&servers), &secrets).unwrap();
        assert_eq!(secrets.forwarding.len(), 48);
        assert_ne!(secrets.forwarding, secrets.hub);
        assert_eq!(
            std::fs::read_to_string(dir.join(FORWARDING_SECRET)).unwrap(),
            secrets.forwarding
        );
        let hub = std::fs::read_to_string(dir.join("plugins/netherforge/hub.properties")).unwrap();
        assert!(hub.contains("\nbind=127.0.0.1\nport=40100\n"), "{hub}");
        assert!(hub.contains(&format!("secret={}\n", secrets.hub)));
        assert!(dir.join("plugins/NetherForge-0.1.0-velocity.jar").is_file());
        assert!(!dir.join("plugins/NetherForge-0.0.9-velocity.jar").exists());
        assert!(dir.join("plugins/LuckPerms.jar").exists());

        // The user's limits stay; the port moves; the secrets are the same next time.
        std::fs::write(
            dir.join("plugins/netherforge/hub.properties"),
            format!("{hub}max-value-bytes=1000\n"),
        )
        .unwrap();
        let next = ProxyPlan {
            hub_port: 40200,
            ..plan(&servers)
        };
        assert_eq!(super::secrets(&dir).unwrap(), secrets);
        proxy(&dir, &plugin, &next, &secrets).unwrap();
        let hub = std::fs::read_to_string(dir.join("plugins/netherforge/hub.properties")).unwrap();
        assert!(
            hub.contains("port=40200\n") && hub.contains("max-value-bytes=1000\n"),
            "{hub}"
        );
    }

    #[test]
    fn puts_paper_behind_the_proxy_and_takes_it_out_again() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path();
        std::fs::write(
            dir.join("server.properties"),
            "server-port=40001\nonline-mode=true\nview-distance=6\n",
        )
        .unwrap();
        // Never in a network: nothing to undo.
        paper_member(dir, None).unwrap();
        assert!(!dir.join("config").exists());

        paper_member(dir, Some("s3cret")).unwrap();
        let properties = std::fs::read_to_string(dir.join("server.properties")).unwrap();
        assert_eq!(
            properties,
            "server-port=40001\nonline-mode=false\nview-distance=6\nserver-ip=127.0.0.1\n"
        );
        let global = std::fs::read_to_string(dir.join("config/paper-global.yml")).unwrap();
        assert_eq!(
            global,
            "proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: s3cret\n"
        );
        assert!(dir.join(MEMBER_MARKER).exists());

        paper_member(dir, None).unwrap();
        let properties = std::fs::read_to_string(dir.join("server.properties")).unwrap();
        assert_eq!(
            properties,
            "server-port=40001\nonline-mode=true\nview-distance=6\nserver-ip=\n"
        );
        let global = std::fs::read_to_string(dir.join("config/paper-global.yml")).unwrap();
        assert!(global.contains("enabled: false"), "{global}");
        assert!(!dir.join(MEMBER_MARKER).exists());
    }

    #[test]
    fn edits_only_paper_globals_velocity_section() {
        let written = "_version: 31\nproxies:\n  bungee-cord:\n    online-mode: true\n  velocity:\n    enabled: false\n    online-mode: true\n    secret: ''\nscoreboards:\n  track-plugin-scoreboards: false\n";
        let on = paper_global(Some(written), Some("abc")).unwrap();
        assert!(on.contains("_version: 31"), "{on}");
        assert!(on.contains("bungee-cord:\n    online-mode: true"), "{on}");
        assert!(
            on.contains("velocity:\n    enabled: true\n    online-mode: true\n    secret: abc"),
            "{on}"
        );
        assert!(on.contains("track-plugin-scoreboards: false"), "{on}");
        assert_eq!(paper_global(Some(&on), Some("abc")), None);
        assert!(paper_global(Some(&on), Some("other")).is_some());
        assert_eq!(paper_global(Some(written), None), None);
        assert_eq!(paper_global(Some("- a list"), Some("abc")), None);
    }

    #[test]
    fn sets_properties_and_keeps_the_rest() {
        assert_eq!(
            set_properties(
                Some("# a=1\na=2\nb=3"),
                &[("a", "4".into()), ("c", "5".into())]
            ),
            "# a=1\na=4\nb=3\nc=5\n"
        );
        assert_eq!(set_properties(None, &[("a", "1".into())]), "a=1\n");
    }
}
