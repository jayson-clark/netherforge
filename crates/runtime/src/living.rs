//! `Living`, `Mob` and `Mannequin` (Kotlin's `LivingImpl`, `MobImpl`, `MannequinImpl`): health, attributes,
//! status effects and equipment; a mob's target and AI; a mannequin's look. Everything answers nil or false
//! while the entity isn't in the world.
//!
//! Not yet: a mob's walking (`move_to`, `stop_pathing`) and goals, with mob goals.

use mlua::Function;
use netherforge_api::api::{LivingApi, MannequinApi, MobApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, shapes, unions, values};
use netherforge_format::canonical::name_of;
use netherforge_format::game::{self, Registry};
use netherforge_format::item::AttributeOperation;
use netherforge_platform::{
    AttributeModifierData, EntityFlag, EntityNumber, MannequinLook, MannequinPose, MannequinSkin,
    MannequinSkinDefault, MannequinSkinPlayer, MannequinSkinTexture, SkinPart, SkinTexture,
    StatusEffectData, Uuid,
};

use crate::Target;
use crate::events::parse_uuid;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

const OPERATIONS: [AttributeOperation; 3] = [
    AttributeOperation::AddValue,
    AttributeOperation::AddMultipliedBase,
    AttributeOperation::AddMultipliedTotal,
];

impl GameContext {
    pub(crate) fn subscribe_on(
        &self,
        handle: Handle,
        called: &str,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
        once: bool,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope(called)?;
        let target = netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .map(Target::Handle)
            .map_err(|e| err(e.to_string()))?;
        self.scripts
            .subscribe(scope, target, event, handler, options, once)
    }

    /// An attribute's namespaced id, unless the server is known not to have it.
    fn attribute_id(&self, attribute: &str) -> ApiResult<String> {
        let id = game::normalize(attribute);
        if !game::is_id(attribute) || self.shared.platform.game().lacks(Registry::Attribute, &id) {
            return Err(err(format!("no attribute \"{attribute}\" on this server")));
        }
        Ok(id)
    }

    /// An id a script gave, in the project's namespace (`slow_zone` is `shop:slow_zone`), so it can't replace
    /// or remove anything else's; another namespace's is the script's mistake (Kotlin's `ownId`).
    pub(crate) fn own_id(&self, id: &str, what: &str) -> ApiResult<String> {
        let namespace = self.shared.centities.borrow().home().to_owned();
        let (given, name) = id.split_once(':').unwrap_or((namespace.as_str(), id));
        if given != namespace {
            return Err(err(format!(
                "\"{id}\" isn't one of the project's {what} ids: \"<name>\" or \"{namespace}:<name>\""
            )));
        }
        Ok(format!("{namespace}:{name}"))
    }

    /// A status effect's namespaced id, once the server is known to have it.
    fn effect_id(&self, effect: &str) -> ApiResult<String> {
        let id = game::normalize(effect);
        if !game::is_id(effect)
            || !self
                .shared
                .platform
                .world_entities()
                .effect_exists(id.clone())
        {
            return Err(err(format!("no status effect \"{effect}\" on this server")));
        }
        Ok(id)
    }

    fn living_entity(this: &handles::Living) -> handles::Entity {
        handles::Entity {
            id: this.id.clone(),
        }
    }
}

impl LivingApi for GameContext {
    fn health(&self, this: &handles::Living) -> ApiResult<Option<f64>> {
        Ok(self.number(&Self::living_entity(this), EntityNumber::Health))
    }

    fn set_health(&self, this: &handles::Living, health: f64) -> ApiResult<bool> {
        let entities = self.shared.platform.world_entities();
        let Some(id) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        let Some(max) = entities.number(id, EntityNumber::MaxHealth) else {
            return Ok(false);
        };
        if health.is_nan() || health < 0.0 || health > max {
            return Err(err(format!(
                "health must be from 0 to max_health() ({max}), not {health}"
            )));
        }
        Ok(entities.set_number(id, EntityNumber::Health, health))
    }

    fn max_health(&self, this: &handles::Living) -> ApiResult<Option<f64>> {
        Ok(self.number(&Self::living_entity(this), EntityNumber::MaxHealth))
    }

