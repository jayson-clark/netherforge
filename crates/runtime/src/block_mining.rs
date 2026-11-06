//! How the project's blocks, and the game's blocks with rules that change their mining, are mined (Kotlin's
//! `BlockMining`): by the server, not the player's game. A player hitting one has their `block_break_speed` turned
//! down so far (`SLOWED`) that their game never finishes (it would take the block away itself, and the note blocks
//! around it), and `mine` counts their progress each tick from what the server says the carrier would take with
//! what they hold, times how much faster the block is than its carrier, shows the cracks, and breaks it at the end
//! with the server's own break. `CustomBlocks` owns the blocks; this holds who's mining what.

use std::collections::{HashMap, HashSet};

use netherforge_format::game::Registry;
use netherforge_format::item::AttributeOperation;
use netherforge_format::kind::block::{BlockFile, BlockTool, UNBREAKABLE};
use netherforge_format::kind::game_block::GameBlockFile;
use netherforge_platform::{
    AttributeModifierData, ItemData, Platform, PlayerRef, Uuid, game_event,
};

/// What a mining target of one of the game's blocks starts with: no name of the project's block can.
const GAME_TARGET: &str = "#";
const CREATIVE: &str = "creative";
const SPECTATOR: &str = "spectator";
/// The attribute the game scales how fast a player mines by.
const BREAK_SPEED: &str = "minecraft:block_break_speed";
/// What the game divides mining progress by: 30 with the tool a block needs, 100 without.
const HARVEST: f64 = 30.0;
const NO_HARVEST: f64 = 100.0;
/// The range the factor takes; a block mined a thousand times quicker than a note block is instant anyway.
const MIN_FACTOR: f64 = 0.0001;
const MAX_FACTOR: f64 = 1000.0;
/// What a slowed player's break speed is multiplied by: their game would take hours to finish even a note block
/// with the fastest axe there is, and the server's count divides it out again.
const SLOWED: f64 = 1e-6;
/// The cracks' stages a block's progress is shown in: the game's ten.
const CRACKS: f64 = 10.0;

type At = (i32, i32, i32);

/// A player mining one of the project's blocks: `factor` is how much faster than its carrier the block is to mine
/// with what they hold, `stage` the cracks shown.
#[derive(Clone)]
struct Mining {
    world: String,
    at: At,
    target: String,
    factor: f64,
    progress: f64,
    stage: i32,
}

/// A mining `mine` finished: the block to break as the player's.
pub struct Finished {
    pub player: Uuid,
    pub world: String,
    pub at: At,
    /// One of the game's blocks' state as it was; none for the project's.
    state: Option<String>,
    target: String,
    factor: f64,
}

/// What mining asks of the blocks: what's placed where, and the files.
pub trait Mined {
    /// Whether the project's blocks are in play now.
    fn usable(&self) -> bool;
    /// The name of the project's block at a position.
    fn placed(&self, world: &str, x: i32, y: i32, z: i32) -> Option<String>;
    fn block_file(&self, name: &str) -> Option<BlockFile>;
    /// The project's rules for one of the game's block states, when it has some.
    fn game_rule(&self, state: &str) -> Option<GameBlockFile>;
}

/// Who's mining what, and whose speed is turned down.
#[derive(Default)]
pub struct BlockMining {
    mining: HashMap<Uuid, Mining>,
    slowed: HashSet<Uuid>,
    tool_states: HashMap<BlockTool, Option<String>>,
    /// The project's namespace: the modifier's id is in it.
    pub home: String,
}

impl BlockMining {
    fn modifier_id(&self) -> String {
        format!("{}:mining", self.home)
    }

    fn slow(&mut self, platform: &dyn Platform, player: Uuid) {
        if self.slowed.contains(&player) {
            return;
        }
        let modifier = AttributeModifierData {
            id: self.modifier_id(),
            amount: SLOWED - 1.0,
            operation: AttributeOperation::AddMultipliedTotal,
            saved: true,
        };
        if platform
            .attributes()
            .add_modifier(player, BREAK_SPEED.into(), modifier)
        {
            self.slowed.insert(player);
        }
    }

