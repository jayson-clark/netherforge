//! The videos screens play (Kotlin's `ScreenVideos`, `Playback`, `FrameFeed`, `NfvReader` and `PlaybackImpl`):
//! each video's converted frames and sounds (`videos/<id>/`), and every [`Playback`] going.
//!
//! - **Frames** are format's `nfv` layout, read a frame at a time from the file ([`NfvReader`]), so a long video
//!   costs one frame's memory. A playback's [`FrameFeed`] reads them ahead of where it plays on the videos' [`Lane`]
//!   (a thread of the session's), so the main thread never touches the file: it asks for the frames it will draw
//!   next and draws those that are ready. One that isn't holds the picture for a tick, as a slow disk would show.
//!   Every playback of a video file shares one reader while any of them (or a read still going) holds it.
//! - **Sound** is played to the players a playback is drawn for, near where its screen hangs: the whole sound from
//!   its start, or a piece at a time ([`Audio::chunks`]) once it was paused or moved through, since the game can't
//!   start a sound mid-file.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::PlaybackApi;
use netherforge_api::codec::Codec;
use netherforge_api::codecs;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiResult, shapes};
use netherforge_format::canonical::name_of;
use netherforge_format::kind::video::{
    self, AUDIO, AUDIO_CHUNK_SECONDS, DEFAULT_FPS, FRAMES, VideoFile, VideoFit,
};
use netherforge_format::nfv::{self, HEADER_SIZE, Header, KEY, MAX_FRAME};
use netherforge_format::problem::{Problem, codes};
use netherforge_luau::ScopeId;
use netherforge_platform::{Platform, SoundPlay, Uuid};

use super::canvas::Canvas;
use super::{ScreenState, Screens, ScreensContext, err};
use crate::scripts::Target;
use crate::session::Define;
use crate::world::GameContext;

pub const DEFAULT_AUDIO_RANGE: f64 = 32.0;
pub const MIN_SPEED: f64 = 0.25;
pub const MAX_SPEED: f64 = 4.0;
/// How many frames past the one drawn are read ahead: a quarter of a second at 20 a second.
pub const AHEAD: i64 = 5;
const TICKS_PER_SECOND: f64 = 20.0;
const SOUND_CATEGORY: &str = "record";
/// Seconds in each piece of a video's sound.
const CHUNK: f64 = AUDIO_CHUNK_SECONDS as f64;

/// Finds a project file on disk by project path.
pub type FindPath = dyn Fn(&str) -> Option<PathBuf>;

/// Finds a project file on disk by project path (a package's `ns:path`): what videos' frames are read from, a frame
/// at a time. The adapter gives one; with none, no video plays.
#[derive(Clone)]
pub struct ProjectPaths(pub Rc<FindPath>);

impl std::fmt::Debug for ProjectPaths {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ProjectPaths")
    }
}

// ---- the lane ----------------------------------------------------------------

pub type Job = Box<dyn FnOnce() + Send>;

/// Where videos' frames are read (Kotlin's workers' `"videos"` lane): jobs run in the order they're given.
#[derive(Clone)]
pub struct Lane(Arc<dyn Fn(Job) + Send + Sync>);

impl Lane {
    pub fn new(run: impl Fn(Job) + Send + Sync + 'static) -> Lane {
        Lane(Arc::new(run))
    }

    /// A thread of its own, which ends once the last clone of the lane goes and its jobs are done.
    pub fn thread(name: &str) -> Lane {
        let (send, jobs) = mpsc::channel::<Job>();
        let spawned = std::thread::Builder::new()
            .name(name.to_owned())
            .spawn(move || {
                for job in jobs {
                    // A job that panics mustn't take the lane with it.
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(job));
                }
            });
        match spawned {
            Ok(_) => Lane::new(move |job| {
                let _ = send.send(job);
            }),
            Err(_) => Lane::inline(),
        }
    }

    /// Runs each job at once, on the caller's thread: for tests.
    pub fn inline() -> Lane {
        Lane::new(|job| job())
    }

    pub fn run(&self, job: Job) {
        (self.0)(job)
    }
}

// ---- the file ----------------------------------------------------------------

/// A video's converted frames, open: its header, the index of where each frame starts, and the file.
pub struct NfvReader {
    file: File,
    pub header: Header,
    offsets: Vec<u64>,
}

/// One frame, inflated: every pixel (`key`), or the rectangles that changed.
pub struct Frame {
    pub key: bool,
    pub payload: Vec<u8>,
}

fn cut_short(_: std::io::Error) -> String {
    "it's cut short".into()
}

impl NfvReader {
    /// Opens `path`, reading its header and index; why not, when it isn't a video file this reads.
    pub fn open(path: &Path) -> Result<NfvReader, String> {
        let mut file = File::open(path).map_err(|e| e.to_string())?;
        let mut head = [0u8; HEADER_SIZE];
        file.read_exact(&mut head)
            .map_err(|_| "it isn't a converted video (no NFV1 at its start)".to_owned())?;
        let header = Header::parse(&head)?;
        let length = file.metadata().map_err(|e| e.to_string())?.len();
        // Each frame has 8 bytes of index after the header: a count the file can't hold isn't believed.
        if u64::from(header.frames) * 8 > length.saturating_sub(HEADER_SIZE as u64) {
            return Err(format!(
                "its header says {} frames, more than the file holds",
                header.frames
            ));
        }
        let mut index = vec![0u8; header.frames as usize * 8];
        file.read_exact(&mut index).map_err(cut_short)?;
        Ok(NfvReader {
            file,
            header,
            offsets: nfv::index(&index),
        })
    }

    /// Frame `index`, read and inflated.
    pub fn frame(&mut self, index: usize) -> Result<Frame, String> {
        let at = *self
            .offsets
            .get(index)
            .ok_or_else(|| format!("no frame {index} of {}", self.offsets.len()))?;
        self.file.seek(SeekFrom::Start(at)).map_err(cut_short)?;
        let mut head = [0u8; 5];
        self.file.read_exact(&mut head).map_err(cut_short)?;
        let length = u32::from_be_bytes(head[1..5].try_into().expect("4 bytes")) as usize;
        if length > MAX_FRAME {
            return Err(format!("frame {index} says it's {length} bytes"));
        }
        let mut packed = vec![0u8; length];
        self.file.read_exact(&mut packed).map_err(cut_short)?;
        Ok(Frame {
            key: head[0] == KEY,
            payload: nfv::unpack(&packed)?,
        })
    }
}

