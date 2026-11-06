//! Recipes (Kotlin's `Recipes`, `StationCrafting` and `NfRecipesImpl`): every valid `recipes/<id>.json` and
//! those scripts register (`nf.recipes.register`), each of the latter belonging to the scope that registered it.
//!
//! A recipe the game makes goes to the server at once (`RecipeOps::add` and `remove`), but to players' clients
//! only once a tick (`resend`), so a reload that changes many, or a body registering a few, sends the list once.
//! A `station` recipe is never the server's: scripts offer it (`nf.recipes.available`) and make it
//! (`nf.recipes.craft`) from a player's inventory, near the blocks it names.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::Rc;

use indexmap::IndexMap;
use mlua::Value;
use netherforge_api::api::NfRecipesApi;
use netherforge_api::codec::Codec;
use netherforge_api::{ApiError, ApiResult, Caller, codecs, handles, shapes, values};
use netherforge_format::game::{self, Registry};
use netherforge_format::kind::recipe::{Ingredient, RecipeFile};
use netherforge_format::math::Vec3;
use netherforge_luau::ScopeId;
use netherforge_platform::{
    InventoryRef, InventoryRefPlayer, ItemData, Platform, RecipeSpec, Uuid,
};

use crate::Room;
use crate::centity::definitions::lua_path;
use crate::events::parse_uuid;
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

const KIND: &str = "recipe";
/// The most a result stack is given as, when it's made many times over.
const MOST_IN_A_STACK: i32 = 64;

/// The session's recipes: the files' and those scripts registered.
#[derive(Default)]
pub struct Recipes {
    /// The project's files' recipes the server has (or a station's), by id.
    files: IndexMap<String, RecipeFile>,
    /// Each file's path, for saying whose an id is.
    paths: BTreeMap<String, String>,
    /// Recipes scripts registered, by id, with the scope that did.
    created: IndexMap<String, (RecipeFile, ScopeId)>,
    /// The ids the game was given, so a `station` recipe (never given) is never taken back from it.
    sent: HashSet<String>,
    /// Whether players' recipe lists are behind the server's.
    dirty: bool,
}

impl Recipes {
    /// Every recipe's id: the files', then the scripts'.
    pub fn ids(&self) -> Vec<String> {
        let mut files: Vec<String> = self.files.keys().cloned().collect();
        let mut created: Vec<String> = self.created.keys().cloned().collect();
        files.sort();
        created.sort();
        files.extend(created);
        files
    }

    pub fn has(&self, id: &str) -> bool {
        self.files.contains_key(id) || self.created.contains_key(id)
    }

    /// The recipe `id` (a file's or a script's).
    pub fn file(&self, id: &str) -> Option<&RecipeFile> {
        self.files
            .get(id)
            .or_else(|| self.created.get(id).map(|(f, _)| f))
    }

    /// Every `station` recipe, by id: scripts make these, never the game.
    fn stations(&self) -> BTreeMap<String, RecipeFile> {
        let files = self.files.iter();
        let created = self.created.iter().map(|(id, (f, _))| (id, f));
        files
            .chain(created)
            .filter(|(_, f)| !f.in_game())
            .map(|(id, f)| (id.clone(), f.clone()))
            .collect()
    }

    fn path(&self, id: &str) -> String {
        self.paths
            .get(id)
            .cloned()
            .unwrap_or_else(|| format!("recipes/{id}.json"))
    }

    /// Gives the server `file` (a game recipe; a station's is never sent): false when it couldn't build it.
    fn put(&mut self, platform: &dyn Platform, id: &str, file: &RecipeFile) -> bool {
        if !file.in_game() {
            return true;
        }
        self.dirty = true;
        let added = platform.recipes().add(RecipeSpec {
            id: id.to_owned(),
            file: file.clone(),
        });
        if added {
            self.sent.insert(id.to_owned());
        } else {
            platform.log().warn(format!(
                "The server couldn't make recipe \"{id}\"; it's left out"
            ));
        }
        added
    }

