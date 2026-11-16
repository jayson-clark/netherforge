//! What the showcase's effects spawn, tick by tick from seed 7, is format's
//! golden (`packages/format/testdata/particles/samples.json`, written by Kotlin's
//! `EffectSamplesGoldenTest`). The draws are Kotlin's to the bit; a position
//! may differ in its last bits where the JVM's sine or cube root does.

use std::path::{Path, PathBuf};

use netherforge_format::kind::particle::compiled::CompiledEffect;
use netherforge_format::kind::particle::sample::EffectSampler;
use netherforge_format::project;
use serde_json::Value;

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

#[test]
fn the_showcases_effects_sample_as_the_golden_says() {
    let dir = root().join("examples/showcase");
    let source = project::Folder::open(&dir).unwrap();
    let packages = netherforge_format::package::Folders { root: dir.clone() };
    let loaded = project::load(
        &source,
        project::Options {
            packages: &packages,
            ..Default::default()
        },
    );
    let mut actual = serde_json::Map::new();
    for (id, effect) in loaded.compiled::<CompiledEffect>() {
        let mut sampler = EffectSampler::new(effect.clone(), 7);
        let ticks: Vec<_> = (0..effect.duration).map(|_| sampler.step(false)).collect();
        assert!(sampler.finished());
        actual.insert(id, serde_json::to_value(ticks).unwrap());
    }
    let golden = root().join("packages/format/testdata/particles/samples.json");
    let expected: Value = serde_json::from_str(&std::fs::read_to_string(golden).unwrap()).unwrap();
    assert_eq!(actual.len(), expected.as_object().unwrap().len());
    if let Some(at) = near(&expected, &Value::Object(actual), "$") {
        panic!("{at}");
    }
}

/// Where `got` first differs from `want`, numbers within a few units in their last place.
fn near(want: &Value, got: &Value, at: &str) -> Option<String> {
    match (want, got) {
        (Value::Number(a), Value::Number(b)) => {
            let (a, b) = (a.as_f64().unwrap(), b.as_f64().unwrap());
            ((a - b).abs() > 1e-12 * a.abs().max(1.0)).then(|| format!("{at}: {b}, not {a}"))
        }
        (Value::Object(a), Value::Object(b)) => {
            if a.len() != b.len() {
                return Some(format!(
                    "{at}: keys {:?}, not {:?}",
                    b.keys().collect::<Vec<_>>(),
                    a.keys().collect::<Vec<_>>()
                ));
            }
            a.iter().find_map(|(k, v)| match b.get(k) {
                Some(w) => near(v, w, &format!("{at}.{k}")),
                None => Some(format!("{at}.{k}: missing")),
            })
        }
        (Value::Array(a), Value::Array(b)) if a.len() == b.len() => a
            .iter()
            .zip(b)
            .enumerate()
            .find_map(|(i, (x, y))| near(x, y, &format!("{at}[{i}]"))),
        _ if want == got => None,
        _ => Some(format!("{at}: {got}, not {want}")),
    }
}

// Format's `EffectSamplerTest`.

use netherforge_format::kind::particle::compiled::{self, ColorCurve, NumberCurve};
use netherforge_format::kind::particle::sample::{EffectFrame, EffectSpawn, SpawnData};
use netherforge_format::math::Vec3;
use serde_json::json;

fn effect(duration: i32, emitters: Value, looped: bool) -> CompiledEffect {
    let file = serde_json::from_value(
        json!({ "duration": duration, "loop": looped, "emitters": emitters }),
    )
    .unwrap();
    compiled::compile("test", &file, None)
}

fn counts(effect: &CompiledEffect, ticks: i32, looped: bool) -> Vec<usize> {
    let mut sampler = EffectSampler::new(effect.clone(), 1);
    (0..ticks).map(|_| sampler.step(looped).len()).collect()
}

fn close(expected: Vec3, actual: Vec3, what: &str) {
    let ok = [
        (expected.0, actual.0),
        (expected.1, actual.1),
        (expected.2, actual.2),
    ]
    .iter()
    .all(|(a, b)| (a - b).abs() < 1e-9);
    assert!(ok, "{what}: expected {expected:?}, got {actual:?}");
}

fn length(v: Vec3) -> f64 {
    (v.0 * v.0 + v.1 * v.1 + v.2 * v.2).sqrt()
}

fn one(emitter: Value) -> EffectSpawn {
    let effect = effect(1, json!({ "a": emitter }), false);
    let mut spawns = EffectSampler::new(effect, 1).step(false);
    assert_eq!(spawns.len(), 1);
    spawns.remove(0)
}

