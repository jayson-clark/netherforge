//! The commands modules declare (`nf.commands.register`, Kotlin's `ProjectCommands`, `CommandDefinitions` and
//! `ArgumentValues`), on the server for as long as the module that declared them runs.
//!
//! The server reads what's typed against a command's syntax and hands over what it read (`CommandInput`); this
//! checks what only the runtime knows (the permission and `players_only` of each subcommand typed, the project's
//! own names), answers a mistake there with a red line and the usage, turns each value into what the handler
//! gets, fills in the defaults, and calls the handler with a `CommandEvent`.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Lua, Value};
use netherforge_api::api::NfCommandsApi;
use netherforge_api::codec::{Codec, ItemCodec, LocationCodec, Vec3Codec};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, shapes, unions, values};
use netherforge_luau::ScopeId;
use netherforge_platform::{
    ArgumentSyntax, ArgumentType, ArgumentValue, CommandHandler, CommandInput, CommandSender,
    CommandSpec, CommandSyntax, Completion, Platform, SelectedEntity, SuggestionRequest,
};

use crate::events::uuid_text;
use crate::scripts::WeakScripts;
use crate::session::Service;
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// An argument as its command keeps it: its syntax, the value it has when left out, and the script's own
/// completion.
struct Argument {
    syntax: ArgumentSyntax,
    default: Value,
    complete: Option<Function>,
}

/// A command or subcommand as its module declared it: the syntax the server reads it by, and what only the
/// runtime holds: the handler, each argument's default and completion, and the subcommands under it.
struct Node {
    syntax: CommandSyntax,
    handler: Option<Function>,
    arguments: Vec<Argument>,
    subcommands: BTreeMap<String, Node>,
}

impl Node {
    /// The node `path`'s subcommands lead to; none when one isn't there.
    fn at(&self, path: &[String]) -> Option<&Node> {
        path.iter()
            .try_fold(self, |node, name| node.subcommands.get(name))
    }
}

struct Declared {
    scope: ScopeId,
    root: Rc<Node>,
}

/// The session's project commands, by name.
#[derive(Default)]
pub struct Commands {
    declared: IndexMap<String, Declared>,
}

impl Commands {
    /// Every command declared, by name, with the scope of the module that declared it.
    pub fn all(&self) -> Vec<(String, ScopeId)> {
        self.declared
            .iter()
            .map(|(name, d)| (name.clone(), d.scope))
            .collect()
    }
}

const RESERVED: &[&str] = &["nf", "netherforge"];

fn is_command_name(name: &str) -> bool {
    let mut chars = name.chars();
    name.len() <= 32
        && chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-')
        && !RESERVED.contains(&name)
}

fn is_subcommand_name(name: &str) -> bool {
    let mut chars = name.chars();
    chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-')
}

fn is_argument_name(name: &str) -> bool {
    let mut chars = name.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// What Brigadier's `word` takes, which is what each of a `choice` argument's choices must be.
fn is_word(text: &str) -> bool {
    !text.is_empty()
        && text
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '+' | '-'))
}

/// A command's or a subcommand's fields, which are the same but for where the handler comes from.
struct Fields {
    description: Option<String>,
    permission: Option<String>,
    players_only: Option<bool>,
    arguments: Option<Vec<shapes::CommandArgument>>,
    subcommands: Option<BTreeMap<String, shapes::Subcommand>>,
    handler: Option<Function>,
}

