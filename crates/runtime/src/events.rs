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

use std::cell::RefCell;

use crate::Room;
use crate::scripts::Target;
use crate::session::Session;

#[path = "generated/game_event_dispatch.rs"]
#[rustfmt::skip]
mod dispatch;

pub use dispatch::{Around, EventSource, GameEventDispatch, RAISED, WATCHED};

/// One stage of an event's path: the event's name there, on a handle or on `nf` (none).
pub type Stage<'a> = (&'a str, Option<Handle>);

/// What handlers made of a raised event: whether it ended cancelled, and its fields as they left them.
pub struct Emitted {
    pub cancelled: bool,
    pub fields: Table,
}

/// What the dispatch asks of the session running now.
pub trait EventSession {
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

/// A UUID from its text (`uuid_text`'s, or without the dashes, either case); none for anything else.
pub fn parse_uuid(text: &str) -> Option<Uuid> {
    let hex: String = text.chars().filter(|c| *c != '-').collect();
    if hex.len() != 32 {
        return None;
    }
    u128::from_str_radix(&hex, 16).ok().map(Uuid)
}

/// An inventory's handle, keyed by what holds it (Kotlin's `InventoryKeys.of`).
pub fn inventory_handle(inventory: &InventoryRef) -> handles::Inventory {
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

/// What an inventory's handle names (`inventory_handle`'s inverse); none for a key that names nothing.
pub fn inventory_ref(handle: &handles::Inventory) -> Option<InventoryRef> {
    use netherforge_platform::{
        InventoryRefBlock, InventoryRefEnderChest, InventoryRefEntity, InventoryRefPlayer,
        InventoryRefStorage,
    };
    let (kind, rest) = handle.holder.split_once('/')?;
    Some(match kind {
        "player" => InventoryRef::Player(InventoryRefPlayer {
            player: parse_uuid(rest)?,
        }),
        "ender_chest" => InventoryRef::EnderChest(InventoryRefEnderChest {
            player: parse_uuid(rest)?,
        }),
        "entity" => InventoryRef::Entity(InventoryRefEntity {
            entity: parse_uuid(rest)?,
        }),
        "storage" => {
            let mut parts = rest.splitn(3, '/');
            InventoryRef::Storage(InventoryRefStorage {
                player: parse_uuid(parts.next()?)?,
                size: parts.next()?.parse().ok()?,
                name: parts.next()?.to_owned(),
            })
        }
        "block" => {
            let mut parts = rest.splitn(4, '/');
            InventoryRef::Block(InventoryRefBlock {
                x: parts.next()?.parse().ok()?,
                y: parts.next()?.parse().ok()?,
                z: parts.next()?.parse().ok()?,
                world: parts.next()?.to_owned(),
            })
        }
        _ => return None,
    })
}

/// A block's position as a `Block` handle keys on it (Kotlin's `Locations.pack`).
pub fn pack(x: i32, y: i32, z: i32) -> i64 {
    ((i64::from(x) & 0x3FF_FFFF) << 38)
        | ((i64::from(z) & 0x3FF_FFFF) << 12)
        | (i64::from(y) & 0xFFF)
}

/// The session running now, as the dispatch asks for it: open while the event is handled, and only while it
/// runs (`Running::open`).
pub struct Running<'b, 'a> {
    session: std::cell::Ref<'b, Session<'a>>,
}

impl<'b, 'a> Running<'b, 'a> {
    /// The session in `cell`, when it runs and isn't being changed (a reload, a stop: an event the server
    /// raises meanwhile goes through unchanged).
    pub fn open(cell: &'b RefCell<Session<'a>>) -> Option<Running<'b, 'a>> {
        let session = cell.try_borrow().ok()?;
        session.room()?;
        Some(Running { session })
    }

    pub fn session(&self) -> &Session<'a> {
        &self.session
    }

    pub fn room(&self) -> &Room {
        self.session.room().expect("open only while it runs")
    }

    pub fn platform(&self) -> &dyn Platform {
        self.session.platform()
    }

    fn lua(&self) -> Lua {
        self.room().host().lua().clone()
    }

    fn target(&self, lua: &Lua, handle: &Option<Handle>) -> Option<Target> {
        match handle {
            None => Some(Target::Nf),
            Some(h) => handle::push_any(lua, h).ok().map(Target::Handle),
        }
    }
}

impl EventSession for Running<'_, '_> {
    fn listening(&self, stages: &[Stage]) -> bool {
        let lua = self.lua();
        stages.iter().any(|(event, handle)| {
            self.target(&lua, handle)
                .is_some_and(|target| self.room().scripts().listening(&target, event))
        })
    }

