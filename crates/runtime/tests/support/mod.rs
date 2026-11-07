//! The fake Platform the centities' tests began, shared by the tests that need a server with entities, a
//! player, worlds, commands, particles and bots: what it was asked is in its log of calls.
#![allow(dead_code, unused_imports)]

pub use std::cell::RefCell;
pub use std::collections::BTreeMap;

pub use netherforge_format::kind::centity::DisplayDef;
pub use netherforge_format::package::NoPackages;
pub use netherforge_format::project::ProjectSource;
pub use netherforge_platform::{
    ClickButton, DisplayLook, DisplayPose, EntityOps, EntityRole, EntityTag, FakeEntityOps,
    FakePlatform, FakePlatformLog, FakeWorldOps, Location, PlatformError, PlatformLog, PlayerRef,
    Ray, ServerInfo, Uuid, WorldOps,
};
pub use netherforge_runtime::session::Session;

/// Game facts that know no registry, and every block as one without properties.
pub struct NoFacts;

impl netherforge_format::game::GameData for NoFacts {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&std::collections::BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&std::collections::BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    /// Dust (a colour and a size) and flames (nothing).
    fn particle(&self, id: &str) -> Option<netherforge_format::game::ParticleData> {
        use netherforge_format::game::ParticleData;
        match id {
            "minecraft:dust" => Some(ParticleData::Dust),
            "minecraft:flame" => Some(ParticleData::None),
            _ => None,
        }
    }
    /// Every block there is, with no properties.
    fn block_properties(&self, _: &str) -> Option<BTreeMap<String, Vec<String>>> {
        Some(BTreeMap::new())
    }
}

/// A window the fake holds: its slots and who's looking.
pub type FakeWindow = (Vec<Option<netherforge_platform::ItemData>>, Vec<Uuid>);

/// A command the fake server took: its spec and its handler.
pub type Registered = (
    netherforge_platform::CommandSpec,
    std::rc::Rc<dyn netherforge_platform::CommandHandler>,
);

pub struct Fake {
    /// The commands registered, by name.
    pub commands: RefCell<BTreeMap<String, Registered>>,
    /// Each open window's slots and viewers, by window.
    pub windows: RefCell<BTreeMap<u128, FakeWindow>>,
    /// Storages, by name.
    pub storages: RefCell<BTreeMap<String, Vec<Option<netherforge_platform::ItemData>>>>,
    /// Alex's inventory: nine slots.
    pub inventory: RefCell<Vec<Option<netherforge_platform::ItemData>>>,
    /// Each scoreboard team's entries.
    pub teams: RefCell<BTreeMap<String, Vec<String>>>,
    pub game: NoFacts,
    /// Whether every chunk's entities are loaded.
    pub unloaded: std::cell::Cell<bool>,
    /// The one player online, and where they are.
    pub player: RefCell<Option<Location>>,
    pub calls: RefCell<Vec<String>>,
    pub next: RefCell<u128>,
    pub poses: RefCell<BTreeMap<u128, DisplayPose>>,
    /// Blocks of the floor that were broken.
    pub broken: RefCell<std::collections::HashSet<(i32, i32, i32)>>,
    /// The bots: who's online, who's joining, what they were told to do.
    pub bots: FakeBots,
    /// The data pack format the server says it has (none: it doesn't say, and never needs a restart).
    pub datapack_format: RefCell<Option<Vec<i32>>>,
    /// Whether Alex has eyes the server reports (a line of sight from where they stand, the way they face).
    pub alex_eye: std::cell::Cell<bool>,
    /// Worlds loaded besides `world`.
    pub extra_worlds: RefCell<Vec<String>>,
    /// Each world's spawn, where set.
    pub spawns: RefCell<BTreeMap<String, Location>>,
    /// Where the main world's storage is (`level.dat`, `dimensions/`): none, and no world saves.
    pub storage: RefCell<Option<std::path::PathBuf>>,
    /// The worlds saved, in order.
    pub saved_worlds: RefCell<Vec<String>>,
    /// Why the server was asked to stop.
    pub shutdown: RefCell<Option<String>>,
}

impl Default for Fake {
    fn default() -> Fake {
        Fake {
            commands: RefCell::default(),
            windows: RefCell::default(),
            inventory: RefCell::new(vec![None; 9]),
            storages: RefCell::default(),
            teams: RefCell::new(BTreeMap::from([
                ("nf.stale".to_owned(), Vec::new()),
                ("red".to_owned(), Vec::new()),
            ])),
            game: NoFacts,
            unloaded: std::cell::Cell::new(false),
            player: RefCell::new(None),
            calls: RefCell::default(),
            next: RefCell::default(),
            poses: RefCell::default(),
            broken: RefCell::default(),
            bots: FakeBots::default(),
            datapack_format: RefCell::new(None),
            alex_eye: std::cell::Cell::new(false),
            extra_worlds: RefCell::default(),
            spawns: RefCell::default(),
            storage: RefCell::default(),
            saved_worlds: RefCell::default(),
            shutdown: RefCell::default(),
        }
    }
}

