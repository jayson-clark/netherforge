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
use std::rc::Rc;

use netherforge_format::Problem;
use netherforge_format::Severity;
use netherforge_format::names::MinecraftVersion;
use netherforge_format::package::PackageSources;
use netherforge_format::project::{self, Project as Loaded, ProjectSource};
use netherforge_luau::{Host, Limits};
use std::collections::{HashMap, HashSet};

use netherforge_platform::{BlockRef, EntityTag, Platform, PlayerRef, Uuid};

use crate::centity::api::CentityContext;
use crate::modules::{self, Modules, Owner};
use crate::{Room, Shared};

/// The Lua budget a module's body and handlers run with (instructions' worth of steps).
pub const MODULE_BUDGET: u64 = 1_000_000;

/// What a session tells its services about the project as it read.
pub struct Define<'s> {
    pub project: &'s Loaded<'s>,
    pub source: &'s dyn ProjectSource,
    pub platform: &'s dyn Platform,
}

/// A part of the runtime that holds something of the project's (`RuntimeService`).
///
/// Its hooks take `&self`: the server's events reach a session while it ticks (a script teleports a player,
/// and the teleport is an event), so a service keeps what changes in cells, and never holds one borrowed
/// while it calls a script.
pub trait Service {
    /// Its name, for the log.
    fn name(&self) -> &'static str;
    /// The kinds of resource it reloads, by their ids: each kind has one service that does.
    fn reloads(&self) -> &'static [&'static str] {
        &[]
    }
    /// Starts it in `room`, with what the project defines.
    fn start(&self, _room: &Room, _project: &Define) {}
    /// Every service has started: what waited for the others (a restored centity's script requires modules).
    fn after_start(&self, _room: &Room) {}
    /// One tick.
    fn tick(&self, _room: &Room, _platform: &dyn Platform) {}
    /// The resources of `kind` (one it reloads) that changed, by id, the project as it reads now.
    /// The first half of a reload of `ids`: stops what's about to be replaced, so the scopes that go are
    /// released to every service before anything new runs (Kotlin releases a scope as it closes).
    fn unload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>) {}

    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, _project: &Define) {}
    /// After a reload: the kinds of resource it changed (`vanilla` for `client/vanilla.json`), for a service that
    /// follows kinds it doesn't reload itself (the resource pack draws the project's blocks).
    fn reloaded(&self, _room: &Room, _kinds: &BTreeSet<&str>, _project: &Define) {}
    /// Stops it: everything it holds goes.
    fn stop(&self, _room: &Room, _platform: &dyn Platform) {}

    /// Scopes stopped for good (closed, or disabled by a failing body): what they made here goes.
    fn scope_released(&self, _scopes: &[netherforge_luau::ScopeId]) {}
    /// A player joined, before scripts hear of it.
    fn player_joined(&self, _player: &PlayerRef) {}
    /// A player left, after scripts heard of it.
    fn player_quit(&self, _player: &PlayerRef) {}
    /// An entity died or was removed for good.
    fn entity_gone(&self, _entity: Uuid) {}
    /// Entities came into the world with their chunk: NetherForge's own by `tagged`, the rest `untagged`.
    fn entities_loaded(&self, _tagged: &HashMap<Uuid, EntityTag>, _untagged: &[Uuid]) {}
    /// Entities are about to unload with their chunk, while they can still be written.
    fn entities_unloading(&self, _entities: &HashSet<Uuid>) {}
    /// A chunk is about to unload, once its scripts have heard of it.
    fn chunk_unloading(&self, _world: &str, _chunk_x: i32, _chunk_z: i32) {}
    /// A chunk came into memory (`generated` when it was made just now), before its scripts hear of it.
    fn chunk_loaded(&self, _world: &str, _chunk_x: i32, _chunk_z: i32, _generated: bool) {}
    /// A world is being saved (an autosave, `/save-all`, the server stopping).
    fn world_saving(&self, _world: &str) {}
    /// Another plugin was enabled or disabled, or a service provider registered or went.
    fn plugins_changed(&self) {}
    /// A block was placed or broken (once scripts let it be): what's built from the world's blocks reads it again.
    fn block_changed(&self, _block: &BlockRef) {}

    /// What's wrong as it runs, beyond the project's files (the particle budget's warning).
    fn problems(&self) -> Vec<Problem> {
        Vec::new()
    }
}

