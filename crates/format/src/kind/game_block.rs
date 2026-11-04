//! `game_blocks/<id>.json`: the project's mining rules and drops for some of
//! the game's own blocks. Format's `GameBlockFile`, `GameBlocks` and
//! `GameBlockValidator`. Whether another file (or a package's) names one of
//! its blocks, or the project's blocks are held in one, is a check across
//! resources.

use std::collections::{BTreeSet, HashMap};

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

pub use super::block::BlockTool;
use super::block::is_hardness;
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::problem::{ProblemSink, codes};

/// What a tag is written with in `blocks`.
pub const TAG: &str = "#";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GameBlockFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// The game's blocks these rules are for: ids (`minecraft:deepslate_bricks`) and block tags (`#minecraft:logs`).
    pub blocks: Vec<String>,
    /// How long it takes to mine, as a project block's: 0 breaks at once, -1 never. Default the game's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hardness: Option<f64>,
    /// The tool it's mined with. Default the game's tools for it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool: Option<BlockTool>,
    /// Whether it drops nothing unless mined with the right tool. Default the game's rule.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub requires_tool: Option<bool>,
    /// `<id>`: the loot table rolled when it's broken, instead of the game's.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "loot_table"))]
    pub drops: Option<String>,
}

impl GameBlockFile {
    /// Whether mining it takes something the game wouldn't, so the server counts it.
    pub fn mined_by_server(&self) -> bool {
        self.hardness.is_some() || self.tool.is_some() || self.requires_tool.is_some()
    }
}

/// The block ids `entry` (an id or a `#tag`) names, namespaced: none for one
/// that isn't an id, or a tag the game doesn't have (or isn't known).
pub fn expand(entry: &str, game: Option<&dyn GameData>) -> BTreeSet<String> {
    let (tag, id) = match entry.strip_prefix(TAG) {
        Some(id) => (true, id),
        None => (false, entry),
    };
    if !game::is_id(id) {
        return BTreeSet::new();
    }
    let normal = game::normalize(id);
    if tag {
        game.and_then(|g| g.tag_of(Registry::Block, &normal))
            .into_iter()
            .flatten()
            .cloned()
            .collect()
    } else {
        BTreeSet::from([normal])
    }
}

impl Document for GameBlockFile {
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../.netherforge/schema/game_block.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let game = cx.game;
        if self.blocks.is_empty() {
            sink.report(
                codes::GAME_BLOCK_BLOCKS,
                "blocks lists none of the game's blocks, so these rules are for nothing",
                Some("$.blocks".into()),
            );
        }
        let mut seen = HashMap::new();
        for (index, entry) in self.blocks.iter().enumerate() {
            let at = format!("$.blocks[{index}]");
            let tag = entry.starts_with(TAG);
            let id = entry.strip_prefix(TAG).unwrap_or(entry);
            if !game::is_id(id) {
                sink.report(
                    codes::GAME_BLOCK_BLOCKS,
                    format!("\"{entry}\" isn't one of the game's blocks: an id like \"minecraft:stone\", or a tag like \"#minecraft:logs\""),
                    Some(at),
                );
                continue;
            }
            let normal = game::normalize(id);
            if let Some(game) = game {
                if tag && game.tag_of(Registry::Block, &normal).is_none() {
                    sink.report(
                        codes::GAME_BLOCK_UNKNOWN,
                        format!(
                            "Minecraft {} has no block tag \"#{normal}\"",
                            game.minecraft_version()
                        ),
                        Some(at),
                    );
                    continue;
                } else if !tag && game.lacks(Registry::Block, &normal) {
                    sink.report(
                        codes::GAME_BLOCK_UNKNOWN,
                        format!(
                            "Minecraft {} has no block \"{normal}\"",
                            game.minecraft_version()
                        ),
                        Some(at),
                    );
                    continue;
                }
            }
            // Each block once: a tag counts every block in it, once the game says what they are.
            for block in expand(entry, game) {
                let first = *seen.entry(block.clone()).or_insert(index);
                if first != index {
                    sink.report(
                        codes::GAME_BLOCK_TWICE,
                        format!("{block} is in blocks already, as \"{}\": each block has one set of rules", self.blocks[first]),
                        Some(at),
                    );
                    break;
                }
            }
        }
        if let Some(hardness) = self.hardness
            && !is_hardness(hardness)
        {
            sink.report(
                codes::GAME_BLOCK_HARDNESS,
                "hardness is how long it takes to mine: 0 or more, or -1 for a block nobody can break",
                Some("$.hardness".into()),
            );
        }
        if self.requires_tool == Some(false) && self.drops.is_none() {
            sink.report(
                codes::GAME_BLOCK_REQUIRES_TOOL,
                "requiresTool: false can't make the game's own drops fall for the wrong tool: the game keeps its rule for \
                 its loot. Name drops of the project's to drop them for any tool",
                Some("$.requiresTool".into()),
            );
        }
    }
}

impl GameBlockFile {
    /// Each block has one set of rules, and none is one the project's own blocks are held in.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let game = cx.game();
        let mut earlier: std::collections::BTreeMap<String, &str> = Default::default();
        for (id, file) in cx.models::<GameBlockFile>() {
            if id >= cx.id() {
                continue;
            }
            for entry in &file.blocks {
                for block in expand(entry, game) {
                    earlier.entry(block).or_insert(id);
                }
            }
        }
        let mut carriers: Vec<super::block::BlockCarrier> = Vec::new();
        for (_, block) in cx.models::<super::block::BlockFile>() {
            if !carriers.contains(&block.carrier()) {
                carriers.push(block.carrier());
            }
        }
        let held: BTreeSet<String> = carriers
            .into_iter()
            .flat_map(|c| super::carriers::blocks_of(game, c))
            .collect();
        for (index, entry) in self.blocks.iter().enumerate() {
            let at = format!("$.blocks[{index}]");
            let blocks = expand(entry, game);
            if let Some((block, other)) = blocks.iter().find_map(|b| Some((b, *earlier.get(b)?))) {
                sink.problems.push(
                    codes::GAME_BLOCK_TWICE
                        .at(
                            &sink.file,
                            format!("{block} already has the rules of game block \"{other}\": each block has one set of rules"),
                            Some(at.clone()),
                        )
                        .with_related(vec![crate::Location {
                            file: cx.path_of("game_block", other),
                            path: Some("$.blocks".into()),
                        }]),
                );
            }
            if let Some(block) = blocks.iter().find(|b| held.contains(*b)) {
                sink.report(
                    codes::GAME_BLOCK_CARRIER,
                    format!("The project's own blocks are held in {block}, so the server takes its states for the project's: it can't mine it as one of the game's"),
                    Some(at),
                );
            }
        }
    }
}
