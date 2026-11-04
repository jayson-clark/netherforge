//! What a NetherForge project file means, in Rust: `packages/format`'s port
//! (see the rust-runtime skill, "Format"). One format: the same bytes on
//! every side, so a file nobody changed is never a diff.

pub mod canonical;
pub mod json;
pub mod kinds;
pub mod manifest;
pub mod names;
pub mod problem;
pub mod read;
pub mod settings;
pub mod snapshot;

pub use problem::{Location, Problem, ProblemCode, ProblemSink, Severity, codes};