/// Draws `frame` onto `canvas` with the video's top left at (`x`, `y`); what falls off the canvas is left out.
pub fn draw(
    header: &Header,
    frame: &Frame,
    canvas: &mut Canvas,
    x: i32,
    y: i32,
) -> Result<(), String> {
    let mut row: Vec<i32> = Vec::new();
    nfv::apply(header, frame.key, &frame.payload, |px, py, pixels| {
        row.clear();
        row.extend(pixels.iter().map(|&p| i32::from(p)));
        canvas.write(x + px as i32, y + py as i32, &row, 0, row.len());
    })
}

// ---- reading ahead ----------------------------------------------------------------

struct Feed {
    reader: Arc<Mutex<NfvReader>>,
    total: i64,
    frames: Mutex<HashMap<i64, Arc<Frame>>>,
    /// The frames wanted, in the order they're read.
    window: Mutex<Vec<i64>>,
    reading: AtomicBool,
    closed: AtomicBool,
    failure: Mutex<Option<String>>,
}

impl Feed {
    fn next_missing(&self) -> Option<i64> {
        let window = self.window.lock().expect("window");
        let frames = self.frames.lock().expect("frames");
        window.iter().copied().find(|i| !frames.contains_key(i))
    }

    fn stopped(&self) -> bool {
        self.closed.load(Ordering::SeqCst) || self.failure.lock().expect("failure").is_some()
    }
}

/// A playback's frames, read and inflated ahead of where it plays, on the lane.
pub struct FrameFeed {
    feed: Arc<Feed>,
    lane: Lane,
}

impl FrameFeed {
    pub fn new(reader: Arc<Mutex<NfvReader>>, lane: Lane) -> FrameFeed {
        let total = i64::from(reader.lock().expect("reader").header.frames);
        FrameFeed {
            feed: Arc::new(Feed {
                reader,
                total,
                frames: Mutex::new(HashMap::new()),
                window: Mutex::new(Vec::new()),
                reading: AtomicBool::new(false),
                closed: AtomicBool::new(false),
                failure: Mutex::new(None),
            }),
            lane,
        }
    }

    /// Frame `index`, when it has been read.
    pub fn ready(&self, index: i64) -> Option<Arc<Frame>> {
        self.feed
            .frames
            .lock()
            .expect("frames")
            .get(&index)
            .cloned()
    }

    /// Why reading stopped, once it has: the playback ends for it.
    pub fn failure(&self) -> Option<String> {
        self.feed.failure.lock().expect("failure").clone()
    }

    /// Asks for frames `from` through `through`: read in order, and the rest forgotten. Past the video's end
    /// they're its first frames again when it `wraps` (it loops), and none when it doesn't.
    pub fn want(&self, from: i64, through: i64, wraps: bool) {
        let total = self.feed.total;
        if self.feed.closed.load(Ordering::SeqCst) || total == 0 {
            return;
        }
        let mut wanted: Vec<i64> = Vec::new();
        for index in from.max(0)..=through {
            let index = match index < total {
                true => index,
                false if wraps => index % total,
                false => break,
            };
            if !wanted.contains(&index) {
                wanted.push(index);
            }
        }
        self.feed
            .frames
            .lock()
            .expect("frames")
            .retain(|i, _| wanted.contains(i));
        *self.feed.window.lock().expect("window") = wanted;
        if self.feed.next_missing().is_some() {
            read(&self.feed, &self.lane);
        }
    }

    /// Stops reading for it and lets go of what it read.
    pub fn close(&self) {
        self.feed.closed.store(true, Ordering::SeqCst);
        self.feed.frames.lock().expect("frames").clear();
    }
}

fn read(feed: &Arc<Feed>, lane: &Lane) {
    if feed
        .reading
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }
    let (feed, again) = (feed.clone(), lane.clone());
    lane.run(Box::new(move || {
        while !feed.stopped() {
            let Some(next) = feed.next_missing() else {
                break;
            };
            let frame = feed.reader.lock().expect("reader").frame(next as usize);
            match frame {
                Ok(frame) => {
                    feed.frames
                        .lock()
                        .expect("frames")
                        .insert(next, Arc::new(frame));
                }
                Err(e) => *feed.failure.lock().expect("failure") = Some(e),
            }
        }
        feed.reading.store(false, Ordering::SeqCst);
        // A want that came as it finished is read now rather than at the next want.
        if !feed.stopped() && feed.next_missing().is_some() {
            read(&feed, &again);
        }
    }));
}

// ---- playbacks ----------------------------------------------------------------

/// A video's sounds in the resource pack: `whole` from its start, and pieces of five seconds each.
pub struct Audio {
    pub whole: String,
    pub chunks: Vec<String>,
}

/// A video as the server runs it: its file, where its frames are (none until converted) and its sounds, and why
/// its frames don't match its file, when they don't. `serial` tells it from the video before a reload.
pub struct Video {
    pub file: VideoFile,
    pub frames: Option<String>,
    pub audio: Option<Rc<Audio>>,
    pub stale: Option<Problem>,
    /// How long its converted frames last, when they read.
    pub length: Option<f64>,
    serial: u64,
}

/// What `screen:play` takes, checked.
#[derive(Clone, Copy, Debug)]
pub struct PlayOptions {
    pub looping: bool,
    pub speed: f64,
    pub start: f64,
    pub volume: f64,
    pub x: i32,
    pub y: i32,
    pub audio_range: f64,
}

impl Default for PlayOptions {
    fn default() -> PlayOptions {
        PlayOptions {
            looping: false,
            speed: 1.0,
            start: 0.0,
            volume: 1.0,
            x: 0,
            y: 0,
            audio_range: DEFAULT_AUDIO_RANGE,
        }
    }
}

