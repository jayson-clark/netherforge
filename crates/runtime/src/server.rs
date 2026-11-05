//! What the server tells the runtime (`PlatformEvents`), for as long as the plugin is enabled: the Kotlin
//! runtime's `ServerEvents`. It holds the session; each event goes to it, to its services' hooks (what
//! loaded or unloaded, who clicked a centity) and to its scripts, along the path the runtime decides (a
//! player's death, then `entity_death`). A session that isn't running hears nothing but what keeps the world
//! in order (a centity's entities loading, a click on one), and every answer is then "unchanged". Every other
//! event scripts hear is `game()`'s, generated from the API spec.
//!
//! **Events arrive at any time on the main thread**, a tick's scripts' own doings included (a script teleports
//! a player: the teleport is an event). While the session ticks it's shared, so they reach it; while it's
//! being changed (a reload, a stop) it can't be, and they go through unchanged.
//!
//! Not yet, each with its service: project items' stages (an item's handlers first) and their looks,
//! custom blocks, structure markers, generated loot, menus, dialogs, resource packs and screens. Those
//! events answer "unchanged" until then.

use std::cell::{Ref, RefCell, RefMut};
use std::collections::HashMap;
use std::rc::Rc;

use netherforge_api::codec::{Bool, Int, ItemCodec, List, Num, Opt, TextCodec};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{codecs, shapes};
use netherforge_platform::{
    BlockRef, ClickButton, DeathAnswer, DialogAnswers, DropsAnswer, Dynamic, EntityTag, GameEvents,
    ItemData, ItemView, Location, MenuClick, PickAnswer, PlatformEvents, PlayerRef, Ray,
    StructureMarker, Uuid,
};

use crate::events::{Emitted, EventSession, EventSource, GameEventDispatch, Running, Stage};
use crate::session::Session;

/// The session the server's events go to: the dispatch's source.
pub struct Current<'a>(RefCell<Session<'a>>);

impl<'a> EventSource for Current<'a> {
    type Open<'b>
        = Running<'b, 'a>
    where
        Self: 'b;

    fn open(&self) -> Option<Running<'_, 'a>> {
        Running::open(&self.0)
    }
}

/// `PlatformEvents` for a session: what the adapter calls.
pub struct ServerEvents<'a> {
    game: GameEventDispatch<Current<'a>>,
}

impl<'a> ServerEvents<'a> {
    pub fn new(session: Session<'a>) -> ServerEvents<'a> {
        ServerEvents {
            game: GameEventDispatch(Current(RefCell::new(session))),
        }
    }

    /// The session, shared (what the bridge and `/nf` ask).
    pub fn session(&self) -> Ref<'_, Session<'a>> {
        self.game.0.0.borrow()
    }

    /// The session to change (reload it, stop it): never while an event is being handled.
    pub fn session_mut(&self) -> RefMut<'_, Session<'a>> {
        self.game.0.0.borrow_mut()
    }

    /// The session while it runs, for one event.
    fn open(&self) -> Option<Running<'_, 'a>> {
        self.game.0.open()
    }

    /// The session's scripts, whatever it's doing: none while it's being changed.
    fn any(&self) -> Option<Ref<'_, Session<'a>>> {
        self.game.0.0.try_borrow().ok()
    }
}

/// Raises `payload` (made only if something listens) along `stages`; none when nothing listened.
fn raise<C: netherforge_api::codec::Codec>(
    s: &Running,
    stages: &[Stage],
    codec: C,
    payload: impl FnOnce() -> C::T,
) -> Option<Emitted> {
    if !s.listening(stages) {
        return None;
    }
    s.emit(stages, codec, payload())
}

/// Whether a raised event ended cancelled.
fn cancelled(out: Option<Emitted>) -> bool {
    out.is_some_and(|out| out.cancelled)
}

fn player_stage<'s>(s: &Running, event: &'s str, player: &PlayerRef) -> Stage<'s> {
    (event, Some(Handle::Player(s.player(player))))
}

/// A list of items a handler left in `field`: none when it's the same as `before` (or unreadable).
fn drops_back(
    s: &Running,
    out: &Emitted,
    field: &str,
    before: &[ItemData],
) -> Option<Vec<ItemData>> {
    let after = s.read(out, field, List(ItemCodec))?;
    let after = s.items_back(after, before);
    (after != before).then_some(after)
}

