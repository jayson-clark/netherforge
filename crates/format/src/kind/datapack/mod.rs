//! `datapacks/<id>/`: a datapack of the game's own, for experts: worldgen
//! JSON written by hand, passed through into the start-up datapack. Format's
//! `PackMeta`, `DatapackKind`, `CompiledDatapack` and `DatapackValidator.validate`:
//! `pack.mcmeta`'s formats and overlays, that every file is a worldgen entry
//! or tag where the game looks for one and reads as JSON, and what the game's
//! registries say of it. Its namespaces, collisions and references to the
//! project are checks across resources.

pub mod layout;
pub mod references;

use std::collections::BTreeSet;
use std::fmt;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::document::{Context, Document};
use crate::game::{self, GameData};
use crate::problem::{Problem, ProblemSink, codes};
use layout::DatapackPath;

/// The file that makes a folder of `datapacks/` a datapack.
pub const FILE_NAME: &str = "pack.mcmeta";
const FORMAT_RULE: &str = "min_format and max_format are each a data pack format (a number, or [major, minor]), the first no later than the second";

/// `pack.mcmeta`, in the game's spelling.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PackMeta {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    pub pack: PackSection,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub overlays: Option<PackOverlays>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PackSection {
    /// What the game's pack list says about it: a text component, kept as written.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<Value>,
    /// The oldest data pack format its files are for: a number, or `[major, minor]`.
    pub min_format: Value,
    /// The newest: a number (any minor of it), or `[major, minor]`.
    pub max_format: Value,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PackOverlays {
    #[serde(default)]
    pub entries: Vec<PackOverlay>,
}

/// A folder beside `data/` whose files replace the pack's on a server of the formats it's for.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PackOverlay {
    pub min_format: Value,
    pub max_format: Value,
    pub directory: String,
}

/// A data pack format, `major.minor`, as the game compares them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct PackFormat {
    pub major: i32,
    pub minor: i32,
}

impl fmt::Display for PackFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.minor == i32::MAX {
            write!(f, "{}", self.major)
        } else {
            write!(f, "{}.{}", self.major, self.minor)
        }
    }
}

impl PackFormat {
    pub fn of([major, minor]: [i32; 2]) -> PackFormat {
        PackFormat { major, minor }
    }

    /// `value` as the lower (`upper` false) or upper end of a range: a whole
    /// number is that major format, from its first minor or to its last;
    /// `[major]` the same; `[major, minor]` exactly that.
    pub fn read(value: &Value, upper: bool) -> Option<PackFormat> {
        let parts: Vec<&Value> = match value {
            Value::Array(items) => items.iter().collect(),
            other => vec![other],
        };
        if parts.is_empty() || parts.len() > 2 {
            return None;
        }
        let numbers: Option<Vec<i32>> = parts
            .iter()
            .map(|p| {
                p.as_i64()
                    .filter(|n| *n >= 0)
                    .and_then(|n| i32::try_from(n).ok())
            })
            .collect();
        let numbers = numbers?;
        let minor = numbers
            .get(1)
            .copied()
            .unwrap_or(if upper { i32::MAX } else { 0 });
        Some(PackFormat {
            major: numbers[0],
            minor,
        })
    }

    /// The formats from `min` to `max`, or `None` when either isn't one or they're the wrong way round.
    pub fn range(min: &Value, max: &Value) -> Option<(PackFormat, PackFormat)> {
        let (from, to) = (Self::read(min, false)?, Self::read(max, true)?);
        (from <= to).then_some((from, to))
    }
}

fn within(format: PackFormat, (from, to): (PackFormat, PackFormat)) -> bool {
    from <= format && format <= to
}

/// A datapack as the start-up datapack takes it: its formats, overlays in order, and files.
#[derive(Clone, Debug, PartialEq)]
pub struct CompiledDatapack {
    pub formats: (PackFormat, PackFormat),
    pub overlays: Vec<(String, (PackFormat, PackFormat))>,
    pub files: Vec<DatapackPath>,
}

