//! Modules and `require`, as the Kotlin runtime's `ModulesTest` has them.

use netherforge_luau::mlua::Value;
use netherforge_luau::{Host, Limits, ScopeId};
use netherforge_runtime::{Grants, Modules, Project, Room};

const BUDGET: u64 = 1_000_000;

fn project(files: &[(&str, &str)]) -> Project {
    Project::of(
        files
            .iter()
            .map(|(p, s)| (p.to_string(), s.to_string()))
            .collect(),
    )
}

struct Test {
    room: Room,
    modules: Modules,
}

impl Test {
    fn new(files: &[(&str, &str)]) -> Test {
        let room = Room::new(Host::new(Limits::default()).unwrap(), Grants::default()).unwrap();
        let modules = Modules::new(room.scripts().clone(), project(files), BUDGET);
        Test { room, modules }
    }

    fn get(&self, scope: ScopeId, name: &str) -> Value {
        self.room.host().env(scope).unwrap().get(name).unwrap()
    }

    fn errors(&self) -> Vec<String> {
        self.room
            .scripts()
            .take_reports()
            .into_iter()
            .map(|r| r.failure.to_string())
            .collect()
    }
}

#[test]
fn every_requirer_gets_the_running_module() {
    let t = Test::new(&[
        (
            "modules/counter/init.lua",
            "local M = { count = 0 } function M.bump() M.count += 1 end return M",
        ),
        (
            "modules/a/init.lua",
            "local counter = require('counter') counter.bump()",
        ),
        (
            "modules/b/init.lua",
            "local counter = require('counter') counter.bump() seen = counter.count",
        ),
    ]);
    t.modules.start();
    let b = t.modules.module("b").unwrap();
    assert_eq!(
        t.get(b, "seen")
            .as_f64()
            .or(t.get(b, "seen").as_i64().map(|n| n as f64)),
        Some(2.0)
    );
    assert!(t.errors().is_empty());
}

#[test]
fn a_modules_own_files_and_dotted_names() {
    let t = Test::new(&[
        (
            "modules/greeter/init.lua",
            "local util = require('util') return { hello = util.hello }",
        ),
        (
            "modules/greeter/util.lua",
            "return { hello = function(n) return 'hello ' .. n end }",
        ),
        (
            "modules/greeter/messages/init.lua",
            "return { bye = 'bye' }",
        ),
        (
            "modules/user/init.lua",
            "local g = require('greeter') local m = require('greeter.messages') said = g.hello('Alex') .. ', ' .. m.bye",
        ),
    ]);
    let user = t.modules.module("user").unwrap();
    assert_eq!(t.get(user, "said").to_string().unwrap(), "hello Alex, bye");
    assert!(t.errors().is_empty());
}

#[test]
fn a_file_beside_a_script_is_part_of_each_instance() {
    let t = Test::new(&[
        (
            "centities/tower/root.lua",
            "local h = require('helpers') h.n += 1 count = h.n",
        ),
        ("centities/tower/helpers.lua", "return { n = 0 }"),
    ]);
    let one = t
        .modules
        .script("tower 1", "centities/tower", "centities/tower/root.lua")
        .unwrap();
    let two = t
        .modules
        .script("tower 2", "centities/tower", "centities/tower/root.lua")
        .unwrap();
    // Each instance has its own copy.
    assert_eq!(t.get(one, "count").to_string().unwrap(), "1");
    assert_eq!(t.get(two, "count").to_string().unwrap(), "1");
}

