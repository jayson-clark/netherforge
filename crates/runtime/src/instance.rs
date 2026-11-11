//! What the server's rooms have in common (ROADMAP.md phase 2): a session runs one or more rooms of its project, each
//! its own Luau state and its own [`Shared`](crate::shared::Shared), and this is the part every room's `Shared`
//! points to. Only what must exist once per server lives here: the store, the resource pack and its server, the
//! scripts' files, the packages' databases (migrated once), the loot tables (pure definitions), permissions held on
//! players, the worlds' failed file work, the reload's notes, the dev bridge's console and the lanes.
//!
//! Everything a room does lives in the room's `Shared`. A room never reaches another's: what crosses between rooms
//! goes through here, the databases or the network.

use std::cell::RefCell;
use std::rc::Rc;

use std::collections::HashMap;

use netherforge_platform::{Platform, Uuid};

/// A room of the server: `MAIN` is the one every session has, which holds every world and player no other room
/// does; each room opened later has the next number.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct RoomId(pub u32);

impl RoomId {
    pub const MAIN: RoomId = RoomId(0);
}

impl std::fmt::Display for RoomId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if *self == RoomId::MAIN {
            f.write_str("main")
        } else {
            write!(f, "room {}", self.0)
        }
    }
}

/// Which room each world and each player is in: one that isn't named is the main room's.
#[derive(Default)]
pub struct Routes {
    worlds: HashMap<String, RoomId>,
    players: HashMap<Uuid, RoomId>,
}

impl Routes {
    pub fn world(&self, world: &str) -> RoomId {
        self.worlds.get(world).copied().unwrap_or(RoomId::MAIN)
    }

    pub fn player(&self, player: Uuid) -> RoomId {
        self.players.get(&player).copied().unwrap_or(RoomId::MAIN)
    }

    /// `world` is `room`'s from now on.
    pub fn give_world(&mut self, world: &str, room: RoomId) {
        if room == RoomId::MAIN {
            self.worlds.remove(world);
        } else {
            self.worlds.insert(world.to_owned(), room);
        }
    }

    /// `player` is in `room` from now on.
    pub fn put_player(&mut self, player: Uuid, room: RoomId) {
        if room == RoomId::MAIN {
            self.players.remove(&player);
        } else {
            self.players.insert(player, room);
        }
    }

    /// The worlds `room` holds, when it isn't the main room (which holds every world no other does).
    pub fn worlds_of(&self, room: RoomId) -> Vec<String> {
        let mut worlds: Vec<String> = self
            .worlds
            .iter()
            .filter(|(_, r)| **r == room)
            .map(|(w, _)| w.clone())
            .collect();
        worlds.sort();
        worlds
    }

    /// The players `room` holds, when it isn't the main room.
    pub fn players_of(&self, room: RoomId) -> Vec<Uuid> {
        self.players
            .iter()
            .filter(|(_, r)| **r == room)
            .map(|(p, _)| *p)
            .collect()
    }

    /// `room` closed: its worlds and players are nobody's, which is the main room's.
    pub fn forget(&mut self, room: RoomId) {
        self.worlds.retain(|_, r| *r != room);
        self.players.retain(|_, r| *r != room);
    }
}

pub struct Instance {
    pub platform: Rc<dyn Platform>,
    /// The runtime's store: placements and saved tables.
    pub store: Rc<crate::store::Store>,
    /// The packages the server provides that the project loaded, as `/nf requires` lists them: the adapter's.
    pub provided_packages: RefCell<Option<crate::session::WrittenPackFiles>>,
    /// The resource pack as last built, and the characters it draws glyphs and skins with.
    pub packs: RefCell<crate::packs::Packs>,
    /// The scripts' data directory (`nf.files`), when the server has one.
    pub files: RefCell<Option<crate::files::ScriptFiles>>,
    /// The project's loot tables.
    pub loot: RefCell<crate::loot::LootTables>,
    /// The packages' own databases (`nf.db`), and the owner's connections.
    pub databases: RefCell<crate::databases::Databases>,
    /// The permission nodes scripts set on players.
    pub permissions: RefCell<crate::permissions::Permissions>,
    /// What the worlds' file work said as it failed on its lane, for the log at a tick.
    pub managed_failures: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    /// What services say of the reload running now (what kept running on each resource): read once it's done.
    pub reloading: RefCell<crate::reload::Notes>,
    /// The dev bridge's console, while there's a bridge: what scripts say and how they fail goes there as itself
    /// (a line with where it was written, an error with its traceback) rather than as a plain log line.
    pub console: RefCell<Option<Rc<dyn crate::bridge::Console>>>,
    /// Lanes work goes to off the main thread, by name, made as they're first wanted (a biome search's).
    lanes: RefCell<std::collections::HashMap<&'static str, crate::http::Workers>>,
    /// Which room each world and player is in.
    pub routes: RefCell<Routes>,
}

impl Instance {
    pub fn new(platform: Rc<dyn Platform>, store: Rc<crate::store::Store>) -> Rc<Instance> {
        Rc::new(Instance {
            platform,
            store,
            provided_packages: RefCell::default(),
            packs: RefCell::default(),
            files: RefCell::default(),
            loot: RefCell::default(),
            databases: RefCell::default(),
            permissions: RefCell::default(),
            managed_failures: Default::default(),
            reloading: RefCell::default(),
            console: RefCell::default(),
            lanes: RefCell::default(),
            routes: RefCell::default(),
        })
    }

    /// The lane named `name`: one thread whose jobs run in order, made the first time it's wanted.
    pub fn lane(&self, name: &'static str) -> crate::http::Workers {
        self.lanes
            .borrow_mut()
            .entry(name)
            .or_insert_with(|| crate::http::Workers::lane(&format!("NetherForge {name}")))
            .clone()
    }

    /// Says that `count` things kept running on resource `kind:id`'s new version (live instances, windows kept,
    /// players sent the rebuilt pack): its `reattached` in the reload's answer.
    pub fn reattached(&self, kind: &'static str, id: &str, count: usize) {
        *self
            .reloading
            .borrow_mut()
            .reattached
            .entry((kind, id.to_owned()))
            .or_default() += count as i32;
    }
}
