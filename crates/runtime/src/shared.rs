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
    /// Natural spawning: its passes, rules and scripts' conditions.
    pub spawner: RefCell<crate::centity::natural::Spawner>,
    /// The pictures scripts hold.
    pub images: Rc<crate::images::Images>,
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
}

impl Shared {
    pub fn new(platform: Rc<dyn Platform>, store: Rc<crate::store::Store>) -> Rc<Shared> {
        Rc::new(Shared {
            platform,
            store,
            data: RefCell::default(),
            permissions: RefCell::default(),
            session_tables: RefCell::default(),
            schedules: RefCell::default(),
            names: RefCell::default(),
            centities: RefCell::default(),
            font: RefCell::default(),
            centity_scripts: RefCell::default(),
            next_flight: std::cell::Cell::new(0),
            path_nodes: std::cell::Cell::new(0),
            spawner: RefCell::default(),
            images: Rc::default(),
            physics: RefCell::default(),
            stats: RefCell::default(),
            modules: RefCell::default(),
            classed: RefCell::default(),
            entities: RefCell::default(),
            gone: RefCell::default(),
        })
    }

    /// The entity `info` describes as the handle the spec's types say (`Entity`), made the class it is
    /// (Kotlin's `entityHandle`): a player's, a mob's, a dropped item's... A handle is one value per thing, so
    /// the script gets the value pushed here, of that class.
    pub fn entity(
        &self,
        lua: &mlua::Lua,
        info: &netherforge_platform::EntityInfo,
    ) -> handles::Entity {
        use netherforge_platform::EntityCategory;
        let id = crate::events::uuid_text(&info.id);
        let classed = if info.player.is_some() {
            Handle::Player(handles::Player { id: id.clone() })
        } else {
            match info.category {
                EntityCategory::Mob => Handle::Mob(handles::Mob { id: id.clone() }),
                EntityCategory::Mannequin => {
                    Handle::Mannequin(handles::Mannequin { id: id.clone() })
                }
                EntityCategory::Living => Handle::Living(handles::Living { id: id.clone() }),
                EntityCategory::DroppedItem => {
                    Handle::DroppedItem(handles::DroppedItem { id: id.clone() })
                }
                EntityCategory::Other => return handles::Entity { id },
            }
        };
        if let Ok(pushed) = netherforge_api::handle::push_any(lua, &classed) {
            self.classed.borrow_mut().push(pushed);
        }
        handles::Entity { id }
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
