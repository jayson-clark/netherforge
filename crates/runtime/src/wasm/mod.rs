//! `nf.wasm` (Kotlin's `wasm/` and `WasmImpls`): the project's WebAssembly modules (`wasm/<id>/`) and everything
//! scripts run of them, on **wasmtime** (see the rust-runtime skill for why).
//!
//! - **Compiling.** Every module that read without errors is compiled off the main thread as the session starts
//!   (and again when its files change), on the pool: validated, its imports held to WASI's, compiled by Cranelift.
//!   A failure is the problem `runtime.wasm`, and running the module fails saying so.
//! - **Instances** (`nf.wasm.instantiate`): a store each, with its own memory and its own lane of calls. A call
//!   that traps or runs out of time closes its instance (its memory may be half-written).
//! - **Runs** (`nf.wasm.run`): an instance made, run to its end with its input and thrown away.
//! - **Programs** (`nf.wasm.start`): an instance on a thread of its own, talking line by line (`program.rs`); the
//!   main thread hands their lines to scripts each tick.
//!
//! Everything a scope makes is its own and goes when it's released; the owner's limits (`WasmConfig`) are held
//! here: memory through one `Budget`, counts per package, time by each store's `Guard`. A module a script ran
//! restarts that script when it reloads (`Service::restarts`).

pub mod api;
pub mod calls;
pub mod compiled;
pub mod files;
pub mod program;
pub mod threads;
pub mod wasi;

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use indexmap::IndexMap;
use netherforge_api::handles;
use netherforge_luau::ScopeId;

use crate::http::Reply;
use compiled::{CompiledModule, Machine};
use program::ProgramShared;
use threads::{Budget, Guard, Lane, Pool, Ticker};

/// `wasm:` in `config.yml`, read on dev servers too: how WebAssembly modules run. Calls, runs and compiling share
/// `threads` threads (each is at most one core); a started program holds a thread of its own while it runs, at
/// most `max_processes_total` in all. An instance or program may use at most `max_memory_mb` of memory, and the
/// ones running together at most `max_memory_total_mb` (each counted at the most it may grow to); a call or run is
/// stopped after `max_call_seconds`. `compile` off runs every module in Pulley, wasmtime's interpreter, many times
/// slower, for a machine Cranelift can't write code for.
#[derive(Clone, Debug)]
pub struct WasmConfig {
    pub threads: usize,
    pub compile: bool,
    pub max_module_mb: usize,
    pub max_memory_mb: u64,
    pub max_memory_total_mb: u64,
    pub max_call_seconds: u64,
    pub max_queued_calls_per_package: usize,
    pub max_instances_per_package: usize,
    pub max_processes_per_package: usize,
    pub max_processes_total: usize,
    pub max_output_bytes: usize,
    pub max_line_bytes: usize,
    pub max_queued_lines: usize,
    pub lines_per_tick: usize,
}

impl Default for WasmConfig {
    fn default() -> WasmConfig {
        WasmConfig {
            threads: 2,
            compile: true,
            max_module_mb: 64,
            max_memory_mb: 256,
            max_memory_total_mb: 1024,
            max_call_seconds: 10,
            max_queued_calls_per_package: 64,
            max_instances_per_package: 8,
            max_processes_per_package: 2,
            max_processes_total: 4,
            max_output_bytes: 1_048_576,
            max_line_bytes: 65_536,
            max_queued_lines: 1000,
            lines_per_tick: 100,
        }
    }
}

impl WasmConfig {
    /// The most memory one instance may have, in pages.
    pub fn max_pages(&self) -> u64 {
        self.max_memory_mb * compiled::PAGES_PER_MB
    }

    /// Why `what` was stopped at the time limit.
    pub fn too_long(&self, what: &str) -> String {
        format!(
            "{what} took longer than {} seconds (wasm.max-call-seconds in config.yml), so it was stopped",
            self.max_call_seconds
        )
    }

    pub fn memory_taken(&self) -> String {
        format!(
            "the server's WebAssembly memory is taken: its modules may hold {} MiB together (wasm.max-memory-total-mb in config.yml), and each instance or program counts as {} MiB (wasm.max-memory-mb); close one",
            self.max_memory_total_mb, self.max_memory_mb
        )
    }
}

