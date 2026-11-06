//! What a reload answers (Kotlin's `ReloadBatch` and its results): each resource it touched as the bridge names it,
//! `{ package, kind, id }`, whether it took the new version, what kept running on it, and its problems; and whether
//! the server must restart for the change (`StartupCheck`, Kotlin's `StartupDatapackCheck`).
//!
//! A service reloads a kind's ids together, so what a resource's reload kept running (`reattached`) is told on the
//! side, through `Shared::reattached`, and the session works the rest out itself: a resource is `ok` when its files
//! have no error and no script failed while it reloaded. A failure is the resource's whose scope failed (a module,
//! a resource's script), else the one whose file it was in.

use std::collections::{BTreeMap, HashMap};

use netherforge_format::Problem;
use netherforge_format::bridge::{PackBuild, ReloadedResource};
use netherforge_format::kinds::{self, PathRole};

/// A resource as the runtime names it: its kind and its id (`acme:coin` for a package's).
pub type Key = (&'static str, String);

/// What services say of a reload as it runs, read once it's done.
#[derive(Default)]
pub struct Notes {
    /// What kept running on each resource's new version, by resource.
    pub reattached: HashMap<Key, i32>,
    /// The resource pack built, when a pack reloaded.
    pub pack: Option<PackBuild>,
}

/// The resource `path` (a project or package path) belongs to, as the runtime names it: none for files no resource
/// owns (the manifest, a README, anything in `.netherforge/`) and for an id that can't be one.
pub fn resource_of(path: &str) -> Option<Key> {
    let found = kinds::classify(path)?;
    let kind = found.kind?;
    let id = found.id.filter(|id| netherforge_format::names::is_id(id))?;
    Some(match found.package {
        Some(namespace) => (kind, kinds::package_path(&namespace, &id)),
        None => (kind, id),
    })
}

/// Whether `path` is `key`'s main file (its `centity.json`, `item.json`...), wherever its group folder is.
pub fn is_main_of(path: &str, key: &Key) -> bool {
    resource_of(path).as_ref() == Some(key)
        && kinds::classify(path).is_some_and(|f| f.role == PathRole::Main)
}

/// The project documents a path is that a reload handles without naming a resource: `netherforge.json` and the lock
/// (the whole package, any package's), `fonts/default.json` and `client/vanilla.json` (what services follow).
pub fn document_of(path: &str) -> Option<&'static str> {
    kinds::classify(path).and_then(|f| f.document.filter(|_| f.kind.is_none()))
}

/// The kinds whose resources go in the start-up datapack: a change to one may need a restart.
pub const STARTUP_KINDS: &[&str] = &[
    "advancement",
    "biome",
    "datapack",
    "dialog",
    "dimension_type",
    "sky",
    "structure",
    "terrain",
];

/// A script failure while a reload ran: the resource whose scope failed (when it's one), and the problem.
pub struct Failed {
    pub owner: Option<Key>,
    pub problem: Problem,
}

/// Each resource's outcome, in the order they reloaded: `order`'s resources, given the project's problems as they read
/// now, which of their main files exist, and the failures while they reloaded.
pub fn outcomes(
    order: &[Key],
    home: &str,
    problems: &[Problem],
    files: &std::collections::BTreeSet<String>,
    failed: Vec<Failed>,
    notes: &Notes,
    log: &dyn netherforge_platform::PlatformLog,
) -> Vec<ReloadedResource> {
    let mut failures: BTreeMap<usize, Vec<Problem>> = BTreeMap::new();
    for failure in failed {
        let by_file = resource_of(&failure.problem.file);
        let at = failure
            .owner
            .as_ref()
            .and_then(|o| order.iter().position(|k| k == o))
            .or_else(|| by_file.and_then(|f| order.iter().position(|k| *k == f)))
            .unwrap_or(0);
        let kept = failures.entry(at).or_default();
        if !kept.contains(&failure.problem) {
            kept.push(failure.problem);
        }
    }
    order
        .iter()
        .enumerate()
        .map(|(at, key)| {
            let mine: Vec<Problem> = problems
                .iter()
                .filter(|p| resource_of(&p.file).as_ref() == Some(key))
                .cloned()
                .collect();
            let errors = mine
                .iter()
                .any(|p| p.severity == netherforge_format::Severity::Error);
            let exists = files.iter().any(|f| is_main_of(f, key));
            if errors && exists {
                log.warn(format!(
                    "{}:{} has errors; it keeps running the last good version",
                    key.0, key.1
                ));
            }
            let failed = failures.remove(&at).unwrap_or_default();
            let (pkg, id) = match kinds::split_package(&key.1) {
                (Some(namespace), id) => (namespace.to_owned(), id.to_owned()),
                (None, id) => (home.to_owned(), id.to_owned()),
            };
            ReloadedResource {
                pkg,
                kind: Some(key.0.to_owned()),
                id: Some(id),
                ok: !errors && failed.is_empty(),
                reattached: notes.reattached.get(key).copied().unwrap_or(0),
                problems: mine.into_iter().chain(failed).collect(),
                pack: if key.0 == "resource_pack" {
                    notes.pack.clone()
                } else {
                    None
                },
            }
        })
        .collect()
}

