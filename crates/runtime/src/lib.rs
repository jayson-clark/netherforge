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
pub mod block_data;
pub mod block_mining;
pub mod border;
pub mod bossbar;
pub mod bots;
pub mod centity;
pub mod commands;
pub mod custom_blocks;
pub mod cutscenes;
pub mod data;
pub mod definitions;
pub mod dialog;
pub mod effects;
pub mod encoding;
pub mod entity;
pub mod events;
pub mod files;
pub mod http;
pub mod huds;
pub mod images;
pub mod interop;
pub mod inventory;
pub mod items;
pub mod java_time;
pub mod living;
pub mod loot;
pub mod menu;
pub mod modules;
pub mod names;
pub mod network;
pub mod pack_storage;
pub mod packs;
pub mod particles;
pub mod permissions;
pub mod player;
pub mod project_items;
pub mod recipes;
pub mod schedule;
pub mod screen;
pub mod scripts;
pub mod server;
pub mod session;
pub mod settings;
pub mod shared;
pub mod sky;
pub mod stats;
pub mod store;
pub mod team;
pub mod testing;
pub mod utilities;
pub mod wasm;
pub mod web;
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
    /// Whether the package `namespace` (none: no package's code is calling) may do `wanted`, for `called`: a refusal
    /// says what to add, as the Kotlin runtime's does. A call no package makes passes only when every package
    /// declares it.
    pub fn check(
        &self,
        namespace: Option<&str>,
        wanted: &Requirement,
        called: &str,
    ) -> ApiResult<()> {
        let granted = |namespace: &str| {
            wanted.granted_by(self.requires.get(namespace).and_then(Option::as_ref))
        };
        let Some(namespace) = namespace else {
            if self.requires.keys().all(|n| granted(n)) {
                return Ok(());
            }
            return Err(ApiError(format!(
                "{called} was called by no package's code, and not every package declares {}",
                wanted.id()
            )));
        };
        if granted(namespace) {
            return Ok(());
        }
        Err(ApiError(format!(
            "{called} needs {}, which package \"{namespace}\" hasn't declared: add {} to its {}",
            wanted.id(),
            wanted.declaration(),
            netherforge_format::manifest::FILE_NAME
        )))
    }

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
        let Some(shared) = &self.shared else {
            return NfApi::config(&self.scripts, caller, name);
        };
        let namespace = self
            .scripts
            .namespace(caller.scope)
            .unwrap_or_else(|| self.grants.home.clone());
        let value = shared
            .settings
            .borrow_mut()
            .config(caller.scope, &namespace, &name)
            .map_err(ApiError)?;
        Ok(settings::to_lua(self.scripts.host().lua(), &value))
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
    /// `nf.server`, `nf.players`, `Sender`, `nf.math`, `nf.json`, `nf.random`, `nf.time`, `nf.text`.
    utilities: Option<utilities::UtilitiesContext>,
    /// `nf.http`, `HttpStream`, `WebSocket`.
    http: Option<http::api::HttpApi>,
    /// `nf.web`, `Route`, `WebResponse`, `OAuthFlow`.
    web: Option<web::api::WebApi>,
    /// `nf.wasm`, `WasmInstance`, `WasmProcess`.
    wasm: Option<wasm::api::WasmApi>,
    /// `nf.network`, `NetworkChannel`, `NetworkMap`, `NetworkHold`.
    network: Option<network::service::NetworkContext>,
    /// `nf.economy`, `nf.placeholders`.
    interop: Option<interop::InteropContext>,
    /// `nf.test`, in a script test run only.
    test: Option<testing::api::TestContext>,
    /// `nf.bots`, `Bot`.
    bots: Option<bots::BotsContext>,
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
        let namespace = scope.map(|scope| {
            self.scripts
                .namespace(scope)
                .unwrap_or_else(|| self.grants.home.clone())
        });
        self.grants.check(namespace.as_deref(), &wanted, called)
    }

    fn waiting_task(&self, called: &str) -> ApiResult<u64> {
        self.scripts.waiting_task(called)
    }

    fn wait_on(&self, task: u64, wait: u64) {
        self.scripts.wait_on(task, wait);
    }

    fn testing(&self) -> bool {
        self.test.is_some()
    }

    /// An entity's handle as the class it is now (a `Bot`, a `Mob`): Kotlin's `RuntimeApi.refine`.
    fn refine(&self, handle: &handles::Handle) -> Option<handles::Handle> {
        let shared = &self.worlds.as_ref()?.shared;
        if !netherforge_api::handle::is_a(handle.class(), "Entity") {
            return None;
        }
        let Some(netherforge_api::handle::KeyPart::Str(id)) = handle.key().into_iter().next()
        else {
            return None;
        };
        Some(shared.class_of_id(events::parse_uuid(&id)?))
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
    fn nf_server(&self) -> &dyn netherforge_api::api::NfServerApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_players(&self) -> &dyn netherforge_api::api::NfPlayersApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn sender(&self) -> &dyn netherforge_api::api::SenderApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_math(&self) -> &dyn netherforge_api::api::NfMathApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_json(&self) -> &dyn netherforge_api::api::NfJsonApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_random(&self) -> &dyn netherforge_api::api::NfRandomApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn random(&self) -> &dyn netherforge_api::api::RandomApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_time(&self) -> &dyn netherforge_api::api::NfTimeApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_text(&self) -> &dyn netherforge_api::api::NfTextApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_looks(&self) -> &dyn netherforge_api::api::NfLooksApi {
        match &self.utilities {
            Some(u) => u,
            None => &Unported,
        }
    }
    fn nf_items(&self) -> &dyn netherforge_api::api::NfItemsApi {
        match &self.worlds {
            Some(g) => g,
            None => &Unported,
        }
    }
    fn projectitem(&self) -> &dyn netherforge_api::api::ProjectItemApi {
        match &self.worlds {
            Some(g) => g,
            None => &Unported,
        }
    }
    fn nf_commands(&self) -> &dyn netherforge_api::api::NfCommandsApi {
        match &self.worlds {
            Some(g) => g,
            None => &Unported,
        }
    }
    fn nf_encoding(&self) -> &dyn netherforge_api::api::NfEncodingApi {
        &crate::encoding::Encoding
    }
    fn nf_crypto(&self) -> &dyn netherforge_api::api::NfCryptoApi {
        &crate::encoding::Encoding
    }
    fn nf_bossbars(&self) -> &dyn netherforge_api::api::NfBossbarsApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn bossbar(&self) -> &dyn netherforge_api::api::BossBarApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn sidebar(&self) -> &dyn netherforge_api::api::SidebarApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_teams(&self) -> &dyn netherforge_api::api::NfTeamsApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn team(&self) -> &dyn netherforge_api::api::TeamApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn worldborder(&self) -> &dyn netherforge_api::api::WorldBorderApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn inventory(&self) -> &dyn netherforge_api::api::InventoryApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_menus(&self) -> &dyn netherforge_api::api::NfMenusApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn menu(&self) -> &dyn netherforge_api::api::MenuApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn slot(&self) -> &dyn netherforge_api::api::SlotApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn menutemplate(&self) -> &dyn netherforge_api::api::MenuTemplateApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_dialogs(&self) -> &dyn netherforge_api::api::NfDialogsApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn dialog(&self) -> &dyn netherforge_api::api::DialogApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn button(&self) -> &dyn netherforge_api::api::ButtonApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_screens(&self) -> &dyn netherforge_api::api::NfScreensApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn screen(&self) -> &dyn netherforge_api::api::ScreenApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn playback(&self) -> &dyn netherforge_api::api::PlaybackApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn hud(&self) -> &dyn netherforge_api::api::HudApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_packs(&self) -> &dyn netherforge_api::api::NfPacksApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_files(&self) -> &dyn netherforge_api::api::NfFilesApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn file(&self) -> &dyn netherforge_api::api::FileApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_blocks(&self) -> &dyn netherforge_api::api::NfBlocksApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn projectblock(&self) -> &dyn netherforge_api::api::ProjectBlockApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn customblock(&self) -> &dyn netherforge_api::api::CustomBlockApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_recipes(&self) -> &dyn netherforge_api::api::NfRecipesApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_loot(&self) -> &dyn netherforge_api::api::NfLootApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_particles(&self) -> &dyn netherforge_api::api::NfParticlesApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn effect(&self) -> &dyn netherforge_api::api::EffectApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_cutscenes(&self) -> &dyn netherforge_api::api::NfCutscenesApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn cutscene(&self) -> &dyn netherforge_api::api::CutsceneApi {
        match &self.worlds {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_http(&self) -> &dyn netherforge_api::api::NfHttpApi {
        match &self.http {
            Some(h) => h,
            None => &Unported,
        }
    }
    fn httpstream(&self) -> &dyn netherforge_api::api::HttpStreamApi {
        match &self.http {
            Some(h) => h,
            None => &Unported,
        }
    }
    fn websocket(&self) -> &dyn netherforge_api::api::WebSocketApi {
        match &self.http {
            Some(h) => h,
            None => &Unported,
        }
    }
    fn nf_web(&self) -> &dyn netherforge_api::api::NfWebApi {
        match &self.web {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn route(&self) -> &dyn netherforge_api::api::RouteApi {
        match &self.web {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn webresponse(&self) -> &dyn netherforge_api::api::WebResponseApi {
        match &self.web {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn oauthflow(&self) -> &dyn netherforge_api::api::OAuthFlowApi {
        match &self.web {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_wasm(&self) -> &dyn netherforge_api::api::NfWasmApi {
        match &self.wasm {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn wasminstance(&self) -> &dyn netherforge_api::api::WasmInstanceApi {
        match &self.wasm {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn wasmprocess(&self) -> &dyn netherforge_api::api::WasmProcessApi {
        match &self.wasm {
            Some(w) => w,
            None => &Unported,
        }
    }
    fn nf_economy(&self) -> &dyn netherforge_api::api::NfEconomyApi {
        match &self.interop {
            Some(i) => i,
            None => &Unported,
        }
    }
    fn nf_placeholders(&self) -> &dyn netherforge_api::api::NfPlaceholdersApi {
        match &self.interop {
            Some(i) => i,
            None => &Unported,
        }
    }
    fn nf_network(&self) -> &dyn netherforge_api::api::NfNetworkApi {
        match &self.network {
            Some(n) => n,
            None => &Unported,
        }
    }
    fn networkchannel(&self) -> &dyn netherforge_api::api::NetworkChannelApi {
        match &self.network {
            Some(n) => n,
            None => &Unported,
        }
    }
    fn networkmap(&self) -> &dyn netherforge_api::api::NetworkMapApi {
        match &self.network {
            Some(n) => n,
            None => &Unported,
        }
    }
    fn networkhold(&self) -> &dyn netherforge_api::api::NetworkHoldApi {
        match &self.network {
            Some(n) => n,
            None => &Unported,
        }
    }
    fn nf_test(&self) -> &dyn netherforge_api::api::NfTestApi {
        match &self.test {
            Some(t) => t,
            None => &Unported,
        }
    }
    fn nf_bots(&self) -> &dyn netherforge_api::api::NfBotsApi {
        match &self.bots {
            Some(b) => b,
            None => &Unported,
        }
    }
    fn bot(&self) -> &dyn netherforge_api::api::BotApi {
        match &self.bots {
            Some(b) => b,
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
        let worlds = shared
            .as_ref()
            .map(|shared| world::GameContext::new(shared.clone(), scripts.clone()));
        let http = worlds.as_ref().map(|game| {
            http::api::HttpApi::install_liveness(&game.shared, &scripts);
            http::api::HttpApi {
                game: game.clone(),
                grants: grants.clone(),
            }
        });
        let web = worlds.as_ref().map(|game| {
            web::WebContext::install_liveness(&game.shared, &scripts);
            web::api::WebApi {
                game: game.clone(),
                grants: grants.clone(),
            }
        });
        let wasm = worlds.as_ref().map(|game| {
            wasm::api::WasmApi::install_liveness(&game.shared, &scripts);
            wasm::api::WasmApi { game: game.clone() }
        });
        let network = worlds
            .as_ref()
            .map(|game| network::service::NetworkContext {
                shared: game.shared.clone(),
                scripts: scripts.clone(),
            });
        let interop = worlds.as_ref().map(|game| interop::InteropContext {
            shared: game.shared.clone(),
            scripts: scripts.clone(),
        });
        let bots = worlds.as_ref().map(|game| bots::BotsContext {
            shared: game.shared.clone(),
            scripts: scripts.clone(),
        });
        let test = worlds
            .as_ref()
            .filter(|game| game.shared.tests.borrow().harness.is_some())
            .map(|game| testing::api::TestContext {
                game: game.clone(),
                statuses: Default::default(),
            });
        let images = shared.as_ref().map(|shared| images::ImagesApi {
            images: shared.images.clone(),
            http: http.clone(),
        });
        let schedules = shared.as_ref().map(|shared| schedule::ScheduleContext {
            shared: shared.clone(),
            scripts: scripts.clone(),
        });
        let utilities = shared.as_ref().map(|shared| utilities::UtilitiesContext {
            shared: shared.clone(),
            scripts: scripts.clone(),
            randoms: Default::default(),
        });
        let centities = shared.map(|shared| {
            centity::api::CentityContext::install_liveness(&shared, &scripts);
            menu::MenuContext::install_liveness(&shared, &scripts);
            dialog::DialogContext::install_liveness(&shared, &scripts);
            particles::ParticlesContext::install_liveness(&shared, &scripts);
            cutscenes::CutscenesContext::install_liveness(&shared, &scripts);
            custom_blocks::BlocksContext::install_liveness(&shared, &scripts);
            screen::ScreensContext::install_liveness(&shared, &scripts);
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
            utilities,
            worlds,
            http,
            web,
            wasm,
            network,
            interop,
            test,
            bots,
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
