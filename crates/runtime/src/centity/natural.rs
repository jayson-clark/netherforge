//! Centities that appear by themselves near players, from the `spawning` block of their `centity.json`
//! (Kotlin's `NaturalSpawner`).
//!
//! It keeps to a budget however many players and centities there are: each player gets a pass every
//! `PASS_INTERVAL` ticks (staggered by player), at most `PLAYERS_PER_TICK` passes run in a tick, and a pass
//! looks at `ATTEMPTS` places, each a column 24 to 48 blocks from the player searched from near their height
//! for somewhere to stand, or, when a rule wants water, for water. Which centities may appear there is each
//! rule's filter (world, its time, moon and weather, height, light, biome, the block stood on), its `when`
//! condition (a script's, asked last) and its cap near this player; one is chosen by weight, then a group of
//! them, each member asked of `nf.on("centity_natural_spawn")` first, which may refuse it or move it.
//! Natural instances are temporary; every `PASS_INTERVAL` ticks those with no player within their
//! `despawn_distance` go.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use mlua::{Function, Value};
use netherforge_api::{codecs, handles, shapes, values};
use netherforge_format::game::{GameData, Registry};
use netherforge_format::kind::centity::compiled::CompiledCentity;
use netherforge_format::kind::centity::{
    DAY_TICKS, DEFAULT_DESPAWN_DISTANCE, DEFAULT_SPAWN_CAP, DEFAULT_SPAWN_WEIGHT, MAX_LIGHT,
    MAX_SPAWN_DISTANCE, MIN_LIGHT, MIN_SPAWN_DISTANCE, SpawnPlacement, SpawnTime, SpawnWeather,
    SpawningDef,
};
use netherforge_luau::ScopeId;
use netherforge_platform::{BlockSnapshot, Location, PlayerRef, Uuid};

use super::api::CentityContext;
use crate::events::uuid_text;

/// Ticks between one player's passes, and between sweeps for natural centities nobody is near.
pub const PASS_INTERVAL: u64 = 20;
/// Most players whose pass runs in one tick.
pub const PLAYERS_PER_TICK: usize = 2;
/// Places looked at in one pass.
pub const ATTEMPTS: usize = 4;
/// How far above or below a player's own height a column is searched from, and how far it's searched.
const HEIGHT_REACH: i32 = 12;
const SEARCH: i32 = 24;
const GROUP_RADIUS: i32 = 3;
const GROUP_TRIES: usize = 3;

/// A condition a script registered for `spawning.when`, kept until its scope goes.
pub struct Condition {
    pub scope: ScopeId,
    pub name: String,
    pub callback: Function,
}

/// The spawner's state for one session.
pub struct Spawner {
    ticks: u64,
    due: HashMap<Uuid, u64>,
    rules: HashMap<String, (Rc<CompiledCentity>, Rule)>,
    /// Every live condition, by its full name (`<namespace>:<name>`).
    pub conditions: HashMap<String, Condition>,
    /// Conditions a rule named that no script had registered, by `<centity> <condition>`: each logged once.
    missing: HashSet<String>,
    random: netherforge_luau::random::Random,
}

impl Default for Spawner {
    fn default() -> Spawner {
        let mut seed = [0u8; 8];
        getrandom::fill(&mut seed).expect("the system has randomness");
        Spawner {
            ticks: 0,
            due: HashMap::new(),
            rules: HashMap::new(),
            conditions: HashMap::new(),
            missing: HashSet::new(),
            random: netherforge_luau::random::Random::new(
                i64::from_le_bytes(seed) & ((1 << 53) - 1),
            ),
        }
    }
}

impl Spawner {
    /// The same choices every time: for a test.
    pub fn seeded(seed: i64) -> Spawner {
        Spawner {
            random: netherforge_luau::random::Random::new(seed),
            ..Spawner::default()
        }
    }

