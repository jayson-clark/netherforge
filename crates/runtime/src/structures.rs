//! Structures (Kotlin's `StructureStore`, `GradualJobs` and `StructureSpawns`): the project's (`structures/<id>.nbt`),
//! its builds (`builds/<id>/`, a grid of structure files placed only gradually) and the ones scripts save; placing
//! them at once or over ticks, gradual fills, and the centities structures' markers name.
//!
//! - **An id names one thing**: a build can't take a structure's (format's `build.id-taken`), and a save can't take
//!   either's. Saves go to `<data>/.nf/structures/<id>.nbt`, never into the project, so a server saving an arena can't
//!   leave a deployed project dirty; they survive restarts and reloads. A project file that's saved again is read
//!   again (the Platform forgets the old one).
//! - **Gradual work** (`world:place_gradually`, `world:fill_gradually`) is a scope's own. Every job going shares
//!   `structures.blocks-per-tick` a tick, handed out in turn (the first served moves round every tick) and in equal
//!   shares, each never past its own `per_tick`; a package has at most `structures.max-jobs-per-package` going. A job
//!   ends when it's done (its value is what it did), when its world goes or a part can't be read (it fails, saying
//!   why), or when its scope stops or the session does (dropped: nobody's left to hear). What it did stays. A
//!   placement reads a build's parts one at a time, and puts entities in last, after every block (an item frame needs
//!   its wall), from a second read of only the parts that hold any.
//! - **Markers**: a structure's `marker` entities tagged `nf.centity.<centity>` turn into the centity they name once
//!   they're in the world, once only (the marker goes first). One naming a centity the project doesn't have stays,
//!   and is looked at again when the centities reload.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::PathBuf;
use std::rc::Rc;

use netherforge_api::api::NfStructuresApi;
use netherforge_api::{ApiError, ApiResult, Caller, values};
use netherforge_format::kind::files::{PART_SIDE, part_of};
use netherforge_format::names;
use netherforge_luau::ScopeId;
use netherforge_platform::{
    BlockVector, Platform, StructureMarker, StructurePlacement, StructurePlacer, StructureTransform,
};

use crate::screen::video::ProjectPaths;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::{Room, Scripts};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The longest side a saved structure may have: what a structure block saves.
pub const MAX_SIDE: i32 = 48;
/// How many blocks one `world:fill_gradually` changes at most: `FILL_GRADUALLY_LIMIT` in the spec.
pub const FILL_GRADUALLY_LIMIT: i64 = 16_777_216;
/// What placing one part's entities counts as against the tick's blocks: a part's worth of reading.
const ENTITY_PASS_COST: usize = 1024;
const EXTENSION: &str = ".nbt";

/// `structures:` in `config.yml`, read on dev servers too.
#[derive(Clone, Copy, Debug)]
pub struct StructuresConfig {
    /// Blocks every job going shares a tick.
    pub blocks_per_tick: usize,
    /// Jobs a package may have going at once.
    pub max_jobs_per_package: usize,
}

impl Default for StructuresConfig {
    fn default() -> StructuresConfig {
        StructuresConfig {
            blocks_per_tick: 8192,
            max_jobs_per_package: 4,
        }
    }
}

/// A build of the project's: its folder as a project path, and how many parts it has along x, y and z.
#[derive(Clone, Debug)]
struct Build {
    location: String,
    grid: (i32, i32, i32),
}

impl Build {
    fn shown(&self, x: i32, y: i32, z: i32) -> String {
        format!("{}/{x}_{y}_{z}{EXTENSION}", self.location)
    }
}

/// One file of what a placement places: a structure's one, or a build's part at `offset` in the build, with the
/// size its place in the grid says it has (none for a structure, which has any).
#[derive(Clone, Debug)]
pub struct Piece {
    file: PathBuf,
    shown: String,
    offset: BlockVector,
    expected: Option<BlockVector>,
}

impl Piece {
    fn check(&self, actual: &BlockVector) -> Option<String> {
        let expected = self.expected.as_ref()?;
        (actual != expected).then(|| {
            format!(
                "{} is {} × {} × {} blocks, but its place in the build's grid makes it {} × {} × {} (every part is {PART_SIDE} a side but the last along each axis)",
                self.shown, actual.x, actual.y, actual.z, expected.x, expected.y, expected.z
            )
        })
    }
}

