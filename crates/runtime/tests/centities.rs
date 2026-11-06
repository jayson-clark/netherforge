//! Centities on the Rust runtime against a fake Platform that records what's asked of the server's
//! entities: an instance spawned as an anchor and its displays, synced each tick writing only what changed,
//! moved onto a reloaded definition, and removed.

use std::cell::RefCell;
use std::collections::BTreeMap;

use netherforge_format::kind::centity::DisplayDef;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    ClickButton, DisplayLook, DisplayPose, EntityOps, EntityRole, EntityTag, FakeEntityOps,
    FakePlatform, FakePlatformLog, FakeWorldOps, Location, PlatformError, PlatformLog, PlayerRef,
    Ray, ServerInfo, Uuid, WorldOps,
};
use netherforge_runtime::session::Session;

/// Game facts that know no registry, and every block as one without properties.
struct NoFacts;

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
type FakeWindow = (Vec<Option<netherforge_platform::ItemData>>, Vec<Uuid>);

/// A command the fake server took: its spec and its handler.
type Registered = (
    netherforge_platform::CommandSpec,
    std::rc::Rc<dyn netherforge_platform::CommandHandler>,
);

struct Fake {
    /// The commands registered, by name.
    commands: RefCell<BTreeMap<String, Registered>>,
    /// Each open window's slots and viewers, by window.
    windows: RefCell<BTreeMap<u128, FakeWindow>>,
    /// Storages, by name.
    storages: RefCell<BTreeMap<String, Vec<Option<netherforge_platform::ItemData>>>>,
    /// Alex's inventory: nine slots.
    inventory: RefCell<Vec<Option<netherforge_platform::ItemData>>>,
    /// Each scoreboard team's entries.
    teams: RefCell<BTreeMap<String, Vec<String>>>,
    game: NoFacts,
    /// Whether every chunk's entities are loaded.
    unloaded: std::cell::Cell<bool>,
    /// The one player online, and where they are.
    player: RefCell<Option<Location>>,
    calls: RefCell<Vec<String>>,
    next: RefCell<u128>,
    poses: RefCell<BTreeMap<u128, DisplayPose>>,
    /// Blocks of the floor that were broken.
    broken: RefCell<std::collections::HashSet<(i32, i32, i32)>>,
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
        }
    }
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.calls.borrow_mut())
    }
    fn call(&self, what: String) {
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

fn at(l: &Location) -> String {
    format!("{} {} {} {}", l.world, l.x, l.y, l.z)
}

impl FakeEntityOps for Fake {
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
    fn known(&self, _: String) -> Option<PlayerRef> {
        None
    }
    fn get(&self, uuid: Uuid) -> Option<PlayerRef> {
        (uuid == Uuid(77)).then(|| PlayerRef {
            uuid,
            name: "Alex".into(),
        })
    }
    fn online(&self) -> Vec<PlayerRef> {
        match &*self.player.borrow() {
            Some(_) => vec![PlayerRef {
                uuid: Uuid(77),
                name: "Alex".into(),
            }],
            None => Vec::new(),
        }
    }
    fn location(&self, uuid: Uuid) -> Option<Location> {
        (uuid == Uuid(77))
            .then(|| self.player.borrow().clone())
            .flatten()
    }
}

impl FakeWorldOps for Fake {
    fn names(&self) -> Vec<String> {
        vec!["world".into()]
    }
    fn exists(&self, world: String) -> bool {
        world == "world"
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
    fn slots_of(
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

/// Whoever runs a command in a test: its replies go in the fake's calls.
struct TestSender {
    player: Option<PlayerRef>,
    allowed: bool,
    replies: RefCell<Vec<String>>,
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

impl FakePlatform for Fake {
    fn commands(&self) -> &dyn netherforge_platform::CommandOps {
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
}

struct Files {
    /// Leaked anew on each change: a test's few lists, so a running session can see files come and go.
    names: RefCell<&'static std::collections::BTreeSet<String>>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
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
    fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl Files {
    fn delete(&self, path: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.remove(path);
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.texts.borrow_mut().remove(path);
    }
    fn add(&self, path: &str, text: &str) {
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

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

fn lamp(head_y: f64) -> String {
    format!(
        r#"{{ "nodes": {{
  "base": {{ "display": {{ "type": "block", "block": "minecraft:stone" }} }},
  "head": {{ "parent": "base", "transform": {{ "translation": [0, {head_y}, 0] }}, "display": {{ "type": "block", "block": "minecraft:glowstone" }} }}
}} }}"#
    )
}

fn place(x: f64, y: f64, z: f64) -> Location {
    Location {
        world: "world".into(),
        x,
        y,
        z,
        yaw: 0.0,
        pitch: 0.0,
    }
}

#[test]
fn an_instance_is_drawn_synced_reloaded_and_removed() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(session.refusal().is_none(), "{:?}", session.problems());
    platform.take();

    // Spawned: its anchor, a display per node with its offset in its pose, each riding the anchor.
    let lamp = session
        .spawn("lamp", place(10.0, 64.0, 5.0), 0.0)
        .expect("spawned");
    let calls = platform.take();
    assert_eq!(calls[0], "anchor 1 at world 10 64 5");
    assert_eq!(
        calls[1],
        "display 2 base block at world 10 64 5 offset 0 0 0"
    );
    assert_eq!(
        calls[2],
        "display 3 head block at world 10 64 5 offset 0 1 0"
    );
    assert!(calls.contains(&"mount 2 on 1".to_owned()), "{calls:?}");
    assert!(calls.contains(&"mount 3 on 1".to_owned()), "{calls:?}");
    assert!(
        session
            .spawn("nothing", place(0.0, 0.0, 0.0), 0.0)
            .is_none()
    );
    assert!(
        session
            .spawn(
                "lamp",
                Location {
                    world: "nether".into(),
                    ..place(0.0, 0.0, 0.0)
                },
                0.0
            )
            .is_none()
    );

    // Nothing changed: nothing is sent.
    session.tick();
    session.tick();
    assert_eq!(platform.take(), Vec::<String>::new());

    // Turned a quarter: every display turns (its rotation is in its pose); the head, straight above, stays above.
    session.centities().get_mut(lamp).unwrap().turn_to(90.0);
    session.tick();
    assert_eq!(
        platform.take(),
        ["pose 2 0 0 0 eased 1", "pose 3 0 1 0 eased 1"]
    );

    // A reload moves the instance onto the new definition: only what changed is re-posed.
    files.write("centities/lamp/centity.json", &self::lamp(2.0));
    session.reload(&["centities/lamp/centity.json".into()]);
    let calls = platform.take();
    let poses: Vec<&String> = calls.iter().filter(|c| c.starts_with("pose")).collect();
    assert!(
        poses.iter().any(|c| c.starts_with("pose 3 0 2 0")),
        "{calls:?}"
    );
    assert!(
        !calls.iter().any(|c| c.starts_with("display")),
        "kept: {calls:?}"
    );

    // Removed: its entities go.
    assert!(session.centities().remove(&*platform, lamp));
    let calls = platform.take();
    for id in [1, 2, 3] {
        assert!(calls.contains(&format!("remove {id}")), "{calls:?}");
    }
    assert!(session.centities().get(lamp).is_none());
}

/// The lamp, with a script.
fn scripted_lamp() -> String {
    let lamp = lamp(1.0);
    let end = lamp.rfind('}').expect("an object");
    format!(
        r#"{}, "script": {{ "file": "script.lua" }} }}"#,
        lamp[..end].trim_end()
    )
}

/// What scripts printed, in order: the log's `info [who] line` without the location.
fn printed(calls: &[String]) -> Vec<String> {
    calls
        .iter()
        .filter_map(|c| c.strip_prefix("info ["))
        .map(|c| {
            let (_, rest) = c.split_once("] ").expect("a label");
            rest.rsplit_once(" (")
                .map_or(rest, |(line, _)| line)
                .to_owned()
        })
        .collect()
}

#[test]
fn scripts_spawn_instances_and_an_instance_runs_its_own_script() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &scripted_lamp()),
        (
            "centities/lamp/script.lua",
            r#"
print("body", this:kind(), this:node("head"):translation().y)
this:node("head"):set_translation(vector.create(0, 3, 0), { instant = true })
local ticks = 0
this:on("spawn", function(e) print("spawn", e.centity == this) end)
this:on("tick", function() ticks += 1 end)
this:on("remove", function() print("remove after", ticks, "ticks", this:exists()) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(1, 64, 2))
print("spawned", lamp:exists(), lamp:position().x, #lamp:nodes(), lamp:node("nope"))
print("found", nf.centities.get(lamp:id()) == lamp, #nf.centities.all({ kind = "lamp" }))
print("world", lamp:node("head"):world_position().y)
print(pcall(nf.centities.spawn, "lmap", vector.create(0, 0, 0)))
nf.after(3, function()
    lamp:remove()
    print("gone", lamp:exists(), lamp:position(), nf.centities.get(lamp:id()))
    lamp:on("tick", function() print("never") end)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(session.refusal().is_none(), "{:?}", session.problems());
    assert_eq!(
        printed(&platform.take()),
        [
            "body\tlamp\t1",
            "spawn\ttrue",
            "spawned\ttrue\t1\t2\tnil",
            "found\ttrue\t1",
            "world\t67",
            "false\tmodules/game/init.lua:6: no centity \"lmap\" in this project (did you mean \"lamp\"?)",
        ]
    );
    for _ in 0..4 {
        session.tick();
    }
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        ["remove after\t2\tticks\ttrue", "gone\tfalse\tnil\tnil"],
        "{calls:?}"
    );
    assert!(calls.contains(&"remove 1".to_owned()), "{calls:?}");
    assert!(session.centities().all().next().is_none());
}

#[test]
fn a_reload_restarts_an_instances_script_without_spawn() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = scripted_lamp();
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        (
            "centities/lamp/script.lua",
            r#"print("v1"); this:on("spawn", function() print("spawn") end)"#,
        ),
        (
            "modules/game/init.lua",
            r#"local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
lamp:node("head"):on("click", function() end)"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["v1", "spawn"], "{start:?}");
    files.write(
        "centities/lamp/script.lua",
        r#"print("v2", this:node("head") ~= nil); this:on("spawn", function() print("spawn") end)"#,
    );
    session.reload(&["centities/lamp/script.lua".into()]);
    assert_eq!(printed(&platform.take()), ["v2\ttrue"]);
}

#[test]
fn a_clip_is_sent_ahead_as_keyframes_and_its_end_is_heard() {
    let platform = std::rc::Rc::new(Fake::default());
    let lamp = lamp(1.0);
    let end = lamp.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "animations": {{ "rise": {{ "loop": "once", "tracks": {{ "head": {{ "translation": [
  {{ "time": 0, "value": [0, 1, 0] }}, {{ "time": 1, "value": [0, 3, 0] }} ] }} }} }} }}, "script": {{ "file": "script.lua" }} }}"#,
        lamp[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        (
            "centities/lamp/script.lua",
            r#"
this:on("animation_start", function(e) print("start", e.animation) end)
this:on("animation_end", function(e) print("end", e.animation) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
print(lamp:play_animation("rise"), lamp:is_animation_playing("rise"), table.concat(lamp:animations(), ","))
print(pcall(lamp.play_animation, lamp, "fall"))
nf.after(10, function() print("at", string.format("%.3f", lamp:animation_tick("rise"))) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "start\trise",
            "true\ttrue\trise",
            "false\tmodules/game/init.lua:4: lamp has no animation \"fall\" (it has: rise)",
        ],
        "{start:?}"
    );
    let mut poses = Vec::new();
    let mut lines = Vec::new();
    for _ in 0..25 {
        session.tick();
        let calls = platform.take();
        lines.extend(printed(&calls));
        poses.extend(calls.into_iter().filter(|c| c.starts_with("pose 3 ")));
    }
    // Timers run before the clips move on: nine ticks in.
    assert_eq!(lines[0], "at\t9.000", "{lines:?}");
    assert_eq!(lines.len(), 2, "{lines:?}");
    assert_eq!(lines[1], "end\trise", "{lines:?}");
    // A pose now (the clip just started), one planned most of the way, and back to rest at the end.
    assert!(poses.len() <= 4, "{poses:?}");
    assert!(poses.iter().any(|p| p.ends_with("eased 17")), "{poses:?}");
    assert!(
        poses.last().unwrap().starts_with("pose 3 0 1 0"),
        "{poses:?}"
    );
}

#[test]
fn a_click_goes_to_the_hitbox_the_line_of_sight_meets_and_up_its_path() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r#"{ "nodes": {
  "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
  "head": { "parent": "base", "transform": { "translation": [0, 1, 0] }, "hitbox": {} }
}, "script": { "file": "script.lua" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", centity),
        (
            "centities/lamp/script.lua",
            r#"
local function said(where)
    return function(e)
        print(where, e.target:name(), e.click, e.hit_position and e.hit_position.y)
    end
end
this:node("head"):on("click", said("head"))
this:node("base"):on("click", said("base"))
this:on("click", function(e)
    said("centity")(e)
    if e.target:name() == "base" then e:stop() end
end)
nf.on("centity_click", said("nf"))
nf.after(3, function() this:node("head"):set_clickable(false) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
nf.centities.spawn("lamp", vector.create(10, 64, 0))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    // A hitbox for each node, each a unit cube where its node is: off the anchor, so standing on its own.
    let hitboxes: Vec<&String> = start.iter().filter(|c| c.starts_with("hitbox")).collect();
    assert_eq!(
        hitboxes,
        [
            "hitbox 2 base at world 10.5 64 0.5 size 1 1",
            "hitbox 3 head at world 10.5 65 0.5 size 1 1"
        ],
        "{start:?}"
    );
    let player = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let down = |x: f64, z: f64| Ray {
        world: "world".into(),
        x,
        y: 70.0,
        z,
        dx: 0.0,
        dy: -1.0,
        dz: 0.0,
    };
    // The client picked the base, but the line of sight meets the head's top first.
    assert!(session.entity_clicked(Uuid(2), &player, ClickButton::Right, Some(down(10.5, 0.5))));
    // What scripts print is logged with the next tick's report.
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "head\thead\tright\t66",
            "base\thead\tright\t66",
            "centity\thead\tright\t66",
            "nf\thead\tright\t66"
        ]
    );
    // Without a line of sight, the entity the client picked; the centity's handler stops it there.
    assert!(session.entity_clicked(Uuid(2), &player, ClickButton::Left, None));
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        ["base\tbase\tleft\tnil", "centity\tbase\tleft\tnil"]
    );
    // Not one of ours.
    assert!(!session.entity_clicked(Uuid(999), &player, ClickButton::Left, None));
    // The head takes no clicks: its hitbox collapses, and the same line of sight reaches the base.
    session.tick();
    let calls = platform.take();
    assert!(
        calls.iter().any(|c| c.starts_with("resize 3 0.01 0.01")),
        "{calls:?}"
    );
    assert!(session.entity_clicked(Uuid(3), &player, ClickButton::Right, Some(down(10.5, 0.5))));
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        ["base\tbase\tright\t65", "centity\tbase\tright\t65"]
    );
}

#[test]
fn a_script_changes_looks_displays_and_turns() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r##"{ "nodes": {
  "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
  "label": { "parent": "base", "transform": { "translation": [0, 2, 0] }, "display": { "type": "text", "text": "hi" } },
  "panel": { "display": { "type": "rect", "color": "#ff0000" } }
} }"##;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/sign/centity.json", centity),
        (
            "modules/game/init.lua",
            r##"
local sign = nf.centities.spawn("sign", vector.create(0, 64, 0))
print(sign:is_glowing(), sign:set_glowing(true), sign:is_glowing())
print(sign:set_glow_color("#00ff00"), sign:glow_color(), sign:node("label"):glow_color())
print(pcall(sign.set_glow_color, sign, "green"))
local label = sign:node("label")
print(label:set_display_text_style({ alignment = "left", opacity = 128 }))
local style = label:display_text_style()
print(style.alignment, style.opacity, style.line_width, style.shadow)
local panel = sign:node("panel")
print(panel:display_size().x, panel:set_display_size(vector.create(2, 0.5, 0)), panel:display_size().y)
print(pcall(panel.set_display_size, panel, vector.create(0, 1, 0)))
print(panel:display_anchor(), panel:set_display_anchor("bottom"), panel:display_anchor())
print(sign:node("base"):set_brightness({ block_light = 15, sky_light = 3 }), sign:node("base"):brightness().sky_light)
local b = sign:bounds()
print(b.min.y, b.max.y)
sign:look_at(vector.create(10, 64, 0))
print(sign:yaw())
label:rotate(vector.create(0, 1, 0), 90)
print(string.format("%.3f", label:rotation().y), string.format("%.3f", label:world_rotation().y))
label:look_at(vector.create(0, 66, 5))
print(string.format("%.1f %.1f", label:rotation().x, label:rotation().y))
"##,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "false\ttrue\ttrue",
            "true\t#00ff00\tnil",
            "false\tmodules/game/init.lua:5: bad argument 'color' (#RRGGBB expected, got \"green\")",
            "true",
            "left\t128\t200\tfalse",
            "1\ttrue\t0.5",
            "false\tmodules/game/init.lua:12: bad argument 'size' (a width and height more than 0, got 0 by 1)",
            "center\ttrue\tbottom",
            "true\t3",
            "64\t65",
            "-90",
            "90.000\t0.000",
            "0.0 -90.0",
        ],
        "{start:?}"
    );
}

