//! Screens (Kotlin's `Screens`, `ScreenState`, `Placement`, `ScreenFrames`, `MapSending`, `ScreenImages` and
//! `ScreenImpls`): the project's (`screens/<id>/screen.json`) and those scripts make, their fonts (`screen_fonts/`),
//! and who each is drawn for.
//!
//! - **Maps.** A screen is maps side by side, each a number the server never made itself: a project screen's
//!   numbers, and a keyed script screen's, are kept in the store for good (from `MAP_BASE` up), so a map item or frame
//!   of one shows it again after a restart; a screen without a key takes numbers for the session. None is given twice.
//! - **Sending**: once a tick each player it's drawn for is sent what changed, within their budget of bytes; a player
//!   who starts seeing it is sent it whole.
//! - **Frames**: a screen that hangs somewhere is item frames, one a map, never saved with their chunk: hung again
//!   whenever their chunk is loaded and they aren't there, taken down when the session ends.
//!
//! - **Videos** (`video`): playbacks, their frames read ahead off the main thread, and their sound.
//!
//! - **Aim** (`aim`): a click on a frame, a swing aimed from further away, `hover`/`leave`, `looking_at`.

pub mod aim;
pub mod canvas;
pub mod text;
pub mod video;

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{NfScreensApi, ScreenApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, shapes, values};
use netherforge_format::kind::screen::{
    DEFAULT_CLICK_RANGE, DEFAULT_RANGE, MAX_CLICK_RANGE, MAX_RANGE, MAX_TILES, ScreenFacing,
    ScreenFile, ScreenViewers, TILE,
};
use netherforge_format::kind::screen_font::{
    DEFAULT as DEFAULT_FONT, MAX_LINE_HEIGHT, ScreenFontFile,
};
use netherforge_format::map_colors::{Dither, Palette, TRANSPARENT, parse};
use netherforge_luau::ScopeId;
use netherforge_platform::{
    EntityRole, EntityTag, ItemData, Location, Platform, PlayerRef, Ray, Uuid,
};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;
use canvas::{Canvas, Rect, SKIP};
use text::{Align, ScreenFont, TextLayout};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The first map number screens take: far past any the server will make itself.
pub const MAP_BASE: i64 = 1_000_000_000;
pub const CLEAR: &str = "clear";
/// Ticks between checks that every frame is up (one goes with its chunk).
const HANG_EVERY: u64 = 20;
/// Ticks between working out who's near each screen.
const VIEW_EVERY: u64 = 10;
/// What a map packet costs besides its pixels, against a player's budget.
const PACKET_COST: i32 = 32;
/// The most pixels one call reads or sets: four maps' worth.
const MAX_PIXELS: i64 = 65536;
const MAX_THICKNESS: i64 = 32;
const MAX_POINTS: usize = 1024;
/// The biggest side a picture may be drawn at: a screen's widest.
pub const MAX_SIDE: i64 = 2048;
/// Pictures kept, as a script tends to draw the same one again.
const KEPT_PICTURES: usize = 32;

/// A script's whole number as a pixel coordinate, held to a sane range (Kotlin's `Long.int`).
fn int(n: i64) -> i32 {
    n.clamp(-1_000_000, 1_000_000) as i32
}

/// `screens:` in `config.yml`: at most `bytes_per_tick` of screens' pixels go to each player a tick.
#[derive(Clone, Copy, Debug)]
pub struct ScreensConfig {
    pub bytes_per_tick: i32,
    /// For tests: videos' frames are read at once, on the main thread, rather than on the videos' lane.
    pub read_inline: bool,
}

impl Default for ScreensConfig {
    fn default() -> ScreensConfig {
        ScreensConfig {
            bytes_per_tick: 64 * 1024,
            read_inline: false,
        }
    }
}

/// Where a screen hangs: `world`, the block its top left map is in (the air in front of the wall), and the way it
/// faces. Its other maps go to the right of a player facing it and down.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Placement<'w> {
    pub world: &'w str,
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub facing: ScreenFacing,
}

/// A `Placement` that owns its world's name.
#[derive(Clone, Debug, PartialEq)]
pub struct Placed {
    pub world: String,
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub facing: ScreenFacing,
}

/// How far in front of the wall the picture is: an item frame's thickness.
const SURFACE: f64 = 0.5 - 1.0 / 16.0;

impl Placed {
    /// The block map (`column`, `row`) hangs in.
    pub fn block_of(&self, column: i32, row: i32) -> (i32, i32, i32) {
        let (rx, rz) = self.facing.right();
        (self.x + rx * column, self.y - row, self.z + rz * column)
    }

    pub fn location_of(&self, column: i32, row: i32) -> Location {
        let (x, y, z) = self.block_of(column, row);
        Location {
            world: self.world.clone(),
            x: f64::from(x),
            y: f64::from(y),
            z: f64::from(z),
            yaw: 0.0,
            pitch: 0.0,
        }
    }

    /// The picture's top left corner in the world.
    pub fn corner(&self) -> (f64, f64, f64) {
        let (nx, nz) = self.facing.normal();
        let (rx, rz) = self.facing.right();
        (
            f64::from(self.x) + 0.5 - f64::from(nx) * SURFACE - f64::from(rx) * 0.5,
            f64::from(self.y) + 1.0,
            f64::from(self.z) + 0.5 - f64::from(nz) * SURFACE - f64::from(rz) * 0.5,
        )
    }

    /// The middle of a `columns` by `rows` screen, for who's near it.
    pub fn middle(&self, columns: i32, rows: i32) -> (f64, f64, f64) {
        let (cx, cy, cz) = self.corner();
        let (rx, rz) = self.facing.right();
        (
            cx + f64::from(rx) * f64::from(columns) / 2.0,
            cy - f64::from(rows) / 2.0,
            cz + f64::from(rz) * f64::from(columns) / 2.0,
        )
    }

    /// Where `ray` meets the picture of a `columns` by `rows` screen within `reach` blocks, from its front: the
    /// pixel and how far along the ray. None when it misses, comes from behind, or is too far.
    pub fn hit(&self, ray: &Ray, columns: i32, rows: i32, reach: f64) -> Option<Hit> {
        if ray.world != self.world {
            return None;
        }
        let (nx, nz) = self.facing.normal();
        let (rx, rz) = self.facing.right();
        let (nx, nz, rx, rz) = (f64::from(nx), f64::from(nz), f64::from(rx), f64::from(rz));
        let (cx, cy, cz) = self.corner();
        let toward = ray.dx * nx + ray.dz * nz;
        // Looking at its face means looking against the way it faces.
        if toward >= -1e-9 {
            return None;
        }
        let t = ((cx - ray.x) * nx + (cz - ray.z) * nz) / toward;
        if !(0.0..=reach).contains(&t) {
            return None;
        }
        let (hx, hy, hz) = (ray.x + ray.dx * t, ray.y + ray.dy * t, ray.z + ray.dz * t);
        let across = (hx - cx) * rx + (hz - cz) * rz;
        let down = cy - hy;
        let px = (across * f64::from(TILE)).floor();
        let py = (down * f64::from(TILE)).floor();
        if px < 0.0 || py < 0.0 || px >= f64::from(columns * TILE) || py >= f64::from(rows * TILE) {
            return None;
        }
        Some(Hit {
            x: px as i32,
            y: py as i32,
            distance: t,
        })
    }
}

/// A pixel a ray reached, `distance` blocks along it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Hit {
    pub x: i32,
    pub y: i32,
    pub distance: f64,
}

/// How far a click on a frame is looked for along the player's sight: past any reach the game allows.
const CLICK_REACH: f64 = 16.0;
/// How much nearer than a screen a block must be to be in front of it (the wall it hangs on is just behind).
const OCCLUSION_SLACK: f64 = 0.05;
/// How far `nf.screens.looking_at` looks by default.
const DEFAULT_LOOK: f64 = 64.0;

/// How a screen shows: who for, how near, glowing or not, how far a click reaches, its first colour.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Settings {
    pub viewers: ScreenViewers,
    pub range: i32,
    pub glowing: bool,
    pub click_range: f64,
    pub background: i32,
}

/// A player a screen is drawn for, and what of each of its maps they haven't been sent yet.
pub struct Viewer {
    pub world: String,
    pub pending: Vec<Option<Rect>>,
}

impl Viewer {
    fn new(world: String, tiles: usize) -> Viewer {
        Viewer {
            world,
            pending: vec![Some(Rect::WHOLE); tiles],
        }
    }

    fn resend(&mut self, tiles: usize) {
        self.pending = vec![Some(Rect::WHOLE); tiles];
    }
}

/// One screen: its pixels (and players' own views), the maps they're drawn on, where it hangs, who it's drawn for.
/// `id` keys its handle: a project screen's name (`lobby`, `acme:lobby`), or `#<n>` for one a script made.
pub struct ScreenState {
    pub id: String,
    /// The project's screen it is, or the key a script made it with.
    pub name: Option<String>,
    pub namespace: String,
    /// The scope that made it; none for a project screen.
    pub owner: Option<ScopeId>,
    pub settings: Settings,
    pub file: Option<ScreenFile>,
    pub columns: i32,
    pub rows: i32,
    pub maps: Vec<i64>,
    pub canvas: Canvas,
    /// What its item frames are tagged with: a project screen's and a keyed one's the same every time.
    pub uuid: Uuid,
    pub placement: Option<Placed>,
    pub frames: Vec<Option<Uuid>>,
    pub rehang: bool,
    shown_to: HashSet<Uuid>,
    hidden_from: HashSet<Uuid>,
    pub viewers: IndexMap<Uuid, Viewer>,
    pub personal: HashMap<Uuid, Canvas>,
    /// Its script's scope, for a project screen with a script.
    pub scope: Option<ScopeId>,
    pub removed: bool,
    /// The video playing on each canvas, by number: the one everyone sees under none, a player's own view under them.
    pub playbacks: HashMap<Option<Uuid>, i64>,
    /// The pixel each player is aiming at, packed `x << 16 | y`, while a `hover` has been heard for them.
    pub hovering: HashMap<Uuid, i32>,
}

