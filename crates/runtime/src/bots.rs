//! Bots on the Rust runtime (Kotlin's `bots/ScriptBots`, `api/BotImpl.kt` and the runtime's half of
//! `bridge/BotsBridge.kt`): `nf.bots` behind `requires.bots`, the `Bot` handle's controls, and the one way anything of
//! the runtime drives a bot ([`BotsContext::act`]). The bot itself (its connection, the client's half, movement) is
//! the adapter's: everything here goes through the Platform's `BotOps`.
//!
//! - **A script's bot belongs to the scope that joined it** and leaves when that scope is released, and every one
//!   leaves when the session stops: a bot is a new player each time (a fresh UUID, never saved), so there's nothing
//!   to carry across a reload. The owner's `bots:` ([`BotsConfig`]) decides whether scripts may join any and how many
//!   may be online at once; the bridge's own bots aren't counted, and every bot online is a `Bot` to scripts.
//! - **A `Player` handle becomes a `Bot`** when a `Bot` method is called on it (the API crate's refining `__index`,
//!   asking [`crate::Shared::class_of_id`]): the server isn't asked whether every player it hands out is a bot.
//! - **What takes no ticks answers whether it was done**: the Platform calls an action's `done` at once for those, so
//!   a failure is known as `act` returns. A walk and a mine are async, their value delivered at a tick boundary.

use std::cell::RefCell;
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::task::{Context, Poll};

use indexmap::IndexMap;
use netherforge_api::api::{BotApi, NfBotsApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, Pending, shapes, unions, values};
use netherforge_format::Problem;
use netherforge_format::bridge::{
    BotActResult, BotAction, BotClick, BotHand, BotPackAnswer, BotPosition, BotState,
    DEFAULT_WALK_TICKS, MAX_TICKS,
};
use netherforge_luau::ScopeId;
use netherforge_platform::{BotAim, BotJoin, Platform, PlayerRef, ScriptBot, SkinTexture, Uuid};

use crate::Room;
use crate::centity::api::CentityContext;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::Scripts;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// `bots:` in `config.yml`: whether scripts may join bots (`nf.bots`, for a package that declares `requires.bots`),
/// off by default (the editor's dev server always allows them), and at most `max` of them online at once across every
/// package. The bridge's own bots (tests, coding agents) aren't held to either.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BotsConfig {
    pub enabled: bool,
    pub max: usize,
}

impl Default for BotsConfig {
    fn default() -> BotsConfig {
        BotsConfig {
            enabled: false,
            max: 20,
        }
    }
}

/// A script's bot: `uuid` is set once it's in the world.
struct Owned {
    name: String,
    scope: ScopeId,
    uuid: std::cell::Cell<Option<Uuid>>,
}

/// What `Shared::bots` holds: the owner's `bots:`, the bots scripts joined, and who declares them.
#[derive(Default)]
pub struct ScriptBots {
    pub config: BotsConfig,
    /// By name in lowercase, joining ones included: what counts towards `bots.max`.
    owned: IndexMap<String, Rc<Owned>>,
    /// The project's namespace, and the namespaces whose manifest declares `requires.bots`, the project's first.
    home: String,
    declaring: Vec<String>,
}

fn key(name: &str) -> String {
    name.to_lowercase()
}

/// A player's name, as the platform's bots take one: 3 to 16 letters, digits or `_`.
fn valid_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// A value the Platform's callback gives once, as a future the room polls at its tick boundary.
pub struct Answer<T>(Slot<T>);

/// Where the Platform's callback puts an [`Answer`]'s value.
type Slot<T> = Rc<RefCell<Option<Result<T, String>>>>;

impl<T> Answer<T> {
    fn new() -> (Answer<T>, Slot<T>) {
        let cell = Rc::new(RefCell::new(None));
        (Answer(cell.clone()), cell)
    }

    fn failed(why: String) -> Answer<T> {
        Answer(Rc::new(RefCell::new(Some(Err(why)))))
    }

    /// Whether it has failed already.
    pub fn has_failed(&self) -> bool {
        matches!(&*self.0.borrow(), Some(Err(_)))
    }
}