    /// A player left: their passes go.
    pub fn forget(&mut self, player: Uuid) {
        self.due.remove(&player);
    }

    fn unit(&mut self) -> f64 {
        (self.random.next_word() >> 11) as f64 / (1u64 << 53) as f64
    }

    fn below(&mut self, n: i32) -> i32 {
        if n <= 0 {
            0
        } else {
            (self.random.next_word() % n as u64) as i32
        }
    }

    fn between(&mut self, lo: i32, hi: i32) -> i32 {
        lo + self.below(hi - lo + 1)
    }
}

/// A place a centity could be: on the ground or in water, its feet block, what's under it, its biome.
pub struct Spot {
    pub world: String,
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub placement: SpawnPlacement,
    pub feet: BlockSnapshot,
    pub below: BlockSnapshot,
    pub biome: Option<String>,
}

impl Spot {
    fn standing_on(&self) -> Option<String> {
        netherforge_format::block_state::BlockState::parse(&self.below.state).map(|s| s.id)
    }
}

/// What a world's sky is now.
struct Sky {
    time_of_day: i32,
    moon_phase: i32,
    weather: SpawnWeather,
}

/// One centity's `spawning` block with its ids and tags resolved against the game.
#[derive(Clone, Debug)]
pub struct Rule {
    pub id: String,
    worlds: Option<HashSet<String>>,
    biomes: Option<HashSet<String>>,
    blocks: Option<HashSet<String>>,
    light: (i32, i32),
    height: (Option<i32>, Option<i32>),
    pub weight: i32,
    pub group: (i32, i32),
    pub cap: i32,
    pub despawn_distance: i32,
    pub placement: SpawnPlacement,
    time: Option<SpawnTime>,
    moon_phases: Option<HashSet<i32>>,
    weather: Option<std::collections::BTreeSet<SpawnWeather>>,
    /// The `when` condition's full name (`<namespace>:<name>`).
    pub condition: Option<String>,
}

/// Ids and `#tag`s as the set of ids they name; none (anything) for no list.
fn expand(
    list: Option<&Vec<String>>,
    registry: Registry,
    game: &dyn GameData,
) -> Option<HashSet<String>> {
    let list = list.filter(|l| !l.is_empty())?;
    let mut out = HashSet::new();
    for entry in list {
        match entry.strip_prefix('#') {
            Some(tag) => {
                if let Some(ids) = game.tag_of(registry, &netherforge_format::game::normalize(tag))
                {
                    out.extend(ids.iter().cloned());
                }
            }
            None => {
                out.insert(netherforge_format::game::normalize(entry));
            }
        }
    }
    Some(out)
}

impl Rule {
    pub fn of(id: &str, def: &SpawningDef, game: &dyn GameData, home: &str) -> Rule {
        let group_min = def.group.as_ref().and_then(|g| g.min).unwrap_or(1);
        let biomes: Option<Vec<String>> = def.biomes.as_ref().map(|b| {
            b.iter()
                .map(|it| {
                    netherforge_format::kind::biome::key_of(it, home).unwrap_or_else(|| it.clone())
                })
                .collect()
        });
        Rule {
            id: id.to_owned(),
            worlds: def
                .worlds
                .as_ref()
                .filter(|w| !w.is_empty())
                .map(|w| w.iter().map(|n| n.0.clone()).collect()),
            biomes: expand(biomes.as_ref(), Registry::Biome, game),
            blocks: expand(def.blocks.as_ref(), Registry::Block, game),
            light: (
                def.light.as_ref().and_then(|l| l.min).unwrap_or(MIN_LIGHT),
                def.light.as_ref().and_then(|l| l.max).unwrap_or(MAX_LIGHT),
            ),
            height: (
                def.height.as_ref().and_then(|h| h.min),
                def.height.as_ref().and_then(|h| h.max),
            ),
            weight: def.weight.unwrap_or(DEFAULT_SPAWN_WEIGHT),
            group: (
                group_min,
                group_min.max(def.group.as_ref().and_then(|g| g.max).unwrap_or(group_min)),
            ),
            cap: def.cap.unwrap_or(DEFAULT_SPAWN_CAP),
            despawn_distance: def.despawn_distance.unwrap_or(DEFAULT_DESPAWN_DISTANCE),
            placement: def.placement.unwrap_or(SpawnPlacement::Ground),
            time: def.time,
            moon_phases: def
                .moon_phase
                .as_ref()
                .filter(|m| !m.is_empty())
                .map(|m| m.iter().map(|p| *p as i32).collect()),
            weather: def
                .weather
                .as_ref()
                .filter(|w| !w.is_empty())
                .map(|w| w.iter().copied().collect()),
            // A bare name is the centity's own package's: the project's, or `acme`'s for `acme:wisp`.
            condition: def.condition.as_ref().map(|name| {
                if name.contains(':') {
                    name.clone()
                } else {
                    let ns = id.split_once(':').map_or(home, |(ns, _)| ns);
                    format!("{ns}:{name}")
                }
            }),
        }
    }

