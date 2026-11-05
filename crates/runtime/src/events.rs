//! The server's events, raised to scripts (the Kotlin runtime's `RuntimeGameEvents`): the generated
//! dispatch (`GameEventDispatch`, from `packages/api`'s `raised`) and the session it asks
//! (`EventSession`): whether a project runs, whether anything listens along an event's path, the
//! handles and values a payload holds, and raising it.

use mlua::{Lua, Table, Value};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{handle, shapes, values};
use netherforge_platform::{
    BlockRef, InventoryRef, ItemData, Location, Platform, PlayerRef, StatusEffectData, Uuid,
};

use crate::Room;
use crate::scripts::Target;

#[path = "generated/game_event_dispatch.rs"]
#[rustfmt::skip]
mod dispatch;

pub use dispatch::{GameEventDispatch, RAISED, WATCHED};

/// One stage of an event's path: the event's name there, on a handle or on `nf` (none).
pub type Stage<'a> = (&'a str, Option<Handle>);

/// What handlers made of a raised event: whether it ended cancelled, and its fields as they left them.
pub struct Emitted {
    pub cancelled: bool,
    pub fields: Table,
}

/// What the dispatch asks of the session running now.
pub trait EventSession {
    /// Whether a project runs: while none does, every event goes through unchanged.
    fn running(&self) -> bool;
    /// Whether anything listens anywhere along `stages`: whether building the payload is worth it.
    fn listening(&self, stages: &[Stage]) -> bool;
    /// Raises `payload` (pushed with `codec`) along `stages`; none when it couldn't be.
    fn emit<C: Codec>(&self, stages: &[Stage], codec: C, payload: C::T) -> Option<Emitted>;
    /// What a handler left in writable field `field`, read as it's read back; none when it can't be.
    fn read<C: Codec>(&self, out: &Emitted, field: &str, codec: C) -> Option<C::T>;

    fn player(&self, player: &PlayerRef) -> handles::Player;
    fn entity(&self, id: &Uuid) -> handles::Entity;
    fn living(&self, id: &Uuid) -> handles::Living;
    fn mob(&self, id: &Uuid) -> handles::Mob;
    fn block(&self, block: &BlockRef) -> handles::Block;
    fn world(&self, name: &str) -> handles::World;
    fn inventory(&self, inventory: &InventoryRef) -> handles::Inventory;
    fn location(&self, location: &Location) -> values::Location;
    fn item(&self, item: &ItemData) -> values::Item;
    fn status_effect(&self, effect: &StatusEffectData) -> shapes::StatusEffect;
    fn text(&self, text: &str) -> values::Text;
    /// Where a location a handler wrote points on the server: `was`'s world for a world it doesn't have,
    /// and `was`'s facing where it gives none.
    fn placed(&self, location: values::Location, was: &Location) -> Location;
    /// What a handler left in an item field: `before` itself when it's the same item.
    fn item_back(&self, after: values::Item, before: &ItemData) -> ItemData;
    /// As `item_back`, for an item field that may be empty.
    fn item_back_opt(
        &self,
        after: Option<values::Item>,
        before: &Option<ItemData>,
    ) -> Option<ItemData>;
    /// As `item_back`, for a list of items.
    fn items_back(&self, after: Vec<values::Item>, before: &[ItemData]) -> Vec<ItemData>;
}

/// A UUID as text, as handles key on it: `8-4-4-4-12` hex digits, lower case.
pub fn uuid_text(id: &Uuid) -> String {
    let hex = format!("{:032x}", id.0);
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

/// A block's position as a `Block` handle keys on it (Kotlin's `Locations.pack`).
pub fn pack(x: i32, y: i32, z: i32) -> i64 {
    ((i64::from(x) & 0x3FF_FFFF) << 38)
        | ((i64::from(z) & 0x3FF_FFFF) << 12)
        | (i64::from(y) & 0xFFF)
}

/// The session running now, as the dispatch asks for it: its room (none while no project runs) and the
/// server.
pub struct Running<'a> {
    pub room: Option<&'a Room>,
    pub platform: &'a dyn Platform,
}

impl Running<'_> {
    fn lua(&self) -> Option<Lua> {
        self.room.map(|r| r.host().lua().clone())
    }

    fn target(&self, lua: &Lua, handle: &Option<Handle>) -> Option<Target> {
        match handle {
            None => Some(Target::Nf),
            Some(h) => handle::push_any(lua, h).ok().map(Target::Handle),
        }
    }
}

