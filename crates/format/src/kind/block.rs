//! `blocks/<id>/block.json`: a block of the project's own, held in the world
//! as an unused state of one of the game's blocks (its carrier) and drawn by
//! the resource pack. Format's `BlockFile` and `BlockValidator`: its tick and
//! sound are its fields' rules. Whether its carrier has a state left, its
//! game sounds, and what its carrier means for its model and hardness are
//! checks across resources (with the carriers' plan, `BlockCarriers`).

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Map, name_of};
use crate::document::{Context, Document};
use crate::game;
use crate::problem::{ProblemSink, codes};
use crate::script::{self, ScriptDef};

pub const FILE_NAME: &str = "block.json";
pub const DEFAULT_HARDNESS: f64 = 1.5;
/// The hardness of a block nobody can break, as bedrock.
pub const UNBREAKABLE: f64 = -1.0;
/// The most ticks between `tick` events: an hour.
pub const MAX_TICK: i32 = 72_000;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BlockFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// `<pack>/<key>`: its look, an entry of a resource pack's `blocks`.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "block_model"))]
    pub model: Option<String>,
    /// What it's held as, which decides how it behaves physically. Default a note block.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub carrier: Option<BlockCarrier>,
    /// How long it takes to mine (stone 1.5): 0 breaks at once, -1 never. Default 1.5.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hardness: Option<f64>,
    /// The tool it's mined with. Default none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool: Option<BlockTool>,
    /// Whether it drops nothing unless mined with the right tool. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub requires_tool: Option<bool>,
    /// `<id>`: the loot table rolled when it's broken.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "loot_table"))]
    pub drops: Option<String>,
    /// The game's kind of block it sounds like (`stone`, `wood`): `block.<sound>.place` and `.break`.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = "^[a-z0-9_]+$", "x-problem" = "block.sound", "x-rule" = "the game's kind of block it sounds like, like \"stone\" or \"wood\""))]
    pub sound: Option<String>,
    /// Sounds of its own for when it's placed and broken, instead of `sound`'s.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sounds: Option<BlockSounds>,
    /// The project's own facts about it, any JSON, for scripts.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub properties: Option<Map<serde_json::Value>>,
    /// `<id>`: a centity drawn over the block, for a shape that isn't a cube.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "centity"))]
    pub centity: Option<String>,
    /// Whether it falls like sand. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub falls: Option<bool>,
    /// Which ways it can face. Default none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<BlockRotation>,
    /// Ticks between its `tick` events; it doesn't tick when left out.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 72000), extend("x-problem" = "block.tick"))]
    pub tick: Option<i32>,
    /// Its one script, with `this` the block.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptDef>,
}

/// The game's block whose states carry a project block: it decides collision, selection, culling and light.
#[derive(
    Clone,
    Copy,
    Debug,
    Default,
    PartialEq,
    Eq,
    Hash,
    PartialOrd,
    Ord,
    Serialize,
    Deserialize,
    JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum BlockCarrier {
    /// A solid, opaque full cube.
    #[default]
    NoteBlock,
    /// Nothing to collide with and nothing it hides: plants, flat decorations.
    Tripwire,
    /// A solid cube the neighbours' faces show through.
    Leaves,
}

/// Which ways a block can face; its model is drawn facing north.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum BlockRotation {
    #[default]
    None,
    /// North, east, south or west.
    Horizontal,
    /// Those, up or down too.
    All,
}

/// The way a block's front faces, and the pack's blockstate rotation (x, y) that turns its model there.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum BlockFacing {
    North,
    East,
    South,
    West,
    Up,
    Down,
}

impl BlockFacing {
    pub fn rotation(self) -> (i32, i32) {
        match self {
            BlockFacing::North => (0, 0),
            BlockFacing::East => (0, 90),
            BlockFacing::South => (0, 180),
            BlockFacing::West => (0, 270),
            BlockFacing::Up => (270, 0),
            BlockFacing::Down => (90, 0),
        }
    }
}

impl BlockRotation {
    pub fn facings(self) -> &'static [BlockFacing] {
        use BlockFacing::*;
        match self {
            BlockRotation::None => &[],
            BlockRotation::Horizontal => &[North, East, South, West],
            BlockRotation::All => &[North, East, South, West, Up, Down],
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BlockSounds {
    /// `<pack>/<key>`: played where it's placed.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "sound"))]
    pub place: Option<String>,
    /// `<pack>/<key>`: played where it's broken.
    #[serde(rename = "break", skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "sound"))]
    pub destroy: Option<String>,
}

/// A block's tool, and the game's tag of the blocks it mines fastest.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum BlockTool {
    Pickaxe,
    Axe,
    Shovel,
    Hoe,
}

impl BlockTool {
    pub fn tag(self) -> String {
        format!("{}:mineable/{}", game::NAMESPACE, name_of(&self))
    }
}

/// Whether a hardness is one: 0 or more, or -1 for a block nobody can break.
pub fn is_hardness(hardness: f64) -> bool {
    hardness.is_finite() && (hardness >= 0.0 || hardness == UNBREAKABLE)
}

impl BlockFile {
    pub fn carrier(&self) -> BlockCarrier {
        self.carrier.unwrap_or_default()
    }

    pub fn rotation(&self) -> BlockRotation {
        self.rotation.unwrap_or_default()
    }

    /// How many of its carrier's states it takes: one, or one per way it faces.
    pub fn states(&self) -> usize {
        self.rotation().facings().len().max(1)
    }

