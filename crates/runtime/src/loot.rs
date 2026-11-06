//! Loot tables (Kotlin's `LootTables` and `NfLootImpl`): the project's `loot/<id>.json` and its packages', rolled
//! by format's `LootRoller` from a generator seeded per roll; a game table a pick lands on is rolled by the server
//! (`LootOps`) with a seed from the same draws, so a seed gives the same items every time. A table is data: a reload
//! swaps it and the next roll uses it; a file with errors keeps its last good version.

use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use netherforge_api::api::NfLootApi;
use netherforge_api::{ApiError, ApiResult, Caller, handles, shapes, values};
use netherforge_format::game::{self, GameData, Registry};
use netherforge_format::kind::loot::LootTableFile;
use netherforge_format::kind::loot_roll::{self, LootContext, LootDrop};
use netherforge_format::kind::recipe::Ingredient;
use netherforge_format::random::Random;
use netherforge_format::reference::resolve_any;
use netherforge_platform::{GameLootContext, InventoryRef, ItemData, Location, Platform, Uuid};

use crate::Room;
use crate::events::{inventory_ref, parse_uuid};
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

const KIND: &str = "loot_table";
/// A stack's size when its item doesn't say.
const MOST_IN_A_STACK: i32 = 64;
/// A player's hotbar and main inventory: slots 0 to 35.
const PLAYER_MAIN: usize = 36;

/// What a roll is for: who and what its tables' conditions ask about, and where a game table is rolled.
#[derive(Clone, Debug, Default)]
pub struct LootRoll {
    /// The player behind it: who killed the mob, broke the block or opened the chest.
    pub player: Option<Uuid>,
    /// What they killed or broke it with.
    pub tool: Option<ItemData>,
    pub luck: f64,
    /// The entity whose loot it is.
    pub looted: Option<Uuid>,
    /// Where a game table is rolled; else the player's place, else the looted entity's.
    pub location: Option<Location>,
}

/// The session's loot tables, as the project names them (`treasure`, `library:junk`).
#[derive(Default)]
pub struct LootTables {
    tables: BTreeMap<String, LootTableFile>,
    /// The project's namespace, which references naming none are in.
    home: String,
}

impl LootTables {
    /// The table `reference` names as the server knows it (`treasure`, `library:junk`; the project's own written
    /// `showcase:treasure` too), or none when no table running is called that.
    pub fn id_of(&self, reference: &str) -> Option<String> {
        let id = resolve_any(&self.home, reference)?.relative_to(&self.home);
        self.tables.contains_key(&id).then_some(id)
    }
}

/// What a roll's conditions ask, answered from what the script said.
struct Conditions<'a> {
    roll: &'a LootRoll,
    home: &'a str,
    game: &'a dyn GameData,
}

impl LootContext for Conditions<'_> {
    fn luck(&self) -> f64 {
        self.roll.luck
    }

    fn has_player(&self) -> bool {
        self.roll.player.is_some()
    }

    /// As a recipe matches an ingredient: a project item by its id, a vanilla kind or tag only a stack that
    /// isn't one.
    fn tool_is(&self, tool: &Ingredient) -> bool {
        let Some(held) = self.roll.tool.as_ref().map(|t| &t.def) else {
            return false;
        };
        let named = |text: &str| resolve_any(self.home, text);
        if let Ingredient::Item(wanted) = tool {
            return held.item.as_deref().is_some_and(|project| {
                named(project).is_some() && named(project) == named(wanted)
            });
        }
        if held.item.is_some() || held.kind.is_empty() {
            return false;
        }
        let kind = game::normalize(&held.kind);
        match tool {
            Ingredient::Tag(tag) => self
                .game
                .tag(Registry::Item.key(), &game::normalize(tag))
                .is_some_and(|t| t.contains(&kind)),
            Ingredient::Kind(wanted) => game::normalize(wanted) == kind,
            Ingredient::Item(_) => false,
        }
    }

    fn enchantment_level(&self, enchantment: &str) -> i32 {
        let Some(tool) = &self.roll.tool else {
            return 0;
        };
        tool.def
            .enchantments
            .as_ref()
            .and_then(|e| {
                e.iter()
                    .find(|(k, _)| game::normalize(k) == enchantment)
                    .map(|(_, v)| v.0)
            })
            .unwrap_or(0)
    }
}

impl GameContext {
    /// `table` as the server names it: a project or package table that's there, or a game table's id.
    fn loot_named(&self, table: &str) -> ApiResult<String> {
        let home = self.shared.loot.borrow().home.clone();
        if let Some((namespace, _)) = table.split_once(':')
            && namespace != home
            && !self
                .shared
                .names
                .borrow()
                .namespaces
                .contains_key(namespace)
        {
            return Ok(table.to_owned());
        }
        let name = self.with_names(|names| names.resource(KIND, table))?;
        self.shared.loot.borrow().id_of(&name).ok_or_else(|| {
            err(format!(
                "there's no loot table \"{table}\" (loot/{name}.json)"
            ))
        })
    }

