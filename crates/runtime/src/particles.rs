//! Particle effects (Kotlin's `ParticleEffects`, `ActiveEffect`, `NfParticlesImpl`, `EffectImpl`): the project's
//! effects and every one playing.
//!
//! Definitions are the compiled (valid) effects, the last good one kept while a file has errors. An effect
//! belongs to the scope that played it and ends with it (`unloaded`). Once a tick, after the centities' sync (the
//! service is registered after theirs), one pass over every playing effect, round-robin: follow its target, step
//! its sampler (always, so its time is the server's), and send its spawns in world space to who's near enough,
//! within the pass's budget of points. The pass raises into Lua only for `end` handlers.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{EffectApi, NfParticlesApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, codecs, shapes, unions, values};
use netherforge_format::Problem;
use netherforge_format::Severity;
use netherforge_format::kind::particle::compiled::CompiledEffect;
use netherforge_format::kind::particle::sample::{EffectFrame, EffectSampler, EffectSpawn};
use netherforge_format::math::Vec3;
use netherforge_luau::ScopeId;
use netherforge_platform::{Location, ParticleSpawn, Platform, PlayerRef, Uuid};

use crate::Room;
use crate::centity::api::not_in_project;
use crate::events::parse_uuid;
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// Effects playing on the server at once; one more is an error at `play`.
pub const MAX_ACTIVE: usize = 1024;
/// Points (spawns) the pass sends in one server tick, across every effect.
pub const MAX_POINTS_PER_TICK: usize = 4096;
/// The largest `scale` a play takes.
pub const MAX_SCALE: f64 = 16.0;
/// How far the client draws particles that aren't forced, and those that are.
const RANGE: f64 = 32.0;
const FORCED_RANGE: f64 = 128.0;
/// The budget warning at most once in this many ticks (ten seconds), and over once none is skipped for as long.
pub const BUDGET_WARNING_TICKS: u64 = 200;

const KIND: &str = "particle_effect";

/// Why an effect ended, as `EffectEndEvent.reason` says it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndReason {
    Finished,
    Stopped,
    TargetGone,
    Removed,
    Unloaded,
}

impl EndReason {
    fn name(self) -> &'static str {
        match self {
            EndReason::Finished => "finished",
            EndReason::Stopped => "stopped",
            EndReason::TargetGone => "target_gone",
            EndReason::Removed => "removed",
            EndReason::Unloaded => "unloaded",
        }
    }
}

/// What an effect can follow: anything with a position and a yaw that can stop existing.
#[derive(Clone, Debug, PartialEq)]
pub enum FollowTarget {
    Centity(Uuid),
    Node(Uuid, String),
    Player(Uuid),
    Entity(Uuid),
}

/// What a handle `follow` takes names: a centity, a node, a player or another entity. A handle whose id isn't
/// a UUID names nobody: following it ends the effect at once.
fn follow_target(handle: &Handle) -> ApiResult<FollowTarget> {
    let id = |text: &str| parse_uuid(text).unwrap_or(Uuid(0));
    Ok(match handle {
        Handle::Centity(c) => FollowTarget::Centity(id(&c.id)),
        Handle::Node(n) => FollowTarget::Node(id(&n.centity), n.name.clone()),
        Handle::Player(p) => FollowTarget::Player(id(&p.id)),
        Handle::Entity(handles::Entity { id: e })
        | Handle::Living(handles::Living { id: e })
        | Handle::Mob(handles::Mob { id: e })
        | Handle::DroppedItem(handles::DroppedItem { id: e })
        | Handle::Mannequin(handles::Mannequin { id: e }) => FollowTarget::Entity(id(e)),
        other => {
            return Err(err(format!("an effect can't follow a {}", other.class())));
        }
    })
}

