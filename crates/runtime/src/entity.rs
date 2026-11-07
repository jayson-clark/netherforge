//! `Entity` and `DroppedItem` (Kotlin's `EntityImpl`, `DroppedItemImpl`): a vanilla entity by UUID, read live
//! through the Platform. Everything answers nil or false while it isn't in the world (gone, or its chunk
//! unloaded); mistakes (an unknown tag, a value out of range) are errors.
//!
//! The entities service keeps what the server forgets (Kotlin's `HiddenEntities` and `EntityData`): which
//! players each entity is hidden from, applied again when they join and when the entity loads; and the
//! `entity:data()` tables, read from the entity the first time a script asks and written back when its chunk
//! unloads, a world saves and the session ends, only when changed.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use mlua::{Function, Value};
use netherforge_api::api::{DroppedItemApi, EntityApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, shapes, unions, values};
use netherforge_format::math::Vec3;
use netherforge_platform::{
    EntityCategory, EntityFlag, EntityInfo, EntityNumber, EntityTag, InventoryRef,
    InventoryRefEntity, InventoryRefPlayer, Location, PlayerRef, Uuid,
};

use crate::events::{inventory_handle, parse_uuid, uuid_text};
use crate::session::Service;
use crate::shared::{DataTable, Shared};
use crate::world::GameContext;
use crate::{Room, Target};

/// How far the `target_*` raycasts reach when the script doesn't say: `TARGET_DISTANCE` in `spec/constants.ts`.
pub const TARGET_DISTANCE: f64 = 20.0;

/// The most text a saved table may take.
const MAX_DATA_BYTES: usize = 1024 * 1024;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

fn vec(v: &values::Vec3) -> Vec3 {
    Vec3(v.x, v.y, v.z)
}

fn value(v: Vec3) -> values::Vec3 {
    values::Vec3 {
        x: v.0,
        y: v.1,
        z: v.2,
    }
}

/// A tag scripts may give: letters, digits, `_`, `-`, `.` and `+`.
fn check_tag(tag: &str) -> ApiResult<()> {
    if tag.is_empty()
        || !tag
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_.+-".contains(c))
    {
        return Err(err(format!(
            "\"{tag}\" isn't a tag: letters, digits, _, -, . and +"
        )));
    }
    Ok(())
}

/// The unit direction a yaw and pitch face, in Minecraft's convention (`vec3.from_yaw_pitch`'s maths).
pub fn direction_of(yaw: f64, pitch: f64) -> Vec3 {
    let (y, p) = (yaw.to_radians(), pitch.to_radians());
    Vec3(-y.sin() * p.cos(), -p.sin(), y.cos() * p.cos())
}

fn location_value(l: &Location) -> values::Location {
    values::Location {
        world: handles::World {
            name: l.world.clone(),
        },
        position: values::Vec3 {
            x: l.x,
            y: l.y,
            z: l.z,
        },
        yaw: Some(l.yaw),
        pitch: Some(l.pitch),
    }
}

/// What the entities service keeps.
#[derive(Default)]
pub struct EntityState {
    /// The players each entity is hidden from.
    pub hidden: HashMap<Uuid, HashSet<Uuid>>,
    /// The `entity:data()` tables scripts asked for.
    pub data: HashMap<Uuid, DataTable>,
}

impl GameContext {
    fn uuid(this: &handles::Entity) -> Option<Uuid> {
        parse_uuid(&this.id)
    }

    fn info(&self, this: &handles::Entity) -> Option<EntityInfo> {
        self.shared
            .platform
            .world_entities()
            .info(Self::uuid(this)?)
    }

    fn here(&self, this: &handles::Entity) -> Option<Location> {
        self.info(this).map(|i| i.location)
    }

    pub(crate) fn flag(&self, this: &handles::Entity, flag: EntityFlag) -> bool {
        Self::uuid(this).and_then(|id| self.shared.platform.world_entities().flag(id, flag))
            == Some(true)
    }