#[test]
fn a_script_adds_and_removes_nodes_and_tweens_one() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
local arm = lamp:add_node("arm", { transform = { translation = vector.create(1, 0, 0) }, display = { type = "block", block = "minecraft:oak_log" } })
local hand = arm:add_child("hand", { display = { type = "block", block = "minecraft:glass" }, hitbox = {} })
print(#lamp:nodes(), hand:parent():name(), hand:world_position().x)
print(pcall(lamp.add_node, lamp, "arm", {}))
print(pcall(lamp.add_node, lamp, "x", { display = { type = "block", blok = "minecraft:stone" } }))
print(pcall(lamp.add_node, lamp, "y", { itemTransform = 1 }))
print(pcall(lamp.node(lamp, "base").remove, lamp:node("base")))
arm:tween({ translation = vector.create(3, 0, 0), ticks = 4, easing = "linear" })
nf.after(2, function() print("tween", arm:translation().x, arm:is_tweening()) end)
nf.after(6, function()
    print("tween", arm:translation().x, arm:is_tweening())
    print(arm:remove(), #lamp:nodes(), lamp:node("hand"))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start)
            .get(..5)
            .unwrap_or_else(|| panic!("{start:?}")),
        [
            "4\tarm\t1",
            "false\tmodules/game/init.lua:6: bad argument 'name' (the centity already has a node called \"arm\")",
            "false\tmodules/game/init.lua:7: definition.display: Unknown key \"blok\"",
            "false\tmodules/game/init.lua:8: definition.itemTransform: Lua spells keys in snake_case, so it's item_transform",
            "false\tmodules/game/init.lua:9: node \"base\" is declared by centity.json; only nodes a script added can be removed",
        ],
        "{start:?}"
    );
    // The added nodes' entities are made at the next sync.
    session.tick();
    let calls = platform.take();
    assert!(calls.iter().any(|c| c.contains(" arm block")), "{calls:?}");
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("hitbox") && c.contains("hand")),
        "{calls:?}"
    );
    for _ in 0..6 {
        session.tick();
    }
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        ["tween\t1.5\ttrue", "tween\t3\tfalse", "true\t2\tnil"],
        "{calls:?}"
    );
}

