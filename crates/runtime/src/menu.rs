//! Menus (Kotlin's `Menus` and `MenuImpl`): every open window and the script behind each.
//!
//! A **window** is to `menu.json` what an instance is to a centity. A `shared` menu has one, made as the session
//! starts (before any script runs, so a module's body finds it) and named by the menu's own id; any other gets a
//! window per opening, named by a fresh UUID, retired the tick after its last viewer closes it.
//!
//! Each window runs its own copy of the menu's script, in its own scope, with `this` the window. A click is heard
//! by the clicked slot's handlers, then the window's, then `nf.on("menu_click")`; a locked window starts every
//! click that would move items in or out of it already cancelled, which a handler may undo. When a window goes,
//! so does every handler on it and its slots.
//!
//! Not yet: slots bound to storages and equipment, slot rules and `shift_into` (the next slice);
//! `nf.menus.create` and templates; skins in titles (they wait for the built resource pack: a skin draws
//! nothing until then, and the words show on their own).

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{MenuApi, MenuTemplateApi, NfMenusApi, SlotApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, codecs, shapes, unions, values};
use netherforge_format::kind::menu::CompiledMenu;
use netherforge_luau::ScopeId;
use netherforge_platform::{ItemData, MenuClick, Platform, PlayerRef, Uuid, WindowSpec};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// One live window.
pub struct Window {
    pub id: String,
    pub uuid: Uuid,
    pub definition: Rc<CompiledMenu>,
    /// What the script that opened it passed, kept until it goes.
    pub context: Value,
    /// The title without the skin, as MiniMessage.
    pub title: Option<String>,
    pub skin: Option<String>,
    pub locked: bool,
    /// The menu's script's scope on this window, while it runs.
    scope: Option<ScopeId>,
    /// What the server was last told the title is, skin included.
    pushed_title: String,
}

/// The file's title; with no title and no skin, the menu's name, so a plain window isn't blank.
fn default_title(definition: &CompiledMenu) -> Option<String> {
    definition.title.clone().or_else(|| {
        definition
            .name
            .clone()
            .filter(|_| definition.skin.is_none())
    })
}

/// The session's menus and their windows.
#[derive(Default)]
pub struct Menus {
    definitions: BTreeMap<String, Rc<CompiledMenu>>,
    /// Each menu's script, by menu id: its project path.
    scripts: BTreeMap<String, String>,
    windows: IndexMap<String, Window>,
    retiring: Vec<String>,
}

impl Menus {
    fn by_uuid(&self, uuid: Uuid) -> Option<&Window> {
        self.windows.values().find(|w| w.uuid == uuid)
    }

    /// The skin's characters, then the words. Until the built pack is here a skin draws nothing.
    fn compose_title(window: &Window) -> String {
        window.title.clone().unwrap_or_default()
    }
}

