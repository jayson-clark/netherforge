//! Format's `WorkspaceTest`, `NetworkUsesTest` and `LuaStringsTest`.

use netherforge_format::lua::literals;
use netherforge_format::workspace::{
    self, NetworkUse, NetworkUseKind, WorkspaceFile, check_network_uses, expand, is_pattern,
    network_uses,
};

/// The folders under the workspace that hold a netherforge.json, as the host finds them.
fn projects() -> Vec<String> {
    [
        "servers/hub",
        "servers/chess",
        "servers/terra",
        "packages/chess",
        "tools/scratch",
    ]
    .map(String::from)
    .to_vec()
}

fn read(text: &str) -> WorkspaceFile {
    WorkspaceFile::parse(text).unwrap()
}

#[test]
fn projects_are_folders_or_every_project_directly_in_one_in_order() {
    let w = read(
        r#"{ "name": "Polsar", "projects": ["servers/*", "./packages/chess/", "servers/hub"] }"#,
    );
    let check = workspace::check(&w, &projects());
    assert_eq!(check.problems, []);
    assert_eq!(
        check.members,
        [
            "servers/chess",
            "servers/hub",
            "servers/terra",
            "packages/chess"
        ]
    );
    assert_eq!(
        expand(&["packages/*".into()], &projects()),
        ["packages/chess"]
    );
    assert_eq!(expand(&["tools/*".into()], &projects()), ["tools/scratch"]);
}

#[test]
fn a_workspace_holds_only_whats_under_it() {
    for bad in [
        "../other",
        "/abs",
        "a/../b",
        "a\\b",
        "",
        ".",
        "servers/*/x",
        "a/./b",
        "C:/x",
    ] {
        assert!(!is_pattern(bad), "{bad}");
    }
    for good in ["servers/hub", "servers/*", "*", "my project/v2.1"] {
        assert!(is_pattern(good), "{good}");
    }
}

#[test]
fn every_mistake_has_its_code_at_its_place() {
    let w = read(
        r#"{
          "name": " ",
          "projects": ["../escape", "servers/missing", "empty/*", "servers/*"],
          "network": {
            "arrival": "lobby",
            "servers": { "hub": "servers/hub", "Bad Name": "servers/chess", "ghost": "servers/missing" },
            "options": {
              "hub": { "group": "lobby", "capacity": 50, "role": "standard" },
              "nowhere": { "group": "x" },
              "ghost": { "role": "admin" }
            }
          }
        }"#,
    );
    let codes: Vec<(String, String)> = workspace::check(&w, &projects())
        .problems
        .into_iter()
        .map(|p| (p.code.unwrap(), p.path.unwrap()))
        .collect();
    let expected = [
        ("workspace.name", "$.name"),
        ("workspace.project-path", "$.projects[0]"),
        ("workspace.missing-project", "$.projects[1]"),
        ("workspace.empty-pattern", "$.projects[2]"),
        ("workspace.server-name", "$.network.servers[\"Bad Name\"]"),
        ("workspace.server-project", "$.network.servers.ghost"),
        ("workspace.server-options", "$.network.options.nowhere"),
        ("workspace.server-options", "$.network.options.ghost"),
        ("workspace.arrival", "$.network.arrival"),
    ]
    .map(|(c, p)| (c.to_string(), p.to_string()));
    assert_eq!(codes, expected);
}

#[test]
fn its_written_canonically_each_project_once() {
    let w = read(
        r#"{ "network": { "servers": { "terra": "servers/terra", "hub": "servers/hub" }, "arrival": "hub" }, "projects": ["servers/hub/", "servers/hub", "packages/*"], "name": "Polsar" }"#,
    );
    assert_eq!(
        w.write(),
        r#"{
  "$schema": ".netherforge/schema/workspace.schema.json",
  "name": "Polsar",
  "projects": ["servers/hub", "packages/*"],
  "network": {
    "arrival": "hub",
    "servers": {
      "hub": "servers/hub",
      "terra": "servers/terra"
    }
  }
}
"#
    );
}

#[test]
fn finds_the_literal_names_scripts_give_nf_network() {
    let source = r#"local network = nf.network
local games = nf.network.channel("games")
local seats = network.map 'seats'
-- nf.network.send_player(player, "commented")
nf.network.send_player(player, "chess", { board = 1 })
nf.network.send_player(find(player, ","), [[terra]])
nf.network.send_player(player, "a" .. suffix)
nf.network.send_player(player, target)
other.send_player(player, "nope")
nf.network.channel(name)"#;
    let uses = |kind, name: &str, line| NetworkUse {
        kind,
        name: name.into(),
        line,
    };
    assert_eq!(
        network_uses(source),
        [
            uses(NetworkUseKind::Channel, "games", 2),
            uses(NetworkUseKind::Map, "seats", 3),
            uses(NetworkUseKind::Server, "chess", 5),
            uses(NetworkUseKind::Server, "terra", 6),
        ]
    );
}

#[test]
fn a_player_sent_to_a_server_the_network_doesnt_have_is_a_warning_at_its_line() {
    let problems = check_network_uses(
        [
            (
                "modules/lobby/init.lua",
                "nf.network.send_player(p, \"hub\")\n\nnf.network.send_player(p, \"chesss\")",
            ),
            ("modules/other/init.lua", "nf.network.channel(\"x\")"),
        ],
        &["hub".into(), "chess".into()],
    );
    let found: Vec<(Option<String>, String, Option<u32>)> = problems
        .iter()
        .map(|p| (p.code.clone(), p.file.clone(), p.line))
        .collect();
    assert_eq!(
        found,
        [(
            Some("workspace.unknown-server".into()),
            "modules/lobby/init.lua".into(),
            Some(3)
        )]
    );
    assert_eq!(
        problems[0].message,
        "There's no server \"chesss\" in the workspace's network: it has chess, hub."
    );
}

#[test]
fn quotes_escapes_and_long_brackets_are_read() {
    let source = "local a = \"minecraft:stone\" -- \"not this\"\n\
local b = 'it''s' .. \"a\\\"b\\n\" .. \"\\65\\x42\\u{43}\"\n\
--[[ \"nor this\"\n\
  ]] local c = [[\n\
long]] .. [==[with ]] in it]==]\n\
local d = \"a\\z\n\
           b\"";
    assert_eq!(
        literals(source),
        [
            "minecraft:stone",
            "it",
            "s",
            "a\"b\n",
            "ABC",
            "long",
            "with ]] in it",
            "ab"
        ]
    );
}