/// Why a video ended, as `event.reason` says it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndReason {
    Finished,
    Stopped,
    Replaced,
    Removed,
    Unloaded,
}

impl EndReason {
    pub fn name(self) -> &'static str {
        match self {
            EndReason::Finished => "finished",
            EndReason::Stopped => "stopped",
            EndReason::Replaced => "replaced",
            EndReason::Removed => "removed",
            EndReason::Unloaded => "unloaded",
        }
    }
}

/// A video playing on a screen: its frames drawn onto the canvas of `screen` it was played on (`viewer`'s own view,
/// or the one everyone sees), with their top left at (`x`, `y`). `position` is where it is in seconds; each tick
/// moves it by `speed` / 20 and draws the frames it passed (from the key frame before when it jumped).
pub struct Playback {
    pub number: i64,
    /// The video as the project names it (`trailer`, `acme:trailer`).
    pub video: String,
    pub screen: String,
    /// Whose own view it plays on; none for the screen everyone sees.
    pub viewer: Option<Uuid>,
    pub header: Header,
    feed: FrameFeed,
    /// The scope that played it, or none for the screen's file (`video` in `screen.json`).
    pub owner: Option<ScopeId>,
    pub x: i32,
    pub y: i32,
    pub looping: bool,
    pub speed: f64,
    pub volume: f64,
    pub audio_range: f64,
    pub audio: Option<Rc<Audio>>,
    pub position: f64,
    pub paused: bool,
    pub ended: bool,
    /// The frame last drawn; -1 before the first.
    pub shown: i64,
    /// Who hears it from its start, the whole sound, and who hears it a piece at a time (and which piece).
    whole_for: HashSet<Uuid>,
    piece_for: HashMap<Uuid, usize>,
    /// Whether its sound may play from the start now: it's at its start and hasn't been moved through, so the whole
    /// sound lines up. Anything that moves it (a seek, a pause, a speed) puts everyone on pieces.
    from_start: bool,
}

impl Playback {
    pub fn handle(&self) -> handles::Playback {
        handles::Playback {
            number: self.number,
            video: self.video.clone(),
        }
    }

    /// The frame due at `position`.
    pub fn frame_at(&self, position: f64) -> i64 {
        (position * f64::from(self.header.fps) + 1e-9).floor() as i64
    }

    /// Draws every frame up to `target` onto `canvas`: on from the one shown when that's within a key interval,
    /// else from the key frame at or before `target`. How many were drawn; none while the feed hasn't read them yet
    /// (it's asked to), so the picture holds until they're there. What the feed couldn't read is the error.
    pub fn draw_to(&mut self, target: i64, canvas: &mut Canvas) -> Result<usize, String> {
        let total = i64::from(self.header.frames);
        if total == 0 {
            return Ok(0);
        }
        if let Some(failure) = self.feed.failure() {
            return Err(failure);
        }
        let last = target.clamp(0, total - 1);
        if last == self.shown {
            self.feed.want(last + 1, last + AHEAD, self.looping);
            return Ok(0);
        }
        let interval = i64::from(self.header.key_interval);
        let from = if last > self.shown && last - self.shown <= interval && self.shown >= 0 {
            self.shown + 1
        } else {
            self.header.key_before(last as usize) as i64
        };
        let mut frames = Vec::new();
        for index in from..=last {
            match self.feed.ready(index) {
                Some(frame) => frames.push(frame),
                None => {
                    self.feed.want(from, last + AHEAD, self.looping);
                    return Ok(0);
                }
            }
        }
        for frame in &frames {
            draw(&self.header, frame, canvas, self.x, self.y)?;
        }
        self.shown = last;
        // What comes next, read while this tick goes on: a looping video's start, once it's near its end.
        self.feed.want(last + 1, last + AHEAD, self.looping);
        Ok(frames.len())
    }

    /// Moves to `seconds` (held to the video), as a jump: the sound picks up again at the next piece.
    pub fn seek(&mut self, seconds: f64) {
        self.position = seconds.clamp(0.0, self.header.length());
        self.from_start = false;
    }

    fn audible(&self) -> bool {
        self.audio.is_some() && !self.paused && self.speed == 1.0 && self.volume > 0.0
    }
}

/// The session's videos and every playback going.
#[derive(Default)]
pub struct Videos {
    pub records: BTreeMap<String, Video>,
    /// Every playback going, in the order they started; one ending stays here while its `end` is heard.
    pub playbacks: IndexMap<i64, Playback>,
    next_playback: i64,
    /// Each video's frames file while anything reads it, by the video's serial.
    open: HashMap<u64, std::sync::Weak<Mutex<NfvReader>>>,
    lane: Option<Lane>,
}

impl Videos {
    pub fn names(&self) -> Vec<String> {
        self.records.keys().cloned().collect()
    }

    pub fn stale(&self) -> Vec<Problem> {
        self.records
            .values()
            .filter_map(|v| v.stale.clone())
            .collect()
    }

    /// The playback `number`, while it hasn't ended.
    pub fn live(&self, number: i64) -> Option<&Playback> {
        self.playbacks.get(&number).filter(|p| !p.ended)
    }

    fn lane(&mut self, inline: bool) -> Lane {
        self.lane
            .get_or_insert_with(|| {
                if inline {
                    Lane::inline()
                } else {
                    Lane::thread("netherforge-videos")
                }
            })
            .clone()
    }
}

/// Every video of the project, everywhere: by the name the server knows each by, with what's in its folder and
/// where that is. A video with errors is left out.
pub(super) fn load_videos(
    project: &Define,
    paths: Option<&ProjectPaths>,
    serial: &mut u64,
) -> BTreeMap<String, Video> {
    let home = project.project.namespace().to_owned();
    let running: BTreeMap<String, VideoFile> = project
        .project
        .running::<VideoFile>()
        .into_iter()
        .map(|(id, f)| (id, f.clone()))
        .collect();
    let folders = super::folders(project, "video");
    let mut videos = BTreeMap::new();
    for (name, resource) in project.project.present("video") {
        let (Some(file), Some(folder)) = (running.get(&name), folders.get(&name)) else {
            continue;
        };
        *serial += 1;
        let video = video_of(
            &name,
            file,
            &resource.found.inside,
            folder,
            &home,
            paths,
            *serial,
        );
        videos.insert(name, video);
    }
    videos
}

