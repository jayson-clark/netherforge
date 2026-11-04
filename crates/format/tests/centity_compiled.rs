//! A centity as it runs: format's `CentityTest` (the compiler's part) and what
//! the examples don't show (ropes, a body's and physics' defaults and ranges).

use netherforge_format::kind::centity::CentityFile;
use netherforge_format::kind::centity::compiled::{self, CompiledCentity, ResolvedBody};
use netherforge_format::math::Vec3;
use serde_json::json;

fn file(value: serde_json::Value) -> CentityFile {
    serde_json::from_value(value).unwrap()
}

fn tower() -> CompiledCentity {
    compiled::compile(
        "tower",
        &file(json!({
            "nodes": {
                "top": { "parent": "root", "transform": { "translation": [0, 1, 0] } },
                "root": { "transform": { "rotation": [0, 90, 0] } },
                "tip": { "parent": "top", "transform": { "translation": [1, 0, 0] } }
            },
            "animations": {
                "spin": { "loop": "loop", "tracks": { "top": { "rotation": [
                    { "time": 0, "value": [0, 0, 0] }, { "time": 1, "value": [0, 180, 0] }
                ] } } }
            }
        })),
    )
}

fn assert_close(expected: Vec3, actual: Vec3) {
    let near = |a: f64, b: f64| (a - b).abs() < 1e-9;
    assert!(
        near(expected.0, actual.0) && near(expected.1, actual.1) && near(expected.2, actual.2),
        "expected {expected:?}, got {actual:?}"
    );
}

#[test]
fn compiles_parents_first() {
    let compiled = tower();
    let names: Vec<&str> = compiled.nodes.iter().map(|n| n.name.as_str()).collect();
    assert_eq!(names, ["root", "top", "tip"]);
    let parents: Vec<i32> = compiled.nodes.iter().map(|n| n.parent_index).collect();
    assert_eq!(parents, [-1, 0, 1]);
}

#[test]
fn composes_down_the_tree() {
    // root turns 90° about Y, so the tip's +X offset ends up along -Z.
    let world = tower().world(None);
    assert_close(Vec3(0.0, 1.0, -1.0), world[2].translation());
}

#[test]
fn samples_animations_with_looping() {
    let compiled = tower();
    let spin = compiled.animation("spin").unwrap();
    assert_close(Vec3(0.0, 90.0, 0.0), spin.tracks[0].sample(0.5));
    assert!((spin.resolve(1.25, spin.looped).time - 0.25).abs() < 1e-9);
    let posed = compiled.pose(spin, 0.5);
    assert_close(Vec3(0.0, 90.0, 0.0), posed[1].rotation.unwrap());
}

#[test]
fn an_additive_clip_changes_the_authored_pose() {
    let compiled = compiled::compile(
        "tower",
        &file(json!({
            "nodes": {
                "root": {},
                "top": { "parent": "root", "transform": { "translation": [0, 1, 0] } }
            },
            "animations": { "bump": { "blend": "additive", "tracks": { "top": {
                "translation": [{ "time": 0, "value": [0, 0.5, 0] }],
                "scale": [{ "time": 0, "value": [2, 0.5, 2] }]
            } } } }
        })),
    );
    let posed = compiled.pose(compiled.animation("bump").unwrap(), 0.0);
    // top rests at y 1: the clip lifts it by half a block, and halves its height (its scale was 1).
    assert_close(Vec3(0.0, 1.5, 0.0), posed[1].translation.unwrap());
    assert_close(Vec3(2.0, 0.5, 2.0), posed[1].scale.unwrap());
}

#[test]
fn rotation_takes_the_short_way_round() {
    let compiled = compiled::compile(
        "x",
        &file(json!({
            "nodes": { "a": {} },
            "animations": { "a": { "tracks": { "a": { "rotation": [
                { "time": 0, "value": [0, 350, 0] }, { "time": 1, "value": [0, 10, 0] }
            ] } } } }
        })),
    );
    assert_close(
        Vec3(0.0, 360.0, 0.0),
        compiled.animations[0].tracks[0].sample(0.5),
    );
}

