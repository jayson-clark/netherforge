//! A terrain's script on Luau (format's `TerrainScripts`, `ScriptRunner` and
//! `TerrainScriptState`): the stages format's generator hands its script,
//! run by the runtime's own host (`netherforge-luau`), not a second Lua host.
//! The server and the editor's preview both run it, so a preview can't drift
//! from the world.
//!
//! Each state is a Luau state of its own (a script can use 64 MB), made on a
//! thread's first need and kept in that thread's pool for the next call.
//! `glue.luau` is the API the script sees, the same as on Lua 5.4: what Lua 5.4
//! did in 64-bit integers (`math.random`'s stream, a plan's numbers) is here,
//! in Rust, so a seed makes the world it made.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use mlua::{Function, Lua, MultiValue, Table, UserData, Value};
use netherforge_format::block_state::BlockState;
use netherforge_format::kind::terrain::compiled::{CompiledTerrain, TerrainBlock};
use netherforge_format::kind::terrain::generate::{
    ChunkGeneration, ScriptSeeds, ScriptState, Stage, Stages, TerrainGenerator, TerrainScript,
    TerrainScripts,
};
use netherforge_format::kind::terrain::scripts::{ScriptFailure, module_paths};
use netherforge_format::noise::fast_noise_lite::FastNoiseLite;
use netherforge_luau::random::Random;
use netherforge_luau::{Failure, Host, Limits, ScopeId, Stop};

/// The glue a state runs before the script.
const GLUE: &str = include_str!("glue.luau");

/// The memory each state may hold.
pub const MEMORY_MB: usize = 64;

/// Who hears that a call failed: from whichever thread generates, as often as
/// a call fails (each failure is deterministic: the same place fails the same
/// way every time), so the caller says it once.
pub type Failed = Arc<dyn Fn(ScriptFailure) + Send + Sync>;

/// What a generator's script runs with: the files it reads (the script and
/// the module files it may require, by project path), the package it's in
/// (`None` for the project's own: a package's script may name its own blocks
/// and loot tables bare), and who hears when it fails. A generator never reads
/// a file while it makes a chunk.
#[derive(Clone)]
pub struct LuauScripts {
    pub sources: Arc<BTreeMap<String, String>>,
    pub namespace: Option<String>,
    pub failed: Failed,
}

impl TerrainScripts for LuauScripts {
    fn bind(
        &self,
        terrain: &CompiledTerrain,
        seed: i64,
        _min_y: i32,
        _max_y: i32,
    ) -> Box<dyn TerrainScript> {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let script = terrain.script.as_ref().expect("a terrain with a script");
        Box::new(Runner {
            id: NEXT.fetch_add(1, Ordering::Relaxed),
            file: script.file.clone(),
            budget: script.budget,
            scripts: self.clone(),
            seeds: ScriptSeeds::new(seed),
            found: OnceLock::new(),
            closed: AtomicBool::new(false),
        })
    }
}

impl LuauScripts {
    /// Loads `terrain`'s script once as a chunk thread would (its body run,
    /// its stages found), in a normal overworld of seed 0, and answers how it
    /// failed, or none when it loads: what the server checks as it publishes a
    /// generator, so a mistake is a problem at once. `failed` isn't told.
    pub fn check(&self, terrain: &CompiledTerrain) -> Option<ScriptFailure> {
        terrain.script.as_ref()?;
        let found: Arc<std::sync::Mutex<Option<ScriptFailure>>> = Default::default();
        let quiet = LuauScripts {
            failed: {
                let found = found.clone();
                Arc::new(move |failure| {
                    found
                        .lock()
                        .expect("no panic holds it")
                        .get_or_insert(failure);
                })
            },
            ..self.clone()
        };
        let overworld = netherforge_format::kind::terrain::OVERWORLD;
        let generator = terrain.bind(0, overworld.min_y, overworld.max_y, Some(&quiet));
        generator.load_script();
        generator.close();
        found.lock().expect("no panic holds it").take()
    }
}

thread_local! {
    /// Each runner's states this thread keeps, by the runner's id.
    static POOL: RefCell<HashMap<u64, Vec<Box<dyn ScriptState>>>> = RefCell::new(HashMap::new());
}

/// A generator's script: its states, a pool per thread.
struct Runner {
    id: u64,
    file: String,
    budget: i32,
    scripts: LuauScripts,
    seeds: ScriptSeeds,
    /// The stages the first state made found.
    found: OnceLock<Stages>,
    closed: AtomicBool,
}