impl<T> Future for Answer<T> {
    type Output = Result<T, String>;

    fn poll(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Self::Output> {
        match self.0.borrow_mut().take() {
            Some(value) => Poll::Ready(value),
            None => Poll::Pending,
        }
    }
}

fn pending<T: 'static, U: 'static>(
    answer: Answer<T>,
    then: impl FnOnce(T) -> U + 'static,
) -> Pending<U> {
    Box::pin(async move { answer.await.map(then).map_err(ApiError) })
}

/// `nf.bots` and `Bot`, and the runtime's way to drive any bot.
#[derive(Clone)]
pub struct BotsContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl BotsContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// Joins a bot for `scope` (Kotlin's `ScriptBots.join`): its UUID once it's in the world, or why not (bots are
    /// off, too many, the name is taken, the server turned it away). A name that can't be a player's is the script's
    /// mistake, an error at its line.
    pub fn join(
        &self,
        scope: ScopeId,
        name: &str,
        at: Option<BotPosition>,
        skin: Option<SkinTexture>,
    ) -> ApiResult<Answer<Uuid>> {
        if !valid_name(name) {
            return Err(ApiError(format!(
                "bad argument 'name' (3 to 16 letters, digits or _ expected, got \"{name}\")"
            )));
        }
        let failed = {
            let bots = self.shared.bots.borrow();
            if !bots.config.enabled {
                Some("bots are off on this server: the server owner turns them on with bots.enabled in config.yml".to_owned())
            } else if bots.owned.len() >= bots.config.max {
                Some(format!(
                    "{} bots are online already, as many as this server allows (bots.max in config.yml)",
                    bots.config.max
                ))
            } else if bots.owned.contains_key(&key(name))
                || self.platform().players().find(name.into()).is_some()
            {
                Some(format!("someone called {name} is online already"))
            } else {
                None
            }
        };
        if let Some(why) = failed {
            return Ok(Answer::failed(why));
        }
        let bot = Rc::new(Owned {
            name: name.to_owned(),
            scope,
            uuid: Default::default(),
        });
        self.shared
            .bots
            .borrow_mut()
            .owned
            .insert(key(name), bot.clone());
        let (answer, cell) = Answer::new();
        let shared = Rc::downgrade(&self.shared);
        let join = BotJoin {
            name: name.to_owned(),
            at,
            pack: BotPackAnswer::Loaded,
            script: Some(ScriptBot { skin }),
        };
        self.platform().bots().join(
            join,
            Box::new(move |result| {
                let Some(shared) = shared.upgrade() else {
                    return;
                };
                let ours = shared
                    .bots
                    .borrow()
                    .owned
                    .get(&key(&bot.name))
                    .is_some_and(|o| Rc::ptr_eq(o, &bot));
                let value = match result {
                    Ok(info) => match parse_uuid(&info.uuid) {
                        Some(uuid) if ours => {
                            bot.uuid.set(Some(uuid));
                            Ok(uuid)
                        }
                        Some(_) => {
                            // Its scope went while it was joining: it leaves as it arrives.
                            shared.platform.bots().leave(bot.name.clone());
                            Err(format!(
                                "{}'s script stopped while it was joining",
                                bot.name
                            ))
                        }
                        None => Err(format!("{} couldn't join", bot.name)),
                    },
                    Err(why) => {
                        if ours {
                            shared.bots.borrow_mut().owned.shift_remove(&key(&bot.name));
                        }
                        Err(why.message)
                    }
                };
                *cell.borrow_mut() = Some(value);
            }),
        );
        Ok(answer)
    }

    /// Disconnects bot `name`, whoever joined it; false when no bot of that name is online.
    pub fn leave(&self, name: &str) -> bool {
        self.shared.bots.borrow_mut().owned.shift_remove(&key(name));
        let bots = self.platform().bots();
        let online = bots
            .list()
            .iter()
            .any(|b| b.name.eq_ignore_ascii_case(name));
        if online {
            bots.leave(name.to_owned());
        }
        online
    }

