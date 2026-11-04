//! `netherforge.json`: what a project is. Format's `ProjectManifest` and
//! `ManifestKind` (its canonical form).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::canonical::{Map, set};

pub const FILE_NAME: &str = "netherforge.json";
pub const SCHEMA: &str = ".netherforge/schema/netherforge.schema.json";
/// The format version this reads and writes.
pub const FORMAT_VERSION: i64 = 1;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Manifest {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    pub format_version: i64,
    pub name: String,
    pub namespace: String,
    pub version: String,
    pub minecraft: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub managed_worlds: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub worlds: Option<Map<WorldConfig>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub requires: Option<Requires>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allow: Option<Allow>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dependencies: Option<Map<Dependency>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exports: Option<Map<Vec<String>>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub settings: Option<Map<SettingDef>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub generate: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub listing: Option<Listing>,
}

impl Manifest {
    /// Its canonical form (`ManifestKind.canonical`): the schema set; managed
    /// worlds, requirements, permissions and exports as sorted sets; only
    /// worlds that say something.
    pub fn canonical(&self) -> Manifest {
        let mut out = self.clone();
        out.schema = Some(SCHEMA.into());
        out.managed_worlds = out.managed_worlds.as_deref().map(set);
        out.worlds = out
            .worlds
            .map(|worlds| {
                worlds
                    .into_iter()
                    .filter(|(_, w)| !w.is_empty())
                    .collect::<Map<_>>()
            })
            .filter(|w| !w.is_empty());
        if let Some(requires) = &mut out.requires {
            requires.http = requires.http.as_deref().map(set);
            requires.plugins = requires.plugins.as_deref().map(set);
        }
        if let Some(allow) = &mut out.allow {
            allow.permissions = allow.permissions.as_deref().map(set);
        }
        out.exports = out
            .exports
            .map(|exports| exports.into_iter().map(|(k, ids)| (k, set(&ids))).collect());
        out
    }

    /// The file, canonically.
    pub fn write(&self) -> String {
        crate::canonical::write(&self.canonical())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpawnCategory {
    Monster,
    Animal,
    WaterAnimal,
    WaterAmbient,
    WaterUndergroundCreature,
    Ambient,
    Axolotl,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorldConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spawn_limits: Option<BTreeMap<SpawnCategory, i64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spawn_intervals: Option<BTreeMap<SpawnCategory, i64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub game_rules: Option<Map<Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub terrain: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seed: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension_type: Option<String>,
}

impl WorldConfig {
    /// It says nothing (`WorldConfig.isEmpty`).
    pub fn is_empty(&self) -> bool {
        self.spawn_limits.as_ref().is_none_or(|m| m.is_empty())
            && self.spawn_intervals.as_ref().is_none_or(|m| m.is_empty())
            && self.game_rules.as_ref().is_none_or(|m| m.is_empty())
            && self.terrain.is_none()
            && self.seed.is_none()
            && self.dimension_type.is_none()
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Dependency {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub git: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rev: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provided: Option<bool>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Requires {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub console: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub moderation: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub db: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub web: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bots: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wasm: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub network: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plugins: Option<Vec<String>>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Allow {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub permissions: Option<Vec<String>>,
}

impl Allow {
    /// Whether it allows permission `node`: a node it lists, or one under it.
    pub fn allows_permission(&self, node: &str) -> bool {
        self.permissions
            .iter()
            .flatten()
            .any(|p| node == p || node.starts_with(&format!("{p}.")))
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Listing {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub authors: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub screenshots: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub players: Option<ListingPlayers>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ListingPlayers {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max: Option<i64>,
}

/// A setting the server's owner may change (`settings` in the manifest), by
/// its `type`. Reading and checking its values comes with settings.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase", deny_unknown_fields)]
pub enum SettingDef {
    Boolean {
        description: String,
        default: bool,
    },
    Integer {
        description: String,
        default: i64,
        #[serde(skip_serializing_if = "Option::is_none")]
        min: Option<i64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        max: Option<i64>,
    },
    Number {
        description: String,
        default: f64,
        #[serde(skip_serializing_if = "Option::is_none")]
        min: Option<f64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        max: Option<f64>,
    },
    String {
        description: String,
        default: String,
    },
    Choice {
        description: String,
        default: String,
        choices: Vec<String>,
    },
}
