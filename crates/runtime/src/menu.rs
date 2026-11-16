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
//! A slot of a window that isn't shared can be **bound** to a slot of a player's storage, or to what a player
//! wears: whatever goes in or out of it is written there as it happens (a click the game applies is heard straight
//! after, `menu_changed`), so an item is only ever in the storage or out of it. A slot's **rule** says which stacks
//! it takes and how many; `shift_into` moves a shift-click from below into a storage's bound slots, offering it to
//! each slot's handlers.
//!
//! Skins in titles are drawn from the built pack: before there is one, the words show on their own.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{MenuApi, MenuTemplateApi, NfMenusApi, SlotApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, codecs, shapes, unions, values};
use netherforge_format::kind::menu::CompiledMenu;
use netherforge_luau::ScopeId;
use netherforge_platform::{
    InventoryRef, InventoryRefPlayer, InventoryRefStorage, ItemData, MenuClick, Platform,
    PlayerRef, Uuid, WindowSpec,
};

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
    /// Its slots bound to a storage's, by slot.
    bindings: HashMap<i32, Binding>,
    /// Its slots bound to what a player wears, by slot.
    worn: HashMap<i32, Worn>,
    /// What its slots take, for those a script gave a rule, by slot.
    rules: HashMap<i32, Rule>,
    /// Where a shift-click from the player's own inventory goes, when a script set it.
    shift_into: Option<ShiftInto>,
    /// The template it was made from, by id; none for a window of a menu file.
    template: Option<String>,
}

impl Window {
    /// Whether `slot` shows a storage's slot or a player's equipment: theirs to use by hand.
    fn bound(&self, slot: i32) -> bool {
        self.bindings.contains_key(&slot) || self.worn.contains_key(&slot)
    }
}

/// Which slot of a storage a window's slot shows.
#[derive(Clone)]
struct Binding {
    storage: InventoryRefStorage,
    index: i32,
}

/// A slot that shows what `player` wears in `slot` (`head`, `chest`, `legs`, `feet`, `off_hand`).
#[derive(Clone)]
struct Worn {
    player: Uuid,
    slot: String,
}

/// What a slot takes: `takes` says which stacks, `max` how many of one; `source` is as the script gave it.
#[derive(Clone)]
struct Rule {
    source: shapes::SlotRule,
    max: Option<i32>,
    takes: Rc<dyn Fn(&ItemData) -> bool>,
}

/// Where a shift-click from the player's inventory goes: the window's slots bound to `storage`'s, `first` to `last`.
#[derive(Clone)]
struct ShiftInto {
    storage: InventoryRefStorage,
    first: i32,
    last: i32,
}

fn same(a: &InventoryRefStorage, b: &InventoryRefStorage) -> bool {
    a.player == b.player && a.name == b.name
}

/// What a click on a ruled slot does about the rule.
enum Ruled {
    /// A stack the slot doesn't take: the click is refused.
    Refuse,
    /// The game's own move is fine.
    Allowed,
    /// Only some of the stack may go in: the slot gets `held`, the cursor `rest`.
    Move {
        slot: i32,
        held: Box<ItemData>,
        player: Uuid,
        rest: Option<Box<ItemData>>,
    },
}

fn with_count(item: &ItemData, count: i32) -> ItemData {
    let mut item = item.clone();
    item.def.count = Some(count);
    item
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
    /// Menus scripts made (`nf.menus.create`), by id: no file and no script.
    templates: IndexMap<String, Template>,
}

/// A menu a script made, until it's removed or the scope that made it stops.
pub struct Template {
    pub definition: Rc<CompiledMenu>,
    pub owner: ScopeId,
}

impl Menus {
    fn by_uuid(&self, uuid: Uuid) -> Option<&Window> {
        self.windows.values().find(|w| w.uuid == uuid)
    }

    /// The template window `id` was made from, while it's there.
    fn template_of(&self, id: &str) -> Option<handles::MenuTemplate> {
        let template = self.windows.get(id)?.template.clone()?;
        self.templates
            .contains_key(&template)
            .then_some(handles::MenuTemplate { id: template })
    }

    /// Every open window made from template `id`.
    fn windows_of(&self, id: &str) -> Vec<String> {
        self.windows
            .values()
            .filter(|w| w.template.as_deref() == Some(id))
            .map(|w| w.id.clone())
            .collect()
    }

