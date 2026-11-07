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
    /// What the server refused as it started, as problems: worked out once, as it doesn't change until a restart.
    refusal: Option<Vec<Problem>>,
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
    /// doesn't say its data pack format is never out of date. What the server started with is the whole datapack,
    /// unless the refusal on record is about exactly these files (Kotlin's `StartupDatapackFiles.forStart`).
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
        let format = [format[0], format[1]];
        let main = datapacks.main_world();
        let game = datapacks.game();
        let build =
            |pass_through: bool,
             leave_out: &dyn Fn(&netherforge_format::reference::ResourceKey) -> bool| {
                netherforge_format::startup::build(
                    project,
                    format,
                    &ServerText(platform),
                    main.as_deref(),
                    pass_through,
                    &game,
                    leave_out,
                )
                .into_iter()
                .filter_map(|(path, entry)| match entry {
                    netherforge_format::startup::Entry::Text(text) => {
                        Some((path, text.into_bytes()))
                    }
                    netherforge_format::startup::Entry::Copy(from) => {
                        source.bytes(&from).map(|bytes| (path, bytes))
                    }
                })
                .collect::<HashMap<String, Vec<u8>>>()
            };
        let whole = build(true, &|_| false);
        let refusal = datapacks.refused();
        if self.refusal.is_none() {
            self.refusal = Some(
                refusal
                    .as_ref()
                    .map(|r| refusal_problems(r, project, format))
                    .unwrap_or_default(),
            );
        }
        let now = match refusal.filter(|r| r.hash == hash_of(&whole)) {
            Some(refused) => {
                let (pass_through, skies) = leaves_out(&refused, project.namespace());
                build(pass_through, &|sky| {
                    skies.as_ref().is_none_or(|s| s.contains(sky))
                })
            }
            None => whole,
        };
        let was = self.stale;
        self.stale = now != datapacks.started();
        if self.stale && !was {
            platform.log().warn(restart_message());
        }
        self.stale
    }

    /// `runtime.datapack` while the server runs without what it refused, and `runtime.restart` while it's out of date.
    pub fn problems(&self) -> Vec<Problem> {
        let mut problems = self.refusal.clone().unwrap_or_default();
        if self.stale {
            problems.push(netherforge_format::codes::RUNTIME_RESTART.at(
                netherforge_format::manifest::FILE_NAME,
                restart_message(),
                None,
            ));
        }
        problems
    }
}

/// What a start-up datapack is known by (Kotlin's `DatapackRefusal.hashOf`): a SHA-256 of its files, by path, in path
/// order, each its path, a zero, its length in decimal, a zero and its bytes.
pub fn hash_of(files: &HashMap<String, Vec<u8>>) -> String {
    use sha2::Digest;
    let mut digest = sha2::Sha256::new();
    let mut paths: Vec<&String> = files.keys().collect();
    paths.sort();
    for path in paths {
        let bytes = &files[path];
        digest.update(path.as_bytes());
        digest.update([0]);
        digest.update(bytes.len().to_string().as_bytes());
        digest.update([0]);
        digest.update(bytes);
    }
    digest
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

const BIOME_REGISTRY: &str = "minecraft:worldgen/biome";

const REFUSED: &str = "The server refused the project's datapacks as it started, so it runs without them until they change";

/// The sky `element` of `registry` is a twin of, if it's one.
fn sky_of(
    home: &str,
    registry: &str,
    element: &str,
) -> Option<netherforge_format::reference::ResourceKey> {
    if registry != BIOME_REGISTRY {
        return None;
    }
    let (namespace, path) = element.split_once(':')?;
    let key = netherforge_format::reference::ResourceKey {
        namespace: namespace.to_owned(),
        path: path.to_owned(),
    };
    netherforge_format::startup::sky::sky_of(home, &key)
}

/// Each entry the refusal's report names: its registry, the entry, and the first line of what's wrong with it.
pub fn refusal_entries(
    refusal: &netherforge_format::startup::DatapackRefusal,
) -> Vec<(String, String, String)> {
    let registry_line =
        regex::Regex::new(r"^\s*> Errors in registry (\S+):\s*$").expect("a pattern");
    let element_line =
        regex::Regex::new(r"^\s*>> Errors in element (\S+):\s*$").expect("a pattern");
    let exception =
        regex::Regex::new(r"^\s*([\w$]+\.)*[\w$]+(Exception|Error): ").expect("a pattern");
    let lines: Vec<&str> = refusal.report.lines().collect();
    let mut registry: Option<String> = None;
    let mut out = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        if let Some(c) = registry_line.captures(line) {
            registry = Some(c[1].to_owned());
        }
        let Some(element) = element_line.captures(line).map(|c| c[1].to_owned()) else {
            continue;
        };
        let cause = lines[index + 1..]
            .iter()
            .find(|l| !l.trim().is_empty() && !l.trim_start().starts_with("at "))
            .filter(|l| !registry_line.is_match(l) && !element_line.is_match(l))
            .map(|l| exception.replace(l, "").trim().to_owned())
            .unwrap_or_default();
        out.push((
            registry.clone().unwrap_or_else(|| "?".to_owned()),
            element,
            if cause.is_empty() {
                "the game couldn't read it".to_owned()
            } else {
                cause
            },
        ));
    }
    out
}

