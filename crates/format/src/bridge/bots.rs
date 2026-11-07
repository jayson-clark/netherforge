//! Bots (format's `Bots.kt`): fake players on the dev server, for tests and
//! coding agents to try what only a player can (joins, chat and commands,
//! clicking centities, menus, dialogs, sidebars, boss bars, the resource pack).
//!
//! A bot is a real player to the server: it joins through the same
//! configuration phase a client does, its inputs are the packets a client
//! sends (so every check and event fires as for a person), and what the server
//! sends it is recorded: `BotState` is what its screen shows now, `BotEvent`s
//! what arrived, in order. Only a dev server has bots. The extension's methods
//! are `bots/…` in the method table (`super::BotsJoin`…).

use indexmap::IndexMap;
use serde::{Deserialize, Serialize};

use super::{Json, is_default, is_true, yes};

/// How long `walk_to` walks before it gives up, in ticks, when it doesn't say.
pub const DEFAULT_WALK_TICKS: i32 = 200;

/// The most ticks any action waits.
pub const MAX_TICKS: i32 = 500;

/// Where a bot is, or where to put it. `world` none: the bot's current world (or the default one on joining).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotPosition {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub world: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub yaw: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pitch: Option<f64>,
}

/// How a bot answers a resource pack the server offers.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "snake_case")]
pub enum BotPackAnswer {
    /// Accept, download it from its URL and check its SHA-1: loaded, or failed to download.
    #[default]
    Accept,
    Decline,
    /// Accept, and say it failed to download.
    Fail,
    /// Say nothing at all.
    Ignore,
    /// Say it loaded without downloading it.
    Loaded,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotInfo {
    pub name: String,
    pub uuid: String,
    pub world: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    /// How many events it has recorded: where `bots/events` reads from next.
    pub events: i32,
}

/// What an action did: where the bot is after it, and the event it started at.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotActResult {
    pub world: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub since: i32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "snake_case")]
pub enum BotButton {
    #[default]
    Left,
    Right,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "snake_case")]
pub enum BotHand {
    #[default]
    Main,
    Off,
}

/// A click on a menu slot, as a client makes them.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "snake_case")]
pub enum BotClick {
    #[default]
    Left,
    Right,
    ShiftLeft,
    ShiftRight,
    Middle,
    Drop,
    DropAll,
    Double,
    Hotbar,
    OffHand,
}

fn walk_ticks() -> i32 {
    DEFAULT_WALK_TICKS
}

fn is_walk_ticks(ticks: &i32) -> bool {
    *ticks == DEFAULT_WALK_TICKS
}

fn up() -> String {
    "up".into()
}

fn is_up(face: &String) -> bool {
    face == "up"
}

