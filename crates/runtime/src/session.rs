//! One run of a project: the Kotlin runtime's `ProjectSession`, in Rust, as
//! far as the ported services go. A session reads the project through format
//! (with its packages), refuses one that can't run, starts its services in a
//! room, ticks them, reloads what changed, and stops them. What it reports goes
//! to the Platform's log.
//!
//! **Services.** Everything a session runs is a [`Service`] in the one list
//! `Session::services` makes: registration order is the order they start,
//! tick and hear every hook in (stop is the reverse), and a kind of resource
//! reloads through the one service that says it reloads it. A new subsystem is
//! a service registered there; nothing else names it.
//!
//! Not yet: the game's facts from the Platform (format reads without them, so
//! its checks that need the game say nothing), packages' modules (a module is
//! the project's own), the bridge to the editor, and every service but modules.

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::Problem;
use netherforge_format::Severity;
use netherforge_format::names::MinecraftVersion;
use netherforge_format::package::PackageSources;
use netherforge_format::project::{self, Project as Loaded, ProjectSource};
use netherforge_luau::{Host, Limits};
use netherforge_platform::Platform;

use crate::Room;
use crate::modules::{self, Modules, Owner};

/// The Lua budget a module's body and handlers run with (instructions' worth of steps).
pub const MODULE_BUDGET: u64 = 1_000_000;

/// What a session tells its services about the project as it read.
pub struct Define<'s> {
    pub project: &'s Loaded<'s>,
    pub source: &'s dyn ProjectSource,
}

/// A part of the runtime that holds something of the project's (`RuntimeService`).
pub trait Service {
    /// Its name, for the log.
    fn name(&self) -> &'static str;
    /// The kinds of resource it reloads, by their ids: each kind has one service that does.
    fn reloads(&self) -> &'static [&'static str] {
        &[]
    }
    /// Starts it in `room`, with what the project defines.
    fn start(&mut self, _room: &Room, _project: &Define) {}
    /// One tick.
    fn tick(&mut self, _room: &Room) {}
    /// The resources of `kind` (one it reloads) that changed, by id, the project as it reads now.
    fn reload(&mut self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, _project: &Define) {}
    /// Stops it: everything it holds goes.
    fn stop(&mut self, _room: &Room) {}
}

/// Why a project can't run: what the log says, and the problem to report if format didn't.
#[derive(Clone, Debug, PartialEq)]
pub struct Refusal {
    pub message: String,
    pub problem: Option<Problem>,
}

/// One run of a project.
pub struct Session<'a> {
    platform: &'a dyn Platform,
    source: &'a dyn ProjectSource,
    packages: &'a dyn PackageSources,
    project: Loaded<'a>,
    refusal: Option<Refusal>,
    room: Option<Room>,
    services: Vec<Box<dyn Service>>,
}

