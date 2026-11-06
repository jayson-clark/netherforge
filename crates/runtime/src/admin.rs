//! `/netherforge` (alias `/nf`): what a server operator does by hand (Kotlin's `AdminCommand` and `DataCommand`).
//!
//! It lives in the runtime rather than the adapter so it's tested against the fake platform and every adapter gets
//! the same command. The command's one argument is the rest of the line, which it reads and completes itself.
//!
//! The platform calls the handler whenever a sender types it, so it holds the server's events weakly and reaches
//! the session through them: shared for what only reads, borrowed to change for what reloads or sets (`/nf reload`,
//! `/nf settings set`), which a command a script runs mid-tick can't have ("the project is busy"). The settings
//! dialog's Save comes as a dialog press, inside an event: it's held for the next tick, where the session can be
//! changed ([`AdminCommand::settle`]).

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::{Rc, Weak};
use std::sync::mpsc::Receiver;

use netherforge_format::manifest::SettingDef;
use netherforge_format::settings::SettingRead;
use netherforge_platform::api::ArgumentType;
use netherforge_platform::{
    ArgumentSyntax, ArgumentValue, CommandHandler, CommandInput, CommandSender, CommandSpec,
    CommandSyntax, Completion, Dynamic, Location, PlayerRef, SuggestionRequest, Uuid,
};
use serde_json::Value;

use crate::server::ServerEvents;
use crate::session::Session;

/// Who may run `/nf`.
pub const PERMISSION: &str = "netherforge.admin";

/// `/nf`'s one argument: everything after it.
pub const ARGUMENTS: &str = "arguments";

const SUBCOMMANDS: &[&str] = &[
    "spawn",
    "list",
    "find",
    "kill",
    "tp",
    "reload",
    "modules",
    "scripts",
    "profile",
    "pack",
    "data",
    "settings",
    "requires",
    "schedules",
    "network",
    "drain",
    "shutdown",
];

/// How long `/nf shutdown --after-empty` waits for the server to empty, unless `--timeout` says.
const DEFAULT_SHUTDOWN_SECONDS: u64 = 60;
const MAX_PROBLEMS: usize = 5;
const MAX_SCRIPTS: usize = 15;
const BYTES_PER_MB: usize = 1024 * 1024;
const MAX_ROWS: usize = 20;
const MAX_LINES: usize = 40;
const MAX_VALUE: usize = 60;
const SHORT: usize = 8;

/// The ids the settings dialog goes by: `netherforge:settings/<namespace>`, and the list.
const SETTINGS_PREFIX: &str = "netherforge:settings/";
const SETTINGS_LIST: &str = "netherforge:settings";
const SAVE: &str = "save";
const CANCEL: &str = "cancel";
const INTRO: &str = "<gray>What this server's owner sets for the project. Changes apply at once.";
/// The longest a text box allows, unless the value is already longer.
const MAX_TEXT: usize = 256;
/// A whole number with a range of at most this many steps is a slider; a wider one is typed.
const MAX_SLIDER_STEPS: i64 = 100;

type Said = Result<(), String>;

/// A Save the settings dialog heard: applied at the next tick, its lines told to whoever pressed.
struct HeldSave {
    namespace: String,
    values: Vec<(String, Option<Value>)>,
    lines: Vec<String>,
    player: Uuid,
}

/// The `/nf` command.
pub struct AdminCommand {
    events: Weak<ServerEvents<'static>>,
    /// The plugin's folder: `exports/` is in it.
    state_dir: PathBuf,
    held: RefCell<Vec<HeldSave>>,
    /// Exports under way, and who asked.
    #[allow(clippy::type_complexity)]
    exports: RefCell<Vec<(Receiver<Result<PathBuf, String>>, Rc<dyn CommandSender>)>>,
    me: RefCell<Weak<AdminCommand>>,
}

impl AdminCommand {
    /// Registers `/netherforge` (`/nf`) with the platform for `events`; `state_dir` is the plugin's folder. The
    /// command, which `ServerEvents::tick` settles each tick, or none if the server refused it.
    pub fn register(
        events: &Rc<ServerEvents<'static>>,
        state_dir: PathBuf,
    ) -> Option<Rc<AdminCommand>> {
        let command = Rc::new(AdminCommand {
            events: Rc::downgrade(events),
            state_dir,
            held: RefCell::default(),
            exports: RefCell::default(),
            me: RefCell::default(),
        });
        *command.me.borrow_mut() = Rc::downgrade(&command);
        let spec = CommandSpec {
            name: "netherforge".into(),
            aliases: vec!["nf".into()],
            syntax: CommandSyntax {
                description:
                    "NetherForge: spawn, list, find, kill and reload centities; script costs; pack status"
                        .into(),
                permission: Some(PERMISSION.into()),
                players_only: false,
                // Free-form: the command reads its own words and completes them itself.
                arguments: vec![ArgumentSyntax {
                    name: ARGUMENTS.into(),
                    r#type: ArgumentType::Text,
                    optional: true,
                    min: None,
                    max: None,
                    choices: Vec::new(),
                    completes: true,
                }],
                subcommands: HashMap::new(),
                runs: true,
            },
        };
        let registered = events
            .session()
            .platform()
            .commands()
            .register(spec, command.clone());
        // Each tick, what waited: the settings dialog's Saves, and exports that finished.
        let (weak_events, weak_command) = (Rc::downgrade(events), Rc::downgrade(&command));
        events.set_after_tick(Some(Rc::new(move || {
            if let (Some(events), Some(command)) = (weak_events.upgrade(), weak_command.upgrade()) {
                command.settle(&events);
                command.poll_exports(&events);
            }
        })));
        registered.then_some(command)
    }