    fn allows_world(&self, world: &str) -> bool {
        self.worlds.as_ref().is_none_or(|w| w.contains(world))
    }

    /// Whether the world's time, moon and weather allow it.
    fn allows_sky(&self, sky: &Sky) -> bool {
        if self.time.is_some_and(|t| !t.contains(sky.time_of_day)) {
            return false;
        }
        if self
            .moon_phases
            .as_ref()
            .is_some_and(|m| !m.contains(&sky.moon_phase))
        {
            return false;
        }
        // A thunderstorm is rain too.
        if let Some(weather) = &self.weather
            && !weather.contains(&sky.weather)
            && !(sky.weather == SpawnWeather::Thunder && weather.contains(&SpawnWeather::Rain))
        {
            return false;
        }
        true
    }

    fn fits(&self, spot: &Spot) -> bool {
        spot.placement == self.placement
            && self.allows_world(&spot.world)
            && self.height.0.is_none_or(|min| spot.y >= min)
            && self.height.1.is_none_or(|max| spot.y <= max)
            && (self.light.0..=self.light.1).contains(&spot.feet.light)
            && self
                .biomes
                .as_ref()
                .is_none_or(|b| spot.biome.as_ref().is_some_and(|x| b.contains(x)))
            && self
                .blocks
                .as_ref()
                .is_none_or(|b| spot.standing_on().is_some_and(|x| b.contains(&x)))
    }
}

fn near(a: &Location, b: &Location, distance: i32) -> bool {
    if a.world != b.world {
        return false;
    }
    let (dx, dy, dz) = (a.x - b.x, a.y - b.y, a.z - b.z);
    dx * dx + dy * dy + dz * dz <= f64::from(distance) * f64::from(distance)
}

/// Water something can be in: not a waterlogged block that's solid, like stairs.
fn swimmable(b: &BlockSnapshot) -> bool {
    b.water && !b.solid
}

impl CentityContext {
    /// One tick of the spawner: the despawn sweep, then the passes due.
    pub(crate) fn natural_tick(&self) {
        let ticks = {
            let mut spawner = self.shared.spawner.borrow_mut();
            spawner.ticks += 1;
            spawner.ticks
        };
        if ticks.is_multiple_of(PASS_INTERVAL) {
            self.despawn();
        }
        self.spawn_pass(ticks);
    }

