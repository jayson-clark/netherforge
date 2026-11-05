//! `wasm/<id>/`: a WebAssembly module scripts run with `nf.wasm` (format's
//! `wasm`), and the data files it reads. Built for WASI preview 1 and
//! single-threaded, it imports nothing but WASI's functions: whatever it
//! computes, it can only answer the script that called it.
//!
//! Format never reads the binary itself while loading a project: a host reads
//! it with `read` and hands the `WasmInfo` to format
//! (`ProjectSource::wasm`), and the checks (`check`) are of that. The server
//! compiles the module.

use serde::{Deserialize, Serialize};

use crate::problem::{Problem, codes};

/// The file that makes a folder a module.
pub const MAIN: &str = "module.wasm";

/// The one module a module may import from.
pub const WASI: &str = "wasi_snapshot_preview1";

/// The export a WASI command runs.
pub const COMMAND_ENTRY: &str = "_start";

/// What a module built for WASI threads imports to start one, which the server doesn't provide.
pub const THREAD_SPAWN: &str = "thread-spawn";

/// Every function of WASI preview 1.
pub const FUNCTIONS: [&str; 46] = [
    "args_get",
    "args_sizes_get",
    "environ_get",
    "environ_sizes_get",
    "clock_res_get",
    "clock_time_get",
    "fd_advise",
    "fd_allocate",
    "fd_close",
    "fd_datasync",
    "fd_fdstat_get",
    "fd_fdstat_set_flags",
    "fd_fdstat_set_rights",
    "fd_filestat_get",
    "fd_filestat_set_size",
    "fd_filestat_set_times",
    "fd_pread",
    "fd_prestat_get",
    "fd_prestat_dir_name",
    "fd_pwrite",
    "fd_read",
    "fd_readdir",
    "fd_renumber",
    "fd_seek",
    "fd_sync",
    "fd_tell",
    "fd_write",
    "path_create_directory",
    "path_filestat_get",
    "path_filestat_set_times",
    "path_link",
    "path_open",
    "path_readlink",
    "path_remove_directory",
    "path_rename",
    "path_symlink",
    "path_unlink_file",
    "poll_oneoff",
    "proc_exit",
    "proc_raise",
    "sched_yield",
    "random_get",
    "sock_accept",
    "sock_recv",
    "sock_send",
    "sock_shutdown",
];

/// What an import or export is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum WasmExtern {
    Function,
    Table,
    Memory,
    Global,
    Tag,
}

/// One import: `module` and `name` are its two-part name (`wasi_snapshot_preview1`, `fd_write`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WasmImport {
    pub module: String,
    pub name: String,
    pub kind: WasmExtern,
}

impl WasmImport {
    /// Whether the server provides it: a function of WASI preview 1.
    pub fn provided(&self) -> bool {
        self.kind == WasmExtern::Function
            && self.module == WASI
            && FUNCTIONS.contains(&self.name.as_str())
    }

    /// Whether it's one a module built for threads makes: its shared memory, or `wasi.thread-spawn`.
    pub fn threaded(&self) -> bool {
        self.kind == WasmExtern::Memory || (self.module == "wasi" && self.name == THREAD_SPAWN)
    }
}

/// One export. A function's `params` and `results` are its value types (`i32`, `i64`, `f32`, `f64`, `v128`,
/// `funcref`, `externref`, or `ref` for any other reference); none for anything else, and for a function whose
/// type format couldn't read (a type of the GC proposal's).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WasmExport {
    pub name: String,
    pub kind: WasmExtern,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub params: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub results: Option<Vec<String>>,
}

/// A memory's limits in 64 KiB pages; `maximum` none when it may grow to whatever the server allows. `shared` is
/// a threads build's, `memory64` one addressed by 64-bit numbers.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WasmMemory {
    pub minimum: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub maximum: Option<i64>,
    #[serde(default)]
    pub shared: bool,
    #[serde(default)]
    pub memory64: bool,
    #[serde(default)]
    pub imported: bool,
}

