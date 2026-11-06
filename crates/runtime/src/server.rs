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
    ticks: std::cell::Cell<u64>,
    /// The dev bridge, on a dev server: its work runs at the start of each tick, its status after.
    bridge: RefCell<Option<Rc<crate::bridge::Bridge>>>,
    /// The profiler, which outlives the session's runs: every session it runs hands it its ticks.
    profiler: RefCell<Rc<crate::profile::Profiler>>,
    /// What runs after each tick (`/nf`'s held work).
    after_tick: RefCell<Option<Rc<dyn Fn()>>>,
}

/// How often the runtime looks whether the network's policy changed, or the hub a project waits for is here.
pub const POLICY_CHECK_TICKS: u64 = 20;

impl<'a> ServerEvents<'a> {
    pub fn new(session: Session<'a>) -> ServerEvents<'a> {
        let info = session.platform().info();
        let profiler = Rc::new(crate::profile::Profiler::new(
            std::path::PathBuf::from("profiles"),
            format!(
                "Minecraft {}, NetherForge {}",
                info.minecraft_version, info.plugin_version
            ),
        ));
        let events = ServerEvents {
            game: GameEventDispatch(Current(RefCell::new(session))),
            ticks: std::cell::Cell::new(0),
            bridge: RefCell::new(None),
            profiler: RefCell::new(profiler.clone()),
            after_tick: RefCell::new(None),
        };
        events.set_profiler(profiler);
        events
    }

    /// The profiler every run of the session feeds: the adapter's, writing reports into the plugin's `profiles`
    /// folder.
    pub fn set_profiler(&self, profiler: Rc<crate::profile::Profiler>) {
        let session = self.session();
        profiler.set_log(session.platform_rc());
        if let Some(bridge) = self.bridge() {
            profiler.attach_bridge(bridge);
        }
        session.set_profiler(Some(profiler.clone()));
        *self.profiler.borrow_mut() = profiler;
    }

    /// What runs at the end of every tick, once nothing holds the session (`/nf`'s held work).
    pub fn set_after_tick(&self, after: Option<Rc<dyn Fn()>>) {
        *self.after_tick.borrow_mut() = after;
    }

    /// The profiler.
    pub fn profiler(&self) -> Rc<crate::profile::Profiler> {
        self.profiler.borrow().clone()
    }

    /// Runs the dev bridge with the server: what the editor asks is answered at the start of each tick, and scripts'
    /// lines and errors go to its console. The session should run on `bridge.platform()`, so the runtime's own log
    /// lines reach the editor too.
    pub fn attach_bridge(&self, bridge: Rc<crate::bridge::Bridge>) {
        *self.session().shared().console.borrow_mut() = Some(bridge.console());
        self.profiler().attach_bridge(bridge.clone());
        *self.bridge.borrow_mut() = Some(bridge);
    }

    /// The dev bridge, when there's one.
    pub fn bridge(&self) -> Option<Rc<crate::bridge::Bridge>> {
        self.bridge.borrow().clone()
    }

    /// The session, shared, unless it's being changed.
    pub fn try_session(&self) -> Result<Ref<'_, Session<'a>>, std::cell::BorrowError> {
        self.game.0.0.try_borrow()
    }

    /// The session to change, unless something holds it.
    pub fn session_mut_now(&self) -> Option<RefMut<'_, Session<'a>>> {
        self.game.0.0.try_borrow_mut().ok()
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

/// The project items' side of the running session.
fn items(s: &Running) -> crate::project_items::ItemContext {
    crate::project_items::ItemContext {
        shared: s.session().shared().clone(),
        scripts: s.room().scripts().clone(),
    }
}

/// The screens' side of the running session.
fn screens(s: &Running) -> crate::screen::ScreensContext {
    crate::screen::ScreensContext {
        shared: s.session().shared().clone(),
        scripts: s.room().scripts().clone(),
    }
}

/// The project blocks' side of the running session.
fn blocks(s: &Running) -> crate::custom_blocks::BlocksContext {
    crate::custom_blocks::BlocksContext {
        shared: s.session().shared().clone(),
        scripts: s.room().scripts().clone(),
    }
}

/// A click on `block`, once the item's and the player's handlers let it through: one on one of the project's
/// blocks is heard by its block (`click`), and a right click with an item that places a block puts it on the face
/// that was clicked (`place` on that block first). True cancels the click.
fn block_click(
    s: &Running,
    player: &PlayerRef,
    button: ClickButton,
    block: Option<&BlockRef>,
    face: Option<&str>,
    item: Option<&ItemData>,
    hand: &str,
) -> bool {
    let (Some(block), Some(face)) = (block, face) else {
        return false;
    };
    let context = blocks(s);
    let shared = s.session().shared().clone();
    if !shared.blocks.borrow().is_usable() {
        return false;
    }
    let here = shared
        .blocks
        .borrow()
        .at(&block.world, block.x, block.y, block.z);
    if let Some(name) = here.filter(|n| shared.blocks.borrow().has(n)) {
        let stages = [(
            "click",
            Some(Handle::ProjectBlock(handles::ProjectBlock { id: name })),
        )];
        let clicked = raise(s, &stages, codecs::ProjectBlockClickEvent, || {
            shapes::ProjectBlockClickEvent {
                player: s.player(player),
                block: handles::CustomBlock {
                    world: block.world.clone(),
                    position: crate::events::pack(block.x, block.y, block.z),
                },
                click: button.lua_name().to_owned(),
                face: face.to_owned(),
                item: item.map(|i| s.item(i)),
                hand: hand.to_owned(),
            }
        });
        if cancelled(clicked) {
            return true;
        }
    }
    if button != ClickButton::Right {
        return false;
    }
    let Some(item) = item else { return false };
    let Some(name) = shared.items.borrow().block_of(item) else {
        return false;
    };
    let click = crate::custom_blocks::Click {
        player,
        against: block,
        face,
        item,
        hand,
    };
    context.place_from_item(&click, &name, |placed, facing| {
        let stages = [(
            "place",
            Some(Handle::ProjectBlock(handles::ProjectBlock {
                id: name.clone(),
            })),
        )];
        cancelled(raise(s, &stages, codecs::ProjectBlockPlaceEvent, || {
            shapes::ProjectBlockPlaceEvent {
                player: Some(s.player(player)),
                block: placed,
                item: Some(s.item(item)),
                facing: facing.map(|f| crate::custom_blocks::facing_name(f).to_owned()),
            }
        }))
    });
    // A block-placing item places or is refused: it's never used as the item it is.
    true
}

/// `stages` with the item's own stage first (`event` on the project item `item` is a stack of, if any).
fn with_item<'s>(item: Option<Handle>, event: &'s str, stages: &[Stage<'s>]) -> Vec<Stage<'s>> {
    item.map(|h| (event, Some(h)))
        .into_iter()
        .chain(stages.iter().cloned())
        .collect()
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
        // What the editor asked since the last tick, before any script of this one runs.
        let bridge = self.bridge.borrow().clone();
        if let Some(bridge) = &bridge {
            bridge.run(self);
        }
        let wanted = match self.any() {
            Some(session) => {
                session.tick();
                session.shared().packs.borrow().wanted
            }
            None => false,
        };
        // `nf.packs.rebuild` asked during the tick: read again from disk once nothing is running.
        if wanted && let Ok(mut session) = self.game.0.0.try_borrow_mut() {
            session.rebuild_packs();
        }
        let ticks = self.ticks.get() + 1;
        self.ticks.set(ticks);
        // The network's policy for this server came, or changed, or the hub a project waits for is here: a new run.
        if ticks.is_multiple_of(POLICY_CHECK_TICKS)
            && let Ok(mut session) = self.game.0.0.try_borrow_mut()
            && let Some(why) = session.hub_changed()
        {
            session.platform().log().info(why.to_owned());
            session.restart();
        }
        if let Some(bridge) = &bridge {
            bridge.tick(self);
        }
        // Reports written since: whoever asked for them is told.
        self.profiler().poll();
        // The end of `/nf shutdown`: the server is asked to stop.
        let stop = self
            .any()
            .and_then(|s| s.shared().network.borrow_mut().stop_server.take());
        if let Some(reason) = stop
            && let Some(session) = self.any()
        {
            session.platform().shutdown_server(reason);
        }
        let after = self.after_tick.borrow().clone();
        if let Some(after) = after {
            after();
        }
    }

    fn item_rendered(&self, stack: ItemData) -> ItemData {
        match self.open() {
            Some(s) => items(&s).render(stack),
            None => stack,
        }
    }

    fn item_viewing(&self, reference: Option<String>) -> bool {
        self.open()
            .is_some_and(|s| items(&s).viewing(reference.as_deref()))
    }

    fn item_viewed(&self, player: Uuid, stack: ItemData) -> Option<ItemView> {
        self.open().and_then(|s| items(&s).view(player, &stack))
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
        // A click aimed at a screen within its click range is the screen's, before anything in the world hears it.
        if screens(&s).swing(&player, button) {
            return true;
        }
        let stages = with_item(
            items(&s).stage(item.as_ref()),
            "interact",
            &[
                player_stage(&s, "interact", &player),
                ("player_interact", None),
            ],
        );
        cancelled(raise(&s, &stages, codecs::PlayerInteractEvent, || {
            shapes::PlayerInteractEvent {
                player: s.player(&player),
                click: button.lua_name().to_owned(),
                block: block.as_ref().map(|b| s.block(b)),
                face: face.clone(),
                item: item.as_ref().map(|i| s.item(i)),
                hand: hand.clone(),
            }
        })) || block_click(
            &s,
            &player,
            button,
            block.as_ref(),
            face.as_deref(),
            item.as_ref(),
            &hand,
        )
    }

    fn player_use_item(&self, player: PlayerRef, item: ItemData, hand: String) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = with_item(
            items(&s).stage(Some(&item)),
            "use",
            &[
                player_stage(&s, "use_item", &player),
                ("player_use_item", None),
            ],
        );
        cancelled(raise(&s, &stages, codecs::PlayerUseItemEvent, || {
            shapes::PlayerUseItemEvent {
                player: s.player(&player),
                item: s.item(&item),
                hand,
            }
        }))
    }

    fn draws_without_ammo(&self, item: ItemData) -> bool {
        self.open().is_some_and(|s| {
            s.session()
                .shared()
                .items
                .borrow()
                .draws_without_ammo(&item)
        })
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
        let custom = blocks(&s);
        // One of the project's blocks is broken by the runtime (its drops, its sounds, its scripts' say): the
        // server's own break is cancelled.
        if let Some((name, rolled, creative)) = custom.breaking(&player, &block) {
            let stages = with_item(
                items(&s).held_stage(player.uuid, "main_hand"),
                "break_block",
                &[
                    (
                        "break",
                        Some(Handle::ProjectBlock(handles::ProjectBlock {
                            id: name.clone(),
                        })),
                    ),
                    ("block_break", Some(Handle::World(s.world(&block.world)))),
                    ("block_break", None),
                ],
            );
            let drops = if s.listening(&stages) {
                let payload = shapes::BlockBreakEvent {
                    player: s.player(&player),
                    block: s.block(&block),
                    state: block.state.clone(),
                    drops: rolled.iter().map(|i| s.item(i)).collect(),
                    experience: i64::from(experience),
                };
                let out = s.emit(&stages, codecs::BlockBreakEvent, payload)?;
                if out.cancelled {
                    return None;
                }
                match creative {
                    true => Vec::new(),
                    false => drops_back(&s, &out, "drops", &rolled).unwrap_or(rolled),
                }
            } else {
                rolled
            };
            custom.broken(&player, &block, &name, drops);
            s.session().block_changed(&block);
            return None;
        }
        // One of the game's with rules of the project's drops what its rule says instead of the game's drops.
        let ruled = custom.game_drops(&player, &block);
        let stages = with_item(
            items(&s).held_stage(player.uuid, "main_hand"),
            "break_block",
            &[
                ("block_break", Some(Handle::World(s.world(&block.world)))),
                ("block_break", None),
            ],
        );
        let mut answer = DropsAnswer {
            drops: ruled.clone(),
            experience,
        };
        if s.listening(&stages) {
            let before = ruled.clone().unwrap_or_else(drops);
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
                drops: match &ruled {
                    Some(_) => Some(drops_back(&s, &out, "drops", &before).unwrap_or(before)),
                    None => drops_back(&s, &out, "drops", &before),
                },
                experience: experience_back(&s, &out, "experience", experience),
            };
        }
        s.session().block_changed(&block);
        Some(answer)
    }

    /// A player let go of the block they were mining, or looked away.
    fn block_stop_break(&self, player: PlayerRef, block: BlockRef) {
        let Some(s) = self.open() else { return };
        let shared = s.session().shared().clone();
        shared.mining.borrow_mut().stopped_breaking(
            &*shared.platform,
            &player,
            &block.world,
            (block.x, block.y, block.z),
        );
    }

    fn world_broke(&self, block: BlockRef) -> Option<Vec<ItemData>> {
        let s = self.open()?;
        blocks(&s).world_broke(&block)
    }

    fn block_updated(&self, block: BlockRef) {
        if let Some(s) = self.open() {
            blocks(&s).block_updated(&block);
        }
    }

    fn falling_landed(&self, entity: Uuid, block: BlockRef) {
        if let Some(s) = self.open() {
            blocks(&s).falling_landed(entity, &block);
        }
    }

    fn pick_block(
        &self,
        player: PlayerRef,
        block: BlockRef,
        inventory: Vec<Option<ItemData>>,
    ) -> Option<PickAnswer> {
        let s = self.open()?;
        blocks(&s).picking(&player, &block, &inventory)
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
        let stages = with_item(
            items(&s).stage(Some(&item)),
            "drop",
            &[
                player_stage(&s, "drop_item", &player),
                ("player_drop_item", None),
            ],
        );
        cancelled(raise(&s, &stages, codecs::PlayerItemEvent, || {
            shapes::PlayerItemEvent {
                player: s.player(&player),
                item: s.item(&item),
            }
        }))
    }

    fn player_pickup_item(&self, player: PlayerRef, item: ItemData, entity: Uuid) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = with_item(
            items(&s).stage(Some(&item)),
            "pickup",
            &[
                player_stage(&s, "pickup_item", &player),
                ("player_pickup_item", None),
            ],
        );
        let stopped = cancelled(raise(&s, &stages, codecs::PlayerPickupItemEvent, || {
            shapes::PlayerPickupItemEvent {
                player: s.player(&player),
                item: s.item(&item),
                entity: handles::DroppedItem {
                    id: crate::events::uuid_text(&entity),
                },
            }
        }));
        // What they picked up may be a stack from before an item changed: it's in their inventory by the next tick.
        if !stopped {
            items(&s).refresh_soon(player.uuid);
        }
        stopped
    }

