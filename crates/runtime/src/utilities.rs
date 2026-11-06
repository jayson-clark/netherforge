//! The small namespaces scripts use most: `nf.server`, `nf.players`, `Sender`, `nf.math`, `nf.json`,
//! `nf.random` and `Random`, `nf.time`, `nf.text` (Kotlin's `NfServerImpl`, `NfPlayersImpl`, `SenderImpl`,
//! `NfJsonImpl`, `NfRandomImpl`, `NfTimeImpl`, `NfTextImpl`, and the prelude's `utilities.lua` for the
//! `impl: "lua"` ones).
//!
//! `Random` is the prelude's xoshiro256** (`netherforge_luau::random`), so a seed draws the numbers it drew; a
//! generator's state is the session's, kept until it ends (the prelude's were collected with the handle).
//! Noise is Kotlin's simplex noise over Java's `SplittableRandom`, so a seed is the same pattern.
//!
//! Not yet: `nf.time.format` and `parse` (Java's pattern language), `nf.text.glyph` and `space` (the built
//! resource pack).

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use mlua::{Table, Value};
use netherforge_api::api::{
    NfJsonApi, NfMathApi, NfPlayersApi, NfRandomApi, NfServerApi, NfTextApi, NfTimeApi, RandomApi,
    SenderApi,
};
use netherforge_api::handles;
use netherforge_api::{ApiError, ApiResult, Caller, shapes, values};
use netherforge_luau::random::Random;
use netherforge_platform::PlayerRef;

use crate::Scripts;
use crate::events::{parse_uuid, uuid_text};
use crate::shared::Shared;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The console's sender id.
pub const CONSOLE_SENDER: &str = "console";

fn player_handle(player: &PlayerRef) -> handles::Player {
    handles::Player {
        id: uuid_text(&player.uuid),
    }
}

/// A Lua number's text as Lua writes it (`2`, `2.5`).
fn number_text(n: f64) -> String {
    if n == n.floor() && n.abs() < 1e15 {
        format!("{}", n as i64)
    } else {
        format!("{n}")
    }
}

/// The generators `nf.random.new` made, by handle id.
#[derive(Default)]
pub struct Randoms {
    states: HashMap<i64, Random>,
    made: i64,
}

#[derive(Clone)]
pub struct UtilitiesContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
    pub randoms: Rc<RefCell<Randoms>>,
}

impl NfServerApi for UtilitiesContext {
    fn tick(&self, _: &Caller) -> ApiResult<i64> {
        Ok(self.scripts.tick_count() as i64)
    }
    fn unix_time(&self, _: &Caller) -> ApiResult<i64> {
        Ok(self.shared.schedules.borrow().now().timestamp_millis())
    }
    fn ticks_per_second(&self, _: &Caller) -> ApiResult<f64> {
        Ok(self.shared.platform.performance().ticks_per_second())
    }
    fn tick_milliseconds(&self, _: &Caller) -> ApiResult<f64> {
        Ok(self.shared.platform.performance().tick_milliseconds())
    }
    fn minecraft_version(&self, _: &Caller) -> ApiResult<String> {
        Ok(self.shared.platform.info().minecraft_version)
    }
    fn broadcast(&self, _: &Caller, text: values::Text) -> ApiResult<()> {
        self.shared.platform.players().broadcast(text.0);
        Ok(())
    }
    fn run(&self, _: &Caller, command: String) -> ApiResult<bool> {
        let line = command.strip_prefix('/').unwrap_or(&command).to_owned();
        Ok(self.shared.platform.commands().run_console(line))
    }
    fn max_players(&self, _: &Caller) -> ApiResult<i64> {
        Ok(i64::from(self.shared.platform.server_admin().max_players()))
    }
    fn set_max_players(&self, _: &Caller, count: i64) -> ApiResult<()> {
        if !(0..=i64::from(i32::MAX)).contains(&count) {
            return Err(err(format!("count must be at least 0, not {count}")));
        }
        self.shared
            .platform
            .server_admin()
            .set_max_players(count as i32);
        Ok(())
    }
    fn motd(&self, _: &Caller) -> ApiResult<values::Text> {
        Ok(values::Text(self.shared.platform.server_admin().motd()))
    }
    fn set_motd(&self, _: &Caller, text: values::Text) -> ApiResult<()> {
        self.shared.platform.server_admin().set_motd(text.0);
        Ok(())
    }
    fn is_whitelist_enabled(&self, _: &Caller) -> ApiResult<bool> {
        Ok(self.shared.platform.server_admin().is_whitelist_enabled())
    }
    fn set_whitelist_enabled(&self, _: &Caller, enabled: bool) -> ApiResult<()> {
        self.shared
            .platform
            .server_admin()
            .set_whitelist_enabled(enabled);
        Ok(())
    }
}

