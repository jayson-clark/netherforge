//! A session on the Rust runtime against a fake Platform: a project read
//! through format, its modules started, ticked, reloaded and stopped, what it
//! reports in the Platform's log. The fake fakes the log and the server's
//! facts (`FakePlatform`); every other group is `Unported`, so a test that
//! reached one would say which.

use std::cell::RefCell;
use std::collections::BTreeMap;

use netherforge_format::package::{
    NoPackages, OpenedPackage, PackageLookup, PackageRequest, PackageSources,
};
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::session::Session;

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

/// Nobody online.
impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
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

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
}

/// A project in memory, whose files can change.
#[derive(Default)]
struct Files {
    names: std::collections::BTreeSet<String>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: files.iter().map(|(p, _)| p.to_string()).collect(),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
    fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &std::collections::BTreeSet<String> {
        &self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

#[test]
fn a_projects_modules_start_tick_and_reload() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/greeter/init.lua",
            "local M = {} function M.hello() return 'hello' end return M",
        ),
        (
            "modules/door/init.lua",
            "local greeter = require('greeter') print(greeter.hello()) nf.after(2, function() print('later') end)",
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(session.refusal().is_none());
    assert_eq!(
        platform.take(),
        [
            "info [module door] hello (modules/door/init.lua:1)",
            "info Requires nothing beyond what every project may do",
            "info Running \"Demo\": 2 modules",
        ]
    );
    session.tick();
    session.tick();
    session.tick();
    assert_eq!(
        platform.take(),
        ["info [module door] later (modules/door/init.lua:1)"]
    );

    // A module that changed starts again from its new files, and so does every module that required it.
    files.write(
        "modules/greeter/init.lua",
        "local M = {} function M.hello() return 'hi' end return M",
    );
    session.reload(&["modules/greeter/init.lua".into()]);
    assert_eq!(
        platform.take(),
        ["info [module door] hi (modules/door/init.lua:1)"]
    );

    // One that fails is reported, and the rest keep running.
    files.write("modules/greeter/init.lua", "error('broken')");
    session.reload(&["modules/greeter/init.lua".into()]);
    let lines = platform.take();
    assert!(
        lines
            .iter()
            .any(|l| l.starts_with("warn") && l.contains("broken")),
        "{lines:?}"
    );
    session.stop();
}

#[test]
fn a_project_for_another_minecraft_is_refused() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[("netherforge.json", &MANIFEST.replace("26.3", "26.1"))]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    assert_eq!(
        session
            .refusal()
            .and_then(|r| r.problem.as_ref())
            .and_then(|p| p.code.as_deref()),
        Some("runtime.minecraft-unsupported")
    );
    assert!(session.room().is_none());
    assert_eq!(
        platform.take(),
        [
            "error Not running Demo: This project targets Minecraft 26.1, but this server runs NetherForge for Minecraft 26.3. Run it on a matching server, or change \"minecraft\" in netherforge.json."
        ]
    );
}

#[test]
fn reaching_a_group_the_fake_doesnt_fake_says_which() {
    let platform = std::rc::Rc::new(Fake::default());
    let failed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        netherforge_platform::Platform::teams(&*platform).names()
    }));
    let message = failed.unwrap_err().downcast::<String>().unwrap();
    assert_eq!(*message, "TeamOps::names isn't on this platform");
}

// ---- packages ----

/// Packages beside the project, each a set of files by namespace.
struct Packages(BTreeMap<&'static str, Vec<(&'static str, String)>>);

impl PackageSources for Packages {
    fn open(&self, namespace: &str, _: &PackageRequest) -> PackageLookup<'_> {
        match self.0.get(namespace) {
            Some(files) => {
                let files: Vec<(&str, &str)> =
                    files.iter().map(|(p, t)| (*p, t.as_str())).collect();
                PackageLookup::Opened(OpenedPackage {
                    source: Box::new(Files::new(&files)),
                    hash: None,
                    commit: None,
                    from: None,
                })
            }
            None => PackageLookup::Missing(None),
        }
    }
}

fn package_manifest(namespace: &str, more: &str) -> String {
    format!(
        r#"{{ "formatVersion": 1, "name": "{namespace}", "namespace": "{namespace}", "version": "1.0.0", "minecraft": "26.3"{more} }}"#
    )
}

