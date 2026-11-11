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

use netherforge_platform::{
    CommandHandler, CommandInput, CommandSender, CommandSpec, Completion, Platform,
    SuggestionRequest, Uuid,
};

/// A room of the server: `MAIN` is the one every session has, which holds every world and player no other room
/// does; each room opened later has the next number.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, PartialOrd, Ord)]
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
    /// The last boss bar number given, in any room.
    pub next_bar: std::cell::Cell<i32>,
    /// The rooms open now, the main room among them.
    pub open: RefCell<std::collections::BTreeSet<RoomId>>,
    /// Every room there is (opening or closing ones too): what it's for and where it is in its life.
    pub rooms: RefCell<std::collections::BTreeMap<RoomId, crate::rooms::RoomInfo>>,
    /// What scripts asked of the rooms this tick, for the session at its end.
    pub room_requests: RefCell<Vec<crate::rooms::RoomRequest>>,
    /// The last room number given.
    last_room: std::cell::Cell<u32>,
    /// The players being moved between rooms right now: what the server holds for everyone (the pack, their
    /// permissions, their presence on the network) doesn't hear them leave or arrive.
    moving: RefCell<std::collections::HashSet<Uuid>>,
    /// What came in from the network's hub for each room beside the main one, which reads the link for all of them.
    pub network_inbound: RefCell<HashMap<RoomId, Vec<crate::network::Inbound>>>,
    /// What each room listens to on the network: the hub is told every room's at once.
    pub network_listening: RefCell<std::collections::BTreeMap<RoomId, (Vec<String>, Vec<String>)>>,
    /// Each recipe the server has from the rooms, by id: how many rooms gave it, and the version it has.
    recipes: RefCell<HashMap<String, (u32, netherforge_format::kind::recipe::RecipeFile)>>,
    /// Each command the rooms declared, by name: every room's handler, behind the one the server has.
    commands: RefCell<HashMap<String, std::collections::BTreeMap<RoomId, Rc<dyn CommandHandler>>>>,
}

/// What the server runs for a command the rooms declared: the sender's room's handler (a player's room's; the main
/// room's for the console, or the first room's that has one).
struct RoomCommand {
    name: String,
    instance: std::rc::Weak<Instance>,
}

impl RoomCommand {
    fn handler(&self, sender: &dyn CommandSender) -> Option<Rc<dyn CommandHandler>> {
        let instance = self.instance.upgrade()?;
        let room = sender
            .player()
            .map_or(RoomId::MAIN, |p| instance.routes.borrow().player(p.uuid));
        let commands = instance.commands.borrow();
        let by_room = commands.get(&self.name)?;
        by_room
            .get(&room)
            .or_else(|| by_room.values().next())
            .cloned()
    }
}

impl CommandHandler for RoomCommand {
    fn run(&self, sender: Rc<dyn CommandSender>, label: String, input: CommandInput) {
        if let Some(handler) = self.handler(&*sender) {
            handler.run(sender, label, input);
        }
    }

    fn suggest(&self, sender: Rc<dyn CommandSender>, request: SuggestionRequest) -> Completion {
        match self.handler(&*sender) {
            Some(handler) => handler.suggest(sender, request),
            None => Completion {
                start: request.partial.len() as i32,
                suggestions: Vec::new(),
            },
        }
    }
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
            next_bar: Default::default(),
            commands: RefCell::default(),
            recipes: RefCell::default(),
            open: RefCell::new([RoomId::MAIN].into()),
            rooms: RefCell::new(
                [(
                    RoomId::MAIN,
                    crate::rooms::RoomInfo {
                        key: None,
                        state: crate::rooms::RoomState::Open,
                    },
                )]
                .into(),
            ),
            room_requests: RefCell::default(),
            last_room: Default::default(),
            moving: RefCell::default(),
            network_inbound: RefCell::default(),
            network_listening: RefCell::default(),
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

    /// The next room's number: never one given before while the server runs.
    pub fn next_room_id(&self) -> RoomId {
        let id = self.last_room.get() + 1;
        self.last_room.set(id);
        RoomId(id)
    }

    /// What room `room` is for (an owner's id, a match's), when it was opened with a key.
    pub fn room_key(&self, room: RoomId) -> Option<String> {
        self.rooms.borrow().get(&room).and_then(|r| r.key.clone())
    }

    /// `player`, on their way here, belongs in the room keyed `key`: the room open with that key, or one opened for it
    /// at the end of the tick. Already here, they're moved there then.
    pub fn place_in_room(&self, player: Uuid, key: &str) {
        let found = self
            .rooms
            .borrow()
            .iter()
            .find(|(_, r)| {
                r.key.as_deref() == Some(key) && r.state != crate::rooms::RoomState::Closing
            })
            .map(|(id, _)| *id);
        let id = found.unwrap_or_else(|| {
            let id = self.next_room_id();
            self.rooms.borrow_mut().insert(
                id,
                crate::rooms::RoomInfo {
                    key: Some(key.to_owned()),
                    state: crate::rooms::RoomState::Opening,
                },
            );
            self.room_requests
                .borrow_mut()
                .push(crate::rooms::RoomRequest::Open {
                    id,
                    key: Some(key.to_owned()),
                });
            id
        });
        if self.platform.players().get(player).is_some() {
            self.room_requests
                .borrow_mut()
                .push(crate::rooms::RoomRequest::Send { player, to: id });
        } else {
            self.routes.borrow_mut().put_player(player, id);
        }
    }