/// One module's compile, shared by the main thread and the threads that wait for it: done, failed, or still going.
#[derive(Default)]
pub struct Compile {
    state: Mutex<Option<Result<Arc<CompiledModule>, String>>>,
    done: Condvar,
    /// Whether its failure was logged.
    told: AtomicBool,
}

impl Compile {
    fn set(&self, result: Result<Arc<CompiledModule>, String>) {
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = Some(result);
        self.done.notify_all();
    }

    /// Its result, once there is one.
    pub fn peek(&self) -> Option<Result<Arc<CompiledModule>, String>> {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Its result, waiting for it (off the main thread only).
    pub fn wait(&self) -> Result<Arc<CompiledModule>, String> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some(result) = &*state {
                return result.clone();
            }
            state = self.done.wait(state).unwrap_or_else(|e| e.into_inner());
        }
    }
}

/// The compiled `key`, waiting for it: an error saying why it can't run (it has errors, or didn't compile).
pub fn await_compiled(key: &str, compile: Option<&Compile>) -> Result<Arc<CompiledModule>, String> {
    let Some(compile) = compile else {
        return Err(format!(
            "module \"{key}\" has errors, so it can't run: see the problems"
        ));
    };
    compile
        .wait()
        .map_err(|e| format!("module \"{key}\" couldn't be compiled: {e}"))
}

/// The engine, the pool and the epoch's ticker: made on the first need, gone when the session stops.
pub struct Threads {
    pub machine: Arc<Machine>,
    pub pool: Arc<Pool>,
    /// Set as the session stops: every guard made under it stops its code (`Guard::under`).
    pub stopping: Arc<AtomicBool>,
    ticker: Mutex<Option<Ticker>>,
}

impl Threads {
    /// A guard for a store run on these threads.
    pub fn guard(&self) -> Arc<Guard> {
        Arc::new(Guard::under(self.stopping.clone()))
    }
}

/// An instance a script made, its `owner` scope's: its store behind its lane, which runs its calls one at a time.
pub struct LiveInstance {
    pub number: i64,
    pub owner: ScopeId,
    pub namespace: String,
    /// The module, as the server names it.
    pub key: String,
    pub module: Arc<CompiledModule>,
    pub guard: Arc<Guard>,
    pub held: Mutex<Option<compiled::Running>>,
    pub lane: Lane,
    closed: AtomicBool,
}

impl LiveInstance {
    pub fn closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Closes it from a call running in it (it holds `held`): nothing more runs in it.
    fn close_here(&self, held: &mut Option<compiled::Running>) {
        self.closed.store(true, Ordering::SeqCst);
        self.guard.close();
        *held = None;
    }

    /// Closes it: a call running now stops at the next epoch, and once it has (after it on the lane) its store
    /// and the memory it holds go. False when it was already closed.
    pub fn close(self: &Arc<LiveInstance>) -> bool {
        if self.closed.swap(true, Ordering::SeqCst) {
            return false;
        }
        self.guard.close();
        let me = self.clone();
        // A pool that's gone runs nothing more in it: the store goes with the last hold on it.
        let _ = self.lane.execute(move || {
            me.held.lock().unwrap_or_else(|e| e.into_inner()).take();
        });
        true
    }
}

/// A program a script started, its `owner` scope's.
pub struct Program {
    pub number: i64,
    pub handle: handles::WasmProcess,
    pub owner: ScopeId,
    pub namespace: String,
    pub key: String,
    pub shared: Arc<ProgramShared>,
    /// Set once its end has been handed to scripts.
    pub ended: bool,
}

/// What reads a project file's bytes by project path (a package's `ns:path`), and where one is on disk.
#[derive(Clone, Default)]
pub struct Sources {
    pub bytes: Option<crate::screen::ProjectBytes>,
    pub paths: Option<crate::screen::video::ProjectPaths>,
}