fn background_canvas(columns: i32, rows: i32, background: i32) -> Canvas {
    let mut canvas = Canvas::new(columns, rows);
    let (w, h) = (canvas.width, canvas.height);
    canvas.fill(0, 0, w, h, background);
    canvas.settle();
    canvas
}

impl ScreenState {
    #[allow(clippy::too_many_arguments)]
    fn new(
        id: String,
        name: Option<String>,
        namespace: String,
        owner: Option<ScopeId>,
        settings: Settings,
        columns: i32,
        rows: i32,
        maps: Vec<i64>,
        file: Option<ScreenFile>,
    ) -> ScreenState {
        let owned = owner.map_or(String::new(), |o| format!("{o:?}"));
        let uuid = crate::packs::name_uuid(
            format!(
                "screen:{namespace}:{}:{owned}",
                name.as_deref().unwrap_or(&id)
            )
            .as_bytes(),
        );
        ScreenState {
            canvas: background_canvas(columns, rows, settings.background),
            frames: vec![None; (columns * rows) as usize],
            id,
            name,
            namespace,
            owner,
            settings,
            file,
            columns,
            rows,
            maps,
            uuid,
            placement: None,
            rehang: true,
            shown_to: HashSet::new(),
            hidden_from: HashSet::new(),
            viewers: IndexMap::new(),
            personal: HashMap::new(),
            scope: None,
            removed: false,
            playbacks: HashMap::new(),
            hovering: HashMap::new(),
        }
    }

    pub fn tiles(&self) -> usize {
        (self.columns * self.rows) as usize
    }

    pub fn handle(&self) -> handles::Screen {
        self.handle_for(None)
    }

    pub fn handle_for(&self, player: Option<Uuid>) -> handles::Screen {
        handles::Screen {
            id: self.id.clone(),
            player: player.map_or(String::new(), |p| uuid_text(&p)),
        }
    }

    pub fn canvas_for(&mut self, player: Option<Uuid>) -> &mut Canvas {
        match player.and_then(|p| self.personal.get_mut(&p)) {
            Some(own) => own,
            None => &mut self.canvas,
        }
    }

    /// The handle an event `player` caused carries: their own view's, when they have one.
    fn handle_of(&self, player: Uuid) -> handles::Screen {
        self.handle_for(self.personal.contains_key(&player).then_some(player))
    }

    /// The events' path for something `player` did to it: their own view's handlers first, then everyone's.
    fn path_of(&self, player: Uuid) -> Vec<handles::Screen> {
        let mut path = Vec::new();
        if self.personal.contains_key(&player) {
            path.push(self.handle_for(Some(player)));
        }
        path.push(self.handle());
        path
    }

    fn view_of(&self, player: Uuid) -> &Canvas {
        self.personal.get(&player).unwrap_or(&self.canvas)
    }

    fn move_to(&mut self, placement: Option<Placed>) {
        self.placement = placement;
        self.rehang = true;
    }

    /// Whether (`x`, `y`, `z`) is within `range` blocks of its middle; false while it hangs nowhere.
    fn is_near(&self, x: f64, y: f64, z: f64, range: f64) -> bool {
        let Some((mx, my, mz)) = self
            .placement
            .as_ref()
            .map(|p| p.middle(self.columns, self.rows))
        else {
            return false;
        };
        let (dx, dy, dz) = (x - mx, y - my, z - mz);
        dx * dx + dy * dy + dz * dz <= range * range
    }

    /// A new size: everything drawn goes, and every viewer is sent it whole again.
    fn resize(&mut self, columns: i32, rows: i32, maps: Vec<i64>) {
        self.columns = columns;
        self.rows = rows;
        self.maps = maps;
        self.canvas = background_canvas(columns, rows, self.settings.background);
        let fresh = self.canvas.clone();
        for own in self.personal.values_mut() {
            *own = fresh.clone();
        }
        self.frames = vec![None; (columns * rows) as usize];
        self.rehang = true;
        let tiles = self.tiles();
        for viewer in self.viewers.values_mut() {
            viewer.resend(tiles);
        }
    }
}

/// A picture as drawn: `width` by `height`, an index (or `SKIP`) a pixel.
#[derive(Clone)]
pub struct Picture {
    pub width: usize,
    pub height: usize,
    pub colors: Rc<Vec<i32>>,
}

/// The size to draw a `width` by `height` picture at, asked for as `want_width` by `want_height`.
pub fn picture_size(
    width: i64,
    height: i64,
    want_width: Option<i64>,
    want_height: Option<i64>,
) -> (i64, i64) {
    let w = match (want_width, want_height) {
        (Some(w), _) => w,
        (None, Some(h)) => (width * h / height.max(1)).max(1),
        (None, None) => width,
    };
    let h = match (want_height, want_width) {
        (Some(h), _) => h,
        (None, Some(w)) => (height * w / width.max(1)).max(1),
        (None, None) => height,
    };
    (w.clamp(1, MAX_SIDE), h.clamp(1, MAX_SIDE))
}

/// A picture as asked for: its path, size and dithering.
type PictureKey = (String, Option<i64>, Option<i64>, Dither);

/// Reads a project file's bytes by project path.
pub type ReadBytes = dyn Fn(&str) -> Option<Vec<u8>>;

/// A `'static` reader of the project's files' bytes by project path (a package's `ns:path`): what screens read
/// pictures and font files with while scripts run. The adapter gives one that reads the disk.
#[derive(Clone)]
pub struct ProjectBytes(pub Rc<ReadBytes>);

impl std::fmt::Debug for ProjectBytes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ProjectBytes")
    }
}

/// The session's screens and what they draw with.
#[derive(Default)]
pub struct Screens {
    pub config: ScreensConfig,
    pub read: Option<ProjectBytes>,
    /// Where the project's files are on disk: what videos' frames are read from.
    pub paths: Option<video::ProjectPaths>,
    pub videos: video::Videos,
    /// Each video's number, so one read again on a reload is a new video to the readers.
    video_serial: u64,
    pub screens: IndexMap<String, ScreenState>,
    next_screen: u64,
    /// The map numbers kept for good, by (namespace, screen key), each by its tile's stable number.
    kept: BTreeMap<(String, String), BTreeMap<i64, i64>>,
    next_map: i64,
    font_files: BTreeMap<String, ScreenFontFile>,
    /// Each screen's folder and each font's, where their scripts and files are.
    folders: BTreeMap<String, String>,
    font_folders: BTreeMap<String, String>,
    fonts: HashMap<String, Rc<ScreenFont>>,
    game_font: Option<Rc<ScreenFont>>,
    ticks: u64,
    /// Our frames, by entity: whose screen and which tile.
    owners: HashMap<Uuid, (String, usize)>,
    /// The tick each player last clicked a screen in.
    clicked: HashMap<Uuid, u64>,
    pictures: VecDeque<(PictureKey, Picture)>,
    palette: Option<Palette>,
    /// The project's screens that have a file, with its first error when it has errors.
    files: BTreeMap<String, bool>,
}

impl Screens {
    /// The screen a handle is, and whose own view it is (none for everyone's): none when either is gone.
    pub fn resolve(&self, handle: &handles::Screen) -> Option<(&ScreenState, Option<Uuid>)> {
        let screen = self.screens.get(&handle.id).filter(|s| !s.removed)?;
        if handle.player.is_empty() {
            return Some((screen, None));
        }
        let player = parse_uuid(&handle.player)?;
        screen
            .personal
            .contains_key(&player)
            .then_some((screen, Some(player)))
    }

    fn palette(&mut self, platform: &dyn Platform) -> &mut Palette {
        self.palette
            .get_or_insert_with(|| Palette::of(Some(platform.game())))
    }

    /// `text` as a map colour: `#rrggbb`'s nearest, or transparent for `"clear"` and none.
    pub fn color(&mut self, platform: &dyn Platform, text: Option<&str>) -> ApiResult<i32> {
        let Some(text) = text.filter(|t| *t != CLEAR) else {
            return Ok(i32::from(TRANSPARENT));
        };
        let rgb = parse(text).ok_or_else(|| {
            err(format!(
                "\"{text}\" isn't a colour: write #rrggbb, or \"{CLEAR}\""
            ))
        })?;
        Ok(i32::from(self.palette(platform).nearest(rgb)))
    }

    /// A map colour as a script reads it: `#rrggbb`, or `"clear"`.
    pub fn color_name(&mut self, platform: &dyn Platform, index: u8) -> String {
        match self.palette(platform).rgb(index) {
            Some(rgb) => format!("#{rgb:06x}"),
            None => CLEAR.to_owned(),
        }
    }

    pub fn font_names(&self) -> Vec<String> {
        std::iter::once(DEFAULT_FONT.to_owned())
            .chain(self.font_files.keys().cloned())
            .collect()
    }