/// What the server's config says a session runs with (Kotlin's `RuntimeConfig`, as far as it's ported).
#[derive(Clone, Debug, Default)]
pub struct Config {
    /// `schedules.time-zone`: the zone `nf.schedule`'s times are in; the server's own when none.
    pub time_zone: Option<String>,
    /// The wall clock schedules read, in Unix milliseconds, fixed: for tests. The system's when none.
    pub wall_clock: Option<i64>,
    /// The scripts' data directory (`plugins/NetherForge/data/`): none, and `nf.files` finds nothing there.
    pub data_dir: Option<std::path::PathBuf>,
    /// `files:` in `config.yml`: what each package may keep there.
    pub files: crate::files::FilesConfig,
    /// `resource-pack:` in `config.yml`: where the pack is served from.
    pub pack: crate::packs::PackConfig,
    /// `screens:` in `config.yml`.
    pub screens: crate::screen::ScreensConfig,
    /// The project's files' bytes while scripts run (pictures, font files): the adapter's reader of the disk.
    pub bytes: Option<crate::screen::ProjectBytes>,
    /// Where the project's files are on disk (videos' frames, read a frame at a time): the adapter's.
    pub paths: Option<crate::screen::video::ProjectPaths>,
}

/// Why a project can't run: what the log says, and the problem to report if format didn't.
#[derive(Clone, Debug, PartialEq)]
pub struct Refusal {
    pub message: String,
    pub problem: Option<Problem>,
}

/// One run of a project.
pub struct Session<'a> {
    platform: Rc<dyn Platform>,
    source: &'a dyn ProjectSource,
    packages: &'a dyn PackageSources,
    project: Loaded<'a>,
    refusal: Option<Refusal>,
    room: Option<Room>,
    services: Vec<Box<dyn Service>>,
    /// What its services share with the room's API: its centities, its modules.
    shared: Rc<Shared>,
    reported: std::cell::RefCell<Reported>,
}

/// How long the same runtime error stays quiet after it's logged: five seconds.
pub const ERROR_QUIET_TICKS: u64 = 100;

/// What the session has said of its scripts' failures (Kotlin's `ScriptReports`).
#[derive(Default)]
struct Reported {
    /// When each distinct runtime error was last logged, and how many like it were held back since.
    errors: std::collections::HashMap<String, (u64, u32)>,
    /// The problems scripts caused while running, by scope, until the scope closes.
    problems: BTreeMap<netherforge_luau::ScopeId, Vec<Problem>>,
}

/// A failure's message without its location, and where it was (`modules/door/init.lua`, line 3).
fn placed(failure: &netherforge_luau::Failure) -> (String, Option<String>, Option<u32>) {
    use netherforge_luau::Failure;
    match failure {
        Failure::Stopped { stop, location } => (
            stop.to_string(),
            location.as_ref().map(|l| l.chunk.clone()),
            location.as_ref().map(|l| l.line),
        ),
        Failure::Memory => (failure.to_string(), None, None),
        Failure::Error { message, .. } => {
            // Luau locates a message `chunk:line: message`; a chunk is a project path (a package's has a colon).
            let located = message.match_indices(": ").find_map(|(at, _)| {
                let (place, rest) = (&message[..at], &message[at + 2..]);
                let (file, line) = place.rsplit_once(':')?;
                let line: u32 = line.parse().ok()?;
                file.ends_with(".lua")
                    .then(|| (rest.to_owned(), file.to_owned(), line))
            });
            match located {
                Some((rest, file, line)) => (rest, Some(file), Some(line)),
                None => (message.clone(), None, None),
            }
        }
    }
}

