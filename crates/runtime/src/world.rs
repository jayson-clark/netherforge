//! `World` and `nf.worlds` (Kotlin's `WorldImpl` and `NfWorldsImpl`): a world by name, read live through the
//! Platform. Everything answers nil or false once the world has gone (unloaded); a mistake only the server can
//! see (an unknown game rule) is an error. The centity questions (`raycast` against hitboxes, `overlap_*`,
//! `sweep`, `nearest`, `centities`) go to the centities' spatial index.
//!
//! Not yet, each with the area it needs: gradual fills and structures, `locate_biome` (the workers),
//! managed worlds (`create`, `load`, `copy`, `import`, `unload`). `blocks`'
//! `custom` and `flood`'s project blocks wait for custom blocks: until then no block is a project block.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use mlua::{Function, Value};
use netherforge_api::api::{NfCentitiesApi, NfWorldsApi, WorldApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, Pending, shapes, unions, values};
use netherforge_format::canonical::name_of;
use netherforge_format::game::{self, Registry};
use netherforge_format::kind::centity::{DAY_TICKS, DayPeriod, MoonPhase};
use netherforge_format::manifest::SpawnCategory;
use netherforge_format::math::Vec3;
use netherforge_luau::ScopeId;
use netherforge_platform::{
    BlockBoxRead, BoxState, Dynamic, EntityCategory, GameRuleType, Location, Platform,
};

use crate::centity::api::CentityContext;
use crate::centity::spatial::{Found, QueryFilter, QueryShape};
use crate::events::{pack, uuid_text};
use crate::shared::Shared;
use crate::{Scripts, Target};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

fn vec(v: &values::Vec3) -> Vec3 {
    Vec3(v.x, v.y, v.z)
}

fn value(v: Vec3) -> values::Vec3 {
    values::Vec3 {
        x: v.0,
        y: v.1,
        z: v.2,
    }
}

fn length(v: Vec3) -> f64 {
    (v.0 * v.0 + v.1 * v.1 + v.2 * v.2).sqrt()
}

const DAY: i64 = DAY_TICKS as i64;

/// How many blocks one `world:fill_blocks` may change: `FILL_LIMIT` in the spec (`spec/worlds.ts`).
const FILL_LIMIT: i64 = 32768;

/// The most blocks `world:blocks` reads in one call: 32 × 32 × 32, so one call can't stall a tick.
const MAX_BOX: i64 = 32 * 32 * 32;

/// `world:flood`'s limits: what it fills unless told, and the most it ever does.
const FLOOD_DEFAULT: i64 = 4096;
const FLOOD_LIMIT: i64 = 32768;

/// The six neighbours that share a face: `world:flood`'s directions unless told.
const FLOOD_FACES: [[i32; 3]; 6] = [
    [1, 0, 0],
    [-1, 0, 0],
    [0, 1, 0],
    [0, -1, 0],
    [0, 0, 1],
    [0, 0, -1],
];

/// Further out than any world's border, and the y range a packed position holds.
const HORIZONTAL: f64 = 33_554_431.0;

/// The block a point is in (Kotlin's `BlockPosition.of`); a point no world can have is an error.
pub fn block_at(point: &values::Vec3) -> ApiResult<(i32, i32, i32)> {
    let (x, y, z) = (point.x.floor(), point.y.floor(), point.z.floor());
    let inside = |c: f64| (-HORIZONTAL..=HORIZONTAL).contains(&c);
    if !inside(x) || !inside(z) || !(-2048.0..=2047.0).contains(&y) {
        return Err(err(format!(
            "the position ({}, {}, {}) is outside any world",
            point.x, point.y, point.z
        )));
    }
    Ok((x as i32, y as i32, z as i32))
}

/// A block's position from its packed form (Kotlin's `BlockPosition.unpack`).
pub fn unpack(packed: i64) -> (i32, i32, i32) {
    (
        (packed >> 38) as i32,
        ((packed << 52) >> 52) as i32,
        ((packed << 26) >> 38) as i32,
    )
}

fn block_handle(world: &str, x: i32, y: i32, z: i32) -> handles::Block {
    handles::Block {
        world: world.to_owned(),
        position: pack(x, y, z),
    }
}

fn location_value(l: &Location) -> values::Location {
    values::Location {
        world: handles::World {
            name: l.world.clone(),
        },
        position: values::Vec3 {
            x: l.x,
            y: l.y,
            z: l.z,
        },
        yaw: Some(l.yaw),
        pitch: Some(l.pitch),
    }
}

/// A `Location|Vec3` as a place in `world`: a bare position has no facing.
fn place_in(place: unions::LocationOrVec3, world: &str) -> ApiResult<Location> {
    let (target, position, yaw, pitch) = match place {
        unions::LocationOrVec3::Location(l) => (Some(l.world.name), l.position, l.yaw, l.pitch),
        unions::LocationOrVec3::Vec3(v) => (None, v, None, None),
    };
    if let Some(target) = &target
        && target != world
    {
        return Err(err(format!(
            "location_or_position is in {target}, not {world}"
        )));
    }
    Ok(Location {
        world: world.to_owned(),
        x: position.x,
        y: position.y,
        z: position.z,
        yaw: yaw.unwrap_or(0.0),
        pitch: pitch.unwrap_or(0.0),
    })
}

fn finite(v: &values::Vec3, name: &str) -> ApiResult<Vec3> {
    if !(v.x.is_finite() && v.y.is_finite() && v.z.is_finite()) {
        return Err(err(format!(
            "bad argument '{name}' (a finite position expected)"
        )));
    }
    Ok(vec(v))
}

fn non_negative(value: f64, name: &str) -> ApiResult<f64> {
    if value.is_nan() || value < 0.0 || !value.is_finite() {
        return Err(err(format!(
            "bad argument '{name}' (at least 0 expected, got {value})"
        )));
    }
    Ok(value)
}

fn unit(v: &values::Vec3, name: &str) -> ApiResult<Vec3> {
    let v = vec(v);
    let l = length(v);
    if l == 0.0 || !l.is_finite() {
        return Err(err(format!("bad argument '{name}' (it can't be zero)")));
    }
    Ok(v * (1.0 / l))
}

/// The category a script named, already checked against the spec's choices.
fn spawn_category(id: &str) -> SpawnCategory {
    *SpawnCategory::ALL
        .iter()
        .find(|c| name_of(*c) == id)
        .expect("the binding checked the choice")
}

const MOON_PHASES: [MoonPhase; 8] = [
    MoonPhase::FullMoon,
    MoonPhase::WaningGibbous,
    MoonPhase::LastQuarter,
    MoonPhase::WaningCrescent,
    MoonPhase::NewMoon,
    MoonPhase::WaxingCrescent,
    MoonPhase::FirstQuarter,
    MoonPhase::WaxingGibbous,
];