    fn events(&self) -> Result<Rc<ServerEvents<'static>>, String> {
        self.events
            .upgrade()
            .ok_or_else(|| "NetherForge isn't enabled".to_owned())
    }

    /// What waited for a tick: the settings dialog's Saves.
    pub fn settle(&self, events: &ServerEvents<'static>) {
        let held = std::mem::take(&mut *self.held.borrow_mut());
        for save in held {
            let Some(mut session) = events.session_mut_now() else {
                self.held.borrow_mut().push(save);
                continue;
            };
            let mut lines = save.lines;
            if !save.values.is_empty() {
                let shown: Vec<(String, Option<Value>)> = save.values.clone();
                match session.set_settings(&save.namespace, save.values) {
                    Ok(names) => {
                        let settings = session.shared().settings.borrow();
                        for name in names {
                            let value = shown
                                .iter()
                                .find(|(n, _)| *n == name)
                                .and_then(|(_, v)| v.clone())
                                .unwrap_or(Value::Null);
                            let text = settings
                                .definition(&save.namespace, &name)
                                .map(|d| d.show(&value))
                                .unwrap_or_default();
                            lines.push(format!("<green>{name} is now {}", escape(&session, &text)));
                        }
                    }
                    Err(why) => lines.push(format!("<red>{}", escape(&session, &why))),
                }
            }
            if lines.is_empty() {
                lines.push("<gray>No settings changed.".into());
            }
            let platform = session.platform_rc();
            drop(session);
            for line in lines {
                platform.players().message(save.player, line);
            }
        }
    }

    fn dispatch(&self, sender: &Rc<dyn CommandSender>, text: &str) -> Said {
        let args: Vec<&str> = text.split(' ').filter(|w| !w.is_empty()).collect();
        let rest = args.get(1..).unwrap_or_default();
        let first = args.first().map(|a| a.to_lowercase());
        let say = |line: String| sender.reply(line);
        match first.as_deref() {
            Some("spawn") => self.spawn(sender, rest),
            Some("list") => self.list(sender, None),
            Some("find") => match rest.first() {
                Some(c) => self.list(sender, Some(c)),
                None => usage(sender, "find <centity>"),
            },
            Some("kill") => self.kill(sender, rest),
            Some("tp") => match rest.first() {
                Some(t) => self.teleport(sender, t),
                None => usage(sender, "tp <instance>"),
            },
            Some("reload") => self.reload(sender, rest),
            Some("modules") => self.modules(sender),
            Some("scripts") => self.scripts(
                sender,
                rest.first().is_some_and(|a| a.eq_ignore_ascii_case("all")),
            ),
            Some("profile") => self.profile(sender, rest.first().copied()),
            Some("pack") => self.pack(sender, rest),
            Some("data") => self.data(sender, rest),
            Some("settings") => self.settings(sender, rest, text),
            Some("requires") => self.requires(sender),
            Some("schedules") => self.schedules(sender),
            Some("network") => self.network(sender),
            Some("drain") => self.drain(sender, rest),
            Some("shutdown") => self.shutdown(sender, rest),
            _ => {
                say(format!("<gray>/nf {}", SUBCOMMANDS.join("|")));
                Ok(())
            }
        }
    }

    // ---- centities ----

    fn spawn(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        let Some(centity) = args.first() else {
            return usage(sender, "spawn <centity> [player]");
        };
        let events = self.events()?;
        let session = shared(&events)?;
        let platform = session.platform_rc();
        let player = match args.get(1) {
            Some(name) => Some(
                platform
                    .players()
                    .find((*name).into())
                    .ok_or_else(|| format!("no player \"{name}\" online"))?,
            ),
            None => sender.player(),
        };
        let Some(player) = player else {
            return Err(format!(
                "say which player to spawn it at: /nf spawn {centity} <player>"
            ));
        };
        if !session.running() {
            return Err(not_running(&session));
        }
        if session.centities().definition(centity).is_none() {
            return Err(format!("no centity \"{centity}\" in this project"));
        }
        let at = platform
            .players()
            .location(player.uuid)
            .map(|l| in_front(&l))
            .or_else(|| {
                platform
                    .worlds()
                    .spawn_location(platform.worlds().default_world())
            })
            .ok_or("there's no world to spawn in")?;
        let id = session
            .spawn(centity, at.clone(), 0.0)
            .ok_or_else(|| format!("couldn't spawn {centity} at {}", place(&at)))?;
        let anchor = session
            .centities()
            .get(id)
            .map_or(at, |i| i.anchor().clone());
        sender.reply(format!(
            "<green>Spawned {centity} <gray>{} at {}",
            short(id),
            place(&anchor)
        ));
        Ok(())
    }

    fn list(&self, sender: &Rc<dyn CommandSender>, centity: Option<&str>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let here = sender
            .player()
            .and_then(|p| session.platform().players().location(p.uuid));
        let centities = session.centities();
        let mut instances: Vec<(Uuid, String, Location)> = centities
            .all()
            .filter(|i| !i.removed && centity.is_none_or(|c| i.centity() == c))
            .map(|i| (i.id, i.centity().to_owned(), i.anchor().clone()))
            .collect();
        instances.sort_by(|a, b| {
            distance(here.as_ref(), &a.2).total_cmp(&distance(here.as_ref(), &b.2))
        });
        let home = session.project().namespace().to_owned();
        let inert: Vec<(Uuid, String, Location)> = centities
            .inert()
            .map(|r| (r.id, relative(&r.centity, &home), r.anchor.clone()))
            .filter(|(_, c, _)| centity.is_none_or(|w| c == w))
            .collect();
        if instances.is_empty() && inert.is_empty() {
            sender.reply(format!(
                "<gray>No {} spawned.",
                centity.unwrap_or("centities")
            ));
            return Ok(());
        }
        sender.reply(format!("<gold>{} spawned:", instances.len() + inert.len()));
        for (id, centity, anchor) in instances {
            let far = distance(here.as_ref(), &anchor);
            let far = if far < f64::MAX {
                format!(" <gray>({far:.0} blocks)")
            } else {
                String::new()
            };
            sender.reply(format!(
                "<yellow>{} <white>{centity} <gray>{}{far}",
                short(id),
                place(&anchor)
            ));
        }
        for (id, centity, anchor) in inert {
            sender.reply(format!(
                "<dark_gray>{} {centity} (definition missing) {}",
                short(id),
                place(&anchor)
            ));
        }
        Ok(())
    }

    fn kill(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        let Some(target) = args.first() else {
            return usage(sender, "kill <instance|centity|all>");
        };
        let events = self.events()?;
        let session = shared(&events)?;
        let all: Vec<(Uuid, String)> = session
            .centities()
            .all()
            .filter(|i| !i.removed)
            .map(|i| (i.id, i.centity().to_owned()))
            .collect();
        let chosen: Vec<Uuid> = if *target == "all" {
            all.iter().map(|(id, _)| *id).collect()
        } else if all.iter().any(|(_, c)| c == target) {
            all.iter()
                .filter(|(_, c)| c == target)
                .map(|(id, _)| *id)
                .collect()
        } else {
            vec![by_prefix(
                all.iter().map(|(id, _)| *id),
                target,
                "instance or centity",
            )?]
        };
        let Some(context) = centity_context(&session) else {
            return Err(not_running(&session));
        };
        let mut removed = chosen.iter().filter(|id| context.remove(**id)).count();
        if *target == "all" {
            let inert: Vec<Uuid> = session.centities().inert().map(|r| r.id).collect();
            let platform = session.platform_rc();
            for id in inert {
                if session.centities().remove_inert(&*platform, id) {
                    removed += 1;
                }
            }
        }
        sender.reply(format!("<green>Removed {removed}."));
        Ok(())
    }

    fn teleport(&self, sender: &Rc<dyn CommandSender>, target: &str) -> Said {
        let player = sender.player().ok_or("only a player can teleport")?;
        let events = self.events()?;
        let session = shared(&events)?;
        let ids: Vec<Uuid> = session.centities().all().map(|i| i.id).collect();
        let id = by_prefix(ids.into_iter(), target, "instance or centity")?;
        let (centity, anchor) = {
            let centities = session.centities();
            let instance = centities.get(id).ok_or("it's gone")?;
            (instance.centity().to_owned(), instance.anchor().clone())
        };
        session.platform().players().teleport(player.uuid, anchor);
        sender.reply(format!("<green>Teleported to {centity} {}.", short(id)));
        Ok(())
    }

    // ---- reloading and what runs ----

    fn reload(&self, sender: &Rc<dyn CommandSender>, paths: &[&str]) -> Said {
        let events = self.events()?;
        let mut session = events
            .session_mut_now()
            .ok_or("the project is busy; try again")?;
        let result = if paths.is_empty() {
            session.restart()
        } else {
            session.reload(&paths.iter().map(|p| p.to_string()).collect::<Vec<_>>())
        };
        drop(session);
        if let Some(bridge) = events.bridge() {
            bridge.send_problems(&events, false);
        }
        if result.resources.is_empty() {
            sender.reply(format!("<gray>Nothing to reload for {}.", paths.join(", ")));
            return Ok(());
        }
        for resource in &result.resources {
            let color = if resource.ok { "green" } else { "red" };
            let extra = if resource.reattached > 0 {
                format!(" <gray>({} reattached)", resource.reattached)
            } else {
                String::new()
            };
            sender.reply(format!(
                "<{color}>{}: {}{extra}",
                resource.label(),
                if resource.ok { "reloaded" } else { "failed" }
            ));
            for problem in resource.problems.iter().take(MAX_PROBLEMS) {
                let line = problem.line.map_or(String::new(), |l| format!(":{l}"));
                sender.reply(format!(
                    "<gray>  {}{line}: {}",
                    problem.file, problem.message
                ));
            }
        }
        if result.restart {
            sender.reply("<yellow>The server learns these only as it starts: restart it to run them (until then it runs what it started with).".into());
        }
        Ok(())
    }

    fn modules(&self, sender: &Rc<dyn CommandSender>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let Some(modules) = session.shared().modules.borrow().clone() else {
            sender.reply("<gray>This project has no modules.".into());
            return Ok(());
        };
        let ids = modules.ids();
        if ids.is_empty() {
            sender.reply("<gray>This project has no modules.".into());
            return Ok(());
        }
        let mut commands: HashMap<String, Vec<String>> = HashMap::new();
        for (name, scope) in session.shared().commands.borrow().all() {
            if let Some(crate::modules::Owner::Module(id)) = modules.owner(scope) {
                commands.entry(id).or_default().push(format!("/{name}"));
            }
        }
        for id in ids {
            let status = modules.status(&id);
            let color = match status {
                crate::modules::ModuleStatus::Running => "green",
                crate::modules::ModuleStatus::Failed => "red",
                crate::modules::ModuleStatus::Idle => "gray",
            };
            let declared = commands
                .get(&id)
                .map_or(String::new(), |c| format!(" <gray>{}", c.join(" ")));
            sender.reply(format!("<{color}>{id} <white>{}{declared}", status.label()));
        }
        Ok(())
    }

    /// `/nf scripts [all]`: every running script, the most expensive first: its average and worst milliseconds a
    /// tick over the last `costs::WINDOW` ticks, then what it holds (memory once it's a megabyte or more). The top
    /// `MAX_SCRIPTS` unless `all`.
    fn scripts(&self, sender: &Rc<dyn CommandSender>, all: bool) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let entries = session.costs();
        if entries.is_empty() {
            sender.reply("<gray>No scripts are running.".into());
            return Ok(());
        }
        let total: f64 = entries.iter().map(|e| e.average_millis).sum();
        sender.reply(format!(
            "<gold>{} {}, {total:.2} ms a tick <gray>(average / max ms a tick over the last {} ticks)",
            entries.len(),
            if entries.len() == 1 { "script" } else { "scripts" },
            crate::costs::WINDOW
        ));
        let shown = if all {
            entries.len()
        } else {
            entries.len().min(MAX_SCRIPTS)
        };
        for entry in &entries[..shown] {
            let mut holds = vec![
                count(entry.calls as usize, "call"),
                count(entry.subscriptions, "subscription"),
                count(entry.tasks, "task"),
            ];
            for (name, n) in &entry.counts {
                if *n == 1 {
                    holds.push(format!("1 {}", name.strip_suffix('s').unwrap_or(name)));
                } else if *n > 1 {
                    holds.push(format!("{n} {name}"));
                }
            }
            if entry.bytes / BYTES_PER_MB > 0 {
                holds.push(format!("{} MB", entry.bytes / BYTES_PER_MB));
            }
            sender.reply(format!(
                "<yellow>{:.2} <gray>/ {:.2} <white>{} <gray>{}",
                entry.average_millis,
                entry.max_millis,
                entry.title,
                holds.join(", ")
            ));
        }
        if shown < entries.len() {
            sender.reply(format!(
                "<gray>…and {} more: /nf scripts all",
                entries.len() - shown
            ));
        }
        Ok(())
    }

    /// `/nf profile <seconds>`: records every tick for that long and writes a report into the plugin's
    /// `profiles` folder, then says where. One at a time.
    fn profile(&self, sender: &Rc<dyn CommandSender>, seconds: Option<&str>) -> Said {
        let events = self.events()?;
        let profiler = events.profiler();
        let session = shared(&events)?;
        let tick = session.ticks();
        let left = profiler.recording_left(tick);
        let Some(seconds) = seconds else {
            let running = left.map_or(String::new(), |l| {
                format!(" (one is recording: {l} s left)")
            });
            return usage(sender, &format!("profile <seconds>{running}"));
        };
        let count: u64 = seconds
            .parse()
            .map_err(|_| "say how many seconds to profile: /nf profile 30".to_owned())?;
        // What was measured before isn't the recording's: the session hands it over first, into the batch
        // flushed now.
        session.drain_pending_profile();
        let told = sender.clone();
        profiler.record(
            tick,
            count,
            Box::new(move |message| told.reply(format!("<green>{message}"))),
        )?;
        sender.reply(format!(
            "<gold>Profiling for {count} s<gray>; the report goes into the plugin's profiles folder."
        ));
        Ok(())
    }

    /// `/nf pack` says what's built and where it's served; `/nf pack send [player]` sends it again.
    fn pack(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let platform = session.platform_rc();
        if args.first().is_some_and(|a| a.eq_ignore_ascii_case("send")) {
            let target = match args.get(1) {
                Some(name) => Some(
                    platform
                        .players()
                        .find((*name).into())
                        .ok_or_else(|| format!("no player \"{name}\" online"))?,
                ),
                None => sender.player(),
            }
            .ok_or("say who to send it to: /nf pack send <player>")?;
            let mut packs = session.shared().packs.borrow_mut();
            packs.forget(target.uuid);
            let sent = packs.send(&*platform, target.uuid);
            sender.reply(if sent {
                format!("<green>Sent the resource pack to {}.", target.name)
            } else {
                "<red>There's no resource pack to send.".into()
            });
            return Ok(());
        }
        let packs = session.shared().packs.borrow();
        let Some(built) = &packs.built else {
            sender.reply("<gray>No resource pack: the project has no resource packs, or they couldn't be built.".into());
            return Ok(());
        };
        sender.reply(format!(
            "<gold>Resource pack <white>{} <gray>({} bytes)",
            built.sha1,
            built.bytes.len()
        ));
        sender.reply(format!(
            "<gray>{}",
            built
                .url
                .clone()
                .unwrap_or_else(|| "not delivered (resource-pack.enabled is off)".into())
        ));
        Ok(())
    }

    // ---- what a project may do ----

    /// What the project and its packages declare they need (`requires`), each with who declares it, what the
    /// server's config gives, and the web routes served.
    fn requires(&self, sender: &Rc<dyn CommandSender>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let project = session.project();
        let combined = netherforge_format::requirement::combined(project);
        let tree: Vec<String> = std::iter::once(project.namespace().to_owned())
            .chain(project.packages.keys().cloned())
            .collect();
        let grants: Vec<String> = session
            .shared()
            .http
            .borrow()
            .config
            .allow_private
            .iter()
            .map(|a| {
                let missing = if tree.contains(&a.namespace) {
                    String::new()
                } else {
                    format!(" (no package \"{}\" in this project)", a.namespace)
                };
                format!(
                    "http.allow-private: {} may reach {}:{}{missing}",
                    a.namespace, a.host, a.port
                )
            })
            .collect();
        if !grants.is_empty() {
            sender.reply("<gold>Given by this server's config.yml:".into());
            for grant in grants {
                sender.reply(format!("<yellow>{}", escape(&session, &grant)));
            }
        }
        if combined.is_empty() {
            sender.reply("<gray>The project and its packages require nothing beyond what every project may do.".into());
            return Ok(());
        }
        sender.reply("<gold>The project and its packages require:".into());
        for usage in &combined {
            sender.reply(format!(
                "<yellow>{} <gray>declared by {}",
                usage.requirement,
                usage.packages.join(", ")
            ));
        }
        let web = session.shared().web.borrow();
        if web.routes.is_empty() {
            return Ok(());
        }
        let served = web.base.as_ref().map_or_else(
            || "not served: web.enabled is off in config.yml".to_owned(),
            |b| format!("served at {b}"),
        );
        sender.reply(format!("<gold>Web routes <gray>({served}):"));
        for route in web.routes.values() {
            let tail = if route.path.text == "/" {
                ""
            } else {
                route.path.text.as_str()
            };
            let login = if matches!(route.answerer, crate::web::Answerer::Login { .. }) {
                ", login flow"
            } else {
                ""
            };
            sender.reply(format!(
                "<yellow>{} /{}{tail} <gray>({}{login})",
                route.method, route.namespace, route.namespace
            ));
        }
        Ok(())
    }

    /// `/nf schedules`: every live `nf.schedule`, soonest first.
    fn schedules(&self, sender: &Rc<dyn CommandSender>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let schedules = session.shared().schedules.borrow();
        if schedules.entries.is_empty() {
            sender.reply("<gray>No schedules are running.".into());
            return Ok(());
        }
        let now = schedules.now();
        let zone = &schedules.zone;
        sender.reply(format!(
            "<gold>{} <gray>(times in {})",
            count(schedules.entries.len(), "schedule"),
            zone.id()
        ));
        let mut entries: Vec<_> = schedules.entries.values().collect();
        entries.sort_by_key(|e| e.next.map_or(i64::MAX, |n| n.timestamp_millis()));
        let host = session.room().map(|r| r.host().clone());
        for entry in entries {
            let when = match entry.next {
                None => "never".to_owned(),
                Some(next) => {
                    let left = (next - now).num_minutes().max(0);
                    format!(
                        "{} {} <gray>(in {})",
                        zone.local(next).format("%Y-%m-%d %H:%M"),
                        zone.abbreviation(next),
                        minutes(left)
                    )
                }
            };
            let id = entry
                .id
                .as_ref()
                .map_or(String::new(), |id| format!(" <gray>{id}"));
            let title = host
                .as_ref()
                .and_then(|h| h.scope_name(entry.scope))
                .unwrap_or_default();
            sender.reply(format!(
                "<yellow>{}{id} <white>{title} <gray>next <white>{when}",
                entry.recurrence.describe()
            ));
        }
        Ok(())
    }

    // ---- the network ----

    /// `/nf network`: how this server is in a network (or why not), whether it's connected, every server with its
    /// players, and what this session listens to.
    fn network(&self, sender: &Rc<dyn CommandSender>) -> Said {
        use crate::network::Mode;
        let events = self.events()?;
        let session = shared(&events)?;
        let network = session.shared().network.borrow();
        let config = &network.config;
        let Some(link) = network.link() else {
            let why = config
                .problem()
                .unwrap_or_else(|| "network.enabled is off in config.yml".into());
            sender.reply(format!("<gray>This server isn't part of a network: {why}."));
            return Ok(());
        };
        let name = link.name().unwrap_or_default();
        match link.mode {
            Mode::Off => {
                let why = link
                    .config()
                    .problem()
                    .unwrap_or_else(|| "network.enabled is off in config.yml".into());
                sender.reply(format!("<gray>This server isn't part of a network: {why}."));
                return Ok(());
            }
            Mode::Local => sender.reply(format!(
                "<gold>A network of this server alone, <yellow>{name}<gold>, as on every dev server not in one."
            )),
            Mode::Remote => {
                let state = if link.connected() {
                    "<green>connected"
                } else {
                    "<red>not connected (trying again)"
                };
                let c = link.config();
                sender.reply(format!(
                    "<gold>In the network as <yellow>{name}<gold> through its hub at {}:{}: {state}",
                    c.host, c.port
                ));
            }
        }
        let state = link.state.borrow();
        let startup = state.startup_millis.map_or(String::new(), |ms| {
            format!(", ready {:.1}s after start", ms as f64 / 1000.0)
        });
        sender.reply(format!(
            "<gray>It's <yellow>{}<gray>{startup}{}.",
            link.lifecycle().word(),
            if state.idle { ", idle" } else { "" }
        ));
        if link.mode == Mode::Remote && state.connected && !state.grants.trusted {
            sender.reply(format!(
                "<gray>Its credential is <yellow>{}<gray>: {}.",
                link.config().credential.clone().unwrap_or_default(),
                state.grants.describe()
            ));
        }
        let servers: Vec<_> = state.servers.values().cloned().collect();
        drop(state);
        let counts = link.counts();
        for server in servers {
            let shown = if server.linked {
                "<green>connected"
            } else {
                "<gray>not connected"
            };
            let group = server
                .group
                .as_ref()
                .map_or(String::new(), |g| format!(" in <yellow>{g}<gray>"));
            let room = server
                .capacity
                .map_or(String::new(), |c| format!(" of {c}"));
            let closed = if server.linked && !server.accepting {
                ", not taking players"
            } else {
                ""
            };
            sender.reply(format!(
                "<yellow>{} <gray>{shown}{group}, {}{room} online{closed}",
                server.name,
                counts.get(&server.name).copied().unwrap_or(0)
            ));
        }
        let (channels, maps) = network.listening();
        sender.reply(format!(
            "<gray>Listening to {} channels and {} maps.",
            channels.len(),
            maps.len()
        ));
        Ok(())
    }

    /// `/nf drain [off]`: this server takes no new players (or, with `off`, takes them again).
    fn drain(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        let on = !args.first().is_some_and(|a| a.eq_ignore_ascii_case("off"));
        let events = self.events()?;
        let session = shared(&events)?;
        let link = session
            .shared()
            .network
            .borrow()
            .link()
            .ok_or("this server isn't part of a network")?;
        if link.state.borrow().shutdown.is_some() {
            sender.reply("<red>The server is shutting down.".into());
            return Ok(());
        }
        link.set_draining(on);
        session.platform().log().info(format!(
            "{} {} (/nf drain)",
            sender.name(),
            if on {
                "drained the server"
            } else {
                "ended the server's draining"
            }
        ));
        sender.reply(if on {
            "<gold>Draining: <gray>no new players are sent here. /nf drain off undoes it.".into()
        } else {
            "<green>Taking players again.".into()
        });
        Ok(())
    }

    /// `/nf shutdown [--after-empty] [--timeout <seconds>]`: drains, waits for the server to empty when asked (up
    /// to the timeout), sends the players left to the group's fallback, and asks the server to stop.
    fn shutdown(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        let after_empty = args.contains(&"--after-empty");
        let timeout = match args.iter().position(|a| *a == "--timeout") {
            None => DEFAULT_SHUTDOWN_SECONDS,
            Some(at) => args
                .get(at + 1)
                .and_then(|t| t.strip_suffix('s').unwrap_or(t).parse::<u64>().ok())
                .filter(|t| *t > 0)
                .ok_or("--timeout takes a number of seconds: /nf shutdown --timeout 60")?,
        };
        let events = self.events()?;
        let session = shared(&events)?;
        session.platform().log().info(format!(
            "{} is shutting the server down (/nf shutdown{}, {timeout} seconds)",
            sender.name(),
            if after_empty { " --after-empty" } else { "" }
        ));
        let has_link = session.shared().network.borrow().link().is_some();
        if has_link {
            let room = session.room().ok_or_else(|| not_running(&session))?;
            crate::network::service::NetworkContext {
                shared: session.shared().clone(),
                scripts: room.scripts().clone(),
            }
            .shutdown(after_empty, timeout);
        } else {
            // Not in any network: nobody to hand the players to, so the server is asked to stop at once.
            session.shared().network.borrow_mut().stop_server =
                Some("NetherForge: /nf shutdown".into());
        }
        sender.reply(format!(
            "<gold>Shutting down: <gray>{}",
            if after_empty {
                format!("draining, then stopping once it's empty (or in {timeout} seconds).")
            } else {
                "sending everyone to the fallback, then stopping.".into()
            }
        ));
        Ok(())
    }

    // ---- settings ----

    /// `/nf settings`: the packages' server-owner settings. A player gets the dialog to change them; `list` shows
    /// them, `set <setting> <value>` and `reset <setting>` change one (`name` for the project's,
    /// `namespace:name` for a package's), and `reload` reads the settings files again.
    fn settings(&self, sender: &Rc<dyn CommandSender>, args: &[&str], text: &str) -> Said {
        let events = self.events()?;
        {
            let session = shared(&events)?;
            if !session.running() {
                return Err("the project isn't running".into());
            }
        }
        match args.first().map(|a| a.to_lowercase()).as_deref() {
            None => {
                let Some(player) = sender.player() else {
                    return self.list_settings(sender, None);
                };
                self.show_settings(&events, &player)
            }
            Some("list") => self.list_settings(sender, args.get(1).copied()),
            Some("set") => {
                let Some(label) = args.get(1) else {
                    return usage(sender, "settings set <setting> <value>");
                };
                // The value is the rest of the line as typed, spaces and all.
                let value = rest_after_words(text, 3);
                let (Some(value), true) = (value, args.len() >= 3) else {
                    return usage(sender, &format!("settings set {label} <value>"));
                };
                let mut session = events
                    .session_mut_now()
                    .ok_or("the project is busy; try again")?;
                let (namespace, name) = setting_name(&session, label);
                let changed = session.set_setting_text(&namespace, &name, &value)?;
                let shown = current_shown(&session, &namespace, &name);
                let shown = escape(&session, &shown);
                sender.reply(if changed {
                    format!("<green>{label} is now {shown}.")
                } else {
                    format!("<gray>{label} was already {shown}.")
                });
                Ok(())
            }
            Some("reset") => {
                let Some(label) = args.get(1) else {
                    return usage(sender, "settings reset <setting>");
                };
                let mut session = events
                    .session_mut_now()
                    .ok_or("the project is busy; try again")?;
                let (namespace, name) = setting_name(&session, label);
                session.set_setting(&namespace, &name, None)?;
                let definition = session
                    .shared()
                    .settings
                    .borrow()
                    .definition(&namespace, &name)?;
                let shown = escape(&session, &definition.show(&definition.default_value()));
                sender.reply(format!("<green>{label} is back to its default, {shown}."));
                Ok(())
            }
            Some("reload") => {
                let mut session = events
                    .session_mut_now()
                    .ok_or("the project is busy; try again")?;
                let changed = session.reload_settings();
                sender.reply(format!(
                    "<green>Read the settings files again: {} changed.",
                    if changed == 1 {
                        "1 setting".to_owned()
                    } else {
                        format!("{changed} settings")
                    }
                ));
                Ok(())
            }
            _ => usage(
                sender,
                "settings [list|set <setting> <value>|reset <setting>|reload]",
            ),
        }
    }

    /// Every package's settings (or `namespace`'s): each value, whether it's the default, and what it's for.
    fn list_settings(&self, sender: &Rc<dyn CommandSender>, namespace: Option<&str>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        let settings = session.shared().settings.borrow();
        let namespaces = match namespace {
            Some(ns) => vec![ns.to_owned()],
            None => settings.namespaces(),
        };
        if namespaces.is_empty() {
            sender.reply("<gray>No package here has settings.".into());
            return Ok(());
        }
        let home = session.project().namespace().to_owned();
        for ns in namespaces {
            let values = settings.values(&ns)?;
            sender.reply(format!(
                "<gold>{} <gray>({ns}, settings/{ns}.json)",
                escape(
                    &session,
                    &settings.package_name(&ns).unwrap_or_else(|| ns.clone())
                )
            ));
            for (name, definition, value) in values {
                let shown = escape(&session, &definition.show(&value));
                let default = if settings.is_set(&ns, &name) {
                    format!(
                        " <gray>(default {})",
                        escape(&session, &definition.show(&definition.default_value()))
                    )
                } else {
                    " <gray>(default)".into()
                };
                let label = if ns == home {
                    name.clone()
                } else {
                    format!("{ns}:{name}")
                };
                sender.reply(format!(
                    "<yellow>{label} <white>{shown}{default} <dark_gray>{}",
                    escape(&session, definition.description())
                ));
            }
        }
        Ok(())
    }

    /// The settings as an in-game dialog, for a server with no editor (Kotlin's `SettingsDialog`): one form per
    /// package, each setting an input of its kind, Save writing every value that changed (at the next tick); with
    /// several packages, a list to pick one from. Built for one showing, answered by the runtime, not a script.
    fn show_settings(&self, events: &Rc<ServerEvents<'static>>, player: &PlayerRef) -> Said {
        let session = shared(events)?;
        let settings = session.shared().settings.borrow();
        let namespaces = settings.namespaces();
        if namespaces.is_empty() {
            return Err("no package here has settings".into());
        }
        let platform = session.platform_rc();
        let esc = |t: &str| platform.text().escape(t.to_owned());
        let mut forms = Vec::new();
        for ns in &namespaces {
            let values = settings.values(ns)?;
            let lines: Vec<String> = values
                .iter()
                .map(|(name, d, _)| {
                    format!("<yellow>{name}</yellow> <gray>{}", esc(d.description()))
                })
                .collect();
            let package = esc(&settings.package_name(ns).unwrap_or_else(|| ns.clone()));
            let inputs: Vec<Value> = values
                .iter()
                .map(|(name, d, value)| settings_input(name, d, value, &esc))
                .collect();
            let file = serde_json::json!({
                "title": format!("Settings: {package}"),
                "externalTitle": package,
                "type": "confirmation",
                "body": [
                    { "type": "message", "text": INTRO },
                    { "type": "message", "text": lines.join("\n") },
                ],
                "inputs": inputs,
                "buttons": [
                    { "key": SAVE, "label": "<green>Save" },
                    { "key": CANCEL, "label": "Cancel" },
                ],
            });
            forms.push(netherforge_platform::DialogSpec {
                id: format!("{SETTINGS_PREFIX}{ns}"),
                file: serde_json::from_value(file).map_err(|e| e.to_string())?,
                listed: Vec::new(),
            });
        }
        let spec = if forms.len() == 1 {
            forms.pop().expect("one")
        } else {
            netherforge_platform::DialogSpec {
                id: SETTINGS_LIST.into(),
                file: serde_json::from_value(serde_json::json!({
                    "title": "Settings",
                    "type": "dialog_list",
                    "body": [{ "type": "message", "text": INTRO }],
                }))
                .map_err(|e| e.to_string())?,
                listed: forms,
            }
        };
        drop(settings);
        let me = self.me.borrow().clone();
        let weak_events = Rc::downgrade(events);
        let dialog = crate::dialog::RuntimeDialog {
            spec,
            pressed: Rc::new(move |player, dialog, button, answers| {
                let (Some(me), Some(events)) = (me.upgrade(), weak_events.upgrade()) else {
                    return;
                };
                me.settings_pressed(&events, player, dialog, button, answers);
            }),
        };
        let shown = session
            .room()
            .map(|room| {
                crate::dialog::DialogContext {
                    shared: session.shared().clone(),
                    scripts: room.scripts().clone(),
                }
                .show_runtime(player, dialog)
            })
            .unwrap_or(false);
        if !shown {
            return Err("couldn't show the settings dialog".into());
        }
        Ok(())
    }

    /// A press in the settings dialog: Save sets every setting whose value changed, at the next tick.
    fn settings_pressed(
        &self,
        events: &ServerEvents<'static>,
        player: &PlayerRef,
        dialog: &str,
        button: &str,
        answers: &HashMap<String, Dynamic>,
    ) {
        let Some(namespace) = dialog.strip_prefix(SETTINGS_PREFIX) else {
            return;
        };
        if button != SAVE {
            return;
        }
        let Ok(session) = events.try_session() else {
            return;
        };
        let settings = session.shared().settings.borrow();
        let Ok(values) = settings.values(namespace) else {
            return;
        };
        let mut lines = Vec::new();
        let mut accepted = Vec::new();
        for (name, definition, value) in values {
            let Some(answer) = answers.get(&name) else {
                continue;
            };
            // A slider answers a number (12.0 for a whole one); everything else its words.
            let text = match answer {
                Dynamic::Number(n) if n.fract() == 0.0 => format!("{}", *n as i64),
                Dynamic::Number(n) => n.to_string(),
                Dynamic::String(s) => s.clone(),
                Dynamic::Bool(b) => b.to_string(),
                other => format!("{other:?}"),
            };
            if text == definition.show(&value) {
                continue;
            }
            match definition.parse(&text) {
                SettingRead::Ok(json) => accepted.push((name, Some(json))),
                SettingRead::Bad(message) => lines.push(format!(
                    "<red>{name}: {}; it stays {}",
                    escape(&session, &message),
                    escape(&session, &definition.show(&value))
                )),
            }
        }
        self.held.borrow_mut().push(HeldSave {
            namespace: namespace.to_owned(),
            values: accepted,
            lines,
            player: player.uuid,
        });
    }

    // ---- the store ----

    /// `/nf data`: what the runtime keeps in its store (Kotlin's `DataCommand`): every table's size, a table's
    /// first rows, one saved table's JSON, or everything exported as JSON into `exports/`.
    fn data(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        match args.first().map(|a| a.to_lowercase()).as_deref() {
            None => self.data_sizes(sender),
            Some("show") => self.data_show(sender, &args[1..]),
            Some("export") => self.data_export(sender),
            Some(table) => self.data_rows(sender, table),
        }
    }

    fn data_sizes(&self, sender: &Rc<dyn CommandSender>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        session.save_data();
        let store = &session.shared().store;
        let file = store.file();
        let bytes = file
            .as_ref()
            .and_then(|f| std::fs::metadata(f).ok())
            .map_or(0, |m| m.len());
        let name = file.as_ref().and_then(|f| f.file_name()).map_or_else(
            || "netherforge.db".to_owned(),
            |n| n.to_string_lossy().into_owned(),
        );
        let shown = file
            .as_ref()
            .map_or_else(|| "in memory".to_owned(), |f| f.display().to_string());
        sender.reply(format!(
            "<gold>{name} <gray>({:.1} KiB, {shown})",
            bytes as f64 / 1024.0
        ));
        for size in store.sizes() {
            let namespaces = if size.by_namespace.is_empty() {
                String::new()
            } else {
                let each: Vec<String> = size
                    .by_namespace
                    .iter()
                    .map(|(ns, n)| format!("{ns} {n}"))
                    .collect();
                format!(" <gray>({})", each.join(", "))
            };
            sender.reply(format!(
                "<yellow>{} <white>{}{namespaces}",
                size.table, size.rows
            ));
        }
        sender.reply(
            "<gray>/nf data <table>, /nf data show <player|centity|named> <who>, /nf data export"
                .into(),
        );
        Ok(())
    }

    fn data_rows(&self, sender: &Rc<dyn CommandSender>, table: &str) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        session.save_data();
        let (rows, total) = session.shared().store.rows(table, MAX_ROWS)?;
        if rows.is_empty() {
            sender.reply(format!("<gray>{table} is empty."));
            return Ok(());
        }
        sender.reply(format!(
            "<gold>{table} <gray>({total} {})",
            if total == 1 { "row" } else { "rows" }
        ));
        for row in &rows {
            let cells: Vec<String> = row
                .iter()
                .map(|(column, value)| format!("<gray>{column}=<white>{}", clip(value)))
                .collect();
            sender.reply(format!("<white>{}", cells.join("<gray>, <white>")));
        }
        if total > rows.len() as i64 {
            sender.reply(format!(
                "<gray>…and {} more: /nf data export",
                total - rows.len() as i64
            ));
        }
        Ok(())
    }

    fn data_show(&self, sender: &Rc<dyn CommandSender>, args: &[&str]) -> Said {
        use crate::data::Owner;
        let wrong = "/nf data show <player|centity|named> <who>".to_owned();
        let kind = args.first().map(|k| k.to_lowercase());
        let who = args.get(1).ok_or(wrong.clone())?;
        let events = self.events()?;
        let session = shared(&events)?;
        let (owner, label) = match kind.as_deref() {
            Some("player") => {
                let id = session
                    .platform()
                    .players()
                    .find((*who).into())
                    .map(|p| p.uuid)
                    .or_else(|| crate::events::parse_uuid(who))
                    .ok_or_else(|| format!("no player \"{who}\" online; give their UUID"))?;
                (Owner::Player(id), format!("player {who}"))
            }
            Some("centity") => {
                let ids = records(&session);
                let id = by_prefix(ids.into_iter(), who, "instance")?;
                (
                    Owner::Centity(id),
                    format!("centity {}", crate::events::uuid_text(&id)),
                )
            }
            Some("named") => {
                let home = session.project().namespace().to_owned();
                let key =
                    netherforge_format::reference::resolve_any(&home, who).ok_or_else(|| {
                        format!("\"{who}\" isn't a data table's name (name, or namespace:name)")
                    })?;
                (
                    Owner::Named {
                        namespace: key.namespace.clone(),
                        name: key.path.clone(),
                    },
                    format!("nf.data(\"{}\") of {}", key.path, key.namespace),
                )
            }
            _ => return Err(wrong),
        };
        session.save_data_of(&owner);
        let Some(text) = session.shared().store.data(&owner) else {
            sender.reply(format!("<gray>Nothing is saved for {label}."));
            return Ok(());
        };
        let pretty = serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(|v| pretty_json(&v))
            .unwrap_or_else(|| text.clone());
        let lines: Vec<&str> = pretty.lines().collect();
        sender.reply(format!("<gold>{label} <gray>({} bytes)", text.len()));
        for line in lines.iter().take(MAX_LINES) {
            sender.reply(format!("<white>{line}"));
        }
        if lines.len() > MAX_LINES {
            sender.reply(format!(
                "<gray>…and {} more lines: /nf data export",
                lines.len() - MAX_LINES
            ));
        }
        Ok(())
    }

    fn data_export(&self, sender: &Rc<dyn CommandSender>) -> Said {
        let events = self.events()?;
        let session = shared(&events)?;
        session.save_data();
        let name = format!(
            "store-{}.json",
            chrono::Local::now().format("%Y%m%d-%H%M%S")
        );
        let file = self.state_dir.join("exports").join(name);
        sender.reply("<gold>Exporting the store…".into());
        let (tx, rx) = std::sync::mpsc::channel();
        session.shared().store.export(file, move |result| {
            let _ = tx.send(result);
        });
        self.exports.borrow_mut().push((rx, sender.clone()));
        Ok(())
    }

    /// Tells whoever exported what came of it, on the main thread, each tick.
    pub fn poll_exports(&self, events: &ServerEvents<'static>) {
        let mut exports = self.exports.borrow_mut();
        exports.retain(|(rx, sender)| match rx.try_recv() {
            Ok(Ok(file)) => {
                sender.reply(format!("<green>Exported the store to {}", file.display()));
                false
            }
            Ok(Err(why)) => {
                if let Ok(session) = events.try_session() {
                    session
                        .platform()
                        .log()
                        .error(format!("Couldn't export the store: {why}"), None);
                }
                sender.reply(format!("<red>Couldn't export the store: {why}"));
                false
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => true,
            Err(std::sync::mpsc::TryRecvError::Disconnected) => false,
        });
    }

    // ---- completion ----

    /// Suggestions for the last of `args`, the words typed so far with the one being typed last.
    fn options(&self, args: &[&str]) -> Vec<String> {
        let prefix = args.last().copied().unwrap_or("");
        let Ok(events) = self.events() else {
            return Vec::new();
        };
        let Ok(session) = events.try_session() else {
            return Vec::new();
        };
        let first = args.first().map(|a| a.to_lowercase()).unwrap_or_default();
        let owned = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        let options: Vec<String> = if args.len() >= 2 && first == "data" {
            self.data_options(&session, &args[1..])
        } else {
            match args.len() {
                0 | 1 => owned(SUBCOMMANDS),
                2 => match first.as_str() {
                    "spawn" | "find" => session.centities().definition_ids(),
                    "kill" => std::iter::once("all".to_owned())
                        .chain(session.centities().definition_ids())
                        .chain(session.centities().all().map(|i| short(i.id)))
                        .collect(),
                    "tp" => session.centities().all().map(|i| short(i.id)).collect(),
                    "reload" => std::iter::once(netherforge_format::manifest::FILE_NAME.to_owned())
                        .chain(session.source().files().iter().cloned())
                        .collect(),
                    "pack" => owned(&["send"]),
                    "scripts" => owned(&["all"]),
                    "profile" => owned(&["10", "30", "60"]),
                    "settings" => owned(&["list", "set", "reset", "reload"]),
                    "drain" => owned(&["off"]),
                    "shutdown" => owned(&["--after-empty", "--timeout"]),
                    _ => Vec::new(),
                },
                3 => {
                    let second = args[1].to_lowercase();
                    if first == "spawn" {
                        online(&session)
                    } else if first == "settings" && (second == "set" || second == "reset") {
                        setting_labels(&session)
                    } else if first == "settings" && second == "list" {
                        session.shared().settings.borrow().namespaces()
                    } else {
                        Vec::new()
                    }
                }
                4 if first == "settings" && args[1].eq_ignore_ascii_case("set") => {
                    setting_values(&session, args[2])
                }
                _ => Vec::new(),
            }
        };
        let lower = prefix.to_lowercase();
        options
            .into_iter()
            .filter(|o| o.to_lowercase().starts_with(&lower))
            .collect()
    }

    fn data_options(&self, session: &Session, args: &[&str]) -> Vec<String> {
        let show = args.first().is_some_and(|a| a.eq_ignore_ascii_case("show"));
        match args.len() {
            1 => ["show", "export"]
                .iter()
                .map(|s| s.to_string())
                .chain(session.shared().store.table_names())
                .collect(),
            2 if show => vec!["player".into(), "centity".into(), "named".into()],
            3 if show && args[1].eq_ignore_ascii_case("player") => online(session),
            3 if show && args[1].eq_ignore_ascii_case("centity") => {
                records(session).into_iter().map(short).collect()
            }
            _ => Vec::new(),
        }
    }
}

