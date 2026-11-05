//! NetherForge's runtime in Rust (phase 1 of the cost roadmap, ../ROADMAP.md): what a
//! room runs. So far its scripts: scopes, events, timers and tasks. See the
//! rust-runtime skill.

use std::collections::BTreeMap;
use std::rc::Rc;

use netherforge_api::api::{
    CentityApi, ImageApi, LuaApi, NfApi, NfCentitiesApi, NfImagesApi, NodeApi, SubscriptionApi,
    TaskApi,
};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Runtime};
use netherforge_format::manifest::Requires;
use netherforge_format::requirement::Requirement;
use netherforge_luau::{Host, ScopeId};

pub mod centity;
pub mod events;
pub mod images;
pub mod items;
pub mod modules;
pub mod scripts;
pub mod server;
pub mod session;
pub mod shared;
pub mod stats;
pub mod store;

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

/// The API as the runtime implements it so far, every other class unported.
struct Api {
    scripts: Scripts,
    grants: Grants,
    /// The centity classes, when the room runs a session's centities.
    centities: Option<centity::api::CentityContext>,
    /// `nf.images` and `Image`, with them.
    images: Option<images::ImagesApi>,
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
        &self.scripts
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
        let centities = shared.map(|shared| {
            centity::api::CentityContext::install_liveness(&shared, &scripts);
            centity::api::CentityContext::new(shared, scripts.clone())
        });
        let api = Api {
            scripts: scripts.clone(),
            grants,
            centities,
            images,
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
