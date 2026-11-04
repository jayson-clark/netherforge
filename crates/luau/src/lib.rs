//! NetherForge's Luau host: one Luau state per room, a scope per script, and
//! the guards that keep a script from stalling the server or taking its
//! memory. The design is the rust-runtime skill's "The host".

mod clock;
mod failure;
mod host;
mod limits;

pub use clock::{Clock, ManualClock, SystemClock};
pub use failure::{Failure, Location, Stop};
pub use host::{Host, ScopeId, WeakHost};
pub use limits::Limits;
pub use mlua;
