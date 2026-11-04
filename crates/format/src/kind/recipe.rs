//! `recipes/<id>.json`: a crafting, cooking, smithing, stonecutting or
//! station recipe. Format's `RecipeFile`, `RecipeKind` and `RecipeValidator`.
//! Which fields a recipe has depends on its type, so most of its checks are
//! its own; a material's count is a field's rule.

use std::collections::BTreeSet;

use schemars::JsonSchema;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::item::{self, ItemDef};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};

pub const GRID: usize = 3;
pub const MOST_INGREDIENTS: usize = GRID * GRID;
pub const MAX_STATION_RANGE: i32 = 16;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RecipeFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(rename = "type")]
    pub kind: RecipeType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pattern: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub key: Option<Map<Ingredient>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ingredients: Option<Vec<Ingredient>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ingredient: Option<Ingredient>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template: Option<Ingredient>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub base: Option<Ingredient>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub addition: Option<Ingredient>,
    pub result: ItemDef,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub experience: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cooking_time: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<RecipeCategory>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub materials: Option<Vec<Material>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stations: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub station_range: Option<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum RecipeType {
    Shaped,
    Shapeless,
    Furnace,
    Blasting,
    Smoking,
    CampfireCooking,
    SmithingTransform,
    Stonecutting,
    Station,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum RecipeCategory {
    Building,
    Redstone,
    Equipment,
    Misc,
    Food,
    Blocks,
}

impl RecipeCategory {
    fn id(self) -> &'static str {
        match self {
            RecipeCategory::Building => "building",
            RecipeCategory::Redstone => "redstone",
            RecipeCategory::Equipment => "equipment",
            RecipeCategory::Misc => "misc",
            RecipeCategory::Food => "food",
            RecipeCategory::Blocks => "blocks",
        }
    }
}

impl RecipeType {
    pub fn id(self) -> &'static str {
        match self {
            RecipeType::Shaped => "shaped",
            RecipeType::Shapeless => "shapeless",
            RecipeType::Furnace => "furnace",
            RecipeType::Blasting => "blasting",
            RecipeType::Smoking => "smoking",
            RecipeType::CampfireCooking => "campfire_cooking",
            RecipeType::SmithingTransform => "smithing_transform",
            RecipeType::Stonecutting => "stonecutting",
            RecipeType::Station => "station",
        }
    }

    pub fn cooking(self) -> bool {
        matches!(
            self,
            RecipeType::Furnace
                | RecipeType::Blasting
                | RecipeType::Smoking
                | RecipeType::CampfireCooking
        )
    }

    /// The categories it may be in.
    pub fn categories(self) -> &'static [RecipeCategory] {
        use RecipeCategory::*;
        match self {
            RecipeType::Shaped | RecipeType::Shapeless => &[Building, Redstone, Equipment, Misc],
            t if t.cooking() => &[Food, Blocks, Misc],
            _ => &[],
        }
    }

    pub fn required_fields(self) -> &'static [&'static str] {
        match self {
            RecipeType::Shaped => &["pattern", "key"],
            RecipeType::Shapeless => &["ingredients"],
            RecipeType::SmithingTransform => &["template", "base", "addition"],
            RecipeType::Station => &["materials"],
            _ => &["ingredient"],
        }
    }

    pub fn optional_fields(self) -> &'static [&'static str] {
        if self.cooking() {
            &["experience", "cookingTime"]
        } else if self == RecipeType::Station {
            &["stations", "stationRange"]
        } else {
            &[]
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Material {
    pub ingredient: Ingredient,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("x-problem" = "recipe.ingredients"))]
    pub count: Option<i32>,
}

/// An item id (`"minecraft:stick"`), a tag (`"#minecraft:planks"`), or a
/// project item (`{ "item": "ruby" }`).
#[derive(Clone, Debug, PartialEq, JsonSchema)]
#[schemars(with = "serde_json::Value")]
pub enum Ingredient {
    Kind(String),
    Tag(String),
    Item(String),
}

const INGREDIENT: &str = "An ingredient is an item id (\"minecraft:stick\"), a tag (\"#minecraft:planks\") or a project item ({ \"item\": \"ruby\" })";

impl Serialize for Ingredient {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Ingredient::Kind(kind) => s.serialize_str(kind),
            Ingredient::Tag(tag) => s.serialize_str(&format!("#{tag}")),
            Ingredient::Item(item) => serde_json::json!({ "item": item }).serialize(s),
        }
    }
}

impl<'de> Deserialize<'de> for Ingredient {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Ingredient, D::Error> {
        use serde::de::Error;
        match serde_json::Value::deserialize(d)? {
            serde_json::Value::String(text) => Ok(match text.strip_prefix('#') {
                Some(tag) => Ingredient::Tag(tag.to_owned()),
                None => Ingredient::Kind(text),
            }),
            serde_json::Value::Object(fields) => {
                if let Some(other) = fields.keys().find(|k| *k != "item") {
                    return Err(D::Error::custom(format!(
                        "An ingredient object has only \"item\", not \"{other}\""
                    )));
                }
                match fields.get("item") {
                    Some(serde_json::Value::String(item)) => Ok(Ingredient::Item(item.clone())),
                    _ => Err(D::Error::custom(INGREDIENT)),
                }
            }
            _ => Err(D::Error::custom(INGREDIENT)),
        }
    }
}