#[test]
fn a_clip_without_keys_still_lasts_and_once_ends() {
    let compiled = compiled::compile(
        "x",
        &file(json!({ "nodes": { "a": {} }, "animations": { "idle": {} } })),
    );
    let idle = &compiled.animations[0];
    assert!(idle.length > 0.0);
    assert!(idle.resolve(1.0, idle.looped).ended);
}

#[test]
fn a_rope_is_drawn_by_its_links() {
    let compiled = compiled::compile(
        "bridge",
        &file(json!({
            "nodes": {
                "post": {},
                "rope": { "parent": "post", "display": { "type": "block", "block": "minecraft:chain" },
                          "rope": { "links": 3 } },
                "sign": { "parent": "post" }
            }
        })),
    );
    let names: Vec<&str> = compiled.nodes.iter().map(|n| n.name.as_str()).collect();
    assert_eq!(
        names,
        ["post", "rope", "sign", "rope[1]", "rope[2]", "rope[3]"]
    );
    let rope = &compiled.nodes[1];
    assert!(rope.display.is_none());
    let links = rope.rope.as_ref().unwrap();
    assert_eq!(links.links, [3, 4, 5]);
    // The schema's default length, half a block, each laid along Z from the rope.
    assert_eq!(links.link_length, 0.5);
    let second = &compiled.nodes[4];
    assert_eq!(second.parent_index, 1);
    assert!(second.display.is_some());
    assert_eq!(second.transform.translation, Some(Vec3(0.0, 0.0, 0.75)));
    assert_eq!(second.transform.scale, Some(Vec3(1.0, 1.0, 0.5)));
}

#[test]
fn a_single_node_compiles_as_in_a_file() {
    let node = serde_json::from_value(json!({
        "transform": { "translation": [0, 2, 0] },
        "display": { "type": "block", "block": "minecraft:stone" }
    }))
    .unwrap();
    let node = compiled::compile_node("lamp", &node, 1);
    assert_eq!(node.name, "lamp");
    assert_eq!(node.parent_index, 1);
    assert_eq!(node.transform.translation_or_default(), Vec3(0.0, 2.0, 0.0));
}

#[test]
fn a_body_takes_its_schemas_defaults_and_ranges() {
    let body: ResolvedBody = ResolvedBody::of(
        &serde_json::from_value(json!({ "width": 2, "drag": 1.5, "stepHeight": -1 })).unwrap(),
    );
    assert_eq!(body.width, 2.0);
    assert_eq!(body.height, 1.8);
    // Out of its range only warns, and is held there.
    assert_eq!(body.drag, 1.0);
    assert_eq!(body.step_height, 0.0);
    assert_eq!(body.max_fall_speed, 78.4);
    assert!(!body.fly && body.blocks);
}

#[test]
fn physics_and_health_take_their_schemas_defaults() {
    let compiled = compiled::compile(
        "c",
        &file(json!({
            "nodes": { "a": { "physics": { "bounciness": 2, "maxSpin": -5 } } },
            "health": { "max": 10, "bar": { "segments": 4 } }
        })),
    );
    let physics = compiled.nodes[0].physics.as_ref().unwrap();
    assert_eq!(physics.bounciness, 1.0);
    assert_eq!(physics.max_spin, 0.0);
    assert_eq!(physics.gravity, 32.0);
    assert_eq!(physics.mass, None);
    assert!(physics.rotates && physics.entities);
    let health = compiled.health.as_ref().unwrap();
    assert!(health.remove_on_death);
    let bar = health.bar.as_ref().unwrap();
    assert_eq!((bar.segments, bar.show_ticks), (4, 100));
    assert_eq!(bar.glyph, "▮");
}

#[test]
fn a_negative_max_spin_is_a_warning() {
    let text = r#"{
  "nodes": {
    "a": {
      "physics": {
        "maxSpin": -5
      }
    }
  }
}
"#;
    let cx = netherforge_format::document::Context::default();
    let read =
        netherforge_format::document::load::<CentityFile>(text, "centities/c/centity.json", &cx);
    let codes: Vec<(&str, &str)> = read
        .problems
        .iter()
        .map(|p| {
            (
                p.code.as_deref().unwrap_or(""),
                p.path.as_deref().unwrap_or(""),
            )
        })
        .collect();
    assert_eq!(
        codes,
        [("centity.physics-range", "$.nodes.a.physics.maxSpin")]
    );
}
