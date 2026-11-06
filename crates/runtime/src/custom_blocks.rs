//! The project's blocks (`blocks/<id>/block.json`) and every one of them placed in a world (Kotlin's
//! `CustomBlocks`, `NfBlocksImpl`, `ProjectBlockImpl` and `CustomBlockImpl`).
//!
//! **What a block is in the world**: a state of its carrier (format's `carriers::plan`: a note block's, tripwire's
//! or a leaves block's, which the resource pack draws as the block). Which block a state is can change when the
//! project's blocks do, so each chunk keeps a **legend** of the states its blocks were placed as (state to block,
//! a few entries a chunk however many blocks): when a chunk loads, every block is read from its state through the
//! legend and put in the state it has now, so adding or removing blocks never turns a placed one into another.
//! Only a block a centity is drawn over keeps a **record** of its own (naming its instance), and so does a block the
//! project no longer has, so its state is free for another. A carrier state no legend names (a generator's, a
//! paste) is adopted as the block that state is now; a leaves state only in a chunk just generated or a world the
//! project generates, since elsewhere it's a player's leaves. A position that stops being a carrier is forgotten,
//! with its centity, by the sweep (or at once, when the runtime did it).
//!
//! Holding the carriers still (and the vanilla note block's tuning and sounding) is the adapter's
//! (`BlockOps::freeze`); this only holds blocks. Breaking, clicks, placing from items, mining, falling and the
//! game's own blocks' rules come with the next slices.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::rc::{Rc, Weak};

use indexmap::IndexSet;
use mlua::{Function, Value};
use netherforge_api::api::{CustomBlockApi, NfBlocksApi, ProjectBlockApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, codecs, shapes, values};
use netherforge_format::Problem;
use netherforge_format::kind::block::{BlockCarrier, BlockFacing, BlockFile, BlockRotation};
use netherforge_format::kind::carriers::{self, Plan};
use netherforge_luau::ScopeId;
use netherforge_platform::{ChunkGrid, Location, Platform, Uuid};
use serde::{Deserialize, Serialize};

use crate::Room;
use crate::centity::api::CentityContext;
use crate::events::{pack, parse_uuid, uuid_text};
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::{GameContext, unpack};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

const KIND: &str = "block";
/// Chunks checked a tick: a tracked chunk is looked at again every `chunks / SWEEP_PER_TICK` ticks.
const SWEEP_PER_TICK: usize = 2;

/// What the server remembers of a block in a chunk (saved with it): `id` is the block's name as the project knows it.
#[derive(Serialize, Deserialize)]
struct Record {
    id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    centity: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    facing: Option<String>,
}

fn facing_name(facing: BlockFacing) -> &'static str {
    match facing {
        BlockFacing::North => "north",
        BlockFacing::East => "east",
        BlockFacing::South => "south",
        BlockFacing::West => "west",
        BlockFacing::Up => "up",
        BlockFacing::Down => "down",
    }
}

fn facing_of_name(name: &str) -> Option<BlockFacing> {
    [
        BlockFacing::North,
        BlockFacing::East,
        BlockFacing::South,
        BlockFacing::West,
        BlockFacing::Up,
        BlockFacing::Down,
    ]
    .into_iter()
    .find(|f| facing_name(*f) == name)
}

/// The record text of a block.
fn record(name: &str, centity: Option<Uuid>, facing: Option<BlockFacing>) -> String {
    serde_json::to_string(&Record {
        id: name.to_owned(),
        centity: centity.map(|c| uuid_text(&c)),
        facing: facing.map(|f| facing_name(f).to_owned()),
    })
    .expect("a record writes")
}

/// What a chunk's legend says a state is: a block's name, and for a block that turns the way it faced in it
/// (`chair#east`), so a block whose states move still faces the way it did.
fn legend_value(name: &str, facing: Option<BlockFacing>) -> String {
    match facing {
        Some(f) => format!("{name}#{}", facing_name(f)),
        None => name.to_owned(),
    }
}

fn legend_name(value: &str) -> &str {
    value.split('#').next().unwrap_or(value)
}

fn legend_facing(value: &str) -> Option<BlockFacing> {
    value.split_once('#').and_then(|(_, f)| facing_of_name(f))
}

fn read_legend(json: Option<&str>) -> BTreeMap<String, String> {
    json.and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default()
}

