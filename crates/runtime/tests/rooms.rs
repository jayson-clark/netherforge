//! Several rooms of one project in one session (ROADMAP.md phase 2): each its own Lua state, its own players and
//! worlds, every event going to one room, and what one room does never reaching another's scripts.

mod support;

use netherforge_platform::{PlatformEvents, game_event};
use netherforge_runtime::instance::RoomId;
use netherforge_runtime::server::ServerEvents;
use support::*;

/// What scripts printed, in order, with the room's label: `info [module t] line` without the location.
fn printed(calls: &[String]) -> Vec<String> {
    calls
        .iter()
        .filter_map(|c| c.strip_prefix("info ["))
        .map(|c| {
            let (_, rest) = c.split_once("] ").expect("a label");
            rest.rsplit_once(" (")
                .map_or(rest, |(line, _)| line)
                .to_owned()
        })
        .collect()
}

const COUNTER: &str = r#"
local count = 0
print("started", nf.room_id and nf.room_id() or "?")
nf.on("player_join", function(e)
  count += 1
  print("join", e.player:name(), count, #nf.players.online())
end)
nf.on("player_quit", function(e)
  print("quit", e.player:name(), #nf.players.online())
end)
nf.worlds.get("world"):on("block_place", function(e)
  print("placed in world")
end)
"#;

fn alex_join() -> game_event::PlayerJoin {
    game_event::PlayerJoin {
        player: alex(),
        first_join: false,
        message: None,
    }
}

fn placed(world: &str) -> game_event::BlockPlace {
    let block = netherforge_platform::BlockRef {
        world: world.into(),
        x: 1,
        y: 64,
        z: 1,
        id: "minecraft:stone".into(),
        state: "minecraft:stone".into(),
    };
    game_event::BlockPlace {
        player: alex(),
        block: block.clone(),
        state: "minecraft:stone".into(),
        against: block,
    }
}

#[test]
fn each_room_runs_the_project_in_a_lua_state_of_its_own() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/t/init.lua", COUNTER),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room().expect("a room");
    assert_eq!(room, RoomId(1));
    assert_eq!(printed(&platform.take()), ["started\t?", "started\t?"]);
    // Alex arrives in the main room, then moves: each room counts for itself, and sees only its own players.
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    server.session().move_player(alex().uuid, room);
    server.session().move_player(alex().uuid, RoomId::MAIN);
    server.tick();
    // Each room's lines are logged together, the main room's first.
    assert_eq!(
        printed(&platform.take()),
        [
            "join\tAlex\t1\t1",
            "quit\tAlex\t1",
            "join\tAlex\t2\t1",
            "join\tAlex\t1\t1",
            "quit\tAlex\t1",
        ]
    );
}

#[test]
fn a_rooms_world_hears_its_events_and_no_other_room_does() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/t/init.lua", COUNTER),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room().expect("a room");
    platform.take();
    server.game().block_place(&mut placed("world"));
    server.tick();
    let lines = platform.take();
    assert!(
        lines
            .iter()
            .any(|l| l.starts_with("info [module t] placed in world")),
        "{lines:?}"
    );
    server.session().give_world("world", room);
    server.game().block_place(&mut placed("world"));
    server.tick();
    let lines = platform.take();
    let placed: Vec<&String> = lines
        .iter()
        .filter(|l| l.contains("placed in world"))
        .collect();
    assert_eq!(placed.len(), 1, "{lines:?}");
    assert!(
        placed[0].starts_with("info [room 1 module t] placed in world"),
        "{lines:?}"
    );
}

#[test]
fn a_rooms_failure_is_its_own() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local ticks = 0
nf.every(1, function()
  ticks += 1
  if #nf.players.online() > 0 then error("broken here") end
  if ticks == 2 then print("ticked twice") end
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room().expect("a room");
    // Only room 1 has a player: its timer fails, the main room's carries on.
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.session().move_player(alex().uuid, room);
    platform.take();
    server.tick();
    server.tick();
    let lines = platform.take();
    assert_eq!(printed(&lines), ["ticked twice"], "{lines:?}");
    assert!(lines.iter().any(|l| l.contains("broken here")), "{lines:?}");
}

#[test]
fn a_closed_room_sends_its_players_back_and_its_lua_state_goes() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/t/init.lua", COUNTER),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room().expect("a room");
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    server.session().move_player(alex().uuid, room);
    server.session().give_world("world", room);
    server.tick();
    platform.take();
    server.session_mut().close_room(room);
    server.tick();
    assert_eq!(
        printed(&platform.take()),
        ["quit\tAlex\t1", "join\tAlex\t2\t1"]
    );
    assert!(server.session().run(room).is_none());
    // Its world is the main room's again.
    server.game().block_place(&mut placed("world"));
    server.tick();
    assert_eq!(printed(&platform.take()), ["placed in world"]);
}