fn video_of(
    name: &str,
    file: &VideoFile,
    inside: &BTreeSet<String>,
    folder: &str,
    home: &str,
    paths: Option<&ProjectPaths>,
    serial: u64,
) -> Video {
    let frames = inside
        .contains(FRAMES)
        .then(|| format!("{folder}/{FRAMES}"));
    let (package, id) = netherforge_format::kinds::split_package(name);
    let namespace = package.unwrap_or(home);
    let audio = (file.audio.unwrap_or(true) && inside.contains(AUDIO)).then(|| {
        let pieces: BTreeSet<u32> = inside.iter().filter_map(|f| video::chunk_of(f)).collect();
        let chunks = (0..)
            .take_while(|i| pieces.contains(i))
            .map(|i| format!("{namespace}:{}", video::chunk_key(id, i)))
            .collect();
        Rc::new(Audio {
            whole: format!("{namespace}:{}", video::sound_key(id)),
            chunks,
        })
    });
    let main = format!("{folder}/{}", video::FILE_NAME);
    let header = frames
        .as_ref()
        .and_then(|f| paths.and_then(|p| (p.0)(f)))
        .map(|path| NfvReader::open(&path).map(|r| r.header));
    Video {
        file: file.clone(),
        frames,
        audio,
        stale: header.as_ref().and_then(|h| staleness(file, h, &main)),
        length: header.and_then(Result::ok).map(|h| h.length()),
        serial,
    }
}

/// Why the frames whose `header` this is weren't converted for `file` as it is now (or can't be read), or none
/// when they were.
fn staleness(file: &VideoFile, header: &Result<Header, String>, main: &str) -> Option<Problem> {
    let header = match header {
        Ok(header) => header,
        Err(e) => {
            return Some(codes::VIDEO_STALE.at(
                main,
                format!("Its converted frames can't be read ({e}): convert it again"),
                None,
            ));
        }
    };
    let mut differs = Vec::new();
    if i32::from(header.columns) != file.width || i32::from(header.rows) != file.height {
        differs.push(format!("{} by {} maps", header.columns, header.rows));
    }
    if i32::from(header.fps) != file.fps.unwrap_or(DEFAULT_FPS) {
        differs.push(format!("{} frames a second", header.fps));
    }
    if header.dither() != file.dither.unwrap_or_default() {
        differs.push(format!("dithered {}", name_of(&header.dither())));
    }
    if header.fit() != file.fit.unwrap_or(VideoFit::Contain) {
        differs.push(format!("fit {}", name_of(&header.fit())));
    }
    if header.audio != file.audio.unwrap_or(true) {
        differs.push(
            if header.audio {
                "with sound"
            } else {
                "without sound"
            }
            .to_owned(),
        );
    }
    (!differs.is_empty()).then(|| {
        codes::VIDEO_STALE.at(
            main,
            format!(
                "Its frames were converted {}: convert it again to match its file",
                differs.join(", ")
            ),
            None,
        )
    })
}

// ---- sound ----------------------------------------------------------------

/// Who hears `playback` now: who it's drawn for, near where its screen hangs.
fn listeners(playback: &Playback, screen: &ScreenState, platform: &dyn Platform) -> Vec<Uuid> {
    screen
        .viewers
        .keys()
        .copied()
        .filter(|player| {
            playback.viewer.is_none_or(|v| v == *player)
                && (screen.placement.is_none()
                    || platform
                        .players()
                        .location(*player)
                        .is_some_and(|at| screen.is_near(at.x, at.y, at.z, playback.audio_range)))
        })
        .collect()
}

fn play_to(platform: &dyn Platform, player: Uuid, sound: &str, volume: f64) -> bool {
    platform.sounds().play_to(
        player,
        SoundPlay {
            sound: sound.to_owned(),
            category: SOUND_CATEGORY.to_owned(),
            volume,
            pitch: 1.0,
        },
    )
}

/// Plays the whole sound from its start to everyone who hears it now.
fn start_whole(playback: &mut Playback, screen: &ScreenState, platform: &dyn Platform) {
    let Some(audio) = playback.audio.clone() else {
        return;
    };
    if !playback.audible() {
        return;
    }
    for player in listeners(playback, screen, platform) {
        if play_to(platform, player, &audio.whole, playback.volume) {
            playback.whole_for.insert(player);
        }
    }
}

fn piece_of(seconds: f64) -> i64 {
    (seconds / CHUNK + 1e-9).floor() as i64
}

/// Its sound as it moved from `before` to `now`: those out of earshot stopped, and a new piece started at each
/// boundary.
fn sound(
    playback: &mut Playback,
    screen: &ScreenState,
    platform: &dyn Platform,
    before: f64,
    now: f64,
) {
    let Some(audio) = playback.audio.clone() else {
        return;
    };
    if !playback.audible() {
        return silence(playback, None, platform);
    }
    let hearing = listeners(playback, screen, platform);
    let hearing_set: HashSet<Uuid> = hearing.iter().copied().collect();
    let gone: Vec<Uuid> = playback
        .whole_for
        .iter()
        .chain(playback.piece_for.keys())
        .filter(|p| !hearing_set.contains(p))
        .copied()
        .collect();
    for player in gone {
        silence(playback, Some(player), platform);
    }
    let piece = piece_of(now);
    let crossed = piece != piece_of(before) || (before == now && now == 0.0);
    if !crossed || piece < 0 || piece as usize >= audio.chunks.len() {
        return;
    }
    let piece = piece as usize;
    for player in hearing {
        if playback.whole_for.contains(&player) {
            continue;
        }
        if let Some(old) = playback.piece_for.get(&player) {
            platform
                .sounds()
                .stop(player, audio.chunks.get(*old).cloned());
        }
        if play_to(platform, player, &audio.chunks[piece], playback.volume) {
            playback.piece_for.insert(player, piece);
        }
    }
}