    /// Has bot `id` do `action` as its client would: done once the platform says so (now, or ticks later for a walk
    /// or a mine), or a failure saying why it couldn't. A bot that has left fails at once.
    pub fn act(&self, id: Uuid, action: BotAction, aim: Option<BotAim>) -> Answer<BotActResult> {
        let Some(name) = self.name_of(id) else {
            return Answer::failed("the bot has left".into());
        };
        let (answer, cell) = Answer::new();
        let failed = name.clone();
        self.platform().bots().act(
            name,
            action,
            aim,
            Box::new(move |result| {
                *cell.borrow_mut() = Some(result.map_err(|why| {
                    if why.message.is_empty() {
                        format!("{failed} couldn't")
                    } else {
                        why.message
                    }
                }));
            }),
        );
        answer
    }

    /// `act` for what takes no ticks: whether the bot did it. (Something that would take ticks counts as done once
    /// it has started.)
    pub fn did(&self, id: Uuid, action: BotAction, aim: Option<BotAim>) -> bool {
        !self.act(id, action, aim).has_failed()
    }

    /// The online bot `id`'s name, or none when it isn't one (it left, or it was never a bot).
    pub fn name_of(&self, id: Uuid) -> Option<String> {
        let player = self.platform().players().get(id)?;
        self.platform().bots().is_bot(id).then_some(player.name)
    }

    /// Every bot online, whoever joined it.
    pub fn online(&self) -> Vec<Uuid> {
        self.platform()
            .bots()
            .list()
            .iter()
            .filter_map(|b| parse_uuid(&b.uuid))
            .collect()
    }

    fn did_handle(&self, this: &handles::Bot, action: BotAction, aim: Option<BotAim>) -> bool {
        parse_uuid(&this.id).is_some_and(|id| self.did(id, action, aim))
    }

    fn act_handle(&self, this: &handles::Bot, action: BotAction) -> Answer<BotActResult> {
        match parse_uuid(&this.id) {
            Some(id) => self.act(id, action, None),
            None => Answer::failed("the bot has left".into()),
        }
    }