/// One playing effect: its timeline, where it is, what it follows, and who may see it.
pub struct Active {
    /// The handle's key: unique for the session's life, never reused.
    pub number: i64,
    /// The effect's id as the server knows it (a package's `ns:id`).
    pub kind: String,
    /// The scope that played it, or none for one the runtime played (the editor's "Play on server").
    pub owner: Option<ScopeId>,
    sampler: EffectSampler,
    pub world: String,
    /// Its origin in world space.
    pub origin: Vec3,
    pub yaw: f64,
    pub pitch: f64,
    pub scale: f64,
    /// Only these players may see it; none for everyone in range.
    viewers: Option<HashSet<Uuid>>,
    /// The play's `loop` option; none to follow the file.
    looped: Option<bool>,
    pub follow: Option<FollowTarget>,
    /// Where it sits from what it follows, in effect space (turned with the target's yaw, not scaled).
    pub offset: Vec3,
    /// Set once it has ended, for any reason: its `end` handlers run with it still found.
    pub ended: bool,
}

impl Active {
    fn looped(&self) -> bool {
        self.looped.unwrap_or(self.sampler.effect().looped)
    }

    fn handle(&self) -> handles::Effect {
        handles::Effect {
            number: self.number,
            kind: self.kind.clone(),
        }
    }
}

/// What `nf.particles.play` takes besides the effect and the place.
#[derive(Clone, Debug, Default)]
pub struct PlayOptions {
    pub viewers: Option<HashSet<Uuid>>,
    pub scale: Option<f64>,
    pub looped: Option<bool>,
    pub follow: Option<FollowTarget>,
    pub offset: Option<Vec3>,
}

/// The session's particle effects: definitions, files, and the ones playing.
#[derive(Default)]
pub struct Particles {
    definitions: BTreeMap<String, CompiledEffect>,
    /// Every effect file the tree has, by id, with its first error when it has errors.
    files: BTreeMap<String, Option<String>>,
    /// Each effect's main file, for the budget's warning.
    paths: BTreeMap<String, String>,
    active: IndexMap<i64, Active>,
    next: i64,
    /// How far along the active effects the next pass starts, so a full budget doesn't always skip the same ones.
    rotation: usize,
    ticks: u64,
    last_warning: Option<u64>,
    last_skip: Option<u64>,
    skipped_since_warning: usize,
    skipped_kinds: BTreeSet<String>,
    /// The budget's warning, on the first skipped effect's file, while the pass skips effects.
    budget_problem: Option<Problem>,
}

impl Particles {
    pub fn ids(&self) -> Vec<String> {
        self.files.keys().cloned().collect()
    }

    pub fn find(&self, number: i64) -> Option<&Active> {
        self.active.get(&number)
    }

    /// How many effects `scope` played are still playing.
    pub fn count_owned(&self, scope: ScopeId) -> usize {
        self.active
            .values()
            .filter(|e| e.owner == Some(scope))
            .count()
    }

    /// Every effect playing (or ending), in the order they started.
    pub fn playing(&self) -> impl Iterator<Item = &Active> {
        self.active.values()
    }

    /// The budget's warning, while there is one.
    pub fn problems(&self) -> Vec<Problem> {
        self.budget_problem.iter().cloned().collect()
    }

    /// Starts `kind` at `at` (its yaw and pitch turn it), for `owner`. None when `at`'s world isn't loaded.
    pub fn play(
        &mut self,
        platform: &dyn Platform,
        kind: &str,
        at: &Location,
        owner: Option<ScopeId>,
        options: PlayOptions,
    ) -> ApiResult<Option<handles::Effect>> {
        let Some(definition) = self.definitions.get(kind) else {
            return Err(err(match self.files.get(kind) {
                Some(Some(problem)) => format!(
                    "particle effect \"{kind}\" has errors, and there's no earlier version to play: {problem}"
                ),
                _ => not_in_project("particle effect", kind, &self.ids()),
            }));
        };
        if self.active.len() >= MAX_ACTIVE {
            return Err(err(format!(
                "too many particle effects playing ({MAX_ACTIVE}): stop the ones you're done with"
            )));
        }
        let scale = options.scale.unwrap_or(1.0);
        if !(scale > 0.0 && scale <= MAX_SCALE) {
            return Err(err(format!(
                "options.scale must be more than 0 and at most {MAX_SCALE}, not {scale}"
            )));
        }
        if options.offset.is_some() && options.follow.is_none() {
            return Err(err("options.offset only goes with options.follow"));
        }
        if !platform.worlds().exists(at.world.clone()) {
            return Ok(None);
        }
        self.next += 1;
        let effect = Active {
            number: self.next,
            kind: kind.to_owned(),
            owner,
            sampler: EffectSampler::new(definition.clone(), random_seed()),
            world: at.world.clone(),
            origin: Vec3(at.x, at.y, at.z),
            yaw: at.yaw,
            pitch: at.pitch,
            scale,
            viewers: options.viewers,
            looped: options.looped,
            offset: if options.follow.is_some() {
                options.offset.unwrap_or(Vec3(0.0, 0.0, 0.0))
            } else {
                Vec3(0.0, 0.0, 0.0)
            },
            follow: options.follow,
            ended: false,
        };
        let handle = effect.handle();
        self.active.insert(effect.number, effect);
        Ok(Some(handle))
    }