/// A centity drawn over a block that faces `facing` is turned with it, about its own up: the model's y turn.
fn yaw_of(facing: Option<BlockFacing>) -> f64 {
    f64::from(facing.map_or(0, |f| f.rotation().1))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct ChunkKey {
    world_index: usize,
    x: i32,
    z: i32,
}

type At = (i32, i32, i32);

/// A block in a loaded chunk that has more to it than its name: one that ticks, a centity drawn over it, or a pin.
#[derive(Clone, Debug)]
struct Placed {
    name: String,
    centity: Option<Uuid>,
    /// The tick it next ticks at.
    next: u64,
    facing: Option<BlockFacing>,
    /// Whether a block that needn't keep a record has one anyway (its table is kept by it).
    pinned: bool,
}

/// The project's blocks in a loaded chunk: every one's name (its number) where it is, and a `Placed` only for the
/// few with more to them, which is what lets a chunk a world generator filled with them cost kilobytes.
#[derive(Default)]
struct ChunkBlocks {
    names: ChunkGrid,
    kept: HashMap<At, Placed>,
}

/// A running block: its file, and the scope of its script.
struct Def {
    file: BlockFile,
    scope: Option<ScopeId>,
}

/// The session's blocks: definitions, the plan of states, and what's placed in loaded chunks.
#[derive(Default)]
pub struct CustomBlocks {
    defs: BTreeMap<String, Def>,
    /// Each block's folder, where its script is read from (a package's at its package path).
    folders: BTreeMap<String, String>,
    plan: Option<Plan>,
    /// Whether the server holds every carrier the plan uses still: only then are its states safe to hold blocks in.
    held: bool,
    /// The worlds the project generates itself (a terrain in `netherforge.json`), where leaves states are adopted.
    generated_worlds: BTreeSet<String>,
    by_state: HashMap<String, String>,
    /// Every name a block has had this session, by its number less one.
    names: Vec<String>,
    numbers: HashMap<String, u16>,
    /// Worlds by index, so a chunk's key is small.
    worlds: Vec<String>,
    chunks: HashMap<ChunkKey, ChunkBlocks>,
    ticking: IndexSet<ChunkKey>,
    sweep_queue: VecDeque<ChunkKey>,
    /// Each chunk's fingerprint the last time the sweep reconciled it.
    swept: HashMap<ChunkKey, i64>,
    problems: Vec<Problem>,
}

impl CustomBlocks {
    fn usable(&self) -> bool {
        self.held && self.plan.is_some()
    }

    fn world_index(&mut self, world: &str) -> usize {
        match self.worlds.iter().position(|w| w == world) {
            Some(i) => i,
            None => {
                self.worlds.push(world.to_owned());
                self.worlds.len() - 1
            }
        }
    }

    fn key(&mut self, world: &str, x: i32, z: i32) -> ChunkKey {
        ChunkKey {
            world_index: self.world_index(world),
            x,
            z,
        }
    }

    fn known_key(&self, world: &str, x: i32, z: i32) -> Option<ChunkKey> {
        let world_index = self.worlds.iter().position(|w| w == world)?;
        Some(ChunkKey { world_index, x, z })
    }

    fn number(&mut self, name: &str) -> u16 {
        if let Some(n) = self.numbers.get(name) {
            return *n;
        }
        self.names.push(name.to_owned());
        let n = u16::try_from(self.names.len()).unwrap_or(u16::MAX);
        self.numbers.insert(name.to_owned(), n);
        n
    }

    /// Whether a block keeps a record of its own: one a centity is drawn over, which names the instance.
    fn recorded(&self, name: &str) -> bool {
        self.defs
            .get(name)
            .is_some_and(|d| d.file.centity.is_some())
    }

    fn ticks(&self, name: &str) -> bool {
        self.defs.get(name).is_some_and(|d| d.file.tick.is_some())
    }

    fn keeps(&self, placed: &Placed) -> bool {
        placed.pinned
            || placed.centity.is_some()
            || self.recorded(&placed.name)
            || self.ticks(&placed.name)
    }

    pub fn has(&self, name: &str) -> bool {
        self.defs.contains_key(name)
    }

    pub fn ids(&self) -> Vec<String> {
        self.defs.keys().cloned().collect()
    }

    /// The ways `name` can face, none for a block that doesn't turn.
    pub fn facings_of(&self, name: &str) -> &'static [BlockFacing] {
        self.defs
            .get(name)
            .map_or(&[], |d| d.file.rotation.unwrap_or_default().facings())
    }

    /// The state `name` is held as facing `facing` (its one state for a block that doesn't turn).
    pub fn state_of(&self, name: &str, facing: Option<BlockFacing>) -> Option<String> {
        self.plan.as_ref()?.state_of(name, facing).map(|s| s.text())
    }

    /// The project block a placed carrier state is, or none.
    pub fn name_of_state(&self, state: &str) -> Option<&str> {
        self.by_state
            .get(state)
            .map(String::as_str)
            .filter(|n| self.defs.contains_key(*n))
    }

    /// The block at a position, or none when it isn't one of the project's (or its chunk hasn't been looked at).
    pub fn at(&self, world: &str, x: i32, y: i32, z: i32) -> Option<String> {
        let key = self.known_key(world, x >> 4, z >> 4)?;
        let blocks = self.chunks.get(&key)?;
        let number = blocks.names.get(x & 15, y, z & 15);
        (number != 0).then(|| self.names[usize::from(number) - 1].clone())
    }

    /// Whether any block of the project's is placed in a loaded chunk.
    pub fn any_placed(&self) -> bool {
        !self.chunks.is_empty()
    }

    fn put(&mut self, key: ChunkKey, at: At, placed: Placed) {
        let number = self.number(&placed.name);
        let keeps = self.keeps(&placed);
        let blocks = self.chunks.entry(key).or_default();
        blocks.names.set(at.0 & 15, at.1, at.2 & 15, number);
        if keeps {
            blocks.kept.insert(at, placed);
        } else {
            blocks.kept.remove(&at);
        }
    }

    /// Stops tracking the block at `at`: what it was, or none.
    fn take(&mut self, key: ChunkKey, at: At) -> Option<Placed> {
        let blocks = self.chunks.get_mut(&key)?;
        let number = blocks.names.set(at.0 & 15, at.1, at.2 & 15, 0);
        if number == 0 {
            return None;
        }
        let placed = blocks.kept.remove(&at).unwrap_or_else(|| Placed {
            name: self.names[usize::from(number) - 1].clone(),
            centity: None,
            next: 0,
            facing: None,
            pinned: false,
        });
        if blocks.names.is_empty() {
            self.chunks.remove(&key);
            self.ticking.shift_remove(&key);
        }
        Some(placed)
    }

    fn refresh_ticking(&mut self, key: ChunkKey) {
        let ticks = self
            .chunks
            .get(&key)
            .is_some_and(|b| b.kept.values().any(|p| self.ticks(&p.name)));
        if ticks {
            self.ticking.insert(key);
        } else {
            self.ticking.shift_remove(&key);
        }
    }

    /// The problems with the project's blocks as the server holds them.
    pub fn problems(&self) -> Vec<Problem> {
        self.problems.clone()
    }
}

/// What a reconcile leaves to do once the blocks are let go: centities to spawn for blocks and to remove.
#[derive(Default)]
struct Afterwards {
    /// A block that needs its centity: where, which block, facing.
    spawn: Vec<(String, At, String, Option<BlockFacing>)>,
    /// A block that went: its centity goes.
    gone: Vec<Uuid>,
}

