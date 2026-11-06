//! The states of the game's blocks that custom blocks are held in (format's
//! `BlockCarriers`): each carrier keeps some for the game's own use (a
//! player's note block, string or leaves) and gives the rest to blocks,
//! rarest first. The pack build's plan takes them in this order; the checks
//! ask how many there are.

use std::collections::BTreeMap;

use super::block::BlockCarrier;
use crate::game::{GameData, Registry};

pub const NOTE_BLOCK: &str = "minecraft:note_block";
pub const TRIPWIRE: &str = "minecraft:tripwire";
pub const LEAVES_TAG: &str = "minecraft:leaves";

const INSTRUMENT: &str = "instrument";
const NOTE: &str = "note";
const POWERED: &str = "powered";
const ATTACHED: &str = "attached";
const DISARMED: &str = "disarmed";
const SIDES: [&str; 4] = ["east", "north", "south", "west"];
const DISTANCE: &str = "distance";
const PERSISTENT: &str = "persistent";
const WATERLOGGED: &str = "waterlogged";

/// One state of one of the game's blocks.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct State {
    pub id: String,
    pub properties: BTreeMap<String, String>,
}

impl State {
    fn on(&self, property: &str) -> bool {
        self.properties.get(property).is_some_and(|v| v == "true")
    }
}

/// The states one carrier holds blocks in: each of the game's blocks it takes
/// states of (with every state), and the states blocks take, rarest first.
#[derive(Clone, Debug, PartialEq)]
pub struct Pool {
    pub carrier: BlockCarrier,
    pub blocks: Vec<(String, Vec<State>)>,
    pub carriers: Vec<State>,
}

/// Every combination of `names`' values.
fn every(id: &str, names: &[&String], properties: &BTreeMap<String, Vec<String>>) -> Vec<State> {
    names.iter().fold(
        vec![State {
            id: id.to_owned(),
            properties: BTreeMap::new(),
        }],
        |states, name| {
            states
                .iter()
                .flat_map(|state| {
                    properties[*name].iter().map(move |value| {
                        let mut next = state.clone();
                        next.properties.insert((*name).clone(), value.clone());
                        next
                    })
                })
                .collect()
        },
    )
}

fn has(properties: &BTreeMap<String, Vec<String>>, names: &[&str]) -> bool {
    names
        .iter()
        .all(|n| properties.get(*n).is_some_and(|v| !v.is_empty()))
}

fn binary(properties: &BTreeMap<String, Vec<String>>, name: &str) -> bool {
    properties.get(name).is_some_and(|v| {
        let mut v = v.clone();
        v.sort();
        v == ["false", "true"]
    })
}

/// The states `carrier` holds blocks in, in `game`; `None` when there are none
/// (no game data, or a game without the blocks).
pub fn pool(game: Option<&dyn GameData>, carrier: BlockCarrier) -> Option<Pool> {
    let game = game?;
    match carrier {
        BlockCarrier::NoteBlock => {
            let properties = game.block_properties(NOTE_BLOCK)?;
            if !has(&properties, &[INSTRUMENT, NOTE, POWERED]) {
                return None;
            }
            let instruments = &properties[INSTRUMENT];
            // Vanilla note blocks keep the default instrument's column.
            let vanilla = game
                .block_default(NOTE_BLOCK, INSTRUMENT)
                .unwrap_or_else(|| instruments[0].clone());
            let state = |i: &str, n: &str, p: &str| State {
                id: NOTE_BLOCK.into(),
                properties: BTreeMap::from([
                    (INSTRUMENT.into(), i.into()),
                    (NOTE.into(), n.into()),
                    (POWERED.into(), p.into()),
                ]),
            };
            let (notes, powered) = (&properties[NOTE], &properties[POWERED]);
            let all = instruments
                .iter()
                .flat_map(|i| {
                    notes
                        .iter()
                        .flat_map(move |n| powered.iter().map(move |p| state(i, n, p)))
                })
                .collect();
            let mut powered_first = powered.clone();
            powered_first.sort_by_key(|p| p != "true");
            let carriers = instruments
                .iter()
                .rev()
                .filter(|i| **i != vanilla)
                .flat_map(|i| {
                    powered_first
                        .iter()
                        .flat_map(move |p| notes.iter().map(move |n| state(i, n, p)))
                })
                .collect();
            Some(Pool {
                carrier,
                blocks: vec![(NOTE_BLOCK.into(), all)],
                carriers,
            })
        }
        BlockCarrier::Tripwire => {
            let properties = game.block_properties(TRIPWIRE)?;
            if !binary(&properties, DISARMED) || !binary(&properties, POWERED) {
                return None;
            }
            let names: Vec<&String> = properties.keys().collect();
            let all = every(TRIPWIRE, &names, &properties);
            let vanilla = |s: &State| !s.on(DISARMED) && !s.on(POWERED);
            let mut carriers: Vec<State> = all.iter().filter(|s| !vanilla(s)).cloned().collect();
            // Powered and disarmed, then disarmed, then powered: the rarer a vanilla tripwire is in it, the sooner.
            carriers.sort_by_key(|s| {
                let rank = if s.on(DISARMED) && s.on(POWERED) {
                    0
                } else if s.on(DISARMED) {
                    1
                } else {
                    2
                };
                let sides: String = SIDES
                    .iter()
                    .map(|side| if s.on(side) { '1' } else { '0' })
                    .collect();
                (rank, s.on(ATTACHED), sides)
            });
            Some(Pool {
                carrier,
                blocks: vec![(TRIPWIRE.into(), all)],
                carriers,
            })
        }
        BlockCarrier::Leaves => {
            let mut blocks = Vec::new();
            let mut carriers = Vec::new();
            for id in game.tag_of(Registry::Block, LEAVES_TAG)? {
                let Some(properties) = game.block_properties(id) else {
                    continue;
                };
                if !has(&properties, &[DISTANCE, PERSISTENT, WATERLOGGED]) {
                    continue;
                }
                let names: Vec<&String> = properties.keys().collect();
                let all = every(id, &names, &properties);
                // The farthest a leaves block can be from a log: what a frozen server places a player's at.
                let farthest = properties[DISTANCE]
                    .iter()
                    .max_by_key(|d| d.parse::<i32>().unwrap_or(0))
                    .cloned();
                let distance = |s: &State| {
                    s.properties
                        .get(DISTANCE)
                        .and_then(|d| d.parse::<i32>().ok())
                        .unwrap_or(0)
                };
                let mut own: Vec<State> = all
                    .iter()
                    .filter(|s| {
                        s.on(PERSISTENT)
                            && !s.on(WATERLOGGED)
                            && s.properties.get(DISTANCE) != farthest.as_ref()
                    })
                    .cloned()
                    .collect();
                own.sort_by_key(distance);
                carriers.extend(own);
                blocks.push((id.clone(), all));
            }
            (!blocks.is_empty()).then_some(Pool {
                carrier,
                blocks,
                carriers,
            })
        }
    }
}

