//! The worlds a project makes and may take away (Kotlin's `ManagedWorlds`): `nf.worlds.create`, `load`, `copy`,
//! `import`, and `world:unload`, `world:is_managed`.
//!
//! - **Any script may find and load any world, but only a managed one can be unloaded or deleted**: one the
//!   project's scripts created, or one `netherforge.json` names in `managedWorlds`, never the server's main world.
//! - **The worlds scripts made are kept in the store** (its `worlds` table) under the project's namespace, with how
//!   each was made: the server doesn't remember a plugin's generator, and a world made with a project dimension type
//!   isn't loaded while the server lacks the type (it would be made the overworld's height). A world deleted through
//!   `unload` is forgotten. The worlds themselves are the server's: a reload leaves them loaded.
//! - **A copy's files, and a deleted world's, are a lane's** (`world files`), so they happen in the order they were
//!   asked for; the adapter only says what the file work is. A copy is then loaded on the main thread at the next
//!   tick and its world goes to the script that asked; a script that stops first isn't called, and the world is
//!   made and kept anyway.
//!
//! The worlds `netherforge.json` makes as the session starts (`worlds` with a `terrain` or a `dimensionType`) are
//! the terrains' service's (`terrains.rs`, once the generators are published to the server). A project terrain
//! named here is checked against the project's terrains that have no errors.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::mpsc;
use std::time::Instant;

use netherforge_api::{ApiError, ApiResult, Pending, handles, shapes};
use netherforge_format::names;
use netherforge_platform::{Platform, WorldSettings};

use crate::screen::video::ProjectPaths;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::{Room, Scripts};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The environment of a world nobody here made, and of every copy of a map.
const NORMAL: &str = "normal";
/// A load slower than this is said in the log.
const SLOW_LOAD_MILLIS: u128 = 50;
/// The lane worlds' files are copied and deleted on.
const FILES: &str = "world files";
/// Why the server lacks a dimension type the project has.
const RESTART: &str =
    "it learns them only as it starts, so one added or fixed since needs a restart";

/// `worlds:` in `config.yml`.
#[derive(Clone, Debug, Default)]
pub struct WorldsConfig {
    /// The folder imported worlds come from (a tool puts them there); none: importing is off.
    pub import_root: Option<PathBuf>,
    /// The most worlds loaded at once, the server's own included; 0 for any number.
    pub max_loaded: usize,
}

/// How a world the project made was made: what the store's `worlds` table keeps.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OwnedWorld {
    pub environment: String,
    pub terrain: Option<String>,
    pub dimension_type: Option<String>,
}

/// A copy being made: its files on the lane, then its load at a tick.
struct Copy {
    name: String,
    terrain: Option<String>,
    from: String,
    done: mpsc::Receiver<Result<(), String>>,
    outcome: Rc<RefCell<Option<Result<String, String>>>>,
}

/// The session's managed worlds.
#[derive(Default)]
pub struct ManagedWorlds {
    pub config: WorldsConfig,
    /// Whose worlds these are: the project's namespace.
    namespace: String,
    /// Worlds the project made, by name, with how each was made.
    created: BTreeMap<String, OwnedWorld>,
    /// `managedWorlds` in `netherforge.json`.
    named: BTreeSet<String>,
    /// The new worlds' names of the copies being made.
    copying: HashSet<String>,
    copies: Vec<Copy>,
    /// The project's maps, by id: their folders as project paths.
    maps: BTreeMap<String, String>,
    /// The project's terrains (and its packages'), as the server names them, that have no errors.
    terrains: BTreeSet<String>,
    /// The project's dimension types (and its packages'), as the project names them: whether each is running.
    dimension_types: BTreeMap<String, bool>,
    pub paths: Option<ProjectPaths>,
}

impl ManagedWorlds {
    /// How the project made `name`, if it did.
    pub fn made_with(&self, name: &str) -> Option<OwnedWorld> {
        self.created.get(name).cloned()
    }

    /// The project made (or loaded) `name` so: kept in the store, the project's from now on.
    pub fn remember(
        &mut self,
        shared: &Shared,
        name: &str,
        environment: &str,
        terrain: Option<String>,
        dimension_type: Option<String>,
    ) {
        let owned = OwnedWorld {
            environment: environment.to_owned(),
            terrain,
            dimension_type,
        };
        shared.store.own_world(&self.namespace, name, &owned);
        self.created.insert(name.to_owned(), owned);
    }
}