/// What a bot does: one of a client's inputs.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BotAction {
    Teleport {
        x: f64,
        y: f64,
        z: f64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        world: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        yaw: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        pitch: Option<f64>,
    },
    Look {
        yaw: f64,
        pitch: f64,
    },
    LookAt {
        x: f64,
        y: f64,
        z: f64,
    },
    /// Walks to `x`, `z` as a client would, until there or `timeoutTicks` pass.
    #[serde(rename_all = "camelCase")]
    WalkTo {
        x: f64,
        z: f64,
        #[serde(default, skip_serializing_if = "is_default")]
        sprint: bool,
        #[serde(default = "walk_ticks", skip_serializing_if = "is_walk_ticks")]
        timeout_ticks: i32,
    },
    Jump,
    Sneak {
        on: bool,
    },
    Sprint {
        on: bool,
    },
    Chat {
        message: String,
    },
    Command {
        line: String,
    },
    /// Right-clicks an entity, a centity (or one of its nodes), or what's at a point.
    Interact {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        entity: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        centity: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        node: Option<String>,
        #[serde(default, skip_serializing_if = "is_default")]
        hand: BotHand,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        x: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        y: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        z: Option<f64>,
    },
    /// Hits an entity, a centity (or one of its nodes), or what's at a point.
    Attack {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        entity: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        centity: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        node: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        x: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        y: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        z: Option<f64>,
    },
    Swing,
    UseItem {
        #[serde(default, skip_serializing_if = "is_default")]
        hand: BotHand,
    },
    ReleaseItem,
    Fly {
        on: bool,
    },
    EditSign {
        x: i32,
        y: i32,
        z: i32,
        lines: Vec<String>,
        #[serde(default = "yes", skip_serializing_if = "is_true")]
        front: bool,
    },
    ClickButton {
        button: i32,
    },
    UseBlock {
        x: i32,
        y: i32,
        z: i32,
        #[serde(default = "up", skip_serializing_if = "is_up")]
        face: String,
        #[serde(default, skip_serializing_if = "is_default")]
        hand: BotHand,
    },
    BreakBlock {
        x: i32,
        y: i32,
        z: i32,
        #[serde(default = "up", skip_serializing_if = "is_up")]
        face: String,
    },
    PickBlock {
        x: i32,
        y: i32,
        z: i32,
    },
    SelectSlot {
        slot: i32,
    },
    SwapHands,
    Drop {
        #[serde(default, skip_serializing_if = "is_default")]
        all: bool,
    },
    ClickSlot {
        slot: i32,
        #[serde(default, skip_serializing_if = "is_default")]
        click: BotClick,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        hotbar: Option<i32>,
    },
    Drag {
        slots: Vec<i32>,
        #[serde(default, skip_serializing_if = "is_default")]
        button: BotButton,
    },
    CloseMenu,
    /// Presses a dialog's button, by its label or index, with its inputs' values.
    DialogButton {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        button: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        index: Option<i32>,
        #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
        inputs: IndexMap<String, Json>,
    },
    DialogClose,
    Stop,
    Respawn,
}