impl Fake {
    pub fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.calls.borrow_mut())
    }
    pub fn call(&self, what: String) {
        self.calls.borrow_mut().push(what);
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.call(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.call(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.call(format!("error {message}"));
    }
}

pub fn at(l: &Location) -> String {
    format!("{} {} {} {}", l.world, l.x, l.y, l.z)
}

impl FakeEntityOps for Fake {
    /// No structure has put a marker in the world.
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
    fn spawn_hitbox(
        &self,
        location: Location,
        width: f64,
        height: f64,
        tag: EntityTag,
    ) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        assert_eq!(tag.role, EntityRole::Hitbox);
        self.call(format!(
            "hitbox {id} {} at {} size {width} {height}",
            tag.node,
            at(&location)
        ));
        Some(Uuid(id))
    }
    fn resize_hitbox(&self, id: Uuid, width: f64, height: f64) {
        self.call(format!("resize {} {width} {height}", id.0));
    }
    fn dismount(&self, id: Uuid) {
        self.call(format!("dismount {}", id.0));
    }
    fn set_persistent(&self, id: Uuid, persistent: bool) {
        self.call(format!("persistent {} {persistent}", id.0));
    }
    fn spawn_anchor(&self, location: Location, tag: EntityTag) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        assert_eq!(tag.role, EntityRole::Anchor);
        self.call(format!("anchor {id} at {}", at(&location)));
        Some(Uuid(id))
    }
    fn spawn_display(
        &self,
        location: Location,
        display: DisplayDef,
        pose: DisplayPose,
        tag: EntityTag,
    ) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        let kind = match display {
            DisplayDef::Block { .. } => "block",
            DisplayDef::Text { .. } => "text",
            _ => "other",
        };
        let t = pose.matrix.translation();
        self.call(format!(
            "display {id} {} {kind} at {} offset {} {} {}",
            tag.node,
            at(&location),
            t.0,
            t.1,
            t.2
        ));
        self.poses.borrow_mut().insert(id, pose);
        Some(Uuid(id))
    }
    fn mount(&self, vehicle: Uuid, passenger: Uuid) -> bool {
        self.call(format!("mount {} on {}", passenger.0, vehicle.0));
        true
    }
    fn set_pose(&self, id: Uuid, pose: DisplayPose) {
        let t = pose.matrix.translation();
        self.call(format!(
            "pose {} {} {} {} eased {}",
            id.0, t.0, t.1, t.2, pose.interpolation_ticks
        ));
        self.poses.borrow_mut().insert(id.0, pose);
    }
    fn set_look(&self, id: Uuid, _: DisplayLook) {
        self.call(format!("look {}", id.0));
    }
    fn teleport(&self, id: Uuid, to: Location) {
        self.call(format!("teleport {} to {}", id.0, at(&to)));
    }
    fn update_display(&self, id: Uuid, display: DisplayDef) -> bool {
        if let DisplayDef::Text {
            runs: Some(runs), ..
        } = &display
        {
            let coloured = runs.iter().filter(|r| r.rgb.is_some()).count();
            self.call(format!("update {} runs {coloured}", id.0));
        }
        true
    }
    fn is_loaded(&self, _: Uuid) -> bool {
        true
    }
    fn remove(&self, id: Uuid) -> bool {
        self.call(format!("remove {}", id.0));
        true
    }
}

impl netherforge_platform::FakeClientEntityOps for Fake {
    fn set_viewers(&self, id: Uuid, viewers: Vec<Uuid>) {
        let who: Vec<String> = viewers.iter().map(|v| v.0.to_string()).collect();
        self.call(format!("viewers {} {}", id.0, who.join(",")));
    }
}

impl netherforge_platform::FakeWorldEntityOps for Fake {
    fn boxes(
        &self,
        _: String,
        _: netherforge_format::math::BoundingBox,
    ) -> Vec<netherforge_platform::EntityBox> {
        Vec::new()
    }
    fn info(&self, id: Uuid) -> Option<netherforge_platform::EntityInfo> {
        if let Some(bot) = self.bots.by_id(id) {
            return Some(netherforge_platform::EntityInfo {
                id,
                kind: "minecraft:player".into(),
                location: bot.at.clone(),
                category: netherforge_platform::EntityCategory::Living,
                player: Some(bot.player()),
                tags: Vec::new(),
            });
        }
        if id == Uuid(77) {
            let location = self.player.borrow().clone()?;
            return Some(netherforge_platform::EntityInfo {
                id,
                kind: "minecraft:player".into(),
                location,
                category: netherforge_platform::EntityCategory::Living,
                player: Some(PlayerRef {
                    uuid: id,
                    name: "Alex".into(),
                }),
                tags: Vec::new(),
            });
        }
        self.list("world".into()).into_iter().find(|e| e.id == id)
    }
    /// One zombie, standing at (5, 64, 5).
    fn list(&self, _: String) -> Vec<netherforge_platform::EntityInfo> {
        vec![netherforge_platform::EntityInfo {
            id: Uuid(500),
            kind: "minecraft:zombie".into(),
            location: place(5.0, 64.0, 5.0),
            category: netherforge_platform::EntityCategory::Mob,
            player: None,
            tags: vec!["guard".into()],
        }]
    }
    fn raycast(
        &self,
        _: String,
        _: netherforge_format::math::Vec3,
        _: netherforge_format::math::Vec3,
        _: f64,
        _: Vec<Uuid>,
    ) -> Option<netherforge_platform::EntityHit> {
        None
    }
    fn spawnable(&self, kind: String) -> bool {
        kind == "minecraft:pig"
    }
    fn spawn(
        &self,
        kind: String,
        at: Location,
        setup: netherforge_platform::SpawnSetup,
    ) -> Option<Uuid> {
        self.call(format!(
            "spawn {kind} at {} {:?} {:?} {:?}",
            at.x, setup.custom_name, setup.tags, setup.data
        ));
        Some(Uuid(600))
    }
    fn number(&self, _: Uuid, number: netherforge_platform::EntityNumber) -> Option<f64> {
        use netherforge_platform::EntityNumber;
        matches!(number, EntityNumber::Health | EntityNumber::MaxHealth).then_some(20.0)
    }
    fn set_number(&self, id: Uuid, number: netherforge_platform::EntityNumber, value: f64) -> bool {
        self.call(format!("number {} {} {value}", id.0, number.name()));
        true
    }
    fn set_custom_name(&self, id: Uuid, name: Option<String>) -> bool {
        self.call(format!("custom name {} {name:?}", id.0));
        true
    }
    fn data(&self, _: Uuid) -> Option<String> {
        Some(r#"{"kills":2}"#.into())
    }
    fn set_data(&self, id: Uuid, json: Option<String>) -> bool {
        self.call(format!("data {} {json:?}", id.0));
        true
    }
    fn hide(&self, viewer: Uuid, id: Uuid) -> bool {
        self.call(format!("hide {} from {}", id.0, viewer.0));
        true
    }
    fn show(&self, viewer: Uuid, id: Uuid) -> bool {
        self.call(format!("show {} to {}", id.0, viewer.0));
        true
    }
    fn remove(&self, id: Uuid) -> bool {
        self.call(format!("remove entity {}", id.0));
        true
    }
}

impl netherforge_platform::FakeBlockOps for Fake {
    fn set(&self, _: String, x: i32, y: i32, z: i32, state: String, update: bool) -> bool {
        self.call(format!("set {x} {y} {z} {state} {update}"));
        true
    }
    /// The floor's blocks in a box, x fastest, then z, then y.
    fn r#box(
        &self,
        _: String,
        x0: i32,
        y0: i32,
        z0: i32,
        x1: i32,
        y1: i32,
        z1: i32,
    ) -> Option<netherforge_platform::BlockBoxRead> {
        let state = |name: &str, solid: bool| netherforge_platform::BoxState {
            state: name.into(),
            solid,
            liquid: false,
        };
        let mut cells = Vec::new();
        for y in y0..=y1 {
            for _ in z0..=z1 {
                for x in x0..=x1 {
                    cells.push(i32::from(y < 64 || x == 10));
                }
            }
        }
        Some(netherforge_platform::BlockBoxRead {
            states: vec![
                state("minecraft:air", false),
                state("minecraft:stone", true),
            ],
            cells,
        })
    }
    fn get(
        &self,
        _: String,
        x: i32,
        y: i32,
        _: i32,
    ) -> Option<netherforge_platform::BlockSnapshot> {
        let solid = y < 64 || x == 10;
        Some(netherforge_platform::BlockSnapshot {
            state: if solid {
                "minecraft:stone"
            } else {
                "minecraft:air"
            }
            .into(),
            air: !solid,
            solid,
            liquid: false,
            light: if solid { 0 } else { 15 },
            sky_light: 15,
            water: false,
        })
    }
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn find(&self, name: String) -> Option<PlayerRef> {
        if let Some(bot) = self.bots.by_name(&name) {
            return Some(bot.player());
        }
        let alex = name == "Alex";
        alex.then_some(PlayerRef {
            uuid: Uuid(77),
            name,
        })
    }
    fn message(&self, uuid: Uuid, text: String) -> bool {
        self.call(format!("message {} {text}", uuid.0));
        true
    }
    fn title(
        &self,
        uuid: Uuid,
        title: String,
        subtitle: String,
        fade_in: i32,
        stay: i32,
        fade_out: i32,
    ) -> bool {
        self.call(format!(
            "title {} {title}/{subtitle} {fade_in} {stay} {fade_out}",
            uuid.0
        ));
        true
    }
    fn game_mode(&self, _: Uuid) -> Option<String> {
        Some("survival".into())
    }
    fn known(&self, name: String) -> Option<PlayerRef> {
        self.bots.by_name(&name).map(|b| b.player())
    }
    fn get(&self, uuid: Uuid) -> Option<PlayerRef> {
        if let Some(bot) = self.bots.by_id(uuid) {
            return Some(bot.player());
        }
        (uuid == Uuid(77)).then(|| PlayerRef {
            uuid,
            name: "Alex".into(),
        })
    }
    fn online(&self) -> Vec<PlayerRef> {
        let mut online = match &*self.player.borrow() {
            Some(_) => vec![PlayerRef {
                uuid: Uuid(77),
                name: "Alex".into(),
            }],
            None => Vec::new(),
        };
        online.extend(self.bots.online.borrow().iter().map(FakeBot::player));
        online
    }
    fn teleport(&self, uuid: Uuid, to: Location) -> bool {
        if uuid != Uuid(77) {
            return false;
        }
        self.call(format!("teleport Alex {}", at(&to)));
        *self.player.borrow_mut() = Some(to);
        true
    }
    fn location(&self, uuid: Uuid) -> Option<Location> {
        if let Some(bot) = self.bots.by_id(uuid) {
            return Some(bot.at);
        }
        (uuid == Uuid(77))
            .then(|| self.player.borrow().clone())
            .flatten()
    }
    fn eye(&self, uuid: Uuid) -> Option<Ray> {
        if uuid == Uuid(77) && self.alex_eye.get() {
            let at = self.player.borrow().clone()?;
            let (yaw, pitch) = (at.yaw.to_radians(), at.pitch.to_radians());
            return Some(Ray {
                world: at.world,
                x: at.x,
                y: at.y + 1.62,
                z: at.z,
                dx: -yaw.sin() * pitch.cos(),
                dy: -pitch.sin(),
                dz: yaw.cos() * pitch.cos(),
            });
        }
        let at = self.bots.by_id(uuid)?.at;
        Some(Ray {
            world: at.world,
            x: at.x,
            y: at.y + 1.62,
            z: at.z,
            dx: 0.0,
            dy: 0.0,
            dz: 1.0,
        })
    }
}

