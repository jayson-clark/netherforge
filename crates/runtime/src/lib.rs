//! NetherForge's runtime in Rust (phase 1 of the cost roadmap, ../ROADMAP.md): what a
//! room runs. So far its scripts: scopes, events, timers and tasks. See the
//! rust-runtime skill.

use std::collections::BTreeMap;
use std::rc::Rc;

use mlua::{Function, Value};
use netherforge_api::api::{
    CentityApi, ImageApi, LuaApi, NfApi, NfCentitiesApi, NfImagesApi, NodeApi, SubscriptionApi,
    TaskApi,
};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, Runtime, Waiting, handles, shapes, unions};
use netherforge_format::manifest::Requires;
use netherforge_format::requirement::Requirement;
use netherforge_luau::{Host, ScopeId};

pub mod block;
pub mod centity;
pub mod data;
pub mod effects;
pub mod entity;
pub mod events;
pub mod images;
pub mod items;
pub mod living;
pub mod modules;
pub mod names;
pub mod permissions;
pub mod player;
pub mod schedule;
pub mod scripts;
pub mod server;
pub mod session;
pub mod shared;
pub mod stats;
pub mod store;
pub mod world;

pub use modules::{Modules, Owner, Project};
pub use scripts::{Delivered, Log, MAX_ERRORS, Report, Scripts, Target};
pub use shared::Shared;

/// What each package of a room's code has declared it may do (`requires` in its `netherforge.json`), and the
/// project's namespace: Kotlin's `Requirements`. A call is held to the package whose code makes it, never to the
/// project that depends on it: a library's script needs `moderation` in the library's own manifest.
#[derive(Clone, Debug, Default)]
pub struct Grants {
    /// The project's namespace: whose its own code is.
    pub home: String,
    /// Each package's declaration by namespace, the project's own included.
    pub requires: BTreeMap<String, Option<Requires>>,
}

impl Grants {
    /// Only the project, `home`, declaring `requires`.
    pub fn only(home: &str, requires: Option<Requires>) -> Grants {
        Grants {
            home: home.into(),
            requires: BTreeMap::from([(home.to_owned(), requires)]),
        }
    }
}

/// `nf` itself: the scripts' functions, and `nf.data`, whose tables are the session's.
struct NfContext {
    scripts: Scripts,
    shared: Option<Rc<Shared>>,
    grants: Grants,
}

impl NfApi for NfContext {
    fn on(
        &self,
        caller: &Caller,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        NfApi::on(&self.scripts, caller, event, handler, options)
    }

    fn once(
        &self,
        caller: &Caller,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        NfApi::once(&self.scripts, caller, event, handler)
    }

    fn emit(&self, caller: &Caller, event: String, payload: Value) -> ApiResult<bool> {
        NfApi::emit(&self.scripts, caller, event, payload)
    }

    fn after(&self, caller: &Caller, ticks: i64, callback: Function) -> ApiResult<handles::Task> {
        NfApi::after(&self.scripts, caller, ticks, callback)
    }

    fn every(&self, caller: &Caller, ticks: i64, callback: Function) -> ApiResult<handles::Task> {
        NfApi::every(&self.scripts, caller, ticks, callback)
    }

    fn task(
        &self,
        caller: &Caller,
        callback: Function,
        rest: Vec<Value>,
    ) -> ApiResult<handles::Task> {
        NfApi::task(&self.scripts, caller, callback, rest)
    }

    fn wait(&self, caller: &Caller, ticks: i64) -> ApiResult<Waiting> {
        NfApi::wait(&self.scripts, caller, ticks)
    }

    fn wait_until(
        &self,
        caller: &Caller,
        predicate: Function,
        timeout: Option<i64>,
    ) -> ApiResult<Waiting> {
        NfApi::wait_until(&self.scripts, caller, predicate, timeout)
    }