impl CompiledDatapack {
    /// `meta` with `files` (paths in its folder); `None` when its formats aren't readable.
    pub fn of<'a>(
        meta: &PackMeta,
        files: impl IntoIterator<Item = &'a String>,
    ) -> Option<CompiledDatapack> {
        let formats = PackFormat::range(&meta.pack.min_format, &meta.pack.max_format)?;
        let overlays = meta
            .overlays
            .iter()
            .flat_map(|o| &o.entries)
            .map(|o| {
                PackFormat::range(&o.min_format, &o.max_format).map(|f| (o.directory.clone(), f))
            })
            .collect::<Option<Vec<_>>>()?;
        let mut files = layout::entries(meta, files);
        files.sort_by(|a, b| a.file.cmp(&b.file));
        Some(CompiledDatapack {
            formats,
            overlays,
            files,
        })
    }

    /// The files a server reading `format` gets, by where they go in a datapack without overlays.
    pub fn files_for(&self, format: PackFormat) -> Vec<(String, &DatapackPath)> {
        let mut out: Vec<(String, &DatapackPath)> = Vec::new();
        if !within(format, self.formats) {
            return out;
        }
        fn put<'a>(path: &'a DatapackPath, out: &mut Vec<(String, &'a DatapackPath)>) {
            let target = path.target();
            match out.iter_mut().find(|(t, _)| *t == target) {
                Some(slot) => slot.1 = path,
                None => out.push((target, path)),
            }
        }
        for path in self.files.iter().filter(|p| p.overlay.is_none()) {
            put(path, &mut out);
        }
        for (directory, formats) in &self.overlays {
            if within(format, *formats) {
                for path in self
                    .files
                    .iter()
                    .filter(|p| p.overlay.as_deref() == Some(directory))
                {
                    put(path, &mut out);
                }
            }
        }
        out
    }
}

/// The game's name for the registry whose datapack folder is `registry`: `minecraft:worldgen/biome`.
pub fn registry_key(registry: &str) -> String {
    format!("{}:{registry}", game::NAMESPACE)
}

/// A registry folder in a message's words: `worldgen/placed_feature` is a placed feature.
fn noun(registry: &str) -> String {
    registry
        .rsplit('/')
        .next()
        .unwrap_or(registry)
        .replace('_', " ")
}

/// An entry or tag in a message: `placed feature "showcase:rocks"`, `biome tag "#showcase:wet"`.
pub fn named(registry: &str, key: &str, tag: bool) -> String {
    if tag {
        format!("{} tag \"#{key}\"", noun(registry))
    } else {
        format!("{} \"{key}\"", noun(registry))
    }
}

/// A reference as the game reads one (bare is the game's): whether it's a tag, and its key; `None` when it isn't an id.
pub fn key_of(text: &str) -> Option<(bool, String)> {
    let tag = text.starts_with('#');
    let id = text.trim_start_matches('#');
    game::is_id(id).then(|| (tag, game::normalize(id)))
}

/// The references in one file: a tag's values, or what the rules find; `None` for a tag that isn't one.
pub fn references_in(path: &DatapackPath, json: &Value) -> Option<Vec<references::Found>> {
    if path.tag {
        references::tag_values(&path.registry, json)
    } else {
        Some(references::find(&path.registry, json))
    }
}

/// One file's `json` against what `game` knows: its registry, a `minecraft` file's replacing something, and the game's ids it names.
fn against_game(
    path: &DatapackPath,
    json: &Value,
    game: Option<&dyn GameData>,
    at: &str,
    problems: &mut Vec<Problem>,
) {
    let Some(found) = references_in(path, json) else {
        problems.push(codes::DATAPACK_TAG.at(at, "A tag is { \"values\": [...] } (and \"replace\": true or false), each an id, a #tag or { \"id\": … }", None));
        return;
    };
    for reference in &found {
        if key_of(&reference.text).is_none() {
            problems.push(codes::DATAPACK_REFERENCE.at(
                at,
                format!(
                    "\"{}\" isn't an id, like \"{}:…\"",
                    reference.text,
                    game::NAMESPACE
                ),
                Some(reference.path.clone()),
            ));
        }
    }
    let Some(game) = game else { return };
    let version = game.minecraft_version();
    let Some(entries) = game.registry(&registry_key(&path.registry)) else {
        problems.push(codes::DATAPACK_REGISTRY.at(
            at,
            format!(
                "Minecraft {version} has no registry {}, so the game ignores this file",
                path.registry
            ),
            None,
        ));
        return;
    };
    if path.namespace == game::NAMESPACE {
        let replaces = if path.tag {
            game.tag(&registry_key(&path.registry), &path.key())
                .is_some()
        } else {
            entries.contains(&path.key())
        };
        if !replaces {
            problems.push(codes::DATAPACK_OVERRIDE.at(
                at,
                format!("Minecraft {version} has no {} to replace: put new ones in the project's namespace", named(&path.registry, &path.key(), path.tag)),
                None,
            ));
        }
    }
    for reference in &found {
        let Some((tag, key)) = key_of(&reference.text) else {
            continue;
        };
        if !key.starts_with(&format!("{}:", game::NAMESPACE)) {
            continue;
        }
        let Some(registry) = reference
            .targets
            .iter()
            .find(|t| game.registry(&registry_key(t)).is_some())
        else {
            continue;
        };
        let registry_key = registry_key(registry);
        let has = if tag {
            game.tag(&registry_key, &key).is_some()
        } else {
            game.registry(&registry_key)
                .is_some_and(|r| r.contains(&key))
        };
        if !has {
            problems.push(codes::DATAPACK_REFERENCE.at(
                at,
                format!("Minecraft {version} has no {}", named(registry, &key, tag)),
                Some(reference.path.clone()),
            ));
        }
    }
}