impl FakeWorldOps for Fake {
    fn names(&self) -> Vec<String> {
        let mut names = vec!["world".to_owned()];
        names.extend(self.extra_worlds.borrow().iter().cloned());
        names
    }
    fn exists(&self, world: String) -> bool {
        world == "world" || self.extra_worlds.borrow().contains(&world)
    }
    fn spawn_location(&self, world: String) -> Option<Location> {
        if let Some(spawn) = self.spawns.borrow().get(&world) {
            return Some(spawn.clone());
        }
        FakeWorldOps::exists(self, world.clone()).then_some(Location {
            world,
            x: 0.5,
            y: 64.0,
            z: 0.5,
            yaw: 0.0,
            pitch: 0.0,
        })
    }
    fn environment(&self, world: String) -> Option<String> {
        FakeWorldOps::exists(self, world).then(|| "normal".into())
    }
    fn default_world(&self) -> String {
        "world".into()
    }
    fn entities_loaded(&self, _: Location) -> bool {
        !self.unloaded.get()
    }
    fn is_chunk_loaded(&self, _: String, _: i32, _: i32) -> bool {
        true
    }
    fn full_time(&self, _: String) -> Option<i64> {
        Some(6000)
    }
    fn moon_phase(&self, _: String) -> Option<i32> {
        Some(0)
    }
    fn weather(&self, _: String) -> Option<String> {
        Some("clear".into())
    }
    fn heights(&self, _: String) -> Option<(i32, i32)> {
        Some((-64, 320))
    }
    fn biome(&self, _: String, _: i32, _: i32, _: i32) -> Option<String> {
        Some("minecraft:plains".into())
    }
    fn game_rule_type(&self, rule: String) -> Option<netherforge_platform::GameRuleType> {
        (rule == "minecraft:keep_inventory").then_some(netherforge_platform::GameRuleType::Boolean)
    }
    fn game_rule(&self, _: String, _: String) -> Option<netherforge_platform::Dynamic> {
        Some(netherforge_platform::Dynamic::Bool(true))
    }
    /// The first solid block along a ray, by small steps.
    fn raycast_blocks(
        &self,
        _: String,
        origin: netherforge_format::math::Vec3,
        direction: netherforge_format::math::Vec3,
        max_distance: f64,
        _: bool,
    ) -> Option<netherforge_platform::BlockHit> {
        let solid = |x: i32, y: i32, _: i32| y < 64 || x == 10;
        let cell = |p: netherforge_format::math::Vec3| {
            (p.0.floor() as i32, p.1.floor() as i32, p.2.floor() as i32)
        };
        let mut previous = cell(origin);
        let mut t = 0.0;
        while t <= max_distance {
            let p = origin + direction * t;
            let c = cell(p);
            if c != previous && solid(c.0, c.1, c.2) {
                let normal = netherforge_format::math::Vec3(
                    f64::from(previous.0 - c.0),
                    f64::from(previous.1 - c.1),
                    f64::from(previous.2 - c.2),
                );
                return Some(netherforge_platform::BlockHit {
                    x: c.0,
                    y: c.1,
                    z: c.2,
                    position: p,
                    normal,
                });
            }
            previous = c;
            t += 0.01;
        }
        None
    }
    /// A floor: every block below y 64 is stone (but those broken), and a wall of it at x 10.
    fn collision_boxes(
        &self,
        _: String,
        min_x: f64,
        min_y: f64,
        min_z: f64,
        max_x: f64,
        max_y: f64,
        max_z: f64,
    ) -> Vec<netherforge_format::math::BoundingBox> {
        let mut boxes = Vec::new();
        for x in min_x.floor() as i32..=max_x.floor() as i32 {
            for y in min_y.floor() as i32..=max_y.floor() as i32 {
                for z in min_z.floor() as i32..=max_z.floor() as i32 {
                    if (y < 64 || x == 10) && !self.broken.borrow().contains(&(x, y, z)) {
                        let (x, y, z) = (f64::from(x), f64::from(y), f64::from(z));
                        boxes.push(netherforge_format::math::BoundingBox {
                            min: netherforge_format::math::Vec3(x, y, z),
                            max: netherforge_format::math::Vec3(x + 1.0, y + 1.0, z + 1.0),
                        });
                    }
                }
            }
        }
        boxes
    }
}