/// A point to aim at, from an action's `x`, `y` and `z`: none when it gives none, an error when only some.
pub fn point(x: Option<f64>, y: Option<f64>, z: Option<f64>) -> Result<Option<[f64; 3]>, String> {
    match (x, y, z) {
        (None, None, None) => Ok(None),
        (Some(x), Some(y), Some(z)) => Ok(Some([x, y, z])),
        _ => Err("give all of x, y and z to aim at a point, or none".into()),
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotItem {
    pub slot: i32,
    pub item: String,
    pub count: i32,
    pub name: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub lore: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotMenu {
    #[serde(rename = "type")]
    pub kind: String,
    pub title: String,
    pub size: i32,
    pub items: Vec<BotItem>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<BotItem>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotDialogInput {
    pub key: String,
    pub kind: String,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub initial: Option<Json>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotDialog {
    #[serde(rename = "type")]
    pub kind: String,
    pub title: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub body: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub inputs: Vec<BotDialogInput>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub buttons: Vec<String>,
    #[serde(default = "yes", skip_serializing_if = "is_true")]
    pub can_escape: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotBossBar {
    pub name: String,
    pub progress: f64,
    pub color: String,
    pub overlay: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotSidebar {
    pub title: String,
    pub lines: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotTeam {
    pub name: String,
    pub display_name: String,
    pub prefix: String,
    pub suffix: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
    pub friendly_fire: bool,
    pub see_invisible_teammates: bool,
    pub nametags: String,
    pub collision: String,
    pub members: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotListEntry {
    pub name: String,
    pub uuid: String,
    pub listed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub order: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub below_name: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotPack {
    pub id: String,
    pub url: String,
    pub hash: String,
    pub status: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotEntity {
    pub uuid: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub distance: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "snake_case")]
pub enum BotTargetKind {
    Block,
    Entity,
}

/// What the bot's crosshair is on: a block (and its face and state) or an entity (and the centity node it is).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotTarget {
    pub kind: BotTargetKind,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub distance: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub block_x: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub block_y: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub block_z: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub face: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entity: Option<String>,
    #[serde(rename = "type", default, skip_serializing_if = "Option::is_none")]
    pub entity_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub centity: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub node: Option<String>,
}

/// What a bot's screen shows now.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotState {
    pub name: String,
    pub uuid: String,
    pub world: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub yaw: f64,
    pub pitch: f64,
    pub on_ground: bool,
    pub health: f64,
    pub food: i32,
    pub game_mode: String,
    pub dead: bool,
    pub sneaking: bool,
    pub sprinting: bool,
    pub selected_slot: i32,
    pub inventory: Vec<BotItem>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub menu: Option<BotMenu>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dialog: Option<BotDialog>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub boss_bars: Vec<BotBossBar>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sidebar: Option<BotSidebar>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subtitle: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub action_bar: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub resource_packs: Vec<BotPack>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub entities: Vec<BotEntity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target: Option<BotTarget>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub teams: Vec<BotTeam>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub player_list: Vec<BotListEntry>,
    pub events: i32,
}

/// What arrived at a bot, in order; `seq` numbers each event.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BotEvent {
    Chat {
        seq: i32,
        text: String,
    },
    ActionBar {
        seq: i32,
        text: String,
    },
    Title {
        seq: i32,
        text: String,
    },
    Subtitle {
        seq: i32,
        text: String,
    },
    MenuOpen {
        seq: i32,
        menu: String,
        title: String,
    },
    MenuClose {
        seq: i32,
    },
    Dialog {
        seq: i32,
        dialog: BotDialog,
    },
    DialogClose {
        seq: i32,
    },
    ResourcePack {
        seq: i32,
        id: String,
        url: String,
        status: String,
    },
    Sound {
        seq: i32,
        sound: String,
        x: f64,
        y: f64,
        z: f64,
        volume: f64,
        pitch: f64,
    },
    StopSound {
        seq: i32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sound: Option<String>,
    },
    Particle {
        seq: i32,
        particle: String,
        x: f64,
        y: f64,
        z: f64,
        count: i32,
        dx: f64,
        dy: f64,
        dz: f64,
        speed: f64,
        forced: bool,
    },
    BossBar {
        seq: i32,
        name: String,
        shown: bool,
    },
    Teleport {
        seq: i32,
        x: f64,
        y: f64,
        z: f64,
    },
    Death {
        seq: i32,
        message: String,
    },
    Respawn {
        seq: i32,
        world: String,
    },
    BlockChange {
        seq: i32,
        x: i32,
        y: i32,
        z: i32,
        state: String,
    },
    Equipment {
        seq: i32,
        entity: String,
        slot: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        item: Option<String>,
    },
    BookOpen {
        seq: i32,
        pages: Vec<String>,
    },
    Camera {
        seq: i32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        entity: Option<String>,
    },
    Kicked {
        seq: i32,
        reason: String,
    },
}

impl BotEvent {
    pub fn seq(&self) -> i32 {
        use BotEvent::*;
        match self {
            Chat { seq, .. }
            | ActionBar { seq, .. }
            | Title { seq, .. }
            | Subtitle { seq, .. }
            | MenuOpen { seq, .. }
            | MenuClose { seq }
            | Dialog { seq, .. }
            | DialogClose { seq }
            | ResourcePack { seq, .. }
            | Sound { seq, .. }
            | StopSound { seq, .. }
            | Particle { seq, .. }
            | BossBar { seq, .. }
            | Teleport { seq, .. }
            | Death { seq, .. }
            | Respawn { seq, .. }
            | BlockChange { seq, .. }
            | Equipment { seq, .. }
            | BookOpen { seq, .. }
            | Camera { seq, .. }
            | Kicked { seq, .. } => *seq,
        }
    }
}

/// Events from `since` on, where to read from next, and how many were dropped (the oldest go past a limit).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotEvents {
    pub events: Vec<BotEvent>,
    pub next: i32,
    #[serde(default, skip_serializing_if = "is_default")]
    pub dropped: i32,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BotJoinParams {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub at: Option<BotPosition>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub resource_pack: BotPackAnswer,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotParams {
    pub name: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotActParams {
    pub name: String,
    pub action: BotAction,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BotEventsParams {
    pub name: String,
    #[serde(default, skip_serializing_if = "is_default")]
    pub since: i32,
}