    /// The font `name` (as the project names it, or `default`): loaded the first time it's asked for.
    pub fn font(&mut self, platform: &dyn Platform, name: &str) -> ApiResult<Rc<ScreenFont>> {
        if name == DEFAULT_FONT {
            return Ok(self
                .game_font
                .get_or_insert_with(|| Rc::new(ScreenFont::of_game(&platform.screens().font())))
                .clone());
        }
        if let Some(font) = self.fonts.get(name) {
            return Ok(font.clone());
        }
        let Some(file) = self.font_files.get(name) else {
            return Err(err(crate::centity::api::not_in_project(
                "screen font",
                name,
                &self.font_names(),
            )));
        };
        let bytes = match &file.file {
            Some(path) => {
                let full = format!(
                    "{}/{path}",
                    self.font_folders.get(name).cloned().unwrap_or_default()
                );
                let read = self.read.as_ref().and_then(|r| (r.0)(&full));
                Some(read.ok_or_else(|| err(format!("screen font \"{name}\" has no {path}")))?)
            }
            None => None,
        };
        let font = ScreenFont::of_file(file, bytes.as_deref())
            .map_err(|e| err(format!("screen font \"{name}\" can't be read: {e}")))?;
        let font = Rc::new(font);
        self.fonts.insert(name.to_owned(), font.clone());
        Ok(font)
    }

    /// The map numbers of a `columns` by `rows` screen: kept for good under `key` in `namespace`, or for now.
    fn maps(
        &mut self,
        store: &crate::store::Store,
        namespace: &str,
        key: Option<&str>,
        columns: i32,
        rows: i32,
    ) -> Vec<i64> {
        (0..columns * rows)
            .map(|tile| {
                let stable = i64::from((tile / columns) * MAX_TILES + tile % columns);
                if let Some(key) = key
                    && let Some(map) = self
                        .kept
                        .get(&(namespace.to_owned(), key.to_owned()))
                        .and_then(|k| k.get(&stable))
                {
                    return *map;
                }
                let map = self.next_map;
                self.next_map += 1;
                store.set_screen_map_next(self.next_map);
                if let Some(key) = key {
                    self.kept
                        .entry((namespace.to_owned(), key.to_owned()))
                        .or_default()
                        .insert(stable, map);
                    store.put_screen_map(namespace, key, stable, map);
                }
                map
            })
            .collect()
    }

    /// The picture at project path `path` at `width` by `height`, or none when there's no such file or it isn't one.
    fn picture(
        &mut self,
        platform: &dyn Platform,
        path: &str,
        width: Option<i64>,
        height: Option<i64>,
        dither: Dither,
    ) -> Option<Picture> {
        let key = (path.to_owned(), width, height, dither);
        if let Some(at) = self.pictures.iter().position(|(k, _)| *k == key) {
            let kept = self.pictures.remove(at)?;
            let picture = kept.1.clone();
            self.pictures.push_back(kept);
            return Some(picture);
        }
        let bytes = (self.read.as_ref()?.0)(path)?;
        let image = crate::images::decode(&bytes)?;
        let (w, h) = picture_size(
            i64::from(image.width()),
            i64::from(image.height()),
            width,
            height,
        );
        let scaled = if (w, h) == (i64::from(image.width()), i64::from(image.height())) {
            image
        } else {
            image.resized(w as u32, h as u32)
        };
        let (w, h) = (w as usize, h as usize);
        let colors = dither.apply(self.palette(platform), w, h, |i| {
            scaled.get((i % w) as u32, (i / w) as u32)
        });
        let picture = Picture {
            width: w,
            height: h,
            colors: Rc::new(
                colors
                    .into_iter()
                    .map(|c| c.map_or(SKIP, i32::from))
                    .collect(),
            ),
        };
        self.pictures.push_back((key, picture.clone()));
        while self.pictures.len() > KEPT_PICTURES {
            self.pictures.pop_front();
        }
        Some(picture)
    }

    fn settings_of(&mut self, platform: &dyn Platform, file: &ScreenFile) -> Settings {
        Settings {
            viewers: file.viewers.unwrap_or(ScreenViewers::Near),
            range: file.range.unwrap_or(DEFAULT_RANGE),
            glowing: file.glowing.unwrap_or(true),
            click_range: file.click_range.unwrap_or(DEFAULT_CLICK_RANGE),
            background: self
                .color(platform, file.background.as_deref())
                .unwrap_or(i32::from(TRANSPARENT)),
        }
    }

    fn project_screen(
        &mut self,
        shared_store: &crate::store::Store,
        platform: &dyn Platform,
        name: &str,
        file: &ScreenFile,
        home: &str,
    ) -> ScreenState {
        let (package, bare) = netherforge_format::kinds::split_package(name);
        let namespace = package.unwrap_or(home).to_owned();
        let maps = self.maps(
            shared_store,
            &namespace,
            Some(&format!("screen:{bare}")),
            file.width,
            file.height,
        );
        let settings = self.settings_of(platform, file);
        let mut screen = ScreenState::new(
            name.to_owned(),
            Some(name.to_owned()),
            namespace,
            None,
            settings,
            file.width,
            file.height,
            maps,
            Some(file.clone()),
        );
        screen.move_to(placement_of(file));
        screen
    }
}

fn placement_of(file: &ScreenFile) -> Option<Placed> {
    file.place.as_ref().map(|p| Placed {
        world: p.world.clone(),
        x: p.x,
        y: p.y,
        z: p.z,
        facing: p.facing,
    })
}

/// The screens' work that reaches the platform and scripts.
#[derive(Clone)]
pub struct ScreensContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl ScreensContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn target(&self, handle: handles::Screen) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &Handle::Screen(handle))
            .ok()
            .map(Target::Handle)
    }

    /// Hangs every frame of `id` that isn't up and whose chunk is loaded.
    fn hang(&self, id: &str) {
        let platform = self.platform();
        let mut screens = self.shared.screens.borrow_mut();
        let screens = &mut *screens;
        let Some(screen) = screens.screens.get_mut(id) else {
            return;
        };
        screen.rehang = false;
        let Some(placement) = screen.placement.clone() else {
            return;
        };
        for tile in 0..screen.tiles() {
            let existing = screen.frames[tile];
            if existing.is_some_and(|e| platform.entities().is_loaded(e)) {
                continue;
            }
            let (column, row) = (tile as i32 % screen.columns, tile as i32 / screen.columns);
            let at = placement.location_of(column, row);
            if !platform.worlds().entities_loaded(at.clone()) {
                continue;
            }
            if let Some(old) = existing {
                screens.owners.remove(&old);
                platform.entities().remove(old);
            }
            let tag = EntityTag {
                instance: screen.uuid,
                node: format!("{column},{row}"),
                role: EntityRole::Screen,
            };
            let frame = platform.screens().hang(
                at,
                placement.facing,
                screen.maps[tile] as i32,
                screen.settings.glowing,
                tag,
            );
            screen.frames[tile] = frame;
            if let Some(frame) = frame {
                screens.owners.insert(frame, (screen.id.clone(), tile));
            }
        }
    }

    fn take_down(&self, id: &str) {
        let platform = self.platform();
        let mut screens = self.shared.screens.borrow_mut();
        let screens = &mut *screens;
        let Some(screen) = screens.screens.get_mut(id) else {
            return;
        };
        for frame in screen.frames.iter_mut() {
            if let Some(entity) = frame.take() {
                screens.owners.remove(&entity);
                platform.entities().remove(entity);
            }
        }
    }

    /// Works out who `id` is drawn for now: those it's new to are sent it whole, those it isn't any more forgotten
    /// (and stop aiming at it).
    fn view(&self, id: &str) {
        for player in self.view_only(id) {
            self.leave(id, player);
        }
    }

    /// `view` but for the leaves: the players it's no longer drawn for.
    fn view_only(&self, id: &str) -> Vec<Uuid> {
        let platform = self.platform();
        let players = platform.players();
        let mut screens = self.shared.screens.borrow_mut();
        let Some(screen) = screens.screens.get_mut(id).filter(|s| !s.removed) else {
            return Vec::new();
        };
        let mut wanted: IndexMap<Uuid, String> = IndexMap::new();
        for player in players.online() {
            let uuid = player.uuid;
            if screen.hidden_from.contains(&uuid) {
                continue;
            }
            let Some(at) = players.location(uuid) else {
                continue;
            };
            let near = if screen.shown_to.contains(&uuid) {
                true
            } else if screen.settings.viewers == ScreenViewers::Script {
                false
            } else {
                match &screen.placement {
                    None => true,
                    Some(p) => {
                        at.world == p.world
                            && screen.is_near(at.x, at.y, at.z, f64::from(screen.settings.range))
                    }
                }
            };
            if near {
                wanted.insert(uuid, at.world);
            }
        }
        let gone: Vec<Uuid> = screen
            .viewers
            .iter()
            .filter(|(player, viewer)| wanted.get(*player) != Some(&viewer.world))
            .map(|(player, _)| *player)
            .collect();
        for player in &gone {
            screen.viewers.shift_remove(player);
        }
        let tiles = screen.tiles();
        for (player, world) in wanted {
            // Someone new, or back from another world (their game forgets maps it's no longer in), gets it whole.
            screen
                .viewers
                .entry(player)
                .or_insert_with(|| Viewer::new(world, tiles));
        }
        gone
    }

    /// Sends every viewer of every screen what changed of the screens they see, each up to the tick's budget.
    fn flush(&self) {
        let platform = self.platform();
        let mut screens = self.shared.screens.borrow_mut();
        let budget_each = screens.config.bytes_per_tick;
        let tick = screens.ticks;
        let ids: Vec<String> = screens
            .screens
            .iter()
            .filter(|(_, s)| !s.removed)
            .map(|(id, _)| id.clone())
            .collect();
        if ids.is_empty() {
            return;
        }
        let mut budget: HashMap<Uuid, i32> = HashMap::new();
        // Each tick starts with another screen, so one big screen can't always have a player's budget first.
        let first = (tick % ids.len() as u64) as usize;
        for i in 0..ids.len() {
            let Some(screen) = screens.screens.get_mut(&ids[(first + i) % ids.len()]) else {
                continue;
            };
            gather(screen, None);
            let own: Vec<Uuid> = screen.personal.keys().copied().collect();
            for player in own {
                gather(screen, Some(player));
            }
            let viewers: Vec<Uuid> = screen.viewers.keys().copied().collect();
            for player in viewers {
                let mut left = budget.get(&player).copied().unwrap_or(budget_each);
                if left <= 0 {
                    continue;
                }
                let tiles = screen.tiles();
                let start = (tick % tiles as u64) as usize;
                for k in 0..tiles {
                    let tile = (start + k) % tiles;
                    let Some(rect) = screen.viewers[&player].pending[tile] else {
                        continue;
                    };
                    let cost = rect.area() + PACKET_COST;
                    if cost > left {
                        break;
                    }
                    let region = screen.view_of(player).region(tile, rect);
                    let map = screen.maps[tile] as i32;
                    if !platform.screens().send_map(
                        player,
                        map,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height(),
                        region,
                    ) {
                        break;
                    }
                    if let Some(viewer) = screen.viewers.get_mut(&player) {
                        viewer.pending[tile] = None;
                    }
                    left -= cost;
                }
                budget.insert(player, left);
            }
        }
    }

    /// Takes `id` away: its frames, everyone's own views of it, every handler on it. With `forget` (a script
    /// removing its own screen), a keyed one gives up its maps' numbers.
    fn remove(&self, id: &str, forget: bool) {
        self.end_where(video::EndReason::Removed, |p| p.screen == id);
        self.leave_all(id);
        let (handles, owned) = {
            let mut screens = self.shared.screens.borrow_mut();
            let screens = &mut *screens;
            let Some(screen) = screens.screens.get(id).filter(|s| !s.removed) else {
                return;
            };
            if forget
                && screen.owner.is_some()
                && let Some(name) = &screen.name
            {
                let key = format!("key:{name}");
                if let Some(tiles) = screens
                    .kept
                    .remove(&(screen.namespace.clone(), key.clone()))
                {
                    for tile in tiles.keys() {
                        self.shared
                            .store
                            .forget_screen_map(&screen.namespace, &key, *tile);
                    }
                }
            }
            let screen = screens.screens.get_mut(id).expect("there");
            screen.removed = true;
            screen.viewers.clear();
            let mut handles = vec![screen.handle()];
            handles.extend(screen.personal.keys().map(|p| screen.handle_for(Some(*p))));
            screen.personal.clear();
            (handles, screen.owner.is_some())
        };
        self.take_down(id);
        for handle in handles {
            if let Some(target) = self.target(handle) {
                self.scripts.drop_target(&target);
            }
        }
        if owned {
            self.shared.screens.borrow_mut().screens.shift_remove(id);
        }
    }

    fn start_script(&self, id: &str) {
        let (path, folder) = {
            let screens = self.shared.screens.borrow();
            let Some(screen) = screens.screens.get(id) else {
                return;
            };
            let Some(script) = screen.file.as_ref().and_then(|f| f.script.as_ref()) else {
                return;
            };
            let Some(folder) = screens.folders.get(id) else {
                return;
            };
            (format!("{folder}/{}", script.file), folder.clone())
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return;
        };
        let Ok(this) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::Screen(handles::Screen {
                id: id.to_owned(),
                player: String::new(),
            }),
        ) else {
            return;
        };
        let (scope, _) = modules.script_with(
            &format!("screen {id}"),
            &folder,
            &path,
            &[("this", Value::UserData(this))],
        );
        if let Some(screen) = self.shared.screens.borrow_mut().screens.get_mut(id) {
            screen.scope = scope;
        }
    }

    fn stop_script(&self, id: &str) {
        let scope = self
            .shared
            .screens
            .borrow_mut()
            .screens
            .get_mut(id)
            .and_then(|s| s.scope.take());
        if let Some(scope) = scope {
            match self.shared.modules.borrow().clone() {
                Some(modules) => modules.close(scope),
                None => self.scripts.close(scope),
            }
        }
    }

    /// Tells `scripts` which screen handles still name a screen (a player's own view, while they have it).
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let screens = shared.screens.try_borrow().ok()?;
            match handle {
                Handle::Screen(s) => Some(screens.resolve(s).is_some()),
                Handle::Playback(p) => Some(screens.videos.playbacks.contains_key(&p.number)),
                _ => None,
            }
        }));
    }
}

