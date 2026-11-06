//! Other plugins (Kotlin's `interop/`): `nf.economy` through Vault and `nf.placeholders` through PlaceholderAPI, each
//! behind its `plugin:` requirement. There's no general way to call another plugin; the Platform's `PluginOps`,
//! `EconomyOps` and `PlaceholderOps` are the whole of it.
//!
//! - **Presence is never decided once** (`Plugins`): the declared plugins the server lacks are worked out as the
//!   session starts, again on the first tick, and on any tick after `plugins_changed`; each is the problem
//!   `runtime.plugin-missing` on each declaring package's manifest. A call checks again and fails saying so.
//! - **Placeholders** (`Placeholders`): one expansion per namespace, owned by the registering scope, gone with it.
//!   The Platform takes the resolver as an `Rc`, so it's only ever asked on the main thread: the adapter's glue answers
//!   other threads (async chat, scoreboard plugins) from what the resolver last gave, as Kotlin's resolver did itself.
//!   A callback asked for its own placeholder from inside itself is answered none. After a plugin change, expansions
//!   PlaceholderAPI forgot are registered again.

use std::cell::{Cell, RefCell};
use std::collections::BTreeSet;
use std::rc::Rc;

use mlua::{Function, Value};
use netherforge_api::api::{NfEconomyApi, NfPlaceholdersApi};
use netherforge_api::{ApiError, ApiResult, Caller, handles};
use netherforge_format::Problem;
use netherforge_format::requirement::Requirement;
use netherforge_luau::ScopeId;
use netherforge_platform::{EconomyOps, PlaceholderResolver, Platform, Uuid};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::scripts::{Scripts, WeakScripts};
use crate::session::{Define, Service};
use crate::shared::Shared;

const PLACEHOLDER_API: &str = "placeholderapi";
const VAULT: &str = "vault";
const WHOLE_LIMIT: f64 = 1e15;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The plugins the project's packages declared, and which of them the server lacks.
#[derive(Default)]
pub struct Plugins {
    /// Each declared plugin with the packages that declare it, in the order requirements are shown.
    declared: Vec<(String, Vec<String>)>,
    missing: BTreeSet<String>,
    /// Something changed since the last look.
    stale: bool,
    home: String,
}

/// An expansion a script offers: `%<namespace>_<key>%`.
struct Expansion {
    scope: ScopeId,
    namespace: String,
    callback: Function,
    /// The callback is running: a request it makes itself isn't answered.
    running: Cell<bool>,
    live: Cell<bool>,
}

/// The placeholders scripts registered, by namespace.
#[derive(Default)]
pub struct Placeholders {
    expansions: Vec<Rc<Expansion>>,
}

impl Placeholders {
    /// How many placeholders `scope` provides (`/nf scripts`' count).
    pub fn count_owned(&self, scope: ScopeId) -> usize {
        self.expansions
            .iter()
            .filter(|e| e.scope == scope && e.live.get())
            .count()
    }
}

/// What `Shared::interop` holds.
#[derive(Default)]
pub struct Interop {
    pub plugins: Plugins,
    pub placeholders: Placeholders,
}

impl Interop {
    /// Refuses `call` unless `plugin` is enabled now.
    fn require(platform: &dyn Platform, plugin: &str, call: &str) -> ApiResult<()> {
        if platform.plugins().is_enabled(plugin.into()) {
            return Ok(());
        }
        Err(err(format!(
            "{call} needs the plugin \"{plugin}\", which isn't enabled on this server"
        )))
    }

    fn look_missing(&self, platform: &dyn Platform) -> BTreeSet<String> {
        self.plugins
            .declared
            .iter()
            .map(|(p, _)| p.clone())
            .filter(|p| !platform.plugins().is_enabled(p.clone()))
            .collect()
    }
}

/// The resolver PlaceholderAPI asks: the script's callback, as its scope's code.
struct Resolver {
    expansion: Rc<Expansion>,
    scripts: WeakScripts,
}