impl CommandHandler for AdminCommand {
    fn run(&self, sender: Rc<dyn CommandSender>, _label: String, input: CommandInput) {
        let text = match input.arguments.get(ARGUMENTS) {
            Some(ArgumentValue::Text(t)) => t.text.clone(),
            _ => String::new(),
        };
        if let Err(why) = self.dispatch(&sender, &text) {
            sender.reply(format!("<red>{why}"));
        }
    }

    fn suggest(&self, _sender: Rc<dyn CommandSender>, request: SuggestionRequest) -> Completion {
        let partial = request.partial;
        let words: Vec<&str> = partial.trim_start().split(' ').collect();
        Completion {
            start: partial.rfind(' ').map_or(0, |i| i as i32 + 1),
            suggestions: self.options(&words),
        }
    }
}

// ---- helpers ----

fn shared<'e>(
    events: &'e ServerEvents<'static>,
) -> Result<std::cell::Ref<'e, Session<'static>>, String> {
    events
        .try_session()
        .map_err(|_| "the project is busy; try again".to_owned())
}

fn usage(sender: &Rc<dyn CommandSender>, text: &str) -> Said {
    sender.reply(format!("<gray>/nf {text}"));
    Ok(())
}

fn not_running(session: &Session) -> String {
    let why = session
        .problems()
        .into_iter()
        .next()
        .map_or(String::new(), |p| format!(": {}", p.message));
    format!("the project isn't running{why}")
}

