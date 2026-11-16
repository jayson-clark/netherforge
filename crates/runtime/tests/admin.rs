//! `/nf`, run as a player and from the console (Kotlin's `AdminCommandTest`, and the rest of the subcommands): the
//! centities, reloads and modules, what each script costs, the store, settings, what the project requires, the
//! network's words and `/nf shutdown` asking the server to stop.

mod support;
use support::*;

use std::rc::Rc;

use netherforge_runtime::admin::AdminCommand;
use netherforge_runtime::server::ServerEvents;

const SCRIPTED: &str = r#"{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } }, "script": { "file": "script.lua" } }"#;

/// A server running `files` with `/nf` registered: the project leaked, as the plugin's lives as long as it does.
fn server(files: &[(&str, &str)]) -> (Rc<Fake>, Rc<ServerEvents<'static>>, std::path::PathBuf) {
    let platform = Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let mut all = vec![("netherforge.json", MANIFEST)];
    all.extend_from_slice(files);
    let files: &'static Files = Box::leak(Box::new(Files::new(&all)));
    let session = Session::start(platform.clone(), files, &NoPackages);
    let events = Rc::new(ServerEvents::new(session));
    let state = std::env::temp_dir().join(format!("nf-admin-{}-{}", std::process::id(), {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        NEXT.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
    }));
    AdminCommand::register(&events, state.clone()).expect("registered");
    (platform, events, state)
}

fn tick(events: &ServerEvents, n: usize) {
    use netherforge_platform::PlatformEvents;
    for _ in 0..n {
        events.tick();
    }
}

fn nf(platform: &Fake, line: &str) -> Vec<String> {
    command(platform, Some(alex()), line)
}

#[test]
fn spawn_list_find_tp_and_kill() {
    let (platform, events, _) = server(&[
        ("centities/tower/centity.json", SCRIPTED),
        ("centities/tower/script.lua", ""),
        ("centities/lamp/centity.json", SCRIPTED),
        ("centities/lamp/script.lua", ""),
    ]);
    let spawned = nf(&platform, "nf spawn tower");
    assert!(
        spawned[0].starts_with("<green>Spawned tower"),
        "{spawned:?}"
    );
    command(&platform, Some(alex()), "netherforge spawn lamp");
    assert_eq!(
        nf(&platform, "nf spawn nope"),
        ["<red>no centity \"nope\" in this project"]
    );
    assert_eq!(
        command(&platform, None, "nf spawn tower"),
        ["<red>say which player to spawn it at: /nf spawn tower <player>"]
    );

    let listed = nf(&platform, "nf list");
    assert_eq!(listed[0], "<gold>2 spawned:");
    assert_eq!(listed.len(), 3);
    assert!(listed[1].contains("(2 blocks)"), "{listed:?}");
    assert_eq!(nf(&platform, "nf find lamp").len(), 2);

    let tower = events
        .session()
        .centities()
        .all()
        .find(|i| i.centity() == "tower")
        .expect("a tower")
        .id;
    let short = &netherforge_runtime::events::uuid_text(&tower)[..8];
    *platform.player.borrow_mut() = Some(place(50.0, 64.0, 0.0));
    assert_eq!(
        nf(&platform, &format!("nf tp {short}")),
        [format!("<green>Teleported to tower {short}.")]
    );
    assert_eq!(platform.player.borrow().as_ref().unwrap().x, 0.0);

    assert_eq!(nf(&platform, "nf kill tower"), ["<green>Removed 1."]);
    assert_eq!(nf(&platform, "nf kill all"), ["<green>Removed 1."]);
    assert_eq!(events.session().centities().all().count(), 0);
    assert_eq!(nf(&platform, "nf list"), ["<gray>No centities spawned."]);
}