/// Hands each changed map of a canvas (everyone's, or `player`'s own) to the viewers who see it.
fn gather(screen: &mut ScreenState, player: Option<Uuid>) {
    let tiles = screen.tiles();
    let canvas = match player {
        Some(p) => screen.personal.get_mut(&p).expect("there"),
        None => &mut screen.canvas,
    };
    if !canvas.changed {
        return;
    }
    let mut rects = Vec::new();
    for tile in 0..tiles {
        if let Some(rect) = canvas.take(tile) {
            rects.push((tile, rect));
        }
    }
    canvas.settle();
    let personal: HashSet<Uuid> = screen.personal.keys().copied().collect();
    for (who, viewer) in screen.viewers.iter_mut() {
        let sees = match player {
            Some(p) => *who == p,
            None => !personal.contains(who),
        };
        if sees {
            for (tile, rect) in &rects {
                viewer.pending[*tile] = Some(rect.union(viewer.pending[*tile]));
            }
        }
    }
}

// ---- the API ----------------------------------------------------------------

impl GameContext {
    fn screens_context(&self) -> ScreensContext {
        ScreensContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    /// Draws on `this`'s canvas (everyone's or a player's own); nothing once it's gone.
    fn draw<R>(
        &self,
        this: &handles::Screen,
        f: impl FnOnce(&mut Canvas, &mut Palette) -> R,
    ) -> Option<R> {
        let platform = &*self.shared.platform;
        let mut screens = self.shared.screens.borrow_mut();
        let (_, player) = screens.resolve(this)?;
        let Screens {
            screens: all,
            palette,
            ..
        } = &mut *screens;
        let palette = palette.get_or_insert_with(|| Palette::of(Some(platform.game())));
        let screen = all.get_mut(&this.id)?;
        Some(f(screen.canvas_for(player), palette))
    }

    fn screen_color(&self, text: Option<&str>) -> ApiResult<i32> {
        self.shared
            .screens
            .borrow_mut()
            .color(&*self.shared.platform, text)
    }

    /// A shape's fill and outline as map colours (`SKIP` for none), and the outline's thickness.
    fn shape_style(&self, style: &shapes::ShapeStyle) -> ApiResult<(i32, i32, i32)> {
        if style.fill.is_none() && style.outline.is_none() {
            return Err(err("a style has a fill, an outline or both"));
        }
        let thickness = style.thickness.unwrap_or(1);
        if !(1..=MAX_THICKNESS).contains(&thickness) {
            return Err(err(format!(
                "style.thickness is 1 to {MAX_THICKNESS}, not {thickness}"
            )));
        }
        let fill = match &style.fill {
            Some(f) => self.screen_color(Some(f))?,
            None => SKIP,
        };
        let outline = match &style.outline {
            Some(o) => self.screen_color(Some(o))?,
            None => SKIP,
        };
        Ok((fill, outline, thickness as i32))
    }

    /// How text options read, checked: the font named in the calling package, sizes in range.
    fn text_layout<R>(
        &self,
        options: Option<&shapes::ScreenTextOptions>,
        f: impl FnOnce(&TextLayout) -> R,
    ) -> ApiResult<R> {
        let font_name = match options.and_then(|o| o.font.as_deref()) {
            None => DEFAULT_FONT.to_owned(),
            Some(DEFAULT_FONT) => DEFAULT_FONT.to_owned(),
            Some(font) => self.with_names(|names| names.resource("screen_font", font))?,
        };
        let scale = options.and_then(|o| o.scale).unwrap_or(1);
        if !(1..=8).contains(&scale) {
            return Err(err(format!("options.scale is 1 to 8, not {scale}")));
        }
        let rgb = |at: &str, text: &str| {
            parse(text)
                .ok_or_else(|| err(format!("{at} isn't a colour written #rrggbb: \"{text}\"")))
        };
        let color = options
            .and_then(|o| o.color.as_deref())
            .map(|c| rgb("options.color", c))
            .transpose()?
            .unwrap_or(0xFF_FFFF);
        let shadow = options
            .and_then(|o| o.shadow.as_deref())
            .map(|c| rgb("options.shadow", c))
            .transpose()?;
        let width = match options.and_then(|o| o.width) {
            Some(w) if w < 1 => {
                return Err(err(format!("options.width is 1 pixel or more, not {w}")));
            }
            w => w.map(|w| w as i32),
        };
        let line_height = match options.and_then(|o| o.line_height) {
            Some(h) if !(1..=i64::from(MAX_LINE_HEIGHT)).contains(&h) => {
                return Err(err(format!(
                    "options.line_height is 1 to {MAX_LINE_HEIGHT}, not {h}"
                )));
            }
            h => h.map(|h| h as i32),
        };
        let align = match options.and_then(|o| o.align.as_deref()) {
            Some("center") => Align::Center,
            Some("right") => Align::Right,
            _ => Align::Left,
        };
        let font = self
            .shared
            .screens
            .borrow_mut()
            .font(&*self.shared.platform, &font_name)?;
        Ok(f(&TextLayout {
            font: &font,
            color,
            scale: scale as i32,
            align,
            width,
            shadow,
            line_height,
        }))
    }

    /// A resource pack's texture by `<pack>/<path>` (a package's as `ns:<pack>/<path>`): its project path.
    fn texture_path(&self, image: &str) -> ApiResult<String> {
        let from = self.calling_namespace();
        let key = netherforge_format::reference::resolve_any(&from, image)
            .filter(|k| k.path.contains('/'))
            .ok_or_else(|| {
                err(format!(
                    "\"{image}\" isn't a picture: name a resource pack's texture as <pack>/<path>, like \"ui/logo\""
                ))
            })?;
        let (pack, rest) = key.path.split_once('/').expect("a slash");
        let home = self.shared.names.borrow().home.clone();
        let name = if key.namespace == home {
            pack.to_owned()
        } else {
            netherforge_format::kinds::package_path(&key.namespace, pack)
        };
        if !self.shared.packs.borrow().pack_files.contains(&name) {
            let elsewhere = if key.namespace != from {
                format!(" in package \"{}\"", key.namespace)
            } else {
                String::new()
            };
            return Err(err(format!(
                "there's no resource pack \"{pack}\"{elsewhere}"
            )));
        }
        Ok(
            netherforge_format::kind::resource_pack::compiled::texture_path(
                &name,
                &format!("{rest}.png"),
            ),
        )
    }
}

impl NfScreensApi for GameContext {
    fn get(&self, _caller: &Caller, screen: String) -> ApiResult<handles::Screen> {
        let name = self.with_names(|names| names.resource("screen", &screen))?;
        let screens = self.shared.screens.borrow();
        if let Some(found) = screens
            .screens
            .get(&name)
            .filter(|s| s.owner.is_none() && !s.removed)
        {
            return Ok(found.handle());
        }
        if screens.files.contains_key(&name) {
            return Err(err(format!(
                "screen \"{name}\" has errors, and there's no earlier version to run"
            )));
        }
        let known: Vec<String> = screens
            .screens
            .values()
            .filter(|s| s.owner.is_none())
            .map(|s| s.id.clone())
            .collect();
        Err(err(crate::centity::api::not_in_project(
            "screen", &name, &known,
        )))
    }

