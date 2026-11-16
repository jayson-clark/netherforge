//! Cutscenes (Kotlin's `Cutscenes`, `ActiveCutscene`, `NfCutscenesImpl`, `CutsceneImpl`): the project's cutscenes
//! and every one playing.
//!
//! A cutscene is a camera path. Playing one for a player makes a text display with nothing drawn (the camera),
//! puts the player in spectator mode looking through it, and moves it each tick along the path with the display's
//! own teleport easing (one tick, so the client draws the movement between the server's ticks). The server moves a
//! spectating player to what they look through, so their chunks follow the camera; spectator mode is what stops
//! them moving and interacting.
//!
//! What the player was (where, their game mode, whether they could fly, what they spectated) is a [`PlayerState`],
//! put back however the cutscene ends, and kept in the store meanwhile so a crash can't lose it: the player is
//! put back when they next join, before scripts hear. A cutscene played over another keeps the first's state, so a
//! chain never flashes the world. A cutscene belongs to the scope that played it; each run holds the definition it
//! started with, so a reload of the file never moves one playing.
//!
//! No borrow of the cutscenes is held across a call to the server: a teleport or a game mode change is an event,
//! which scripts hear, and they may play or stop a cutscene from it.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{CutsceneApi, NfCutscenesApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, codecs, shapes, unions};
use netherforge_format::Severity;
use netherforge_format::kind::centity::DisplayDef;
use netherforge_format::kind::cutscene::{CameraPose, CompiledCue, CompiledCutscene};
use netherforge_format::math::{Matrix4, Vec3, normalize_degrees};
use netherforge_luau::ScopeId;
use netherforge_platform::{
    DisplayPose, EntityFlag, EntityNumber, EntityRole, EntityTag, Location, Platform, PlayerRef,
    Uuid,
};

use crate::Room;
use crate::centity::api::not_in_project;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

const KIND: &str = "cutscene";
const SPECTATOR: &str = "spectator";
/// The camera's name in the tag it carries, which tells it from a centity's nodes.
pub const CAMERA: &str = "cutscene:camera";
/// `#AARRGGBB`: no colour at all.
const INVISIBLE: &str = "#00000000";
/// Ticks a cue's text fades out over.
const TEXT_FADE_OUT: i32 = 10;
/// Keeps a time that is a whole tick from rounding up to the next.
const EPSILON: f64 = 1e-9;
const TICKS_PER_SECOND: f64 = 20.0;

/// Why a cutscene ended, as `CutsceneEndEvent.reason` says it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndReason {
    Finished,
    Stopped,
    Skipped,
    Replaced,
    PlayerLeft,
    Unloaded,
}

impl EndReason {
    fn name(self) -> &'static str {
        match self {
            EndReason::Finished => "finished",
            EndReason::Stopped => "stopped",
            EndReason::Skipped => "skipped",
            EndReason::Replaced => "replaced",
            EndReason::PlayerLeft => "player_left",
            EndReason::Unloaded => "unloaded",
        }
    }
}

/// What a cutscene changes about a player and puts back when it ends: where they were, their game mode and what
/// they could do with it. (What they were riding isn't put back: they're dismounted for the camera.)
#[derive(Clone, Debug, PartialEq)]
pub struct PlayerState {
    pub location: Location,
    pub game_mode: String,
    pub can_fly: bool,
    pub flying: bool,
    /// The entity they were spectating through, when they were in spectator mode and looking through one.
    pub spectating: Option<Uuid>,
}

