//! The runtime's own state on one server (Kotlin's `Store` and `Database`): `plugins/NetherForge/netherforge.db`,
//! one SQLite file, its schema the very migrations the Kotlin runtime applies (one file for both runtimes, so
//! either reads what the other wrote). So far what centities keep: spawned instances (the instance index) and
//! `centity:data()` tables; the store's other tables come with their areas.
//!
//! Services read what they need when they start and stage what changes; `commit` (the end of every tick)
//! writes what's staged as one transaction, each write under a key so a value changed ten times in a tick is
//! written once, as it ended. A write that fails is rolled back alone (a savepoint each) and said in the log;
//! the rest of its batch is written.
//!
//! Not yet: the store's own lane (Kotlin writes on a worker thread; here the commit is the main thread's, a
//! transaction a tick) and moving a file that isn't a database aside.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::path::Path;

use indexmap::IndexMap;
use netherforge_platform::{Location, Uuid};
use rusqlite::{Connection, OptionalExtension, params};

use crate::events::{parse_uuid, uuid_text};

/// The file in the plugin's folder.
pub const FILE: &str = "netherforge.db";
/// The store's migrations' scope in the `migrations` table.
const SCOPE: &str = "netherforge";
/// Every migration, in order: the Kotlin runtime's own files.
const MIGRATIONS: &[(i64, &str, &str)] = &[(
    1,
    "init",
    include_str!(
        "../../../apps/plugin/runtime/src/main/resources/dev/netherforge/plugin/store/migrations/001_init.sql"
    ),
)];

/// A spawned centity as the store keeps it, so it comes back after a restart (Kotlin's `InstanceRecord`).
#[derive(Clone, Debug, PartialEq)]
pub struct InstanceRecord {
    pub id: Uuid,
    /// The centity, named in full (`shop:turret`).
    pub centity: String,
    /// The anchor; its own facing is always 0.
    pub anchor: Location,
    pub yaw: f64,
    pub displays: IndexMap<String, Uuid>,
    pub hitboxes: IndexMap<String, Uuid>,
    pub anchor_entity: Option<Uuid>,
    pub orphans: Vec<Uuid>,
}

type Write = Box<dyn Fn(&Connection) -> rusqlite::Result<()>>;

pub struct Store {
    connection: Connection,
    staged: RefCell<IndexMap<String, Write>>,
    /// What went wrong writing, for the log: taken by whoever logs.
    failures: RefCell<Vec<String>>,
    written: std::cell::Cell<u64>,
}

impl Store {
    /// The store in `file`, made and migrated as needed.
    pub fn open(file: &Path) -> Result<Store, String> {
        let connection = Connection::open(file).map_err(|e| format!("{}: {e}", file.display()))?;
        Store::with(connection)
    }

    /// A store nothing outlives: what a session with no folder of its own keeps.
    pub fn in_memory() -> Store {
        Store::with(Connection::open_in_memory().expect("SQLite in memory")).expect("migrated")
    }

    fn with(connection: Connection) -> Result<Store, String> {
        let store = Store {
            connection,
            staged: RefCell::default(),
            failures: RefCell::default(),
            written: std::cell::Cell::new(0),
        };
        store.migrate()?;
        Ok(store)
    }

    fn migrate(&self) -> Result<(), String> {
        let c = &self.connection;
        c.execute_batch(
            "CREATE TABLE IF NOT EXISTS migrations (scope TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, applied TEXT NOT NULL, PRIMARY KEY (scope, version))",
        )
        .map_err(|e| e.to_string())?;
        for (version, name, sql) in MIGRATIONS {
            let done: Option<i64> = c
                .query_row(
                    "SELECT version FROM migrations WHERE scope = ?1 AND version = ?2",
                    params![SCOPE, version],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| e.to_string())?;
            if done.is_some() {
                continue;
            }
            let apply = || -> rusqlite::Result<()> {
                c.execute_batch("BEGIN")?;
                c.execute_batch(sql)?;
                c.execute(
                    "INSERT INTO migrations (scope, version, name, applied) VALUES (?1, ?2, ?3, datetime('now'))",
                    params![SCOPE, version, name],
                )?;
                c.execute_batch("COMMIT")
            };
            if let Err(e) = apply() {
                let _ = c.execute_batch("ROLLBACK");
                return Err(format!("{version:03}_{name}.sql: {e}"));
            }
        }
        Ok(())
    }