    fn all(&self, _caller: &Caller) -> ApiResult<Vec<handles::Screen>> {
        Ok(self
            .shared
            .screens
            .borrow()
            .screens
            .values()
            .filter(|s| !s.removed)
            .map(ScreenState::handle)
            .collect())
    }

    fn create(
        &self,
        caller: &Caller,
        options: shapes::ScreenOptions,
    ) -> ApiResult<handles::Screen> {
        let tiles = |field: &str, value: i64| {
            if !(1..=i64::from(MAX_TILES)).contains(&value) {
                return Err(err(format!(
                    "options.{field} is 1 to {MAX_TILES} maps, not {value}"
                )));
            }
            Ok(value as i32)
        };
        let columns = tiles("width", options.width)?;
        let rows = tiles("height", options.height)?;
        let range = match options.range {
            Some(r) if !(1..=i64::from(MAX_RANGE)).contains(&r) => {
                return Err(err(format!(
                    "options.range is 1 to {MAX_RANGE} blocks, not {r}"
                )));
            }
            r => r.map_or(DEFAULT_RANGE, |r| r as i32),
        };
        let click_range = match options.click_range {
            Some(r) if r.is_nan() || !(0.0..=MAX_CLICK_RANGE).contains(&r) => {
                return Err(err(format!(
                    "options.click_range is 0 to {} blocks, not {r}",
                    MAX_CLICK_RANGE as i64
                )));
            }
            r => r.unwrap_or(DEFAULT_CLICK_RANGE),
        };
        if options.key.as_deref().is_some_and(|k| k.trim().is_empty()) {
            return Err(err("options.key is a name, not empty"));
        }
        let namespace = self.calling_namespace();
        let platform = &*self.shared.platform;
        let mut screens = self.shared.screens.borrow_mut();
        if let Some(key) = &options.key
            && screens.screens.values().any(|s| {
                !s.removed
                    && s.owner.is_some()
                    && s.namespace == namespace
                    && s.name.as_ref() == Some(key)
            })
        {
            return Err(err(format!(
                "a screen with the key \"{key}\" is still there: remove it first, or use another key"
            )));
        }
        let settings = Settings {
            viewers: if options.viewers.as_deref() == Some("script") {
                ScreenViewers::Script
            } else {
                ScreenViewers::Near
            },
            range,
            glowing: options.glowing.unwrap_or(true),
            click_range,
            background: screens.color(platform, options.background.as_deref())?,
        };
        let key = options.key.as_ref().map(|k| format!("key:{k}"));
        let maps = screens.maps(
            &self.shared.store,
            &namespace,
            key.as_deref(),
            columns,
            rows,
        );
        screens.next_screen += 1;
        let id = format!("#{}", screens.next_screen);
        let screen = ScreenState::new(
            id.clone(),
            options.key.clone(),
            namespace,
            Some(caller.scope),
            settings,
            columns,
            rows,
            maps,
            None,
        );
        let handle = screen.handle();
        screens.screens.insert(id.clone(), screen);
        drop(screens);
        self.screens_context().view(&id);
        Ok(handle)
    }

    fn fonts(&self, _caller: &Caller) -> ApiResult<Vec<String>> {
        let names = self.shared.screens.borrow().font_names();
        Ok(names
            .into_iter()
            .map(|n| {
                if n == DEFAULT_FONT {
                    n
                } else {
                    self.with_names(|names| names.spell(&n))
                }
            })
            .collect())
    }

    fn measure_text(
        &self,
        _caller: &Caller,
        text: values::Text,
        options: Option<shapes::ScreenTextOptions>,
    ) -> ApiResult<shapes::ScreenTextSize> {
        let (width, height) =
            self.text_layout(options.as_ref(), |layout| text::measure(&text.0, layout))?;
        Ok(shapes::ScreenTextSize {
            width: i64::from(width),
            height: i64::from(height),
        })
    }

    fn map_color(&self, _caller: &Caller, color: String) -> ApiResult<String> {
        let platform = &*self.shared.platform;
        let mut screens = self.shared.screens.borrow_mut();
        let index = screens.color(platform, Some(&color))?;
        Ok(screens.color_name(platform, index as u8))
    }

    fn looking_at(
        &self,
        _caller: &Caller,
        player: handles::Player,
        distance: Option<f64>,
    ) -> ApiResult<Option<shapes::ScreenPoint>> {
        let Some(uuid) = parse_uuid(&player.id) else {
            return Ok(None);
        };
        let Some(eye) = self.shared.platform.players().eye(uuid) else {
            return Ok(None);
        };
        let reach = distance
            .filter(|d| d.is_finite() && *d > 0.0)
            .unwrap_or(DEFAULT_LOOK);
        let context = self.screens_context();
        let Some((id, hit)) = context.aim(uuid, &eye, Some(reach)) else {
            return Ok(None);
        };
        let screens = self.shared.screens.borrow();
        let Some(screen) = screens.screens.get(&id) else {
            return Ok(None);
        };
        Ok(Some(shapes::ScreenPoint {
            screen: screen.handle_of(uuid),
            x: i64::from(hit.x),
            y: i64::from(hit.y),
            distance: hit.distance,
        }))
    }
}

impl ScreenApi for GameContext {
    fn name(&self, this: &handles::Screen) -> ApiResult<Option<String>> {
        let screens = self.shared.screens.borrow();
        let Some((screen, _)) = screens.resolve(this) else {
            return Ok(None);
        };
        Ok(if screen.owner.is_none() {
            Some(self.with_names(|names| names.spell(&screen.id)))
        } else {
            screen.name.clone()
        })
    }

    fn exists(&self, this: &handles::Screen) -> ApiResult<bool> {
        Ok(self.shared.screens.borrow().resolve(this).is_some())
    }

    fn width(&self, this: &handles::Screen) -> ApiResult<i64> {
        Ok(self
            .shared
            .screens
            .borrow()
            .resolve(this)
            .map_or(0, |(s, _)| i64::from(s.columns * TILE)))
    }

    fn height(&self, this: &handles::Screen) -> ApiResult<i64> {
        Ok(self
            .shared
            .screens
            .borrow()
            .resolve(this)
            .map_or(0, |(s, _)| i64::from(s.rows * TILE)))
    }

    fn clear(&self, this: &handles::Screen, color: Option<String>) -> ApiResult<()> {
        let index = self.screen_color(color.as_deref())?;
        self.draw(this, |canvas, _| {
            let (w, h) = (canvas.width, canvas.height);
            canvas.fill(0, 0, w, h, index);
        });
        Ok(())
    }

    fn pixel(&self, this: &handles::Screen, x: i64, y: i64) -> ApiResult<Option<String>> {
        let index = self.draw(this, |canvas, _| {
            (x >= 0 && y >= 0 && canvas.inside(int(x), int(y))).then(|| canvas.get(int(x), int(y)))
        });
        let Some(Some(index)) = index else {
            return Ok(None);
        };
        Ok(Some(
            self.shared
                .screens
                .borrow_mut()
                .color_name(&*self.shared.platform, index),
        ))
    }

    fn set_pixel(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        color: Option<String>,
    ) -> ApiResult<()> {
        let index = self.screen_color(color.as_deref())?;
        self.draw(this, |canvas, _| canvas.set(int(x), int(y), index));
        Ok(())
    }

