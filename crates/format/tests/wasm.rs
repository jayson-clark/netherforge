//! Format's `WasmTest`: `wasm/<id>/`, a module and its data, checked by what its host read of it.

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::project::{self, ProjectSource};
use netherforge_format::wasm::{
    self, WasmExport, WasmExtern, WasmImport, WasmInfo, WasmMemory, read,
};

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;

/// A project's files, and the modules its host read (none: a host that reads none).
struct Source {
    files: BTreeSet<String>,
    wasm: Option<BTreeMap<String, WasmInfo>>,
}

impl ProjectSource for Source {
    fn files(&self) -> &BTreeSet<String> {
        &self.files
    }
    fn read(&self, path: &str) -> Option<String> {
        (path == "netherforge.json").then(|| MANIFEST.to_string())
    }
    fn reads_wasm(&self) -> bool {
        self.wasm.is_some()
    }
    fn wasm(&self, path: &str) -> Option<WasmInfo> {
        self.wasm.as_ref()?.get(path).cloned()
    }
}

fn problems(wasm: Option<BTreeMap<String, WasmInfo>>, files: &[&str]) -> Vec<(String, String)> {
    let source = Source {
        files: files
            .iter()
            .map(|f| f.to_string())
            .chain(["netherforge.json".to_string()])
            .collect(),
        wasm,
    };
    project::load(&source, Default::default())
        .problems
        .into_iter()
        .map(|p| (p.code.unwrap_or_default(), p.message))
        .collect()
}

const PATH: &str = "wasm/engine/module.wasm";

fn checked(info: WasmInfo) -> Vec<(String, String)> {
    problems(Some(BTreeMap::from([(PATH.to_string(), info)])), &[PATH])
}

fn codes(info: WasmInfo) -> Vec<String> {
    checked(info).into_iter().map(|(c, _)| c).collect()
}

fn wasi(name: &str) -> WasmImport {
    WasmImport {
        module: wasm::WASI.into(),
        name: name.into(),
        kind: WasmExtern::Function,
    }
}

fn start() -> WasmExport {
    WasmExport {
        name: "_start".into(),
        kind: WasmExtern::Function,
        params: Some(vec![]),
        results: Some(vec![]),
    }
}

#[test]
fn the_module_file_makes_the_folder_one_and_the_rest_is_its_data() {
    let files = [
        "wasm/chess/module.wasm",
        "wasm/chess/nets/big.nnue",
        "wasm/chess/book.bin",
    ];
    assert_eq!(problems(None, &files), []);
}

#[test]
fn a_module_imports_wasi_alone() {
    assert_eq!(
        checked(WasmInfo {
            imports: vec![wasi("fd_write")],
            exports: vec![start()],
            ..Default::default()
        }),
        []
    );
    let env_now = WasmImport {
        module: "env".into(),
        name: "now".into(),
        kind: WasmExtern::Function,
    };
    assert_eq!(
        checked(WasmInfo {
            imports: vec![env_now, wasi("fd_teleport")],
            exports: vec![start()],
            ..Default::default()
        }),
        [
            ("wasm.import".to_string(), "The module imports env.now, which the server doesn't provide: a module may import only WASI's functions (wasi_snapshot_preview1)".to_string()),
            ("wasm.import".to_string(), "The module imports wasi_snapshot_preview1.fd_teleport, which isn't a function of WASI preview 1".to_string()),
        ]
    );
    // A threads build says so once, whatever gave it away.
    assert_eq!(
        codes(WasmInfo {
            imports: vec![
                WasmImport {
                    module: "env".into(),
                    name: "memory".into(),
                    kind: WasmExtern::Memory
                },
                WasmImport {
                    module: "wasi".into(),
                    name: "thread-spawn".into(),
                    kind: WasmExtern::Function
                },
            ],
            exports: vec![start()],
            memory: Some(WasmMemory {
                minimum: 17,
                maximum: Some(16384),
                shared: true,
                imported: true,
                ..Default::default()
            }),
            ..Default::default()
        }),
        ["wasm.threads"]
    );
    assert_eq!(
        codes(WasmInfo {
            exports: vec![start()],
            memory: Some(WasmMemory {
                minimum: 1,
                memory64: true,
                ..Default::default()
            }),
            ..Default::default()
        }),
        ["wasm.memory"]
    );
    assert_eq!(
        codes(WasmInfo {
            exports: vec![WasmExport {
                name: "memory".into(),
                kind: WasmExtern::Memory,
                params: None,
                results: None
            }],
            ..Default::default()
        }),
        ["wasm.exports"]
    );
    assert_eq!(
        checked(WasmInfo {
            error: Some("it ends too soon".into()),
            ..Default::default()
        }),
        [(
            "wasm.invalid".to_string(),
            "module.wasm can't be run: it ends too soon".to_string()
        )]
    );
}

#[test]
fn a_host_that_reads_modules_says_when_one_cant_be_read() {
    let codes: Vec<String> = problems(Some(BTreeMap::new()), &[PATH])
        .into_iter()
        .map(|(c, _)| c)
        .collect();
    assert_eq!(codes, ["wasm.invalid"]);
    // One that reads none (the editor) says nothing: the dev server does.
    assert_eq!(problems(None, &[PATH]), []);
}