    /// The game's kind of block it sounds like: its `sound`, else what its tool mines mostly.
    pub fn sound(&self) -> &str {
        self.sound.as_deref().unwrap_or(match self.tool {
            Some(BlockTool::Axe) => "wood",
            Some(BlockTool::Shovel) => "gravel",
            Some(BlockTool::Hoe) => "grass",
            Some(BlockTool::Pickaxe) | None => "stone",
        })
    }

    pub fn place_sound(&self) -> String {
        format!("minecraft:block.{}.place", self.sound())
    }

    pub fn break_sound(&self) -> String {
        format!("minecraft:block.{}.break", self.sound())
    }

    pub fn hardness_or_default(&self) -> f64 {
        self.hardness.unwrap_or(DEFAULT_HARDNESS)
    }

    pub fn requires_tool_or_default(&self) -> bool {
        self.requires_tool.unwrap_or(false)
    }

    pub fn falls_or_default(&self) -> bool {
        self.falls.unwrap_or(false)
    }
}

impl Document for BlockFile {
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../../.netherforge/schema/block.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    const SCRIPTED: bool = true;

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if self.hardness.is_some_and(|h| !is_hardness(h)) {
            sink.report(
                codes::BLOCK_HARDNESS,
                "hardness is how long it takes to mine: 0 or more, or -1 for a block nobody can break",
                Some("$.hardness".into()),
            );
        }
        if self.requires_tool == Some(true) && self.tool.is_none() {
            sink.report(
                codes::BLOCK_REQUIRES_TOOL,
                "requiresTool means nothing without a tool: no tool is the right one, so nothing would ever drop",
                Some("$.requiresTool".into()),
            );
        }
        if self.falls == Some(true) {
            if self.carrier() == BlockCarrier::Tripwire {
                sink.report(
                    codes::BLOCK_FALLS,
                    "Tripwire breaks when what's under it goes, so a tripwire block can't fall",
                    Some("$.falls".into()),
                );
            } else if self.centity.is_some() {
                sink.report(
                    codes::BLOCK_FALLS,
                    "A falling block is drawn by the game alone, so a block a centity is drawn over can't fall",
                    Some("$.falls".into()),
                );
            }
        }
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
    }
}

impl BlockFile {
    /// Its game sounds, whether its carrier has a state left for it, and what its carrier means for it.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let game_data = cx.game();
        if self.sound.is_some()
            && let Some(game_data) = game_data
        {
            let missing: Vec<String> = [self.place_sound(), self.break_sound()]
                .into_iter()
                .filter(|s| game_data.lacks(game::Registry::SoundEvent, s))
                .collect();
            if !missing.is_empty() {
                sink.report(
                    codes::BLOCK_SOUND,
                    format!("The game has no {} sound", missing.join(" or ")),
                    Some("$.sound".into()),
                );
            }
        }
        let carrier = self.carrier();
        let noun = name_of(&carrier).replace('_', " ");
        let capacity = super::carriers::capacity(game_data, carrier);
        // The states the blocks before it take, in name order (a block that turns takes one per way it faces).
        let taken: usize = cx
            .models::<BlockFile>()
            .filter(|(id, b)| *id < cx.id() && b.carrier() == carrier)
            .map(|(_, b)| b.states())
            .sum();
        if capacity > 0 && taken + self.states() > capacity {
            let turns = match self.rotation() {
                BlockRotation::None => String::new(),
                rotation => format!(
                    " (it turns, which takes a state for each of its {} ways)",
                    rotation.facings().len()
                ),
            };
            sink.report(
                codes::BLOCK_CARRIERS,
                format!(
                    "The game has {capacity} {noun} states to hold blocks in, and the {noun} blocks up to this one take {}{turns}",
                    taken + self.states()
                ),
                Some("$".into()),
            );
        }
        if carrier == BlockCarrier::Tripwire {
            if self.hardness.is_some_and(|h| h != 0.0) {
                sink.report(
                    codes::BLOCK_CARRIER,
                    "Tripwire breaks at once whatever the block's hardness: a tripwire block can't take longer to mine",
                    Some("$.hardness".into()),
                );
            }
            let drawn = cx
                .vanilla()
                .is_some_and(|v| v.blockstates.get_str(super::carriers::TRIPWIRE).is_some());
            if !drawn {
                sink.report(
                    codes::BLOCK_VANILLA_LOOKS,
                    format!(
                        "Drawing tripwire blocks takes the game's own look of tripwire, which the editor writes into {} from your Minecraft: open the project in the editor with {} imported. Until then these blocks look like string",
                        crate::vanilla::FILE,
                        crate::vanilla::import_hint(cx.minecraft())
                    ),
                    Some("$.carrier".into()),
                );
            }
        }
        // A model that isn't a whole cube, on a note block, leaves see-through holes where the neighbours' faces are hidden.
        if carrier == BlockCarrier::NoteBlock && self.centity.is_none() {
            let look = self.model.as_deref().and_then(|m| {
                cx.references()
                    .resolve(crate::reference::RefKind::BlockModel, m)
                    .filter(|k| k.namespace == cx.home())
            });
            let shaped = look.is_some_and(|look| {
                cx.model::<super::resource_pack::ResourcePackFile>(look.pack())
                    .and_then(|pack| {
                        pack.blocks
                            .iter()
                            .find(|(k, _)| k.0 == look.key())
                            .map(|(_, b)| b)
                    })
                    .is_some_and(|block| block.model.is_some())
            });
            if shaped {
                sink.report(
                    codes::BLOCK_CARRIER,
                    "A note block is an opaque cube, so the blocks beside it don't draw the faces that touch it: unless this model fills the whole block, those faces show through. A shape that isn't a cube wants \"carrier\": \"leaves\" (solid) or \"tripwire\" (no collision)",
                    Some("$.model".into()),
                );
            }
        }
    }
}