/// The experience a handler left in `field`, held to what the server can drop; `was` when unreadable.
fn experience_back(s: &Running, out: &Emitted, field: &str, was: i32) -> i32 {
    s.read(out, field, Int)
        .map_or(was, |v| v.clamp(0, i64::from(i32::MAX)) as i32)
}

impl<'a> PlatformEvents for ServerEvents<'a> {
    fn game(&self) -> &dyn GameEvents {
        &self.game
    }

    fn tick(&self) {
        if let Some(session) = self.any() {
            session.tick();
        }
    }

    fn item_rendered(&self, stack: ItemData) -> ItemData {
        stack
    }

    fn item_viewing(&self, _reference: Option<String>) -> bool {
        false
    }

    fn item_viewed(&self, _player: Uuid, _stack: ItemData) -> Option<ItemView> {
        None
    }

    fn player_interact(
        &self,
        player: PlayerRef,
        button: ClickButton,
        block: Option<BlockRef>,
        face: Option<String>,
        item: Option<ItemData>,
        hand: String,
    ) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = [
            player_stage(&s, "interact", &player),
            ("player_interact", None),
        ];
        cancelled(raise(&s, &stages, codecs::PlayerInteractEvent, || {
            shapes::PlayerInteractEvent {
                player: s.player(&player),
                click: button.lua_name().to_owned(),
                block: block.as_ref().map(|b| s.block(b)),
                face,
                item: item.as_ref().map(|i| s.item(i)),
                hand,
            }
        }))
    }

    fn player_use_item(&self, player: PlayerRef, item: ItemData, hand: String) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = [
            player_stage(&s, "use_item", &player),
            ("player_use_item", None),
        ];
        cancelled(raise(&s, &stages, codecs::PlayerUseItemEvent, || {
            shapes::PlayerUseItemEvent {
                player: s.player(&player),
                item: s.item(&item),
                hand,
            }
        }))
    }

    fn draws_without_ammo(&self, _item: ItemData) -> bool {
        false
    }

    fn block_break(
        &self,
        player: PlayerRef,
        block: BlockRef,
        drops: Box<dyn Fn() -> Vec<ItemData>>,
        experience: i32,
    ) -> Option<DropsAnswer> {
        let unchanged = DropsAnswer {
            drops: None,
            experience,
        };
        let Some(s) = self.open() else {
            return Some(unchanged);
        };
        let stages = [
            ("block_break", Some(Handle::World(s.world(&block.world)))),
            ("block_break", None),
        ];
        let mut answer = unchanged;
        if s.listening(&stages) {
            let before = drops();
            let payload = shapes::BlockBreakEvent {
                player: s.player(&player),
                block: s.block(&block),
                state: block.state.clone(),
                drops: before.iter().map(|i| s.item(i)).collect(),
                experience: i64::from(experience),
            };
            let out = s.emit(&stages, codecs::BlockBreakEvent, payload)?;
            if out.cancelled {
                return None;
            }
            answer = DropsAnswer {
                drops: drops_back(&s, &out, "drops", &before),
                experience: experience_back(&s, &out, "experience", experience),
            };
        }
        s.session().block_changed(&block);
        Some(answer)
    }

    fn block_stop_break(&self, _player: PlayerRef, _block: BlockRef) {}

    fn world_broke(&self, _block: BlockRef) -> Option<Vec<ItemData>> {
        None
    }

    fn block_updated(&self, _block: BlockRef) {}

    fn falling_landed(&self, _entity: Uuid, _block: BlockRef) {}

    fn pick_block(
        &self,
        _player: PlayerRef,
        _block: BlockRef,
        _inventory: Vec<Option<ItemData>>,
    ) -> Option<PickAnswer> {
        None
    }

    /// `player_death` on the player and `nf`, then `entity_death` on `nf` with what that left.
    fn player_died(
        &self,
        player: PlayerRef,
        killer: Option<Uuid>,
        cause: String,
        message: Option<String>,
        keep_inventory: bool,
        drops: Vec<ItemData>,
        experience: i32,
    ) -> DeathAnswer {
        let Some(s) = self.open() else {
            return DeathAnswer {
                message,
                keep_inventory,
                drops: None,
                experience,
            };
        };
        let (mut message_after, mut keep_after, mut drops_after) =
            (message.clone(), keep_inventory, drops.clone());
        let stages = [player_stage(&s, "death", &player), ("player_death", None)];
        if let Some(out) = raise(&s, &stages, codecs::PlayerDeathEvent, || {
            shapes::PlayerDeathEvent {
                player: s.player(&player),
                killer: killer.as_ref().map(|k| s.entity(k)),
                cause: cause.clone(),
                message: message.as_deref().map(|m| s.text(m)),
                keep_inventory,
                drops: drops.iter().map(|i| s.item(i)).collect(),
            }
        }) {
            if let Some(v) = s.read(&out, "message", Opt(TextCodec)) {
                message_after = v.map(|t| t.0);
            }
            if let Some(v) = s.read(&out, "keep_inventory", Bool) {
                keep_after = v;
            }
            if let Some(v) = drops_back(&s, &out, "drops", &drops) {
                drops_after = v;
            }
        }
        // Keeping the inventory means nothing from it drops, unless a handler said what does.
        if keep_after && !keep_inventory && drops_after == drops {
            drops_after = Vec::new();
        }
        let mut experience_after = experience;
        let stages = [("entity_death", None)];
        if let Some(out) = raise(&s, &stages, codecs::EntityDeathEvent, || {
            shapes::EntityDeathEvent {
                entity: handles::Living::from(s.player(&player)),
                killer: killer.as_ref().map(|k| s.entity(k)),
                drops: drops_after.iter().map(|i| s.item(i)).collect(),
                experience: i64::from(experience),
            }
        }) {
            if let Some(v) = drops_back(&s, &out, "drops", &drops_after) {
                drops_after = v;
            }
            experience_after = experience_back(&s, &out, "experience", experience);
        }
        DeathAnswer {
            message: message_after,
            keep_inventory: keep_after,
            drops: (drops_after != drops).then_some(drops_after),
            experience: experience_after,
        }
    }

    fn player_drop_item(&self, player: PlayerRef, item: ItemData) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = [
            player_stage(&s, "drop_item", &player),
            ("player_drop_item", None),
        ];
        cancelled(raise(&s, &stages, codecs::PlayerItemEvent, || {
            shapes::PlayerItemEvent {
                player: s.player(&player),
                item: s.item(&item),
            }
        }))
    }

    fn player_pickup_item(&self, player: PlayerRef, item: ItemData, entity: Uuid) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = [
            player_stage(&s, "pickup_item", &player),
            ("player_pickup_item", None),
        ];
        cancelled(raise(&s, &stages, codecs::PlayerPickupItemEvent, || {
            shapes::PlayerPickupItemEvent {
                player: s.player(&player),
                item: s.item(&item),
                entity: handles::DroppedItem {
                    id: crate::events::uuid_text(&entity),
                },
            }
        }))
    }

    fn player_consume_item(&self, player: PlayerRef, item: ItemData) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = [
            player_stage(&s, "consume_item", &player),
            ("player_consume_item", None),
        ];
        cancelled(raise(&s, &stages, codecs::PlayerItemEvent, || {
            shapes::PlayerItemEvent {
                player: s.player(&player),
                item: s.item(&item),
            }
        }))
    }

    fn world_saving(&self, world: String) {
        if let Some(session) = self.any() {
            session.world_saving(&world);
        }
    }

    fn plugins_changed(&self) {
        if let Some(session) = self.any() {
            session.plugins_changed();
        }
    }

    fn entity_clicked(
        &self,
        entity: Uuid,
        player: PlayerRef,
        button: ClickButton,
        sight: Option<Ray>,
    ) -> bool {
        self.any()
            .is_some_and(|session| session.entity_clicked(entity, &player, button, sight))
    }

    fn entities_loaded(&self, tagged: HashMap<Uuid, EntityTag>, untagged: Vec<Uuid>) {
        if let Some(session) = self.any() {
            session.entities_loaded(&tagged, &untagged);
        }
    }

    fn structure_markers_loaded(&self, _markers: Vec<StructureMarker>) {}

    fn entities_unloading(&self, entities: Vec<Uuid>) {
        if let Some(session) = self.any() {
            session.entities_unloading(&entities.into_iter().collect());
        }
    }

    fn entity_damaged(
        &self,
        entity: Uuid,
        amount: f64,
        cause: String,
        attacker: Option<Uuid>,
    ) -> Option<f64> {
        let Some(s) = self.open() else {
            return Some(amount);
        };
        let target = s.entity(&entity);
        let stages = [
            ("damage", Some(Handle::Entity(target.clone()))),
            ("entity_damage", None),
        ];
        let Some(out) = raise(&s, &stages, codecs::EntityDamageEvent, || {
            shapes::EntityDamageEvent {
                entity: target.clone(),
                amount,
                cause,
                attacker: attacker.as_ref().map(|a| s.entity(a)),
            }
        }) else {
            return Some(amount);
        };
        if out.cancelled {
            return None;
        }
        let after = s.read(&out, "amount", Num).unwrap_or(amount);
        Some(if after.is_finite() {
            after.max(0.0)
        } else {
            amount
        })
    }

    fn entity_died(
        &self,
        entity: Uuid,
        killer: Option<Uuid>,
        drops: Box<dyn Fn() -> Vec<ItemData>>,
        experience: i32,
    ) -> DropsAnswer {
        let mut answer = DropsAnswer {
            drops: None,
            experience,
        };
        let Some(s) = self.open() else { return answer };
        let target = s.living(&entity);
        let stages = [
            ("death", Some(Handle::Living(target.clone()))),
            ("entity_death", None),
        ];
        if s.listening(&stages) {
            let before = drops();
            let payload = shapes::EntityDeathEvent {
                entity: target,
                killer: killer.as_ref().map(|k| s.entity(k)),
                drops: before.iter().map(|i| s.item(i)).collect(),
                experience: i64::from(experience),
            };
            if let Some(out) = s.emit(&stages, codecs::EntityDeathEvent, payload) {
                answer = DropsAnswer {
                    drops: drops_back(&s, &out, "drops", &before),
                    experience: experience_back(&s, &out, "experience", experience),
                };
            }
        }
        s.session().entity_gone(entity);
        answer
    }

    fn generated_loot(
        &self,
        _table: String,
        _player: Option<Uuid>,
        _location: Location,
    ) -> Option<Vec<ItemData>> {
        None
    }

    fn player_interact_entity(&self, player: PlayerRef, entity: Uuid, hand: String) -> bool {
        let Some(s) = self.open() else { return false };
        let target = s.entity(&entity);
        let stages = [
            ("interact", Some(Handle::Entity(target.clone()))),
            player_stage(&s, "interact_entity", &player),
            ("player_interact_entity", None),
        ];
        cancelled(raise(
            &s,
            &stages,
            codecs::PlayerInteractEntityEvent,
            || shapes::PlayerInteractEntityEvent {
                player: s.player(&player),
                entity: target.clone(),
                hand,
            },
        ))
    }

    fn resource_pack_status(&self, _player: Uuid, _pack: Uuid, _status: Option<String>) {}

    fn menu_clicked(&self, _window: Uuid, _click: MenuClick) -> bool {
        false
    }

    fn menu_dragged(
        &self,
        _window: Uuid,
        _player: PlayerRef,
        _slots: Vec<i32>,
        _cursor: Option<ItemData>,
        _own_slots: Vec<i32>,
        _click: String,
    ) -> bool {
        false
    }

    fn menu_closed(&self, _window: Uuid, _player: PlayerRef) {}

    fn menu_changed(&self, _window: Uuid, _slots: Vec<i32>) {}

    fn dialog_pressed(
        &self,
        _player: PlayerRef,
        _dialog: String,
        _button: String,
        _values: HashMap<String, Dynamic>,
    ) {
    }

    fn dialog_closed(&self, _player: PlayerRef, _dialog: String) {}

    fn custom_clicked(
        &self,
        _player: PlayerRef,
        _id: String,
        _answers: Rc<dyn DialogAnswers>,
    ) -> bool {
        false
    }
}
