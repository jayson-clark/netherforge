//! What one session's parts share with the API its scripts call: the server, the centities' instances,
//! the font text is measured with, and the project's modules. The session owns it; the room's API holds it
//! too, so it's `'static` and every piece is a `RefCell`: a borrow is never held while Lua runs.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;

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
    /// The `centity:data()` tables scripts asked for, by instance.
    pub centity_data: RefCell<std::collections::HashMap<netherforge_platform::Uuid, DataTable>>,
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
}

impl Shared {
    pub fn new(platform: Rc<dyn Platform>, store: Rc<crate::store::Store>) -> Rc<Shared> {
        Rc::new(Shared {
            platform,
            store,
            centity_data: RefCell::default(),
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
        })
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
