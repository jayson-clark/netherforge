//! What answers the editor's requests on the main thread (Kotlin's `BridgeService`'s handlers, `NetherForgeRuntime`'s
//! `spawnNear`, `playParticleEffect`, `settings`, and `BotsBridge`): one function per method, its failures in the
//! sentence the editor shows. A handler that panics is answered `INTERNAL_ERROR` and logged, as Kotlin's unexpected
//! exceptions are.

use std::cell::Ref;
use std::path::Path;

use netherforge_format::bridge::{
    self, BlockPos, BotAction, InstanceInfo, LoadedWorld, Method, PlayerPosition, SavedStructure,
    SavedWorld, ServerSettings, WorldSpawn,
};
use netherforge_platform::{BlockVector, BotAim, BotJoin, Location, PlayerRef};
use serde::Serialize;
use serde_json::Value;

use super::{Bridge, Failure, Reply};
use crate::events::uuid_text;
use crate::server::ServerEvents;
use crate::session::Session;

/// How far in front of a player's eyes the editor's "Play on server" plays an effect, in blocks.
const EFFECT_DISTANCE: f64 = 3.0;

/// The most a structure is along each side.
const MAX_SIDE: i32 = 48;

type Outcome = Result<Value, Failure>;

fn ok<T: Serialize>(value: T) -> Outcome {
    Ok(serde_json::to_value(value).expect("results serialize"))
}

fn done() -> Outcome {
    Ok(Value::Null)
}

fn params<M: Method>(params: Option<&Value>) -> Result<M::Params, Failure>
where
    M::Params: 'static,
{
    bridge::decode_params::<M>(params).map_err(|e| Failure {
        code: bridge::rpc::INVALID_PARAMS,
        message: format!("Invalid params for {}: {e}", M::NAME),
    })
}

/// Answers `method` with `reply`: now, or (joining a bot, a bot's action) once the platform says it's done.
pub(super) fn answer(
    bridge: &Bridge,
    events: &ServerEvents,
    method: &'static str,
    params: Option<Value>,
    reply: Reply,
) {
    let later = reply.clone();
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        handle(bridge, events, method, params.as_ref(), later)
    }));
    match outcome {
        Ok(Some(outcome)) => reply.answer(outcome),
        Ok(None) => {}
        Err(panic) => {
            let message = panic
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| panic.downcast_ref::<&str>().map(|s| (*s).to_owned()))
                .unwrap_or_else(|| "failed".into());
            bridge
                .inner
                .log()
                .error(format!("Bridge request {method} failed: {message}"), None);
            reply.answer(Err(Failure {
                code: bridge::rpc::INTERNAL_ERROR,
                message,
            }));
        }
    }
}

