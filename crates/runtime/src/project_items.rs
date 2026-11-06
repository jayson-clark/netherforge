//! The project's items (`items/<id>/item.json`) and the script behind each (Kotlin's `item/Items.kt`).
//!
//! An item is data first: its look is what the platform resolves every stack naming it against and stamps on
//! it. Like a dialog, it has one script, not one per stack: it starts once modules run and runs until the item
//! reloads, and hears what players do with any stack of the item, routed here by the stack's id (`stage`) ahead
//! of the player's own handlers.
//!
//! Stale stacks (stamped with a look the item has since changed from) are rewritten when the server sees them:
//! a player's inventory when they join and when the item reloads, and the tick after they pick something up.
//! How a stack looks to one player (`view`, `item_view`) is the platform's to ask about each stack it sends; a
//! player's are sent again at the end of a tick (`player:refresh_items`), and everyone's when a session starts.

use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use indexmap::IndexMap;
use mlua::{Function, Table, Value};
use netherforge_api::api::{NfItemsApi, ProjectItemApi};
use netherforge_api::codec::{Codec, ItemCodec, TextCodec};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, shapes, values};
use netherforge_format::item::project_items;
use netherforge_format::kind::item::ItemFile;
use netherforge_luau::ScopeId;
use netherforge_platform::{
    InventoryRef, InventoryRefPlayer, ItemData, ItemLook, ItemView, Platform, PlayerRef, Uuid,
};

use crate::Room;
use crate::events::uuid_text;
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

struct Record {
    file: ItemFile,
    look: ItemLook,
    /// The item's script's scope, while it runs.
    scope: Option<ScopeId>,
}

impl Record {
    fn new(file: ItemFile) -> Record {
        let def = file.look();
        let hash = project_items::hash(&def);
        Record {
            file,
            look: ItemLook { def, hash },
            scope: None,
        }
    }
}

/// The session's project items.
#[derive(Default)]
pub struct ProjectItems {
    records: IndexMap<String, Record>,
    /// Each item's script, by id: its project path.
    scripts: BTreeMap<String, String>,
    home: String,
    /// Players whose inventory to check at the next tick: they just picked something up.
    pending: BTreeSet<Uuid>,
    /// Players whose games to send their stacks' looks again at the next tick.
    views_pending: BTreeSet<Uuid>,
    /// Whether everyone's stacks are sent again at the next tick: a session's handlers aren't the last one's.
    views_changed: bool,
}

impl ProjectItems {
    /// The id of the project item `reference` names, as a file or a script writes it (`ruby`) or a stack
    /// carries it (`shop:ruby`), or none when it names none of the items running.
    pub fn id_of(&self, reference: &str) -> Option<String> {
        let key = netherforge_format::reference::resolve_any(&self.home, reference)?;
        let id = key.relative_to(&self.home);
        self.records.contains_key(&id).then_some(id)
    }

    /// The current look of the item `reference` names, or none: what the platform resolves and stamps stacks
    /// with.
    pub fn look(&self, reference: &str) -> Option<ItemLook> {
        self.id_of(reference)
            .and_then(|id| self.records.get(&id).map(|r| r.look.clone()))
    }

    /// Whether a stack of a project item is a bow that draws and shoots without ammo.
    pub fn draws_without_ammo(&self, stack: &ItemData) -> bool {
        stack
            .def
            .item
            .as_deref()
            .and_then(|r| self.id_of(r))
            .and_then(|id| self.records.get(&id))
            .is_some_and(|r| r.file.draw_without_ammo == Some(true))
    }

    /// Every item's id, sorted.
    pub fn ids(&self) -> Vec<String> {
        let mut ids: Vec<String> = self.records.keys().cloned().collect();
        ids.sort();
        ids
    }
}