    fn wait_for(
        &self,
        caller: &Caller,
        handle: unions::CentityOrNodeOrEntityOrWorldOrProjectBlockOrMenuOrSlotOrMenuTemplateOrDialogOrButtonOrProjectItemOrEffectOrHttpStreamOrWebSocketOrOAuthFlowOrWasmProcessOrNetworkChannelOrNetworkMapOrCutsceneOrScreenOrPlaybackOrTable,
        event: String,
        filter: Option<Function>,
    ) -> ApiResult<Waiting> {
        NfApi::wait_for(&self.scripts, caller, handle, event, filter)
    }

    fn data(&self, caller: &Caller, name: String) -> ApiResult<Value> {
        if !netherforge_format::names::is_id(&name) {
            return Err(ApiError(format!(
                "\"{name}\" isn't a data table name ({})",
                netherforge_format::names::ID_RULE
            )));
        }
        let Some(shared) = &self.shared else {
            return NfApi::data(&Unported, caller, name);
        };
        // Each package's own: a dependency's `nf.data("config")` isn't the project's.
        let namespace = self
            .scripts
            .namespace(caller.scope)
            .unwrap_or_else(|| self.grants.home.clone());
        let host = self.scripts.host();
        data::ScriptData::table(
            shared,
            &host,
            data::Owner::Named {
                namespace,
                name: name.clone(),
            },
            format!("nf.data(\"{name}\")"),
        )
        .map(Value::Table)
        .map_err(|e| ApiError(e.to_string()))
    }

    fn db(&self, caller: &Caller, name: Option<String>) -> ApiResult<handles::Database> {
        NfApi::db(&self.scripts, caller, name)
    }

    fn config(&self, caller: &Caller, name: String) -> ApiResult<Value> {
        NfApi::config(&self.scripts, caller, name)
    }

    fn instructions_left(&self, caller: &Caller) -> ApiResult<i64> {
        NfApi::instructions_left(&self.scripts, caller)
    }
}

/// The API as the runtime implements it so far, every other class unported.
struct Api {
    scripts: Scripts,
    nf: NfContext,
    grants: Grants,
    /// The centity classes, when the room runs a session's centities.
    centities: Option<centity::api::CentityContext>,
    /// `nf.images` and `Image`, with them.
    images: Option<images::ImagesApi>,
    /// `World` and `nf.worlds`, with them.
    worlds: Option<world::GameContext>,
    /// `nf.schedule` and `Schedule`.
    schedules: Option<schedule::ScheduleContext>,
}

impl Runtime for Api {
    /// Format's `Requirement` decides, as the spec's `requires` names it (`db`, `http`, `plugin:vault`), for the
    /// package whose code `scope` runs, and a refusal says what to add, as the Kotlin runtime's does. A call no
    /// scope makes (none of the project's code) passes only when every package declares it.
    fn requires(&self, scope: Option<ScopeId>, requirement: &str, called: &str) -> ApiResult<()> {
        let Some(wanted) = Requirement::parse(requirement) else {
            return Err(ApiError(format!(
                "{called} needs \"{requirement}\", which isn't a requirement"
            )));
        };
        let granted = |namespace: &str| {
            wanted.granted_by(self.grants.requires.get(namespace).and_then(Option::as_ref))
        };
        let Some(scope) = scope else {
            if self.grants.requires.keys().all(|n| granted(n)) {
                return Ok(());
            }
            return Err(ApiError(format!(
                "{called} was called by no package's code, and not every package declares {}",
                wanted.id()
            )));
        };
        let namespace = self
            .scripts
            .namespace(scope)
            .unwrap_or_else(|| self.grants.home.clone());
        if granted(&namespace) {
            return Ok(());
        }
        Err(ApiError(format!(
            "{called} needs {}, which package \"{namespace}\" hasn't declared: add {} to its {}",
            wanted.id(),
            wanted.declaration(),
            netherforge_format::manifest::FILE_NAME
        )))
    }

    fn waiting_task(&self, called: &str) -> ApiResult<u64> {
        self.scripts.waiting_task(called)
    }

    fn wait_on(&self, task: u64, wait: u64) {
        self.scripts.wait_on(task, wait);
    }
}

