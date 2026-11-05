//! The project's skies (`skies/<id>.json`) over worlds and players (Kotlin's `Skies`): which one each world and
//! each player has (`world:set_sky`, `player:set_sky`), and what each online player is shown of it, a step of
//! its fade at a time.
//!
//! A sky is shown as its twin biomes (format's `startup::sky`), which the server learns only as it starts, from
//! the start-up datapack: a sky the server started with has twins for the steps its file had then, and one added
//! since has none (setting it is an error until a restart). Nothing here is kept: the session stopping puts
//! everyone back under the world's own biomes.
//!
//! Not yet: saying a sky was left out because the server refused its twins (`DatapackOps::refused` is still a
//! placeholder); such a sky reads as new since the server started.

use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;

use netherforge_api::{ApiError, ApiResult};
use netherforge_format::kind::sky::SkyFile;
use netherforge_format::reference::{ResourceKey, resolve_any};
use netherforge_format::startup::sky::{CLOCK_FOLDER, FOLDER, night_key};
use netherforge_platform::{Platform, PlayerRef, SkyView, Uuid};

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// What a world or a player was last given: a sky, or none, and over how long it fades; `order` says which
/// came last.
#[derive(Clone)]
struct Target {
    sky: Option<String>,
    fade: i64,
    order: u64,
}

/// What a player is shown now, and how it's getting where it's going.
#[derive(Default)]
struct Viewer {
    world: Option<String>,
    sky: Option<String>,
    step: i32,
    want_sky: Option<String>,
    want_fade: i64,
    interval: i64,
    next_at: i64,
    shown: Option<SkyView>,
}

/// The skies the session holds.
#[derive(Default)]
pub struct Skies {
    /// Every sky the project's files have, as the project names it (`blood_moon`, `acme:eclipse`).
    files: BTreeMap<String, SkyFile>,
    home: String,
    worlds: HashMap<String, Target>,
    players: HashMap<Uuid, Target>,
    changes: u64,
    viewers: HashMap<Uuid, Viewer>,
    now: i64,
    /// The steps of each sky's fade the server has twins for, and its clock, read once from what it started with.
    started: HashMap<String, (i32, Option<String>)>,
}

impl Skies {
    fn key_of(&self, name: &str) -> ResourceKey {
        resolve_any(&self.home, name).expect("a sky's name")
    }

    /// The steps of `name`'s fade the server has twins for, and its clock when it started with one.
    fn started(&mut self, platform: &dyn Platform, name: &str) -> (i32, Option<String>) {
        if let Some(known) = self.started.get(name) {
            return known.clone();
        }
        let key = self.key_of(name);
        let started = platform.datapacks().started();
        let prefix = format!(
            "data/{}/worldgen/biome/{FOLDER}/{}/{}/",
            self.home, key.namespace, key.path
        );
        let steps = started
            .keys()
            .filter_map(|p| p.strip_prefix(&prefix))
            .filter_map(|rest| rest.split('/').next()?.parse::<i32>().ok())
            .max()
            .unwrap_or(0);
        let clock_key = night_key(&self.home, &key);
        let clock = started
            .contains_key(&format!(
                "data/{}/{CLOCK_FOLDER}/{}.json",
                clock_key.namespace, clock_key.path
            ))
            .then(|| clock_key.to_string());
        self.started.insert(name.to_owned(), (steps, clock.clone()));
        (steps, clock)
    }

    fn steps(&mut self, platform: &dyn Platform, name: &str) -> i32 {
        self.started(platform, name).0
    }

    /// That `name` (as the project names it) can be set: an error for one the project has no file for, or
    /// none the server started with.
    pub fn check(&mut self, platform: &dyn Platform, name: &str) -> ApiResult<()> {
        if !self.files.contains_key(name) {
            let file = name.split_once(':').map_or(name, |(_, f)| f);
            return Err(err(format!(
                "there's no sky \"{name}\" (skies/{file}.json)"
            )));
        }
        if self.steps(platform, name) == 0 {
            return Err(err(format!(
                "sky \"{name}\" is new since the server started: a sky's colours are biomes, which the game learns as it starts, so restart it"
            )));
        }
        Ok(())
    }