/// The items' state and the room's API to them.
#[derive(Clone)]
pub struct ItemContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl ItemContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn target(&self, handle: Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    fn start_script(&self, id: &str) {
        let Some(path) = self.shared.items.borrow().scripts.get(id).cloned() else {
            return;
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return;
        };
        let Ok(this) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::ProjectItem(handles::ProjectItem { id: id.to_owned() }),
        ) else {
            return;
        };
        let folder = path
            .rsplit_once('/')
            .map_or(path.as_str(), |(f, _)| f)
            .to_owned();
        let (scope, _) = modules.script_with(
            &format!("item {id}"),
            &folder,
            &path,
            &[("this", Value::UserData(this))],
        );
        if let Some(record) = self.shared.items.borrow_mut().records.get_mut(id) {
            record.scope = scope;
        }
    }

    fn stop_script(&self, id: &str) {
        let scope = self
            .shared
            .items
            .borrow_mut()
            .records
            .get_mut(id)
            .and_then(|r| r.scope.take());
        if let Some(scope) = scope {
            match self.shared.modules.borrow().clone() {
                Some(modules) => modules.close(scope),
                None => self.scripts.close(scope),
            }
        }
    }

    /// Moves `id` onto `next` (its script restarts), or forgets it when it's gone: its handlers go, and stacks
    /// of it keep the look they have. Every online player's inventory is checked for stacks the change made
    /// stale.
    fn reload(&self, id: &str, next: Option<ItemFile>) {
        self.stop_script(id);
        let previous = self.shared.items.borrow_mut().records.shift_remove(id);
        let Some(next) = next else {
            if let Some(target) = self.target(Handle::ProjectItem(handles::ProjectItem {
                id: id.to_owned(),
            })) {
                self.scripts.drop_target(&target);
            }
            return;
        };
        let record = Record::new(next);
        let changed = previous.is_none_or(|p| p.look.hash != record.look.hash);
        self.shared
            .items
            .borrow_mut()
            .records
            .insert(id.to_owned(), record);
        self.start_script(id);
        if changed {
            self.refresh_online();
        }
    }

    /// A stack of `id` as a script asked for it: `overrides` on top of the item's look, `count` 1 unless they
    /// say otherwise, as its `render` handlers finish it.
    pub fn create(&self, id: &str, overrides: Option<ItemData>) -> ApiResult<ItemData> {
        let Some(look) = self.shared.items.borrow().look(id) else {
            return Err(err(format!("there's no item \"{id}\" (items/{id})")));
        };
        let mut def = overrides.map(|o| o.def).unwrap_or_default();
        def.item = Some(id.to_owned());
        let mut def = project_items::resolve(&def, &look.def);
        def.count = Some(def.count.unwrap_or(1));
        Ok(self.render(ItemData {
            def,
            raw: None,
            look: None,
        }))
    }

    /// `stack`, a stack of one of the project's items being made, as the item's `render` handlers finish it:
    /// the name and lore they assign. `stack` itself when it's no project item's or nobody listens.
    pub fn render(&self, stack: ItemData) -> ItemData {
        let Some(id) = stack
            .def
            .item
            .as_deref()
            .and_then(|r| self.shared.items.borrow().id_of(r))
        else {
            return stack;
        };
        let Some(target) = self.target(Handle::ProjectItem(handles::ProjectItem { id })) else {
            return stack;
        };
        if !self.scripts.listening(&target, "render") {
            return stack;
        }
        let lua = self.scripts.host().lua().clone();
        let payload = shapes::ItemRenderEvent {
            item: crate::items::to_value(&stack),
            display_name: stack.def.name.clone().map(values::Text),
            lore: stack
                .def
                .lore
                .clone()
                .map(|l| l.into_iter().map(values::Text).collect()),
        };
        let Ok(Value::Table(fields)) = netherforge_api::codecs::ItemRenderEvent.push(&lua, payload)
        else {
            return stack;
        };
        let out = self.scripts.raise(&target, "render", fields);
        let (name, lore) = named(&lua, &out.fields);
        if name == stack.def.name && lore == stack.def.lore {
            return stack;
        }
        let mut stack = stack;
        stack.def.name = name;
        stack.def.lore = lore;
        stack
    }

    /// Whether a stack of the project item `reference` names (none for a stack of none) may look different to
    /// some player: someone listens to `item_view`, or to its item's `view`.
    pub fn viewing(&self, reference: Option<&str>) -> bool {
        if self.scripts.listening(&Target::Nf, "item_view") {
            return true;
        }
        let Some(id) = reference.and_then(|r| self.shared.items.borrow().id_of(r)) else {
            return false;
        };
        self.target(Handle::ProjectItem(handles::ProjectItem { id }))
            .is_some_and(|t| self.scripts.listening(&t, "view"))
    }

    /// How `stack` looks to `player`, as its item's `view` handlers and then `nf`'s `item_view` leave its name and
    /// lore; none when they're as they were, or when a handler failed: the stack is then sent as it is.
    pub fn view(&self, player: Uuid, stack: &ItemData) -> Option<ItemView> {
        let id = stack
            .def
            .item
            .as_deref()
            .and_then(|r| self.shared.items.borrow().id_of(r));
        let mut path = Vec::new();
        if let Some(id) = id
            && let Some(target) = self.target(Handle::ProjectItem(handles::ProjectItem { id }))
        {
            path.push((target, "view"));
        }
        path.push((Target::Nf, "item_view"));
        let lua = self.scripts.host().lua().clone();
        let payload = shapes::ItemViewEvent {
            player: handles::Player {
                id: uuid_text(&player),
            },
            item: crate::items::to_value(stack),
            display_name: stack.def.name.clone().map(values::Text),
            lore: stack
                .def
                .lore
                .clone()
                .map(|l| l.into_iter().map(values::Text).collect()),
        };
        let Ok(Value::Table(fields)) = netherforge_api::codecs::ItemViewEvent.push(&lua, payload)
        else {
            return None;
        };
        let failures = self.scripts.handler_failures();
        let out = self.scripts.raise_path(&path, fields, false);
        if self.scripts.handler_failures() != failures {
            return None;
        }
        let (name, lore) = named(&lua, &out.fields);
        if name == stack.def.name && lore == stack.def.lore {
            return None;
        }
        Some(ItemView { name, lore })
    }

    /// The item's stage of `event`, to go first on its path, when `item` is a stack of one of the project's
    /// items.
    pub fn stage(&self, item: Option<&ItemData>) -> Option<Handle> {
        let id = item?
            .def
            .item
            .as_deref()
            .and_then(|r| self.shared.items.borrow().id_of(r))?;
        Some(Handle::ProjectItem(handles::ProjectItem { id }))
    }

    /// The item's stage for what `player` holds in `hand` (`main_hand` or `off_hand`): looked up only while the
    /// project has items, since the event doesn't say what's held.
    pub fn held_stage(&self, player: Uuid, hand: &str) -> Option<Handle> {
        if self.shared.items.borrow().records.is_empty() {
            return None;
        }
        let held = self
            .platform()
            .world_entities()
            .equipment(player, hand.to_owned());
        self.stage(held.as_ref())
    }

    /// Checks `player`'s inventory at the next tick: what they just picked up is in it by then.
    pub fn refresh_soon(&self, player: Uuid) {
        self.shared.items.borrow_mut().pending.insert(player);
    }

    /// Sends `player`'s game the stacks it shows that now look different to them, at the end of this tick.
    pub fn refresh_view(&self, player: Uuid) {
        self.shared.items.borrow_mut().views_pending.insert(player);
    }

    fn refresh(&self, player: Uuid) -> i32 {
        self.platform()
            .project_items()
            .refresh(InventoryRef::Player(InventoryRefPlayer { player }))
    }

    /// Every online player's own inventory.
    fn refresh_online(&self) {
        for player in self.platform().players().online() {
            self.refresh(player.uuid);
        }
    }

    /// Once a tick: the inventories and the views asked for.
    fn tick(&self) {
        let (pending, everyone, views) = {
            let mut items = self.shared.items.borrow_mut();
            let pending = std::mem::take(&mut items.pending);
            let everyone = std::mem::take(&mut items.views_changed);
            let views: Vec<Uuid> = std::mem::take(&mut items.views_pending)
                .into_iter()
                .collect();
            (pending, everyone, views)
        };
        let views = if everyone {
            self.platform()
                .players()
                .online()
                .into_iter()
                .map(|p| p.uuid)
                .collect()
        } else {
            views
        };
        for player in pending {
            self.refresh(player);
        }
        for player in views {
            self.platform().item_views().refresh(player);
        }
    }
}

