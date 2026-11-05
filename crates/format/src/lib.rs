//! What a NetherForge project file means, in Rust: `packages/format`'s port
//! (see the rust-runtime skill, "Format"). One format: the same bytes on
//! every side, so a file nobody changed is never a diff.

pub mod animation;
pub mod block_state;
pub mod canonical;
pub mod document;
pub mod features;
pub mod font;
pub mod game;
pub mod image;
pub mod item;
pub mod json;
pub mod kind;
pub mod kinds;
pub mod lua;
pub mod manifest;
pub mod map_colors;
pub mod math;
pub mod names;
pub mod noise;
pub mod package;
pub mod png;
pub mod policy;
pub mod problem;
pub mod project;
pub mod random;
pub mod read;
pub mod reference;
pub mod requirement;
pub mod resolve;
pub mod rules;
pub mod schema;
pub mod script;
pub mod settings;
pub mod snapshot;
pub mod startup;
pub mod text;
pub mod vanilla;
pub mod worldgen;

pub use problem::{Location, Problem, ProblemCode, ProblemSink, Severity, codes};