impl NfPlayersApi for UtilitiesContext {
    fn online(&self, _: &Caller) -> ApiResult<Vec<handles::Player>> {
        Ok(self
            .shared
            .platform
            .players()
            .online()
            .iter()
            .map(player_handle)
            .collect())
    }
    fn get(&self, _: &Caller, name_or_id: String) -> ApiResult<Option<handles::Player>> {
        Ok(self
            .shared
            .platform
            .players()
            .known(name_or_id)
            .as_ref()
            .map(player_handle))
    }
    fn known(&self, _: &Caller) -> ApiResult<Vec<handles::Player>> {
        Ok(self
            .shared
            .platform
            .server_admin()
            .known()
            .iter()
            .map(player_handle)
            .collect())
    }
    fn banned(&self, _: &Caller) -> ApiResult<Vec<handles::Player>> {
        Ok(self
            .shared
            .platform
            .server_admin()
            .banned()
            .iter()
            .map(player_handle)
            .collect())
    }
    fn whitelisted(&self, _: &Caller) -> ApiResult<Vec<handles::Player>> {
        Ok(self
            .shared
            .platform
            .server_admin()
            .whitelisted()
            .iter()
            .map(player_handle)
            .collect())
    }
}

impl SenderApi for UtilitiesContext {
    fn name(&self, this: &handles::Sender) -> ApiResult<String> {
        if this.id == CONSOLE_SENDER {
            return Ok("CONSOLE".into());
        }
        Ok(self
            .shared
            .platform
            .players()
            .known(this.id.clone())
            .map_or_else(|| this.id.clone(), |p| p.name))
    }
    fn send_message(&self, this: &handles::Sender, text: values::Text) -> ApiResult<bool> {
        let players = self.shared.platform.players();
        if this.id == CONSOLE_SENDER {
            players.message_console(text.0);
            return Ok(true);
        }
        Ok(parse_uuid(&this.id).is_some_and(|id| players.message(id, text.0)))
    }
    fn has_permission(&self, this: &handles::Sender, permission: String) -> ApiResult<bool> {
        if this.id == CONSOLE_SENDER {
            return Ok(true);
        }
        Ok(parse_uuid(&this.id).is_some_and(|id| {
            self.shared
                .platform
                .players()
                .has_permission(id, permission)
        }))
    }
    fn is_player(&self, this: &handles::Sender) -> ApiResult<bool> {
        Ok(this.id != CONSOLE_SENDER)
    }
    fn is_console(&self, this: &handles::Sender) -> ApiResult<bool> {
        Ok(this.id == CONSOLE_SENDER)
    }
}

impl NfMathApi for UtilitiesContext {
    fn lerp(&self, _: &Caller, from: f64, to: f64, fraction: f64) -> ApiResult<f64> {
        Ok(from + (to - from) * fraction)
    }
    fn clamp(&self, _: &Caller, value: f64, min: f64, max: f64) -> ApiResult<f64> {
        if min > max {
            return Err(err(format!(
                "bad argument 'min' ({} is greater than max, {})",
                number_text(min),
                number_text(max)
            )));
        }
        Ok(value.clamp(min, max))
    }
    fn remap(
        &self,
        _: &Caller,
        value: f64,
        from_min: f64,
        from_max: f64,
        to_min: f64,
        to_max: f64,
    ) -> ApiResult<f64> {
        if from_min == from_max {
            return Err(err(format!(
                "bad argument 'from_max' (the range from {} to itself has no size)",
                number_text(from_min)
            )));
        }
        Ok(to_min + (value - from_min) * (to_max - to_min) / (from_max - from_min))
    }
    /// The curves keyframes use, except that `step` reaches 1 at the end.
    fn ease(&self, _: &Caller, easing: String, fraction: f64) -> ApiResult<f64> {
        let t = fraction.clamp(0.0, 1.0);
        Ok(match easing.as_str() {
            "step" => {
                if t >= 1.0 {
                    1.0
                } else {
                    0.0
                }
            }
            "ease_in" => t * t,
            "ease_out" => 1.0 - (1.0 - t) * (1.0 - t),
            "ease_in_out" => {
                if t < 0.5 {
                    2.0 * t * t
                } else {
                    1.0 - 2.0 * (1.0 - t) * (1.0 - t)
                }
            }
            _ => t,
        })
    }
}