/// The managed worlds' side of the API.
#[derive(Clone)]
pub struct ManagedContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl ManagedContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// Whether the project may unload `name`: created by its scripts or named in `managedWorlds`, and not the main
    /// world.
    pub fn is_managed(&self, name: &str) -> bool {
        if name == self.platform().worlds().default_world() {
            return false;
        }
        let managed = self.shared.managed_worlds.borrow();
        managed.created.contains_key(name) || managed.named.contains(name)
    }

    /// A world more is loaded within `worlds.max-loaded`, or an error saying it isn't.
    fn check_room(&self) -> ApiResult<()> {
        let most = self.shared.managed_worlds.borrow().config.max_loaded;
        if most > 0 && self.platform().worlds().names().len() >= most {
            return Err(err(format!(
                "the server has {most} worlds loaded, its most (worlds.max-loaded in config.yml): unload one first"
            )));
        }
        Ok(())
    }

    fn check_new_name(&self, name: &str) -> ApiResult<()> {
        if !names::is_id(name) {
            return Err(err(format!(
                "\"{name}\" can't be a new world's name ({})",
                names::ID_RULE
            )));
        }
        // Before the files: a copy's files can be in place while it's still being loaded.
        if self.shared.managed_worlds.borrow().copying.contains(name) {
            return Err(err(format!(
                "a world named \"{name}\" is being copied already"
            )));
        }
        let platform = self.platform();
        if platform.worlds().exists(name.to_owned())
            || platform.world_manager().is_saved(name.to_owned())
        {
            return Err(err(format!(
                "there's already a world named \"{name}\" (nf.worlds.load loads a saved one)"
            )));
        }
        Ok(())
    }

    /// `load` timed, and logged when it took long enough to notice.
    fn timed(&self, name: &str, load: impl FnOnce() -> bool) -> bool {
        let started = Instant::now();
        let loaded = load();
        let millis = started.elapsed().as_millis();
        if loaded && millis >= SLOW_LOAD_MILLIS {
            self.platform()
                .log()
                .info(format!("Loaded world \"{name}\" in {millis} ms"));
        }
        loaded
    }

    fn own(&self, name: &str, owned: OwnedWorld) {
        let mut managed = self.shared.managed_worlds.borrow_mut();
        self.shared
            .store
            .own_world(&managed.namespace, name, &owned);
        managed.created.insert(name.to_owned(), owned);
    }

    /// `name` is gone (deleted, or its copy failed): nobody's any more.
    fn forget(&self, name: &str) {
        self.shared.managed_worlds.borrow_mut().created.remove(name);
        self.shared.store.forget_world(name);
    }

    /// The server's name of the project terrain `reference` names: an error naming what there is when it names none.
    fn terrain(&self, reference: &str) -> ApiResult<String> {
        let game = crate::world::GameContext::new(self.shared.clone(), self.scripts.clone());
        let id = game.with_names(|names| names.resource("terrain", reference))?;
        let managed = self.shared.managed_worlds.borrow();
        if managed.terrains.contains(&id) {
            return Ok(id);
        }
        let known: Vec<&str> = managed.terrains.iter().map(String::as_str).collect();
        Err(err(format!(
            "\"{reference}\" isn't one of the project's terrains{}",
            if known.is_empty() {
                " (it has none)".to_owned()
            } else {
                format!(" (it has: {})", known.join(", "))
            }
        )))
    }

    /// The server's key of the project dimension type `reference` names (`showcase:deep`).
    fn dimension_type(&self, reference: &str) -> ApiResult<String> {
        let game = crate::world::GameContext::new(self.shared.clone(), self.scripts.clone());
        let id = game.with_names(|names| names.resource("dimension_type", reference))?;
        let managed = self.shared.managed_worlds.borrow();
        match managed.dimension_types.get(&id) {
            Some(true) => Ok(if id.contains(':') {
                id
            } else {
                format!("{}:{id}", managed.namespace)
            }),
            Some(false) => Err(err(format!(
                "dimension type \"{reference}\" has errors (see its file), so the server can't have it"
            ))),
            None => {
                let known: Vec<&str> = managed
                    .dimension_types
                    .iter()
                    .filter(|(_, running)| **running)
                    .map(|(k, _)| k.as_str())
                    .collect();
                Err(err(format!(
                    "\"{reference}\" isn't one of the project's dimension types{}",
                    if known.is_empty() {
                        " (it has none)".to_owned()
                    } else {
                        format!(" (it has: {})", known.join(", "))
                    }
                )))
            }
        }
    }

    pub fn create(
        &self,
        name: &str,
        options: Option<shapes::WorldCreateOptions>,
    ) -> ApiResult<Option<handles::World>> {
        let options = options.unwrap_or(shapes::WorldCreateOptions {
            generator: None,
            terrain: None,
            environment: None,
            dimension_type: None,
            seed: None,
            structures: None,
            keep_spawn_loaded: None,
        });
        let environment = options.environment.unwrap_or_else(|| NORMAL.into());
        // One of the project's terrains (named as the calling package names it), or else a vanilla world type.
        let terrain = options
            .terrain
            .as_deref()
            .map(|t| self.terrain(t))
            .transpose()?;
        if terrain.is_some() && options.generator.is_some() {
            return Err(err(
                "a world has a generator (one of the game's world types) or a terrain (one of the project's), not both: leave out one",
            ));
        }
        if terrain.is_some() && environment != NORMAL {
            return Err(err(format!(
                "a terrain of the project makes a normal world, not a {environment}: leave out environment, or use a vanilla generator"
            )));
        }
        let dimension_type = options
            .dimension_type
            .as_deref()
            .map(|d| self.dimension_type(d))
            .transpose()?;
        let settings = WorldSettings {
            generator: options.generator.unwrap_or_else(|| NORMAL.into()),
            environment: environment.clone(),
            seed: options.seed,
            structures: options.structures.unwrap_or(true),
            keep_spawn_loaded: options.keep_spawn_loaded.unwrap_or(false),
            terrain: terrain.clone(),
            dimension_type: dimension_type.clone(),
        };
        self.check_new_name(name)?;
        self.check_room()?;
        let ops = self.platform().world_manager();
        if let Some(dimension) = &dimension_type
            && !ops.dimension_types().contains(dimension)
        {
            return Err(err(format!(
                "the server has no dimension type \"{dimension}\": {RESTART}"
            )));
        }
        if !ops.create(name.to_owned(), settings) {
            return Ok(None);
        }
        self.own(
            name,
            OwnedWorld {
                environment,
                terrain,
                dimension_type,
            },
        );
        Ok(Some(handles::World { name: name.into() }))
    }

    /// Loads a saved world; it may have been loaded already.
    pub fn load(&self, name: &str) -> ApiResult<Option<handles::World>> {
        if !names::is_world_name(name) {
            return Err(err(format!(
                "\"{name}\" isn't a world's name ({})",
                names::WORLD_NAME_RULE
            )));
        }
        let world = Some(handles::World { name: name.into() });
        let platform = self.platform();
        if platform.worlds().exists(name.to_owned()) {
            return Ok(world);
        }
        if self.shared.managed_worlds.borrow().copying.contains(name) {
            return Ok(None);
        }
        let ops = platform.world_manager();
        if !ops.is_saved(name.to_owned()) {
            return Ok(None);
        }
        self.check_room()?;
        let owned = self
            .shared
            .managed_worlds
            .borrow()
            .created
            .get(name)
            .cloned();
        if let Some(dimension) = owned.as_ref().and_then(|o| o.dimension_type.clone())
            && !ops.dimension_types().contains(&dimension)
        {
            return Err(err(format!(
                "world \"{name}\" was made with dimension type \"{dimension}\", which the server doesn't have: {RESTART}"
            )));
        }
        let environment = owned
            .as_ref()
            .map_or_else(|| NORMAL.to_owned(), |o| o.environment.clone());
        let terrain = owned.and_then(|o| o.terrain);
        let loaded = self.timed(name, || ops.load(name.to_owned(), environment, terrain));
        Ok(loaded.then_some(world).flatten())
    }

    /// Copies map `map` into a new world `name`, the project's from now on.
    pub fn copy(&self, map: &str, name: &str) -> ApiResult<Pending<handles::World>> {
        let folder = {
            let managed = self.shared.managed_worlds.borrow();
            let Some(location) = managed.maps.get(map) else {
                let known: Vec<String> = managed.maps.keys().cloned().collect();
                return Err(err(crate::centity::api::not_in_project("map", map, &known)));
            };
            managed
                .paths
                .as_ref()
                .and_then(|p| (p.0)(location))
                .ok_or_else(|| err(format!("the map \"{map}\" isn't on this server's disk")))?
        };
        self.check_new_name(name)?;
        self.check_room()?;
        let work = self
            .platform()
            .world_manager()
            .copy(folder, name.to_owned());
        Ok(self.copy_in(work, name, None, format!("map \"{map}\"")))
    }

    /// Imports the world in `folder` (under `worlds.import-root`) as a new world `name`, the project's from now on.
    pub fn import(
        &self,
        folder: &str,
        name: &str,
        terrain: Option<String>,
    ) -> ApiResult<Pending<handles::World>> {
        let Some(root) = self
            .shared
            .managed_worlds
            .borrow()
            .config
            .import_root
            .clone()
        else {
            return Err(err(
                "importing worlds is off on this server (worlds.import-root in config.yml)",
            ));
        };
        let source = normalize(&root.join(folder));
        if !source.starts_with(&root) || source == root {
            return Err(err(format!(
                "\"{folder}\" isn't a folder inside the import root"
            )));
        }
        if !source.is_dir() {
            return Err(err(format!(
                "there's no folder \"{folder}\" in the import root ({})",
                root.display()
            )));
        }
        let terrain = terrain.as_deref().map(|t| self.terrain(t)).transpose()?;
        self.check_new_name(name)?;
        self.check_room()?;
        let work = self
            .platform()
            .world_manager()
            .copy(source, name.to_owned());
        Ok(self.copy_in(work, name, terrain, format!("folder \"{folder}\"")))
    }

    /// `work` puts `name`'s files in place, then it's loaded (with `terrain`) as the project's at a tick.
    fn copy_in(
        &self,
        work: Box<dyn netherforge_platform::FileWork>,
        name: &str,
        terrain: Option<String>,
        from: String,
    ) -> Pending<handles::World> {
        self.own(
            name,
            OwnedWorld {
                environment: NORMAL.into(),
                terrain: terrain.clone(),
                dimension_type: None,
            },
        );
        let (send, done) = mpsc::channel();
        self.shared.lane(FILES).submit(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| work.run()))
                .map_err(|e| panic_text(&e));
            let _ = send.send(result);
        });
        let outcome: Rc<RefCell<Option<Result<String, String>>>> = Rc::default();
        let mut managed = self.shared.managed_worlds.borrow_mut();
        managed.copying.insert(name.to_owned());
        managed.copies.push(Copy {
            name: name.to_owned(),
            terrain,
            from,
            done,
            outcome: outcome.clone(),
        });
        Box::pin(std::future::poll_fn(move |_| {
            match outcome.borrow_mut().take() {
                Some(Ok(name)) => std::task::Poll::Ready(Ok(handles::World { name })),
                Some(Err(why)) => std::task::Poll::Ready(Err(ApiError(why))),
                None => std::task::Poll::Pending,
            }
        }))
    }

    /// The copies whose files are in place: each loaded now, or forgotten saying why it isn't.
    pub fn tick(&self) {
        let finished: Vec<(Copy, Result<(), String>)> = {
            let mut managed = self.shared.managed_worlds.borrow_mut();
            let mut finished = Vec::new();
            let mut going = Vec::new();
            for copy in std::mem::take(&mut managed.copies) {
                match copy.done.try_recv() {
                    Ok(result) => finished.push((copy, result)),
                    Err(mpsc::TryRecvError::Empty) => going.push(copy),
                    Err(mpsc::TryRecvError::Disconnected) => {
                        finished.push((copy, Err("the copy was stopped".into())))
                    }
                }
            }
            managed.copies = going;
            finished
        };
        for (copy, result) in finished {
            self.shared
                .managed_worlds
                .borrow_mut()
                .copying
                .remove(&copy.name);
            let why = match result {
                Err(e) => Some(format!("couldn't copy its files ({e})")),
                Ok(()) => {
                    let ops = self.platform().world_manager();
                    let loaded = self.timed(&copy.name, || {
                        ops.load(copy.name.clone(), NORMAL.into(), copy.terrain.clone())
                    });
                    (!loaded).then(|| "the server couldn't load the copy".to_owned())
                }
            };
            let outcome = match why {
                None => Ok(copy.name.clone()),
                Some(why) => {
                    self.forget(&copy.name);
                    self.platform().log().warn(format!(
                        "Couldn't copy {} into world \"{}\": {why}",
                        copy.from, copy.name
                    ));
                    Err(format!(
                        "couldn't copy {} into world \"{}\": {why}",
                        copy.from, copy.name
                    ))
                }
            };
            *copy.outcome.borrow_mut() = Some(outcome);
        }
    }

    /// Unloads `name`: its players go to `move_to`'s spawn first. With `delete` its files go too (on the lane),
    /// and the project forgets it.
    pub fn unload(
        &self,
        name: &str,
        save: bool,
        delete: bool,
        move_to: Option<String>,
    ) -> ApiResult<bool> {
        let platform = self.platform();
        let main = platform.worlds().default_world();
        if name == main {
            return Err(err("the server's main world can't be unloaded"));
        }
        if !self.is_managed(name) {
            return Err(err(format!(
                "\"{name}\" isn't this project's to unload: only worlds its scripts created, or that netherforge.json names in managedWorlds, can be"
            )));
        }
        if move_to.as_deref() == Some(name) {
            return Err(err("move_players_to is the world being unloaded"));
        }
        if !platform.worlds().exists(name.to_owned()) {
            return Ok(false);
        }
        let destination = move_to.unwrap_or(main);
        let spawn = platform
            .worlds()
            .spawn_location(destination.clone())
            .ok_or_else(|| {
                err(format!(
                    "there's no world named \"{destination}\" to move players to"
                ))
            })?;
        let players = platform.players();
        for player in players.online() {
            if players
                .location(player.uuid)
                .is_some_and(|l| l.world == name)
            {
                players.teleport(player.uuid, spawn.clone());
            }
        }
        let ops = platform.world_manager();
        if !ops.unload(name.to_owned(), save && !delete) {
            return Ok(false);
        }
        if delete {
            let work = ops.delete(name.to_owned());
            // Nobody waits for it, so a failure is the log's alone, said at a tick.
            let failures = self.shared.managed_failures.clone();
            let world = name.to_owned();
            self.shared.lane(FILES).submit(move || {
                if let Err(e) =
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| work.run()))
                {
                    failures
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .push(format!(
                            "Couldn't delete all of world \"{world}\"'s files: {}",
                            panic_text(&e)
                        ));
                }
            });
            self.forget(name);
        }
        Ok(true)
    }
}