/// Stops `playback`'s sound for `player`, or for everyone.
fn silence(playback: &mut Playback, player: Option<Uuid>, platform: &dyn Platform) {
    let Some(audio) = playback.audio.clone() else {
        return;
    };
    let who: Vec<Uuid> = match player {
        Some(p) => vec![p],
        None => playback
            .whole_for
            .iter()
            .chain(playback.piece_for.keys())
            .copied()
            .collect::<HashSet<_>>()
            .into_iter()
            .collect(),
    };
    for p in who {
        if playback.whole_for.remove(&p) {
            platform.sounds().stop(p, Some(audio.whole.clone()));
        }
        if let Some(piece) = playback.piece_for.remove(&p)
            && let Some(name) = audio.chunks.get(piece)
        {
            platform.sounds().stop(p, Some(name.clone()));
        }
    }
}

// ---- what plays them ----------------------------------------------------------------

/// What a playback's tick came to.
enum Step {
    Nothing,
    Frame(i64, f64),
    End(EndReason),
}

impl ScreensContext {
    fn warn(&self, message: String) {
        self.shared.platform.log().warn(message);
    }

    /// Plays `video` (as the project names it) on screen `id` (`viewer`'s own view, or everyone's), for `owner`.
    /// None when it hasn't been converted, or its frames can't be read; a video the project doesn't have is an error.
    pub fn play(
        &self,
        id: &str,
        viewer: Option<Uuid>,
        video: &str,
        owner: Option<ScopeId>,
        options: PlayOptions,
    ) -> ApiResult<Option<handles::Playback>> {
        let (number, previous) = {
            let mut guard = self.shared.screens.borrow_mut();
            let Screens {
                screens,
                videos,
                paths,
                config,
                ..
            } = &mut *guard;
            let Some(found) = videos.records.get(video) else {
                let known = videos.names();
                return Err(err(crate::centity::api::not_in_project(
                    "video", video, &known,
                )));
            };
            let Some(path) = found
                .frames
                .as_ref()
                .and_then(|f| paths.as_ref().and_then(|p| (p.0)(f)))
            else {
                return Ok(None);
            };
            let (serial, audio) = (found.serial, found.audio.clone());
            // Its header and index are read once, as it's first played; its frames on the lane as it plays.
            let reader = match videos.open.get(&serial).and_then(std::sync::Weak::upgrade) {
                Some(reader) => reader,
                None => match NfvReader::open(&path) {
                    Ok(reader) => {
                        let reader = Arc::new(Mutex::new(reader));
                        videos.open.retain(|_, r| r.strong_count() > 0);
                        videos.open.insert(serial, Arc::downgrade(&reader));
                        reader
                    }
                    Err(e) => {
                        drop(guard);
                        self.warn(format!("Video {video} can't be played: {e}"));
                        return Ok(None);
                    }
                },
            };
            let header = reader.lock().expect("reader").header.clone();
            let lane = videos.lane(config.read_inline);
            videos.next_playback += 1;
            let number = videos.next_playback;
            let position = options.start.clamp(0.0, header.length());
            let playback = Playback {
                number,
                video: video.to_owned(),
                screen: id.to_owned(),
                viewer,
                header,
                feed: FrameFeed::new(reader, lane),
                owner,
                x: options.x,
                y: options.y,
                looping: options.looping,
                speed: options.speed.clamp(MIN_SPEED, MAX_SPEED),
                volume: options.volume.clamp(0.0, 1.0),
                audio_range: options.audio_range,
                audio,
                position,
                paused: false,
                ended: false,
                shown: -1,
                whole_for: HashSet::new(),
                piece_for: HashMap::new(),
                from_start: position == 0.0,
            };
            videos.playbacks.insert(number, playback);
            let previous = screens
                .get_mut(id)
                .and_then(|s| s.playbacks.insert(viewer, number));
            (number, previous)
        };
        if let Some(previous) = previous {
            self.end(previous, EndReason::Replaced);
        }
        let platform = &*self.shared.platform;
        let drawn = {
            let mut guard = self.shared.screens.borrow_mut();
            let Screens {
                screens, videos, ..
            } = &mut *guard;
            match (videos.playbacks.get_mut(&number), screens.get_mut(id)) {
                (Some(playback), Some(screen)) => {
                    let target = playback.frame_at(playback.position);
                    let drawn = playback.draw_to(target, screen.canvas_for(viewer));
                    if drawn.is_ok() && playback.from_start {
                        start_whole(playback, screen, platform);
                    }
                    drawn.map(|_| playback.handle())
                }
                _ => Err("its screen went".into()),
            }
        };
        match drawn {
            Ok(handle) => Ok(Some(handle)),
            Err(e) => {
                self.warn(format!("Video {video} can't be played: {e}"));
                self.end(number, EndReason::Stopped);
                Ok(None)
            }
        }
    }

    /// Each tick: every video moves on, draws the frames it passed, and plays its sound.
    pub fn advance(&self) {
        let numbers: Vec<i64> = self
            .shared
            .screens
            .borrow()
            .videos
            .playbacks
            .keys()
            .copied()
            .collect();
        for number in numbers {
            match self.step(number) {
                Ok(Step::Nothing) => {}
                Ok(Step::Frame(frame, time)) => self.frame_drawn(number, frame, time),
                Ok(Step::End(reason)) => self.end(number, reason),
                Err(e) => {
                    let video = self
                        .shared
                        .screens
                        .borrow()
                        .videos
                        .playbacks
                        .get(&number)
                        .map(|p| p.video.clone())
                        .unwrap_or_default();
                    self.shared.platform.log().error(
                        format!("Video {video} failed, so it was stopped: {e}"),
                        None,
                    );
                    self.end(number, EndReason::Stopped);
                }
            }
        }
    }