impl NfJsonApi for UtilitiesContext {
    /// What JSON can't hold is the script's mistake.
    fn encode(&self, _: &Caller, value: Value) -> ApiResult<String> {
        let host = self.scripts.host();
        let encoded = netherforge_luau::json::encode(
            &host,
            &value,
            "value",
            "encoded",
            &netherforge_api::handle::SavedHandles,
        )
        .map_err(|e| err(format!("{e:?}")))?;
        if let Some(problem) = encoded.problems.into_iter().next() {
            return Err(err(format!("bad argument 'value' ({problem})")));
        }
        encoded
            .text
            .ok_or_else(|| err("bad argument 'value' (it can't be encoded)"))
    }
    /// Text that isn't JSON is a condition (it may come from a player or a file): nil.
    fn decode(&self, _: &Caller, text: String) -> ApiResult<Value> {
        let host = self.scripts.host();
        Ok(netherforge_luau::json::decode(
            host.lua(),
            &text,
            &netherforge_api::handle::SavedHandles,
        )
        .unwrap_or(Value::Nil))
    }
}

impl UtilitiesContext {
    fn with_random<R>(
        &self,
        this: &handles::Random,
        f: impl FnOnce(&mut Random) -> R,
    ) -> ApiResult<R> {
        let mut randoms = self.randoms.borrow_mut();
        let state = randoms
            .states
            .get_mut(&this.id)
            .ok_or_else(|| err("this Random is from a session that has ended"))?;
        Ok(f(state))
    }

    /// The cost of `instructions` of work, charged to the calling script.
    fn afford(&self, instructions: u64) -> ApiResult<()> {
        match self.scripts.host().charge(instructions) {
            Some(stop) => Err(err(stop.to_string())),
            None => Ok(()),
        }
    }
}

/// What a sort compares a weighted table's keys by: numbers, then strings, then booleans, then the rest.
fn key_before(a: &Value, b: &Value) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    fn rank(v: &Value) -> u8 {
        match v {
            Value::Integer(_) | Value::Number(_) => 1,
            Value::String(_) => 2,
            Value::Boolean(_) => 3,
            _ => 4,
        }
    }
    match (a, b) {
        (Value::String(x), Value::String(y)) => x.as_bytes().cmp(&y.as_bytes()),
        (Value::Boolean(x), Value::Boolean(y)) => x.cmp(y),
        _ if rank(a) == 1 && rank(b) == 1 => a
            .as_f64()
            .unwrap_or(0.0)
            .partial_cmp(&b.as_f64().unwrap_or(0.0))
            .unwrap_or(Ordering::Equal),
        _ if rank(a) != rank(b) => rank(a).cmp(&rank(b)),
        _ => a.type_name().cmp(b.type_name()).then_with(|| {
            let text = |v: &Value| v.to_string().unwrap_or_default();
            text(a).cmp(&text(b))
        }),
    }
}

const PER_ELEMENT: u64 = 12;
const PER_COMPARE: f64 = 20.0;