    /// Every centity that has a `spawning` block, in id order, its rule resolved.
    fn rule_list(&self) -> Vec<Rule> {
        let home = self.shared.centities.borrow().home().to_owned();
        let definitions: Vec<(String, Rc<CompiledCentity>)> = {
            let centities = self.shared.centities.borrow();
            centities
                .definition_ids()
                .into_iter()
                .filter_map(|id| Some((id.clone(), centities.definition(&id)?.clone())))
                .filter(|(_, d)| d.spawning.is_some())
                .collect()
        };
        if definitions.is_empty() {
            self.shared.spawner.borrow_mut().rules.clear();
            return Vec::new();
        }
        let game = self.shared.platform.game();
        let mut spawner = self.shared.spawner.borrow_mut();
        let mut out = Vec::new();
        let mut kept = HashSet::new();
        for (id, definition) in definitions {
            let Some(spawning) = &definition.spawning else {
                continue;
            };
            kept.insert(id.clone());
            let rule = match spawner.rules.get(&id) {
                Some((was, rule)) if Rc::ptr_eq(was, &definition) => rule.clone(),
                _ => {
                    let rule = Rule::of(&id, spawning, game, &home);
                    spawner
                        .rules
                        .insert(id.clone(), (definition.clone(), rule.clone()));
                    spawner
                        .missing
                        .retain(|m| !m.starts_with(&format!("{id} ")));
                    rule
                }
            };
            out.push(rule);
        }
        spawner.rules.retain(|k, _| kept.contains(k));
        out
    }

    fn sky(&self, world: &str) -> Option<Sky> {
        let worlds = self.shared.platform.worlds();
        let time = worlds.full_time(world.to_owned())?;
        let moon = worlds.moon_phase(world.to_owned())?;
        let weather = match worlds.weather(world.to_owned()).as_deref() {
            Some("thunder") => SpawnWeather::Thunder,
            Some("rain") => SpawnWeather::Rain,
            _ => SpawnWeather::Clear,
        };
        Some(Sky {
            time_of_day: time.rem_euclid(i64::from(DAY_TICKS)) as i32,
            moon_phase: moon,
            weather,
        })
    }

    fn spawn_pass(&self, ticks: u64) {
        let platform = &*self.shared.platform;
        let all = self.rule_list();
        if all.is_empty() {
            return;
        }
        let online = platform.players().online();
        if online.is_empty() {
            return;
        }
        let mut passes = 0;
        for player in online {
            if passes >= PLAYERS_PER_TICK {
                break;
            }
            let due = {
                let mut spawner = self.shared.spawner.borrow_mut();
                let stagger = (player.uuid.0 % u128::from(PASS_INTERVAL)) as u64;
                let at = *spawner.due.entry(player.uuid).or_insert(ticks + stagger);
                if at > ticks {
                    continue;
                }
                spawner.due.insert(player.uuid, ticks + PASS_INTERVAL);
                at
            };
            let _ = due;
            passes += 1;
            let Some(at) = platform.players().location(player.uuid) else {
                continue;
            };
            let Some(sky) = self.sky(&at.world) else {
                continue;
            };
            let here: Vec<Rule> = all
                .iter()
                .filter(|r| r.allows_world(&at.world) && r.allows_sky(&sky))
                .cloned()
                .collect();
            if !here.is_empty() {
                self.pass(&player, &at, here);
            }
        }
    }

