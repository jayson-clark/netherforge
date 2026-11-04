//! `netherforge.json`: what a project is. Format's `ProjectManifest` and
//! `ManifestKind` (its canonical form).

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::canonical::{Entries, Map, set};

pub const FILE_NAME: &str = "netherforge.json";
pub const SCHEMA: &str = ".netherforge/schema/netherforge.schema.json";
/// The format version this reads and writes.
pub const FORMAT_VERSION: i64 = 1;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

#[derive(
    Clone,
    Copy,
    Debug,
    PartialEq,
    Eq,
    Hash,
    PartialOrd,
    Ord,
    Serialize,
    Deserialize,
    schemars::JsonSchema,
)]
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

impl SpawnCategory {
    /// Every category, in their order.
    pub const ALL: [SpawnCategory; 7] = [
        SpawnCategory::Monster,
        SpawnCategory::Animal,
        SpawnCategory::WaterAnimal,
        SpawnCategory::WaterAmbient,
        SpawnCategory::WaterUndergroundCreature,
        SpawnCategory::Ambient,
        SpawnCategory::Axolotl,
    ];
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorldConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spawn_limits: Option<Entries<SpawnCategory, i64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spawn_intervals: Option<Entries<SpawnCategory, i64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub game_rules: Option<Map<Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "terrain"))]
    pub terrain: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seed: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "dimension_type"))]
    pub dimension_type: Option<String>,
}

