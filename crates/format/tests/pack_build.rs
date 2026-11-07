//! Every file of each example's built resource pack is format's: the one
//! players get as they join, and each pack sent on demand on its own, for
//! resource pack format [75, 0], as `packages/format/testdata/packs/<example>.json`
//! (written by Kotlin's `PackBuildGoldenTest`) has them.

use std::path::{Path, PathBuf};

use netherforge_format::kind::resource_pack::build::{self, Entry, VideoSound};
use netherforge_format::kind::resource_pack::compiled::CompiledResourcePack;
use netherforge_format::manifest::FILE_NAME;
use netherforge_format::project;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn written(files: &std::collections::BTreeMap<String, Entry>) -> Value {
    Value::Object(
        files
            .iter()
            .map(|(path, entry)| {
                let text = match entry {
                    Entry::Text(text) => text.clone(),
                    Entry::Copy(from) => format!("copy of {from}"),
                    Entry::Bytes(bytes) => format!("bytes {:x}", Sha256::digest(bytes)),
                };
                (path.clone(), json!(text))
            })
            .collect(),
    )
}

#[test]
fn the_examples_packs_build_as_the_goldens_say() {
    let mut compared = 0;
    for entry in std::fs::read_dir(root().join("examples")).unwrap() {
        let dir = entry.unwrap().path();
        if !dir.join(FILE_NAME).exists() {
            continue;
        }
        let name = dir.file_name().unwrap().to_string_lossy().to_string();
        let source = project::Folder::open(&dir).unwrap();
        let packages = netherforge_format::package::Folders { root: dir.clone() };
        let loaded = project::load(
            &source,
            project::Options {
                packages: &packages,
                ..Default::default()
            },
        );
        let golden = root().join(format!("packages/format/testdata/packs/{name}.json"));
        if !build::needed(&loaded) {
            assert!(
                !golden.exists(),
                "{name} builds no pack, but there's a golden"
            );
            continue;
        }
        let expected: Value =
            serde_json::from_str(&std::fs::read_to_string(&golden).unwrap()).unwrap();
        let description = loaded
            .manifest
            .as_ref()
            .map(|m| m.name.clone())
            .filter(|n| !n.trim().is_empty())
            .unwrap_or("NetherForge".into());
        let (separate, joined): (Vec<&CompiledResourcePack>, Vec<&CompiledResourcePack>) = loaded
            .compiled_resource_packs
            .values()
            .partition(|p| p.on_demand);
        let mut actual = serde_json::Map::new();
        let videos = VideoSound::of(&loaded);
        actual.insert(
            "joined".into(),
            written(&build::build(
                &joined,
                [75, 0],
                &description,
                None,
                loaded.vanilla.as_ref(),
                &videos,
                &|_| None,
            )),
        );
        for pack in separate {
            let own = pack
                .description
                .clone()
                .or(pack.name.clone())
                .unwrap_or(pack.key.clone());
            actual.insert(
                pack.key.clone(),
                written(&build::build(
                    &[pack],
                    [75, 0],
                    &own,
                    None,
                    None,
                    &[],
                    &|_| None,
                )),
            );
        }
        for (build, files) in expected.as_object().unwrap() {
            let got = actual
                .get(build)
                .unwrap_or_else(|| panic!("{name}: no build {build}"));
            for (path, text) in files.as_object().unwrap() {
                compared += 1;
                match got.get(path) {
                    None => panic!("{name} {build}: {path} is missing"),
                    Some(other) if other != text => panic!(
                        "{name} {build}: {path}\n--- want\n{}\n--- got\n{}",
                        text.as_str().unwrap(),
                        other.as_str().unwrap()
                    ),
                    _ => {}
                }
            }
            let extra: Vec<&String> = got
                .as_object()
                .unwrap()
                .keys()
                .filter(|p| !files.as_object().unwrap().contains_key(*p))
                .collect();
            assert!(
                extra.is_empty(),
                "{name} {build}: not in the golden: {extra:?}"
            );
        }
    }
    assert!(compared > 0);
}

