//! What a NetherForge project file means, in Rust: `packages/format`'s port
//! (see the rust-runtime skill, "Format"). One format: the same bytes on
//! every side, so a file nobody changed is never a diff.

pub mod animation;
pub mod block_state;
pub mod canonical;
pub mod document;
pub mod game;
pub mod item;
pub mod json;
pub mod kind;
pub mod kinds;
pub mod manifest;
pub mod math;
pub mod names;
pub mod noise;
pub mod problem;
pub mod read;
pub mod rules;
pub mod script;
pub mod settings;
pub mod snapshot;
pub mod worldgen;

pub use problem::{Location, Problem, ProblemCode, ProblemSink, Severity, codes};
