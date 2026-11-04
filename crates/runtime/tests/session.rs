//! A session on the Rust runtime against a fake Platform: a project read
//! through format, its modules started, ticked, reloaded and stopped, what it
//! reports in the Platform's log. The fake fakes the log and the server's
//! facts (`FakePlatform`); every other group is `Unported`, so a test that
//! reached one would say which.

use std::cell::RefCell;
use std::collections::BTreeMap;

use netherforge_format::package::NoPackages;
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

impl FakePlatform for Fake {
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
    let platform = Fake::default();
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
    let mut session = Session::start(&platform, &files, &NoPackages);
    assert!(session.refusal().is_none());
    assert_eq!(
        platform.take(),
        [
            "info [module door] hello (modules/door/init.lua:1)",
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
    let platform = Fake::default();
    let files = Files::new(&[("netherforge.json", &MANIFEST.replace("26.3", "26.1"))]);
    let session = Session::start(&platform, &files, &NoPackages);
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
    let platform = Fake::default();
    let failed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        netherforge_platform::Platform::teams(&platform).names()
    }));
    let message = failed.unwrap_err().downcast::<String>().unwrap();
    assert_eq!(*message, "TeamOps::names isn't on this platform");
}