    fn step(&self, number: i64) -> Result<Step, String> {
        let platform = &*self.shared.platform;
        let mut guard = self.shared.screens.borrow_mut();
        let Screens {
            screens, videos, ..
        } = &mut *guard;
        let Some(playback) = videos.playbacks.get_mut(&number).filter(|p| !p.ended) else {
            return Ok(Step::Nothing);
        };
        if playback.paused {
            return Ok(Step::Nothing);
        }
        let Some(screen) = screens
            .get_mut(&playback.screen)
            .filter(|s| !s.removed && playback.viewer.is_none_or(|v| s.personal.contains_key(&v)))
        else {
            return Ok(Step::End(EndReason::Removed));
        };
        let before = playback.position;
        playback.position += playback.speed / TICKS_PER_SECOND;
        let total = i64::from(playback.header.frames);
        if playback.frame_at(playback.position) >= total {
            if !playback.looping {
                playback.draw_to(total - 1, screen.canvas_for(playback.viewer))?;
                return Ok(Step::End(EndReason::Finished));
            }
            let length = playback.header.length();
            playback.position = (playback.position - length).clamp(0.0, length);
            silence(playback, None, platform);
            playback.from_start = playback.speed == 1.0;
            if playback.from_start {
                start_whole(playback, screen, platform);
            }
            let now = playback.position;
            sound(playback, screen, platform, now, now);
        } else {
            let now = playback.position;
            sound(playback, screen, platform, before, now);
        }
        let target = playback.frame_at(playback.position);
        if playback.draw_to(target, screen.canvas_for(playback.viewer))? > 0 {
            return Ok(Step::Frame(
                target,
                target as f64 / f64::from(playback.header.fps),
            ));
        }
        Ok(Step::Nothing)
    }

    fn playback_target(&self, handle: handles::Playback) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &Handle::Playback(handle))
            .ok()
            .map(Target::Handle)
    }

    fn frame_drawn(&self, number: i64, frame: i64, time: f64) {
        let Some(handle) = self
            .shared
            .screens
            .borrow()
            .videos
            .live(number)
            .map(Playback::handle)
        else {
            return;
        };
        let Some(target) = self.playback_target(handle.clone()) else {
            return;
        };
        if !self.scripts.listening(&target, "frame") {
            return;
        }
        let lua = self.scripts.host().lua().clone();
        let payload = shapes::PlaybackFrameEvent {
            playback: handle,
            frame,
            time,
        };
        if let Ok(Value::Table(fields)) = codecs::PlaybackFrameEvent.push(&lua, payload) {
            self.scripts.raise(&target, "frame", fields);
        }
    }

    /// Ends playback `number`, its `end` heard while it still answers; the screen keeps its last frame.
    pub fn end(&self, number: i64, reason: EndReason) {
        let platform = &*self.shared.platform;
        let handle = {
            let mut guard = self.shared.screens.borrow_mut();
            let Screens {
                screens, videos, ..
            } = &mut *guard;
            let Some(playback) = videos.playbacks.get_mut(&number).filter(|p| !p.ended) else {
                return;
            };
            playback.ended = true;
            silence(playback, None, platform);
            if let Some(screen) = screens.get_mut(&playback.screen)
                && screen.playbacks.get(&playback.viewer) == Some(&number)
            {
                screen.playbacks.remove(&playback.viewer);
            }
            playback.feed.close();
            playback.handle()
        };
        let target = self.playback_target(handle.clone());
        if let Some(target) = &target {
            let lua = self.scripts.host().lua().clone();
            let payload = shapes::PlaybackEndEvent {
                playback: handle,
                reason: reason.name().to_owned(),
            };
            if let Ok(Value::Table(fields)) = codecs::PlaybackEndEvent.push(&lua, payload) {
                self.scripts.raise(target, "end", fields);
            }
        }
        self.shared
            .screens
            .borrow_mut()
            .videos
            .playbacks
            .shift_remove(&number);
        if let Some(target) = &target {
            self.scripts.drop_target(target);
        }
    }

    /// Ends every playback `which` picks, with `reason`.
    pub fn end_where(&self, reason: EndReason, which: impl Fn(&Playback) -> bool) {
        let numbers: Vec<i64> = self
            .shared
            .screens
            .borrow()
            .videos
            .playbacks
            .values()
            .filter(|p| !p.ended && which(p))
            .map(|p| p.number)
            .collect();
        for number in numbers {
            self.end(number, reason);
        }
    }

    /// Stops every playback's sound for `player` (they left).
    pub fn silence_for(&self, player: Uuid) {
        let platform = &*self.shared.platform;
        let mut screens = self.shared.screens.borrow_mut();
        for playback in screens.videos.playbacks.values_mut() {
            silence(playback, Some(player), platform);
        }
    }

    /// A project screen's `video`: played from the start, belonging to the screen.
    pub fn autoplay(&self, id: &str, home: &str) {
        let Some(video) = self
            .shared
            .screens
            .borrow()
            .screens
            .get(id)
            .and_then(|s| s.file.as_ref()?.video.clone())
        else {
            return;
        };
        // A package's file is read with its references written in full, so this is the video as the server names it.
        let name = video
            .video
            .strip_prefix(&format!("{home}:"))
            .unwrap_or(&video.video)
            .to_owned();
        let options = PlayOptions {
            looping: video.looped.unwrap_or(true),
            volume: video.volume.unwrap_or(1.0),
            ..PlayOptions::default()
        };
        match self.play(id, None, &name, None, options) {
            Ok(Some(_)) => {}
            Ok(None) => self.warn(format!(
                "Screen {id} can't play video {name}: it hasn't been converted, or its frames can't be read"
            )),
            Err(e) => self.warn(format!("Screen {id} can't play video {name}: {}", e.0)),
        }
    }

    /// Runs `f` on playback `number` while it hasn't ended, with its screen: false when it has.
    fn with_playback(
        &self,
        number: i64,
        f: impl FnOnce(&mut Playback, &mut ScreenState, &dyn Platform) -> Result<(), String>,
    ) -> ApiResult<bool> {
        let platform = &*self.shared.platform;
        let mut guard = self.shared.screens.borrow_mut();
        let Screens {
            screens, videos, ..
        } = &mut *guard;
        let Some(playback) = videos.playbacks.get_mut(&number).filter(|p| !p.ended) else {
            return Ok(false);
        };
        let Some(screen) = screens.get_mut(&playback.screen) else {
            return Ok(false);
        };
        f(playback, screen, platform).map_err(err)?;
        Ok(true)
    }
}