fn escape(session: &Session, text: &str) -> String {
    session.platform().text().escape(text.to_owned())
}

fn centity_context(session: &Session) -> Option<crate::centity::api::CentityContext> {
    let room = session.room()?;
    Some(crate::centity::api::CentityContext::new(
        session.shared().clone(),
        room.scripts().clone(),
    ))
}

fn short(id: Uuid) -> String {
    crate::events::uuid_text(&id)[..SHORT].to_owned()
}

fn place(at: &Location) -> String {
    format!("{} {:.1} {:.1} {:.1}", at.world, at.x, at.y, at.z)
}

fn distance(from: Option<&Location>, to: &Location) -> f64 {
    match from {
        Some(f) if f.world == to.world => {
            let (dx, dy, dz) = (f.x - to.x, f.y - to.y, f.z - to.z);
            (dx * dx + dy * dy + dz * dz).sqrt()
        }
        _ => f64::MAX,
    }
}

/// Two blocks in front of where `at` looks, on whole blocks, facing south.
fn in_front(at: &Location) -> Location {
    let yaw = at.yaw.to_radians();
    Location {
        world: at.world.clone(),
        x: (at.x - yaw.sin() * 2.0).floor(),
        y: at.y.floor(),
        z: (at.z + yaw.cos() * 2.0).floor(),
        yaw: 0.0,
        pitch: 0.0,
    }
}