impl Document for PackMeta {
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../../.netherforge/schema/datapack.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let range = PackFormat::range(&self.pack.min_format, &self.pack.max_format);
        let target = cx
            .game
            .and_then(|g| g.data_pack_format())
            .map(PackFormat::of);
        match range {
            None => sink.report(codes::DATAPACK_FORMAT, FORMAT_RULE, Some("$.pack".into())),
            Some((from, to)) => {
                if let Some(target) = target
                    && !within(target, (from, to))
                {
                    let version = cx.game.map(|g| g.minecraft_version()).unwrap_or_default();
                    sink.report(
                        codes::DATAPACK_VERSION,
                        format!(
                            "This datapack is written for data pack formats {from} to {to}, and Minecraft {version} reads {target}: \
                             check its files against that version's worldgen and change min_format or max_format (or add an overlay for it)"
                        ),
                        Some("$.pack".into()),
                    );
                }
            }
        }
        let mut seen = BTreeSet::new();
        for (index, overlay) in self.overlays.iter().flat_map(|o| &o.entries).enumerate() {
            let at = format!("$.overlays.entries[{index}]");
            if PackFormat::range(&overlay.min_format, &overlay.max_format).is_none() {
                sink.report(codes::DATAPACK_FORMAT, FORMAT_RULE, Some(at.clone()));
            }
            let directory = &overlay.directory;
            if !layout::is_name(directory) || directory == layout::DATA {
                sink.report(
                    codes::DATAPACK_OVERLAY,
                    format!("\"{directory}\" isn't an overlay's folder: lowercase letters, digits, _, - and ., and not \"{}\"", layout::DATA),
                    Some(format!("{at}.directory")),
                );
            } else if !seen.insert(directory.as_str()) {
                sink.report(
                    codes::DATAPACK_OVERLAY,
                    format!("\"{directory}\" is listed twice"),
                    Some(format!("{at}.directory")),
                );
            }
        }
        let Some(files) = cx.files else { return };
        let overlays = layout::overlays_of(self);
        // The game's data is the target version's: it's asked only about the files a server of that version reads
        // (the pack's own and its overlays for that format; with no format to go by, the pack's own).
        let compiled = CompiledDatapack::of(self, files);
        let read: BTreeSet<String> = match (target, &compiled) {
            (Some(target), Some(compiled)) => compiled
                .files_for(target)
                .into_iter()
                .map(|(_, p)| p.file.clone())
                .collect(),
            _ => compiled
                .iter()
                .flat_map(|c| &c.files)
                .filter(|p| p.overlay.is_none())
                .map(|p| p.file.clone())
                .collect(),
        };
        for file in files.iter().filter(|f| f.as_str() != FILE_NAME) {
            let at = format!("{}/{file}", cx.location);
            let path = match layout::place(file, &overlays) {
                Err(message) => {
                    sink.problems
                        .push(codes::DATAPACK_FILE.at(&at, message, None));
                    continue;
                }
                Ok(path) => path,
            };
            let Some(text) = cx.read.and_then(|read| read(file)) else {
                continue;
            };
            let json = match crate::read::parse::<Value>(&text, &at) {
                Err(problem) => {
                    sink.problems.push(*problem);
                    continue;
                }
                Ok(json) => json,
            };
            let game = cx.game.filter(|_| read.contains(file));
            against_game(&path, &json, game, &at, &mut sink.problems);
        }
    }
}

