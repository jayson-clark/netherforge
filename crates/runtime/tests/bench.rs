//! What a tick costs the runtime on a busy project, against the fake Platform: Kosmoi's `entities` baseline (200
//! walking centities, each with two `tick` handlers and three displays, and 100 crates knocked about by physics).
//! The fake's calls are cheap Rust, so what crossing to Java costs isn't in it; scripts, events, handles, Luau's
//! collector and physics are. Ignored: run it in release, alone, to time the tick or to profile it:
//!
//!     cargo test --release -p netherforge-runtime --test bench -- --ignored --nocapture
//!
//! `BENCH_TICKS` (default 600) sets how many ticks are timed, after 200 to settle.

mod support;
use support::*;

const WALKER: &str = r#"{
  "nodes": {
    "body": {
      "transform": { "translation": [-0.3, 0, -0.3], "scale": [0.6, 0.6, 0.6] },
      "display": { "type": "block", "block": "minecraft:oak_planks" },
      "hitbox": {}
    },
    "head": {
      "parent": "body",
      "transform": { "translation": [0.5, 1.4, 0.5] },
      "display": { "type": "item", "item": "minecraft:carved_pumpkin", "itemTransform": "fixed" }
    },
    "label": {
      "parent": "head",
      "transform": { "translation": [0, 0.8, 0] },
      "display": { "type": "text", "text": "walker" }
    }
  },
  "body": { "width": 0.6, "height": 1.2 },
  "script": { "file": "script.lua" }
}"#;

const WALKER_SCRIPT: &str = r#"
local this = this --[[@as Centity]]
local head = this:node("head")
this:on("tick", function()
	head:rotate(vec3.up, 9)
end)
this:on("tick", function()
	local angle = math.random() * 2 * math.pi
	local heading = vec3(math.cos(angle), 0, math.sin(angle))
	this:set_walk_velocity(heading * 2)
	this:look_at(this:position() + heading)
end, { every = 40 })
"#;

const CRATE: &str = r#"{
  "nodes": {
    "crate": {
      "transform": { "scale": [0.8, 0.8, 0.8] },
      "display": { "type": "block", "block": "minecraft:barrel" },
      "hitbox": {},
      "physics": { "bounciness": 0.3, "friction": 0.6 }
    }
  },
  "script": { "file": "script.lua" }
}"#;

const CRATE_SCRIPT: &str = r#"
local this = this --[[@as Centity]]
local crate = this:node("crate")
nf.after(math.random(1, 60), function()
	this:on("tick", function()
		crate:add_velocity(vec3(math.random() * 4 - 2, 6, math.random() * 4 - 2))
	end, { every = 60 })
end)
"#;

// Inside x 10, the fake's wall; on its floor at y 64.
const SPAWNER: &str = r#"
local function somewhere(up)
	return vector.create(math.random(-27, 8) + 0.5, 64 + up, math.random(-27, 27) + 0.5)
end
for _ = 1, 200 do
	nf.centities.spawn("walker", somewhere(0), { temporary = true })
end
for _ = 1, 100 do
	nf.centities.spawn("crate", somewhere(2), { temporary = true })
end
print("spawned")
"#;

#[test]
#[ignore = "a benchmark: run it in release, alone"]
fn the_entities_baseline_tick() {
    let platform = std::rc::Rc::new(Fake::default());
    // A flat world's ground: four layers.
    platform.floor.set(60);
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/walker/centity.json", WALKER),
        ("centities/walker/script.lua", WALKER_SCRIPT),
        ("centities/crate/centity.json", CRATE),
        ("centities/crate/script.lua", CRATE_SCRIPT),
        ("modules/arena/init.lua", SPAWNER),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert!(start.iter().any(|l| l.contains("] spawned")), "{start:?}");
    for _ in 0..200 {
        session.tick();
        platform.take();
    }
    let ticks: u32 = std::env::var("BENCH_TICKS")
        .ok()
        .and_then(|t| t.parse().ok())
        .unwrap_or(600);
    let (wall, cpu) = (std::time::Instant::now(), thread_cpu());
    for _ in 0..ticks {
        session.tick();
        platform.take();
    }
    let cpu = (thread_cpu() - cpu) / ticks;
    let wall = wall.elapsed() / ticks;
    // The thread's own CPU time, which a busy machine (other builds, other tests) inflates far less than the wall's.
    eprintln!("entities baseline: {cpu:?} of CPU a tick ({wall:?} by the wall) over {ticks} ticks");
}

/// This thread's CPU time so far.
fn thread_cpu() -> std::time::Duration {
    #[repr(C)]
    struct Timespec {
        sec: i64,
        nsec: i64,
    }
    unsafe extern "C" {
        fn clock_gettime(clock: i32, ts: *mut Timespec) -> i32;
    }
    // CLOCK_THREAD_CPUTIME_ID: 16 on macOS, 3 on Linux.
    let clock = if cfg!(target_os = "macos") { 16 } else { 3 };
    let mut ts = Timespec { sec: 0, nsec: 0 };
    // SAFETY: `ts` is a valid timespec for the call to fill.
    assert_eq!(unsafe { clock_gettime(clock, &mut ts) }, 0);
    std::time::Duration::new(ts.sec as u64, ts.nsec as u32)
}
