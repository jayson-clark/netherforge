//! Server-owner settings on the Rust runtime (Kotlin's `SettingsTest`): `nf.config` reads the owner's value or the
//! default, a change is written to the owner's file and heard by the package's scripts that listen, and restarts
//! those that only read it; what the file holds that doesn't fit is a problem and never used.

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::session::{Config, Session};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.lines.borrow_mut().push(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.lines.borrow_mut().push(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.lines.borrow_mut().push(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
}

/// No structure has put a marker in the world.
impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
}

struct Files {
    names: &'static BTreeSet<String>,
    texts: std::collections::BTreeMap<String, String>,
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
}

const MANIFEST: &str = r#"{
  "formatVersion": 1, "name": "Arena", "namespace": "arena", "version": "1.0.0", "minecraft": "26.3",
  "settings": {
    "difficulty": { "type": "choice", "description": "How hard.", "default": "normal", "choices": ["easy", "normal", "hard"] },
    "max_players": { "type": "integer", "description": "How many.", "default": 8, "min": 2, "max": 16 },
    "pvp": { "type": "boolean", "description": "Whether PvP.", "default": true },
    "rate": { "type": "number", "description": "How fast.", "default": 1.5 }
  }
}"#;

/// Module `reader` prints what it read as it starts; `listener` hears changes.
fn project() -> Files {
    let files = [
        ("netherforge.json".to_owned(), MANIFEST.to_owned()),
        (
            "modules/reader/init.lua".to_owned(),
            r#"print("read", nf.config("difficulty"), nf.config("max_players"), nf.config("pvp"), nf.config("rate"))
print(pcall(nf.config, "nope"))"#
                .to_owned(),
        ),
        (
            "modules/listener/init.lua".to_owned(),
            r#"print("listening to", nf.config("max_players"))
nf.on("setting_changed", function(e) print("changed", e.setting, e.value, e.previous) end)"#
                .to_owned(),
        ),
    ];
    Files {
        names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
        texts: files.into_iter().collect(),
    }
}

fn start<'a>(files: &'a Files, platform: &std::rc::Rc<Fake>, dir: &std::path::Path) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            settings_dir: Some(dir.to_path_buf()),
            ..Config::default()
        },
    )
}

/// What scripts printed (by module) and what was logged as a warning, or about settings.
fn lines(platform: &Fake) -> Vec<String> {
    let mut lines = Vec::new();
    for line in std::mem::take(&mut *platform.lines.borrow_mut()) {
        if let Some(rest) = line.strip_prefix("info [module ") {
            let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
            lines.push(text.replace('\t', " "));
        } else if line.starts_with("warn")
            || line.contains("Setting ")
            || line.contains("Restarting")
        {
            lines.push(line);
        }
    }
    lines
}

fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = lines(platform);
    let started = Instant::now();
    while seen.len() < count && started.elapsed() < Duration::from_secs(5) {
        session.tick();
        seen.extend(lines(platform));
    }
    seen
}

#[test]
fn a_script_reads_the_owners_value_or_the_default() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("arena.json"),
        r#"{ "max_players": 12, "pvp": "yes", "colour": "red" }"#,
    )
    .unwrap();
    let files = project();
    let platform = std::rc::Rc::new(Fake::default());
    let session = start(&files, &platform, dir.path());
    let seen = printed(&session, &platform, 5);
    let file = dir.path().join("arena.json").display().to_string();
    assert_eq!(
        seen,
        [
            format!("warn {file}: pvp: \"yes\" isn't true or false; it has its default"),
            format!("warn {file}: colour: there's no setting \"colour\""),
            "listener] listening to 12".to_owned(),
            "reader] read normal 12 true 1.5".to_owned(),
            "reader] false modules/reader/init.lua:2: there's no setting \"nope\" in netherforge.json's settings (it has difficulty, max_players, pvp, rate)".to_owned(),
        ]
    );
    let mut problems: Vec<(Option<String>, Option<String>)> = session
        .problems()
        .into_iter()
        .filter(|p| {
            p.code
                .as_deref()
                .is_some_and(|c| c.starts_with("settings."))
        })
        .map(|p| (p.code, p.path))
        .collect();
    problems.sort();
    assert_eq!(
        problems,
        [
            (Some("settings.unknown".into()), Some("$.settings".into())),
            (Some("settings.value".into()), Some("$.settings.pvp".into())),
        ]
    );
}