impl WorldConfig {
    /// It says nothing (`WorldConfig.isEmpty`).
    pub fn is_empty(&self) -> bool {
        self.spawn_limits.as_ref().is_none_or(Entries::is_empty)
            && self.spawn_intervals.as_ref().is_none_or(Entries::is_empty)
            && self.game_rules.as_ref().is_none_or(Entries::is_empty)
            && self.terrain.is_none()
            && self.seed.is_none()
            && self.dimension_type.is_none()
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListingPlayers {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max: Option<i64>,
}

/// A setting the server's owner may change (`settings` in the manifest), by
/// its `type`. Reading and checking its values comes with settings.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
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

const MAX_DESCRIPTION: usize = 500;
const MAX_TAGS: usize = 10;

/// The format version a manifest's text says, if it says one.
pub fn format_version_of(text: &str) -> Option<i64> {
    serde_json::from_str::<Value>(text)
        .ok()?
        .get("formatVersion")?
        .as_i64()
}

/// Why a project in `version` can't be opened by this build, or `None` for the current one.
pub fn format_problem(version: i64) -> Option<String> {
    use std::cmp::Ordering::*;
    match version.cmp(&FORMAT_VERSION) {
        Greater => Some(format!(
            "This project uses format {version}; this NetherForge reads format {FORMAT_VERSION}. Update NetherForge."
        )),
        Less => Some(format!(
            "This project says format {version}, which no NetherForge has used: this NetherForge reads format {FORMAT_VERSION}."
        )),
        Equal => None,
    }
}

/// `netherforge.json` from its text (`None` when there's none), and its
/// problems: format's `Projects.loadManifest`.
pub fn load(text: Option<&str>) -> (Option<Manifest>, Vec<crate::Problem>) {
    use crate::json::child_path;
    use crate::names::{self, MinecraftVersion};
    use crate::problem::{ProblemSink, codes};

    let Some(text) = text else {
        return (
            None,
            vec![codes::PROJECT_NO_MANIFEST.at(
                FILE_NAME,
                "This folder has no netherforge.json, so it isn't a NetherForge project",
                None,
            )],
        );
    };
    // Another format's manifest needn't read as this one's: say which format it is, and nothing else.
    if let Some(message) = format_version_of(text).and_then(format_problem) {
        return (
            None,
            vec![codes::PROJECT_FORMAT_VERSION.at(
                FILE_NAME,
                message,
                Some("$.formatVersion".into()),
            )],
        );
    }
    let manifest: Manifest = match crate::read::parse(text, FILE_NAME) {
        Ok(m) => m,
        Err(problem) => return (None, vec![*problem]),
    };
    let mut sink = ProblemSink::new(FILE_NAME);
    let at = |p: &str| Some(p.to_owned());
    match MinecraftVersion::parse(&manifest.minecraft) {
        None => sink.report(
            codes::PROJECT_MINECRAFT,
            format!("\"{}\" isn't a Minecraft version", manifest.minecraft),
            at("$.minecraft"),
        ),
        Some(v) if v < MinecraftVersion::parse(names::OLDEST_SUPPORTED).expect("a version") => sink
            .report(
                codes::PROJECT_MINECRAFT_OLD,
                format!(
                    "NetherForge supports Minecraft {} and later",
                    names::OLDEST_SUPPORTED
                ),
                at("$.minecraft"),
            ),
        Some(_) => {}
    }
    if manifest.name.trim().is_empty() {
        sink.report(codes::PROJECT_NAME, "The project has no name", at("$.name"));
    }
    let namespace = &manifest.namespace;
    if !names::is_namespace(namespace) {
        sink.report(
            codes::PROJECT_NAMESPACE,
            format!(
                "\"{namespace}\" isn't a usable namespace ({})",
                names::NAMESPACE_RULE
            ),
            at("$.namespace"),
        );
    } else if names::RESERVED_NAMESPACES.contains(&namespace.as_str()) {
        sink.report(
            codes::PROJECT_NAMESPACE_RESERVED,
            format!("\"{namespace}\" is taken (by the game, the server or NetherForge): pick one that's the project's own"),
            at("$.namespace"),
        );
    }
    if !names::is_version(&manifest.version) {
        sink.report(
            codes::PROJECT_VERSION,
            format!(
                "\"{}\" isn't a semantic version, like 1.0.0",
                manifest.version
            ),
            at("$.version"),
        );
    }
    for (index, name) in manifest.managed_worlds.iter().flatten().enumerate() {
        if !names::is_world_name(name) {
            sink.report(
                codes::PROJECT_WORLD_NAME,
                format!("\"{name}\" isn't a world name ({})", names::WORLD_NAME_RULE),
                Some(format!("$.managedWorlds[{index}]")),
            );
        }
    }
    for (name, config) in manifest.worlds.iter().flat_map(|w| w.iter()) {
        let world = child_path("$.worlds", &name.0);
        if !names::is_world_name(&name.0) {
            sink.report(
                codes::PROJECT_WORLD_NAME,
                format!("\"{name}\" isn't a world name ({})", names::WORLD_NAME_RULE),
                Some(world.clone()),
            );
        }
        for (rule, value) in config.game_rules.iter().flat_map(|r| r.iter()) {
            let at = child_path(&child_path(&world, "gameRules"), &rule.0);
            let whole = value.as_i64().is_some_and(|n| i32::try_from(n).is_ok())
                && !value.to_string().contains('.');
            if !names::is_game_id(&rule.0) {
                sink.report(
                    codes::PROJECT_WORLD_RULE,
                    format!("\"{rule}\" isn't a game rule's id (keep_inventory)"),
                    Some(at),
                );
            } else if !(value.is_boolean() || whole) {
                sink.report(
                    codes::PROJECT_WORLD_RULE,
                    format!("{rule} is true, false or a whole number, as the rule takes"),
                    Some(at),
                );
            }
        }
        for (field, values) in [
            ("spawnLimits", &config.spawn_limits),
            ("spawnIntervals", &config.spawn_intervals),
        ] {
            for (category, value) in values.iter().flat_map(|v| v.iter()) {
                if *value < 0 {
                    let id = serde_json::to_value(category)
                        .expect("a name")
                        .as_str()
                        .expect("a name")
                        .to_owned();
                    sink.report(
                        codes::PROJECT_WORLD_SPAWN,
                        format!("{field}.{id} is {value}: a whole number, 0 or more"),
                        Some(child_path(&child_path(&world, field), &id)),
                    );
                }
            }
        }
    }
    for (index, node) in manifest
        .allow
        .iter()
        .flat_map(|a| a.permissions.iter().flatten())
        .enumerate()
    {
        if !names::is_permission_node(node) {
            sink.report(
                codes::PROJECT_PERMISSION_NODE,
                format!(
                    "\"{node}\" isn't a permission node ({})",
                    names::PERMISSION_NODE_RULE
                ),
                Some(format!("$.allow.permissions[{index}]")),
            );
        }
    }
    for (index, host) in manifest
        .requires
        .iter()
        .flat_map(|r| r.http.iter().flatten())
        .enumerate()
    {
        if !names::is_http_host(host) {
            // A URL where a host belongs is the usual mistake: name the host to write.
            let bare = host
                .split_once("://")
                .map(|(_, rest)| rest)
                .unwrap_or("")
                .split('/')
                .next()
                .unwrap_or("")
                .split('?')
                .next()
                .unwrap_or("")
                .split(':')
                .next()
                .unwrap_or("")
                .to_lowercase();
            let message = if !bare.is_empty() && names::is_http_host(&bare) {
                format!(
                    "\"{host}\" is a URL: list its host alone, \"{bare}\", which covers http, https, ws and wss"
                )
            } else {
                format!("\"{host}\" isn't a host ({})", names::HTTP_HOST_RULE)
            };
            sink.report(
                codes::PROJECT_REQUIRES_HOST,
                message,
                Some(format!("$.requires.http[{index}]")),
            );
        }
    }
    for (index, name) in manifest
        .requires
        .iter()
        .flat_map(|r| r.plugins.iter().flatten())
        .enumerate()
    {
        if !names::is_plugin_name(name) {
            sink.report(
                codes::PROJECT_REQUIRES_PLUGIN,
                format!(
                    "\"{name}\" isn't a plugin's name ({})",
                    names::PLUGIN_NAME_RULE
                ),
                Some(format!("$.requires.plugins[{index}]")),
            );
        }
    }
    if let Some(settings) = &manifest.settings {
        crate::settings::validate(settings.iter().map(|(k, v)| (k.0.as_str(), v)), &mut sink);
    }
    if let Some(listing) = &manifest.listing {
        check_listing(listing, &mut sink);
    }
    (Some(manifest), sink.problems)
}

/// The pictures a listing names, each with where.
fn pictures(listing: &Listing) -> Vec<(&str, String)> {
    let mut out: Vec<(&str, String)> = listing
        .icon
        .iter()
        .map(|i| (i.as_str(), "$.listing.icon".to_owned()))
        .collect();
    for (index, shot) in listing.screenshots.iter().flatten().enumerate() {
        out.push((shot.as_str(), format!("$.listing.screenshots[{index}]")));
    }
    out
}

fn check_listing(listing: &Listing, sink: &mut crate::ProblemSink) {
    use crate::problem::codes::PROJECT_LISTING;
    let mut report =
        |message: String, at: &str| sink.report(PROJECT_LISTING, message, Some(at.to_owned()));
    if let Some(description) = &listing.description {
        // Kotlin's `length`: UTF-16 units.
        let length = description.encode_utf16().count();
        if length > MAX_DESCRIPTION {
            report(
                format!("The description is {length} characters: at most {MAX_DESCRIPTION}"),
                "$.listing.description",
            );
        }
    }
    for (index, author) in listing.authors.iter().flatten().enumerate() {
        if author.trim().is_empty() {
            report(
                "An author is a name, not nothing".into(),
                &format!("$.listing.authors[{index}]"),
            );
        }
    }
    if let Some(tags) = &listing.tags {
        if tags.len() > MAX_TAGS {
            report(
                format!("{} tags: at most {MAX_TAGS}", tags.len()),
                "$.listing.tags",
            );
        }
        static TAG: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
            regex::Regex::new("^[a-z0-9][a-z0-9-]{0,31}$").expect("a valid pattern")
        });
        let tag = &*TAG;
        for (index, t) in tags.iter().enumerate() {
            if !tag.is_match(t) {
                report(
                    format!("\"{t}\" isn't a tag: lowercase letters, digits and -, at most 32"),
                    &format!("$.listing.tags[{index}]"),
                );
            }
        }
    }
    for (picture, at) in pictures(listing) {
        if !picture.ends_with(".png")
            || picture.starts_with('/')
            || picture.split('/').any(|p| p == ".." || p.is_empty())
        {
            report(
                format!("\"{picture}\" isn't a PNG's path in the project (listing/icon.png)"),
                &at,
            );
        }
    }
    if let Some(players) = &listing.players {
        let min = players.min.unwrap_or(1);
        if min < 1 {
            report(
                format!("players.min is {min}: at least 1"),
                "$.listing.players.min",
            );
        }
        if let Some(max) = players.max
            && max < min
        {
            report(
                format!("players.max is {max}, less than min"),
                "$.listing.players.max",
            );
        }
    }
}

/// The listing's pictures that aren't among the project's `files`.
pub fn listing_files(
    manifest: Option<&Manifest>,
    files: &std::collections::BTreeSet<String>,
) -> Vec<crate::Problem> {
    let Some(listing) = manifest.and_then(|m| m.listing.as_ref()) else {
        return Vec::new();
    };
    let mut sink = crate::ProblemSink::new(FILE_NAME);
    for (picture, at) in pictures(listing) {
        if picture.ends_with(".png") && !files.contains(picture) {
            sink.report(
                crate::codes::PROJECT_LISTING,
                format!("There's no {picture} in the project"),
                Some(at),
            );
        }
    }
    sink.problems
}