#[test]
fn reload_reports_each_resource_and_modules_shows_whats_running() {
    let (platform, _events, _) = server(&[
        ("centities/tower/centity.json", SCRIPTED),
        ("centities/tower/script.lua", ""),
        (
            "modules/shop/init.lua",
            "nf.commands.register('shop', function() end)",
        ),
        ("modules/broken/init.lua", "error('no')"),
    ]);
    assert_eq!(
        nf(&platform, "nf reload centities/tower/script.lua"),
        ["<green>centity:tower: reloaded"]
    );
    // A whole reload is the package's: the module that fails says why.
    assert_eq!(
        nf(&platform, "nf reload"),
        ["<red>demo: failed", "<gray>  modules/broken/init.lua:1: no"]
    );
    assert_eq!(
        nf(&platform, "nf reload nothing.txt"),
        ["<gray>Nothing to reload for nothing.txt."]
    );
    assert_eq!(
        nf(&platform, "nf modules"),
        [
            "<red>broken <white>failed",
            "<green>shop <white>running <gray>/shop"
        ]
    );
}

#[test]
fn scripts_lists_what_each_script_costs_and_holds() {
    let (platform, events, _) = server(&[(
        "modules/busy/init.lua",
        r#"
        nf.on("tick", function() end)
        nf.every(1, function() end)
        nf.task(function() while true do nf.wait(5) end end)
        "#,
    )]);
    tick(&events, 5);
    let said = nf(&platform, "nf scripts");
    assert!(
        said[0].starts_with("<gold>1 script, ")
            && said[0].ends_with("(average / max ms a tick over the last 100 ticks)"),
        "{said:?}"
    );
    assert!(said[1].contains("<white>module busy <gray>"), "{said:?}");
    assert!(
        said[1].contains("1 subscription, 1 task"),
        "the handler and the task: {said:?}"
    );
    assert!(said[1].contains("calls"), "{said:?}");
}

#[test]
fn completion_offers_subcommands_centities_and_players() {
    let (platform, _events, _) = server(&[
        ("centities/tower/centity.json", SCRIPTED),
        ("centities/tower/script.lua", ""),
    ]);
    assert_eq!(complete(&platform, "nf sp"), ["spawn"]);
    assert_eq!(complete(&platform, "nf spawn "), ["tower"]);
    assert_eq!(complete(&platform, "nf spawn tower A"), ["Alex"]);
    assert_eq!(complete(&platform, "nf profile "), ["10", "30", "60"]);
    assert_eq!(complete(&platform, "nf data sh"), ["show"]);
    assert_eq!(complete(&platform, "nf data show pl"), ["player"]);
}