/// A centity's full name as the project calls it: its own without the namespace.
fn relative(full: &str, home: &str) -> String {
    full.strip_prefix(&format!("{home}:"))
        .unwrap_or(full)
        .to_owned()
}

/// The one id among `ids` starting with `prefix`.
fn by_prefix(ids: impl Iterator<Item = Uuid>, prefix: &str, what: &str) -> Result<Uuid, String> {
    let prefix = prefix.to_lowercase();
    let matches: Vec<Uuid> = ids
        .filter(|id| crate::events::uuid_text(id).starts_with(&prefix))
        .collect();
    match matches.len() {
        0 => Err(format!("no {what} \"{prefix}\"")),
        1 => Ok(matches[0]),
        n => Err(format!(
            "\"{prefix}\" matches {n} instances; give more of the id"
        )),
    }
}

fn count(n: usize, noun: &str) -> String {
    format!("{n} {noun}{}", if n == 1 { "" } else { "s" })
}

/// A length of whole minutes as `2d 3h 5m`, units that are 0 left out; under a minute is `under a minute`.
fn minutes(total: i64) -> String {
    if total < 1 {
        return "under a minute".into();
    }
    [
        (total / 1440, "d"),
        (total % 1440 / 60, "h"),
        (total % 60, "m"),
    ]
    .iter()
    .filter(|(n, _)| *n > 0)
    .map(|(n, unit)| format!("{n}{unit}"))
    .collect::<Vec<_>>()
    .join(" ")
}