    /// `player`'s speed is theirs again.
    pub fn unslow(&mut self, platform: &dyn Platform, player: Uuid) {
        if self.slowed.remove(&player) {
            platform
                .attributes()
                .remove_modifier(player, BREAK_SPEED.into(), self.modifier_id());
        }
    }

    /// Takes the modifier off whether or not this session put it there: the server saves it with the player.
    pub fn clear_slowed(&mut self, platform: &dyn Platform, player: Uuid) {
        self.slowed.remove(&player);
        platform
            .attributes()
            .remove_modifier(player, BREAK_SPEED.into(), self.modifier_id());
    }

    /// The player isn't mining one of the project's blocks any more: its cracks go.
    pub fn stop_mining(&mut self, platform: &dyn Platform, player: Uuid) {
        let Some(was) = self.mining.remove(&player) else {
            return;
        };
        if was.stage >= 0 {
            platform
                .blocks()
                .show_breaking(player, was.world, was.at.0, was.at.1, was.at.2, -1);
        }
    }

    /// A player is hitting the block at `start`'s position: `start` says whether it breaks at once, and may be
    /// refused (a block nobody can break by hand). True refuses it.
    pub fn started_breaking(
        &mut self,
        platform: &dyn Platform,
        blocks: &dyn Mined,
        start: &mut game_event::BlockStartBreak,
    ) -> bool {
        let player = start.player.uuid;
        let b = &start.block;
        let name = blocks
            .usable()
            .then(|| blocks.placed(&b.world, b.x, b.y, b.z))
            .flatten();
        let file = name.as_deref().and_then(|n| blocks.block_file(n));
        // A start, even on the same block, begins again from nothing (as the game's does when the tool changes).
        self.stop_mining(platform, player);
        let (Some(name), Some(file)) = (name, file) else {
            return self.started_breaking_game(platform, blocks, start);
        };
        let creative = platform.players().game_mode(player).as_deref() == Some(CREATIVE);
        let hardness = file.hardness_or_default();
        if hardness == UNBREAKABLE && !creative {
            return true;
        }
        // The server breaks it on this hit: the player's game, slowed, doesn't.
        if hardness == 0.0 {
            start.instant = true;
        }
        if creative || hardness == 0.0 {
            return false;
        }
        // A carrier that breaks at once (tripwire) can't be mined slower: the player's game does it, as the server's.
        let Some(factor) =
            self.mining_factor(platform, &file, &start.block.state, start.item.as_ref())
        else {
            self.unslow(platform, player);
            return false;
        };
        self.slow(platform, player);
        self.mining.insert(
            player,
            Mining {
                world: start.block.world.clone(),
                at: (start.block.x, start.block.y, start.block.z),
                target: name,
                factor,
                progress: 0.0,
                stage: -1,
            },
        );
        false
    }

    /// `started_breaking` for one of the game's blocks: one whose rule changes how it's mined is mined by the server,
    /// as the project's are, with the game's own numbers for whatever the rule leaves out.
    fn started_breaking_game(
        &mut self,
        platform: &dyn Platform,
        blocks: &dyn Mined,
        start: &mut game_event::BlockStartBreak,
    ) -> bool {
        let player = start.player.uuid;
        let Some(rule) = blocks
            .game_rule(&start.block.state)
            .filter(GameBlockFile::mined_by_server)
        else {
            self.unslow(platform, player);
            return false;
        };
        let creative = platform.players().game_mode(player).as_deref() == Some(CREATIVE);
        let hardness = rule.hardness;
        if hardness == Some(UNBREAKABLE) && !creative {
            return true;
        }
        if hardness == Some(0.0) {
            start.instant = true;
        }
        if creative || hardness == Some(0.0) {
            return false;
        }
        let Some(factor) =
            self.game_factor(platform, &rule, &start.block.state, start.item.as_ref())
        else {
            self.unslow(platform, player);
            return false;
        };
        self.slow(platform, player);
        let id = start.block.state.split('[').next().unwrap_or_default();
        self.mining.insert(
            player,
            Mining {
                world: start.block.world.clone(),
                at: (start.block.x, start.block.y, start.block.z),
                target: format!("{GAME_TARGET}{id}"),
                factor,
                progress: 0.0,
                stage: -1,
            },
        );
        false
    }