    fn target(&mut self, sky: Option<String>, fade: i64) -> Target {
        self.changes += 1;
        Target {
            sky,
            fade,
            order: self.changes,
        }
    }

    pub fn set_world(
        &mut self,
        platform: &dyn Platform,
        world: &str,
        sky: &str,
        fade: i64,
    ) -> ApiResult<()> {
        self.check(platform, sky)?;
        let target = self.target(Some(sky.to_owned()), fade);
        self.worlds.insert(world.to_owned(), target);
        Ok(())
    }

    pub fn clear_world(&mut self, world: &str, fade: i64) {
        if self
            .worlds
            .get(world)
            .and_then(|t| t.sky.as_ref())
            .is_none()
        {
            return;
        }
        let target = self.target(None, fade);
        self.worlds.insert(world.to_owned(), target);
    }

    pub fn of_world(&self, world: &str) -> Option<String> {
        self.worlds.get(world)?.sky.clone()
    }

    pub fn set_player(
        &mut self,
        platform: &dyn Platform,
        player: Uuid,
        sky: &str,
        fade: i64,
    ) -> ApiResult<()> {
        self.check(platform, sky)?;
        let target = self.target(Some(sky.to_owned()), fade);
        self.players.insert(player, target);
        Ok(())
    }

    pub fn clear_player(&mut self, player: Uuid, fade: i64) {
        if self
            .players
            .get(&player)
            .and_then(|t| t.sky.as_ref())
            .is_none()
        {
            return;
        }
        let target = self.target(None, fade);
        self.players.insert(player, target);
    }

    pub fn of_player(&self, player: Uuid) -> Option<String> {
        self.players.get(&player)?.sky.clone()
    }

    fn jump(&mut self, platform: &dyn Platform, player: Uuid, want: Option<String>) {
        let steps = want.as_deref().map_or(0, |w| self.steps(platform, w));
        let viewer = self.viewers.get_mut(&player).expect("a viewer");
        viewer.want_sky.clone_from(&want);
        viewer.sky = want;
        viewer.step = steps;
    }

    /// Moves what `player` is shown a step towards what they should see, when it's due.
    fn view(&mut self, platform: &dyn Platform, player: Uuid) {
        let world = platform.players().location(player).map(|l| l.world);
        let own = self.players.get(&player).cloned();
        let there = world.as_ref().and_then(|w| self.worlds.get(w)).cloned();
        // Their own sky over their world's; of the two, the fade of the one that changed last.
        let target = own.clone().filter(|t| t.sky.is_some()).or(there.clone());
        let latest = [own, there].into_iter().flatten().max_by_key(|t| t.order);
        let want = target.and_then(|t| t.sky);
        let fade = latest.map_or(0, |t| t.fade);
        if let std::collections::hash_map::Entry::Vacant(entry) = self.viewers.entry(player) {
            entry.insert(Viewer {
                world: world.clone(),
                ..Viewer::default()
            });
            self.jump(platform, player, want.clone());
        }
        let (same_world, same_want) = {
            let v = &self.viewers[&player];
            (v.world == world, v.want_sky == want && v.want_fade == fade)
        };
        if !same_world {
            // Another world: its sky at once, as the client loads its chunks anew anyway.
            self.viewers.get_mut(&player).expect("a viewer").world = world;
            self.jump(platform, player, want.clone());
        } else if !same_want {
            if fade <= 0 {
                self.viewers.get_mut(&player).expect("a viewer").want_fade = fade;
                self.jump(platform, player, want.clone());
            } else {
                let want_steps = want.as_deref().map_or(0, |w| self.steps(platform, w));
                let now = self.now;
                let v = self.viewers.get_mut(&player).expect("a viewer");
                v.want_sky.clone_from(&want);
                v.want_fade = fade;
                let out = if v.sky.is_some() && v.sky != want {
                    v.step
                } else {
                    0
                };
                let into = if want.is_some() {
                    want_steps - if v.sky == want { v.step } else { 0 }
                } else {
                    0
                };
                let moves = i64::from(out + into);
                v.interval = if moves > 0 { (fade / moves).max(1) } else { 0 };
                v.next_at = now;
            }
        }
        if self.now >= self.viewers[&player].next_at {
            self.step(platform, player, want);
        }
        self.show(platform, player);
    }