    fn pass(&self, player: &PlayerRef, at: &Location, candidates: Vec<Rule>) {
        let mut remaining: Vec<(Rule, i32)> = {
            let centities = self.shared.centities.borrow();
            candidates
                .into_iter()
                .map(|rule| {
                    let near_now = centities
                        .all()
                        .filter(|i| {
                            i.natural
                                && !i.removed
                                && i.centity() == rule.id
                                && near(i.anchor(), at, rule.despawn_distance)
                        })
                        .count() as i32;
                    let room = rule.cap - near_now;
                    (rule, room)
                })
                .filter(|(_, room)| *room > 0)
                .collect()
        };
        for _ in 0..ATTEMPTS {
            if remaining.is_empty() {
                return;
            }
            let water = remaining
                .iter()
                .any(|(r, _)| r.placement == SpawnPlacement::Water);
            let Some(spot) = self.find_spot(at, water) else {
                continue;
            };
            let fits: Vec<usize> = (0..remaining.len())
                .filter(|i| {
                    remaining[*i].0.fits(&spot) && self.holds(&remaining[*i].0, &spot, player)
                })
                .collect();
            if fits.is_empty() {
                continue;
            }
            let chosen = {
                let total: i32 = fits.iter().map(|i| remaining[*i].0.weight).sum();
                let mut pick = self.shared.spawner.borrow_mut().below(total);
                let mut chosen = *fits.last().expect("some");
                for i in &fits {
                    pick -= remaining[*i].0.weight;
                    if pick < 0 {
                        chosen = *i;
                        break;
                    }
                }
                chosen
            };
            let rule = remaining[chosen].0.clone();
            let wanted = self
                .shared
                .spawner
                .borrow_mut()
                .between(rule.group.0, rule.group.1);
            let count = wanted.min(remaining[chosen].1);
            let mut spawned = 0;
            for member in 0..count {
                // The first is where the place was found; the rest stand near it, wherever they fit.
                let place = if member == 0 {
                    Some(spot_clone(&spot))
                } else {
                    self.around(&spot, &rule, player)
                };
                let Some(place) = place else { continue };
                if self.spawn_natural(&rule, &place, player) {
                    spawned += 1;
                }
            }
            remaining[chosen].1 -= spawned;
            remaining.retain(|(_, room)| *room > 0);
        }
    }

    /// One member of a group: asks `centity_natural_spawn`, then spawns. False when a handler refused it.
    fn spawn_natural(&self, rule: &Rule, spot: &Spot, player: &PlayerRef) -> bool {
        let mut at = Location {
            world: spot.world.clone(),
            x: f64::from(spot.x) + 0.5,
            y: f64::from(spot.y),
            z: f64::from(spot.z) + 0.5,
            yaw: 0.0,
            pitch: 0.0,
        };
        let mut yaw = self.shared.spawner.borrow_mut().unit() * 360.0 - 180.0;
        let target = crate::Target::Nf;
        if self.scripts.listening(&target, "centity_natural_spawn") {
            let event = shapes::CentityNaturalSpawnEvent {
                centity: rule.id.clone(),
                location: values::Location {
                    world: handles::World {
                        name: at.world.clone(),
                    },
                    position: values::Vec3 {
                        x: at.x,
                        y: at.y,
                        z: at.z,
                    },
                    yaw: Some(yaw),
                    pitch: None,
                },
                player: handles::Player {
                    id: uuid_text(&player.uuid),
                },
            };
            let lua = self.scripts.host().lua().clone();
            let Ok(Value::Table(fields)) =
                netherforge_api::codec::Codec::push(&codecs::CentityNaturalSpawnEvent, &lua, event)
            else {
                return false;
            };
            let delivered = self.scripts.raise(&target, "centity_natural_spawn", fields);
            if delivered.cancelled {
                return false;
            }
            if let Some(moved) = self.field(
                &delivered,
                "location",
                netherforge_api::codec::LocationCodec,
            ) {
                if !self
                    .shared
                    .platform
                    .worlds()
                    .exists(moved.world.name.clone())
                {
                    return false;
                }
                at = Location {
                    world: moved.world.name,
                    x: moved.position.x,
                    y: moved.position.y,
                    z: moved.position.z,
                    yaw: 0.0,
                    pitch: 0.0,
                };
                yaw = moved.yaw.unwrap_or(yaw);
            }
        }
        self.spawn_with(&rule.id, at, yaw, &|i| i.natural = true)
            .is_some()
    }

