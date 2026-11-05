//! Scripts' saved tables: `centity:data()`, `player:data()` and `nf.data(name)` (Kotlin's `ScriptData`).
//!
//! Each is a live Lua table, made the first time a script asks for it (from the store, when it was saved
//! before) and kept for as long as the session runs, so a reload finds it as it was. The whole table is
//! encoded by the JSON codec and saved when it changed: at autosave, when its player leaves, when the session
//! ends. A table encoded past `MAX_BYTES` isn't saved; its last save stands. When a session ends each table's
//! latest encoding is kept, and the next session's table (a restart after `netherforge.json` changed) starts
//! from it, not from the store.
//!
//! `nf.data(name)` is per package: each package has its own tables by name, under its namespace. A thing's
//! table (`player:data()`, `centity:data()`) is the thing's, one for every package's scripts.
//!
//! Not yet: tagged values in saved tables (a handle, a location), which the codec reports and skips.

use std::collections::HashMap;
use std::rc::Rc;

use mlua::Value;
use netherforge_api::handle::SavedHandles;
use netherforge_luau::Host;
use netherforge_platform::{Platform, PlayerRef, Uuid};

use crate::Room;
use crate::events::uuid_text;
use crate::session::Service;
use crate::shared::Shared;
use crate::store::Store;

/// The most a table may take, encoded: the same as a file (`File:write`).
pub const MAX_BYTES: usize = 1 << 20;

/// How often every table that changed is saved: five minutes, as the server's own autosave.
pub const AUTOSAVE_EVERY: u64 = 6000;

/// Whose table it is.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Owner {
    Centity(Uuid),
    Player(Uuid),
    /// `nf.data(name)` of the package `namespace`.
    Named {
        namespace: String,
        name: String,
    },
}

/// The log line for `what` encoding to `bytes`, past `MAX_BYTES`.
pub fn too_big(what: &str, bytes: usize) -> String {
    format!(
        "{what} takes {:.1} MiB, more than the 1 MiB a table may, so it wasn't saved: the last save stands",
        bytes as f64 / 1_048_576.0
    )
}

#[derive(Default)]
struct Table {
    /// Names it in the log (`player Steve`).
    label: String,
    table: Option<mlua::Table>,
    /// The text last saved or read, so an unchanged table isn't saved again.
    saved: Option<String>,
    /// Its encoding when the last session ended, for this session's table to start from.
    carried: Option<String>,
    /// What the last save couldn't keep, so the same problems aren't logged at every save.
    problems: Vec<String>,
    too_big: bool,
}

/// Every saved table the session's scripts asked for.
#[derive(Default)]
pub struct ScriptData {
    tables: HashMap<Owner, Table>,
}

impl ScriptData {
    /// `owner`'s live table, made from what the last session left or the store has (or empty) the first time.
    pub fn table(
        shared: &Shared,
        host: &Host,
        owner: Owner,
        label: String,
    ) -> mlua::Result<mlua::Table> {
        let lua = host.lua();
        let text = {
            let mut data = shared.data.borrow_mut();
            let table = data.tables.entry(owner.clone()).or_default();
            table.label = label.clone();
            if let Some(live) = &table.table {
                return Ok(live.clone());
            }
            match table.carried.take() {
                Some(text) => Some(text),
                None => {
                    let read = shared.store.data(&owner);
                    table.saved.clone_from(&read);
                    read
                }
            }
        };
        let made = match text
            .as_deref()
            .map(|t| netherforge_luau::json::decode(lua, t, &SavedHandles))
        {
            Some(Ok(Value::Table(t))) => t,
            Some(_) => {
                shared.platform.log().warn(format!(
                    "What the store has for {label}'s data isn't a saved table; it starts empty"
                ));
                lua.create_table()?
            }
            None => lua.create_table()?,
        };
        if let Some(table) = shared.data.borrow_mut().tables.get_mut(&owner) {
            table.table = Some(made.clone());
        }
        Ok(made)
    }