    /// What one roll of `table` gives: a project table as the project names it, or one of the game's by its
    /// id. `seed` makes it give the same every time.
    pub fn roll_loot(&self, table: &str, roll: &LootRoll, seed: i64) -> ApiResult<Vec<ItemData>> {
        let (own, home) = {
            let loot = self.shared.loot.borrow();
            (loot.tables.get(table).cloned(), loot.home.clone())
        };
        let Some(own) = own else {
            // A name in the project's namespace or a package's is a project table's, which isn't there.
            let namespace = table.split_once(':').map_or(home.as_str(), |(n, _)| n);
            if namespace == home
                || self
                    .shared
                    .names
                    .borrow()
                    .namespaces
                    .contains_key(namespace)
            {
                return Err(err(format!(
                    "there's no loot table \"{table}\" (loot/{table}.json)"
                )));
            }
            return self.game_loot(table, roll, seed);
        };
        let drops = {
            let loot = self.shared.loot.borrow();
            let find = |reference: &str| {
                let id = resolve_any(&home, reference)?.relative_to(&home);
                loot.tables.get(&id).cloned()
            };
            let conditions = Conditions {
                roll,
                home: &home,
                game: self.shared.platform.game(),
            };
            loot_roll::roll(&own, &find, &conditions, &mut Random::new(seed)).map_err(err)?
        };
        let mut items = Vec::new();
        for drop in drops {
            match drop {
                LootDrop::Vanilla { table, seed } => {
                    items.extend(self.game_loot(&table, roll, seed)?)
                }
                LootDrop::Stack { item, count } => {
                    let stack = self.loot_stack(*item)?;
                    let most = stack.def.max_stack_size.unwrap_or(MOST_IN_A_STACK).max(1);
                    let mut from = 0;
                    while from < count {
                        let mut each = stack.clone();
                        each.def.count = Some(most.min(count - from));
                        items.push(each);
                        from += most;
                    }
                }
            }
        }
        Ok(items)
    }

    /// One of `item`: a project item's look with what the entry adds, or a vanilla item.
    fn loot_stack(&self, mut item: netherforge_format::item::ItemDef) -> ApiResult<ItemData> {
        item.count = Some(1);
        let Some(reference) = item.item.take() else {
            item.kind = game::normalize(&item.kind);
            return Ok(ItemData {
                def: item,
                raw: None,
                look: None,
            });
        };
        let id = self
            .shared
            .items
            .borrow()
            .id_of(&reference)
            .ok_or_else(|| err(format!("there's no item \"{reference}\"")))?;
        self.items().create(
            &id,
            Some(ItemData {
                def: item,
                raw: None,
                look: None,
            }),
        )
    }

    fn game_loot(&self, table: &str, roll: &LootRoll, seed: i64) -> ApiResult<Vec<ItemData>> {
        let platform = &*self.shared.platform;
        if !platform.loot().exists(table.to_owned()) {
            return Err(err(format!(
                "neither the project nor the server has a loot table \"{table}\""
            )));
        }
        let at = roll
            .location
            .clone()
            .or_else(|| roll.player.and_then(|p| platform.players().location(p)))
            .or_else(|| {
                roll.looted
                    .and_then(|e| platform.world_entities().info(e))
                    .map(|i| i.location)
            })
            .ok_or_else(|| {
                err(format!(
                    "the game's loot table \"{table}\" is rolled at a place: give the roll a location, a player or a looted entity"
                ))
            })?;
        let context = GameLootContext {
            location: at,
            luck: roll.luck,
            player: roll.player,
            looted: roll.looted,
        };
        platform
            .loot()
            .roll(table.to_owned(), context, seed)
            .ok_or_else(|| err(format!("the server has no loot table \"{table}\"")))
    }

    /// What the script said, with `place` where a game table is rolled when it says nowhere.
    fn loot_roll(
        &self,
        context: &Option<shapes::LootContext>,
        place: Option<Location>,
    ) -> ApiResult<LootRoll> {
        let Some(c) = context else {
            return Ok(LootRoll {
                location: place,
                ..LootRoll::default()
            });
        };
        let tool = match &c.tool {
            Some(tool) => Some(
                crate::items::from_value(tool, "context.tool", self.shared.platform.game())
                    .map_err(err)?,
            ),
            None => None,
        };
        Ok(LootRoll {
            player: c.player.as_ref().and_then(|p| parse_uuid(&p.id)),
            tool,
            luck: c.luck.unwrap_or(0.0),
            looted: c.looted.as_ref().and_then(|e| parse_uuid(&e.id)),
            location: c
                .location
                .as_ref()
                .map(|l| Location {
                    world: l.world.name.clone(),
                    x: l.position.x,
                    y: l.position.y,
                    z: l.position.z,
                    yaw: l.yaw.unwrap_or(0.0),
                    pitch: l.pitch.unwrap_or(0.0),
                })
                .or(place),
        })
    }

