//! `spawn_particle` and `play_sound` (Kotlin's `Effects`): ids and options checked against the server (particles
//! and their data kinds, the sound registry) and the project (pack sounds), then handed to the Platform.
//! Particles go through the batched `ParticleOps::spawn` particle effects use, with viewers worked out here.

use netherforge_api::{ApiError, ApiResult, shapes, values};
use netherforge_format::game::{self, ParticleData, Registry};
use netherforge_format::kind::particle::sample::SpawnData;
use netherforge_format::math::Vec3;
use netherforge_platform::{ParticleSpawn, Platform, PlayerRef, SoundPlay};

use crate::names::Calling;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// How far the client draws particles that aren't forced, and those that are.
const RANGE: f64 = 32.0;
const FORCED_RANGE: f64 = 128.0;
/// Particles one spawn may scatter: past this a script's typo could flood every client.
const MAX_COUNT: i64 = 10_000;
const WHITE: i32 = 0xFF_FFFF;

/// `#rrggbb` (either case) as `0xRRGGBB`; white when not given.
fn color(text: Option<&str>, field: &str) -> ApiResult<i32> {
    let Some(text) = text else { return Ok(WHITE) };
    let hex = text
        .strip_prefix('#')
        .filter(|h| h.len() == 6 && h.chars().all(|c| c.is_ascii_hexdigit()));
    hex.and_then(|h| i32::from_str_radix(h, 16).ok())
        .ok_or_else(|| {
            err(format!(
                "options.{field} must be \"#rrggbb\", not \"{text}\""
            ))
        })
}

/// One spawn from a particle id and its options, every option checked against what the particle takes.
pub fn particle_spawn(
    platform: &dyn Platform,
    particle: &str,
    position: &values::Vec3,
    options: &shapes::ParticleOptions,
) -> ApiResult<ParticleSpawn> {
    if !game::is_id(particle) {
        return Err(err(format!("\"{particle}\" isn't a particle id")));
    }
    let id = game::normalize(particle);
    let kind = platform
        .game()
        .particle(&id)
        .ok_or_else(|| err(format!("no particle \"{id}\" on this server")))?;
    if kind == ParticleData::Other {
        return Err(err(format!(
            "particle \"{id}\" takes data NetherForge can't send"
        )));
    }
    let given = [
        ("color", options.color.is_some()),
        ("to_color", options.to_color.is_some()),
        ("size", options.size.is_some()),
        ("block_state", options.block_state.is_some()),
        ("item", options.item.is_some()),
    ];
    let takes: &[&str] = match kind {
        ParticleData::Dust => &["color", "size"],
        ParticleData::DustTransition => &["color", "to_color", "size"],
        ParticleData::Color => &["color"],
        ParticleData::Block => &["block_state"],
        ParticleData::Item => &["item"],
        ParticleData::None | ParticleData::Other => &[],
    };
    if let Some((option, _)) = given.iter().find(|(o, given)| *given && !takes.contains(o)) {
        return Err(err(format!(
            "particle \"{id}\" doesn't take options.{option}"
        )));
    }
    let count = options.count.unwrap_or(1);
    if !(0..=MAX_COUNT).contains(&count) {
        return Err(err(format!(
            "options.count must be 0 to {MAX_COUNT}, not {count}"
        )));
    }
    let size = options.size.unwrap_or(1.0);
    if !(0.01..=4.0).contains(&size) {
        return Err(err(format!("options.size must be 0.01 to 4, not {size}")));
    }
    let main = color(options.color.as_deref(), "color")?;
    let data = match kind {
        ParticleData::Dust => Some(SpawnData::Dust { color: main, size }),
        ParticleData::DustTransition => Some(SpawnData::DustTransition {
            color: main,
            to_color: color(options.to_color.as_deref(), "to_color")?,
            size,
        }),
        ParticleData::Color => Some(SpawnData::Color { color: main }),
        ParticleData::Block => {
            let state = options
                .block_state
                .as_deref()
                .ok_or_else(|| err(format!("particle \"{id}\" needs options.block_state")))?;
            Some(SpawnData::Block {
                state: crate::block::canonical_state(
                    platform.game(),
                    state,
                    "options.block_state",
                )?,
            })
        }
        ParticleData::Item => {
            let item = options
                .item
                .as_ref()
                .ok_or_else(|| err(format!("particle \"{id}\" needs options.item")))?;
            let stack =
                crate::items::from_value(item, "options.item", platform.game()).map_err(err)?;
            Some(SpawnData::Item {
                item: Box::new(stack.def),
            })
        }
        ParticleData::None | ParticleData::Other => None,
    };
    Ok(ParticleSpawn {
        particle: id,
        position: Vec3(position.x, position.y, position.z),
        count: count as i32,
        offset: options
            .spread
            .as_ref()
            .map_or(Vec3(0.0, 0.0, 0.0), |s| Vec3(s.x, s.y, s.z)),
        speed: options.speed.unwrap_or(0.0),
        data,
        force: options.force == Some(true),
    })
}