/// One cutscene playing for one player.
pub struct Active {
    /// The handle's key: unique for the session's life, never reused.
    pub number: i64,
    /// The cutscene's id as the server knows it (a package's `ns:id`).
    pub kind: String,
    /// The scope that played it, or none for one the runtime played.
    pub owner: Option<ScopeId>,
    pub player: Uuid,
    /// The version it started with: a reload never moves one playing.
    definition: Rc<CompiledCutscene>,
    pub world: String,
    /// Added to every position of the path.
    origin: Vec3,
    skippable: bool,
    /// What to put back when it ends: the player's, from before the first of a run of cutscenes.
    saved: PlayerState,
    /// The display the player looks through.
    pub camera: Uuid,
    /// Server ticks played.
    pub tick: i64,
    next_cue: usize,
    /// The tick a cue's text stops showing, or none when none is up.
    text_until: Option<i64>,
    /// The chunk (x, z) of `world` held for the camera, where it is now, until it ends.
    held: Option<(i32, i32)>,
    /// Set once it has ended, for any reason: its `end` handlers run with it still found.
    pub ended: bool,
}

impl Active {
    /// Seconds played so far, at most the cutscene's length.
    pub fn time(&self) -> f64 {
        (self.tick as f64 / TICKS_PER_SECOND).min(self.definition.length)
    }

    fn handle(&self) -> handles::Cutscene {
        handles::Cutscene {
            number: self.number,
            kind: self.kind.clone(),
        }
    }
}

/// What `nf.cutscenes.play` takes besides the player and the cutscene.
#[derive(Clone, Debug, Default)]
pub struct PlayOptions {
    /// Added to every position of the path.
    pub origin: Option<Vec3>,
    /// The world the cutscene is in: the player's when none.
    pub world: Option<String>,
    pub skippable: Option<bool>,
}

/// The session's cutscenes: definitions, files, the ones playing, and who a crash left in one.
#[derive(Default)]
pub struct Cutscenes {
    definitions: BTreeMap<String, Rc<CompiledCutscene>>,
    /// Every cutscene file the tree has, by id, with its first error when it has errors.
    files: BTreeMap<String, Option<String>>,
    active: IndexMap<i64, Active>,
    by_player: HashMap<Uuid, i64>,
    next: i64,
    /// What players a cutscene held when the server went down were before it, until they join and are put back.
    leftovers: BTreeMap<Uuid, PlayerState>,
}

impl Cutscenes {
    pub fn ids(&self) -> Vec<String> {
        self.files.keys().cloned().collect()
    }

    pub fn find(&self, number: i64) -> Option<&Active> {
        self.active.get(&number)
    }

    /// What `player` is watching now.
    pub fn of(&self, player: Uuid) -> Option<&Active> {
        self.active.get(self.by_player.get(&player)?)
    }

    /// The length of `kind` (seconds) as the project defines it.
    pub fn length_of(&self, kind: &str) -> Option<f64> {
        self.definitions.get(kind).map(|d| d.length)
    }

    /// How many cutscenes `scope` played are still playing (`/nf scripts`' count).
    pub fn count_owned(&self, scope: ScopeId) -> usize {
        self.active
            .values()
            .filter(|c| !c.ended && c.owner == Some(scope))
            .count()
    }
}

/// The chunk (x, z) a location is in.
fn chunk_of(at: &Location) -> (i32, i32) {
    ((at.x.floor() as i32) >> 4, (at.z.floor() as i32) >> 4)
}

fn location_of(world: &str, pose: CameraPose, origin: Vec3) -> Location {
    Location {
        world: world.to_owned(),
        x: pose.position.0 + origin.0,
        y: pose.position.1 + origin.1,
        z: pose.position.2 + origin.2,
        yaw: normalize_degrees(pose.yaw),
        pitch: pose.pitch,
    }
}

/// A fresh UUID (version 4) for the camera's tag: this run.
fn random_uuid() -> Uuid {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    let mut id = u128::from_be_bytes(bytes);
    id = (id & !(0xF << 76)) | (0x4 << 76);
    id = (id & !(0x3 << 62)) | (0x2 << 62);
    Uuid(id)
}