    fn take(&mut self, platform: &dyn Platform, id: &str) {
        if self.sent.remove(id) {
            self.dirty = true;
            platform.recipes().remove(id.to_owned());
        }
    }

    /// Moves `id` onto `next`, or takes it away when `next` is none (the file was deleted).
    fn reload(&mut self, platform: &dyn Platform, id: &str, next: Option<RecipeFile>) {
        if self.files.shift_remove(id).is_some() {
            self.take(platform, id);
        }
        if let Some(next) = next
            && self.put(platform, id, &next)
        {
            self.files.insert(id.to_owned(), next);
        }
    }

    /// Adds a recipe for `owner`, replacing one a script registered with that id. A file's id is the script's
    /// mistake.
    fn register(
        &mut self,
        platform: &dyn Platform,
        id: &str,
        file: RecipeFile,
        owner: ScopeId,
    ) -> ApiResult<()> {
        if self.files.contains_key(id) {
            return Err(err(format!(
                "recipe \"{id}\" is {}'s; pick another id",
                self.path(id)
            )));
        }
        if self.created.shift_remove(id).is_some() {
            self.take(platform, id);
        }
        if !self.put(platform, id, &file) {
            return Err(err(format!("the server couldn't make recipe \"{id}\"")));
        }
        self.created.insert(id.to_owned(), (file, owner));
        Ok(())
    }

    /// Takes away a recipe a script registered; false when there's none by that id.
    fn remove(&mut self, platform: &dyn Platform, id: &str) -> ApiResult<bool> {
        if self.files.contains_key(id) {
            return Err(err(format!(
                "recipe \"{id}\" is {}'s; delete the file to remove it",
                self.path(id)
            )));
        }
        if self.created.shift_remove(id).is_none() {
            return Ok(false);
        }
        self.take(platform, id);
        Ok(true)
    }

    /// Once a tick: tells players about the recipes that changed.
    fn resend(&mut self, platform: &dyn Platform) {
        if std::mem::take(&mut self.dirty) {
            platform.recipes().resend();
        }
    }
}

/// What stands near a place: block ids, each with how far it is (blocks, on the furthest axis), and the names a
/// script said to count as near whatever stands there.
struct Near {
    distances: HashMap<String, i32>,
    extra: HashSet<String>,
}

impl Near {
    fn within(&self, name: &str, range: i32) -> bool {
        self.extra.contains(name) || self.distances.get(name).is_some_and(|d| *d <= range)
    }
}

impl GameContext {
    /// The stations within `range` of `center` in `world`, plus `extra`. Each chunk column is read on its own:
    /// a box over an unloaded chunk reads as nothing. Project blocks wait for custom blocks: until then a
    /// station is a block id or a tag.
    fn stations_near(&self, world: &str, center: Vec3, range: i32, extra: &[String]) -> Near {
        let platform = &*self.shared.platform;
        let (cx, cy, cz) = (
            center.0.floor() as i32,
            center.1.floor() as i32,
            center.2.floor() as i32,
        );
        let heights = platform.worlds().heights(world.to_owned());
        let y0 = (cy - range).max(heights.map_or(cy - range, |h| h.0));
        let y1 = (cy + range).min(heights.map_or(cy + range + 1, |h| h.1) - 1);
        let mut distances: HashMap<String, i32> = HashMap::new();
        for chunk_x in (cx - range).div_euclid(16)..=(cx + range).div_euclid(16) {
            for chunk_z in (cz - range).div_euclid(16)..=(cz + range).div_euclid(16) {
                let x0 = (cx - range).max(chunk_x * 16);
                let x1 = (cx + range).min(chunk_x * 16 + 15);
                let z0 = (cz - range).max(chunk_z * 16);
                let z1 = (cz + range).min(chunk_z * 16 + 15);
                if y1 < y0 {
                    continue;
                }
                let Some(read) = platform
                    .blocks()
                    .r#box(world.to_owned(), x0, y0, z0, x1, y1, z1)
                else {
                    continue;
                };
                let (size_x, size_z) = (x1 - x0 + 1, z1 - z0 + 1);
                for (i, index) in read.cells.iter().enumerate() {
                    let Some(state) = read.states.get(*index as usize) else {
                        continue;
                    };
                    let state = &state.state;
                    if state.starts_with("minecraft:air") || state.starts_with("minecraft:cave_air")
                    {
                        continue;
                    }
                    let i = i as i32;
                    let x = x0 + i % size_x;
                    let z = z0 + (i / size_x) % size_z;
                    let y = y0 + i / (size_x * size_z);
                    let distance = (x - cx).abs().max((y - cy).abs()).max((z - cz).abs());
                    let id = state.split('[').next().unwrap_or(state).to_owned();
                    let at = distances.entry(id).or_insert(distance);
                    *at = (*at).min(distance);
                }
            }
        }
        Near {
            distances,
            extra: extra.iter().cloned().collect(),
        }
    }