    /// Encodes and saves `owner`'s table if it changed since it was last saved: the text it encoded to, or
    /// none when it couldn't be encoded.
    fn save(shared: &Shared, host: &Host, owner: &Owner) -> Option<String> {
        let (live, label) = {
            let data = shared.data.borrow();
            let table = data.tables.get(owner)?;
            match &table.table {
                Some(live) => (live.clone(), table.label.clone()),
                None => return table.carried.clone(),
            }
        };
        let log = shared.platform.log();
        let encoded = match netherforge_luau::json::encode(
            host,
            &Value::Table(live),
            "data",
            "saved",
            &SavedHandles,
        ) {
            Ok(encoded) => encoded,
            Err(e) => {
                log.warn(format!("Couldn't save {label}'s data: {e:?}"));
                return None;
            }
        };
        let mut data = shared.data.borrow_mut();
        let table = data.tables.get_mut(owner)?;
        if encoded.problems != table.problems {
            for problem in &encoded.problems {
                log.warn(format!("Saving {label}'s data: {problem}"));
            }
            table.problems = encoded.problems;
        }
        let text = encoded.text?;
        if text.len() > MAX_BYTES {
            if !table.too_big {
                log.warn(too_big(&format!("{label}'s data"), text.len()));
            }
            table.too_big = true;
            return Some(text);
        }
        table.too_big = false;
        if table.saved.as_deref() != Some(text.as_str()) {
            table.saved = Some(text.clone());
            shared.store.put_data(owner, text.clone());
        }
        Some(text)
    }

    /// The owners of every table, for a pass over them.
    fn owners(shared: &Shared) -> Vec<Owner> {
        shared.data.borrow().tables.keys().cloned().collect()
    }

    /// `owner` is gone for good (a removed centity): its table goes. The store's row goes with the thing's.
    pub fn forget(shared: &Shared, owner: &Owner) {
        shared.data.borrow_mut().tables.remove(owner);
    }
}

/// Saved tables as a session's service: autosave, a player's on quit, every one at the end.
pub struct DataService {
    pub shared: Rc<Shared>,
    pub host: std::cell::RefCell<Option<Host>>,
}

impl DataService {
    /// Every table that changed; a removed centity's is let go of.
    fn save_all(&self, host: &Host) {
        for owner in ScriptData::owners(&self.shared) {
            if let Owner::Centity(id) = owner
                && !self
                    .shared
                    .centities
                    .borrow()
                    .get(id)
                    .is_some_and(|i| !i.removed)
            {
                ScriptData::forget(&self.shared, &owner);
                continue;
            }
            ScriptData::save(&self.shared, host, &owner);
        }
    }
}

impl Service for DataService {
    fn name(&self) -> &'static str {
        "saved data"
    }

    fn start(&self, room: &Room, _project: &crate::session::Define) {
        *self.host.borrow_mut() = Some(room.host().clone());
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        if room.scripts().tick_count().is_multiple_of(AUTOSAVE_EVERY)
            && let Some(host) = self.host.borrow().clone()
        {
            self.save_all(&host);
        }
    }

    /// They left: their table is saved now, after their `player_quit` handlers.
    fn player_quit(&self, player: &PlayerRef) {
        if let Some(host) = self.host.borrow().clone() {
            ScriptData::save(&self.shared, &host, &Owner::Player(player.uuid));
        }
    }

    /// The session is ending, after every script's unload (this service stops last): every table is saved,
    /// and its latest encoding kept for the next session's table to start from (even one too big to save).
    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let Some(host) = self.host.borrow_mut().take() else {
            return;
        };
        for owner in ScriptData::owners(&self.shared) {
            let text = ScriptData::save(&self.shared, &host, &owner);
            let mut data = self.shared.data.borrow_mut();
            if let Some(table) = data.tables.get_mut(&owner)
                && table.table.take().is_some()
            {
                table.carried = text.or_else(|| table.saved.clone());
            }
        }
        self.shared.store.commit();
    }
}

/// `centity <id>`, as a centity's table is named in the log.
pub fn centity_label(centity: &str, id: Uuid) -> String {
    format!("centity {centity} {}", uuid_text(&id))
}

/// For a store: what a table was saved as, for tests.
pub fn saved(store: &Store, owner: &Owner) -> Option<String> {
    store.data(owner)
}

/// `player:session()` (Kotlin's `SessionTables`): a plain table per online player, shared by every script and
/// never saved, made the first time a script asks while they're online and kept until they leave (after their
/// `player_quit` handlers, which still find it) or the session ends. A module's reload leaves it be.
pub struct SessionTablesService {
    pub shared: Rc<Shared>,
}

impl Service for SessionTablesService {
    fn name(&self) -> &'static str {
        "session tables"
    }

    fn player_quit(&self, player: &PlayerRef) {
        self.shared.session_tables.borrow_mut().remove(&player.uuid);
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        self.shared.session_tables.borrow_mut().clear();
    }
}