impl netherforge_platform::FakeParticleOps for Fake {
    fn spawn(
        &self,
        world: String,
        spawns: Vec<netherforge_platform::ParticleSpawn>,
        viewers: Vec<PlayerRef>,
    ) {
        for spawn in spawns {
            self.call(format!(
                "particle {world} {} x{} {:?} to {}",
                spawn.particle,
                spawn.count,
                spawn.data,
                viewers
                    .iter()
                    .map(|v| v.name.clone())
                    .collect::<Vec<_>>()
                    .join(",")
            ));
        }
    }
}

impl netherforge_platform::FakeSoundOps for Fake {
    fn play(
        &self,
        world: String,
        _: netherforge_format::math::Vec3,
        sound: netherforge_platform::SoundPlay,
    ) -> bool {
        self.call(format!(
            "sound {world} {} {} {}",
            sound.sound, sound.volume, sound.pitch
        ));
        true
    }
    fn play_to(&self, player: Uuid, sound: netherforge_platform::SoundPlay) -> bool {
        self.call(format!("sound to {} {}", player.0, sound.sound));
        true
    }
}

/// The server started with three steps of sky `demo:dusk`'s twins.
impl netherforge_platform::FakeDatapackOps for Fake {
    fn format(&self) -> Option<Vec<i32>> {
        self.datapack_format.borrow().clone()
    }
    fn main_world(&self) -> Option<String> {
        Some("world".into())
    }
    fn game(&self) -> netherforge_format::startup::GameDatapack {
        Default::default()
    }
    fn refused(&self) -> Option<netherforge_format::startup::DatapackRefusal> {
        None
    }
    fn text_json(&self, text: String, _: Box<dyn Fn(String) -> Option<String>>) -> String {
        serde_json::Value::String(text).to_string()
    }
    fn started(&self) -> std::collections::HashMap<String, Vec<u8>> {
        (1..=3)
            .map(|step| {
                (
                    format!("data/demo/worldgen/biome/sky/demo/dusk/{step}/minecraft/plains.json"),
                    Vec::new(),
                )
            })
            .collect()
    }
}

impl netherforge_platform::FakeSkyOps for Fake {
    fn show(&self, player: Uuid, sky: Option<netherforge_platform::SkyView>) {
        self.call(format!(
            "sky {} {:?}",
            player.0,
            sky.map(|s| (s.sky, s.step))
        ));
    }
}

impl netherforge_platform::FakeAdvancementOps for Fake {
    fn criteria(&self, key: String) -> Option<Vec<String>> {
        (key == "minecraft:story/mine_diamond").then(|| vec!["diamond".into()])
    }
    fn grant(&self, player: Uuid, key: String, criterion: Option<String>) -> bool {
        self.call(format!("grant {} {key} {criterion:?}", player.0));
        true
    }
}

impl netherforge_platform::FakeBossBarOps for Fake {
    fn create(&self, id: i32, look: netherforge_platform::BossBarLook) {
        self.call(format!("bar {id} made {} {}", look.text, look.progress));
    }
    fn update(&self, id: i32, look: netherforge_platform::BossBarLook) {
        self.call(format!("bar {id} {} {}", look.text, look.progress));
    }
    fn show(&self, id: i32, player: Uuid) -> bool {
        self.call(format!("bar {id} shown to {}", player.0));
        true
    }
    fn hide(&self, id: i32, player: Uuid) {
        self.call(format!("bar {id} hidden from {}", player.0));
    }
    fn remove(&self, id: i32) {
        self.call(format!("bar {id} removed"));
    }
}

impl netherforge_platform::FakeSidebarOps for Fake {
    fn show(&self, player: Uuid, title: String, lines: Vec<String>) -> bool {
        self.call(format!("sidebar {} {title} {lines:?}", player.0));
        true
    }
    fn hide(&self, player: Uuid) -> bool {
        self.call(format!("sidebar {} hidden", player.0));
        true
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        self.teams.borrow().keys().cloned().collect()
    }
    fn create(&self, name: String, _: netherforge_platform::TeamLook) -> bool {
        self.call(format!("team {name} made"));
        self.teams.borrow_mut().insert(name, Vec::new()).is_none()
    }
    fn update(&self, name: String, look: netherforge_platform::TeamLook) -> bool {
        self.call(format!("team {name} {} {:?}", look.prefix, look.color));
        true
    }
    fn remove(&self, name: String) -> bool {
        self.call(format!("team {name} removed"));
        self.teams.borrow_mut().remove(&name).is_some()
    }
    fn entries(&self, name: String) -> Option<Vec<String>> {
        self.teams.borrow().get(&name).cloned()
    }
    fn add_entry(&self, name: String, entry: String) -> bool {
        self.teams
            .borrow_mut()
            .get_mut(&name)
            .map(|e| e.push(entry))
            .is_some()
    }
    fn team_of(&self, entry: String) -> Option<String> {
        self.teams
            .borrow()
            .iter()
            .find(|(_, e)| e.contains(&entry))
            .map(|(n, _)| n.clone())
    }
    fn set_below_name(&self, player: String, text: Option<String>) {
        self.call(format!("below {player} {text:?}"));
    }
    fn clear_below_names(&self) {
        self.call("below cleared".into());
    }
}