impl<'a> Session<'a> {
    /// The project in `source` read with its `packages`, and started on `platform` unless it's refused.
    pub fn start(
        platform: &'a dyn Platform,
        source: &'a dyn ProjectSource,
        packages: &'a dyn PackageSources,
    ) -> Session<'a> {
        let project = load(source, packages);
        let mut session = Session {
            platform,
            source,
            packages,
            refusal: refusal(platform, &project),
            project,
            room: None,
            services: services(),
        };
        session.run();
        session
    }

    fn run(&mut self) {
        let log = self.platform.log();
        for problem in self
            .project
            .problems
            .iter()
            .filter(|p| p.severity == Severity::Error)
        {
            let line = problem.line.map(|l| format!(":{l}")).unwrap_or_default();
            log.warn(format!("{}{line}: {}", problem.file, problem.message));
        }
        let name = self
            .project
            .manifest
            .as_ref()
            .map_or("the project", |m| m.name.as_str())
            .to_owned();
        if let Some(refusal) = &self.refusal {
            log.error(format!("Not running {name}: {}", refusal.message), None);
            return;
        }
        let room = match Host::new(Limits::default())
            .and_then(|host| Room::new(host, grants(&self.project)))
        {
            Ok(room) => room,
            Err(e) => {
                log.error(
                    format!("Not running {name}: the Lua state didn't start: {e}"),
                    None,
                );
                return;
            }
        };
        let define = Define {
            project: &self.project,
            source: self.source,
        };
        for service in &mut self.services {
            service.start(&room, &define);
        }
        self.room = Some(room);
        self.report();
        log.info(format!(
            "Running \"{name}\": {} modules",
            self.project.everywhere("module").len()
        ));
    }

    /// The project as it read last.
    pub fn project(&self) -> &Loaded<'a> {
        &self.project
    }

    /// Why it isn't running, when it's refused.
    pub fn refusal(&self) -> Option<&Refusal> {
        self.refusal.as_ref()
    }

    /// Its room, while it runs.
    pub fn room(&self) -> Option<&Room> {
        self.room.as_ref()
    }

    /// What's wrong with the project as it reads: format's findings and the session's own refusal.
    pub fn problems(&self) -> Vec<Problem> {
        let refused = self.refusal.as_ref().and_then(|r| r.problem.clone());
        self.project
            .problems
            .iter()
            .cloned()
            .chain(refused)
            .collect()
    }

    /// One server tick: each service in order, then what the scripts reported.
    pub fn tick(&mut self) {
        let Some(room) = &self.room else { return };
        room.scripts().tick();
        for service in &mut self.services {
            service.tick(room);
        }
        self.report();
    }

    /// Reads the project again after `changed` (project paths) changed, and
    /// reloads each changed resource through the service that reloads its kind.
    /// A change to `netherforge.json` (or the lock) restarts the session whole.
    pub fn reload(&mut self, changed: &[String]) {
        let whole = changed.iter().any(|p| {
            p == netherforge_format::manifest::FILE_NAME
                || p == netherforge_format::package::LOCK_FILE
        });
        if whole || self.room.is_none() {
            self.stop();
            self.project = load(self.source, self.packages);
            self.refusal = refusal(self.platform, &self.project);
            self.run();
            return;
        }
        self.project = load(self.source, self.packages);
        let mut by_kind: BTreeMap<&'static str, BTreeSet<String>> = BTreeMap::new();
        for path in changed {
            if let Some(found) = netherforge_format::kinds::classify(path)
                && let (Some(kind), Some(id)) = (found.kind, found.id)
                && found.package.is_none()
            {
                by_kind.entry(kind).or_default().insert(id);
            }
        }
        let room = self.room.as_ref().expect("running");
        let define = Define {
            project: &self.project,
            source: self.source,
        };
        for (kind, ids) in &by_kind {
            if let Some(service) = self
                .services
                .iter_mut()
                .find(|s| s.reloads().contains(kind))
            {
                service.reload(room, kind, ids, &define);
            }
        }
        self.report();
    }

    /// Stops every service, in reverse order, and closes the room.
    pub fn stop(&mut self) {
        let Some(room) = self.room.take() else { return };
        for service in self.services.iter_mut().rev() {
            service.stop(&room);
        }
        self.services = services();
    }

    /// What the scripts reported since the last time, to the log: failures as
    /// warnings (an error once it gave up), and the lines they logged.
    fn report(&self) {
        let Some(room) = &self.room else { return };
        let log = self.platform.log();
        let scripts = room.scripts();
        for report in scripts.take_reports() {
            let message = format!(
                "{} {} failed: {}",
                label(room, report.scope),
                report.what,
                report.failure
            );
            if report.gave_up {
                log.error(
                    format!("{message} (stopped after {} in a row)", crate::MAX_ERRORS),
                    None,
                );
            } else {
                log.warn(message);
            }
        }
        for line in scripts.take_logs() {
            let at = line.location.map(|l| format!(" ({l})")).unwrap_or_default();
            log.info(format!("[{}] {}{at}", label(room, line.scope), line.line));
        }
    }
}

impl Drop for Session<'_> {
    fn drop(&mut self) {
        self.stop();
    }
}

/// The services a session runs, in the one order they start, tick and stop in.
fn services() -> Vec<Box<dyn Service>> {
    vec![Box::new(ModulesService::default())]
}

/// Whose a scope is, for the log: `module greeter`, a script's file.
fn label(room: &Room, scope: netherforge_luau::ScopeId) -> String {
    room.host()
        .scope_name(scope)
        .unwrap_or_else(|| "script".into())
}