impl Resolver {
    /// What a callback returned as placeholder text: a string, a number (a whole one without `.0`) or a boolean.
    fn text(value: &Value) -> Option<String> {
        match value {
            Value::String(s) => Some(s.to_string_lossy()),
            Value::Boolean(b) => Some(b.to_string()),
            Value::Integer(i) => Some(i.to_string()),
            Value::Number(n) => Some(
                if n.is_finite() && *n == n.round() && n.abs() < WHOLE_LIMIT {
                    (*n as i64).to_string()
                } else {
                    n.to_string()
                },
            ),
            _ => None,
        }
    }
}

impl PlaceholderResolver for Resolver {
    fn resolve(&self, player: Option<Uuid>, key: String) -> Option<String> {
        let expansion = &self.expansion;
        if !expansion.live.get() || expansion.running.get() {
            return None;
        }
        let scripts = self.scripts.upgrade()?;
        expansion.running.set(true);
        let handle = player.map(|p| handles::Player { id: uuid_text(&p) });
        let lua = scripts.host().lua().clone();
        let player = match handle {
            Some(h) => netherforge_api::handle::push_any(&lua, &handles::Handle::Player(h))
                .map(Value::UserData)
                .unwrap_or(Value::Nil),
            None => Value::Nil,
        };
        let result = scripts.call_kept(expansion.scope, &expansion.callback, (key.clone(), player));
        expansion.running.set(false);
        match result {
            Ok(value) => Self::text(&value),
            Err(failure) => {
                scripts.handler_failed(
                    expansion.scope,
                    &format!("placeholder %{}_{key}%", expansion.namespace),
                    failure,
                );
                None
            }
        }
    }
}

/// `nf.economy` and `nf.placeholders`.
#[derive(Clone)]
pub struct InteropContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl InteropContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn economy(&self, call: &str) -> ApiResult<&dyn EconomyOps> {
        Interop::require(self.platform(), VAULT, call)?;
        self.platform().economy().ok_or_else(|| {
            err(format!(
                "{call} needs an economy: Vault is enabled, but no economy plugin has registered with it (EssentialsX, for one, does)"
            ))
        })
    }

    fn amount(call: &str, amount: f64) -> ApiResult<()> {
        if !amount.is_finite() || amount <= 0.0 {
            return Err(err(format!(
                "bad argument 'amount' ({call} takes an amount above 0 that isn't infinite or NaN, not {amount})"
            )));
        }
        Ok(())
    }

    fn resolver(&self, expansion: &Rc<Expansion>) -> Rc<dyn PlaceholderResolver> {
        Rc::new(Resolver {
            expansion: expansion.clone(),
            scripts: self.scripts.downgrade(),
        })
    }

    fn release(&self, expansion: &Expansion) {
        expansion.live.set(false);
        if self.platform().plugins().is_enabled(PLACEHOLDER_API.into()) {
            self.platform()
                .placeholders()
                .unregister(expansion.namespace.clone());
        }
    }

    /// Every expansion `keep` doesn't keep goes.
    fn release_where(&self, gone: impl Fn(&Expansion) -> bool) {
        let released: Vec<Rc<Expansion>> = {
            let mut interop = self.shared.interop.borrow_mut();
            let (out, kept) = std::mem::take(&mut interop.placeholders.expansions)
                .into_iter()
                .partition(|e| gone(e));
            interop.placeholders.expansions = kept;
            out
        };
        for expansion in released {
            self.release(&expansion);
        }
    }
}

impl NfEconomyApi for InteropContext {
    fn balance(&self, _: &Caller, player: handles::Player) -> ApiResult<f64> {
        let economy = self.economy("nf.economy.balance")?;
        Ok(parse_uuid(&player.id).map_or(0.0, |u| economy.balance(u)))
    }