    /// One step: the sky shown out first when it isn't the one wanted, then the one wanted in.
    fn step(&mut self, platform: &dyn Platform, player: Uuid, want: Option<String>) {
        let want_steps = want.as_deref().map_or(0, |w| self.steps(platform, w));
        let now = self.now;
        let v = self.viewers.get_mut(&player).expect("a viewer");
        match &v.sky {
            Some(shown) if Some(shown) != want.as_ref() => {
                v.step -= 1;
                if v.step <= 0 {
                    v.sky = None;
                    v.step = 0;
                }
            }
            shown if want.is_some() && (shown.is_none() || v.step < want_steps) => {
                v.sky.clone_from(&want);
                v.step += 1;
            }
            _ => return,
        }
        v.next_at = now + v.interval;
    }

    fn show(&mut self, platform: &dyn Platform, player: Uuid) {
        let (sky, step) = {
            let v = &self.viewers[&player];
            (v.sky.clone(), v.step)
        };
        let view = match sky {
            Some(sky) if step > 0 => {
                let clock = self.started(platform, &sky).1;
                Some(SkyView {
                    home: self.home.clone(),
                    sky: self.key_of(&sky).to_string(),
                    step,
                    clock,
                })
            }
            _ => None,
        };
        let v = self.viewers.get_mut(&player).expect("a viewer");
        if view == v.shown {
            return;
        }
        v.shown.clone_from(&view);
        platform.skies().show(player, view);
    }
}

/// Skies as a session's service.
pub struct SkiesService {
    pub shared: Rc<Shared>,
}

impl SkiesService {
    fn define(&self, project: &Define) {
        let mut skies = self.shared.skies.borrow_mut();
        skies.home = project.project.namespace().to_owned();
        skies.files = project
            .project
            .running::<SkyFile>()
            .into_iter()
            .map(|(id, file)| (id, file.clone()))
            .collect();
    }
}

impl Service for SkiesService {
    fn name(&self) -> &'static str {
        "skies"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["sky"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        self.define(project);
    }

    fn reload(
        &self,
        _room: &Room,
        _kind: &str,
        _ids: &std::collections::BTreeSet<String>,
        project: &Define,
    ) {
        self.define(project);
    }

    fn tick(&self, _room: &Room, platform: &dyn Platform) {
        let mut skies = self.shared.skies.borrow_mut();
        skies.now += 1;
        if skies.worlds.is_empty() && skies.players.is_empty() && skies.viewers.is_empty() {
            return;
        }
        for player in platform.players().online() {
            skies.view(platform, player.uuid);
        }
    }

    fn player_quit(&self, player: &PlayerRef) {
        let mut skies = self.shared.skies.borrow_mut();
        skies.viewers.remove(&player.uuid);
        skies.players.remove(&player.uuid);
    }

    /// Everyone shown the world's own biomes again.
    fn stop(&self, _room: &Room, platform: &dyn Platform) {
        let mut skies = self.shared.skies.borrow_mut();
        for (player, viewer) in &skies.viewers {
            if viewer.shown.is_some() {
                platform.skies().show(*player, None);
            }
        }
        *skies = Skies::default();
    }
}

/// A sky option's fade, in ticks: none below 0.
pub fn fade_ticks(fade: Option<i64>) -> ApiResult<i64> {
    match fade {
        None => Ok(0),
        Some(t) if t < 0 => Err(err("options.fade_ticks can't be negative")),
        Some(t) => Ok(t.min(i64::from(i32::MAX))),
    }
}
