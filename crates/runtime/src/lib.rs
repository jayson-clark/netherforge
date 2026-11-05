//! NetherForge's runtime in Rust (phase 1 of the cost roadmap, ../ROADMAP.md): what a
//! room runs. So far its scripts: scopes, events, timers and tasks. See the
//! rust-runtime skill.

use std::rc::Rc;

use netherforge_api::api::{LuaApi, NfApi, SubscriptionApi, TaskApi};
use netherforge_api::{ApiError, ApiResult, Runtime};
use netherforge_format::manifest::Requires;
use netherforge_format::requirement::Requirement;
use netherforge_luau::{Host, ScopeId};

pub mod modules;
pub mod scripts;
pub mod session;

pub use modules::{Modules, Owner, Project};
pub use scripts::{Delivered, Log, MAX_ERRORS, Report, Scripts, Target};

/// What a room's code has declared it may do (`requires` in its `netherforge.json`): the project's own until
/// packages' code is ported, then each package's (Kotlin's `Requirements`, with the policy).
#[derive(Clone, Debug, Default)]
pub struct Grants {
    /// The package whose declaration it is, as refusals name it.
    pub namespace: String,
    pub requires: Option<Requires>,
}

/// The API as the runtime implements it so far, every other class unported.
struct Api {
    scripts: Scripts,
    grants: Grants,
}

impl Runtime for Api {
    /// Format's `Requirement` decides, as the spec's `requires` names it (`db`, `http`, `plugin:vault`), and a
    /// refusal says what to add, as the Kotlin runtime's does.
    fn requires(&self, _: Option<ScopeId>, requirement: &str, called: &str) -> ApiResult<()> {
        let Some(wanted) = Requirement::parse(requirement) else {
            return Err(ApiError(format!(
                "{called} needs \"{requirement}\", which isn't a requirement"
            )));
        };
        if wanted.granted_by(self.grants.requires.as_ref()) {
            return Ok(());
        }
        Err(ApiError(format!(
            "{called} needs {}, which package \"{}\" hasn't declared: add {} to its {}",
            wanted.id(),
            self.grants.namespace,
            wanted.declaration(),
            netherforge_format::manifest::FILE_NAME
        )))
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
}

/// A room: its Luau state and its scripts.
pub struct Room {
    host: Host,
    scripts: Scripts,
}

impl Room {
    /// A room on `host`, whose code may do what `grants` declares.
    pub fn new(host: Host, grants: Grants) -> mlua::Result<Room> {
        let scripts = Scripts::new(host.clone())?;
        let api = Api {
            scripts: scripts.clone(),
            grants,
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