/// The menus' state and the room's API to them.
#[derive(Clone)]
pub struct MenuContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl MenuContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    /// Makes window `id` of `definition`, fills it from the file, and starts its script when `start`.
    fn make(&self, id: String, definition: Rc<CompiledMenu>, context: Value, start: bool) -> Uuid {
        let uuid = crate::centity::saving::random_uuid();
        let window = Window {
            id: id.clone(),
            uuid,
            title: default_title(&definition),
            skin: definition.skin.clone(),
            locked: definition.locked,
            definition,
            context,
            scope: None,
            pushed_title: String::new(),
        };
        self.shared
            .menus
            .borrow_mut()
            .windows
            .insert(id.clone(), window);
        self.build(&id);
        if start {
            self.start_script(&id);
        }
        uuid
    }

    /// Creates the server's window and fills it from the file.
    fn build(&self, id: &str) {
        let (uuid, spec, slots) = {
            let mut menus = self.shared.menus.borrow_mut();
            let Some(window) = menus.windows.get_mut(id) else {
                return;
            };
            window.pushed_title = Menus::compose_title(window);
            let spec = WindowSpec {
                r#type: window.definition.kind,
                size: window.definition.size,
                title: window.pushed_title.clone(),
            };
            let slots: Vec<(i32, ItemData)> = window
                .definition
                .slots
                .iter()
                .filter_map(|(slot, def)| {
                    Some((
                        *slot,
                        ItemData {
                            def: def.item.clone()?,
                            raw: None,
                            look: None,
                        },
                    ))
                })
                .collect();
            (window.uuid, spec, slots)
        };
        let ops = self.platform().menus();
        ops.create(uuid, spec);
        for (slot, item) in slots {
            ops.set_item(uuid, slot, Some(item));
        }
    }

    /// Runs the menu's script on window `id`, with `this` the window.
    fn start_script(&self, id: &str) {
        let (menu, path) = {
            let menus = self.shared.menus.borrow();
            let Some(window) = menus.windows.get(id).filter(|w| w.scope.is_none()) else {
                return;
            };
            let menu = window.definition.id.clone();
            let Some(path) = menus.scripts.get(&menu).cloned() else {
                return;
            };
            (menu, path)
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return;
        };
        let Ok(this) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::Menu(handles::Menu { id: id.to_owned() }),
        ) else {
            return;
        };
        let folder = path
            .rsplit_once('/')
            .map_or(path.as_str(), |(f, _)| f)
            .to_owned();
        let short: String = id.chars().take(8).collect();
        let (scope, _) = modules.script_with(
            &format!("menu {menu} {short}"),
            &folder,
            &path,
            &[("this", Value::UserData(this))],
        );
        if let Some(window) = self.shared.menus.borrow_mut().windows.get_mut(id) {
            window.scope = scope;
        }
    }

    fn stop_script(&self, id: &str) {
        let scope = self
            .shared
            .menus
            .borrow_mut()
            .windows
            .get_mut(id)
            .and_then(|w| w.scope.take());
        if let Some(scope) = scope {
            match self.shared.modules.borrow().clone() {
                Some(modules) => modules.close(scope),
                None => self.scripts.close(scope),
            }
        }
    }

    /// Takes window `id` down: its script stops, every handler on it and its slots goes, and the server's window.
    fn retire(&self, id: &str) {
        self.stop_script(id);
        if let Some(target) = self.target(Handle::Menu(handles::Menu { id: id.to_owned() })) {
            self.scripts.drop_target(&target);
        }
        let size = self
            .shared
            .menus
            .borrow()
            .windows
            .get(id)
            .map_or(0, |w| w.definition.size);
        for index in 0..size {
            if let Some(target) = self.target(Handle::Slot(handles::Slot {
                menu: id.to_owned(),
                index: i64::from(index),
            })) {
                self.scripts.drop_target(&target);
            }
        }
        let gone = self.shared.menus.borrow_mut().windows.shift_remove(id);
        if let Some(window) = gone {
            self.platform().menus().destroy(window.uuid);
        }
    }

    fn target(&self, handle: Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    /// Raises `payload` along `path` (`(event, handle)`, none for `nf`), from a click or a drag: whether it ended
    /// cancelled. A locked window's click that moves items starts `precancelled`.
    fn raise<C: Codec>(
        &self,
        path: &[(&str, Option<Handle>)],
        codec: C,
        payload: C::T,
        precancelled: bool,
    ) -> bool {
        let lua = self.scripts.host().lua().clone();
        let Ok(Value::Table(fields)) = codec.push(&lua, payload) else {
            return precancelled;
        };
        let targets: Vec<(Target, &str)> = path
            .iter()
            .filter_map(|(event, handle)| {
                let target = match handle {
                    Some(h) => self.target(h.clone())?,
                    None => Target::Nf,
                };
                Some((target, *event))
            })
            .collect();
        let delivered = self.scripts.raise_path(&targets, fields, precancelled);
        delivered.cancelled
    }

    /// `open` or `close` on the window, then on `nf`.
    fn menu_event(&self, id: &str, player: &PlayerRef, own: &str, server: &str) {
        let context = self
            .shared
            .menus
            .borrow()
            .windows
            .get(id)
            .map_or(Value::Nil, |w| w.context.clone());
        let menu = handles::Menu { id: id.to_owned() };
        let payload = shapes::MenuEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            menu: menu.clone(),
            context,
        };
        self.raise(
            &[(own, Some(Handle::Menu(menu))), (server, None)],
            codecs::MenuEvent,
            payload,
            false,
        );
    }

    /// Opens `menu` for `player`: the shared window, or a new one of their own (its script's body has run before
    /// they see it) holding `context`. None when the menu or the player is gone.
    pub fn open(&self, menu: &str, player: &PlayerRef, context: Value) -> Option<String> {
        let definition = self.shared.menus.borrow().definitions.get(menu).cloned()?;
        let id = if definition.shared {
            let existing = self.shared.menus.borrow().windows.contains_key(menu);
            if !existing {
                self.make(menu.to_owned(), definition.clone(), Value::Nil, true);
            }
            menu.to_owned()
        } else {
            let id = uuid_text(&crate::centity::saving::random_uuid());
            self.make(id.clone(), definition.clone(), context, true);
            id
        };
        let uuid = self.shared.menus.borrow().windows.get(&id)?.uuid;
        if !self.platform().menus().open(uuid, player.uuid) {
            if !definition.shared {
                self.retire(&id);
            }
            return None;
        }
        self.menu_event(&id, player, "open", "menu_open");
        Some(id)
    }

    /// A click while window `uuid` was open, along its path: the clicked slot, the window, then `nf`. True means
    /// cancel.
    pub fn click(&self, uuid: Uuid, click: MenuClick) -> bool {
        let (id, size, locked, context) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.by_uuid(uuid) else {
                return false;
            };
            (w.id.clone(), w.definition.size, w.locked, w.context.clone())
        };
        let menu = handles::Menu { id: id.clone() };
        let slot = click
            .slot
            .filter(|s| click.top && (0..size).contains(s))
            .map(|s| handles::Slot {
                menu: id.clone(),
                index: i64::from(s),
            });
        let payload = shapes::MenuClickEvent {
            player: handles::Player {
                id: uuid_text(&click.player.uuid),
            },
            menu: menu.clone(),
            target: slot.clone(),
            index: click.slot.map(i64::from),
            in_menu: click.top,
            click: click.click.clone(),
            hotbar_index: click.hotbar.map(i64::from),
            item: click.item.as_ref().map(crate::items::to_value),
            cursor_item: click.cursor.as_ref().map(crate::items::to_value),
            context,
        };
        let mut path = Vec::new();
        if let Some(slot) = slot {
            path.push(("click", Some(Handle::Slot(slot))));
        }
        path.push(("click", Some(Handle::Menu(menu))));
        path.push(("menu_click", None));
        self.raise(
            &path,
            codecs::MenuClickEvent,
            payload,
            locked && click.moves_items,
        )
    }

    /// A drag of `cursor` over `slots` of the window and `own` of the player's inventory below. A locked window
    /// starts one over its own slots already cancelled. True means cancel.
    pub fn drag(
        &self,
        uuid: Uuid,
        player: &PlayerRef,
        slots: &[i32],
        cursor: Option<&ItemData>,
        own: &[i32],
        click: &str,
    ) -> bool {
        let (id, locked, context) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.by_uuid(uuid) else {
                return false;
            };
            (w.id.clone(), w.locked, w.context.clone())
        };
        let menu = handles::Menu { id };
        let payload = shapes::MenuDragEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            menu: menu.clone(),
            indices: slots.iter().map(|s| i64::from(*s)).collect(),
            own_indices: own.iter().map(|s| i64::from(*s)).collect(),
            click: click.to_owned(),
            cursor_item: cursor.map(crate::items::to_value),
            context,
        };
        self.raise(
            &[("drag", Some(Handle::Menu(menu)))],
            codecs::MenuDragEvent,
            payload,
            locked && !slots.is_empty(),
        )
    }

    /// The server says `player` closed window `uuid`: its `close`, and it's retired next tick if nobody's left.
    pub fn closed(&self, uuid: Uuid, player: &PlayerRef) {
        let Some((id, shared)) = self
            .shared
            .menus
            .borrow()
            .by_uuid(uuid)
            .map(|w| (w.id.clone(), w.definition.shared))
        else {
            return;
        };
        self.menu_event(&id, player, "close", "menu_close");
        if !shared {
            self.shared.menus.borrow_mut().retiring.push(id);
        }
    }

    fn window_uuid(&self, id: &str) -> Option<Uuid> {
        self.shared.menus.borrow().windows.get(id).map(|w| w.uuid)
    }

    fn refresh_title(&self, id: &str) {
        let pushed = {
            let mut menus = self.shared.menus.borrow_mut();
            let Some(window) = menus.windows.get_mut(id) else {
                return;
            };
            let title = Menus::compose_title(window);
            if title == window.pushed_title {
                return;
            }
            window.pushed_title.clone_from(&title);
            (window.uuid, title)
        };
        self.platform().menus().retitle(pushed.0, pushed.1);
    }

    /// The window of ours `player` is looking at.
    pub fn viewing(&self, player: Uuid) -> Option<String> {
        let uuid = self.platform().menus().viewing(player)?;
        self.shared
            .menus
            .borrow()
            .by_uuid(uuid)
            .map(|w| w.id.clone())
    }

    /// Closes window `id` for `player` (false when they aren't viewing it), or for everyone when none.
    pub fn close(&self, id: &str, player: Option<Uuid>) -> bool {
        let Some(uuid) = self.window_uuid(id) else {
            return false;
        };
        let ops = self.platform().menus();
        if let Some(p) = player
            && !ops.viewers(uuid).contains(&p)
        {
            return false;
        }
        ops.close(uuid, player);
        true
    }

    /// Moves every window of menu `id` onto `next`, or takes them down when it's gone. A window whose container
    /// and size didn't change is kept (viewers and contents stay, but for slots whose authored item changed);
    /// one whose shape changed is rebuilt and reopened for its viewers. Either way its script restarts.
    fn reload(&self, id: &str, next: Option<Rc<CompiledMenu>>) {
        let previous = self.shared.menus.borrow().definitions.get(id).cloned();
        let live: Vec<String> = self
            .shared
            .menus
            .borrow()
            .windows
            .values()
            .filter(|w| w.definition.id == id)
            .map(|w| w.id.clone())
            .collect();
        let Some(next) = next else {
            self.shared.menus.borrow_mut().definitions.remove(id);
            for window in live {
                self.retire(&window);
            }
            return;
        };
        self.shared
            .menus
            .borrow_mut()
            .definitions
            .insert(id.to_owned(), next.clone());
        let ops = self.platform().menus();
        for window in live {
            self.stop_script(&window);
            let shared_before = previous.as_ref().is_some_and(|p| p.shared);
            if shared_before && !next.shared {
                self.retire(&window);
                continue;
            }
            let same_shape = previous
                .as_ref()
                .is_some_and(|p| p.kind == next.kind && p.size == next.size);
            let uuid = self.window_uuid(&window).expect("live");
            if same_shape {
                let previous = previous.as_ref().expect("same shape");
                {
                    let mut menus = self.shared.menus.borrow_mut();
                    let w = menus.windows.get_mut(&window).expect("live");
                    w.definition = next.clone();
                    if previous.title != next.title || previous.name != next.name {
                        w.title = default_title(&next);
                    }
                    if previous.skin != next.skin {
                        w.skin.clone_from(&next.skin);
                    }
                    if previous.locked != next.locked {
                        w.locked = next.locked;
                    }
                }
                let slots: BTreeSet<i32> = previous
                    .slots
                    .keys()
                    .chain(next.slots.keys())
                    .copied()
                    .collect();
                for slot in slots {
                    let before = previous.slots.get(&slot).and_then(|s| s.item.clone());
                    let after = next.slots.get(&slot).and_then(|s| s.item.clone());
                    if before != after {
                        ops.set_item(
                            uuid,
                            slot,
                            after.map(|def| ItemData {
                                def,
                                raw: None,
                                look: None,
                            }),
                        );
                    }
                }
                self.refresh_title(&window);
            } else {
                let viewers = ops.viewers(uuid);
                ops.destroy(uuid);
                {
                    let mut menus = self.shared.menus.borrow_mut();
                    let w = menus.windows.get_mut(&window).expect("live");
                    w.definition = next.clone();
                    w.title = default_title(&next);
                    w.skin.clone_from(&next.skin);
                    w.locked = next.locked;
                }
                self.build(&window);
                for viewer in viewers {
                    ops.open(uuid, viewer);
                }
            }
            self.start_script(&window);
        }
        if next.shared && !self.shared.menus.borrow().windows.contains_key(id) {
            self.make(id.to_owned(), next, Value::Nil, true);
        }
    }

    /// Windows nobody is looking at any more go. Once a tick.
    fn retire_unseen(&self) {
        let retiring = std::mem::take(&mut self.shared.menus.borrow_mut().retiring);
        let ops = self.platform().menus();
        for id in retiring {
            let Some(uuid) = self.window_uuid(&id) else {
                continue;
            };
            if ops.viewers(uuid).is_empty() {
                self.retire(&id);
            }
        }
    }

    /// Tells `scripts` which menu and slot handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let menus = shared.menus.try_borrow().ok()?;
            match handle {
                Handle::Menu(m) => Some(menus.windows.contains_key(&m.id)),
                Handle::Slot(s) => Some(
                    menus
                        .windows
                        .get(&s.menu)
                        .is_some_and(|w| (0..i64::from(w.definition.size)).contains(&s.index)),
                ),
                _ => None,
            }
        }));
    }
}