/// The cutscenes' work that reaches the server and scripts: playing, the pass, ending and putting back.
#[derive(Clone)]
pub struct CutscenesContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl CutscenesContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// Starts `kind` for `player`, for `owner`. None when they can't watch one now: offline, dead, the world isn't
    /// there, or the camera couldn't be made (the player is then as they were). A cutscene the project doesn't
    /// have, or one that never had a good version, is the script's mistake.
    pub fn play(
        &self,
        kind: &str,
        player: Uuid,
        owner: Option<ScopeId>,
        options: PlayOptions,
    ) -> ApiResult<Option<handles::Cutscene>> {
        let (definition, previous) = {
            let cutscenes = self.shared.cutscenes.borrow();
            let Some(definition) = cutscenes.definitions.get(kind).cloned() else {
                return Err(err(match cutscenes.files.get(kind) {
                    Some(Some(problem)) => format!(
                        "cutscene \"{kind}\" has errors, and there's no earlier version to play: {problem}"
                    ),
                    _ => not_in_project("cutscene", kind, &cutscenes.ids()),
                }));
            };
            let previous = cutscenes
                .of(player)
                .filter(|c| !c.ended)
                .map(|c| (c.number, c.saved.clone()));
            (definition, previous)
        };
        let platform = self.platform();
        let Some(here) = platform.players().location(player) else {
            return Ok(None);
        };
        if platform
            .world_entities()
            .number(player, EntityNumber::Health)
            .is_some_and(|h| h <= 0.0)
        {
            return Ok(None);
        }
        let world = options.world.clone().unwrap_or_else(|| here.world.clone());
        if !platform.worlds().exists(world.clone()) {
            return Ok(None);
        }
        // The state to put back is the player's before the first of a run of cutscenes.
        let saved = match &previous {
            Some((_, saved)) => saved.clone(),
            None => match self.capture(player, here) {
                Some(saved) => saved,
                None => return Ok(None),
            },
        };
        let origin = options.origin.unwrap_or(Vec3(0.0, 0.0, 0.0));
        let start = location_of(&world, definition.pose_at(0.0), origin);
        // The camera's chunk is held while it's there: one nothing holds unloads at the end of the tick, before
        // the player carried to it has their own chunks round it, and a camera that can't be reached lets them go.
        let held = chunk_of(&start);
        platform.worlds().hold_chunk(world.clone(), held.0, held.1);
        let id = random_uuid();

        // Whatever else they were doing: windows closed, a ride left, then the camera made and looked through.
        let replaced = previous.as_ref().and_then(|(n, _)| self.retire(*n));
        platform.players().close_inventory(player);
        if let Some(vehicle) = platform.world_entities().vehicle(player) {
            platform.world_entities().remove_passenger(vehicle, player);
        }
        let camera = platform.entities().spawn_display(
            start,
            // Nothing drawn: no words, no background. Other players see nothing where it is.
            DisplayDef::Text {
                text: String::new(),
                billboard: None,
                alignment: None,
                background: Some(INVISIBLE.to_owned()),
                line_width: None,
                see_through: None,
                shadow: None,
                anchor: None,
                runs: None,
            },
            DisplayPose {
                matrix: Matrix4::IDENTITY,
                interpolation_ticks: 0,
                cull_size: 0.0,
            },
            EntityTag {
                instance: id,
                node: CAMERA.to_owned(),
                role: EntityRole::Display,
            },
        );
        if let Some(camera) = camera {
            // Saved with its chunk it could outlive a crash; the look makes the client tween each move over a tick.
            platform.entities().set_persistent(camera, false);
            let mut look = crate::centity::instance::default_look();
            look.teleport_ticks = 1;
            platform.entities().set_look(camera, look);
        }
        let Some(camera) = camera.filter(|c| self.look(player, *c)) else {
            if let Some(camera) = camera {
                platform.entities().remove(camera);
            }
            platform.worlds().release_chunk(world, held.0, held.1);
            // The cutscene they were in (if any) is gone too, so the state is theirs to have back.
            self.restore(player, &saved);
            if let Some((number, player)) = replaced {
                self.announce(number, player, EndReason::Replaced);
            }
            return Ok(None);
        };
        let handle = {
            let mut cutscenes = self.shared.cutscenes.borrow_mut();
            cutscenes.next += 1;
            let cutscene = Active {
                number: cutscenes.next,
                kind: kind.to_owned(),
                owner,
                player,
                skippable: options.skippable.unwrap_or(definition.skippable),
                definition,
                world,
                origin,
                saved: saved.clone(),
                camera,
                tick: 0,
                next_cue: 0,
                text_until: None,
                held: Some(held),
                ended: false,
            };
            let handle = cutscene.handle();
            cutscenes.by_player.insert(player, cutscene.number);
            cutscenes.active.insert(cutscene.number, cutscene);
            handle
        };
        // Kept (a run of cutscenes keeps the first) until they're put back, in case the server goes down meanwhile.
        if previous.is_none() {
            self.shared.store.put_cutscene_state(player, &saved);
        }
        // The one it replaced is told only now, with the new one already playing: a handler that plays or stops
        // again finds things whole.
        if let Some((number, player)) = replaced {
            self.announce(number, player, EndReason::Replaced);
        }
        Ok(Some(handle))
    }

    /// What to put back, or none for a player who isn't there.
    fn capture(&self, player: Uuid, at: Location) -> Option<PlayerState> {
        let platform = self.platform();
        let game_mode = platform.players().game_mode(player)?;
        let entities = platform.world_entities();
        Some(PlayerState {
            location: at,
            game_mode,
            can_fly: entities.flag(player, EntityFlag::CanFly).unwrap_or(false),
            flying: entities.flag(player, EntityFlag::Flying).unwrap_or(false),
            spectating: platform.player_views().camera(player),
        })
    }

    /// Spectator mode, looking through `camera`. False when the server wouldn't.
    fn look(&self, player: Uuid, camera: Uuid) -> bool {
        let players = self.platform().players();
        if players.game_mode(player).as_deref() != Some(SPECTATOR) {
            players.set_game_mode(player, SPECTATOR.to_owned());
        }
        // A script may have refused the change, or the server the camera.
        players.game_mode(player).as_deref() == Some(SPECTATOR)
            && self
                .platform()
                .player_views()
                .set_camera(player, Some(camera))
    }

    /// Puts `player` back as `state` says, each step on its own (the Rust Platform's calls don't throw, so each is
    /// simply made whatever the last answered). The camera is let go first: a teleport or a game mode change with
    /// one still on would carry them with it.
    pub fn restore(&self, player: Uuid, state: &PlayerState) {
        // Whatever the server does next, nothing is left to put back.
        self.shared.store.delete_cutscene_state(player);
        let platform = self.platform();
        platform.player_views().set_camera(player, None);
        platform.players().teleport(player, state.location.clone());
        platform
            .players()
            .set_game_mode(player, state.game_mode.clone());
        // A game mode change decides what they may do, so what they were allowed is said after it.
        let entities = platform.world_entities();
        entities.set_flag(player, EntityFlag::CanFly, state.can_fly);
        entities.set_flag(player, EntityFlag::Flying, state.flying && state.can_fly);
        if let Some(entity) = state.spectating
            && state.game_mode == SPECTATOR
        {
            platform.player_views().set_camera(player, Some(entity));
        }
    }

    /// Takes `number` out of play without telling anyone: its camera goes, its title is cleared, its chunk let go,
    /// and the player isn't put back. Who watched it and what they were, or none when it had already ended.
    fn retire(&self, number: i64) -> Option<(i64, Uuid)> {
        let (player, camera, world, held, titled) = {
            let mut cutscenes = self.shared.cutscenes.borrow_mut();
            let cutscene = cutscenes.active.get_mut(&number).filter(|c| !c.ended)?;
            cutscene.ended = true;
            let taken = (
                cutscene.player,
                cutscene.camera,
                cutscene.world.clone(),
                cutscene.held.take(),
                cutscene.text_until.take().is_some(),
            );
            if cutscenes.by_player.get(&taken.0) == Some(&number) {
                cutscenes.by_player.remove(&taken.0);
            }
            taken
        };
        let platform = self.platform();
        if titled {
            platform.players().clear_title(player);
        }
        platform.entities().remove(camera);
        if let Some((x, z)) = held {
            platform.worlds().release_chunk(world, x, z);
        }
        Some((number, player))
    }

    /// Ends `number`: the player is put back first (so a handler that moves them has the last word), then its
    /// `end` handlers hear it while the handle still answers, then it's gone and so is every handler on it.
    pub fn end(&self, number: i64, reason: EndReason) {
        let Some((_, player)) = self.retire(number) else {
            return;
        };
        let saved = self
            .shared
            .cutscenes
            .borrow()
            .find(number)
            .map(|c| c.saved.clone());
        if let Some(saved) = saved {
            self.restore(player, &saved);
        }
        self.announce(number, player, reason);
    }

    /// Raises `end` for a cutscene that's already out of play (still found while handlers hear it, so
    /// `is_active()` answers true), then forgets it and drops what listened to it.
    fn announce(&self, number: i64, player: Uuid, reason: EndReason) {
        let Some(handle) = self
            .shared
            .cutscenes
            .borrow()
            .find(number)
            .map(Active::handle)
        else {
            return;
        };
        let lua = self.scripts.host().lua().clone();
        let target = netherforge_api::handle::push_any(&lua, &Handle::Cutscene(handle.clone()))
            .ok()
            .map(Target::Handle);
        if let Some(target) = &target {
            let payload = shapes::CutsceneEndEvent {
                cutscene: handle,
                player: handles::Player {
                    id: uuid_text(&player),
                },
                reason: reason.name().to_owned(),
            };
            if let Ok(Value::Table(fields)) = codecs::CutsceneEndEvent.push(&lua, payload) {
                self.scripts.raise(target, "end", fields);
            }
        }
        self.shared
            .cutscenes
            .borrow_mut()
            .active
            .shift_remove(&number);
        if let Some(target) = &target {
            self.scripts.drop_target(target);
        }
    }

    /// Ends `number` now, with `reason`, and puts the player back. False when it had already ended.
    pub fn stop(&self, number: i64, reason: EndReason) -> bool {
        let live = self
            .shared
            .cutscenes
            .borrow()
            .find(number)
            .is_some_and(|c| !c.ended);
        if live {
            self.end(number, reason);
        }
        live
    }

    fn end_where(&self, reason: EndReason, which: impl Fn(&Active) -> bool) {
        let doomed: Vec<i64> = self
            .shared
            .cutscenes
            .borrow()
            .active
            .values()
            .filter(|c| !c.ended && which(c))
            .map(|c| c.number)
            .collect();
        for number in doomed {
            self.end(number, reason);
        }
    }

    /// One pass over every playing cutscene, once a server tick after the world has moved.
    pub fn pass(&self) {
        let numbers: Vec<i64> = self
            .shared
            .cutscenes
            .borrow()
            .active
            .values()
            .filter(|c| !c.ended)
            .map(|c| c.number)
            .collect();
        for number in numbers {
            self.step(number);
        }
    }

    fn step(&self, number: i64) {
        let Some((player, camera, skippable)) = self
            .shared
            .cutscenes
            .borrow()
            .find(number)
            .filter(|c| !c.ended)
            .map(|c| (c.player, c.camera, c.skippable))
        else {
            return;
        };
        let platform = self.platform();
        if platform.players().get(player).is_none() {
            return self.end(number, EndReason::PlayerLeft);
        }
        // Someone changed their game mode, or the camera's gone: the cutscene no longer holds them.
        if platform.players().game_mode(player).as_deref() != Some(SPECTATOR)
            || !platform.entities().is_loaded(camera)
        {
            return self.end(number, EndReason::Stopped);
        }
        // They pressed sneak, which makes the server let go of the camera: a skip, or a hold.
        if platform.player_views().camera(player) != Some(camera) {
            if skippable {
                return self.end(number, EndReason::Skipped);
            }
            if !platform.player_views().set_camera(player, Some(camera)) {
                return self.end(number, EndReason::Stopped);
            }
        }
        let moved = {
            let mut cutscenes = self.shared.cutscenes.borrow_mut();
            let Some(cutscene) = cutscenes.active.get_mut(&number).filter(|c| !c.ended) else {
                return;
            };
            cutscene.tick += 1;
            let ticks = (cutscene.definition.length * TICKS_PER_SECOND - EPSILON).ceil() as i64;
            // One tick past the last pose, so the client has drawn the camera arriving.
            if cutscene.tick > ticks {
                None
            } else {
                let to = location_of(
                    &cutscene.world,
                    cutscene.definition.pose_at(cutscene.time()),
                    cutscene.origin,
                );
                // The next chunk is held before the camera goes into it, then the last let go.
                let chunk = chunk_of(&to);
                let swap = (cutscene.held != Some(chunk))
                    .then(|| (cutscene.held.replace(chunk), chunk, cutscene.world.clone()));
                Some((to, swap))
            }
        };
        let Some((to, swap)) = moved else {
            return self.end(number, EndReason::Finished);
        };
        if let Some((last, next, world)) = swap {
            platform.worlds().hold_chunk(world.clone(), next.0, next.1);
            if let Some((x, z)) = last {
                platform.worlds().release_chunk(world, x, z);
            }
        }
        platform.entities().teleport(camera, to);
        self.cues(number);
        let clear = {
            let mut cutscenes = self.shared.cutscenes.borrow_mut();
            match cutscenes.active.get_mut(&number) {
                Some(c) if !c.ended && c.text_until.is_some_and(|until| until <= c.tick) => {
                    c.text_until = None;
                    true
                }
                _ => false,
            }
        };
        if clear {
            platform.players().clear_title(player);
        }
    }

    /// Fires every cue whose time has come, in order, until the cutscene ends (a cue's handler may stop it).
    fn cues(&self, number: i64) {
        loop {
            let next = {
                let mut cutscenes = self.shared.cutscenes.borrow_mut();
                let Some(cutscene) = cutscenes.active.get_mut(&number).filter(|c| !c.ended) else {
                    return;
                };
                let now = cutscene.tick as f64 / TICKS_PER_SECOND + EPSILON;
                match cutscene.definition.cues.get(cutscene.next_cue) {
                    Some(cue) if cue.time <= now => {
                        let cue = cue.clone();
                        cutscene.next_cue += 1;
                        if cue.text.is_some() {
                            let stay = (cue.duration * TICKS_PER_SECOND).ceil() as i64;
                            cutscene.text_until = Some(cutscene.tick + stay);
                        }
                        (cue, cutscene.player, cutscene.handle())
                    }
                    _ => return,
                }
            };
            self.fire(next.0, next.1, next.2);
        }
    }

    fn fire(&self, cue: CompiledCue, player: Uuid, handle: handles::Cutscene) {
        if let Some(text) = cue.text {
            let stay = (cue.duration * TICKS_PER_SECOND).ceil() as i32;
            self.platform()
                .players()
                .title(player, String::new(), text, 0, stay, TEXT_FADE_OUT);
        }
        let Some(event) = cue.event else {
            return;
        };
        let lua = self.scripts.host().lua().clone();
        let Ok(target) = netherforge_api::handle::push_any(&lua, &Handle::Cutscene(handle.clone()))
            .map(Target::Handle)
        else {
            return;
        };
        if !self.scripts.listening(&target, "cue") {
            return;
        }
        let payload = shapes::CutsceneCueEvent {
            cutscene: handle,
            player: handles::Player {
                id: uuid_text(&player),
            },
            cue: event,
        };
        if let Ok(Value::Table(fields)) = codecs::CutsceneCueEvent.push(&lua, payload) {
            self.scripts.raise(&target, "cue", fields);
        }
    }

    /// Takes `next` as `id`'s definition (none: its file has errors, `problem` the first, or it's gone, `exists`
    /// false). Cutscenes playing keep theirs.
    pub fn reload(
        &self,
        id: &str,
        next: Option<CompiledCutscene>,
        exists: bool,
        problem: Option<String>,
    ) {
        let mut cutscenes = self.shared.cutscenes.borrow_mut();
        if exists {
            cutscenes
                .files
                .insert(id.to_owned(), problem.filter(|_| next.is_none()));
        } else {
            cutscenes.files.remove(id);
        }
        match next {
            Some(next) => {
                cutscenes.definitions.insert(id.to_owned(), Rc::new(next));
            }
            None if !exists => {
                cutscenes.definitions.remove(id);
            }
            None => {}
        }
    }

    /// Tells `scripts` which cutscene handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let cutscenes = shared.cutscenes.try_borrow().ok()?;
            match handle {
                Handle::Cutscene(c) => Some(cutscenes.find(c.number).is_some()),
                _ => None,
            }
        }));
    }
}