    /// Whether `rule`'s `when` condition holds at `spot`: true without one. One no script registered is false
    /// (logged once a centity), and so is one that fails or answers anything but true.
    fn holds(&self, rule: &Rule, spot: &Spot, player: &PlayerRef) -> bool {
        let Some(key) = &rule.condition else {
            return true;
        };
        let condition = self
            .shared
            .spawner
            .borrow()
            .conditions
            .get(key)
            .map(|c| (c.scope, c.callback.clone(), c.name.clone()));
        let Some((scope, callback, name)) = condition else {
            let first = self
                .shared
                .spawner
                .borrow_mut()
                .missing
                .insert(format!("{} {key}", rule.id));
            if first {
                let bare = key.split_once(':').map_or(key.as_str(), |(_, n)| n);
                self.shared.platform.log().warn(format!(
                    "Centity {} spawns only when \"{bare}\" holds, and no script has registered that condition (nf.spawning.condition in the {key}'s package), so it doesn't appear",
                    rule.id
                ));
            }
            return false;
        };
        let lua = self.scripts.host().lua().clone();
        let Ok(check) = lua.create_table() else {
            return false;
        };
        let location = values::Location {
            world: handles::World {
                name: spot.world.clone(),
            },
            position: values::Vec3 {
                x: f64::from(spot.x) + 0.5,
                y: f64::from(spot.y),
                z: f64::from(spot.z) + 0.5,
            },
            yaw: None,
            pitch: None,
        };
        let pushed = (|| -> mlua::Result<()> {
            check.raw_set("centity", rule.id.clone())?;
            check.raw_set(
                "location",
                netherforge_api::codec::Codec::push(
                    &netherforge_api::codec::LocationCodec,
                    &lua,
                    location,
                )?,
            )?;
            let player = netherforge_api::handle::push_any(
                &lua,
                &handles::Handle::Player(handles::Player {
                    id: uuid_text(&player.uuid),
                }),
            )?;
            check.raw_set("player", player)?;
            Ok(())
        })();
        if pushed.is_err() {
            return false;
        }
        match self.scripts.call_kept(scope, &callback, check) {
            Ok(Value::Boolean(true)) => true,
            Ok(_) => false,
            Err(failure) => {
                self.scripts
                    .handler_failed(scope, &format!("spawn condition \"{name}\""), failure);
                false
            }
        }
    }

    /// A column `MIN_SPAWN_DISTANCE..MAX_SPAWN_DISTANCE` blocks from `at`, searched from around its height.
    fn find_spot(&self, at: &Location, water: bool) -> Option<Spot> {
        let (angle, distance, dy) = {
            let mut spawner = self.shared.spawner.borrow_mut();
            let angle = spawner.unit() * std::f64::consts::TAU;
            let distance = f64::from(MIN_SPAWN_DISTANCE)
                + spawner.unit() * f64::from(MAX_SPAWN_DISTANCE - MIN_SPAWN_DISTANCE);
            (
                angle,
                distance,
                spawner.between(-HEIGHT_REACH, HEIGHT_REACH),
            )
        };
        let x = (at.x + angle.cos() * distance).floor() as i32;
        let z = (at.z + angle.sin() * distance).floor() as i32;
        self.spot_in(&at.world, x, z, at.y.floor() as i32 + dy, water)
    }

    /// A spot within a few blocks of `spot`, for the rest of a group.
    fn around(&self, spot: &Spot, rule: &Rule, player: &PlayerRef) -> Option<Spot> {
        for _ in 0..GROUP_TRIES {
            let (dx, dz, dy) = {
                let mut s = self.shared.spawner.borrow_mut();
                (
                    s.between(-GROUP_RADIUS, GROUP_RADIUS),
                    s.between(-GROUP_RADIUS, GROUP_RADIUS),
                    s.between(-1, 1),
                )
            };
            let found = self.spot_in(
                &spot.world,
                spot.x + dx,
                spot.z + dz,
                spot.y + dy,
                rule.placement == SpawnPlacement::Water,
            );
            if let Some(found) = found
                && rule.fits(&found)
                && self.holds(rule, &found, player)
            {
                return Some(found);
            }
        }
        None
    }