    fn pixels(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        width: i64,
        height: i64,
    ) -> ApiResult<Vec<String>> {
        if width < 0 || height < 0 || width.saturating_mul(height) > MAX_PIXELS {
            return Err(err(format!(
                "a block of pixels is at most {MAX_PIXELS}, not {width} by {height}"
            )));
        }
        let indices = self.draw(this, |canvas, _| {
            let mut out = Vec::with_capacity((width * height) as usize);
            for py in 0..height {
                for px in 0..width {
                    let (ax, ay) = (x + px, y + py);
                    out.push(
                        (ax >= 0
                            && ay >= 0
                            && ax < i64::from(canvas.width)
                            && ay < i64::from(canvas.height))
                        .then(|| canvas.get(ax as i32, ay as i32)),
                    );
                }
            }
            out
        });
        let Some(indices) = indices else {
            return Ok(Vec::new());
        };
        let platform = &*self.shared.platform;
        let mut screens = self.shared.screens.borrow_mut();
        Ok(indices
            .into_iter()
            .map(|i| i.map_or(CLEAR.to_owned(), |i| screens.color_name(platform, i)))
            .collect())
    }

    fn set_pixels(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        width: i64,
        colors: Vec<String>,
    ) -> ApiResult<()> {
        if width < 1 {
            return Err(err(format!("width is 1 pixel or more, not {width}")));
        }
        if colors.len() as i64 > MAX_PIXELS {
            return Err(err(format!(
                "set_pixels takes at most {MAX_PIXELS} colours, not {}",
                colors.len()
            )));
        }
        let indices: Vec<i32> = colors
            .iter()
            .map(|c| self.screen_color(Some(c)))
            .collect::<ApiResult<_>>()?;
        let w = width as usize;
        self.draw(this, |canvas, _| {
            for (row, chunk) in indices.chunks(w).enumerate() {
                canvas.write(int(x), int(y) + row as i32, chunk, 0, chunk.len());
            }
        });
        Ok(())
    }

    fn fill(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        width: i64,
        height: i64,
        color: String,
    ) -> ApiResult<()> {
        let index = self.screen_color(Some(&color))?;
        self.draw(this, |canvas, _| {
            canvas.fill(int(x), int(y), int(width), int(height), index)
        });
        Ok(())
    }

    fn rectangle(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        width: i64,
        height: i64,
        style: shapes::ShapeStyle,
    ) -> ApiResult<()> {
        let (fill, outline, thickness) = self.shape_style(&style)?;
        let radius = style.radius.unwrap_or(0);
        if radius < 0 {
            return Err(err(format!("style.radius is 0 or more, not {radius}")));
        }
        self.draw(this, |canvas, _| {
            canvas::rectangle(
                canvas,
                int(x),
                int(y),
                int(width),
                int(height),
                int(radius),
                fill,
                outline,
                thickness,
            )
        });
        Ok(())
    }

    fn line(
        &self,
        this: &handles::Screen,
        x1: i64,
        y1: i64,
        x2: i64,
        y2: i64,
        color: String,
        thickness: Option<i64>,
    ) -> ApiResult<()> {
        let index = self.screen_color(Some(&color))?;
        let t = thickness.unwrap_or(1);
        if !(1..=MAX_THICKNESS).contains(&t) {
            return Err(err(format!("thickness is 1 to {MAX_THICKNESS}, not {t}")));
        }
        self.draw(this, |canvas, _| {
            canvas::line(canvas, int(x1), int(y1), int(x2), int(y2), index, t as i32)
        });
        Ok(())
    }

    fn circle(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        radius: i64,
        style: shapes::ShapeStyle,
    ) -> ApiResult<()> {
        if radius < 0 {
            return Err(err(format!("radius is 0 or more, not {radius}")));
        }
        let (fill, outline, thickness) = self.shape_style(&style)?;
        self.draw(this, |canvas, _| {
            canvas::ellipse(
                canvas,
                int(x),
                int(y),
                int(radius),
                int(radius),
                fill,
                outline,
                thickness,
            )
        });
        Ok(())
    }

    fn ellipse(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        radius_x: i64,
        radius_y: i64,
        style: shapes::ShapeStyle,
    ) -> ApiResult<()> {
        if radius_x < 0 || radius_y < 0 {
            return Err(err(format!(
                "an ellipse's radii are 0 or more, not {radius_x} and {radius_y}"
            )));
        }
        let (fill, outline, thickness) = self.shape_style(&style)?;
        self.draw(this, |canvas, _| {
            canvas::ellipse(
                canvas,
                int(x),
                int(y),
                int(radius_x),
                int(radius_y),
                fill,
                outline,
                thickness,
            )
        });
        Ok(())
    }

    fn polygon(
        &self,
        this: &handles::Screen,
        points: Vec<i64>,
        style: shapes::ShapeStyle,
    ) -> ApiResult<()> {
        if points.len() < 6 || !points.len().is_multiple_of(2) {
            return Err(err(format!(
                "a polygon's points are x, y pairs, at least three of them: {} numbers isn't",
                points.len()
            )));
        }
        if points.len() > MAX_POINTS * 2 {
            return Err(err(format!("a polygon has at most {MAX_POINTS} points")));
        }
        let (fill, outline, thickness) = self.shape_style(&style)?;
        let points: Vec<i32> = points.into_iter().map(int).collect();
        self.draw(this, |canvas, _| {
            canvas::polygon(canvas, &points, fill, outline, thickness)
        });
        Ok(())
    }

    fn text(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        text: values::Text,
        options: Option<shapes::ScreenTextOptions>,
    ) -> ApiResult<shapes::ScreenTextSize> {
        let size = self.text_layout(options.as_ref(), |layout| {
            self.draw(this, |canvas, palette| {
                text::draw(canvas, palette, int(x), int(y), &text.0, layout)
            })
        })?;
        let (width, height) = size.unwrap_or((0, 0));
        Ok(shapes::ScreenTextSize {
            width: i64::from(width),
            height: i64::from(height),
        })
    }

    fn image(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        image: String,
        options: Option<shapes::ScreenImageOptions>,
    ) -> ApiResult<bool> {
        let path = self.texture_path(&image)?;
        let side = |field: &str, v: Option<i64>| match v {
            Some(v) if !(1..=MAX_SIDE).contains(&v) => {
                Err(err(format!("options.{field} is 1 to {MAX_SIDE}, not {v}")))
            }
            v => Ok(v),
        };
        let width = side("width", options.as_ref().and_then(|o| o.width))?;
        let height = side("height", options.as_ref().and_then(|o| o.height))?;
        let dither = match options.as_ref().and_then(|o| o.dither.as_deref()) {
            Some("none") => Dither::None,
            Some("ordered") => Dither::Ordered,
            _ => Dither::FloydSteinberg,
        };
        if self.shared.screens.borrow().resolve(this).is_none() {
            return Ok(false);
        }
        let picture = self.shared.screens.borrow_mut().picture(
            &*self.shared.platform,
            &path,
            width,
            height,
            dither,
        );
        let Some(picture) = picture else {
            return Ok(false);
        };
        self.draw(this, |canvas, _| {
            canvas::blit(canvas, int(x), int(y), &picture.colors, picture.width, 1)
        });
        Ok(true)
    }

    fn copy(
        &self,
        this: &handles::Screen,
        x: i64,
        y: i64,
        width: i64,
        height: i64,
        to_x: i64,
        to_y: i64,
    ) -> ApiResult<()> {
        if width.saturating_mul(height) > MAX_PIXELS * 64 {
            return Err(err("a copy is at most a screen's pixels"));
        }
        self.draw(this, |canvas, _| {
            canvas.copy(
                int(x),
                int(y),
                int(width),
                int(height),
                int(to_x),
                int(to_y),
            )
        });
        Ok(())
    }

    fn place(
        &self,
        this: &handles::Screen,
        location: values::Location,
        facing: String,
    ) -> ApiResult<bool> {
        if self.shared.screens.borrow().resolve(this).is_none() {
            return Ok(false);
        }
        let world = location.world.name;
        if !self.shared.platform.worlds().exists(world.clone()) {
            return Ok(false);
        }
        let facing = match facing.as_str() {
            "north" => ScreenFacing::North,
            "south" => ScreenFacing::South,
            "east" => ScreenFacing::East,
            _ => ScreenFacing::West,
        };
        let p = location.position;
        let placed = Placed {
            world,
            x: p.x.floor() as i32,
            y: p.y.floor() as i32,
            z: p.z.floor() as i32,
            facing,
        };
        self.place_screen(&this.id, Some(placed));
        Ok(true)
    }

    fn take_down(&self, this: &handles::Screen) -> ApiResult<bool> {
        let hangs = self
            .shared
            .screens
            .borrow()
            .resolve(this)
            .is_some_and(|(s, _)| s.placement.is_some());
        if hangs {
            self.place_screen(&this.id, None);
        }
        Ok(hangs)
    }

    fn location(&self, this: &handles::Screen) -> ApiResult<Option<values::Location>> {
        let screens = self.shared.screens.borrow();
        Ok(screens
            .resolve(this)
            .and_then(|(s, _)| s.placement.clone())
            .map(|p| values::Location {
                world: handles::World { name: p.world },
                position: values::Vec3 {
                    x: f64::from(p.x),
                    y: f64::from(p.y),
                    z: f64::from(p.z),
                },
                yaw: None,
                pitch: None,
            }))
    }

    fn facing(&self, this: &handles::Screen) -> ApiResult<Option<String>> {
        let screens = self.shared.screens.borrow();
        Ok(screens
            .resolve(this)
            .and_then(|(s, _)| s.placement.as_ref())
            .map(|p| netherforge_format::canonical::name_of(&p.facing)))
    }