#[test]
fn a_change_is_written_heard_and_restarts_what_read_it() {
    let dir = tempfile::tempdir().unwrap();
    let files = project();
    let platform = std::rc::Rc::new(Fake::default());
    let mut session = start(&files, &platform, dir.path());
    printed(&session, &platform, 3);
    assert_eq!(
        session.set_setting("arena", "difficulty", Some(&serde_json::json!("hard"))),
        Ok(true)
    );
    // What's logged comes first; what scripts print is reported at the next tick.
    assert_eq!(
        printed(&session, &platform, 5),
        [
            "info Setting arena:difficulty is now hard (was normal)",
            "info Restarting what read it: module:reader",
            "listener] changed difficulty hard normal",
            "reader] read hard 8 true 1.5",
            "reader] false modules/reader/init.lua:2: there's no setting \"nope\" in netherforge.json's settings (it has difficulty, max_players, pvp, rate)",
        ]
    );
    assert_eq!(
        std::fs::read_to_string(dir.path().join("arena.json")).unwrap(),
        "{\n  \"difficulty\": \"hard\"\n}\n"
    );
    // The listener read max_players, and hears its change instead of restarting.
    assert_eq!(
        session.set_setting_text("arena", "max_players", " 10 "),
        Ok(true)
    );
    assert_eq!(
        &printed(&session, &platform, 4)[..4],
        [
            "info Setting arena:max_players is now 10 (was 8)",
            "info Restarting what read it: module:reader",
            "listener] changed max_players 10 8",
            "reader] read hard 10 true 1.5",
        ]
    );
    lines(&platform);
    // The same value again changes nothing; a value it can't have says why.
    assert_eq!(
        session.set_setting_text("arena", "max_players", "10"),
        Ok(false)
    );
    assert_eq!(
        session.set_setting_text("arena", "max_players", "40"),
        Err("max_players: 40 isn't a whole number from 2 to 16".into())
    );
    assert_eq!(
        session.set_setting("arena", "pvp", Some(&serde_json::json!(3))),
        Err("pvp: 3 isn't true or false".into())
    );
    assert_eq!(
        session.set_setting("arena", "nope", None),
        Err("there's no setting \"nope\" in netherforge.json's settings (it has difficulty, max_players, pvp, rate)".into())
    );
    assert_eq!(
        session.set_setting("other", "x", None),
        Err("no package \"other\" with settings (there's arena)".into())
    );
    // Back to their defaults: the file goes.
    assert_eq!(session.set_setting("arena", "difficulty", None), Ok(true));
    assert_eq!(
        session.set_setting("arena", "max_players", Some(&serde_json::Value::Null)),
        Ok(true)
    );
    assert!(!dir.path().join("arena.json").exists());
}

#[test]
fn a_file_edited_by_hand_is_read_again_and_an_unreadable_one_left_alone() {
    let dir = tempfile::tempdir().unwrap();
    let files = project();
    let platform = std::rc::Rc::new(Fake::default());
    let mut session = start(&files, &platform, dir.path());
    printed(&session, &platform, 3);
    std::fs::write(dir.path().join("arena.json"), r#"{ "rate": 2.25 }"#).unwrap();
    assert_eq!(session.reload_settings(), 1);
    let seen = printed(&session, &platform, 4);
    assert_eq!(
        &seen[..4],
        [
            "info Setting arena:rate is now 2.25 (was 1.5)",
            "info Restarting what read it: module:reader",
            "listener] changed rate 2.25 1.5",
            "reader] read normal 8 true 2.25",
        ]
    );
    std::fs::write(dir.path().join("arena.json"), "[1, 2]").unwrap();
    session.reload_settings();
    assert!(
        session
            .problems()
            .iter()
            .any(|p| p.code.as_deref() == Some("settings.file"))
    );
    assert!(
        session
            .set_setting("arena", "pvp", Some(&serde_json::json!(false)))
            .unwrap_err()
            .ends_with("isn't a JSON object: fix or delete it, then /nf settings reload")
    );
    assert_eq!(
        std::fs::read_to_string(dir.path().join("arena.json")).unwrap(),
        "[1, 2]"
    );
}
