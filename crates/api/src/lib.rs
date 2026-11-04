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

pub mod values;

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
