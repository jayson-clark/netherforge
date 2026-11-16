//! Where the game's worldgen JSON names other registry entries by id, for the
//! places that are the same in every supported version (format's
//! `WorldgenReferences`). A place names an entry of the first of its targets
//! the game has: registries are sometimes renamed between versions.

use serde_json::Value;

use crate::json::child_path;

/// In a file of `registry`, the value at `pattern` (keys, `*` for every
/// element or value, `**` for any depth) names entries of `targets`.
pub struct Rule {
    pub registry: &'static str,
    pub pattern: &'static str,
    pub targets: &'static [&'static str],
}

/// A reference a file makes: at `path`, `text` as written, an entry of the first of `targets` the game has.
#[derive(Clone, Debug, PartialEq)]
pub struct Found {
    pub path: String,
    pub text: String,
    pub targets: Vec<String>,
}

const BIOME: &[&str] = &["worldgen/biome"];
const PLACED_FEATURE: &[&str] = &["worldgen/placed_feature"];
const CONFIGURED_FEATURE: &[&str] = &["worldgen/configured_feature", "worldgen/feature"];
const CONFIGURED_CARVER: &[&str] = &["worldgen/configured_carver", "worldgen/carver"];
const STRUCTURE: &[&str] = &["worldgen/structure"];
const STRUCTURE_SET: &[&str] = &["worldgen/structure_set"];
const TEMPLATE_POOL: &[&str] = &["worldgen/template_pool"];
const PROCESSOR_LIST: &[&str] = &["worldgen/processor_list"];
const NOISE: &[&str] = &["worldgen/noise"];
const NOISE_SETTINGS: &[&str] = &["worldgen/noise_settings"];
const DENSITY_FUNCTION: &[&str] = &["worldgen/density_function"];
const PARAMETER_LIST: &[&str] = &["worldgen/multi_noise_biome_source_parameter_list"];
const MATERIAL_RULE: &[&str] = &["worldgen/material_rule"];
const DIMENSION_TYPE: &[&str] = &["dimension_type"];

const fn rule(
    registry: &'static [&'static str],
    pattern: &'static str,
    targets: &'static [&'static str],
) -> Rule {
    Rule {
        registry: registry[0],
        pattern,
        targets,
    }
}

pub const RULES: &[Rule] = &[
    rule(PLACED_FEATURE, "feature", CONFIGURED_FEATURE),
    rule(BIOME, "features.*", PLACED_FEATURE),
    rule(BIOME, "carvers", CONFIGURED_CARVER),
    rule(STRUCTURE, "biomes", BIOME),
    rule(STRUCTURE, "start_pool", TEMPLATE_POOL),
    rule(STRUCTURE_SET, "structures.*.structure", STRUCTURE),
    rule(TEMPLATE_POOL, "fallback", TEMPLATE_POOL),
    rule(
        TEMPLATE_POOL,
        "elements.*.element.processors",
        PROCESSOR_LIST,
    ),
    rule(TEMPLATE_POOL, "elements.*.element.feature", PLACED_FEATURE),
    rule(
        &["worldgen/world_preset"],
        "dimensions.*.type",
        DIMENSION_TYPE,
    ),
    rule(
        &["worldgen/world_preset"],
        "dimensions.*.generator.settings",
        NOISE_SETTINGS,
    ),
    rule(
        &["worldgen/world_preset"],
        "dimensions.*.generator.biome_source.preset",
        PARAMETER_LIST,
    ),
    rule(
        &["worldgen/world_preset"],
        "dimensions.*.generator.biome_source.biome",
        BIOME,
    ),
    rule(
        &["worldgen/world_preset"],
        "dimensions.*.generator.biome_source.biomes.*.biome",
        BIOME,
    ),
    rule(
        &["worldgen/flat_level_generator_preset"],
        "settings.biome",
        BIOME,
    ),
    rule(
        &["worldgen/flat_level_generator_preset"],
        "settings.structure_overrides",
        STRUCTURE_SET,
    ),
    rule(NOISE_SETTINGS, "noise_router.*", DENSITY_FUNCTION),
    rule(NOISE_SETTINGS, "material_rule", MATERIAL_RULE),
    rule(NOISE_SETTINGS, "**.noise", NOISE),
    rule(DENSITY_FUNCTION, "**.noise", NOISE),
];

/// Every reference `element`, an entry of `registry`, makes where the rules look, in document order per rule.
pub fn find(registry: &str, element: &Value) -> Vec<Found> {
    let mut out = Vec::new();
    for rule in RULES.iter().filter(|r| r.registry == registry) {
        let pattern: Vec<&str> = rule.pattern.split('.').collect();
        walk(element, &pattern, rule.targets, "$", &mut out);
    }
    let mut unique: Vec<Found> = Vec::new();
    for found in out {
        if !unique.contains(&found) {
            unique.push(found);
        }
    }
    unique
}

/// A tag file's values, ids or `#tags` of its own registry; `None` for a tag that isn't one.
pub fn tag_values(registry: &str, element: &Value) -> Option<Vec<Found>> {
    let object = element.as_object()?;
    let values = object.get("values")?.as_array()?;
    if object.get("replace").is_some_and(|r| !r.is_boolean())
        || object.keys().any(|k| k != "values" && k != "replace")
    {
        return None;
    }
    let targets = vec![registry.to_owned()];
    values
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let at = format!("$.values[{index}]");
            match value {
                Value::String(text) => Some(Found {
                    path: at,
                    text: text.clone(),
                    targets: targets.clone(),
                }),
                Value::Object(entry) => {
                    let id = entry.get("id")?.as_str()?;
                    if entry.keys().any(|k| k != "id" && k != "required") {
                        return None;
                    }
                    Some(Found {
                        path: format!("{at}.id"),
                        text: id.to_owned(),
                        targets: targets.clone(),
                    })
                }
                _ => None,
            }
        })
        .collect()
}

fn owned(targets: &[&str]) -> Vec<String> {
    targets.iter().map(|t| (*t).to_owned()).collect()
}

fn children<'a>(element: &'a Value, path: &str) -> Vec<(&'a Value, String)> {
    match element {
        Value::Array(items) => items
            .iter()
            .enumerate()
            .map(|(i, c)| (c, format!("{path}[{i}]")))
            .collect(),
        Value::Object(map) => map.iter().map(|(k, c)| (c, child_path(path, k))).collect(),
        _ => Vec::new(),
    }
}

fn walk(
    element: &Value,
    pattern: &[&str],
    targets: &'static [&'static str],
    path: &str,
    out: &mut Vec<Found>,
) {
    let Some((segment, rest)) = pattern.split_first() else {
        match element {
            Value::String(text) => out.push(Found {
                path: path.to_owned(),
                text: text.clone(),
                targets: owned(targets),
            }),
            Value::Array(items) => {
                for (index, item) in items.iter().enumerate() {
                    if let Value::String(text) = item {
                        out.push(Found {
                            path: format!("{path}[{index}]"),
                            text: text.clone(),
                            targets: owned(targets),
                        });
                    }
                }
            }
            _ => {}
        }
        return;
    };
    match *segment {
        "*" => {
            for (child, at) in children(element, path) {
                walk(child, rest, targets, &at, out);
            }
        }
        "**" => {
            walk(element, rest, targets, path, out);
            for (child, at) in children(element, path) {
                walk(child, pattern, targets, &at, out);
            }
        }
        key => {
            if let Some(child) = element.get(key).filter(|_| element.is_object()) {
                walk(child, rest, targets, &child_path(path, key), out);
            }
        }
    }
}
