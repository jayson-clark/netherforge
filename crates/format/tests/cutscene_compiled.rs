//! A cutscene as it plays: format's `CutsceneTest`, its sampling and compiling.

use netherforge_format::kind::cutscene::{self, CameraPath, CutsceneFile};
use serde_json::{Value, json};

fn path(positions: Value, rotations: Value) -> CameraPath {
    CameraPath {
        positions: serde_json::from_value(positions).unwrap(),
        rotations: serde_json::from_value(rotations).unwrap(),
    }
}

fn at_x(time: f64, x: f64) -> Value {
    json!({ "time": time, "value": [x, 0, 0] })
}

fn look() -> Value {
    json!([{ "time": 0, "yaw": 0, "pitch": 0 }])
}

fn file(extra: Value) -> CutsceneFile {
    let mut file =
        json!({ "camera": { "position": [at_x(0.0, 0.0), at_x(2.0, 10.0)], "rotation": look() } });
    file.as_object_mut()
        .unwrap()
        .extend(extra.as_object().unwrap().clone());
    serde_json::from_value(file).unwrap()
}

#[test]
fn position_is_linear_between_keys_and_held_outside_them() {
    let path = path(json!([at_x(1.0, 10.0), at_x(3.0, 30.0)]), look());
    let xs: Vec<f64> = [0.0, 1.0, 2.0, 3.0, 99.0]
        .iter()
        .map(|t| path.at(*t).position.0)
        .collect();
    assert_eq!(xs, [10.0, 10.0, 20.0, 30.0, 30.0]);
}

#[test]
fn the_easing_on_a_key_shapes_the_segment_that_leaves_it() {
    let eased = path(
        json!([{ "time": 0, "value": [0, 0, 0], "easing": "ease_in" }, at_x(2.0, 100.0)]),
        look(),
    );
    // Halfway through the segment ease_in is a quarter of the way.
    assert_eq!(eased.at(1.0).position.0, 25.0);
    let stepped = path(
        json!([{ "time": 0, "value": [0, 0, 0], "easing": "step" }, at_x(2.0, 100.0)]),
        look(),
    );
    assert_eq!(stepped.at(1.99).position.0, 0.0);
    assert_eq!(stepped.at(2.0).position.0, 100.0);
}

#[test]
fn yaw_turns_the_short_way_round_and_pitch_is_plain() {
    let path = path(
        json!([at_x(0.0, 0.0)]),
        json!([{ "time": 0, "yaw": 350, "pitch": -10 }, { "time": 2, "yaw": 10, "pitch": 30 }]),
    );
    let half = path.at(1.0);
    // 350 to 10 is 20 degrees forwards, not 340 back.
    assert_eq!((half.yaw, half.pitch), (360.0, 10.0));
    assert_eq!(path.at(0.0).yaw, 350.0);
    assert_eq!(path.at(5.0).yaw, 10.0);
}

#[test]
fn two_keys_at_one_time_dont_divide_by_nothing() {
    let path = path(json!([at_x(1.0, 0.0), at_x(1.0, 5.0)]), look());
    assert_eq!(path.at(2.0).position.0, 5.0);
}

#[test]
fn its_length_is_the_files_else_the_last_key_or_cue() {
    assert_eq!(cutscene::compile("x", &file(json!({}))).length, 2.0);
    assert_eq!(
        cutscene::compile("x", &file(json!({ "length": 5 }))).length,
        5.0
    );
    let cued = cutscene::compile(
        "x",
        &file(json!({ "cues": [{ "time": 3, "event": "late" }] })),
    );
    assert_eq!(cued.length, 3.0);
    assert_eq!(cued.cues[0].time, 3.0);
}

#[test]
fn cues_come_out_in_time_order_with_their_default_duration() {
    let compiled = cutscene::compile(
        "x",
        &file(
            json!({ "cues": [{ "time": 1.5, "text": "b" }, { "time": 0.5, "text": "a", "duration": 1 }] }),
        ),
    );
    let texts: Vec<&str> = compiled
        .cues
        .iter()
        .map(|c| c.text.as_deref().unwrap())
        .collect();
    assert_eq!(texts, ["a", "b"]);
    let durations: Vec<f64> = compiled.cues.iter().map(|c| c.duration).collect();
    // The second's is its field's schema default.
    assert_eq!(durations, [1.0, 3.0]);
}