/// A gradual job's value, once it's over: what it did, or why it stopped.
type Outcome = Rc<RefCell<Option<Result<i64, String>>>>;

enum Work {
    Place {
        world: String,
        pieces: Vec<Piece>,
        origin: BlockVector,
        placement: StructurePlacement,
        seed: i64,
        index: usize,
        placer: Option<Box<dyn StructurePlacer>>,
        placed: i64,
        /// Pieces whose entities go in once every block is: their indexes.
        with_entities: Vec<usize>,
        entities_done: usize,
    },
    Fill {
        world: String,
        min: BlockVector,
        max: BlockVector,
        state: String,
        update: bool,
        /// The chunk columns the box covers, in order: x fastest, then z.
        columns: Vec<(i32, i32)>,
        column: usize,
        /// How far into the current column it is, bottom layer first, x then z within a layer.
        at: i64,
        changed: i64,
    },
}

struct Job {
    scope: ScopeId,
    namespace: String,
    per_tick: usize,
    outcome: Outcome,
    work: Work,
}

impl Job {
    fn done(&self) -> bool {
        self.outcome.borrow().is_some()
    }

    fn finish(&mut self, value: i64) {
        self.close();
        *self.outcome.borrow_mut() = Some(Ok(value));
    }

    fn fail(&mut self, why: String) {
        self.close();
        *self.outcome.borrow_mut() = Some(Err(why));
    }

    fn drop_with(&mut self, why: &str) {
        if !self.done() {
            self.fail(why.into());
        }
    }

    fn close(&mut self) {
        if let Work::Place { placer, .. } = &mut self.work {
            *placer = None;
        }
    }

    /// Goes through up to `budget` blocks; answers how many it went through.
    fn run(&mut self, platform: &dyn Platform, budget: usize, effects: &mut Effects) -> usize {
        match &mut self.work {
            Work::Place { .. } => self.place(platform, budget, effects),
            Work::Fill { .. } => self.fill(platform, budget, effects),
        }
    }

    fn place(&mut self, platform: &dyn Platform, budget: usize, effects: &mut Effects) -> usize {
        let mut went = 0usize;
        loop {
            if went >= budget || self.done() {
                return went;
            }
            let Work::Place {
                world,
                pieces,
                origin,
                placement,
                seed,
                index,
                placer,
                placed,
                with_entities,
                entities_done,
            } = &mut self.work
            else {
                return went;
            };
            let gone = format!("the world \"{world}\" has gone");
            let open = |i: usize| -> Result<Box<dyn StructurePlacer>, String> {
                let piece = &pieces[i];
                let transform = StructureTransform {
                    origin: origin.clone(),
                    offset: piece.offset.clone(),
                    rotation: placement.rotation,
                    mirror: placement.mirror.clone(),
                };
                let Some(opened) = platform.structures().placer(
                    piece.file.clone(),
                    world.clone(),
                    transform,
                    placement.clone(),
                    *seed + i as i64,
                ) else {
                    return Err(if platform.worlds().exists(world.clone()) {
                        format!("{} can't be read as a structure", piece.shown)
                    } else {
                        gone.clone()
                    });
                };
                if let Some(why) = piece.check(&opened.size()) {
                    return Err(why);
                }
                Ok(opened)
            };
            if *index < pieces.len() {
                if placer.is_none() {
                    match open(*index) {
                        Ok(p) => *placer = Some(p),
                        Err(why) => {
                            self.fail(why);
                            return went.max(1);
                        }
                    }
                }
                let current = placer.as_ref().expect("opened");
                let Some(moved) = current.place((budget - went).min(i32::MAX as usize) as i32)
                else {
                    self.fail(gone);
                    return went.max(1);
                };
                went += moved.max(0) as usize;
                if current.remaining() == 0 {
                    *placed += current.placed();
                    if placement.entities && current.has_entities() {
                        with_entities.push(*index);
                    }
                    *placer = None;
                    *index += 1;
                } else if moved == 0 {
                    return went;
                }
            } else if *entities_done < with_entities.len() {
                // Read again for its entities alone: its blocks are long in.
                let piece = match open(with_entities[*entities_done]) {
                    Ok(p) => p,
                    Err(why) => {
                        self.fail(why);
                        return went.max(1);
                    }
                };
                if !piece.entities() {
                    self.fail(gone);
                    return went.max(1);
                }
                *entities_done += 1;
                went += ENTITY_PASS_COST;
            }
            if let Work::Place {
                pieces,
                index,
                with_entities,
                entities_done,
                placed,
                ..
            } = &self.work
                && *index == pieces.len()
                && *entities_done == with_entities.len()
            {
                if !with_entities.is_empty() {
                    effects.markers = true;
                }
                let placed = *placed;
                self.finish(placed);
            }
        }
    }