impl GameContext {
    fn cutscenes(&self) -> CutscenesContext {
        CutscenesContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }
}

impl NfCutscenesApi for GameContext {
    fn play(
        &self,
        caller: &Caller,
        player: handles::Player,
        cutscene: String,
        options: Option<shapes::CutscenePlayOptions>,
    ) -> ApiResult<Option<handles::Cutscene>> {
        // Resolved first: a mistake in the name is the script's, whoever is watching.
        let kind = self.with_names(|names| names.resource(KIND, &cutscene))?;
        let Some(uuid) = parse_uuid(&player.id) else {
            return Ok(None);
        };
        let mut play = PlayOptions::default();
        if let Some(options) = options {
            play.skippable = options.skippable;
            match options.origin {
                Some(unions::LocationOrVec3::Location(l)) => {
                    play.origin = Some(Vec3(l.position.x, l.position.y, l.position.z));
                    play.world = Some(l.world.name);
                }
                Some(unions::LocationOrVec3::Vec3(v)) => play.origin = Some(Vec3(v.x, v.y, v.z)),
                None => {}
            }
        }
        self.cutscenes().play(&kind, uuid, Some(caller.scope), play)
    }

    fn stop(&self, _caller: &Caller, player: handles::Player) -> ApiResult<bool> {
        let Some(number) = parse_uuid(&player.id)
            .and_then(|p| self.shared.cutscenes.borrow().of(p).map(|c| c.number))
        else {
            return Ok(false);
        };
        Ok(self.cutscenes().stop(number, EndReason::Stopped))
    }