impl netherforge_platform::FakePlayerListOps for Fake {
    fn set_listed(&self, viewer: Uuid, player: Uuid, listed: bool) -> bool {
        self.call(format!("listed {} for {} {listed}", player.0, viewer.0));
        true
    }
}

/// The world's border: 1000 across, at the origin.
impl netherforge_platform::FakeBorderOps for Fake {
    fn max_size(&self) -> f64 {
        59_999_968.0
    }
    fn max_center(&self) -> f64 {
        29_999_984.0
    }
    fn get(
        &self,
        owner: netherforge_platform::BorderOwner,
    ) -> Option<netherforge_platform::BorderState> {
        matches!(owner, netherforge_platform::BorderOwner::World(_)).then_some(
            netherforge_platform::BorderState {
                center_x: 0.0,
                center_z: 0.0,
                size: 1000.0,
                damage_amount: 0.2,
                damage_buffer: 5.0,
                warning_distance: 5,
                warning_ticks: 300,
            },
        )
    }
    fn set_size(&self, _: netherforge_platform::BorderOwner, size: f64, ticks: i64) -> bool {
        self.call(format!("border size {size} over {ticks}"));
        true
    }
    fn set_damage(&self, _: netherforge_platform::BorderOwner, amount: f64, buffer: f64) -> bool {
        self.call(format!("border damage {amount} {buffer}"));
        true
    }
}

impl Fake {
    /// The slots `r#ref` names: a storage's (made as it's first asked for), else Alex's own.
    pub fn slots_of(
        &self,
        r#ref: &netherforge_platform::InventoryRef,
    ) -> Option<Vec<Option<netherforge_platform::ItemData>>> {
        match r#ref {
            netherforge_platform::InventoryRef::Player(_) => Some(self.inventory.borrow().clone()),
            netherforge_platform::InventoryRef::Storage(s) => Some(
                self.storages
                    .borrow_mut()
                    .entry(s.name.clone())
                    .or_insert_with(|| vec![None; s.size as usize])
                    .clone(),
            ),
            _ => None,
        }
    }
}

/// Alex's own inventory and storages, and nothing else.
impl netherforge_platform::FakeInventoryOps for Fake {
    fn kind(&self, r#ref: netherforge_platform::InventoryRef) -> Option<String> {
        match r#ref {
            netherforge_platform::InventoryRef::Player(_) => Some("player".into()),
            netherforge_platform::InventoryRef::Storage(_) => Some("storage".into()),
            _ => None,
        }
    }
    fn size(&self, r#ref: netherforge_platform::InventoryRef) -> Option<i32> {
        self.slots_of(&r#ref).map(|s| s.len() as i32)
    }
    fn contents(
        &self,
        r#ref: netherforge_platform::InventoryRef,
    ) -> Option<Vec<Option<netherforge_platform::ItemData>>> {
        self.slots_of(&r#ref)
    }
    fn contents_with_filter(
        &self,
        r#ref: netherforge_platform::InventoryRef,
        _: netherforge_platform::StackFilter,
    ) -> Option<Vec<Option<netherforge_platform::ItemData>>> {
        self.contents(r#ref)
    }
    fn item(
        &self,
        r#ref: netherforge_platform::InventoryRef,
        slot: i32,
    ) -> Option<netherforge_platform::ItemData> {
        self.slots_of(&r#ref)?[slot as usize].clone()
    }
    fn set_item(
        &self,
        r#ref: netherforge_platform::InventoryRef,
        slot: i32,
        item: Option<netherforge_platform::ItemData>,
    ) -> bool {
        match r#ref {
            netherforge_platform::InventoryRef::Storage(s) => {
                self.slots_of(&netherforge_platform::InventoryRef::Storage(s.clone()));
                self.storages.borrow_mut().get_mut(&s.name).expect("made")[slot as usize] = item;
            }
            _ => self.inventory.borrow_mut()[slot as usize] = item,
        }
        true
    }
    /// Into the first empty slot of Alex's, whole.
    fn add(
        &self,
        _: netherforge_platform::InventoryRef,
        item: netherforge_platform::ItemData,
    ) -> Option<i32> {
        let mut slots = self.inventory.borrow_mut();
        match slots.iter_mut().find(|s| s.is_none()) {
            Some(slot) => {
                *slot = Some(item);
                Some(0)
            }
            None => Some(item.def.count.unwrap_or(1)),
        }
    }
}

impl netherforge_platform::FakeMenuOps for Fake {
    fn create(&self, window: Uuid, spec: netherforge_platform::WindowSpec) {
        self.call(format!("window made {} \"{}\"", spec.size, spec.title));
        self.windows
            .borrow_mut()
            .insert(window.0, (vec![None; spec.size as usize], Vec::new()));
    }
    fn destroy(&self, window: Uuid) {
        self.call("window destroyed".into());
        self.windows.borrow_mut().remove(&window.0);
    }
    fn retitle(&self, _: Uuid, title: String) {
        self.call(format!("window retitled \"{title}\""));
    }
    fn item(&self, window: Uuid, slot: i32) -> Option<netherforge_platform::ItemData> {
        self.windows.borrow().get(&window.0)?.0[slot as usize].clone()
    }
    fn set_item(
        &self,
        window: Uuid,
        slot: i32,
        item: Option<netherforge_platform::ItemData>,
    ) -> bool {
        match self.windows.borrow_mut().get_mut(&window.0) {
            Some((slots, _)) => {
                slots[slot as usize] = item;
                true
            }
            None => false,
        }
    }
    fn open(&self, window: Uuid, player: Uuid) -> bool {
        match self.windows.borrow_mut().get_mut(&window.0) {
            Some((_, viewers)) => {
                viewers.push(player);
                true
            }
            None => false,
        }
    }
    fn viewers(&self, window: Uuid) -> Vec<Uuid> {
        self.windows
            .borrow()
            .get(&window.0)
            .map(|w| w.1.clone())
            .unwrap_or_default()
    }
    fn viewing(&self, player: Uuid) -> Option<Uuid> {
        self.windows
            .borrow()
            .iter()
            .find(|(_, w)| w.1.contains(&player))
            .map(|(id, _)| Uuid(*id))
    }
    /// Room for a full stack of 64.
    fn room(&self, window: Uuid, slot: i32, _: netherforge_platform::ItemData) -> i32 {
        64 - self
            .item(window, slot)
            .map_or(0, |i| i.def.count.unwrap_or(1))
    }
    fn set_cursor(
        &self,
        _: Uuid,
        player: Uuid,
        item: Option<netherforge_platform::ItemData>,
    ) -> bool {
        self.call(format!(
            "cursor {} {:?}",
            player.0,
            item.map(|i| (i.def.kind, i.def.count))
        ));
        true
    }
    fn refresh(&self, _: Uuid) {
        self.call("window refreshed".into());
    }
    fn close(&self, window: Uuid, player: Option<Uuid>) {
        if let Some((_, viewers)) = self.windows.borrow_mut().get_mut(&window.0) {
            viewers.retain(|v| player.is_some_and(|p| p != *v));
        }
    }
}

impl netherforge_platform::FakeDialogOps for Fake {
    fn show(&self, player: Uuid, dialog: netherforge_platform::DialogSpec) -> bool {
        let initial = dialog.file.inputs.first().map(|i| format!("{i:?}"));
        self.call(format!(
            "dialog {} to {} \"{}\" {initial:?}",
            dialog.id, player.0, dialog.file.title
        ));
        true
    }
    fn close(&self, player: Uuid) -> bool {
        self.call(format!("dialog closed for {}", player.0));
        true
    }
}

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, player: Uuid, nodes: std::collections::HashMap<String, bool>) {
        let mut nodes: Vec<String> = nodes.into_iter().map(|(n, v)| format!("{n}={v}")).collect();
        nodes.sort();
        self.call(format!("permissions {} [{}]", player.0, nodes.join(" ")));
    }
}

