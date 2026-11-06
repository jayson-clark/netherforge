//! NetherForge's Lua API, as the Rust runtime implements it. `generated/` is
//! written by `pnpm generate` from `packages/api` (never edit it): the handle
//! types, the shapes and unions that cross, and one trait per class. The rest is
//! what they're built from. See the rust-runtime skill, "The API crate".

use std::future::Future;
use std::pin::Pin;

use netherforge_luau::ScopeId;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports)]
#[path = "generated/handles.rs"]
pub mod handles;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports)]
#[path = "generated/shapes.rs"]
pub mod shapes;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports)]
#[path = "generated/unions.rs"]
pub mod unions;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports)]
#[path = "generated/api.rs"]
pub mod api;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports, unused_variables)]
#[path = "generated/codecs.rs"]
pub mod codecs;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports, unused_variables)]
#[path = "generated/bindings.rs"]
pub mod bindings;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports, unused_variables)]
#[path = "generated/unported.rs"]
pub mod unported;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports, unused_variables)]
#[path = "generated/arguments.rs"]
pub mod arguments;

#[rustfmt::skip]
#[allow(clippy::all, unused_imports, unused_variables)]
#[path = "generated/raisable.rs"]
pub mod raisable;

pub mod bind;
pub mod codec;
pub mod events;
pub mod handle;
pub mod pending;
pub mod values;

pub use bind::{install, nf_for};
pub use netherforge_luau::json::MAX_SAFE_INTEGER;

/// What a function that waits (`nf.wait`, `dialog:ask`) returns: its task's
/// wake-up is registered. The binding then yields the task's coroutine, and
/// what the scheduler resumes it with is what the script's call returns.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Waiting;

/// What the runtime does around every binding, whatever its class.
pub trait Runtime {
    /// Whether the package `scope` belongs to declared `requirement`
    /// (`requires` in its `netherforge.json`): the one check, before anything
    /// is read or done. `called` names the function (`nf.http.request`).
    fn requires(&self, scope: Option<ScopeId>, requirement: &str, called: &str) -> ApiResult<()>;

    /// The task whose own code is running, for an `async` function `called` without a callback (its waiting
    /// form); an error anywhere else, said before any work starts.
    fn waiting_task(&self, called: &str) -> ApiResult<u64>;

    /// Task `task` waits for async wait `wait`'s value: it's resumed with `value, err` when that's delivered.
    fn wait_on(&self, task: u64, wait: u64);

    /// Whether this is a script test run, which alone has the test-only namespaces (`nf.test`): a server's
    /// scripts find `nil` there.
    fn testing(&self) -> bool {
        false
    }
}

/// What a call into the API did wrong, as the script is told: its message
/// (`bad argument 'radius' (a number from 0 up)`), raised at the script's line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApiError(pub String);

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ApiError {}

pub type ApiResult<T> = Result<T, ApiError>;

/// The script calling a namespace function: its scope, which owns what the call
/// makes (a subscription, a timer, a boss bar).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Caller {
    pub scope: ScopeId,
}

/// An `async` function's value, given later: work on the runtime's pool, whose
/// result reaches the room at its next tick boundary (the script gets it as
/// `value, err`). Game logic never runs on the pool.
pub type Pending<T> = Pin<Box<dyn Future<Output = ApiResult<T>>>>;