/// The work that reaches scripts and centities.
#[derive(Clone)]
pub struct BlocksContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl BlocksContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn write_record(&self, world: &str, at: At, json: Option<String>) {
        let key = self
            .shared
            .blocks
            .borrow()
            .known_key(world, at.0 >> 4, at.2 >> 4);
        if let Some(key) = key {
            self.shared.blocks.borrow_mut().swept.remove(&key);
        }
        self.platform()
            .blocks()
            .set_record(world.to_owned(), at.0, at.1, at.2, json);
    }

    fn write_legend(
        &self,
        world: &str,
        chunk_x: i32,
        chunk_z: i32,
        before: Option<&str>,
        legend: &BTreeMap<String, String>,
    ) {
        let json =
            (!legend.is_empty()).then(|| serde_json::to_string(legend).expect("a legend writes"));
        if json.as_deref() == before {
            return;
        }
        let key = self
            .shared
            .blocks
            .borrow()
            .known_key(world, chunk_x, chunk_z);
        if let Some(key) = key {
            self.shared.blocks.borrow_mut().swept.remove(&key);
        }
        self.platform()
            .blocks()
            .set_legend(world.to_owned(), chunk_x, chunk_z, json);
    }

    /// Says in the chunk's legend which block (and way) `name`'s state facing `facing` is, if it doesn't yet.
    fn note_legend(
        &self,
        world: &str,
        chunk_x: i32,
        chunk_z: i32,
        name: &str,
        facing: Option<BlockFacing>,
    ) {
        let Some(state) = self.shared.blocks.borrow().state_of(name, facing) else {
            return;
        };
        let before = self
            .platform()
            .blocks()
            .legend(world.to_owned(), chunk_x, chunk_z);
        let mut legend = read_legend(before.as_deref());
        let value = legend_value(name, facing);
        if legend.get(&state) != Some(&value) {
            legend.insert(state, value);
            self.write_legend(world, chunk_x, chunk_z, before.as_deref(), &legend);
        }
    }

    /// The centity drawn over block `name` placed at `at`, spawned.
    fn spawn_centity(
        &self,
        world: &str,
        at: At,
        name: &str,
        facing: Option<BlockFacing>,
    ) -> Option<Uuid> {
        let (reference, home) = {
            let blocks = self.shared.blocks.borrow();
            let reference = blocks.defs.get(name)?.file.centity.clone()?;
            (reference, self.shared.centities.borrow().home().to_owned())
        };
        let centity =
            netherforge_format::reference::resolve_any(&home, &reference)?.relative_to(&home);
        let spawned = CentityContext::new(self.shared.clone(), self.scripts.clone()).spawn(
            &centity,
            Location {
                world: world.to_owned(),
                x: f64::from(at.0) + 0.5,
                y: f64::from(at.1),
                z: f64::from(at.2) + 0.5,
                yaw: 0.0,
                pitch: 0.0,
            },
            yaw_of(facing),
        );
        if spawned.is_none() {
            self.platform().log().warn(format!(
                "Block {name}'s centity \"{reference}\" couldn't be spawned at {world} {} {} {}",
                at.0, at.1, at.2
            ));
        }
        spawned
    }

    /// A block went: the centity drawn over it goes.
    fn centity_gone(&self, centity: Option<Uuid>) {
        let Some(id) = centity else { return };
        let live = self.shared.centities.borrow().get(id).is_some();
        if live {
            CentityContext::new(self.shared.clone(), self.scripts.clone()).remove(id);
        } else {
            self.shared
                .centities
                .borrow_mut()
                .remove_inert(&*self.shared.platform, id);
        }
    }

    fn finish(&self, after: Afterwards) {
        for id in after.gone {
            self.centity_gone(Some(id));
        }
        for (world, at, name, facing) in after.spawn {
            let centity = self.spawn_centity(&world, at, &name, facing);
            {
                let mut blocks = self.shared.blocks.borrow_mut();
                let key = blocks.key(&world, at.0 >> 4, at.2 >> 4);
                if let Some(placed) = blocks
                    .chunks
                    .get_mut(&key)
                    .and_then(|b| b.kept.get_mut(&at))
                    .filter(|p| p.name == name)
                {
                    placed.centity = centity;
                }
            }
            self.write_record(&world, at, Some(record(&name, centity, facing)));
        }
    }

    /// Brings what the server remembers of a chunk, what's in its blocks and what the project's blocks are into
    /// agreement: each record's block is in its state now, a record whose block has gone is forgotten, and a
    /// carrier state with no record is adopted (a leaves state only in a chunk just `generated`, or in a world the
    /// project generates).
    pub fn reconcile(&self, world: &str, chunk_x: i32, chunk_z: i32, generated: bool) {
        let platform = self.platform();
        let ops = platform.blocks();
        let Some(plan) = self.shared.blocks.borrow().plan.clone() else {
            return;
        };
        let Some(records) = ops.records(world.to_owned(), chunk_x, chunk_z) else {
            return;
        };
        let legend_text = ops.legend(world.to_owned(), chunk_x, chunk_z);
        let legend = read_legend(legend_text.as_deref());
        // The states blocks are held in now, and those the legend says they were placed in.
        let states: Vec<String> = {
            let blocks = self.shared.blocks.borrow();
            let mut states: Vec<String> = blocks.by_state.keys().cloned().collect();
            states.sort();
            for state in legend.keys() {
                if !blocks.by_state.contains_key(state) {
                    states.push(state.clone());
                }
            }
            states
        };
        let Some(found) = ops.find(world.to_owned(), chunk_x, chunk_z, states.clone()) else {
            return;
        };
        let state_at = |at: At| -> Option<&String> {
            let i = found.get(at.0 & 15, at.1, at.2 & 15);
            (i != 0).then(|| &states[usize::from(i) - 1])
        };
        let ticks_now = self.scripts.tick_count();
        let mut after = Afterwards::default();
        let mut blocks = self.shared.blocks.borrow_mut();
        let key = blocks.key(world, chunk_x, chunk_z);
        let previous = blocks.chunks.remove(&key).unwrap_or_default();
        let mut recorded: HashSet<At> = HashSet::new();
        let adopts_leaves = generated || blocks.generated_worlds.contains(world);
        let mut tracked: Vec<(At, Placed)> = Vec::new();
        let mut plain: Vec<(At, String)> = Vec::new();
        let mut records_to_write: Vec<(At, Option<String>)> = Vec::new();
        let mut sets: Vec<(At, String)> = Vec::new();
        let placed_of =
            |blocks: &CustomBlocks, at: At, name: &str, centity: Option<Uuid>, facing| {
                let known = previous.kept.get(&at).filter(|p| p.name == name).cloned();
                let mut placed = known.unwrap_or_else(|| Placed {
                    name: name.to_owned(),
                    centity: None,
                    next: ticks_now
                        + blocks.defs.get(name).and_then(|d| d.file.tick).unwrap_or(0) as u64,
                    facing: None,
                    pinned: false,
                });
                placed.centity = centity;
                placed.facing = facing;
                placed
            };
        for r in records {
            let at = (r.x, r.y, r.z);
            recorded.insert(at);
            let Ok(read) = serde_json::from_str::<Record>(&r.json) else {
                records_to_write.push((at, None));
                continue;
            };
            let facing = read.facing.as_deref().and_then(facing_of_name);
            let expected = plan.state_of(&read.id, facing).map(|s| s.text());
            let centity = read.centity.as_deref().and_then(parse_uuid);
            let Some(expected) = expected.filter(|_| blocks.defs.contains_key(&read.id)) else {
                // A block the project has no longer (or has with errors): it stays as it was, inert, until it's back.
                continue;
            };
            if state_at(at) == Some(&expected) || {
                let now = ops.get(world.to_owned(), at.0, at.1, at.2).map(|s| s.state);
                let carrier = now.as_deref().is_some_and(|s| plan.is_carrier(s));
                if carrier {
                    // The block's state moved with the project's blocks: put it right, quietly.
                    sets.push((at, expected.clone()));
                }
                carrier
            } {
                let mut placed = placed_of(&blocks, at, &read.id, centity, facing);
                // One that needn't keep a record (one from before legends) loses it; the legend has it now.
                if !blocks.recorded(&read.id) && centity.is_none() {
                    records_to_write.push((at, None));
                    placed.pinned = false;
                }
                tracked.push((at, placed));
            } else {
                // Whatever it was, it isn't there now: its centity goes.
                after.gone.extend(centity);
                records_to_write.push((at, None));
            }
        }
        // What each state found is: the block it was placed as, else (a generated chunk, a paste) the one it is now.
        let leaves_ids: HashSet<String> = plan
            .pools
            .get(&BlockCarrier::Leaves)
            .map(|p| p.blocks.iter().map(|(id, _)| id.clone()).collect())
            .unwrap_or_default();
        let value_of: Vec<Option<String>> = states
            .iter()
            .map(|state| {
                legend.get(state).cloned().or_else(|| {
                    let name = blocks.by_state.get(state)?;
                    let leaves = plan
                        .state_of(name, None)
                        .is_some_and(|s| leaves_ids.contains(&s.id));
                    (adopts_leaves || !leaves).then(|| legend_value(name, plan.facing_of(state)))
                })
            })
            .collect();
        let mut cells: Vec<(At, usize)> = Vec::new();
        found.for_each(|x, y, z, i| {
            cells.push(((chunk_x * 16 + x, y, chunk_z * 16 + z), usize::from(i) - 1));
        });
        for (at, i) in cells {
            let Some(value) = &value_of[i] else { continue };
            if recorded.contains(&at) {
                continue;
            }
            let name = legend_name(value).to_owned();
            let facing = legend_facing(value);
            if !blocks.defs.contains_key(&name) {
                // A block the project has no longer (or has with errors): pinned by a record, inert, until it's back.
                records_to_write.push((at, Some(record(&name, None, facing))));
                continue;
            }
            let Some(expected) = plan.state_of(&name, facing).map(|s| s.text()) else {
                continue;
            };
            if expected != states[i] {
                sets.push((at, expected));
            }
            if blocks.recorded(&name) || blocks.ticks(&name) {
                let placed = placed_of(&blocks, at, &name, None, facing);
                let fresh = placed.centity.is_none() && blocks.recorded(&name);
                tracked.push((at, placed));
                if fresh {
                    after
                        .spawn
                        .push((world.to_owned(), at, name.clone(), facing));
                }
            } else {
                plain.push((at, name));
            }
        }
        // A block known by its state alone that something else replaced while its chunk was loaded.
        previous.names.for_each(|x, y, z, _| {
            if found.get(x, y, z) != 0 {
                return;
            }
            let at = (chunk_x * 16 + x, y, chunk_z * 16 + z);
            if !recorded.contains(&at) {
                after
                    .gone
                    .extend(previous.kept.get(&at).and_then(|p| p.centity));
            }
        });
        for (at, placed) in tracked {
            // A block drawn by a centity whose instance is missing gets a new one.
            if blocks.recorded(&placed.name)
                && let Some(id) = placed.centity
            {
                let centities = self.shared.centities.borrow();
                if centities.get(id).is_none() && !centities.is_inert(id) {
                    after
                        .spawn
                        .push((world.to_owned(), at, placed.name.clone(), placed.facing));
                }
            }
            blocks.put(key, at, placed);
        }
        for (at, name) in plain {
            let number = blocks.number(&name);
            blocks
                .chunks
                .entry(key)
                .or_default()
                .names
                .set(at.0 & 15, at.1, at.2 & 15, number);
        }
        // The legend of what keeps no record: each one's state now, to its name.
        let mut new_legend = BTreeMap::new();
        if let Some(chunk) = blocks.chunks.get(&key) {
            let mut present = vec![false; blocks.names.len() + 1];
            chunk
                .names
                .for_each(|_, _, _, n| present[usize::from(n)] = true);
            for (n, name) in blocks.names.iter().enumerate() {
                if !present[n + 1] || blocks.recorded(name) {
                    continue;
                }
                for u in plan.uses.iter().filter(|u| &u.name == name) {
                    new_legend.insert(u.state.text(), legend_value(name, u.facing));
                }
            }
        }
        blocks.refresh_ticking(key);
        drop(blocks);
        for (at, state) in sets {
            ops.set(world.to_owned(), at.0, at.1, at.2, state, false);
        }
        for (at, json) in records_to_write {
            self.write_record(world, at, json);
        }
        self.write_legend(world, chunk_x, chunk_z, legend_text.as_deref(), &new_legend);
        self.finish(after);
    }

    /// Puts block `name` at a position: its state set (neighbours aren't told), what's recorded of it, its
    /// centity spawned. False when the project has no such block, the game has no state left to hold it, or the
    /// chunk isn't loaded. A custom block that was there is forgotten first. No script hears of it.
    pub fn place(
        &self,
        name: &str,
        world: &str,
        x: i32,
        y: i32,
        z: i32,
        facing: Option<BlockFacing>,
    ) -> bool {
        let state = {
            let blocks = self.shared.blocks.borrow();
            if !blocks.usable() || !blocks.has(name) {
                return false;
            }
            let turned = facing.filter(|f| blocks.facings_of(name).contains(f));
            match blocks.state_of(name, turned) {
                Some(state) => state,
                None => return false,
            }
        };
        if !self
            .platform()
            .blocks()
            .set(world.to_owned(), x, y, z, state.clone(), false)
        {
            return false;
        }
        let facing = self
            .shared
            .blocks
            .borrow()
            .plan
            .as_ref()
            .and_then(|p| p.facing_of(&state));
        self.register(name, world, (x, y, z), facing);
        true
    }

    /// Takes `name` into the chunk's tracked blocks, with the record and centity; whatever custom block was there
    /// goes first.
    fn register(&self, name: &str, world: &str, at: At, facing: Option<BlockFacing>) {
        let ticks_now = self.scripts.tick_count();
        let (was, recorded, tick) = {
            let mut blocks = self.shared.blocks.borrow_mut();
            let key = blocks.key(world, at.0 >> 4, at.2 >> 4);
            let was = blocks.take(key, at);
            let tick = blocks.defs.get(name).and_then(|d| d.file.tick);
            let placed = Placed {
                name: name.to_owned(),
                centity: None,
                next: ticks_now + tick.unwrap_or(0) as u64,
                facing,
                pinned: false,
            };
            blocks.put(key, at, placed);
            if tick.is_some() {
                blocks.ticking.insert(key);
            }
            (was, blocks.recorded(name), tick)
        };
        let _ = tick;
        if let Some(was) = was {
            self.centity_gone(was.centity);
        }
        if recorded {
            let centity = self.spawn_centity(world, at, name, facing);
            {
                let mut blocks = self.shared.blocks.borrow_mut();
                let key = blocks.key(world, at.0 >> 4, at.2 >> 4);
                if let Some(placed) = blocks
                    .chunks
                    .get_mut(&key)
                    .and_then(|b| b.kept.get_mut(&at))
                {
                    placed.centity = centity;
                }
            }
            self.write_record(world, at, Some(record(name, centity, facing)));
        } else {
            // Known by its state: the chunk's legend says which block that is (and a record of what was there goes).
            self.write_record(world, at, None);
            self.note_legend(world, at.0 >> 4, at.2 >> 4, name, facing);
        }
    }

    /// Forgets what's recorded at a position (its block was removed, or replaced by something else).
    pub fn forget(&self, world: &str, x: i32, y: i32, z: i32) -> Option<String> {
        let placed = {
            let mut blocks = self.shared.blocks.borrow_mut();
            let key = blocks.known_key(world, x >> 4, z >> 4)?;
            blocks.take(key, (x, y, z))?
        };
        self.centity_gone(placed.centity);
        self.write_record(world, (x, y, z), None);
        Some(placed.name)
    }

    /// What a script did to the block at a position: if it was one of the project's and isn't its state any more,
    /// it's forgotten. `state` is what's there now.
    pub fn replaced(&self, world: &str, x: i32, y: i32, z: i32, state: &str) {
        let still = {
            let blocks = self.shared.blocks.borrow();
            let Some(name) = blocks.at(world, x, y, z) else {
                return;
            };
            blocks
                .plan
                .as_ref()
                .is_some_and(|p| p.states_of(&name).iter().any(|s| s == state))
        };
        if !still {
            self.forget(world, x, y, z);
        }
    }

    /// The way the block at a position faces, from its state: a block of the project's that turns.
    pub fn facing_at(&self, world: &str, x: i32, y: i32, z: i32) -> Option<BlockFacing> {
        self.shared.blocks.borrow().at(world, x, y, z)?;
        let state = self
            .platform()
            .blocks()
            .get(world.to_owned(), x, y, z)?
            .state;
        self.shared.blocks.borrow().plan.as_ref()?.facing_of(&state)
    }

    /// Turns the block at a position to `facing`, keeping everything else about it. False when it isn't one of
    /// the project's blocks, its chunk isn't loaded, or it can't face that way.
    pub fn turn(&self, world: &str, x: i32, y: i32, z: i32, facing: BlockFacing) -> bool {
        let (name, state) = {
            let blocks = self.shared.blocks.borrow();
            let Some(name) = blocks.at(world, x, y, z) else {
                return false;
            };
            if !blocks.facings_of(&name).contains(&facing) {
                return false;
            }
            let Some(state) = blocks.state_of(&name, Some(facing)) else {
                return false;
            };
            (name, state)
        };
        if !self
            .platform()
            .blocks()
            .set(world.to_owned(), x, y, z, state, false)
        {
            return false;
        }
        let kept = {
            let mut blocks = self.shared.blocks.borrow_mut();
            let recorded = blocks.recorded(&name);
            let key = blocks.key(world, x >> 4, z >> 4);
            blocks
                .chunks
                .get_mut(&key)
                .and_then(|b| b.kept.get_mut(&(x, y, z)))
                .map(|placed| {
                    placed.facing = Some(facing);
                    (placed.centity, recorded || placed.pinned)
                })
        };
        match kept {
            Some((centity, rewrite)) => {
                if rewrite {
                    self.write_record(world, (x, y, z), Some(record(&name, centity, Some(facing))));
                }
                if let Some(id) = centity
                    && let Some(instance) = self.shared.centities.borrow_mut().get_mut(id)
                {
                    instance.turn_to(yaw_of(Some(facing)));
                }
            }
            None => self.note_legend(world, x >> 4, z >> 4, &name, Some(facing)),
        }
        true
    }

    /// Raises `tick` on each placed block due one, while its block's script listens.
    fn tick_blocks(&self) {
        let now = self.scripts.tick_count();
        let due: Vec<(String, At, String)> = {
            let mut blocks = self.shared.blocks.borrow_mut();
            let keys: Vec<ChunkKey> = blocks.ticking.iter().copied().collect();
            let intervals: HashMap<String, i32> = blocks
                .defs
                .iter()
                .filter_map(|(id, d)| Some((id.clone(), d.file.tick?)))
                .collect();
            let mut due = Vec::new();
            for key in keys {
                let world = blocks.worlds[key.world_index].clone();
                let Some(chunk) = blocks.chunks.get_mut(&key) else {
                    continue;
                };
                let mut ats: Vec<&At> = chunk.kept.keys().collect();
                ats.sort();
                let ats: Vec<At> = ats.into_iter().copied().collect();
                for at in ats {
                    let placed = chunk.kept.get_mut(&at).expect("there");
                    let Some(interval) = intervals.get(&placed.name) else {
                        continue;
                    };
                    if now < placed.next {
                        continue;
                    }
                    placed.next = now + *interval as u64;
                    due.push((world.clone(), at, placed.name.clone()));
                }
            }
            due
        };
        let lua = self.scripts.host().lua().clone();
        for (world, at, name) in due {
            let Ok(target) = netherforge_api::handle::push_any(
                &lua,
                &Handle::ProjectBlock(handles::ProjectBlock { id: name }),
            ) else {
                continue;
            };
            let target = Target::Handle(target);
            if !self.scripts.listening(&target, "tick") {
                continue;
            }
            let payload = shapes::ProjectBlockTickEvent {
                block: handles::CustomBlock {
                    world,
                    position: pack(at.0, at.1, at.2),
                },
            };
            if let Ok(Value::Table(fields)) = codecs::ProjectBlockTickEvent.push(&lua, payload) {
                self.scripts.raise(&target, "tick", fields);
            }
        }
    }

    /// Looks at a few chunks a tick, each every so often: a block something else took away, or a paste, is noticed
    /// within seconds. A chunk whose blocks and legend are as they were the last time is passed over.
    fn sweep(&self) {
        for _ in 0..SWEEP_PER_TICK {
            let next = {
                let mut blocks = self.shared.blocks.borrow_mut();
                if blocks.sweep_queue.is_empty() {
                    let keys: Vec<ChunkKey> = blocks.chunks.keys().copied().collect();
                    blocks.sweep_queue.extend(keys);
                    let present: HashSet<ChunkKey> = blocks.chunks.keys().copied().collect();
                    blocks.swept.retain(|k, _| present.contains(k));
                }
                blocks.sweep_queue.pop_front()
            };
            let Some(key) = next else { return };
            let world = {
                let blocks = self.shared.blocks.borrow();
                if !blocks.chunks.contains_key(&key) {
                    continue;
                }
                blocks.worlds[key.world_index].clone()
            };
            let before = self.fingerprint(&world, key);
            if before.is_some() && self.shared.blocks.borrow().swept.get(&key) == before.as_ref() {
                continue;
            }
            self.reconcile(&world, key.x, key.z, false);
            let after = self.fingerprint(&world, key);
            let mut blocks = self.shared.blocks.borrow_mut();
            match after.filter(|_| blocks.chunks.contains_key(&key)) {
                Some(print) => {
                    blocks.swept.insert(key, print);
                }
                None => {
                    blocks.swept.remove(&key);
                }
            }
        }
    }

    /// What a chunk's custom blocks are, summed up: the cells that could be any carrier state, and its legend.
    fn fingerprint(&self, world: &str, key: ChunkKey) -> Option<i64> {
        let ops = self.platform().blocks();
        let legend_text = ops.legend(world.to_owned(), key.x, key.z);
        let mut states: Vec<String> = self
            .shared
            .blocks
            .borrow()
            .by_state
            .keys()
            .cloned()
            .collect();
        states.sort();
        for state in read_legend(legend_text.as_deref()).into_keys() {
            if !states.contains(&state) {
                states.push(state);
            }
        }
        let blocks = ops.fingerprint(world.to_owned(), key.x, key.z, states)?;
        let legend_hash = legend_text.as_deref().map_or(0, java_hash);
        Some(blocks.wrapping_mul(31).wrapping_add(i64::from(legend_hash)))
    }

    fn start_script(&self, id: &str) {
        let (path, folder) = {
            let blocks = self.shared.blocks.borrow();
            let Some(def) = blocks.defs.get(id) else {
                return;
            };
            let Some(script) = &def.file.script else {
                return;
            };
            let Some(folder) = blocks.folders.get(id) else {
                return;
            };
            (format!("{folder}/{}", script.file), folder.clone())
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return;
        };
        let Ok(this) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::ProjectBlock(handles::ProjectBlock { id: id.to_owned() }),
        ) else {
            return;
        };
        let (scope, _) = modules.script_with(
            &format!("block {id}"),
            &folder,
            &path,
            &[("this", Value::UserData(this))],
        );
        if let Some(def) = self.shared.blocks.borrow_mut().defs.get_mut(id) {
            def.scope = scope;
        }
    }

    fn stop_script(&self, id: &str) {
        let scope = self
            .shared
            .blocks
            .borrow_mut()
            .defs
            .get_mut(id)
            .and_then(|d| d.scope.take());
        if let Some(scope) = scope {
            match self.shared.modules.borrow().clone() {
                Some(modules) => modules.close(scope),
                None => self.scripts.close(scope),
            }
        }
    }

    /// Reconciles every loaded chunk of every world.
    fn reconcile_loaded(&self) {
        let platform = self.platform();
        for world in platform.worlds().names() {
            for (x, z) in platform.worlds().loaded_chunks(world.clone()) {
                self.reconcile(&world, x, z, false);
            }
        }
    }

    /// Tells `scripts` which project block handles still name a block.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let blocks = shared.blocks.try_borrow().ok()?;
            match handle {
                Handle::ProjectBlock(b) => Some(blocks.has(&b.id)),
                _ => None,
            }
        }));
    }
}