/// The outcome now, or none when `reply` will be answered later.
fn handle(
    bridge: &Bridge,
    events: &ServerEvents,
    method: &str,
    json: Option<&Value>,
    reply: Reply,
) -> Option<Outcome> {
    let platform = &*bridge.inner;
    Some(match method {
        bridge::Reload::NAME => (|| {
            let paths = params::<bridge::Reload>(json)?.paths;
            let result = {
                let mut session = events
                    .session_mut_now()
                    .ok_or_else(|| Failure::request("the project is busy"))?;
                session.reload(&paths)
            };
            // Scripts that restarted took their problems with them; a whole package read its settings again.
            bridge.send_problems(events, true);
            if result.resources.iter().any(|r| r.kind.is_none()) {
                bridge.send_settings(events);
            }
            ok(result)
        })(),
        bridge::Spawn::NAME => (|| {
            let p = params::<bridge::Spawn>(json)?;
            let player = p.player.as_deref().map(|n| find(platform, n)).transpose()?;
            let session = session(events)?;
            let id = spawn_near(&session, &p.centity, player.as_ref())?;
            let info = info_of(&session, id).ok_or_else(|| Failure::request("it went at once"))?;
            ok(info)
        })(),
        bridge::Instances::NAME => (|| -> Outcome { ok(instances(&*session(events)?)) })(),
        bridge::ExportGameData::NAME => ok(platform.export_game_data()),
        bridge::Command::NAME => (|| {
            let line = params::<bridge::Command>(json)?.line;
            let run = line.strip_prefix('/').unwrap_or(&line).to_owned();
            if platform.commands().run_console(run) {
                done()
            } else {
                Err(Failure::request(format!(
                    "the server didn't run \"{line}\""
                )))
            }
        })(),
        bridge::PlayParticleEffect::NAME => (|| {
            let p = params::<bridge::PlayParticleEffect>(json)?;
            let player = p.player.as_deref().map(|n| find(platform, n)).transpose()?;
            play_particle_effect(&*session(events)?, &p.effect, player.as_ref(), p.looped)?;
            done()
        })(),
        bridge::StopParticleEffects::NAME => (|| {
            let session = session(events)?;
            if let Some(room) = session.room() {
                crate::particles::ParticlesContext {
                    shared: session.shared().clone(),
                    scripts: room.scripts().clone(),
                }
                .stop_unowned();
            }
            done()
        })(),
        bridge::PlayerPositionOf::NAME => (|| {
            let p = params::<bridge::PlayerPositionOf>(json)?;
            ok(player_position(platform, p.player.as_deref())?)
        })(),
        bridge::Worlds::NAME => {
            let worlds = platform.worlds();
            let main = worlds.default_world();
            let mut names = worlds.names();
            names.sort_by_key(|n| *n != main);
            ok(names
                .into_iter()
                .map(|name| LoadedWorld {
                    environment: worlds
                        .environment(name.clone())
                        .unwrap_or_else(|| "normal".into()),
                    main: name == main,
                    name,
                })
                .collect::<Vec<_>>())
        }
        bridge::SaveStructure::NAME => (|| {
            let p = params::<bridge::SaveStructure>(json)?;
            ok(save_structure(bridge, events, &p)?)
        })(),
        bridge::SaveWorld::NAME => (|| {
            let world = params::<bridge::SaveWorld>(json)?.world;
            ok(save_world(bridge, &world)?)
        })(),
        bridge::Settings::NAME => (|| -> Outcome { ok(settings(bridge, &*session(events)?)) })(),
        bridge::SetSetting::NAME => (|| {
            let p = params::<bridge::SetSetting>(json)?;
            {
                let mut session = events
                    .session_mut_now()
                    .ok_or_else(|| Failure::request("the project is busy"))?;
                session
                    .set_setting(&p.namespace, &p.setting, p.value.as_ref().map(|v| &v.0))
                    .map_err(Failure::request)?;
            }
            bridge.send_settings(events);
            ok(settings(bridge, &*session(events)?))
        })(),
        // ---- the bots extension ----
        bridge::BotsJoin::NAME => {
            let p = match params::<bridge::BotsJoin>(json) {
                Ok(p) => p,
                Err(failure) => return Some(Err(failure)),
            };
            let join = BotJoin {
                name: p.name,
                at: p.at,
                pack: p.resource_pack,
                script: None,
            };
            // Joining finishes ticks later; the answer waits for it.
            platform.bots().join(
                join,
                Box::new(move |outcome| {
                    reply.answer(match outcome {
                        Ok(info) => ok(info),
                        Err(e) => Err(Failure::request(e.message)),
                    })
                }),
            );
            return None;
        }
        bridge::BotsLeave::NAME => (|| {
            let name = params::<bridge::BotsLeave>(json)?.name;
            platform.bots().leave(name);
            done()
        })(),
        bridge::BotsList::NAME => ok(platform.bots().list()),
        bridge::BotsAct::NAME => {
            let acted = (|| {
                let p = params::<bridge::BotsAct>(json)?;
                let name = bot(platform, &p.name)?;
                let aim = aim_of(events, &p.action)?;
                Ok((name, p.action, aim))
            })();
            let (name, action, aim) = match acted {
                Ok(acted) => acted,
                Err(failure) => return Some(Err(failure)),
            };
            platform.bots().act(
                name,
                action,
                aim,
                Box::new(move |outcome| {
                    reply.answer(match outcome {
                        Ok(result) => ok(result),
                        Err(e) => Err(Failure::request(e.message)),
                    })
                }),
            );
            return None;
        }
        bridge::BotsState::NAME => (|| {
            let name = bot(platform, &params::<bridge::BotsState>(json)?.name)?;
            let state = platform.bots().state(name);
            // Which centity and node it looks at is the runtime's to say, from the line of sight a click is picked by.
            let state = match events.try_session().ok().and_then(|s| s.bots()) {
                Some(bots) => bots.bridge_state(state),
                None => state,
            };
            ok(state)
        })(),
        bridge::BotsEvents::NAME => (|| {
            let p = params::<bridge::BotsEvents>(json)?;
            let name = bot(platform, &p.name)?;
            ok(platform.bots().events(name, p.since))
        })(),
        other => Err(Failure {
            code: bridge::rpc::METHOD_NOT_FOUND,
            message: format!("Unknown method \"{other}\""),
        }),
    })
}

