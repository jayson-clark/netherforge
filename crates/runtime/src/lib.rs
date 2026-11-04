//! NetherForge's runtime in Rust (phase 1 of ludicraft's ROADMAP.md): what a
//! room runs. So far its scripts: scopes, events, timers and tasks. See the
//! rust-runtime skill.

use std::collections::HashSet;
use std::rc::Rc;

use netherforge_api::api::{LuaApi, NfApi, SubscriptionApi, TaskApi};
use netherforge_api::{ApiError, ApiResult, Runtime};
use netherforge_luau::{Host, ScopeId};

pub mod modules;
pub mod scripts;

pub use modules::{Modules, Owner, Project};
pub use scripts::{Delivered, Log, MAX_ERRORS, Report, Scripts, Target};

/// The API as the runtime implements it so far, every other class unported.
struct Api {
    scripts: Scripts,
    /// What the room's packages declared (`requires`). Per package once
    /// packages are ported; until then the room's.
    granted: HashSet<String>,
}

impl Runtime for Api {
    fn requires(&self, _: Option<ScopeId>, requirement: &str, called: &str) -> ApiResult<()> {
        if self.granted.contains(requirement) {
            return Ok(());
        }
        Err(ApiError(format!(
            "{called} needs \"{requirement}\" in the package's requires (netherforge.json)"
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
    /// A room on `host`, whose packages may use what `granted` names.
    pub fn new(host: Host, granted: impl IntoIterator<Item = String>) -> mlua::Result<Room> {
        let scripts = Scripts::new(host.clone())?;
        let api = Api {
            scripts: scripts.clone(),
            granted: granted.into_iter().collect(),
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
