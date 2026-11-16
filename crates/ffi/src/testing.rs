//! A project's script tests run from the JVM (`netherforge test`'s jar, Kotlin's `ScriptTestRunner`): the runtime's
//! own runner ([`netherforge_runtime::testing::runner`]) on fake servers the JVM makes, one per test, each its
//! testkit's `FakePlatform`. The JVM's `TestServers` object is written by hand on each side (`apps/plugin/ffi`'s
//! `TestServers`), its methods by number:
//!
//! 0. `start() -> (platform, server id)`: a fresh fake server;
//! 1. `after_tick(server)`: the server's own part of a tick, after the runtime's (its scheduler, the world's entities);
//! 2. `join(server, name) -> PlayerRef?`: a fake player joined, the join events raised;
//! 3. `shown(server, player, slot?, menu) -> ItemData?`: the stack a player's game was last sent there;
//! 4. `close(server)`: the server let go;
//! 5. `print(line)`: a line of the run's report.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::{Rc, Weak};

use netherforge_platform::{ItemData, Platform, PlatformEvents, PlayerRef, Uuid};
use netherforge_runtime::project_files::{FilesConfig, ProjectFiles};
use netherforge_runtime::server::ServerEvents;
use netherforge_runtime::session::{Config, Session};
use netherforge_runtime::testing::TestHarness;
use netherforge_runtime::testing::runner::{Refused, Runner, TestEvent, TestServer, TextReport};

use crate::JavaPlatform;
use crate::objects::JavaRef;
use crate::wire::{Put, Reader, Take};

/// What the JVM asks a test run for.
pub struct TestArgs {
    pub servers: JavaRef,
    pub project: PathBuf,
    pub package_cache: Option<PathBuf>,
    pub filter: Option<String>,
    pub json: bool,
    pub junit: Option<PathBuf>,
    /// A folder the run may keep its servers' files in (their scripts' data), emptied after.
    pub scratch: PathBuf,
    pub version: String,
}

impl Take for TestArgs {
    fn take(r: &mut Reader) -> Self {
        TestArgs {
            servers: Take::take(r),
            project: Take::take(r),
            package_cache: Take::take(r),
            filter: Take::take(r),
            json: Take::take(r),
            junit: Take::take(r),
            scratch: Take::take(r),
            version: Take::take(r),
        }
    }
}

/// One fake server's half of the harness: its id on the JVM's side, and the runtime's events once they're there.
struct Harness {
    servers: Rc<JavaRef>,
    id: u64,
    events: RefCell<Weak<ServerEvents<'static>>>,
}

impl TestHarness for Harness {
    fn tick(&self) {
        if let Some(events) = self.events.borrow().upgrade() {
            events.tick();
        }
        self.servers
            .call::<()>("TestServers::after_tick", 1, |w| self.id.put(w));
    }

    fn join(&self, name: &str) -> Option<PlayerRef> {
        self.servers.call("TestServers::join", 2, |w| {
            self.id.put(w);
            name.to_owned().put(w);
        })
    }

    fn shown(&self, player: Uuid, slot: Option<i32>, menu: bool) -> Option<ItemData> {
        self.servers.call("TestServers::shown", 3, |w| {
            self.id.put(w);
            player.put(w);
            slot.put(w);
            menu.put(w);
        })
    }

    fn events(&self) -> Option<Rc<dyn PlatformEvents>> {
        self.events
            .borrow()
            .upgrade()
            .map(|e| e as Rc<dyn PlatformEvents>)
    }
}

/// A fake server running the project, for one test.
struct Server {
    servers: Rc<JavaRef>,
    id: u64,
    events: Rc<ServerEvents<'static>>,
}

impl TestServer for Server {
    fn events(&self) -> &ServerEvents<'static> {
        &self.events
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        if let Some(mut session) = self.events.session_mut_now() {
            session.stop();
        }
        self.servers
            .call::<()>("TestServers::close", 4, |w| self.id.put(w));
    }
}

/// Runs the project's tests, printing the report through the JVM: the process's exit code (0 every test passed, 1
/// one failed or errored, 2 the tests couldn't run).
pub fn run(args: TestArgs) -> i32 {
    let servers = Rc::new(args.servers);
    // Every server reads the project's files afresh, as its own server would; they outlive the run.
    let files: &'static ProjectFiles = Box::leak(Box::new(ProjectFiles::new(FilesConfig {
        configured: args.project.clone(),
        resolves: true,
        package_cache: args.package_cache.clone(),
        ..FilesConfig::default()
    })));
    let count = std::cell::Cell::new(0u64);
    let start = || {
        let (platform, id): (JavaRef, u64) = servers.call("TestServers::start", 0, |_| {});
        let platform: Rc<dyn Platform> = Rc::new(JavaPlatform::new(platform));
        let harness = Rc::new(Harness {
            servers: servers.clone(),
            id,
            events: RefCell::new(Weak::new()),
        });
        count.set(count.get() + 1);
        let data = args.scratch.join(format!("server-{}", count.get()));
        let _ = std::fs::create_dir_all(&data);
        let config = Config {
            testing: Some(harness.clone()),
            data_dir: Some(data.join("data")),
            limits: Some(test_limits()),
            ..Config::default()
        };
        let store = Rc::new(netherforge_runtime::store::Store::in_memory());
        let session = Session::configured(platform.clone(), files, files, store, config);
        let shared = session.shared().clone();
        let events = Rc::new(ServerEvents::new(session));
        *harness.events.borrow_mut() = Rc::downgrade(&events);
        crate::compose::bind(&platform, &events, &shared);
        events
            .session_mut_now()
            .expect("nothing holds a session that hasn't run")
            .begin();
        Server {
            servers: servers.clone(),
            id,
            events,
        }
    };
    let runner = Runner {
        project: args.project.display().to_string(),
        version: args.version.clone(),
        start: &start,
    };
    let mut text = TextReport::default();
    let mut results = Vec::new();
    let print =
        |line: &str| servers.call::<()>("TestServers::print", 5, |w| line.to_owned().put(w));
    let mut report = |event: &TestEvent| {
        if let TestEvent::Result(result) = event {
            results.push(result.clone());
        }
        if args.json {
            print(&serde_json::to_string(event).unwrap_or_default());
        } else {
            for line in text.lines(event) {
                print(&line);
            }
        }
    };
    let finished = runner.run(args.filter.as_deref(), &mut report);
    let code = match finished {
        Err(Refused { message, problems }) => {
            report(&TestEvent::Failed { message, problems });
            2
        }
        Ok(finished) => {
            if let Some(file) = &args.junit {
                if let Some(dir) = file.parent() {
                    let _ = std::fs::create_dir_all(dir);
                }
                let xml =
                    netherforge_runtime::testing::runner::junit(&results, finished.duration_millis);
                if let Err(e) = std::fs::write(file, xml) {
                    print(&format!("Couldn't write {}: {e}", file.display()));
                }
            }
            if finished.ok() { 0 } else { 1 }
        }
    };
    let _ = std::fs::remove_dir_all(&args.scratch);
    code
}

/// What the sandbox holds a call into a script to in a test: a test's call includes every tick it advances, so the
/// time limit is generous, and the memory too.
fn test_limits() -> netherforge_luau::Limits {
    netherforge_luau::Limits {
        deadline: std::time::Duration::from_secs(120),
        memory_bytes: 1024 * 1024 * 1024,
        ..netherforge_luau::Limits::default()
    }
}