    fn player_consume_item(&self, player: PlayerRef, item: ItemData) -> bool {
        let Some(s) = self.open() else { return false };
        let stages = with_item(
            items(&s).stage(Some(&item)),
            "consume",
            &[
                player_stage(&s, "consume_item", &player),
                ("player_consume_item", None),
            ],
        );
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

    /// Markers of centities in a structure came into the world: each turns into its centity.
    fn structure_markers_loaded(&self, markers: Vec<StructureMarker>) {
        let Some(s) = self.open() else { return };
        crate::structures::StructuresContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .spawn(markers);
    }

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
        // A weapon's item hears its hit first.
        let weapon = attacker
            .filter(|_| matches!(cause.as_str(), "entity_attack" | "entity_sweep_attack"))
            .and_then(|a| items(&s).held_stage(a, "main_hand"));
        let stages = with_item(
            weapon,
            "hit",
            &[
                ("damage", Some(Handle::Entity(target.clone()))),
                ("entity_damage", None),
            ],
        );
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

    /// A container the world's generation made, opened for the first time, names one of the project's tables (the
    /// start-up datapack's stand-in): rolled now, for whoever opened it, there.
    fn generated_loot(
        &self,
        table: String,
        player: Option<Uuid>,
        location: Location,
    ) -> Option<Vec<ItemData>> {
        let s = self.open()?;
        let shared = s.session().shared().clone();
        let log = shared.platform.log();
        let Some(id) = shared.loot.borrow().id_of(&table) else {
            log.warn(format!(
                "A generated container at {} {} {} {} names the loot table \"{table}\", which isn't running: it's left empty",
                location.world, location.x as i64, location.y as i64, location.z as i64
            ));
            return Some(Vec::new());
        };
        let game = crate::world::GameContext::new(shared.clone(), s.room().scripts().clone());
        let roll = crate::loot::LootRoll {
            player,
            location: Some(location),
            ..Default::default()
        };
        let mut seed = [0u8; 8];
        getrandom::fill(&mut seed).expect("the system has randomness");
        match game.roll_loot(&id, &roll, i64::from_le_bytes(seed)) {
            Ok(items) => Some(items),
            Err(e) => {
                log.warn(format!(
                    "Rolling the loot table \"{table}\" for a generated container failed: {}",
                    e.0
                ));
                Some(Vec::new())
            }
        }
    }

    fn player_interact_entity(&self, player: PlayerRef, entity: Uuid, hand: String) -> bool {
        let Some(s) = self.open() else { return false };
        let target = s.entity(&entity);
        let held = items(&s).held_stage(player.uuid, &hand);
        let mut stages = vec![("interact", Some(Handle::Entity(target.clone())))];
        stages.extend(held.map(|h| ("interact_entity", Some(h))));
        stages.push(player_stage(&s, "interact_entity", &player));
        stages.push(("player_interact_entity", None));
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

    /// Only our packs' statuses are kept; scripts hear each (the `onDemand` pack's name, or none for the project's).
    fn resource_pack_status(&self, player: Uuid, pack: Uuid, status: Option<String>) {
        let Some(s) = self.open() else { return };
        let changed = s
            .session()
            .shared()
            .packs
            .borrow_mut()
            .status_changed(player, pack, status);
        let Some((name, status)) = changed else {
            return;
        };
        let who = PlayerRef {
            uuid: player,
            name: String::new(),
        };
        let stages = [
            player_stage(&s, "resource_pack_status", &who),
            ("player_resource_pack_status", None),
        ];
        raise(&s, &stages, codecs::PlayerResourcePackStatusEvent, || {
            shapes::PlayerResourcePackStatusEvent {
                player: s.player(&who),
                pack: name.map(|n| {
                    let shared = s.session().shared().clone();
                    let names = shared.names.borrow();
                    let home = names.home.clone();
                    crate::names::Calling {
                        names: &names,
                        from: home,
                    }
                    .spell(&n)
                }),
                status,
            }
        });
    }

    fn menu_clicked(&self, window: Uuid, click: MenuClick) -> bool {
        let Some(s) = self.open() else { return false };
        crate::menu::MenuContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .click(window, click)
    }

    fn menu_dragged(
        &self,
        window: Uuid,
        player: PlayerRef,
        slots: Vec<i32>,
        cursor: Option<ItemData>,
        own_slots: Vec<i32>,
        click: String,
    ) -> bool {
        let Some(s) = self.open() else { return false };
        crate::menu::MenuContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .drag(window, &player, &slots, cursor.as_ref(), &own_slots, &click)
    }

    fn menu_closed(&self, window: Uuid, player: PlayerRef) {
        let Some(s) = self.open() else { return };
        crate::menu::MenuContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .closed(window, &player);
    }

    /// What a viewer's click or drag moved: the bound slots' storages hold it now.
    fn menu_changed(&self, window: Uuid, slots: Vec<i32>) {
        let Some(s) = self.open() else { return };
        crate::menu::MenuContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .changed(window, &slots);
    }

    fn dialog_pressed(
        &self,
        player: PlayerRef,
        dialog: String,
        button: String,
        values: HashMap<String, Dynamic>,
    ) {
        let Some(s) = self.open() else { return };
        crate::dialog::DialogContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .pressed(&player, &dialog, &button, values, false);
    }

    fn dialog_closed(&self, player: PlayerRef, dialog: String) {
        let Some(s) = self.open() else { return };
        crate::dialog::DialogContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .closed(&player, &dialog, false);
    }

    fn custom_clicked(
        &self,
        player: PlayerRef,
        id: String,
        answers: Rc<dyn DialogAnswers>,
    ) -> bool {
        let Some(s) = self.open() else { return false };
        crate::dialog::DialogContext {
            shared: s.session().shared().clone(),
            scripts: s.room().scripts().clone(),
        }
        .registry_clicked(&player, &id, &*answers)
    }
}
