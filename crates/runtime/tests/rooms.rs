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
nf.on("block_place", function(e)
  print("placed in world")
end)
nf.on("world_load", function(e)
  print("loaded", e.world:name())
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
    let room = server.session_mut().open_room(None, false).expect("a room");
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
    let room = server.session_mut().open_room(None, false).expect("a room");
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
    // A world loading is heard by its room alone, though it's heard on `nf`, not on the world.
    server.session().give_world("arena", room);
    server.game().world_load(&mut game_event::World {
        world: "arena".into(),
    });
    server.tick();
    let lines = platform.take();
    let loaded: Vec<&String> = lines.iter().filter(|l| l.contains("loaded")).collect();
    assert_eq!(loaded.len(), 1, "{lines:?}");
    assert!(
        loaded[0].starts_with("info [room 1 module t] loaded"),
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
    let room = server.session_mut().open_room(None, false).expect("a room");
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
    let room = server.session_mut().open_room(None, false).expect("a room");
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

#[test]
fn a_players_data_goes_with_them_and_a_rooms_named_data_is_its_own() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local mine = nf.data("scores")
mine.opened = (mine.opened or 0) + 1
nf.on("player_join", function(e)
  local data = e.player:data()
  data.visits = (data.visits or 0) + 1
  print("visits", data.visits, "opened", mine.opened)
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    server.session().move_player(alex().uuid, room);
    server.session().move_player(alex().uuid, RoomId::MAIN);
    server.tick();
    // The main room's named table and room 1's are two tables; Alex's is one, read again in each room.
    assert_eq!(
        printed(&platform.take()),
        [
            "visits\t1\topened\t1",
            "visits\t3\topened\t1",
            "visits\t2\topened\t1"
        ]
    );
}

#[test]
fn a_command_runs_in_its_senders_room_and_the_server_has_it_once() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local runs = 0
nf.commands.register("score", function(event)
  runs += 1
  print("score", runs)
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    assert_eq!(platform.commands.borrow().len(), 1);
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    command(&platform, Some(alex()), "score");
    server.session().move_player(alex().uuid, room);
    command(&platform, Some(alex()), "score");
    command(&platform, Some(alex()), "score");
    // The console's goes to the main room.
    command(&platform, None, "score");
    server.tick();
    let lines = platform.take();
    let scores: Vec<&String> = lines.iter().filter(|l| l.contains("] score")).collect();
    assert_eq!(scores.len(), 4, "{lines:?}");
    assert!(
        scores[0].starts_with("info [module t] score\t1"),
        "{lines:?}"
    );
    assert!(
        scores[1].starts_with("info [module t] score\t2"),
        "{lines:?}"
    );
    assert!(
        scores[2].starts_with("info [room 1 module t] score\t1"),
        "{lines:?}"
    );
    assert!(
        scores[3].starts_with("info [room 1 module t] score\t2"),
        "{lines:?}"
    );
    // The server keeps /score until the last room that has it goes.
    server.session_mut().close_room(room);
    assert_eq!(platform.commands.borrow().len(), 1);
    server.session_mut().stop();
    assert_eq!(platform.commands.borrow().len(), 0);
}

#[test]
fn a_rooms_players_see_only_each_other_in_the_list_and_in_chat() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[("netherforge.json", MANIFEST), ("modules/t/init.lua", "")]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let sam = FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    };
    platform.bots.online.borrow_mut().push(FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    });
    server.game().player_join(&mut alex_join());
    platform.take();
    // Sam goes to room 1: Alex and Sam leave each other's lists.
    server.session().move_player(sam.id, room);
    let listed: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("listed"))
        .collect();
    assert_eq!(listed, ["listed 77 for 88 false", "listed 88 for 77 false"]);
    // What Alex says, only the main room's players see.
    let mut chat = game_event::PlayerChat {
        player: alex(),
        message: "hi".into(),
        format: "<player>: <message>".into(),
        recipients: vec![alex(), sam.player()],
    };
    assert!(!server.game().player_chat(&mut chat));
    assert_eq!(chat.recipients, [alex()]);
    // Back together.
    server.session().move_player(sam.id, RoomId::MAIN);
    let listed: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("listed"))
        .collect();
    assert_eq!(listed, ["listed 77 for 88 true", "listed 88 for 77 true"]);
}

