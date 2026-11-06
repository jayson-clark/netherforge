//! A module the server compiled (Kotlin's `CompiledModule`): wasmtime's `Module` and the `InstancePre` of it against
//! WASI, the functions it exports with their types, and its data. Made off the main thread once per load of its
//! file, and shared by every instance of it.

use std::sync::Arc;
use std::time::Instant;

use wasmtime::{Engine, InstancePre, Linker, Module, Store, ValType};

use super::calls::Failed;
use super::files::{Files, WasmData};
use super::threads::{Guard, Reservation};
use super::wasi::{self, Input, Limits, NoInput, NoOutput, Output, StoreData, Wasi};

/// 64 KiB pages in a MiB.
pub const PAGES_PER_MB: u64 = 16;
const PAGE_BYTES: u64 = 65_536;
const MIB: usize = 1024 * 1024;

/// A value's type as the API names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ty {
    I32,
    I64,
    F32,
    F64,
    V128,
    FuncRef,
    ExternRef,
    Ref,
}

impl Ty {
    fn of(ty: &ValType) -> Ty {
        match ty {
            ValType::I32 => Ty::I32,
            ValType::I64 => Ty::I64,
            ValType::F32 => Ty::F32,
            ValType::F64 => Ty::F64,
            ValType::V128 => Ty::V128,
            ValType::Ref(r) if r.matches(&wasmtime::RefType::FUNCREF) => Ty::FuncRef,
            ValType::Ref(r) if r.matches(&wasmtime::RefType::EXTERNREF) => Ty::ExternRef,
            ValType::Ref(_) => Ty::Ref,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Ty::I32 => "i32",
            Ty::I64 => "i64",
            Ty::F32 => "f32",
            Ty::F64 => "f64",
            Ty::V128 => "v128",
            Ty::FuncRef => "funcref",
            Ty::ExternRef => "externref",
            Ty::Ref => "ref",
        }
    }

    /// Whether a script can take it as a result: a number.
    pub fn number(self) -> bool {
        matches!(self, Ty::I32 | Ty::I64 | Ty::F32 | Ty::F64)
    }
}

/// An exported function's parameters and results.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Signature {
    pub params: Vec<Ty>,
    pub results: Vec<Ty>,
}

/// The engine every module of a session is compiled with, and WASI on a linker for it.
pub struct Machine {
    pub engine: Engine,
    linker: Linker<StoreData>,
    /// Whether it compiles to the machine's code (Cranelift), or to Pulley's bytecode for its interpreter.
    pub native: bool,
}

/// The deepest a module's own stack may go.
const MAX_WASM_STACK: usize = 1024 * 1024;

impl Machine {
    /// An engine compiling with Cranelift, or for Pulley (`wasm.compile: false`), stopping code at epochs; one this
    /// host can't compile for falls back to Pulley, and `warn` hears it.
    pub fn new(compile: bool, warn: &dyn Fn(String)) -> Result<Machine, String> {
        let make = |native: bool| -> Result<Engine, String> {
            let mut config = wasmtime::Config::new();
            config
                .epoch_interruption(true)
                .max_wasm_stack(MAX_WASM_STACK)
                .cranelift_opt_level(wasmtime::OptLevel::Speed);
            if !native {
                let pulley = if cfg!(target_pointer_width = "64") {
                    "pulley64"
                } else {
                    "pulley32"
                };
                config.target(pulley).map_err(|e| e.to_string())?;
            }
            Engine::new(&config).map_err(|e| e.to_string())
        };
        let (engine, native) = match compile {
            true => match make(true) {
                Ok(engine) => (engine, true),
                Err(e) => {
                    warn(format!(
                        "WebAssembly modules run in the interpreter: this server's machine can't compile them ({e})"
                    ));
                    (make(false)?, false)
                }
            },
            false => (make(false)?, false),
        };
        let mut linker = Linker::new(&engine);
        wasi::define(&mut linker).map_err(|e| e.to_string())?;
        Ok(Machine {
            engine,
            linker,
            native,
        })
    }
}

/// A compiled module.
pub struct CompiledModule {
    /// Its name as the server knows it: `chess`, or `library:chess` for a package's.
    pub key: String,
    pre: InstancePre<StoreData>,
    /// Exported functions in the order it exports them, with their types.
    pub functions: Vec<(String, Signature)>,
    pub data: Arc<WasmData>,
    /// The memory it starts with, in pages (0 for none).
    pub initial_pages: u64,
}

/// What a running instance reads and writes as its standard streams, and the name and arguments it's run with.
pub struct Io {
    pub name: String,
    pub stdin: Box<dyn Input>,
    pub stdout: Box<dyn Output>,
    pub stderr: Box<dyn Output>,
    pub arguments: Vec<String>,
    pub environment: Vec<(String, String)>,
}

impl Io {
    /// A library's: nothing to read, its output thrown away.
    pub fn quiet(name: &str) -> Io {
        Io {
            name: name.to_owned(),
            stdin: Box::new(NoInput),
            stdout: Box::new(NoOutput),
            stderr: Box::new(NoOutput),
            arguments: Vec::new(),
            environment: Vec::new(),
        }
    }
}

