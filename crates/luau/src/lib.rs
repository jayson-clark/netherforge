//! NetherForge's Luau host: one Luau state per room, a scope per script, and
//! the guards that keep a script from stalling the server or taking its
//! memory. The design is the rust-runtime skill's "The host".

mod clock;
mod failure;
mod host;
pub mod json;
mod limits;
pub mod random;
mod timing;

pub use clock::{Clock, ManualClock, SystemClock};
pub use failure::{Failure, Location, Stop};
pub use host::{Host, ScopeId, WeakHost, message_of, project_location as script_location};
pub use limits::Limits;
pub use mlua;
pub use timing::{FunctionCost, ScopeCost};
