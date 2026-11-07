//! The tables `block:data()` hands out (Kotlin's `BlockData`): one per block position, the same table to every
//! script, kept for as long as the session runs.
//!
//! A table is read from its chunk the first time a script asks for it (JSON the adapter keeps in the chunk's
//! persistent data, `BlockOps::data`) and written back when the chunk unloads, when the world saves and when the
//! session ends, only when it changed. A table outlives its chunk being unloaded: a script holding it keeps the
//! same table, written again once the chunk is back. It holds what every `data()` table may, through the same
//! codec and handle tags as `entity:data()`, and an empty table is no data.

use std::collections::{BTreeSet, HashMap};
use std::rc::Rc;

use mlua::Value;
use netherforge_platform::{BlockVector, Platform};

use crate::Room;
use crate::session::Service;
use crate::shared::Shared;

type Key = (String, i32, i32, i32);

/// A table scripts hold, and the JSON it was last read or saved as (none: nothing stored).
struct Held {
    table: mlua::Table,
    saved: Option<String>,
    /// What the last save couldn't keep, so the same problems aren't logged at every save.
    problems: Vec<String>,
    /// The last save found it past the 1 MiB a table may take: logged once while it stays so.
    too_big: bool,
}

#[derive(Default)]
pub struct BlockData {
    held: HashMap<Key, Held>,
}

impl BlockData {
    /// Whether scripts gave the block at a position a table: one is held, or one is stored.
    pub fn has(&self, platform: &dyn Platform, world: &str, x: i32, y: i32, z: i32) -> bool {
        self.held.contains_key(&(world.to_owned(), x, y, z))
            || platform.blocks().data(world.to_owned(), x, y, z).is_some()
    }

    /// The positions in a loaded chunk that scripts gave a table: held, or stored.
    pub fn tables_in(
        &self,
        platform: &dyn Platform,
        world: &str,
        chunk_x: i32,
        chunk_z: i32,
    ) -> BTreeSet<(i32, i32, i32)> {
        let mut found: BTreeSet<(i32, i32, i32)> = self
            .held
            .keys()
            .filter(|(w, x, _, z)| w == world && x >> 4 == chunk_x && z >> 4 == chunk_z)
            .map(|(_, x, y, z)| (*x, *y, *z))
            .collect();
        for BlockVector { x, y, z } in platform
            .blocks()
            .with_data(world.to_owned(), chunk_x, chunk_z)
            .unwrap_or_default()
        {
            found.insert((x, y, z));
        }
        found
    }

    /// A custom block went: its table goes with it, from the world and from what scripts hold (a script still
    /// holding the table keeps one that's no longer saved; the position's next one starts empty).
    pub fn discard(&mut self, platform: &dyn Platform, world: &str, x: i32, y: i32, z: i32) {
        self.held.remove(&(world.to_owned(), x, y, z));
        platform.blocks().set_data(world.to_owned(), x, y, z, None);
    }
}

impl crate::world::GameContext {
    /// The table for a block position, or nil when its chunk isn't loaded (and it hasn't been read before).
    pub(crate) fn block_data(&self, world: &str, x: i32, y: i32, z: i32) -> mlua::Result<Value> {
        let key = (world.to_owned(), x, y, z);
        if let Some(held) = self.shared.block_data.borrow().held.get(&key) {
            return Ok(Value::Table(held.table.clone()));
        }
        let blocks = self.shared.platform.blocks();
        if blocks.get(world.to_owned(), x, y, z).is_none() {
            return Ok(Value::Nil);
        }
        let host = self.scripts.host();
        let lua = host.lua();
        let saved = blocks.data(world.to_owned(), x, y, z);
        let table = match saved
            .as_deref()
            .map(|t| netherforge_luau::json::decode(lua, t, &netherforge_api::handle::SavedHandles))
        {
            Some(Ok(Value::Table(t))) => t,
            _ => lua.create_table()?,
        };
        self.shared.block_data.borrow_mut().held.insert(
            key,
            Held {
                table: table.clone(),
                saved,
                problems: Vec::new(),
                too_big: false,
            },
        );
        Ok(Value::Table(table))
    }
}

/// The block data service: writes the tables back as their chunks unload, their world saves and the session ends.
pub struct BlockDataService {
    pub shared: Rc<Shared>,
    pub host: std::cell::RefCell<Option<netherforge_luau::Host>>,
}

impl BlockDataService {
    fn save(&self, which: &dyn Fn(&Key) -> bool) {
        let Some(host) = self.host.borrow().clone() else {
            return;
        };
        let platform = &*self.shared.platform;
        let tables: Vec<(Key, mlua::Table)> = self
            .shared
            .block_data
            .borrow()
            .held
            .iter()
            .filter(|(key, _)| which(key))
            .map(|(key, held)| (key.clone(), held.table.clone()))
            .collect();
        for (key, table) in tables {
            let (world, x, y, z) = &key;
            let place = format!("block:data() at {world} {x} {y} {z}");
            let encoded = match netherforge_luau::json::encode(
                &host,
                &Value::Table(table),
                "data",
                "saved",
                &netherforge_api::handle::SavedHandles,
            ) {
                Ok(encoded) => encoded,
                Err(e) => {
                    platform.log().warn(format!("{place} wasn't saved: {e:?}"));
                    continue;
                }
            };
            let mut data = self.shared.block_data.borrow_mut();
            let Some(held) = data.held.get_mut(&key) else {
                continue;
            };
            if encoded.problems != held.problems {
                for problem in &encoded.problems {
                    platform.log().warn(format!("Saving {place}: {problem}"));
                }
                held.problems = encoded.problems;
            }
            let Some(text) = encoded.text else { continue };
            // An empty table is no data: the block's entry goes.
            let json = (text != "{}").then_some(text);
            if json == held.saved {
                continue;
            }
            if let Some(json) = &json
                && json.len() > crate::data::MAX_BYTES
            {
                if !held.too_big {
                    platform
                        .log()
                        .warn(crate::data::too_big(&place, json.len()));
                }
                held.too_big = true;
                continue;
            }
            held.too_big = false;
            if platform
                .blocks()
                .set_data(world.clone(), *x, *y, *z, json.clone())
            {
                held.saved = json;
            }
        }
    }
}

impl Service for BlockDataService {
    fn name(&self) -> &'static str {
        "block data"
    }

    fn start(&self, room: &Room, _project: &crate::session::Define) {
        *self.host.borrow_mut() = Some(room.host().clone());
    }

    /// Writes back the tables in one chunk, which is about to unload.
    fn chunk_unloading(&self, world: &str, chunk_x: i32, chunk_z: i32) {
        self.save(&|(w, x, _, z)| w == world && x >> 4 == chunk_x && z >> 4 == chunk_z);
    }

    fn world_saving(&self, world: &str) {
        self.save(&|(w, _, _, _)| w == world);
    }

    /// Writes back every table, then lets them go: the session is ending, after every script's `unload`.
    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        self.save(&|_| true);
        *self.shared.block_data.borrow_mut() = BlockData::default();
        *self.host.borrow_mut() = None;
    }
}