/// The codes an ingredient's checks report in, so each kind keeps its own.
pub struct IngredientCodes {
    pub invalid: crate::ProblemCode,
    pub unknown_item: crate::ProblemCode,
    pub unknown_tag: crate::ProblemCode,
}

pub const RECIPE_INGREDIENT: IngredientCodes = IngredientCodes {
    invalid: codes::RECIPE_INGREDIENT,
    unknown_item: codes::RECIPE_UNKNOWN_ITEM,
    unknown_tag: codes::RECIPE_UNKNOWN_TAG,
};

/// An ingredient's checks (`Rules.ingredient`): an id or tag the game has; never air.
pub fn check_ingredient(
    ingredient: &Ingredient,
    at: &str,
    codes: &IngredientCodes,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    let valid = |id: &str| {
        regex::Regex::new(game::ID_PATTERN)
            .expect("a pattern")
            .is_match(id)
    };
    let at = Some(at.to_owned());
    match ingredient {
        Ingredient::Item(_) => {}
        Ingredient::Tag(tag) => {
            if !valid(tag) {
                sink.report(codes.invalid, format!("\"#{tag}\" isn't an item tag"), at);
            } else if let Some(game) = game
                && !game.has_tag(Registry::Item, &game::normalize(tag))
            {
                sink.report(
                    codes.unknown_tag,
                    format!(
                        "Minecraft {} has no item tag \"#{}\"",
                        game.minecraft_version(),
                        game::normalize(tag)
                    ),
                    at,
                );
            }
        }
        Ingredient::Kind(kind) => {
            if kind.is_empty() || !valid(kind) {
                sink.report(codes.invalid, format!("\"{kind}\" isn't an item id"), at);
            } else if game::normalize(kind) == "minecraft:air" {
                sink.report(codes.invalid, "An ingredient can't be air", at);
            } else if let Some(game) = game
                && !game.has(Registry::Item, &game::normalize(kind))
            {
                sink.report(
                    codes.unknown_item,
                    format!(
                        "Minecraft {} has no item \"{}\"",
                        game.minecraft_version(),
                        game::normalize(kind)
                    ),
                    at,
                );
            }
        }
    }
}

impl RecipeFile {
    /// Every ingredient, with where it is.
    pub fn ingredients(&self) -> Vec<(String, &Ingredient)> {
        let mut out = Vec::new();
        for (symbol, ingredient) in self.key.iter().flat_map(|k| k.iter()) {
            out.push((child_path("$.key", &symbol.0), ingredient));
        }
        for (k, ingredient) in self.ingredients.iter().flatten().enumerate() {
            out.push((format!("$.ingredients[{k}]"), ingredient));
        }
        for (name, ingredient) in [
            ("ingredient", &self.ingredient),
            ("template", &self.template),
            ("base", &self.base),
            ("addition", &self.addition),
        ] {
            if let Some(ingredient) = ingredient {
                out.push((format!("$.{name}"), ingredient));
            }
        }
        for (k, material) in self.materials.iter().flatten().enumerate() {
            out.push((format!("$.materials[{k}].ingredient"), &material.ingredient));
        }
        out
    }

    fn shaped(&self, sink: &mut ProblemSink) {
        let Some(pattern) = &self.pattern else { return };
        let report = |sink: &mut ProblemSink, message: String, at: &str| {
            sink.report(codes::RECIPE_PATTERN, message, Some(at.to_owned()))
        };
        if pattern.is_empty() || pattern.len() > GRID {
            report(sink, format!("A pattern has 1 to {GRID} rows"), "$.pattern");
        }
        let width = pattern.first().map_or(0, |r| r.chars().count());
        for (index, row) in pattern.iter().enumerate() {
            let length = row.chars().count();
            if length == 0 || length > GRID {
                report(
                    sink,
                    format!("A pattern row has 1 to {GRID} characters"),
                    &format!("$.pattern[{index}]"),
                );
            } else if length != width {
                report(
                    sink,
                    format!("Every row of a pattern is as wide as the first ({width})"),
                    &format!("$.pattern[{index}]"),
                );
            }
        }
        if !pattern.is_empty() && pattern.iter().all(|r| r.trim().is_empty()) {
            report(
                sink,
                "A pattern needs at least one ingredient".into(),
                "$.pattern",
            );
        }
        // In the order they're first used.
        let mut used: Vec<char> = Vec::new();
        for c in pattern.iter().flat_map(|r| r.chars()).filter(|c| *c != ' ') {
            if !used.contains(&c) {
                used.push(c);
            }
        }
        let keys: BTreeSet<String> = self
            .key
            .iter()
            .flat_map(|k| k.iter().map(|(s, _)| s.0.clone()))
            .collect();
        for symbol in &used {
            if !keys.contains(&symbol.to_string()) {
                report(
                    sink,
                    format!("\"{symbol}\" in the pattern isn't in key"),
                    "$.pattern",
                );
            }
        }
        for (symbol, _) in self.key.iter().flat_map(|k| k.iter()) {
            let at = Some(child_path("$.key", &symbol.0));
            if symbol.0.chars().count() != 1 || symbol.0 == " " {
                sink.report(
                    codes::RECIPE_KEY,
                    "A key is one character other than a space",
                    at,
                );
            } else if !used.contains(&symbol.0.chars().next().expect("one character")) {
                sink.report(
                    codes::RECIPE_KEY,
                    format!("\"{symbol}\" isn't in the pattern"),
                    at,
                );
            }
        }
    }
}