/// The session to read, refusing while it's being changed.
fn session<'e, 'a>(events: &'e ServerEvents<'a>) -> Result<Ref<'e, Session<'a>>, Failure> {
    events
        .try_session()
        .map_err(|_| Failure::request("the project is busy"))
}

/// The player `name` (a name or a UUID), online.
fn find(platform: &dyn netherforge_platform::Platform, name: &str) -> Result<PlayerRef, Failure> {
    platform
        .players()
        .find(name.to_owned())
        .ok_or_else(|| Failure::request(format!("no player \"{name}\" online")))
}

/// The bot `name`, as the platform names it.
fn bot(platform: &dyn netherforge_platform::Platform, name: &str) -> Result<String, Failure> {
    platform
        .bots()
        .list()
        .into_iter()
        .find(|b| b.name.eq_ignore_ascii_case(name))
        .map(|b| b.name)
        .ok_or_else(|| Failure::request(format!("no bot named \"{name}\"")))
}

/// Why nothing can be done with a project that doesn't run: its first problem.
fn not_running(session: &Session) -> Failure {
    let first = session
        .problems()
        .into_iter()
        .next()
        .map(|p| format!(": {}", p.message))
        .unwrap_or_default();
    Failure::request(format!("the project isn't running{first}"))
}

/// Two blocks ahead of where `location` faces, on the block grid.
fn in_front(location: &Location) -> Location {
    let yaw = location.yaw.to_radians();
    Location {
        world: location.world.clone(),
        x: (location.x - yaw.sin() * 2.0).floor(),
        y: location.y.floor(),
        z: (location.z + yaw.cos() * 2.0).floor(),
        yaw: 0.0,
        pitch: 0.0,
    }
}

/// Spawns `centity` in front of `player` (or the first player online, or at the main world's spawn).
fn spawn_near(
    session: &Session,
    centity: &str,
    player: Option<&PlayerRef>,
) -> Result<netherforge_platform::Uuid, Failure> {
    if !session.running() {
        return Err(not_running(session));
    }
    if session.centities().definition(centity).is_none() {
        return Err(Failure::request(format!(
            "no centity \"{centity}\" in this project"
        )));
    }
    let platform = session.platform();
    let who = player
        .cloned()
        .or_else(|| platform.players().online().into_iter().next());
    let at = who
        .and_then(|p| platform.players().location(p.uuid))
        .map(|l| in_front(&l))
        .or_else(|| {
            platform
                .worlds()
                .spawn_location(platform.worlds().default_world())
        })
        .ok_or_else(|| Failure::request("there's no world to spawn in"))?;
    session.spawn(centity, at.clone(), 0.0).ok_or_else(|| {
        Failure::request(format!(
            "couldn't spawn {centity} at {} {} {} {}",
            at.world, at.x, at.y, at.z
        ))
    })
}

fn info_of(session: &Session, id: netherforge_platform::Uuid) -> Option<InstanceInfo> {
    let centities = session.centities();
    let instance = centities.get(id)?;
    let at = instance.anchor();
    Some(InstanceInfo {
        uuid: uuid_text(&id),
        centity: instance.declared.id.clone(),
        world: at.world.clone(),
        x: at.x,
        y: at.y,
        z: at.z,
    })
}

/// Every instance, live or inert (whose centity is missing).
fn instances(session: &Session) -> Vec<InstanceInfo> {
    let centities = session.centities();
    let live = centities.all().map(|i| {
        let at = i.anchor();
        InstanceInfo {
            uuid: uuid_text(&i.id),
            centity: i.declared.id.clone(),
            world: at.world.clone(),
            x: at.x,
            y: at.y,
            z: at.z,
        }
    });
    let inert = centities.inert().map(|r| InstanceInfo {
        uuid: uuid_text(&r.id),
        centity: r.centity.clone(),
        world: r.anchor.world.clone(),
        x: r.anchor.x,
        y: r.anchor.y,
        z: r.anchor.z,
    });
    live.chain(inert).collect()
}

