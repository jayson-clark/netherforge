//! `advancements/<id>.json`: an advancement, its display and its criteria.
//! Format's `AdvancementFile`, `AdvancementKind` and `AdvancementValidator`; a
//! criterion's name, the experience and a requirement group's size are their
//! fields' rules. (Its datapack entry is written with the start-up datapack.)

use std::collections::BTreeSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Free, Map};
use crate::document::{Context, Document};
use crate::game::{self, Registry};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AdvancementFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "advancement"))]
    pub parent: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display: Option<AdvancementDisplay>,
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": r"^[a-z0-9][a-z0-9_]{0,63}$",
        "x-problem": "advancement.criterion-name",
        "x-rule": "a usable criterion name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)"
    }))]
    pub criteria: Map<AdvancementCriterion>,
    /// Groups of criteria: it's done when one of each group is met.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub requirements: Option<Vec<RequirementGroup>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "advancement.experience"))]
    pub experience: Option<i32>,
}

/// One group of a requirement: any of its criteria will do.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(transparent)]
#[schemars(extend("minItems" = 1, "x-problem" = "advancement.requirements"))]
pub struct RequirementGroup(pub Vec<String>);

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AdvancementDisplay {
    pub icon: AdvancementIcon,
    #[schemars(extend("x-minimessage" = true))]
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-minimessage" = true))]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frame: Option<AdvancementFrame>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub background: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub toast: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub announce: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hidden: Option<bool>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AdvancementIcon {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "item"))]
    pub item: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "item_model"))]
    pub item_model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub glint: Option<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum AdvancementFrame {
    Task,
    Goal,
    Challenge,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AdvancementCriterion {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub trigger: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conditions: Option<Map<Free>>,
}

impl Document for AdvancementFile {
    /// Each its file in the game's format; none for one whose icon's item isn't running.
    fn datapack(
        running: &[(crate::reference::ResourceKey, &Self)],
        cx: &crate::startup::Context,
    ) -> Vec<(String, crate::startup::Entry)> {
        use crate::startup::advancement;
        running
            .iter()
            .filter_map(|(key, file)| {
                let json = advancement::of(file, cx)?;
                Some((
                    format!(
                        "data/{}/{}/{}.json",
                        key.namespace,
                        advancement::FOLDER,
                        key.path
                    ),
                    crate::startup::Entry::json(&json),
                ))
            })
            .collect()
    }

    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../.netherforge/schema/advancement.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if let Some(display) = &self.display {
            let at = "$.display.icon";
            let icon = &display.icon;
            match (&icon.kind, &icon.item) {
                (None, None) => sink.report(
                    codes::ADVANCEMENT_ICON,
                    "An icon needs a kind, like \"minecraft:diamond\", or an item: one of the project's own",
                    Some(at.into()),
                ),
                (Some(_), Some(_)) => sink.report(
                    codes::ADVANCEMENT_ICON,
                    "An icon is a game item (kind) or a project item (item), not both",
                    Some(format!("{at}.kind")),
                ),
                (Some(kind), None) if !game::is_id(kind) => {
                    sink.report(codes::ADVANCEMENT_ICON, format!("\"{kind}\" isn't an item id"), Some(format!("{at}.kind")));
                }
                (Some(kind), None) => {
                    if let Some(game) = cx.game
                        && game.lacks(Registry::Item, &game::normalize(kind))
                    {
                        sink.report(
                            codes::ADVANCEMENT_UNKNOWN_ICON,
                            format!("Minecraft {} has no item \"{}\"", game.minecraft_version(), game::normalize(kind)),
                            Some(format!("{at}.kind")),
                        );
                    }
                }
                (None, Some(_)) => {}
            }
            let root = self.parent.is_none();
            match (&display.background, root) {
                (None, true) => sink.report(
                    codes::ADVANCEMENT_BACKGROUND,
                    "A tree's root is its tab: without a background, the tab shows the missing texture",
                    Some("$.display".into()),
                ),
                (Some(_), false) => sink.report(
                    codes::ADVANCEMENT_BACKGROUND,
                    "Only a tree's root (no parent) shows its background",
                    Some("$.display.background".into()),
                ),
                _ => {}
            }
            if let Some(background) = &display.background
                && (!game::is_id(background) || !background.contains(':'))
            {
                sink.report(
                    codes::ADVANCEMENT_BACKGROUND,
                    format!(
                        "\"{background}\" isn't a texture's id, like \"minecraft:block/stone\""
                    ),
                    Some("$.display.background".into()),
                );
            }
        }
        for (name, criterion) in self.criteria.iter() {
            let at = child_path("$.criteria", &name.0);
            match &criterion.trigger {
                None if criterion.conditions.is_some() => sink.report(
                    codes::ADVANCEMENT_TRIGGER,
                    "conditions are a trigger's: without a trigger only a script meets the criterion",
                    Some(format!("{at}.conditions")),
                ),
                None => {}
                Some(trigger) if !game::is_id(trigger) || !trigger.contains(':') => sink.report(
                    codes::ADVANCEMENT_TRIGGER,
                    format!("\"{trigger}\" isn't a trigger's id, like \"minecraft:inventory_changed\""),
                    Some(format!("{at}.trigger")),
                ),
                Some(trigger) => {
                    if let Some(game) = cx.game
                        && game.lacks(Registry::TriggerType, trigger)
                    {
                        sink.report(
                            codes::ADVANCEMENT_UNKNOWN_TRIGGER,
                            format!("Minecraft {} has no trigger \"{trigger}\"", game.minecraft_version()),
                            Some(format!("{at}.trigger")),
                        );
                    }
                }
            }
        }
        if self.criteria.is_empty() {
            sink.report(
                codes::ADVANCEMENT_CRITERIA,
                "An advancement needs at least one criterion, or nothing can complete it",
                Some("$.criteria".into()),
            );
        }
        if let Some(requirements) = &self.requirements {
            let names: BTreeSet<&str> = self.criteria.iter().map(|(k, _)| k.0.as_str()).collect();
            for (index, group) in requirements.iter().enumerate() {
                for (inner, name) in group.0.iter().enumerate() {
                    if !names.contains(name.as_str()) {
                        sink.report(
                            codes::ADVANCEMENT_REQUIREMENTS,
                            format!("There's no criterion \"{name}\" in criteria"),
                            Some(format!("$.requirements[{index}][{inner}]")),
                        );
                    }
                }
            }
            let required: BTreeSet<&str> = requirements
                .iter()
                .flat_map(|g| g.0.iter().map(String::as_str))
                .collect();
            for name in &names {
                if !required.contains(name) {
                    sink.report(
                        codes::ADVANCEMENT_REQUIREMENTS,
                        format!("Criterion \"{name}\" is in no group, and the game refuses an advancement whose requirements leave one out"),
                        Some("$.requirements".into()),
                    );
                }
            }
        }
    }
}

impl AdvancementFile {
    /// Its parents don't lead back to it.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let Some(parent) = &self.parent else { return };
        let own = |file: &AdvancementFile| {
            file.parent.as_deref().and_then(|p| {
                cx.references()
                    .own(crate::reference::RefKind::Advancement, p)
            })
        };
        let Some(start) = own(self) else { return };
        let mut seen = std::collections::BTreeSet::new();
        let mut next = Some(start.clone());
        while let Some(id) = next.filter(|n| seen.insert(n.clone())) {
            if id == cx.id() {
                let through = if start == cx.id() {
                    ""
                } else {
                    " through its parents"
                };
                sink.problems.push(
                    codes::ADVANCEMENT_CYCLE
                        .at(
                            &sink.file,
                            format!("\"{parent}\" leads back to this advancement{through}, so it would hang from itself"),
                            Some("$.parent".into()),
                        )
                        .with_related(vec![crate::Location {
                            file: cx.path_of("advancement", &start),
                            path: None,
                        }]),
                );
                return;
            }
            next = cx.model::<AdvancementFile>(&id).and_then(own);
        }
    }
}