    fn fill(&mut self, platform: &dyn Platform, budget: usize, effects: &mut Effects) -> usize {
        let Work::Fill { world, .. } = &self.work else {
            return 0;
        };
        let world = world.clone();
        let gone = format!("the world \"{world}\" has gone");
        let worlds = platform.worlds();
        let Some((bottom, top)) = worlds
            .exists(world.clone())
            .then(|| worlds.heights(world.clone()))
            .flatten()
        else {
            self.fail(gone);
            return 1;
        };
        let mut went = 0usize;
        while went < budget && !self.done() {
            let Work::Fill {
                min,
                max,
                state,
                update,
                columns,
                column,
                at,
                changed,
                ..
            } = &mut self.work
            else {
                break;
            };
            let (cx, cz) = columns[*column];
            let x0 = min.x.max(cx << 4);
            let x1 = max.x.min((cx << 4) + 15);
            let z0 = min.z.max(cz << 4);
            let z1 = max.z.min((cz << 4) + 15);
            let depth = i64::from(z1 - z0 + 1);
            let layer = i64::from(x1 - x0 + 1) * depth;
            let volume = layer * i64::from(max.y - min.y + 1);
            if *at == 0
                && !worlds.is_chunk_loaded(world.clone(), cx, cz)
                && !worlds.load_chunk(world.clone(), cx, cz)
            {
                self.fail(gone);
                return went.max(1);
            }
            let count = ((budget - went) as i64).min(volume - *at);
            for i in 0..count {
                let k = *at + i;
                let y = min.y + (k / layer) as i32;
                if y < bottom || y >= top {
                    continue;
                }
                let in_layer = k % layer;
                let x = x0 + (in_layer / depth) as i32;
                let z = z0 + (in_layer % depth) as i32;
                if platform
                    .blocks()
                    .set(world.clone(), x, y, z, state.clone(), *update)
                {
                    *changed += 1;
                    effects
                        .replaced
                        .push((world.clone(), x, y, z, state.clone()));
                }
            }
            *at += count;
            went += count as usize;
            if *at == volume {
                *column += 1;
                *at = 0;
                if *column == columns.len() {
                    let changed = *changed;
                    self.finish(changed);
                }
            }
        }
        went
    }
}

/// What a tick's jobs did that others hear of: blocks a fill set (custom blocks forget what they replaced), and
/// whether a placement put entities in (their markers may spawn centities).
#[derive(Default)]
struct Effects {
    replaced: Vec<(String, i32, i32, i32, String)>,
    markers: bool,
}

/// The session's structures: where they come from, and the gradual work going.
#[derive(Default)]
pub struct Structures {
    pub config: StructuresConfig,
    /// The project's structures, by id: their project paths.
    structures: BTreeMap<String, String>,
    builds: BTreeMap<String, Build>,
    /// Where the project's files are on disk: the adapter's.
    pub paths: Option<ProjectPaths>,
    /// `<data>/.nf/structures`, where scripts' saves go; none: there's nowhere to save.
    pub saved: Option<PathBuf>,
    jobs: Vec<Job>,
    turn: usize,
    /// The centities markers named that the project doesn't have, said once.
    warned: HashSet<String>,
}