    /// A player let go of the block they were mining, or looked away.
    pub fn stopped_breaking(
        &mut self,
        platform: &dyn Platform,
        player: &PlayerRef,
        world: &str,
        at: At,
    ) {
        let same = self
            .mining
            .get(&player.uuid)
            .is_some_and(|m| m.world == world && m.at == at);
        if same {
            self.stop_mining(platform, player.uuid);
        }
    }

    /// What's mined at a position: the project's block's name, or for one of the game's blocks with rules (`state`
    /// what's there now) `GAME_TARGET` and its id.
    fn target_at(blocks: &dyn Mined, world: &str, at: At, state: Option<&str>) -> Option<String> {
        if let Some(name) = blocks.placed(world, at.0, at.1, at.2) {
            return Some(name);
        }
        let state = state?;
        blocks
            .game_rule(state)
            .filter(GameBlockFile::mined_by_server)
            .map(|_| {
                format!(
                    "{GAME_TARGET}{}",
                    state.split('[').next().unwrap_or_default()
                )
            })
    }

    /// A tick of everyone mining one of the project's blocks. At 1 the block is done: what's handed back is for
    /// the caller to break as theirs with the server's own break (which raises the server's event, so it's done
    /// once nothing is borrowed), then to hand to `after_break`.
    pub fn mine(&mut self, platform: &dyn Platform, blocks: &dyn Mined) -> Vec<Finished> {
        let ops = platform.blocks();
        let mut finished = Vec::new();
        let players: Vec<Uuid> = self.mining.keys().copied().collect();
        for player in players {
            let Some(mut mined) = self.mining.get(&player).cloned() else {
                continue;
            };
            let (w, at) = (mined.world.clone(), mined.at);
            let state = mined
                .target
                .starts_with(GAME_TARGET)
                .then(|| ops.get(w.clone(), at.0, at.1, at.2).map(|s| s.state))
                .flatten();
            let now = Self::target_at(blocks, &w, at, state.as_deref());
            let mode = platform.players().game_mode(player);
            let step = ops.break_progress(player, w.clone(), at.0, at.1, at.2);
            let gone = now.as_deref() != Some(mined.target.as_str())
                || matches!(mode.as_deref(), None | Some(CREATIVE) | Some(SPECTATOR));
            let (false, Some(step)) = (gone, step) else {
                self.stop_mining(platform, player);
                continue;
            };
            let slowed = if self.slowed.contains(&player) {
                SLOWED
            } else {
                1.0
            };
            mined.progress += step / slowed * mined.factor;
            if mined.progress < 1.0 {
                let stage = (mined.progress * CRACKS) as i32 - 1;
                if stage != mined.stage {
                    mined.stage = stage;
                    ops.show_breaking(player, w.clone(), at.0, at.1, at.2, stage);
                }
                self.mining.insert(player, mined);
                continue;
            }
            self.mining.insert(player, mined.clone());
            self.stop_mining(platform, player);
            finished.push(Finished {
                player,
                world: w,
                at,
                state,
                target: mined.target,
                factor: mined.factor,
            });
        }
        finished
    }