/// Whether a time of day (0 to 23999) is night, as format's `DayPeriod::Night` says.
pub fn is_night(time_of_day: i64) -> bool {
    let (start, end) = DayPeriod::Night.ticks();
    (i64::from(start)..=i64::from(end)).contains(&time_of_day)
}

/// The block id a state names: what's before its properties.
fn id_of(state: &str) -> &str {
    state.split('[').next().unwrap_or(state)
}

/// The classes of the game's own things (`World`, `nf.worlds`, `Block`, entities), for one room.
#[derive(Clone)]
pub struct GameContext {
    pub(crate) shared: Rc<Shared>,
    pub(crate) scripts: Scripts,
}

impl GameContext {
    pub fn new(shared: Rc<Shared>, scripts: Scripts) -> GameContext {
        GameContext { shared, scripts }
    }

    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// `f` with the tree's names as the calling package sees them.
    pub(crate) fn with_names<R>(&self, f: impl FnOnce(&crate::names::Calling) -> R) -> R {
        let from = self.calling_namespace();
        let names = self.shared.names.borrow();
        f(&crate::names::Calling {
            names: &names,
            from,
        })
    }

    pub(crate) fn centities(&self) -> CentityContext {
        CentityContext::new(self.shared.clone(), self.scripts.clone())
    }

    /// The world's name, while the server has it.
    fn live<'w>(&self, this: &'w handles::World) -> Option<&'w str> {
        self.platform()
            .worlds()
            .exists(this.name.clone())
            .then_some(this.name.as_str())
    }

    fn full_time(&self, this: &handles::World) -> Option<i64> {
        let world = self.live(this)?;
        self.platform().worlds().full_time(world.to_owned())
    }

    fn entity(&self, id: netherforge_platform::Uuid) -> handles::Entity {
        self.shared.entity_by_id(self.scripts.host().lua(), id)
    }

    pub(crate) fn running_scope(&self, called: &str) -> ApiResult<ScopeId> {
        self.scripts
            .host()
            .running_scope()
            .ok_or_else(|| err(format!("{called} was called by no script")))
    }

    fn world_target(&self, this: &handles::World) -> ApiResult<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &Handle::World(this.clone()))
            .map(Target::Handle)
            .map_err(|e| err(e.to_string()))
    }

    /// A game rule's namespaced id and type, once the server is known to have it.
    fn game_rule_id(&self, rule: &str) -> ApiResult<(String, GameRuleType)> {
        let id = game::normalize(rule);
        let kind = game::is_id(rule)
            .then(|| self.platform().worlds().game_rule_type(id.clone()))
            .flatten()
            .ok_or_else(|| err(format!("no game rule \"{rule}\" on this server")))?;
        Ok((id, kind))
    }

    /// What a spatial query in `world` finds, as `overlap_*`, `sweep` and `nearest` hand it back; empty once
    /// the world has gone.
    fn query(
        &self,
        this: &handles::World,
        shape: &QueryShape,
        filter: &QueryFilter,
    ) -> Vec<shapes::QueryHit> {
        let Some(world) = self.live(this) else {
            return Vec::new();
        };
        let found = self
            .shared
            .centities
            .borrow_mut()
            .find(self.platform(), world, shape, filter);
        let centities = self.shared.centities.borrow();
        found
            .into_iter()
            .map(|found| match found {
                Found::Centity {
                    instance,
                    node,
                    reach,
                } => {
                    let id = uuid_text(&instance);
                    let name = centities
                        .get(instance)
                        .map(|i| i.definition.nodes[node].name.clone())
                        .unwrap_or_default();
                    shapes::QueryHit {
                        centity: Some(handles::Centity { id: id.clone() }),
                        node: Some(handles::Node { centity: id, name }),
                        entity: None,
                        block: None,
                        position: value(reach.position),
                        distance: reach.distance,
                        normal: None,
                    }
                }
                Found::Entity { id, reach } => shapes::QueryHit {
                    centity: None,
                    node: None,
                    entity: Some(self.entity(id)),
                    block: None,
                    position: value(reach.position),
                    distance: reach.distance,
                    normal: None,
                },
            })
            .collect()
    }

    /// A test of a block's state for each of `names`, as a game block file names blocks: an id, a `#tag`, or
    /// `solid`, `liquid` or `passable`. One that names nothing the server has is an error at `place`.
    fn block_matcher(
        &self,
        names: &[String],
        place: &str,
    ) -> ApiResult<impl Fn(&BoxState) -> bool + use<>> {
        let game = self.platform().game();
        let (mut ids, mut solid, mut liquid, mut passable) = (HashSet::new(), false, false, false);
        for (i, name) in names.iter().enumerate() {
            let at = format!("{place}[{}]", i + 1);
            match name.as_str() {
                "solid" => solid = true,
                "liquid" => liquid = true,
                "passable" => passable = true,
                _ if name.starts_with('#') => {
                    let tag = &name[1..];
                    let members = game::is_id(tag)
                        .then(|| game.tag(Registry::Block.key(), &game::normalize(tag)))
                        .flatten()
                        .ok_or_else(|| {
                            err(format!(
                                "bad argument '{at}' (\"{name}\" isn't a block tag the server has)"
                            ))
                        })?;
                    ids.extend(members.iter().cloned());
                }
                _ => {
                    let id = game::normalize(name);
                    if !game::is_id(name) || game.block_properties(&id).is_none() {
                        return Err(err(format!(
                            "bad argument '{at}' (\"{name}\" isn't a block, a #tag, solid, liquid or passable)"
                        )));
                    }
                    ids.insert(id);
                }
            }
        }
        Ok(move |state: &BoxState| {
            (solid && state.solid)
                || (liquid && state.liquid)
                || (passable && !state.solid && !state.liquid)
                || ids.contains(id_of(&state.state))
        })
    }
}

/// `world:flood`: a breadth-first fill through the blocks `passes` lets through, reading the world a
/// `BRICK`-sided box at a time, so a fill of thousands of blocks is a few reads (Kotlin's `Flood`).
struct Flood<'p, P: Fn(&BoxState) -> bool> {
    platform: &'p dyn Platform,
    world: String,
    bottom: i32,
    top: i32,
    passes: P,
    bricks: HashMap<(i32, i32, i32), Option<BlockBoxRead>>,
    passing: HashMap<String, bool>,
}

const BRICK: i32 = 16;

struct Filled {
    filled: Vec<(i32, i32, i32)>,
    filled_states: Vec<BoxState>,
    edges: Vec<(i32, i32, i32)>,
    edge_states: Vec<BoxState>,
    stopped: Option<&'static str>,
}