    fn damage(
        &self,
        this: &handles::Living,
        amount: f64,
        source: Option<handles::Entity>,
    ) -> ApiResult<bool> {
        if amount < 0.0 || !amount.is_finite() {
            return Err(err("amount can't be below 0"));
        }
        let entities = self.shared.platform.world_entities();
        let Some(id) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        if !entities
            .info(id)
            .is_some_and(|i| crate::entity::living(i.category) || i.player.is_some())
        {
            return Ok(false);
        }
        Ok(entities.damage(id, amount, source.and_then(|s| parse_uuid(&s.id))))
    }

    fn heal(&self, this: &handles::Living, amount: f64) -> ApiResult<bool> {
        if amount < 0.0 || !amount.is_finite() {
            return Err(err("amount can't be below 0"));
        }
        let entities = self.shared.platform.world_entities();
        let Some(id) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        let (Some(health), Some(max)) = (
            entities.number(id, EntityNumber::Health),
            entities.number(id, EntityNumber::MaxHealth),
        ) else {
            return Ok(false);
        };
        Ok(entities.set_number(id, EntityNumber::Health, max.min(health + amount)))
    }

    fn attribute(&self, this: &handles::Living, attribute: String) -> ApiResult<Option<f64>> {
        let id = self.attribute_id(&attribute)?;
        Ok(parse_uuid(&this.id).and_then(|e| self.shared.platform.attributes().value(e, id)))
    }

    fn attribute_base(&self, this: &handles::Living, attribute: String) -> ApiResult<Option<f64>> {
        let id = self.attribute_id(&attribute)?;
        Ok(parse_uuid(&this.id).and_then(|e| self.shared.platform.attributes().base(e, id)))
    }

    fn set_attribute_base(
        &self,
        this: &handles::Living,
        attribute: String,
        value: f64,
    ) -> ApiResult<bool> {
        let id = self.attribute_id(&attribute)?;
        if !value.is_finite() {
            return Err(err(format!("value must be a number, not {value}")));
        }
        Ok(parse_uuid(&this.id)
            .is_some_and(|e| self.shared.platform.attributes().set_base(e, id, value)))
    }

    fn attribute_modifiers(
        &self,
        this: &handles::Living,
        attribute: String,
    ) -> ApiResult<Option<Vec<shapes::AttributeModifier>>> {
        let id = self.attribute_id(&attribute)?;
        let Some(mut modifiers) =
            parse_uuid(&this.id).and_then(|e| self.shared.platform.attributes().modifiers(e, id))
        else {
            return Ok(None);
        };
        modifiers.sort_by(|a, b| a.id.cmp(&b.id));
        Ok(Some(
            modifiers
                .into_iter()
                .map(|m| shapes::AttributeModifier {
                    id: m.id,
                    amount: m.amount,
                    operation: name_of(&m.operation),
                })
                .collect(),
        ))
    }

    fn add_attribute_modifier(
        &self,
        this: &handles::Living,
        attribute: String,
        id: String,
        amount: f64,
        operation: Option<String>,
        saved: Option<bool>,
    ) -> ApiResult<bool> {
        let attribute = self.attribute_id(&attribute)?;
        if !game::is_id(&id) {
            return Err(err(format!(
                "\"{id}\" isn't a modifier id: lowercase letters, digits, _, -, . and /"
            )));
        }
        let modifier = self.own_id(&id, "modifier")?;
        if !amount.is_finite() {
            return Err(err(format!("amount must be a number, not {amount}")));
        }
        let operation = match operation {
            Some(name) => *OPERATIONS
                .iter()
                .find(|o| name_of(*o) == name)
                .expect("the binding checked the choice"),
            None => AttributeOperation::AddValue,
        };
        let Some(entity) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        Ok(self.shared.platform.attributes().add_modifier(
            entity,
            attribute,
            AttributeModifierData {
                id: modifier,
                amount,
                operation,
                saved: saved.unwrap_or(true),
            },
        ))
    }