impl Document for RecipeFile {
    const SCHEMA: &'static str = "../.netherforge/schema/recipe.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let kind = self.kind;
        let present = [
            ("pattern", self.pattern.is_some()),
            ("key", self.key.is_some()),
            ("ingredients", self.ingredients.is_some()),
            ("ingredient", self.ingredient.is_some()),
            ("template", self.template.is_some()),
            ("base", self.base.is_some()),
            ("addition", self.addition.is_some()),
            ("experience", self.experience.is_some()),
            ("cookingTime", self.cooking_time.is_some()),
            ("materials", self.materials.is_some()),
            ("stations", self.stations.is_some()),
            ("stationRange", self.station_range.is_some()),
        ];
        for (field, here) in present {
            if here
                && !kind.required_fields().contains(&field)
                && !kind.optional_fields().contains(&field)
            {
                sink.report(
                    codes::RECIPE_FIELD,
                    format!("A {} recipe has no {field}", kind.id()),
                    Some(format!("$.{field}")),
                );
            }
        }
        for field in kind.required_fields() {
            if !present.iter().any(|(f, here)| f == field && *here) {
                sink.report(
                    codes::RECIPE_MISSING,
                    format!("A {} recipe needs {field}", kind.id()),
                    Some("$".into()),
                );
            }
        }
        if self.group.is_some() && kind == RecipeType::SmithingTransform {
            sink.report(
                codes::RECIPE_FIELD,
                "A smithing_transform recipe has no group",
                Some("$.group".into()),
            );
        }
        match kind {
            RecipeType::Shaped => self.shaped(sink),
            RecipeType::Shapeless => {
                if let Some(list) = &self.ingredients
                    && (list.is_empty() || list.len() > MOST_INGREDIENTS)
                {
                    sink.report(
                        codes::RECIPE_INGREDIENTS,
                        format!("A shapeless recipe takes 1 to {MOST_INGREDIENTS} ingredients"),
                        Some("$.ingredients".into()),
                    );
                }
            }
            RecipeType::Station => {
                if self.materials.as_ref().is_some_and(|m| m.is_empty()) {
                    sink.report(
                        codes::RECIPE_INGREDIENTS,
                        "A station recipe takes at least one material",
                        Some("$.materials".into()),
                    );
                }
                for (index, station) in self.stations.iter().flatten().enumerate() {
                    if station.split('|').any(|s| s.trim().is_empty()) {
                        sink.report(
                            codes::RECIPE_STATION,
                            "A station is a block id, a #tag or a project block, or several of them between | (any of them)",
                            Some(format!("$.stations[{index}]")),
                        );
                    }
                }
                if self
                    .station_range
                    .is_some_and(|r| !(1..=MAX_STATION_RANGE).contains(&r))
                {
                    sink.report(
                        codes::RECIPE_STATION,
                        format!("stationRange must be 1 to {MAX_STATION_RANGE}"),
                        Some("$.stationRange".into()),
                    );
                }
            }
            _ => {}
        }
        if kind.cooking() {
            if self.experience.is_some_and(|e| e < 0.0) {
                sink.report(
                    codes::RECIPE_EXPERIENCE,
                    "experience can't be negative",
                    Some("$.experience".into()),
                );
            }
            if self.cooking_time.is_some_and(|t| t < 1) {
                sink.report(
                    codes::RECIPE_COOKING_TIME,
                    "cookingTime must be at least 1 tick",
                    Some("$.cookingTime".into()),
                );
            }
        }
        if let Some(category) = self.category
            && !kind.categories().contains(&category)
        {
            let allowed: Vec<String> = kind
                .categories()
                .iter()
                .map(|c| format!("\"{}\"", c.id()))
                .collect();
            let message = if allowed.is_empty() {
                format!("A {} recipe has no category", kind.id())
            } else {
                format!(
                    "A {} recipe's category is one of {}",
                    kind.id(),
                    allowed.join(", ")
                )
            };
            sink.report(codes::RECIPE_CATEGORY, message, Some("$.category".into()));
        }
        for (at, ingredient) in self.ingredients() {
            check_ingredient(ingredient, &at, &RECIPE_INGREDIENT, sink, cx.game);
        }
        item::check(&self.result, "$.result", sink, cx.game);
    }
}