impl<P: Fn(&BoxState) -> bool> Flood<'_, P> {
    fn state(&mut self, x: i32, y: i32, z: i32) -> Option<BoxState> {
        if y < self.bottom || y >= self.top {
            return None;
        }
        let (bx, by, bz) = (
            x.div_euclid(BRICK),
            y.div_euclid(BRICK),
            z.div_euclid(BRICK),
        );
        let y0 = (by * BRICK).max(self.bottom);
        let brick = self.bricks.entry((bx, by, bz)).or_insert_with(|| {
            let y1 = (by * BRICK + BRICK).min(self.top) - 1;
            self.platform.blocks().r#box(
                self.world.clone(),
                bx * BRICK,
                y0,
                bz * BRICK,
                bx * BRICK + BRICK - 1,
                y1,
                bz * BRICK + BRICK - 1,
            )
        });
        let read = brick.as_ref()?;
        let (lx, ly, lz) = (x - bx * BRICK, y - y0, z - bz * BRICK);
        let cell = read.cells[(lx + BRICK * (lz + BRICK * ly)) as usize];
        read.states.get(cell as usize).cloned()
    }

    fn fills(&mut self, state: &BoxState) -> bool {
        if let Some(known) = self.passing.get(&state.state) {
            return *known;
        }
        let fills = (self.passes)(state);
        self.passing.insert(state.state.clone(), fills);
        fills
    }

    fn run(
        &mut self,
        start: (i32, i32, i32),
        directions: &[[i32; 3]],
        limit: usize,
        bounds: Option<[i32; 6]>,
    ) -> Filled {
        let mut out = Filled {
            filled: Vec::new(),
            filled_states: Vec::new(),
            edges: Vec::new(),
            edge_states: Vec::new(),
            stopped: None,
        };
        let Some(first) = self.state(start.0, start.1, start.2) else {
            out.stopped = Some("unloaded");
            return out;
        };
        let mut seen = HashSet::from([start]);
        if !self.fills(&first) {
            out.edges.push(start);
            out.edge_states.push(first);
            return out;
        }
        out.filled.push(start);
        out.filled_states.push(first);
        let mut head = 0;
        while head < out.filled.len() {
            let (cx, cy, cz) = out.filled[head];
            head += 1;
            for d in directions {
                let next = (cx + d[0], cy + d[1], cz + d[2]);
                if !seen.insert(next) {
                    continue;
                }
                if let Some(b) = bounds
                    && (next.0 < b[0]
                        || next.1 < b[1]
                        || next.2 < b[2]
                        || next.0 > b[3]
                        || next.1 > b[4]
                        || next.2 > b[5])
                {
                    out.stopped = out.stopped.or(Some("bounds"));
                    continue;
                }
                let Some(state) = self.state(next.0, next.1, next.2) else {
                    out.stopped = Some("unloaded");
                    continue;
                };
                if !self.fills(&state) {
                    out.edges.push(next);
                    out.edge_states.push(state);
                    continue;
                }
                if out.filled.len() >= limit {
                    // One more it would have filled: there's more past the limit.
                    out.stopped = Some("limit");
                    seen.remove(&next);
                    continue;
                }
                out.filled.push(next);
                out.filled_states.push(state);
            }
        }
        out
    }
}

impl WorldApi for GameContext {
    fn name(&self, this: &handles::World) -> ApiResult<String> {
        Ok(this.name.clone())
    }

    fn exists(&self, this: &handles::World) -> ApiResult<bool> {
        Ok(self.platform().worlds().exists(this.name.clone()))
    }