/// What a WebAssembly module's bytes say about it, as far as checking a project needs: what it imports, what it
/// exports and its memory. `error` says why the bytes aren't a module format can read; the rest is then empty.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WasmInfo {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default)]
    pub imports: Vec<WasmImport>,
    #[serde(default)]
    pub exports: Vec<WasmExport>,
    /// The module's memory, its own or the one it imports; none when it has none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory: Option<WasmMemory>,
    /// Whether it has a start function, which runs as it's instantiated.
    #[serde(default)]
    pub start: bool,
}

impl WasmInfo {
    /// The function it exports as `name`.
    pub fn function(&self, name: &str) -> Option<&WasmExport> {
        self.exports
            .iter()
            .find(|e| e.kind == WasmExtern::Function && e.name == name)
    }

    /// Whether it's a WASI command: it exports `_start`, which runs it as a program.
    pub fn command(&self) -> bool {
        self.function(COMMAND_ENTRY).is_some()
    }
}

/// What's wrong with module `file` (its `module.wasm`) as its host read it (`info`), for the server to run it:
/// it can't be read, it was built for threads or 64-bit memory, it imports what the server doesn't provide, or it
/// exports nothing to call. `reads` is whether the host reads modules at all (the editor doesn't: the dev server
/// says).
pub fn check(file: &str, reads: bool, info: Option<&WasmInfo>) -> Vec<Problem> {
    if !reads {
        return Vec::new();
    }
    let report = |code: crate::ProblemCode, message: String| code.at(file, message, None);
    let Some(info) = info else {
        return vec![report(codes::WASM_INVALID, format!("{MAIN} can't be read"))];
    };
    if let Some(error) = &info.error {
        return vec![report(
            codes::WASM_INVALID,
            format!("{MAIN} can't be run: {error}"),
        )];
    }
    let mut out = Vec::new();
    let threaded = info.imports.iter().find(|i| i.threaded());
    if threaded.is_some() || info.memory.as_ref().is_some_and(|m| m.shared) {
        let what = threaded.map_or_else(
            || "its memory is shared".to_string(),
            |i| format!("it imports {}.{}", i.module, i.name),
        );
        out.push(report(
            codes::WASM_THREADS,
            format!("The module was built for threads ({what}), which the server doesn't run: build it single-threaded (for wasm32-wasip1, not wasm32-wasip1-threads)"),
        ));
    }
    for import in &info.imports {
        if import.provided() || import.threaded() {
            continue;
        }
        let what = format!("{}.{}", import.module, import.name);
        out.push(report(
            codes::WASM_IMPORT,
            if import.module == WASI {
                format!("The module imports {what}, which isn't a function of WASI preview 1")
            } else {
                format!("The module imports {what}, which the server doesn't provide: a module may import only WASI's functions ({WASI})")
            },
        ));
    }
    if info.memory.as_ref().is_some_and(|m| m.memory64) {
        out.push(report(
            codes::WASM_MEMORY,
            "The module's memory is 64-bit (memory64), which the server doesn't run: build it for wasm32".into(),
        ));
    }
    if !info.exports.iter().any(|e| e.kind == WasmExtern::Function) {
        out.push(report(
            codes::WASM_EXPORTS,
            "The module exports no function: nothing can call or run it".into(),
        ));
    }
    out
}

// ---- Reading a module's bytes ----

/// `\0asm`, then version 1 of the core format.
const MAGIC: [u8; 4] = [0x00, 0x61, 0x73, 0x6D];
const VERSION: [u8; 4] = [0x01, 0x00, 0x00, 0x00];
/// Version 13 of the binary, with layer 1: a component of the component model, not a core module.
const COMPONENT: [u8; 4] = [0x0D, 0x00, 0x01, 0x00];

const TYPE: u8 = 1;
const IMPORT: u8 = 2;
const FUNCTION: u8 = 3;
const MEMORY: u8 = 5;
const EXPORT: u8 = 7;
const START: u8 = 8;

/// The function type form; any other (a GC proposal's struct, array or rec group) leaves the types unknown.
const FUNC_TYPE: u8 = 0x60;

type Signature = (Vec<String>, Vec<String>);

/// Reads a WebAssembly module's sections far enough for `WasmInfo`: its types, imports, functions, memories,
/// exports and start, skipping every other section by its size. It checks the binary's framing (the header, each
/// section inside the file, each vector inside its section), not the code: the server's runtime validates that
/// when it compiles the module.
pub fn read(bytes: &[u8]) -> WasmInfo {
    read_module(bytes).unwrap_or_else(|error| WasmInfo {
        error: Some(error),
        ..WasmInfo::default()
    })
}