/// How many blocks `carrier` can hold in `game`: 0 when that isn't known.
pub fn capacity(game: Option<&dyn GameData>, carrier: BlockCarrier) -> usize {
    pool(game, carrier).map_or(0, |p| p.carriers.len())
}

/// The game's blocks whose states `carrier` holds blocks in.
pub fn blocks_of(game: Option<&dyn GameData>, carrier: BlockCarrier) -> Vec<String> {
    pool(game, carrier).map_or_else(Vec::new, |p| {
        p.blocks.into_iter().map(|(id, _)| id).collect()
    })
}

impl State {
    /// `id[key=value,…]`, as a block state is written.
    pub fn text(&self) -> String {
        let properties: Vec<(String, String)> = self
            .properties
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        crate::block_state::BlockState {
            id: self.id.clone(),
            properties,
        }
        .to_string()
    }
}

impl Pool {
    /// Whether `state` is one a custom block could be in.
    pub fn is_carrier(&self, state: &State) -> bool {
        self.carriers.contains(state)
    }

    /// The model the game draws every state of `block` with, when it's one model for all of them (a note block's,
    /// a leaves block's); none when its states differ (tripwire's), which only the client's file says.
    pub fn vanilla_model(&self, block: &str) -> Option<String> {
        match self.carrier {
            BlockCarrier::NoteBlock => Some(VANILLA_MODEL.to_owned()),
            BlockCarrier::Tripwire => None,
            BlockCarrier::Leaves => {
                let (namespace, path) = block
                    .split_once(':')
                    .unwrap_or((crate::game::NAMESPACE, block));
                Some(format!("{namespace}:block/{path}"))
            }
        }
    }
}

/// What the pack draws every note block state no custom block uses as: the game's own look.
pub const VANILLA_MODEL: &str = "minecraft:block/note_block";

/// One block's use of a carrier: `name` is the block as the project names it, `pack` the pack its model is in
/// (`ui`, or `library:ui` for a package's) and `entry` its key there, both none for a block with no model yet.
/// `hidden` is a block drawn by a centity, which shows no cube of its own.
#[derive(Clone, Debug, PartialEq)]
pub struct Use {
    pub name: String,
    pub state: State,
    pub pack: Option<String>,
    pub entry: Option<String>,
    pub hidden: bool,
    /// The way the block faces in this state, for a block that turns.
    pub facing: Option<super::block::BlockFacing>,
}

/// Which state each block takes (format's `BlockCarriers.Plan`). `overflow` is the blocks there was no state left
/// for in their carrier, and `unheld` those whose carrier the game doesn't have.
#[derive(Clone, Debug, PartialEq)]
pub struct Plan {
    pub pools: BTreeMap<BlockCarrier, Pool>,
    pub home: String,
    pub uses: Vec<Use>,
    pub overflow: Vec<String>,
    pub unheld: Vec<String>,
}