impl Runner {
    fn failed(&self, stage: &str, message: String) {
        (self.scripts.failed)(ScriptFailure {
            file: self.file.clone(),
            stage: stage.to_owned(),
            message,
        });
    }
}

impl TerrainScript for Runner {
    fn stages(&self, generator: &TerrainGenerator) -> Stages {
        if let Some(found) = self.found.get() {
            return *found;
        }
        let state = self.take(generator);
        self.give(state);
        self.found.get().copied().unwrap_or_default()
    }

    fn take(&self, generator: &TerrainGenerator) -> Box<dyn ScriptState> {
        let kept = POOL.with(|pool| pool.borrow_mut().get_mut(&self.id).and_then(Vec::pop));
        kept.unwrap_or_else(|| {
            let state = State::new(self, generator);
            let _ = self.found.set(state.stages);
            Box::new(state)
        })
    }

    fn give(&self, state: Box<dyn ScriptState>) {
        if self.closed.load(Ordering::Relaxed) {
            return;
        }
        POOL.with(|pool| pool.borrow_mut().entry(self.id).or_default().push(state));
    }

    /// Lets go of this thread's states; another thread's go as it next gives one back.
    fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
        POOL.with(|pool| pool.borrow_mut().remove(&self.id));
    }
}

impl Drop for Runner {
    fn drop(&mut self) {
        let id = self.id;
        POOL.with(|pool| pool.borrow_mut().remove(&id));
    }
}

/// What the host functions reach while a call runs: the generator (and the
/// chunk, during a chunk stage) the call was made for.
struct Shared {
    generator: Cell<*const TerrainGenerator>,
    chunk: Cell<*mut ()>,
    noises: Vec<FastNoiseLite>,
    palette: Vec<TerrainBlock>,
    loot: usize,
    sources: Arc<BTreeMap<String, String>>,
    namespace: Option<String>,
    world_seed: i64,
    /// `math.random`'s generator, seeded for each call by the glue.
    random: RefCell<Random>,
}

impl Shared {
    /// The generator of the running call.
    fn generator(&self) -> mlua::Result<&TerrainGenerator> {
        let at = self.generator.get();
        if at.is_null() {
            return Err(mlua::Error::runtime(
                "a terrain's script can only be asked during a call",
            ));
        }
        // Safety: set for the length of a call into the state, from a reference that outlives it, and cleared after.
        Ok(unsafe { &*at })
    }