    /// The skin's characters, then the words. A window type that centres its title (a dispenser) moves the
    /// zero-wide skin prefix along with the words, so the skin is shifted back by as much: known exactly from the
    /// words' width in the default font (none for no words), and left where it falls when that can't be measured.
    fn compose_title(shared: &Shared, window: &Window) -> String {
        let words = window.title.clone().unwrap_or_default();
        let Some(skin) = &window.skin else {
            return words;
        };
        let width = if words.is_empty() {
            Some(0)
        } else {
            let font = shared.font.borrow();
            font.as_ref()
                .and_then(|f| netherforge_format::text::TextWidth::of_font(f).measure(&words))
        };
        let shift = width.map_or(0, |w| window.definition.kind.skin_shift(w));
        let skin = shared
            .packs
            .borrow()
            .skin_title(skin, shift)
            .unwrap_or_default();
        skin + &words
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
            bindings: HashMap::new(),
            worn: HashMap::new(),
            rules: HashMap::new(),
            shift_into: None,
            template: None,
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
            window.pushed_title = Menus::compose_title(&self.shared, window);
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

    /// `open` or `close` on the window, then on its template, then on `nf`.
    fn menu_event(&self, id: &str, player: &PlayerRef, own: &str, server: &str) {
        let context = self
            .shared
            .menus
            .borrow()
            .windows
            .get(id)
            .map_or(Value::Nil, |w| w.context.clone());
        let menu = handles::Menu { id: id.to_owned() };
        let template = self.shared.menus.borrow().template_of(id);
        let payload = shapes::MenuEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            menu: menu.clone(),
            context,
        };
        let mut path = vec![(own, Some(Handle::Menu(menu)))];
        if let Some(template) = template {
            path.push((own, Some(Handle::MenuTemplate(template))));
        }
        path.push((server, None));
        self.raise(&path, codecs::MenuEvent, payload, false);
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

    /// Opens a new window of template `template` for `player`, holding `context`. None when the template or the
    /// player is gone.
    pub fn open_template(
        &self,
        template: &str,
        player: &PlayerRef,
        context: Value,
    ) -> Option<String> {
        let definition = self
            .shared
            .menus
            .borrow()
            .templates
            .get(template)?
            .definition
            .clone();
        let id = uuid_text(&crate::centity::saving::random_uuid());
        let uuid = self.make(id.clone(), definition, context, false);
        if let Some(window) = self.shared.menus.borrow_mut().windows.get_mut(&id) {
            window.template = Some(template.to_owned());
        }
        if !self.platform().menus().open(uuid, player.uuid) {
            self.retire(&id);
            return None;
        }
        self.menu_event(&id, player, "open", "menu_open");
        Some(id)
    }

    /// Takes template `id` away: its windows retire and the handlers on it go.
    pub fn remove_template(&self, id: &str) -> bool {
        if self
            .shared
            .menus
            .borrow_mut()
            .templates
            .shift_remove(id)
            .is_none()
        {
            return false;
        }
        let windows = self.shared.menus.borrow().windows_of(id);
        for window in windows {
            self.retire(&window);
        }
        if let Some(target) = self.target(Handle::MenuTemplate(handles::MenuTemplate {
            id: id.to_owned(),
        })) {
            self.scripts.drop_target(&target);
        }
        true
    }

    /// A click while window `uuid` was open, along its path: the clicked slot, the window, then `nf`. True means
    /// cancel. A locked window starts a click that moves items already cancelled, but for its bound slots, which
    /// are the player's to use by hand. A shift-click from below into a window with `shift_into` is moved by the
    /// runtime; a click past a slot's rule is refused, or moves only what the rule lets in.
    pub fn click(&self, uuid: Uuid, click: MenuClick) -> bool {
        let (id, size, locked, context, shifting, rule, by_hand) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.by_uuid(uuid) else {
                return false;
            };
            let shifting = w.shift_into.is_some()
                && !click.top
                && ["shift_left", "shift_right"].contains(&click.click.as_str());
            let rule = click
                .slot
                .filter(|_| click.top)
                .and_then(|s| w.rules.get(&s).cloned());
            let by_hand =
                click.top && click.slot.is_some_and(|s| w.bound(s)) && click.click != "double";
            (
                w.id.clone(),
                w.definition.size,
                w.locked,
                w.context.clone(),
                shifting,
                rule,
                by_hand,
            )
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
        if let Some(template) = self.shared.menus.borrow().template_of(&id) {
            path.push(("click", Some(Handle::MenuTemplate(template))));
        }
        path.push(("menu_click", None));
        if shifting {
            // The runtime moves the stack itself, slot by slot, so the game's own move is always stopped.
            if !self.raise(&path, codecs::MenuClickEvent, payload, false) {
                self.shift_in(&id, &click);
            }
            return true;
        }
        let ruled = match &rule {
            Some(rule) => match self.ruled(uuid, &click, rule) {
                Ruled::Refuse => return true,
                other => Some(other),
            },
            None => None,
        };
        let cancelled = self.raise(
            &path,
            codecs::MenuClickEvent,
            payload,
            locked && click.moves_items && !by_hand,
        );
        match ruled {
            Some(Ruled::Move {
                slot,
                held,
                player,
                rest,
            }) if !cancelled => {
                // More than the rule's max would go in: the runtime puts in what fits, and the game's own move is
                // stopped. The client guessed the whole stack went in, so it's sent the window again.
                self.write(&id, slot, Some(*held));
                self.platform()
                    .menus()
                    .set_cursor(uuid, player, rest.map(|r| *r));
                self.platform().menus().refresh(uuid);
                true
            }
            _ => cancelled,
        }
    }

    /// What a click on a slot with `rule` does about it.
    fn ruled(&self, uuid: Uuid, click: &MenuClick, rule: &Rule) -> Ruled {
        let Some(slot) = click.slot else {
            return Ruled::Allowed;
        };
        let ops = self.platform().menus();
        let held = ops.item(uuid, slot);
        match click.click.as_str() {
            "left" | "right" => {
                let Some(cursor) = &click.cursor else {
                    return Ruled::Allowed;
                };
                if !(rule.takes)(cursor) {
                    return Ruled::Refuse;
                }
                let Some(max) = rule.max else {
                    return Ruled::Allowed;
                };
                let count = cursor.def.count.unwrap_or(1);
                let stacks = held.is_some() && ops.room(uuid, slot, cursor.clone()) > 0;
                if held.is_some() && !stacks {
                    return if count <= max {
                        Ruled::Allowed
                    } else {
                        Ruled::Refuse
                    };
                }
                let has = held.as_ref().map_or(0, |h| h.def.count.unwrap_or(1));
                let wanted = if click.click == "right" { 1 } else { count };
                if has + wanted <= max {
                    return Ruled::Allowed;
                }
                let put = (max - has)
                    .min(wanted)
                    .min(ops.room(uuid, slot, cursor.clone()));
                if put <= 0 {
                    return Ruled::Refuse;
                }
                let rest = count - put;
                Ruled::Move {
                    slot,
                    held: Box::new(with_count(cursor, has + put)),
                    player: click.player.uuid,
                    rest: (rest > 0).then(|| Box::new(with_count(cursor, rest))),
                }
            }
            "number_key" => {
                let incoming = click.hotbar.and_then(|h| {
                    self.platform()
                        .inventories()
                        .contents(InventoryRef::Player(InventoryRefPlayer {
                            player: click.player.uuid,
                        }))?
                        .get(h as usize)
                        .cloned()
                        .flatten()
                });
                match incoming {
                    None => Ruled::Allowed,
                    Some(incoming)
                        if (rule.takes)(&incoming)
                            && incoming.def.count.unwrap_or(1) <= rule.max.unwrap_or(i32::MAX) =>
                    {
                        Ruled::Allowed
                    }
                    Some(_) => Ruled::Refuse,
                }
            }
            _ => Ruled::Allowed,
        }
    }

    /// Moves the stack a shift-click took from the player's inventory into the window's slots that show the
    /// storage of its `shift_into`, first slot first: partial stacks it can top up, then empty ones, each taking
    /// what the server says it holds. A slot is offered the stack as a click on it, which may refuse it.
    fn shift_in(&self, id: &str, click: &MenuClick) {
        let (Some(from), Some(moving)) = (click.slot, click.item.clone()) else {
            return;
        };
        let (uuid, mut slots) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.windows.get(id) else {
                return;
            };
            let Some(target) = &w.shift_into else {
                return;
            };
            let slots: Vec<(i32, i32)> = w
                .bindings
                .iter()
                .filter(|(_, b)| {
                    same(&b.storage, &target.storage)
                        && (target.first..=target.last).contains(&b.index)
                })
                .map(|(slot, b)| (b.index, *slot))
                .collect();
            (w.uuid, slots)
        };
        slots.sort();
        let ops = self.platform().menus();
        let mut left = moving.def.count.unwrap_or(1);
        for partial in [true, false] {
            for (_, slot) in &slots {
                if left <= 0 {
                    break;
                }
                let held = ops.item(uuid, *slot);
                if held.is_some() != partial {
                    continue;
                }
                let rule = self
                    .shared
                    .menus
                    .borrow()
                    .windows
                    .get(id)
                    .and_then(|w| w.rules.get(slot).cloned());
                if rule.as_ref().is_some_and(|r| !(r.takes)(&moving)) {
                    continue;
                }
                let has = held.as_ref().map_or(0, |h| h.def.count.unwrap_or(1));
                let room = ops
                    .room(uuid, *slot, moving.clone())
                    .min(rule.as_ref().and_then(|r| r.max).unwrap_or(i32::MAX) - has);
                if room <= 0 || !self.offer(id, click, *slot, &moving) {
                    continue;
                }
                let amount = room.min(left);
                if !self.write(id, *slot, Some(with_count(&moving, has + amount))) {
                    continue;
                }
                left -= amount;
            }
        }
        let taken = moving.def.count.unwrap_or(1) - left;
        if taken > 0 {
            let remainder = (left > 0).then(|| with_count(&moving, left));
            self.platform().inventories().set_item(
                InventoryRef::Player(InventoryRefPlayer {
                    player: click.player.uuid,
                }),
                from,
                remainder,
            );
        }
    }