    fn emit<C: Codec>(&self, stages: &[Stage], codec: C, payload: C::T) -> Option<Emitted> {
        let room = self.room();
        let lua = self.lua();
        let Value::Table(fields) = codec.push(&lua, payload).ok()? else {
            return None;
        };
        let path: Vec<(Target, &str)> = stages
            .iter()
            .filter_map(|(event, handle)| Some((self.target(&lua, handle)?, *event)))
            .collect();
        let delivered = room.scripts().raise_path(&path, fields, false);
        // What a handler's scope let go of meanwhile goes now (a temporary centity it spawned).
        self.session.released();
        Some(Emitted {
            cancelled: delivered.cancelled,
            fields: delivered.fields,
        })
    }

    fn read<C: Codec>(&self, out: &Emitted, field: &str, codec: C) -> Option<C::T> {
        let lua = self.lua();
        let value: Value = out.fields.raw_get(field).ok()?;
        match codec.read(&lua, value, &format!("event.{field}")) {
            Ok(v) => Some(v),
            Err(mistake) => {
                self.platform()
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

    /// The block, its value a `CustomBlock`'s when the project's block is there (Kotlin's `blockAs`).
    fn block(&self, block: &BlockRef) -> handles::Block {
        let position = pack(block.x, block.y, block.z);
        let shared = self.session.shared();
        let custom = shared
            .blocks
            .borrow()
            .at(&block.world, block.x, block.y, block.z)
            .is_some();
        if custom
            && let Ok(pushed) = netherforge_api::handle::push_any(
                self.room().host().lua(),
                &netherforge_api::handles::Handle::CustomBlock(handles::CustomBlock {
                    world: block.world.clone(),
                    position,
                }),
            )
        {
            shared.classed.borrow_mut().push(pushed);
        }
        handles::Block {
            world: block.world.clone(),
            position,
        }
    }

    fn world(&self, name: &str) -> handles::World {
        handles::World {
            name: name.to_owned(),
        }
    }

    fn inventory(&self, inventory: &InventoryRef) -> handles::Inventory {
        inventory_handle(inventory)
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

    fn item(&self, item: &ItemData) -> values::Item {
        crate::items::to_value(item)
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
        let world = if self.platform().worlds().exists(location.world.name.clone()) {
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

    /// A handler's item, or the server's as it was when it's the same item or can't be read (said in the log).
    fn item_back(&self, after: values::Item, before: &ItemData) -> ItemData {
        match crate::items::from_value(&after, "event.item", self.platform().game()) {
            Ok(item) if item == *before => before.clone(),
            Ok(item) => item,
            Err(mistake) => {
                self.platform()
                    .log()
                    .warn(format!("A handler left {mistake}; it's left as it was"));
                before.clone()
            }
        }
    }

    fn item_back_opt(
        &self,
        after: Option<values::Item>,
        before: &Option<ItemData>,
    ) -> Option<ItemData> {
        let after = after?;
        Some(match before {
            Some(before) => self.item_back(after, before),
            None => crate::items::from_value(&after, "event.item", self.platform().game()).ok()?,
        })
    }

    fn items_back(&self, after: Vec<values::Item>, before: &[ItemData]) -> Vec<ItemData> {
        after
            .into_iter()
            .enumerate()
            .filter_map(|(i, item)| match before.get(i) {
                Some(was) => Some(self.item_back(item, was)),
                None => crate::items::from_value(&item, "event.items", self.platform().game()).ok(),
            })
            .collect()
    }
}

impl Running<'_, '_> {
    fn blocks(&self) -> crate::custom_blocks::BlocksContext {
        crate::custom_blocks::BlocksContext {
            shared: self.session.shared().clone(),
            scripts: self.room().scripts().clone(),
        }
    }

    fn exploded(&self, event: &netherforge_platform::game_event::Explode, cancelled: bool) {
        if cancelled || !event.breaks_blocks || !self.session.shared().blocks.borrow().explodable()
        {
            return;
        }
        self.blocks().exploded(&(event.blocks)(), event.r#yield);
    }
}

/// What the session's services do around the server's events (Kotlin's `RuntimeGameEvents`): they hear of a join
/// before scripts and of a quit after, keep a chunk's centities once its scripts have had it, and physics reads
/// again the blocks a placement changed.
impl Around for Running<'_, '_> {
    fn player_join(
        &self,
        event: &mut netherforge_platform::game_event::PlayerJoin,
        raise: &dyn Fn(&mut netherforge_platform::game_event::PlayerJoin),
    ) {
        self.session.player_joined(&event.player);
        raise(event)
    }

    fn player_quit(
        &self,
        event: &mut netherforge_platform::game_event::PlayerQuit,
        raise: &dyn Fn(&mut netherforge_platform::game_event::PlayerQuit),
    ) {
        raise(event);
        self.session.player_quit(&event.player);
    }

    fn chunk_unload(
        &self,
        event: &mut netherforge_platform::game_event::Chunk,
        raise: &dyn Fn(&mut netherforge_platform::game_event::Chunk),
    ) {
        raise(event);
        self.session.chunk_unloading(&event.world, event.x, event.z);
    }

    /// What mining one of the project's blocks takes is set before scripts hear of it (they may then make it
    /// instant, or stop it).
    fn block_start_break(
        &self,
        event: &mut netherforge_platform::game_event::BlockStartBreak,
        raise: &dyn Fn(&mut netherforge_platform::game_event::BlockStartBreak) -> bool,
    ) -> bool {
        let shared = self.session.shared().clone();
        let refused = {
            let blocks = shared.blocks.borrow();
            shared
                .mining
                .borrow_mut()
                .started_breaking(&*shared.platform, &*blocks, event)
        };
        raise(event) || refused
    }

    /// A block of the project's doesn't go with a piston.
    fn piston_extend(
        &self,
        event: &mut netherforge_platform::game_event::Piston,
        raise: &dyn Fn(&mut netherforge_platform::game_event::Piston) -> bool,
    ) -> bool {
        let held = self.blocks().pushes(&event.blocks);
        raise(event) || held
    }

    fn piston_retract(
        &self,
        event: &mut netherforge_platform::game_event::Piston,
        raise: &dyn Fn(&mut netherforge_platform::game_event::Piston) -> bool,
    ) -> bool {
        let held = self.blocks().pushes(&event.blocks);
        raise(event) || held
    }

    /// What scripts left breaking, the project's blocks (and the game's with drops of the project's) break with
    /// their drops.
    fn entity_explode(
        &self,
        event: &mut netherforge_platform::game_event::Explode,
        raise: &dyn Fn(&mut netherforge_platform::game_event::Explode) -> bool,
    ) -> bool {
        let cancelled = raise(event);
        self.exploded(event, cancelled);
        cancelled
    }

    fn block_explode(
        &self,
        event: &mut netherforge_platform::game_event::Explode,
        raise: &dyn Fn(&mut netherforge_platform::game_event::Explode) -> bool,
    ) -> bool {
        let cancelled = raise(event);
        self.exploded(event, cancelled);
        cancelled
    }

    fn chunk_load(
        &self,
        event: &mut netherforge_platform::game_event::ChunkLoad,
        raise: &dyn Fn(&mut netherforge_platform::game_event::ChunkLoad),
    ) {
        self.session
            .chunk_loaded(&event.world, event.x, event.z, event.generated);
        raise(event)
    }

    fn block_place(
        &self,
        event: &mut netherforge_platform::game_event::BlockPlace,
        raise: &dyn Fn(&mut netherforge_platform::game_event::BlockPlace) -> bool,
    ) -> bool {
        let cancelled = raise(event);
        if !cancelled {
            self.session.block_changed(&event.block);
        }
        cancelled
    }
}