impl Plan {
    /// The state `name` is held as facing `facing`: its one state for a block that doesn't turn.
    pub fn state_of(
        &self,
        name: &str,
        facing: Option<super::block::BlockFacing>,
    ) -> Option<&State> {
        let held: Vec<&Use> = self.uses.iter().filter(|u| u.name == name).collect();
        let first = held.first()?;
        if first.facing.is_none() {
            return Some(&first.state);
        }
        let wanted = facing.unwrap_or(super::block::BlockFacing::North);
        held.iter()
            .find(|u| u.facing == Some(wanted))
            .map(|u| &u.state)
    }

    /// The way the block held in `state` (canonical text) faces, for a block that turns.
    pub fn facing_of(&self, state: &str) -> Option<super::block::BlockFacing> {
        self.uses
            .iter()
            .find(|u| u.state.text() == state)
            .and_then(|u| u.facing)
    }

    /// Every state `name` is held in: one, or one per way it faces.
    pub fn states_of(&self, name: &str) -> Vec<String> {
        self.uses
            .iter()
            .filter(|u| u.name == name)
            .map(|u| u.state.text())
            .collect()
    }

    /// Whether `state` (canonical text) is one any carrier could hold a block in.
    pub fn is_carrier(&self, state: &str) -> bool {
        let Some(parsed) = crate::block_state::BlockState::parse(state) else {
            return false;
        };
        let state = State {
            id: parsed.id,
            properties: parsed.properties.into_iter().collect(),
        };
        self.pools.values().any(|p| p.is_carrier(&state))
    }

    /// The block `state` (canonical text) is the carrier of.
    pub fn name_of(&self, state: &str) -> Option<&str> {
        self.uses
            .iter()
            .find(|u| u.state.text() == state)
            .map(|u| u.name.as_str())
    }
}

/// Gives every block in `blocks` (by the name the project knows it by, a package's `ns:id`) a state of its
/// carrier's pool, in name order. A block that doesn't run (`None`) keeps its place so the others' states don't
/// move while it's being fixed, in the carrier `carriers` says its file names. A running block's references are
/// written in full, so its model resolves in `home`. None without game data.
pub fn plan(
    blocks: &BTreeMap<String, Option<&super::block::BlockFile>>,
    home: &str,
    game: Option<&dyn GameData>,
    carriers: &BTreeMap<String, BlockCarrier>,
    rotations: &BTreeMap<String, super::block::BlockRotation>,
) -> Option<Plan> {
    game?;
    let mut pools: BTreeMap<BlockCarrier, Option<Pool>> = BTreeMap::new();
    let mut next: BTreeMap<BlockCarrier, usize> = BTreeMap::new();
    let (mut uses, mut overflow, mut unheld) = (Vec::new(), Vec::new(), Vec::new());
    for (name, block) in blocks {
        let carrier = block
            .and_then(|b| b.carrier)
            .or_else(|| carriers.get(name).copied())
            .unwrap_or_default();
        let Some(pool) = pools
            .entry(carrier)
            .or_insert_with(|| pool(game, carrier))
            .as_ref()
        else {
            unheld.push(name.clone());
            continue;
        };
        let model = block.and_then(|b| b.model.as_deref());
        let resolved = model.and_then(|m| crate::reference::resolve_any(home, m));
        if model.is_some() && resolved.is_none() {
            continue;
        }
        // A block that turns takes a state per way it faces, all or none of them.
        let rotation = block
            .and_then(|b| b.rotation)
            .or_else(|| rotations.get(name).copied())
            .unwrap_or(super::block::BlockRotation::None);
        let facings = rotation.facings();
        let at = next.get(&carrier).copied().unwrap_or(0);
        let needed = facings.len().max(1);
        if at + needed > pool.carriers.len() {
            overflow.push(name.clone());
            continue;
        }
        next.insert(carrier, at + needed);
        let pack = resolved.as_ref().map(|k| match k.namespace == home {
            true => k.pack().to_owned(),
            false => format!("{}:{}", k.namespace, k.pack()),
        });
        let entry = resolved.as_ref().map(|k| k.key().to_owned());
        let hidden = block.is_some_and(|b| b.centity.is_some());
        let use_of = |state: &State, facing| Use {
            name: name.clone(),
            state: state.clone(),
            pack: pack.clone(),
            entry: entry.clone(),
            hidden,
            facing,
        };
        if facings.is_empty() {
            uses.push(use_of(&pool.carriers[at], None));
        } else {
            for (i, facing) in facings.iter().enumerate() {
                uses.push(use_of(&pool.carriers[at + i], Some(*facing)));
            }
        }
    }
    // Nothing to hold any of them in (a game without the carriers' blocks): as no plan.
    let known: BTreeMap<BlockCarrier, Pool> = pools
        .into_iter()
        .filter_map(|(c, p)| Some((c, p?)))
        .collect();
    if known.is_empty() && !blocks.is_empty() {
        return None;
    }
    Some(Plan {
        pools: known,
        home: home.to_owned(),
        uses,
        overflow,
        unheld,
    })
}