/// `nf.commands.register`'s definition, checked: its shape was read strictly by its codec, and this holds it to
/// what the codec can't say, naming the mistake by its place (`definition.arguments[2]`).
fn node(lua: &Lua, fields: Fields, at: &str) -> ApiResult<Node> {
    let arguments = fields
        .arguments
        .unwrap_or_default()
        .into_iter()
        .enumerate()
        .map(|(i, a)| argument(lua, a, &format!("{at}.arguments[{}]", i + 1)))
        .collect::<ApiResult<Vec<_>>>()?;
    let mut optional_from: Option<&str> = None;
    let mut seen = HashSet::new();
    for (i, declared) in arguments.iter().enumerate() {
        let argument = &declared.syntax;
        if !seen.insert(argument.name.as_str()) {
            return Err(err(format!(
                "'{at}': two arguments called '{}'",
                argument.name
            )));
        }
        if argument.optional {
            optional_from = optional_from.or(Some(&argument.name));
        } else if let Some(from) = optional_from {
            return Err(err(format!(
                "argument '{}' in '{at}' has no default but comes after '{from}', which has one: optional arguments must come last",
                argument.name
            )));
        }
        if i + 1 < arguments.len() && argument.r#type == ArgumentType::Text {
            return Err(err(format!(
                "argument '{}' in '{at}' is text, which takes the rest of the line, so it must be the last",
                argument.name
            )));
        }
    }
    if !arguments.is_empty() && fields.handler.is_none() {
        return Err(err(format!(
            "'{at}' has arguments but no handler to give them to"
        )));
    }
    let mut subcommands = BTreeMap::new();
    for (name, sub) in fields.subcommands.unwrap_or_default() {
        if !is_subcommand_name(&name) {
            return Err(err(format!(
                "'{at}.subcommands' has {name}: subcommand names are lowercase letters, digits, _ and -, starting with a letter"
            )));
        }
        let node = node(
            lua,
            Fields {
                description: sub.description,
                permission: sub.permission,
                players_only: sub.players_only,
                arguments: sub.arguments,
                subcommands: sub.subcommands,
                handler: sub.handler,
            },
            &format!("{at}.subcommands.{name}"),
        )?;
        subcommands.insert(name, node);
    }
    if fields.handler.is_none() && subcommands.is_empty() {
        return Err(err(format!("'{at}' has neither a handler nor subcommands")));
    }
    let syntax = CommandSyntax {
        description: fields.description.unwrap_or_default(),
        permission: fields.permission,
        players_only: fields.players_only == Some(true),
        arguments: arguments.iter().map(|a| a.syntax.clone()).collect(),
        subcommands: subcommands
            .iter()
            .map(|(name, node)| (name.clone(), node.syntax.clone()))
            .collect(),
        runs: fields.handler.is_some(),
    };
    Ok(Node {
        syntax,
        handler: fields.handler,
        arguments,
        subcommands,
    })
}