// ---- The reader ----

const I32: u8 = 0x7F;
const HEADER: [u8; 8] = [0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00];

fn leb(value: u32) -> Vec<u8> {
    let mut out = Vec::new();
    let mut rest = value;
    loop {
        let mut b = (rest & 0x7F) as u8;
        rest >>= 7;
        if rest != 0 {
            b |= 0x80;
        }
        out.push(b);
        if rest == 0 {
            return out;
        }
    }
}

fn name(text: &str) -> Vec<u8> {
    [leb(text.len() as u32), text.as_bytes().to_vec()].concat()
}

fn section(id: u8, body: Vec<u8>) -> Vec<u8> {
    [vec![id], leb(body.len() as u32), body].concat()
}

fn vector(items: Vec<Vec<u8>>) -> Vec<u8> {
    let mut out = leb(items.len() as u32);
    for item in items {
        out.extend(item);
    }
    out
}

#[test]
fn reads_what_a_module_imports_and_exports() {
    let types = vector(vec![
        [
            vec![0x60],
            vector(vec![vec![I32], vec![I32]]),
            vector(vec![vec![I32]]),
        ]
        .concat(),
        [vec![0x60], vector(vec![]), vector(vec![])].concat(),
    ]);
    let imports = vector(vec![
        [
            name("wasi_snapshot_preview1"),
            name("fd_write"),
            vec![0x00],
            leb(0),
        ]
        .concat(),
    ]);
    let functions = vector(vec![leb(0), leb(1)]);
    let memory = vector(vec![[vec![0x01], leb(2), leb(16)].concat()]);
    let exports = vector(vec![
        [name("add"), vec![0x00], leb(1)].concat(),
        [name("_start"), vec![0x00], leb(2)].concat(),
        [name("memory"), vec![0x02], leb(0)].concat(),
    ]);
    let code = vector(vec![vec![0x02, 0x00, 0x0B], vec![0x02, 0x00, 0x0B]]);
    let bytes = [
        HEADER.to_vec(),
        section(1, types),
        section(2, imports),
        section(3, functions),
        section(5, memory),
        section(7, exports),
        section(10, code),
    ]
    .concat();
    let info = read(&bytes);
    assert_eq!(info.error, None);
    assert_eq!(info.imports, [wasi("fd_write")]);
    let strings = |s: &[&str]| Some(s.iter().map(|t| t.to_string()).collect::<Vec<_>>());
    assert_eq!(
        info.exports,
        [
            WasmExport {
                name: "add".into(),
                kind: WasmExtern::Function,
                params: strings(&["i32", "i32"]),
                results: strings(&["i32"])
            },
            start(),
            WasmExport {
                name: "memory".into(),
                kind: WasmExtern::Memory,
                params: None,
                results: None
            },
        ]
    );
    assert_eq!(
        info.memory,
        Some(WasmMemory {
            minimum: 2,
            maximum: Some(16),
            ..Default::default()
        })
    );
    assert!(info.command());
}

#[test]
fn says_why_bytes_arent_a_module() {
    assert_eq!(
        read(b"hello").error.as_deref(),
        Some("it isn't a WebAssembly module (it doesn't start with \\0asm)")
    );
    let component = [0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00];
    assert!(read(&component).error.unwrap().contains("component"));
    // A section that says it's longer than the file.
    let types = vector(vec![[vec![0x60], vector(vec![]), vector(vec![])].concat()]);
    let mut cut = [HEADER.to_vec(), section(1, types)].concat();
    cut.pop();
    assert_eq!(
        read(&cut).error.as_deref(),
        Some("section 1 runs past the end of the file")
    );
    // A count that couldn't fit in its section is refused before anything is made of it.
    let forged = [HEADER.to_vec(), section(1, leb(1_000_000))].concat();
    assert!(
        read(&forged)
            .error
            .unwrap()
            .contains("more than its section's bytes can hold")
    );
    let other = [0x00, 0x61, 0x73, 0x6D, 0x02, 0x00, 0x00, 0x00];
    assert_eq!(
        read(&other).error.as_deref(),
        Some("it's version 2 0 0 0 of WebAssembly's binary format, not 1")
    );
}

#[test]
fn what_a_host_read_crosses_as_formats_json() {
    // Kotlin's serialised `WasmInfo`: what a host hands over.
    let json = r#"{ "imports": [{ "module": "wasi_snapshot_preview1", "name": "fd_write", "kind": "FUNCTION" }],
        "exports": [{ "name": "_start", "kind": "FUNCTION", "params": [], "results": [] }],
        "memory": { "minimum": 1, "maximum": 2, "shared": false, "memory64": false, "imported": false }, "start": false }"#;
    let info: WasmInfo = serde_json::from_str(json).unwrap();
    assert!(info.command());
    assert_eq!(info.memory.unwrap().maximum, Some(2));
}