    /// Searches one column from `from` for air over something solid, or with `water`, for water too.
    fn spot_in(&self, world: &str, x: i32, z: i32, from: i32, water: bool) -> Option<Spot> {
        let platform = &*self.shared.platform;
        let (low, high) = platform.worlds().heights(world.to_owned())?;
        let get = |y: i32| platform.blocks().get(world.to_owned(), x, y, z);
        let mut y = from.clamp(low + 1, high - 2);
        let mut feet = get(y)?;
        let mut steps = 0;
        // From inside the ground, up to the surface; from the air, down onto it.
        while steps < SEARCH && (feet.solid || feet.liquid) && y < high - 2 {
            if water && swimmable(&feet) {
                return self.in_water(world, x, y, z, feet);
            }
            y += 1;
            feet = get(y)?;
            steps += 1;
        }
        if water && swimmable(&feet) {
            return self.in_water(world, x, y, z, feet);
        }
        if feet.solid || feet.liquid {
            return None;
        }
        let mut below = get(y - 1)?;
        while steps < SEARCH && !below.solid && !below.liquid && y - 1 > low {
            y -= 1;
            feet = below;
            below = get(y - 1)?;
            steps += 1;
        }
        if water && swimmable(&below) {
            return self.in_water(world, x, y - 1, z, below);
        }
        if !below.solid || below.liquid {
            return None;
        }
        // Room for its head.
        let head = get(y + 1)?;
        if head.solid || head.liquid {
            return None;
        }
        let biome = platform.worlds().biome(world.to_owned(), x, y, z);
        Some(Spot {
            world: world.to_owned(),
            x,
            y,
            z,
            placement: SpawnPlacement::Ground,
            feet,
            below,
            biome,
        })
    }

    /// A place in the water block `feet` at `y`, with no solid block over it.
    fn in_water(&self, world: &str, x: i32, y: i32, z: i32, feet: BlockSnapshot) -> Option<Spot> {
        let platform = &*self.shared.platform;
        let head = platform.blocks().get(world.to_owned(), x, y + 1, z)?;
        if head.solid {
            return None;
        }
        let below = platform.blocks().get(world.to_owned(), x, y - 1, z)?;
        let biome = platform.worlds().biome(world.to_owned(), x, y, z);
        Some(Spot {
            world: world.to_owned(),
            x,
            y,
            z,
            placement: SpawnPlacement::Water,
            feet,
            below,
            biome,
        })
    }

    /// Removes natural centities with no player within their `despawn_distance`, in their world.
    fn despawn(&self) {
        let platform = &*self.shared.platform;
        let naturals: Vec<(Uuid, Location, i32)> = self
            .shared
            .centities
            .borrow()
            .all()
            .filter(|i| i.natural && !i.removed)
            .map(|i| {
                let distance = i
                    .definition
                    .spawning
                    .as_ref()
                    .and_then(|s| s.despawn_distance)
                    .unwrap_or(DEFAULT_DESPAWN_DISTANCE);
                (i.id, i.anchor().clone(), distance)
            })
            .collect();
        if naturals.is_empty() {
            return;
        }
        let players: Vec<Location> = platform
            .players()
            .online()
            .iter()
            .filter_map(|p| platform.players().location(p.uuid))
            .collect();
        for (id, at, distance) in naturals {
            if !players.iter().any(|p| near(&at, p, distance)) {
                CentityContext::remove(self, id);
            }
        }
    }

    /// The scopes that stopped take their conditions with them.
    pub(crate) fn release_conditions(&self, released: &[ScopeId]) {
        self.shared
            .spawner
            .borrow_mut()
            .conditions
            .retain(|_, c| !released.contains(&c.scope));
    }
}

fn spot_clone(spot: &Spot) -> Spot {
    Spot {
        world: spot.world.clone(),
        x: spot.x,
        y: spot.y,
        z: spot.z,
        placement: spot.placement,
        feet: spot.feet.clone(),
        below: spot.below.clone(),
        biome: spot.biome.clone(),
    }
}