fn argument(lua: &Lua, argument: shapes::CommandArgument, at: &str) -> ApiResult<Argument> {
    if !is_argument_name(&argument.name) {
        return Err(err(format!(
            "'{at}.name' (\"{}\") must be letters, digits and _, not starting with a digit",
            argument.name
        )));
    }
    // The codec only lets through the types the spec declares.
    let r#type = ArgumentType::of(&argument.r#type)
        .ok_or_else(|| err(format!("no argument type {}", argument.r#type)))?;
    let numeric = matches!(r#type, ArgumentType::Integer | ArgumentType::Number);
    for (bound, value) in [("min", argument.min), ("max", argument.max)] {
        let Some(value) = value else { continue };
        if !numeric {
            return Err(err(format!(
                "'{at}.{bound}' is only for integer and number arguments"
            )));
        }
        if r#type == ArgumentType::Integer && value != value.floor() {
            return Err(err(format!(
                "'{at}.{bound}' must be a whole number for an integer argument"
            )));
        }
    }
    if let (Some(min), Some(max)) = (argument.min, argument.max)
        && min > max
    {
        return Err(err(format!("'{at}.min' is more than its max")));
    }
    let choices = argument.choices.clone().unwrap_or_default();
    if r#type == ArgumentType::Choice {
        if choices.is_empty() {
            return Err(err(format!("'{at}' is a choice, so it needs choices")));
        }
        if let Some(bad) = choices.iter().find(|c| !is_word(c)) {
            return Err(err(format!(
                "'{at}.choices' has \"{bad}\": choices are single words (letters, digits, _ - . +)"
            )));
        }
    } else if argument.choices.is_some() {
        return Err(err(format!("'{at}.choices' is only for choice arguments")));
    }
    let optional = !matches!(argument.default, Value::Nil);
    let default = match argument.default.clone() {
        Value::Nil => Value::Nil,
        value => default(lua, value, &argument, r#type, &format!("{at}.default"))?,
    };
    let syntax = ArgumentSyntax {
        name: argument.name,
        r#type,
        optional,
        min: argument.min,
        max: argument.max,
        choices,
        completes: argument.complete.is_some(),
    };
    Ok(Argument {
        syntax,
        default,
        complete: argument.complete,
    })
}

/// A `default`: `false` (nothing), or a value of the type its handler gets, within the argument's bounds and
/// choices.
fn default(
    lua: &Lua,
    value: Value,
    argument: &shapes::CommandArgument,
    r#type: ArgumentType,
    at: &str,
) -> ApiResult<Value> {
    if matches!(value, Value::Boolean(false)) {
        return Ok(value);
    }
    let name = r#type.lua_name();
    if let Some(Err(e)) = netherforge_api::arguments::read_default(lua, name, value.clone(), at) {
        // `bad argument 'at' (X expected, got Y)` for the default itself says `or false`.
        let expected = netherforge_api::arguments::expected(name).unwrap_or_default();
        let prefix = format!("bad argument '{at}' ({expected} expected");
        return Err(match e.0.strip_prefix(&prefix) {
            Some(rest) => err(format!("{prefix} or false{rest}").replacen(
                &format!("({expected} expected or false"),
                &format!("({expected} or false expected"),
                1,
            )),
            None => e,
        });
    }
    if let Value::Integer(_) | Value::Number(_) = value {
        let number = match value {
            Value::Integer(n) => n as f64,
            Value::Number(n) => n,
            _ => unreachable!(),
        };
        if argument.min.is_some_and(|m| number < m) || argument.max.is_some_and(|m| number > m) {
            let shown = if number == number.floor() && number.abs() < 1e15 {
                format!("{}", number as i64)
            } else {
                format!("{number}")
            };
            return Err(err(format!("'{at}' ({shown}) is outside min and max")));
        }
    }
    if r#type == ArgumentType::Choice
        && let Value::String(s) = &value
    {
        let text = s.to_string_lossy();
        if !argument.choices.iter().flatten().any(|c| *c == text) {
            return Err(err(format!("'{at}' (\"{text}\") isn't one of its choices")));
        }
    }
    Ok(value)
}

/// A command's run and completions, as the server calls them.
struct Handler {
    name: String,
    shared: Weak<Shared>,
    scripts: WeakScripts,
}

/// A mistake in what was typed that only the runtime can see (no such world, centity or menu).
struct Problem(String);

impl Handler {
    fn declared(&self, shared: &Shared) -> Option<(ScopeId, Rc<Node>)> {
        shared
            .commands
            .borrow()
            .declared
            .get(&self.name)
            .map(|d| (d.scope, d.root.clone()))
    }
}

impl CommandHandler for Handler {
    fn run(&self, sender: Rc<dyn CommandSender>, label: String, input: CommandInput) {
        let name = &self.name;
        let (Some(shared), Some(scripts)) = (self.shared.upgrade(), self.scripts.upgrade()) else {
            sender.reply(format!(
                "<red>/{name} isn't available right now: the module that declared it has stopped."
            ));
            return;
        };
        let Some((scope, root)) = self.declared(&shared).filter(|(s, _)| scripts.live(*s)) else {
            sender.reply(format!(
                "<red>/{name} isn't available right now: the module that declared it has stopped."
            ));
            return;
        };
        let Some(node) = root.at(&input.path) else {
            sender.reply(format!(
                "<red>/{name} has no subcommand {}",
                input.path.join(" ")
            ));
            return;
        };
        if let Some((refusal, up_to)) = refusal(&root.syntax, &input.path, &*sender) {
            let typed = std::iter::once(label.clone())
                .chain(input.path.iter().take(up_to).cloned())
                .collect::<Vec<_>>()
                .join(" ");
            sender.reply(match refusal {
                Refusal::Permission => format!("<red>You don't have permission to use /{typed}."),
                Refusal::PlayersOnly => format!("<red>Only players can use /{typed}."),
            });
            return;
        }
        let Some(handler) = &node.handler else {
            for line in usage(&label, &input.path, &node.syntax, &*sender) {
                sender.reply(format!("<gray>Usage: {line}"));
            }
            return;
        };
        let lua = scripts.host().lua().clone();
        let arguments = match arguments(&lua, &shared, node, &input.arguments, &*sender, true) {
            Ok(a) => a,
            Err(Problem(problem)) => {
                sender.reply(format!("<red>{problem}"));
                for line in usage(&label, &input.path, &node.syntax, &*sender) {
                    sender.reply(format!("<gray>Usage: {line}"));
                }
                return;
            }
        };
        let Ok(event) = event(&lua, &*sender, name, &label, &input.text, arguments) else {
            return;
        };
        scripts.host().next_kind("command");
        if let Err(failure) = scripts.call(scope, handler, event) {
            let message = failure.to_string();
            scripts.handler_failed(scope, &format!("/{name}"), failure);
            sender.reply(format!("<red>/{name} failed: {message}"));
        }
    }

    fn suggest(&self, sender: Rc<dyn CommandSender>, request: SuggestionRequest) -> Completion {
        let none = Completion {
            start: request.partial.chars().count() as i32,
            suggestions: Vec::new(),
        };
        let (Some(shared), Some(scripts)) = (self.shared.upgrade(), self.scripts.upgrade()) else {
            return none;
        };
        let Some((scope, root)) = self.declared(&shared).filter(|(s, _)| scripts.live(*s)) else {
            return none;
        };
        let Some(node) = root.at(&request.path) else {
            return none;
        };
        let Some(argument) = node
            .arguments
            .iter()
            .find(|a| a.syntax.name == request.argument)
        else {
            return none;
        };
        let Some(complete) = &argument.complete else {
            return Completion {
                start: 0,
                suggestions: matching(
                    suggestions(&shared, argument.syntax.r#type),
                    &request.partial,
                ),
            };
        };
        let lua = scripts.host().lua().clone();
        let Ok(before) = arguments(&lua, &shared, node, &request.arguments, &*sender, false) else {
            return none;
        };
        let Ok(event) = event(
            &lua,
            &*sender,
            &self.name,
            &self.name,
            &request.partial,
            before,
        ) else {
            return none;
        };
        let what = format!("completing /{} <{}>", self.name, argument.syntax.name);
        let answer = match scripts.call_as(
            "complete",
            scope,
            complete,
            (event, request.partial.clone()),
        ) {
            Ok(answer) => answer,
            Err(failure) => {
                scripts.handler_failed(scope, &what, failure);
                return none;
            }
        };
        let name = &argument.syntax.name;
        let offered = match strings(&answer) {
            Ok(offered) => offered,
            Err(problem) => {
                let failure = netherforge_luau::Failure::Error {
                    message: format!("complete for argument '{name}' {problem}"),
                    traceback: None,
                };
                scripts.handler_failed(scope, &what, failure);
                return none;
            }
        };
        Completion {
            start: 0,
            suggestions: matching(offered, &request.partial),
        }
    }
}

/// A completion's answer as a list of strings, or what's wrong with it.
fn strings(answer: &Value) -> Result<Vec<String>, String> {
    let Value::Table(list) = answer else {
        return Err(format!(
            "must return a list of strings, not a {}",
            netherforge_api::codec::type_name(answer)
        ));
    };
    let mut out = Vec::new();
    for (i, item) in list.clone().sequence_values::<Value>().enumerate() {
        match item {
            Ok(Value::String(s)) => out.push(s.to_string_lossy()),
            Ok(other) => {
                return Err(format!(
                    "returned a {} at [{}]: suggestions are strings",
                    netherforge_api::codec::type_name(&other),
                    i + 1
                ));
            }
            Err(e) => return Err(format!("must return a list of strings ({e})")),
        }
    }
    Ok(out)
}

fn matching(offered: Vec<String>, typed: &str) -> Vec<String> {
    let typed = typed.to_lowercase();
    offered
        .into_iter()
        .filter(|s| s.to_lowercase().starts_with(&typed))
        .collect()
}

/// A `CommandEvent`: the command's name, what was typed to run it, everything after it, the arguments by name,
/// who ran it, and the player when it was one.
fn event(
    lua: &Lua,
    sender: &dyn CommandSender,
    name: &str,
    label: &str,
    input: &str,
    arguments: Vec<(String, Value)>,
) -> mlua::Result<Value> {
    let event = lua.create_table()?;
    event.raw_set("name", name)?;
    event.raw_set("label", label)?;
    event.raw_set("input", input)?;
    let args = lua.create_table()?;
    for (name, value) in arguments {
        args.raw_set(name, value)?;
    }
    event.raw_set("arguments", args)?;
    let player = sender.player().map(|p| uuid_text(&p.uuid));
    let sender_id = player
        .clone()
        .unwrap_or_else(|| crate::utilities::CONSOLE_SENDER.to_owned());
    event.raw_set(
        "sender",
        netherforge_api::handle::push_any(lua, &Handle::Sender(handles::Sender { id: sender_id }))?,
    )?;
    if let Some(id) = player {
        event.raw_set(
            "player",
            netherforge_api::handle::push_any(lua, &Handle::Player(handles::Player { id }))?,
        )?;
    }
    Ok(Value::Table(event))
}

/// Each argument of `node` the server read, as its handler gets it, and (with `defaults`) the default of each
/// left out.
fn arguments(
    lua: &Lua,
    shared: &Shared,
    node: &Node,
    read: &HashMap<String, ArgumentValue>,
    sender: &dyn CommandSender,
    defaults: bool,
) -> Result<Vec<(String, Value)>, Problem> {
    let mut out = Vec::new();
    for argument in &node.arguments {
        match read.get(&argument.syntax.name) {
            Some(value) => out.push((
                argument.syntax.name.clone(),
                resolve(lua, shared, &argument.syntax, value, sender)?,
            )),
            None if defaults => out.push((argument.syntax.name.clone(), argument.default.clone())),
            None => {}
        }
    }
    Ok(out)
}

/// The handler's value for `argument`, the same on every server: a `Player` handle, a `Vec3`, a `World` once
/// there's such a world, a `Dialog` once the project has one.
fn resolve(
    lua: &Lua,
    shared: &Shared,
    argument: &ArgumentSyntax,
    value: &ArgumentValue,
    sender: &dyn CommandSender,
) -> Result<Value, Problem> {
    let name = format!("<{}>", argument.name);
    let platform = &*shared.platform;
    let mismatch = || {
        Problem(format!(
            "the server read {name} ({}) as {value:?}",
            argument.r#type.lua_name()
        ))
    };
    let push = |h: Handle| netherforge_api::handle::push_any(lua, &h).map(Value::UserData);
    let lua_err = |e: mlua::Error| Problem(e.to_string());
    let word = || match value {
        ArgumentValue::Text(t) => Ok(t.text.clone()),
        _ => Err(mismatch()),
    };
    let entity = |selected: &SelectedEntity| -> Result<Value, Problem> {
        let handle = shared.entity_by_id(lua, selected.uuid);
        push(Handle::Entity(handle)).map_err(lua_err)
    };
    Ok(match (argument.r#type, value) {
        (
            ArgumentType::Word | ArgumentType::Text | ArgumentType::Choice,
            ArgumentValue::Text(t),
        ) => Value::String(lua.create_string(&t.text).map_err(lua_err)?),
        (ArgumentType::Integer, ArgumentValue::Integer(n)) => Value::Number(n.value as f64),
        (ArgumentType::Number, ArgumentValue::Decimal(n)) => Value::Number(n.value),
        (ArgumentType::Boolean, ArgumentValue::Bool(b)) => Value::Boolean(b.value),
        (ArgumentType::Player, ArgumentValue::Players(p)) if p.players.len() == 1 => {
            push(Handle::Player(handles::Player {
                id: uuid_text(&p.players[0].uuid),
            }))
            .map_err(lua_err)?
        }
        (ArgumentType::Players, ArgumentValue::Players(p)) => {
            let list = lua.create_table().map_err(lua_err)?;
            for player in &p.players {
                let handle = push(Handle::Player(handles::Player {
                    id: uuid_text(&player.uuid),
                }))
                .map_err(lua_err)?;
                list.raw_push(handle).map_err(lua_err)?;
            }
            Value::Table(list)
        }
        (ArgumentType::Entity, ArgumentValue::Entities(e)) if e.entities.len() == 1 => {
            entity(&e.entities[0])?
        }
        (ArgumentType::Entities, ArgumentValue::Entities(e)) => {
            let list = lua.create_table().map_err(lua_err)?;
            for selected in &e.entities {
                list.raw_push(entity(selected)?).map_err(lua_err)?;
            }
            Value::Table(list)
        }
        (ArgumentType::Position, ArgumentValue::Position(at)) => Vec3Codec
            .push(
                lua,
                values::Vec3 {
                    x: at.x,
                    y: at.y,
                    z: at.z,
                },
            )
            .map_err(lua_err)?,
        (ArgumentType::Location, ArgumentValue::Position(at)) => {
            let from = sender.location();
            // The console stands at a world's spawn facing nowhere in particular: no facing from it.
            let facing = sender.player().is_some() && from.is_some();
            let world = from
                .as_ref()
                .map_or_else(|| platform.worlds().default_world(), |l| l.world.clone());
            LocationCodec
                .push(
                    lua,
                    values::Location {
                        world: handles::World { name: world },
                        position: values::Vec3 {
                            x: at.x,
                            y: at.y,
                            z: at.z,
                        },
                        yaw: from.as_ref().filter(|_| facing).map(|l| l.yaw),
                        pitch: from.as_ref().filter(|_| facing).map(|l| l.pitch),
                    },
                )
                .map_err(lua_err)?
        }
        (ArgumentType::BlockState, ArgumentValue::BlockState(s)) => {
            Value::String(lua.create_string(&s.state).map_err(lua_err)?)
        }
        (ArgumentType::Item, ArgumentValue::Item(i)) => ItemCodec
            .push(lua, crate::items::to_value(&i.item))
            .map_err(lua_err)?,
        (ArgumentType::World, _) => {
            let text = word()?;
            if !platform.worlds().exists(text.clone()) {
                return Err(Problem(format!(
                    "{name}: no world called \"{text}\" (worlds: {}).",
                    platform.worlds().names().join(", ")
                )));
            }
            push(Handle::World(handles::World { name: text })).map_err(lua_err)?
        }
        (ArgumentType::Centity, _) => {
            let text = word()?;
            let live = crate::events::parse_uuid(&text)
                .is_some_and(|id| shared.centities.borrow().get(id).is_some());
            if !live {
                return Err(Problem(format!(
                    "{name}: no live centity with the id \"{text}\"."
                )));
            }
            push(Handle::Centity(handles::Centity { id: text })).map_err(lua_err)?
        }
        (ArgumentType::CentityKind, _) => {
            let text = word()?;
            if shared.centities.borrow().definition(&text).is_none() {
                return Err(Problem(format!(
                    "{name}: no centity \"{text}\" in this project."
                )));
            }
            Value::String(lua.create_string(&text).map_err(lua_err)?)
        }
        (ArgumentType::Menu, _) => {
            let text = word()?;
            if !crate::menu::menu_ids(shared).contains(&text) {
                return Err(Problem(format!(
                    "{name}: no menu \"{text}\" in this project."
                )));
            }
            Value::String(lua.create_string(&text).map_err(lua_err)?)
        }
        (ArgumentType::Dialog, _) => {
            let text = word()?;
            if !crate::dialog::dialog_ids(shared).contains(&text) {
                return Err(Problem(format!(
                    "{name}: no dialog \"{text}\" in this project."
                )));
            }
            push(Handle::Dialog(handles::Dialog { id: text })).map_err(lua_err)?
        }
        _ => return Err(mismatch()),
    })
}

/// The words a type only the runtime knows offers; nothing for the rest, which the server completes.
fn suggestions(shared: &Shared, r#type: ArgumentType) -> Vec<String> {
    match r#type {
        ArgumentType::World => shared.platform.worlds().names(),
        ArgumentType::Centity => shared
            .centities
            .borrow()
            .all()
            .map(|i| uuid_text(&i.id))
            .collect(),
        ArgumentType::CentityKind => shared.centities.borrow().definition_ids(),
        ArgumentType::Menu => crate::menu::menu_ids(shared),
        ArgumentType::Dialog => crate::dialog::dialog_ids(shared),
        _ => Vec::new(),
    }
}

enum Refusal {
    Permission,
    PlayersOnly,
}

/// Why `sender` can't use what `path` leads to from `root`, and how much of `path` that's about: a permission
/// along the way (up to the node that needs it), or `players_only` on any of it (all of it).
fn refusal(
    root: &CommandSyntax,
    path: &[String],
    sender: &dyn CommandSender,
) -> Option<(Refusal, usize)> {
    let mut node = root;
    let mut players_only = node.players_only;
    if node
        .permission
        .as_ref()
        .is_some_and(|p| !sender.has_permission(p.clone()))
    {
        return Some((Refusal::Permission, 0));
    }
    for (i, name) in path.iter().enumerate() {
        node = node.subcommands.get(name)?;
        if node
            .permission
            .as_ref()
            .is_some_and(|p| !sender.has_permission(p.clone()))
        {
            return Some((Refusal::Permission, i + 1));
        }
        players_only = players_only || node.players_only;
    }
    (players_only && sender.player().is_none()).then_some((Refusal::PlayersOnly, path.len()))
}

/// How `node` (reached by `path` from `/label`) can be typed: one line for itself when it runs, and one per
/// subcommand the sender may use, in name order.
fn usage(
    label: &str,
    path: &[String],
    node: &CommandSyntax,
    sender: &dyn CommandSender,
) -> Vec<String> {
    let prefix = std::iter::once(format!("/{label}"))
        .chain(path.iter().cloned())
        .collect::<Vec<_>>()
        .join(" ");
    let shape = |a: &ArgumentSyntax| {
        if a.optional {
            format!("[{}]", a.name)
        } else {
            format!("<{}>", a.name)
        }
    };
    let mut lines = Vec::new();
    if node.runs {
        lines.push(
            std::iter::once(prefix.clone())
                .chain(node.arguments.iter().map(shape))
                .collect::<Vec<_>>()
                .join(" "),
        );
    }
    let mut names: Vec<&String> = node.subcommands.keys().collect();
    names.sort();
    for name in names {
        let sub = &node.subcommands[name];
        if sub
            .permission
            .as_ref()
            .is_some_and(|p| !sender.has_permission(p.clone()))
        {
            continue;
        }
        let mut parts = vec![format!("{prefix} {name}")];
        if sub.runs {
            parts.extend(sub.arguments.iter().map(shape));
        }
        let more = if sub.subcommands.is_empty() {
            ""
        } else {
            " …"
        };
        lines.push(format!("{}{more}", parts.join(" ")));
    }
    lines
}

impl NfCommandsApi for GameContext {
    fn register(
        &self,
        caller: &Caller,
        name: String,
        definition: Option<unions::CommandDefinitionOrFunction>,
        handler: Option<Function>,
    ) -> ApiResult<bool> {
        let scope = caller.scope;
        let (declared, run) = match definition {
            None => (None, handler),
            Some(unions::CommandDefinitionOrFunction::CommandDefinition(d)) => (Some(d), handler),
            Some(unions::CommandDefinitionOrFunction::Function(f)) => {
                if handler.is_some() {
                    return Err(err(
                        "bad argument 'handler' (nil expected after a handler in the definition's place, got function)",
                    ));
                }
                (None, Some(f))
            }
        };
        let owner = self
            .shared
            .modules
            .borrow()
            .as_ref()
            .and_then(|m| m.owner(scope));
        let Some(crate::modules::Owner::Module(module)) = owner else {
            return Err(err(
                "only modules can declare commands; a centity can be removed while someone is typing one",
            ));
        };
        if !is_command_name(&name) {
            return Err(err(format!(
                "\"{name}\" can't be a command name (lowercase letters, digits, _ and -, starting with a letter)"
            )));
        }
        let lua = self.scripts.host().lua().clone();
        let aliases = declared
            .as_ref()
            .and_then(|d| d.aliases.clone())
            .unwrap_or_default();
        let root = match declared {
            Some(d) => Fields {
                description: d.description,
                permission: d.permission,
                players_only: d.players_only,
                arguments: d.arguments,
                subcommands: d.subcommands,
                handler: run,
            },
            None => Fields {
                description: None,
                permission: None,
                players_only: None,
                arguments: None,
                subcommands: None,
                handler: run,
            },
        };
        let root = node(&lua, root, "definition")?;
        if let Some(bad) = aliases.iter().find(|a| !is_command_name(a)) {
            return Err(err(format!(
                "\"{bad}\" can't be an alias (lowercase letters, digits, _ and -, starting with a letter)"
            )));
        }
        let spec = CommandSpec {
            name: name.clone(),
            aliases,
            syntax: root.syntax.clone(),
        };
        let handler = Rc::new(Handler {
            name: name.clone(),
            shared: Rc::downgrade(&self.shared),
            scripts: self.scripts.downgrade(),
        });
        let taken = self.shared.commands.borrow().declared.contains_key(&name);
        if taken || !self.shared.platform.commands().register(spec, handler) {
            self.shared.platform.log().warn(format!(
                "module {module}: /{name} is already a command, so it wasn't declared"
            ));
            return Ok(false);
        }
        self.shared.commands.borrow_mut().declared.insert(
            name,
            Declared {
                scope,
                root: Rc::new(root),
            },
        );
        Ok(true)
    }
}

/// The commands as a session's service: a stopped module's go.
pub struct CommandsService {
    pub shared: Rc<Shared>,
}

impl CommandsService {
    fn unregister(&self, platform: &dyn Platform, names: Vec<String>) {
        for name in names {
            self.shared
                .commands
                .borrow_mut()
                .declared
                .shift_remove(&name);
            platform.commands().unregister(name);
        }
    }
}

impl Service for CommandsService {
    fn name(&self) -> &'static str {
        "commands"
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let names: Vec<String> = self
            .shared
            .commands
            .borrow()
            .declared
            .iter()
            .filter(|(_, d)| scopes.contains(&d.scope))
            .map(|(n, _)| n.clone())
            .collect();
        self.unregister(&*self.shared.platform, names);
    }

    fn stop(&self, _room: &crate::Room, platform: &dyn Platform) {
        let names: Vec<String> = self
            .shared
            .commands
            .borrow()
            .declared
            .keys()
            .cloned()
            .collect();
        self.unregister(platform, names);
    }
}

/// The names of every project command, for tests and `/nf`.
pub fn command_names(shared: &Shared) -> Vec<String> {
    shared.commands.borrow().declared.keys().cloned().collect()
}