impl PackMeta {
    /// Its files against the project: namespaces, collisions, and references to the project's own.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let home = cx.home();
        let own = cx.datapack_entries();
        let datapacks: Vec<(&str, Vec<DatapackPath>)> = cx
            .read("datapack")
            .filter_map(|r| {
                let meta = r.model::<PackMeta>()?;
                Some((r.found.id.as_str(), layout::entries(meta, &r.found.inside)))
            })
            .collect();
        let defined: BTreeSet<(String, String, bool)> = datapacks
            .iter()
            .flat_map(|(_, paths)| paths.iter())
            .filter(|p| p.namespace == home)
            .map(|p| (p.registry.clone(), p.path.clone(), p.tag))
            .chain(own.keys().map(|(r, p)| (r.to_string(), p.clone(), false)))
            .collect();
        // Every file another datapack writes, by where it goes: the first (by id) to write one keeps it.
        let mut earlier: std::collections::BTreeMap<String, (&str, &DatapackPath)> =
            Default::default();
        for (id, paths) in datapacks.iter().filter(|(id, _)| *id < cx.id()) {
            for path in paths {
                earlier.entry(path.target()).or_insert((id, path));
            }
        }
        let location = cx.location_of("datapack", cx.id());
        let mine = datapacks
            .iter()
            .find(|(id, _)| *id == cx.id())
            .map(|(_, p)| p);
        for path in mine.into_iter().flatten() {
            let at = format!("{location}/{}", path.file);
            if path.namespace == home {
                if !crate::reference::is_resource_path(&path.path) {
                    sink.problems.push(codes::DATAPACK_FILE.at(
                        &at,
                        format!("\"{}\" isn't an id the project's references can name: lowercase letters, digits and _, in folders", path.path),
                        None,
                    ));
                    continue;
                }
            } else if path.namespace != game::NAMESPACE || cx.in_package() {
                let may = if cx.in_package() {
                    format!("its own namespace, \"{home}\"")
                } else {
                    format!(
                        "the project's namespace, \"{home}\", or \"{}\"",
                        game::NAMESPACE
                    )
                };
                sink.problems.push(codes::DATAPACK_NAMESPACE.at(
                    &at,
                    format!(
                        "A datapack here writes only {may}, not \"{}\"",
                        path.namespace
                    ),
                    None,
                ));
                continue;
            }
            if path.namespace == home
                && !path.tag
                && let Some((kind, id)) = own
                    .iter()
                    .find(|((r, p), _)| *r == path.registry && *p == path.path)
                    .map(|(_, v)| *v)
            {
                sink.problems.push(
                    codes::DATAPACK_CONFLICT
                        .at(
                            &at,
                            format!(
                                "The {} \"{id}\" is \"{}\" in {} too: rename one of them",
                                kind.replace('_', " "),
                                path.key(),
                                path.registry
                            ),
                            None,
                        )
                        .with_related(vec![crate::Location {
                            file: cx.path_of(kind, id),
                            path: None,
                        }]),
                );
            }
            if let Some((owner, other)) = earlier.get(&path.target()) {
                sink.problems.push(
                    codes::DATAPACK_CONFLICT
                        .at(
                            &at,
                            format!(
                                "Datapack \"{owner}\" writes {} too: keep it in one of them",
                                path.target()
                            ),
                            None,
                        )
                        .with_related(vec![crate::Location {
                            file: format!("{}/{}", cx.location_of("datapack", owner), other.file),
                            path: None,
                        }]),
                );
            }
            let Some(json) = cx
                .read_file(&at)
                .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            else {
                continue;
            };
            let Some(found) = references_in(path, &json) else {
                continue;
            };
            for reference in found {
                let Some((tag, key)) = key_of(&reference.text) else {
                    continue;
                };
                let (namespace, key_path) = key.split_once(':').expect("a normalized key");
                if namespace == game::NAMESPACE {
                    continue;
                }
                if namespace == home {
                    if reference
                        .targets
                        .iter()
                        .all(|t| !defined.contains(&(t.to_string(), key_path.to_owned(), tag)))
                    {
                        // Said in the target version's words when its game data says which name the registry has there.
                        let registry = reference
                            .targets
                            .iter()
                            .find(|t| {
                                cx.game()
                                    .is_some_and(|g| g.registry(&registry_key(t)).is_some())
                            })
                            .or(reference.targets.first())
                            .expect("a reference has a registry");
                        sink.problems.push(codes::DATAPACK_REFERENCE.at(
                            &at,
                            format!(
                                "The project has no {}: a datapack's {} would be it",
                                named(registry, &key, tag),
                                layout::entry_file(namespace, key_path, registry, tag)
                            ),
                            Some(reference.path.clone()),
                        ));
                    }
                } else if !cx.references().packages().any(|p| p == namespace) {
                    sink.problems.push(codes::DATAPACK_REFERENCE.at(
                        &at,
                        format!(
                            "\"{namespace}\" is neither this project's namespace (\"{home}\"), the game's, nor a package's it depends on"
                        ),
                        Some(reference.path.clone()),
                    ));
                }
            }
        }
    }
}