    /// Whether every station `recipe` needs is near: an entry with `|` is near when any of its choices is.
    fn stations_met(&self, recipe: &RecipeFile, near: &Near) -> bool {
        let range = recipe.station_range_or_default();
        recipe.stations.iter().flatten().all(|entry| {
            entry
                .split('|')
                .any(|station| self.station_met(station.trim(), near, range))
        })
    }

    /// Whether one station (an id or a `#tag`) is within `range`.
    fn station_met(&self, station: &str, near: &Near, range: i32) -> bool {
        if let Some(tag) = station.strip_prefix('#') {
            let members = game::is_id(tag)
                .then(|| {
                    self.shared
                        .platform
                        .game()
                        .tag(Registry::Block.key(), &game::normalize(tag))
                })
                .flatten();
            return members.is_some_and(|m| m.iter().any(|b| near.within(b, range)))
                || near.within(station, range);
        }
        near.within(station, range)
            || (game::is_id(station) && near.within(&game::normalize(station), range))
    }

    /// The largest station range any of `recipes` asks for: how far round a place to look.
    fn station_range<'r>(recipes: impl IntoIterator<Item = &'r RecipeFile>) -> i32 {
        recipes
            .into_iter()
            .map(RecipeFile::station_range_or_default)
            .max()
            .unwrap_or(0)
    }

    /// Whether `stack` is what `ingredient` takes: a project item's own stacks only for it, a kind or tag never a
    /// project item's.
    fn takes(&self, ingredient: &Ingredient, stack: &ItemData) -> bool {
        let items = self.shared.items.borrow();
        let project = stack.def.item.as_deref().and_then(|i| items.id_of(i));
        if let Ingredient::Item(wanted) = ingredient {
            return project.is_some() && project == items.id_of(wanted);
        }
        if project.is_some() {
            return false;
        }
        let kind = game::normalize(&stack.def.kind);
        match ingredient {
            Ingredient::Tag(tag) => self
                .shared
                .platform
                .game()
                .tag(Registry::Item.key(), &game::normalize(tag))
                .is_some_and(|t| t.contains(&kind)),
            Ingredient::Kind(wanted) => game::normalize(wanted) == kind,
            Ingredient::Item(_) => false,
        }
    }

    /// How many times over `player`'s inventory holds `recipe`'s materials.
    fn times_held(&self, recipe: &RecipeFile, player: Uuid) -> i32 {
        let r#ref = InventoryRef::Player(InventoryRefPlayer { player });
        let Some(slots) = self.shared.platform.inventories().contents(r#ref) else {
            return 0;
        };
        let mut times = i32::MAX;
        for material in recipe.materials.iter().flatten() {
            let held: i32 = slots
                .iter()
                .flatten()
                .filter(|stack| self.takes(&material.ingredient, stack))
                .map(|stack| stack.def.count.unwrap_or(1))
                .sum();
            times = times.min(held / material.count_or_default());
        }
        if times == i32::MAX { 0 } else { times }
    }

    /// One making's result: a project item's built from its look (and rendered), anything else as the file says.
    fn recipe_result(&self, recipe: &RecipeFile) -> ApiResult<ItemData> {
        let result = recipe.result.clone();
        let project = result
            .item
            .as_deref()
            .and_then(|i| self.shared.items.borrow().id_of(i));
        match project {
            Some(id) => self.items().create(
                &id,
                Some(ItemData {
                    def: result,
                    raw: None,
                    look: None,
                }),
            ),
            None => {
                let mut def = result;
                def.count = Some(def.count.unwrap_or(1));
                Ok(ItemData {
                    def,
                    raw: None,
                    look: None,
                })
            }
        }
    }

    /// Makes `recipe` `times` over for `player`: takes its materials and gives its result (what doesn't fit
    /// drops at their feet); `each`, when given, is what each making gives, from the result.
    fn make(
        &self,
        recipe: &RecipeFile,
        player: Uuid,
        times: i32,
        each: Option<&dyn Fn(ItemData) -> ApiResult<ItemData>>,
    ) -> ApiResult<()> {
        let inventories = self.shared.platform.inventories();
        let r#ref = InventoryRef::Player(InventoryRefPlayer { player });
        let Some(mut slots) = inventories.contents(r#ref.clone()) else {
            return Ok(());
        };
        for material in recipe.materials.iter().flatten() {
            let mut owed = material.count_or_default() * times;
            for (slot, held) in slots.iter_mut().enumerate() {
                if owed <= 0 {
                    break;
                }
                let Some(stack) = held.clone() else {
                    continue;
                };
                if !self.takes(&material.ingredient, &stack) {
                    continue;
                }
                let count = stack.def.count.unwrap_or(1);
                let taken = count.min(owed);
                owed -= taken;
                let left = (taken != count).then(|| {
                    let mut left = stack.clone();
                    left.def.count = Some(count - taken);
                    left
                });
                *held = left.clone();
                inventories.set_item(r#ref.clone(), slot as i32, left);
            }
        }
        let one = self.recipe_result(recipe)?;
        if let Some(each) = each {
            for _ in 0..times {
                self.give(&r#ref, player, each(one.clone())?);
            }
            return Ok(());
        }
        let mut left = one.def.count.unwrap_or(1) * times;
        while left > 0 {
            let n = left.min(MOST_IN_A_STACK);
            left -= n;
            let mut stack = one.clone();
            stack.def.count = Some(n);
            self.give(&r#ref, player, stack);
        }
        Ok(())
    }

    /// Puts `stack` in `player`'s inventory, dropping what doesn't fit at their feet.
    fn give(&self, r#ref: &InventoryRef, player: Uuid, stack: ItemData) {
        let platform = &*self.shared.platform;
        let count = stack.def.count.unwrap_or(1);
        let over = platform
            .inventories()
            .add(r#ref.clone(), stack.clone())
            .unwrap_or(count);
        if over > 0
            && let Some(at) = platform.players().location(player)
        {
            let mut rest = stack;
            rest.def.count = Some(over);
            platform
                .world_entities()
                .spawn_item(at.world, Vec3(at.x, at.y, at.z), rest);
        }
    }

    /// A station recipe as scripts see it, with `times` when it's being offered.
    fn shown(
        &self,
        id: &str,
        file: &RecipeFile,
        times: Option<i32>,
    ) -> ApiResult<shapes::StationRecipe> {
        let result = crate::items::to_value(&self.recipe_result(file)?);
        let items = self.shared.items.borrow();
        let materials = file
            .materials
            .iter()
            .flatten()
            .map(|m| {
                let (item, kind, tag) = match &m.ingredient {
                    Ingredient::Item(i) => (
                        items
                            .id_of(i)
                            .map(|id| self.with_names(|names| names.spell(&id))),
                        None,
                        None,
                    ),
                    Ingredient::Kind(k) => (None, Some(game::normalize(k)), None),
                    Ingredient::Tag(t) => (None, None, Some(game::normalize(t))),
                };
                shapes::RecipeMaterial {
                    item,
                    kind,
                    tag,
                    count: i64::from(m.count_or_default()),
                }
            })
            .collect();
        Ok(shapes::StationRecipe {
            id: self.with_names(|names| names.spell(id)),
            result,
            materials,
            stations: file.stations.clone().unwrap_or_default(),
            station_range: i64::from(file.station_range_or_default()),
            group: file.group.clone(),
            times: times.map(i64::from),
        })
    }

    /// A player's place while they're online.
    fn online_at(
        &self,
        player: &handles::Player,
    ) -> Option<(Uuid, netherforge_platform::Location)> {
        let players = self.shared.platform.players();
        let uuid = parse_uuid(&player.id).filter(|id| players.get(*id).is_some())?;
        Some((uuid, players.location(uuid)?))
    }

    /// `act` on their recipe book. A bare id names a project recipe, which must exist (a typo is an error, even
    /// for someone offline); a namespaced one (`minecraft:bread`) is any the server has, which the server checks.
    pub(crate) fn recipe_book(
        &self,
        player: &handles::Player,
        recipe: &str,
        act: impl FnOnce(&dyn netherforge_platform::RecipeOps, Uuid, String) -> bool,
    ) -> ApiResult<bool> {
        let id = if recipe.contains(':') {
            recipe.to_owned()
        } else {
            let id = self.with_names(|names| names.resource(KIND, recipe))?;
            let recipes = self.shared.recipes.borrow();
            if !recipes.has(&id) {
                let known: Vec<String> = recipes
                    .ids()
                    .iter()
                    .map(|k| self.with_names(|names| names.spell(k)))
                    .collect();
                let has = if known.is_empty() {
                    String::new()
                } else {
                    format!(" (it has: {})", known.join(", "))
                };
                return Err(err(format!("the project has no recipe \"{recipe}\"{has}")));
            }
            id
        };
        let players = self.shared.platform.players();
        let Some(uuid) = parse_uuid(&player.id).filter(|id| players.get(*id).is_some()) else {
            return Ok(false);
        };
        Ok(act(self.shared.platform.recipes(), uuid, id))
    }
}

fn vec(v: &values::Vec3) -> Vec3 {
    Vec3(v.x, v.y, v.z)
}

impl NfRecipesApi for GameContext {
    /// Checked as a `recipes/` file is, then kept for the calling scope.
    fn register(&self, caller: &Caller, id: String, definition: Value) -> ApiResult<()> {
        if !netherforge_format::names::is_id(&id) {
            return Err(err(format!(
                "\"{id}\" isn't a usable recipe id ({})",
                netherforge_format::names::ID_RULE
            )));
        }
        let game = self.shared.platform.game();
        let file = self.with_names(|names| {
            crate::definitions::recipe_from(&self.scripts.host(), &definition, game, names)
        })?;
        for (at, ingredient) in file.ingredients() {
            if let Ingredient::Item(item) = ingredient
                && self.shared.items.borrow().id_of(item).is_none()
            {
                return Err(err(format!(
                    "{}: there's no item \"{item}\"",
                    lua_path(&at)
                )));
            }
        }
        // The calling package's own: a package's recipes are named in its namespace.
        let name = self.with_names(|names| names.resource(KIND, &id))?;
        self.shared
            .recipes
            .borrow_mut()
            .register(&*self.shared.platform, &name, file, caller.scope)
    }

    fn remove(&self, _caller: &Caller, id: String) -> ApiResult<bool> {
        let name = self.with_names(|names| names.resource(KIND, &id))?;
        self.shared
            .recipes
            .borrow_mut()
            .remove(&*self.shared.platform, &name)
    }

    fn get(&self, _caller: &Caller, id: String) -> ApiResult<Option<shapes::StationRecipe>> {
        let name = self.with_names(|names| names.resource(KIND, &id))?;
        let file = self
            .shared
            .recipes
            .borrow()
            .file(&name)
            .filter(|f| !f.in_game())
            .cloned();
        file.map(|f| self.shown(&name, &f, None)).transpose()
    }

    fn available(
        &self,
        _caller: &Caller,
        player: handles::Player,
        options: Option<shapes::RecipeAvailableOptions>,
    ) -> ApiResult<Vec<shapes::StationRecipe>> {
        let Some((uuid, at)) = self.online_at(&player) else {
            return Ok(Vec::new());
        };
        let options = options.as_ref();
        let group = options.and_then(|o| o.group.clone());
        let recipes: BTreeMap<String, RecipeFile> = self
            .shared
            .recipes
            .borrow()
            .stations()
            .into_iter()
            .filter(|(_, f)| group.is_none() || f.group == group)
            .collect();
        let center = options
            .and_then(|o| o.near.as_ref())
            .map_or(Vec3(at.x, at.y, at.z), vec);
        let extra = options.and_then(|o| o.stations.clone()).unwrap_or_default();
        let near = self.stations_near(
            &at.world,
            center,
            Self::station_range(recipes.values()),
            &extra,
        );
        let all = options.and_then(|o| o.all) == Some(true);
        let mut shown = Vec::new();
        for (id, file) in &recipes {
            let times = if self.stations_met(file, &near) {
                self.times_held(file, uuid)
            } else {
                0
            };
            if times > 0 || all {
                shown.push(self.shown(id, file, Some(times))?);
            }
        }
        Ok(shown)
    }

    fn craft(
        &self,
        _caller: &Caller,
        player: handles::Player,
        id: String,
        options: Option<shapes::RecipeCraftOptions>,
    ) -> ApiResult<i64> {
        let times = options.as_ref().and_then(|o| o.times).unwrap_or(1);
        if times < 1 {
            return Err(err(format!(
                "bad argument 'options.times' (at least 1 expected, got {times})"
            )));
        }
        let name = self.with_names(|names| names.resource(KIND, &id))?;
        let Some(file) = self.shared.recipes.borrow().file(&name).cloned() else {
            return Err(err(format!("there's no recipe \"{id}\"")));
        };
        if file.in_game() {
            return Err(err(format!(
                "recipe \"{id}\" isn't a station recipe: the game makes it"
            )));
        }
        let Some((uuid, at)) = self.online_at(&player) else {
            return Ok(0);
        };
        let center = options
            .as_ref()
            .and_then(|o| o.near.as_ref())
            .map_or(Vec3(at.x, at.y, at.z), vec);
        let extra = options
            .as_ref()
            .and_then(|o| o.stations.clone())
            .unwrap_or_default();
        let near = self.stations_near(&at.world, center, Self::station_range([&file]), &extra);
        if !self.stations_met(&file, &near) {
            return Ok(0);
        }
        let made = i32::try_from(times)
            .unwrap_or(i32::MAX)
            .min(self.times_held(&file, uuid));
        if made > 0 {
            let recipe = self.with_names(|names| names.spell(&name));
            let each = |result: ItemData| -> ApiResult<ItemData> {
                let lua = self.scripts.host().lua().clone();
                let payload = shapes::RecipeCraftEvent {
                    player: player.clone(),
                    recipe: recipe.clone(),
                    result: crate::items::to_value(&result),
                };
                let Ok(Value::Table(fields)) = codecs::RecipeCraftEvent.push(&lua, payload) else {
                    return Ok(result);
                };
                let delivered = self.scripts.raise(&Target::Nf, "recipe_craft", fields);
                let back =
                    codecs::RecipeCraftEvent.read(&lua, Value::Table(delivered.fields), "event")?;
                crate::items::from_value(&back.result, "event.result", self.shared.platform.game())
                    .map_err(err)
            };
            let listening = self.scripts.listening(&Target::Nf, "recipe_craft");
            self.make(
                &file,
                uuid,
                made,
                listening.then_some(&each as &dyn Fn(ItemData) -> ApiResult<ItemData>),
            )?;
        }
        Ok(i64::from(made))
    }

    fn all(&self, _caller: &Caller) -> ApiResult<Vec<String>> {
        let ids = self.shared.recipes.borrow().ids();
        Ok(ids
            .iter()
            .map(|id| self.with_names(|names| names.spell(id)))
            .collect())
    }
}

/// The recipes service: gives the server the files' recipes, swaps one when its file changes, takes away what
/// a stopped scope registered, and tells players once a tick.
pub struct RecipesService {
    pub shared: Rc<Shared>,
}

fn paths(project: &Define) -> BTreeMap<String, String> {
    project
        .project
        .everywhere(KIND)
        .into_iter()
        .map(|(id, resource)| {
            let found = &resource.found;
            let path = found.kind.location_of(&found.id, &found.group);
            let path = match netherforge_format::kinds::split_package(&id) {
                (Some(namespace), _) => netherforge_format::kinds::package_path(namespace, &path),
                (None, _) => path,
            };
            (id, path)
        })
        .collect()
}

impl Service for RecipesService {
    fn name(&self) -> &'static str {
        "recipes"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &[KIND]
    }

    /// Takes every file's recipe, after the items: a recipe's project items are built from their looks.
    fn start(&self, _room: &Room, project: &Define) {
        let platform = &*self.shared.platform;
        let mut recipes = self.shared.recipes.borrow_mut();
        recipes.paths = paths(project);
        for (id, file) in project.project.running::<RecipeFile>() {
            if recipes.put(platform, &id, file) {
                recipes.files.insert(id, file.clone());
            }
        }
    }

    fn tick(&self, _room: &Room, platform: &dyn Platform) {
        self.shared.recipes.borrow_mut().resend(platform);
    }

    /// The server learns the recipe again (or forgets it); a file with errors keeps its last good version.
    fn reload(&self, _room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let platform = &*self.shared.platform;
        let running: BTreeMap<String, RecipeFile> = project
            .project
            .running::<RecipeFile>()
            .into_iter()
            .map(|(id, f)| (id, f.clone()))
            .collect();
        let paths = paths(project);
        let mut recipes = self.shared.recipes.borrow_mut();
        for id in ids {
            match running.get(id) {
                Some(next) => recipes.reload(platform, id, Some(next.clone())),
                None if !paths.contains_key(id) => recipes.reload(platform, id, None),
                None => {}
            }
        }
        recipes.paths = paths;
    }

    /// What a stopped scope registered goes.
    fn scope_released(&self, scopes: &[ScopeId]) {
        let platform = &*self.shared.platform;
        let mut recipes = self.shared.recipes.borrow_mut();
        let gone: Vec<String> = recipes
            .created
            .iter()
            .filter(|(_, (_, owner))| scopes.contains(owner))
            .map(|(id, _)| id.clone())
            .collect();
        for id in gone {
            recipes.created.shift_remove(&id);
            recipes.take(platform, &id);
        }
    }

    /// Takes every recipe away, and tells players at once.
    fn stop(&self, _room: &Room, platform: &dyn Platform) {
        let mut recipes = self.shared.recipes.borrow_mut();
        let ids: Vec<String> = recipes
            .files
            .keys()
            .chain(recipes.created.keys())
            .cloned()
            .collect();
        for id in ids {
            recipes.take(platform, &id);
        }
        recipes.resend(platform);
        *recipes = Recipes::default();
    }
}