    /// What a click on `target` names: an entity by UUID, or for a centity's node, the runtime's aim at its hitbox
    /// (its interaction entity and middle, which only the runtime knows). None when it's gone or has no hitbox.
    fn aim_at(&self, target: &Handle) -> Option<(Option<String>, Option<BotAim>)> {
        if let Handle::Node(node) = target {
            let id = parse_uuid(&node.centity)?;
            let known = self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| i.index_of(&node.name).is_some());
            if !known {
                return None;
            }
            let (entity, at) = self.centities().aim(id, Some(&node.name))?;
            return Some((None, Some(BotAim { entity, at })));
        }
        if !netherforge_api::handle::is_a(target.class(), "Entity") {
            return None;
        }
        let id = match target.key().first() {
            Some(netherforge_api::handle::KeyPart::Str(id)) => parse_uuid(id)?,
            _ => return None,
        };
        Some((Some(uuid_text(&id)), None))
    }

    fn centities(&self) -> CentityContext {
        CentityContext::new(self.shared.clone(), self.scripts.clone())
    }

    /// The face of the block at `x`, `y`, `z` that faces bot `id`'s eyes, as a player mines it.
    fn face_toward(&self, id: Uuid, x: i32, y: i32, z: i32) -> Option<&'static str> {
        let eye = self.platform().players().eye(id)?;
        let dx = eye.x - (x as f64 + 0.5);
        let dy = eye.y - (y as f64 + 0.5);
        let dz = eye.z - (z as f64 + 0.5);
        Some(if dy.abs() >= dx.abs() && dy.abs() >= dz.abs() {
            if dy > 0.0 { "up" } else { "down" }
        } else if dx.abs() >= dz.abs() {
            if dx > 0.0 { "east" } else { "west" }
        } else if dz > 0.0 {
            "south"
        } else {
            "north"
        })
    }

    // ---- the bridge's half (Kotlin's `BotsBridge`): the bridge server calls these once it's ported ----

    /// Where a bridge `interact` or `attack` naming a centity's node aims: the node's interaction entity and the
    /// middle of its hitbox, which only the runtime knows. None for any other action; an error the bridge answers
    /// with when the centity or node isn't there, or has nothing to click.
    pub fn bridge_aim(&self, action: &BotAction) -> Result<Option<BotAim>, String> {
        let (centity, node) = match action {
            BotAction::Interact { centity, node, .. } | BotAction::Attack { centity, node, .. } => {
                (centity, node)
            }
            _ => return Ok(None),
        };
        let Some(centity) = centity else {
            if node.is_some() {
                return Err(
                    "`node` names a node of the centity given as `centity`, and there's none"
                        .into(),
                );
            }
            return Ok(None);
        };
        let missing = || format!("no centity instance \"{centity}\"");
        let id = parse_uuid(centity).ok_or_else(missing)?;
        let kind = {
            let centities = self.shared.centities.borrow();
            let instance = centities
                .get(id)
                .filter(|i| !i.removed)
                .ok_or_else(missing)?;
            if let Some(node) = node
                && instance.index_of(node).is_none()
            {
                return Err(format!(
                    "centity {} has no node \"{node}\"",
                    instance.centity()
                ));
            }
            instance.centity().to_owned()
        };
        let nothing = match node {
            Some(node) => format!("node \"{node}\" of centity {kind} has no hitbox"),
            None => format!("centity {kind} has nothing"),
        };
        let (entity, at) = self
            .centities()
            .aim(id, node.as_deref())
            .ok_or_else(|| format!("{nothing} to click"))?;
        Ok(Some(BotAim { entity, at }))
    }

    /// A bridge `bots/state` with its target's centity and node filled in when it looks at a centity's hitbox: which
    /// node a click there reaches is the runtime's to say, from the same line of sight a click is picked by.
    pub fn bridge_state(&self, state: BotState) -> BotState {
        let Some(target) = &state.target else {
            return state;
        };
        let (Some(entity), Some(player)) = (
            target.entity.as_deref().and_then(parse_uuid),
            parse_uuid(&state.uuid),
        ) else {
            return state;
        };
        let sight = self.platform().players().eye(player);
        let Some((instance, node)) = self
            .centities()
            .click_target(entity, player, sight.as_ref())
        else {
            return state;
        };
        let mut target = target.clone();
        target.centity = Some(uuid_text(&instance));
        target.node = node;
        BotState {
            target: Some(target),
            ..state
        }
    }
}

fn hand(off_hand: Option<bool>) -> BotHand {
    if off_hand == Some(true) {
        BotHand::Off
    } else {
        BotHand::Main
    }
}

fn block(coordinate: f64) -> i32 {
    coordinate.floor() as i32
}

impl NfBotsApi for BotsContext {
    fn join(
        &self,
        caller: &Caller,
        name: String,
        location_or_position: Option<unions::LocationOrVec3>,
        options: Option<shapes::BotJoinOptions>,
    ) -> ApiResult<Pending<handles::Bot>> {
        let at = location_or_position.map(|place| match place {
            unions::LocationOrVec3::Location(l) => BotPosition {
                x: l.position.x,
                y: l.position.y,
                z: l.position.z,
                world: Some(l.world.name),
                yaw: Some(l.yaw.unwrap_or(0.0)),
                pitch: Some(l.pitch.unwrap_or(0.0)),
            },
            unions::LocationOrVec3::Vec3(v) => BotPosition {
                x: v.x,
                y: v.y,
                z: v.z,
                world: Some(self.platform().worlds().default_world()),
                yaw: Some(0.0),
                pitch: Some(0.0),
            },
        });
        let skin = options.and_then(|o| o.skin).map(|s| SkinTexture {
            value: s.texture,
            signature: s.signature,
        });
        let answer = BotsContext::join(self, caller.scope, &name, at, skin)?;
        Ok(pending(answer, |uuid| handles::Bot {
            id: uuid_text(&uuid),
        }))
    }

    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::Bot>> {
        Ok(self
            .online()
            .iter()
            .map(|id| handles::Bot { id: uuid_text(id) })
            .collect())
    }
}