#[test]
fn what_require_refuses() {
    let t = Test::new(&[
        ("centities/tower/root.lua", "require('root')"),
        ("modules/loop_a/init.lua", "require('loop_b')"),
        ("modules/loop_b/init.lua", "require('loop_a')"),
        ("modules/broken/init.lua", "error('broken')"),
        (
            "modules/user/init.lua",
            "ok, why = pcall(require, 'broken') ok2, missing = pcall(require, 'nowhere') ok3, bad = pcall(require, 'a/b')",
        ),
    ]);
    let _ = t
        .modules
        .script("tower", "centities/tower", "centities/tower/root.lua");
    let _ = t.modules.module("loop_a");
    let user = t.modules.module("user").unwrap();
    let errors = t.errors();
    let has = |part: &str| errors.iter().any(|e| e.contains(part));
    assert!(
        has(
            "\"root\" is this script's own file, centities/tower/root.lua, which is already running"
        ),
        "{errors:?}"
    );
    assert!(
        has("circular require of modules/loop_a/init.lua"),
        "{errors:?}"
    );
    assert!(t.get(user, "why").to_string().unwrap().contains("broken"));
    let missing = t.get(user, "missing").to_string().unwrap();
    assert!(missing.contains("no module \"nowhere\" (looked for modules/user/nowhere.lua, modules/user/nowhere/init.lua, modules/nowhere/init.lua)"), "{missing}");
    assert!(
        t.get(user, "bad")
            .to_string()
            .unwrap()
            .contains("isn't a module name")
    );
}

#[test]
fn a_module_that_failed_tells_its_next_requirer_so() {
    let t = Test::new(&[
        ("modules/broken/init.lua", "error('broken')"),
        (
            "modules/user/init.lua",
            "ok, why = pcall(require, 'broken')",
        ),
    ]);
    let _ = t.modules.module("broken");
    let user = t.modules.module("user").unwrap();
    let why = t.get(user, "why").to_string().unwrap();
    assert!(
        why.contains("module \"broken\" failed to load; fix it and save to reload"),
        "{why}"
    );
}

#[test]
fn a_modules_handlers_are_the_modules_wherever_its_file_was_required_from() {
    let t = Test::new(&[
        (
            "modules/listener/init.lua",
            "heard = 0 nf.on('shop:bought', function() heard += 1 end)",
        ),
        (
            "centities/shop/root.lua",
            "require('listener') nf.emit('shop:bought', {})",
        ),
    ]);
    let shop = t
        .modules
        .script("shop", "centities/shop", "centities/shop/root.lua")
        .unwrap();
    let listener = t.modules.module("listener").unwrap();
    assert_eq!(t.get(listener, "heard").to_string().unwrap(), "1");
    assert_eq!(t.modules.dependents("listener"), [shop]);
    // Closing the module closes its handler, not the script's.
    t.modules.close(listener);
    t.room
        .host()
        .run::<()>(
            shop,
            BUDGET,
            "centities/shop/root.lua",
            "nf.emit('shop:bought', {})",
        )
        .unwrap();
    assert!(t.errors().is_empty());
}

#[test]
fn a_reload_runs_the_new_files() {
    let t = Test::new(&[("modules/m/init.lua", "version = 1")]);
    let old = t.modules.module("m").unwrap();
    assert_eq!(t.get(old, "version").to_string().unwrap(), "1");
    let new = t
        .modules
        .reload("m", project(&[("modules/m/init.lua", "version = 2")]))
        .unwrap();
    assert_ne!(old, new);
    assert_eq!(t.get(new, "version").to_string().unwrap(), "2");
}

#[test]
fn a_scope_has_its_own_g_and_logs_with_its_line() {
    let t = Test::new(&[(
        "modules/m/init.lua",
        "_G.mine = 1\nlog('hello', 2, nil, true)\nprint(mine)",
    )]);
    let m = t.modules.module("m").unwrap();
    assert_eq!(t.get(m, "mine").to_string().unwrap(), "1");
    let logs = t.room.scripts().take_logs();
    let lines: Vec<(&str, u32)> = logs
        .iter()
        .map(|l| (l.line.as_str(), l.location.as_ref().unwrap().line))
        .collect();
    assert_eq!(lines, [("hello\t2\tnil\ttrue", 2), ("1", 3)]);
}