/// The session's WebAssembly.
#[derive(Default)]
pub struct Wasm {
    pub config: WasmConfig,
    pub sources: Sources,
    threads: Option<Arc<Threads>>,
    budget: Option<Arc<Budget>>,
    /// Every module that read (errors or not), by the server's name, with its `module.wasm`'s path: what scripts
    /// may name.
    pub known: BTreeMap<String, String>,
    /// Each running module's compile, by the server's name.
    pub compiled: HashMap<String, Arc<Compile>>,
    /// The scopes that ran each module, by the server's name: what its reload restarts.
    pub users: HashMap<String, BTreeSet<ScopeId>>,
    pub instances: IndexMap<i64, Arc<LiveInstance>>,
    pub programs: IndexMap<i64, Program>,
    next_number: i64,
    /// Calls waiting or running, by package: held to `max-queued-calls-per-package`. From any thread.
    queued: Arc<Mutex<HashMap<String, usize>>>,
    /// Instantiations not yet done, by package: they count against `max-instances-per-package`.
    pub instantiating: HashMap<String, usize>,
    /// What the engine said as it was made (no compiler on this machine), for the log.
    pub warnings: Vec<String>,
}

/// Where a job runs: the pool, or an instance's lane over it.
pub enum On<'a> {
    Pool,
    Lane(&'a Lane),
}

impl Wasm {
    /// The threads, made now if they weren't.
    pub fn threads(&mut self) -> Result<Arc<Threads>, String> {
        if let Some(threads) = &self.threads {
            return Ok(threads.clone());
        }
        let warnings = std::cell::RefCell::new(Vec::new());
        let machine = Machine::new(self.config.compile, &|w| warnings.borrow_mut().push(w))?;
        self.warnings.extend(warnings.into_inner());
        let ticker = Ticker::start(machine.engine.clone());
        let threads = Arc::new(Threads {
            machine: Arc::new(machine),
            pool: Arc::new(Pool::new("NetherForge wasm call", self.config.threads)),
            stopping: Arc::default(),
            ticker: Mutex::new(Some(ticker)),
        });
        self.threads = Some(threads.clone());
        Ok(threads)
    }

    pub fn budget(&mut self) -> Arc<Budget> {
        let pages = self.config.max_memory_total_mb * compiled::PAGES_PER_MB;
        self.budget
            .get_or_insert_with(|| Arc::new(Budget::new(pages)))
            .clone()
    }

    pub fn next_number(&mut self) -> i64 {
        self.next_number += 1;
        self.next_number
    }

    /// Compiles `bytes` as module `key` with its `data`, off the main thread; what was compiled of it before is
    /// replaced (a compile still going finishes unseen).
    pub fn compile(&mut self, key: &str, bytes: Option<Vec<u8>>, data: files::WasmData) {
        let compile = Arc::new(Compile::default());
        self.compiled.insert(key.to_owned(), compile.clone());
        let Some(bytes) = bytes else {
            compile.set(Err("module.wasm can't be read".into()));
            return;
        };
        let threads = match self.threads() {
            Ok(threads) => threads,
            Err(e) => {
                compile.set(Err(format!("the server can't run WebAssembly: {e}")));
                return;
            }
        };
        let max_bytes = self.config.max_module_mb * 1024 * 1024;
        let (machine, key, held) = (threads.machine.clone(), key.to_owned(), compile.clone());
        let sent = threads.pool.submit(move || {
            let result = CompiledModule::compile(&machine, &key, &bytes, data, max_bytes);
            held.set(result.map(Arc::new));
        });
        if !sent {
            compile.set(Err("the server is stopping".into()));
        }
    }

    /// Compiles that failed and weren't logged yet: each module's name and why.
    pub fn newly_failed(&self) -> Vec<(String, String)> {
        let mut failed: Vec<(String, String)> = self
            .compiled
            .iter()
            .filter_map(|(key, compile)| match compile.peek() {
                Some(Err(why)) if !compile.told.swap(true, Ordering::SeqCst) => {
                    Some((key.clone(), why))
                }
                _ => None,
            })
            .collect();
        failed.sort();
        failed
    }