impl LuaApi for Api {
    fn nf(&self) -> &dyn NfApi {
        &self.nf
    }
    fn subscription(&self) -> &dyn SubscriptionApi {
        &self.scripts
    }
    fn task(&self) -> &dyn TaskApi {
        &self.scripts
    }
    fn nf_centities(&self) -> &dyn NfCentitiesApi {
        match &self.centities {
            Some(c) => c,
            None => &Unported,
        }
    }
    fn centity(&self) -> &dyn CentityApi {
        match &self.centities {
            Some(c) => c,
            None => &Unported,
        }
    }
    fn node(&self) -> &dyn NodeApi {
        match &self.centities {
            Some(c) => c,
            None => &Unported,
        }
    }
    fn nf_spawning(&self) -> &dyn netherforge_api::api::NfSpawningApi {
        match &self.centities {
            Some(c) => c,
            None => &Unported,
        }
    }
    fn nf_images(&self) -> &dyn NfImagesApi {
        match &self.images {
            Some(i) => i,
            None => &Unported,
        }
    }
    fn image(&self) -> &dyn ImageApi {
        match &self.images {
            Some(i) => i,
            None => &Unported,
        }
    }
    fn world(&self) -> &dyn netherforge_api::api::WorldApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn block(&self) -> &dyn netherforge_api::api::BlockApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn entity(&self) -> &dyn netherforge_api::api::EntityApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn droppeditem(&self) -> &dyn netherforge_api::api::DroppedItemApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn living(&self) -> &dyn netherforge_api::api::LivingApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn mob(&self) -> &dyn netherforge_api::api::MobApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn mannequin(&self) -> &dyn netherforge_api::api::MannequinApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn player(&self) -> &dyn netherforge_api::api::PlayerApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_schedule(&self) -> &dyn netherforge_api::api::NfScheduleApi {
        match &self.schedules {
            Some(s) => s,
            None => &Unported,
        }
    }
    fn schedule(&self) -> &dyn netherforge_api::api::ScheduleApi {
        match &self.schedules {
            Some(s) => s,
            None => &Unported,
        }
    }
    fn nf_worlds(&self) -> &dyn netherforge_api::api::NfWorldsApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
}

/// A room: its Luau state and its scripts.
pub struct Room {
    host: Host,
    scripts: Scripts,
}

impl Room {
    /// A room on `host`, whose code may do what `grants` declares.
    pub fn new(host: Host, grants: Grants) -> mlua::Result<Room> {
        Room::with(host, grants, None)
    }

    /// A room whose API reaches what a session shares (its centities), when it's given.
    pub fn with(host: Host, grants: Grants, shared: Option<Rc<Shared>>) -> mlua::Result<Room> {
        let scripts = Scripts::new(host.clone())?;
        let images = shared.as_ref().map(|shared| images::ImagesApi {
            images: shared.images.clone(),
        });
        let worlds = shared
            .as_ref()
            .map(|shared| world::GameContext::new(shared.clone(), scripts.clone()));
        let schedules = shared.as_ref().map(|shared| schedule::ScheduleContext {
            shared: shared.clone(),
            scripts: scripts.clone(),
        });
        let centities = shared.map(|shared| {
            centity::api::CentityContext::install_liveness(&shared, &scripts);
            centity::api::CentityContext::new(shared, scripts.clone())
        });
        let nf = NfContext {
            scripts: scripts.clone(),
            shared: worlds.as_ref().map(|w| w.shared.clone()),
            grants: grants.clone(),
        };
        let api = Api {
            nf,
            scripts: scripts.clone(),
            grants,
            centities,
            images,
            schedules,
            worlds,
        };
        netherforge_api::install(&host, Rc::new(api))?;
        Ok(Room { host, scripts })
    }

    pub fn host(&self) -> &Host {
        &self.host
    }

    pub fn scripts(&self) -> &Scripts {
        &self.scripts
    }
}