    /// Stages `write` under `key`, in place of anything staged under it before.
    fn stage(&self, key: String, write: Write) {
        self.staged.borrow_mut().insert(key, write);
    }

    /// Writes everything staged as one transaction, each write rolled back alone when it fails.
    pub fn commit(&self) {
        let staged = std::mem::take(&mut *self.staged.borrow_mut());
        if staged.is_empty() {
            return;
        }
        let c = &self.connection;
        if let Err(e) = c.execute_batch("BEGIN") {
            self.failures
                .borrow_mut()
                .push(format!("The store couldn't write: {e}"));
            return;
        }
        for (key, write) in staged {
            let _ = c.execute_batch("SAVEPOINT one");
            match write(c) {
                Ok(()) => {
                    let _ = c.execute_batch("RELEASE one");
                    self.written.set(self.written.get() + 1);
                }
                Err(e) => {
                    let _ = c.execute_batch("ROLLBACK TO one; RELEASE one");
                    self.failures
                        .borrow_mut()
                        .push(format!("The store couldn't write {key}: {e}"));
                }
            }
        }
        if let Err(e) = c.execute_batch("COMMIT") {
            self.failures
                .borrow_mut()
                .push(format!("The store couldn't write: {e}"));
        }
    }

    /// What went wrong writing since the last call.
    pub fn take_failures(&self) -> Vec<String> {
        std::mem::take(&mut self.failures.borrow_mut())
    }

    /// Writes committed so far.
    pub fn written(&self) -> u64 {
        self.written.get()
    }

    // ---- instances ----