    pub(crate) fn set_flag(&self, this: &handles::Entity, flag: EntityFlag, value: bool) -> bool {
        Self::uuid(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_flag(id, flag, value)
        })
    }

    pub(crate) fn number(&self, this: &handles::Entity, number: EntityNumber) -> Option<f64> {
        self.shared
            .platform
            .world_entities()
            .number(Self::uuid(this)?, number)
    }

    /// The class the handle's value is, made as specific as the server can say first.
    fn class_of(&self, this: &handles::Entity) -> Option<&'static str> {
        let lua = self.scripts.host().lua().clone();
        if let Some(id) = Self::uuid(this) {
            self.shared.entity_by_id(&lua, id);
        }
        let pushed = netherforge_api::handle::push_any(&lua, &Handle::Entity(this.clone())).ok()?;
        netherforge_api::handle::class_of(&pushed)
    }

    fn is_class(&self, this: &handles::Entity, class: &str) -> bool {
        self.class_of(this)
            .is_some_and(|c| netherforge_api::handle::is_a(c, class))
    }

    /// A ray from the eyes along where it looks, or none while it isn't in the world.
    fn sight(
        &self,
        this: &handles::Entity,
        max_distance: Option<f64>,
    ) -> ApiResult<Option<(String, Vec3, Vec3, f64)>> {
        let distance = max_distance.unwrap_or(TARGET_DISTANCE);
        if distance < 0.0 || !distance.is_finite() {
            return Err(err("max_distance can't be below 0"));
        }
        let Some(eye) =
            Self::uuid(this).and_then(|id| self.shared.platform.world_entities().eye(id))
        else {
            return Ok(None);
        };
        Ok(Some((
            eye.world,
            Vec3(eye.x, eye.y, eye.z),
            Vec3(eye.dx, eye.dy, eye.dz),
            distance,
        )))
    }

    fn raycast_from(
        &self,
        this: &handles::Entity,
        max_distance: Option<f64>,
        options: shapes::RaycastOptions,
    ) -> ApiResult<Option<shapes::RaycastHit>> {
        use netherforge_api::api::WorldApi;
        let Some((world, from, direction, distance)) = self.sight(this, max_distance)? else {
            return Ok(None);
        };
        WorldApi::raycast(
            self,
            &handles::World { name: world },
            value(from),
            value(direction),
            distance,
            Some(options),
        )
    }

    /// An online player's id, for hiding from them.
    fn online(&self, player: &handles::Player) -> Option<Uuid> {
        parse_uuid(&player.id).filter(|id| self.shared.platform.players().get(*id).is_some())
    }
}

impl EntityApi for GameContext {
    fn id(&self, this: &handles::Entity) -> ApiResult<String> {
        Ok(this.id.clone())
    }

    fn kind(&self, this: &handles::Entity) -> ApiResult<Option<String>> {
        Ok(self.info(this).map(|i| i.kind))
    }