    /// Moves `number` to `to` (its facing too) and stops it following anything. False once it has ended.
    pub fn teleport(&mut self, number: i64, to: &Location) -> bool {
        let Some(effect) = self.active.get_mut(&number).filter(|e| !e.ended) else {
            return false;
        };
        effect.follow = None;
        effect.offset = Vec3(0.0, 0.0, 0.0);
        effect.world = to.world.clone();
        effect.origin = Vec3(to.x, to.y, to.z);
        effect.yaw = to.yaw;
        effect.pitch = to.pitch;
        true
    }

    /// Makes `number` follow `target` from the next pass on. False once it has ended.
    pub fn follow(&mut self, number: i64, target: FollowTarget, offset: Option<Vec3>) -> bool {
        let Some(effect) = self.active.get_mut(&number).filter(|e| !e.ended) else {
            return false;
        };
        effect.follow = Some(target);
        effect.offset = offset.unwrap_or(Vec3(0.0, 0.0, 0.0));
        true
    }

    fn budget_recovered(&mut self) {
        if self.last_skip.take().is_some() {
            self.budget_problem = None;
        }
    }
}

/// A seed for an effect's sampler: a fresh one every play, as Kotlin's `Random.nextLong()`.
fn random_seed() -> i64 {
    let mut bytes = [0u8; 8];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    i64::from_le_bytes(bytes)
}