    /// Every spawned centity the store knows, in the order they were first put.
    pub fn instances(&self) -> Vec<InstanceRecord> {
        self.commit();
        let read = || -> rusqlite::Result<Vec<InstanceRecord>> {
            let mut entities: std::collections::HashMap<
                String,
                Vec<(String, String, Option<String>)>,
            > = Default::default();
            let mut rows = self
                .connection
                .prepare("SELECT instance, entity, role, node FROM instance_entities")?;
            for row in rows.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })? {
                let (instance, entity, role, node) = row?;
                entities
                    .entry(instance)
                    .or_default()
                    .push((entity, role, node));
            }
            let mut out = Vec::new();
            let mut rows = self
                .connection
                .prepare("SELECT id, centity, world, x, y, z, yaw FROM instances ORDER BY rowid")?;
            for row in rows.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, f64>(3)?,
                    r.get::<_, f64>(4)?,
                    r.get::<_, f64>(5)?,
                    r.get::<_, f64>(6)?,
                ))
            })? {
                let (id, centity, world, x, y, z, yaw) = row?;
                let Some(uuid) = parse_uuid(&id) else {
                    continue;
                };
                let mine = entities.remove(&id).unwrap_or_default();
                let role = |role: &'static str| mine.iter().filter(move |(_, r, _)| r == role);
                out.push(InstanceRecord {
                    id: uuid,
                    centity,
                    anchor: Location {
                        world,
                        x,
                        y,
                        z,
                        yaw: 0.0,
                        pitch: 0.0,
                    },
                    yaw: if yaw.is_finite() { yaw } else { 0.0 },
                    displays: role("display")
                        .filter_map(|(e, _, n)| {
                            Some((n.clone().unwrap_or_default(), parse_uuid(e)?))
                        })
                        .collect(),
                    hitboxes: role("hitbox")
                        .filter_map(|(e, _, n)| {
                            Some((n.clone().unwrap_or_default(), parse_uuid(e)?))
                        })
                        .collect(),
                    anchor_entity: role("anchor").find_map(|(e, _, _)| parse_uuid(e)),
                    orphans: role("orphan")
                        .filter_map(|(e, _, _)| parse_uuid(e))
                        .collect(),
                });
            }
            Ok(out)
        };
        read().unwrap_or_else(|e| {
            self.failures
                .borrow_mut()
                .push(format!("The store couldn't read the centities: {e}"));
            Vec::new()
        })
    }

    /// The instance as `record` says, its entities included.
    pub fn put_instance(&self, record: InstanceRecord) {
        let key = format!("instances {}", uuid_text(&record.id));
        self.stage(
            key,
            Box::new(move |c| {
                let id = uuid_text(&record.id);
                let a = &record.anchor;
                c.execute(
                    "INSERT INTO instances (id, centity, world, x, y, z, yaw) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) \
                     ON CONFLICT (id) DO UPDATE SET centity = excluded.centity, world = excluded.world, \
                     x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw",
                    params![id, record.centity, a.world, a.x, a.y, a.z, record.yaw],
                )?;
                c.execute("DELETE FROM instance_entities WHERE instance = ?1", params![id])?;
                let mut insert = c.prepare("INSERT INTO instance_entities (instance, entity, role, node) VALUES (?1, ?2, ?3, ?4)")?;
                for (node, entity) in &record.displays {
                    insert.execute(params![id, uuid_text(entity), "display", node])?;
                }
                for (node, entity) in &record.hitboxes {
                    insert.execute(params![id, uuid_text(entity), "hitbox", node])?;
                }
                if let Some(entity) = record.anchor_entity {
                    insert.execute(params![id, uuid_text(&entity), "anchor", None::<String>])?;
                }
                for entity in &record.orphans {
                    insert.execute(params![id, uuid_text(entity), "orphan", None::<String>])?;
                }
                Ok(())
            }),
        );
    }

    /// The instance is gone for good.
    pub fn delete_instance(&self, id: Uuid) {
        let text = uuid_text(&id);
        self.stage(
            format!("instances {text}"),
            Box::new(move |c| {
                c.execute(
                    "DELETE FROM instance_entities WHERE instance = ?1",
                    params![text],
                )?;
                c.execute("DELETE FROM instances WHERE id = ?1", params![text])?;
                Ok(())
            }),
        );
    }

    // ---- saved tables ----

    /// `owner`'s saved table as last saved (the codec's JSON), or none when it never was.
    pub fn data(&self, owner: &crate::data::Owner) -> Option<String> {
        use crate::data::Owner;
        self.commit();
        let row = match owner {
            Owner::Centity(id) => self.connection.query_row(
                "SELECT value FROM centity_data WHERE instance = ?1",
                params![uuid_text(id)],
                |r| r.get(0),
            ),
            Owner::Player(id) => self.connection.query_row(
                "SELECT value FROM player_data WHERE player = ?1",
                params![uuid_text(id)],
                |r| r.get(0),
            ),
            Owner::Named { namespace, name } => self.connection.query_row(
                "SELECT value FROM named_data WHERE namespace = ?1 AND name = ?2",
                params![namespace, name],
                |r| r.get(0),
            ),
        };
        row.optional().ok().flatten()
    }

    /// The staging key of `owner`'s row, so a later write of it replaces an earlier one.
    fn data_key(owner: &crate::data::Owner) -> String {
        use crate::data::Owner;
        match owner {
            Owner::Centity(id) => format!("centity_data {}", uuid_text(id)),
            Owner::Player(id) => format!("player_data {}", uuid_text(id)),
            Owner::Named { namespace, name } => format!("named_data {namespace}:{name}"),
        }
    }

    pub fn put_data(&self, owner: &crate::data::Owner, text: String) {
        use crate::data::Owner;
        let owner = owner.clone();
        self.stage(
            Store::data_key(&owner),
            Box::new(move |c| {
                match &owner {
                    Owner::Centity(id) => c.execute(
                        "INSERT INTO centity_data (instance, value) VALUES (?1, ?2) ON CONFLICT (instance) DO UPDATE SET value = excluded.value",
                        params![uuid_text(id), text],
                    )?,
                    Owner::Player(id) => c.execute(
                        "INSERT INTO player_data (player, value) VALUES (?1, ?2) ON CONFLICT (player) DO UPDATE SET value = excluded.value",
                        params![uuid_text(id), text],
                    )?,
                    Owner::Named { namespace, name } => c.execute(
                        "INSERT INTO named_data (namespace, name, value) VALUES (?1, ?2, ?3) ON CONFLICT (namespace, name) DO UPDATE SET value = excluded.value",
                        params![namespace, name, text],
                    )?,
                };
                Ok(())
            }),
        );
    }

    pub fn delete_data(&self, owner: &crate::data::Owner) {
        use crate::data::Owner;
        let owner = owner.clone();
        self.stage(
            Store::data_key(&owner),
            Box::new(move |c| {
                match &owner {
                    Owner::Centity(id) => c.execute(
                        "DELETE FROM centity_data WHERE instance = ?1",
                        params![uuid_text(id)],
                    )?,
                    Owner::Player(id) => c.execute(
                        "DELETE FROM player_data WHERE player = ?1",
                        params![uuid_text(id)],
                    )?,
                    Owner::Named { namespace, name } => c.execute(
                        "DELETE FROM named_data WHERE namespace = ?1 AND name = ?2",
                        params![namespace, name],
                    )?,
                };
                Ok(())
            }),
        );
    }
}