impl BotApi for BotsContext {
    fn leave(&self, this: &handles::Bot) -> ApiResult<bool> {
        let Some(name) = parse_uuid(&this.id).and_then(|id| self.name_of(id)) else {
            return Ok(false);
        };
        Ok(BotsContext::leave(self, &name))
    }

    fn walk_to(
        &self,
        this: &handles::Bot,
        position: values::Vec3,
        options: Option<shapes::BotWalkOptions>,
    ) -> ApiResult<Pending<values::Vec3>> {
        let options = options.as_ref();
        let timeout = options
            .and_then(|o| o.timeout)
            .unwrap_or(DEFAULT_WALK_TICKS as i64);
        if !(1..=MAX_TICKS as i64).contains(&timeout) {
            return Err(ApiError(format!(
                "bad argument 'options.timeout' (1 to {MAX_TICKS} ticks expected, got {timeout})"
            )));
        }
        let walk = BotAction::WalkTo {
            x: position.x,
            z: position.z,
            sprint: options.and_then(|o| o.sprint) == Some(true),
            timeout_ticks: timeout as i32,
        };
        Ok(pending(self.act_handle(this, walk), |r| values::Vec3 {
            x: r.x,
            y: r.y,
            z: r.z,
        }))
    }

    fn stop(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Stop, None))
    }

    fn look_at(&self, this: &handles::Bot, point: values::Vec3) -> ApiResult<bool> {
        let look = BotAction::LookAt {
            x: point.x,
            y: point.y,
            z: point.z,
        };
        Ok(self.did_handle(this, look, None))
    }

    fn jump(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Jump, None))
    }

    fn set_sneaking(&self, this: &handles::Bot, sneaking: bool) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Sneak { on: sneaking }, None))
    }

    fn set_sprinting(&self, this: &handles::Bot, sprinting: bool) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Sprint { on: sprinting }, None))
    }

    fn chat(&self, this: &handles::Bot, message: String) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Chat { message }, None))
    }

    fn attack(&self, this: &handles::Bot, target: Handle) -> ApiResult<bool> {
        let Some((entity, aim)) = self.aim_at(&target) else {
            return Ok(false);
        };
        let attack = BotAction::Attack {
            entity,
            centity: None,
            node: None,
            x: None,
            y: None,
            z: None,
        };
        Ok(self.did_handle(this, attack, aim))
    }

    fn interact(
        &self,
        this: &handles::Bot,
        target: Handle,
        options: Option<shapes::BotInteractOptions>,
    ) -> ApiResult<bool> {
        let Some((entity, aim)) = self.aim_at(&target) else {
            return Ok(false);
        };
        let at = options.as_ref().and_then(|o| o.at);
        let interact = BotAction::Interact {
            entity,
            centity: None,
            node: None,
            hand: hand(options.as_ref().and_then(|o| o.off_hand)),
            x: at.as_ref().map(|a| a.x),
            y: at.as_ref().map(|a| a.y),
            z: at.as_ref().map(|a| a.z),
        };
        Ok(self.did_handle(this, interact, aim))
    }

    fn use_item(&self, this: &handles::Bot, off_hand: Option<bool>) -> ApiResult<bool> {
        let use_item = BotAction::UseItem {
            hand: hand(off_hand),
        };
        Ok(self.did_handle(this, use_item, None))
    }

    fn release_item(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::ReleaseItem, None))
    }

    fn swing(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Swing, None))
    }

    fn use_block(
        &self,
        this: &handles::Bot,
        position: values::Vec3,
        face: Option<String>,
        off_hand: Option<bool>,
    ) -> ApiResult<bool> {
        let use_block = BotAction::UseBlock {
            x: block(position.x),
            y: block(position.y),
            z: block(position.z),
            face: face.unwrap_or_else(|| "up".into()),
            hand: hand(off_hand),
        };
        Ok(self.did_handle(this, use_block, None))
    }

    fn break_block(&self, this: &handles::Bot, position: values::Vec3) -> ApiResult<Pending<bool>> {
        let (x, y, z) = (block(position.x), block(position.y), block(position.z));
        let face = parse_uuid(&this.id)
            .and_then(|id| self.face_toward(id, x, y, z))
            .unwrap_or("up");
        let mine = BotAction::BreakBlock {
            x,
            y,
            z,
            face: face.into(),
        };
        Ok(pending(self.act_handle(this, mine), |_| true))
    }

    fn swap_hands(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::SwapHands, None))
    }

    fn drop_item(&self, this: &handles::Bot, stack: Option<bool>) -> ApiResult<bool> {
        let drop = BotAction::Drop {
            all: stack == Some(true),
        };
        Ok(self.did_handle(this, drop, None))
    }

    fn click_slot(&self, this: &handles::Bot, slot: i64, click: Option<String>) -> ApiResult<bool> {
        let kind = match click.as_deref().unwrap_or("left") {
            "left" => BotClick::Left,
            "right" => BotClick::Right,
            "shift_left" => BotClick::ShiftLeft,
            "shift_right" => BotClick::ShiftRight,
            "drop" => BotClick::Drop,
            "drop_all" => BotClick::DropAll,
            "double" => BotClick::Double,
            "swap_offhand" => BotClick::OffHand,
            other => return Err(ApiError(format!("the binding checked click: {other}"))),
        };
        if !(0..=i32::MAX as i64).contains(&slot) {
            return Ok(false);
        }
        let click = BotAction::ClickSlot {
            slot: slot as i32,
            click: kind,
            hotbar: None,
        };
        Ok(self.did_handle(this, click, None))
    }

    fn respawn(&self, this: &handles::Bot) -> ApiResult<bool> {
        Ok(self.did_handle(this, BotAction::Respawn, None))
    }
}