#[test]
fn a_rope_is_laid_between_its_ends_each_tick() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r#"{ "nodes": {
  "line": { "display": { "type": "block", "block": "minecraft:chain" }, "rope": { "links": 4, "linkLength": 1 } }
} }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/line/centity.json", centity),
        (
            "modules/game/init.lua",
            r#"
local line = nf.centities.spawn("line", vector.create(0, 64, 0))
local rope = line:node("line")
print(rope:is_rope(), line:node("base") == nil, #line:nodes())
local link = line:node("line[1]")
print(pcall(link.rope_between, link, vector.create(0, 64, 0), vector.create(1, 64, 0)))
rope:rope_between(vector.create(0, 65, 0), vector.create(2, 65, 0))
nf.after(2, function()
    local shown = 0
    for _, node in line:nodes() do
        if node:name() ~= "line" and node:is_visible() then shown += 1 end
    end
    print("shown", shown, string.format("%.2f", link:world_position().x))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t5"),
        "{start:?}"
    );
    assert!(
        lines.get(1).is_some_and(|l| l.contains("isn't a rope")),
        "{start:?}"
    );
    for _ in 0..3 {
        session.tick();
    }
    // Taut over two blocks: two of its four links, the first halfway along its block.
    assert_eq!(printed(&platform.take()), ["shown\t2\t0.50"]);
}

#[test]
fn temporary_centities_go_with_their_scope_or_their_lifetime_and_a_private_one_is_its_viewers_alone()
 {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
        (
            "modules/game/init.lua",
            r#"
local timed = nf.centities.spawn("lamp", vector.create(0, 64, 0), { lifetime = 3 })
timed:on("remove", function() print("timed gone") end)
local kept = nf.centities.spawn("lamp", vector.create(5, 64, 0), { temporary = true })
print(kept:is_temporary(), kept:keep(), kept:is_temporary(), timed:lifetime())
local scoped = nf.centities.spawn("lamp", vector.create(9, 64, 0), { temporary = true })
-- Private, with no viewers yet: its client entities are sent to nobody.
local mine = nf.centities.spawn("lamp", vector.create(0, 70, 0), { temporary = true, viewers = {} })
print(#mine:viewers())
print(pcall(nf.centities.spawn, "lamp", vector.create(0, 0, 0), { viewers = {} }))
print(pcall(nf.centities.spawn, "lamp", vector.create(0, 0, 0), { lifetime = 0 }))
"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\tfalse\t3"),
        "{start:?}"
    );
    assert_eq!(lines.get(1).map(String::as_str), Some("0"), "{start:?}");
    assert!(
        lines
            .get(2)
            .is_some_and(|l| l.contains("only a temporary centity can be private")),
        "{start:?}"
    );
    assert!(
        lines
            .get(3)
            .is_some_and(|l| l.contains("at least 1 expected, got 0")),
        "{start:?}"
    );
    assert!(
        start
            .iter()
            .any(|c| c.starts_with("viewers ") && c.ends_with(' ')),
        "{start:?}"
    );
    // A temporary one's entities aren't saved; keeping one makes them so again.
    assert!(
        start.contains(&"persistent 1 false".to_owned()),
        "{start:?}"
    );
    assert!(start.contains(&"persistent 4 true".to_owned()), "{start:?}");
    for _ in 0..3 {
        session.tick();
    }
    assert_eq!(printed(&platform.take()), ["timed gone"]);
    assert_eq!(session.centities().all().count(), 3);
    // The module reloads: what it spawned temporary goes with its scope; the kept one stays.
    files.write("modules/game/init.lua", "");
    session.reload(&["modules/game/init.lua".into()]);
    platform.take();
    assert_eq!(session.centities().all().count(), 1);
}

#[test]
fn a_centity_takes_buffs_and_damage_and_dies() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = lamp(1.0);
    let end = centity.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "health": {{ "max": 10, "bar": {{ "segments": 5 }} }} }}"#,
        centity[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/dummy/centity.json", &centity),
        (
            "buffs/frail.json",
            r#"{ "stats": { "defense": -4 }, "blockedBy": ["ironskin"], "stacking": "add" }"#,
        ),
        (
            "buffs/ironskin.json",
            r#"{ "stats": { "defense": 6 }, "cures": ["frail"] }"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local d = nf.centities.spawn("dummy", vector.create(0, 64, 0))
d:on("buff_end", function(e) print("ended", e.buff, e.reason) end)
d:on("damage", function(e) e.amount = e.amount / 2 end)
d:on("death", function(e) print("died", e.cause) end)
d:on("remove", function() print("removed") end)
print(d:add_buff("frail", 5), d:stat("defense"), d:buff_ticks("frail"))
print(d:add_buff("ironskin", 2), d:stat("defense"), d:has_buff("frail"), d:add_buff("frail", 5))
print(pcall(d.add_buff, d, "slow", 5))
d:set_modifiers("gear", { defense = 1 })
print(d:stat("defense"), d:node("health_bar") ~= nil, d:node("health_bar"):display_text())
print(d:damage(4), d:health(), d:node("health_bar"):is_visible())
nf.after(4, function()
    print(#d:buffs(), d:damage(100, { cause = "fall" }), d:exists())
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "true\t-4\t5",
            "ended\tfrail\tcured",
            "true\t6\tfalse\tfalse",
            "false\tmodules/game/init.lua:9: there's no buff \"slow\" (buffs/slow.json)",
            "7\ttrue\t<red>▮▮▮▮▮<dark_gray>",
            "2\t8\ttrue",
        ],
        "{start:?}"
    );
    for _ in 0..5 {
        session.tick();
    }
    let lines = printed(&platform.take());
    assert_eq!(
        lines,
        [
            "ended\tironskin\texpired",
            "died\tfall",
            "removed",
            "0\t8\tfalse"
        ],
        "{lines:?}"
    );
}

#[test]
fn a_body_falls_lands_walks_into_a_wall_and_jumps() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = lamp(1.0);
    let end = centity.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "body": {{ "width": 0.6, "height": 1.8 }} }}"#,
        centity[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/walker/centity.json", &centity),
        (
            "modules/game/init.lua",
            r#"
local w = nf.centities.spawn("walker", vector.create(8.5, 66, 0.5))
w:on("land", function(e) print("landed", e.speed > 0, w:position().y, w:is_on_ground()) end)
w:on("bump", function(e) print("bump", e.normal.x) end)
print(w:has_body(), w:body().height, w:is_on_ground())
nf.after(30, function()
    w:set_walk_velocity(vector.create(4, 0, 0))
end)
nf.after(50, function()
    print("at wall", string.format("%.1f", w:position().x), w:wall() and w:wall().x)
    w:set_walk_velocity(nil)
    print(w:jump(1.25), w:is_on_ground(), w:velocity().y > 0)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["true\t1.8\tfalse"], "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..52 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines[0], "landed\ttrue\t64\ttrue", "{lines:?}");
    assert!(lines.iter().any(|l| l == "bump\t-1"), "{lines:?}");
    // It stops with its side against the wall's face, at x 10.
    assert!(lines.iter().any(|l| l == "at wall\t9.7\t-1"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "true\tfalse\ttrue"), "{lines:?}");
}

#[test]
fn centities_hear_what_their_hitboxes_start_touching() {
    let platform = std::rc::Rc::new(Fake::default());
    let block = r#"{ "nodes": { "cube": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/cube/centity.json", block),
        (
            "modules/game/init.lua",
            r#"
local a = nf.centities.spawn("cube", vector.create(0, 64, 0))
local b = nf.centities.spawn("cube", vector.create(0.5, 64, 0))
a:on("touch", function(e) print("touch", e.node:name(), e.other == b, e.other_node:name(), e.entity) end)
nf.after(3, function() b:teleport(vector.create(5, 64, 0)) end)
nf.after(5, function() b:teleport(vector.create(1, 64, 0)) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    platform.take();
    let mut lines = Vec::new();
    for _ in 0..7 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    // Once as they start touching, not again while they do; again after they part and meet (faces touching).
    assert_eq!(
        lines,
        [
            "touch\tcube\ttrue\tcube\tnil",
            "touch\tcube\ttrue\tcube\tnil"
        ],
        "{lines:?}"
    );
}

#[test]
fn a_launched_centity_hits_what_it_meets_and_ends_on_a_block() {
    let platform = std::rc::Rc::new(Fake::default());
    let cube = r#"{ "nodes": { "cube": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/cube/centity.json", cube),
        (
            "modules/game/init.lua",
            r#"
local target = nf.centities.spawn("cube", vector.create(5, 64, 0))
local shot = nf.centities.spawn("cube", vector.create(0, 64.5, 0.5))
shot:on("hit", function(e) print("hit", e.target == target, e.node:name()) end)
shot:on("flight_end", function(e) print("end", e.reason, e.target == target) end)
print(shot:launch({ velocity = vector.create(20, 0, 0) }), shot:is_launched(), shot:velocity().x)
local ball = nf.centities.spawn("cube", vector.create(-5, 66, 0.5))
ball:on("flight_end", function(e) print("ball", e.reason, e.normal and e.normal.y, e.block ~= nil) end)
ball:launch({ velocity = vector.create(0, -10, 0), radius = 0 })
print(pcall(ball.launch, ball, { velocity = vector.create(1, 0, 0), bounce = 2 }))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t20"),
        "{start:?}"
    );
    assert!(
        lines
            .get(1)
            .is_some_and(|l| l.contains("0 to 1 expected, got 2")),
        "{start:?}"
    );
    let mut lines = Vec::new();
    for _ in 0..20 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert!(lines.contains(&"hit\ttrue\tcube".to_owned()), "{lines:?}");
    assert!(lines.contains(&"end\thit\ttrue".to_owned()), "{lines:?}");
    assert!(
        lines.contains(&"ball\tblock\t1\ttrue".to_owned()),
        "{lines:?}"
    );
    // Both flights removed what flew; the target stays.
    assert_eq!(session.centities().all().count(), 1);
}

#[test]
fn a_centity_walks_to_a_place() {
    let platform = std::rc::Rc::new(Fake::default());
    let walker = r#"{ "nodes": { "body": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": { "boxes": [{ "min": [-0.3, 0, -0.3], "max": [0.3, 1.8, 0.3] }] } } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/walker/centity.json", walker),
        (
            "modules/game/init.lua",
            r#"
local w = nf.centities.spawn("walker", vector.create(8.5, 64, 0.5))
w:on("path_end", function(e) print("arrived", e.reached, math.floor(w:position().x)) end)
print(w:move_to(vector.create(2.5, 64, 0.5)), w:has_path(), w:path_target().x)
print(w:move_to(vector.create(12.5, 64, 0.5)), w:has_path())
print(w:move_to(vector.create(2.5, 64, 0.5), { speed = 8 }))
print(pcall(w.move_to, w, vector.create(0, 64, 0), { speed = 50 }))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t2.5"),
        "{start:?}"
    );
    // The wall at x 10 is endless: a partial path, as close as it gets (Kotlin walks it too).
    assert_eq!(
        lines.get(1).map(String::as_str),
        Some("true\ttrue"),
        "{start:?}"
    );
    assert!(lines.get(3).is_some_and(|l| l.contains("options.speed must be more than 0 and at most 20, not 50")), "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..40 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["arrived\ttrue\t2"], "{lines:?}");
}

#[test]
fn a_physics_body_falls_lands_settles_and_is_pushed() {
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        (
            "modules/game/init.lua",
            r#"
local c = nf.centities.spawn("crate", vector.create(0.5, 67, 0.5))
local b = c:node("box")
b:on("collide", function(e) print("collide", e.other, e.hit_normal.y > 0.5, e.speed > 1) end)
b:on("sleep", function() print("sleep", string.format("%.2f", b:world_position().y), b:is_on_ground()) end)
b:on("wake", function() print("wake") end)
print(b:mass(), b:has_gravity(), b:is_physics_enabled())
nf.after(120, function()
    print(b:apply_impulse(vector.create(0, 5, 0)), b:velocity().y > 0)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["1\ttrue\ttrue"], "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..130 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert!(
        lines
            .first()
            .is_some_and(|l| l == "collide\tnil\ttrue\ttrue"),
        "{lines:?}"
    );
    // It comes to rest on the floor at y 64 (its unit cube's bottom is its origin).
    assert!(lines.iter().any(|l| l == "sleep\t64.00\ttrue"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "true\ttrue"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "wake"), "{lines:?}");
}

#[test]
fn a_body_lands_on_another_centitys_hitbox_and_on_another_centitys_body() {
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let table = r#"{ "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        ("centities/table/centity.json", table),
        (
            "modules/game/init.lua",
            r#"
nf.centities.spawn("table", vector.create(0, 64, 0))
local a = nf.centities.spawn("crate", vector.create(0, 66, 0))
local b = nf.centities.spawn("crate", vector.create(5, 64, 0))
local c = nf.centities.spawn("crate", vector.create(5, 67, 0))
nf.after(100, function()
    print(string.format("%.2f %.2f %.2f",
        a:node("box"):world_position().y, b:node("box"):world_position().y, c:node("box"):world_position().y))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    platform.take();
    let mut lines = Vec::new();
    for _ in 0..101 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    // On the table's hitbox; on the floor; on the other crate.
    assert_eq!(lines, ["65.00 64.00 65.00"], "{lines:?}");
}

#[test]
fn an_instance_and_its_data_outlive_the_session_and_its_centity_going_away() {
    let platform = std::rc::Rc::new(Fake::default());
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let centity = scripted_lamp();
    let script = r#"
this:on("spawn", function() print("spawn") end)
local data = this:data()
data.visits = (data.visits or 0) + 1
print("visits", data.visits)
"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        ("centities/lamp/script.lua", script),
        ("modules/game/init.lua", ""),
    ]);
    {
        let session = Session::start_with(platform.clone(), &files, &NoPackages, store.clone());
        session
            .spawn("lamp", place(3.0, 64.0, 3.0), 90.0)
            .expect("spawned");
        session.tick();
        let calls = platform.take();
        assert_eq!(printed(&calls), ["visits\t1", "spawn"], "{calls:?}");
    }
    // The session stopped: its instance stays in the world, its entities with it.
    let calls = platform.take();
    assert!(!calls.iter().any(|c| c.starts_with("remove")), "{calls:?}");
    assert_eq!(store.instances().len(), 1);
    let id = store.instances()[0].id;
    {
        let mut session = Session::start_with(platform.clone(), &files, &NoPackages, store.clone());
        let calls = platform.take();
        // Restored, not spawned: no new entities, no `spawn`, the data as it was saved.
        assert_eq!(printed(&calls), ["visits\t2"], "{calls:?}");
        session.tick();
        let calls = platform.take();
        assert!(
            !calls
                .iter()
                .any(|c| c.starts_with("anchor") || c.starts_with("display")),
            "{calls:?}"
        );
        assert_eq!(session.centities().get(id).map(|i| i.yaw()), Some(90.0));
        // Its centity goes: it's inert, kept; back, it's an instance again.
        files.delete("centities/lamp/centity.json");
        session.reload(&["centities/lamp/centity.json".into()]);
        assert!(session.centities().get(id).is_none());
        assert_eq!(session.centities().inert().count(), 1);
        session.tick();
        assert_eq!(store.instances().len(), 1);
        files.add("centities/lamp/centity.json", &centity);
        session.reload(&["centities/lamp/centity.json".into()]);
        session.tick();
        assert!(session.centities().get(id).is_some());
        assert_eq!(printed(&platform.take()), ["visits\t3"]);
    }
}

#[test]
fn a_script_draws_a_picture_on_an_image_display() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity =
        r#"{ "nodes": { "screen": { "display": { "type": "image", "width": 2, "height": 2 } } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/screen/centity.json", centity),
        (
            "modules/game/init.lua",
            r##"
local s = nf.centities.spawn("screen", vector.create(0, 64, 0)):node("screen")
local red = nf.images.fill(2, 2, "#ff0000")
print(red:width(), red:pixel(1, 1), red:pixel(5, 5), s:image())
print(s:set_image(red), s:image():pixel(0, 0))
local copy = nf.images.decode(red:encode())
print(copy:height(), copy:pixel(0, 1), red:cropped(1, 0, 1, 2):width())
print(pcall(nf.images.fill, 0, 2, "#ff0000"))
"##,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "2\t#FF0000\tnil\tnil",
            "true\t#FF0000",
            "2\t#FF0000\t1",
            "false\tmodules/game/init.lua:8: an image is 1 to 2048 pixels each way, not 0 by 2",
        ],
        "{start:?}"
    );
    session.tick();
    let calls = platform.take();
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("update ") && !c.ends_with("runs 0")),
        "{calls:?}"
    );
}

#[test]
fn an_instance_hears_its_chunk_unload_and_load_and_strays_go() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &scripted_lamp()),
        (
            "centities/lamp/script.lua",
            r#"
this:on("chunk_unload", function() print("unload") end)
this:on("chunk_load", function() print("load") end)
local ticks = 0
this:on("tick", function() ticks += 1 end)
nf.every(1, function() if ticks == 5 then print("five") end end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    session
        .spawn("lamp", place(0.0, 64.0, 0.0), 0.0)
        .expect("spawned");
    platform.take();
    session.tick();
    platform.unloaded.set(true);
    for _ in 0..10 {
        session.tick();
    }
    platform.unloaded.set(false);
    for _ in 0..5 {
        session.tick();
    }
    // One tick, ten without (unloaded), then five more: its tick handler ran six times.
    let lines = printed(&platform.take());
    assert_eq!(lines, ["unload", "load", "five"], "{lines:?}");
    // A tagged entity no instance knows is a stray.
    let stray = netherforge_platform::EntityTag {
        instance: Uuid(12345),
        node: "x".into(),
        role: EntityRole::Display,
    };
    session.entities_loaded(&std::collections::HashMap::from([(Uuid(999), stray)]), &[]);
    assert!(platform.take().contains(&"remove 999".to_owned()));
}

#[test]
fn centities_appear_near_a_player_by_their_rules_and_go_when_nobody_is_near() {
    let platform = std::rc::Rc::new(Fake::default());
    let wisp = r#"{ "nodes": { "glow": { "display": { "type": "block", "block": "minecraft:glowstone" } } },
  "spawning": { "cap": 2, "light": { "min": 10 }, "blocks": ["minecraft:stone"], "when": "calm" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/wisp/centity.json", wisp),
        (
            "modules/game/init.lua",
            r#"
local calm = false
nf.spawning.condition("calm", function(check) return calm and check.centity == "wisp" end)
nf.after(20, function() calm = true end)
nf.on("centity_natural_spawn", function(e) print("appears", e.centity, e.location.position.y) end)
print(pcall(nf.spawning.condition, "calm", function() end))
"#,
        ),
    ]);
    *platform.player.borrow_mut() = Some(place(0.5, 64.0, 0.5));
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = printed(&platform.take());
    assert!(
        start
            .first()
            .is_some_and(|l| l.contains("already registered")),
        "{start:?}"
    );
    // Not calm yet: nothing appears (calm comes at tick 20's start, before its pass).
    for _ in 0..19 {
        session.tick();
    }
    let early = platform.take();
    assert_eq!(session.centities().all().count(), 0, "{early:?}");
    for _ in 0..61 {
        session.tick();
    }
    let lines = printed(&platform.take());
    let count = session.centities().all().count();
    assert!((1..=2).contains(&count), "{count} {lines:?}");
    assert!(lines.iter().all(|l| l == "appears\twisp\t64"), "{lines:?}");
    for instance in session.centities().all() {
        let a = instance.anchor();
        let distance = (a.x * a.x + a.z * a.z).sqrt();
        assert!(
            instance.natural && a.y == 64.0 && (23.0..=49.0).contains(&distance),
            "{a:?}"
        );
    }
    // The player goes far away: at the next sweep these go (others may appear where they are now).
    let old: Vec<Uuid> = session.centities().all().map(|i| i.id).collect();
    *platform.player.borrow_mut() = Some(place(5000.5, 64.0, 0.5));
    for _ in 0..20 {
        session.tick();
    }
    assert!(old.iter().all(|id| session.centities().get(*id).is_none()));
    assert!(session.centities().all().all(|i| i.anchor().x > 4000.0));
}

fn alex() -> netherforge_platform::PlayerRef {
    netherforge_platform::PlayerRef {
        uuid: Uuid(0x0f1b_0c7e_6a43_3d1f_9a8c_2b5e_8f1a_0c11),
        name: "Alex".into(),
    }
}

fn stack(item: &str) -> netherforge_platform::ItemData {
    netherforge_platform::ItemData {
        def: netherforge_format::item::ItemDef {
            kind: item.into(),
            ..Default::default()
        },
        raw: None,
        look: None,
    }
}

#[test]
fn the_servers_own_events_reach_scripts_and_their_answers_go_back() {
    use netherforge_platform::{BlockRef, PlatformEvents};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/rules/init.lua",
            r#"
nf.on("player_interact", function(e) print("interact", e.click, e.block ~= nil, e.face, e.hand); e:cancel() end)
nf.on("block_break", function(e)
    print("break", e.state, #e.drops, e.drops[1].kind, e.experience)
    e.drops = {}
    e.experience = 7
end)
nf.on("entity_damage", function(e) print("damage", e.amount, e.cause, e.attacker ~= nil); e.amount = e.amount * 2 end)
nf.on("player_death", function(e) print("death", e.cause, e.message); e.message = nil; e.keep_inventory = true end)
nf.on("entity_death", function(e) print("entity death", #e.drops, e.experience) end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    platform.take();
    let block = BlockRef {
        world: "world".into(),
        x: 3,
        y: 63,
        z: 4,
        id: "minecraft:dirt".into(),
        state: "minecraft:dirt".into(),
    };
    assert!(server.player_interact(
        alex(),
        ClickButton::Right,
        Some(block.clone()),
        Some("up".into()),
        None,
        "main_hand".into()
    ));
    let answer = server
        .block_break(
            alex(),
            block.clone(),
            Box::new(|| vec![stack("minecraft:dirt")]),
            0,
        )
        .expect("not cancelled");
    assert_eq!(answer.drops, Some(vec![]));
    assert_eq!(answer.experience, 7);
    assert_eq!(
        server.entity_damaged(Uuid(5), 3.0, "entity_attack".into(), Some(alex().uuid)),
        Some(6.0)
    );
    // Keeping the inventory drops nothing from it; the message is gone.
    let death = server.player_died(
        alex(),
        None,
        "fall".into(),
        Some("Alex fell".into()),
        false,
        vec![stack("minecraft:dirt")],
        10,
    );
    assert_eq!(death.message, None);
    assert!(death.keep_inventory);
    assert_eq!(death.drops, Some(vec![]));
    assert_eq!(death.experience, 10);
    // What handlers printed is logged with the next tick.
    server.tick();
    let lines = printed(&platform.take());
    assert_eq!(
        lines,
        [
            "interact\tright\ttrue\tup\tmain_hand",
            "break\tminecraft:dirt\t1\tminecraft:dirt\t0",
            "damage\t3\tentity_attack\ttrue",
            "death\tfall\tAlex fell",
            "entity death\t0\t10",
        ],
        "{lines:?}"
    );
    // Nothing listens to dropping an item: it goes through, and nothing was built for it.
    assert!(!server.player_drop_item(alex(), stack("minecraft:dirt")));
    // Once the session stops, every answer is "unchanged".
    server.session_mut().stop();
    assert!(!server.player_interact(
        alex(),
        ClickButton::Left,
        None,
        None,
        None,
        "main_hand".into()
    ));
    assert_eq!(
        server.entity_damaged(Uuid(5), 3.0, "fall".into(), None),
        Some(3.0)
    );
}

#[test]
fn a_body_resting_on_a_block_falls_when_the_block_is_broken() {
    use netherforge_platform::{BlockRef, PlatformEvents};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        (
            "modules/game/init.lua",
            r#"
local b = nf.centities.spawn("crate", vector.create(0.5, 65, 0.5)):node("box")
b:on("sleep", function() print("sleep", string.format("%.2f", b:world_position().y)) end)
b:on("wake", function() print("wake") end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let mut lines = Vec::new();
    for _ in 0..120 {
        server.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["sleep\t64.00"], "{lines:?}");
    // The floor under it goes, wider than the crate.
    for x in -1..=1 {
        for z in -1..=1 {
            let block = BlockRef {
                world: "world".into(),
                x,
                y: 63,
                z,
                id: "minecraft:stone".into(),
                state: "minecraft:stone".into(),
            };
            server.block_break(alex(), block, Box::new(Vec::new), 0);
            platform.broken.borrow_mut().insert((x, 63, z));
        }
    }
    // It wakes at once, not when the blocks would next have been read again.
    server.tick();
    server.tick();
    assert_eq!(printed(&platform.take()), ["wake"]);
    let mut lines = Vec::new();
    for _ in 0..120 {
        server.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["sleep\t63.00"], "{lines:?}");
}

#[test]
fn a_world_answers_from_the_server_and_its_centities() {
    let platform = std::rc::Rc::new(Fake::default());
    let table = r#"{ "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/table/centity.json", table),
        (
            "modules/look/init.lua",
            r#"
local w = nf.worlds.default()
print(w:name(), w:exists(), w:time_of_day(), w:is_day(), w:is_night(), w:day_count(), w:moon_phase(), w:weather())
print(w:min_height(), w:max_height(), w:block(vector.create(1.5, 63.2, -0.5)):world():name(), w:border() ~= nil)
local box = w:blocks(vector.create(9, 63, 0), vector.create(10, 64, 0), { what = "class" })
print(box.size_x, box.size_y, box.size_z, table.concat(box.cells, ","))
local f = w:flood(vector.create(0, 64, 0), { through = { "minecraft:air" }, bounds = { min = vector.create(0, 64, 0), max = vector.create(12, 64, 0) }, kinds = true })
print(#f.positions, #f.edges, f.edge_kinds[1], f.stopped)
local down = w:raycast(vector.create(0.5, 70, 0.5), vector.create(0, -1, 0), 20)
print(down.block ~= nil, string.format("%.1f", down.distance), down.normal.y)
local t = nf.centities.spawn("table", vector.create(3.5, 64, 0.5))
local hit = w:raycast(vector.create(0.5, 64.5, 0.5), vector.create(1, 0, 0), 20)
print(hit.centity == t, hit.node:name(), string.format("%.2f", hit.distance))
local near = w:overlap_sphere(vector.create(3.5, 64.5, 0.5), 2, { entities = false })
print(#near, near[1].centity == t, w:nearest(vector.create(3.5, 64.5, 0.5), 2).node:name())
print(#w:centities(), #w:centities({ kind = "nope" }), #w:entities({ kind = "zombie", tag = "guard" }), #w:entities({ living = false }))
print(w:game_rule("keep_inventory"), pcall(w.game_rule, w, "nope"))
print(#nf.worlds.all(), nf.worlds.get("nether"))
w:on("dusk", function() end)
local b = w:block(vector.create(2, 63, 2))
print(b:kind(), b:is_solid(), b:relative("up"):is_air(), b:relative(vector.create(0, 1, 0)) == b:relative("up"), b:position().y)
print(b:set_state("stone", { update = false }), pcall(b.set_state, b, "minecraft:stone[facing=up]"))
print(w:fill_blocks(vector.create(0, 319, 0), vector.create(1, 321, 0), "glass"), pcall(w.fill_blocks, w, vector.create(0, 0, 0), vector.create(99, 99, 99), "glass"))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let all = platform.take();
    let lines = printed(&all);
    assert!(!all.iter().any(|l| l.starts_with("warn")), "{all:?}");
    assert_eq!(
        lines,
        [
            "world\ttrue\t6000\ttrue\tfalse\t0\tfull_moon\tclear",
            "-64\t320\tworld\ttrue",
            "2\t2\t1\t2,2,0,2",
            "10\t1\tminecraft:stone\tbounds",
            "true\t6.0\t1",
            "true\ttop\t3.00",
            "1\ttrue\ttop",
            "1\t0\t1\t0",
            "true\tfalse\tmodules/look/init.lua:17: no game rule \"nope\" on this server",
            "1\tnil",
            "minecraft:stone\ttrue\ttrue\ttrue\t63",
            "true\tfalse\tmodules/look/init.lua:22: state: minecraft:stone has no property \"facing\" (it has: none)",
            "2\tfalse\tmodules/look/init.lua:23: that box is 1000000 blocks, and one fill_blocks changes at most 32768: spread it over several ticks",
        ],
        "{lines:?}"
    );
    assert!(
        all.contains(&"set 2 63 2 minecraft:stone false".to_owned()),
        "{all:?}"
    );
}

#[test]
fn entities_answer_from_the_server_and_keep_what_it_forgets() {
    use netherforge_platform::{PlatformEvents, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/zoo/init.lua",
            r#"
local z = nf.worlds.default():entities({ kind = "zombie" })[1]
print(z:kind(), z:is_mob(), z:is_living(), z:is_player(), z:tags()[1], z:has_tag("guard"), z:position().x)
print(pcall(z.add_tag, z, "no spaces"))
print(z:health(), z:max_health(), z:set_health(5), pcall(z.set_health, z, 50))
print(z:set_custom_name("<red>Bob"), z:data().kills)
z:data().kills = 3
local alex = nf.worlds.default():players()[1]
print(z:hide_from(alex), z:is_hidden_from(alex))
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "minecraft:zombie\ttrue\ttrue\tfalse\tguard\ttrue\t5",
            "false\tmodules/zoo/init.lua:4: \"no spaces\" isn't a tag: letters, digits, _, -, . and +",
            "20\t20\ttrue\tfalse\tmodules/zoo/init.lua:5: health must be from 0 to max_health() (20), not 50",
            "true\t2",
            "true\ttrue",
        ]
    );
    assert!(
        calls.contains(&"number 500 HEALTH 5".to_owned()),
        "{calls:?}"
    );
    assert!(calls.contains(&"hide 500 from 77".to_owned()), "{calls:?}");
    // A save writes the changed table back; the next save, unchanged, doesn't.
    server.world_saving("world".into());
    assert!(
        platform
            .take()
            .contains(&r#"data 500 Some("{\"kills\":3}")"#.to_owned())
    );
    server.world_saving("world".into());
    assert!(!platform.take().iter().any(|c| c.starts_with("data")));
    // The server forgot who it was hidden from when Alex left: it's hidden again as they join.
    let mut join = game_event::PlayerJoin {
        player: netherforge_platform::PlayerRef {
            uuid: Uuid(77),
            name: "Alex".into(),
        },
        first_join: false,
        message: None,
    };
    server.game().player_join(&mut join);
    assert!(platform.take().contains(&"hide 500 from 77".to_owned()));
    // The session ends: it shows again.
    server.session_mut().stop();
    assert!(platform.take().contains(&"show 500 to 77".to_owned()));
}

#[test]
fn a_player_answers_while_online_and_takes_buffs() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("buffs/swift.json", r#"{ "stats": { "speed": 2 } }"#),
        (
            "modules/greet/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
print(alex:name(), alex:is_player(), alex:game_mode(), alex:send_message("<green>hi"))
print(alex:send_title("Welcome", nil, { stay = 40 }), pcall(alex.send_title, alex, "x", nil, { stay = -1 }))
print(pcall(alex.set_food, alex, 30))
print(alex:storage("bank", 27) ~= nil, pcall(alex.storage, alex, "Bank!", 27))
alex:on("buff_add", function(e) print("buff", e.buff, e.ticks, e.player == alex); e.ticks = 5 end)
print(alex:add_buff("swift", 100), alex:buff_ticks("swift"), alex:stat("speed"), #alex:buffs())
alex:on("buff_end", function(e) print("ended", e.buff, e.reason) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "Alex\ttrue\tsurvival\ttrue",
            "true\tfalse\tmodules/greet/init.lua:4: options.stay can't be negative",
            "false\tmodules/greet/init.lua:5: food must be from 0 to 20, not 30",
            "true\tfalse\tmodules/greet/init.lua:6: \"Bank!\" isn't a storage name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)",
            "buff\tswift\t100\ttrue",
            "true\t5\t2\t1",
        ]
    );
    assert!(
        calls.contains(&"message 77 <green>hi".to_owned()),
        "{calls:?}"
    );
    assert!(
        calls.contains(&"title 77 Welcome/ 10 40 20".to_owned()),
        "{calls:?}"
    );
    let mut lines = Vec::new();
    for _ in 0..6 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["ended\tswift\texpired"]);
}

#[test]
fn saved_tables_are_saved_when_they_change_and_carried_into_a_restart() {
    use netherforge_platform::{PlatformEvents, game_event};
    use netherforge_runtime::data::Owner;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/scores/init.lua",
            r#"
local s = nf.data("scores")
print(s.top, pcall(nf.data, "Bad Name"))
s.top = (s.top or 0) + 1
if s.spot then print("spot", s.home == nf.worlds.default(), s.spot.world:name(), s.spot.position.y) end
s.home = nf.worlds.default()
s.spot = nf.worlds.default():location(vector.create(1, 2, 3))
s.timer = nf.after(1000, function() end)
nf.worlds.default():players()[1]:data().coins = 3
local alex = nf.worlds.default():players()[1]
alex:session().streak = 4
nf.on("player_quit", function(e) print("quit", e.player:session().streak) end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start_with(
        platform.clone(),
        &files,
        &NoPackages,
        store.clone(),
    ));
    let first = printed(&platform.take());
    assert_eq!(
        first,
        [
            "nil\tfalse\tmodules/scores/init.lua:3: \"Bad Name\" isn't a data table name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)"
        ]
    );
    // Their table is saved as they leave.
    let alex = netherforge_platform::PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    server.game().player_quit(&mut game_event::PlayerQuit {
        player: alex,
        message: None,
    });
    store.commit();
    assert_eq!(
        store.data(&Owner::Player(Uuid(77))).as_deref(),
        Some(r#"{"coins":3}"#)
    );
    // A restart (netherforge.json changed) starts from the table as the last session left it.
    server
        .session_mut()
        .reload(&["netherforge.json".to_owned()]);
    let raw = platform.take();
    assert!(
        raw.contains(
            &"warn Saving nf.data(\"scores\")'s data: data.timer: a Task handle can't be saved"
                .to_owned()
        ),
        "{raw:?}"
    );
    let restarted = printed(&raw);
    assert!(
        restarted.contains(&"spot\ttrue\tworld\t2".to_owned()),
        "{restarted:?}"
    );
    assert_eq!(
        restarted[0],
        "1\tfalse\tmodules/scores/init.lua:3: \"Bad Name\" isn't a data table name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)"
    );
    server.session_mut().stop();
    let named = Owner::Named {
        namespace: "demo".into(),
        name: "scores".into(),
    };
    let text = store.data(&named).expect("saved");
    assert!(
        text.contains(r#""home":{"$world":["world"]}"#) && text.contains(r#""top":2"#),
        "{text}"
    );
}

#[test]
fn permissions_scripts_set_are_kept_and_held_while_the_project_runs() {
    use netherforge_platform::{PlatformEvents, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let manifest = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3", "allow": { "permissions": ["shop"] } }"#;
    let files = Files::new(&[
        ("netherforge.json", manifest),
        (
            "modules/vip/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
alex:set_permission("shop.vip", true)
print(pcall(alex.set_permission, alex, "admin.ban", true))
print(pcall(alex.set_permission, alex, "Not A Node", true))
print(alex:permissions()["shop.vip"], alex:unset_permission("nope"))
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start_with(
        platform.clone(),
        &files,
        &NoPackages,
        store.clone(),
    ));
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        [
            "false\tmodules/vip/init.lua:4: Player:set_permission needs permission \"admin.ban\", which package \"demo\" hasn't allowed: add it (or a node above it) to allow.permissions in its netherforge.json (it lists: shop)",
            "false\tmodules/vip/init.lua:5: \"Not A Node\" isn't a permission node (lowercase letters, digits, _ and -, in parts separated by dots (like shop.vip), at most 255 characters)",
            "true\tfalse",
        ]
    );
    assert!(
        calls.contains(&"permissions 77 [shop.vip=true]".to_owned()),
        "{calls:?}"
    );
    // They come back: the server forgot, and it's applied again.
    let alex = netherforge_platform::PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    server.game().player_join(&mut game_event::PlayerJoin {
        player: alex,
        first_join: false,
        message: None,
    });
    assert!(
        platform
            .take()
            .contains(&"permissions 77 [shop.vip=true]".to_owned())
    );
    // The project stops: everyone loses what it set; the store keeps it for the next run.
    server.session_mut().stop();
    assert!(platform.take().contains(&"permissions 77 []".to_owned()));
    store.commit();
    assert_eq!(
        store
            .permissions_of("demo")
            .get(&Uuid(77))
            .and_then(|n| n.get("shop.vip")),
        Some(&true)
    );
}

#[test]
fn schedules_run_at_their_times_and_catch_up_what_they_missed() {
    // 2026-03-28 23:00 UTC is 23:00 in London, the night before the clocks go forward at 01:00.
    let start = 1_774_738_800_000i64;
    let platform = std::rc::Rc::new(Fake::default());
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/clock/init.lua",
            r#"
local nightly = nf.schedule.daily("1:30", function() print("nightly") end, { id = "nightly", catch_up = true })
print(nightly:is_active(), nightly:next_run())
print(pcall(nf.schedule.daily, "25:00", function() end))
print(pcall(nf.schedule.weekly, "someday", "10:00", function() end))
print(pcall(nf.schedule.cron, "* * *", function() end) == false)
print(pcall(nf.schedule.daily, "1:00", function() end, { catch_up = true }))
local cron = nf.schedule.cron("0 */6 * * *", function() print("cron") end)
print(cron:next_run())
"#,
        ),
    ]);
    let config = netherforge_runtime::session::Config {
        time_zone: Some("Europe/London".into()),
        wall_clock: Some(start),
    };
    let mut session =
        Session::start_configured(platform.clone(), &files, &NoPackages, store.clone(), config);
    let lines = printed(&platform.take());
    // 01:30 is in the gap the clocks skip that night: it's 02:30 BST, 01:30 UTC.
    let half_past_one_utc = start + 2 * 3_600_000 + 1_800_000;
    assert_eq!(
        lines,
        [
            format!("true\t{half_past_one_utc}"),
            "false\tmodules/clock/init.lua:4: bad argument 'time' (\"25:00\" isn't a time of day: \"HH:mm\" on a 24-hour clock, like \"18:00\")".to_owned(),
            "false\tmodules/clock/init.lua:5: bad argument 'day' (\"someday\" isn't a weekday: \"mon\", \"tue\", \"wed\", \"thu\", \"fri\", \"sat\" or \"sun\")".to_owned(),
            "true".to_owned(),
            "false\tmodules/clock/init.lua:7: options.catch_up needs an options.id: that's what the schedule's last run is kept under".to_owned(),
            // The next six-hourly time in London is 00:00 GMT, an hour on.
            format!("{}", start + 3_600_000),
        ]
    );
    // Its time comes: it runs once, however late the tick.
    session.set_wall_clock(Some(half_past_one_utc + 60_000));
    session.tick();
    let ran = printed(&platform.take());
    assert!(ran.contains(&"nightly".to_owned()), "{ran:?}");
    // The server is off over the next night's run: with catch_up it runs as its script starts.
    session.stop();
    session.set_wall_clock(Some(half_past_one_utc + 26 * 3_600_000));
    session.reload(&["netherforge.json".to_owned()]);
    platform.take();
    session.tick();
    assert!(printed(&platform.take()).contains(&"nightly".to_owned()));
}

#[test]
fn particles_and_sounds_are_checked_and_sent_to_who_is_near() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/fx/init.lua",
            r##"
local w = nf.worlds.default()
print(w:spawn_particle("dust", vector.create(1, 64, 1), { color = "#ff0000", size = 2, count = 3 }))
w:spawn_particle("flame", vector.create(500, 64, 0))
print(pcall(w.spawn_particle, w, "flame", vector.create(0, 0, 0), { color = "#ff0000" }))
print(pcall(w.spawn_particle, w, "dust", vector.create(0, 0, 0), { color = "red" }))
print(pcall(w.spawn_particle, w, "smoke", vector.create(0, 0, 0)))
print(w:play_sound("entity.pig.ambient", vector.create(0, 64, 0), { pitch = 1.5 }))
print(pcall(w.play_sound, w, "x", vector.create(0, 64, 0), { pitch = 3 }))
local alex = w:players()[1]
print(alex:play_sound("block.note_block.pling"), pcall(alex.spawn_particle, alex, "flame", vector.create(0, 0, 0), { viewers = {} }))
"##,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        [
            "true",
            "false\tmodules/fx/init.lua:5: particle \"minecraft:flame\" doesn't take options.color",
            "false\tmodules/fx/init.lua:6: options.color must be \"#rrggbb\", not \"red\"",
            "false\tmodules/fx/init.lua:7: no particle \"minecraft:smoke\" on this server",
            "true",
            "false\tmodules/fx/init.lua:9: options.pitch must be 0.5 to 2, not 3",
            "true\tfalse\tmodules/fx/init.lua:11: options.viewers: player:spawn_particle is only for that player (world:spawn_particle takes viewers)",
        ]
    );
    let sent: Vec<&String> = calls
        .iter()
        .filter(|c| c.starts_with("particle") || c.starts_with("sound"))
        .collect();
    assert_eq!(
        sent,
        [
            "particle world minecraft:dust x3 Some(Dust { color: 16711680, size: 2.0 }) to Alex",
            "sound world minecraft:entity.pig.ambient 1 1.5",
            "sound to 77 minecraft:block.note_block.pling",
        ]
    );
}

#[test]
fn a_world_spawns_entities_with_their_tags_and_data() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/farm/init.lua",
            r#"
local w = nf.worlds.default()
local pig = w:spawn_entity("pig", vector.create(3, 64, 0), { custom_name = "Wilbur", tags = { "farm" }, data = { home = w, age = 2 } })
print(pig ~= nil, pcall(w.spawn_entity, w, "dragon", vector.create(0, 64, 0)))
print(pcall(w.spawn_entity, w, "pig", vector.create(0, 64, 0), { tags = { "no spaces" } }))
print(pcall(w.spawn_entity, w, "pig", vector.create(0, 64, 0), { data = 5 }))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        [
            "true\tfalse\tmodules/farm/init.lua:4: \"dragon\" isn't an entity this server can spawn",
            "false\tmodules/farm/init.lua:5: \"no spaces\" isn't a tag: letters, digits, _, -, . and +",
            "false\tmodules/farm/init.lua:6: options.data must be a table of your own values, like { guard = true }",
        ]
    );
    assert!(
        calls.contains(&r#"spawn minecraft:pig at 3 Some("Wilbur") ["farm"] Some("{\"age\":2,\"home\":{\"$world\":[\"world\"]}}")"#.to_owned()),
        "{calls:?}"
    );
}

#[test]
fn a_worlds_sky_fades_a_step_at_a_time() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("skies/dusk.json", r##"{ "sky": "#203060", "fade": 3 }"##),
        ("skies/storm.json", r##"{ "sky": "#404040" }"##),
        (
            "modules/sky/init.lua",
            r#"
local w = nf.worlds.default()
print(w:set_sky("dusk", { fade_ticks = 6 }), w:sky())
print(pcall(w.set_sky, w, "storm"))
print(pcall(w.set_sky, w, "nope"))
nf.after(4, function() w:clear_sky({ fade_ticks = 6 }) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert!(!start.iter().any(|l| l.starts_with("warn")), "{start:?}");
    assert_eq!(
        printed(&start),
        [
            "true\tdusk",
            "false\tmodules/sky/init.lua:4: sky \"storm\" is new since the server started: a sky's colours are biomes, which the game learns as it starts, so restart it",
            "false\tmodules/sky/init.lua:5: there's no sky \"nope\" (skies/nope.json)",
        ]
    );
    let mut shown = Vec::new();
    for tick in 1..=14 {
        session.tick();
        shown.extend(
            platform
                .take()
                .into_iter()
                .filter(|c| c.starts_with("sky"))
                .map(|c| format!("{tick}: {c}")),
        );
    }
    // A player who hadn't seen the world is shown its sky at once; cleared over six ticks, its three steps go one
    // every two.
    assert_eq!(
        shown,
        [
            "1: sky 77 Some((\"demo:dusk\", 3))",
            "4: sky 77 Some((\"demo:dusk\", 2))",
            "6: sky 77 Some((\"demo:dusk\", 1))",
            "8: sky 77 None",
        ]
    );
}

#[test]
fn advancements_are_checked_against_the_server() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/goals/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
print(alex:grant_advancement("minecraft:story/mine_diamond", "diamond"))
print(pcall(alex.grant_advancement, alex, "minecraft:story/mine_diamond", "gold"))
print(pcall(alex.grant_advancement, alex, "minecraft:nope"))
print(pcall(alex.grant_advancement, alex, "explorer"))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        [
            "true",
            "false\tmodules/goals/init.lua:4: advancement \"minecraft:story/mine_diamond\" has no criterion \"gold\" (it has \"diamond\")",
            "false\tmodules/goals/init.lua:5: the server has no advancement \"minecraft:nope\"",
            "false\tmodules/goals/init.lua:6: the server has no advancement \"demo:explorer\"",
        ]
    );
    assert!(calls.contains(&"grant 77 minecraft:story/mine_diamond Some(\"diamond\")".to_owned()));
}

#[test]
fn the_small_namespaces_answer_as_the_prelude_did() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/util/init.lua",
            r#"
print(nf.math.lerp(0, 10, 0.25), nf.math.clamp(12, 0, 10), nf.math.remap(5, 0, 10, 100, 200), nf.math.ease("ease_in", 0.5))
print(pcall(nf.math.clamp, 1, 5, 2))
local text = nf.json.encode({ home = nf.worlds.default(), list = { 1, 2.5, "x" } })
print(text, nf.json.decode(text).home == nf.worlds.default(), nf.json.decode("not json"))
local a, b = nf.random.new(42), nf.random.new(42)
print(a:integer(1, 100) == b:integer(1, 100), a:number() == b:number(), a:pick({ "x" }), a:weighted({ only = 1, never = 0 }))
local list = { 1, 2, 3, 4, 5 }
a:shuffle(list)
print(#list, pcall(a.weighted, a, { bad = -1 }))
print(nf.time.duration(90061000), nf.time.duration(-5000), nf.time.parse_duration("1h 30m"), nf.time.parse_duration("250ms"), nf.time.parse_duration("1h 1h"))
print(nf.server.tick(), #nf.random.uuid())
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "2.5\t10\t150\t0.25",
            "false\tmodules/util/init.lua:3: bad argument 'min' (5 is greater than max, 2)",
            r#"{"home":{"$world":["world"]},"list":[1,2.5,"x"]}	true	nil"#,
            "true\ttrue\tx\tonly",
            "5\tfalse\tmodules/util/init.lua:10: bad argument 'weights' (the weight of bad must be a number from 0 up, not -1)",
            "1d 1h 1m 1s\t-5s\t5400000\t250\tnil",
            "0\t36",
        ]
    );
}

#[test]
fn boss_bars_show_follow_a_centity_and_go_with_their_script() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let boss = r#"{ "nodes": { "body": { "display": { "type": "block", "block": "minecraft:stone" } } }, "health": { "max": 10 } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/boss/centity.json", boss),
        (
            "modules/arena/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local bar = nf.bossbars.create({ text = "Wave 1", progress = 0.5 })
print(bar:show_to(alex), #alex:bossbars(), pcall(bar.set_progress, bar, 2))
local boss = nf.centities.spawn("boss", vector.create(10, 64, 0))
local health = nf.bossbars.create({ text = "Boss" })
print(health:attach(boss, { range = 20 }), health:attached() == boss)
boss:damage(5)
alex:sidebar():set_lines({ "Kills: 3" })
print(alex:sidebar():lines()[1], #alex:sidebar():lines())
"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "true\t1\tfalse\tmodules/arena/init.lua:4: progress must be from 0 to 1, not 2",
            "true\ttrue",
            "Kills: 3\t1",
        ]
    );
    session.tick();
    let ticked = platform.take();
    let bars: Vec<&String> = calls
        .iter()
        .chain(&ticked)
        .filter(|c| c.starts_with("bar") || c.starts_with("sidebar"))
        .collect();
    assert_eq!(
        bars,
        [
            "bar 1 made Wave 1 0.5",
            "bar 1 shown to 77",
            "bar 2 made Boss 1",
            "bar 2 shown to 77",
            "sidebar 77  [\"Kills: 3\"]",
            "bar 2 Boss 0.5",
        ]
    );
    // The module's reloaded: its bars go with its old scope.
    session.reload(&["modules/arena/init.lua".to_owned()]);
    let reloaded = platform.take();
    assert!(
        reloaded.contains(&"bar 1 removed".to_owned())
            && reloaded.contains(&"bar 2 removed".to_owned()),
        "{reloaded:?}"
    );
}

#[test]
fn teams_are_the_projects_own_on_the_scoreboard_and_go_at_the_end() {
    use netherforge_platform::{PlatformEvents, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/teams/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local zombie = nf.worlds.default():entities()[1]
local blue = nf.teams.create("blue", { prefix = "[B] " })
print(blue:add_member(alex), blue:add_member(zombie), alex:team() == blue, #blue:members(), blue:members()[1]:is_player())
print(pcall(nf.teams.create, "blue"), pcall(nf.teams.create, "no spaces"))
print(blue:set_color("blue"), blue:color(), #nf.teams.all())
alex:set_below_name("Level 3")
print(alex:below_name(), alex:set_listed_for(alex, false), alex:is_listed_for(alex))
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            // Entries sorted: the zombie's UUID before "Alex".
            "true\ttrue\ttrue\t2\tfalse",
            "false\tfalse\tmodules/teams/init.lua:6: a team's name is letters, digits and _ - . +, not \"no spaces\"",
            "true\tblue\t1",
            "Level 3\ttrue\tfalse",
        ]
    );
    // A crash's leftover of ours goes before anything runs; another plugin's stays.
    assert!(
        calls.contains(&"team nf.stale removed".to_owned())
            && !calls.contains(&"team red removed".to_owned())
    );
    assert!(
        calls.contains(&"team nf.blue [B]  Some(\"blue\")".to_owned()),
        "{calls:?}"
    );
    // They come back: out of their own list again.
    let alex = netherforge_platform::PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    server.game().player_join(&mut game_event::PlayerJoin {
        player: alex,
        first_join: false,
        message: None,
    });
    assert!(
        platform
            .take()
            .contains(&"listed 77 for 77 false".to_owned())
    );
    server.session_mut().stop();
    let stopped = platform.take();
    for line in [
        "team nf.blue removed",
        "below cleared",
        "listed 77 for 77 true",
    ] {
        assert!(stopped.contains(&line.to_owned()), "{line} {stopped:?}");
    }
}

#[test]
fn a_worlds_border_is_read_and_set() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/border/init.lua",
            r#"
local border = nf.worlds.default():border()
print(border:size(), border:damage().buffer, border:warning().ticks, border:set_size(500, { ticks = 200 }))
print(pcall(border.set_size, border, 0), border:set_damage({ amount = 1 }), pcall(border.set_damage, border, { buffer = -1 }))
print(select(2, pcall(border.set_size, border, 0)))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        [
            "1000\t5\t300\ttrue",
            "false\ttrue\tfalse\tmodules/border/init.lua:4: damage.buffer can't be negative",
            "modules/border/init.lua:5: a border's size must be from 1 to 59999968, not 0",
        ]
    );
    assert!(
        calls.contains(&"border size 500 over 200".to_owned())
            && calls.contains(&"border damage 1 5".to_owned()),
        "{calls:?}"
    );
}

#[test]
fn an_inventory_gives_counts_finds_and_takes_matching_stacks() {
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/bag/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local bag = alex:inventory()
print(bag:kind(), bag:size(), bag:holder() == alex)
bag:add_item({ kind = "minecraft:bread", count = 5 })
bag:add_item({ kind = "minecraft:emerald", count = 3, data = { coin = true, mint = "x" } })
bag:set_item(4, { kind = "minecraft:bread", count = 2 })
print(bag:count_item("bread"), bag:count_item({ data = { coin = true } }), bag:first_slot("emerald"), bag:has_item("bread", 8))
print(bag:remove_item("bread", 6), bag:count_item("bread"), bag:item(4).count, bag:item(0))
print(pcall(bag.item, bag, 9))
print(pcall(bag.remove_item, bag, { colour = 1 }))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    let lines = printed(&calls);
    assert_eq!(
        lines[..4],
        [
            "player\t9\ttrue",
            "7\t3\t1\tfalse",
            "6\t1\t1\tnil",
            "false\tmodules/bag/init.lua:10: slot 9 is outside this inventory: it has 9 slots, 0 to 8",
        ],
        "{lines:?}"
    );
    assert!(
        lines[4].starts_with(
            "false\tmodules/bag/init.lua:11: bad argument 'match' (unknown field `colour`"
        ),
        "{lines:?}"
    );
}

#[test]
fn a_menu_window_runs_its_script_hears_clicks_and_goes_when_closed() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let shop = r#"{ "name": "Shop", "rows": 1, "locked": true, "slots": { "4": { "item": { "kind": "minecraft:emerald" } } }, "script": { "file": "script.lua" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("menus/shop/menu.json", shop),
        (
            "menus/shop/script.lua",
            r#"
print("window", this:size(), this:title(), this:item(4).kind, this:context())
this:slot(4):on("click", function(e) print("slot", e.index, e.cancelled, e.context); e:uncancel() end)
this:on("click", function(e) print("menu", e.index, e.in_menu, e.cancelled) end)
this:on("close", function(e) print("closed", e.player:name()) end)
"#,
        ),
        (
            "modules/open/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local menu = alex:open_menu("shop", { context = "gift" })
print(menu ~= nil, alex:menu() == menu, menu:kind(), pcall(menu.slot, menu, 9))
nf.on("menu_click", function(e) print("nf", e.index, e.cancelled) end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "window\t9\tShop\tminecraft:emerald\tgift",
            "true\ttrue\tshop\tfalse\tmodules/open/init.lua:4: slot 9 is outside this window: it has 9 slots, 0 to 8",
        ]
    );
    assert!(
        calls.contains(&"window made 9 \"Shop\"".to_owned()),
        "{calls:?}"
    );
    let window = Uuid(*platform.windows.borrow().keys().next().expect("a window"));
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let click = |slot: i32, top: bool| netherforge_platform::MenuClick {
        player: alex.clone(),
        slot: Some(slot),
        top,
        click: "left".into(),
        hotbar: None,
        item: None,
        cursor: None,
        moves_items: true,
    };
    // A locked window's click starts cancelled; the slot's handler lets this one through.
    assert!(!server.menu_clicked(window, click(4, true)));
    // A click in the window off that slot stays cancelled; one in their own bag below isn't the window's.
    assert!(server.menu_clicked(window, click(2, true)));
    server.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "slot\t4\ttrue\tgift",
            "menu\t4\ttrue\tfalse",
            "nf\t4\tfalse",
            "menu\t2\ttrue\ttrue",
            "nf\t2\ttrue",
        ]
    );
    // They close it: its close, and next tick it's retired with its handlers.
    platform
        .windows
        .borrow_mut()
        .get_mut(&window.0)
        .expect("open")
        .1
        .clear();
    server.menu_closed(window, alex.clone());
    server.tick();
    let closed = platform.take();
    assert_eq!(printed(&closed), ["closed\tAlex"]);
    assert!(
        closed.contains(&"window destroyed".to_owned()),
        "{closed:?}"
    );
    assert!(!server.menu_clicked(window, click(4, true)));
}

#[test]
fn a_menu_slot_bound_to_a_storage_writes_through_and_a_rule_lets_in_what_fits() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let vault =
        r#"{ "name": "Vault", "rows": 1, "locked": false, "script": { "file": "script.lua" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("menus/vault/menu.json", vault),
        (
            "menus/vault/script.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local bank = alex:storage("bank", 9)
bank:set_item(0, { kind = "minecraft:emerald", count = 2 })
print(this:slot(0):bind(bank), this:item(0).count, this:slot(0):binding().index)
this:set_item(1, { kind = "minecraft:bread" })
print(this:slot(1):bind(bank), bank:item(1))
this:slot(0):set_item({ kind = "minecraft:emerald", count = 5 })
print(bank:item(0).count, #bank:viewers())
this:slot(2):set_rule({ kinds = { "minecraft:diamond" }, max = 1 })
print(this:slot(2):accepts({ kind = "minecraft:emerald" }), this:slot(2):rule().max)
print(pcall(this:slot(2).set_rule, this:slot(2), { max = 0 }))
"#,
        ),
        (
            "modules/open/init.lua",
            "nf.worlds.default():players()[1]:open_menu('vault')",
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "true\t2\t0",
            // Bound, the slot shows the storage's (empty): the bread it held isn't the storage's.
            "true\tnil",
            "5\t0",
            "false\t1",
            "false\tmenus/vault/script.lua:12: bad argument 'rule.max' (at least 1, got 0)",
        ]
    );
    let window = Uuid(*platform.windows.borrow().keys().next().expect("a window"));
    // The game put something in a bound slot by hand: the storage holds it once the click is done.
    platform
        .windows
        .borrow_mut()
        .get_mut(&window.0)
        .expect("open")
        .0[1] = Some(stack("minecraft:apple"));
    server.menu_changed(window, vec![1]);
    assert_eq!(
        platform.storages.borrow()["demo:bank"][1]
            .as_ref()
            .map(|i| i.def.kind.clone()),
        Some("minecraft:apple".to_owned())
    );
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let click = |cursor: netherforge_platform::ItemData| netherforge_platform::MenuClick {
        player: alex.clone(),
        slot: Some(2),
        top: true,
        click: "left".into(),
        hotbar: None,
        item: None,
        cursor: Some(cursor),
        moves_items: true,
    };
    // An emerald the rule doesn't take is refused; three diamonds put one in, the rest stay on the cursor.
    assert!(server.menu_clicked(window, click(stack("minecraft:emerald"))));
    let mut diamonds = stack("minecraft:diamond");
    diamonds.def.count = Some(3);
    assert!(server.menu_clicked(window, click(diamonds)));
    let moved = platform.take();
    assert!(
        moved.contains(&"cursor 77 Some((\"minecraft:diamond\", Some(2)))".to_owned()),
        "{moved:?}"
    );
    assert_eq!(
        platform.windows.borrow()[&window.0].0[2]
            .as_ref()
            .and_then(|i| i.def.count),
        Some(1)
    );
}

#[test]
fn a_dialog_is_shown_pressed_and_closed_with_its_context() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let guestbook = r#"{ "type": "notice", "title": "Sign", "inputs": [ { "type": "text", "key": "line", "maxLength": 8 } ], "buttons": [ { "key": "sign", "label": "Sign" } ], "script": { "file": "script.lua" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("dialogs/guestbook/dialog.json", guestbook),
        (
            "dialogs/guestbook/script.lua",
            r#"
this:button("sign"):on("press", function(e) print("signed", e.values.line, e.context, e.key) end)
this:on("close", function(e) print("left", e.context) end)
print(this:title(), this:buttons()[1], this:inputs()[1], this:button("sign"):label(), pcall(this.button, this, "nope"))
"#,
        ),
        (
            "modules/visit/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
print(alex:open_dialog("guestbook", { context = "visit", values = { line = "hi" } }))
print(pcall(alex.open_dialog, alex, "guestbook", { values = { line = "far too long" } }))
print(pcall(alex.open_dialog, alex, "guestbook", { values = { colour = "x" } }))
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    let lines = printed(&calls);
    assert_eq!(lines[0], "true", "{lines:?}");
    assert_eq!(
        lines[1],
        "false\tmodules/visit/init.lua:4: options.values.line is 12 characters, more than the input's max_length of 8"
    );
    assert_eq!(
        lines[2],
        "false\tmodules/visit/init.lua:5: dialog guestbook has no input \"colour\" (it has: line)"
    );
    assert!(lines[3].starts_with("Sign\tsign\tline\tSign\tfalse\tdialogs/guestbook/script.lua:4: dialog guestbook has no button \"nope\" (it has: sign)"), "{lines:?}");
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("dialog guestbook to 77 \"Sign\"")
                && c.contains("initial: Some")
                && c.contains("hi")),
        "{calls:?}"
    );
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let pressed = std::collections::HashMap::from([(
        "line".to_owned(),
        netherforge_platform::Dynamic::String("hello".into()),
    )]);
    server.dialog_pressed(
        alex.clone(),
        "guestbook".into(),
        "sign".into(),
        pressed.clone(),
    );
    // Pressed, the notice closed: a second press of it is a client's invention, ignored.
    server.dialog_pressed(alex.clone(), "guestbook".into(), "sign".into(), pressed);
    server.tick();
    let after = platform.take();
    assert_eq!(printed(&after), ["signed\thello\tvisit\tsign"], "{after:?}");
    assert!(
        after.iter().any(
            |l| l.contains("pressed \"sign\" on dialog guestbook, which isn't on their screen")
        )
    );
}

#[test]
fn dialog_ask_waits_for_their_press_or_close() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let confirm = r#"{ "type": "confirmation", "title": "Sure?", "buttons": [ { "key": "yes", "label": "Yes" }, { "key": "no", "label": "No" } ] }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("dialogs/confirm/dialog.json", confirm),
        (
            "modules/ask/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local confirm = nf.dialogs.get("confirm")
print(pcall(confirm.ask, confirm, alex))
nf.task(function()
  local first = confirm:ask(alex)
  print("first", first and first.key, first and first.player == alex)
  print("second", confirm:ask(alex))
  confirm:open_for(alex)
  local pressed = nf.wait_for(confirm, "press")
  print("third", pressed.key)
end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert!(
        printed(&calls)[0]
            .starts_with("false\tmodules/ask/init.lua:4: dialog:ask only works inside a task"),
        "{calls:?}"
    );
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let none = std::collections::HashMap::new();
    server.tick();
    let shown = platform.take();
    assert!(
        calls
            .iter()
            .chain(&shown)
            .any(|c| c.starts_with("dialog confirm to 77")),
        "{calls:?} {shown:?}"
    );
    server.dialog_pressed(alex.clone(), "confirm".into(), "yes".into(), none.clone());
    server.tick();
    assert_eq!(printed(&platform.take()), ["first\tyes\ttrue"]);
    server.dialog_closed(alex.clone(), "confirm".into());
    server.tick();
    assert_eq!(printed(&platform.take()), ["second\tnil"]);
    // wait_for on a handle: the dialog's own press.
    server.dialog_pressed(alex.clone(), "confirm".into(), "no".into(), none);
    server.tick();
    assert_eq!(printed(&platform.take()), ["third\tno"]);
}

#[test]
fn a_dialog_made_in_lua_is_checked_as_its_file_and_shown() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/made/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local d = nf.dialogs.create({
  title = "Name it",
  inputs = { { type = "text", key = "name", max_length = 8 } },
  body = { { type = "item", item = { kind = "minecraft:bread", count = 2 } } },
  buttons = { { key = "ok", label = "Ok" } },
})
d:button("ok"):on("press", function(e) print("named", e.values.name) end)
print(d:title(), d:inputs()[1], d:exists(), pcall(nf.dialogs.get, d:id()))
print(alex:open_dialog(d))
print(pcall(nf.dialogs.create, { title = "x", inputs = { { type = "text", key = "a", maxLength = 2 } } }))
print(pcall(nf.dialogs.create, { title = "x", script = { file = "a.lua" } }))
print(pcall(nf.dialogs.create, { title = "x", type = "dialog_list", dialogs = { "nope" } }))
print(pcall(nf.dialogs.create, { title = "x", body = { { type = "item", item = { kind = "minecraft:bread", colour = 1 } } } }))
print(pcall(nf.dialogs.create, { title = "x", columns = 3 }))
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    let lines = printed(&calls);
    assert!(
        lines[0].starts_with("Name it\tname\ttrue\tfalse\t"),
        "{lines:?}"
    );
    assert_eq!(lines[1], "true");
    assert_eq!(
        &lines[2..],
        [
            "false\tmodules/made/init.lua:12: definition.inputs[1].maxLength: Lua spells keys in snake_case, so it's max_length",
            "false\tmodules/made/init.lua:13: definition.script: a dialog made in Lua has no script of its own; put handlers on the Dialog it returns",
            "false\tmodules/made/init.lua:14: definition.dialogs[1]: no dialog \"nope\" in this project",
            "false\tmodules/made/init.lua:15: bad argument 'definition.body[1].item' (unknown field `colour`, expected one of `kind`, `name`, `lore`, `enchantments`, `glint`, `hide_tooltip`, `unbreakable`, `item_model`, `tooltip_style`, `equipment`, `color`, `profile`, `max_stack_size`, `rarity`, `attribute_modifiers`, `can_break`, `can_place_on`, `invisible`, `hidden_tooltip_components`, `food`, `cooldown`, `hold`, `use_effects`, `item`, `count`, `damage`, `map`, `data`)",
            "false\tmodules/made/init.lua:16: definition.columns: Only a multi_action dialog lays its buttons out in columns",
        ],
        "{lines:?}"
    );
    let shown = calls
        .iter()
        .find(|c| c.starts_with("dialog "))
        .expect("shown");
    let id = shown.split(' ').nth(1).unwrap().to_owned();
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let values = std::collections::HashMap::from([(
        "name".to_owned(),
        netherforge_platform::Dynamic::String("Rex".into()),
    )]);
    server.dialog_pressed(alex, id, "ok".into(), values);
    server.tick();
    assert_eq!(printed(&platform.take()), ["named\tRex"]);
}

#[test]
fn a_menu_template_opens_windows_whose_events_reach_it() {
    use netherforge_platform::PlatformEvents;
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/made/init.lua",
            r#"
local alex = nf.worlds.default():players()[1]
local t = nf.menus.create({ rows = 2, title = "Made", locked = true, slots = { [3] = { kind = "minecraft:emerald", count = 2 } } })
print(t:exists(), t:size(), t:rows(), t:title(), t:is_locked(), t:item(3).kind, t:item(3).count, t:item(4))
print(pcall(t.item, t, 18))
t:on("click", function(e) print("template", e.index, e.cancelled, e.context) end)
t:on("open", function(e) print("opened", e.context) end)
local menu = alex:open_menu(t, { context = "c" })
print(menu:kind(), menu:template() == t, #t:windows(), menu:item(3).kind)
nf.on("menu_click", function(e) print("nf", e.index) end)
print(pcall(nf.menus.create, { rows = 2, colour = "red" }))
print(pcall(nf.menus.create, { rows = 9 }))
print(pcall(nf.menus.create, { slots = { [40] = { kind = "minecraft:stone" } } }))
print(pcall(nf.menus.create, { slots = { [1] = { kind = "minecraft:stone", colour = 1 } } }))
_G.t = t
nf.task(function() nf.wait(1); print("removed", t:remove(), t:exists(), menu:exists(), t:remove()) end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    let lines = printed(&calls);
    assert_eq!(
        lines[..4],
        [
            "true\t18\t2\tMade\ttrue\tminecraft:emerald\t2\tnil",
            "false\tmodules/made/init.lua:5: slot 18 is outside this template's windows: they have 18 slots, 0 to 17",
            "opened\tc",
            "nil\ttrue\t1\tminecraft:emerald",
        ],
        "{lines:?}"
    );
    assert_eq!(
        lines[4..7],
        [
            "false\tmodules/made/init.lua:11: unknown field 'definition.colour' (fields: locked, rows, skin, slots, title, type)",
            "false\tmodules/made/init.lua:12: definition.rows: A chest has 1 to 6 rows",
            "false\tmodules/made/init.lua:13: definition.slots[40]: This menu has 27 slots (0–26)",
        ],
        "{lines:?}"
    );
    assert!(lines[7].starts_with(
        "false\tmodules/made/init.lua:14: bad argument 'definition.slots[1]' (unknown field `colour`"
    ));
    let window = Uuid(*platform.windows.borrow().keys().next().expect("a window"));
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let click = netherforge_platform::MenuClick {
        player: alex,
        slot: Some(3),
        top: true,
        click: "left".into(),
        hotbar: None,
        item: None,
        cursor: None,
        moves_items: true,
    };
    assert!(server.menu_clicked(window, click));
    server.tick();
    let after = platform.take();
    assert_eq!(
        printed(&after),
        [
            "template\t3\ttrue\tc",
            "nf\t3",
            "removed\ttrue\tfalse\tfalse\tfalse"
        ],
        "{after:?}"
    );
    assert!(after.contains(&"window destroyed".to_owned()), "{after:?}");
}

#[test]
fn encoding_and_crypto_write_what_the_kotlin_runtime_wrote() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/enc/init.lua",
            r#"
local e, c = nf.encoding, nf.crypto
print(e.url_encode("a b/c~é"), e.url_decode("a%20b%2Fc+"), e.url_decode("%zz"))
print(e.form_encode({ name = "Steve Jobs", n = 2, half = 0.5, ok = true, metadata = { player = "x" }, items = { "a", "b" }, none = {} }))
print(pcall(e.form_encode, { "a" }))
print(pcall(e.form_encode, { f = print }))
local form = e.form_decode("a=1&b=x+y&a=2&c")
print(form.a, form.b, form.c, e.form_decode("a=%"))
print(e.base64_encode("hi?>"), e.base64_encode("hi?>", { url_safe = true, padding = false }), e.base64_decode("aGk_Pg"), e.base64_decode("!!"))
print(e.hex_encode("Hi"), e.hex_decode("4869"), e.hex_decode("486"), e.hex_decode("zz"))
print(c.hash("abc"))
print(c.hash("abc", { algorithm = "sha1" }), c.hash("abc", { encoding = "base64" }))
print(c.sign("The quick brown fox jumps over the lazy dog", { secret = "key" }))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    assert!(!calls.iter().any(|l| l.starts_with("warn")), "{calls:?}");
    assert_eq!(
        printed(&calls),
        [
            "a%20b%2Fc~%C3%A9\ta b/c+\tnil",
            "half=0.5&items%5B0%5D=a&items%5B1%5D=b&metadata%5Bplayer%5D=x&n=2&name=Steve+Jobs&ok=true",
            "false\tmodules/enc/init.lua:5: bad argument 'fields' (a form's names are strings: a table of them expected, got a list)",
            "false\tmodules/enc/init.lua:6: fields.f: a function can't be put in a form",
            "1\tx y\t\tnil",
            "aGk/Pg==\taGk_Pg\thi?>\tnil",
            "4869\tHi\tnil\tnil",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "a9993e364706816aba3e25717850c26c9cd0d89d\tungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        ]
    );
}

#[test]
fn a_module_declares_a_command_that_reads_typed_arguments() {
    use netherforge_platform::{ArgumentValue, CommandInput, SuggestionRequest};
    let platform = std::rc::Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/cmd/init.lua",
            r#"
print(nf.commands.register("greet", {
  description = "Says hello",
  arguments = {
    { name = "times", type = "integer", min = 1, max = 5, default = 1 },
    { name = "who", type = "word", default = false, complete = function(event, partial) return { "alex", "amy", "bo" } end },
  },
  subcommands = {
    admin = { permission = "greet.admin", handler = function(e) print("admin", e.label) end },
    shout = { players_only = true, arguments = { { name = "text", type = "text" } }, handler = function(e) print("shout", e.arguments.text, e.player:name()) end },
  },
}, function(e) print("greet", e.arguments.times, e.arguments.who, e.input, e.player and e.player:name(), e.sender:is_console()) end))
print(nf.commands.register("vanilla", function() end))
print(pcall(nf.commands.register, "Bad", function() end))
print(pcall(nf.commands.register, "x", { arguments = { { name = "a", type = "word", default = "z" }, { name = "b", type = "word" } } }, function() end))
print(pcall(nf.commands.register, "y", { arguments = { { name = "n", type = "integer", default = 9, max = 5 } } }, function() end))
print(pcall(nf.commands.register, "z", { arguments = { { name = "c", type = "choice" } } }, function() end))
print(pcall(nf.commands.register, "w", {}))
print(pcall(nf.commands.register, "v", { arguments = { { name = "n", type = "integer", default = "x" } } }, function() end))
print(nf.commands.register("boom", function() error("kaboom") end))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let calls = platform.take();
    let lines = printed(&calls);
    assert_eq!(
        lines,
        [
            "true",
            "false",
            "false\tmodules/cmd/init.lua:14: \"Bad\" can't be a command name (lowercase letters, digits, _ and -, starting with a letter)",
            "false\tmodules/cmd/init.lua:15: argument 'b' in 'definition' has no default but comes after 'a', which has one: optional arguments must come last",
            "false\tmodules/cmd/init.lua:16: 'definition.arguments[1].default' (9) is outside min and max",
            "false\tmodules/cmd/init.lua:17: 'definition.arguments[1]' is a choice, so it needs choices",
            "false\tmodules/cmd/init.lua:18: 'definition' has neither a handler nor subcommands",
            "false\tmodules/cmd/init.lua:19: bad argument 'definition.arguments[1].default' (whole number or false expected, got string)",
            "true",
        ],
        "{calls:?}"
    );
    assert!(
        calls
            .iter()
            .any(|c| c.contains("/vanilla is already a command")),
        "{calls:?}"
    );
    let (spec, handler) = platform
        .commands
        .borrow()
        .get("greet")
        .cloned()
        .expect("registered");
    assert_eq!(spec.syntax.arguments.len(), 2);
    assert!(spec.syntax.arguments[0].optional);
    let alex = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let sender = |player: Option<PlayerRef>, allowed: bool| {
        std::rc::Rc::new(TestSender {
            player,
            allowed,
            replies: RefCell::new(Vec::new()),
        })
    };
    let input = |path: &[&str], arguments: Vec<(&str, ArgumentValue)>, text: &str| CommandInput {
        path: path.iter().map(|s| s.to_string()).collect(),
        arguments: arguments
            .into_iter()
            .map(|(k, v)| (k.to_owned(), v))
            .collect(),
        text: text.into(),
    };
    let console = sender(None, true);
    handler.run(console.clone(), "greet".into(), input(&[], vec![], ""));
    let player = sender(Some(alex.clone()), false);
    handler.run(
        player.clone(),
        "greet".into(),
        input(
            &[],
            vec![
                (
                    "times",
                    ArgumentValue::Integer(netherforge_platform::ArgumentValueInteger { value: 3 }),
                ),
                (
                    "who",
                    ArgumentValue::Text(netherforge_platform::ArgumentValueText {
                        text: "bo".into(),
                    }),
                ),
            ],
            "3 bo",
        ),
    );
    handler.run(
        player.clone(),
        "greet".into(),
        input(&["admin"], vec![], "admin"),
    );
    handler.run(
        console.clone(),
        "greet".into(),
        input(&["shout"], vec![], "shout hi"),
    );
    let text = ArgumentValue::Text(netherforge_platform::ArgumentValueText {
        text: "hi there".into(),
    });
    handler.run(
        player.clone(),
        "greet".into(),
        input(&["shout"], vec![("text", text)], "shout hi there"),
    );
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "greet\t1\tfalse\t\tnil\ttrue",
            "greet\t3\tbo\t3 bo\tAlex\tfalse",
            "shout\thi there\tAlex",
        ]
    );
    assert_eq!(
        *console.replies.borrow(),
        ["<red>Only players can use /greet shout."]
    );
    assert_eq!(
        *player.replies.borrow(),
        ["<red>You don't have permission to use /greet admin."]
    );
    let completion = handler.suggest(
        player.clone(),
        SuggestionRequest {
            path: Vec::new(),
            argument: "who".into(),
            arguments: Default::default(),
            partial: "a".into(),
        },
    );
    assert_eq!(completion.suggestions, ["alex", "amy"]);
    let (_, boom) = platform
        .commands
        .borrow()
        .get("boom")
        .cloned()
        .expect("registered");
    let typed = sender(None, true);
    boom.run(typed.clone(), "boom".into(), input(&[], vec![], ""));
    assert_eq!(typed.replies.borrow().len(), 1);
    assert!(
        typed.replies.borrow()[0].starts_with("<red>/boom failed: modules/cmd/init.lua:20: kaboom"),
        "{:?}",
        typed.replies.borrow()
    );
    platform.take();
    let mut session = session;
    session.stop();
    let stopped = platform.take();
    assert!(
        stopped.contains(&"command unregistered /greet".to_owned()),
        "{stopped:?}"
    );
    assert!(platform.commands.borrow().is_empty());
}
