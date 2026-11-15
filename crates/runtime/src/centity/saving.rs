//! What of centities outlives a session (Kotlin's half of `Centities` that talks to the store): each normal
//! instance's record (what it is, where it's anchored, which way it faces, which entities are its), staged
//! when it changes and written at the end of the tick; its place and facing again when the session stops.
//! A session starts by restoring every record: an instance again when its centity is there (its entities are
//! still in the world, persistent, and the first sync takes them up), an inert record when it isn't, kept
//! until the centity comes back. Temporary and natural instances are never stored.

use netherforge_platform::{Location, Platform, Uuid};

use super::{Centities, Instance, autoplay, health};
use crate::store::{InstanceRecord, Store};

/// Where a world's centities' records go in its own folder (beside the game's saved data, in our namespace), so they
/// travel with its files: a kept room's world saved on one server and opened on another (the control endpoint's
/// `/save` and `/rooms/open`). The store is what a server reads; this file is only written for a world leaving it and
/// read (from the map a tool copied it into, [`read_map_records`]) for one coming in.
pub const RECORDS_FILE: &str = "data/netherforge/centities.json";

/// The store's records of the centities in `world` written into its folder `dimension` (no file when it has none).
pub fn write_world_records(
    store: &Store,
    world: &str,
    dimension: &std::path::Path,
) -> Result<(), String> {
    let file = dimension.join(RECORDS_FILE);
    let records = store.world_records(world);
    let none = records["centities"].as_array().is_none_or(Vec::is_empty);
    let written = if none {
        match std::fs::remove_file(&file) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    } else {
        let partial = file.with_extension("json.partial");
        std::fs::create_dir_all(file.parent().expect("a folder"))
            .and_then(|()| std::fs::write(&partial, records.to_string()))
            .and_then(|()| std::fs::rename(&partial, &file))
    };
    written.map_err(|e| format!("couldn't write {}: {e}", file.display()))
}

/// Where a map (`worlds/<name>/` in a room's folder, as a tool copied a world: its `level.dat`, its own folder as the
/// map's overworld) has the world's own folder: the map layout the adapter installs from (Paper's
/// `WorldStorage.MAP_DIMENSION`).
pub const MAP_DIMENSION: &str = "dimensions/minecraft/overworld";

/// The records in map `map`'s world folder, if it has any, as the centities of world `world` (what it's brought in as).
/// Read from the map, not the world made from it: the server's import keeps only the game's own folders of it.
pub fn read_map_records(
    map: &std::path::Path,
    world: &str,
) -> Result<Vec<(InstanceRecord, Option<serde_json::Value>)>, String> {
    let file = map.join(MAP_DIMENSION).join(RECORDS_FILE);
    let text = match std::fs::read_to_string(&file) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(format!("couldn't read {}: {e}", file.display())),
        Ok(text) => text,
    };
    serde_json::from_str(&text)
        .map_err(|e| e.to_string())
        .and_then(|records| crate::store::records_from_json(world, &records))
        .map_err(|e| format!("{} isn't centities' records: {e}", file.display()))
}

/// A random UUID (version 4), as the server's own entities have.
pub fn random_uuid() -> Uuid {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid(u128::from_be_bytes(bytes))
}

impl Centities {
    /// The namespace the project's own centities are named in, for records that outlive it changing.
    pub fn set_home(&mut self, home: &str) {
        self.home = home.to_owned();
    }

    /// The namespace the project's own centities are named in.
    pub fn home(&self) -> &str {
        &self.home
    }

    /// A centity's name in full (`shop:turret`).
    fn full(&self, centity: &str) -> String {
        if centity.contains(':') {
            centity.to_owned()
        } else {
            format!("{}:{centity}", self.home)
        }
    }

    /// A full name as the project names it: its own centities bare.
    fn named(&self, full: &str) -> String {
        match full.split_once(':') {
            Some((ns, id)) if ns == self.home => id.to_owned(),
            _ => full.to_owned(),
        }
    }

    /// Instance `id`'s identity changed (spawned, removed, gone inert or back, its entities): the store hears
    /// at the end of the tick.
    pub fn changed(&mut self, id: Uuid) {
        self.changed.insert(id);
    }

    pub(crate) fn record(&self, instance: &Instance) -> InstanceRecord {
        InstanceRecord {
            id: instance.id,
            centity: self.full(instance.centity()),
            anchor: instance.anchor().clone(),
            yaw: instance.yaw(),
            displays: instance.displays.clone(),
            hitboxes: instance.hitboxes.clone(),
            anchor_entity: instance.anchor_entity,
            orphans: instance.orphans.clone(),
        }
    }

