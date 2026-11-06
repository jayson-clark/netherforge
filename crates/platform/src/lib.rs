//! The Platform: what NetherForge's runtime asks of a server (groups of
//! operations, like `TeamOps`), what the server tells it (`PlatformEvents`),
//! and the types they pass. `generated/` is written by `pnpm generate` from
//! `packages/platform` (never edit it); the rest is what it's built from. See
//! the rust-runtime skill, "The Platform boundary".

pub mod chunk_grid;
#[rustfmt::skip]
mod generated;

/// What the Lua API spec says the platform carries: the server's events (`GameEvents`), written by
/// `packages/api`'s `pnpm generate`.
pub mod api {
    #[rustfmt::skip]
    mod game_events;
    #[rustfmt::skip]
    mod command_arguments;

    pub use command_arguments::*;
    pub use game_events::*;
}

pub use api::{ArgumentType, GameEvents, WatchedEvent, game_event};

pub use generated::*;

/// A player's or an entity's UUID.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Uuid(pub u128);

/// What went wrong on the other side: its message (the server's exception's).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlatformError {
    pub message: String,
    pub cause: Option<String>,
}

impl std::fmt::Display for PlatformError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for PlatformError {}

/// A value of any of the JSON-like kinds (`Any` in the spec): a game rule's,
/// a block entity's data.
#[derive(Clone, Debug, PartialEq)]
pub enum Dynamic {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    List(Vec<Dynamic>),
    Map(Vec<(String, Dynamic)>),
}