#[test]
fn one_burst_at_start_and_every_pulses_stop_before_end() {
    let start = effect(
        5,
        json!({ "a": { "particle": "flame", "burst": 3, "start": 2 } }),
        false,
    );
    assert_eq!(counts(&start, 5, false), [0, 0, 3, 0, 0]);
    let every = effect(
        10,
        json!({ "a": { "particle": "flame", "burst": 1, "start": 1, "every": 3, "end": 7 } }),
        false,
    );
    assert_eq!(counts(&every, 10, false), [0, 1, 0, 0, 1, 0, 0, 0, 0, 0]);
}

#[test]
fn fractional_rates_accumulate_and_a_rate_curve_ramps() {
    let quarter = effect(
        12,
        json!({ "a": { "particle": "flame", "rate": 0.25 } }),
        false,
    );
    let ticks: Vec<usize> = counts(&quarter, 12, false)
        .iter()
        .enumerate()
        .filter(|(_, n)| **n > 0)
        .map(|(i, _)| i)
        .collect();
    assert_eq!(ticks, [3, 7, 11]);
    let ramp = effect(
        6,
        json!({ "a": { "particle": "flame", "rate": 1, "curves": { "rate": [{ "time": 0, "value": 0 }, { "time": 5, "value": 5 }] } } }),
        false,
    );
    assert_eq!(counts(&ramp, 6, false), [0, 1, 2, 3, 4, 5]);
}

#[test]
fn looping_wraps_and_starts_rates_from_nothing() {
    let effect = effect(
        4,
        json!({ "a": { "particle": "flame", "rate": 0.4 } }),
        false,
    );
    // 0.4, 0.8, 1.2 → 1 (0.2 left), 0.6; then from nothing again, not from 0.6.
    assert_eq!(counts(&effect, 8, true), [0, 0, 1, 0, 0, 0, 1, 0]);
    let mut sampler = EffectSampler::new(effect, 1);
    for _ in 0..4 {
        sampler.step(true);
    }
    assert_eq!(sampler.tick(), 0);
    assert!(!sampler.finished());
}

#[test]
fn an_effect_that_doesnt_loop_finishes_after_its_last_tick() {
    let effect = effect(
        3,
        json!({ "a": { "particle": "flame", "burst": 1, "every": 1 } }),
        false,
    );
    let mut sampler = EffectSampler::new(effect, 1);
    assert_eq!(sampler.step(false).len(), 1);
    assert_eq!(sampler.step(false).len(), 1);
    assert!(!sampler.finished());
    assert_eq!(sampler.step(false).len(), 1);
    assert!(sampler.finished());
    assert!(sampler.step(false).is_empty());
}

#[test]
fn curves_ease_and_hold_outside_their_keys() {
    let curve = |easing: &str| {
        let keys = serde_json::from_value::<Vec<_>>(json!([
            { "time": 10, "value": 0, "easing": easing }, { "time": 20, "value": 10 }
        ]))
        .unwrap();
        NumberCurve::of(&keys)
    };
    let near = |a: f64, b: f64, what: &str| assert!((a - b).abs() < 1e-9, "{what}: {a} vs {b}");
    near(0.0, curve("linear").at(0.0), "before the first key");
    near(10.0, curve("linear").at(99.0), "after the last key");
    near(5.0, curve("linear").at(15.0), "linear");
    near(0.0, curve("step").at(19.0), "step");
    near(2.5, curve("ease_in").at(15.0), "ease in");
    near(7.5, curve("ease_out").at(15.0), "ease out");
    near(5.0, curve("ease_in_out").at(15.0), "ease in-out");
    near(10.0, curve("step").at(20.0), "step lands on the next key");
    let unsorted = serde_json::from_value::<Vec<_>>(
        json!([{ "time": 20, "value": 10 }, { "time": 10, "value": 0 }]),
    )
    .unwrap();
    near(
        5.0,
        NumberCurve::of(&unsorted).at(15.0),
        "keys in any order",
    );
    let colors = serde_json::from_value::<Vec<_>>(
        json!([{ "time": 0, "color": "#000000" }, { "time": 10, "color": "#ff8000" }]),
    )
    .unwrap();
    let colors = ColorCurve::of(&colors);
    assert_eq!(colors.at(5.0), 0x804000);
    assert_eq!(colors.at(30.0), 0xff8000);
}