impl EventSession for Running<'_> {
    fn running(&self) -> bool {
        self.room.is_some()
    }

    fn listening(&self, stages: &[Stage]) -> bool {
        let (Some(room), Some(lua)) = (self.room, self.lua()) else {
            return false;
        };
        stages.iter().any(|(event, handle)| {
            self.target(&lua, handle)
                .is_some_and(|target| room.scripts().listening(&target, event))
        })
    }

    fn emit<C: Codec>(&self, stages: &[Stage], codec: C, payload: C::T) -> Option<Emitted> {
        let room = self.room?;
        let lua = self.lua()?;
        let Value::Table(fields) = codec.push(&lua, payload).ok()? else {
            return None;
        };
        let path: Vec<(Target, &str)> = stages
            .iter()
            .filter_map(|(event, handle)| Some((self.target(&lua, handle)?, *event)))
            .collect();
        let delivered = room.scripts().raise_path(&path, fields, false);
        Some(Emitted {
            cancelled: delivered.cancelled,
            fields: delivered.fields,
        })
    }

    fn read<C: Codec>(&self, out: &Emitted, field: &str, codec: C) -> Option<C::T> {
        let lua = self.lua()?;
        let value: Value = out.fields.raw_get(field).ok()?;
        match codec.read(&lua, value, &format!("event.{field}")) {
            Ok(v) => Some(v),
            Err(mistake) => {
                self.platform
                    .log()
                    .warn(format!("A handler left {}; it's left as it was", mistake.0));
                None
            }
        }
    }

    fn player(&self, player: &PlayerRef) -> handles::Player {
        handles::Player {
            id: uuid_text(&player.uuid),
        }
    }

    fn entity(&self, id: &Uuid) -> handles::Entity {
        handles::Entity { id: uuid_text(id) }
    }

    fn living(&self, id: &Uuid) -> handles::Living {
        handles::Living { id: uuid_text(id) }
    }

    fn mob(&self, id: &Uuid) -> handles::Mob {
        handles::Mob { id: uuid_text(id) }
    }

    fn block(&self, block: &BlockRef) -> handles::Block {
        handles::Block {
            world: block.world.clone(),
            position: pack(block.x, block.y, block.z),
        }
    }

    fn world(&self, name: &str) -> handles::World {
        handles::World { name: name.to_owned() }
    }

    fn inventory(&self, inventory: &InventoryRef) -> handles::Inventory {
        let holder = match inventory {
            InventoryRef::Player(p) => format!("player/{}", uuid_text(&p.player)),
            InventoryRef::EnderChest(p) => format!("ender_chest/{}", uuid_text(&p.player)),
            InventoryRef::Storage(s) => {
                format!("storage/{}/{}/{}", uuid_text(&s.player), s.size, s.name)
            }
            InventoryRef::Entity(e) => format!("entity/{}", uuid_text(&e.entity)),
            InventoryRef::Block(b) => format!("block/{}/{}/{}/{}", b.x, b.y, b.z, b.world),
        };
        handles::Inventory { holder }
    }

    fn location(&self, location: &Location) -> values::Location {
        values::Location {
            world: handles::World {
                name: location.world.clone(),
            },
            position: values::Vec3 {
                x: location.x,
                y: location.y,
                z: location.z,
            },
            yaw: Some(location.yaw),
            pitch: Some(location.pitch),
        }
    }

    /// Items are ported with their area: until then an item crosses as an empty one.
    fn item(&self, _: &ItemData) -> values::Item {
        values::Item("{}".into())
    }

    fn status_effect(&self, effect: &StatusEffectData) -> shapes::StatusEffect {
        shapes::StatusEffect {
            effect: effect.effect.clone(),
            ticks: i64::from(effect.ticks),
            amplifier: i64::from(effect.amplifier),
            ambient: effect.ambient,
            particles: effect.particles,
            icon: effect.icon,
        }
    }

    fn text(&self, text: &str) -> values::Text {
        values::Text(text.to_owned())
    }

    fn placed(&self, location: values::Location, was: &Location) -> Location {
        let world = if self.platform.worlds().exists(location.world.name.clone()) {
            location.world.name
        } else {
            was.world.clone()
        };
        Location {
            world,
            x: location.position.x,
            y: location.position.y,
            z: location.position.z,
            yaw: location.yaw.unwrap_or(was.yaw),
            pitch: location.pitch.unwrap_or(was.pitch),
        }
    }

    /// Items are ported with their area: until then a handler's item leaves the server's as it was.
    fn item_back(&self, _: values::Item, before: &ItemData) -> ItemData {
        before.clone()
    }

    fn item_back_opt(
        &self,
        _: Option<values::Item>,
        before: &Option<ItemData>,
    ) -> Option<ItemData> {
        before.clone()
    }

    fn items_back(&self, _: Vec<values::Item>, before: &[ItemData]) -> Vec<ItemData> {
        before.to_vec()
    }
}