impl Structures {
    /// Reads where the project's structures and builds are.
    fn define(&mut self, project: &netherforge_format::project::Project) {
        self.structures = project
            .resources
            .iter()
            .filter(|r| r.read && r.found.kind.id == "structure")
            .map(|r| {
                (
                    r.found.id.clone(),
                    project.place_of("structure", &r.found.id),
                )
            })
            .collect();
        self.builds = project
            .resources
            .iter()
            .filter(|r| r.read && r.found.kind.id == "build")
            .map(|r| {
                let parts: Vec<(u32, u32, u32)> =
                    r.found.inside.iter().filter_map(|f| part_of(f)).collect();
                let most = |axis: fn(&(u32, u32, u32)) -> u32| {
                    parts.iter().map(axis).max().unwrap_or(0) as i32 + 1
                };
                let location = r.found.kind.location_of(&r.found.id, &r.found.group);
                (
                    r.found.id.clone(),
                    Build {
                        location,
                        grid: (most(|p| p.0), most(|p| p.1), most(|p| p.2)),
                    },
                )
            })
            .collect();
    }

    fn disk(&self, path: &str) -> Option<PathBuf> {
        self.paths.as_ref().and_then(|p| (p.0)(path))
    }

    fn saved_file(&self, id: &str) -> Option<PathBuf> {
        Some(self.saved.as_ref()?.join(format!("{id}{EXTENSION}")))
    }

    /// The file holding structure `id` and how to name it: the project's, else one a script saved.
    fn file(&self, id: &str) -> Option<(PathBuf, String)> {
        if let Some(path) = self.structures.get(id) {
            return Some((self.disk(path)?, path.clone()));
        }
        if !names::is_id(id) {
            return None;
        }
        let file = self.saved_file(id)?;
        file.is_file()
            .then(|| (file, format!("the structure saved as \"{id}\"")))
    }

    pub fn exists(&self, id: &str) -> bool {
        self.file(id).is_some() || self.builds.contains_key(id)
    }

    fn saved_ids(&self) -> Vec<String> {
        let Some(dir) = &self.saved else {
            return Vec::new();
        };
        let Ok(entries) = std::fs::read_dir(dir) else {
            return Vec::new();
        };
        entries
            .filter_map(|e| e.ok()?.file_name().into_string().ok())
            .filter_map(|n| n.strip_suffix(EXTENSION).map(str::to_owned))
            .collect()
    }

    fn missing(&self, id: &str) -> ApiError {
        let mut known: Vec<String> = self
            .structures
            .keys()
            .chain(self.builds.keys())
            .cloned()
            .collect();
        known.sort();
        known.extend(self.saved_ids());
        err(format!(
            "{} (the project's are structures/<id>.nbt and builds/<id>/; scripts add more with world:save_structure)",
            crate::centity::api::not_in_project("structure", id, &known)
        ))
    }

    fn unreadable(&self, id: &str, file: &std::path::Path) -> ApiError {
        let shown = self
            .structures
            .get(id)
            .cloned()
            .unwrap_or_else(|| format!("the structure saved as \"{id}\""));
        err(format!(
            "{shown} can't be read as a structure{}",
            if file.is_file() { "" } else { " (it's gone)" }
        ))
    }

    /// A build's size: its whole parts, and what its last part along each axis holds.
    fn build_size(&self, platform: &dyn Platform, build: &Build) -> ApiResult<BlockVector> {
        let (gx, gy, gz) = build.grid;
        let shown = build.shown(gx - 1, gy - 1, gz - 1);
        let last = self.disk(&shown);
        let tail = last
            .clone()
            .and_then(|f| platform.structures().size(f))
            .ok_or_else(|| {
                err(format!(
                    "{shown} can't be read as a structure{}",
                    if last.is_some_and(|f| f.is_file()) {
                        ""
                    } else {
                        " (it's gone)"
                    }
                ))
            })?;
        let side = PART_SIDE as i32;
        Ok(BlockVector {
            x: (gx - 1) * side + tail.x,
            y: (gy - 1) * side + tail.y,
            z: (gz - 1) * side + tail.z,
        })
    }

    /// How big structure or build `id` is; none when there's nothing by that id.
    pub fn size(&self, platform: &dyn Platform, id: &str) -> ApiResult<Option<BlockVector>> {
        if let Some((file, _)) = self.file(id) {
            return platform
                .structures()
                .size(file.clone())
                .map(Some)
                .ok_or_else(|| self.unreadable(id, &file));
        }
        match self.builds.get(id) {
            Some(build) => self.build_size(platform, build).map(Some),
            None => Ok(None),
        }
    }

