//! What one session's parts share with the API its scripts call: the server, the centities' instances,
//! the font text is measured with, and the project's modules. The session owns it; the room's API holds it
//! too, so it's `'static` and every piece is a `RefCell`: a borrow is never held while Lua runs.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;

use netherforge_api::handles::{self, Handle};
use netherforge_format::font::DefaultFontFile;
use netherforge_platform::Platform;

use crate::Modules;
use crate::centity::{Centities, Drawing};

/// A `centity:data()` table, live, and the text it was last saved or read as.
pub struct DataTable {
    pub table: mlua::Table,
    pub saved: Option<String>,
}

pub struct Shared {
    pub platform: Rc<dyn Platform>,
    pub centities: RefCell<Centities>,
    /// The project's `fonts/default.json`, which anchored text is measured with.
    pub font: RefCell<Option<DefaultFontFile>>,
    /// Each centity's script, by centity id: its project path.
    pub centity_scripts: RefCell<BTreeMap<String, String>>,
    /// The last flight launched: each launch is told apart by its number.
    pub next_flight: std::cell::Cell<u64>,
    /// Places searched this tick by walks finding a new path on their own.
    pub path_nodes: std::cell::Cell<usize>,
    /// The runtime's store: placements and saved tables.
    pub store: Rc<crate::store::Store>,
    /// Scripts' saved tables: `centity:data()`, `player:data()`, `nf.data(name)`.
    pub data: RefCell<crate::data::ScriptData>,
    /// The permission nodes scripts set on players.
    pub permissions: RefCell<crate::permissions::Permissions>,
    /// `player:session()`: a plain table per online player, never saved.
    pub session_tables: RefCell<std::collections::HashMap<netherforge_platform::Uuid, mlua::Table>>,
    /// `nf.schedule`'s live schedules.
    pub schedules: RefCell<crate::schedule::Schedules>,
    /// What each package of the tree can name, as the project last read: how names cross between packages.
    pub names: RefCell<netherforge_format::project::Names>,
    /// The skies over worlds and players.
    pub skies: RefCell<crate::sky::Skies>,
    /// The screens and what they draw with.
    pub screens: RefCell<crate::screen::Screens>,
    /// Every online player's HUD.
    pub huds: RefCell<crate::huds::Huds>,
    /// The resource pack as last built, and the characters it draws glyphs and skins with.
    pub packs: RefCell<crate::packs::Packs>,
    /// The scripts' data directory (`nf.files`), when the server has one.
    pub files: RefCell<Option<crate::files::ScriptFiles>>,
    /// The tables `block:data()` hands out.
    pub block_data: RefCell<crate::block_data::BlockData>,
    /// Who's mining the project's blocks (and the game's with rules), and whose speed is turned down.
    pub mining: RefCell<crate::block_mining::BlockMining>,
    /// The project's blocks and every one placed in a loaded chunk.
    pub blocks: RefCell<crate::custom_blocks::CustomBlocks>,
    /// The project's recipes and those scripts registered.
    pub recipes: RefCell<crate::recipes::Recipes>,
    /// The project's loot tables.
    pub loot: RefCell<crate::loot::LootTables>,
    /// The project's particle effects and every one playing.
    pub particles: RefCell<crate::particles::Particles>,
    /// The project's cutscenes and every one playing.
    pub cutscenes: RefCell<crate::cutscenes::Cutscenes>,
    /// Boss bars scripts made, and players' sidebars.
    pub bars: RefCell<crate::bossbar::Bars>,
    /// Teams scripts made, lines under name tags, and who's out of whose player list.
    pub teams: RefCell<crate::team::Teams>,
    /// Menus' definitions and their open windows.
    pub menus: RefCell<crate::menu::Menus>,
    /// Dialogs and which one each player has open.
    pub dialogs: RefCell<crate::dialog::Dialogs>,
    /// The commands modules declared.
    pub commands: RefCell<crate::commands::Commands>,
    /// The project's items.
    pub items: RefCell<crate::project_items::ProjectItems>,
    /// Natural spawning: its passes, rules and scripts' conditions.
    pub spawner: RefCell<crate::centity::natural::Spawner>,
    /// `nf.http`: the owner's limits, the client, the rates, and the connections open.
    pub http: RefCell<crate::http::api::Http>,
    /// `nf.web`: routes, the requests waiting on them, login flows.
    pub web: RefCell<crate::web::Web>,
    /// `nf.wasm`: the modules compiled, the instances and programs scripts hold, the owner's limits.
    pub wasm: RefCell<crate::wasm::Wasm>,
    /// The plugins packages declared, and the placeholders scripts offer.
    pub interop: RefCell<crate::interop::Interop>,
    /// The bots scripts joined, and the owner's `bots:`.
    pub bots: RefCell<crate::bots::ScriptBots>,
    /// The owner's values for each package's settings (`nf.config`).
    pub settings: RefCell<crate::settings::Settings>,
    /// `nf.network`: the plugin's link, the channels and maps scripts named, what waits for the hub.
    pub network: RefCell<crate::network::service::Network>,
    /// The pictures scripts hold.
    pub images: Rc<crate::images::Images>,
    /// A script test run's tests (`nf.test`), and its harness: none on a server.
    pub tests: RefCell<crate::testing::Tests>,
    /// Nodes' rigid bodies.
    pub physics: RefCell<crate::centity::physics::Physics>,
    /// Players' and centities' stats and buffs.
    pub stats: RefCell<crate::stats::StatSheets>,
    /// The project's modules, while the session runs: what a resource's script requires through.
    pub modules: RefCell<Option<Modules>>,
    /// Handles pushed as their real class (`classed`), held until the tick ends so the class stays with them
    /// until the script has its value.
    pub classed: RefCell<Vec<mlua::AnyUserData>>,
    /// Who entities are hidden from, and their `data()` tables.
    pub entities: RefCell<crate::entity::EntityState>,
    /// Entities scripts removed since the session last looked: gone for good.
    pub gone: RefCell<Vec<netherforge_platform::Uuid>>,
    /// Mobs' walks (`mob:move_to`) and the goals scripts added.
    pub mobs: Rc<crate::mob::Mobs>,
    /// The packages' own databases (`nf.db`), and the owner's connections.
    pub databases: RefCell<crate::databases::Databases>,
    /// The worlds the project made and may take away.
    pub managed_worlds: RefCell<crate::managed_worlds::ManagedWorlds>,
    /// What the worlds' file work said as it failed on its lane, for the log at a tick.
    pub managed_failures: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    /// Structures: the project's, its builds, scripts' saves, and the gradual work going.
    pub structures: RefCell<crate::structures::Structures>,
    /// Lanes work goes to off the main thread, by name, made as they're first wanted (a biome search's).
    pub lanes: RefCell<std::collections::HashMap<&'static str, crate::http::Workers>>,
}

