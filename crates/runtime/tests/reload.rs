//! What a save reloads, as the editor is told (Kotlin's `ReloadTest`): each resource's `{ package, kind, id }`,
//! whether it took the new version, what kept running on it, its problems, a script's failure at its line, the whole
//! package, packages' own files, and whether the server must restart.

mod support;
use support::*;

use netherforge_format::bridge::ReloadedResource;

const SCRIPTED: &str = r#"{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } }, "script": { "file": "script.lua" } }"#;

/// What scripts printed, in order.
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

fn paths(paths: &[&str]) -> Vec<String> {
    paths.iter().map(|p| p.to_string()).collect()
}

fn labels(resources: &[ReloadedResource]) -> Vec<String> {
    resources.iter().map(ReloadedResource::label).collect()
}

fn scripted(script: &str) -> Files {
    Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/c/centity.json", SCRIPTED),
        ("centities/c/script.lua", script),
    ])
}

#[test]
fn saving_a_centitys_script_reattaches_live_instances_to_it() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = scripted("print('v1 load')\nnf.on('unload', function() print('v1 unload') end)");
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session
        .spawn("c", place(0.0, 64.0, 0.0), 0.0)
        .expect("spawned");
    session.tick();
    platform.take();
    files.write("centities/c/script.lua", "print('v2 load')");
    let result = session.reload(&paths(&["centities/c/script.lua"]));
    assert_eq!(
        result.resources,
        [ReloadedResource {
            pkg: "demo".into(),
            kind: Some("centity".into()),
            id: Some("c".into()),
            ok: true,
            reattached: 1,
            problems: Vec::new(),
            pack: None,
        }]
    );
    assert!(!result.restart);
    assert_eq!(printed(&platform.take()), ["v1 unload", "v2 load"]);
}

#[test]
fn several_saves_to_one_centity_reload_it_once_and_other_files_nothing() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = scripted("print('load')");
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.spawn("c", place(0.0, 64.0, 0.0), 0.0).unwrap();
    session.tick();
    platform.take();
    let result = session.reload(&paths(&[
        "centities/c/script.lua",
        "centities/c/centity.json",
        "README.md",
        ".netherforge/data/x",
    ]));
    assert_eq!(labels(&result.resources), ["centity:c"]);
    assert_eq!(printed(&platform.take()), ["load"]);
    assert!(session.reload(&paths(&["README.md"])).resources.is_empty());
}

#[test]
fn a_broken_script_is_reported_at_its_line_and_fixed_by_the_next_save() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = scripted("print('fine')");
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.spawn("c", place(0.0, 64.0, 0.0), 0.0).unwrap();
    files.write(
        "centities/c/script.lua",
        "local x = 1\nlocal y = 2\nerror('broken')\n",
    );
    let broken = session.reload(&paths(&["centities/c/script.lua"]));
    let broken = &broken.resources[0];
    assert!(!broken.ok);
    let problem = &broken.problems[0];
    assert_eq!(
        (
            problem.file.as_str(),
            problem.line,
            problem.code.as_deref().unwrap_or("")
        ),
        ("centities/c/script.lua", Some(3), "script.error"),
        "{problem:?}"
    );
    files.write("centities/c/script.lua", "print('fixed')");
    assert!(
        session
            .reload(&paths(&["centities/c/script.lua"]))
            .resources[0]
            .ok,
        "the next save fixes it"
    );
}

#[test]
fn a_centity_file_that_doesnt_parse_keeps_the_last_good_version_and_says_so() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = scripted("print('load')");
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.spawn("c", place(0.0, 64.0, 0.0), 0.0).unwrap();
    session.tick();
    platform.take();
    files.write("centities/c/centity.json", "{ \"nodes\": ");
    let result = session.reload(&paths(&["centities/c/centity.json"]));
    let resource = &result.resources[0];
    assert!(!resource.ok);
    assert!(
        resource
            .problems
            .iter()
            .any(|p| p.file == "centities/c/centity.json"),
        "{:?}",
        resource.problems
    );
    assert!(
        platform
            .take()
            .iter()
            .any(|l| l == "warn centity:c has errors; it keeps running the last good version")
    );
}