    /// What a gradual placement of `id` goes through: a structure's one file, or a build's parts from the bottom
    /// up, each with the size its place in the grid says it has.
    fn pieces(&self, platform: &dyn Platform, id: &str) -> ApiResult<Vec<Piece>> {
        if let Some((file, shown)) = self.file(id) {
            return Ok(vec![Piece {
                file,
                shown,
                offset: BlockVector { x: 0, y: 0, z: 0 },
                expected: None,
            }]);
        }
        let build = self.builds.get(id).ok_or_else(|| self.missing(id))?;
        let size = self.build_size(platform, build)?;
        let (gx, gy, gz) = build.grid;
        let side = PART_SIDE as i32;
        let along = |part: i32, parts: i32, whole: i32| {
            if part < parts - 1 {
                side
            } else {
                whole - (parts - 1) * side
            }
        };
        let mut pieces = Vec::new();
        for j in 0..gy {
            for k in 0..gz {
                for i in 0..gx {
                    let shown = build.shown(i, j, k);
                    let file = self.disk(&shown).unwrap_or_else(|| PathBuf::from(&shown));
                    pieces.push(Piece {
                        file,
                        shown,
                        offset: BlockVector {
                            x: i * side,
                            y: j * side,
                            z: k * side,
                        },
                        expected: Some(BlockVector {
                            x: along(i, gx, size.x),
                            y: along(j, gy, size.y),
                            z: along(k, gz, size.z),
                        }),
                    });
                }
            }
        }
        Ok(pieces)
    }

    /// The project's structure or build files that changed: the Platform reads them again next time.
    fn forget(&self, platform: &dyn Platform, kind: &str, id: &str) {
        match kind {
            "build" => {
                let Some(build) = self.builds.get(id) else {
                    return;
                };
                let (gx, gy, gz) = build.grid;
                for j in 0..gy {
                    for k in 0..gz {
                        for i in 0..gx {
                            if let Some(file) = self.disk(&build.shown(i, j, k)) {
                                platform.structures().forget(file);
                            }
                        }
                    }
                }
            }
            _ => {
                if let Some(file) = self.structures.get(id).and_then(|p| self.disk(p)) {
                    platform.structures().forget(file);
                }
            }
        }
    }

    /// One tick of gradual work: every job's share of the tick's blocks.
    fn pass(&mut self, platform: &dyn Platform) -> Effects {
        let mut effects = Effects::default();
        if self.jobs.is_empty() {
            return effects;
        }
        let n = self.jobs.len();
        let order: Vec<usize> = (0..n).map(|i| (i + self.turn) % n).collect();
        self.turn = (self.turn + 1) % n.max(1);
        let mut used = vec![0usize; n];
        let mut budget = self.config.blocks_per_tick;
        while budget > 0 {
            let wanting: Vec<usize> = (0..n)
                .filter(|&i| {
                    let job = &self.jobs[order[i]];
                    !job.done() && used[i] < job.per_tick
                })
                .collect();
            if wanting.is_empty() {
                break;
            }
            let share = (budget / wanting.len()).max(1);
            let mut moved = false;
            for i in wanting {
                if budget == 0 {
                    break;
                }
                let job = &mut self.jobs[order[i]];
                let give = share.min(job.per_tick - used[i]).min(budget);
                let went = job.run(platform, give, &mut effects).min(budget);
                used[i] += went;
                budget -= went;
                if went > 0 {
                    moved = true;
                }
            }
            if !moved {
                break;
            }
        }
        self.jobs.retain(|j| !j.done());
        effects
    }

    /// How many jobs `scope` has going.
    pub fn count(&self, scope: ScopeId) -> usize {
        self.jobs.iter().filter(|j| j.scope == scope).count()
    }

    fn start(&mut self, job: Job) -> ApiResult<Outcome> {
        let going = self
            .jobs
            .iter()
            .filter(|j| j.namespace == job.namespace)
            .count();
        if going >= self.config.max_jobs_per_package {
            return Ok(Rc::new(RefCell::new(Some(Err(format!(
                "package \"{}\" already has {going} gradual placements and fills going, the server's limit (structures.max-jobs-per-package in config.yml)",
                job.namespace
            ))))));
        }
        let outcome = job.outcome.clone();
        self.jobs.push(job);
        Ok(outcome)
    }
}