#[test]
fn even_rings_are_evenly_spaced_and_spin() {
    let effect = effect(
        5,
        json!({ "a": { "particle": "flame", "burst": 4, "every": 1, "shape": { "type": "ring", "radius": 2 },
                       "distribution": "even", "spin": 10 } }),
        false,
    );
    let mut sampler = EffectSampler::new(effect, 1);
    let first = sampler.step(false);
    close(Vec3(2.0, 0.0, 0.0), first[0].position, "tick 0, point 0");
    close(Vec3(0.0, 0.0, 2.0), first[1].position, "tick 0, point 1");
    close(Vec3(-2.0, 0.0, 0.0), first[2].position, "tick 0, point 2");
    // Tick 1: everything turned 10° about +Y.
    let angles: Vec<f64> = sampler
        .step(false)
        .iter()
        .map(|s| s.position.2.atan2(s.position.0).to_degrees())
        .collect();
    assert!(
        (angles[0] + 10.0).abs() < 1e-9 && (angles[1] - 80.0).abs() < 1e-9,
        "{angles:?}"
    );
}

#[test]
fn even_lines_and_fibonacci_spheres() {
    let line = |burst: i32| {
        let effect = effect(
            1,
            json!({ "a": { "particle": "flame", "burst": burst, "shape": { "type": "line", "to": [0, 4, 0] }, "distribution": "even" } }),
            false,
        );
        EffectSampler::new(effect, 1)
            .step(false)
            .iter()
            .map(|s| s.position.1)
            .collect::<Vec<f64>>()
    };
    assert_eq!(line(3), [0.0, 2.0, 4.0]);
    assert_eq!(line(1), [2.0]);
    let sphere = effect(
        1,
        json!({ "a": { "particle": "flame", "burst": 50, "shape": { "type": "sphere", "radius": 1.5, "surface": true }, "distribution": "even" } }),
        false,
    );
    let shell = EffectSampler::new(sphere, 1).step(false);
    assert_eq!(shell.len(), 50);
    for spawn in &shell {
        assert!((length(spawn.position) - 1.5).abs() < 1e-9);
    }
}

#[test]
fn random_shapes_stay_inside_and_a_radius_curve_grows_them() {
    let disc = effect(
        1,
        json!({ "a": { "particle": "flame", "burst": 100, "shape": { "type": "disc", "radius": 2 } } }),
        false,
    );
    for spawn in EffectSampler::new(disc, 7).step(false) {
        assert_eq!(spawn.position.1, 0.0);
        assert!(length(spawn.position) <= 2.0);
    }
    let ball = effect(
        1,
        json!({ "a": { "particle": "flame", "burst": 100, "shape": { "type": "sphere", "radius": 1 } } }),
        false,
    );
    for spawn in EffectSampler::new(ball, 7).step(false) {
        assert!(length(spawn.position) <= 1.0);
    }
    let growing = effect(
        5,
        json!({ "a": { "particle": "flame", "burst": 1, "every": 1, "shape": { "type": "ring" },
                       "curves": { "radius": [{ "time": 0, "value": 1 }, { "time": 4, "value": 5 }] } } }),
        false,
    );
    let mut sampler = EffectSampler::new(growing, 1);
    for expected in [1.0, 2.0, 3.0, 4.0, 5.0] {
        assert!((length(sampler.step(false)[0].position) - expected).abs() < 1e-9);
    }
}

#[test]
fn motion_sets_count_and_offset() {
    let base = json!({ "particle": "flame", "burst": 1, "shape": { "type": "line", "to": [2, 0, 0] }, "distribution": "even", "speed": 0.5 });
    let with = |extra: Value| {
        let mut e = base.clone();
        e.as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        one(e)
    };
    let random = with(json!({ "count": 4, "spread": [0.1, 0.2, 0.3] }));
    assert_eq!(random.count, 4);
    assert_eq!(random.offset, Vec3(0.1, 0.2, 0.3));
    assert_eq!(random.speed, 0.5);
    let outward = with(json!({ "motion": "outward" }));
    assert_eq!(outward.count, 0);
    close(Vec3(1.0, 0.0, 0.0), outward.offset, "outward");
    close(
        Vec3(-1.0, 0.0, 0.0),
        with(json!({ "motion": "inward" })).offset,
        "inward",
    );
    // A point at the centre flies straight up the shape's axis.
    close(
        Vec3(0.0, 1.0, 0.0),
        one(json!({ "particle": "flame", "burst": 1, "motion": "outward" })).offset,
        "from the centre",
    );
    let stream =
        with(json!({ "motion": "direction", "direction": [0, 0, 3], "rotation": [0, 90, 0] }));
    close(Vec3(1.0, 0.0, 0.0), stream.offset, "turned direction");
    close(Vec3(0.0, 0.0, -1.0), stream.position, "turned point");
}