    /// Whether `player` is being moved between rooms now.
    pub fn is_moving(&self, player: Uuid) -> bool {
        self.moving.borrow().contains(&player)
    }

    /// `f`, with `player` being moved between rooms meanwhile.
    pub fn moving<R>(&self, player: Uuid, f: impl FnOnce() -> R) -> R {
        self.moving.borrow_mut().insert(player);
        let out = f();
        self.moving.borrow_mut().remove(&player);
        out
    }

    /// Every room's network listening at once: the channels and maps any room listens to.
    pub fn network_union(&self) -> (Vec<String>, Vec<String>) {
        let listening = self.network_listening.borrow();
        let mut channels: Vec<String> = listening
            .values()
            .flat_map(|(c, _)| c.iter().cloned())
            .collect();
        let mut maps: Vec<String> = listening
            .values()
            .flat_map(|(_, m)| m.iter().cloned())
            .collect();
        channels.sort();
        channels.dedup();
        maps.sort();
        maps.dedup();
        (channels, maps)
    }

    /// The room that answers requests on `channel`: the first that listens to it.
    pub fn network_answerer(&self, channel: &str) -> Option<RoomId> {
        self.network_listening
            .borrow()
            .iter()
            .find(|(_, (channels, _))| channels.iter().any(|c| c == channel))
            .map(|(room, _)| *room)
    }

    /// A room gives the server recipe `id` (it hadn't): the server has it while any room does, as the room that gave
    /// it last has it (a reload's new version replaces the old for every room). False when the server couldn't make it.
    pub fn give_recipe(
        &self,
        id: &str,
        file: &netherforge_format::kind::recipe::RecipeFile,
    ) -> bool {
        let mut recipes = self.recipes.borrow_mut();
        let ops = self.platform.recipes();
        match recipes.get_mut(id) {
            Some((holders, held)) if held == file => {
                *holders += 1;
                true
            }
            Some((holders, held)) => {
                ops.remove(id.to_owned());
                let added = ops.add(netherforge_platform::RecipeSpec {
                    id: id.to_owned(),
                    file: file.clone(),
                });
                if added {
                    *holders += 1;
                    *held = file.clone();
                } else if *holders == 0 {
                    recipes.remove(id);
                }
                added
            }
            None => {
                let added = ops.add(netherforge_platform::RecipeSpec {
                    id: id.to_owned(),
                    file: file.clone(),
                });
                if added {
                    recipes.insert(id.to_owned(), (1, file.clone()));
                }
                added
            }
        }
    }

    /// A room takes back recipe `id` it gave: the server's goes once no room has it.
    pub fn take_recipe(&self, id: &str) {
        let mut recipes = self.recipes.borrow_mut();
        let Some((holders, _)) = recipes.get_mut(id) else {
            return;
        };
        *holders = holders.saturating_sub(1);
        if *holders == 0 {
            recipes.remove(id);
            self.platform.recipes().remove(id.to_owned());
        }
    }

    /// Declares `/name` for `room`: the server is given it the first time any room declares it. False when the server
    /// already has another `/name`, or `room` declared it already.
    pub fn declare_command(
        self: &Rc<Self>,
        room: RoomId,
        spec: CommandSpec,
        handler: Rc<dyn CommandHandler>,
    ) -> bool {
        let name = spec.name.clone();
        let known = self.commands.borrow().contains_key(&name);
        if known {
            let mut commands = self.commands.borrow_mut();
            let by_room = commands.get_mut(&name).expect("known");
            if by_room.contains_key(&room) {
                return false;
            }
            by_room.insert(room, handler);
            return true;
        }
        let dispatch = Rc::new(RoomCommand {
            name: name.clone(),
            instance: Rc::downgrade(self),
        });
        if !self.platform.commands().register(spec, dispatch) {
            return false;
        }
        self.commands
            .borrow_mut()
            .entry(name)
            .or_default()
            .insert(room, handler);
        true
    }

    /// `room` no longer has `/name`: the server's goes once no room does.
    pub fn drop_command(&self, room: RoomId, name: &str) {
        let gone = {
            let mut commands = self.commands.borrow_mut();
            let Some(by_room) = commands.get_mut(name) else {
                return;
            };
            by_room.remove(&room);
            if by_room.is_empty() {
                commands.remove(name);
                true
            } else {
                false
            }
        };
        if gone {
            self.platform.commands().unregister(name.to_owned());
        }
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
