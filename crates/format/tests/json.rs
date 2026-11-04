use std::path::{Path, PathBuf};

use netherforge_format::json::{child_path, format_number, print, quote};

#[test]
fn numbers_print_the_same_as_formats() {
    // CanonicalJsonTest's cases.
    for (value, expected) in [
        (1.0, "1"),
        (-2.0, "-2"),
        (0.5, "0.5"),
        (0.1, "0.1"),
        (123.456, "123.456"),
        (0.00001, "0.00001"),
        (1e-7, "1e-7"),
        (1e22, "1e+22"),
        (1e21, "1e+21"),
        (1e20, "100000000000000000000"),
        (0.000001, "0.000001"),
        (100.0, "100"),
        (-0.25, "-0.25"),
        (-0.0, "0"),
        (1.5e-7, "1.5e-7"),
        (0.1 + 0.2, "0.30000000000000004"),
    ] {
        assert_eq!(format_number(value), expected, "{value}");
    }
}

#[test]
fn strings_and_paths() {
    assert_eq!(quote("a\"b\\c\n\u{1}✓"), "\"a\\\"b\\\\c\\n\\u0001✓\"");
    assert_eq!(child_path("$.nodes", "root"), "$.nodes.root");
    assert_eq!(child_path("$.nodes", "a b"), "$.nodes[\"a b\"]");
}

fn json_files(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        if path.is_dir() {
            if !name.starts_with('.') && name != "node_modules" {
                json_files(&path, out);
            }
        } else if name.ends_with(".json")
            // Not format's writing: LuaLS's settings (project-support writes them), and a
            // resource pack's own Minecraft models, which pass through as they are.
            && name != ".luarc.json"
            && !path.components().any(|c| c.as_os_str() == "models")
        {
            out.push(path);
        }
    }
}

/// Every project file the examples hold is format's writing: read and
/// printed again, it's the same bytes.
#[test]
fn every_examples_file_prints_back_to_itself() {
    let examples = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../examples");
    let mut files = Vec::new();
    json_files(&examples, &mut files);
    assert!(files.len() > 50, "{} files", files.len());
    let mut different = Vec::new();
    for file in &files {
        let text = std::fs::read_to_string(file).unwrap();
        let value: serde_json::Value = serde_json::from_str(&text).unwrap();
        if format!("{}\n", print(&value)) != text {
            different.push(file.strip_prefix(&examples).unwrap().display().to_string());
        }
    }
    assert!(
        different.is_empty(),
        "{} of {} differ: {different:#?}",
        different.len(),
        files.len()
    );
}