/// Java's `String.hashCode`, which Kotlin's fingerprint summed a legend with.
fn java_hash(text: &str) -> i32 {
    text.encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(i32::from(c)))
}

/// Defines the blocks from the project: running files, folders, the plan of states, and its problems.
fn define(shared: &Shared, project: &Define) {
    let platform = &*shared.platform;
    let mut blocks = shared.blocks.borrow_mut();
    let running: BTreeMap<String, BlockFile> = project
        .project
        .running::<BlockFile>()
        .into_iter()
        .map(|(id, f)| (id, f.clone()))
        .collect();
    let scopes: BTreeMap<String, Option<ScopeId>> = blocks
        .defs
        .iter_mut()
        .map(|(id, d)| (id.clone(), d.scope.take()))
        .collect();
    blocks.defs = running
        .into_iter()
        .map(|(id, file)| {
            let scope = scopes.get(&id).copied().flatten();
            (id, Def { file, scope })
        })
        .collect();
    let mut folders = BTreeMap::new();
    let mut every: BTreeMap<String, Option<BlockFile>> = BTreeMap::new();
    let mut carriers_of = BTreeMap::new();
    let mut rotations = BTreeMap::new();
    let mut paths = BTreeMap::new();
    for (id, resource) in project.project.everywhere(KIND) {
        let found = &resource.found;
        let folder = found.kind.location_of(&found.id, &found.group);
        let folder = match netherforge_format::kinds::split_package(&id) {
            (Some(namespace), _) => netherforge_format::kinds::package_path(namespace, &folder),
            (None, _) => folder,
        };
        if let Some(model) = resource.model::<BlockFile>() {
            carriers_of.insert(id.clone(), model.carrier.unwrap_or_default());
            rotations.insert(id.clone(), model.rotation.unwrap_or(BlockRotation::None));
        }
        paths.insert(id.clone(), format!("{folder}/block.json"));
        every.insert(id.clone(), blocks.defs.get(&id).map(|d| d.file.clone()));
        folders.insert(id, folder);
    }
    blocks.folders = folders;
    blocks.generated_worlds = project
        .project
        .manifest
        .as_ref()
        .map(|m| {
            m.worlds
                .iter()
                .flat_map(|w| w.iter())
                .filter(|(_, w)| w.terrain.is_some())
                .map(|(name, _)| name.to_string())
                .collect()
        })
        .unwrap_or_default();
    let home = project.project.namespace().to_owned();
    let refs: BTreeMap<String, Option<&BlockFile>> = every
        .iter()
        .map(|(id, f)| (id.clone(), f.as_ref()))
        .collect();
    let plan = if refs.is_empty() {
        None
    } else {
        carriers::plan(
            &refs,
            &home,
            Some(platform.game()),
            &carriers_of,
            &rotations,
        )
    };
    blocks.by_state = plan
        .iter()
        .flat_map(|p| p.uses.iter().map(|u| (u.state.text(), u.name.clone())))
        .collect();
    blocks.swept.clear();
    let mut problems = Vec::new();
    if !refs.is_empty() && plan.is_none() {
        problems.push(netherforge_format::codes::RUNTIME_BLOCKS.at(
            "blocks",
            "This server's game has none of the blocks to hold the project's blocks in",
            None,
        ));
    }
    let noun =
        |carrier: BlockCarrier| netherforge_format::canonical::name_of(&carrier).replace('_', " ");
    for name in plan.iter().flat_map(|p| &p.unheld) {
        let carrier = carriers_of.get(name).copied().unwrap_or_default();
        problems.push(netherforge_format::codes::RUNTIME_BLOCKS.at(
            paths.get(name).map_or("blocks", String::as_str),
            format!(
                "This server's game has no {} states to hold this block in",
                noun(carrier)
            ),
            Some("$.carrier".into()),
        ));
    }
    for name in plan.iter().flat_map(|p| &p.overflow) {
        let carrier = carriers_of.get(name).copied().unwrap_or_default();
        let capacity = plan
            .as_ref()
            .and_then(|p| p.pools.get(&carrier))
            .map_or(0, |p| p.carriers.len());
        problems.push(netherforge_format::codes::BLOCK_CARRIERS.at(
            paths.get(name).map_or("blocks", String::as_str),
            format!(
                "The game has {capacity} {} states to hold blocks in, and the project has more {} blocks than that",
                noun(carrier),
                noun(carrier)
            ),
            Some("$".into()),
        ));
    }
    // Asks the server to hold still every carrier the plan uses: one it won't is a problem.
    let mut held = true;
    if let Some(plan) = &plan {
        let mut used: Vec<BlockCarrier> = plan.pools.keys().copied().collect();
        if used.is_empty() {
            used.push(BlockCarrier::NoteBlock);
        }
        for carrier in used {
            if platform.blocks().freeze(carrier) {
                continue;
            }
            held = false;
            let message = format!(
                "This server won't stop working {} states out itself, so the project's blocks can't be held in them",
                noun(carrier)
            );
            platform.log().error(message.clone(), None);
            problems.push(netherforge_format::codes::RUNTIME_BLOCKS.at("blocks", message, None));
        }
    }
    blocks.held = held;
    blocks.plan = plan;
    blocks.problems = problems;
}