    fn current(
        &self,
        _caller: &Caller,
        player: handles::Player,
    ) -> ApiResult<Option<handles::Cutscene>> {
        Ok(parse_uuid(&player.id).and_then(|p| {
            self.shared
                .cutscenes
                .borrow()
                .of(p)
                .filter(|c| !c.ended)
                .map(Active::handle)
        }))
    }
}

impl CutsceneApi for GameContext {
    fn kind(&self, this: &handles::Cutscene) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.kind)))
    }

    fn length(&self, this: &handles::Cutscene) -> ApiResult<f64> {
        Ok(self
            .shared
            .cutscenes
            .borrow()
            .length_of(&this.kind)
            .unwrap_or(0.0))
    }

    fn player(&self, this: &handles::Cutscene) -> ApiResult<Option<handles::Player>> {
        let Some(player) = self
            .shared
            .cutscenes
            .borrow()
            .find(this.number)
            .map(|c| c.player)
        else {
            return Ok(None);
        };
        Ok(self
            .shared
            .platform
            .players()
            .get(player)
            .map(|_| handles::Player {
                id: uuid_text(&player),
            }))
    }

    fn is_active(&self, this: &handles::Cutscene) -> ApiResult<bool> {
        Ok(self.shared.cutscenes.borrow().find(this.number).is_some())
    }

    fn time(&self, this: &handles::Cutscene) -> ApiResult<Option<f64>> {
        Ok(self
            .shared
            .cutscenes
            .borrow()
            .find(this.number)
            .filter(|c| !c.ended)
            .map(Active::time))
    }

    fn stop(&self, this: &handles::Cutscene) -> ApiResult<bool> {
        Ok(self.cutscenes().stop(this.number, EndReason::Stopped))
    }

    fn on(
        &self,
        this: &handles::Cutscene,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Cutscene(this.clone()),
            "Cutscene:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Cutscene,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Cutscene(this.clone()),
            "Cutscene:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// The cutscenes service: defines them, runs the pass each tick, puts back whoever a crash left in one, and ends
/// what a stopped scope played or a leaving player watched.
pub struct CutscenesService {
    pub shared: Rc<Shared>,
    /// The room's scripts, once it starts: what an ended cutscene's `end` handlers run in.
    pub scripts: RefCell<Option<crate::Scripts>>,
}

impl CutscenesService {
    fn context(&self) -> Option<CutscenesContext> {
        Some(CutscenesContext {
            shared: self.shared.clone(),
            scripts: self.scripts.borrow().clone()?,
        })
    }
}

/// Every cutscene file the tree has, by id, with its first error (none for a valid one).
fn files(project: &Define) -> BTreeMap<String, Option<String>> {
    project
        .project
        .present(KIND)
        .into_iter()
        .map(|(id, resource)| {
            let problem = resource
                .problems
                .iter()
                .find(|p| p.severity == Severity::Error)
                .map(|p| p.message.clone());
            (id, problem)
        })
        .collect()
}

impl Service for CutscenesService {
    fn phase(&self) -> &'static str {
        "effects"
    }

    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        vec![(
            "cutscenes",
            self.shared.cutscenes.borrow().count_owned(scope),
        )]
    }

    fn name(&self) -> &'static str {
        "cutscenes"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &[KIND]
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
        let leftovers = self.shared.store.cutscene_states();
        let mut cutscenes = self.shared.cutscenes.borrow_mut();
        cutscenes.definitions = project
            .project
            .compiled::<CompiledCutscene>()
            .into_iter()
            .map(|(id, c)| (id, Rc::new(c.clone())))
            .collect();
        cutscenes.files = files(project);
        cutscenes.leftovers = leftovers;
    }

    fn tick(&self, _room: &Room, _platform: &dyn Platform) {
        if let Some(context) = self.context() {
            context.pass();
        }
    }

    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let files = files(project);
        let compiled = project.project.compiled::<CompiledCutscene>();
        let context = CutscenesContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        };
        for id in ids {
            let next = compiled
                .iter()
                .find(|(c, _)| c == id)
                .map(|(_, c)| (*c).clone());
            context.reload(
                id,
                next,
                files.contains_key(id),
                files.get(id).cloned().flatten(),
            );
        }
    }

    /// What a stopped scope played ends `unloaded`, and its player is put back.
    fn scope_released(&self, scopes: &[ScopeId]) {
        if let Some(context) = self.context() {
            context.end_where(EndReason::Unloaded, |c| {
                c.owner.is_some_and(|o| scopes.contains(&o))
            });
        }
    }

    /// A player the server lost mid-cutscene is put back before scripts hear they joined.
    fn player_joined(&self, player: &PlayerRef) {
        let Some(state) = self
            .shared
            .cutscenes
            .borrow_mut()
            .leftovers
            .remove(&player.uuid)
        else {
            return;
        };
        self.shared.platform.log().warn(format!(
            "{} was in a cutscene when the server stopped; putting them back",
            player.name
        ));
        if let Some(context) = self.context() {
            context.restore(player.uuid, &state);
        }
    }

    /// The player is leaving (or has): they're put back while the server still has them.
    fn player_quit(&self, player: &PlayerRef) {
        let Some(number) = self
            .shared
            .cutscenes
            .borrow()
            .of(player.uuid)
            .map(|c| c.number)
        else {
            return;
        };
        if let Some(context) = self.context() {
            context.end(number, EndReason::PlayerLeft);
        }
    }

    /// The session ending: every player is put back before anything is torn down.
    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        CutscenesContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
        .end_where(EndReason::Unloaded, |_| true);
        *self.shared.cutscenes.borrow_mut() = Cutscenes::default();
    }
}