    /// The chunk of the running chunk stage.
    #[allow(clippy::mut_from_ref)]
    fn chunk(&self) -> mlua::Result<&mut ChunkGeneration<'static>> {
        let at = self.chunk.get();
        if at.is_null() {
            return Err(mlua::Error::runtime(
                "a chunk can only be used during the stage it's given to",
            ));
        }
        // Safety: set for the length of a chunk stage, from the stage's own `&mut`, and cleared after; nothing else
        // reaches the chunk while the stage runs.
        Ok(unsafe { &mut *(at as *mut ChunkGeneration<'static>) })
    }

    /// The palette entry a script's block id is: an entry's own label, or the
    /// game's block state written another way; -1 when the generator doesn't place it.
    fn resolve(&self, text: &str) -> i32 {
        let at = |label: &str| self.palette.iter().position(|b| b.label() == label);
        if let Some(i) = at(text) {
            return i as i32;
        }
        // A package's own block, named bare as its script names it: the palette has it in full.
        if let Some(home) = &self.namespace
            && !text.contains(':')
            && let Some(i) = at(&format!("{home}:{text}"))
        {
            return i as i32;
        }
        if !text.starts_with("minecraft:") {
            return -1;
        }
        let Some(state) = BlockState::parse(text) else {
            return -1;
        };
        let wanted = TerrainBlock::Vanilla {
            state: state.to_string(),
        };
        self.palette
            .iter()
            .position(|b| *b == wanted)
            .map_or(-1, |i| i as i32)
    }
}

/// A plan's 64-bit numbers, which Luau's doubles can't hold: a seed, or a cell's generator.
struct Word(Cell<u64>);

impl UserData for Word {}

fn mix64(z: u64) -> u64 {
    let z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    let z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

/// A whole number as a script passes one: NaN refused, the rest held to `i32`.
fn int(value: f64, index: usize) -> mlua::Result<i32> {
    if value.is_nan() {
        return Err(mlua::Error::runtime(format!(
            "bad argument #{index} (number expected)"
        )));
    }
    Ok(value.clamp(f64::from(i32::MIN), f64::from(i32::MAX)) as i32)
}

/// One Luau state running a generator's script.
struct State {
    host: Host,
    scope: ScopeId,
    entries: Table,
    shared: std::rc::Rc<Shared>,
    budget: u64,
    stages: Stages,
    seeds: ScriptSeeds,
    file: String,
    failed: Failed,
}

impl State {
    fn new(runner: &Runner, generator: &TerrainGenerator) -> State {
        let terrain = &generator.terrain;
        let shared = std::rc::Rc::new(Shared {
            generator: Cell::new(generator),
            chunk: Cell::new(std::ptr::null_mut()),
            noises: ScriptSeeds::noises(terrain, generator.seed),
            palette: terrain.palette.clone(),
            loot: terrain.loot.len(),
            sources: runner.scripts.sources.clone(),
            namespace: runner.scripts.namespace.clone(),
            world_seed: generator.seed,
            random: RefCell::new(Random::lua_seeded(0, 0)),
        });
        let limits = Limits {
            memory_bytes: MEMORY_MB * 1024 * 1024,
            // A generator is held to its budget alone: a chunk thread may be slow.
            deadline: Duration::from_secs(3600),
            ..Limits::default()
        };
        let host = Host::new(limits).expect("a Luau state opens");
        let scope = host
            .open_scope(&runner.file, &runner.file)
            .expect("a scope opens");
        let budget = u64::try_from(runner.budget).unwrap_or(0);
        let mut state = State {
            entries: host.lua().create_table().expect("a table"),
            host,
            scope,
            shared,
            budget,
            stages: Stages::default(),
            seeds: runner.seeds,
            file: runner.file.clone(),
            failed: runner.scripts.failed.clone(),
        };
        let loaded = state.load(runner, generator);
        state.shared.generator.set(std::ptr::null());
        match loaded {
            Ok(names) => {
                state.stages = names
                    .split(',')
                    .filter_map(Stage::named)
                    .fold(Stages::default(), Stages::with)
            }
            Err(message) => runner.failed("load", message),
        }
        if state.stages.contains(Stage::Density) && terrain.density.is_none() {
            runner.failed(
                "load",
                format!(
                    "{}: the density stage runs only in a file with a terrain.density: add one (`{{}}` for no noises of its own)",
                    runner.file
                ),
            );
        }
        state
    }

    /// The glue run, and the script's body: its stages' names.
    fn load(&mut self, runner: &Runner, generator: &TerrainGenerator) -> Result<String, String> {
        let lua = self.host.lua().clone();
        let host = self.host_functions(&lua).map_err(|e| e.to_string())?;
        let env = self.host.env(self.scope).expect("the state's scope");
        // The glue's own environment is the state's scope; it isn't a project chunk, so a stop is located in the script.
        let glue = lua
            .load(GLUE)
            .set_name("=nf/terrain")
            .set_environment(env)
            .into_function()
            .map_err(|e| e.to_string())?;
        self.entries = self
            .host
            .invoke(self.scope, u64::MAX / 2, &glue, host)
            .map_err(|f| self.message(&f))?;
        let terrain = &generator.terrain;
        let names = |items: &mut dyn Iterator<Item = String>| items.collect::<Vec<_>>().join("\n");
        let args = (
            runner.file.clone(),
            generator.seed.to_string(),
            generator.min_y,
            generator.max_y - 1,
            terrain.sea_level,
            names(
                &mut terrain
                    .script
                    .iter()
                    .flat_map(|s| s.noises.iter().map(|n| n.name.clone())),
            ),
            names(&mut terrain.areas.iter().map(|a| a.name.clone())),
            names(&mut terrain.areas.iter().map(|a| a.biome.clone())),
            names(&mut terrain.palette.iter().map(|b| b.label().to_owned())),
            names(
                &mut terrain
                    .areas
                    .iter()
                    .filter(|a| a.volume.is_some())
                    .map(|a| a.name.clone()),
            ),
            terrain.loot.join("\n"),
            runner.scripts.namespace.clone().unwrap_or_default(),
        );
        self.call("load", args)
    }

    /// Calls entry point `name` with `args` within the script's budget: its
    /// answer, or how it failed, said as format says it.
    fn call<R: mlua::FromLuaMulti>(
        &self,
        name: &str,
        args: impl mlua::IntoLuaMulti,
    ) -> Result<R, String> {
        let f: Function = self.entries.raw_get(name).map_err(|e| e.to_string())?;
        self.host
            .invoke(self.scope, self.budget, &f, args)
            .map_err(|failure| self.message(&failure))
    }

    fn message(&self, failure: &Failure) -> String {
        match failure {
            Failure::Stopped { stop, location } => {
                let at = location
                    .as_ref()
                    .map(|l| format!("{l}: "))
                    .unwrap_or_default();
                match stop {
                    Stop::Budget { budget } => {
                        format!("{at}ran past its budget of {budget} instructions")
                    }
                    Stop::Time { limit } => {
                        format!("{at}ran past its time limit of {} ms", limit.as_millis())
                    }
                }
            }
            Failure::Memory => format!("used more than {MEMORY_MB} MB of memory"),
            // Out of memory inside the glue's `pcall` comes back as Luau's message.
            Failure::Error { message, .. } if message.ends_with("not enough memory") => {
                format!("used more than {MEMORY_MB} MB of memory")
            }
            Failure::Error { message, .. } => message.clone(),
        }
    }

    fn failed(&self, stage: &str, message: String) {
        (self.failed)(ScriptFailure {
            file: self.file.clone(),
            stage: stage.to_owned(),
            message,
        });
    }

    /// Runs `f` with the call's generator reachable from the host functions.
    fn during<T>(&self, generator: &TerrainGenerator, f: impl FnOnce() -> T) -> T {
        let before = self.shared.generator.replace(generator);
        let out = f();
        self.shared.generator.set(before);
        out
    }

    fn host_functions(&self, lua: &Lua) -> mlua::Result<Table> {
        let host = lua.create_table()?;
        let shared = &self.shared;
        let add = |name: &str, f: Function| host.raw_set(name, f);

        let s = shared.clone();
        add(
            "noise",
            lua.create_function(move |_, (index, x, y, z): (f64, f64, f64, Option<f64>)| {
                let noise = s
                    .noises
                    .get(int(index, 1)? as usize)
                    .ok_or_else(|| mlua::Error::runtime("no such noise"))?;
                Ok(match z {
                    Some(z) => noise.get_noise_3d(x, y, z),
                    None => noise.get_noise_2d(x, y),
                })
            })?,
        )?;
        let s = shared.clone();
        add(
            "fill",
            lua.create_function(
                move |_, (x1, y1, z1, x2, y2, z2, block): (f64, f64, f64, f64, f64, f64, f64)| {
                    let block = int(block, 7)?;
                    if block < 0 || block as usize >= s.palette.len() {
                        return Err(mlua::Error::runtime(format!("no block {block}")));
                    }
                    let (x1, y1, z1, x2, y2, z2) = (
                        int(x1, 1)?,
                        int(y1, 2)?,
                        int(z1, 3)?,
                        int(x2, 4)?,
                        int(y2, 5)?,
                        int(z2, 6)?,
                    );
                    let chunk = s.chunk()?;
                    let (min_x, min_z) = (chunk.min_x(), chunk.min_z());
                    let (from_x, to_x) = (x1.min(x2).max(min_x), x1.max(x2).min(min_x + 15));
                    let (from_z, to_z) = (z1.min(z2).max(min_z), z1.max(z2).min(min_z + 15));
                    for x in from_x..=to_x {
                        for z in from_z..=to_z {
                            chunk
                                .buffer
                                .fill(x - min_x, z - min_z, y1.min(y2), y1.max(y2), block);
                        }
                    }
                    Ok(())
                },
            )?,
        )?;
        let s = shared.clone();
        add(
            "block",
            lua.create_function(move |_, (x, y, z): (f64, f64, f64)| {
                let g = s.generator()?;
                let chunk = s.chunk()?;
                let (lx, y, lz) = (
                    int(x, 1)? - chunk.min_x(),
                    int(y, 2)?,
                    int(z, 3)? - chunk.min_z(),
                );
                if !(0..=15).contains(&lx) || !(0..=15).contains(&lz) || y < g.min_y || y >= g.max_y
                {
                    return Ok(-1);
                }
                Ok(chunk.buffer.get(lx, y, lz))
            })?,
        )?;
        let s = shared.clone();
        add(
            "file_height",
            lua.create_function(move |_, (x, z): (f64, f64)| {
                let g = s.generator()?;
                Ok(g.sampler(None).file_height(int(x, 1)?, int(z, 2)?))
            })?,
        )?;
        let s = shared.clone();
        add(
            "area",
            lua.create_function(move |_, (x, z): (f64, f64)| {
                let g = s.generator()?;
                Ok(g.sampler(None).area_at(int(x, 1)?, int(z, 2)?))
            })?,
        )?;
        let s = shared.clone();
        add(
            "point_area",
            lua.create_function(move |_, (x, y, z): (f64, f64, f64)| {
                let g = s.generator()?;
                Ok(g.sampler(None)
                    .point_area_at(int(x, 1)?, int(y, 2)?, int(z, 3)?))
            })?,
        )?;
        let s = shared.clone();
        add(
            "set_loot",
            lua.create_function(move |_, (x, y, z, table): (f64, f64, f64, f64)| {
                let g = s.generator()?;
                let chunk = s.chunk()?;
                let (lx, y, lz, table) = (
                    int(x, 1)? - chunk.min_x(),
                    int(y, 2)?,
                    int(z, 3)? - chunk.min_z(),
                    int(table, 4)?,
                );
                if table < 0 || table as usize >= s.loot {
                    return Err(mlua::Error::runtime(format!("no loot table {table}")));
                }
                if (0..=15).contains(&lx) && (0..=15).contains(&lz) && y >= g.min_y && y < g.max_y {
                    let at = chunk.buffer.column_start(lx, lz) + (y - g.min_y) as usize;
                    chunk.buffer.loot.insert(at, table);
                }
                Ok(())
            })?,
        )?;
        let s = shared.clone();
        add(
            "resolve",
            lua.create_function(move |_, text: String| Ok(s.resolve(&text)))?,
        )?;
        let s = shared.clone();
        add(
            "module_path",
            lua.create_function(move |_, (name, from): (String, String)| {
                Ok(module_paths(&name, &from, s.sources.keys())
                    .into_iter()
                    .find(|p| s.sources.contains_key(p)))
            })?,
        )?;
        let s = shared.clone();
        add(
            "load",
            lua.create_function(move |lua, (path, env): (String, Table)| {
                let source = s
                    .sources
                    .get(&path)
                    .ok_or_else(|| mlua::Error::runtime(format!("{path} isn't there to run")))?;
                lua.load(source.as_str())
                    .set_name(format!("@{path}"))
                    .set_environment(env)
                    .into_function()
            })?,
        )?;
        add(
            "caller_source",
            lua.create_function(|lua, ()| {
                Ok(netherforge_luau::script_location(lua)
                    .map_or_else(String::new, |at| format!("@{}", at.chunk)))
            })?,
        )?;
        add(
            "function_info",
            lua.create_function(|_, f: Function| {
                let info = f.info();
                Ok((
                    info.short_src.unwrap_or_default(),
                    info.line_defined.unwrap_or(0),
                ))
            })?,
        )?;
        let s = shared.clone();
        add(
            "randomseed",
            lua.create_function(move |_, n: f64| {
                *s.random.borrow_mut() = Random::lua_seeded(n as i64, 0);
                Ok(())
            })?,
        )?;
        let s = shared.clone();
        add(
            "random",
            lua.create_function(move |_, args: MultiValue| {
                lua_random(&mut s.random.borrow_mut(), args)
            })?,
        )?;
        let s = shared.clone();
        add(
            "plan_seed",
            lua.create_function(move |_, name: String| {
                let mut h: u64 = 0xcbf2_9ce4_8422_2325;
                for byte in name.bytes() {
                    h = (h ^ u64::from(byte)).wrapping_mul(0x0100_0000_01b3);
                }
                Ok(Word(Cell::new(mix64(s.world_seed as u64 ^ h))))
            })?,
        )?;
        add(
            "cell_random",
            lua.create_function(|_, (seed, x, z): (mlua::UserDataRef<Word>, f64, f64)| {
                let cell = |n: f64, by: i64| mix64((n as i64).wrapping_mul(by) as u64);
                Ok(Word(Cell::new(mix64(
                    seed.0.get() ^ cell(x, 0x1B87_3593) ^ cell(z, 0x2C1B_3C6D),
                ))))
            })?,
        )?;
        add(
            "plan_draw",
            lua.create_function(
                |_, (rng, m, n): (mlua::UserDataRef<Word>, Option<f64>, Option<f64>)| {
                    let state = rng.0.get().wrapping_add(0x9e37_79b9_7f4a_7c15);
                    rng.0.set(state);
                    let r = mix64(state);
                    Ok(match (m, n) {
                        (Some(m), Some(n)) => {
                            let (m, n) = (m as i64, n as i64);
                            let span = n.wrapping_sub(m).wrapping_add(1);
                            (m.wrapping_add((r >> 1) as i64 % span)) as f64
                        }
                        _ => (r >> 11) as f64 * (1.0 / 9_007_199_254_740_992.0),
                    })
                },
            )?,
        )?;
        Ok(host)
    }
}

/// Lua 5.4's `math.random`: a fraction with no arguments, a whole number from
/// 1 to m with one (any 64 bits with 0), from m to n with two.
fn lua_random(random: &mut Random, args: MultiValue) -> mlua::Result<f64> {
    let whole = |value: &Value, index: usize| -> mlua::Result<i64> {
        let n = match value {
            Value::Number(n) => *n,
            Value::Integer(n) => *n as f64,
            _ => {
                return Err(mlua::Error::runtime(format!(
                    "bad argument #{index} to 'random' (number expected)"
                )));
            }
        };
        if n != n.floor() || !n.is_finite() {
            return Err(mlua::Error::runtime(format!(
                "bad argument #{index} to 'random' (number has no integer representation)"
            )));
        }
        Ok(n as i64)
    };
    let args: Vec<Value> = args.into_iter().collect();
    let (low, high) = match args.as_slice() {
        [] => return Ok(random.fraction()),
        [m] => {
            let m = whole(m, 1)?;
            if m == 0 {
                return Ok(random.next_word() as i64 as f64);
            }
            (1, m)
        }
        [m, n] => (whole(m, 1)?, whole(n, 2)?),
        _ => {
            return Err(mlua::Error::runtime(
                "wrong number of arguments to 'random'",
            ));
        }
    };
    if low > high {
        return Err(mlua::Error::runtime(
            "bad argument #1 to 'random' (interval is empty)",
        ));
    }
    Ok(random.integer(low, high) as f64)
}

impl ScriptState for State {
    fn height(&mut self, generator: &TerrainGenerator, x: i32, z: i32, height: i32) -> Option<i32> {
        let seed = self.seeds.height(x, z);
        match self.during(generator, || {
            self.call::<f64>("height", (x, z, height, seed))
        }) {
            Ok(h) => Some(h.clamp(f64::from(i32::MIN), f64::from(i32::MAX)) as i32),
            Err(message) => {
                self.failed("height", message);
                None
            }
        }
    }

    fn density(
        &mut self,
        generator: &TerrainGenerator,
        x: i32,
        y: i32,
        z: i32,
        value: f64,
    ) -> Option<f64> {
        let seed = self.seeds.density(x, y, z);
        self.during(generator, || {
            self.call::<f64>("density", (x, y, z, value, seed))
        })
        .map_err(|message| self.failed("density", message))
        .ok()
    }

    fn area(&mut self, generator: &TerrainGenerator, x: i32, z: i32, file: usize) -> Option<usize> {
        let seed = self.seeds.area(x, z);
        self.during(generator, || self.call::<f64>("area", (x, z, file, seed)))
            .map(|a| a as usize)
            .map_err(|message| self.failed("area", message))
            .ok()
    }

    fn biome(
        &mut self,
        generator: &TerrainGenerator,
        x: i32,
        y: i32,
        z: i32,
        file: usize,
    ) -> Option<usize> {
        let seed = self.seeds.biome(x, y, z);
        self.during(generator, || {
            self.call::<f64>("biome", (x, y, z, file, seed))
        })
        .map(|a| a as usize)
        .map_err(|message| self.failed("biome", message))
        .ok()
    }

    fn chunk_stage(
        &mut self,
        generator: &TerrainGenerator,
        stage: Stage,
        chunk: &mut ChunkGeneration,
    ) -> bool {
        let seed = self.seeds.chunk(stage, chunk.chunk_x, chunk.chunk_z);
        let (x, z) = (chunk.chunk_x, chunk.chunk_z);
        self.shared
            .chunk
            .set(chunk as *mut ChunkGeneration as *mut ());
        let result = self.during(generator, || {
            self.call::<()>("chunk", (stage.name(), x, z, seed))
        });
        self.shared.chunk.set(std::ptr::null_mut());
        match result {
            Ok(()) => true,
            Err(message) => {
                self.failed(stage.name(), message);
                false
            }
        }
    }
}

impl Drop for State {
    fn drop(&mut self) {
        self.host.close_scope(self.scope);
    }
}