impl RandomApi for UtilitiesContext {
    fn integer(&self, this: &handles::Random, min: i64, max: i64) -> ApiResult<i64> {
        if min > max {
            return Err(err(format!(
                "bad argument 'min' ({min} is greater than max, {max})"
            )));
        }
        self.with_random(this, |r| r.integer(min, max))
    }
    fn number(&self, this: &handles::Random, min: Option<f64>, max: Option<f64>) -> ApiResult<f64> {
        match (min, max) {
            (None, None) => self.with_random(this, Random::fraction),
            (Some(min), Some(max)) => self.with_random(this, |r| min + r.fraction() * (max - min)),
            (None, Some(_)) => Err(err("bad argument 'min' (number expected, got nil)")),
            (Some(_), None) => Err(err("bad argument 'max' (number expected, got nil)")),
        }
    }
    fn pick(&self, this: &handles::Random, list: Value) -> ApiResult<Value> {
        let Value::Table(list) = list else {
            return Err(err("bad argument 'list' (table expected)"));
        };
        let n = list.raw_len();
        if n == 0 {
            return Ok(Value::Nil);
        }
        let i = self.with_random(this, |r| 1 + r.up_to(n as u64 - 1))?;
        list.raw_get(i).map_err(|e| err(e.to_string()))
    }
    fn shuffle(&self, this: &handles::Random, list: Value) -> ApiResult<()> {
        let Value::Table(list) = list else {
            return Err(err("bad argument 'list' (table expected)"));
        };
        let n = list.raw_len();
        if n > 64 {
            self.afford(n as u64 * PER_ELEMENT)?;
        }
        let mut i = n;
        while i >= 2 {
            let j = self.with_random(this, |r| 1 + r.up_to(i as u64 - 1))? as usize;
            let (a, b): (Value, Value) = (
                list.raw_get(i).map_err(|e| err(e.to_string()))?,
                list.raw_get(j).map_err(|e| err(e.to_string()))?,
            );
            list.raw_set(i, b).map_err(|e| err(e.to_string()))?;
            list.raw_set(j, a).map_err(|e| err(e.to_string()))?;
            i -= 1;
        }
        Ok(())
    }
    fn weighted(&self, this: &handles::Random, weights: Value) -> ApiResult<Value> {
        let Value::Table(weights) = weights else {
            return Err(err("bad argument 'weights' (table expected)"));
        };
        let (keys, total) = weighted_keys(self, &weights)?;
        if keys.is_empty() {
            return Ok(Value::Nil);
        }
        let mut r = self.with_random(this, Random::fraction)? * total;
        for (key, weight) in &keys[..keys.len() - 1] {
            r -= weight;
            if r < 0.0 {
                return Ok(key.clone());
            }
        }
        Ok(keys[keys.len() - 1].0.clone())
    }
}

/// A weighted table's keys with a weight above 0, sorted, and their total; a weight that isn't a number from
/// 0 up is an error.
fn weighted_keys(
    context: &UtilitiesContext,
    weights: &Table,
) -> ApiResult<(Vec<(Value, f64)>, f64)> {
    let mut keys = Vec::new();
    let (mut total, mut seen) = (0.0, 0u64);
    for pair in weights.pairs::<Value, Value>() {
        let (key, weight) = pair.map_err(|e| err(e.to_string()))?;
        seen += 1;
        if seen % 4096 == 0 {
            context.afford(4096 * PER_ELEMENT)?;
        }
        let text = |v: &Value| match v {
            Value::Number(n) => number_text(*n),
            Value::Integer(i) => i.to_string(),
            other => other
                .to_string()
                .unwrap_or_else(|_| other.type_name().to_owned()),
        };
        let w = match weight {
            Value::Number(n) if (0.0..f64::INFINITY).contains(&n) => n,
            Value::Integer(i) if i >= 0 => i as f64,
            ref other => {
                return Err(err(format!(
                    "bad argument 'weights' (the weight of {} must be a number from 0 up, not {})",
                    text(&key),
                    text(other)
                )));
            }
        };
        if w > 0.0 {
            keys.push((key, w));
            total += w;
        }
    }
    if keys.len() > 64 {
        let n = keys.len() as f64;
        context.afford((n * n.log2() * PER_COMPARE) as u64)?;
    }
    keys.sort_by(|a, b| key_before(&a.0, &b.0));
    Ok((keys, total))
}

impl NfRandomApi for UtilitiesContext {
    fn new(&self, _: &Caller, seed: Option<i64>) -> ApiResult<handles::Random> {
        let seed = seed.unwrap_or_else(|| {
            let mut bytes = [0u8; 8];
            getrandom::fill(&mut bytes).expect("the system has randomness");
            i64::from_le_bytes(bytes)
        });
        let mut randoms = self.randoms.borrow_mut();
        randoms.made += 1;
        let id = randoms.made;
        randoms.states.insert(id, Random::new(seed));
        Ok(handles::Random { id })
    }
    fn noise2(
        &self,
        _: &Caller,
        x: f64,
        z: f64,
        options: Option<shapes::NoiseOptions>,
    ) -> ApiResult<f64> {
        let (seed, scale) = noise_options(options.as_ref())?;
        Ok(SimplexNoise::new(seed).noise2(finite(x, "x")? * scale, finite(z, "z")? * scale))
    }
    fn noise3(
        &self,
        _: &Caller,
        x: f64,
        y: f64,
        z: f64,
        options: Option<shapes::NoiseOptions>,
    ) -> ApiResult<f64> {
        let (seed, scale) = noise_options(options.as_ref())?;
        Ok(SimplexNoise::new(seed).noise3(
            finite(x, "x")? * scale,
            finite(y, "y")? * scale,
            finite(z, "z")? * scale,
        ))
    }
    fn uuid(&self, _: &Caller) -> ApiResult<String> {
        Ok(uuid_text(&crate::centity::saving::random_uuid()))
    }
}

