//! Dialogs (Kotlin's `Dialogs`, `DialogImpl`, `ButtonImpl`): the project's dialogs and the script behind each.
//!
//! A dialog holds nothing, so there's one of each, not one per viewer: its script starts at load and runs until
//! it's reloaded, and showing it is the only thing to do with it. It's built for the server on every show, never
//! registered, so a reload changes what the next show draws.
//!
//! Every button is a callback that arrives as a press, with every input's answer: heard by the button's handlers,
//! then the dialog's, then `nf.on("dialog_press")`. Leaving is reported only through a dialog's exit action;
//! closes the server causes (a script closing it, another dialog over it) raise `close` here, from which dialog
//! each player has open. Each opening may carry a `context`, handed to every press and the `close` of that
//! opening, gone once the dialog leaves their screen. Only a dialog on the player's screen can be pressed (a
//! modified client can send a click for anything); a dialog in the server's registry (the pause screen's) is
//! opened by the player themselves, and its custom clicks are presses and closes like any other.
//!
//! Not yet: `nf.dialogs.create` (dialogs from tables), `dialog:ask` (it waits for `wait_for` on a handle), and the
//! runtime's own dialogs (`/nf settings`).

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::api::{ButtonApi, DialogApi, NfDialogsApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, Waiting, codecs, shapes, unions, values};
use netherforge_format::kind::dialog::{
    AfterAction, DialogBody, DialogFile, DialogInput, DialogType,
};
use netherforge_luau::ScopeId;
use netherforge_platform::{DialogAnswers, DialogSpec, Dynamic, Platform, PlayerRef, Uuid};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::{Accept, Awaited, Target};
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The game's own most for a text input that doesn't say.
const DEFAULT_MAX_LENGTH: usize = 32;

struct Record {
    file: DialogFile,
    /// The dialog's script's scope, while it runs.
    scope: Option<ScopeId>,
}

/// One showing of a dialog to a player, and the context it was opened with.
struct Opening {
    dialog: String,
    context: Value,
}

/// The session's dialogs.
#[derive(Default)]
pub struct Dialogs {
    records: IndexMap<String, Record>,
    /// Each dialog's script, by id: its project path.
    scripts: BTreeMap<String, String>,
    /// The project dialog on each player's screen, as far as anything has told us.
    open: HashMap<Uuid, Opening>,
    home: String,
}

impl Dialogs {
    /// The id of the project dialog `reference` names (`welcome`, `shop:welcome`), or none.
    fn id_of(&self, reference: &str) -> Option<String> {
        let key = netherforge_format::reference::resolve_any(&self.home, reference)?;
        let id = key.relative_to(&self.home);
        self.records.contains_key(&id).then_some(id)
    }

    /// The dialog as the platform builds it, with the dialogs a `dialog_list` offers built alongside (each only
    /// once along a path, so two lists that offer each other don't recurse forever).
    fn spec(
        &self,
        id: &str,
        path: &mut HashSet<String>,
        opened: Option<&DialogFile>,
    ) -> Option<DialogSpec> {
        let record = self.records.get(id)?;
        if !path.insert(id.to_owned()) {
            return None;
        }
        let file = opened.unwrap_or(&record.file).clone();
        let listed = if file.kind == Some(DialogType::DialogList) {
            file.dialogs
                .iter()
                .filter_map(|r| self.id_of(r))
                .filter_map(|other| self.spec(&other, path, None))
                .collect()
        } else {
            Vec::new()
        };
        path.remove(id);
        Some(DialogSpec {
            id: id.to_owned(),
            file,
            listed,
        })
    }

    /// Whether `dialog` is on screen in `opening`: the dialog shown, or one a `dialog_list` it showed leads to.
    fn on_screen(&self, opening: Option<&Opening>, dialog: &str) -> bool {
        let Some(opening) = opening else { return false };
        if opening.dialog == dialog {
            return true;
        }
        fn listed_in(spec: &DialogSpec, dialog: &str) -> bool {
            spec.listed
                .iter()
                .any(|s| s.id == dialog || listed_in(s, dialog))
        }
        self.spec(&opening.dialog, &mut HashSet::new(), None)
            .is_some_and(|shown| listed_in(&shown, dialog))
    }