impl Store {
    /// Every node `namespace`'s scripts set, by player.
    pub fn permissions_of(&self, namespace: &str) -> BTreeMap<Uuid, BTreeMap<String, bool>> {
        self.commit();
        let mut out: BTreeMap<Uuid, BTreeMap<String, bool>> = BTreeMap::new();
        let Ok(mut statement) = self
            .connection
            .prepare("SELECT player, node, value FROM permissions WHERE namespace = ?1")
        else {
            return out;
        };
        let rows = statement.query_map(params![namespace], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
            ))
        });
        for (player, node, value) in rows.into_iter().flatten().flatten() {
            if let Some(player) = crate::events::parse_uuid(&player) {
                out.entry(player).or_default().insert(node, value != 0);
            }
        }
        out
    }

    /// Sets `node` on `player` for `namespace`, or takes it off when `value` is none.
    pub fn set_permission(&self, namespace: &str, player: Uuid, node: &str, value: Option<bool>) {
        let (namespace, player, node) = (namespace.to_owned(), uuid_text(&player), node.to_owned());
        self.stage(
            format!("permissions {namespace} {player} {node}"),
            Box::new(move |c| {
                match value {
                    None => c.execute(
                        "DELETE FROM permissions WHERE namespace = ?1 AND player = ?2 AND node = ?3",
                        params![namespace, player, node],
                    )?,
                    Some(value) => c.execute(
                        "INSERT INTO permissions (namespace, player, node, value) VALUES (?1, ?2, ?3, ?4) ON CONFLICT (namespace, player, node) DO UPDATE SET value = excluded.value",
                        params![namespace, player, node, i64::from(value)],
                    )?,
                };
                Ok(())
            }),
        );
    }
}