    fn item(
        &self,
        this: &handles::Screen,
        column: Option<i64>,
        row: Option<i64>,
    ) -> ApiResult<values::Item> {
        let screens = self.shared.screens.borrow();
        let (screen, _) = screens
            .resolve(this)
            .ok_or_else(|| err("the screen is gone"))?;
        let (c, r) = (column.unwrap_or(0), row.unwrap_or(0));
        if !(0..i64::from(screen.columns)).contains(&c) || !(0..i64::from(screen.rows)).contains(&r)
        {
            return Err(err(format!(
                "the screen is {} by {} maps: there's no map at column {c}, row {r}",
                screen.columns, screen.rows
            )));
        }
        let map = screen.maps[(r * i64::from(screen.columns) + c) as usize];
        let item: ItemData = self.shared.platform.screens().map_item(map as i32);
        Ok(crate::items::to_value(&item))
    }

    fn show_to(&self, this: &handles::Screen, player: handles::Player) -> ApiResult<bool> {
        self.screen_shown(this, &player, true)
    }

    fn hide_from(&self, this: &handles::Screen, player: handles::Player) -> ApiResult<bool> {
        self.screen_shown(this, &player, false)
    }

    fn is_shown_to(&self, this: &handles::Screen, player: handles::Player) -> ApiResult<bool> {
        let screens = self.shared.screens.borrow();
        Ok(screens
            .resolve(this)
            .zip(parse_uuid(&player.id))
            .is_some_and(|((s, _), p)| s.viewers.contains_key(&p)))
    }

    fn viewers(&self, this: &handles::Screen) -> ApiResult<Vec<handles::Player>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.resolve(this).map_or_else(Vec::new, |(s, _)| {
            s.viewers
                .keys()
                .map(|p| handles::Player { id: uuid_text(p) })
                .collect()
        }))
    }

    fn for_player(
        &self,
        this: &handles::Screen,
        player: handles::Player,
    ) -> ApiResult<handles::Screen> {
        let uuid = parse_uuid(&player.id)
            .filter(|p| self.shared.platform.players().get(*p).is_some())
            .ok_or_else(|| err("a player's own view is for someone online"))?;
        let mut screens = self.shared.screens.borrow_mut();
        let own = screens
            .resolve(this)
            .ok_or_else(|| err("the screen is gone"))?
            .1;
        if own.is_some_and(|o| o != uuid) {
            return Err(err(
                "this is another player's own view: call for_player on the screen everyone sees",
            ));
        }
        let screen = screens.screens.get_mut(&this.id).expect("resolved");
        if !screen.personal.contains_key(&uuid) {
            // It starts as what everyone sees, which is what they have: only what's drawn on it from now on is news.
            let mut canvas = Canvas::new(screen.columns, screen.rows);
            canvas.copy_from(&screen.canvas);
            canvas.settle();
            screen.personal.insert(uuid, canvas);
        }
        Ok(screen.handle_for(Some(uuid)))
    }

    fn player(&self, this: &handles::Screen) -> ApiResult<Option<handles::Player>> {
        let screens = self.shared.screens.borrow();
        Ok(screens
            .resolve(this)
            .and_then(|(_, p)| p)
            .map(|p| handles::Player { id: uuid_text(&p) }))
    }

    /// Ends a player's own view: they're sent what everyone sees.
    fn release(&self, this: &handles::Screen) -> ApiResult<bool> {
        let playing = {
            let screens = self.shared.screens.borrow();
            match screens.resolve(this) {
                Some((screen, Some(player))) => screen.playbacks.get(&Some(player)).copied(),
                _ => None,
            }
        };
        if let Some(number) = playing {
            self.screens_context()
                .end(number, video::EndReason::Removed);
        }
        let released = {
            let mut screens = self.shared.screens.borrow_mut();
            let Some((_, Some(player))) = screens.resolve(this) else {
                return Ok(false);
            };
            let screen = screens.screens.get_mut(&this.id).expect("resolved");
            screen.personal.remove(&player);
            let tiles = screen.tiles();
            if let Some(viewer) = screen.viewers.get_mut(&player) {
                viewer.resend(tiles);
            }
            screen.handle_for(Some(player))
        };
        if let Some(target) = self.screens_context().target(released) {
            self.scripts.drop_target(&target);
        }
        Ok(true)
    }

    fn play(
        &self,
        this: &handles::Screen,
        video: String,
        options: Option<shapes::VideoPlayOptions>,
    ) -> ApiResult<Option<handles::Playback>> {
        let name = self.with_names(|names| names.resource("video", &video))?;
        let options = GameContext::play_options(options)?;
        let Some((id, viewer)) = self
            .shared
            .screens
            .borrow()
            .resolve(this)
            .map(|(s, p)| (s.id.clone(), p))
        else {
            return Ok(None);
        };
        let owner = self.scripts.host().running_scope();
        self.screens_context()
            .play(&id, viewer, &name, owner, options)
    }

    fn playback(&self, this: &handles::Screen) -> ApiResult<Option<handles::Playback>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.resolve(this).and_then(|(screen, viewer)| {
            let number = screen.playbacks.get(&viewer)?;
            screens.videos.live(*number).map(video::Playback::handle)
        }))
    }

    fn remove(&self, this: &handles::Screen) -> ApiResult<bool> {
        let project = {
            let screens = self.shared.screens.borrow();
            let Some((screen, _)) = screens.resolve(this) else {
                return Ok(false);
            };
            screen.owner.is_none().then(|| screen.id.clone())
        };
        if let Some(id) = project {
            let name = self.with_names(|names| names.spell(&id));
            return Err(err(format!(
                "screen \"{name}\" is the project's: its file says it's there, so it can't be removed"
            )));
        }
        self.screens_context().remove(&this.id, true);
        Ok(true)
    }

    fn on(
        &self,
        this: &handles::Screen,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Screen(this.clone()),
            "Screen:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Screen,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Screen(this.clone()),
            "Screen:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl GameContext {
    /// Hangs screen `id` at `placement` (none: nowhere), taking down where it hung.
    fn place_screen(&self, id: &str, placement: Option<Placed>) {
        let context = self.screens_context();
        context.take_down(id);
        context.leave_all(id);
        if let Some(screen) = self.shared.screens.borrow_mut().screens.get_mut(id) {
            screen.move_to(placement);
        }
        context.hang(id);
        context.view(id);
    }

    fn screen_shown(
        &self,
        this: &handles::Screen,
        player: &handles::Player,
        show: bool,
    ) -> ApiResult<bool> {
        let Some(uuid) =
            parse_uuid(&player.id).filter(|p| self.shared.platform.players().get(*p).is_some())
        else {
            return Ok(false);
        };
        {
            let mut screens = self.shared.screens.borrow_mut();
            if screens.resolve(this).is_none() {
                return Ok(false);
            }
            let screen = screens.screens.get_mut(&this.id).expect("resolved");
            if show {
                screen.hidden_from.remove(&uuid);
                screen.shown_to.insert(uuid);
            } else {
                screen.shown_to.remove(&uuid);
                screen.hidden_from.insert(uuid);
            }
        }
        self.screens_context().view(&this.id);
        Ok(true)
    }
}

// ---- the service ----------------------------------------------------------------

/// The screens service: defines the project's, hangs and views them, and sends what changed once a tick.
pub struct ScreensService {
    pub shared: Rc<Shared>,
    pub scripts: RefCell<Option<crate::Scripts>>,
    /// The project's namespace.
    pub home: RefCell<String>,
}

impl ScreensService {
    fn context(&self, room: &Room) -> ScreensContext {
        ScreensContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }

    fn running(&self) -> Option<ScreensContext> {
        Some(ScreensContext {
            shared: self.shared.clone(),
            scripts: self.scripts.borrow().clone()?,
        })
    }

    fn ids(&self) -> Vec<String> {
        self.shared
            .screens
            .borrow()
            .screens
            .keys()
            .cloned()
            .collect()
    }

    fn define(&self, project: &Define) {
        let platform = project.platform;
        let store = &self.shared.store;
        let home = project.project.namespace().to_owned();
        let mut screens = self.shared.screens.borrow_mut();
        screens.kept = store.screen_maps();
        let most_kept = screens
            .kept
            .values()
            .flat_map(|k| k.values().copied())
            .max()
            .unwrap_or(MAP_BASE - 1);
        // Past every number ever given, so an item or frame of a screen that's gone never shows another.
        screens.next_map = MAP_BASE
            .max(store.screen_map_next().unwrap_or(0))
            .max(most_kept + 1);
        screens.font_files = project
            .project
            .running::<ScreenFontFile>()
            .into_iter()
            .map(|(id, f)| (id, f.clone()))
            .collect();
        screens.fonts.clear();
        screens.folders = folders(project, "screen");
        screens.font_folders = folders(project, "screen_font");
        screens.files = project
            .project
            .present("screen")
            .into_iter()
            .map(|(id, _)| (id, true))
            .collect();
        let mut serial = screens.video_serial;
        screens.videos.records = video::load_videos(project, screens.paths.as_ref(), &mut serial);
        screens.video_serial = serial;
        screens.screens.clear();
        screens.owners.clear();
        for (name, file) in project.project.running::<ScreenFile>() {
            let screen = screens.project_screen(store, platform, &name, file, &home);
            screens.screens.insert(name, screen);
        }
    }