    /// Whether the slot's click handlers let `item` in: they hear it as a click on the slot, and a cancel refuses it.
    fn offer(&self, id: &str, click: &MenuClick, slot: i32, item: &ItemData) -> bool {
        let context = self
            .shared
            .menus
            .borrow()
            .windows
            .get(id)
            .map_or(Value::Nil, |w| w.context.clone());
        let target = handles::Slot {
            menu: id.to_owned(),
            index: i64::from(slot),
        };
        let payload = shapes::MenuClickEvent {
            player: handles::Player {
                id: uuid_text(&click.player.uuid),
            },
            menu: handles::Menu { id: id.to_owned() },
            target: Some(target.clone()),
            index: Some(i64::from(slot)),
            in_menu: true,
            click: click.click.clone(),
            hotbar_index: None,
            item: Some(crate::items::to_value(item)),
            cursor_item: None,
            context,
        };
        !self.raise(
            &[("click", Some(Handle::Slot(target)))],
            codecs::MenuClickEvent,
            payload,
            false,
        )
    }

    // ---- bindings ----

    /// Writes a slot (and the storage slot or equipment it's bound to); none empties it.
    pub fn write(&self, id: &str, slot: i32, item: Option<ItemData>) -> bool {
        let Some(uuid) = self.window_uuid(id) else {
            return false;
        };
        if !self.platform().menus().set_item(uuid, slot, item) {
            return false;
        }
        self.wrote_through(id, slot);
        true
    }