/// What a start with the refused files leaves out (Kotlin's `DatapackRefusal.leavesOut`): whether the project's
/// datapacks still go in, and the skies left out (none: every one, when the report names nothing it can tell).
pub fn leaves_out(
    refusal: &netherforge_format::startup::DatapackRefusal,
    home: &str,
) -> (
    bool,
    Option<std::collections::HashSet<netherforge_format::reference::ResourceKey>>,
) {
    let found = refusal_entries(refusal);
    if found.is_empty() {
        return (false, None);
    }
    let skies = found
        .iter()
        .filter_map(|(r, e, _)| sky_of(home, r, e))
        .collect();
    let all_skies = found.iter().all(|(r, e, _)| sky_of(home, r, e).is_some());
    (all_skies, Some(skies))
}

/// The refusal as problems (Kotlin's `DatapackRefusal.problems`): one per sky whose twins it named, one per other entry
/// it names, at the file that wrote it; the whole report at `netherforge.json` when it names none.
pub fn refusal_problems(
    refusal: &netherforge_format::startup::DatapackRefusal,
    project: &netherforge_format::project::Project,
    format: [i32; 2],
) -> Vec<Problem> {
    use netherforge_format::codes::RUNTIME_DATAPACK;
    use netherforge_format::manifest::FILE_NAME;
    let home = project.namespace();
    let found = refusal_entries(refusal);
    let mut skies: Vec<(netherforge_format::reference::ResourceKey, Vec<String>)> = Vec::new();
    for (registry, element, message) in &found {
        if let Some(sky) = sky_of(home, registry, element) {
            match skies.iter_mut().find(|(s, _)| *s == sky) {
                Some((_, messages)) => messages.push(message.clone()),
                None => skies.push((sky, vec![message.clone()])),
            }
        }
    }
    let mut problems: Vec<Problem> = skies
        .iter()
        .map(|(sky, messages)| {
            let plains = netherforge_format::reference::ResourceKey {
                namespace: "minecraft".into(),
                path: "plains".into(),
            };
            let twin = netherforge_format::startup::sky::key_of(home, sky, 1, &plains).to_string();
            let file = netherforge_format::startup::source_of(project, format, BIOME_REGISTRY, &twin)
                .unwrap_or_else(|| FILE_NAME.to_owned());
            RUNTIME_DATAPACK.at(
                &file,
                format!(
                    "The server refused sky {sky}'s twin biomes as it started ({} of them: {}), so it runs without that sky until it changes",
                    messages.len(),
                    messages[0]
                ),
                None,
            )
        })
        .collect();
    let rest: Vec<_> = found
        .iter()
        .filter(|(r, e, _)| sky_of(home, r, e).is_none())
        .collect();
    if rest.is_empty() && !skies.is_empty() {
        return problems;
    }
    if rest.is_empty() {
        let head: Vec<&str> = refusal.report.lines().take(4).collect();
        return vec![RUNTIME_DATAPACK.at(
            FILE_NAME,
            format!("{REFUSED}: {}", head.join(" ").trim()),
            None,
        )];
    }
    for (registry, element, message) in rest {
        let file = netherforge_format::startup::source_of(project, format, registry, element)
            .unwrap_or_else(|| FILE_NAME.to_owned());
        problems.push(RUNTIME_DATAPACK.at(
            &file,
            format!("{REFUSED}: {element} in {registry}: {message}"),
            None,
        ));
    }
    problems
}

/// The resource a scope's owner is: a module, or the resource whose script it runs.
pub fn owned_by(owner: &crate::modules::Owner) -> Option<Key> {
    match owner {
        crate::modules::Owner::Module(id) => Some(("module", id.clone())),
        crate::modules::Owner::Script(file) => resource_of(file),
    }
}

#[cfg(test)]
mod refusal_tests {
    use super::*;

    /// The game's report of the placed feature it couldn't read, as 26.3 logs it (Kotlin's `DatapackTest`).
    const REPORT: &str = "> Errors in registry minecraft:worldgen/placed_feature:
>> Errors in element test:rocks:
java.lang.IllegalStateException: Failed to parse test:rocks from pack file/netherforge: No key placement in MapLike[{\"feature\":\"test:rock\"}]
\tat net.minecraft.resources.RegistryDataLoader.loadElementFromResource(RegistryDataLoader.java:301)
> Errors in registry minecraft:worldgen/noise:
>> Errors in element minecraft:gone:
java.lang.IllegalStateException: Missing referenced element";

    #[test]
    fn a_refusal_names_its_entries_and_its_hash_is_the_files_whatever_their_order() {
        let refusal = netherforge_format::startup::DatapackRefusal {
            hash: "abc".into(),
            report: REPORT.into(),
        };
        let entries = refusal_entries(&refusal);
        assert_eq!(
            entries
                .iter()
                .map(|(r, e, _)| (r.as_str(), e.as_str()))
                .collect::<Vec<_>>(),
            [
                ("minecraft:worldgen/placed_feature", "test:rocks"),
                ("minecraft:worldgen/noise", "minecraft:gone")
            ]
        );
        assert_eq!(entries[1].2, "Missing referenced element");
        // Not only skies: the project's datapacks are left out too, and no sky is.
        let (pass_through, skies) = leaves_out(&refusal, "test");
        assert!(!pass_through);
        assert!(skies.is_some_and(|s| s.is_empty()));
        // Kotlin's DatapackRefusal.hashOf of the same files.
        let files: HashMap<String, Vec<u8>> = [("b".into(), vec![2]), ("a".into(), vec![1])].into();
        assert_eq!(
            hash_of(&files),
            "83692627e731b998944aa8de3d5b28b61d9afbbe4a84ad2b72872c55a0607618"
        );
    }
}