/// The particle effects' work that reaches scripts: ending an effect (its `end` handlers) and the pass.
#[derive(Clone)]
pub struct ParticlesContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl ParticlesContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// Ends `number` with `reason`: its `end` handlers hear it while the handle still answers (`is_active()` is
    /// true, `location()` says where it was; `stop()` is false, since it's already ending), then it's gone and
    /// so is every handler on it.
    pub fn end(&self, number: i64, reason: EndReason) {
        let handle = {
            let mut particles = self.shared.particles.borrow_mut();
            let Some(effect) = particles.active.get_mut(&number).filter(|e| !e.ended) else {
                return;
            };
            effect.ended = true;
            effect.handle()
        };
        let lua = self.scripts.host().lua().clone();
        let target = netherforge_api::handle::push_any(&lua, &Handle::Effect(handle.clone()))
            .ok()
            .map(Target::Handle);
        if let Some(target) = &target {
            let payload = shapes::EffectEndEvent {
                effect: handle,
                reason: reason.name().to_owned(),
            };
            if let Ok(Value::Table(fields)) = codecs::EffectEndEvent.push(&lua, payload) {
                self.scripts.raise(target, "end", fields);
            }
        }
        self.shared
            .particles
            .borrow_mut()
            .active
            .shift_remove(&number);
        if let Some(target) = &target {
            self.scripts.drop_target(target);
        }
    }

    /// Ends `number` now, with `reason`. False when it had already ended.
    pub fn stop(&self, number: i64, reason: EndReason) -> bool {
        let live = self
            .shared
            .particles
            .borrow()
            .find(number)
            .is_some_and(|e| !e.ended);
        if live {
            self.end(number, reason);
        }
        live
    }

    fn end_where(&self, reason: EndReason, which: impl Fn(&Active) -> bool) -> usize {
        let doomed: Vec<i64> = self
            .shared
            .particles
            .borrow()
            .active
            .values()
            .filter(|e| which(e))
            .map(|e| e.number)
            .collect();
        for &number in &doomed {
            self.end(number, reason);
        }
        doomed.len()
    }

    /// Ends every effect the runtime played (the editor's "Play on server"): how many.
    pub fn stop_unowned(&self) -> usize {
        self.end_where(EndReason::Stopped, |e| e.owner.is_none())
    }

    /// Moves every live effect of a valid file onto its new definition at its next tick (its tick and
    /// same-named emitters' accumulators carry over); a file with errors leaves the last good definition
    /// playing; a deleted one ends its effects with `"removed"`. How many live effects moved over.
    pub fn reload(
        &self,
        id: &str,
        next: Option<CompiledEffect>,
        exists: bool,
        problem: Option<String>,
    ) -> usize {
        let mut particles = self.shared.particles.borrow_mut();
        if exists {
            particles
                .files
                .insert(id.to_owned(), problem.filter(|_| next.is_none()));
        } else {
            particles.files.remove(id);
        }
        if let Some(next) = next {
            particles.definitions.insert(id.to_owned(), next.clone());
            let mut moved = 0;
            for effect in particles.active.values_mut().filter(|e| e.kind == id) {
                effect.sampler.redefine(next.clone());
                moved += 1;
            }
            return moved;
        }
        if !exists {
            particles.definitions.remove(id);
            drop(particles);
            self.end_where(EndReason::Removed, |e| e.kind == id);
        }
        0
    }

    /// A target's world, position and yaw, or none once it's gone.
    fn locate(&self, target: &FollowTarget) -> Option<(String, Vec3, f64)> {
        let platform = self.platform();
        match target {
            FollowTarget::Centity(id) => {
                let centities = self.shared.centities.borrow();
                let instance = centities.get(*id).filter(|i| !i.removed)?;
                let anchor = instance.anchor();
                Some((
                    anchor.world.clone(),
                    Vec3(anchor.x, anchor.y, anchor.z),
                    instance.yaw(),
                ))
            }
            FollowTarget::Node(id, name) => {
                let centities = self.shared.centities.borrow();
                let instance = centities.get(*id).filter(|i| !i.removed)?;
                let index = instance.index_of(name)?;
                Some((
                    instance.anchor().world.clone(),
                    instance.world_matrix(index).translation(),
                    instance.yaw(),
                ))
            }
            FollowTarget::Player(uuid) => {
                platform.players().get(*uuid)?;
                let at = platform.players().location(*uuid)?;
                Some((at.world.clone(), Vec3(at.x, at.y, at.z), at.yaw))
            }
            FollowTarget::Entity(uuid) => {
                let at = platform.world_entities().info(*uuid)?.location;
                Some((at.world.clone(), Vec3(at.x, at.y, at.z), at.yaw))
            }
        }
    }

    /// One pass over every playing effect. Once a server tick, after the centities' sync.
    pub fn pass(&self) {
        let numbers: Vec<i64> = {
            let mut particles = self.shared.particles.borrow_mut();
            particles.ticks += 1;
            let ticks = particles.ticks;
            if particles
                .last_skip
                .is_some_and(|last| ticks - last >= BUDGET_WARNING_TICKS)
            {
                particles.budget_recovered();
            }
            particles.active.keys().copied().collect()
        };
        if numbers.is_empty() {
            return;
        }
        let start = {
            let mut particles = self.shared.particles.borrow_mut();
            let start = particles.rotation % numbers.len();
            particles.rotation = particles.rotation.wrapping_add(1);
            start
        };
        let online = self.platform().players().online();
        let mut sent = 0;
        let mut skipped: Vec<String> = Vec::new();
        for k in 0..numbers.len() {
            let number = numbers[(start + k) % numbers.len()];
            // 1. Follow: a target that's gone ends the effect.
            let follow = match self.shared.particles.borrow().find(number) {
                Some(e) if !e.ended => e.follow.clone(),
                _ => continue,
            };
            if let Some(target) = follow {
                let Some((world, position, yaw)) = self.locate(&target) else {
                    self.end(number, EndReason::TargetGone);
                    continue;
                };
                let mut particles = self.shared.particles.borrow_mut();
                let Some(effect) = particles.active.get_mut(&number) else {
                    continue;
                };
                let turn = EffectFrame {
                    origin: Vec3(0.0, 0.0, 0.0),
                    yaw,
                    pitch: 0.0,
                    scale: 1.0,
                };
                effect.world = world;
                effect.yaw = yaw;
                effect.pitch = 0.0;
                effect.origin = position + turn.direction(effect.offset);
            }
            // 2. Step, always: its time is the server's, watched or not.
            let (spawns, finished) = {
                let mut particles = self.shared.particles.borrow_mut();
                let Some(effect) = particles.active.get_mut(&number) else {
                    continue;
                };
                let looped = effect.looped();
                let spawns = effect.sampler.step(looped);
                (spawns, effect.sampler.finished())
            };
            // 3–4. Who's near enough, then send, within the pass's budget.
            if !spawns.is_empty() {
                if sent + spawns.len() > MAX_POINTS_PER_TICK {
                    let kind = self
                        .shared
                        .particles
                        .borrow()
                        .find(number)
                        .map(|e| e.kind.clone());
                    skipped.extend(kind);
                } else if self.send(number, &spawns, &online) {
                    sent += spawns.len();
                }
            }
            // 5. A timeline that has played out ends.
            if finished {
                self.end(number, EndReason::Finished);
            }
        }
        if !skipped.is_empty() {
            self.budget_exceeded(skipped);
        }
    }

    /// Sends `spawns` (effect space) to everyone in range of the effect: 32 blocks, 128 for a `force` emitter's,
    /// of its viewers when it has them. Nobody in range sends nothing. False when nothing was sent.
    fn send(&self, number: i64, spawns: &[EffectSpawn], online: &[PlayerRef]) -> bool {
        let platform = self.platform();
        let (world, origin, frame, viewers) = {
            let particles = self.shared.particles.borrow();
            let Some(effect) = particles.find(number) else {
                return false;
            };
            let frame = EffectFrame {
                origin: effect.origin,
                yaw: effect.yaw,
                pitch: effect.pitch,
                scale: effect.scale,
            };
            (
                effect.world.clone(),
                effect.origin,
                frame,
                effect.viewers.clone(),
            )
        };
        let in_range = |range: f64| -> Vec<PlayerRef> {
            online
                .iter()
                .filter(|player| {
                    if viewers.as_ref().is_some_and(|v| !v.contains(&player.uuid)) {
                        return false;
                    }
                    let Some(at) = platform.players().location(player.uuid) else {
                        return false;
                    };
                    let (dx, dy, dz) = (at.x - origin.0, at.y - origin.1, at.z - origin.2);
                    at.world == world && dx * dx + dy * dy + dz * dz <= range * range
                })
                .cloned()
                .collect()
        };
        let (forced, plain): (Vec<&EffectSpawn>, Vec<&EffectSpawn>) =
            spawns.iter().partition(|s| s.force);
        let mut any = false;
        for (batch, range) in [(plain, RANGE), (forced, FORCED_RANGE)] {
            if batch.is_empty() {
                continue;
            }
            let viewers = in_range(range);
            if viewers.is_empty() {
                continue;
            }
            let placed = batch
                .iter()
                .map(|spawn| {
                    let placed = frame.to_world(spawn);
                    ParticleSpawn {
                        particle: placed.particle,
                        position: placed.position,
                        count: placed.count,
                        offset: placed.offset,
                        speed: placed.speed,
                        data: placed.data,
                        force: placed.force,
                    }
                })
                .collect();
            platform.particles().spawn(world.clone(), placed, viewers);
            any = true;
        }
        any
    }

    fn budget_exceeded(&self, skipped: Vec<String>) {
        let mut particles = self.shared.particles.borrow_mut();
        let ticks = particles.ticks;
        particles.last_skip = Some(ticks);
        particles.skipped_since_warning += skipped.len();
        particles.skipped_kinds.extend(skipped);
        if particles
            .last_warning
            .is_some_and(|last| ticks - last < BUDGET_WARNING_TICKS)
        {
            return;
        }
        particles.last_warning = Some(ticks);
        let kinds: Vec<String> = particles.skipped_kinds.iter().cloned().collect();
        let message = format!(
            "particle budget exceeded: {} effects skipped (more than {MAX_POINTS_PER_TICK} points in a tick, from {}); stop effects you're done with, or play fewer at once",
            particles.skipped_since_warning,
            kinds.join(", ")
        );
        // The first skipped effect's file, in whichever group folder it is.
        let file = kinds
            .first()
            .map(|id| {
                particles
                    .paths
                    .get(id)
                    .cloned()
                    .unwrap_or_else(|| format!("particles/{id}/effect.json"))
            })
            .unwrap_or_else(|| "particles".to_owned());
        particles.budget_problem = Some(netherforge_format::codes::PARTICLES_BUDGET.at(
            &file,
            format!(
                "Too many particles at once: effects are skipped to keep to {MAX_POINTS_PER_TICK} points a tick ({})",
                kinds.join(", ")
            ),
            None,
        ));
        particles.skipped_since_warning = 0;
        particles.skipped_kinds.clear();
        drop(particles);
        self.platform().log().warn(message);
    }

    /// Tells `scripts` which effect handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let particles = shared.particles.try_borrow().ok()?;
            match handle {
                Handle::Effect(e) => Some(particles.find(e.number).is_some()),
                _ => None,
            }
        }));
    }
}

