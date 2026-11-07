//! Which Minecraft version each gated feature arrived in: format's
//! `FeatureTable`. The table is `docs/format/features.md`, which Kotlin's
//! `FeatureTable.CURRENT` writes (its golden test) and `build.rs` reads, so
//! there's one. When the Kotlin side goes, the table moves here and the page is
//! written from it.

use crate::names::MinecraftVersion;
use crate::problem::{ProblemSink, codes};

/// A game feature NetherForge uses that arrived after the oldest supported
/// version: `id` is how checks and the Lua API's spec (`since`) name it, `what`
/// says it in a problem's words.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Feature {
    pub id: &'static str,
    pub since: &'static str,
    pub what: &'static str,
}

include!(concat!(env!("OUT_DIR"), "/features.rs"));

/// A table of features: the real one (`CURRENT`), or a test's own.
#[derive(Clone, Copy, Debug)]
pub struct FeatureTable<'a>(pub &'a [Feature]);

/// The table NetherForge goes by.
pub const CURRENT: FeatureTable<'static> = FeatureTable(ALL);

impl<'a> FeatureTable<'a> {
    /// The feature `id`, which must be in the table: an unknown id is a bug in NetherForge, not in a project.
    pub fn get(&self, id: &str) -> &'a Feature {
        self.0
            .iter()
            .find(|f| f.id == id)
            .unwrap_or_else(|| panic!("No feature \"{id}\" in the feature table"))
    }

    /// Whether a project targeting `target` can use feature `id`. A target that isn't a version isn't held against anything.
    pub fn has(&self, target: Option<&str>, id: &str) -> bool {
        let Some(version) = target.and_then(MinecraftVersion::parse) else {
            return true;
        };
        let since =
            MinecraftVersion::parse(self.get(id).since).expect("a feature's version is one");
        version >= since
    }

    /// Reports using feature `id` at `path` when `target` lacks it: what a check calls where a file uses it.
    pub fn require(
        &self,
        target: Option<&str>,
        id: &str,
        sink: &mut ProblemSink,
        path: Option<String>,
    ) {
        if self.has(target, id) {
            return;
        }
        let feature = self.get(id);
        sink.report(
            codes::PROJECT_FEATURE,
            format!(
                "{} needs Minecraft {}; this project targets {}",
                feature.what,
                feature.since,
                target.unwrap_or_default()
            ),
            path,
        );
    }
}
