//! The start-up datapack is format's: every file of the showcase's, for a
//! server of data pack format [94, 1], is `testdata/datapack/showcase.json`
//! (written by Kotlin's `AdvancementTest`), and the biomes', dimension
//! types' and structures' goldens across formats.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use netherforge_format::project::{self, Project};
use netherforge_format::startup::{self, Entry, GameDatapack, TextJson};
use serde_json::{Value, json};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// The test's text: MiniMessage as it is, a glyph drawn as its code point (`[U+e000]`) or nothing.
struct Mini;

impl TextJson for Mini {
    fn json(&self, mini: &str, glyph: &dyn Fn(&str) -> Option<String>) -> Value {
        let tags = regex::Regex::new("<glyph:([^>]+)>").unwrap();
        let drawn = tags.replace_all(mini, |c: &regex::Captures| {
            glyph(&c[1]).map_or_else(String::new, |g| {
                format!("[U+{:x}]", g.chars().next().unwrap() as u32)
            })
        });
        json!({ "mini": drawn })
    }
}

fn showcase() -> Project<'static> {
    let dir = root().join("examples/showcase");
    let source = Box::leak(Box::new(project::Folder::open(&dir).unwrap()));
    let packages = Box::leak(Box::new(netherforge_format::package::Folders { root: dir }));
    project::load(
        source,
        project::Options {
            packages,
            ..Default::default()
        },
    )
}

/// The pack as the goldens write it: each file's text, a copied file as "copy of <path>".
fn written(pack: &BTreeMap<String, Entry>) -> Value {
    Value::Object(
        pack.iter()
            .map(|(path, entry)| {
                let text = match entry {
                    Entry::Text(text) => text.clone(),
                    Entry::Copy(from) => format!("copy of {from}"),
                };
                (path.clone(), json!(text))
            })
            .collect(),
    )
}

fn golden(name: &str) -> Value {
    let path = root().join("packages/format/testdata/datapack").join(name);
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// Where two packs' files first differ.
fn difference(want: &Value, got: &Value) -> Option<String> {
    let (want, got) = (want.as_object().unwrap(), got.as_object().unwrap());
    for (path, text) in want {
        match got.get(path) {
            None => return Some(format!("{path}: missing")),
            Some(other) if other != text => {
                return Some(format!(
                    "{path}:\n--- want\n{}\n--- got\n{}",
                    text.as_str().unwrap(),
                    other.as_str().unwrap()
                ));
            }
            _ => {}
        }
    }
    got.keys()
        .find(|p| !want.contains_key(*p))
        .map(|p| format!("{p}: not in the golden"))
}

#[test]
fn the_showcases_datapack_is_the_games_format() {
    let project = showcase();
    let none = GameDatapack::default();
    let pack = startup::build(&project, [94, 1], &Mini, None, true, &none, &|_| false);
    assert_eq!(
        pack,
        startup::build(&showcase(), [94, 1], &Mini, None, true, &none, &|_| false),
        "built twice, the same"
    );
    if let Some(at) = difference(&golden("showcase.json"), &written(&pack)) {
        panic!("{at}");
    }
    assert!(pack.contains_key("data/library/advancement/gem_collector.json"));
}

#[test]
fn a_project_with_nothing_for_it_has_no_datapack() {
    let files = std::collections::BTreeSet::from(["netherforge.json".to_owned()]);
    let manifest = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;
    let read = |_: &str| Some(manifest.to_owned());
    let project = project::load(
        &project::Files {
            files: &files,
            read: &read,
        },
        Default::default(),
    );
    assert!(
        startup::build(
            &project,
            [94, 1],
            &Mini,
            None,
            true,
            &GameDatapack::default(),
            &|_| false
        )
        .is_empty()
    );
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;

/// A project in memory: its manifest, and `files` (a `None` is a file whose bytes format never reads).
fn load(files: &[(&str, Option<String>)]) -> Project<'static> {
    let mut all: BTreeMap<String, Option<String>> = files
        .iter()
        .map(|(p, t)| (p.to_string(), t.clone()))
        .collect();
    all.insert("netherforge.json".into(), Some(MANIFEST.to_owned()));
    let names: &'static std::collections::BTreeSet<String> =
        Box::leak(Box::new(all.keys().cloned().collect()));
    let all: &'static BTreeMap<String, Option<String>> = Box::leak(Box::new(all));
    let read: &'static dyn Fn(&str) -> Option<String> =
        Box::leak(Box::new(move |p: &str| all.get(p).cloned().flatten()));
    let source = Box::leak(Box::new(project::Files { files: names, read }));
    project::load(source, Default::default())
}

fn fixture(name: &str) -> Option<String> {
    Some(
        std::fs::read_to_string(root().join("packages/format/testdata/datapack").join(name))
            .unwrap(),
    )
}

/// The text of the test's: nothing at all.
struct Blank;

impl TextJson for Blank {
    fn json(&self, _: &str, _: &dyn Fn(&str) -> Option<String>) -> Value {
        json!({})
    }
}

/// `project`'s pack for each of `formats`, its files `keep` says yes to, by format (`94.1`).
fn by_format(project: &Project, formats: &[[i32; 2]], keep: &dyn Fn(&str) -> bool) -> Value {
    let none = GameDatapack::default();
    let mut out = serde_json::Map::new();
    for format in formats {
        let pack = startup::build(project, *format, &Blank, None, true, &none, &|_| false);
        let kept: BTreeMap<String, Entry> = pack.into_iter().filter(|(p, _)| keep(p)).collect();
        out.insert(format!("{}.{}", format[0], format[1]), written(&kept));
    }
    Value::Object(out)
}

#[test]
fn a_biome_is_the_games_format_for_the_servers_version() {
    let project = load(&[
        ("biomes/grove.json", fixture("grove.json")),
        ("biomes/bare.json", Some("{}".into())),
    ]);
    let actual = by_format(&project, &[[94, 1], [121, 0]], &|p| {
        p.ends_with(".json") && p.contains("/worldgen/biome/")
    });
    let expected = golden("biomes.json");
    for (format, files) in expected.as_object().unwrap() {
        if let Some(at) = difference(files, &actual[format]) {
            panic!("{format}: {at}");
        }
    }
}

#[test]
fn a_dimension_type_is_the_games_format_for_the_servers_version() {
    let project = load(&[
        ("dimension_types/deep.json", fixture("deep.json")),
        ("dimension_types/plain.json", Some("{}".into())),
    ]);
    let actual = by_format(&project, &[[94, 1], [101, 1], [121, 0]], &|p| {
        p.contains("/dimension_type/")
    });
    let expected = golden("dimensions.json");
    for (format, files) in expected.as_object().unwrap() {
        if let Some(at) = difference(files, &actual[format]) {
            panic!("{format}: {at}");
        }
    }
}

#[test]
fn structures_that_generate_are_the_games_format() {
    let project = load(&[
        ("structures/village.nbt", None),
        ("structures/village.json", fixture("village.json")),
        ("structures/house.nbt", None),
        ("structures/hut.nbt", None),
        ("structures/road.nbt", None),
        ("structures/shrine.nbt", None),
        ("structures/shrine.json", fixture("shrine.json")),
    ]);
    let pack = startup::build(
        &project,
        [94, 1],
        &Mini,
        None,
        true,
        &GameDatapack::default(),
        &|_| false,
    );
    if let Some(at) = difference(&golden("structures.json"), &written(&pack)) {
        panic!("{at}");
    }
}
