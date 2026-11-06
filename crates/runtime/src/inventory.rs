//! `Inventory` (Kotlin's `InventoryImpl`): a real inventory, read live. Everything answers nil, false or nothing
//! once it isn't there; a slot outside it, or a mistake in an item or a match, is an error. Taking, counting and
//! finding go through `items::ItemMatch` over the inventory's contents.
//!
//! A storage is on screen only where a menu shows it: its viewers are those windows', and a change to it shows in
//! every menu slot bound to it.

use std::collections::BTreeMap;

use netherforge_api::api::InventoryApi;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, unions, values};
use netherforge_platform::{InventoryRef, ItemData};

use crate::events::{inventory_ref, pack, uuid_text};
use crate::items::ItemMatch;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// `index` as a slot of an inventory of `size`; outside it is the script's mistake.
fn slot(index: i64, size: i32) -> ApiResult<i32> {
    if !(0..i64::from(size)).contains(&index) {
        return Err(err(format!(
            "slot {index} is outside this inventory: it has {size} slots, 0 to {}",
            size - 1
        )));
    }
    Ok(index as i32)
}

impl GameContext {
    /// A storage changed: the menu slots bound to it show what it has now.
    fn changed(&self, r#ref: &InventoryRef) {
        if let InventoryRef::Storage(storage) = r#ref {
            crate::menu::MenuContext {
                shared: self.shared.clone(),
                scripts: self.scripts.clone(),
            }
            .storage_changed(storage);
        }
    }

    fn item_match(&self, given: unions::StringOrItemMatch) -> ApiResult<ItemMatch> {
        let json = match given {
            unions::StringOrItemMatch::String(kind) => {
                values::ItemMatch(serde_json::Value::String(kind).to_string())
            }
            unions::StringOrItemMatch::ItemMatch(m) => m,
        };
        ItemMatch::of(&json, "match", self.shared.platform.game()).map_err(err)
    }

    fn contents_matching(
        &self,
        r#ref: InventoryRef,
        matching: &ItemMatch,
    ) -> Option<Vec<Option<ItemData>>> {
        self.shared
            .platform
            .inventories()
            .contents_with_filter(r#ref, matching.filter())
    }
}

impl InventoryApi for GameContext {
    fn exists(&self, this: &handles::Inventory) -> ApiResult<bool> {
        Ok(inventory_ref(this)
            .is_some_and(|r| self.shared.platform.inventories().kind(r).is_some()))
    }

    fn kind(&self, this: &handles::Inventory) -> ApiResult<Option<String>> {
        Ok(inventory_ref(this).and_then(|r| self.shared.platform.inventories().kind(r)))
    }

    fn size(&self, this: &handles::Inventory) -> ApiResult<i64> {
        Ok(inventory_ref(this)
            .and_then(|r| self.shared.platform.inventories().size(r))
            .map_or(0, i64::from))
    }