    /// The dialogs the server's registry holds: those on the pause screen or the quick actions key, and the ones
    /// they list.
    fn registry(&self) -> HashSet<String> {
        let mut found = HashSet::new();
        fn add(dialogs: &Dialogs, id: &str, found: &mut HashSet<String>) {
            let Some(record) = dialogs.records.get(id) else {
                return;
            };
            if !found.insert(id.to_owned()) {
                return;
            }
            if record.file.kind == Some(DialogType::DialogList) {
                for other in record.file.dialogs.iter().filter_map(|r| dialogs.id_of(r)) {
                    add(dialogs, &other, found);
                }
            }
        }
        for (id, record) in &self.records {
            if record.file.pause_menu == Some(true) || record.file.quick_actions == Some(true) {
                add(self, id, &mut found);
            }
        }
        found
    }
}

/// Every input's answer by key, typed by the input: text and an option's id as strings, a checkbox as its
/// `onTrue`/`onFalse` string, a slider as a number. An answer the dialog couldn't have given is left out (the
/// game's client is the player's).
pub fn values(file: &DialogFile, answers: &dyn DialogAnswers) -> HashMap<String, Dynamic> {
    let mut out = HashMap::new();
    for input in &file.inputs {
        let key = input.key().to_owned();
        let value = match input {
            DialogInput::Boolean {
                on_true, on_false, ..
            } => answers.bool(key.clone()).map(|b| {
                Dynamic::String(if b {
                    on_true.clone().unwrap_or_else(|| "true".into())
                } else {
                    on_false.clone().unwrap_or_else(|| "false".into())
                })
            }),
            DialogInput::NumberRange { start, end, .. } => answers
                .number(key.clone())
                .filter(|n| n.is_finite() && *n >= start.min(*end) && *n <= start.max(*end))
                .map(Dynamic::Number),
            DialogInput::Text { max_length, .. } => answers
                .text(key.clone())
                .filter(|t| {
                    t.chars().count() <= max_length.map_or(DEFAULT_MAX_LENGTH, |m| m as usize)
                })
                .map(Dynamic::String),
            DialogInput::SingleOption { options, .. } => answers
                .text(key.clone())
                .filter(|id| options.iter().any(|o| o.id == *id))
                .map(Dynamic::String),
        };
        if let Some(value) = value {
            out.insert(key, value);
        }
    }
    out
}

#[derive(Clone)]
pub struct DialogContext {
    pub shared: Rc<Shared>,
    pub scripts: crate::Scripts,
}