// ---- the blocks' plan, which needs the game's blocks ------------------------------------------------------

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::game::{self, GameData};
use netherforge_format::kind::block::BlockFile;
use netherforge_format::kind::carriers;

/// A game whose note block has two instruments, two notes and power.
struct NoteBlocks;

impl GameData for NoteBlocks {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    fn particle(&self, _: &str) -> Option<game::ParticleData> {
        None
    }
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>> {
        let list = |v: &[&str]| v.iter().map(|s| s.to_string()).collect();
        (id == carriers::NOTE_BLOCK).then(|| {
            BTreeMap::from([
                ("instrument".to_owned(), list(&["harp", "basedrum"])),
                ("note".to_owned(), list(&["0", "1"])),
                ("powered".to_owned(), list(&["false", "true"])),
            ])
        })
    }
}

#[test]
fn a_blocks_state_is_drawn_by_its_look_and_every_other_as_the_games() {
    let manifest = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;
    let files: BTreeMap<&str, &str> = BTreeMap::from([
        ("netherforge.json", manifest),
        (
            "resource_packs/ui/pack.json",
            r#"{ "blocks": { "ruby_ore": { "texture": "block/ruby_ore.png" } } }"#,
        ),
        ("resource_packs/ui/textures/block/ruby_ore.png", ""),
        (
            "blocks/ruby_ore/block.json",
            r#"{ "model": "ui/ruby_ore" }"#,
        ),
        ("blocks/lantern/block.json", r#"{ "centity": "lamp" }"#),
        (
            "centities/lamp/centity.json",
            r#"{ "nodes": { "root": {} } }"#,
        ),
    ]);
    let names: BTreeSet<String> = files.keys().map(|k| k.to_string()).collect();
    let read = |p: &str| files.get(p).map(|t| t.to_string());
    let loaded = project::load(
        &project::Files {
            files: &names,
            read: &read,
        },
        project::Options {
            game: Some(&NoteBlocks),
            ..Default::default()
        },
    );
    let blocks: BTreeMap<String, Option<&BlockFile>> = loaded
        .running::<BlockFile>()
        .into_iter()
        .map(|(n, b)| (n, Some(b)))
        .collect();
    assert_eq!(blocks.len(), 2, "{:?}", loaded.problems);
    let plan = carriers::plan(
        &blocks,
        "test",
        Some(&NoteBlocks),
        &BTreeMap::new(),
        &BTreeMap::new(),
    )
    .expect("a plan");
    // In name order: the lantern first, then the ruby ore, each in the rarest state left (a powered basedrum).
    let held: Vec<(String, String)> = plan
        .uses
        .iter()
        .map(|u| (u.name.clone(), u.state.text()))
        .collect();
    assert_eq!(
        held,
        [
            (
                "lantern".to_owned(),
                "minecraft:note_block[instrument=basedrum,note=0,powered=true]".to_owned()
            ),
            (
                "ruby_ore".to_owned(),
                "minecraft:note_block[instrument=basedrum,note=1,powered=true]".to_owned()
            ),
        ]
    );
    let packs: Vec<&CompiledResourcePack> = loaded.compiled_resource_packs.values().collect();
    let files = build::build(&packs, [75, 0], "Test", Some(&plan), None, &[], &|_| None);
    let Entry::Text(states) = &files["assets/minecraft/blockstates/note_block.json"] else {
        panic!("a blockstate file");
    };
    let states: Value = serde_json::from_str(states).unwrap();
    let variants = states["variants"].as_object().unwrap();
    assert_eq!(variants.len(), 8, "every state of the note block");
    assert_eq!(
        variants["instrument=basedrum,note=1,powered=true"]["model"],
        "test:block/ui/ruby_ore"
    );
    // A block drawn by a centity with no look of its own shows nothing.
    assert_eq!(
        variants["instrument=basedrum,note=0,powered=true"]["model"],
        "test:block/hidden"
    );
    assert_eq!(
        variants["instrument=harp,note=0,powered=false"]["model"],
        carriers::VANILLA_MODEL
    );
    assert!(files.contains_key("assets/test/models/block/hidden.json"));
}