impl GameContext {
    fn particles(&self) -> ParticlesContext {
        ParticlesContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    fn effect_place(at: &Active) -> Location {
        Location {
            world: at.world.clone(),
            x: at.origin.0,
            y: at.origin.1,
            z: at.origin.2,
            yaw: at.yaw,
            pitch: at.pitch,
        }
    }
}

/// A `Location`'s world and facing (nil is 0), or a bare position in `world`, facing nowhere.
fn resolve(place: unions::LocationOrVec3, world: impl FnOnce() -> String) -> Location {
    match place {
        unions::LocationOrVec3::Location(l) => Location {
            world: l.world.name,
            x: l.position.x,
            y: l.position.y,
            z: l.position.z,
            yaw: l.yaw.unwrap_or(0.0),
            pitch: l.pitch.unwrap_or(0.0),
        },
        unions::LocationOrVec3::Vec3(v) => Location {
            world: world(),
            x: v.x,
            y: v.y,
            z: v.z,
            yaw: 0.0,
            pitch: 0.0,
        },
    }
}

impl NfParticlesApi for GameContext {
    fn play(
        &self,
        caller: &Caller,
        effect: String,
        location_or_position: unions::LocationOrVec3,
        options: Option<shapes::ParticlePlayOptions>,
    ) -> ApiResult<Option<handles::Effect>> {
        let at = resolve(location_or_position, || {
            self.centities().script_world(caller.scope)
        });
        let kind = self.with_names(|names| names.resource(KIND, &effect))?;
        let play = match options {
            None => PlayOptions::default(),
            Some(options) => PlayOptions {
                viewers: options.viewers.map(|v| {
                    v.iter()
                        .filter_map(|p| parse_uuid(&p.id))
                        .collect::<HashSet<_>>()
                }),
                scale: options.scale,
                looped: options.r#loop,
                follow: options.follow.as_ref().map(follow_target).transpose()?,
                offset: options.offset.map(|o| Vec3(o.x, o.y, o.z)),
            },
        };
        self.shared.particles.borrow_mut().play(
            &*self.shared.platform,
            &kind,
            &at,
            Some(caller.scope),
            play,
        )
    }
}

impl EffectApi for GameContext {
    fn kind(&self, this: &handles::Effect) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.kind)))
    }

    fn is_active(&self, this: &handles::Effect) -> ApiResult<bool> {
        Ok(self.shared.particles.borrow().find(this.number).is_some())
    }

    fn stop(&self, this: &handles::Effect) -> ApiResult<bool> {
        Ok(self.particles().stop(this.number, EndReason::Stopped))
    }

    fn location(&self, this: &handles::Effect) -> ApiResult<Option<values::Location>> {
        let particles = self.shared.particles.borrow();
        Ok(particles.find(this.number).map(|e| values::Location {
            world: handles::World {
                name: e.world.clone(),
            },
            position: values::Vec3 {
                x: e.origin.0,
                y: e.origin.1,
                z: e.origin.2,
            },
            yaw: Some(e.yaw),
            pitch: Some(e.pitch),
        }))
    }

    /// A bare `Vec3` keeps its world and its facing.
    fn teleport(
        &self,
        this: &handles::Effect,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let here = match self.shared.particles.borrow().find(this.number) {
            Some(e) => GameContext::effect_place(e),
            None => return Ok(false),
        };
        let to = match location_or_position {
            unions::LocationOrVec3::Vec3(v) => Location {
                x: v.x,
                y: v.y,
                z: v.z,
                ..here
            },
            place => resolve(place, || here.world.clone()),
        };
        Ok(self
            .shared
            .particles
            .borrow_mut()
            .teleport(this.number, &to))
    }

    fn follow(
        &self,
        this: &handles::Effect,
        target: Handle,
        offset: Option<values::Vec3>,
    ) -> ApiResult<bool> {
        let follow = follow_target(&target)?;
        let offset = offset.map(|o| Vec3(o.x, o.y, o.z));
        Ok(self
            .shared
            .particles
            .borrow_mut()
            .follow(this.number, follow, offset))
    }

    fn on(
        &self,
        this: &handles::Effect,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Effect(this.clone()),
            "Effect:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Effect,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Effect(this.clone()),
            "Effect:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// The particle effects service: defines them, runs the pass each tick, ends what a stopped scope played.
pub struct ParticlesService {
    pub shared: Rc<Shared>,
    /// The room's scripts, once it starts: what a released scope's effects' `end` handlers run in.
    pub scripts: RefCell<Option<crate::Scripts>>,
}

impl ParticlesService {
    fn context(&self, room: &Room) -> ParticlesContext {
        ParticlesContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }
}

/// Every particle effect file the tree has, by id, with its first error (none for a valid one), and its main
/// file.
fn files(project: &Define) -> (BTreeMap<String, Option<String>>, BTreeMap<String, String>) {
    let mut files = BTreeMap::new();
    let mut paths = BTreeMap::new();
    for (id, resource) in project.project.present(KIND) {
        let problem = resource
            .problems
            .iter()
            .find(|p| p.severity == Severity::Error)
            .map(|p| p.message.clone());
        let found = &resource.found;
        let path = format!(
            "{}/effect.json",
            found.kind.location_of(&found.id, &found.group)
        );
        let path = match netherforge_format::kinds::split_package(&id) {
            (Some(namespace), _) => netherforge_format::kinds::package_path(namespace, &path),
            (None, _) => path,
        };
        files.insert(id.clone(), problem);
        paths.insert(id, path);
    }
    (files, paths)
}

impl Service for ParticlesService {
    fn phase(&self) -> &'static str {
        "effects"
    }

    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        vec![("effects", self.shared.particles.borrow().count_owned(scope))]
    }

    fn name(&self) -> &'static str {
        "particle effects"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &[KIND]
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
        let (files, paths) = files(project);
        let mut particles = self.shared.particles.borrow_mut();
        particles.definitions = project
            .project
            .compiled::<CompiledEffect>()
            .into_iter()
            .map(|(id, c)| (id, c.clone()))
            .collect();
        particles.files = files;
        particles.paths = paths;
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).pass();
    }

    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let (files, paths) = files(project);
        let compiled = project.project.compiled::<CompiledEffect>();
        self.shared.particles.borrow_mut().paths = paths;
        let context = self.context(room);
        for id in ids {
            let next = compiled
                .iter()
                .find(|(c, _)| c == id)
                .map(|(_, c)| (*c).clone());
            let exists = files.contains_key(id);
            let problem = files.get(id).cloned().flatten();
            let moved = context.reload(id, next, exists, problem);
            self.shared.reattached(KIND, id, moved);
        }
    }

    /// What a stopped scope played ends `unloaded`.
    fn scope_released(&self, scopes: &[ScopeId]) {
        let Some(scripts) = self.scripts.borrow().clone() else {
            return;
        };
        ParticlesContext {
            shared: self.shared.clone(),
            scripts,
        }
        .end_where(EndReason::Unloaded, |e| {
            e.owner.is_some_and(|o| scopes.contains(&o))
        });
    }

    fn problems(&self) -> Vec<Problem> {
        self.shared.particles.borrow().problems()
    }

    /// Last in: what the editor played, and anything a script's unload didn't end.
    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).end_where(EndReason::Unloaded, |_| true);
        *self.shared.particles.borrow_mut() = Particles::default();
    }
}