fn clip(value: &str) -> String {
    if value.chars().count() > MAX_VALUE {
        format!("{}…", value.chars().take(MAX_VALUE).collect::<String>())
    } else {
        value.to_owned()
    }
}

/// JSON as kotlinx prints it pretty: four spaces a level.
fn pretty_json(value: &Value) -> Option<String> {
    let mut out = Vec::new();
    let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut out, formatter);
    serde::Serialize::serialize(value, &mut ser).ok()?;
    String::from_utf8(out).ok()
}

/// What follows the first `words` words of `text`, spaces and all (`settings set <name> <the rest>`).
fn rest_after_words(text: &str, words: usize) -> Option<String> {
    let mut rest = text;
    for _ in 0..words {
        rest = rest.trim_start();
        let end = rest.find(char::is_whitespace)?;
        rest = &rest[end..];
    }
    let value = rest.strip_prefix(|c: char| c.is_whitespace())?;
    (!value.is_empty()).then(|| value.to_owned())
}

/// A setting as `/nf settings` takes it: `name` for the project's own, `namespace:name` for a package's.
fn setting_name(session: &Session, text: &str) -> (String, String) {
    match text.split_once(':') {
        Some((ns, name)) => (ns.to_owned(), name.to_owned()),
        None => (session.project().namespace().to_owned(), text.to_owned()),
    }
}