fn load<'a>(source: &dyn ProjectSource, packages: &'a dyn PackageSources) -> Loaded<'a> {
    project::load(
        source,
        project::Options {
            packages,
            ..Default::default()
        },
    )
}

/// Why the project can't run, if it can't: no readable manifest, an error in it, or a
/// target Minecraft version this server doesn't run.
fn refusal(platform: &dyn Platform, project: &Loaded) -> Option<Refusal> {
    let file = netherforge_format::manifest::FILE_NAME;
    if let Some(problem) = project
        .problems
        .iter()
        .find(|p| p.file == file && p.severity == Severity::Error)
    {
        return Some(Refusal {
            message: format!("{file}: {}", problem.message),
            problem: None,
        });
    }
    let Some(manifest) = &project.manifest else {
        return Some(Refusal {
            message: format!("there's no readable {file}"),
            problem: None,
        });
    };
    let target = MinecraftVersion::parse(&manifest.minecraft);
    let supported = platform.info().supported_targets;
    if target.is_none()
        || !supported
            .iter()
            .any(|s| MinecraftVersion::parse(s) == target)
    {
        let message = format!(
            "This project targets Minecraft {}, but this server runs NetherForge for Minecraft {}. Run it on a matching server, or change \"minecraft\" in {file}.",
            manifest.minecraft,
            supported.join(" or ")
        );
        let problem = netherforge_format::codes::RUNTIME_MINECRAFT_UNSUPPORTED.at(
            file,
            message.clone(),
            Some("$.minecraft".into()),
        );
        return Some(Refusal {
            message,
            problem: Some(problem),
        });
    }
    None
}

/// What the project's packages declared they need (`requires`), by the names
/// the manifest's model gives them: each one it says `true` for or lists anything under.
fn grants(project: &Loaded) -> Vec<String> {
    let Some(requires) = project.manifest.as_ref().and_then(|m| m.requires.as_ref()) else {
        return Vec::new();
    };
    let serde_json::Value::Object(fields) = serde_json::to_value(requires).expect("a model") else {
        return Vec::new();
    };
    fields
        .into_iter()
        .filter(|(_, v)| v.as_bool() == Some(true) || v.as_array().is_some_and(|a| !a.is_empty()))
        .map(|(k, _)| k)
        .collect()
}

/// Modules: every module of the project's started in a scope of its own, and one that changed restarted
/// with every scope that required it.
#[derive(Default)]
struct ModulesService {
    modules: Option<Modules>,
}

/// The project's Lua the room runs, by project path: its modules' files, the ones without errors.
fn lua_of(define: &Define) -> modules::Project {
    let running: BTreeSet<&str> = define
        .project
        .resources
        .iter()
        .filter(|r| r.found.kind.id == "module" && !r.errors)
        .map(|r| r.found.id.as_str())
        .collect();
    let files = define
        .source
        .files()
        .iter()
        .filter(|path| {
            path.ends_with(".lua")
                && path
                    .strip_prefix("modules/")
                    .and_then(|rest| rest.split_once('/'))
                    .is_some_and(|(id, _)| running.contains(id))
        })
        .filter_map(|path| Some((path.clone(), define.source.read(path)?)))
        .collect();
    modules::Project { files }
}

impl Service for ModulesService {
    fn name(&self) -> &'static str {
        "modules"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["module"]
    }

    fn start(&mut self, room: &Room, project: &Define) {
        let modules = Modules::new(room.scripts().clone(), lua_of(project), MODULE_BUDGET);
        modules.start();
        self.modules = Some(modules);
    }

    fn reload(&mut self, _room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let Some(modules) = &self.modules else { return };
        let lua = lua_of(project);
        // Each changed module, then every module that required one, once.
        let mut again: Vec<String> = ids.iter().cloned().collect();
        for id in ids {
            for scope in modules.dependents(id) {
                if let Some(Owner::Module(dependent)) = modules.owner(scope)
                    && !again.contains(&dependent)
                {
                    again.push(dependent);
                }
            }
        }
        for id in again {
            // A module that's gone, or now has errors, stops; its failures are in the reports.
            let _ = modules.reload(&id, lua.clone());
        }
    }

    fn stop(&mut self, _room: &Room) {
        self.modules = None;
    }
}