/// Whether the server must restart to run the project as its files say (Kotlin's `StartupDatapackCheck`): the
/// start-up datapack built again from the files and compared with what the server loaded as it started. It lives as
/// long as the session does (a whole reload keeps it), since what the server started with does.
#[derive(Default)]
pub struct StartupCheck {
    /// Whether the last check found the server out of date.
    pub stale: bool,
}

/// What the `runtime.restart` warning says.
pub fn restart_message() -> String {
    let folders: Vec<&str> = STARTUP_KINDS
        .iter()
        .filter_map(|k| kinds::ALL.iter().find(|s| s.id == *k).map(|s| s.folder))
        .collect();
    let mut text = folders.join(", ");
    if let Some(first) = text.get(..1) {
        text = first.to_uppercase() + &text[1..];
    }
    format!(
        "{text} changed since the server started: it runs the ones it started with until it restarts"
    )
}

struct ServerText<'p>(&'p dyn netherforge_platform::Platform);

impl netherforge_format::startup::TextJson for ServerText<'_> {
    fn json(&self, mini: &str, glyph: &dyn Fn(&str) -> Option<String>) -> serde_json::Value {
        // The Platform's callback must own what it answers, so the glyphs the text names are looked up first.
        let mut glyphs: HashMap<String, Option<String>> = HashMap::new();
        for tag in mini.split("<glyph:").skip(1) {
            if let Some(reference) = tag.split('>').next() {
                glyphs.insert(reference.to_owned(), glyph(reference));
            }
        }
        let text = self.0.datapacks().text_json(
            mini.to_owned(),
            Box::new(move |reference| glyphs.get(&reference).cloned().flatten()),
        );
        serde_json::from_str(&text).unwrap_or(serde_json::Value::String(text))
    }
}

impl StartupCheck {
    /// Builds the datapack from `project` as it reads and answers whether it differs from what the server started
    /// with; a change is said once (a warning: the server runs what it started with until it restarts). A server that
    /// doesn't say its data pack format is never out of date.
    pub fn check(
        &mut self,
        project: &netherforge_format::project::Project,
        source: &dyn netherforge_format::project::ProjectSource,
        platform: &dyn netherforge_platform::Platform,
    ) -> bool {
        let datapacks = platform.datapacks();
        let Some(format) = datapacks.format().filter(|f| f.len() == 2) else {
            self.stale = false;
            return false;
        };
        let main = datapacks.main_world();
        let entries = netherforge_format::startup::build(
            project,
            [format[0], format[1]],
            &ServerText(platform),
            main.as_deref(),
            true,
            &netherforge_format::startup::GameDatapack::default(),
            &|_| false,
        );
        let now: HashMap<String, Vec<u8>> = entries
            .into_iter()
            .filter_map(|(path, entry)| match entry {
                netherforge_format::startup::Entry::Text(text) => Some((path, text.into_bytes())),
                netherforge_format::startup::Entry::Copy(from) => {
                    source.bytes(&from).map(|bytes| (path, bytes))
                }
            })
            .collect();
        let was = self.stale;
        self.stale = now != datapacks.started();
        if self.stale && !was {
            platform.log().warn(restart_message());
        }
        self.stale
    }

    /// `runtime.restart` while the server is out of date.
    pub fn problems(&self) -> Vec<Problem> {
        if !self.stale {
            return Vec::new();
        }
        vec![netherforge_format::codes::RUNTIME_RESTART.at(
            netherforge_format::manifest::FILE_NAME,
            restart_message(),
            None,
        )]
    }
}

/// The resource a scope's owner is: a module, or the resource whose script it runs.
pub fn owned_by(owner: &crate::modules::Owner) -> Option<Key> {
    match owner {
        crate::modules::Owner::Module(id) => Some(("module", id.clone())),
        crate::modules::Owner::Script(file) => resource_of(file),
    }
}