#[test]
fn spawns_carry_their_data_at_the_tick() {
    // Without the game's facts, a size says it's dust.
    let effect = effect(
        3,
        json!({ "a": { "particle": "dust", "burst": 1, "every": 1, "size": 2,
                       "curves": { "color": [{ "time": 0, "color": "#000000" }, { "time": 2, "color": "#ffffff" }] } } }),
        false,
    );
    let mut sampler = EffectSampler::new(effect, 1);
    let data: Vec<Option<SpawnData>> = (0..3).map(|_| sampler.step(false).remove(0).data).collect();
    let dust = |color| Some(SpawnData::Dust { color, size: 2.0 });
    assert_eq!(data, [dust(0x000000), dust(0x808080), dust(0xffffff)]);
}

#[test]
fn the_frame_places_effect_space_in_the_world() {
    let frame = |yaw, pitch, scale| EffectFrame {
        yaw,
        pitch,
        scale,
        ..EffectFrame::at(Vec3::ZERO)
    };
    close(
        Vec3(-1.0, 0.0, 0.0),
        frame(90.0, 0.0, 1.0).direction(Vec3(0.0, 0.0, 1.0)),
        "yaw 90",
    );
    close(
        Vec3(0.0, -1.0, 0.0),
        frame(0.0, 90.0, 1.0).direction(Vec3(0.0, 0.0, 1.0)),
        "pitch 90",
    );
    let placed = EffectFrame {
        origin: Vec3(10.0, 64.0, 10.0),
        ..frame(180.0, 0.0, 2.0)
    };
    close(
        Vec3(10.0, 64.0, 8.0),
        placed.position(Vec3(0.0, 0.0, 1.0)),
        "position",
    );
    let spawn = EffectSpawn {
        emitter: "a".into(),
        particle: "minecraft:flame".into(),
        position: Vec3(0.0, 0.0, 1.0),
        count: 0,
        offset: Vec3(0.0, 0.0, 1.0),
        speed: 0.5,
        data: None,
        force: false,
    };
    let world = placed.to_world(&spawn);
    close(
        Vec3(0.0, 0.0, -1.0),
        world.offset,
        "a direction turns and doesn't scale",
    );
    assert!((world.speed - 1.0).abs() < 1e-9);
    let spread = placed.to_world(&EffectSpawn {
        count: 2,
        offset: Vec3(0.1, 0.2, 0.3),
        ..spawn
    });
    close(
        Vec3(0.2, 0.4, 0.6),
        spread.offset,
        "a spread scales and doesn't turn",
    );
}

#[test]
fn a_seed_gives_kotlins_points() {
    let effect = effect(
        2,
        json!({ "a": { "particle": "flame", "burst": 2, "shape": { "type": "sphere", "radius": 1 } } }),
        false,
    );
    let points: Vec<Vec3> = EffectSampler::new(effect.clone(), 42)
        .step(false)
        .iter()
        .map(|s| s.position)
        .collect();
    // Format's literal numbers, which the JVM and JS agree on.
    close(
        Vec3(
            0.44769385706171666,
            -0.3540992878138615,
            -0.30443003637016897,
        ),
        points[0],
        "point 0",
    );
    close(
        Vec3(0.11519145330098937, 0.7903864069309029, 0.2599122264879563),
        points[1],
        "point 1",
    );
    let again: Vec<Vec3> = EffectSampler::new(effect, 42)
        .step(false)
        .iter()
        .map(|s| s.position)
        .collect();
    assert_eq!(points, again, "a replay draws the same points");
}

#[test]
fn redefine_keeps_the_tick_and_same_named_carries() {
    let rate = json!({ "particle": "flame", "rate": 0.5 });
    let before = effect(10, json!({ "a": rate, "b": rate }), false);
    let mut sampler = EffectSampler::new(before.clone(), 1);
    sampler.step(false); // a and b each hold 0.5
    sampler.redefine(effect(10, json!({ "a": rate, "c": rate }), false));
    assert_eq!(sampler.tick(), 1);
    // a reaches 1.0 and spawns; c starts from nothing.
    let emitters: Vec<String> = sampler.step(false).into_iter().map(|s| s.emitter).collect();
    assert_eq!(emitters, ["a"]);
    // A shorter timeline under an effect that doesn't loop finishes it; a looping one wraps.
    for _ in 0..5 {
        sampler.step(false);
    }
    sampler.redefine(effect(
        4,
        json!({ "a": { "particle": "flame", "burst": 1, "every": 1 } }),
        false,
    ));
    assert!(sampler.step(false).is_empty());
    assert!(sampler.finished());
    let mut looping = EffectSampler::new(before, 1);
    for _ in 0..6 {
        looping.step(true);
    }
    looping.redefine(effect(
        4,
        json!({ "a": { "particle": "flame", "burst": 1, "start": 2 } }),
        false,
    ));
    assert_eq!(looping.step(true).len(), 1, "tick 6 wraps to tick 2");
    assert_eq!(looping.tick(), 3);
}