/// The blocks service.
pub struct BlocksService {
    pub shared: Rc<Shared>,
    pub scripts: RefCell<Option<crate::Scripts>>,
}

impl BlocksService {
    fn context(&self, room: &Room) -> BlocksContext {
        BlocksContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }

    fn running(&self) -> Option<BlocksContext> {
        let scripts = self.scripts.borrow().clone()?;
        Some(BlocksContext {
            shared: self.shared.clone(),
            scripts,
        })
    }
}

impl Service for BlocksService {
    fn name(&self) -> &'static str {
        "blocks"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &[KIND]
    }

    /// Before any script runs: a module's body may place blocks.
    fn start(&self, room: &Room, project: &Define) {
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
        define(&self.shared, project);
    }

    /// Their scripts start once modules run; then every loaded chunk is read.
    fn after_start(&self, room: &Room) {
        let context = self.context(room);
        let ids = self.shared.blocks.borrow().ids();
        for id in ids {
            context.start_script(&id);
        }
        if self.shared.blocks.borrow().usable() {
            context.reconcile_loaded();
        }
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        if !self.shared.blocks.borrow().usable() {
            return;
        }
        let context = self.context(room);
        context.tick_blocks();
        context.sweep();
    }

    /// Each changed block's script restarts on its new file; then every chunk is read again under the new plan.
    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let context = self.context(room);
        for id in ids {
            context.stop_script(id);
        }
        let before: HashSet<String> = self.shared.blocks.borrow().defs.keys().cloned().collect();
        define(&self.shared, project);
        let now = self.scripts_now();
        for id in ids {
            if self.shared.blocks.borrow().has(id) {
                context.start_script(id);
                let tick = self
                    .shared
                    .blocks
                    .borrow()
                    .defs
                    .get(id)
                    .and_then(|d| d.file.tick);
                let mut blocks = self.shared.blocks.borrow_mut();
                for chunk in blocks.chunks.values_mut() {
                    for placed in chunk.kept.values_mut().filter(|p| &p.name == id) {
                        placed.next = now + tick.unwrap_or(0) as u64;
                    }
                }
            } else if before.contains(id) {
                let handle = Handle::ProjectBlock(handles::ProjectBlock { id: id.clone() });
                if let Ok(target) =
                    netherforge_api::handle::push_any(context.scripts.host().lua(), &handle)
                {
                    context.scripts.drop_target(&Target::Handle(target));
                }
            }
        }
        if self.shared.blocks.borrow().usable() {
            let keys: Vec<(String, i32, i32)> = {
                let blocks = self.shared.blocks.borrow();
                blocks
                    .chunks
                    .keys()
                    .map(|k| (blocks.worlds[k.world_index].clone(), k.x, k.z))
                    .collect()
            };
            for (world, x, z) in keys {
                context.reconcile(&world, x, z, false);
            }
        }
    }

    fn chunk_loaded(&self, world: &str, chunk_x: i32, chunk_z: i32, generated: bool) {
        if !self.shared.blocks.borrow().usable() {
            return;
        }
        if let Some(context) = self.running() {
            context.reconcile(world, chunk_x, chunk_z, generated);
        }
    }

    fn chunk_unloading(&self, world: &str, chunk_x: i32, chunk_z: i32) {
        let mut blocks = self.shared.blocks.borrow_mut();
        if let Some(key) = blocks.known_key(world, chunk_x, chunk_z) {
            blocks.chunks.remove(&key);
            blocks.ticking.shift_remove(&key);
        }
    }

    fn problems(&self) -> Vec<Problem> {
        self.shared.blocks.borrow().problems()
    }

    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        let ids = self.shared.blocks.borrow().ids();
        for id in ids {
            context.stop_script(&id);
        }
        *self.shared.blocks.borrow_mut() = CustomBlocks::default();
    }
}