impl DialogContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn target(&self, handle: Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    fn raise<C: Codec>(&self, path: &[(&str, Option<Handle>)], codec: C, payload: C::T) -> bool {
        let lua = self.scripts.host().lua().clone();
        let Ok(Value::Table(fields)) = codec.push(&lua, payload) else {
            return false;
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
        self.scripts.raise_path(&targets, fields, false).cancelled
    }

    fn ignored(&self, line: String) {
        self.platform().log().info(line);
    }

    fn start_script(&self, id: &str) {
        let Some(path) = self.shared.dialogs.borrow().scripts.get(id).cloned() else {
            return;
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return;
        };
        let Ok(this) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::Dialog(handles::Dialog { id: id.to_owned() }),
        ) else {
            return;
        };
        let folder = path
            .rsplit_once('/')
            .map_or(path.as_str(), |(f, _)| f)
            .to_owned();
        let (scope, _) = modules.script_with(
            &format!("dialog {id}"),
            &folder,
            &path,
            &[("this", Value::UserData(this))],
        );
        if let Some(record) = self.shared.dialogs.borrow_mut().records.get_mut(id) {
            record.scope = scope;
        }
    }

    fn stop_script(&self, id: &str) {
        let scope = self
            .shared
            .dialogs
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

    fn fire_close(&self, player: &PlayerRef, dialog: &str, context: Value) {
        let handle = handles::Dialog {
            id: dialog.to_owned(),
        };
        let payload = shapes::DialogEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            dialog: handle.clone(),
            context,
        };
        self.raise(
            &[("close", Some(Handle::Dialog(handle)))],
            codecs::DialogEvent,
            payload,
        );
    }

    /// The server is about to take `player`'s dialog away: `close` for the one we know they have.
    fn closed_by_server(&self, player: &PlayerRef) {
        let opening = self.shared.dialogs.borrow_mut().open.remove(&player.uuid);
        let Some(opening) = opening else { return };
        if self
            .shared
            .dialogs
            .borrow()
            .records
            .contains_key(&opening.dialog)
        {
            self.fire_close(player, &opening.dialog, opening.context);
        }
    }

    /// Builds `id` and puts it on `player`'s screen, drawn from `file` when given (the dialog as this opening
    /// changes it), holding `context` until it leaves their screen. A project dialog already there is closed
    /// first (`close`).
    pub fn show(
        &self,
        id: &str,
        player: &PlayerRef,
        file: Option<&DialogFile>,
        context: Value,
    ) -> bool {
        let Some(spec) = self
            .shared
            .dialogs
            .borrow()
            .spec(id, &mut HashSet::new(), file)
        else {
            return false;
        };
        self.closed_by_server(player);
        if !self.platform().dialogs().show(player.uuid, spec) {
            return false;
        }
        self.shared.dialogs.borrow_mut().open.insert(
            player.uuid,
            Opening {
                dialog: id.to_owned(),
                context,
            },
        );
        true
    }

    /// Takes whatever dialog is on `player`'s screen away; a project dialog's `close` handlers hear it.
    pub fn close(&self, player: &PlayerRef) -> bool {
        self.closed_by_server(player);
        self.platform().dialogs().close(player.uuid)
    }

    /// `player` left `dialog` through its exit action (escape, or the exit button).
    pub fn closed(&self, player: &PlayerRef, dialog: &str, registry: bool) {
        let on_screen = {
            let dialogs = self.shared.dialogs.borrow();
            dialogs.on_screen(dialogs.open.get(&player.uuid), dialog)
        };
        // As for a press: leaving a dialog that isn't on their screen is a client's invention.
        if !registry && !on_screen {
            self.ignored(format!(
                "{} left dialog {dialog}, which isn't on their screen",
                player.name
            ));
            return;
        }
        let opening = self.shared.dialogs.borrow_mut().open.remove(&player.uuid);
        if self.shared.dialogs.borrow().records.contains_key(dialog) {
            self.fire_close(player, dialog, opening.map_or(Value::Nil, |o| o.context));
        }
    }

    /// A button was pressed: heard by the button, then the dialog, then `nf.on("dialog_press")`.
    pub fn pressed(
        &self,
        player: &PlayerRef,
        dialog: &str,
        button: &str,
        values: HashMap<String, Dynamic>,
        registry: bool,
    ) {
        let (on_screen, closes, known, context) = {
            let dialogs = self.shared.dialogs.borrow();
            let opening = dialogs.open.get(&player.uuid);
            let record = dialogs.records.get(dialog);
            let closes = record
                .and_then(|r| r.file.after_action)
                .is_none_or(|a| a == AfterAction::Close);
            let known = record.map(|r| r.file.buttons.iter().any(|b| b.key == button));
            (
                dialogs.on_screen(opening, dialog),
                closes,
                known,
                opening.map_or(Value::Nil, |o| o.context.clone()),
            )
        };
        if !registry && !on_screen {
            self.ignored(format!(
                "{} pressed \"{button}\" on dialog {dialog}, which isn't on their screen",
                player.name
            ));
            return;
        }
        // A press closes the screen unless the dialog says to keep it up.
        {
            let mut dialogs = self.shared.dialogs.borrow_mut();
            if closes {
                dialogs.open.remove(&player.uuid);
            } else {
                dialogs.open.insert(
                    player.uuid,
                    Opening {
                        dialog: dialog.to_owned(),
                        context: context.clone(),
                    },
                );
            }
        }
        match known {
            None => self.ignored(format!(
                "{} pressed \"{button}\" on dialog {dialog}, which is gone",
                player.name
            )),
            Some(false) => self.ignored(format!(
                "{} pressed \"{button}\" on dialog {dialog}, which has no such button any more",
                player.name
            )),
            Some(true) => self.dispatch(player, dialog, button, values, context),
        }
    }

    fn dispatch(
        &self,
        player: &PlayerRef,
        dialog: &str,
        button: &str,
        values: HashMap<String, Dynamic>,
        context: Value,
    ) {
        let dialog_handle = handles::Dialog {
            id: dialog.to_owned(),
        };
        let button_handle = handles::Button {
            dialog: dialog.to_owned(),
            key: button.to_owned(),
        };
        let payload = shapes::DialogPressEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            dialog: dialog_handle.clone(),
            target: button_handle.clone(),
            key: button.to_owned(),
            values: values
                .into_iter()
                .map(|(k, v)| {
                    let v = match v {
                        Dynamic::Number(n) => unions::StringOrNumber::Number(n),
                        Dynamic::String(s) => unions::StringOrNumber::String(s),
                        Dynamic::Bool(b) => unions::StringOrNumber::String(b.to_string()),
                        other => unions::StringOrNumber::String(format!("{other:?}")),
                    };
                    (k, v)
                })
                .collect(),
            context,
        };
        self.raise(
            &[
                ("press", Some(Handle::Button(button_handle))),
                ("press", Some(Handle::Dialog(dialog_handle))),
                ("dialog_press", None),
            ],
            codecs::DialogPressEvent,
            payload,
        );
    }

    /// A custom click `id` from a dialog in the server's registry: its button pressed with `answers`, or the
    /// dialog left. True when `id` was one of those (even one that's ignored).
    pub fn registry_clicked(
        &self,
        player: &PlayerRef,
        id: &str,
        answers: &dyn DialogAnswers,
    ) -> bool {
        use netherforge_format::startup::dialog::{Click, click};
        let home = self.shared.dialogs.borrow().home.clone();
        let Some(clicked) = click(id, &home) else {
            return false;
        };
        let dialog = match &clicked {
            Click::Press { dialog, .. } | Click::Exit { dialog } => dialog.clone(),
        };
        let (in_registry, file) = {
            let dialogs = self.shared.dialogs.borrow();
            (
                dialogs.registry().contains(&dialog),
                dialogs.records.get(&dialog).map(|r| r.file.clone()),
            )
        };
        let Some(file) = file.filter(|_| in_registry) else {
            self.ignored(format!(
                "{} clicked {id}, but dialog {dialog} isn't in the server's dialog registry",
                player.name
            ));
            return true;
        };
        match clicked {
            Click::Exit { .. } => self.closed(player, &dialog, true),
            Click::Press { index, .. } => match file.buttons.get(index) {
                Some(button) => {
                    let key = button.key.clone();
                    self.pressed(player, &dialog, &key, values(&file, answers), true);
                }
                None => self.ignored(format!(
                    "{} pressed button {index} on dialog {dialog}, which has no such button any more",
                    player.name
                )),
            },
        }
        true
    }

    /// Moves `id` onto `next` (its script restarts), or forgets it when it's gone. Someone already looking keeps
    /// their screen; their press reaches the new script, and a button the new version doesn't have is ignored.
    fn reload(&self, id: &str, next: Option<DialogFile>) {
        self.stop_script(id);
        let previous = self.shared.dialogs.borrow_mut().records.shift_remove(id);
        let Some(next) = next else {
            // Gone: so are the handlers anyone put on it.
            if let Some(target) = self.target(Handle::Dialog(handles::Dialog { id: id.to_owned() }))
            {
                self.scripts.drop_target(&target);
            }
            return;
        };
        for button in previous.iter().flat_map(|p| p.file.buttons.iter()) {
            if !next.buttons.iter().any(|b| b.key == button.key)
                && let Some(target) = self.target(Handle::Button(handles::Button {
                    dialog: id.to_owned(),
                    key: button.key.clone(),
                }))
            {
                self.scripts.drop_target(&target);
            }
        }
        self.shared.dialogs.borrow_mut().records.insert(
            id.to_owned(),
            Record {
                file: next,
                scope: None,
            },
        );
        self.start_script(id);
    }

    /// Tells `scripts` which dialog and button handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let dialogs = shared.dialogs.try_borrow().ok()?;
            match handle {
                Handle::Dialog(d) => Some(dialogs.records.contains_key(&d.id)),
                Handle::Button(b) => Some(
                    dialogs
                        .records
                        .get(&b.dialog)
                        .is_some_and(|r| r.file.buttons.iter().any(|x| x.key == b.key)),
                ),
                _ => None,
            }
        }));
    }
}