impl Shared {
    pub fn new(platform: Rc<dyn Platform>, store: Rc<crate::store::Store>) -> Rc<Shared> {
        Rc::new(Shared {
            mobs: crate::mob::Mobs::new(platform.clone()),
            lanes: RefCell::default(),
            structures: RefCell::default(),
            managed_worlds: RefCell::default(),
            databases: RefCell::default(),
            managed_failures: Default::default(),
            platform,
            store,
            data: RefCell::default(),
            permissions: RefCell::default(),
            session_tables: RefCell::default(),
            schedules: RefCell::default(),
            names: RefCell::default(),
            skies: RefCell::default(),
            screens: RefCell::default(),
            huds: RefCell::default(),
            packs: RefCell::default(),
            files: RefCell::default(),
            block_data: RefCell::default(),
            mining: RefCell::default(),
            blocks: RefCell::default(),
            recipes: RefCell::default(),
            loot: RefCell::default(),
            particles: RefCell::default(),
            cutscenes: RefCell::default(),
            bars: RefCell::default(),
            teams: RefCell::default(),
            menus: RefCell::default(),
            dialogs: RefCell::default(),
            commands: RefCell::default(),
            items: RefCell::default(),
            centities: RefCell::default(),
            font: RefCell::default(),
            centity_scripts: RefCell::default(),
            next_flight: std::cell::Cell::new(0),
            path_nodes: std::cell::Cell::new(0),
            spawner: RefCell::default(),
            images: Rc::default(),
            tests: RefCell::default(),
            http: RefCell::default(),
            web: RefCell::default(),
            wasm: RefCell::default(),
            network: RefCell::default(),
            settings: RefCell::default(),
            interop: RefCell::default(),
            bots: RefCell::default(),
            physics: RefCell::default(),
            stats: RefCell::default(),
            modules: RefCell::default(),
            classed: RefCell::default(),
            entities: RefCell::default(),
            gone: RefCell::default(),
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

    /// The entity `info` describes as the handle the spec's types say (`Entity`), made the class it is
    /// (Kotlin's `entityHandle`): a player's, a mob's, a dropped item's... A handle is one value per thing, so
    /// the script gets the value pushed here, of that class.
    pub fn entity(
        &self,
        lua: &mlua::Lua,
        info: &netherforge_platform::EntityInfo,
    ) -> handles::Entity {
        let id = crate::events::uuid_text(&info.id);
        let classed = self.class_of(info);
        if matches!(classed, Handle::Entity(_)) {
            return handles::Entity { id };
        }
        if let Ok(pushed) = netherforge_api::handle::push_any(lua, &classed) {
            self.classed.borrow_mut().push(pushed);
        }
        handles::Entity { id }
    }

    /// What `info` describes as the class it is (Kotlin's `entityHandle(info)`): a `Player`, a `Mob`, a
    /// `Mannequin`, a `Living`, a `DroppedItem`, or an `Entity`. A bot's is a `Player` until a script calls a `Bot`
    /// method on it ([`Shared::class_of_id`] then says): the server isn't asked whether each player is a bot.
    pub fn class_of(&self, info: &netherforge_platform::EntityInfo) -> Handle {
        use netherforge_platform::EntityCategory;
        let id = crate::events::uuid_text(&info.id);
        if info.player.is_some() {
            return Handle::Player(handles::Player { id });
        }
        match info.category {
            EntityCategory::Mob => Handle::Mob(handles::Mob { id }),
            EntityCategory::Mannequin => Handle::Mannequin(handles::Mannequin { id }),
            EntityCategory::Living => Handle::Living(handles::Living { id }),
            EntityCategory::DroppedItem => Handle::DroppedItem(handles::DroppedItem { id }),
            EntityCategory::Other => Handle::Entity(handles::Entity { id }),
        }
    }

    /// The entity `id` as the class it is now (Kotlin's `entityHandle(id)`): what the server says of it, a `Player`
    /// (a `Bot` online) for anyone who has played here, or an `Entity` for anything that isn't in the world now.
    pub fn class_of_id(&self, id: netherforge_platform::Uuid) -> Handle {
        let text = crate::events::uuid_text(&id);
        let players = self.platform.players();
        if players.get(id).is_some() {
            return if self.platform.bots().is_bot(id) {
                Handle::Bot(handles::Bot { id: text })
            } else {
                Handle::Player(handles::Player { id: text })
            };
        }
        if let Some(info) = self.platform.world_entities().info(id) {
            return self.class_of(&info);
        }
        if players.known(text.clone()).is_some() {
            return Handle::Player(handles::Player { id: text });
        }
        Handle::Entity(handles::Entity { id: text })
    }

    /// Player `id` as the `Entity` the spec's type says, its value a `Player`'s.
    pub fn player_entity(
        &self,
        lua: &mlua::Lua,
        id: netherforge_platform::Uuid,
    ) -> handles::Entity {
        let text = crate::events::uuid_text(&id);
        let classed = Handle::Player(handles::Player { id: text.clone() });
        if let Ok(pushed) = netherforge_api::handle::push_any(lua, &classed) {
            self.classed.borrow_mut().push(pushed);
        }
        handles::Entity { id: text }
    }

    /// The entity `id` as its class, when the server knows it; an `Entity` otherwise.
    pub fn entity_by_id(&self, lua: &mlua::Lua, id: netherforge_platform::Uuid) -> handles::Entity {
        match self.platform.world_entities().info(id) {
            Some(info) => self.entity(lua, &info),
            None => handles::Entity {
                id: crate::events::uuid_text(&id),
            },
        }
    }

    /// How wide `text` (MiniMessage) draws in the project's default font, the built pack's glyphs (their tags and
    /// their bare characters) included, and how many lines it is; none when the project has no font facts.
    pub fn measure_text(&self, text: &str) -> Option<(i32, i32)> {
        let font = self.font.borrow();
        let packs = self.packs.borrow();
        let width = netherforge_format::text::TextWidth::of_font(font.as_ref()?)
            .with_glyphs(|reference| packs.glyph_advance(reference))
            .with_glyph_chars(|code| packs.glyph_char_advance(code));
        Some((width.measure(text)?, width.line_count(text) as i32))
    }

    /// `f` with the centities and how their displays are drawn (text measured with the project's font).
    pub fn with_drawing<R>(&self, f: impl FnOnce(&mut Centities, &Drawing) -> R) -> R {
        let font = self.font.borrow();
        let font = font.as_ref();
        let text_size = |text: &str| {
            let width = netherforge_format::text::TextWidth::of_font(font?);
            Some((width.measure(text)?, width.line_count(text) as i32))
        };
        let platform = &*self.platform;
        let warn = |message: String| platform.log().warn(message);
        let drawing = Drawing {
            text_size: &text_size,
            warn: &warn,
        };
        f(&mut self.centities.borrow_mut(), &drawing)
    }
}