impl BlocksService {
    fn scripts_now(&self) -> u64 {
        self.scripts.borrow().as_ref().map_or(0, |s| s.tick_count())
    }
}

impl GameContext {
    pub(crate) fn blocks(&self) -> BlocksContext {
        BlocksContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    /// The block handle of a position as the class it is now: a `CustomBlock` when the project's block is there.
    pub(crate) fn custom_at(&self, world: &str, position: i64) -> Option<handles::CustomBlock> {
        let (x, y, z) = unpack(position);
        self.shared.blocks.borrow().at(world, x, y, z)?;
        Some(handles::CustomBlock {
            world: world.to_owned(),
            position,
        })
    }
}

impl NfBlocksApi for GameContext {
    fn get(&self, _caller: &Caller, block: String) -> ApiResult<Option<handles::ProjectBlock>> {
        let id = self.with_names(|names| names.resource(KIND, &block))?;
        Ok(self
            .shared
            .blocks
            .borrow()
            .has(&id)
            .then_some(handles::ProjectBlock { id }))
    }

    fn all(&self, _caller: &Caller) -> ApiResult<Vec<handles::ProjectBlock>> {
        let ids = self.shared.blocks.borrow().ids();
        Ok(ids
            .into_iter()
            .map(|id| handles::ProjectBlock { id })
            .collect())
    }
}

impl ProjectBlockApi for GameContext {
    fn id(&self, this: &handles::ProjectBlock) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.id)))
    }

    /// Its file's `properties`, an empty table when it has none.
    fn properties(&self, this: &handles::ProjectBlock) -> ApiResult<Value> {
        let properties = {
            let blocks = self.shared.blocks.borrow();
            let Some(def) = blocks.defs.get(&this.id) else {
                return Ok(Value::Nil);
            };
            def.file.properties.clone()
        };
        let json = serde_json::to_string(&properties.unwrap_or_default())
            .map_err(|e| err(e.to_string()))?;
        netherforge_luau::json::decode(
            self.scripts.host().lua(),
            &json,
            &netherforge_luau::json::NoTags,
        )
        .map_err(err)
    }

    fn exists(&self, this: &handles::ProjectBlock) -> ApiResult<bool> {
        Ok(self.shared.blocks.borrow().has(&this.id))
    }

    fn place(
        &self,
        this: &handles::ProjectBlock,
        location: values::Location,
        options: Option<shapes::BlockPlaceOptions>,
    ) -> ApiResult<Option<handles::CustomBlock>> {
        if !self.shared.blocks.borrow().has(&this.id) {
            return Ok(None);
        }
        let facing = options
            .and_then(|o| o.facing)
            .and_then(|f| facing_of_name(&f));
        let ways = self.shared.blocks.borrow().facings_of(&this.id);
        if let Some(facing) = facing
            && !ways.contains(&facing)
        {
            let name = self.with_names(|names| names.spell(&this.id));
            let why = if ways.is_empty() {
                "(it doesn't turn: its block.json has no rotation)".to_owned()
            } else {
                let each: Vec<&str> = ways.iter().map(|f| facing_name(*f)).collect();
                format!("(it faces {})", each.join(", "))
            };
            return Err(err(format!(
                "options.facing: block {name} can't face {} {why}",
                facing_name(facing)
            )));
        }
        let world = location.world.name;
        if !self.shared.platform.worlds().exists(world.clone()) {
            return Ok(None);
        }
        let (x, y, z) = (
            location.position.x.floor() as i32,
            location.position.y.floor() as i32,
            location.position.z.floor() as i32,
        );
        if !self.blocks().place(&this.id, &world, x, y, z, facing) {
            return Ok(None);
        }
        Ok(Some(handles::CustomBlock {
            world,
            position: pack(x, y, z),
        }))
    }

    fn on(
        &self,
        this: &handles::ProjectBlock,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::ProjectBlock(this.clone()),
            "ProjectBlock:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::ProjectBlock,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::ProjectBlock(this.clone()),
            "ProjectBlock:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl CustomBlockApi for GameContext {
    fn id(&self, this: &handles::CustomBlock) -> ApiResult<Option<String>> {
        let (x, y, z) = unpack(this.position);
        let name = self.shared.blocks.borrow().at(&this.world, x, y, z);
        Ok(name.map(|n| self.with_names(|names| names.spell(&n))))
    }

    fn project(&self, this: &handles::CustomBlock) -> ApiResult<Option<handles::ProjectBlock>> {
        let (x, y, z) = unpack(this.position);
        let name = self.shared.blocks.borrow().at(&this.world, x, y, z);
        Ok(name.map(|id| handles::ProjectBlock { id }))
    }

    fn facing(&self, this: &handles::CustomBlock) -> ApiResult<Option<String>> {
        let (x, y, z) = unpack(this.position);
        Ok(self
            .blocks()
            .facing_at(&this.world, x, y, z)
            .map(|f| facing_name(f).to_owned()))
    }

    fn set_facing(&self, this: &handles::CustomBlock, facing: String) -> ApiResult<bool> {
        let (x, y, z) = unpack(this.position);
        let Some(facing) = facing_of_name(&facing) else {
            return Ok(false);
        };
        Ok(self.blocks().turn(&this.world, x, y, z, facing))
    }
}