fn missing(id: &str, what: &str, key: &str, known: &[String]) -> String {
    let has = if known.is_empty() {
        " (it has no keyed ones)".to_owned()
    } else {
        format!(" (it has: {})", known.join(", "))
    };
    format!("dialog {id} has no {what} \"{key}\"{has}")
}

/// `input` starting at `value`, for one opening.
fn initial(input: &DialogInput, value: &unions::StringOrNumberOrBoolean) -> ApiResult<DialogInput> {
    use unions::StringOrNumberOrBoolean as V;
    let place = format!("options.values.{}", input.key());
    let mut input = input.clone();
    match (&mut input, value) {
        (
            DialogInput::Text {
                initial,
                max_length,
                ..
            },
            V::String(text),
        ) => {
            if let Some(most) = max_length
                && text.chars().count() > *most as usize
            {
                return Err(err(format!(
                    "{place} is {} characters, more than the input's max_length of {most}",
                    text.chars().count()
                )));
            }
            *initial = Some(text.clone());
        }
        (DialogInput::Text { .. }, _) => {
            return Err(err(format!("{place} must be a string (it's a text input)")));
        }
        (DialogInput::Boolean { initial, .. }, V::Boolean(b)) => *initial = Some(*b),
        (DialogInput::Boolean { .. }, _) => {
            return Err(err(format!(
                "{place} must be true or false (it's a boolean input)"
            )));
        }
        (DialogInput::SingleOption { options, .. }, V::String(id)) => {
            if !options.iter().any(|o| o.id == *id) {
                let ids: Vec<&str> = options.iter().map(|o| o.id.as_str()).collect();
                return Err(err(format!(
                    "{place}: the input has no option \"{id}\" (it has: {})",
                    ids.join(", ")
                )));
            }
            for option in options.iter_mut() {
                option.initial = (option.id == *id).then_some(true);
            }
        }
        (DialogInput::SingleOption { .. }, _) => {
            return Err(err(format!(
                "{place} must be an option's id (it's a single_option input)"
            )));
        }
        (
            DialogInput::NumberRange {
                start,
                end,
                initial,
                ..
            },
            V::Number(n),
        ) => {
            let (low, high) = (start.min(*end), start.max(*end));
            if !(low..=high).contains(n) {
                return Err(err(format!(
                    "{place} is {n}, outside the input's range of {low} to {high}"
                )));
            }
            *initial = Some(*n);
        }
        (DialogInput::NumberRange { .. }, _) => {
            return Err(err(format!(
                "{place} must be a number (it's a number_range input)"
            )));
        }
    }
    Ok(input)
}