    /// Where an inventory is: its block's middle, or its holder's place.
    fn inventory_place(&self, r#ref: &InventoryRef) -> Option<Location> {
        let platform = &*self.shared.platform;
        match r#ref {
            InventoryRef::Block(b) => Some(Location {
                world: b.world.clone(),
                x: f64::from(b.x) + 0.5,
                y: f64::from(b.y) + 0.5,
                z: f64::from(b.z) + 0.5,
                yaw: 0.0,
                pitch: 0.0,
            }),
            InventoryRef::Player(p) => platform.players().location(p.player),
            InventoryRef::EnderChest(p) => platform.players().location(p.player),
            InventoryRef::Storage(s) => platform.players().location(s.player),
            InventoryRef::Entity(e) => platform.world_entities().info(e.entity).map(|i| i.location),
        }
    }
}

/// A roll's seed: the script's, else a new one each roll.
fn seed_of(context: &Option<shapes::LootContext>) -> i64 {
    context.as_ref().and_then(|c| c.seed).unwrap_or_else(|| {
        let mut bytes = [0u8; 8];
        getrandom::fill(&mut bytes).expect("the system has randomness");
        i64::from_le_bytes(bytes)
    })
}

impl NfLootApi for GameContext {
    fn roll(
        &self,
        _caller: &Caller,
        table: String,
        context: Option<shapes::LootContext>,
    ) -> ApiResult<Vec<values::Item>> {
        let name = self.loot_named(&table)?;
        let roll = self.loot_roll(&context, None)?;
        let items = self.roll_loot(&name, &roll, seed_of(&context))?;
        Ok(items.iter().map(crate::items::to_value).collect())
    }

    /// Each stack into an empty slot picked at random from the roll's seed, as the game fills a chest.
    fn fill(
        &self,
        _caller: &Caller,
        table: String,
        inventory: handles::Inventory,
        context: Option<shapes::LootContext>,
    ) -> ApiResult<Option<i64>> {
        let Some(r#ref) = inventory_ref(&inventory) else {
            return Ok(None);
        };
        let inventories = self.shared.platform.inventories();
        let Some(contents) = inventories.contents(r#ref.clone()) else {
            return Ok(None);
        };
        let name = self.loot_named(&table)?;
        let seed = seed_of(&context);
        let roll = self.loot_roll(&context, self.inventory_place(&r#ref))?;
        let items = self.roll_loot(&name, &roll, seed)?;
        // A player's armour, offhand and the rest never take loot: their hotbar and main inventory do.
        let usable = match r#ref {
            InventoryRef::Player(_) => contents.len().min(PLAYER_MAIN),
            _ => contents.len(),
        };
        let mut empty: Vec<usize> = (0..usable).filter(|&i| contents[i].is_none()).collect();
        shuffle(&mut empty, &mut Random::new(!seed));
        let mut empty = empty.into_iter();
        let mut left = 0;
        for item in items {
            let count = i64::from(item.def.count.unwrap_or(1));
            let placed = empty
                .next()
                .is_some_and(|slot| inventories.set_item(r#ref.clone(), slot as i32, Some(item)));
            if !placed {
                left += count;
            }
        }
        self.inventory_changed(&r#ref);
        Ok(Some(left))
    }
}

/// Kotlin's `MutableList.shuffle(random)`: from the end, each swapped with one at or before it.
fn shuffle<T>(items: &mut [T], random: &mut Random) {
    for i in (1..items.len()).rev() {
        let j = random.next_int_below(i as i32 + 1) as usize;
        items.swap(i, j);
    }
}

/// The loot tables service: defines them and swaps one when its file changes.
pub struct LootService {
    pub shared: Rc<Shared>,
}

fn running(project: &Define) -> BTreeMap<String, LootTableFile> {
    project
        .project
        .running::<LootTableFile>()
        .into_iter()
        .map(|(id, t)| (id, t.clone()))
        .collect()
}

impl Service for LootService {
    fn name(&self) -> &'static str {
        "loot tables"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &[KIND]
    }

    fn start(&self, _room: &Room, project: &Define) {
        let mut loot = self.shared.loot.borrow_mut();
        loot.tables = running(project);
        loot.home = project.project.namespace().to_owned();
    }

    /// A valid file replaces its table, a deleted one goes; one with errors keeps its last good version.
    fn reload(&self, _room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let mut next = running(project);
        let present: BTreeSet<String> = project
            .project
            .everywhere(KIND)
            .into_iter()
            .map(|(id, _)| id)
            .collect();
        let mut loot = self.shared.loot.borrow_mut();
        for id in ids {
            match next.remove(id) {
                Some(table) => {
                    loot.tables.insert(id.clone(), table);
                }
                None if !present.contains(id) => {
                    loot.tables.remove(id);
                }
                None => {}
            }
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        *self.shared.loot.borrow_mut() = LootTables::default();
    }
}