fn read_module(bytes: &[u8]) -> Result<WasmInfo, String> {
    if bytes.len() < 8 || bytes[0..4] != MAGIC {
        return Err("it isn't a WebAssembly module (it doesn't start with \\0asm)".into());
    }
    let version = &bytes[4..8];
    if version == COMPONENT {
        return Err(
            "it's a WebAssembly component, not a core module: build a core module (wasm32-wasip1)"
                .into(),
        );
    }
    if version != VERSION {
        let written: Vec<String> = version.iter().map(|b| (*b as i8).to_string()).collect();
        return Err(format!(
            "it's version {} of WebAssembly's binary format, not 1",
            written.join(" ")
        ));
    }

    // None once a type isn't a plain function type: signatures are then unknown.
    let mut types: Option<Vec<Signature>> = Some(Vec::new());
    let mut imports = Vec::new();
    let mut imported_function_types: Vec<u64> = Vec::new();
    let mut function_types: Vec<u64> = Vec::new();
    let mut memory: Option<WasmMemory> = None;
    let mut exports: Vec<(String, WasmExtern, u64)> = Vec::new();
    let mut start = false;

    let mut input = Input {
        bytes,
        at: 8,
        end: bytes.len(),
    };
    while !input.done() {
        let id = input.byte()?;
        let size = input.u32()?;
        let mut section = input.slice(size, &format!("section {id}"))?;
        match id {
            TYPE => types = read_types(&mut section)?,
            IMPORT => {
                for _ in 0..section.count("imports")? {
                    let module = section.name()?;
                    let name = section.name()?;
                    let kind = match section.byte()? {
                        0x00 => {
                            imported_function_types.push(section.u32()?);
                            WasmExtern::Function
                        }
                        0x01 => {
                            section.value_type()?;
                            section.limits()?;
                            WasmExtern::Table
                        }
                        0x02 => {
                            let limits = section.limits()?;
                            memory.get_or_insert(WasmMemory {
                                imported: true,
                                ..limits
                            });
                            WasmExtern::Memory
                        }
                        0x03 => {
                            section.value_type()?;
                            section.byte()?;
                            WasmExtern::Global
                        }
                        0x04 => {
                            section.byte()?;
                            section.u32()?;
                            WasmExtern::Tag
                        }
                        kind => {
                            return Err(format!(
                                "import {module}.{name} is of an unknown kind ({kind})"
                            ));
                        }
                    };
                    imports.push(WasmImport { module, name, kind });
                }
            }
            FUNCTION => {
                for _ in 0..section.count("functions")? {
                    function_types.push(section.u32()?);
                }
            }
            MEMORY => {
                for _ in 0..section.count("memories")? {
                    let limits = section.limits()?;
                    memory.get_or_insert(limits);
                }
            }
            EXPORT => {
                for _ in 0..section.count("exports")? {
                    let name = section.name()?;
                    let kind = match section.byte()? {
                        0x00 => WasmExtern::Function,
                        0x01 => WasmExtern::Table,
                        0x02 => WasmExtern::Memory,
                        0x03 => WasmExtern::Global,
                        0x04 => WasmExtern::Tag,
                        byte => {
                            return Err(format!(
                                "export \"{name}\" is of an unknown kind ({byte})"
                            ));
                        }
                    };
                    exports.push((name, kind, section.u32()?));
                }
            }
            START => start = true,
            _ => {}
        }
    }

    let all_functions: Vec<u64> = imported_function_types
        .into_iter()
        .chain(function_types)
        .collect();
    let exports = exports
        .into_iter()
        .map(|(name, kind, index)| {
            let signature = (kind == WasmExtern::Function)
                .then(|| {
                    let ty = *all_functions.get(usize::try_from(index).ok()?)?;
                    types.as_ref()?.get(usize::try_from(ty).ok()?).cloned()
                })
                .flatten();
            let (params, results) = signature.map_or((None, None), |(p, r)| (Some(p), Some(r)));
            WasmExport {
                name,
                kind,
                params,
                results,
            }
        })
        .collect();
    Ok(WasmInfo {
        error: None,
        imports,
        exports,
        memory,
        start,
    })
}

