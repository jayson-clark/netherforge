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

/// The game's blocks `files` give rules to, by block id, each to the file (by id, the first in id order) whose
/// rules it takes (Kotlin's `GameBlocks.resolve`). A tag is resolved by `game`; an entry that isn't an id is left out.
pub fn resolve(
    files: &std::collections::BTreeMap<String, GameBlockFile>,
    game: Option<&dyn GameData>,
) -> std::collections::BTreeMap<String, String> {
    let mut out = std::collections::BTreeMap::new();
    for (id, file) in files {
        for entry in &file.blocks {
            for block in expand(entry, game) {
                out.entry(block).or_insert_with(|| id.clone());
            }
        }
    }
    out
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
        let held = carriers_of(cx.models::<super::block::BlockFile>().map(|(_, b)| b), game);
        let packaged = rules_of(
            cx.packages().iter().flat_map(|(namespace, package)| {
                package.project.resources.iter().filter_map(move |r| {
                    Some((
                        crate::kinds::package_path(namespace, &r.found.id),
                        r.model::<GameBlockFile>()?,
                    ))
                })
            }),
            game,
        );
        let package_carriers: Vec<(&str, BTreeSet<String>)> = cx
            .packages()
            .iter()
            .map(|(namespace, package)| {
                let blocks = package
                    .project
                    .resources
                    .iter()
                    .filter_map(|r| r.model::<super::block::BlockFile>());
                (namespace.as_str(), carriers_of(blocks, game))
            })
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
            } else if let Some((block, other)) =
                blocks.iter().find_map(|b| Some((b, packaged.get(b)?)))
            {
                let (namespace, id) = crate::kinds::split_package(other);
                sink.problems.push(
                    codes::GAME_BLOCK_TWICE
                        .at(
                            &sink.file,
                            format!(
                                "{block} already has the rules of package \"{}\"'s game block \"{id}\": each block has one set of rules on a server, its packages' included",
                                namespace.unwrap_or_default()
                            ),
                            Some(at.clone()),
                        )
                        .with_related(vec![crate::Location {
                            file: cx.place_of_everywhere("game_block", other),
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
            } else if let Some((namespace, block)) = package_carriers
                .iter()
                .find_map(|(n, used)| Some((n, blocks.iter().find(|b| used.contains(*b))?)))
            {
                sink.report(
                    codes::GAME_BLOCK_CARRIER,
                    format!("Package \"{namespace}\"'s blocks are held in {block}, so the server takes its states for the package's: it can't mine it as one of the game's"),
                    Some(at),
                );
            }
        }
    }
}

/// The game's blocks `blocks` are held in: every block of each carrier they use.
fn carriers_of<'b>(
    blocks: impl Iterator<Item = &'b super::block::BlockFile>,
    game: Option<&dyn GameData>,
) -> BTreeSet<String> {
    let mut carriers = Vec::new();
    for block in blocks {
        if !carriers.contains(&block.carrier()) {
            carriers.push(block.carrier());
        }
    }
    carriers
        .into_iter()
        .flat_map(|c| super::carriers::blocks_of(game, c))
        .collect()
}

/// Every block the files (by name) have rules for, with the first file (by name) naming it (`GameBlocks.resolve`).
fn rules_of<'b>(
    files: impl Iterator<Item = (String, &'b GameBlockFile)>,
    game: Option<&dyn GameData>,
) -> std::collections::BTreeMap<String, String> {
    let mut files: Vec<_> = files.collect();
    files.sort_by(|a, b| a.0.cmp(&b.0));
    let mut out = std::collections::BTreeMap::new();
    for (name, file) in files {
        for entry in &file.blocks {
            for block in expand(entry, game) {
                out.entry(block).or_insert_with(|| name.clone());
            }
        }
    }
    out
}

/// The packages' game blocks against each other's and the project's blocks: a
/// block has one set of rules on a server, and none held a custom block.
pub fn across_packages(project: &crate::project::Project, sink: &mut ProblemSink) {
    if project.packages.is_empty() {
        return;
    }
    let game = project.game();
    let files: Vec<(String, &GameBlockFile)> = project
        .packages
        .iter()
        .flat_map(|(namespace, package)| {
            package.project.resources.iter().filter_map(move |r| {
                Some((
                    crate::kinds::package_path(namespace, &r.found.id),
                    r.model::<GameBlockFile>()?,
                ))
            })
        })
        .collect();
    let mut carriers: Vec<(Option<&str>, BTreeSet<String>)> = vec![(
        None,
        carriers_of(
            project
                .resources
                .iter()
                .filter_map(|r| r.model::<super::block::BlockFile>()),
            game,
        ),
    )];
    for (namespace, package) in &project.packages {
        let blocks = package
            .project
            .resources
            .iter()
            .filter_map(|r| r.model::<super::block::BlockFile>());
        carriers.push((Some(namespace), carriers_of(blocks, game)));
    }
    let owner = rules_of(files.iter().map(|(n, f)| (n.clone(), *f)), game);
    let at = Some("$.dependencies".to_owned());
    for (key, file) in &files {
        let (namespace, id) = crate::kinds::split_package(key);
        let path = project.place_of("game_block", key);
        let named: BTreeSet<String> = file.blocks.iter().flat_map(|e| expand(e, game)).collect();
        if let Some(block) = named.iter().find(|b| {
            owner
                .get(*b)
                .is_some_and(|o| o != key && crate::kinds::split_package(o).0 != namespace)
        }) {
            let other = &owner[block];
            let (other_namespace, other_id) = crate::kinds::split_package(other);
            sink.problems.push(
                codes::GAME_BLOCK_TWICE
                    .at(
                        &sink.file,
                        format!(
                            "Packages \"{}\" (game block \"{other_id}\") and \"{}\" (game block \"{id}\") both have rules for {block}, and a block has one set of rules on a server",
                            other_namespace.unwrap_or_default(),
                            namespace.unwrap_or_default()
                        ),
                        at.clone(),
                    )
                    .with_related(vec![
                        crate::Location { file: project.place_of("game_block", other), path: Some("$.blocks".into()) },
                        crate::Location { file: path.clone(), path: Some("$.blocks".into()) },
                    ]),
            );
        }
        for (whose, used) in &carriers {
            if *whose == namespace {
                continue;
            }
            let Some(block) = named.iter().find(|b| used.contains(*b)) else {
                continue;
            };
            let blocks_of = match whose {
                Some(w) => format!("package \"{w}\"'s blocks are"),
                None => "the project's own blocks are".into(),
            };
            sink.problems.push(
                codes::GAME_BLOCK_CARRIER
                    .at(
                        &sink.file,
                        format!(
                            "Package \"{}\"'s game block \"{id}\" has rules for {block}, but {blocks_of} held in it, so the server takes its states for those blocks: it can't mine it as one of the game's",
                            namespace.unwrap_or_default()
                        ),
                        at.clone(),
                    )
                    .with_related(vec![crate::Location { file: path.clone(), path: Some("$.blocks".into()) }]),
            );
            break;
        }
    }
}