/// What handlers left in an item event's `display_name` and `lore`.
fn named(lua: &mlua::Lua, fields: &Table) -> (Option<String>, Option<Vec<String>>) {
    let name = fields
        .raw_get::<Value>("display_name")
        .ok()
        .and_then(|v| match v {
            Value::Nil => None,
            v => TextCodec.read(lua, v, "display_name").ok().map(|t| t.0),
        });
    let lore = fields.raw_get::<Value>("lore").ok().and_then(|v| match v {
        Value::Table(t) => Some(
            t.sequence_values::<Value>()
                .filter_map(|l| l.ok())
                .filter_map(|l| TextCodec.read(lua, l, "lore").ok().map(|t| t.0))
                .collect(),
        ),
        _ => None,
    });
    (name, lore)
}

impl GameContext {
    pub(crate) fn items(&self) -> ItemContext {
        ItemContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    /// A script's overrides table (an item without its kind) as a stack, or none for nil.
    fn overrides(&self, overrides: Value) -> ApiResult<Option<ItemData>> {
        if matches!(overrides, Value::Nil) {
            return Ok(None);
        }
        let lua = self.scripts.host().lua().clone();
        let read = ItemCodec.read(&lua, overrides, "overrides")?;
        // A kind-less table is only an item once it names one; the look fills the kind in.
        let mut json: serde_json::Value =
            serde_json::from_str(&read.0).map_err(|e| err(e.to_string()))?;
        if let serde_json::Value::Object(map) = &mut json
            && !map.contains_key("kind")
        {
            map.insert(
                "kind".into(),
                serde_json::Value::String("minecraft:stone".into()),
            );
            let item = crate::items::from_value(
                &values::Item(json.to_string()),
                "overrides",
                self.shared.platform.game(),
            )
            .map_err(err)?;
            let mut item = item;
            item.def.kind = String::new();
            return Ok(Some(item));
        }
        crate::items::from_value(&read, "overrides", self.shared.platform.game())
            .map(Some)
            .map_err(err)
    }
}

impl NfItemsApi for GameContext {
    fn create(&self, _: &Caller, item: String, overrides: Value) -> ApiResult<values::Item> {
        let id = self.with_names(|names| names.resource("item", &item))?;
        let overrides = self.overrides(overrides)?;
        let stack = self.items().create(&id, overrides)?;
        Ok(self.spelled_item(&stack))
    }
    fn id(&self, _: &Caller, stack: values::Item) -> ApiResult<Option<String>> {
        let item =
            crate::items::from_value(&stack, "stack", self.shared.platform.game()).map_err(err)?;
        Ok(item
            .def
            .item
            .and_then(|r| self.shared.items.borrow().id_of(&r))
            .map(|id| self.with_names(|names| names.spell(&id))))
    }
    fn get(&self, _: &Caller, item: String) -> ApiResult<Option<handles::ProjectItem>> {
        let id = self.with_names(|names| names.resource("item", &item))?;
        Ok(self
            .shared
            .items
            .borrow()
            .records
            .contains_key(&id)
            .then_some(handles::ProjectItem { id }))
    }
    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::ProjectItem>> {
        Ok(self
            .shared
            .items
            .borrow()
            .ids()
            .into_iter()
            .map(|id| handles::ProjectItem { id })
            .collect())
    }
}

impl GameContext {
    /// A stack as the calling package reads it: its item named in its terms.
    fn spelled_item(&self, stack: &ItemData) -> values::Item {
        let mut stack = stack.clone();
        if let Some(item) = &stack.def.item {
            stack.def.item = Some(self.with_names(|names| names.spell(item)));
        }
        crate::items::to_value(&stack)
    }
}

impl ProjectItemApi for GameContext {
    fn id(&self, this: &handles::ProjectItem) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.id)))
    }
    fn exists(&self, this: &handles::ProjectItem) -> ApiResult<bool> {
        Ok(self.shared.items.borrow().records.contains_key(&this.id))
    }
    fn create(
        &self,
        this: &handles::ProjectItem,
        overrides: Value,
    ) -> ApiResult<Option<values::Item>> {
        if !self.shared.items.borrow().records.contains_key(&this.id) {
            return Ok(None);
        }
        let overrides = self.overrides(overrides)?;
        let stack = self.items().create(&this.id, overrides)?;
        Ok(Some(self.spelled_item(&stack)))
    }
    fn properties(&self, this: &handles::ProjectItem) -> ApiResult<Value> {
        let properties = {
            let items = self.shared.items.borrow();
            let Some(record) = items.records.get(&this.id) else {
                return Ok(Value::Nil);
            };
            serde_json::to_string(&record.file.properties.clone().unwrap_or_default())
                .map_err(|e| err(e.to_string()))?
        };
        netherforge_luau::json::decode(
            self.scripts.host().lua(),
            &properties,
            &netherforge_luau::json::NoTags,
        )
        .map_err(err)
    }
    fn on(
        &self,
        this: &handles::ProjectItem,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::ProjectItem(this.clone()),
            "ProjectItem:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::ProjectItem,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::ProjectItem(this.clone()),
            "ProjectItem:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// Project items as a session's service.
pub struct ItemsService {
    pub shared: Rc<Shared>,
    pub scripts: std::cell::RefCell<Option<crate::Scripts>>,
}

impl ItemsService {
    fn context(&self, room: &Room) -> ItemContext {
        ItemContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }

    fn define(&self, project: &Define) -> BTreeMap<String, ItemFile> {
        let running: BTreeMap<String, ItemFile> = project
            .project
            .running::<ItemFile>()
            .into_iter()
            .map(|(id, f)| (id, f.clone()))
            .collect();
        let mut scripts = BTreeMap::new();
        for (id, resource) in project.project.everywhere("item") {
            let Some(script) = running.get(&id).and_then(|f| f.script.as_ref()) else {
                continue;
            };
            let found = &resource.found;
            let folder = found.kind.location_of(&found.id, &found.group);
            let path = format!("{folder}/{}", script.file);
            let path = match netherforge_format::kinds::split_package(&id) {
                (Some(namespace), _) => netherforge_format::kinds::package_path(namespace, &path),
                (None, _) => path,
            };
            scripts.insert(id, path);
        }
        let mut items = self.shared.items.borrow_mut();
        items.scripts = scripts;
        items.home = project.project.namespace().to_owned();
        running
    }
}

impl Service for ItemsService {
    fn name(&self) -> &'static str {
        "items"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["item"]
    }

    /// Takes every item's look, before any window or script builds a stack.
    fn start(&self, room: &Room, project: &Define) {
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
        let running = self.define(project);
        let mut items = self.shared.items.borrow_mut();
        items.records = running
            .into_iter()
            .map(|(id, file)| (id, Record::new(file)))
            .collect();
        // A new session's handlers aren't the last one's: whatever those showed goes.
        items.views_changed = true;
    }

    fn after_start(&self, room: &Room) {
        let context = self.context(room);
        let ids: Vec<String> = self.shared.items.borrow().records.keys().cloned().collect();
        for id in ids {
            context.start_script(&id);
        }
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).tick();
    }

    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let running = self.define(project);
        let context = self.context(room);
        for id in ids {
            context.reload(id, running.get(id).cloned());
        }
    }

    /// Stacks they carry from before the last change to an item take its new look.
    fn player_joined(&self, player: &PlayerRef) {
        if self.shared.items.borrow().records.is_empty() {
            return;
        }
        self.shared
            .platform
            .project_items()
            .refresh(InventoryRef::Player(InventoryRefPlayer {
                player: player.uuid,
            }));
    }

    fn player_quit(&self, player: &PlayerRef) {
        let mut items = self.shared.items.borrow_mut();
        items.views_pending.remove(&player.uuid);
        items.pending.remove(&player.uuid);
    }

    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        let ids: Vec<String> = self.shared.items.borrow().records.keys().cloned().collect();
        for id in ids {
            context.stop_script(&id);
        }
        *self.shared.items.borrow_mut() = ProjectItems::default();
    }
}