/// Commands kept by name, a name taken once; `vanilla` is the server's own.
impl netherforge_platform::FakeCommandOps for Fake {
    /// Runs what it knows of (anything but a line starting `nothing`).
    fn run_console(&self, line: String) -> bool {
        self.call(format!("console {line}"));
        !line.starts_with("nothing")
    }
    fn register(
        &self,
        spec: netherforge_platform::CommandSpec,
        handler: std::rc::Rc<dyn netherforge_platform::CommandHandler>,
    ) -> bool {
        let mut commands = self.commands.borrow_mut();
        if spec.name == "vanilla" || commands.contains_key(&spec.name) {
            return false;
        }
        self.call(format!("command registered /{}", spec.name));
        commands.insert(spec.name.clone(), (spec, handler));
        true
    }
    fn unregister(&self, name: String) {
        self.call(format!("command unregistered /{name}"));
        self.commands.borrow_mut().remove(&name);
    }
}

/// Stacks rewritten to their item's look: none are stale here.
impl netherforge_platform::FakeProjectItemOps for Fake {
    fn refresh(&self, _: netherforge_platform::InventoryRef) -> i32 {
        0
    }
}

/// Stacks sent again as they look to a player: nothing to send in the fake.
impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: Uuid) -> bool {
        true
    }
}

/// Whoever runs a command in a test: its replies go in the fake's calls.
pub struct TestSender {
    pub player: Option<PlayerRef>,
    pub allowed: bool,
    pub replies: RefCell<Vec<String>>,
}

impl netherforge_platform::CommandSender for TestSender {
    fn player(&self) -> Option<PlayerRef> {
        self.player.clone()
    }
    fn name(&self) -> String {
        self.player
            .as_ref()
            .map_or("CONSOLE".into(), |p| p.name.clone())
    }
    fn location(&self) -> Option<netherforge_platform::Location> {
        self.player
            .as_ref()
            .map(|_| netherforge_platform::Location {
                world: "world".into(),
                x: 1.0,
                y: 64.0,
                z: 2.0,
                yaw: 90.0,
                pitch: 0.0,
            })
    }
    fn reply(&self, mini_message: String) {
        self.replies.borrow_mut().push(mini_message);
    }
    fn has_permission(&self, _: String) -> bool {
        self.allowed
    }
}

/// The server's recipes: each call said.
impl netherforge_platform::FakeRecipeOps for Fake {
    fn add(&self, recipe: netherforge_platform::RecipeSpec) -> bool {
        self.call(format!("recipe add {}", recipe.id));
        true
    }
    fn remove(&self, id: String) -> bool {
        self.call(format!("recipe remove {id}"));
        true
    }
    fn resend(&self) {
        self.call("recipe resend".into());
    }
    fn discover(&self, player: Uuid, recipe: String) -> bool {
        self.call(format!("recipe discover {} {recipe}", player.0));
        true
    }
}

/// The game's one loot table: a diamond, as many as the seed is odd (one or two).
impl netherforge_platform::FakeLootOps for Fake {
    fn exists(&self, table: String) -> bool {
        table == "minecraft:chests/simple"
    }
    fn roll(
        &self,
        table: String,
        context: netherforge_platform::GameLootContext,
        seed: i64,
    ) -> Option<Vec<netherforge_platform::ItemData>> {
        self.call(format!("game loot {table} at {}", at(&context.location)));
        let mut diamond = stack("minecraft:diamond");
        diamond.def.count = Some(1 + (seed & 1) as i32);
        (table == "minecraft:chests/simple").then(|| vec![diamond])
    }
}

/// Worlds saved into `storage`: the main one's dimension is the overworld's.
impl netherforge_platform::FakeWorldManagerOps for Fake {
    fn save_files(&self, name: String) -> Option<netherforge_platform::WorldFiles> {
        let storage = self.storage.borrow().clone()?;
        if !FakeWorldOps::exists(self, name.clone()) {
            return None;
        }
        self.saved_worlds.borrow_mut().push(name.clone());
        let dimension = if name == "world" {
            "overworld".to_owned()
        } else {
            name
        };
        Some(netherforge_platform::WorldFiles {
            level: storage.join("level.dat"),
            dimension: storage.join("dimensions").join("minecraft").join(dimension),
        })
    }
}

/// A structure saved as a line of its size and one per block of stone in it.
impl netherforge_platform::FakeStructureOps for Fake {
    fn save(
        &self,
        file: std::path::PathBuf,
        _: String,
        min: netherforge_platform::BlockVector,
        max: netherforge_platform::BlockVector,
        _: bool,
    ) -> bool {
        let text = format!(
            "size {} {} {}\n",
            max.x - min.x + 1,
            max.y - min.y + 1,
            max.z - min.z + 1
        );
        std::fs::write(file, text).is_ok()
    }
    fn forget(&self, _: std::path::PathBuf) {}
}