fn current_shown(session: &Session, namespace: &str, name: &str) -> String {
    let settings = session.shared().settings.borrow();
    settings
        .values(namespace)
        .ok()
        .and_then(|v| {
            v.into_iter()
                .find(|(n, _, _)| n == name)
                .map(|(_, d, value)| d.show(&value))
        })
        .unwrap_or_default()
}

fn setting_labels(session: &Session) -> Vec<String> {
    let settings = session.shared().settings.borrow();
    let home = session.project().namespace().to_owned();
    settings
        .namespaces()
        .into_iter()
        .flat_map(|ns| {
            let names: Vec<String> = settings
                .values(&ns)
                .unwrap_or_default()
                .into_iter()
                .map(|(n, _, _)| if ns == home { n } else { format!("{ns}:{n}") })
                .collect();
            names
        })
        .collect()
}

/// What a setting's value could be, where there's a short list: a choice's choices, true and false.
fn setting_values(session: &Session, label: &str) -> Vec<String> {
    let (ns, name) = setting_name(session, label);
    match session.shared().settings.borrow().definition(&ns, &name) {
        Ok(SettingDef::Choice { choices, .. }) => choices,
        Ok(SettingDef::Boolean { .. }) => vec!["true".into(), "false".into()],
        _ => Vec::new(),
    }
}