#[test]
fn a_lobby_opens_a_room_sends_its_player_and_the_rooms_life_is_heard() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local here = nf.rooms.current()
local saved = nf.data("saved")
saved.n = (saved.n or 0) + 1
print("room", here:id(), here:is_main(), here:key(), saved.n)
nf.on("room_start", function(e)
  print("start", e.room:id(), e.room:state())
  here:finish()
  here:close()
end)
nf.on("room_finish", function(e) print("finish", e.room:id()) end)
nf.on("room_close", function(e) print("close", e.room:id(), #nf.players.online()) end)
local opened = false
nf.on("player_join", function(e)
  if here:is_main() and not opened then
    opened = true
    local match = nf.rooms.open({ key = "match-1" })
    print("opening", match:id(), match:state(), match:send(e.player))
    match:start()
  elseif not here:is_main() then
    print("arrived", e.player:name(), #here:players())
  end
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    // The room opens, Alex goes there, its match starts, ends and the room closes: Alex back in the main room.
    for _ in 0..4 {
        server.tick();
    }
    let lines = platform.take();
    let said: Vec<String> = lines
        .iter()
        .filter_map(|l| l.strip_prefix("info ["))
        .map(|l| l.rsplit_once(" (").map_or(l, |(t, _)| t).replace('\t', " "))
        .collect();
    assert_eq!(
        said,
        [
            "module t] room 0 true nil 1",
            "module t] opening 1 opening true",
            "room match-1 module t] room 1 false match-1 1",
            "room match-1 module t] arrived Alex 1",
            "room match-1 module t] start 1 started",
            "room match-1 module t] finish 1",
            "room match-1 module t] close 1 1",
        ],
        "{lines:?}"
    );
    // The match has closed: Alex is back in the main room, which is the only one.
    assert_eq!(server.session().rooms().len(), 1);
    assert_eq!(
        server
            .session()
            .route(netherforge_runtime::events::Route::Player(alex().uuid)),
        RoomId::MAIN
    );
}

#[test]
fn each_rooms_share_is_a_metric() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[("netherforge.json", MANIFEST), ("modules/t/init.lua", "")]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    let room = server
        .session_mut()
        .open_room(Some("match-1".into()), false)
        .expect("a room");
    server.session().move_player(alex().uuid, room);
    server.session().give_world("world", room);
    server.tick();
    let text =
        netherforge_runtime::metrics::ServerMetrics::rooms_text(&server.session(), &*platform);
    assert!(
        text.contains("netherforge_room_players{room=\"0\",key=\"\"} 0"),
        "{text}"
    );
    assert!(
        text.contains("netherforge_room_players{room=\"1\",key=\"match-1\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("netherforge_room_chunks{room=\"1\",key=\"match-1\"}"),
        "{text}"
    );
    assert!(
        text.contains("netherforge_room_tick_milliseconds{room=\"1\",key=\"match-1\"}"),
        "{text}"
    );
    assert!(
        text.contains("netherforge_rooms{state=\"open\"} 2"),
        "{text}"
    );
}

#[test]
fn another_rooms_player_is_as_good_as_offline() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
nf.on("player_join", function(e)
  if nf.rooms.current():is_main() and e.player:name() == "Alex" then
    local sam = nf.players.get("Sam")
    print("sam", sam ~= nil, sam:send_message("hi"), sam:location() == nil)
  end
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    let sam = FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    };
    platform.bots.online.borrow_mut().push(FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    });
    server.session().move_player(sam.id, room);
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    platform.take();
    // The main room finds Sam by name, but can't message or place them: they're in room 1.
    server.game().player_join(&mut alex_join());
    server.tick();
    let lines = platform.take();
    assert!(
        !lines.iter().any(|l| l.starts_with("message 88")),
        "{lines:?}"
    );
    assert_eq!(printed(&lines), ["sam\ttrue\tfalse\ttrue"], "{lines:?}");
}

#[test]
fn a_player_moved_on_stops_seeing_the_rooms_bars_and_sees_them_again_back() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local bar = nf.bossbars.create({ text = "main" })
nf.on("player_join", function(e)
  if nf.rooms.current():is_main() then bar:show_to(e.player) end
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    let bars = |lines: Vec<String>| -> Vec<String> {
        lines
            .into_iter()
            .filter(|l| l.starts_with("bar ") && !l.contains(" made "))
            .collect()
    };
    assert!(
        bars(platform.take())
            .iter()
            .any(|l| l.ends_with("shown to 77"))
    );
    // Gone to room 1: the main room's bar leaves their screen, as if they'd left the server.
    server.session().move_player(alex().uuid, room);
    let moved = bars(platform.take());
    assert_eq!(moved.len(), 1, "{moved:?}");
    assert!(moved[0].ends_with("hidden from 77"), "{moved:?}");
    // Back: still its viewer, they see it again.
    server.session().move_player(alex().uuid, RoomId::MAIN);
    assert!(
        bars(platform.take())
            .iter()
            .any(|l| l.ends_with("shown to 77"))
    );
}

#[test]
fn a_module_saved_reloads_in_every_room() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/t/init.lua", r#"print("v1")"#),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    server.session_mut().open_room(None, false).expect("a room");
    platform.take();
    files.write("modules/t/init.lua", r#"print("v2")"#);
    server
        .session_mut()
        .reload(&["modules/t/init.lua".to_owned()]);
    let lines: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("info [") && l.contains("v2"))
        .map(|l| {
            l.split_once(']')
                .map_or(l.clone(), |(label, _)| label.to_owned())
        })
        .collect();
    assert_eq!(lines, ["info [module t", "info [room 1 module t"]);
}