    /// After the server's break of a block `mine` finished: one of the game's blocks shows broken to everyone but
    /// the player, whose game never broke it; a break refused (a protection plugin) starts over while they're still
    /// on it, as the game does.
    pub fn after_break(
        &mut self,
        platform: &dyn Platform,
        blocks: &dyn Mined,
        done: Finished,
        broke: bool,
    ) {
        let ops = platform.blocks();
        let (w, at) = (done.world, done.at);
        if broke && let Some(state) = &done.state {
            ops.show_broken(
                Some(done.player),
                w.clone(),
                at.0,
                at.1,
                at.2,
                state.clone(),
            );
        }
        let after = done
            .state
            .is_some()
            .then(|| ops.get(w.clone(), at.0, at.1, at.2).map(|s| s.state))
            .flatten();
        if Self::target_at(blocks, &w, at, after.as_deref()).as_deref()
            == Some(done.target.as_str())
        {
            self.mining.insert(
                done.player,
                Mining {
                    world: w,
                    at,
                    target: done.target,
                    factor: done.factor,
                    progress: 0.0,
                    stage: -1,
                },
            );
        }
    }

    /// Slows everyone looking at one of the project's blocks before they hit it: the change takes a moment to reach
    /// their game, which could finish a soft block on the first hit meanwhile.
    pub fn watch_looks(&mut self, platform: &dyn Platform, blocks: &dyn Mined) {
        for player in platform.players().online() {
            let id = player.uuid;
            let mode = platform.players().game_mode(id);
            if matches!(mode.as_deref(), Some(CREATIVE) | Some(SPECTATOR)) {
                self.stop_mining(platform, id);
                self.unslow(platform, id);
                continue;
            }
            if self.mining.contains_key(&id) {
                continue;
            }
            let target = platform.blocks().targeted(id);
            let mined = target.is_some_and(|t| {
                let ours = (blocks.usable() && blocks.placed(&t.world, t.x, t.y, t.z).is_some())
                    || blocks
                        .game_rule(&t.state)
                        .is_some_and(|r| r.mined_by_server());
                ours && platform.blocks().hardness(t.state.clone()).unwrap_or(0.0) > 0.0
            });
            if mined {
                self.slow(platform, id);
            } else {
                self.unslow(platform, id);
            }
        }
    }

    /// How much faster than the carrier this block is to mine with `tool`, as the game counts it: the progress a
    /// tick of mining makes is the tool's speed over the block's hardness (and 30, or 100 without the tool a block
    /// needs), so what has to change is the ratio of the two blocks'. None when it can't be told.
    fn mining_factor(
        &mut self,
        platform: &dyn Platform,
        file: &BlockFile,
        carrier: &str,
        tool: Option<&ItemData>,
    ) -> Option<f64> {
        let ops = platform.blocks();
        let carrier_hardness = ops.hardness(carrier.to_owned()).filter(|h| *h > 0.0)?;
        let carrier_speed = ops.break_speed(tool.cloned(), carrier.to_owned())?;
        let representative = file.tool.and_then(|t| self.tool_state(platform, t));
        let speed = match representative {
            Some(state) => ops.break_speed(tool.cloned(), state).unwrap_or(1.0),
            None => 1.0,
        };
        let hardness = Some(file.hardness_or_default()).filter(|h| *h > 0.0)?;
        let harvests =
            !file.requires_tool_or_default() || self.right_tool(platform, file.tool, tool);
        let vanilla = carrier_speed / carrier_hardness / HARVEST;
        let custom = speed / hardness / if harvests { HARVEST } else { NO_HARVEST };
        Some((custom / vanilla).clamp(MIN_FACTOR, MAX_FACTOR))
    }

    /// How much faster than the game's own numbers the game's block `state` is to mine with `tool` under `file`'s
    /// rules. None when it can't be told, or the game breaks the block at once or never.
    fn game_factor(
        &mut self,
        platform: &dyn Platform,
        file: &GameBlockFile,
        state: &str,
        tool: Option<&ItemData>,
    ) -> Option<f64> {
        let ops = platform.blocks();
        let game_hardness = ops.hardness(state.to_owned()).filter(|h| *h > 0.0)?;
        let game_speed = ops.break_speed(tool.cloned(), state.to_owned())?;
        let game_harvests = ops
            .harvests(tool.cloned(), state.to_owned())
            .unwrap_or(true);
        let speed = match file.tool {
            Some(t) => self
                .tool_state(platform, t)
                .and_then(|s| ops.break_speed(tool.cloned(), s))
                .unwrap_or(1.0),
            None => game_speed,
        };
        let hardness = Some(file.hardness.unwrap_or(game_hardness)).filter(|h| *h > 0.0)?;
        let harvests = self
            .game_harvests(platform, file, state, tool)
            .unwrap_or(game_harvests);
        let vanilla = game_speed / game_hardness / if game_harvests { HARVEST } else { NO_HARVEST };
        let custom = speed / hardness / if harvests { HARVEST } else { NO_HARVEST };
        Some((custom / vanilla).clamp(MIN_FACTOR, MAX_FACTOR))
    }