fn finite(value: f64, name: &str) -> ApiResult<f64> {
    if !value.is_finite() {
        return Err(err(format!("{name} must be a finite number, not {value}")));
    }
    Ok(value)
}

fn noise_options(options: Option<&shapes::NoiseOptions>) -> ApiResult<(i64, f64)> {
    let scale = finite(
        options.and_then(|o| o.scale).unwrap_or(1.0),
        "options.scale",
    )?;
    Ok((options.and_then(|o| o.seed).unwrap_or(0), scale))
}

/// Java's `SplittableRandom`, as far as a permutation's shuffle needs: its `nextInt(bound)`, bit for bit.
struct SplittableRandom {
    seed: i64,
}

const GOLDEN_GAMMA: i64 = 0x9e37_79b9_7f4a_7c15_u64 as i64;

impl SplittableRandom {
    fn next_seed(&mut self) -> i64 {
        self.seed = self.seed.wrapping_add(GOLDEN_GAMMA);
        self.seed
    }

    fn mix32(z: i64) -> i32 {
        let z = (z ^ ((z as u64) >> 33) as i64).wrapping_mul(0x62a9_d9ed_7997_05f5_u64 as i64);
        ((((z ^ ((z as u64) >> 28) as i64).wrapping_mul(0xcb24_d0a5_c88c_35b3_u64 as i64)) as u64)
            >> 32) as i32
    }

    fn next_int(&mut self, bound: i32) -> i32 {
        let mut r = Self::mix32(self.next_seed());
        let m = bound - 1;
        if bound & m == 0 {
            return r & m;
        }
        let mut u = ((r as u32) >> 1) as i32;
        loop {
            r = u % bound;
            if u.wrapping_add(m).wrapping_sub(r) >= 0 {
                return r;
            }
            u = ((Self::mix32(self.next_seed()) as u32) >> 1) as i32;
        }
    }
}

/// Simplex noise in 2D and 3D (after Stefan Gustavson's public-domain implementation), over a permutation a
/// seed shuffles: Kotlin's `SimplexNoise`, the same pattern for the same seed.
struct SimplexNoise {
    perm: [usize; 512],
    perm_mod12: [usize; 512],
}

const GRADIENTS: [[f64; 3]; 12] = [
    [1.0, 1.0, 0.0],
    [-1.0, 1.0, 0.0],
    [1.0, -1.0, 0.0],
    [-1.0, -1.0, 0.0],
    [1.0, 0.0, 1.0],
    [-1.0, 0.0, 1.0],
    [1.0, 0.0, -1.0],
    [-1.0, 0.0, -1.0],
    [0.0, 1.0, 1.0],
    [0.0, -1.0, 1.0],
    [0.0, 1.0, -1.0],
    [0.0, -1.0, -1.0],
];

/// A lattice coordinate's place in the permutation, through an i64 so coordinates past i32's range still wrap.
fn wrap(cell: f64) -> usize {
    ((cell as i64) & 255) as usize
}

impl SimplexNoise {
    fn new(seed: i64) -> SimplexNoise {
        let mut values: [i32; 256] = std::array::from_fn(|i| i as i32);
        let mut random = SplittableRandom { seed };
        for i in (1..=255).rev() {
            let j = random.next_int(i as i32 + 1) as usize;
            values.swap(i, j);
        }
        let perm: [usize; 512] = std::array::from_fn(|i| values[i & 255] as usize);
        let perm_mod12 = std::array::from_fn(|i| perm[i] % 12);
        SimplexNoise { perm, perm_mod12 }
    }

    fn corner2(gradient: usize, x: f64, y: f64) -> f64 {
        let mut t = 0.5 - x * x - y * y;
        if t < 0.0 {
            return 0.0;
        }
        t *= t;
        t * t * (GRADIENTS[gradient][0] * x + GRADIENTS[gradient][1] * y)
    }