    fn put(&mut self, store: &Store, record: InstanceRecord) {
        self.stored
            .insert(record.id, (record.anchor.clone(), record.yaw));
        store.put_instance(record);
    }

    /// Stages what changed for the store: each instance (or inert record) as it is now, or its removal.
    pub fn flush(&mut self, store: &Store) {
        let changed: Vec<Uuid> = std::mem::take(&mut self.changed).into_iter().collect();
        for id in changed {
            let record = match self.instances.get(&id) {
                Some(i) if i.transient() => {
                    // A temporary instance is never in the store.
                    if self.stored.remove(&id).is_some() {
                        store.delete_instance(id);
                    }
                    continue;
                }
                Some(i) => Some(self.record(i)),
                None => self.inert.get(&id).cloned(),
            };
            match record {
                Some(record) => self.put(store, record),
                None => {
                    if self.stored.remove(&id).is_some() {
                        store.delete_instance(id);
                        store.delete_data(&crate::data::Owner::Centity(id));
                    }
                }
            }
        }
    }

    /// Every place and facing that changed since it was stored.
    pub fn save_placements(&mut self, store: &Store) {
        self.save_placements_of(store, &|_| true);
    }

    /// Every record the store has in the worlds `owns` says are this room's, as instances again (inert where their
    /// centity is gone). Their scripts aren't started: the session does that.
    pub fn restore(
        &mut self,
        platform: &dyn Platform,
        store: &Store,
        owns: &dyn Fn(&str) -> bool,
    ) -> Vec<Uuid> {
        let mut restored = Vec::new();
        for record in store.instances() {
            if !owns(&record.anchor.world) {
                continue;
            }
            self.stored
                .insert(record.id, (record.anchor.clone(), record.yaw));
            if let Some(id) = self.restore_one(platform, record) {
                restored.push(id);
            }
        }
        restored
    }

    /// One record as an instance, or kept inert while its centity is gone.
    fn restore_one(&mut self, platform: &dyn Platform, record: InstanceRecord) -> Option<Uuid> {
        let id = record.id;
        let name = self.named(&record.centity);
        let Some(definition) = self.definitions.get(&name).cloned() else {
            self.inert.insert(id, record);
            return None;
        };
        self.inert.shift_remove(&id);
        let mut instance = Instance::new(id, definition, record.anchor.clone(), record.yaw);
        instance.displays = record.displays.clone();
        instance.hitboxes = record.hitboxes.clone();
        instance.anchor_entity = record.anchor_entity;
        instance.orphans = record.orphans.clone();
        for (node, entity) in record.displays.iter().chain(record.hitboxes.iter()) {
            self.owners.insert(*entity, (id, node.clone()));
        }
        if let Some(anchor) = record.anchor_entity {
            self.owners.insert(anchor, (id, super::ANCHOR_NODE.into()));
        }
        instance.dirty = Some(self.dirty.clone());
        instance.reshaped();
        health::adopt(platform.game(), &mut instance);
        autoplay(&mut instance);
        self.instances.insert(id, instance);
        Some(id)
    }

    /// Instance `id`'s centity is gone: a normal one goes inert (out of the world, its entities left standing,
    /// its record kept), a temporary one goes for good.
    pub fn make_inert(&mut self, platform: &dyn Platform, id: Uuid) {
        let Some(instance) = self.instances.get(&id) else {
            return;
        };
        if instance.transient() {
            self.remove(platform, id);
            return;
        }
        let record = self.record(instance);
        if let Some(instance) = self.instances.shift_remove(&id) {
            for entity in instance.entity_ids() {
                self.owners.remove(&entity);
            }
        }
        self.grid.unfile(id);
        self.inert.insert(id, record);
        self.changed(id);
    }

    /// The inert records of the centities in `ids`, now that they're back: instances again.
    pub fn revive(
        &mut self,
        platform: &dyn Platform,
        ids: &std::collections::BTreeSet<String>,
    ) -> Vec<Uuid> {
        let back: Vec<InstanceRecord> = self
            .inert
            .values()
            .filter(|r| ids.contains(&self.named(&r.centity)))
            .cloned()
            .collect();
        let mut revived = Vec::new();
        for record in back {
            let id = record.id;
            if self.restore_one(platform, record).is_some() {
                self.changed(id);
                revived.push(id);
            }
        }
        revived
    }

    /// The inert records: instances whose centity is gone.
    pub fn inert(&self) -> impl Iterator<Item = &InstanceRecord> {
        self.inert.values()
    }

    /// Where instance `id` was last stored.
    pub fn stored(&self, id: Uuid) -> Option<&(Location, f64)> {
        self.stored.get(&id)
    }
}