/// Scripts' bots as a session's service: they leave with the scope that joined them, and all of them as the session
/// stops; `runtime.bots-disabled` on each package that declares bots while the owner has them off.
pub struct BotsService {
    pub shared: Rc<Shared>,
    pub context: RefCell<Option<BotsContext>>,
}

impl BotsService {
    fn leave_where(&self, gone: impl Fn(&Owned) -> bool) {
        let names: Vec<String> = self
            .shared
            .bots
            .borrow()
            .owned
            .values()
            .filter(|o| gone(o))
            .map(|o| o.name.clone())
            .collect();
        let Some(context) = self.context.borrow().clone() else {
            return;
        };
        for name in names {
            context.leave(&name);
        }
    }
}

impl Service for BotsService {
    fn name(&self) -> &'static str {
        "bots"
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.context.borrow_mut() = Some(BotsContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        });
        let loaded = project.project;
        let home = loaded.namespace().to_owned();
        let declares = |ns: &str| {
            loaded
                .manifest_of(ns)
                .and_then(|m| m.requires.as_ref())
                .and_then(|r| r.bots)
                == Some(true)
        };
        let mut bots = self.shared.bots.borrow_mut();
        bots.declaring = std::iter::once(home.clone())
            .chain(loaded.packages.keys().cloned())
            .filter(|ns| declares(ns))
            .collect();
        bots.home = home;
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        self.leave_where(|o| scopes.contains(&o.scope));
    }

    fn player_quit(&self, player: &PlayerRef) {
        self.shared
            .bots
            .borrow_mut()
            .owned
            .retain(|_, o| o.uuid.get() != Some(player.uuid));
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        self.leave_where(|_| true);
        *self.context.borrow_mut() = None;
    }

    fn problems(&self) -> Vec<Problem> {
        let bots = self.shared.bots.borrow();
        if bots.config.enabled {
            return Vec::new();
        }
        bots.declaring
            .iter()
            .map(|namespace| {
                let file = if *namespace == bots.home {
                    netherforge_format::manifest::FILE_NAME.to_owned()
                } else {
                    format!("{namespace}:{}", netherforge_format::manifest::FILE_NAME)
                };
                netherforge_format::codes::RUNTIME_BOTS_DISABLED.at(
                    &file,
                    "Bots are off on this server, so this package's scripts can't join any: the server owner turns them on with bots.enabled in config.yml",
                    Some("$.requires.bots".into()),
                )
            })
            .collect()
    }
}