impl Store {
    /// When each of `namespace`'s schedules last ran (Unix milliseconds), by id.
    pub fn schedule_runs(&self, namespace: &str) -> BTreeMap<String, i64> {
        self.commit();
        let Ok(mut statement) = self
            .connection
            .prepare("SELECT id, last_run FROM schedule_runs WHERE namespace = ?1")
        else {
            return BTreeMap::new();
        };
        statement
            .query_map(params![namespace], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
            })
            .into_iter()
            .flatten()
            .flatten()
            .collect()
    }

    /// `namespace`'s schedule `id` last ran at `time` (Unix milliseconds).
    pub fn schedule_ran(&self, namespace: &str, id: &str, time: i64) {
        let (namespace, id) = (namespace.to_owned(), id.to_owned());
        self.stage(
            format!("schedule_runs {namespace} {id}"),
            Box::new(move |c| {
                c.execute(
                    "INSERT INTO schedule_runs (namespace, id, last_run) VALUES (?1, ?2, ?3) ON CONFLICT (namespace, id) DO UPDATE SET last_run = excluded.last_run",
                    params![namespace, id, time],
                )?;
                Ok(())
            }),
        );
    }
}

/// The numbers screens' maps keep for good (Kotlin's `Store.ScreenMaps`): a project screen's, and a keyed script
/// screen's, so a map item or frame of one shows it again after a restart; and the next number to give, past every
/// one ever given, so none is given twice.
impl Store {
    /// Every kept number, by (namespace, screen key), each by its tile's stable number.
    pub fn screen_maps(&self) -> BTreeMap<(String, String), BTreeMap<i64, i64>> {
        self.commit();
        let Ok(mut statement) = self
            .connection
            .prepare("SELECT namespace, screen, tile, map FROM screen_maps")
        else {
            return BTreeMap::new();
        };
        let mut out: BTreeMap<(String, String), BTreeMap<i64, i64>> = BTreeMap::new();
        let rows = statement.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, i64>(3)?,
            ))
        });
        for (namespace, screen, tile, map) in rows.into_iter().flatten().flatten() {
            out.entry((namespace, screen))
                .or_default()
                .insert(tile, map);
        }
        out
    }

    /// The next map number screens may take, if one was kept.
    pub fn screen_map_next(&self) -> Option<i64> {
        self.commit();
        self.connection
            .query_row("SELECT next FROM screen_map_next WHERE id = 0", [], |r| {
                r.get(0)
            })
            .ok()
    }

    pub fn set_screen_map_next(&self, next: i64) {
        self.stage(
            "screen_map_next".to_owned(),
            Box::new(move |c| {
                c.execute(
                    "INSERT INTO screen_map_next (id, next) VALUES (0, ?1) ON CONFLICT (id) DO UPDATE SET next = excluded.next",
                    params![next],
                )?;
                Ok(())
            }),
        );
    }

    pub fn put_screen_map(&self, namespace: &str, screen: &str, tile: i64, map: i64) {
        let (namespace, screen) = (namespace.to_owned(), screen.to_owned());
        self.stage(
            format!("screen_maps {namespace} {screen} {tile}"),
            Box::new(move |c| {
                c.execute(
                    "INSERT INTO screen_maps (namespace, screen, tile, map) VALUES (?1, ?2, ?3, ?4) ON CONFLICT (namespace, screen, tile) DO UPDATE SET map = excluded.map",
                    params![namespace, screen, tile, map],
                )?;
                Ok(())
            }),
        );
    }

    pub fn forget_screen_map(&self, namespace: &str, screen: &str, tile: i64) {
        let (namespace, screen) = (namespace.to_owned(), screen.to_owned());
        self.stage(
            format!("screen_maps {namespace} {screen} {tile}"),
            Box::new(move |c| {
                c.execute(
                    "DELETE FROM screen_maps WHERE namespace = ?1 AND screen = ?2 AND tile = ?3",
                    params![namespace, screen, tile],
                )?;
                Ok(())
            }),
        );
    }
}