#[test]
fn only_a_kept_room_keeps_its_data_under_its_key() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/t/init.lua",
            r#"
local here = nf.rooms.current()
local saved = nf.data("saved")
saved.n = (saved.n or 0) + 1
if here:is_main() then
  local function try(options)
    local ok, err = pcall(nf.rooms.open, options)
    print("open", ok, ok and "" or (tostring(err):gsub("^[^:]+:%d+: ", "")))
  end
  try({ keep = true })
  try({ key = "" })
  local owner = nf.rooms.open({ key = "owner-1", keep = true })
  try({ key = "owner-1" })
  local match = nf.rooms.open({ key = "m-1" })
  print("kept", here:is_kept(), owner:is_kept(), match:is_kept())
  nf.after(1, function()
    owner:close()
    match:close()
    nf.after(1, function()
      nf.rooms.open({ key = "owner-1", keep = true })
      nf.rooms.open({ key = "m-1" })
    end)
  end)
else
  print(here:key(), saved.n)
end
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    for _ in 0..8 {
        server.tick();
    }
    let said: Vec<String> = printed(&platform.take())
        .into_iter()
        .map(|l| l.replace('\t', " "))
        .collect();
    assert_eq!(
        said,
        [
            "open false bad argument 'options' (a kept room needs a key)",
            "open false bad argument 'options' (a room's key can't be empty)",
            "open false bad argument 'options' (a room keyed \"owner-1\" is open already)",
            "kept false true false",
            "owner-1 1",
            "m-1 1",
            // Opened again: the kept room finds its table, the match's room starts afresh.
            "owner-1 2",
            "m-1 1",
        ]
    );
}

#[test]
fn as_the_server_stops_a_rooms_players_arent_moved_to_the_main_room() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("modules/t/init.lua", COUNTER),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    server.game().player_join(&mut alex_join());
    let room = server.session_mut().open_room(None, false).expect("a room");
    server.session().move_player(alex().uuid, room);
    server.tick();
    platform.take();
    server.session_mut().stop();
    let said = printed(&platform.take());
    assert!(
        said.iter()
            .all(|l| !l.starts_with("join") && !l.starts_with("quit")),
        "{said:?}"
    );
    assert_eq!(server.session().rooms().len(), 1);
}

#[test]
fn join_and_leave_messages_are_the_rooms() {
    // Found by the rooms load test (k3d, owner mode): an owner in one room saw "Owner2 joined the game" from another.
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[("netherforge.json", MANIFEST), ("modules/t/init.lua", "")]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let room = server.session_mut().open_room(None, false).expect("a room");
    let sam = FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    };
    platform.bots.online.borrow_mut().push(FakeBot {
        name: "Sam".into(),
        id: Uuid(88),
        at: place(0.0, 64.0, 0.0),
    });
    server.session().move_player(sam.id, room);
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    platform.take();
    let mut join = game_event::PlayerJoin {
        message: Some("<yellow>Alex joined the game".into()),
        ..alex_join()
    };
    server.game().player_join(&mut join);
    assert_eq!(join.message, None, "not broadcast to the server");
    let messages: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("message"))
        .collect();
    assert_eq!(messages, ["message 77 <yellow>Alex joined the game"]);
    let mut quit = game_event::PlayerQuit {
        player: sam.player(),
        message: Some("<yellow>Sam left the game".into()),
    };
    server.game().player_quit(&mut quit);
    assert_eq!(quit.message, None);
    assert!(
        !platform.take().iter().any(|l| l.starts_with("message")),
        "nobody else is in Sam's room"
    );
}
