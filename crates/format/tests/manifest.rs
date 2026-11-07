use std::path::{Path, PathBuf};

use netherforge_format::manifest::{FILE_NAME, Manifest};
use netherforge_format::read::parse;

fn manifests(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            if !path.file_name().unwrap().to_string_lossy().starts_with('.') {
                manifests(&path, out);
            }
        } else if path.file_name().unwrap() == FILE_NAME {
            out.push(path);
        }
    }
}

/// Every manifest the examples and the goldens hold reads, and a valid one
/// writes back to the same bytes.
#[test]
fn every_manifest_reads_and_writes_back() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let mut files = Vec::new();
    manifests(&root.join("examples"), &mut files);
    let examples = files.len();
    manifests(&root.join("packages/format/testdata"), &mut files);
    assert!(
        examples >= 5 && files.len() > examples,
        "{examples} and {}",
        files.len()
    );
    let mut different = Vec::new();
    for (k, file) in files.iter().enumerate() {
        let text = std::fs::read_to_string(file).unwrap();
        match parse::<Manifest>(&text, FILE_NAME) {
            Ok(manifest) => {
                if k < examples && manifest.write() != text {
                    different.push(format!("{}:\n{}", file.display(), manifest.write()));
                }
            }
            // A golden may hold a manifest that's wrong on purpose.
            Err(problem) if k >= examples => {
                let _ = problem;
            }
            Err(problem) => panic!("{}: {:?}", file.display(), problem),
        }
    }
    assert!(different.is_empty(), "{different:#?}");
}

#[test]
fn the_canonical_form_sorts_sets_and_drops_empty_worlds() {
    let text = r#"{"formatVersion":1,"name":"X","namespace":"x","version":"1.0.0","minecraft":"26.1",
        "managedWorlds":["b","a","b"],"worlds":{"empty":{},"arena":{"seed":5}},
        "requires":{"http":["z.com","a.com"]},"settings":{"10":{"type":"boolean","description":"d","default":true},"2":{"type":"integer","description":"d","default":3}}}"#;
    let written = parse::<Manifest>(text, FILE_NAME).unwrap().write();
    assert_eq!(
        written,
        r#"{
  "$schema": ".netherforge/schema/netherforge.schema.json",
  "formatVersion": 1,
  "name": "X",
  "namespace": "x",
  "version": "1.0.0",
  "minecraft": "26.1",
  "managedWorlds": ["a", "b"],
  "worlds": {
    "arena": {
      "seed": 5
    }
  },
  "requires": {
    "http": ["a.com", "z.com"]
  },
  "settings": {
    "2": {
      "type": "integer",
      "description": "d",
      "default": 3
    },
    "10": {
      "type": "boolean",
      "description": "d",
      "default": true
    }
  }
}
"#
    );
}