/// Plays `effect` for the editor: three blocks in front of `player`'s eyes (or the first player online's), facing
/// them; else at the main world's spawn. It's the session's, not a script's.
fn play_particle_effect(
    session: &Session,
    effect: &str,
    player: Option<&PlayerRef>,
    looped: Option<bool>,
) -> Result<(), Failure> {
    if !session.running() {
        return Err(not_running(session));
    }
    let platform = session.platform();
    let who = player
        .cloned()
        .or_else(|| platform.players().online().into_iter().next());
    let at = who
        .as_ref()
        .and_then(|p| platform.players().eye(p.uuid).map(|eye| (p, eye)))
        .map(|(p, eye)| {
            let length = (eye.dx * eye.dx + eye.dy * eye.dy + eye.dz * eye.dz).sqrt();
            let length = if length > 0.0 { length } else { 1.0 };
            let facing = platform.players().location(p.uuid);
            Location {
                world: eye.world.clone(),
                x: eye.x + eye.dx / length * EFFECT_DISTANCE,
                y: eye.y + eye.dy / length * EFFECT_DISTANCE,
                z: eye.z + eye.dz / length * EFFECT_DISTANCE,
                // Facing them: turned around from where they look.
                yaw: facing.map_or(0.0, |f| f.yaw) + 180.0,
                pitch: 0.0,
            }
        })
        .or_else(|| {
            platform
                .worlds()
                .spawn_location(platform.worlds().default_world())
        })
        .ok_or_else(|| Failure::request("there's no world to play it in"))?;
    let played = session
        .shared()
        .particles
        .borrow_mut()
        .play(
            platform,
            effect,
            &at,
            None,
            crate::particles::PlayOptions {
                viewers: None,
                scale: None,
                looped,
                follow: None,
                offset: None,
            },
        )
        .map_err(|e| Failure::request(e.to_string()))?;
    if played.is_none() {
        return Err(Failure::request(format!(
            "the world at {} {} {} {} isn't loaded",
            at.world, at.x, at.y, at.z
        )));
    }
    platform
        .log()
        .info(format!("Playing particle effect {effect}"));
    Ok(())
}

/// Where `name` (or the first player online) stands, to the block.
fn player_position(
    platform: &dyn netherforge_platform::Platform,
    name: Option<&str>,
) -> Result<PlayerPosition, Failure> {
    let player = match name {
        Some(name) => find(platform, name)?,
        None => platform
            .players()
            .online()
            .into_iter()
            .next()
            .ok_or_else(|| Failure::request("nobody is online"))?,
    };
    let at = platform
        .players()
        .location(player.uuid)
        .ok_or_else(|| Failure::request(format!("{} isn't in a world", player.name)))?;
    Ok(PlayerPosition {
        player: player.name,
        world: at.world,
        x: at.x.floor() as i32,
        y: at.y.floor() as i32,
        z: at.z.floor() as i32,
    })
}