    fn environment(&self, this: &handles::World) -> ApiResult<Option<String>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().environment(w.to_owned())))
    }

    fn location(
        &self,
        this: &handles::World,
        position: values::Vec3,
        yaw: Option<f64>,
        pitch: Option<f64>,
    ) -> ApiResult<values::Location> {
        Ok(values::Location {
            world: this.clone(),
            position,
            yaw,
            pitch,
        })
    }

    fn time_of_day(&self, this: &handles::World) -> ApiResult<Option<i64>> {
        Ok(self.full_time(this).map(|t| t.rem_euclid(DAY)))
    }

    fn set_time_of_day(&self, this: &handles::World, ticks: i64) -> ApiResult<bool> {
        let (Some(world), Some(full)) = (self.live(this), self.full_time(this)) else {
            return Ok(false);
        };
        Ok(self.platform().worlds().set_full_time(
            world.to_owned(),
            full.div_euclid(DAY) * DAY + ticks.rem_euclid(DAY),
        ))
    }

    fn is_night(&self, this: &handles::World) -> ApiResult<bool> {
        Ok(self
            .full_time(this)
            .is_some_and(|t| is_night(t.rem_euclid(DAY))))
    }

    fn is_day(&self, this: &handles::World) -> ApiResult<bool> {
        Ok(self
            .full_time(this)
            .is_some_and(|t| !is_night(t.rem_euclid(DAY))))
    }

    fn day_count(&self, this: &handles::World) -> ApiResult<Option<i64>> {
        Ok(self.full_time(this).map(|t| t.div_euclid(DAY)))
    }

    fn moon_phase(&self, this: &handles::World) -> ApiResult<Option<String>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().moon_phase(w.to_owned()))
            .and_then(|i| usize::try_from(i).ok())
            .and_then(|i| MOON_PHASES.get(i))
            .map(name_of))
    }

    fn weather(&self, this: &handles::World) -> ApiResult<Option<String>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().weather(w.to_owned())))
    }

    fn set_weather(
        &self,
        this: &handles::World,
        weather: String,
        options: Option<shapes::WeatherOptions>,
    ) -> ApiResult<bool> {
        let ticks = options.and_then(|o| o.ticks);
        if let Some(ticks) = ticks
            && ticks < 1
        {
            return Err(err(format!("ticks must be at least 1, not {ticks}")));
        }
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self.platform().worlds().set_weather(
            world.to_owned(),
            weather,
            ticks.map(|t| t.min(i64::from(i32::MAX)) as i32),
        ))
    }

    fn sky(&self, this: &handles::World) -> ApiResult<Option<String>> {
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        let sky = self.shared.skies.borrow().of_world(world);
        Ok(sky.map(|s| self.with_names(|names| names.spell(&s))))
    }

    fn set_sky(
        &self,
        this: &handles::World,
        sky: String,
        options: Option<shapes::SkyOptions>,
    ) -> ApiResult<bool> {
        let name = self.with_names(|names| names.resource("sky", &sky))?;
        let fade = crate::sky::fade_ticks(options.and_then(|o| o.fade_ticks))?;
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        self.shared
            .skies
            .borrow_mut()
            .set_world(self.platform(), world, &name, fade)?;
        Ok(true)
    }

    fn clear_sky(
        &self,
        this: &handles::World,
        options: Option<shapes::SkyOptions>,
    ) -> ApiResult<()> {
        let fade = crate::sky::fade_ticks(options.and_then(|o| o.fade_ticks))?;
        if let Some(world) = self.live(this) {
            self.shared.skies.borrow_mut().clear_world(world, fade);
        }
        Ok(())
    }

    fn spawn_location(&self, this: &handles::World) -> ApiResult<Option<values::Location>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().spawn_location(w.to_owned()))
            .map(|l| location_value(&l)))
    }

    fn set_spawn_location(
        &self,
        this: &handles::World,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let at = place_in(location_or_position, &this.name)?;
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self
            .platform()
            .worlds()
            .set_spawn_location(world.to_owned(), at))
    }

    fn min_height(&self, this: &handles::World) -> ApiResult<Option<i64>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().heights(w.to_owned()))
            .map(|(bottom, _)| i64::from(bottom)))
    }

    fn max_height(&self, this: &handles::World) -> ApiResult<Option<i64>> {
        Ok(self
            .live(this)
            .and_then(|w| self.platform().worlds().heights(w.to_owned()))
            .map(|(_, top)| i64::from(top)))
    }

    fn block(
        &self,
        this: &handles::World,
        position: values::Vec3,
    ) -> ApiResult<Option<handles::Block>> {
        let (x, y, z) = block_at(&position)?;
        Ok(self.live(this).map(|w| block_handle(w, x, y, z)))
    }

    fn blocks(
        &self,
        this: &handles::World,
        from: values::Vec3,
        to: values::Vec3,
        options: Option<shapes::BlockBoxOptions>,
    ) -> ApiResult<Option<shapes::BlockBox>> {
        let (a, b) = (block_at(&from)?, block_at(&to)?);
        let (x0, y0, z0) = (a.0.min(b.0), a.1.min(b.1), a.2.min(b.2));
        let size = |p: i32, q: i32| i64::from(p.max(q)) - i64::from(p.min(q)) + 1;
        let (sx, sy, sz) = (size(a.0, b.0), size(a.1, b.1), size(a.2, b.2));
        let volume = sx * sy * sz;
        if volume > MAX_BOX {
            return Err(err(format!(
                "a box of {sx} × {sy} × {sz} is {volume} blocks, more than the {MAX_BOX} world:blocks reads at once"
            )));
        }
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        let Some(read) = self.platform().blocks().r#box(
            world.to_owned(),
            x0,
            y0,
            z0,
            x0 + sx as i32 - 1,
            y0 + sy as i32 - 1,
            z0 + sz as i32 - 1,
        ) else {
            return Ok(None);
        };
        let what = options.and_then(|o| o.what);
        // Each state's value is made once and every block of it shares it.
        let values: Vec<unions::StringOrIntegerOrBoolean> = read
            .states
            .iter()
            .map(|state| match what.as_deref() {
                Some("solid") => unions::StringOrIntegerOrBoolean::Boolean(state.solid),
                Some("class") => unions::StringOrIntegerOrBoolean::Integer(if state.liquid {
                    1
                } else if state.solid {
                    2
                } else {
                    0
                }),
                _ => unions::StringOrIntegerOrBoolean::String(id_of(&state.state).to_owned()),
            })
            .collect();
        let cells = read
            .cells
            .iter()
            .map(|&i| values[i as usize].clone())
            .collect();
        Ok(Some(shapes::BlockBox {
            cells,
            size_x: sx,
            size_y: sy,
            size_z: sz,
            corner: values::Vec3 {
                x: f64::from(x0),
                y: f64::from(y0),
                z: f64::from(z0),
            },
        }))
    }

    fn flood(
        &self,
        this: &handles::World,
        start: values::Vec3,
        options: shapes::FloodOptions,
    ) -> ApiResult<Option<shapes::FloodResult>> {
        let limit = options.limit.unwrap_or(FLOOD_DEFAULT);
        if !(1..=FLOOD_LIMIT).contains(&limit) {
            return Err(err(format!(
                "bad argument 'options.limit' (1 to {FLOOD_LIMIT} expected, got {limit})"
            )));
        }
        let directions: Vec<[i32; 3]> = match &options.directions {
            None => FLOOD_FACES.to_vec(),
            Some(given) => given
                .iter()
                .enumerate()
                .map(|(i, d)| {
                    let whole = [d.x, d.y, d.z];
                    if whole.iter().any(|c| *c != c.round() || *c < -2.0 || *c > 2.0)
                        || whole.iter().all(|c| *c == 0.0)
                    {
                        return Err(err(format!(
                            "bad argument 'options.directions[{}]' (whole numbers from -2 to 2, not all 0, expected, got ({}, {}, {}))",
                            i + 1,
                            d.x,
                            d.y,
                            d.z
                        )));
                    }
                    Ok([d.x as i32, d.y as i32, d.z as i32])
                })
                .collect::<ApiResult<_>>()?,
        };
        if options.through.is_empty() {
            return Err(err(
                "bad argument 'options.through' (at least one block expected)",
            ));
        }
        let through = self.block_matcher(&options.through, "options.through")?;
        let except = match &options.except {
            Some(except) => Some(self.block_matcher(except, "options.except")?),
            None => None,
        };
        let bounds = match &options.bounds {
            Some(b) => {
                let lo = block_at(&values::Vec3 {
                    x: b.min.x.min(b.max.x),
                    y: b.min.y.min(b.max.y),
                    z: b.min.z.min(b.max.z),
                })?;
                let hi = block_at(&values::Vec3 {
                    x: b.min.x.max(b.max.x),
                    y: b.min.y.max(b.max.y),
                    z: b.min.z.max(b.max.z),
                })?;
                Some([lo.0, lo.1, lo.2, hi.0, hi.1, hi.2])
            }
            None => None,
        };
        let at = block_at(&start)?;
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        let Some((bottom, top)) = self.platform().worlds().heights(world.to_owned()) else {
            return Ok(None);
        };
        let mut flood = Flood {
            platform: self.platform(),
            world: world.to_owned(),
            bottom,
            top,
            passes: |s: &BoxState| through(s) && !except.as_ref().is_some_and(|e| e(s)),
            bricks: HashMap::new(),
            passing: HashMap::new(),
        };
        let found = flood.run(at, &directions, limit as usize, bounds);
        let positions = |cells: &[(i32, i32, i32)]| {
            cells
                .iter()
                .map(|(x, y, z)| values::Vec3 {
                    x: f64::from(*x),
                    y: f64::from(*y),
                    z: f64::from(*z),
                })
                .collect()
        };
        let kinds =
            |states: &[BoxState]| states.iter().map(|s| id_of(&s.state).to_owned()).collect();
        let with_kinds = options.kinds == Some(true);
        Ok(Some(shapes::FloodResult {
            positions: positions(&found.filled),
            edges: positions(&found.edges),
            kinds: with_kinds.then(|| kinds(&found.filled_states)),
            edge_kinds: with_kinds.then(|| kinds(&found.edge_states)),
            stopped: found.stopped.map(str::to_owned),
        }))
    }

    fn set_block(
        &self,
        this: &handles::World,
        position: values::Vec3,
        state: String,
        options: Option<shapes::BlockSetOptions>,
    ) -> ApiResult<bool> {
        let (x, y, z) = block_at(&position)?;
        let canonical = crate::block::canonical_state(self.platform().game(), &state, "state")?;
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        let update = options.and_then(|o| o.update) != Some(false);
        let set =
            self.platform()
                .blocks()
                .set(world.to_owned(), x, y, z, canonical.clone(), update);
        if set {
            self.shared
                .physics
                .borrow_mut()
                .block_changed(world, x, y, z);
            self.blocks().replaced(world, x, y, z, &canonical);
        }
        Ok(set)
    }

    /// Every block of the box, in loaded chunks within the world's heights: at most `FILL_LIMIT`.
    fn fill_blocks(
        &self,
        this: &handles::World,
        from: values::Vec3,
        to: values::Vec3,
        state: String,
        options: Option<shapes::BlockSetOptions>,
    ) -> ApiResult<i64> {
        let (a, b) = (block_at(&from)?, block_at(&to)?);
        let canonical = crate::block::canonical_state(self.platform().game(), &state, "state")?;
        let span = |p: i32, q: i32| i64::from(p.max(q)) - i64::from(p.min(q)) + 1;
        let volume = span(a.0, b.0) * span(a.1, b.1) * span(a.2, b.2);
        if volume > FILL_LIMIT {
            return Err(err(format!(
                "that box is {volume} blocks, and one fill_blocks changes at most {FILL_LIMIT}: spread it over several ticks"
            )));
        }
        let Some(world) = self.live(this) else {
            return Ok(0);
        };
        let Some((bottom, top)) = self.platform().worlds().heights(world.to_owned()) else {
            return Ok(0);
        };
        let update = options.and_then(|o| o.update) != Some(false);
        let (worlds, blocks) = (self.platform().worlds(), self.platform().blocks());
        let mut changed = 0;
        for x in a.0.min(b.0)..=a.0.max(b.0) {
            for z in a.2.min(b.2)..=a.2.max(b.2) {
                if !worlds.is_chunk_loaded(world.to_owned(), x >> 4, z >> 4) {
                    continue;
                }
                for y in a.1.min(b.1).max(bottom)..=a.1.max(b.1).min(top - 1) {
                    if blocks.set(world.to_owned(), x, y, z, canonical.clone(), update) {
                        changed += 1;
                        self.shared
                            .physics
                            .borrow_mut()
                            .block_changed(world, x, y, z);
                        self.blocks().replaced(world, x, y, z, &canonical);
                    }
                }
            }
        }
        Ok(changed)
    }

    fn fill_gradually(
        &self,
        this: &handles::World,
        from: values::Vec3,
        to: values::Vec3,
        state: String,
        options: Option<shapes::GradualFillOptions>,
    ) -> ApiResult<Pending<i64>> {
        <Unported as WorldApi>::fill_gradually(&Unported, this, from, to, state, options)
    }

    fn highest_block(
        &self,
        this: &handles::World,
        position: values::Vec3,
    ) -> ApiResult<Option<handles::Block>> {
        let (x, _, z) = block_at(&values::Vec3 { y: 0.0, ..position })?;
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        Ok(self
            .platform()
            .worlds()
            .highest_block_y(world.to_owned(), x, z)
            .map(|y| block_handle(world, x, y, z)))
    }

    fn is_chunk_loaded(&self, this: &handles::World, position: values::Vec3) -> ApiResult<bool> {
        let (x, _, z) = block_at(&values::Vec3 { y: 0.0, ..position })?;
        Ok(self.live(this).is_some_and(|w| {
            self.platform()
                .worlds()
                .is_chunk_loaded(w.to_owned(), x >> 4, z >> 4)
        }))
    }

    fn load_chunk(&self, this: &handles::World, position: values::Vec3) -> ApiResult<bool> {
        let (x, _, z) = block_at(&values::Vec3 { y: 0.0, ..position })?;
        Ok(self.live(this).is_some_and(|w| {
            self.platform()
                .worlds()
                .load_chunk(w.to_owned(), x >> 4, z >> 4)
        }))
    }

    fn locate_biome(
        &self,
        this: &handles::World,
        id: String,
        options: Option<shapes::BiomeSearchOptions>,
    ) -> ApiResult<Pending<values::Vec3>> {
        <Unported as WorldApi>::locate_biome(&Unported, this, id, options)
    }

    fn players(&self, this: &handles::World) -> ApiResult<Vec<handles::Player>> {
        let players = self.platform().players();
        Ok(players
            .online()
            .into_iter()
            .filter(|p| {
                players
                    .location(p.uuid)
                    .is_some_and(|l| l.world == this.name)
            })
            .map(|p| handles::Player {
                id: uuid_text(&p.uuid),
            })
            .collect())
    }

    fn centities(
        &self,
        this: &handles::World,
        filter: Option<shapes::CentityFilter>,
    ) -> ApiResult<Vec<handles::Centity>> {
        let mut filter = filter.unwrap_or(shapes::CentityFilter {
            kind: None,
            world: None,
            near: None,
            radius: None,
        });
        if let Some(world) = &filter.world
            && world.name != this.name
        {
            return Err(err(format!(
                "filter.world is {}, not {}",
                world.name, this.name
            )));
        }
        filter.world = Some(this.clone());
        let caller = Caller {
            scope: self.running_scope("World:centities")?,
        };
        self.centities().all(&caller, Some(filter))
    }

    fn entities(
        &self,
        this: &handles::World,
        filter: Option<shapes::EntityFilter>,
    ) -> ApiResult<Vec<handles::Entity>> {
        let (near, radius) = filter.as_ref().map_or((None, None), |f| (f.near, f.radius));
        if near.is_none() != radius.is_none() {
            return Err(err("filter.near and filter.radius go together"));
        }
        let kind = filter
            .as_ref()
            .and_then(|f| f.kind.as_deref())
            .map(game::normalize);
        let tag = filter.as_ref().and_then(|f| f.tag.clone());
        let living = filter.as_ref().and_then(|f| f.living);
        let host = self.scripts.host();
        let lua = host.lua();
        Ok(self
            .platform()
            .world_entities()
            .list(this.name.clone())
            .into_iter()
            .filter(|info| {
                let at = &info.location;
                let is_living = matches!(
                    info.category,
                    EntityCategory::Living | EntityCategory::Mob | EntityCategory::Mannequin
                );
                kind.as_ref().is_none_or(|k| info.kind == *k)
                    && tag.as_ref().is_none_or(|t| info.tags.contains(t))
                    && living.is_none_or(|l| l == is_living)
                    && near.as_ref().is_none_or(|n| {
                        length(Vec3(at.x - n.x, at.y - n.y, at.z - n.z)) <= radius.unwrap_or(0.0)
                    })
            })
            .map(|info| self.shared.entity(lua, &info))
            .collect())
    }

    fn spawn_entity(
        &self,
        this: &handles::World,
        kind: String,
        location_or_position: unions::LocationOrVec3,
        options: Option<shapes::EntitySpawnOptions>,
    ) -> ApiResult<Option<handles::Entity>> {
        let id = game::normalize(&kind);
        if !game::is_id(&kind) || !self.platform().world_entities().spawnable(id.clone()) {
            return Err(err(format!(
                "\"{kind}\" isn't an entity this server can spawn"
            )));
        }
        let at = place_in(location_or_position, &this.name)?;
        let (custom_name, tags, data, velocity) = match options {
            Some(o) => (
                o.custom_name,
                o.tags.unwrap_or_default(),
                o.data,
                o.velocity,
            ),
            None => (None, Vec::new(), Value::Nil, None),
        };
        for tag in &tags {
            if tag.is_empty()
                || !tag
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "_.+-".contains(c))
            {
                return Err(err(format!(
                    "\"{tag}\" isn't a tag: letters, digits, _, -, . and +"
                )));
            }
        }
        let data = match data {
            Value::Nil => None,
            Value::Table(_) => {
                let host = self.scripts.host();
                let encoded = netherforge_luau::json::encode(
                    &host,
                    &data,
                    "options.data",
                    "saved",
                    &netherforge_api::handle::SavedHandles,
                )
                .map_err(|e| err(format!("{e:?}")))?;
                if let Some(problem) = encoded.problems.into_iter().next() {
                    return Err(err(problem));
                }
                encoded.text.filter(|t| t != "{}" && t != "[]")
            }
            _ => {
                return Err(err(
                    "options.data must be a table of your own values, like { guard = true }",
                ));
            }
        };
        if self.live(this).is_none() {
            return Ok(None);
        }
        let setup = netherforge_platform::SpawnSetup {
            custom_name: custom_name.map(|t| t.0),
            tags,
            data,
            velocity: velocity.map(|v| vec(&v)),
        };
        let lua = self.scripts.host().lua().clone();
        Ok(self
            .platform()
            .world_entities()
            .spawn(id, at, setup)
            .map(|entity| self.shared.entity_by_id(&lua, entity)))
    }

    fn spawn_item(
        &self,
        this: &handles::World,
        position: values::Vec3,
        item: values::Item,
    ) -> ApiResult<Option<handles::DroppedItem>> {
        let item = crate::items::from_value(&item, "item", self.platform().game()).map_err(err)?;
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        Ok(self
            .platform()
            .world_entities()
            .spawn_item(world.to_owned(), vec(&position), item)
            .map(|id| handles::DroppedItem { id: uuid_text(&id) }))
    }

    fn spawn_particle(
        &self,
        this: &handles::World,
        particle: String,
        position: values::Vec3,
        options: Option<shapes::ParticleOptions>,
    ) -> ApiResult<bool> {
        let options = options.unwrap_or_else(no_particle_options);
        let spawn =
            crate::effects::particle_spawn(self.platform(), &particle, &position, &options)?;
        let viewers: Option<Vec<String>> = options
            .viewers
            .map(|v| v.into_iter().map(|p| p.id).collect());
        Ok(crate::effects::send(
            self.platform(),
            &this.name,
            spawn,
            viewers.as_deref(),
        ))
    }

    fn play_sound(
        &self,
        this: &handles::World,
        sound: String,
        position: values::Vec3,
        options: Option<shapes::SoundOptions>,
    ) -> ApiResult<bool> {
        let play = self.with_names(|names| {
            crate::effects::sound(self.platform(), names, &sound, options.as_ref())
        })?;
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self
            .platform()
            .sounds()
            .play(world.to_owned(), vec(&position), play))
    }

    fn explode(
        &self,
        this: &handles::World,
        position: values::Vec3,
        power: f64,
        options: Option<shapes::ExplosionOptions>,
    ) -> ApiResult<bool> {
        if power < 0.0 || !power.is_finite() {
            return Err(err("power can't be below 0"));
        }
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        let (fire, break_blocks) = options.map_or((false, true), |o| {
            (o.fire == Some(true), o.break_blocks != Some(false))
        });
        Ok(self.platform().worlds().explode(
            world.to_owned(),
            vec(&position),
            power,
            fire,
            break_blocks,
        ))
    }

    fn strike_lightning(
        &self,
        this: &handles::World,
        position: values::Vec3,
        options: Option<shapes::LightningOptions>,
    ) -> ApiResult<bool> {
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self.platform().worlds().strike_lightning(
            world.to_owned(),
            vec(&position),
            options.and_then(|o| o.effect_only) == Some(true),
        ))
    }

    /// What a ray hits first within `max_distance`, as `options` allow: a block, an entity (players included,
    /// leaving out `ignore`) or a centity's hitbox.
    fn raycast(
        &self,
        this: &handles::World,
        from: values::Vec3,
        direction: values::Vec3,
        max_distance: f64,
        options: Option<shapes::RaycastOptions>,
    ) -> ApiResult<Option<shapes::RaycastHit>> {
        let d = vec(&direction);
        let l = length(d);
        if l == 0.0 || !l.is_finite() {
            return Err(err("direction can't be zero"));
        }
        if max_distance < 0.0 || !max_distance.is_finite() {
            return Err(err("max_distance can't be below 0"));
        }
        let Some(world) = self.live(this) else {
            return Ok(None);
        };
        let (from, d) = (vec(&from), d * (1.0 / l));
        let options = options.unwrap_or(shapes::RaycastOptions {
            blocks: None,
            entities: None,
            ignore: None,
            centities: None,
            fluids: None,
        });
        let platform = self.platform();
        let mut reach = max_distance;
        let mut best: Option<shapes::RaycastHit> = None;
        let hit = |position: Vec3, normal: Vec3, distance: f64| shapes::RaycastHit {
            position: value(position),
            normal: value(normal),
            distance,
            block: None,
            entity: None,
            centity: None,
            node: None,
        };
        if options.blocks != Some(false)
            && let Some(block) = platform.worlds().raycast_blocks(
                world.to_owned(),
                from,
                d,
                max_distance,
                options.fluids == Some(true),
            )
        {
            reach = length(block.position - from);
            best = Some(shapes::RaycastHit {
                block: Some(block_handle(world, block.x, block.y, block.z)),
                ..hit(block.position, block.normal, reach)
            });
        }
        if options.entities != Some(false) {
            let ignore = options
                .ignore
                .iter()
                .flatten()
                .filter_map(|e| crate::events::parse_uuid(&e.id))
                .collect();
            if let Some(found) =
                platform
                    .world_entities()
                    .raycast(world.to_owned(), from, d, reach, ignore)
            {
                let at = length(found.position - from);
                if at < reach {
                    reach = at;
                    best = Some(shapes::RaycastHit {
                        entity: Some(self.entity(found.id)),
                        ..hit(found.position, found.normal, at)
                    });
                }
            }
        }
        if options.centities != Some(false) {
            let found = crate::centity::api::raycast(
                &mut self.shared.centities.borrow_mut(),
                platform,
                world,
                from,
                d,
                reach,
            );
            if let Some(found) = found
                && found.distance < reach
            {
                let id = uuid_text(&found.instance);
                let name = self
                    .shared
                    .centities
                    .borrow()
                    .get(found.instance)
                    .map(|i| i.definition.nodes[found.index].name.clone())
                    .unwrap_or_default();
                best = Some(shapes::RaycastHit {
                    centity: Some(handles::Centity { id: id.clone() }),
                    node: Some(handles::Node { centity: id, name }),
                    ..hit(found.position, found.normal, found.distance)
                });
            }
        }
        Ok(best)
    }

    fn overlap_box(
        &self,
        this: &handles::World,
        min: values::Vec3,
        max: values::Vec3,
        options: Option<shapes::QueryOptions>,
    ) -> ApiResult<Vec<shapes::QueryHit>> {
        let (a, b) = (finite(&min, "min")?, finite(&max, "max")?);
        let shape = QueryShape::Aabb {
            min: Vec3(a.0.min(b.0), a.1.min(b.1), a.2.min(b.2)),
            max: Vec3(a.0.max(b.0), a.1.max(b.1), a.2.max(b.2)),
        };
        let filter = filter_of(options.as_ref())?;
        Ok(self.query(this, &shape, &filter))
    }

    fn overlap_sphere(
        &self,
        this: &handles::World,
        center: values::Vec3,
        radius: f64,
        options: Option<shapes::QueryOptions>,
    ) -> ApiResult<Vec<shapes::QueryHit>> {
        let shape = QueryShape::Sphere {
            center: finite(&center, "center")?,
            radius: non_negative(radius, "radius")?,
        };
        let filter = filter_of(options.as_ref())?;
        Ok(self.query(this, &shape, &filter))
    }

    fn overlap_cone(
        &self,
        this: &handles::World,
        origin: values::Vec3,
        direction: values::Vec3,
        length: f64,
        angle: f64,
        options: Option<shapes::QueryOptions>,
    ) -> ApiResult<Vec<shapes::QueryHit>> {
        let tip = finite(&origin, "origin")?;
        let axis = unit(&direction, "direction")?;
        let length = non_negative(length, "length")?;
        if !(0.0..=180.0).contains(&angle) {
            return Err(err(format!(
                "bad argument 'angle' (0 to 180 expected, got {angle})"
            )));
        }
        let shape = QueryShape::Cone {
            tip,
            axis,
            length,
            half_angle: angle,
        };
        let filter = filter_of(options.as_ref())?;
        Ok(self.query(this, &shape, &filter))
    }

    fn sweep(
        &self,
        this: &handles::World,
        from: values::Vec3,
        to: values::Vec3,
        options: Option<shapes::SweepOptions>,
    ) -> ApiResult<Vec<shapes::QueryHit>> {
        let (a, b) = (finite(&from, "from")?, finite(&to, "to")?);
        let radius = non_negative(
            options.as_ref().and_then(|o| o.radius).unwrap_or(0.0),
            "options.radius",
        )?;
        let filter = match &options {
            Some(o) => filter_of(Some(&shapes::QueryOptions {
                centities: o.centities,
                players: o.players,
                entities: o.entities,
                kinds: o.kinds.clone(),
                exclude: o.exclude.clone(),
                limit: o.limit,
                line_of_sight: o.line_of_sight,
            }))?,
            None => QueryFilter::default(),
        };
        let Some(world) = self.live(this) else {
            return Ok(Vec::new());
        };
        let span = b - a;
        let span_length = length(span);
        // A block stops the sphere's middle: nothing past it counts, and it's the last hit.
        let block = (options.as_ref().and_then(|o| o.blocks) != Some(false) && span_length > 1e-9)
            .then(|| {
                self.platform().worlds().raycast_blocks(
                    world.to_owned(),
                    a,
                    span * (1.0 / span_length),
                    span_length,
                    options.as_ref().and_then(|o| o.fluids) == Some(true),
                )
            })
            .flatten();
        let end = block.as_ref().map_or(b, |hit| hit.position);
        let mut hits = self.query(
            this,
            &QueryShape::Capsule {
                from: a,
                to: end,
                radius,
            },
            &filter,
        );
        if let Some(block) = block
            && hits.len() < filter.limit
        {
            hits.push(shapes::QueryHit {
                centity: None,
                node: None,
                entity: None,
                block: Some(block_handle(world, block.x, block.y, block.z)),
                position: value(block.position),
                distance: length(block.position - a),
                normal: Some(value(block.normal)),
            });
        }
        Ok(hits)
    }

    fn nearest(
        &self,
        this: &handles::World,
        position: values::Vec3,
        radius: f64,
        options: Option<shapes::QueryOptions>,
    ) -> ApiResult<Option<shapes::QueryHit>> {
        let shape = QueryShape::Sphere {
            center: finite(&position, "position")?,
            radius: non_negative(radius, "radius")?,
        };
        let filter = QueryFilter {
            limit: 1,
            ..filter_of(options.as_ref())?
        };
        Ok(self.query(this, &shape, &filter).into_iter().next())
    }

    fn game_rule(&self, this: &handles::World, rule: String) -> ApiResult<Value> {
        let (id, _) = self.game_rule_id(&rule)?;
        let Some(world) = self.live(this) else {
            return Ok(Value::Nil);
        };
        Ok(
            match self.platform().worlds().game_rule(world.to_owned(), id) {
                Some(Dynamic::Bool(b)) => Value::Boolean(b),
                Some(Dynamic::Number(n)) => Value::Number(n),
                _ => Value::Nil,
            },
        )
    }

    fn set_game_rule(&self, this: &handles::World, rule: String, value: Value) -> ApiResult<bool> {
        let (id, kind) = self.game_rule_id(&rule)?;
        let typed = match kind {
            GameRuleType::Boolean => match value {
                Value::Boolean(b) => Dynamic::Bool(b),
                _ => return Err(err(format!("game rule {rule} takes a boolean"))),
            },
            GameRuleType::Integer => match value {
                Value::Number(n)
                    if n == n.floor()
                        && (f64::from(i32::MIN)..=f64::from(i32::MAX)).contains(&n) =>
                {
                    Dynamic::Number(n)
                }
                Value::Integer(n) if i32::try_from(n).is_ok() => Dynamic::Number(n as f64),
                _ => return Err(err(format!("game rule {rule} takes a whole number"))),
            },
        };
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self
            .platform()
            .worlds()
            .set_game_rule(world.to_owned(), id, typed))
    }

    fn spawn_limit(&self, this: &handles::World, category: String) -> ApiResult<Option<i64>> {
        Ok(self
            .live(this)
            .and_then(|w| {
                self.platform()
                    .worlds()
                    .spawn_limit(w.to_owned(), spawn_category(&category))
            })
            .map(i64::from))
    }

    fn set_spawn_limit(
        &self,
        this: &handles::World,
        category: String,
        limit: i64,
    ) -> ApiResult<bool> {
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self.platform().worlds().set_spawn_limit(
            world.to_owned(),
            spawn_category(&category),
            limit.clamp(-1, i64::from(i32::MAX)) as i32,
        ))
    }

    fn spawn_interval(&self, this: &handles::World, category: String) -> ApiResult<Option<i64>> {
        Ok(self
            .live(this)
            .and_then(|w| {
                self.platform()
                    .worlds()
                    .spawn_interval(w.to_owned(), spawn_category(&category))
            })
            .map(i64::from))
    }

    fn set_spawn_interval(
        &self,
        this: &handles::World,
        category: String,
        ticks: i64,
    ) -> ApiResult<bool> {
        let Some(world) = self.live(this) else {
            return Ok(false);
        };
        Ok(self.platform().worlds().set_spawn_interval(
            world.to_owned(),
            spawn_category(&category),
            ticks.clamp(-1, i64::from(i32::MAX)) as i32,
        ))
    }

    fn is_managed(&self, this: &handles::World) -> ApiResult<bool> {
        <Unported as WorldApi>::is_managed(&Unported, this)
    }

    fn unload(
        &self,
        this: &handles::World,
        options: Option<shapes::WorldUnloadOptions>,
    ) -> ApiResult<bool> {
        <Unported as WorldApi>::unload(&Unported, this, options)
    }

    fn border(&self, this: &handles::World) -> ApiResult<Option<handles::WorldBorder>> {
        Ok(self.live(this).map(|w| handles::WorldBorder {
            owner: "world".into(),
            id: w.to_owned(),
        }))
    }

    fn save_structure(
        &self,
        this: &handles::World,
        id: String,
        from: values::Vec3,
        to: values::Vec3,
        options: Option<shapes::StructureSaveOptions>,
    ) -> ApiResult<bool> {
        <Unported as WorldApi>::save_structure(&Unported, this, id, from, to, options)
    }

    fn place_structure(
        &self,
        this: &handles::World,
        id: String,
        position: values::Vec3,
        options: Option<shapes::StructurePlaceOptions>,
    ) -> ApiResult<bool> {
        <Unported as WorldApi>::place_structure(&Unported, this, id, position, options)
    }

    fn place_gradually(
        &self,
        this: &handles::World,
        id: String,
        position: values::Vec3,
        options: Option<shapes::GradualPlaceOptions>,
    ) -> ApiResult<Pending<i64>> {
        <Unported as WorldApi>::place_gradually(&Unported, this, id, position, options)
    }

    fn on(
        &self,
        this: &handles::World,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("World:on")?;
        let target = self.world_target(this)?;
        self.scripts
            .subscribe(scope, target, event, handler, options, false)
    }

    fn once(
        &self,
        this: &handles::World,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("World:once")?;
        let target = self.world_target(this)?;
        self.scripts
            .subscribe(scope, target, event, handler, None, true)
    }
}