/// MiniMessage's escape, as the adapter's would: a tag's `<` shown as written.
impl netherforge_platform::FakeTextOps for Fake {
    fn escape(&self, text: String) -> String {
        text.replace('<', "\\<")
    }
}

/// Runs `line` (`nf scripts all`) as `player` (none: the console), as the server would a command whose one
/// argument is the rest of the line: what it replied.
pub fn command(platform: &Fake, player: Option<PlayerRef>, line: &str) -> Vec<String> {
    let (name, rest) = line.split_once(' ').unwrap_or((line, ""));
    let handler = {
        let commands = platform.commands.borrow();
        let found = commands
            .values()
            .find(|(spec, _)| spec.name == name || spec.aliases.iter().any(|a| a == name))
            .map(|(_, handler)| handler.clone());
        found.unwrap_or_else(|| panic!("no command /{name}"))
    };
    let sender = std::rc::Rc::new(TestSender {
        player,
        allowed: true,
        replies: RefCell::default(),
    });
    let mut arguments = std::collections::HashMap::new();
    if !rest.is_empty() {
        arguments.insert(
            "arguments".to_owned(),
            netherforge_platform::ArgumentValue::Text(netherforge_platform::ArgumentValueText {
                text: rest.to_owned(),
            }),
        );
    }
    handler.run(
        sender.clone(),
        name.to_owned(),
        netherforge_platform::CommandInput {
            path: Vec::new(),
            arguments,
            text: rest.to_owned(),
        },
    );
    sender.replies.take()
}

/// What completing `line` (`nf spawn `) offers.
pub fn complete(platform: &Fake, line: &str) -> Vec<String> {
    let (name, rest) = line.split_once(' ').unwrap_or((line, ""));
    let handler = {
        let commands = platform.commands.borrow();
        let found = commands
            .values()
            .find(|(spec, _)| spec.name == name || spec.aliases.iter().any(|a| a == name))
            .map(|(_, handler)| handler.clone());
        found.unwrap_or_else(|| panic!("no command /{name}"))
    };
    let sender = std::rc::Rc::new(TestSender {
        player: None,
        allowed: true,
        replies: RefCell::default(),
    });
    handler
        .suggest(
            sender,
            netherforge_platform::SuggestionRequest {
                path: Vec::new(),
                argument: "arguments".into(),
                arguments: std::collections::HashMap::new(),
                partial: rest.to_owned(),
            },
        )
        .suggestions
}

/// Alex, as a command's sender.
pub fn alex() -> PlayerRef {
    PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    }
}

/// The server held alive while the debugger pauses it: said in the calls.
impl netherforge_platform::FakePauseOps for Fake {
    fn hold(&self, message: String) {
        self.call(format!("hold {message}"));
    }
}

impl FakePlatform for Fake {
    fn text(&self) -> &dyn netherforge_platform::TextOps {
        self
    }
    fn pause(&self) -> &dyn netherforge_platform::PauseOps {
        self
    }
    fn world_manager(&self) -> &dyn netherforge_platform::WorldManagerOps {
        self
    }
    fn structures(&self) -> &dyn netherforge_platform::StructureOps {
        self
    }
    fn export_game_data(&self) -> netherforge_format::game::GameDataBundle {
        serde_json::from_value(serde_json::json!({ "minecraft": "26.3" })).expect("a bundle")
    }
    fn shutdown_server(&self, reason: String) {
        *self.shutdown.borrow_mut() = Some(reason);
    }
    fn commands(&self) -> &dyn netherforge_platform::CommandOps {
        self
    }
    fn loot(&self) -> &dyn netherforge_platform::LootOps {
        self
    }
    fn recipes(&self) -> &dyn netherforge_platform::RecipeOps {
        self
    }
    fn project_items(&self) -> &dyn netherforge_platform::ProjectItemOps {
        self
    }
    fn item_views(&self) -> &dyn netherforge_platform::ItemViewOps {
        self
    }
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
    fn game(&self) -> &dyn netherforge_format::game::GameData {
        &self.game
    }
    fn entities(&self) -> &dyn EntityOps {
        self
    }
    fn worlds(&self) -> &dyn WorldOps {
        self
    }
    fn permissions(&self) -> &dyn netherforge_platform::PermissionOps {
        self
    }
    fn particles(&self) -> &dyn netherforge_platform::ParticleOps {
        self
    }
    fn datapacks(&self) -> &dyn netherforge_platform::DatapackOps {
        self
    }
    fn skies(&self) -> &dyn netherforge_platform::SkyOps {
        self
    }
    fn advancements(&self) -> &dyn netherforge_platform::AdvancementOps {
        self
    }
    fn boss_bars(&self) -> &dyn netherforge_platform::BossBarOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn borders(&self) -> &dyn netherforge_platform::BorderOps {
        self
    }
    fn inventories(&self) -> &dyn netherforge_platform::InventoryOps {
        self
    }
    fn menus(&self) -> &dyn netherforge_platform::MenuOps {
        self
    }
    fn dialogs(&self) -> &dyn netherforge_platform::DialogOps {
        self
    }
    fn player_list(&self) -> &dyn netherforge_platform::PlayerListOps {
        self
    }
    fn sidebars(&self) -> &dyn netherforge_platform::SidebarOps {
        self
    }
    fn sounds(&self) -> &dyn netherforge_platform::SoundOps {
        self
    }
    fn client_entities(&self) -> &dyn netherforge_platform::ClientEntityOps {
        self
    }
    fn blocks(&self) -> &dyn netherforge_platform::BlockOps {
        self
    }
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn bots(&self) -> &dyn netherforge_platform::BotOps {
        &self.bots
    }
}

pub struct Files {
    /// Leaked anew on each change: a test's few lists, so a running session can see files come and go.
    pub names: RefCell<&'static std::collections::BTreeSet<String>>,
    pub texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    pub fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: RefCell::new(Box::leak(Box::new(
                files.iter().map(|(p, _)| p.to_string()).collect(),
            ))),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
    pub fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl Files {
    pub fn delete(&self, path: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.remove(path);
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.texts.borrow_mut().remove(path);
    }
    pub fn add(&self, path: &str, text: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.insert(path.into());
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.write(path, text);
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &std::collections::BTreeSet<String> {
        *self.names.borrow()
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

pub const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

pub fn lamp(head_y: f64) -> String {
    format!(
        r#"{{ "nodes": {{
  "base": {{ "display": {{ "type": "block", "block": "minecraft:stone" }} }},
  "head": {{ "parent": "base", "transform": {{ "translation": [0, {head_y}, 0] }}, "display": {{ "type": "block", "block": "minecraft:glowstone" }} }}
}} }}"#
    )
}