    fn deposit(&self, _: &Caller, player: handles::Player, amount: f64) -> ApiResult<Option<f64>> {
        Self::amount("nf.economy.deposit", amount)?;
        let economy = self.economy("nf.economy.deposit")?;
        Ok(parse_uuid(&player.id).and_then(|u| economy.deposit(u, amount)))
    }

    fn withdraw(&self, _: &Caller, player: handles::Player, amount: f64) -> ApiResult<Option<f64>> {
        Self::amount("nf.economy.withdraw", amount)?;
        let economy = self.economy("nf.economy.withdraw")?;
        Ok(parse_uuid(&player.id).and_then(|u| economy.withdraw(u, amount)))
    }
}

impl NfPlaceholdersApi for InteropContext {
    fn parse(
        &self,
        _: &Caller,
        text: String,
        player: Option<handles::Player>,
    ) -> ApiResult<String> {
        Interop::require(self.platform(), PLACEHOLDER_API, "nf.placeholders.parse")?;
        let player = player.and_then(|p| parse_uuid(&p.id));
        Ok(self.platform().placeholders().parse(player, text))
    }

    fn register(&self, caller: &Caller, namespace: String, callback: Function) -> ApiResult<()> {
        Interop::require(self.platform(), PLACEHOLDER_API, "nf.placeholders.register")?;
        let valid = (1..=32).contains(&namespace.len())
            && namespace.starts_with(|c: char| c.is_ascii_lowercase())
            && namespace
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit());
        if !valid {
            return Err(err(format!(
                "bad argument 'namespace' (\"{namespace}\" can't be a placeholder namespace: lowercase letters and digits, starting with a letter, at most 32 characters, since PlaceholderAPI ends it at the first `_`)"
            )));
        }
        // A package's own: it answers `%<its namespace>…_…%` and never another plugin's, which it could otherwise take
        // before their expansion loads. Spelled without `_`, where PlaceholderAPI ends a placeholder's namespace.
        let own = self
            .scripts
            .namespace(caller.scope)
            .unwrap_or_else(|| self.shared.interop.borrow().plugins.home.clone());
        let prefix: String = own.chars().filter(|c| c.is_alphanumeric()).collect();
        if !namespace.starts_with(&prefix) {
            return Err(err(format!(
                "bad argument 'namespace' (a package's placeholders start with its own namespace, \"{prefix}\": \"{prefix}\" or \"{prefix}stats\", not \"{namespace}\")"
            )));
        }
        let taken = self
            .shared
            .interop
            .borrow()
            .placeholders
            .expansions
            .iter()
            .find(|e| e.namespace == namespace)
            .map(|e| e.scope);
        if let Some(scope) = taken {
            let label = self
                .scripts
                .host()
                .scope_name(scope)
                .unwrap_or_else(|| "another script".into());
            return Err(err(format!(
                "the placeholder namespace \"{namespace}\" is already registered by {label}"
            )));
        }
        let expansion = Rc::new(Expansion {
            scope: caller.scope,
            namespace: namespace.clone(),
            callback,
            running: Cell::new(false),
            live: Cell::new(true),
        });
        if !self
            .platform()
            .placeholders()
            .register(namespace.clone(), self.resolver(&expansion))
        {
            return Err(err(format!(
                "the placeholder namespace \"{namespace}\" is already used by another plugin"
            )));
        }
        self.shared
            .interop
            .borrow_mut()
            .placeholders
            .expansions
            .push(expansion);
        Ok(())
    }
}

/// Plugins and placeholders as a session's service.
pub struct InteropService {
    pub shared: Rc<Shared>,
    pub context: RefCell<Option<InteropContext>>,
}