    fn remove_attribute_modifier(
        &self,
        this: &handles::Living,
        attribute: String,
        id: String,
    ) -> ApiResult<bool> {
        let attribute = self.attribute_id(&attribute)?;
        if !game::is_id(&id) {
            return Err(err(format!(
                "\"{id}\" isn't a modifier id: lowercase letters, digits, _, -, . and /"
            )));
        }
        let modifier = self.own_id(&id, "modifier")?;
        Ok(parse_uuid(&this.id).is_some_and(|e| {
            self.shared
                .platform
                .attributes()
                .remove_modifier(e, attribute, modifier)
        }))
    }

    fn effects(&self, this: &handles::Living) -> ApiResult<Vec<shapes::StatusEffect>> {
        Ok(parse_uuid(&this.id)
            .and_then(|e| self.shared.platform.world_entities().effects(e))
            .unwrap_or_default()
            .into_iter()
            .map(|e| shapes::StatusEffect {
                effect: e.effect,
                ticks: i64::from(e.ticks),
                amplifier: i64::from(e.amplifier),
                ambient: e.ambient,
                particles: e.particles,
                icon: e.icon,
            })
            .collect())
    }

    fn has_effect(&self, this: &handles::Living, effect: String) -> ApiResult<bool> {
        let id = self.effect_id(&effect)?;
        Ok(parse_uuid(&this.id)
            .and_then(|e| self.shared.platform.world_entities().effects(e))
            .is_some_and(|all| all.iter().any(|e| e.effect == id)))
    }

    fn add_effect(
        &self,
        this: &handles::Living,
        effect: String,
        ticks: i64,
        options: Option<shapes::EffectOptions>,
    ) -> ApiResult<bool> {
        let id = self.effect_id(&effect)?;
        if ticks < -1 || ticks > i64::from(i32::MAX) {
            return Err(err(format!(
                "ticks must be at least 0 (or -1 for ever), not {ticks}"
            )));
        }
        let amplifier = options.as_ref().and_then(|o| o.amplifier).unwrap_or(0);
        if !(0..=255).contains(&amplifier) {
            return Err(err(format!(
                "options.amplifier must be from 0 to 255, not {amplifier}"
            )));
        }
        let Some(entity) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        let option = |f: fn(&shapes::EffectOptions) -> Option<bool>| options.as_ref().and_then(f);
        Ok(self.shared.platform.world_entities().add_effect(
            entity,
            StatusEffectData {
                effect: id,
                ticks: ticks as i32,
                amplifier: amplifier as i32,
                ambient: option(|o| o.ambient) == Some(true),
                particles: option(|o| o.particles) != Some(false),
                icon: option(|o| o.icon) != Some(false),
            },
        ))
    }

    fn remove_effect(&self, this: &handles::Living, effect: String) -> ApiResult<bool> {
        let id = self.effect_id(&effect)?;
        Ok(parse_uuid(&this.id)
            .is_some_and(|e| self.shared.platform.world_entities().remove_effect(e, id)))
    }

    fn equipment(&self, this: &handles::Living, slot: String) -> ApiResult<Option<values::Item>> {
        Ok(parse_uuid(&this.id)
            .and_then(|e| self.shared.platform.world_entities().equipment(e, slot))
            .map(|item| crate::items::to_value(&item)))
    }

    fn set_equipment(
        &self,
        this: &handles::Living,
        slot: String,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = match item {
            Some(item) => Some(
                crate::items::from_value(&item, "item", self.shared.platform.game())
                    .map_err(err)?,
            ),
            None => None,
        };
        Ok(parse_uuid(&this.id).is_some_and(|e| {
            self.shared
                .platform
                .world_entities()
                .set_equipment(e, slot, item)
        }))
    }

    fn on(
        &self,
        this: &handles::Living,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Living(this.clone()),
            "Living:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Living,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Living(this.clone()),
            "Living:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl MobApi for GameContext {
    fn target(&self, this: &handles::Mob) -> ApiResult<Option<handles::Entity>> {
        let lua = self.scripts.host().lua().clone();
        Ok(parse_uuid(&this.id)
            .and_then(|id| self.shared.platform.world_entities().target(id))
            .map(|id| self.shared.entity_by_id(&lua, id)))
    }

    fn set_target(&self, this: &handles::Mob, entity: Option<handles::Entity>) -> ApiResult<bool> {
        let Some(id) = parse_uuid(&this.id) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .world_entities()
            .set_target(id, entity.and_then(|e| parse_uuid(&e.id))))
    }