/// What a failed piece of file work said (the adapter's work fails by panicking with its reason).
fn panic_text(e: &Box<dyn std::any::Any + Send>) -> String {
    e.downcast_ref::<String>()
        .cloned()
        .or_else(|| e.downcast_ref::<&str>().map(|s| (*s).to_owned()))
        .unwrap_or_else(|| "it failed".into())
}

/// `path` with its `.` and `..` worked out, without asking the disk.
fn normalize(path: &std::path::Path) -> PathBuf {
    let mut out = PathBuf::new();
    for part in path.components() {
        match part {
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other),
        }
    }
    out
}

/// Managed worlds as a session's service.
pub struct ManagedWorldsService {
    pub shared: Rc<Shared>,
}

impl Service for ManagedWorldsService {
    fn name(&self) -> &'static str {
        "managed worlds"
    }

    /// A map is read only when a world is copied from it: later copies see the change. A dimension type is the
    /// server's from its start.
    fn reloads(&self) -> &'static [&'static str] {
        &["map", "dimension_type"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        let namespace = project.project.namespace().to_owned();
        let created = self.shared.store.worlds_of(&namespace);
        let mut managed = self.shared.managed_worlds.borrow_mut();
        managed.created = created;
        managed.namespace = namespace;
        define(&mut managed, project);
    }

    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, project: &Define) {
        define(&mut self.shared.managed_worlds.borrow_mut(), project);
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        ManagedContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
        .tick();
        let failures = std::mem::take(
            &mut *self
                .shared
                .managed_failures
                .lock()
                .unwrap_or_else(|e| e.into_inner()),
        );
        for failure in failures {
            self.shared.platform.log().warn(failure);
        }
    }

    /// A copy still going is left to finish: its files stay, unloaded and the project's (`nf.worlds.load` loads it).
    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let mut managed = self.shared.managed_worlds.borrow_mut();
        managed.copies.clear();
        managed.copying.clear();
    }
}

/// What the project says of its worlds: `managedWorlds`, its maps, terrains and dimension types.
fn define(managed: &mut ManagedWorlds, project: &Define) {
    let loaded = project.project;
    managed.named = loaded
        .manifest
        .as_ref()
        .and_then(|m| m.managed_worlds.clone())
        .unwrap_or_default()
        .into_iter()
        .collect();
    managed.maps = loaded
        .resources
        .iter()
        .filter(|r| r.read && r.found.kind.id == "map")
        .map(|r| {
            (
                r.found.id.clone(),
                r.found.kind.location_of(&r.found.id, &r.found.group),
            )
        })
        .collect();
    managed.terrains = loaded
        .everywhere("terrain")
        .into_iter()
        .filter(|(_, r)| !r.errors)
        .map(|(name, _)| name)
        .collect();
    managed.dimension_types = loaded
        .present("dimension_type")
        .into_iter()
        .map(|(name, r)| (name, r.read && !r.errors))
        .collect();
}