// ---- the API ----------------------------------------------------------------

/// A number a script passed that must be one.
fn not_nan(name: &str, value: f64) -> ApiResult<()> {
    if value.is_nan() {
        return Err(err(format!("{name} is a number, not NaN")));
    }
    Ok(())
}

impl GameContext {
    /// `screen:play`'s options, checked as Kotlin's are.
    pub(super) fn play_options(
        options: Option<shapes::VideoPlayOptions>,
    ) -> ApiResult<PlayOptions> {
        let options = options.unwrap_or(shapes::VideoPlayOptions {
            r#loop: None,
            speed: None,
            start: None,
            volume: None,
            x: None,
            y: None,
            audio_range: None,
        });
        let speed = options.speed.unwrap_or(1.0);
        if speed.is_nan() || speed <= 0.0 {
            return Err(err(format!(
                "options.speed is more than 0, not {}",
                number(speed)
            )));
        }
        let volume = options.volume.unwrap_or(1.0);
        if volume.is_nan() || !(0.0..=1.0).contains(&volume) {
            return Err(err(format!(
                "options.volume is 0 to 1, not {}",
                number(volume)
            )));
        }
        let start = options.start.unwrap_or(0.0);
        if start.is_nan() || start < 0.0 {
            return Err(err(format!(
                "options.start is 0 seconds or more, not {}",
                number(start)
            )));
        }
        let audio_range = options.audio_range.unwrap_or(DEFAULT_AUDIO_RANGE);
        if audio_range.is_nan() || audio_range < 0.0 {
            return Err(err(format!(
                "options.audio_range is 0 blocks or more, not {}",
                number(audio_range)
            )));
        }
        Ok(PlayOptions {
            looping: options.r#loop.unwrap_or(false),
            speed,
            start,
            volume,
            x: super::int(options.x.unwrap_or(0)),
            y: super::int(options.y.unwrap_or(0)),
            audio_range,
        })
    }
}

/// A number as a message shows it: as Lua prints it.
fn number(n: f64) -> String {
    netherforge_luau::json::number(n).unwrap_or_else(|| n.to_string())
}