impl Service for InteropService {
    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        vec![(
            "placeholders",
            self.shared.interop.borrow().placeholders.count_owned(scope),
        )]
    }

    fn name(&self) -> &'static str {
        "plugins"
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.context.borrow_mut() = Some(InteropContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        });
        let platform = &*self.shared.platform;
        let mut interop = self.shared.interop.borrow_mut();
        interop.plugins.home = project.project.namespace().to_owned();
        interop.plugins.declared = netherforge_format::requirement::combined(project.project)
            .into_iter()
            .filter_map(|u| match Requirement::parse(&u.requirement) {
                Some(Requirement::Plugin(name)) => Some((name, u.packages)),
                _ => None,
            })
            .collect();
        // Nothing to look at when nothing's declared: a platform without other plugins needn't be asked.
        if interop.plugins.declared.is_empty() {
            return;
        }
        interop.plugins.missing = interop.look_missing(platform);
        for (plugin, packages) in &interop.plugins.declared {
            if interop.plugins.missing.contains(plugin) {
                platform.log().warn(format!(
                    "{} need the plugin \"{plugin}\", which isn't enabled on this server (yet)",
                    packages.join(", ")
                ));
            }
        }
        // Plugins enabled after this one are looked at again on the first tick.
        interop.plugins.stale = true;
    }

    fn plugins_changed(&self) {
        self.shared.interop.borrow_mut().plugins.stale = true;
    }

    fn tick(&self, _room: &Room, platform: &dyn Platform) {
        let (stale, expansions) = {
            let mut interop = self.shared.interop.borrow_mut();
            let stale = std::mem::take(&mut interop.plugins.stale);
            (stale, interop.placeholders.expansions.clone())
        };
        if !stale {
            return;
        }
        {
            let mut interop = self.shared.interop.borrow_mut();
            let now = interop.look_missing(platform);
            if now != interop.plugins.missing {
                for plugin in now.difference(&interop.plugins.missing) {
                    platform.log().warn(format!(
                        "The plugin \"{plugin}\", which packages declare they need, is no longer enabled"
                    ));
                }
                for plugin in interop.plugins.missing.difference(&now) {
                    platform
                        .log()
                        .info(format!("The plugin \"{plugin}\" is enabled now"));
                }
                interop.plugins.missing = now;
            }
        }
        // PlaceholderAPI was reloaded or enabled again: it forgot what was registered with the one before.
        if expansions.is_empty() || !platform.plugins().is_enabled(PLACEHOLDER_API.into()) {
            return;
        }
        let Some(context) = self.context.borrow().clone() else {
            return;
        };
        for expansion in expansions {
            if platform
                .placeholders()
                .is_registered(expansion.namespace.clone())
            {
                continue;
            }
            if !platform
                .placeholders()
                .register(expansion.namespace.clone(), context.resolver(&expansion))
            {
                platform.log().warn(format!(
                    "The placeholder namespace \"{}\" is used by another plugin now, so %{}_…% has no value",
                    expansion.namespace, expansion.namespace
                ));
            }
        }
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let context = self.context.borrow().clone();
        if let Some(context) = context {
            context.release_where(|e| scopes.contains(&e.scope));
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let context = self.context.borrow_mut().take();
        if let Some(context) = context {
            context.release_where(|_| true);
        }
    }

    fn problems(&self) -> Vec<Problem> {
        let interop = self.shared.interop.borrow();
        let plugins = &interop.plugins;
        plugins
            .declared
            .iter()
            .filter(|(p, _)| plugins.missing.contains(p))
            .flat_map(|(plugin, packages)| {
                packages.iter().map(move |namespace| {
                    let file = if *namespace == plugins.home {
                        netherforge_format::manifest::FILE_NAME.to_owned()
                    } else {
                        format!("{namespace}:{}", netherforge_format::manifest::FILE_NAME)
                    };
                    netherforge_format::codes::RUNTIME_PLUGIN_MISSING.at(
                        &file,
                        format!(
                            "The plugin \"{plugin}\" isn't enabled on this server: calling what needs it is an error until it is"
                        ),
                        Some("$.requires.plugins".into()),
                    )
                })
            })
            .collect()
    }
}