    /// Runs `work` for `namespace` on `on`, held to `max-queued-calls-per-package`: its value as a reply.
    pub fn submit<T: Send + 'static>(
        &mut self,
        namespace: &str,
        on: On,
        work: impl FnOnce() -> Result<T, String> + Send + 'static,
    ) -> Reply<T> {
        let (send, receive) = std::sync::mpsc::channel();
        let most = self.config.max_queued_calls_per_package;
        {
            let mut queued = self.queued.lock().unwrap_or_else(|e| e.into_inner());
            let count = queued.entry(namespace.to_owned()).or_default();
            if *count >= most {
                let _ = send.send(Err(format!(
                    "package \"{namespace}\" already has {most} WebAssembly calls waiting, the server's limit (wasm.max-queued-calls-per-package in config.yml)"
                )));
                return Reply(receive);
            }
            *count += 1;
        }
        let queued = self.queued.clone();
        let namespace = namespace.to_owned();
        let job = {
            let namespace = namespace.clone();
            let queued = queued.clone();
            move || {
                struct Counted(Arc<Mutex<HashMap<String, usize>>>, String);
                impl Drop for Counted {
                    fn drop(&mut self) {
                        let mut queued = self.0.lock().unwrap_or_else(|e| e.into_inner());
                        if let Some(count) = queued.get_mut(&self.1) {
                            *count = count.saturating_sub(1);
                        }
                    }
                }
                let _counted = Counted(queued, namespace);
                let _ = send.send(work());
            }
        };
        let sent = match on {
            On::Lane(lane) => lane.execute(job),
            On::Pool => match self.threads() {
                Ok(threads) => threads.pool.submit(job),
                Err(_) => false,
            },
        };
        if !sent {
            // The job (and its sender) went unrun: the reply says the server is stopping.
            let mut queued = queued.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(count) = queued.get_mut(&namespace) {
                *count = count.saturating_sub(1);
            }
        }
        Reply(receive)
    }

    /// Instances `namespace` holds open or is still making.
    pub fn held_by(&self, namespace: &str) -> usize {
        self.instances
            .values()
            .filter(|i| i.namespace == namespace && !i.closed())
            .count()
            + self.instantiating.get(namespace).copied().unwrap_or(0)
    }

    /// Whether nothing given to the pool is queued or running, and every program can go no further on its own:
    /// what a test run's tick will wait on.
    pub fn quiet(&self) -> bool {
        self.threads.as_ref().is_none_or(|t| t.pool.idle())
            && self.programs.values().all(|p| p.ended || p.shared.quiet())
    }

    /// Ends the threads: what's running was stopped by its guard; nothing new runs.
    pub fn close(&mut self) {
        if let Some(threads) = self.threads.take() {
            threads.stopping.store(true, Ordering::SeqCst);
            threads.pool.close();
            // Its last tick reaches every store still running, which then stops.
            threads
                .ticker
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
        }
    }
}

/// `f` as a failure a script reads, for `what` (`"add"`, `instantiating module "math"`): a stop, a trap, an exit
/// where none was wanted.
pub fn failed(f: calls::Failed, what: &str) -> String {
    match f {
        calls::Failed::Said(said) => said,
        calls::Failed::Module(e) => {
            if e.downcast_ref::<threads::Closed>().is_some() {
                format!("{what} was stopped: the instance was closed")
            } else if let Some(wasi::Exit(code)) = e.downcast_ref::<wasi::Exit>() {
                format!("{what} exited with {code}")
            } else {
                format!("{what} failed: {}", compiled::message(&e))
            }
        }
    }
}

/// What a WASI command's run of `_start` exits with: 0 when it returns, its code when it calls `proc_exit`.
pub fn exit_code(result: Result<(), calls::Failed>) -> Result<i32, calls::Failed> {
    match result {
        Ok(()) => Ok(0),
        Err(calls::Failed::Module(e)) => match e.downcast_ref::<wasi::Exit>() {
            Some(wasi::Exit(code)) => Ok(*code),
            None => Err(calls::Failed::Module(e)),
        },
        Err(other) => Err(other),
    }
}

/// Calls `_start` (or `_initialize`, or any export taking and giving nothing) in `running`.
pub fn run_export(running: &mut compiled::Running, name: &str) -> Result<(), calls::Failed> {
    let func = running
        .instance
        .get_func(&mut running.store, name)
        .ok_or_else(|| calls::Failed::Said(format!("it exports no function \"{name}\"")))?;
    func.call(&mut running.store, &[], &mut [])?;
    Ok(())
}