    fn holder(&self, this: &handles::Inventory) -> ApiResult<Option<Handle>> {
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(None);
        };
        let platform = &*self.shared.platform;
        if platform.inventories().kind(r#ref.clone()).is_none() {
            return Ok(None);
        }
        let player = |id| {
            platform.players().get(id).map(|p| {
                Handle::Player(handles::Player {
                    id: uuid_text(&p.uuid),
                })
            })
        };
        Ok(match r#ref {
            InventoryRef::Player(p) => player(p.player),
            InventoryRef::EnderChest(p) => player(p.player),
            InventoryRef::Storage(s) => player(s.player),
            InventoryRef::Entity(e) => {
                let lua = self.scripts.host().lua().clone();
                Some(Handle::Entity(self.shared.entity_by_id(&lua, e.entity)))
            }
            InventoryRef::Block(b) => Some(Handle::Block(handles::Block {
                world: b.world,
                position: pack(b.x, b.y, b.z),
            })),
        })
    }

    fn item(&self, this: &handles::Inventory, index: i64) -> ApiResult<Option<values::Item>> {
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(None);
        };
        let inventories = self.shared.platform.inventories();
        let Some(size) = inventories.size(r#ref.clone()) else {
            return Ok(None);
        };
        Ok(inventories
            .item(r#ref, slot(index, size)?)
            .map(|i| crate::items::to_value(&i)))
    }

    fn set_item(
        &self,
        this: &handles::Inventory,
        index: i64,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = match item {
            Some(item) => Some(
                crate::items::from_value(&item, "item", self.shared.platform.game())
                    .map_err(err)?,
            ),
            None => None,
        };
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(false);
        };
        let inventories = self.shared.platform.inventories();
        let Some(size) = inventories.size(r#ref.clone()) else {
            return Ok(false);
        };
        let set = inventories.set_item(r#ref.clone(), slot(index, size)?, item);
        self.changed(&r#ref);
        Ok(set)
    }

    fn items(&self, this: &handles::Inventory) -> ApiResult<BTreeMap<i64, values::Item>> {
        let contents = inventory_ref(this)
            .and_then(|r| self.shared.platform.inventories().contents(r))
            .unwrap_or_default();
        Ok(contents
            .into_iter()
            .enumerate()
            .filter_map(|(i, item)| Some((i as i64, crate::items::to_value(&item?))))
            .collect())
    }

    /// What didn't fit: all of it when the inventory isn't there.
    fn add_item(&self, this: &handles::Inventory, item: values::Item) -> ApiResult<i64> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        let count = i64::from(item.def.count.unwrap_or(1));
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(count);
        };
        let left = self.shared.platform.inventories().add(r#ref.clone(), item);
        self.changed(&r#ref);
        Ok(left.map_or(count, i64::from))
    }

    fn remove_item(
        &self,
        this: &handles::Inventory,
        r#match: unions::StringOrItemMatch,
        count: Option<i64>,
    ) -> ApiResult<i64> {
        let matching = self.item_match(r#match)?;
        if count.is_some_and(|c| c < 0) {
            return Err(err("count can't be negative"));
        }
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(0);
        };
        let Some(contents) = self.contents_matching(r#ref.clone(), &matching) else {
            return Ok(0);
        };
        let inventories = self.shared.platform.inventories();
        let taken = matching.remove(&contents, count, |slot, item| {
            inventories.set_item(r#ref.clone(), slot as i32, item);
        });
        self.changed(&r#ref);
        Ok(taken)
    }

    fn count_item(
        &self,
        this: &handles::Inventory,
        r#match: unions::StringOrItemMatch,
    ) -> ApiResult<i64> {
        let matching = self.item_match(r#match)?;
        Ok(inventory_ref(this)
            .and_then(|r| self.contents_matching(r, &matching))
            .map_or(0, |c| matching.count(&c)))
    }

    fn has_item(
        &self,
        this: &handles::Inventory,
        r#match: unions::StringOrItemMatch,
        count: Option<i64>,
    ) -> ApiResult<bool> {
        Ok(InventoryApi::count_item(self, this, r#match)? >= count.unwrap_or(1))
    }

    fn first_slot(
        &self,
        this: &handles::Inventory,
        r#match: unions::StringOrItemMatch,
    ) -> ApiResult<Option<i64>> {
        let matching = self.item_match(r#match)?;
        Ok(inventory_ref(this)
            .and_then(|r| self.contents_matching(r, &matching))
            .and_then(|c| matching.first(&c))
            .map(|s| s as i64))
    }

    fn clear(&self, this: &handles::Inventory) -> ApiResult<bool> {
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(false);
        };
        let cleared = self.shared.platform.inventories().clear(r#ref.clone());
        self.changed(&r#ref);
        Ok(cleared)
    }

    fn viewers(&self, this: &handles::Inventory) -> ApiResult<Vec<handles::Player>> {
        let Some(r#ref) = inventory_ref(this) else {
            return Ok(Vec::new());
        };
        let platform = &*self.shared.platform;
        // A storage is on screen only where a menu shows it.
        let viewers = match &r#ref {
            InventoryRef::Storage(storage) => crate::menu::MenuContext {
                shared: self.shared.clone(),
                scripts: self.scripts.clone(),
            }
            .viewers_of(storage),
            _ => platform.inventories().viewers(r#ref),
        };
        Ok(viewers
            .into_iter()
            .filter_map(|id| platform.players().get(id))
            .map(|p| handles::Player {
                id: uuid_text(&p.uuid),
            })
            .collect())
    }
}