fn depending(more: &str) -> String {
    MANIFEST.replace(
        r#""minecraft": "26.3""#,
        &format!(
            r#""minecraft": "26.3", "dependencies": {{ "acme": {{ "path": "../acme" }} }}{more}"#
        ),
    )
}

/// `acme`: an exported `api` module that uses its own unexported `util`, and an unexported `secret`.
fn acme(more: &str) -> Packages {
    Packages(BTreeMap::from([(
        "acme",
        vec![
            (
                "netherforge.json",
                package_manifest("acme", &format!(r#", "exports": {{ "modules": ["api"] }}{more}"#)),
            ),
            (
                "modules/api/init.lua",
                "local util = require('util') return { greet = function() return util.word() end, run = function() nf.server.run('say hi') end }".into(),
            ),
            ("modules/util/init.lua", "return { word = function() return 'hello' end }".into()),
            ("modules/secret/init.lua", "return 'hidden'".into()),
        ],
    )]))
}

fn warnings(lines: &[String]) -> Vec<String> {
    lines
        .iter()
        .filter(|l| l.starts_with("warn "))
        .cloned()
        .collect()
}

#[test]
fn a_packages_exported_module_is_required_by_its_namespace() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", &depending("")),
        (
            "modules/shop/init.lua",
            "local api = require('acme:api') print(api.greet()) print(pcall(require, 'acme:secret')) print(type(select(2, pcall(require, 'acme:secret'))))",
        ),
    ]);
    let packages = acme("");
    let _session = Session::start(platform.clone(), &files, &packages);
    let lines = platform.take();
    // The package's module finds its own `util` by a bare name, in its own package.
    assert!(
        lines
            .iter()
            .any(|l| l == "info [module shop] hello (modules/shop/init.lua:1)"),
        "{lines:?}"
    );
    // Its other modules are its own.
    assert!(
        lines.iter().any(|l| l.contains(
            "false\tmodules/shop/init.lua:1: package \"acme\" doesn't export its module \"secret\""
        )),
        "{lines:?}"
    );
    // What `pcall` catches is a string, as Lua's own errors are.
    assert!(
        lines
            .iter()
            .any(|l| l == "info [module shop] string (modules/shop/init.lua:1)"),
        "{lines:?}"
    );
    assert_eq!(warnings(&lines), Vec::<String>::new());
}

#[test]
fn a_package_the_project_doesnt_depend_on_isnt_there() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/shop/init.lua", "print(pcall(require, 'acme:api'))"),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let lines = platform.take();
    assert!(
        lines.iter().any(|l| l.contains(
            "false\tmodules/shop/init.lua:1: \"acme:api\": \"acme\" is neither this project's namespace (\"demo\") nor a package's it depends on"
        )),
        "{lines:?}"
    );
}