    fn exists(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.info(this).is_some())
    }

    fn is_player(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.is_class(this, "Player"))
    }

    fn is_living(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.is_class(this, "Living"))
    }

    fn is_mob(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.is_class(this, "Mob"))
    }

    fn location(&self, this: &handles::Entity) -> ApiResult<Option<values::Location>> {
        Ok(self.here(this).map(|l| location_value(&l)))
    }

    fn position(&self, this: &handles::Entity) -> ApiResult<Option<values::Vec3>> {
        Ok(self.here(this).map(|l| values::Vec3 {
            x: l.x,
            y: l.y,
            z: l.z,
        }))
    }

    fn world(&self, this: &handles::Entity) -> ApiResult<Option<handles::World>> {
        Ok(self.here(this).map(|l| handles::World { name: l.world }))
    }

    fn eye_position(&self, this: &handles::Entity) -> ApiResult<Option<values::Vec3>> {
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().eye(id))
            .map(|e| values::Vec3 {
                x: e.x,
                y: e.y,
                z: e.z,
            }))
    }

    fn yaw(&self, this: &handles::Entity) -> ApiResult<Option<f64>> {
        Ok(self.here(this).map(|l| l.yaw))
    }

    fn pitch(&self, this: &handles::Entity) -> ApiResult<Option<f64>> {
        Ok(self.here(this).map(|l| l.pitch))
    }

    fn direction(&self, this: &handles::Entity) -> ApiResult<Option<values::Vec3>> {
        Ok(self.here(this).map(|l| value(direction_of(l.yaw, l.pitch))))
    }

    fn set_yaw(&self, this: &handles::Entity, degrees: f64) -> ApiResult<bool> {
        let (Some(id), Some(at)) = (Self::uuid(this), self.here(this)) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .world_entities()
            .set_rotation(id, degrees, at.pitch))
    }

    fn set_pitch(&self, this: &handles::Entity, degrees: f64) -> ApiResult<bool> {
        let (Some(id), Some(at)) = (Self::uuid(this), self.here(this)) else {
            return Ok(false);
        };
        Ok(self.shared.platform.world_entities().set_rotation(
            id,
            at.yaw,
            degrees.clamp(-90.0, 90.0),
        ))
    }

    fn look_at(&self, this: &handles::Entity, point: values::Vec3) -> ApiResult<bool> {
        let entities = self.shared.platform.world_entities();
        let Some(id) = Self::uuid(this) else {
            return Ok(false);
        };
        let Some(eye) = entities.eye(id) else {
            return Ok(false);
        };
        let (dx, dy, dz) = (point.x - eye.x, point.y - eye.y, point.z - eye.z);
        let flat = (dx * dx + dz * dz).sqrt();
        if flat == 0.0 && dy == 0.0 {
            return Ok(true);
        }
        // Minecraft's angles: yaw 0 faces +z and grows towards -x; pitch 90 faces down.
        let yaw = (-dx).atan2(dz).to_degrees();
        let pitch = (-dy.atan2(flat)).to_degrees();
        Ok(entities.set_rotation(id, yaw, pitch))
    }

    fn teleport(
        &self,
        this: &handles::Entity,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let entities = self.shared.platform.world_entities();
        let (Some(id), Some(at)) = (Self::uuid(this), self.here(this)) else {
            return Ok(false);
        };
        let to = match location_or_position {
            unions::LocationOrVec3::Location(l) => Location {
                world: l.world.name,
                x: l.position.x,
                y: l.position.y,
                z: l.position.z,
                yaw: l.yaw.unwrap_or(at.yaw),
                pitch: l.pitch.unwrap_or(at.pitch),
            },
            unions::LocationOrVec3::Vec3(v) => Location {
                x: v.x,
                y: v.y,
                z: v.z,
                ..at
            },
        };
        Ok(entities.teleport(id, to))
    }

    fn velocity(&self, this: &handles::Entity) -> ApiResult<Option<values::Vec3>> {
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().velocity(id))
            .map(value))
    }

    fn set_velocity(&self, this: &handles::Entity, velocity: values::Vec3) -> ApiResult<bool> {
        Ok(Self::uuid(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_velocity(id, vec(&velocity))
        }))
    }

    fn add_velocity(&self, this: &handles::Entity, velocity: values::Vec3) -> ApiResult<bool> {
        let entities = self.shared.platform.world_entities();
        let Some(id) = Self::uuid(this) else {
            return Ok(false);
        };
        let Some(now) = entities.velocity(id) else {
            return Ok(false);
        };
        Ok(entities.set_velocity(id, now + vec(&velocity)))
    }

    fn is_on_ground(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::OnGround))
    }

    /// A player can't be removed; anything else goes, and what's kept for it with it.
    fn remove(&self, this: &handles::Entity) -> ApiResult<bool> {
        let Some(id) = Self::uuid(this) else {
            return Ok(false);
        };
        if self.shared.platform.players().get(id).is_some()
            || !self.shared.platform.world_entities().remove(id)
        {
            return Ok(false);
        }
        self.shared.gone.borrow_mut().push(id);
        Ok(true)
    }

    fn name(&self, this: &handles::Entity) -> ApiResult<Option<values::Text>> {
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().name(id))
            .map(values::Text))
    }

    fn custom_name(&self, this: &handles::Entity) -> ApiResult<Option<values::Text>> {
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().custom_name(id))
            .map(values::Text))
    }

    fn set_custom_name(
        &self,
        this: &handles::Entity,
        text: Option<values::Text>,
    ) -> ApiResult<bool> {
        Ok(Self::uuid(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_custom_name(id, text.map(|t| t.0))
        }))
    }

    fn is_custom_name_visible(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::CustomNameVisible))
    }

    fn set_custom_name_visible(&self, this: &handles::Entity, visible: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::CustomNameVisible, visible))
    }

    fn tags(&self, this: &handles::Entity) -> ApiResult<Vec<String>> {
        let mut tags = self.info(this).map(|i| i.tags).unwrap_or_default();
        tags.sort();
        Ok(tags)
    }

    fn has_tag(&self, this: &handles::Entity, tag: String) -> ApiResult<bool> {
        Ok(self.info(this).is_some_and(|i| i.tags.contains(&tag)))
    }

    fn add_tag(&self, this: &handles::Entity, tag: String) -> ApiResult<bool> {
        check_tag(&tag)?;
        Ok(Self::uuid(this)
            .is_some_and(|id| self.shared.platform.world_entities().add_tag(id, tag)))
    }

    fn remove_tag(&self, this: &handles::Entity, tag: String) -> ApiResult<bool> {
        Ok(Self::uuid(this)
            .is_some_and(|id| self.shared.platform.world_entities().remove_tag(id, tag)))
    }

    /// The entity's table, the same to every script; nil when it isn't in the world (and wasn't read before).
    fn data(&self, this: &handles::Entity) -> ApiResult<Value> {
        let Some(id) = Self::uuid(this) else {
            return Ok(Value::Nil);
        };
        if let Some(kept) = self.shared.entities.borrow().data.get(&id) {
            return Ok(Value::Table(kept.table.clone()));
        }
        let entities = self.shared.platform.world_entities();
        if entities.info(id).is_none() {
            return Ok(Value::Nil);
        }
        let host = self.scripts.host();
        let lua = host.lua();
        let saved = entities.data(id);
        let table = match saved
            .as_deref()
            .map(|t| netherforge_luau::json::decode(lua, t, &netherforge_api::handle::SavedHandles))
        {
            Some(Ok(Value::Table(t))) => t,
            _ => lua.create_table().map_err(|e| err(e.to_string()))?,
        };
        self.shared.entities.borrow_mut().data.insert(
            id,
            DataTable {
                table: table.clone(),
                saved,
            },
        );
        Ok(Value::Table(table))
    }

    fn is_glowing(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::Glowing))
    }

    fn set_glowing(&self, this: &handles::Entity, glowing: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::Glowing, glowing))
    }

    fn is_visible(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::Visible))
    }

    fn set_visible(&self, this: &handles::Entity, visible: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::Visible, visible))
    }

    fn hide_from(&self, this: &handles::Entity, player: handles::Player) -> ApiResult<bool> {
        let (Some(id), Some(viewer)) = (self.info(this).map(|i| i.id), self.online(&player)) else {
            return Ok(false);
        };
        if !self.shared.platform.world_entities().hide(viewer, id) {
            return Ok(false);
        }
        self.shared
            .entities
            .borrow_mut()
            .hidden
            .entry(id)
            .or_default()
            .insert(viewer);
        Ok(true)
    }

    fn show_to(&self, this: &handles::Entity, player: handles::Player) -> ApiResult<bool> {
        let (Some(id), Some(viewer)) = (self.info(this).map(|i| i.id), self.online(&player)) else {
            return Ok(false);
        };
        if !self.shared.platform.world_entities().show(viewer, id) {
            return Ok(false);
        }
        let mut state = self.shared.entities.borrow_mut();
        if let Some(players) = state.hidden.get_mut(&id) {
            players.remove(&viewer);
            if players.is_empty() {
                state.hidden.remove(&id);
            }
        }
        Ok(true)
    }

    fn is_hidden_from(&self, this: &handles::Entity, player: handles::Player) -> ApiResult<bool> {
        let (Some(id), Some(viewer)) = (Self::uuid(this), parse_uuid(&player.id)) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .entities
            .borrow()
            .hidden
            .get(&id)
            .is_some_and(|p| p.contains(&viewer)))
    }

    fn team(&self, this: &handles::Entity) -> ApiResult<Option<handles::Team>> {
        Ok(self.team_of(this))
    }

    fn is_silent(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::Silent))
    }

    fn set_silent(&self, this: &handles::Entity, silent: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::Silent, silent))
    }

    fn has_gravity(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::Gravity))
    }

    fn set_gravity(&self, this: &handles::Entity, gravity: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::Gravity, gravity))
    }

    fn is_invulnerable(&self, this: &handles::Entity) -> ApiResult<bool> {
        Ok(self.flag(this, EntityFlag::Invulnerable))
    }

    fn set_invulnerable(&self, this: &handles::Entity, invulnerable: bool) -> ApiResult<bool> {
        Ok(self.set_flag(this, EntityFlag::Invulnerable, invulnerable))
    }

    fn passengers(&self, this: &handles::Entity) -> ApiResult<Vec<handles::Entity>> {
        let lua = self.scripts.host().lua().clone();
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().passengers(id))
            .unwrap_or_default()
            .into_iter()
            .map(|id| self.shared.entity_by_id(&lua, id))
            .collect())
    }

    fn add_passenger(&self, this: &handles::Entity, entity: handles::Entity) -> ApiResult<bool> {
        let (Some(id), Some(rider)) = (Self::uuid(this), Self::uuid(&entity)) else {
            return Ok(false);
        };
        Ok(id != rider
            && self
                .shared
                .platform
                .world_entities()
                .add_passenger(id, rider))
    }

    fn remove_passenger(&self, this: &handles::Entity, entity: handles::Entity) -> ApiResult<bool> {
        let (Some(id), Some(rider)) = (Self::uuid(this), Self::uuid(&entity)) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .world_entities()
            .remove_passenger(id, rider))
    }

    fn vehicle(&self, this: &handles::Entity) -> ApiResult<Option<handles::Entity>> {
        let lua = self.scripts.host().lua().clone();
        Ok(Self::uuid(this)
            .and_then(|id| self.shared.platform.world_entities().vehicle(id))
            .map(|id| self.shared.entity_by_id(&lua, id)))
    }

    fn inventory(&self, this: &handles::Entity) -> ApiResult<Option<handles::Inventory>> {
        let Some(info) = self.info(this) else {
            return Ok(None);
        };
        let r#ref = if info.player.is_some() {
            InventoryRef::Player(InventoryRefPlayer { player: info.id })
        } else {
            InventoryRef::Entity(InventoryRefEntity { entity: info.id })
        };
        Ok(self
            .shared
            .platform
            .inventories()
            .kind(r#ref.clone())
            .map(|_| inventory_handle(&r#ref)))
    }

    fn target_block(
        &self,
        this: &handles::Entity,
        max_distance: Option<f64>,
    ) -> ApiResult<Option<handles::Block>> {
        let Some((world, from, direction, distance)) = self.sight(this, max_distance)? else {
            return Ok(None);
        };
        Ok(self
            .shared
            .platform
            .worlds()
            .raycast_blocks(world.clone(), from, direction, distance, false)
            .map(|hit| handles::Block {
                world,
                position: crate::events::pack(hit.x, hit.y, hit.z),
            }))
    }

    fn target_entity(
        &self,
        this: &handles::Entity,
        max_distance: Option<f64>,
    ) -> ApiResult<Option<handles::Entity>> {
        let options = shapes::RaycastOptions {
            blocks: None,
            entities: None,
            ignore: Some(vec![this.clone()]),
            centities: Some(false),
            fluids: None,
        };
        Ok(self
            .raycast_from(this, max_distance, options)?
            .and_then(|hit| hit.entity))
    }

    fn target_centity(
        &self,
        this: &handles::Entity,
        max_distance: Option<f64>,
    ) -> ApiResult<Option<handles::Centity>> {
        let options = shapes::RaycastOptions {
            blocks: None,
            entities: Some(false),
            ignore: None,
            centities: None,
            fluids: None,
        };
        Ok(self
            .raycast_from(this, max_distance, options)?
            .and_then(|hit| hit.centity))
    }

    fn on(
        &self,
        this: &handles::Entity,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Entity:on")?;
        let target = self.entity_target(this)?;
        self.scripts
            .subscribe(scope, target, event, handler, options, false)
    }

    fn once(
        &self,
        this: &handles::Entity,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Entity:once")?;
        let target = self.entity_target(this)?;
        self.scripts
            .subscribe(scope, target, event, handler, None, true)
    }
}

impl GameContext {
    /// The handle's own value, as the thing it subscribes on.
    fn entity_target(&self, this: &handles::Entity) -> ApiResult<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &Handle::Entity(this.clone()))
            .map(Target::Handle)
            .map_err(|e| err(e.to_string()))
    }
}