/// Sends `spawn` in `world` to the players in range of it, of `chosen` (UUIDs as handles key them) when given.
/// False when the world doesn't exist.
pub fn send(
    platform: &dyn Platform,
    world: &str,
    spawn: ParticleSpawn,
    chosen: Option<&[String]>,
) -> bool {
    if !platform.worlds().exists(world.to_owned()) {
        return false;
    }
    let range = if spawn.force { FORCED_RANGE } else { RANGE };
    let players = platform.players();
    let viewers: Vec<PlayerRef> = players
        .online()
        .into_iter()
        .filter(|player| {
            if chosen.is_some_and(|c| !c.contains(&crate::events::uuid_text(&player.uuid))) {
                return false;
            }
            let Some(at) = players.location(player.uuid) else {
                return false;
            };
            let (dx, dy, dz) = (
                at.x - spawn.position.0,
                at.y - spawn.position.1,
                at.z - spawn.position.2,
            );
            at.world == world && dx * dx + dy * dy + dz * dz <= range * range
        })
        .collect();
    if !viewers.is_empty() {
        platform
            .particles()
            .spawn(world.to_owned(), vec![spawn], viewers);
    }
    true
}

/// A sound id, namespaced, once it's known to name a sound: one of a loaded package's pack sounds, by
/// reference (a sound is a look), or else one of the server's (`entity.pig.ambient` is
/// `minecraft:entity.pig.ambient`).
pub fn sound_id(platform: &dyn Platform, calling: &Calling, sound: &str) -> ApiResult<String> {
    let (key, problem) = calling.pack_sound(sound);
    if let Some(key) = key {
        return Ok(key.to_string());
    }
    if !game::is_id(sound) {
        return Err(err(format!("\"{sound}\" isn't a sound id")));
    }
    let id = game::normalize(sound);
    if !platform.game().lacks(Registry::SoundEvent, &id) {
        return Ok(id);
    }
    // Shaped like a sound of a loaded package's pack: say what that pack lacks.
    if let Some(problem) = problem {
        return Err(err(problem));
    }
    Err(err(format!(
        "no sound \"{id}\" on this server or in the project's resource packs"
    )))
}

/// What to play for `sound` and `options`: a pack sound or a server's, its volume and pitch checked.
pub fn sound(
    platform: &dyn Platform,
    calling: &Calling,
    sound: &str,
    options: Option<&shapes::SoundOptions>,
) -> ApiResult<SoundPlay> {
    let id = sound_id(platform, calling, sound)?;
    let volume = options.and_then(|o| o.volume).unwrap_or(1.0);
    if volume < 0.0 || !volume.is_finite() {
        return Err(err("options.volume can't be below 0"));
    }
    let pitch = options.and_then(|o| o.pitch).unwrap_or(1.0);
    if !(0.5..=2.0).contains(&pitch) {
        return Err(err(format!("options.pitch must be 0.5 to 2, not {pitch}")));
    }
    Ok(SoundPlay {
        sound: id,
        category: options
            .and_then(|o| o.category.clone())
            .unwrap_or_else(|| "master".into()),
        volume,
        pitch,
    })
}