    fn corner3(gradient: usize, x: f64, y: f64, z: f64) -> f64 {
        let mut t = 0.6 - x * x - y * y - z * z;
        if t < 0.0 {
            return 0.0;
        }
        t *= t;
        let g = GRADIENTS[gradient];
        t * t * (g[0] * x + g[1] * y + g[2] * z)
    }

    fn noise2(&self, xin: f64, yin: f64) -> f64 {
        let f2 = 0.5 * (3f64.sqrt() - 1.0);
        let g2 = (3.0 - 3f64.sqrt()) / 6.0;
        let s = (xin + yin) * f2;
        let (i, j) = ((xin + s).floor(), (yin + s).floor());
        let t = (i + j) * g2;
        let (x0, y0) = (xin - (i - t), yin - (j - t));
        let (i1, j1) = if x0 > y0 { (1, 0) } else { (0, 1) };
        let (x1, y1) = (x0 - f64::from(i1) + g2, y0 - f64::from(j1) + g2);
        let (x2, y2) = (x0 - 1.0 + 2.0 * g2, y0 - 1.0 + 2.0 * g2);
        let (ii, jj) = (wrap(i), wrap(j));
        let p = &self.perm;
        let n0 = Self::corner2(self.perm_mod12[ii + p[jj]], x0, y0);
        let n1 = Self::corner2(
            self.perm_mod12[ii + i1 as usize + p[jj + j1 as usize]],
            x1,
            y1,
        );
        let n2 = Self::corner2(self.perm_mod12[ii + 1 + p[jj + 1]], x2, y2);
        (70.0 * (n0 + n1 + n2)).clamp(-1.0, 1.0)
    }

    fn noise3(&self, xin: f64, yin: f64, zin: f64) -> f64 {
        let (f3, g3) = (1.0 / 3.0, 1.0 / 6.0);
        let s = (xin + yin + zin) * f3;
        let (i, j, k) = ((xin + s).floor(), (yin + s).floor(), (zin + s).floor());
        let t = (i + j + k) * g3;
        let (x0, y0, z0) = (xin - (i - t), yin - (j - t), zin - (k - t));
        // Which of the six tetrahedra in the cube the point is in: the order of x0, y0 and z0.
        let c: [usize; 6] = if x0 >= y0 && y0 >= z0 {
            [1, 0, 0, 1, 1, 0]
        } else if x0 >= y0 && x0 >= z0 {
            [1, 0, 0, 1, 0, 1]
        } else if x0 >= y0 {
            [0, 0, 1, 1, 0, 1]
        } else if y0 < z0 {
            [0, 0, 1, 0, 1, 1]
        } else if x0 < z0 {
            [0, 1, 0, 0, 1, 1]
        } else {
            [0, 1, 0, 1, 1, 0]
        };
        let f = |v: usize| v as f64;
        let (x1, y1, z1) = (x0 - f(c[0]) + g3, y0 - f(c[1]) + g3, z0 - f(c[2]) + g3);
        let (x2, y2, z2) = (
            x0 - f(c[3]) + 2.0 * g3,
            y0 - f(c[4]) + 2.0 * g3,
            z0 - f(c[5]) + 2.0 * g3,
        );
        let (x3, y3, z3) = (
            x0 - 1.0 + 3.0 * g3,
            y0 - 1.0 + 3.0 * g3,
            z0 - 1.0 + 3.0 * g3,
        );
        let (ii, jj, kk) = (wrap(i), wrap(j), wrap(k));
        let p = &self.perm;
        let m = &self.perm_mod12;
        let n0 = Self::corner3(m[ii + p[jj + p[kk]]], x0, y0, z0);
        let n1 = Self::corner3(m[ii + c[0] + p[jj + c[1] + p[kk + c[2]]]], x1, y1, z1);
        let n2 = Self::corner3(m[ii + c[3] + p[jj + c[4] + p[kk + c[5]]]], x2, y2, z2);
        let n3 = Self::corner3(m[ii + 1 + p[jj + 1 + p[kk + 1]]], x3, y3, z3);
        (32.0 * (n0 + n1 + n2 + n3)).clamp(-1.0, 1.0)
    }
}

/// A pattern for `nf.time`, or the error a script sees.
fn time_pattern(pattern: &str) -> ApiResult<crate::java_time::Pattern> {
    crate::java_time::Pattern::compile(pattern)
        .map_err(|e| ApiError(format!("bad time pattern \"{pattern}\": {e}")))
}