    fn reload_screen(
        &self,
        context: &ScreensContext,
        id: &str,
        next: Option<ScreenFile>,
        home: &str,
    ) {
        let platform = &*self.shared.platform;
        let exists = self.shared.screens.borrow().screens.contains_key(id);
        if !exists {
            let Some(next) = next else { return };
            let screen = self.shared.screens.borrow_mut().project_screen(
                &self.shared.store,
                platform,
                id,
                &next,
                home,
            );
            self.shared
                .screens
                .borrow_mut()
                .screens
                .insert(id.to_owned(), screen);
            context.view(id);
            context.start_script(id);
            context.autoplay(id, home);
            return;
        }
        context.stop_script(id);
        context.end_where(video::EndReason::Unloaded, |p| {
            p.screen == id && p.owner.is_none()
        });
        let Some(next) = next else {
            context.remove(id, false);
            self.shared.screens.borrow_mut().screens.shift_remove(id);
            return;
        };
        let resized = {
            let mut screens = self.shared.screens.borrow_mut();
            let settings = screens.settings_of(platform, &next);
            let screen = screens.screens.get_mut(id).expect("there");
            screen.file = Some(next.clone());
            screen.settings = settings;
            (next.width != screen.columns || next.height != screen.rows)
                .then(|| screen.namespace.clone())
        };
        if let Some(namespace) = resized {
            context.end_where(video::EndReason::Removed, |p| p.screen == id);
            context.take_down(id);
            let bare = netherforge_format::kinds::split_package(id).1.to_owned();
            let mut screens = self.shared.screens.borrow_mut();
            let maps = screens.maps(
                &self.shared.store,
                &namespace,
                Some(&format!("screen:{bare}")),
                next.width,
                next.height,
            );
            screens
                .screens
                .get_mut(id)
                .expect("there")
                .resize(next.width, next.height, maps);
        } else if let Some(screen) = self.shared.screens.borrow_mut().screens.get_mut(id) {
            let (w, h, background) = (
                screen.canvas.width,
                screen.canvas.height,
                screen.settings.background,
            );
            screen.canvas.fill(0, 0, w, h, background);
        }
        let placement = placement_of(&next);
        let moved = self
            .shared
            .screens
            .borrow()
            .screens
            .get(id)
            .is_some_and(|s| s.placement != placement);
        if moved {
            context.take_down(id);
        }
        if let Some(screen) = self.shared.screens.borrow_mut().screens.get_mut(id) {
            screen.move_to(placement);
        }
        context.view(id);
        context.start_script(id);
        context.autoplay(id, home);
    }

    /// The project's videos changed: those playing keep the frames they opened; the next play takes the new ones.
    fn reload_videos(&self, ids: &BTreeSet<String>, project: &Define) {
        let mut screens = self.shared.screens.borrow_mut();
        let mut serial = screens.video_serial;
        let mut fresh = video::load_videos(project, screens.paths.as_ref(), &mut serial);
        screens.video_serial = serial;
        let present: BTreeSet<String> = project
            .project
            .present("video")
            .into_iter()
            .map(|(id, _)| id)
            .collect();
        for id in ids {
            match fresh.remove(id) {
                Some(video) => {
                    screens.videos.records.insert(id.clone(), video);
                }
                None if !present.contains(id) => {
                    screens.videos.records.remove(id);
                }
                // A file with errors keeps its last good version.
                None => {}
            }
        }
    }
}

/// Each resource of `kind`'s folder, a package's at its package path.
fn folders(project: &Define, kind: &str) -> BTreeMap<String, String> {
    project
        .project
        .present(kind)
        .into_iter()
        .map(|(id, resource)| {
            let found = &resource.found;
            let folder = found.kind.location_of(&found.id, &found.group);
            let folder = match netherforge_format::kinds::split_package(&id) {
                (Some(namespace), _) => netherforge_format::kinds::package_path(namespace, &folder),
                (None, _) => folder,
            };
            (id, folder)
        })
        .collect()
}

impl Service for ScreensService {
    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        let screens = self.shared.screens.borrow();
        let count = screens
            .screens
            .values()
            .filter(|s| s.owner == Some(scope) && !s.removed)
            .count();
        let videos = screens
            .videos
            .playbacks
            .values()
            .filter(|p| p.owner == Some(scope))
            .count();
        vec![("screens", count), ("videos", videos)]
    }

    fn name(&self) -> &'static str {
        "screens"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["screen", "screen_font", "video"]
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
        *self.home.borrow_mut() = project.project.namespace().to_owned();
        self.define(project);
    }

    /// Who sees each is known before its script draws, so its first frame reaches them on the first tick.
    fn after_start(&self, room: &Room) {
        let context = self.context(room);
        for id in self.ids() {
            context.view(&id);
        }
        for id in self.ids() {
            context.start_script(&id);
        }
        let home = self.home.borrow().clone();
        for id in self.ids() {
            context.autoplay(&id, &home);
        }
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        context.advance();
        let ticks = {
            let mut screens = self.shared.screens.borrow_mut();
            screens.ticks += 1;
            let ticks = screens.ticks;
            if ticks.is_multiple_of(HANG_EVERY) {
                for screen in screens.screens.values_mut() {
                    screen.rehang = true;
                }
            }
            ticks
        };
        for id in self.ids() {
            let (removed, rehang) = self
                .shared
                .screens
                .borrow()
                .screens
                .get(&id)
                .map_or((true, false), |s| (s.removed, s.rehang));
            if removed {
                continue;
            }
            if rehang {
                context.hang(&id);
            }
            if ticks.is_multiple_of(VIEW_EVERY) {
                context.view(&id);
            }
        }
        context.hover();
        context.flush();
    }

    fn reload(&self, room: &Room, kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let context = self.context(room);
        let platform = project.platform;
        if kind == "video" {
            return self.reload_videos(ids, project);
        }
        if kind == "screen_font" {
            let mut screens = self.shared.screens.borrow_mut();
            screens.font_files = project
                .project
                .running::<ScreenFontFile>()
                .into_iter()
                .map(|(id, f)| (id, f.clone()))
                .collect();
            screens.font_folders = folders(project, "screen_font");
            for id in ids {
                screens.fonts.remove(id);
            }
            return;
        }
        let running: BTreeMap<String, ScreenFile> = project
            .project
            .running::<ScreenFile>()
            .into_iter()
            .map(|(id, f)| (id, f.clone()))
            .collect();
        let present: BTreeSet<String> = project
            .project
            .present("screen")
            .into_iter()
            .map(|(id, _)| id)
            .collect();
        {
            let mut screens = self.shared.screens.borrow_mut();
            screens.folders = folders(project, "screen");
            screens.files = present.iter().map(|id| (id.clone(), true)).collect();
            screens.pictures.clear();
            let _ = platform;
        }
        let home = project.project.namespace().to_owned();
        for id in ids {
            match running.get(id) {
                Some(next) => self.reload_screen(&context, id, Some(next.clone()), &home),
                None if !present.contains(id) => self.reload_screen(&context, id, None, &home),
                // A file with errors keeps its last good version running.
                None => {}
            }
        }
    }

    /// What a stopped scope made goes.
    fn scope_released(&self, scopes: &[ScopeId]) {
        let Some(context) = self.running() else {
            return;
        };
        context.end_where(video::EndReason::Unloaded, |p| {
            p.owner.is_some_and(|o| scopes.contains(&o))
        });
        let gone: Vec<String> = self
            .shared
            .screens
            .borrow()
            .screens
            .values()
            .filter(|s| s.owner.is_some_and(|o| scopes.contains(&o)))
            .map(|s| s.id.clone())
            .collect();
        for id in gone {
            context.remove(&id, false);
        }
    }

    fn player_joined(&self, _player: &PlayerRef) {
        let Some(context) = self.running() else {
            return;
        };
        for id in self.ids() {
            context.view(&id);
        }
    }

    fn player_quit(&self, player: &PlayerRef) {
        if let Some(context) = self.running() {
            let uuid = player.uuid;
            context.forget(uuid);
            context.end_where(video::EndReason::Removed, |p| p.viewer == Some(uuid));
            context.silence_for(uuid);
        }
        let released: Vec<handles::Screen> = {
            let mut screens = self.shared.screens.borrow_mut();
            let mut released = Vec::new();
            for screen in screens.screens.values_mut() {
                screen.viewers.shift_remove(&player.uuid);
                if screen.personal.remove(&player.uuid).is_some() {
                    released.push(screen.handle_for(Some(player.uuid)));
                }
            }
            released
        };
        let Some(context) = self.running() else {
            return;
        };
        for handle in released {
            if let Some(target) = context.target(handle) {
                context.scripts.drop_target(&target);
            }
        }
    }

    /// Our frames from a chunk that came back: anything of a screen's no screen owns is left over, and goes.
    fn entities_loaded(&self, tagged: &HashMap<Uuid, EntityTag>, _untagged: &[Uuid]) {
        let platform = &*self.shared.platform;
        let screens = self.shared.screens.borrow();
        for (entity, tag) in tagged {
            if tag.role == EntityRole::Screen && !screens.owners.contains_key(entity) {
                platform.entities().remove(*entity);
            }
        }
    }

    /// A frame that went (broken, or with its chunk): hung again when it can be.
    fn entity_gone(&self, entity: Uuid) {
        let mut screens = self.shared.screens.borrow_mut();
        let Some((id, tile)) = screens.owners.remove(&entity) else {
            return;
        };
        if let Some(screen) = screens.screens.get_mut(&id)
            && screen.frames.get(tile) == Some(&Some(entity))
        {
            screen.frames[tile] = None;
            screen.rehang = true;
        }
    }

    fn problems(&self) -> Vec<netherforge_format::problem::Problem> {
        self.shared.screens.borrow().videos.stale()
    }

    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        context.end_where(video::EndReason::Unloaded, |_| true);
        for id in self.ids() {
            context.stop_script(&id);
            context.take_down(&id);
        }
        let mut screens = self.shared.screens.borrow_mut();
        let (config, read, paths) = (screens.config, screens.read.clone(), screens.paths.clone());
        *screens = Screens::default();
        screens.config = config;
        screens.read = read;
        screens.paths = paths;
    }
}