#[test]
fn reloading_a_module_restarts_it_every_module_that_required_it_and_the_scripts_that_did() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/a/init.lua",
            "nf.on('unload', function() print('a unloading') end)\nprint('a v1')\nreturn { version = 1 }",
        ),
        (
            "modules/b/init.lua",
            "print('b sees ' .. require('a').version)",
        ),
        ("modules/c/init.lua", "print('c is independent')"),
        ("centities/c/centity.json", SCRIPTED),
        (
            "centities/c/script.lua",
            "print('centity sees ' .. require('a').version)",
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.spawn("c", place(0.0, 64.0, 0.0), 0.0).unwrap();
    session.tick();
    platform.take();
    files.write("modules/a/init.lua", "return { version = 2 }");
    let result = session.reload(&paths(&["modules/a/init.lua"]));
    assert_eq!(
        labels(&result.resources),
        ["module:a", "module:b", "centity:c"]
    );
    assert!(result.resources.iter().all(|r| r.ok), "{result:?}");
    assert_eq!(
        printed(&platform.take()),
        ["a unloading", "b sees 2", "centity sees 2"]
    );
}

#[test]
fn saving_the_manifest_restarts_the_whole_package() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = scripted("print('load')");
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.spawn("c", place(0.0, 64.0, 0.0), 0.0).unwrap();
    let result = session.reload(&paths(&["netherforge.json"]));
    let project = &result.resources[0];
    assert_eq!(
        (project.pkg.as_str(), &project.kind, &project.id),
        ("demo", &None, &None),
        "the whole package"
    );
    assert!(project.ok);
    assert_eq!(project.reattached, 1);
}

#[test]
fn a_project_that_doesnt_run_starts_again_on_any_save_and_says_why_it_doesnt() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[(
        "netherforge.json",
        &MANIFEST.replace("\"26.3\"", "\"26.4\""),
    )]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(!session.running());
    files.add("modules/m/init.lua", "print('runs')");
    let refused = session.reload(&paths(&["modules/m/init.lua"]));
    assert!(!refused.resources[0].ok);
    assert!(
        refused.resources[0]
            .problems
            .iter()
            .any(|p| p.code.as_deref() == Some("runtime.minecraft-unsupported"))
    );
    files.write("netherforge.json", MANIFEST);
    let fixed = session.reload(&paths(&["netherforge.json"]));
    assert!(fixed.resources[0].ok);
    assert!(session.running());
}

#[test]
fn a_particle_effect_moves_live_effects_onto_it() {
    let platform = std::rc::Rc::new(Fake::default());
    let effect = |duration: i32| {
        format!(
            r#"{{ "duration": {duration}, "emitters": {{ "a": {{ "particle": "minecraft:flame", "burst": 1, "every": 1 }} }} }}"#
        )
    };
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("particles/p/effect.json", &effect(40)),
        (
            "modules/m/init.lua",
            "nf.particles.play('p', vector.create(0, 64, 0))",
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    session.tick();
    files.write("particles/p/effect.json", &effect(60));
    let result = session.reload(&paths(&["particles/p/effect.json"]));
    assert_eq!(labels(&result.resources), ["particle_effect:p"]);
    assert_eq!(result.resources[0].reattached, 1, "{result:?}");
}

#[test]
fn an_advancement_that_changed_since_the_server_started_needs_a_restart() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.datapack_format.borrow_mut() = Some(vec![94, 1]);
    let files = Files::new(&[("netherforge.json", MANIFEST)]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    files.add(
        "advancements/first.json",
        r#"{ "display": { "icon": { "item": "minecraft:stone" }, "title": "First", "description": "The first" }, "criteria": { "done": {} } }"#,
    );
    let result = session.reload(&paths(&["advancements/first.json"]));
    assert_eq!(labels(&result.resources), ["advancement:first"]);
    assert!(result.restart, "{result:?}");
    assert!(
        session
            .problems()
            .iter()
            .any(|p| p.code.as_deref() == Some("runtime.restart"))
    );
    // Something that isn't in the start-up datapack doesn't ask again.
    files.add("modules/m/init.lua", "");
    assert!(!session.reload(&paths(&["modules/m/init.lua"])).restart);
}

#[test]
fn a_packages_files_reload_as_its_own_resources() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[("netherforge.json", MANIFEST)]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let result = session.reload(&paths(&["acme:centities/coin/centity.json"]));
    assert_eq!(
        result
            .resources
            .iter()
            .map(|r| (r.pkg.as_str(), r.kind.as_deref(), r.id.as_deref()))
            .collect::<Vec<_>>(),
        [("acme", Some("centity"), Some("coin"))]
    );
    // A package's manifest is the whole tree's.
    let whole = session.reload(&paths(&["acme:netherforge.json"]));
    assert_eq!(whole.resources[0].kind, None);
}