/// A job's value as the room's future: ready once the job is over.
fn awaited(outcome: Outcome) -> netherforge_api::Pending<i64> {
    Box::pin(std::future::poll_fn(move |_| {
        match outcome.borrow_mut().take() {
            Some(result) => std::task::Poll::Ready(result.map_err(ApiError)),
            None => std::task::Poll::Pending,
        }
    }))
}

fn corners(a: (i32, i32, i32), b: (i32, i32, i32)) -> (BlockVector, BlockVector) {
    (
        BlockVector {
            x: a.0.min(b.0),
            y: a.1.min(b.1),
            z: a.2.min(b.2),
        },
        BlockVector {
            x: a.0.max(b.0),
            y: a.1.max(b.1),
            z: a.2.max(b.2),
        },
    )
}

fn vector((x, y, z): (i32, i32, i32)) -> BlockVector {
    BlockVector { x, y, z }
}

fn check_rotation(rotation: i64, integrity: f64) -> ApiResult<()> {
    if rotation % 90 != 0 {
        return Err(err(format!(
            "rotation must be a multiple of 90, not {rotation}"
        )));
    }
    if !(0.0..=1.0).contains(&integrity) {
        return Err(err(format!(
            "integrity must be between 0 and 1, not {integrity}"
        )));
    }
    Ok(())
}

/// A gradual job's own `per_tick`, or its share.
fn per_tick(value: Option<i64>) -> ApiResult<usize> {
    match value {
        None => Ok(usize::MAX),
        Some(v) if v < 1 => Err(err(format!("options.per_tick must be 1 or more, not {v}"))),
        Some(v) => Ok(v.min(i64::from(i32::MAX)) as usize),
    }
}

