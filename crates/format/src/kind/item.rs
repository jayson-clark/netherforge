//! `items/<id>/item.json`: a project item, the look of its stacks and its
//! script. Format's `ItemFile`, `ItemKind` and `ItemValidator`; its look's
//! fields are the stack's own (`item_with_look!`), declared once.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Free, Map};
use crate::document::{Context, Document};
use crate::game;
use crate::item::{
    self, AttributeModifierDef, CooldownDef, EnchantmentLevel, EquipmentDef, FoodDef, Hold,
    ItemDef, ItemRarity, UseEffectsDef, item_with_look,
};
use crate::problem::{ProblemSink, codes};
use crate::script::{self, ScriptDef};

item_with_look! {
    /// A project item: how its stacks look and behave, and its script.
    pub struct ItemFile {
        #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
        pub schema: Option<String>,
        ..look,
        /// A bow that draws with no arrows.
        #[serde(skip_serializing_if = "Option::is_none")]
        pub draw_without_ammo: Option<bool>,
        /// A project block it places.
        #[serde(skip_serializing_if = "Option::is_none")]
        pub block: Option<String>,
        /// Values its script reads.
        #[serde(skip_serializing_if = "Option::is_none")]
        pub properties: Option<Map<Free>>,
        #[serde(skip_serializing_if = "Option::is_none")]
        pub script: Option<ScriptDef>,
    }
}

impl Document for ItemFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/item.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    const SCRIPTED: bool = true;

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        // The look is an item like any other, at the same paths: `$.kind`, `$.itemModel`.
        item::check(&self.look(), "$", sink, cx.game);
        if self.draw_without_ammo == Some(true) && game::normalize(&self.kind) != "minecraft:bow" {
            sink.report(
                codes::ITEM_DRAW_WITHOUT_AMMO,
                "Only a bow can draw without ammo",
                Some("$.drawWithoutAmmo".into()),
            );
        }
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
    }
}