/// A particle's options when a script gives none.
pub(crate) fn no_particle_options() -> shapes::ParticleOptions {
    shapes::ParticleOptions {
        count: None,
        spread: None,
        speed: None,
        color: None,
        to_color: None,
        size: None,
        block_state: None,
        item: None,
        viewers: None,
        force: None,
    }
}

/// A query's options as the spatial index's filter; none is everything.
fn filter_of(options: Option<&shapes::QueryOptions>) -> ApiResult<QueryFilter> {
    match options {
        Some(o) => crate::centity::flights::query_filter(o).map_err(err),
        None => Ok(QueryFilter::default()),
    }
}

impl NfWorldsApi for GameContext {
    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::World>> {
        let worlds = self.platform().worlds();
        let default = worlds.default_world();
        let mut names = vec![default.clone()];
        names.extend(worlds.names().into_iter().filter(|n| *n != default));
        Ok(names
            .into_iter()
            .map(|name| handles::World { name })
            .collect())
    }

    fn get(&self, _: &Caller, name: String) -> ApiResult<Option<handles::World>> {
        Ok(self
            .platform()
            .worlds()
            .exists(name.clone())
            .then_some(handles::World { name }))
    }

    fn create(
        &self,
        caller: &Caller,
        name: String,
        options: Option<shapes::WorldCreateOptions>,
    ) -> ApiResult<Option<handles::World>> {
        <Unported as NfWorldsApi>::create(&Unported, caller, name, options)
    }

    fn load(&self, caller: &Caller, name: String) -> ApiResult<Option<handles::World>> {
        <Unported as NfWorldsApi>::load(&Unported, caller, name)
    }

    fn copy(
        &self,
        caller: &Caller,
        map: String,
        name: String,
    ) -> ApiResult<Pending<handles::World>> {
        <Unported as NfWorldsApi>::copy(&Unported, caller, map, name)
    }

    fn import(
        &self,
        caller: &Caller,
        folder: String,
        name: String,
        options: Option<shapes::WorldImportOptions>,
    ) -> ApiResult<Pending<handles::World>> {
        <Unported as NfWorldsApi>::import(&Unported, caller, folder, name, options)
    }

    fn default(&self, _: &Caller) -> ApiResult<handles::World> {
        Ok(handles::World {
            name: self.platform().worlds().default_world(),
        })
    }
}