/// `index` as a slot of a window of `size`; outside it is the script's mistake.
fn slot_in(index: i64, size: i32) -> ApiResult<i32> {
    if !(0..i64::from(size)).contains(&index) {
        return Err(err(format!(
            "slot {index} is outside this window: it has {size} slots, 0 to {}",
            size - 1
        )));
    }
    Ok(index as i32)
}

impl GameContext {
    fn menus(&self) -> MenuContext {
        MenuContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    fn window<R>(&self, id: &str, f: impl FnOnce(&Window) -> R) -> Option<R> {
        self.shared.menus.borrow().windows.get(id).map(f)
    }

    fn item_of_value(&self, item: Option<values::Item>) -> ApiResult<Option<ItemData>> {
        item.map(|i| crate::items::from_value(&i, "item", self.shared.platform.game()).map_err(err))
            .transpose()
    }

    /// `player:open_menu`: a menu file by name; a template waits for templates.
    pub(crate) fn open_menu(
        &self,
        player: &handles::Player,
        menu: unions::StringOrMenuTemplate,
        options: Option<shapes::MenuOpenOptions>,
    ) -> ApiResult<Option<handles::Menu>> {
        let unions::StringOrMenuTemplate::String(name) = menu else {
            return Err(err(
                "Player:open_menu with a template isn't on the Rust runtime yet",
            ));
        };
        let menu = self.with_names(|names| names.resource("menu", &name))?;
        let definition = self.shared.menus.borrow().definitions.get(&menu).cloned();
        let Some(definition) = definition else {
            let mut known: Vec<String> = self
                .shared
                .menus
                .borrow()
                .definitions
                .keys()
                .cloned()
                .collect();
            known.sort();
            let has = if known.is_empty() {
                String::new()
            } else {
                format!(" (it has: {})", known.join(", "))
            };
            return Err(err(format!("no menu \"{name}\" in this project{has}")));
        };
        let context = options.map_or(Value::Nil, |o| o.context);
        if !matches!(context, Value::Nil) && definition.shared {
            return Err(err(format!(
                "menu {name} is shared: everyone sees its one window, so it can't be opened with a context"
            )));
        }
        let Some(online) =
            parse_uuid(&player.id).and_then(|id| self.shared.platform.players().get(id))
        else {
            return Ok(None);
        };
        Ok(self
            .menus()
            .open(&menu, &online, context)
            .map(|id| handles::Menu { id }))
    }

    pub(crate) fn menu_of(&self, player: &handles::Player) -> Option<handles::Menu> {
        let id = parse_uuid(&player.id)?;
        self.menus().viewing(id).map(|id| handles::Menu { id })
    }

    pub(crate) fn close_menu_of(&self, player: &handles::Player) -> bool {
        let Some(uuid) = parse_uuid(&player.id) else {
            return false;
        };
        let Some(window) = self.menus().viewing(uuid) else {
            return false;
        };
        self.menus().close(&window, Some(uuid))
    }
}

impl NfMenusApi for GameContext {
    fn shared(&self, _: &Caller, menu: String) -> ApiResult<Option<handles::Menu>> {
        let id = self.with_names(|names| names.resource("menu", &menu))?;
        Ok(self
            .window(&id, |w| w.definition.shared)
            .filter(|shared| *shared)
            .map(|_| handles::Menu { id }))
    }

