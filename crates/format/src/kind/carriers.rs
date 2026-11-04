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