fn read_types(section: &mut Input) -> Result<Option<Vec<Signature>>, String> {
    let mut types = Vec::new();
    for _ in 0..section.count("types")? {
        if section.byte()? != FUNC_TYPE {
            return Ok(None);
        }
        let mut params = Vec::new();
        for _ in 0..section.count("parameters")? {
            params.push(section.value_type()?);
        }
        let mut results = Vec::new();
        for _ in 0..section.count("results")? {
            results.push(section.value_type()?);
        }
        types.push((params, results));
    }
    Ok(Some(types))
}

/// The bytes from `at` up to `end`, read forward.
struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
    end: usize,
}

impl Input<'_> {
    fn done(&self) -> bool {
        self.at >= self.end
    }

    fn left(&self) -> u64 {
        (self.end - self.at) as u64
    }

    fn byte(&mut self) -> Result<u8, String> {
        if self.at >= self.end {
            return Err("it ends in the middle of a section".into());
        }
        self.at += 1;
        Ok(self.bytes[self.at - 1])
    }

    fn u32(&mut self) -> Result<u64, String> {
        self.unsigned(32)
    }

    /// An unsigned LEB128 number of at most `bits` bits; one past `i64::MAX` is held there.
    fn unsigned(&mut self, bits: u32) -> Result<u64, String> {
        let mut result: u64 = 0;
        let mut shift = 0;
        loop {
            let b = self.byte()?;
            let digits = u64::from(b & 0x7F);
            if shift < 63 {
                result |= digits << shift;
            } else if digits != 0 {
                result = i64::MAX as u64;
            }
            if b & 0x80 == 0 {
                return Ok(result.min(i64::MAX as u64));
            }
            shift += 7;
            if shift >= bits + 7 {
                return Err(format!("a number in it is longer than {bits} bits"));
            }
        }
    }

    /// How many of something follow, held to the bytes left, so a forged count can't make a huge list.
    fn count(&mut self, what: &str) -> Result<u64, String> {
        let count = self.u32()?;
        if count > self.left() {
            return Err(format!(
                "it says it has {count} {what}, more than its section's bytes can hold"
            ));
        }
        Ok(count)
    }

    fn slice(&mut self, size: u64, what: &str) -> Result<Input<'_>, String> {
        if size > self.left() {
            return Err(format!("{what} runs past the end of the file"));
        }
        let start = self.at;
        self.at += size as usize;
        Ok(Input {
            bytes: self.bytes,
            at: start,
            end: self.at,
        })
    }

    fn name(&mut self) -> Result<String, String> {
        let size = self.u32()?;
        if size > self.left() {
            return Err("a name in it runs past its section".into());
        }
        let start = self.at;
        self.at += size as usize;
        Ok(String::from_utf8_lossy(&self.bytes[start..self.at]).into_owned())
    }

    fn value_type(&mut self) -> Result<String, String> {
        Ok(match self.byte()? {
            0x7F => "i32",
            0x7E => "i64",
            0x7D => "f32",
            0x7C => "f64",
            0x7B => "v128",
            0x70 => "funcref",
            0x6F => "externref",
            0x63 | 0x64 => {
                // A heap type follows: an s33, one byte for an abstract one or a type's index.
                while self.byte()? & 0x80 != 0 {}
                "ref"
            }
            // The GC proposal's other abstract reference types, each one byte.
            0x69..=0x74 => "ref",
            b => return Err(format!("it has an unknown value type (0x{b:x})")),
        }
        .into())
    }

    fn limits(&mut self) -> Result<WasmMemory, String> {
        let flags = self.byte()?;
        if flags > 0x07 {
            return Err(format!(
                "a memory or table in it has unknown limits (flags {flags})"
            ));
        }
        let bits = if flags & 0x04 != 0 { 64 } else { 32 };
        let minimum = self.unsigned(bits)? as i64;
        let maximum = if flags & 0x01 != 0 {
            Some(self.unsigned(bits)? as i64)
        } else {
            None
        };
        Ok(WasmMemory {
            minimum,
            maximum,
            shared: flags & 0x02 != 0,
            memory64: bits == 64,
            imported: false,
        })
    }
}