impl<'a> Session<'a> {
    /// The project in `source` read with its `packages`, and started on `platform` unless it's refused.
    pub fn start(
        platform: Rc<dyn Platform>,
        source: &'a dyn ProjectSource,
        packages: &'a dyn PackageSources,
    ) -> Session<'a> {
        Session::start_with(
            platform,
            source,
            packages,
            Rc::new(crate::store::Store::in_memory()),
        )
    }

    /// `start`, keeping what outlives a session in `store` (the server's `netherforge.db`).
    pub fn start_with(
        platform: Rc<dyn Platform>,
        source: &'a dyn ProjectSource,
        packages: &'a dyn PackageSources,
        store: Rc<crate::store::Store>,
    ) -> Session<'a> {
        Session::start_configured(platform, source, packages, store, Config::default())
    }

    /// `start_with`, as the server's config says.
    pub fn start_configured(
        platform: Rc<dyn Platform>,
        source: &'a dyn ProjectSource,
        packages: &'a dyn PackageSources,
        store: Rc<crate::store::Store>,
        config: Config,
    ) -> Session<'a> {
        let project = load(source, packages);
        let shared = Shared::new(platform.clone(), store);
        shared.schedules.borrow_mut().now = config.wall_clock;
        shared.packs.borrow_mut().config = config.pack.clone();
        {
            let mut screens = shared.screens.borrow_mut();
            screens.config = config.screens;
            screens.read = config.bytes.clone();
            screens.paths = config.paths.clone();
        }
        if let Some(root) = &config.data_dir {
            *shared.files.borrow_mut() =
                Some(crate::files::ScriptFiles::new(root.clone(), config.files));
        }
        if let Some(zone) = &config.time_zone {
            match crate::schedule::Zone::parse(zone) {
                Some(zone) => shared.schedules.borrow_mut().zone = zone,
                None => platform.log().warn(format!(
                    "config.yml's schedules.time-zone \"{zone}\" isn't a time zone (try \"Europe/London\", \"UTC\" or \"+02:00\"): schedules use the server's own"
                )),
            }
        }
        let mut session = Session {
            refusal: refusal(&*platform, &project),
            platform,
            source,
            packages,
            project,
            room: None,
            services: services(&shared),
            shared,
            reported: Default::default(),
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
            .and_then(|host| Room::with(host, grants(&self.project), Some(self.shared.clone())))
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
        *self.shared.names.borrow_mut() = self.project.names();
        // Files from before each package had a folder are the project's.
        if let Some(files) = self.shared.files.borrow_mut().as_mut() {
            files.adopt(self.project.namespace());
        }
        let define = Define {
            project: &self.project,
            source: self.source,
            platform: &*self.platform,
        };
        for service in &self.services {
            service.start(&room, &define);
        }
        for service in &self.services {
            service.after_start(&room);
        }
        self.room = Some(room);
        self.released();
        self.report();
        let uses = netherforge_format::requirement::combined(&self.project);
        log.info(if uses.is_empty() {
            "Requires nothing beyond what every project may do".to_owned()
        } else {
            let each: Vec<String> = uses
                .iter()
                .map(|u| format!("{} ({})", u.requirement, u.packages.join(", ")))
                .collect();
            format!(
                "Requires (by its packages' netherforge.json): {}",
                each.join("; ")
            )
        });
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

    /// Its centities' instances.
    pub fn centities(&self) -> std::cell::RefMut<'_, crate::centity::Centities> {
        self.shared.centities.borrow_mut()
    }

    /// Spawns an instance of `centity` at `at`, facing `yaw`, as `nf.centities.spawn` will: its id, or none
    /// when there's no such centity or world.
    pub fn spawn(
        &self,
        centity: &str,
        at: netherforge_platform::Location,
        yaw: f64,
    ) -> Option<netherforge_platform::Uuid> {
        let room = self.room.as_ref()?;
        CentityContext::new(self.shared.clone(), room.scripts().clone()).spawn(centity, at, yaw)
    }

    /// A player clicked `entity` (`PlatformEvents::entity_clicked`): true when it's one of ours, which the
    /// adapter cancels whatever scripts did with it.
    pub fn entity_clicked(
        &self,
        entity: netherforge_platform::Uuid,
        player: &netherforge_platform::PlayerRef,
        button: netherforge_platform::ClickButton,
        sight: Option<netherforge_platform::Ray>,
    ) -> bool {
        let Some(room) = self.room.as_ref() else {
            return false;
        };
        let ours = CentityContext::new(self.shared.clone(), room.scripts().clone()).click(
            entity,
            player,
            button,
            sight.clone(),
        ) || crate::screen::ScreensContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
        .click(entity, player, button, sight);
        self.released();
        ours
    }

    /// Entities came into memory with their chunk (`PlatformEvents::entities_loaded`).
    pub fn entities_loaded(&self, tagged: &HashMap<Uuid, EntityTag>, untagged: &[Uuid]) {
        self.each(|s| s.entities_loaded(tagged, untagged));
    }

    /// Entities are about to unload with their chunk (`PlatformEvents::entities_unloading`).
    pub fn entities_unloading(&self, entities: &HashSet<Uuid>) {
        self.each(|s| s.entities_unloading(entities));
    }

    /// A player joined: every service hears of it before scripts do (what's hidden from them is hidden again).
    pub fn player_joined(&self, player: &PlayerRef) {
        self.each(|s| s.player_joined(player));
    }

    /// A player left: every service forgets them, after scripts heard of it.
    pub fn player_quit(&self, player: &PlayerRef) {
        self.each(|s| s.player_quit(player));
    }

    /// An entity has gone for good (died, removed): what's kept for it goes, and its handlers.
    pub fn entity_gone(&self, entity: Uuid) {
        self.each(|s| s.entity_gone(entity));
        let Some(room) = &self.room else { return };
        let handle = netherforge_api::handles::Handle::Entity(netherforge_api::handles::Entity {
            id: crate::events::uuid_text(&entity),
        });
        if let Ok(pushed) = netherforge_api::handle::push_any(room.host().lua(), &handle) {
            room.scripts()
                .drop_target(&crate::scripts::Target::Handle(pushed));
        }
    }

    /// The world is saving (`PlatformEvents::world_saving`): what its services keep of it is written as of now.
    pub fn world_saving(&self, world: &str) {
        self.each(|s| s.world_saving(world));
        self.shared.store.commit();
    }

    /// A chunk is unloading, its scripts told: what its services keep of it is written now.
    pub fn chunk_unloading(&self, world: &str, chunk_x: i32, chunk_z: i32) {
        self.each(|s| s.chunk_unloading(world, chunk_x, chunk_z));
        self.shared.store.commit();
    }

    /// A chunk came into memory: its services hear of it before its scripts do (its blocks are the project's,
    /// tracked, before a script asks).
    pub fn chunk_loaded(&self, world: &str, chunk_x: i32, chunk_z: i32, generated: bool) {
        self.each(|s| s.chunk_loaded(world, chunk_x, chunk_z, generated));
        self.released();
    }

    /// Other plugins changed (`PlatformEvents::plugins_changed`).
    pub fn plugins_changed(&self) {
        self.each(|s| s.plugins_changed());
    }

    /// A block was placed or broken.
    pub fn block_changed(&self, block: &BlockRef) {
        self.each(|s| s.block_changed(block));
    }

    /// Every service's hook, in registration order, while it runs.
    fn each(&self, hook: impl Fn(&dyn Service)) {
        if self.room.is_none() {
            return;
        }
        for service in &self.services {
            hook(&**service);
        }
    }

    /// The wall clock schedules read, in Unix milliseconds: a test's, or the system's when none.
    pub fn set_wall_clock(&self, millis: Option<i64>) {
        self.shared.schedules.borrow_mut().now = millis;
    }

    /// What its services share with the room's API.
    pub fn shared(&self) -> &Rc<Shared> {
        &self.shared
    }

    /// The server it runs on.
    pub fn platform(&self) -> &dyn Platform {
        &*self.platform
    }

    /// What goes with the scopes that stopped since the last time (temporary centities).
    pub(crate) fn released(&self) {
        if let Some(room) = &self.room {
            let released = room.scripts().take_released();
            if !released.is_empty() {
                CentityContext::new(self.shared.clone(), room.scripts().clone())
                    .scopes_released(&released);
                for service in &self.services {
                    service.scope_released(&released);
                }
            }
        }
        let gone = std::mem::take(&mut *self.shared.gone.borrow_mut());
        for entity in gone {
            self.entity_gone(entity);
        }
    }

    /// Its room, while it runs.
    pub fn room(&self) -> Option<&Room> {
        self.room.as_ref()
    }

    /// What's wrong with the project as it reads: format's findings, the session's own refusal, and what its
    /// running scripts caused (a body that failed, a handler that errors), until the scope closes.
    pub fn problems(&self) -> Vec<Problem> {
        let refused = self.refusal.as_ref().and_then(|r| r.problem.clone());
        let mut reported = self.reported.borrow_mut();
        if let Some(room) = &self.room {
            let host = room.host();
            reported
                .problems
                .retain(|scope, _| host.scope_name(*scope).is_some());
        }
        self.project
            .problems
            .iter()
            .cloned()
            .chain(refused)
            .chain(reported.problems.values().flatten().cloned())
            .chain(self.services.iter().flat_map(|s| s.problems()))
            .collect()
    }

    /// One server tick: each service in order, then what the scripts reported.
    pub fn tick(&self) {
        let Some(room) = &self.room else { return };
        room.scripts().tick();
        for service in &self.services {
            service.tick(room, &*self.platform);
        }
        self.released();
        self.report();
        self.shared.classed.borrow_mut().clear();
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
            self.refusal = refusal(&*self.platform, &self.project);
            self.run();
            return;
        }
        self.project = load(self.source, self.packages);
        *self.shared.names.borrow_mut() = self.project.names();
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
            platform: &*self.platform,
        };
        // Whatever reloads next runs the Lua as it reads now.
        if let Some(modules) = self.shared.modules.borrow().clone() {
            modules.update(lua_of(&define));
        }
        for (kind, ids) in &by_kind {
            if let Some(service) = self.services.iter().find(|s| s.reloads().contains(kind)) {
                service.unload(room, kind, ids);
            }
        }
        self.released();
        for (kind, ids) in &by_kind {
            if let Some(service) = self.services.iter().find(|s| s.reloads().contains(kind)) {
                service.reload(room, kind, ids, &define);
            }
        }
        let mut kinds: BTreeSet<&str> = by_kind.keys().copied().collect();
        if changed.iter().any(|p| p == "client/vanilla.json") {
            kinds.insert("vanilla");
        }
        for service in &self.services {
            service.reloaded(room, &kinds, &define);
        }
        self.released();
        self.report();
    }

    /// The project's packs read again from disk and rebuilt (`nf.packs.rebuild`), when a script asked for it.
    pub fn rebuild_packs(&mut self) {
        if !std::mem::take(&mut self.shared.packs.borrow_mut().wanted) {
            return;
        }
        let paths: Vec<String> = self
            .source
            .files()
            .iter()
            .filter(|p| p.starts_with("resource_packs/"))
            .cloned()
            .collect();
        if !paths.is_empty() {
            self.reload(&paths);
        }
    }

    /// Stops every service, in reverse order, and closes the room.
    pub fn stop(&mut self) {
        let Some(room) = self.room.take() else { return };
        for service in self.services.iter().rev() {
            service.stop(&room, &*self.platform);
        }
        self.services = services(&self.shared);
    }

    /// What the scripts reported since the last time, to the log (Kotlin's `ScriptReports`): a body that
    /// failed, whose scope is disabled until it's reloaded; a handler, timer or task that failed, located,
    /// the same error again within `ERROR_QUIET_TICKS` only counted (the next line says how many were held
    /// back); one cancelled for failing `MAX_ERRORS` times in a row, which is always said. Each is a
    /// `script.error` problem too, until its scope closes. Then the lines scripts logged.
    fn report(&self) {
        let Some(room) = &self.room else { return };
        let log = self.platform.log();
        let scripts = room.scripts();
        let tick = scripts.tick_count();
        let mut reported = self.reported.borrow_mut();
        for report in scripts.take_reports() {
            let label = label(room, report.scope);
            let (message, file, line) = placed(&report.failure);
            let at = match (&file, line) {
                (Some(file), Some(line)) => format!(" ({file}:{line})"),
                (Some(file), None) => format!(" ({file})"),
                _ => String::new(),
            };
            if report.what == "body" {
                log.warn(format!(
                    "{label}: body failed: {message}{at}. It's disabled until it's reloaded."
                ));
            } else {
                let key = format!(
                    "{:?}|{}|{message}|{file:?}|{line:?}",
                    report.scope, report.what
                );
                let quiet = reported
                    .errors
                    .get(&key)
                    .is_some_and(|(last, _)| tick.saturating_sub(*last) < ERROR_QUIET_TICKS);
                if quiet {
                    if let Some((_, held)) = reported.errors.get_mut(&key) {
                        *held += 1;
                    }
                } else {
                    let held = reported.errors.get(&key).map_or(0, |(_, held)| *held);
                    reported.errors.insert(key, (tick, 0));
                    let more = if held > 0 {
                        format!(" (and {held} more like it)")
                    } else {
                        String::new()
                    };
                    log.warn(format!(
                        "{label}: {} failed: {message}{at}{more}",
                        report.what
                    ));
                }
                if report.gave_up {
                    log.warn(format!(
                        "{label}: {} failed {} times in a row, so it was cancelled{at}",
                        report.what,
                        crate::MAX_ERRORS
                    ));
                }
            }
            let problem = netherforge_format::codes::SCRIPT_ERROR.at(
                file.as_deref().unwrap_or("modules"),
                message,
                None,
            );
            let problem = Problem { line, ..problem };
            let kept = reported.problems.entry(report.scope).or_default();
            if !kept.contains(&problem) {
                kept.push(problem);
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
fn services(shared: &Rc<Shared>) -> Vec<Box<dyn Service>> {
    // Centities are defined before modules start: a module's body may spawn one. Their instances' scripts
    // stop before the modules they require do.
    vec![
        // Saved tables stop last, after every script's unload has had them.
        Box::new(crate::data::DataService {
            shared: shared.clone(),
            host: Default::default(),
        }),
        // Before anything that draws with the pack: menus' skins and scripts' glyphs read the build.
        Box::new(crate::packs::PacksService {
            shared: shared.clone(),
            problems: Default::default(),
        }),
        Box::new(crate::data::SessionTablesService {
            shared: shared.clone(),
        }),
        // Before any script runs, so `has_permission` already answers.
        Box::new(crate::permissions::PermissionsService {
            shared: shared.clone(),
        }),
        Box::new(CentitiesService {
            shared: shared.clone(),
            restored: Default::default(),
        }),
        // After centities: their sync comes first, so a centity moved this tick is where its effect plays.
        Box::new(crate::particles::ParticlesService {
            shared: shared.clone(),
            scripts: Default::default(),
        }),
        Box::new(crate::entity::EntitiesService {
            shared: shared.clone(),
            host: Default::default(),
        }),
        Box::new(crate::project_items::ItemsService {
            shared: shared.clone(),
            scripts: Default::default(),
        }),
        // Block data stops after the blocks, which pin the records of the tables it saves.
        Box::new(crate::block_data::BlockDataService {
            shared: shared.clone(),
            host: Default::default(),
        }),
        // Before any script runs: a module's body may place blocks. Before centities stop, so a block's centity
        // is its block's until the end.
        Box::new(crate::custom_blocks::BlocksService {
            shared: shared.clone(),
            scripts: Default::default(),
        }),
        // After items: a recipe's project items are built from their looks.
        Box::new(crate::recipes::RecipesService {
            shared: shared.clone(),
        }),
        Box::new(crate::loot::LootService {
            shared: shared.clone(),
        }),
        Box::new(crate::commands::CommandsService {
            shared: shared.clone(),
        }),
        Box::new(crate::dialog::DialogsService {
            shared: shared.clone(),
            scripts: Default::default(),
        }),
        Box::new(crate::menu::MenusService {
            shared: shared.clone(),
            scripts: Default::default(),
        }),
        Box::new(crate::team::TeamsService {
            shared: shared.clone(),
        }),
        Box::new(crate::bossbar::BarsService {
            shared: shared.clone(),
        }),
        // After centities and particle effects, as Kotlin's draws in the effects' phase.
        Box::new(crate::huds::HudsService {
            shared: shared.clone(),
        }),
        Box::new(crate::screen::ScreensService {
            shared: shared.clone(),
            scripts: Default::default(),
            home: Default::default(),
        }),
        Box::new(crate::sky::SkiesService {
            shared: shared.clone(),
        }),
        Box::new(crate::schedule::SchedulesService {
            shared: shared.clone(),
        }),
        Box::new(ModulesService {
            shared: shared.clone(),
            modules: Default::default(),
            again: Default::default(),
        }),
    ]
}

/// Centities: the project's compiled definitions, their instances' scripts, and their entities synced
/// each tick.
struct CentitiesService {
    shared: Rc<Shared>,
    /// The instances restored at start, whose scripts start once modules run.
    restored: std::cell::RefCell<Vec<Uuid>>,
}

impl CentitiesService {
    fn define(&self, project: &Define) {
        use netherforge_format::kind::centity::compiled::CompiledCentity;
        use netherforge_format::kinds::package_path;
        let compiled = project.project.compiled::<CompiledCentity>();
        let mut scripts = BTreeMap::new();
        for (id, resource) in project.project.everywhere("centity") {
            let Some(script) = compiled
                .iter()
                .find(|(c, _)| *c == id)
                .and_then(|(_, c)| c.script.as_ref())
            else {
                continue;
            };
            let found = &resource.found;
            let folder = found.kind.location_of(&found.id, &found.group);
            let path = format!("{folder}/{}", script.file);
            let path = match netherforge_format::kinds::split_package(&id) {
                (Some(namespace), _) => package_path(namespace, &path),
                (None, _) => path,
            };
            scripts.insert(id, path);
        }
        let definitions = compiled
            .into_iter()
            .map(|(id, c)| (id, Rc::new(c.clone())))
            .collect();
        self.shared.centities.borrow_mut().define(definitions);
        *self.shared.centity_scripts.borrow_mut() = scripts;
        *self.shared.font.borrow_mut() = project.project.default_font.clone();
    }

    fn context(&self, room: &Room) -> CentityContext {
        CentityContext::new(self.shared.clone(), room.scripts().clone())
    }
}

impl Service for CentitiesService {
    fn name(&self) -> &'static str {
        "centities"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["centity", "buff"]
    }

    fn start(&self, room: &Room, project: &Define) {
        self.define(project);
        self.context(room).define_buffs(buffs(project));
        let mut centities = self.shared.centities.borrow_mut();
        centities.set_home(project.project.namespace());
        *self.restored.borrow_mut() = centities.restore(project.platform, &self.shared.store);
    }

    fn after_start(&self, room: &Room) {
        let restored = self.restored.take();
        self.context(room).start_restored(&restored);
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        context.tick();
        context.commit();
    }

    fn reload(&self, room: &Room, kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let context = self.context(room);
        if kind == "buff" {
            context.define_buffs(buffs(project));
            return;
        }
        let before = context.instances_of(ids);
        self.define(project);
        context.reload(ids, &before);
    }

    /// Instances stay in the world, saved, for the next session to restore; temporary ones go.
    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).stop_all();
        self.shared.stats.borrow_mut().clear();
        self.shared.physics.borrow_mut().clear();
        self.shared.images.clear();
        *self.shared.spawner.borrow_mut() = Default::default();
    }

    /// What's hidden from them is hidden again: the server forgot it.
    fn player_joined(&self, player: &PlayerRef) {
        self.shared
            .centities
            .borrow()
            .player_joined(&*self.shared.platform, player.uuid);
    }

    /// The natural spawner's passes for them go.
    fn player_quit(&self, player: &PlayerRef) {
        self.shared.spawner.borrow_mut().forget(player.uuid);
    }

    /// Their parts are pushed whole and hidden again; strays are removed.
    fn entities_loaded(&self, tagged: &HashMap<Uuid, EntityTag>, _untagged: &[Uuid]) {
        self.shared
            .centities
            .borrow_mut()
            .entities_loaded(&*self.shared.platform, tagged);
    }

    /// Where its instances stand now goes to the store, as of the same moment.
    fn world_saving(&self, world: &str) {
        self.shared
            .centities
            .borrow_mut()
            .save_placements_of(&self.shared.store, &|i| i.anchor().world == world);
    }

    /// The entities at its instances' anchors go with it: their places are saved now.
    fn chunk_unloading(&self, world: &str, chunk_x: i32, chunk_z: i32) {
        let chunk = |c: f64| (c.floor() as i64).div_euclid(16) as i32;
        self.shared
            .centities
            .borrow_mut()
            .save_placements_of(&self.shared.store, &|i| {
                let a = i.anchor();
                a.world == world && chunk(a.x) == chunk_x && chunk(a.z) == chunk_z
            });
    }

    /// Bodies near it run into the blocks as they are now.
    fn block_changed(&self, block: &BlockRef) {
        self.shared
            .physics
            .borrow_mut()
            .block_changed(&block.world, block.x, block.y, block.z);
    }
}