#[test]
fn data_shows_what_the_store_keeps_as_it_is_now() {
    let (platform, events, state) = server(&[
        ("centities/c/centity.json", SCRIPTED),
        ("centities/c/script.lua", "this:data().hp = 10"),
        (
            "modules/m/init.lua",
            r#"nf.data("shop").open = true
            nf.commands.register("visit", function(event) event.player:data().visits = 3 end)"#,
        ),
    ]);
    let id = events
        .session()
        .spawn("c", place(1.0, 64.0, 2.0), 0.0)
        .expect("spawned");
    events.session().player_joined(&alex());
    {
        let session = events.session();
        let room = session.room().unwrap();
        room.host().lua().load("").exec().unwrap();
    }
    // `visit` sets Alex's table through a project command, run as the server would.
    let handler = platform.commands.borrow()["visit"].1.clone();
    handler.run(
        Rc::new(TestSender {
            player: Some(alex()),
            allowed: true,
            replies: RefCell::default(),
        }),
        "visit".into(),
        netherforge_platform::CommandInput {
            path: Vec::new(),
            arguments: Default::default(),
            text: String::new(),
        },
    );

    let sizes = nf(&platform, "nf data");
    assert!(sizes[0].starts_with("<gold>netherforge.db"), "{sizes:?}");
    // Saved tables are saved first, so what's shown is now, not the last autosave.
    assert!(
        sizes.contains(&"<yellow>named_data <white>1 <gray>(demo 1)".to_owned()),
        "{sizes:?}"
    );
    assert!(
        sizes.contains(&"<yellow>instances <white>1".to_owned()),
        "{sizes:?}"
    );
    assert!(
        sizes.contains(&"<yellow>player_data <white>1".to_owned()),
        "{sizes:?}"
    );

    let rows = nf(&platform, "nf data instances");
    assert_eq!(rows[0], "<gold>instances <gray>(1 row)");
    let text = netherforge_runtime::events::uuid_text(&id);
    assert!(
        rows[1].contains(&format!("id=<white>{text}")) && rows[1].contains("centity=<white>demo:c"),
        "{rows:?}"
    );

    assert_eq!(
        nf(&platform, "nf data show player Alex"),
        [
            "<gold>player Alex <gray>(12 bytes)",
            "<white>{",
            "<white>    \"visits\": 3",
            "<white>}"
        ]
    );
    assert_eq!(
        nf(&platform, &format!("nf data show centity {}", &text[..8]))[2],
        "<white>    \"hp\": 10"
    );
    assert_eq!(
        nf(&platform, "nf data show named demo:shop")[2],
        "<white>    \"open\": true"
    );
    assert_eq!(
        nf(&platform, "nf data show named nope"),
        ["<gray>Nothing is saved for nf.data(\"nope\") of demo."]
    );
    assert_eq!(
        nf(&platform, "nf data nope"),
        ["<red>there's no table \"nope\" in the store"]
    );

    // Everything, as JSON, into exports/ in the plugin's folder; said when it's written.
    assert_eq!(
        nf(&platform, "nf data export"),
        ["<gold>Exporting the store…"]
    );
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    let written = loop {
        tick(&events, 1);
        let said = platform.take();
        let _ = said;
        let exported = std::fs::read_dir(state.join("exports"))
            .ok()
            .and_then(|mut d| d.next())
            .and_then(|e| e.ok())
            .map(|e| e.path());
        if let Some(file) = exported
            && file.extension().is_some_and(|e| e == "json")
        {
            break file;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the export never came"
        );
        std::thread::sleep(std::time::Duration::from_millis(10));
    };
    let export: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(written).unwrap()).unwrap();
    assert_eq!(
        export["tables"]["named_data"],
        serde_json::json!([{ "namespace": "demo", "name": "shop", "value": { "open": true } }])
    );
    assert_eq!(export["tables"]["instances"][0]["centity"], "demo:c");
    assert_eq!(export["tables"]["instances"][0]["x"], 1);
    assert!(export["tables"].get("migrations").is_some());
}

#[test]
fn settings_are_listed_set_reset_and_shown_as_a_dialog() {
    const WITH_SETTINGS: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3",
      "settings": {
        "greeting": { "type": "string", "description": "What players are told", "default": "hi" },
        "loud": { "type": "boolean", "description": "Shout it", "default": false }
      } }"#;
    let platform = Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files: &'static Files = Box::leak(Box::new(Files::new(&[
        ("netherforge.json", WITH_SETTINGS),
        (
            "modules/m/init.lua",
            "nf.on('setting_changed', function(e) print('changed ' .. e.name) end)",
        ),
    ])));
    let events = Rc::new(ServerEvents::new(Session::start(
        platform.clone(),
        files,
        &NoPackages,
    )));
    AdminCommand::register(&events, std::env::temp_dir()).unwrap();
    assert_eq!(
        command(&platform, None, "nf settings"),
        [
            "<gold>Demo <gray>(demo, settings/demo.json)",
            "<yellow>greeting <white>hi <gray>(default) <dark_gray>What players are told",
            "<yellow>loud <white>false <gray>(default) <dark_gray>Shout it"
        ]
    );
    assert_eq!(
        command(&platform, None, "nf settings set greeting hello there"),
        ["<green>greeting is now hello there."]
    );
    assert_eq!(
        command(&platform, None, "nf settings set greeting hello there"),
        ["<gray>greeting was already hello there."]
    );
    assert_eq!(
        command(&platform, None, "nf settings list")[1],
        "<yellow>greeting <white>hello there <gray>(default hi) <dark_gray>What players are told"
    );
    assert_eq!(
        command(&platform, None, "nf settings set loud maybe"),
        ["<red>loud: \"maybe\" isn't true or false"]
    );
    assert_eq!(
        command(&platform, None, "nf settings reset greeting"),
        ["<green>greeting is back to its default, hi."]
    );
    assert_eq!(
        complete(&platform, "nf settings set "),
        ["greeting", "loud"]
    );
    assert_eq!(
        complete(&platform, "nf settings set loud "),
        ["true", "false"]
    );

    // A player gets the dialog: a form with an input per setting; Save changes what changed at the next tick.
    platform.take();
    assert_eq!(nf(&platform, "nf settings"), Vec::<String>::new());
    let shown = platform.take();
    assert!(
        shown
            .iter()
            .any(|c| c.contains("netherforge:settings/demo")),
        "{shown:?}"
    );
    {
        use netherforge_platform::PlatformEvents;
        events.dialog_pressed(
            alex(),
            "netherforge:settings/demo".into(),
            "save".into(),
            std::collections::HashMap::from([
                (
                    "greeting".to_owned(),
                    netherforge_platform::Dynamic::String("howdy".into()),
                ),
                (
                    "loud".to_owned(),
                    netherforge_platform::Dynamic::String("false".into()),
                ),
            ]),
        );
    }
    tick(&events, 1);
    let told = platform.take();
    assert!(
        told.contains(&"message 77 <green>greeting is now howdy".to_owned()),
        "{told:?}"
    );
    assert!(
        told.iter().any(|c| c.contains("[module m] changed")),
        "the scripts heard it: {told:?}"
    );
}