impl UtilitiesContext {
    /// The zone `options` names, or the owner's (`schedules.time-zone`), as schedules' are.
    fn zone(&self, options: Option<shapes::TimeOptions>) -> ApiResult<crate::schedule::Zone> {
        match options.and_then(|o| o.zone) {
            None => Ok(self.shared.schedules.borrow().zone.clone()),
            Some(name) => crate::schedule::Zone::parse(&name).ok_or_else(|| {
                ApiError(format!(
                    "options.zone: there's no time zone \"{name}\" (try \"UTC\", \"Europe/London\" or \"+02:00\")"
                ))
            }),
        }
    }
}

/// Whole seconds as `1d 2h 3m 4s`, as `nf.time.duration` writes them.
const DURATION_UNITS: [(&str, i64); 4] = [("d", 86_400), ("h", 3_600), ("m", 60), ("s", 1)];

impl NfTimeApi for UtilitiesContext {
    fn format(
        &self,
        _: &Caller,
        time: i64,
        pattern: String,
        options: Option<shapes::TimeOptions>,
    ) -> ApiResult<String> {
        let zone = self.zone(options)?;
        let compiled = time_pattern(&pattern)?;
        let at = chrono::DateTime::from_timestamp_millis(time).ok_or_else(|| {
            ApiError(format!(
                "can't write {time} as \"{pattern}\": it's outside the years a clock time can have"
            ))
        })?;
        Ok(compiled.format(at, &zone))
    }
    fn parse(
        &self,
        _: &Caller,
        text: String,
        pattern: String,
        options: Option<shapes::TimeOptions>,
    ) -> ApiResult<Option<i64>> {
        let compiled = time_pattern(&pattern)?;
        let zone = self.zone(options)?;
        if !compiled.has_date() {
            return Err(ApiError(format!(
                "the pattern \"{pattern}\" needs a year, month and day"
            )));
        }
        Ok(compiled.parse(&text, &zone))
    }
    fn duration(&self, _: &Caller, milliseconds: i64) -> ApiResult<String> {
        // Whole seconds, towards zero.
        let seconds = milliseconds / 1000;
        let mut left = seconds.unsigned_abs() as i64;
        let mut parts = Vec::new();
        for (unit, size) in DURATION_UNITS {
            if left >= size {
                parts.push(format!("{}{unit}", left / size));
                left %= size;
            }
        }
        if parts.is_empty() {
            return Ok("0s".into());
        }
        Ok(format!(
            "{}{}",
            if seconds < 0 { "-" } else { "" },
            parts.join(" ")
        ))
    }
    /// `1h 30m`, `250ms`, `1.5d`: none for anything else, or a unit twice.
    fn parse_duration(&self, _: &Caller, text: String) -> ApiResult<Option<i64>> {
        let mut rest = text.trim();
        if rest.is_empty() {
            return Ok(None);
        }
        let mut seen = Vec::new();
        let mut total = 0f64;
        while !rest.is_empty() {
            let digits = rest
                .find(|c: char| !(c.is_ascii_digit() || c == '.'))
                .unwrap_or(rest.len());
            let amount = &rest[..digits];
            let valid = !amount.is_empty()
                && !amount.starts_with('.')
                && !amount.ends_with('.')
                && amount.matches('.').count() <= 1;
            if !valid {
                return Ok(None);
            }
            rest = rest[digits..].trim_start();
            // `ms` before `m`, so "250ms" isn't 250 minutes and a stray "s".
            let Some((unit, size)) = [
                ("ms", 1.0),
                ("d", 86_400_000.0),
                ("h", 3_600_000.0),
                ("m", 60_000.0),
                ("s", 1_000.0),
            ]
            .into_iter()
            .find(|(u, _)| rest.starts_with(u)) else {
                return Ok(None);
            };
            if seen.contains(&unit) {
                return Ok(None);
            }
            seen.push(unit);
            total += amount.parse::<f64>().unwrap_or(0.0) * size;
            rest = rest[unit.len()..].trim_start();
        }
        let rounded = total.round();
        Ok((rounded.abs() <= 9.007_199_254_740_991e15).then_some(rounded as i64))
    }
}

impl netherforge_api::api::NfLooksApi for UtilitiesContext {
    fn qualify(&self, _: &Caller, look: String) -> ApiResult<String> {
        let game = crate::world::GameContext::new(self.shared.clone(), self.scripts.clone());
        game.with_names(|names| names.qualify(&look))
    }
}

