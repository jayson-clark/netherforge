use std::collections::BTreeSet;

use netherforge_format::kind::{self, module};
use netherforge_format::snapshot::scan;

fn problems(paths: &[(&str, &str)]) -> Vec<String> {
    let files: BTreeSet<String> = paths.iter().map(|(p, _)| p.to_string()).collect();
    let read = |p: &str| {
        paths
            .iter()
            .find(|(q, _)| *q == p)
            .map(|(_, t)| t.to_string())
    };
    let source = kind::Source::new(&files, &read);
    scan(&files)
        .resources
        .iter()
        .filter(|f| f.kind.id == "module")
        .flat_map(|f| kind::read(f, &source).unwrap())
        .map(|p| format!("{} {}: {}", p.code.unwrap(), p.file, p.message))
        .collect()
}

#[test]
fn a_modules_files_and_whether_it_runs() {
    assert_eq!(
        problems(&[
            ("modules/ok/init.lua", ""),
            ("modules/odd/init.lua", ""),
            ("modules/odd/my-helper.lua", ""),
            ("modules/library/util.lua", ""),
            ("modules/used/util.lua", ""),
            ("modules/empty/notes.txt", ""),
            ("centities/c/centity.json", "{}"),
            (
                "centities/c/root.lua",
                "local u = require('used.util') -- require('library')"
            ),
        ]),
        [
            "module.empty modules/empty: Module \"empty\" has no .lua files",
            "module.no-init modules/library: Module \"library\" has no init.lua and no script requires it, so it never runs",
            "module.file-name modules/odd/my-helper.lua: Module files must be named with letters, digits and _ so require() can reach them",
        ]
    );
}

#[test]
fn require_names_are_read_outside_comments() {
    assert_eq!(
        module::required_names(
            "local a = require('a.b') local b = require \"c\" -- require('d')\nrequire('mixed\")"
        ),
        ["a.b", "c"]
    );
}