/// What players a cutscene holds were before it (Kotlin's `Store.CutsceneStates`): a row from the first of a run of
/// cutscenes until the player is put back, so a crash can't lose it.
impl Store {
    /// Every player a cutscene held, and what they were before it.
    pub fn cutscene_states(&self) -> BTreeMap<Uuid, crate::cutscenes::PlayerState> {
        self.commit();
        let Ok(mut statement) = self.connection.prepare(
            "SELECT player, world, x, y, z, yaw, pitch, game_mode, can_fly, flying, spectating FROM cutscene_states",
        ) else {
            return BTreeMap::new();
        };
        let rows = statement.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                Location {
                    world: r.get(1)?,
                    x: r.get(2)?,
                    y: r.get(3)?,
                    z: r.get(4)?,
                    yaw: r.get(5)?,
                    pitch: r.get(6)?,
                },
                r.get::<_, String>(7)?,
                r.get::<_, i64>(8)?,
                r.get::<_, i64>(9)?,
                r.get::<_, Option<String>>(10)?,
            ))
        });
        let mut out = BTreeMap::new();
        for (player, location, game_mode, can_fly, flying, spectating) in
            rows.into_iter().flatten().flatten()
        {
            let Some(player) = parse_uuid(&player) else {
                continue;
            };
            out.insert(
                player,
                crate::cutscenes::PlayerState {
                    location,
                    game_mode,
                    can_fly: can_fly != 0,
                    flying: flying != 0,
                    spectating: spectating.as_deref().and_then(parse_uuid),
                },
            );
        }
        out
    }

    /// `player` is held by a cutscene, and was `state` before it.
    pub fn put_cutscene_state(&self, player: Uuid, state: &crate::cutscenes::PlayerState) {
        let player = uuid_text(&player);
        let state = state.clone();
        self.stage(
            format!("cutscene_states {player}"),
            Box::new(move |c| {
                let at = &state.location;
                c.execute(
                    "INSERT INTO cutscene_states (player, world, x, y, z, yaw, pitch, game_mode, can_fly, flying, spectating) \
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11) ON CONFLICT (player) DO UPDATE SET world = excluded.world, \
                     x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, \
                     game_mode = excluded.game_mode, can_fly = excluded.can_fly, flying = excluded.flying, \
                     spectating = excluded.spectating",
                    params![
                        player,
                        at.world,
                        at.x,
                        at.y,
                        at.z,
                        at.yaw,
                        at.pitch,
                        state.game_mode,
                        state.can_fly,
                        state.flying,
                        state.spectating.as_ref().map(uuid_text),
                    ],
                )?;
                Ok(())
            }),
        );
    }

    /// `player` is back as they were: nothing is left to put back.
    pub fn delete_cutscene_state(&self, player: Uuid) {
        let player = uuid_text(&player);
        self.stage(
            format!("cutscene_states {player}"),
            Box::new(move |c| {
                c.execute(
                    "DELETE FROM cutscene_states WHERE player = ?1",
                    params![player],
                )?;
                Ok(())
            }),
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_instance_is_kept_and_forgotten() {
        let store = Store::in_memory();
        let record = InstanceRecord {
            id: Uuid(42),
            centity: "demo:lamp".into(),
            anchor: Location {
                world: "world".into(),
                x: 1.0,
                y: 64.0,
                z: 2.0,
                yaw: 0.0,
                pitch: 0.0,
            },
            yaw: 90.0,
            displays: IndexMap::from([("base".into(), Uuid(7))]),
            hitboxes: IndexMap::new(),
            anchor_entity: Some(Uuid(6)),
            orphans: vec![Uuid(9)],
        };
        store.put_instance(record.clone());
        // Staged twice in one tick: written once, as it ended.
        store.put_instance(InstanceRecord {
            yaw: 180.0,
            ..record.clone()
        });
        store.commit();
        assert_eq!(store.written(), 1);
        assert_eq!(
            store.instances(),
            [InstanceRecord {
                yaw: 180.0,
                ..record.clone()
            }]
        );
        store.put_data(
            &crate::data::Owner::Centity(Uuid(42)),
            r#"{"coins":3}"#.into(),
        );
        assert_eq!(
            store
                .data(&crate::data::Owner::Centity(Uuid(42)))
                .as_deref(),
            Some(r#"{"coins":3}"#)
        );
        store.delete_instance(Uuid(42));
        store.commit();
        assert!(store.instances().is_empty());
    }
}