impl GameContext {
    fn dialogs(&self) -> DialogContext {
        DialogContext {
            shared: self.shared.clone(),
            scripts: self.scripts.clone(),
        }
    }

    fn dialog_file(&self, id: &str) -> Option<DialogFile> {
        self.shared
            .dialogs
            .borrow()
            .records
            .get(id)
            .map(|r| r.file.clone())
    }

    /// Dialog `id` as one opening draws it; an input or body element the dialog doesn't have, or a value of the
    /// wrong kind for its input, is the script's mistake.
    fn opened(
        &self,
        id: &str,
        options: &Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<Option<DialogFile>> {
        let Some(file) = self.dialog_file(id) else {
            return Ok(None);
        };
        let Some(options) = options else {
            return Ok(Some(file));
        };
        let input_keys: Vec<String> = file.inputs.iter().map(|i| i.key().to_owned()).collect();
        let body_keys: Vec<String> = file
            .body
            .iter()
            .filter_map(|b| b.key().map(str::to_owned))
            .collect();
        for key in options.values.iter().flat_map(|v| v.keys()) {
            if !input_keys.contains(key) {
                return Err(err(missing(id, "input", key, &input_keys)));
            }
        }
        for key in options.body.iter().flat_map(|b| b.keys()) {
            if !body_keys.contains(key) {
                return Err(err(missing(id, "body element", key, &body_keys)));
            }
        }
        let mut file = file;
        if let Some(values) = &options.values {
            file.inputs = file
                .inputs
                .iter()
                .map(|input| match values.get(input.key()) {
                    Some(value) => initial(input, value),
                    None => Ok(input.clone()),
                })
                .collect::<ApiResult<_>>()?;
        }
        if let Some(body) = &options.body {
            for element in &mut file.body {
                let text = element.key().and_then(|k| body.get(k)).map(|t| t.0.clone());
                if let Some(text) = text {
                    match element {
                        DialogBody::Message { text: t, .. } => *t = text,
                        DialogBody::Item { description, .. } => *description = Some(text),
                    }
                }
            }
        }
        if let Some(title) = &options.title {
            file.title.clone_from(&title.0);
        }
        Ok(Some(file))
    }

    /// Opens `dialog` for `player` as `options` say: checked first (an error even when they're offline).
    pub(crate) fn open_dialog_for(
        &self,
        dialog: &str,
        player: &handles::Player,
        options: Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<bool> {
        let Some(file) = self.opened(dialog, &options)? else {
            return Ok(false);
        };
        let Some(online) =
            parse_uuid(&player.id).and_then(|id| self.shared.platform.players().get(id))
        else {
            return Ok(false);
        };
        let context = options.map_or(Value::Nil, |o| o.context);
        Ok(self.dialogs().show(dialog, &online, Some(&file), context))
    }

    /// `player:open_dialog`: by name (one the project has) or by handle.
    pub(crate) fn open_dialog(
        &self,
        player: &handles::Player,
        dialog: unions::StringOrDialog,
        options: Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<bool> {
        let id = match dialog {
            unions::StringOrDialog::Dialog(d) => d.id,
            unions::StringOrDialog::String(name) => {
                let id = self.with_names(|names| names.resource("dialog", &name))?;
                if self.dialog_file(&id).is_none() {
                    let known: Vec<String> = self
                        .shared
                        .dialogs
                        .borrow()
                        .records
                        .keys()
                        .cloned()
                        .collect();
                    let has = if known.is_empty() {
                        String::new()
                    } else {
                        format!(" (it has: {})", known.join(", "))
                    };
                    return Err(err(format!("no dialog \"{name}\" in this project{has}")));
                }
                id
            }
        };
        self.open_dialog_for(&id, player, options)
    }

    pub(crate) fn close_dialog(&self, player: &handles::Player) -> bool {
        parse_uuid(&player.id)
            .and_then(|id| self.shared.platform.players().get(id))
            .is_some_and(|p| self.dialogs().close(&p))
    }
}

impl NfDialogsApi for GameContext {
    fn get(&self, _: &Caller, dialog: String) -> ApiResult<handles::Dialog> {
        let id = self.with_names(|names| names.resource("dialog", &dialog))?;
        if self.dialog_file(&id).is_none() {
            return Err(err(format!("no dialog \"{dialog}\" in this project")));
        }
        Ok(handles::Dialog { id })
    }

    fn create(&self, caller: &Caller, definition: Value) -> ApiResult<handles::Dialog> {
        <Unported as NfDialogsApi>::create(&Unported, caller, definition)
    }
}

impl DialogApi for GameContext {
    fn id(&self, this: &handles::Dialog) -> ApiResult<String> {
        Ok(self.with_names(|names| names.spell(&this.id)))
    }
    fn exists(&self, this: &handles::Dialog) -> ApiResult<bool> {
        Ok(self.dialog_file(&this.id).is_some())
    }
    fn title(&self, this: &handles::Dialog) -> ApiResult<Option<values::Text>> {
        Ok(self.dialog_file(&this.id).map(|f| values::Text(f.title)))
    }
    fn buttons(&self, this: &handles::Dialog) -> ApiResult<Vec<String>> {
        Ok(self
            .dialog_file(&this.id)
            .map(|f| f.buttons.into_iter().map(|b| b.key).collect())
            .unwrap_or_default())
    }
    fn inputs(&self, this: &handles::Dialog) -> ApiResult<Vec<String>> {
        Ok(self
            .dialog_file(&this.id)
            .map(|f| f.inputs.iter().map(|i| i.key().to_owned()).collect())
            .unwrap_or_default())
    }
    /// Checked against the dialog while it's there; a gone dialog's buttons are handed out and answer nil.
    fn button(&self, this: &handles::Dialog, key: String) -> ApiResult<handles::Button> {
        if let Some(file) = self.dialog_file(&this.id)
            && !file.buttons.iter().any(|b| b.key == key)
        {
            let known: Vec<String> = file.buttons.iter().map(|b| b.key.clone()).collect();
            let has = if known.is_empty() {
                " (it has none)".to_owned()
            } else {
                format!(" (it has: {})", known.join(", "))
            };
            return Err(err(format!(
                "dialog {} has no button \"{key}\"{has}",
                this.id
            )));
        }
        Ok(handles::Button {
            dialog: this.id.clone(),
            key,
        })
    }
    fn open_for(
        &self,
        this: &handles::Dialog,
        player: handles::Player,
        options: Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<bool> {
        self.open_dialog_for(&this.id, &player, options)
    }
    fn ask(
        &self,
        this: &handles::Dialog,
        player: handles::Player,
        options: Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<Waiting> {
        let task = self.scripts.running_task("dialog:ask")?;
        if self.dialog_file(&this.id).is_none() {
            self.scripts.wake_with_nil(task)?;
            return Ok(Waiting);
        }
        let lua = self.scripts.host().lua().clone();
        let push =
            |h: Handle| netherforge_api::handle::push_any(&lua, &h).map_err(|e| err(e.to_string()));
        let dialog = push(Handle::Dialog(this.clone()))?;
        let who = push(Handle::Player(player.clone()))?.to_pointer();
        // Their own press, close or quit: `answer` says whether the event is what's handed back.
        let theirs = move |answer: bool| -> Accept {
            Box::new(move |ev: &Value| {
                let Value::Table(ev) = ev else { return None };
                match ev.get::<Value>("player") {
                    Ok(Value::UserData(p)) if p.to_pointer() == who => Some(if answer {
                        Value::Table(ev.clone())
                    } else {
                        Value::Nil
                    }),
                    _ => None,
                }
            })
        };
        // Listens before it opens the dialog, so nothing the opening causes is missed; a close the opening
        // causes for this same dialog comes before the wait, so it's ignored.
        let subs = self.scripts.wait_any(
            task,
            vec![
                Awaited {
                    target: Target::Handle(dialog.clone()),
                    event: "press".into(),
                    accept: theirs(true),
                },
                Awaited {
                    target: Target::Handle(dialog),
                    event: "close".into(),
                    accept: theirs(false),
                },
                Awaited {
                    target: Target::Nf,
                    event: "player_quit".into(),
                    accept: theirs(false),
                },
            ],
        )?;
        match self.open_dialog_for(&this.id, &player, options) {
            Ok(true) => self.scripts.await_subs(task, subs),
            Ok(false) => {
                self.scripts.release_subs(&subs);
                self.scripts.wake_with_nil(task)?;
            }
            Err(e) => {
                self.scripts.release_subs(&subs);
                return Err(e);
            }
        }
        Ok(Waiting)
    }
    fn on(
        &self,
        this: &handles::Dialog,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Dialog(this.clone()),
            "Dialog:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::Dialog,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Dialog(this.clone()),
            "Dialog:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl ButtonApi for GameContext {
    fn key(&self, this: &handles::Button) -> ApiResult<String> {
        Ok(this.key.clone())
    }
    fn dialog(&self, this: &handles::Button) -> ApiResult<handles::Dialog> {
        Ok(handles::Dialog {
            id: this.dialog.clone(),
        })
    }
    fn label(&self, this: &handles::Button) -> ApiResult<Option<values::Text>> {
        Ok(self.dialog_file(&this.dialog).and_then(|f| {
            f.buttons
                .into_iter()
                .find(|b| b.key == this.key)
                .map(|b| values::Text(b.label.unwrap_or(b.key)))
        }))
    }
    fn on(
        &self,
        this: &handles::Button,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Button(this.clone()),
            "Button:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::Button,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Button(this.clone()),
            "Button:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// Dialogs as a session's service.
pub struct DialogsService {
    pub shared: Rc<Shared>,
}

impl DialogsService {
    fn context(&self, room: &Room) -> DialogContext {
        DialogContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        }
    }

    fn define(&self, project: &Define) -> BTreeMap<String, DialogFile> {
        let running: BTreeMap<String, DialogFile> = project
            .project
            .running::<DialogFile>()
            .into_iter()
            .map(|(id, f)| (id, f.clone()))
            .collect();
        let mut scripts = BTreeMap::new();
        for (id, resource) in project.project.everywhere("dialog") {
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
        let mut dialogs = self.shared.dialogs.borrow_mut();
        dialogs.scripts = scripts;
        dialogs.home = project.project.namespace().to_owned();
        running
    }
}

impl Service for DialogsService {
    fn name(&self) -> &'static str {
        "dialogs"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["dialog"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        let running = self.define(project);
        self.shared.dialogs.borrow_mut().records = running
            .into_iter()
            .map(|(id, file)| (id, Record { file, scope: None }))
            .collect();
    }

    /// Their scripts start once modules run.
    fn after_start(&self, room: &Room) {
        let context = self.context(room);
        let ids: Vec<String> = self
            .shared
            .dialogs
            .borrow()
            .records
            .keys()
            .cloned()
            .collect();
        for id in ids {
            context.start_script(&id);
        }
    }

    fn reload(&self, room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let running = self.define(project);
        let context = self.context(room);
        for id in ids {
            context.reload(id, running.get(id).cloned());
        }
    }

    /// The player left: whatever they had open just goes.
    fn player_quit(&self, player: &PlayerRef) {
        self.shared.dialogs.borrow_mut().open.remove(&player.uuid);
    }

    fn stop(&self, room: &Room, _platform: &dyn Platform) {
        let context = self.context(room);
        let ids: Vec<String> = self
            .shared
            .dialogs
            .borrow()
            .records
            .keys()
            .cloned()
            .collect();
        for id in ids {
            context.stop_script(&id);
        }
        *self.shared.dialogs.borrow_mut() = Dialogs::default();
    }
}