/// The structures' side of the API: what `World`'s structure methods and `nf.structures` do.
#[derive(Clone)]
pub struct StructuresContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl StructuresContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    pub fn save(
        &self,
        id: &str,
        world: &str,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
        entities: bool,
    ) -> ApiResult<bool> {
        if !names::is_id(id) {
            return Err(err(format!(
                "\"{id}\" can't name a structure ({})",
                names::ID_RULE
            )));
        }
        let structures = self.shared.structures.borrow();
        if let Some(path) = structures.structures.get(id) {
            return Err(err(format!(
                "\"{id}\" is one of the project's structures ({path}): save under another id"
            )));
        }
        if let Some(build) = structures.builds.get(id) {
            return Err(err(format!(
                "\"{id}\" is one of the project's builds ({}/): save under another id",
                build.location
            )));
        }
        let sides = [(from.0 - to.0), (from.1 - to.1), (from.2 - to.2)].map(|d| d.abs() + 1);
        if sides.iter().any(|s| *s > MAX_SIDE) {
            return Err(err(format!(
                "that box is {}×{}×{} blocks; a structure is at most {MAX_SIDE} along each side",
                sides[0], sides[1], sides[2]
            )));
        }
        if !self.platform().worlds().exists(world.to_owned()) {
            return Ok(false);
        }
        let Some(file) = structures.saved_file(id) else {
            return Err(err("the server has no data folder to save structures in"));
        };
        if let Some(dir) = file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let (min, max) = corners(from, to);
        let ops = self.platform().structures();
        let saved = ops.save(file.clone(), world.to_owned(), min, max, entities);
        // What the adapter read of the last file under this id is stale now.
        ops.forget(file);
        Ok(saved)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn place(
        &self,
        id: &str,
        world: &str,
        at: (i32, i32, i32),
        rotation: i64,
        mirror: String,
        integrity: f64,
        entities: bool,
    ) -> ApiResult<bool> {
        check_rotation(rotation, integrity)?;
        let file = {
            let structures = self.shared.structures.borrow();
            let found = structures.file(id);
            if found.is_none()
                && let Some(build) = structures.builds.get(id)
            {
                return Err(err(format!(
                    "\"{id}\" is a build ({}/), too big to place in one tick: place it with world:place_gradually",
                    build.location
                )));
            }
            found.ok_or_else(|| structures.missing(id))?.0
        };
        if !self.platform().worlds().exists(world.to_owned()) {
            return Ok(false);
        }
        let ops = self.platform().structures();
        if ops.size(file.clone()).is_none() {
            return Err(self.shared.structures.borrow().unreadable(id, &file));
        }
        let placement = StructurePlacement {
            rotation: rotation.rem_euclid(360) as i32,
            mirror,
            integrity,
            entities,
        };
        let placed = ops.place(file, world.to_owned(), vector(at), placement);
        if placed && entities {
            self.scan();
        }
        Ok(placed)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn place_gradually(
        &self,
        id: &str,
        world: &str,
        at: (i32, i32, i32),
        rotation: i64,
        mirror: String,
        integrity: f64,
        entities: bool,
        per: Option<i64>,
    ) -> ApiResult<netherforge_api::Pending<i64>> {
        check_rotation(rotation, integrity)?;
        let per_tick = per_tick(per)?;
        let pieces = self
            .shared
            .structures
            .borrow()
            .pieces(self.platform(), id)?;
        let scope = self.scope("World:place_gradually")?;
        if !self.platform().worlds().exists(world.to_owned()) {
            return Ok(gone(world));
        }
        let mut seed = [0u8; 8];
        getrandom::fill(&mut seed).expect("the system has randomness");
        let job = Job {
            scope,
            namespace: self.namespace(scope),
            per_tick,
            outcome: Rc::default(),
            work: Work::Place {
                world: world.to_owned(),
                pieces,
                origin: vector(at),
                placement: StructurePlacement {
                    rotation: rotation.rem_euclid(360) as i32,
                    mirror,
                    integrity,
                    entities,
                },
                seed: i64::from_le_bytes(seed),
                index: 0,
                placer: None,
                placed: 0,
                with_entities: Vec::new(),
                entities_done: 0,
            },
        };
        Ok(awaited(self.shared.structures.borrow_mut().start(job)?))
    }

    pub fn fill_gradually(
        &self,
        world: &str,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
        state: String,
        update: bool,
        per: Option<i64>,
    ) -> ApiResult<netherforge_api::Pending<i64>> {
        let volume = [(from.0, to.0), (from.1, to.1), (from.2, to.2)]
            .iter()
            .map(|(a, b)| i64::from((a - b).abs()) + 1)
            .product::<i64>();
        if volume > FILL_GRADUALLY_LIMIT {
            return Err(err(format!(
                "that box is {volume} blocks, and one fill_gradually changes at most {FILL_GRADUALLY_LIMIT}"
            )));
        }
        let per_tick = per_tick(per)?;
        let scope = self.scope("World:fill_gradually")?;
        if !self.platform().worlds().exists(world.to_owned()) {
            return Ok(gone(world));
        }
        let (min, max) = corners(from, to);
        let mut columns = Vec::new();
        for cz in (min.z >> 4)..=(max.z >> 4) {
            for cx in (min.x >> 4)..=(max.x >> 4) {
                columns.push((cx, cz));
            }
        }
        let job = Job {
            scope,
            namespace: self.namespace(scope),
            per_tick,
            outcome: Rc::default(),
            work: Work::Fill {
                world: world.to_owned(),
                min,
                max,
                state,
                update,
                columns,
                column: 0,
                at: 0,
                changed: 0,
            },
        };
        Ok(awaited(self.shared.structures.borrow_mut().start(job)?))
    }

    fn scope(&self, called: &str) -> ApiResult<ScopeId> {
        self.scripts
            .host()
            .running_scope()
            .ok_or_else(|| err(format!("{called} can only be called by a script")))
    }

    fn namespace(&self, scope: ScopeId) -> String {
        self.scripts
            .namespace(scope)
            .unwrap_or_else(|| self.shared.centities.borrow().home().to_owned())
    }

    /// One tick of gradual work, and what it did that others hear of.
    pub fn tick(&self) {
        let effects = self.shared.structures.borrow_mut().pass(self.platform());
        if !effects.replaced.is_empty() {
            let blocks = crate::custom_blocks::BlocksContext {
                shared: self.shared.clone(),
                scripts: self.scripts.clone(),
            };
            for (world, x, y, z, state) in effects.replaced {
                blocks.replaced(&world, x, y, z, &state);
            }
        }
        if effects.markers {
            self.scan();
        }
    }

    /// Spawns from every marker in the loaded world: as the session starts, after the centities reload, and after a
    /// structure was placed with its entities.
    pub fn scan(&self) {
        let markers = self.platform().entities().structure_markers();
        self.spawn(markers);
    }

    /// Turns each marker into the centity it names, once: the marker goes first.
    pub fn spawn(&self, markers: Vec<StructureMarker>) {
        let home = self.shared.centities.borrow().home().to_owned();
        let centities =
            crate::centity::api::CentityContext::new(self.shared.clone(), self.scripts.clone());
        for marker in markers {
            let name = netherforge_format::reference::resolve_any(&home, &marker.centity)
                .map(|k| k.relative_to(&home));
            let known = name
                .as_ref()
                .is_some_and(|n| self.shared.centities.borrow().definition(n).is_some());
            let Some(name) = name.filter(|_| known) else {
                if self
                    .shared
                    .structures
                    .borrow_mut()
                    .warned
                    .insert(marker.centity.clone())
                {
                    self.platform().log().warn(format!(
                        "A structure has a marker for the centity \"{}\", which the project doesn't have: the marker stays in the world, and spawns it once it does",
                        marker.centity
                    ));
                }
                continue;
            };
            // The marker goes first: if it can't be reached it isn't spawned from twice, only later.
            if !self.platform().entities().remove(marker.id) {
                continue;
            }
            self.shared
                .structures
                .borrow_mut()
                .warned
                .remove(&marker.centity);
            let at = marker.at.clone();
            if centities.spawn(&name, at.clone(), at.yaw).is_none() {
                self.platform().log().warn(format!(
                    "The centity \"{}\" couldn't be spawned from the structure marker at {} {} {} {}",
                    marker.centity, at.world, at.x as i64, at.y as i64, at.z as i64
                ));
            }
        }
    }
}

fn gone(world: &str) -> netherforge_api::Pending<i64> {
    let why = format!("world \"{world}\" has gone");
    Box::pin(async move { Err(err(why)) })
}

impl NfStructuresApi for StructuresContext {
    fn exists(&self, _: &Caller, id: String) -> ApiResult<bool> {
        Ok(self.shared.structures.borrow().exists(&id))
    }

    fn size(&self, _: &Caller, id: String) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .shared
            .structures
            .borrow()
            .size(self.platform(), &id)?
            .map(|s| values::Vec3 {
                x: f64::from(s.x),
                y: f64::from(s.y),
                z: f64::from(s.z),
            }))
    }
}