impl NfTextApi for UtilitiesContext {
    fn escape(&self, _: &Caller, text: String) -> ApiResult<values::Text> {
        Ok(values::Text(self.shared.platform.text().escape(text)))
    }
    fn strip(&self, _: &Caller, text: values::Text) -> ApiResult<String> {
        Ok(self.shared.platform.text().strip(text.0))
    }
    /// The character a glyph of the project's packs (or a package's it may name) is drawn as; nil while the pack
    /// isn't built.
    fn glyph(&self, _: &Caller, glyph: String) -> ApiResult<Option<String>> {
        let game = crate::world::GameContext::new(self.shared.clone(), self.scripts.clone());
        let key = game.with_names(|names| {
            names.look(netherforge_format::reference::RefKind::Glyph, &glyph)
        })?;
        Ok(self.shared.packs.borrow().glyph(&key))
    }
    /// Characters that move the cursor `pixels` (held to what the pack's space font reaches); nil while there's no
    /// built pack to draw them.
    fn space(&self, _: &Caller, pixels: i64) -> ApiResult<Option<String>> {
        if self.shared.packs.borrow().built.is_none() {
            return Ok(None);
        }
        let most = i64::from(netherforge_format::kind::resource_pack::fonts::MAX_OFFSET);
        Ok(Some(
            netherforge_format::kind::resource_pack::fonts::offset(pixels.clamp(-most, most) as i32),
        ))
    }
    /// Its width in the project's default font; none when the project has no font facts.
    fn width(&self, _: &Caller, text: values::Text) -> ApiResult<Option<i64>> {
        Ok(self
            .measure_lines(&text.0)
            .map(|(width, _)| i64::from(width)))
    }
    fn measure(
        &self,
        _: &Caller,
        text: values::Text,
        options: Option<shapes::TextMeasureOptions>,
    ) -> ApiResult<Option<shapes::TextSize>> {
        let scale = options.and_then(|o| o.scale).unwrap_or(1.0);
        if scale.is_nan() || scale <= 0.0 || !scale.is_finite() {
            return Err(err(format!(
                "bad argument 'options.scale' (more than 0, got {scale})"
            )));
        }
        let Some((width, lines)) = self.measure_lines(&text.0) else {
            return Ok(None);
        };
        let size = netherforge_format::kind::resource_pack::display::text_box(width, lines);
        Ok(Some(shapes::TextSize {
            width: size.width() * scale,
            height: size.height() * scale,
        }))
    }
}

impl UtilitiesContext {
    /// `text`'s widest line and its lines, in the project's default font.
    fn measure_lines(&self, text: &str) -> Option<(i32, i32)> {
        self.shared.measure_text(text)
    }
}

/// The session's generators go with it.
pub fn clear(randoms: &RefCell<Randoms>) {
    *randoms.borrow_mut() = Randoms::default();
}

/// Each player's handle as an `nf` value, for other modules.
pub fn handle_of(player: &PlayerRef) -> handles::Player {
    player_handle(player)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Java's own draws, from jshell: `new SplittableRandom(42)` then `nextInt(256)`, `nextInt(100)`,
    /// `nextInt(7)`; and from `-7`, `nextInt(256)`, `nextInt(3)`, `nextInt(255)`.
    #[test]
    fn splittable_random_draws_javas_numbers() {
        let mut r = SplittableRandom { seed: 42 };
        assert_eq!(
            [r.next_int(256), r.next_int(100), r.next_int(7)],
            [78, 56, 6]
        );
        let mut q = SplittableRandom { seed: -7 };
        assert_eq!(
            [q.next_int(256), q.next_int(3), q.next_int(255)],
            [122, 0, 181]
        );
    }

    /// Kotlin's simplex noise, transcribed to Java and run in jshell over Java's own `SplittableRandom`.
    #[test]
    fn noise_is_kotlins_pattern_for_a_seed() {
        let n = SimplexNoise::new(3);
        assert_eq!(n.noise2(0.3, 0.7), 0.5557719187984057);
        assert_eq!(n.noise2(-12.25, 40.5), 0.8406774117333069);
        assert_eq!(n.noise3(1.1, 2.2, -3.3), -0.5474587733333338);
        let n = SimplexNoise::new(0);
        assert_eq!(n.noise2(100.1, -7.9), -0.33952303058063116);
        assert_eq!(n.noise3(0.5, 0.25, 0.125), 0.2594820100996229);
    }
}
