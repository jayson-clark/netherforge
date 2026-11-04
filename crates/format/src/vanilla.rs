//! `client/vanilla.json`: what the editor read from the game's client for the
//! project, which the server can't know (the game's own look of the blocks
//! custom blocks are held in, its HUD sprites, the textures and models packs
//! redraw). Format's `VanillaFile`.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Entries, Map, set};
use crate::document::Document;
use crate::problem::{Problem, ProblemSink, codes};

pub const FILE: &str = "client/vanilla.json";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VanillaFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// The Minecraft version it was read from.
    pub minecraft: String,
    /// Each block's blockstate file as the client has it, by block id (`minecraft:tripwire`).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub blockstates: Map<serde_json::Value>,
    /// The game's HUD sprites, by their path under `textures/gui/sprites/` without `.png`.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub sprites: Vec<String>,
    /// The game's textures by their path under `textures/` without `.png`, to their `[width, height]`.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub textures: Map<Vec<i32>>,
    /// Names the project's packs redraw that the game has no texture by.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub unknown_textures: Vec<String>,
    /// The game's models the project's packs redraw, by their path under `models/` without `.json`.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub models: Vec<String>,
    /// Names the project's packs redraw that the game has no model by.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub unknown_models: Vec<String>,
}

impl Document for VanillaFile {
    const SCHEMA: &'static str = "../.netherforge/schema/vanilla.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn canonical(&self) -> Self {
        VanillaFile {
            sprites: set(&self.sprites),
            unknown_textures: set(&self.unknown_textures),
            models: set(&self.models),
            unknown_models: set(&self.unknown_models),
            ..self.clone()
        }
    }
}

/// The file from its `text`, checked against the target `minecraft`: read
/// from another version's client, it's stale.
pub fn load(text: &str, minecraft: Option<&str>) -> (Option<VanillaFile>, Vec<Problem>) {
    let read = crate::document::load::<VanillaFile>(text, FILE, &Default::default());
    let mut problems = read.problems;
    if let Some(file) = &read.value
        && let Some(minecraft) = minecraft
        && file.minecraft != minecraft
    {
        let mut sink = ProblemSink::new(FILE);
        sink.report(
            codes::BLOCK_VANILLA_LOOKS,
            format!(
                "This is the game's look of Minecraft {}'s blocks, and the project is for {minecraft}: the editor writes it again once Minecraft {minecraft} is imported",
                file.minecraft
            ),
            Some("$.minecraft".into()),
        );
        problems.extend(sink.problems);
    }
    (read.value, problems)
}

/// The words a check asks for the file with: open the project in the editor with its Minecraft imported.
pub fn import_hint(minecraft: Option<&str>) -> String {
    match minecraft {
        Some(m) => format!("Minecraft {m}"),
        None => "the project's Minecraft".into(),
    }
}