/// Structures as a session's service.
pub struct StructuresService {
    pub shared: Rc<Shared>,
}

impl StructuresService {
    fn context(&self, room: &Room) -> StructuresContext {
        StructuresContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }
}

impl Service for StructuresService {
    fn name(&self) -> &'static str {
        "structures"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["structure", "build"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        self.shared.structures.borrow_mut().define(project.project);
    }

    fn after_start(&self, room: &Room) {
        self.context(room).scan();
    }

    /// Nothing runs on a structure or a build, so nothing restarts: the next placement reads the files again.
    fn reload(&self, _room: &Room, kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let mut structures = self.shared.structures.borrow_mut();
        for id in ids {
            structures.forget(project.platform, kind, id);
        }
        structures.define(project.project);
        for id in ids {
            structures.forget(project.platform, kind, id);
        }
    }

    /// A marker's centity may have been added or changed: every marker still waiting is looked at.
    fn reloaded(&self, room: &Room, kinds: &BTreeSet<&str>, _project: &Define) {
        if kinds.contains("centity") {
            self.context(room).scan();
        }
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).tick();
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let mut structures = self.shared.structures.borrow_mut();
        for job in &mut structures.jobs {
            if scopes.contains(&job.scope) {
                job.drop_with("its script stopped");
            }
        }
        structures.jobs.retain(|j| !j.done());
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let mut structures = self.shared.structures.borrow_mut();
        for job in &mut structures.jobs {
            job.drop_with("the server's project stopped");
        }
        structures.jobs.clear();
    }
}