    /// Whether `tool` is of the `kind` a block needs for its drops: one the game speeds up on the blocks of that tool
    /// (a pickaxe on stone), which is how the game itself says what a tool is for.
    pub fn right_tool(
        &mut self,
        platform: &dyn Platform,
        kind: Option<BlockTool>,
        tool: Option<&ItemData>,
    ) -> bool {
        let Some(state) = kind.and_then(|k| self.tool_state(platform, k)) else {
            return false;
        };
        platform
            .blocks()
            .break_speed(tool.cloned(), state)
            .unwrap_or(1.0)
            > 1.0
    }

    /// A block the game says `tool` mines (the first of its tag that takes time to mine), as the state the server is
    /// asked about; none when the game has none.
    fn tool_state(&mut self, platform: &dyn Platform, tool: BlockTool) -> Option<String> {
        if let Some(known) = self.tool_states.get(&tool) {
            return known.clone();
        }
        let game = platform.game();
        let mut ids: Vec<String> = game
            .tag(Registry::Block.key(), &tool.tag())
            .map(|t| t.iter().cloned().collect())
            .unwrap_or_default();
        ids.sort();
        let found = ids.into_iter().find_map(|id| {
            let state = crate::block::canonical_state(game, &id, "tool").ok()?;
            (platform.blocks().hardness(state.clone()).unwrap_or(0.0) > 0.0).then_some(state)
        });
        self.tool_states.insert(tool, found.clone());
        found
    }

    /// Whether `tool` gets the drops of `state`, a game block with `file`'s rules: any tool without `requiresTool`
    /// true or false to say otherwise, the game's own rule; with it, the rule's `tool`, or one the game mines the
    /// block faster with. None when the server can't say.
    pub fn game_harvests(
        &mut self,
        platform: &dyn Platform,
        file: &GameBlockFile,
        state: &str,
        tool: Option<&ItemData>,
    ) -> Option<bool> {
        match file.requires_tool {
            Some(false) => Some(true),
            Some(true) => match file.tool {
                Some(kind) => Some(self.right_tool(platform, Some(kind), tool)),
                None => platform
                    .blocks()
                    .break_speed(tool.cloned(), state.to_owned())
                    .map(|s| s > 1.0),
            },
            None => platform.blocks().harvests(tool.cloned(), state.to_owned()),
        }
    }

    /// Nobody mines one of the project's blocks, or needs slowing, any more.
    pub fn stop_all(&mut self, platform: &dyn Platform) {
        let players: Vec<Uuid> = self.mining.keys().copied().collect();
        for player in players {
            self.stop_mining(platform, player);
        }
        let slowed: Vec<Uuid> = self.slowed.iter().copied().collect();
        for player in slowed {
            self.unslow(platform, player);
        }
    }

    /// Whoever mines one of the game's blocks stops: the rules it was mined by changed.
    pub fn stop_game_targets(&mut self, platform: &dyn Platform) {
        let players: Vec<Uuid> = self
            .mining
            .iter()
            .filter(|(_, m)| m.target.starts_with(GAME_TARGET))
            .map(|(p, _)| *p)
            .collect();
        for player in players {
            self.stop_mining(platform, player);
        }
    }

    /// `player` left: what the server kept of their mining goes with them.
    pub fn forget(&mut self, player: Uuid) {
        self.mining.remove(&player);
        self.slowed.remove(&player);
    }
}