/// The project's buffs, by id.
fn buffs(project: &Define) -> BTreeMap<String, netherforge_format::kind::buff::BuffFile> {
    project
        .project
        .running::<netherforge_format::kind::buff::BuffFile>()
        .into_iter()
        .map(|(id, file)| (id, file.clone()))
        .collect()
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

/// Every namespace of the project's tree: the project's own, then each package's.
fn namespaces(project: &Loaded) -> Vec<String> {
    std::iter::once(project.namespace().to_owned())
        .chain(project.packages.keys().cloned())
        .collect()
}

/// What each package of the tree declared it may do: its manifest's `requires`, by namespace.
fn grants(project: &Loaded) -> crate::Grants {
    crate::Grants {
        home: project.namespace().to_owned(),
        requires: namespaces(project)
            .into_iter()
            .map(|ns| {
                let requires = project.manifest_of(&ns).and_then(|m| m.requires.clone());
                (ns, requires)
            })
            .collect(),
    }
}

/// Modules: every module of the project's started in a scope of its own, and one that changed restarted
/// with every scope that required it.
struct ModulesService {
    shared: Rc<Shared>,
    modules: std::cell::RefCell<Option<Modules>>,
    /// The modules a reload's first half closed, which its second half starts again.
    again: std::cell::RefCell<Vec<String>>,
}

/// The project's Lua the room runs, its packages' included: every Lua file by project path (a package's at its
/// package path, `acme:modules/api/init.lua`), every module's place (errors or not: a file is Lua the server
/// runs, and its problems are reported as the project's), and what each package depends on and exports.
fn lua_of(define: &Define) -> modules::Project {
    use netherforge_format::kinds::package_path;
    let project = define.project;
    let lua = |path: &String| path.ends_with(".lua");
    let mut files: BTreeMap<String, String> = define
        .source
        .files()
        .iter()
        .filter(|p| lua(p))
        .filter_map(|p| Some((p.clone(), define.source.read(p)?)))
        .collect();
    for (namespace, package) in &project.packages {
        let source = &package.resolved.source;
        for path in source.files().iter().filter(|p| lua(p)) {
            if let Some(text) = source.read(path) {
                files.insert(package_path(namespace, path), text);
            }
        }
    }
    let places = project
        .everywhere("module")
        .into_iter()
        .map(|(id, resource)| {
            let found = &resource.found;
            let location = found.kind.location_of(&found.id, &found.group);
            let place = match netherforge_format::kinds::split_package(&id) {
                (Some(namespace), _) => package_path(namespace, &location),
                (None, _) => location,
            };
            (id, place)
        })
        .collect();
    let packages = namespaces(project)
        .into_iter()
        .map(|ns| {
            let manifest = project.manifest_of(&ns);
            let info = modules::PackageModules {
                dependencies: manifest
                    .and_then(|m| m.dependencies.as_ref())
                    .map(|d| d.iter().map(|(k, _)| k.0.clone()).collect())
                    .unwrap_or_default(),
                exports: manifest
                    .and_then(|m| m.exports.as_ref())
                    .and_then(|e| e.get_str("modules"))
                    .map(|ids| ids.iter().cloned().collect())
                    .unwrap_or_default(),
            };
            (ns, info)
        })
        .collect();
    modules::Project {
        files,
        home: project.namespace().to_owned(),
        places,
        packages,
    }
}

impl Service for ModulesService {
    fn name(&self) -> &'static str {
        "modules"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["module"]
    }

    fn start(&self, room: &Room, project: &Define) {
        let modules = Modules::new(room.scripts().clone(), lua_of(project), MODULE_BUDGET);
        *self.shared.modules.borrow_mut() = Some(modules.clone());
        *self.modules.borrow_mut() = Some(modules.clone());
        modules.start();
    }

    /// Each changed module's scope closes, and every module's that required one.
    fn unload(&self, _room: &Room, _kind: &str, ids: &BTreeSet<String>) {
        let Some(modules) = self.modules.borrow().clone() else {
            return;
        };
        let again = self.again(&modules, ids);
        for id in &again {
            modules.unload(id);
        }
        *self.again.borrow_mut() = again;
    }

    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, project: &Define) {
        let Some(modules) = self.modules.borrow().clone() else {
            return;
        };
        let lua = lua_of(project);
        for id in self.again.take() {
            // A module that's gone, or now has errors, stops; its failures are in the reports.
            let _ = modules.reload(&id, lua.clone());
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        *self.modules.borrow_mut() = None;
        *self.shared.modules.borrow_mut() = None;
    }
}

impl ModulesService {
    /// Each changed module, then every module that required one, once.
    fn again(&self, modules: &Modules, ids: &BTreeSet<String>) -> Vec<String> {
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
        again
    }
}