    fn has_ai(&self, this: &handles::Mob) -> ApiResult<bool> {
        Ok(self.flag(
            &handles::Entity {
                id: this.id.clone(),
            },
            EntityFlag::Ai,
        ))
    }

    fn set_ai(&self, this: &handles::Mob, ai: bool) -> ApiResult<bool> {
        Ok(self.set_flag(
            &handles::Entity {
                id: this.id.clone(),
            },
            EntityFlag::Ai,
            ai,
        ))
    }

    fn move_to(
        &self,
        this: &handles::Mob,
        target: unions::LocationOrVec3OrEntity,
        options: Option<shapes::PathOptions>,
    ) -> ApiResult<bool> {
        <Unported as MobApi>::move_to(&Unported, this, target, options)
    }

    fn stop_pathing(&self, this: &handles::Mob) -> ApiResult<bool> {
        <Unported as MobApi>::stop_pathing(&Unported, this)
    }

    fn has_path(&self, this: &handles::Mob) -> ApiResult<bool> {
        Ok(parse_uuid(&this.id).is_some_and(|id| self.shared.platform.pathfinding().has_path(id)))
    }

    fn path_target(&self, this: &handles::Mob) -> ApiResult<Option<values::Vec3>> {
        Ok(parse_uuid(&this.id)
            .and_then(|id| self.shared.platform.pathfinding().path_end(id))
            .map(|v| values::Vec3 {
                x: v.0,
                y: v.1,
                z: v.2,
            }))
    }

    fn goals(&self, this: &handles::Mob) -> ApiResult<Option<Vec<shapes::MobGoal>>> {
        <Unported as MobApi>::goals(&Unported, this)
    }

    fn remove_goal(&self, this: &handles::Mob, key: String) -> ApiResult<bool> {
        <Unported as MobApi>::remove_goal(&Unported, this, key)
    }

    fn clear_goals(
        &self,
        this: &handles::Mob,
        options: Option<shapes::GoalClearOptions>,
    ) -> ApiResult<bool> {
        <Unported as MobApi>::clear_goals(&Unported, this, options)
    }

    fn add_goal(
        &self,
        this: &handles::Mob,
        id: String,
        definition: shapes::GoalDefinition,
    ) -> ApiResult<bool> {
        <Unported as MobApi>::add_goal(&Unported, this, id, definition)
    }