/// The box's structure file as bytes: saved into the data folder, read and deleted, so nothing is left there or in
/// the project (the editor writes it into the project itself).
fn save_structure(
    bridge: &Bridge,
    events: &ServerEvents,
    p: &bridge::SaveStructureParams,
) -> Result<SavedStructure, Failure> {
    let platform = &*bridge.inner;
    let sides = [
        (p.from.x - p.to.x).abs() + 1,
        (p.from.y - p.to.y).abs() + 1,
        (p.from.z - p.to.z).abs() + 1,
    ];
    if sides.iter().any(|s| *s > MAX_SIDE) {
        return Err(Failure::request(format!(
            "that box is {}×{}×{} blocks; a structure is at most {MAX_SIDE} along each side",
            sides[0], sides[1], sides[2]
        )));
    }
    if !platform.worlds().exists(p.world.clone()) {
        return Err(Failure::request(format!(
            "no world \"{}\" is loaded",
            p.world
        )));
    }
    let folder = events
        .try_session()
        .ok()
        .and_then(|s| s.shared().structures.borrow().saved.clone())
        .and_then(|saved| saved.parent().map(|p| p.join("captures")))
        .unwrap_or_else(|| std::env::temp_dir().join("netherforge-captures"));
    std::fs::create_dir_all(&folder).map_err(|e| Failure::request(e.to_string()))?;
    let mut name = [0u8; 8];
    getrandom::fill(&mut name).expect("the system has randomness");
    let file = folder.join(format!(
        "{}.nbt",
        name.iter().map(|b| format!("{b:02x}")).collect::<String>()
    ));
    let min = BlockVector {
        x: p.from.x.min(p.to.x),
        y: p.from.y.min(p.to.y),
        z: p.from.z.min(p.to.z),
    };
    let max = BlockVector {
        x: p.from.x.max(p.to.x),
        y: p.from.y.max(p.to.y),
        z: p.from.z.max(p.to.z),
    };
    let ops = platform.structures();
    let saved = ops.save(file.clone(), p.world.clone(), min, max, p.entities);
    let bytes = std::fs::read(&file);
    let _ = std::fs::remove_file(&file);
    ops.forget(file);
    let bytes = match (saved, bytes) {
        (true, Ok(bytes)) => bytes,
        _ => return Err(Failure::request("the server couldn't save that box")),
    };
    use base64::Engine;
    Ok(SavedStructure {
        nbt: base64::engine::general_purpose::STANDARD.encode(bytes),
        size: BlockPos {
            x: sides[0],
            y: sides[1],
            z: sides[2],
        },
    })
}

/// `path` relative to the server's folder, with `/`; refused outside it, since the editor only copies from there.
fn relative(bridge: &Bridge, path: &Path) -> Result<String, Failure> {
    let root = absolute(&bridge.config.server_directory);
    let path = absolute(path);
    match path.strip_prefix(&root) {
        Ok(rest) if !rest.as_os_str().is_empty() => Ok(rest
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect::<Vec<_>>()
            .join("/")),
        _ => Err(Failure::request(format!(
            "{} isn't in the server's folder ({})",
            path.display(),
            root.display()
        ))),
    }
}

/// `path` made absolute and without `.` or `..`, as far as the text says (nothing is resolved on disk).
fn absolute(path: &Path) -> std::path::PathBuf {
    let path = if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut out = std::path::PathBuf::new();
    for part in path.components() {
        match part {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                out.pop();
            }
            other => out.push(other),
        }
    }
    out
}

/// Saves `world` to disk and answers where its files are, relative to the server's folder.
fn save_world(bridge: &Bridge, world: &str) -> Result<SavedWorld, Failure> {
    let platform = &*bridge.inner;
    let files = platform
        .world_manager()
        .save_files(world.to_owned())
        .ok_or_else(|| Failure::request(format!("no world \"{world}\" is loaded")))?;
    let spawn = platform
        .worlds()
        .spawn_location(world.to_owned())
        .ok_or_else(|| Failure::request(format!("world \"{world}\" has no spawn")))?;
    Ok(SavedWorld {
        level: relative(bridge, &files.level)?,
        dimension: relative(bridge, &files.dimension)?,
        main: world == platform.worlds().default_world(),
        spawn: WorldSpawn {
            x: spawn.x.floor() as i32,
            y: spawn.y.floor() as i32,
            z: spawn.z.floor() as i32,
            yaw: spawn.yaw,
            pitch: spawn.pitch,
        },
    })
}

/// Every package's server-owner settings as the editor shows them, each file relative to the server's folder.
pub(super) fn settings(bridge: &Bridge, session: &Session) -> ServerSettings {
    session
        .shared()
        .settings
        .borrow()
        .state(&|file| relative(bridge, file).unwrap_or_else(|_| file.display().to_string()))
}

/// Where an interact or attack naming a centity's node aims: only the runtime knows its hitbox.
fn aim_of(events: &ServerEvents, action: &BotAction) -> Result<Option<BotAim>, Failure> {
    let names_one = matches!(
        action,
        BotAction::Interact {
            centity: Some(_),
            ..
        } | BotAction::Attack {
            centity: Some(_),
            ..
        }
    );
    let bots = events.try_session().ok().and_then(|s| s.bots());
    match bots {
        Some(bots) => bots.bridge_aim(action).map_err(Failure::request),
        None if names_one => Err(Failure::request("the project isn't running")),
        None => Ok(None),
    }
}