/// The input a setting is changed with in the dialog, starting at its value: its kind's.
fn settings_input(
    name: &str,
    definition: &SettingDef,
    value: &Value,
    esc: &dyn Fn(&str) -> String,
) -> Value {
    let text = || {
        let shown = definition.show(value);
        serde_json::json!({
            "type": "text",
            "key": name,
            "label": name,
            "initial": shown,
            "maxLength": MAX_TEXT.max(shown.chars().count()),
        })
    };
    match definition {
        SettingDef::Boolean { .. } => serde_json::json!({
            "type": "boolean",
            "key": name,
            "label": name,
            "initial": value.as_bool().unwrap_or(false),
            "onTrue": "true",
            "onFalse": "false",
        }),
        SettingDef::Integer {
            min: Some(min),
            max: Some(max),
            ..
        } if (1..=MAX_SLIDER_STEPS).contains(&(max - min)) => serde_json::json!({
            "type": "number_range",
            "key": name,
            "label": name,
            "start": *min as f64,
            "end": *max as f64,
            "step": 1.0,
            "initial": value.as_f64().unwrap_or(*min as f64),
        }),
        SettingDef::Choice { choices, .. } => serde_json::json!({
            "type": "single_option",
            "key": name,
            "label": name,
            "options": choices.iter().map(|c| {
                let mut option = serde_json::json!({ "id": c, "label": esc(c) });
                if value.as_str() == Some(c.as_str()) {
                    option["initial"] = Value::Bool(true);
                }
                option
            }).collect::<Vec<_>>(),
        }),
        _ => text(),
    }
}

fn online(session: &Session) -> Vec<String> {
    session
        .platform()
        .players()
        .online()
        .into_iter()
        .map(|p| p.name)
        .collect()
}

/// Every centity the store or the world knows: the live, the inert and what's only stored.
fn records(session: &Session) -> Vec<Uuid> {
    let mut ids: Vec<Uuid> = session.centities().all().map(|i| i.id).collect();
    ids.extend(session.centities().inert().map(|r| r.id));
    for record in session.shared().store.instances() {
        if !ids.contains(&record.id) {
            ids.push(record.id);
        }
    }
    ids
}
