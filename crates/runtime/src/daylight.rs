//! A world's `dusk` and `dawn`, `nf`'s `world_dusk` and `world_dawn` (Kotlin's `Daylight`), and the spawn rates
//! and game rules `netherforge.json`'s `worlds` sets (Kotlin's `WorldSpawnRates`).
//!
//! Daylight: each loaded world's clock is looked at once a tick, and a period of the day (format's `DayPeriod`:
//! night from 13000 to 22999) that differs from the one last seen there is a crossing, whether the clock ran into
//! it or `/time set` jumped there. Only while some script listens to one of the four events: otherwise no clock is
//! read. What was last seen is forgotten when nobody listens and when a world is loaded again, so the first look
//! at a world only notes its period: a script that starts listening at night hears the next dawn, not a dusk that
//! already went by.
//!
//! Spawn rates: put in place for every world `worlds` names that's loaded when the session starts (so a reload
//! re-applies them, over what scripts set with `world:set_spawn_limit`), and for each one loaded or created later.
//! Categories it doesn't name keep whatever the world has.

use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap};

use mlua::Value;
use netherforge_api::codec::Codec;
use netherforge_api::handle;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{codecs, shapes};
use netherforge_format::kind::centity::{DAY_TICKS, DayPeriod};
use netherforge_format::manifest::WorldConfig;
use netherforge_platform::{Dynamic, GameRuleType, Platform};

use crate::Room;
use crate::scripts::Target;
use crate::session::{Define, Service};

/// The time of day in ticks, 0 to 23999, of a world whose clock says `full_time`.
pub fn time_of_day(full_time: i64) -> i64 {
    full_time.rem_euclid(i64::from(DAY_TICKS))
}

/// Whether `time_of_day` (0 to 23999) is night, as format's `DayPeriod::Night` says.
pub fn is_night(time_of_day: i64) -> bool {
    let (start, end) = DayPeriod::Night.ticks();
    (i64::from(start)..=i64::from(end)).contains(&time_of_day)
}

/// Dusk and dawn.
#[derive(Default)]
pub struct DaylightService {
    /// Whether each world was at night when last looked at, while something listens.
    night: RefCell<HashMap<String, bool>>,
}

impl DaylightService {
    fn listening(room: &Room) -> bool {
        let scripts = room.scripts();
        ["dusk", "dawn", "world_dusk", "world_dawn"]
            .iter()
            .any(|event| scripts.listening_anywhere(event))
    }
}

impl Service for DaylightService {
    fn name(&self) -> &'static str {
        "daylight"
    }

    fn tick(&self, room: &Room, platform: &dyn Platform) {
        if !Self::listening(room) {
            self.night.borrow_mut().clear();
            return;
        }
        let worlds = platform.worlds();
        for world in worlds.names() {
            let Some(full) = worlds.full_time(world.clone()) else {
                continue;
            };
            let time = time_of_day(full);
            let now = is_night(time);
            let Some(before) = self.night.borrow_mut().insert(world.clone(), now) else {
                continue;
            };
            if before == now {
                continue;
            }
            let scripts = room.scripts();
            let lua = scripts.host().lua().clone();
            let world_handle = handles::World { name: world };
            let Ok(target) = handle::push_any(&lua, &Handle::World(world_handle.clone())) else {
                continue;
            };
            let (own, nf) = if now {
                ("dusk", "world_dusk")
            } else {
                ("dawn", "world_dawn")
            };
            let payload = shapes::DaylightEvent {
                world: world_handle,
                time_of_day: time,
            };
            if let Ok(Value::Table(fields)) = codecs::DaylightEvent.push(&lua, payload) {
                scripts.raise_path(
                    &[(Target::Handle(target), own), (Target::Nf, nf)],
                    fields,
                    false,
                );
            }
        }
    }

    fn world_loaded(&self, _platform: &dyn Platform, world: &str) {
        self.night.borrow_mut().remove(world);
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        self.night.borrow_mut().clear();
    }
}

/// `worlds`' spawn rates and game rules.
#[derive(Default)]
pub struct SpawnRatesService {
    worlds: RefCell<BTreeMap<String, WorldConfig>>,
}

impl SpawnRatesService {
    fn apply(&self, platform: &dyn Platform, world: &str) {
        let Some(config) = self.worlds.borrow().get(world).cloned() else {
            return;
        };
        let ops = platform.worlds();
        for (category, limit) in config.spawn_limits.iter().flat_map(|e| e.0.iter()) {
            ops.set_spawn_limit(world.to_owned(), *category, clamp(*limit));
        }
        for (category, ticks) in config.spawn_intervals.iter().flat_map(|e| e.0.iter()) {
            ops.set_spawn_interval(world.to_owned(), *category, clamp(*ticks));
        }
        for (rule, value) in config.game_rules.iter().flat_map(|e| e.0.iter()) {
            set_rule(platform, world, rule, value);
        }
    }
}

fn clamp(n: i64) -> i32 {
    n.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32
}

/// Sets one of `gameRules`, as the rule takes it; a rule the server lacks, or a value of the wrong kind, is said in
/// the log.
fn set_rule(platform: &dyn Platform, world: &str, rule: &str, value: &serde_json::Value) {
    let id = netherforge_format::game::normalize(rule);
    let typed = match platform.worlds().game_rule_type(id.clone()) {
        None => {
            return platform.log().warn(format!(
                "netherforge.json: world \"{world}\" sets game rule \"{rule}\", which this server's Minecraft doesn't have"
            ));
        }
        Some(GameRuleType::Boolean) => value.as_bool().map(Dynamic::Bool),
        Some(GameRuleType::Integer) => value
            .as_i64()
            .filter(|n| i32::try_from(*n).is_ok())
            .map(|n| Dynamic::Number(n as f64)),
    };
    match typed {
        Some(typed) => {
            platform.worlds().set_game_rule(world.to_owned(), id, typed);
        }
        None => platform.log().warn(format!(
            "netherforge.json: world \"{world}\"'s game rule \"{rule}\" doesn't take {value}"
        )),
    }
}

impl Service for SpawnRatesService {
    fn name(&self) -> &'static str {
        "spawn rates"
    }

    fn start(&self, _room: &Room, project: &Define) {
        let worlds: BTreeMap<String, WorldConfig> = project
            .project
            .manifest
            .as_ref()
            .and_then(|m| m.worlds.clone())
            .map(|w| w.0.into_iter().map(|(k, v)| (k.0, v)).collect())
            .unwrap_or_default();
        let names: Vec<String> = worlds.keys().cloned().collect();
        *self.worlds.borrow_mut() = worlds;
        for world in names {
            if project.platform.worlds().exists(world.clone()) {
                self.apply(project.platform, &world);
            }
        }
    }

    fn world_loaded(&self, platform: &dyn Platform, world: &str) {
        self.apply(platform, world);
    }
}