impl DroppedItemApi for GameContext {
    fn item(&self, this: &handles::DroppedItem) -> ApiResult<Option<values::Item>> {
        Ok(parse_uuid(&this.id)
            .and_then(|id| self.shared.platform.world_entities().item(id))
            .map(|item| crate::items::to_value(&item)))
    }

    fn set_item(&self, this: &handles::DroppedItem, item: values::Item) -> ApiResult<bool> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        Ok(parse_uuid(&this.id)
            .is_some_and(|id| self.shared.platform.world_entities().set_item(id, item)))
    }

    fn pickup_delay(&self, this: &handles::DroppedItem) -> ApiResult<Option<i64>> {
        Ok(self
            .number(
                &handles::Entity {
                    id: this.id.clone(),
                },
                EntityNumber::PickupDelay,
            )
            .map(|n| n as i64))
    }

    fn set_pickup_delay(&self, this: &handles::DroppedItem, ticks: i64) -> ApiResult<bool> {
        if !(0..=32767).contains(&ticks) {
            return Err(err(format!("ticks must be from 0 to 32767, not {ticks}")));
        }
        Ok(parse_uuid(&this.id).is_some_and(|id| {
            self.shared.platform.world_entities().set_number(
                id,
                EntityNumber::PickupDelay,
                ticks as f64,
            )
        }))
    }
}