pub fn place(x: f64, y: f64, z: f64) -> Location {
    Location {
        world: "world".into(),
        x,
        y,
        z,
        yaw: 0.0,
        pitch: 0.0,
    }
}

pub use netherforge_format::bridge::{BotActResult, BotAction, BotInfo, BotPackAnswer};
pub use netherforge_platform::{BotAim, BotJoin};

pub type Done<T> = Box<dyn Fn(Result<T, PlatformError>)>;

/// A bot walking: who, to where (x, z), and what to call once there.
pub type Walk = (String, f64, f64, Done<BotActResult>);

#[derive(Clone)]
pub struct FakeBot {
    pub name: String,
    pub id: Uuid,
    pub at: Location,
}

impl FakeBot {
    pub fn player(&self) -> PlayerRef {
        PlayerRef {
            uuid: self.id,
            name: self.name.clone(),
        }
    }
    pub fn result(&self) -> BotActResult {
        BotActResult {
            world: self.at.world.clone(),
            x: self.at.x,
            y: self.at.y,
            z: self.at.z,
            since: 0,
        }
    }
}

/// Bots as the Platform has them: a join lands at the fake's next `tick`, a walk arrives there then, anything else
/// is done at once (a respawn of a bot that's alive fails), and every join and action is kept.
#[derive(Default)]
pub struct FakeBots {
    pub online: RefCell<Vec<FakeBot>>,
    pub joining: RefCell<Vec<(BotJoin, Done<BotInfo>)>>,
    pub walking: RefCell<Vec<Walk>>,
    pub joins: RefCell<Vec<BotJoin>>,
    pub acted: RefCell<Vec<(String, BotAction, Option<BotAim>)>>,
    pub next: std::cell::Cell<u128>,
}

impl FakeBots {
    pub fn by_name(&self, name: &str) -> Option<FakeBot> {
        self.online
            .borrow()
            .iter()
            .find(|b| b.name.eq_ignore_ascii_case(name))
            .cloned()
    }
    pub fn by_id(&self, id: Uuid) -> Option<FakeBot> {
        self.online.borrow().iter().find(|b| b.id == id).cloned()
    }
    pub fn info(bot: &FakeBot) -> BotInfo {
        BotInfo {
            name: bot.name.clone(),
            uuid: netherforge_runtime::events::uuid_text(&bot.id),
            world: bot.at.world.clone(),
            x: bot.at.x,
            y: bot.at.y,
            z: bot.at.z,
            events: 0,
        }
    }
    /// The server's tick: joins land and walks arrive.
    pub fn tick(&self) {
        let joining = std::mem::take(&mut *self.joining.borrow_mut());
        for (join, done) in joining {
            self.next.set(self.next.get() + 1);
            let at = join.at.as_ref().map_or_else(
                || place(0.5, 64.0, 0.5),
                |p| Location {
                    world: p.world.clone().unwrap_or_else(|| "world".into()),
                    x: p.x,
                    y: p.y,
                    z: p.z,
                    yaw: 0.0,
                    pitch: 0.0,
                },
            );
            let bot = FakeBot {
                name: join.name.clone(),
                id: Uuid(1000 + self.next.get()),
                at,
            };
            self.online.borrow_mut().push(bot.clone());
            done(Ok(Self::info(&bot)));
        }
        let walking = std::mem::take(&mut *self.walking.borrow_mut());
        for (name, x, z, done) in walking {
            let mut online = self.online.borrow_mut();
            let Some(bot) = online.iter_mut().find(|b| b.name == name) else {
                done(Err(PlatformError {
                    message: format!("{name} left"),
                    cause: None,
                }));
                continue;
            };
            bot.at.x = x;
            bot.at.z = z;
            let result = bot.result();
            drop(online);
            done(Ok(result));
        }
    }
}

impl netherforge_platform::FakeBotOps for FakeBots {
    fn join(&self, join: BotJoin, done: Done<BotInfo>) {
        self.joins.borrow_mut().push(join.clone());
        self.joining.borrow_mut().push((join, done));
    }
    fn leave(&self, name: String) {
        self.online.borrow_mut().retain(|b| b.name != name);
    }
    fn list(&self) -> Vec<BotInfo> {
        self.online.borrow().iter().map(Self::info).collect()
    }
    fn is_bot(&self, id: Uuid) -> bool {
        self.by_id(id).is_some()
    }
    /// What a bot sees: where it stands, healthy, nothing open.
    fn state(&self, name: String) -> netherforge_format::bridge::BotState {
        let bot = self.by_name(&name).expect("a bot online");
        serde_json::from_value(serde_json::json!({
            "name": bot.name, "uuid": netherforge_runtime::events::uuid_text(&bot.id), "world": bot.at.world,
            "x": bot.at.x, "y": bot.at.y, "z": bot.at.z, "yaw": 0.0, "pitch": 0.0, "onGround": true, "health": 20.0,
            "food": 20, "gameMode": "survival", "dead": false, "sneaking": false, "sprinting": false,
            "selectedSlot": 0, "inventory": [], "events": 0
        }))
        .expect("a state")
    }
    /// Nothing arrived.
    fn events(&self, _: String, since: i32) -> netherforge_format::bridge::BotEvents {
        netherforge_format::bridge::BotEvents {
            events: Vec::new(),
            next: since,
            dropped: 0,
        }
    }
    fn act(&self, name: String, action: BotAction, aim: Option<BotAim>, done: Done<BotActResult>) {
        self.acted
            .borrow_mut()
            .push((name.clone(), action.clone(), aim));
        let Some(bot) = self.by_name(&name) else {
            return done(Err(PlatformError {
                message: format!("no bot called {name}"),
                cause: None,
            }));
        };
        match action {
            BotAction::WalkTo { x, z, .. } => self.walking.borrow_mut().push((name, x, z, done)),
            BotAction::Respawn => done(Err(PlatformError {
                message: format!("{name} isn't dead"),
                cause: None,
            })),
            _ => done(Ok(bot.result())),
        }
    }
}

pub fn stack(item: &str) -> netherforge_platform::ItemData {
    netherforge_platform::ItemData {
        def: netherforge_format::item::ItemDef {
            kind: item.into(),
            ..Default::default()
        },
        raw: None,
        look: None,
    }
}