impl PlaybackApi for GameContext {
    fn video(&self, this: &handles::Playback) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.video)))
    }

    fn screen(&self, this: &handles::Playback) -> ApiResult<Option<handles::Screen>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).and_then(|p| {
            screens
                .screens
                .get(&p.screen)
                .map(|s| s.handle_for(p.viewer))
        }))
    }

    fn length(&self, this: &handles::Playback) -> ApiResult<f64> {
        let screens = self.shared.screens.borrow();
        // Once it has gone, the length of its video's frames as they are now.
        let videos = &screens.videos;
        Ok(match videos.playbacks.get(&this.number) {
            Some(playback) => playback.header.length(),
            None => videos
                .records
                .get(&this.video)
                .and_then(|v| v.length)
                .unwrap_or(0.0),
        })
    }

    fn exists(&self, this: &handles::Playback) -> ApiResult<bool> {
        Ok(self
            .shared
            .screens
            .borrow()
            .videos
            .playbacks
            .contains_key(&this.number))
    }

    fn is_paused(&self, this: &handles::Playback) -> ApiResult<bool> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).is_some_and(|p| p.paused))
    }

    fn pause(&self, this: &handles::Playback) -> ApiResult<bool> {
        let mut paused = false;
        self.screens_context()
            .with_playback(this.number, |playback, _, platform| {
                if !playback.paused {
                    playback.paused = true;
                    silence(playback, None, platform);
                    playback.from_start = false;
                    paused = true;
                }
                Ok(())
            })?;
        Ok(paused)
    }

    fn resume(&self, this: &handles::Playback) -> ApiResult<bool> {
        let mut resumed = false;
        self.screens_context()
            .with_playback(this.number, |playback, _, _| {
                if playback.paused {
                    playback.paused = false;
                    resumed = true;
                }
                Ok(())
            })?;
        Ok(resumed)
    }

    fn stop(&self, this: &handles::Playback) -> ApiResult<bool> {
        let live = self
            .shared
            .screens
            .borrow()
            .videos
            .live(this.number)
            .is_some();
        if live {
            self.screens_context().end(this.number, EndReason::Stopped);
        }
        Ok(live)
    }

    fn time(&self, this: &handles::Playback) -> ApiResult<Option<f64>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).map(|p| p.position))
    }

    fn seek(&self, this: &handles::Playback, seconds: f64) -> ApiResult<bool> {
        not_nan("seconds", seconds)?;
        self.screens_context()
            .with_playback(this.number, |playback, screen, platform| {
                playback.seek(seconds);
                silence(playback, None, platform);
                let target = playback.frame_at(playback.position);
                playback
                    .draw_to(target, screen.canvas_for(playback.viewer))
                    .map(|_| ())
            })
    }

    fn speed(&self, this: &handles::Playback) -> ApiResult<Option<f64>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).map(|p| p.speed))
    }

    fn set_speed(&self, this: &handles::Playback, speed: Option<f64>) -> ApiResult<bool> {
        if let Some(speed) = speed {
            not_nan("speed", speed)?;
        }
        let next = speed.unwrap_or(1.0).clamp(MIN_SPEED, MAX_SPEED);
        self.screens_context()
            .with_playback(this.number, |playback, _, platform| {
                if next != playback.speed {
                    playback.speed = next;
                    silence(playback, None, platform);
                    playback.from_start = false;
                }
                Ok(())
            })
    }

    fn volume(&self, this: &handles::Playback) -> ApiResult<Option<f64>> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).map(|p| p.volume))
    }

    fn set_volume(&self, this: &handles::Playback, volume: Option<f64>) -> ApiResult<bool> {
        if let Some(volume) = volume {
            not_nan("volume", volume)?;
        }
        let next = volume.unwrap_or(1.0).clamp(0.0, 1.0);
        self.screens_context()
            .with_playback(this.number, |playback, _, platform| {
                playback.volume = next;
                silence(playback, None, platform);
                playback.from_start = false;
                Ok(())
            })
    }

    fn is_looping(&self, this: &handles::Playback) -> ApiResult<bool> {
        let screens = self.shared.screens.borrow();
        Ok(screens.videos.live(this.number).is_some_and(|p| p.looping))
    }

    fn set_looping(&self, this: &handles::Playback, looping: bool) -> ApiResult<bool> {
        self.screens_context()
            .with_playback(this.number, |playback, _, _| {
                playback.looping = looping;
                Ok(())
            })
    }

    fn on(
        &self,
        this: &handles::Playback,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Playback(this.clone()),
            "Playback:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::Playback,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Playback(this.clone()),
            "Playback:once",
            event,
            handler,
            None,
            true,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    /// A lane that runs only when the test says, so what ran where is plain.
    #[derive(Clone, Default)]
    struct Held(Arc<Mutex<VecDeque<Job>>>);

    impl Held {
        fn lane(&self) -> Lane {
            let queued = self.0.clone();
            Lane::new(move |job| queued.lock().unwrap().push_back(job))
        }

        fn run_all(&self) {
            loop {
                let job = self.0.lock().unwrap().pop_front();
                match job {
                    Some(job) => job(),
                    None => break,
                }
            }
        }

        fn is_empty(&self) -> bool {
            self.0.lock().unwrap().is_empty()
        }
    }

    fn header() -> Header {
        Header {
            columns: 1,
            rows: 1,
            fps: 20,
            frames: 0,
            key_interval: 10,
            dither: 1,
            fit: 0,
            start_ms: 0,
            end_ms: 0,
            audio: false,
            source_sha256: [0; 32],
            palette_hash: 0,
        }
    }

    /// `frames` frames, alternating colours 4 and 10.
    fn reader(frames: usize) -> (tempfile::TempDir, Arc<Mutex<NfvReader>>) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("video.nfv");
        let pixels: Vec<Vec<u8>> = (0..frames)
            .map(|i| vec![if i % 2 == 0 { 4 } else { 10 }; 128 * 128])
            .collect();
        std::fs::write(&path, nfv::encode(&header(), &pixels)).unwrap();
        let reader = NfvReader::open(&path).unwrap();
        (dir, Arc::new(Mutex::new(reader)))
    }

    #[test]
    fn frames_are_read_when_the_lane_runs_ahead_of_the_one_wanted_and_the_rest_forgotten() {
        let held = Held::default();
        let (_dir, reader) = reader(40);
        let feed = FrameFeed::new(reader, held.lane());
        feed.want(0, AHEAD, false);
        assert!(
            feed.ready(0).is_none(),
            "nothing is read on the caller's thread"
        );
        held.run_all();
        for index in 0..=AHEAD {
            assert!(feed.ready(index).is_some(), "frame {index}");
        }
        assert!(feed.ready(AHEAD + 1).is_none());
        feed.want(3, 8, false);
        assert!(feed.ready(0).is_none(), "behind the window: forgotten");
        held.run_all();
        assert!(feed.ready(8).is_some());
        // Past the end, a looping video's first frames are read next.
        feed.want(38, 41, true);
        held.run_all();
        assert!(feed.ready(39).is_some() && feed.ready(0).is_some() && feed.ready(1).is_some());
        assert!(feed.ready(8).is_none());
        feed.close();
        assert!(feed.ready(0).is_none());
    }

    #[test]
    fn a_playback_holds_its_picture_until_its_frames_are_there() {
        let held = Held::default();
        let (_dir, reader) = reader(40);
        let header = reader.lock().unwrap().header.clone();
        let mut playback = Playback {
            number: 1,
            video: "clip".into(),
            screen: "#1".into(),
            viewer: None,
            header,
            feed: FrameFeed::new(reader, held.lane()),
            owner: None,
            x: 0,
            y: 0,
            looping: false,
            speed: 1.0,
            volume: 1.0,
            audio_range: DEFAULT_AUDIO_RANGE,
            audio: None,
            position: 0.0,
            paused: false,
            ended: false,
            shown: -1,
            whole_for: HashSet::new(),
            piece_for: HashMap::new(),
            from_start: true,
        };
        let mut canvas = Canvas::new(1, 1);
        assert_eq!(playback.draw_to(1, &mut canvas), Ok(0), "not read yet");
        held.run_all();
        assert_eq!(playback.draw_to(1, &mut canvas), Ok(2), "frames 0 and 1");
        assert_eq!(playback.shown, 1);
        assert_eq!(canvas.get(5, 5), 10);
        assert!(
            !held.is_empty() || playback.feed.ready(2).is_some(),
            "and the next ones asked for"
        );
    }

    #[test]
    fn a_thread_lane_reads_off_the_callers_thread() {
        let (_dir, reader) = reader(10);
        let feed = FrameFeed::new(reader, Lane::thread("test-videos"));
        feed.want(0, 9, false);
        for _ in 0..1000 {
            if (0..10).all(|i| feed.ready(i).is_some()) {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        panic!("the lane didn't read the frames");
    }

    #[test]
    fn a_file_that_isnt_a_video_says_why() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("video.nfv");
        std::fs::write(&path, b"not a video").unwrap();
        assert_eq!(
            NfvReader::open(&path).err().as_deref(),
            Some("it isn't a converted video (no NFV1 at its start)")
        );
        let mut short = nfv::encode(&header(), &vec![vec![4; 128 * 128]; 3]);
        short.truncate(HEADER_SIZE + 8);
        std::fs::write(&path, &short).unwrap();
        assert_eq!(
            NfvReader::open(&path).err().as_deref(),
            Some("its header says 3 frames, more than the file holds")
        );
    }
}