    fn on(
        &self,
        this: &handles::Mob,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Mob(this.clone()),
            "Mob:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Mob,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Mob(this.clone()),
            "Mob:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// A player's name, as the server looks one up: 3 to 16 letters, digits or `_`.
fn player_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

impl GameContext {
    fn look(&self, this: &handles::Mannequin) -> Option<MannequinLook> {
        self.shared
            .platform
            .mannequins()
            .look(parse_uuid(&this.id)?)
    }

    fn change(&self, this: &handles::Mannequin, change: impl FnOnce(&mut MannequinLook)) -> bool {
        let mannequins = self.shared.platform.mannequins();
        let Some(id): Option<Uuid> = parse_uuid(&this.id) else {
            return false;
        };
        let Some(mut look) = mannequins.look(id) else {
            return false;
        };
        change(&mut look);
        mannequins.set_look(id, look)
    }
}

impl MannequinApi for GameContext {
    fn skin(&self, this: &handles::Mannequin) -> ApiResult<Option<unions::StringOrSkin>> {
        Ok(self.look(this).and_then(|look| match look.skin {
            MannequinSkin::Default(_) => None,
            MannequinSkin::Player(p) => Some(unions::StringOrSkin::String(p.name)),
            MannequinSkin::Texture(t) => Some(unions::StringOrSkin::Skin(shapes::Skin {
                texture: t.texture.value,
                signature: t.texture.signature,
            })),
        }))
    }

    fn set_skin(
        &self,
        this: &handles::Mannequin,
        skin: Option<unions::StringOrSkin>,
    ) -> ApiResult<bool> {
        let chosen = match skin {
            None => MannequinSkin::Default(MannequinSkinDefault {}),
            Some(unions::StringOrSkin::String(name)) => {
                if !player_name(&name) {
                    return Err(err(format!(
                        "bad argument 'skin' (a player's name, 3 to 16 letters, digits or _, expected, got \"{name}\")"
                    )));
                }
                MannequinSkin::Player(MannequinSkinPlayer { name })
            }
            Some(unions::StringOrSkin::Skin(skin)) => {
                MannequinSkin::Texture(MannequinSkinTexture {
                    texture: SkinTexture {
                        value: skin.texture,
                        signature: skin.signature,
                    },
                })
            }
        };
        Ok(self.change(this, |look| look.skin = chosen))
    }

    fn description(&self, this: &handles::Mannequin) -> ApiResult<Option<values::Text>> {
        Ok(self
            .look(this)
            .and_then(|l| l.description)
            .map(values::Text))
    }

    fn set_description(
        &self,
        this: &handles::Mannequin,
        text: Option<values::Text>,
    ) -> ApiResult<bool> {
        Ok(self.change(this, |look| look.description = text.map(|t| t.0)))
    }

    fn is_immovable(&self, this: &handles::Mannequin) -> ApiResult<bool> {
        Ok(self.look(this).is_some_and(|l| l.immovable))
    }

    fn set_immovable(&self, this: &handles::Mannequin, immovable: bool) -> ApiResult<bool> {
        Ok(self.change(this, |look| look.immovable = immovable))
    }

    fn main_hand(&self, this: &handles::Mannequin) -> ApiResult<Option<String>> {
        Ok(self
            .look(this)
            .map(|l| if l.left_handed { "left" } else { "right" }.to_owned()))
    }

    fn set_main_hand(&self, this: &handles::Mannequin, side: String) -> ApiResult<bool> {
        Ok(self.change(this, |look| look.left_handed = side == "left"))
    }

    fn pose(&self, this: &handles::Mannequin) -> ApiResult<Option<String>> {
        Ok(self.look(this).map(|l| l.pose.lua_name().to_owned()))
    }

    fn set_pose(&self, this: &handles::Mannequin, pose: String) -> ApiResult<bool> {
        let pose = *MannequinPose::ALL
            .iter()
            .find(|p| p.lua_name() == pose)
            .expect("the binding checked the choice");
        Ok(self.change(this, |look| look.pose = pose))
    }

    fn skin_parts(&self, this: &handles::Mannequin) -> ApiResult<Option<shapes::SkinParts>> {
        Ok(self.look(this).map(|look| {
            let shown = |part| Some(look.skin_parts.contains(&part));
            shapes::SkinParts {
                cape: shown(SkinPart::Cape),
                jacket: shown(SkinPart::Jacket),
                left_sleeve: shown(SkinPart::LeftSleeve),
                right_sleeve: shown(SkinPart::RightSleeve),
                left_pants: shown(SkinPart::LeftPants),
                right_pants: shown(SkinPart::RightPants),
                hat: shown(SkinPart::Hat),
            }
        }))
    }

    fn set_skin_parts(
        &self,
        this: &handles::Mannequin,
        parts: shapes::SkinParts,
    ) -> ApiResult<bool> {
        Ok(self.change(this, |look| {
            let given = |part: SkinPart| match part {
                SkinPart::Cape => parts.cape,
                SkinPart::Jacket => parts.jacket,
                SkinPart::LeftSleeve => parts.left_sleeve,
                SkinPart::RightSleeve => parts.right_sleeve,
                SkinPart::LeftPants => parts.left_pants,
                SkinPart::RightPants => parts.right_pants,
                SkinPart::Hat => parts.hat,
            };
            look.skin_parts = SkinPart::ALL
                .iter()
                .copied()
                .filter(|p| given(*p).unwrap_or(look.skin_parts.contains(p)))
                .collect();
        }))
    }
}