/// What the server forgets of its entities: who each is hidden from, and their `data()` tables.
pub struct EntitiesService {
    pub shared: Rc<Shared>,
    /// The room's host, while it runs, to encode tables with.
    pub host: std::cell::RefCell<Option<netherforge_luau::Host>>,
}

impl EntitiesService {
    /// Writes back the tables `which` picks that changed since they were saved.
    fn save(&self, which: &dyn Fn(Uuid) -> bool) {
        let Some(host) = self.host.borrow().clone() else {
            return;
        };
        let platform = &*self.shared.platform;
        let tables: Vec<(Uuid, mlua::Table)> = self
            .shared
            .entities
            .borrow()
            .data
            .iter()
            .filter(|(id, _)| which(**id))
            .map(|(id, d)| (*id, d.table.clone()))
            .collect();
        for (id, table) in tables {
            let place = format!("entity:data() of {}", uuid_text(&id));
            let encoded = netherforge_luau::json::encode(
                &host,
                &Value::Table(table),
                "data",
                "saved",
                &netherforge_api::handle::SavedHandles,
            );
            let encoded = match encoded {
                Ok(encoded) => encoded,
                Err(e) => {
                    platform.log().warn(format!("{place} wasn't saved: {e:?}"));
                    continue;
                }
            };
            for problem in &encoded.problems {
                platform.log().warn(format!("Saving {place}: {problem}"));
            }
            let Some(text) = encoded.text else { continue };
            let json = (text != "{}").then_some(text);
            let saved = self
                .shared
                .entities
                .borrow()
                .data
                .get(&id)
                .and_then(|d| d.saved.clone());
            if json == saved {
                continue;
            }
            if let Some(json) = &json
                && json.len() > MAX_DATA_BYTES
            {
                platform.log().warn(format!(
                    "{place} takes {:.1} MiB, more than the 1 MiB a table may, so it wasn't saved: the last save stands",
                    json.len() as f64 / 1_048_576.0
                ));
                continue;
            }
            if platform.world_entities().set_data(id, json.clone())
                && let Some(kept) = self.shared.entities.borrow_mut().data.get_mut(&id)
            {
                kept.saved = json;
            }
        }
    }
}