    /// A slot of window `id` changed (already written): when it's bound, its storage or equipment holds it too.
    fn wrote_through(&self, id: &str, slot: i32) {
        let (uuid, binding, worn) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.windows.get(id) else {
                return;
            };
            (
                w.uuid,
                w.bindings.get(&slot).cloned(),
                w.worn.get(&slot).cloned(),
            )
        };
        let platform = self.platform();
        let holds = platform.menus().item(uuid, slot);
        if let Some(worn) = worn {
            if !platform
                .world_entities()
                .set_equipment(worn.player, worn.slot, holds)
            {
                self.unbind(id, slot);
            }
            return;
        }
        if let Some(binding) = binding
            && !platform.inventories().set_item(
                InventoryRef::Storage(binding.storage),
                binding.index,
                holds,
            )
        {
            self.unbind(id, slot);
        }
    }

    /// Every bound slot of window `id` written through (after an add the server spread).
    pub fn wrote_all(&self, id: &str) {
        let slots: Vec<i32> = self
            .shared
            .menus
            .borrow()
            .windows
            .get(id)
            .map(|w| w.bindings.keys().chain(w.worn.keys()).copied().collect())
            .unwrap_or_default();
        for slot in slots {
            self.wrote_through(id, slot);
        }
    }

    fn bind(&self, id: &str, slot: i32, storage: InventoryRefStorage, index: i32) -> bool {
        let Some(uuid) = self.window_uuid(id) else {
            return false;
        };
        let Some(held) = self
            .platform()
            .inventories()
            .contents(InventoryRef::Storage(storage.clone()))
        else {
            return false;
        };
        if let Some(w) = self.shared.menus.borrow_mut().windows.get_mut(id) {
            w.worn.remove(&slot);
            w.bindings.insert(slot, Binding { storage, index });
        }
        self.platform()
            .menus()
            .set_item(uuid, slot, held.get(index as usize).cloned().flatten());
        true
    }

    fn bind_worn(&self, id: &str, slot: i32, player: Uuid, place: &str) -> bool {
        let Some(uuid) = self.window_uuid(id) else {
            return false;
        };
        let platform = self.platform();
        if platform.players().get(player).is_none() {
            return false;
        }
        if let Some(w) = self.shared.menus.borrow_mut().windows.get_mut(id) {
            w.bindings.remove(&slot);
            w.worn.insert(
                slot,
                Worn {
                    player,
                    slot: place.to_owned(),
                },
            );
        }
        platform.menus().set_item(
            uuid,
            slot,
            platform
                .world_entities()
                .equipment(player, place.to_owned()),
        );
        true
    }

    /// Stops `slot` showing a storage's or what someone wears, and empties it: what it showed is still there.
    fn unbind(&self, id: &str, slot: i32) -> bool {
        let (uuid, had) = {
            let mut menus = self.shared.menus.borrow_mut();
            let Some(w) = menus.windows.get_mut(id) else {
                return false;
            };
            let storage = w.bindings.remove(&slot).is_some();
            let worn = w.worn.remove(&slot).is_some();
            (w.uuid, storage || worn)
        };
        if had {
            self.platform().menus().set_item(uuid, slot, None);
        }
        had
    }

    /// The game moved items in `slots` of window `uuid`: what the bound ones hold now is their storage's.
    pub fn changed(&self, uuid: Uuid, slots: &[i32]) {
        let Some(id) = self
            .shared
            .menus
            .borrow()
            .by_uuid(uuid)
            .map(|w| w.id.clone())
        else {
            return;
        };
        for slot in slots {
            self.wrote_through(&id, *slot);
        }
    }

    /// A script changed `storage`: every slot bound to it shows what it has now.
    pub fn storage_changed(&self, storage: &InventoryRefStorage) {
        let bound: Vec<(Uuid, i32, Binding)> = self
            .shared
            .menus
            .borrow()
            .windows
            .values()
            .flat_map(|w| {
                w.bindings
                    .iter()
                    .filter(|(_, b)| same(&b.storage, storage))
                    .map(|(slot, b)| (w.uuid, *slot, b.clone()))
                    .collect::<Vec<_>>()
            })
            .collect();
        let platform = self.platform();
        for (uuid, slot, binding) in bound {
            let held = platform
                .inventories()
                .contents(InventoryRef::Storage(binding.storage));
            platform.menus().set_item(
                uuid,
                slot,
                held.and_then(|h| h.get(binding.index as usize).cloned().flatten()),
            );
        }
    }

    /// Who's looking at a window with a slot bound to `storage`.
    pub fn viewers_of(&self, storage: &InventoryRefStorage) -> Vec<Uuid> {
        let windows: Vec<Uuid> = self
            .shared
            .menus
            .borrow()
            .windows
            .values()
            .filter(|w| w.bindings.values().any(|b| same(&b.storage, storage)))
            .map(|w| w.uuid)
            .collect();
        let mut viewers: Vec<Uuid> = windows
            .into_iter()
            .flat_map(|w| self.platform().menus().viewers(w))
            .collect();
        viewers.sort();
        viewers.dedup();
        viewers
    }

    /// Puts every bound slot of window `id` back as its storage or wearer has it: after it was rebuilt.
    fn show_bound(&self, id: &str) {
        let (uuid, bindings, worn) = {
            let mut menus = self.shared.menus.borrow_mut();
            let Some(w) = menus.windows.get_mut(id) else {
                return;
            };
            let size = w.definition.size;
            w.bindings.retain(|s, _| *s < size);
            w.worn.retain(|s, _| *s < size);
            w.rules.retain(|s, _| *s < size);
            (w.uuid, w.bindings.clone(), w.worn.clone())
        };
        let platform = self.platform();
        for (slot, binding) in bindings {
            match platform
                .inventories()
                .contents(InventoryRef::Storage(binding.storage))
            {
                Some(held) => {
                    platform.menus().set_item(
                        uuid,
                        slot,
                        held.get(binding.index as usize).cloned().flatten(),
                    );
                }
                None => {
                    self.unbind(id, slot);
                }
            }
        }
        for (slot, worn) in worn {
            if platform.players().get(worn.player).is_some() {
                platform.menus().set_item(
                    uuid,
                    slot,
                    platform.world_entities().equipment(worn.player, worn.slot),
                );
            } else {
                self.unbind(id, slot);
            }
        }
    }

    /// Every slot bound to what someone wears shows what they wear now: it changes other ways than through the
    /// window.
    fn show_worn(&self) {
        let worn: Vec<(String, Uuid, i32, Worn)> = self
            .shared
            .menus
            .borrow()
            .windows
            .values()
            .flat_map(|w| {
                w.worn
                    .iter()
                    .map(|(s, worn)| (w.id.clone(), w.uuid, *s, worn.clone()))
                    .collect::<Vec<_>>()
            })
            .collect();
        let platform = self.platform();
        for (id, uuid, slot, worn) in worn {
            if platform.players().get(worn.player).is_none() {
                self.unbind(&id, slot);
                continue;
            }
            let wearing = platform.world_entities().equipment(worn.player, worn.slot);
            if wearing != platform.menus().item(uuid, slot) {
                platform.menus().set_item(uuid, slot, wearing);
            }
        }
    }

    /// `player` left: every slot bound to one of their storages, or to what they wear, stops showing it.
    fn player_left(&self, player: Uuid) {
        let doomed: Vec<(String, i32)> = self
            .shared
            .menus
            .borrow()
            .windows
            .values()
            .flat_map(|w| {
                w.bindings
                    .iter()
                    .filter(|(_, b)| b.storage.player == player)
                    .map(|(s, _)| *s)
                    .chain(
                        w.worn
                            .iter()
                            .filter(|(_, worn)| worn.player == player)
                            .map(|(s, _)| *s),
                    )
                    .map(|s| (w.id.clone(), s))
                    .collect::<Vec<_>>()
            })
            .collect();
        for (id, slot) in doomed {
            self.unbind(&id, slot);
        }
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
        let (id, locked, context, refused, all_bound) = {
            let menus = self.shared.menus.borrow();
            let Some(w) = menus.by_uuid(uuid) else {
                return false;
            };
            // A drag puts some of the cursor in each slot: refused over a slot that doesn't take it or holds fewer
            // than it brings.
            let refused = cursor.is_some_and(|cursor| {
                slots.iter().any(|slot| {
                    w.rules.get(slot).is_some_and(|rule| {
                        !(rule.takes)(cursor)
                            || rule
                                .max
                                .is_some_and(|max| cursor.def.count.unwrap_or(1) > max)
                    })
                })
            });
            let all_bound = slots.iter().all(|s| w.bound(*s));
            (
                w.id.clone(),
                w.locked,
                w.context.clone(),
                refused,
                all_bound,
            )
        };
        if refused {
            return true;
        }
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
        let template = self.shared.menus.borrow().template_of(&menu.id);
        let mut path = vec![("drag", Some(Handle::Menu(menu)))];
        if let Some(template) = template {
            path.push(("drag", Some(Handle::MenuTemplate(template))));
        }
        self.raise(
            &path,
            codecs::MenuDragEvent,
            payload,
            locked && !slots.is_empty() && !all_bound,
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
            let title = Menus::compose_title(&self.shared, window);
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
            if let Some(w) = self.shared.menus.borrow_mut().windows.get_mut(&window) {
                w.shift_into = None;
            }
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
                self.shared.reattached("menu", id, 1);
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
                    // A bound slot shows its storage's, whatever the file says.
                    if self
                        .shared
                        .menus
                        .borrow()
                        .windows
                        .get(&window)
                        .is_some_and(|w| w.bound(slot))
                    {
                        continue;
                    }
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
                self.show_bound(&window);
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
                Handle::MenuTemplate(t) => Some(menus.templates.contains_key(&t.id)),
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
        let name = match menu {
            unions::StringOrMenuTemplate::String(name) => name,
            unions::StringOrMenuTemplate::MenuTemplate(template) => {
                let context = options.map_or(Value::Nil, |o| o.context);
                let Some(online) =
                    parse_uuid(&player.id).and_then(|id| self.shared.platform.players().get(id))
                else {
                    return Ok(None);
                };
                return Ok(self
                    .menus()
                    .open_template(&template.id, &online, context)
                    .map(|id| handles::Menu { id }));
            }
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

    /// Checked as `menu.json` is, then kept for the calling scope until it stops or the template is removed.
    fn create(&self, caller: &Caller, definition: Value) -> ApiResult<handles::MenuTemplate> {
        let game = self.shared.platform.game();
        let file = self.with_names(|names| {
            crate::definitions::menu_from(&self.scripts.host(), &definition, game, names)
        })?;
        let id = uuid_text(&crate::centity::saving::random_uuid());
        let definition = Rc::new(netherforge_format::kind::menu::compile(&id, &file));
        self.shared.menus.borrow_mut().templates.insert(
            id.clone(),
            Template {
                definition,
                owner: caller.scope,
            },
        );
        Ok(handles::MenuTemplate { id })
    }
}

impl MenuApi for GameContext {
    fn id(&self, this: &handles::Menu) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.id)))
    }
    /// A window from a template has no folder, so no kind (`template()` says where it came from).
    fn kind(&self, this: &handles::Menu) -> ApiResult<Option<String>> {
        Ok(self
            .window(&this.id, |w| {
                w.template.is_none().then(|| w.definition.id.clone())
            })
            .flatten()
            .map(|k| self.with_names(|names| names.spell(&k))))
    }
    fn template(&self, this: &handles::Menu) -> ApiResult<Option<handles::MenuTemplate>> {
        Ok(self.shared.menus.borrow().template_of(&this.id))
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
        let Some(InventoryRef::Storage(storage)) = crate::events::inventory_ref(&storage) else {
            return Err(err(
                "only a player's storage (player:storage) can take shift-clicks from a menu",
            ));
        };
        let size = i64::from(storage.size);
        let (from, to) = (first.unwrap_or(0), last.unwrap_or(size - 1));
        for at in [from, to] {
            if !(0..size).contains(&at) {
                return Err(err(format!(
                    "slot {at} is outside this storage: it has {size} slots, 0 to {}",
                    size - 1
                )));
            }
        }
        if from > to {
            return Err(err(format!("first slot {from} is after last slot {to}")));
        }
        let mut menus = self.shared.menus.borrow_mut();
        let Some(window) = menus.windows.get_mut(&this.id) else {
            return Ok(false);
        };
        if window.definition.shared {
            return Err(err(SHARED_STORAGE));
        }
        window.shift_into = Some(ShiftInto {
            storage,
            first: from as i32,
            last: to as i32,
        });
        Ok(true)
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
        let Some(size) = self.window(&this.id, |w| w.definition.size) else {
            return Ok(false);
        };
        Ok(self.menus().write(&this.id, slot_in(index, size)?, item))
    }
    /// How many didn't fit: all of them when the window is gone.
    /// How many didn't fit: all of them when the window is gone.
    fn add_item(&self, this: &handles::Menu, item: values::Item) -> ApiResult<i64> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        let Some(uuid) = self.window(&this.id, |w| w.uuid) else {
            return Ok(i64::from(item.def.count.unwrap_or(1)));
        };
        let left = self.shared.platform.menus().add(uuid, item);
        self.menus().wrote_all(&this.id);
        Ok(i64::from(left))
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
        let Some(size) = self.window(&this.id, |w| w.definition.size) else {
            return Ok(false);
        };
        // Every slot is checked before any changes.
        let mut slots = Vec::new();
        for (index, item) in items {
            slots.push((slot_in(index, size)?, self.item_of_value(Some(item))?));
        }
        let menus = self.menus();
        for (slot, item) in slots {
            menus.write(&this.id, slot, item);
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
        let menus = self.menus();
        for slot in slots {
            menus.write(&this.id, slot, item.clone());
        }
        Ok(true)
    }
    fn clear(&self, this: &handles::Menu) -> ApiResult<bool> {
        let Some(size) = self.window(&this.id, |w| w.definition.size) else {
            return Ok(false);
        };
        let menus = self.menus();
        for slot in 0..size {
            menus.write(&this.id, slot, None);
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
        let Some(InventoryRef::Storage(storage)) = crate::events::inventory_ref(&storage) else {
            return Err(err(
                "only a player's storage (player:storage) can be bound to a menu slot",
            ));
        };
        let at = index.unwrap_or(this.index);
        let size = i64::from(storage.size);
        if !(0..size).contains(&at) {
            return Err(err(format!(
                "slot {at} is outside this storage: it has {size} slots, 0 to {}",
                size - 1
            )));
        }
        let Some((shared, window_size)) =
            self.window(&this.menu, |w| (w.definition.shared, w.definition.size))
        else {
            return Ok(false);
        };
        if shared {
            return Err(err(SHARED_STORAGE));
        }
        Ok(self.menus().bind(
            &this.menu,
            slot_in(this.index, window_size)?,
            storage,
            at as i32,
        ))
    }
    fn bind_equipment(
        &self,
        this: &handles::Slot,
        player: handles::Player,
        equipment: String,
    ) -> ApiResult<bool> {
        if !BINDABLE.contains(&equipment.as_str()) {
            return Err(err(format!(
                "bad argument 'equipment' ({}, got \"{equipment}\")",
                BINDABLE.join(", ")
            )));
        }
        let Some((shared, size)) =
            self.window(&this.menu, |w| (w.definition.shared, w.definition.size))
        else {
            return Ok(false);
        };
        if shared {
            return Err(err(
                "a shared menu's window can't show what a player wears: everyone would see one player's (bind a window of their own)",
            ));
        }
        let Some(uuid) = parse_uuid(&player.id) else {
            return Ok(false);
        };
        Ok(self
            .menus()
            .bind_worn(&this.menu, slot_in(this.index, size)?, uuid, &equipment))
    }
    fn set_rule(&self, this: &handles::Slot, rule: Option<shapes::SlotRule>) -> ApiResult<bool> {
        let built = match rule {
            Some(rule) => Some(self.settle(rule)?),
            None => None,
        };
        let Some(size) = self.window(&this.menu, |w| w.definition.size) else {
            return Ok(false);
        };
        let slot = slot_in(this.index, size)?;
        if let Some(w) = self.shared.menus.borrow_mut().windows.get_mut(&this.menu) {
            match built {
                Some(rule) => w.rules.insert(slot, rule),
                None => w.rules.remove(&slot),
            };
        }
        Ok(true)
    }
    fn rule(&self, this: &handles::Slot) -> ApiResult<Option<shapes::SlotRule>> {
        let Some(size) = self.window(&this.menu, |w| w.definition.size) else {
            return Ok(None);
        };
        let slot = slot_in(this.index, size)?;
        Ok(self
            .window(&this.menu, |w| w.rules.get(&slot).map(|r| r.source.clone()))
            .flatten())
    }
    fn accepts(&self, this: &handles::Slot, item: values::Item) -> ApiResult<bool> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        let Some(size) = self.window(&this.menu, |w| w.definition.size) else {
            return Ok(false);
        };
        let slot = slot_in(this.index, size)?;
        let rule = self
            .window(&this.menu, |w| w.rules.get(&slot).cloned())
            .flatten();
        Ok(rule.is_none_or(|r| (r.takes)(&item)))
    }
    fn unbind(&self, this: &handles::Slot) -> ApiResult<bool> {
        let Some(size) = self.window(&this.menu, |w| w.definition.size) else {
            return Ok(false);
        };
        Ok(self.menus().unbind(&this.menu, slot_in(this.index, size)?))
    }
    fn binding(&self, this: &handles::Slot) -> ApiResult<Option<shapes::SlotBinding>> {
        let Some(size) = self.window(&this.menu, |w| w.definition.size) else {
            return Ok(None);
        };
        let slot = slot_in(this.index, size)?;
        Ok(self
            .window(&this.menu, |w| {
                if let Some(worn) = w.worn.get(&slot) {
                    return Some(shapes::SlotBinding {
                        storage: None,
                        index: None,
                        player: Some(handles::Player {
                            id: uuid_text(&worn.player),
                        }),
                        equipment: Some(worn.slot.clone()),
                    });
                }
                w.bindings.get(&slot).map(|b| shapes::SlotBinding {
                    storage: Some(crate::events::inventory_handle(&InventoryRef::Storage(
                        b.storage.clone(),
                    ))),
                    index: Some(i64::from(b.index)),
                    player: None,
                    equipment: None,
                })
            })
            .flatten())
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

/// What a slot can show of what a player wears.
const BINDABLE: [&str; 5] = ["head", "chest", "legs", "feet", "off_hand"];
/// Where a rule's `wearable` says a stack is worn.
const WEARABLE: [&str; 5] = ["head", "chest", "legs", "feet", "body"];
const SHARED_STORAGE: &str = "a shared menu's window can't show a storage: everyone would see one player's items (bind a window of their own)";

impl GameContext {
    /// The project item a stack's `item` reference names, by the project's id for it; none for none.
    fn project_item_of(&self, reference: &str) -> Option<String> {
        let names = self.shared.names.borrow();
        let key = netherforge_format::reference::resolve_any(&names.home, reference)?;
        names
            .namespaces
            .get(&key.namespace)?
            .resources
            .contains(&("item".to_owned(), key.path.clone()))
            .then(|| key.relative_to(&names.home))
    }

    /// `rule` as a slot holds it to stacks, checked: its items must be the project's, its places real ones.
    fn settle(&self, rule: shapes::SlotRule) -> ApiResult<Rule> {
        let items: Option<HashSet<String>> = match &rule.items {
            Some(items) => Some(
                items
                    .iter()
                    .map(|id| {
                        let named = self.with_names(|names| names.resource("item", id))?;
                        self.project_item_of(&named).ok_or_else(|| {
                            err(format!("bad argument 'rule.items' (no item \"{id}\")"))
                        })
                    })
                    .collect::<ApiResult<_>>()?,
            ),
            None => None,
        };
        let kinds: Option<Vec<(String, bool)>> = rule.kinds.as_ref().map(|kinds| {
            kinds
                .iter()
                .map(|k| {
                    (
                        netherforge_format::game::normalize(k.trim_start_matches('#')),
                        k.starts_with('#'),
                    )
                })
                .collect()
        });
        if let Some(wearable) = &rule.wearable
            && !WEARABLE.contains(&wearable.as_str())
        {
            return Err(err(format!(
                "bad argument 'rule.wearable' ({}, got \"{wearable}\")",
                WEARABLE.join(", ")
            )));
        }
        let max = match rule.max {
            Some(max) if max < 1 => {
                return Err(err(format!(
                    "bad argument 'rule.max' (at least 1, got {max})"
                )));
            }
            Some(max) => Some(max.min(i64::from(i32::MAX)) as i32),
            None => None,
        };
        let any = items.is_none()
            && kinds.is_none()
            && rule.wearable.is_none()
            && rule.property.is_none();
        let wearable = rule.wearable.clone();
        let property = rule.property.clone();
        let context = self.clone();
        let takes = move |stack: &ItemData| -> bool {
            if any {
                return true;
            }
            let game = context.shared.platform.game();
            let project = stack
                .def
                .item
                .as_deref()
                .and_then(|r| context.project_item_of(r));
            let kind = netherforge_format::game::normalize(&stack.def.kind);
            let is_kind = |id: &str, tag: bool| {
                if tag {
                    game.tag(netherforge_format::game::Registry::Item.key(), id)
                        .is_some_and(|t| t.contains(&kind))
                } else {
                    kind == id
                }
            };
            let by_item = project
                .as_ref()
                .is_some_and(|p| items.as_ref().is_some_and(|i| i.contains(p)));
            let by_kind = project.is_none()
                && kinds
                    .as_ref()
                    .is_some_and(|k| k.iter().any(|(id, tag)| is_kind(id, *tag)));
            let by_wear = wearable.as_ref().is_some_and(|w| {
                let worn = stack
                    .def
                    .equipment
                    .as_ref()
                    .map(|e| netherforge_format::canonical::name_of(&e.slot))
                    .or_else(|| {
                        (project.is_none() && is_kind(&format!("minecraft:{w}_armor"), true))
                            .then(|| w.clone())
                    });
                worn.as_ref() == Some(w)
            });
            // A project item with the key in its `properties`, and not `false`.
            let by_property = property.as_ref().is_some_and(|key| {
                project
                    .as_ref()
                    .is_some_and(|p| context.shared.items.borrow().has_property(p, key))
            });
            by_item || by_kind || by_wear || by_property
        };
        Ok(Rule {
            source: rule,
            max,
            takes: Rc::new(takes),
        })
    }
}

impl GameContext {
    fn template<R>(&self, id: &str, f: impl FnOnce(&CompiledMenu) -> R) -> Option<R> {
        self.shared
            .menus
            .borrow()
            .templates
            .get(id)
            .map(|t| f(&t.definition))
    }
}

impl MenuTemplateApi for GameContext {
    fn id(&self, this: &handles::MenuTemplate) -> ApiResult<String> {
        Ok(this.id.clone())
    }
    fn exists(&self, this: &handles::MenuTemplate) -> ApiResult<bool> {
        Ok(self.template(&this.id, |_| ()).is_some())
    }
    fn size(&self, this: &handles::MenuTemplate) -> ApiResult<i64> {
        Ok(self.template(&this.id, |d| i64::from(d.size)).unwrap_or(0))
    }
    fn rows(&self, this: &handles::MenuTemplate) -> ApiResult<i64> {
        Ok(self.template(&this.id, |d| i64::from(d.rows)).unwrap_or(0))
    }
    fn title(&self, this: &handles::MenuTemplate) -> ApiResult<Option<values::Text>> {
        Ok(self
            .template(&this.id, |d| d.title.clone().map(values::Text))
            .flatten())
    }
    fn skin(&self, this: &handles::MenuTemplate) -> ApiResult<Option<String>> {
        Ok(self.template(&this.id, |d| d.skin.clone()).flatten())
    }
    fn is_locked(&self, this: &handles::MenuTemplate) -> ApiResult<bool> {
        Ok(self.template(&this.id, |d| d.locked).unwrap_or(false))
    }
    fn item(&self, this: &handles::MenuTemplate, index: i64) -> ApiResult<Option<values::Item>> {
        let Some(size) = self.template(&this.id, |d| d.size) else {
            return Ok(None);
        };
        if !(0..i64::from(size)).contains(&index) {
            return Err(err(format!(
                "slot {index} is outside this template's windows: they have {size} slots, 0 to {}",
                size - 1
            )));
        }
        Ok(self
            .template(&this.id, |d| {
                d.slot(index as i32)
                    .and_then(|s| s.item.clone())
                    .map(|def| {
                        crate::items::to_value(&ItemData {
                            def,
                            raw: None,
                            look: None,
                        })
                    })
            })
            .flatten())
    }
    fn windows(&self, this: &handles::MenuTemplate) -> ApiResult<Vec<handles::Menu>> {
        Ok(self
            .shared
            .menus
            .borrow()
            .windows_of(&this.id)
            .into_iter()
            .map(|id| handles::Menu { id })
            .collect())
    }
    fn remove(&self, this: &handles::MenuTemplate) -> ApiResult<bool> {
        Ok(self.menus().remove_template(&this.id))
    }
    fn on(
        &self,
        this: &handles::MenuTemplate,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::MenuTemplate(this.clone()),
            "MenuTemplate:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::MenuTemplate,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::MenuTemplate(this.clone()),
            "MenuTemplate:once",
            event,
            handler,
            None,
            true,
        )
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
        *self.scripts.borrow_mut() = Some(room.scripts().clone());
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
        let context = self.context(room);
        context.retire_unseen();
        context.show_worn();
    }

    fn player_quit(&self, player: &PlayerRef) {
        if let Some(scripts) = self.scripts.borrow().clone() {
            MenuContext {
                shared: self.shared.clone(),
                scripts,
            }
            .player_left(player.uuid);
        }
    }

    /// The templates a stopped scope made go, and their windows.
    fn scope_released(&self, scopes: &[ScopeId]) {
        let Some(scripts) = self.scripts.borrow().clone() else {
            return;
        };
        let doomed: Vec<String> = self
            .shared
            .menus
            .borrow()
            .templates
            .iter()
            .filter(|(_, t)| scopes.contains(&t.owner))
            .map(|(id, _)| id.clone())
            .collect();
        let context = MenuContext {
            shared: self.shared.clone(),
            scripts,
        };
        for id in doomed {
            context.remove_template(&id);
        }
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

/// The project's menus, by id, sorted: what a `menu` command argument takes.
pub fn menu_ids(shared: &Shared) -> Vec<String> {
    shared.menus.borrow().definitions.keys().cloned().collect()
}

/// The ids of every window, for tests and `/nf`.
pub fn window_ids(shared: &Shared) -> HashSet<String> {
    shared.menus.borrow().windows.keys().cloned().collect()
}