#[test]
fn a_call_is_held_to_the_package_whose_code_makes_it() {
    let platform = std::rc::Rc::new(Fake::default());
    // The project declares console; the package doesn't: the package's code is refused.
    let files = Files::new(&[
        (
            "netherforge.json",
            &depending(r#", "requires": { "console": true }"#),
        ),
        (
            "modules/shop/init.lua",
            "local api = require('acme:api') print(pcall(api.run)) print(pcall(nf.server.run, 'say hi'))",
        ),
    ]);
    let packages = acme("");
    let _session = Session::start(platform.clone(), &files, &packages);
    let lines = platform.take();
    assert!(
        lines.iter().any(|l| l.contains(
            "false\tacme:modules/api/init.lua:1: nf.server.run needs console, which package \"acme\" hasn't declared: add \"requires\": { \"console\": true } to its netherforge.json"
        )),
        "{lines:?}"
    );
    // The project's own call passes the check (and reaches what isn't ported yet).
    assert!(
        lines.iter().any(|l| l.contains(
            "false\tmodules/shop/init.lua:1: nf.server.run isn't on the Rust runtime yet"
        )),
        "{lines:?}"
    );
    assert!(
        lines
            .iter()
            .any(|l| l == "info Requires (by its packages' netherforge.json): console (demo)"),
        "{lines:?}"
    );
}

#[test]
fn a_module_in_group_folders_is_required_by_its_id() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/(combat)/sword/init.lua", "return { damage = 7 }"),
        ("modules/(combat)/sword/edge.lua", "return 'sharp'"),
        (
            "modules/door/init.lua",
            "print(require('sword').damage, require('sword.edge'))",
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let lines = platform.take();
    assert!(
        lines
            .iter()
            .any(|l| l == "info [module door] 7\tsharp (modules/door/init.lua:1)"),
        "{lines:?}"
    );
}

#[test]
fn a_repeated_error_is_logged_once_in_a_while_and_counted_in_between() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        // Fails every other tick: never 20 in a row, so it keeps running.
        (
            "modules/flaky/init.lua",
            "local n = 0\nnf.on('tick', function() n += 1 if n % 2 == 0 then error('odd') end end)",
        ),
        // Fails every tick: cancelled after 20 in a row.
        (
            "modules/broken/init.lua",
            "nf.on('tick', function() error('always') end)",
        ),
        ("modules/dead/init.lua", "local x = nil\nx.y = 1"),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert!(
        start.contains(&"warn module dead: body failed: attempt to index nil with 'y' (modules/dead/init.lua:2). It's disabled until it's reloaded.".to_owned()),
        "{start:?}"
    );
    for _ in 0..210 {
        session.tick();
    }
    let lines = warnings(&platform.take());
    let flaky: Vec<&String> = lines
        .iter()
        .filter(|l| l.contains("module flaky"))
        .collect();
    assert_eq!(flaky.len(), 3, "{flaky:?}");
    assert_eq!(
        flaky[0],
        "warn module flaky: tick handler failed: odd (modules/flaky/init.lua:2)"
    );
    assert_eq!(
        flaky[1],
        "warn module flaky: tick handler failed: odd (modules/flaky/init.lua:2) (and 49 more like it)"
    );
    let broken: Vec<&String> = lines
        .iter()
        .filter(|l| l.contains("module broken"))
        .collect();
    assert_eq!(
        broken,
        [
            "warn module broken: tick handler failed: always (modules/broken/init.lua:1)",
            "warn module broken: tick handler failed 20 times in a row, so it was cancelled (modules/broken/init.lua:1)",
        ]
    );
    // Each is a problem, once, at its line.
    let problems: Vec<(String, Option<u32>, String)> = session
        .problems()
        .into_iter()
        .filter(|p| p.code.as_deref() == Some("script.error"))
        .map(|p| (p.file, p.line, p.message))
        .collect();
    assert_eq!(
        problems,
        [
            (
                "modules/broken/init.lua".to_owned(),
                Some(1),
                "always".to_owned()
            ),
            (
                "modules/dead/init.lua".to_owned(),
                Some(2),
                "attempt to index nil with 'y'".to_owned()
            ),
            (
                "modules/flaky/init.lua".to_owned(),
                Some(2),
                "odd".to_owned()
            ),
        ]
    );
}

#[test]
fn the_servers_events_reach_scripts_and_what_they_write_goes_back() {
    use netherforge_platform::{PlatformEvents, PlayerRef, Uuid, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/greeter/init.lua",
            "nf.on('player_join', function(e) e.message = 'welcome' .. (e.first_join and ', new one' or '') end)
nf.on('player_chat', function(e) if e.message == 'bad' then e:cancel() end end)",
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let alex = PlayerRef {
        uuid: Uuid(0x0f1b_0c7e_6a43_3d1f_9a8c_2b5e_8f1a_0c11),
        name: "Alex".into(),
    };
    let join = |message: &str| game_event::PlayerJoin {
        player: alex.clone(),
        first_join: true,
        message: Some(message.into()),
    };
    let mut joined = join("Alex joined");
    server.game().player_join(&mut joined);
    assert_eq!(joined.message.as_deref(), Some("welcome, new one"));
    let mut chat = game_event::PlayerChat {
        player: alex.clone(),
        message: "bad".into(),
        format: "<Alex> bad".into(),
    };
    assert!(server.game().player_chat(&mut chat), "cancelled");
    chat.message = "fine".into();
    assert!(!server.game().player_chat(&mut chat));
    // While the session is being changed (a reload), an event goes through as it was.
    {
        let _changing = server.session_mut();
        let mut joined = join("Alex joined");
        server.game().player_join(&mut joined);
        assert_eq!(joined.message.as_deref(), Some("Alex joined"));
    }
    // Nothing runs while the session doesn't.
    server.session_mut().stop();
    let mut joined = join("Alex joined");
    server.game().player_join(&mut joined);
    assert_eq!(joined.message.as_deref(), Some("Alex joined"));
}