#[test]
fn requires_says_what_the_tree_needs_and_its_web_routes() {
    const NEEDS: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3",
      "requires": { "http": ["api.example.com"] } }"#;
    let platform = Rc::new(Fake::default());
    let files: &'static Files = Box::leak(Box::new(Files::new(&[("netherforge.json", NEEDS)])));
    let events = Rc::new(ServerEvents::new(Session::start(
        platform.clone(),
        files,
        &NoPackages,
    )));
    AdminCommand::register(&events, std::env::temp_dir()).unwrap();
    assert_eq!(
        command(&platform, None, "nf requires"),
        [
            "<gold>The project and its packages require:",
            "<yellow>http:api.example.com <gray>declared by demo"
        ]
    );
}

#[test]
fn network_drain_and_shutdown_on_a_server_in_no_network() {
    let (platform, events, _) = server(&[]);
    let said = nf(&platform, "nf network");
    assert_eq!(said.len(), 1);
    assert!(
        said[0].starts_with("<gray>This server isn't part of a network: "),
        "{said:?}"
    );
    assert_eq!(
        nf(&platform, "nf drain"),
        ["<gold>Draining: <gray>no new players are sent here. /nf drain off undoes it."]
    );
    assert_eq!(
        nf(&platform, "nf drain off"),
        ["<green>Taking players again."]
    );
    assert_eq!(
        nf(&platform, "nf shutdown --timeout soon"),
        ["<red>--timeout takes a number of seconds: /nf shutdown --timeout 60"]
    );
    assert_eq!(
        nf(&platform, "nf shutdown"),
        ["<gold>Shutting down: <gray>sending everyone to the fallback, then stopping."]
    );
    // Drained, then stopping (nobody to send anywhere), then the server is asked to stop as a tick ends.
    assert_eq!(*platform.shutdown.borrow(), None);
    tick(&events, 3);
    assert_eq!(
        platform.shutdown.borrow().as_deref(),
        Some("NetherForge: /nf shutdown")
    );
}

#[test]
fn schedules_pack_and_an_unknown_word() {
    let (platform, events, _) = server(&[(
        "modules/m/init.lua",
        r#"nf.schedule.daily("09:30", function() end)"#,
    )]);
    events.session().set_wall_clock(Some(0));
    let said = nf(&platform, "nf schedules");
    assert!(
        said[0].starts_with("<gold>1 schedule <gray>(times in "),
        "{said:?}"
    );
    assert!(
        said[1].contains("<white>module m <gray>next <white>"),
        "{said:?}"
    );
    assert_eq!(
        nf(&platform, "nf pack"),
        ["<gray>No resource pack: the project has no resource packs, or they couldn't be built."]
    );
    assert_eq!(
        nf(&platform, "nf what"),
        [
            "<gray>/nf spawn|list|find|kill|tp|reload|modules|scripts|profile|pack|data|settings|requires|schedules|network|drain|shutdown"
        ]
    );
}