/// An instance, its store (its memory, its WASI) and the memory it holds against the budget, until it's dropped.
pub struct Running {
    pub store: Store<StoreData>,
    pub instance: wasmtime::Instance,
    _reservation: Reservation,
}

impl CompiledModule {
    /// The type of the function it exports as `name`.
    pub fn signature(&self, name: &str) -> Option<&Signature> {
        self.functions
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, s)| s)
    }

    /// Whether it's a WASI command: it exports `_start`.
    pub fn command(&self) -> bool {
        self.signature(netherforge_format::wasm::COMMAND_ENTRY)
            .is_some()
    }

    /// Parses, validates and compiles `bytes`, the module `key`'s `module.wasm`, refusing what the server can't
    /// run: an import that isn't WASI preview 1's, a module over `max_bytes`. A failure says why, in words for the
    /// problem and the scripts that run it.
    pub fn compile(
        machine: &Machine,
        key: &str,
        bytes: &[u8],
        data: WasmData,
        max_bytes: usize,
    ) -> Result<CompiledModule, String> {
        if bytes.len() > max_bytes {
            return Err(format!(
                "module.wasm is {} MiB, more than the server compiles (wasm.max-module-mb, {} MiB)",
                bytes.len() / MIB,
                max_bytes / MIB
            ));
        }
        Module::validate(&machine.engine, bytes).map_err(|e| {
            format!(
                "module.wasm isn't a valid WebAssembly module: {}",
                message(&e)
            )
        })?;
        let module = Module::new(&machine.engine, bytes)
            .map_err(|e| format!("module.wasm couldn't be compiled: {}", message(&e)))?;
        for import in module.imports() {
            let provided = matches!(import.ty(), wasmtime::ExternType::Func(_))
                && import.module() == wasi::MODULE
                && netherforge_format::wasm::FUNCTIONS.contains(&import.name());
            if !provided {
                return Err(format!(
                    "module.wasm imports {}.{}, which the server doesn't provide: a module may import only WASI's functions ({}), single-threaded",
                    import.module(),
                    import.name(),
                    wasi::MODULE
                ));
            }
        }
        let functions = module
            .exports()
            .filter_map(|export| {
                let wasmtime::ExternType::Func(ty) = export.ty() else {
                    return None;
                };
                Some((
                    export.name().to_owned(),
                    Signature {
                        params: ty.params().map(|t| Ty::of(&t)).collect(),
                        results: ty.results().map(|t| Ty::of(&t)).collect(),
                    },
                ))
            })
            .collect();
        let initial_pages = netherforge_format::wasm::read(bytes)
            .memory
            .map_or(0, |m| m.minimum.max(0) as u64);
        let pre = machine
            .linker
            .instantiate_pre(&module)
            .map_err(|e| format!("module.wasm can't be linked to WASI: {}", message(&e)))?;
        Ok(CompiledModule {
            key: key.to_owned(),
            pre,
            functions,
            data: Arc::new(data),
            initial_pages,
        })
    }

    /// A new instance, its start function run, whose memory may grow to `max_pages` at most, holding `reservation`
    /// while it lives; `guard` stops its code. One that starts bigger than that fails.
    pub fn instantiate(
        &self,
        io: Io,
        max_pages: u64,
        guard: Arc<Guard>,
        reservation: Reservation,
    ) -> Result<Running, Failed> {
        if self.initial_pages > max_pages {
            return Err(Failed::Said(format!(
                "module \"{}\" starts with {} MiB of memory, more than the server lets one have (wasm.max-memory-mb, {} MiB)",
                self.key,
                self.initial_pages / PAGES_PER_MB,
                max_pages / PAGES_PER_MB
            )));
        }
        let mut args = vec![io.name.into_bytes()];
        args.extend(io.arguments.into_iter().map(String::into_bytes));
        let environment = io
            .environment
            .into_iter()
            .map(|(name, value)| format!("{name}={value}").into_bytes())
            .collect();
        let data = StoreData {
            wasi: Wasi {
                args,
                environment,
                stdin: io.stdin,
                stdout: io.stdout,
                stderr: io.stderr,
                files: Files::new(self.data.clone()),
                guard: guard.clone(),
                started: Instant::now(),
            },
            limits: Limits {
                max_memory_bytes: (max_pages * PAGE_BYTES) as usize,
            },
        };
        let mut store = Store::new(self.pre.module().engine(), data);
        store.limiter(|data| &mut data.limits);
        store.set_epoch_deadline(1);
        store.epoch_deadline_callback(|cx| {
            cx.data().wasi.guard.check()?;
            Ok(wasmtime::UpdateDeadline::Continue(1))
        });
        // The module's start section runs as it's made; a command's _start is the caller's to call.
        let instance = self.pre.instantiate(&mut store)?;
        Ok(Running {
            store,
            instance,
            _reservation: reservation,
        })
    }
}

/// What went wrong, as a script reads it: a trap's own words, else the error's first cause.
pub fn message(e: &wasmtime::Error) -> String {
    if let Some(trap) = e.downcast_ref::<wasmtime::Trap>() {
        return trap.to_string();
    }
    e.root_cause().to_string()
}