    fn create(&self, caller: &Caller, definition: Value) -> ApiResult<handles::MenuTemplate> {
        <Unported as NfMenusApi>::create(&Unported, caller, definition)
    }
}

impl MenuApi for GameContext {
    fn id(&self, this: &handles::Menu) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.id)))
    }
    fn kind(&self, this: &handles::Menu) -> ApiResult<Option<String>> {
        Ok(self
            .window(&this.id, |w| w.definition.id.clone())
            .map(|k| self.with_names(|names| names.spell(&k))))
    }
    fn template(&self, _: &handles::Menu) -> ApiResult<Option<handles::MenuTemplate>> {
        Ok(None)
    }
    fn exists(&self, this: &handles::Menu) -> ApiResult<bool> {
        Ok(self.window(&this.id, |_| ()).is_some())
    }
    fn is_shared(&self, this: &handles::Menu) -> ApiResult<bool> {
        Ok(self.window(&this.id, |w| w.definition.shared) == Some(true))
    }
    fn context(&self, this: &handles::Menu) -> ApiResult<Value> {
        Ok(self
            .window(&this.id, |w| w.context.clone())
            .unwrap_or(Value::Nil))
    }
    fn size(&self, this: &handles::Menu) -> ApiResult<i64> {
        Ok(self
            .window(&this.id, |w| i64::from(w.definition.size))
            .unwrap_or(0))
    }
    fn rows(&self, this: &handles::Menu) -> ApiResult<i64> {
        Ok(self
            .window(&this.id, |w| i64::from(w.definition.rows))
            .unwrap_or(0))
    }
    fn title(&self, this: &handles::Menu) -> ApiResult<Option<values::Text>> {
        Ok(self
            .window(&this.id, |w| w.title.clone())
            .flatten()
            .map(values::Text))
    }
    fn set_title(&self, this: &handles::Menu, text: values::Text) -> ApiResult<bool> {
        let set = self
            .shared
            .menus
            .borrow_mut()
            .windows
            .get_mut(&this.id)
            .map(|w| w.title = Some(text.0))
            .is_some();
        if set {
            self.menus().refresh_title(&this.id);
        }
        Ok(set)
    }
    fn skin(&self, this: &handles::Menu) -> ApiResult<Option<String>> {
        Ok(self
            .window(&this.id, |w| w.skin.clone())
            .flatten()
            .map(|s| self.with_names(|names| names.spell(&s))))
    }
    fn set_skin(&self, this: &handles::Menu, skin: Option<String>) -> ApiResult<bool> {
        let named = match &skin {
            Some(skin) => Some(self.with_names(|names| {
                names.look_name(netherforge_format::reference::RefKind::Skin, skin)
            })?),
            None => None,
        };
        let set = self
            .shared
            .menus
            .borrow_mut()
            .windows
            .get_mut(&this.id)
            .map(|w| w.skin = named)
            .is_some();
        if set {
            self.menus().refresh_title(&this.id);
        }
        Ok(set)
    }
    fn is_locked(&self, this: &handles::Menu) -> ApiResult<bool> {
        Ok(self.window(&this.id, |w| w.locked) == Some(true))
    }
    fn set_locked(&self, this: &handles::Menu, locked: bool) -> ApiResult<()> {
        if let Some(w) = self.shared.menus.borrow_mut().windows.get_mut(&this.id) {
            w.locked = locked;
        }
        Ok(())
    }
    fn shift_into(
        &self,
        this: &handles::Menu,
        storage: handles::Inventory,
        first: Option<i64>,
        last: Option<i64>,
    ) -> ApiResult<bool> {
        <Unported as MenuApi>::shift_into(&Unported, this, storage, first, last)
    }
    /// Checked against the window while it's there; a gone window's slots are handed out and answer nil.
    fn slot(&self, this: &handles::Menu, index: i64) -> ApiResult<handles::Slot> {
        if let Some(size) = self.window(&this.id, |w| w.definition.size) {
            slot_in(index, size)?;
        }
        Ok(handles::Slot {
            menu: this.id.clone(),
            index,
        })
    }
    fn item(&self, this: &handles::Menu, index: i64) -> ApiResult<Option<values::Item>> {
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(None);
        };
        Ok(self
            .shared
            .platform
            .menus()
            .item(uuid, slot_in(index, size)?)
            .map(|i| crate::items::to_value(&i)))
    }
    fn set_item(
        &self,
        this: &handles::Menu,
        index: i64,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = self.item_of_value(item)?;
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .menus()
            .set_item(uuid, slot_in(index, size)?, item))
    }
    /// How many didn't fit: all of them when the window is gone.
    fn add_item(&self, this: &handles::Menu, item: values::Item) -> ApiResult<i64> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        let Some(uuid) = self.window(&this.id, |w| w.uuid) else {
            return Ok(i64::from(item.def.count.unwrap_or(1)));
        };
        Ok(i64::from(self.shared.platform.menus().add(uuid, item)))
    }
    fn items(&self, this: &handles::Menu) -> ApiResult<BTreeMap<i64, values::Item>> {
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(BTreeMap::new());
        };
        let ops = self.shared.platform.menus();
        Ok((0..size)
            .filter_map(|slot| {
                Some((
                    i64::from(slot),
                    crate::items::to_value(&ops.item(uuid, slot)?),
                ))
            })
            .collect())
    }
    fn set_items(
        &self,
        this: &handles::Menu,
        items: BTreeMap<i64, values::Item>,
    ) -> ApiResult<bool> {
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(false);
        };
        // Every slot is checked before any changes.
        let mut slots = Vec::new();
        for (index, item) in items {
            slots.push((slot_in(index, size)?, self.item_of_value(Some(item))?));
        }
        let ops = self.shared.platform.menus();
        for (slot, item) in slots {
            ops.set_item(uuid, slot, item);
        }
        Ok(true)
    }
    fn fill(
        &self,
        this: &handles::Menu,
        item: values::Item,
        indices: Option<Vec<i64>>,
    ) -> ApiResult<bool> {
        let item = self.item_of_value(Some(item))?;
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(false);
        };
        let ops = self.shared.platform.menus();
        let slots: Vec<i32> = match indices {
            Some(indices) => indices
                .into_iter()
                .map(|i| slot_in(i, size))
                .collect::<ApiResult<_>>()?,
            None => (0..size).filter(|s| ops.item(uuid, *s).is_none()).collect(),
        };
        for slot in slots {
            ops.set_item(uuid, slot, item.clone());
        }
        Ok(true)
    }
    fn clear(&self, this: &handles::Menu) -> ApiResult<bool> {
        let Some((uuid, size)) = self.window(&this.id, |w| (w.uuid, w.definition.size)) else {
            return Ok(false);
        };
        let ops = self.shared.platform.menus();
        for slot in 0..size {
            ops.set_item(uuid, slot, None);
        }
        Ok(true)
    }
    fn viewers(&self, this: &handles::Menu) -> ApiResult<Vec<handles::Player>> {
        let Some(uuid) = self.window(&this.id, |w| w.uuid) else {
            return Ok(Vec::new());
        };
        let platform = &*self.shared.platform;
        Ok(platform
            .menus()
            .viewers(uuid)
            .into_iter()
            .filter_map(|id| platform.players().get(id))
            .map(|p| handles::Player {
                id: uuid_text(&p.uuid),
            })
            .collect())
    }
    fn cursor_item(
        &self,
        this: &handles::Menu,
        player: handles::Player,
    ) -> ApiResult<Option<values::Item>> {
        let (Some(uuid), Some(viewer)) =
            (self.window(&this.id, |w| w.uuid), parse_uuid(&player.id))
        else {
            return Ok(None);
        };
        Ok(self
            .shared
            .platform
            .menus()
            .cursor(uuid, viewer)
            .map(|i| crate::items::to_value(&i)))
    }
    fn set_cursor_item(
        &self,
        this: &handles::Menu,
        player: handles::Player,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = self.item_of_value(item)?;
        let (Some(uuid), Some(viewer)) =
            (self.window(&this.id, |w| w.uuid), parse_uuid(&player.id))
        else {
            return Ok(false);
        };
        Ok(self.shared.platform.menus().set_cursor(uuid, viewer, item))
    }
    fn refresh(&self, this: &handles::Menu) -> ApiResult<bool> {
        let Some(uuid) = self.window(&this.id, |w| w.uuid) else {
            return Ok(false);
        };
        self.shared.platform.menus().refresh(uuid);
        Ok(true)
    }
    /// Shows the window to one more player.
    fn open_for(&self, this: &handles::Menu, player: handles::Player) -> ApiResult<bool> {
        let Some(uuid) = self.window(&this.id, |w| w.uuid) else {
            return Ok(false);
        };
        let Some(online) =
            parse_uuid(&player.id).and_then(|id| self.shared.platform.players().get(id))
        else {
            return Ok(false);
        };
        if !self.shared.platform.menus().open(uuid, online.uuid) {
            return Ok(false);
        }
        self.menus()
            .menu_event(&this.id, &online, "open", "menu_open");
        Ok(true)
    }
    fn close_for(&self, this: &handles::Menu, player: handles::Player) -> ApiResult<bool> {
        let Some(viewer) = parse_uuid(&player.id) else {
            return Ok(false);
        };
        Ok(self.menus().close(&this.id, Some(viewer)))
    }
    fn close_all(&self, this: &handles::Menu) -> ApiResult<bool> {
        Ok(self.menus().close(&this.id, None))
    }
    fn on(
        &self,
        this: &handles::Menu,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Menu(this.clone()),
            "Menu:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::Menu,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Menu(this.clone()),
            "Menu:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl SlotApi for GameContext {
    fn index(&self, this: &handles::Slot) -> ApiResult<i64> {
        Ok(this.index)
    }
    fn menu(&self, this: &handles::Slot) -> ApiResult<handles::Menu> {
        Ok(handles::Menu {
            id: this.menu.clone(),
        })
    }
    fn item(&self, this: &handles::Slot) -> ApiResult<Option<values::Item>> {
        MenuApi::item(
            self,
            &handles::Menu {
                id: this.menu.clone(),
            },
            this.index,
        )
    }
    fn set_item(&self, this: &handles::Slot, item: Option<values::Item>) -> ApiResult<bool> {
        MenuApi::set_item(
            self,
            &handles::Menu {
                id: this.menu.clone(),
            },
            this.index,
            item,
        )
    }
    fn bind(
        &self,
        this: &handles::Slot,
        storage: handles::Inventory,
        index: Option<i64>,
    ) -> ApiResult<bool> {
        <Unported as SlotApi>::bind(&Unported, this, storage, index)
    }
    fn bind_equipment(
        &self,
        this: &handles::Slot,
        player: handles::Player,
        equipment: String,
    ) -> ApiResult<bool> {
        <Unported as SlotApi>::bind_equipment(&Unported, this, player, equipment)
    }
    fn set_rule(&self, this: &handles::Slot, rule: Option<shapes::SlotRule>) -> ApiResult<bool> {
        <Unported as SlotApi>::set_rule(&Unported, this, rule)
    }
    fn rule(&self, this: &handles::Slot) -> ApiResult<Option<shapes::SlotRule>> {
        <Unported as SlotApi>::rule(&Unported, this)
    }
    fn accepts(&self, this: &handles::Slot, item: values::Item) -> ApiResult<bool> {
        <Unported as SlotApi>::accepts(&Unported, this, item)
    }
    fn unbind(&self, this: &handles::Slot) -> ApiResult<bool> {
        <Unported as SlotApi>::unbind(&Unported, this)
    }
    fn binding(&self, this: &handles::Slot) -> ApiResult<Option<shapes::SlotBinding>> {
        <Unported as SlotApi>::binding(&Unported, this)
    }
    fn on(
        &self,
        this: &handles::Slot,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Slot(this.clone()),
            "Slot:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::Slot,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Slot(this.clone()),
            "Slot:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl MenuTemplateApi for GameContext {
    fn id(&self, this: &handles::MenuTemplate) -> ApiResult<String> {
        Ok(this.id.clone())
    }
    fn exists(&self, _: &handles::MenuTemplate) -> ApiResult<bool> {
        Ok(false)
    }
    fn size(&self, this: &handles::MenuTemplate) -> ApiResult<i64> {
        <Unported as MenuTemplateApi>::size(&Unported, this)
    }
    fn rows(&self, this: &handles::MenuTemplate) -> ApiResult<i64> {
        <Unported as MenuTemplateApi>::rows(&Unported, this)
    }
    fn title(&self, this: &handles::MenuTemplate) -> ApiResult<Option<values::Text>> {
        <Unported as MenuTemplateApi>::title(&Unported, this)
    }
    fn skin(&self, this: &handles::MenuTemplate) -> ApiResult<Option<String>> {
        <Unported as MenuTemplateApi>::skin(&Unported, this)
    }
    fn is_locked(&self, this: &handles::MenuTemplate) -> ApiResult<bool> {
        <Unported as MenuTemplateApi>::is_locked(&Unported, this)
    }
    fn item(&self, this: &handles::MenuTemplate, index: i64) -> ApiResult<Option<values::Item>> {
        <Unported as MenuTemplateApi>::item(&Unported, this, index)
    }
    fn windows(&self, this: &handles::MenuTemplate) -> ApiResult<Vec<handles::Menu>> {
        <Unported as MenuTemplateApi>::windows(&Unported, this)
    }
    fn remove(&self, this: &handles::MenuTemplate) -> ApiResult<bool> {
        <Unported as MenuTemplateApi>::remove(&Unported, this)
    }
    fn on(
        &self,
        this: &handles::MenuTemplate,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        <Unported as MenuTemplateApi>::on(&Unported, this, event, handler, options)
    }
    fn once(
        &self,
        this: &handles::MenuTemplate,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        <Unported as MenuTemplateApi>::once(&Unported, this, event, handler)
    }
}

/// Menus as a session's service.
pub struct MenusService {
    pub shared: Rc<Shared>,
    pub scripts: std::cell::RefCell<Option<crate::Scripts>>,
}

impl MenusService {
    fn context(&self, room: &Room) -> MenuContext {
        MenuContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }

    fn define(&self, project: &Define) -> BTreeMap<String, Rc<CompiledMenu>> {
        let compiled = project.project.compiled::<CompiledMenu>();
        let mut scripts = BTreeMap::new();
        for (id, resource) in project.project.everywhere("menu") {
            let Some(script) = compiled
                .iter()
                .find(|(c, _)| *c == id)
                .and_then(|(_, c)| c.script.as_ref())
            else {
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
        self.shared.menus.borrow_mut().scripts = scripts;
        compiled
            .into_iter()
            .map(|(id, c)| (id, Rc::new(c.clone())))
            .collect()
    }
}

impl Service for MenusService {
    fn name(&self) -> &'static str {
        "menus"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["menu"]
    }

    /// Takes every definition and makes every shared menu's window; their scripts start once modules run.
    fn start(&self, room: &Room, project: &Define) {
        let definitions = self.define(project);
        self.shared.menus.borrow_mut().definitions = definitions.clone();
        let context = self.context(room);
        for (id, definition) in definitions {
            if definition.shared {
                context.make(id, definition, Value::Nil, false);
            }
        }
    }

    fn after_start(&self, room: &Room) {
        let context = self.context(room);
        let ids: Vec<String> = self.shared.menus.borrow().windows.keys().cloned().collect();
        for id in ids {
            context.start_script(&id);
        }
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        self.context(room).retire_unseen();
    }

    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let definitions = self.define(project);
        let context = self.context(room);
        for id in ids {
            context.reload(id, definitions.get(id).cloned());
        }
    }

    /// Every window goes.
    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        let ids: Vec<String> = self.shared.menus.borrow().windows.keys().cloned().collect();
        for id in ids {
            context.retire(&id);
        }
        *self.shared.menus.borrow_mut() = Menus::default();
    }
}

/// The ids of every window, for tests and `/nf`.
pub fn window_ids(shared: &Shared) -> HashSet<String> {
    shared.menus.borrow().windows.keys().cloned().collect()
}