impl Service for EntitiesService {
    fn name(&self) -> &'static str {
        "entities"
    }

    fn phase(&self) -> &'static str {
        "world"
    }

    fn start(&self, room: &Room, _project: &crate::session::Define) {
        *self.host.borrow_mut() = Some(room.host().clone());
    }

    /// Every table is written back, and everything hidden shows again: the session is ending.
    fn stop(&self, _room: &Room, platform: &dyn netherforge_platform::Platform) {
        self.save(&|_| true);
        let state = std::mem::take(&mut *self.shared.entities.borrow_mut());
        for (entity, players) in state.hidden {
            for player in players {
                platform.world_entities().show(player, entity);
            }
        }
        *self.host.borrow_mut() = None;
    }

    fn player_joined(&self, player: &PlayerRef) {
        let state = self.shared.entities.borrow();
        for (entity, players) in &state.hidden {
            if players.contains(&player.uuid) {
                self.shared
                    .platform
                    .world_entities()
                    .hide(player.uuid, *entity);
            }
        }
    }

    fn entities_loaded(&self, _tagged: &HashMap<Uuid, EntityTag>, untagged: &[Uuid]) {
        let state = self.shared.entities.borrow();
        if state.hidden.is_empty() {
            return;
        }
        for entity in untagged {
            for player in state.hidden.get(entity).into_iter().flatten() {
                self.shared.platform.world_entities().hide(*player, *entity);
            }
        }
    }

    fn entities_unloading(&self, entities: &HashSet<Uuid>) {
        self.save(&|id| entities.contains(&id));
    }

    fn world_saving(&self, _world: &str) {
        self.save(&|_| true);
    }

    /// Its table goes, unsaved, and who it was hidden from.
    fn entity_gone(&self, entity: Uuid) {
        let mut state = self.shared.entities.borrow_mut();
        state.data.remove(&entity);
        state.hidden.remove(&entity);
    }
}

/// Whether `category` is something with health.
pub fn living(category: EntityCategory) -> bool {
    matches!(
        category,
        EntityCategory::Living | EntityCategory::Mob | EntityCategory::Mannequin
    )
}
