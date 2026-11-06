//! `nf.wasm`, `WasmInstance` and `WasmProcess` (Kotlin's `NfWasmImpl`, `WasmInstanceImpl`, `WasmProcessImpl` and
//! what `WasmModules` does on the main thread), and the service that compiles, delivers and cleans up.
//!
//! What a script got wrong in the call (a module the project doesn't have, a function it doesn't export, arguments
//! that don't fit) is an error at its line, found here on the main thread; what only running it can say (a trap, a
//! limit, a module that didn't compile) is `nil, err` or the program's `end`. No borrow of the session's `Wasm` is
//! held while a script runs.

use std::collections::{BTreeMap, BTreeSet};
use std::rc::{Rc, Weak};
use std::sync::Arc;

use mlua::{Function, Value};
use netherforge_api::api::{NfWasmApi, WasmInstanceApi, WasmProcessApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, Pending, codecs, shapes};
use netherforge_luau::ScopeId;
use netherforge_platform::Platform;

use super::calls::{self, Number};
use super::compiled::{self, Io};
use super::files::{DataFile, WasmData};
use super::program::{
    LineOutput, ProgramEnd, ProgramInput, ProgramOutput, ProgramShared, SharedOutput,
};
use super::{LiveInstance, On, Program, Wasm, await_compiled, exit_code, failed, run_export};
use crate::Room;
use crate::scripts::Target;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

fn refused<T: 'static>(why: String) -> Pending<T> {
    Box::pin(async move { Err(ApiError(why)) })
}

/// The largest whole number a double holds exactly.
const SAFE: i64 = 9_007_199_254_740_991;

/// `nf.wasm` and its handles, for one room.
#[derive(Clone)]
pub struct WasmApi {
    pub game: GameContext,
}

impl WasmApi {
    fn shared(&self) -> &Rc<Shared> {
        &self.game.shared
    }

    fn namespace(&self, scope: ScopeId) -> String {
        self.game
            .scripts
            .namespace(scope)
            .unwrap_or_else(|| self.shared().names.borrow().home.clone())
    }

    /// The module `name` names (resolved in the calling package), as the server names it; recorded as `scope`'s.
    pub(crate) fn resolve(&self, scope: ScopeId, name: &str, what: &str) -> ApiResult<String> {
        let from = self.namespace(scope);
        let key = {
            let names = self.shared().names.borrow();
            crate::names::Calling {
                names: &names,
                from,
            }
            .resource("wasm", name)?
        };
        let mut wasm = self.shared().wasm.borrow_mut();
        if !wasm.known.contains_key(&key) {
            let known: Vec<String> = wasm.known.keys().cloned().collect();
            return Err(err(format!(
                "{what}: {}",
                crate::centity::api::not_in_project("WebAssembly module", name, &known)
            )));
        }
        wasm.users.entry(key.clone()).or_default().insert(scope);
        Ok(key)
    }

    /// `key` as the running script names it.
    fn spell(&self, key: &str) -> String {
        self.game.with_names(|names| names.spell(key))
    }

    fn environment(given: Option<BTreeMap<String, String>>) -> ApiResult<Vec<(String, String)>> {
        let given = given.unwrap_or_default();
        if let Some(name) = given
            .keys()
            .find(|n| n.is_empty() || n.contains('=') || n.contains('\0'))
        {
            return Err(err(format!(
                "options.environment has a name that can't be an environment variable's: \"{name}\""
            )));
        }
        Ok(given.into_iter().collect())
    }

    fn target(&self, handle: Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.game.scripts.host().lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    /// Calls `name` in instance `this` with `arguments`; `text` for `call_text`. The values reach the main thread
    /// as a `Reply`.
    fn call(
        &self,
        this: &handles::WasmInstance,
        name: &str,
        arguments: &Value,
        text: bool,
    ) -> ApiResult<crate::http::Reply<Answer>> {
        let what = if text {
            "WasmInstance:call_text"
        } else {
            "WasmInstance:call"
        };
        let mut wasm = self.shared().wasm.borrow_mut();
        let Some(record) = wasm
            .instances
            .get(&this.number)
            .filter(|i| !i.closed())
            .cloned()
        else {
            let (send, receive) = std::sync::mpsc::channel();
            let _ = send.send(Err("the instance is closed".into()));
            return Ok(crate::http::Reply(receive));
        };
        let module = record.module.clone();
        let Some(signature) = module.signature(name).cloned() else {
            let mut names: Vec<&str> = module.functions.iter().map(|(n, _)| n.as_str()).collect();
            names.sort_unstable();
            return Err(err(format!(
                "{what}: module \"{}\" exports no function \"{name}\" (it exports [{}])",
                record.key,
                names.join(", ")
            )));
        };
        let results: Vec<&str> = signature.results.iter().map(|t| t.name()).collect();
        if text && !calls::answers_text(&signature) {
            return Err(err(format!(
                "{what}: {name} returns ({}), not text: text comes back as two i32s (where it starts and its size) or one i64 holding both",
                results.join(", ")
            )));
        }
        if !text && signature.results.iter().any(|t| !t.number()) {
            return Err(err(format!(
                "{what}: {name} returns a [{}], which a script can't take",
                results.join(", ")
            )));
        }
        let arguments = calls::arguments(&module, name, &signature, arguments)?;
        let (seconds, max_text) = (wasm.config.max_call_seconds, wasm.config.max_output_bytes);
        let too_long = format!("{} and the instance closed", wasm.config.too_long(name));
        let (lane, name, namespace) = (
            record.lane.clone(),
            name.to_owned(),
            record.namespace.clone(),
        );
        Ok(wasm.submit(&namespace, On::Lane(&lane), move || {
            let mut held = record.held.lock().unwrap_or_else(|e| e.into_inner());
            if record.closed() {
                return Err("the instance is closed".into());
            }
            let Some(running) = held.as_mut() else {
                return Err("the instance is closed".into());
            };
            let result = record.guard.limited(seconds, || {
                let compiled::Running {
                    store, instance, ..
                } = running;
                calls::call(
                    store,
                    instance,
                    &module,
                    &name,
                    &arguments,
                    |store, instance, results| {
                        if text {
                            calls::text(store, instance, &module, results, max_text)
                                .map(Answer::Text)
                        } else {
                            calls::values(results).map(Answer::Numbers)
                        }
                    },
                )
            });
            match result {
                Ok(answer) => Ok(answer),
                Err(failure) => {
                    // Its memory may be half-written: nothing more runs in it.
                    record.close_here(&mut held);
                    Err(match failure {
                        None => too_long,
                        Some(f) => failed(f, &name),
                    })
                }
            }
        }))
    }

    // ---- a test's stand-ins (`nf.test.stub_wasm`) ------------------------------------------------------------

    /// Whether module `key` is stood in for now.
    fn standing_in(&self, key: &str) -> bool {
        self.shared().wasm.borrow().stubs.contains_key(key)
    }

    /// `answer`, made at the next turn of the main thread (before the next tick, as the test's code) from what stands
    /// in for module `key` now; an error when the stand-in has gone.
    fn stood_in<T: 'static>(
        &self,
        key: &str,
        answer: impl FnOnce(&crate::Scripts, ScopeId, &shapes::TestWasmStub) -> Result<T, String>
        + 'static,
    ) -> crate::http::Reply<T> {
        let (send, receive) = std::sync::mpsc::channel();
        let (shared, scripts, key) = (
            Rc::downgrade(self.shared()),
            self.game.scripts.clone(),
            key.to_owned(),
        );
        self.shared()
            .tests
            .borrow_mut()
            .later
            .push(Box::new(move || {
                let Some(shared) = shared.upgrade() else {
                    return;
                };
                let stub = shared.wasm.borrow().stubs.get(&key).cloned();
                let _ = send.send(match stub {
                    None => Err(format!("the stand-in for module \"{key}\" is gone")),
                    Some((scope, stub)) => answer(&scripts, scope, &stub),
                });
            }));
        crate::http::Reply(receive)
    }

    /// A stand-in instance's call of `name` with `arguments`: what the stand-in's function of that name answers.
    fn stand_in_call(
        &self,
        stand_in: &super::StandIn,
        name: &str,
        arguments: &Value,
    ) -> ApiResult<crate::http::Reply<Value>> {
        let host = self.game.scripts.host();
        let arguments = crate::testing::json_of(&host, arguments, "args").map_err(ApiError)?;
        let (key, name) = (stand_in.key.clone(), name.to_owned());
        Ok(self.stood_in(&stand_in.key, move |scripts, scope, stub| {
            let Some(function) = stub.functions.as_ref().and_then(|f| f.get(&name)) else {
                return Err(format!(
                    "the stand-in for module \"{key}\" has no function \"{name}\""
                ));
            };
            let lua = scripts.host().lua().clone();
            let arguments = match &arguments {
                Some(json) => {
                    netherforge_luau::json::to_lua(&lua, json, &netherforge_luau::json::NoTags)
                        .map_err(|e| e.to_string())?
                }
                None => Value::Nil,
            };
            stub_answer(
                scripts,
                scope,
                function,
                arguments,
                &format!("functions.{name}"),
            )
        }))
    }

    /// A stand-in program's lines sent since the last tick, answered by its `input`; its end once its input closed.
    fn answer_stand_ins(&self) {
        let stood: Vec<(i64, handles::WasmProcess, Arc<ProgramShared>)> = {
            let wasm = self.shared().wasm.borrow();
            wasm.programs
                .values()
                .filter(|p| p.stand_in == Some(false) && !p.ended)
                .map(|p| (p.number, p.handle.clone(), p.shared.clone()))
                .collect()
        };
        for (number, handle, shared) in stood {
            let (lines, finished) = shared.take_sent();
            let stub = self
                .shared()
                .wasm
                .borrow()
                .stubs
                .get(&handle.module)
                .cloned();
            let Some((scope, stub)) = stub else { continue };
            for line in lines {
                let Some(input) = &stub.input else { continue };
                let process =
                    netherforge_api::handle::push(self.game.scripts.host().lua(), &handle)
                        .map(Value::UserData)
                        .unwrap_or(Value::Nil);
                match stub_lines(&self.game.scripts, scope, input, line, process) {
                    Ok(printed) => {
                        for text in printed {
                            shared.push(ProgramOutput::Line {
                                text,
                                stream: "stdout",
                            });
                        }
                    }
                    Err(why) => {
                        shared.push(ProgramOutput::Ended {
                            reason: ProgramEnd::Failed,
                            exit_code: None,
                            detail: Some(why),
                        });
                    }
                }
            }
            if finished
                && let Some(program) = self.shared().wasm.borrow_mut().programs.get_mut(&number)
                && program.stand_in == Some(false)
            {
                program.stand_in = Some(true);
                shared.push(ProgramOutput::Ended {
                    reason: ProgramEnd::Exited,
                    exit_code: Some(0),
                    detail: None,
                });
            }
        }
    }

    /// Hands each program's lines to scripts, up to `lines-per-tick` each, then its end.
    pub fn deliver(&self) {
        let (numbers, per_tick) = {
            let wasm = self.shared().wasm.borrow();
            let numbers: Vec<i64> = wasm.programs.keys().copied().collect();
            (numbers, wasm.config.lines_per_tick)
        };
        for number in numbers {
            let mut lines = 0;
            while lines < per_tick {
                let found = {
                    let wasm = self.shared().wasm.borrow();
                    wasm.programs
                        .get(&number)
                        .filter(|p| !p.ended)
                        .map(|p| (p.handle.clone(), p.shared.clone()))
                };
                let Some((handle, shared)) = found else { break };
                let Some(output) = shared.take() else { break };
                match output {
                    ProgramOutput::Line { text, stream } => {
                        shared.release(1);
                        lines += 1;
                        self.emit_output(&handle, text, stream);
                    }
                    ProgramOutput::Ended {
                        reason,
                        exit_code,
                        detail,
                    } => {
                        self.end(number, reason, exit_code, detail);
                    }
                }
            }
        }
    }

    fn emit_output(&self, handle: &handles::WasmProcess, line: String, stream: &str) {
        let Some(target) = self.target(Handle::WasmProcess(handle.clone())) else {
            return;
        };
        let scripts = &self.game.scripts;
        if !scripts.listening(&target, "output") {
            return;
        }
        let lua = scripts.host().lua().clone();
        let payload = codecs::WasmOutputEvent.push(
            &lua,
            shapes::WasmOutputEvent {
                process: handle.clone(),
                line,
                stream: stream.to_owned(),
            },
        );
        if let Ok(Value::Table(fields)) = payload {
            scripts.raise(&target, "output", fields);
        }
    }

    /// Ends program `number`: its thread is stopped, what it printed but wasn't handed over is dropped, then its
    /// `end` handlers hear it while the handle still answers, and it's gone with every handler.
    pub fn end(
        &self,
        number: i64,
        reason: ProgramEnd,
        exit_code: Option<i32>,
        detail: Option<String>,
    ) -> bool {
        let handle = {
            let mut wasm = self.shared().wasm.borrow_mut();
            let Some(program) = wasm.programs.get_mut(&number).filter(|p| !p.ended) else {
                return false;
            };
            program.ended = true;
            program.shared.stop();
            program.handle.clone()
        };
        let target = self.target(Handle::WasmProcess(handle.clone()));
        if let Some(target) = &target {
            let scripts = &self.game.scripts;
            let lua = scripts.host().lua().clone();
            let payload = codecs::WasmEndEvent.push(
                &lua,
                shapes::WasmEndEvent {
                    process: handle,
                    reason: reason.name().to_owned(),
                    exit_code: exit_code.map(i64::from),
                    detail,
                },
            );
            if let Ok(Value::Table(fields)) = payload {
                scripts.raise(target, "end", fields);
            }
        }
        self.shared()
            .wasm
            .borrow_mut()
            .programs
            .shift_remove(&number);
        if let Some(target) = &target {
            self.game.scripts.drop_target(target);
        }
        true
    }

    /// Ends every program and closes every instance `which` scope made; what they ran is no longer theirs.
    pub fn release_where(&self, which: impl Fn(ScopeId) -> bool) {
        let programs: Vec<i64> = self
            .shared()
            .wasm
            .borrow()
            .programs
            .values()
            .filter(|p| which(p.owner))
            .map(|p| p.number)
            .collect();
        for number in programs {
            self.end(number, ProgramEnd::Unloaded, None, None);
        }
        let mut wasm = self.shared().wasm.borrow_mut();
        let instances: Vec<Arc<LiveInstance>> = wasm
            .instances
            .values()
            .filter(|i| which(i.owner))
            .cloned()
            .collect();
        for instance in instances {
            instance.close();
            wasm.instances.shift_remove(&instance.number);
        }
        wasm.stand_ins.retain(|_, s| !which(s.owner));
        // A test's scope closed: what it stood in with goes.
        wasm.stubs.retain(|_, (scope, _)| !which(*scope));
        for scopes in wasm.users.values_mut() {
            scopes.retain(|s| !which(*s));
        }
    }

    /// Tells `scripts` which instance and program handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let wasm = shared.wasm.try_borrow().ok()?;
            match handle {
                Handle::WasmInstance(h) => Some(
                    wasm.instances.get(&h.number).is_some_and(|i| !i.closed())
                        || wasm.stand_ins.contains_key(&h.number),
                ),
                Handle::WasmProcess(h) => Some(wasm.programs.contains_key(&h.number)),
                _ => None,
            }
        }));
    }

    fn program_open(&self, number: i64) -> Option<Arc<ProgramShared>> {
        self.shared()
            .wasm
            .borrow()
            .programs
            .get(&number)
            .filter(|p| !p.ended)
            .map(|p| p.shared.clone())
    }
}

/// What a call answered: its numbers, or its text.
pub enum Answer {
    Numbers(Vec<Number>),
    Text(String),
}

/// Counts an instantiation in flight for its package until it's dropped (done, or its task gone).
struct Instantiating {
    shared: Weak<Shared>,
    namespace: String,
}

impl Drop for Instantiating {
    fn drop(&mut self) {
        if let Some(shared) = self.shared.upgrade()
            && let Ok(mut wasm) = shared.wasm.try_borrow_mut()
            && let Some(count) = wasm.instantiating.get_mut(&self.namespace)
        {
            *count = count.saturating_sub(1);
        }
    }
}

impl NfWasmApi for WasmApi {
    fn functions(&self, caller: &Caller, module: String) -> ApiResult<Vec<shapes::WasmFunction>> {
        let key = self.resolve(caller.scope, &module, "nf.wasm.functions")?;
        let wasm = self.shared().wasm.borrow();
        let Some(Some(Ok(compiled))) = wasm.compiled.get(&key).map(|c| c.peek()) else {
            return Ok(Vec::new());
        };
        Ok(compiled
            .functions
            .iter()
            .map(|(name, signature)| shapes::WasmFunction {
                name: name.clone(),
                params: signature
                    .params
                    .iter()
                    .map(|t| t.name().to_owned())
                    .collect(),
                results: signature
                    .results
                    .iter()
                    .map(|t| t.name().to_owned())
                    .collect(),
            })
            .collect())
    }

    fn instantiate(
        &self,
        caller: &Caller,
        module: String,
    ) -> ApiResult<Pending<handles::WasmInstance>> {
        let key = self.resolve(caller.scope, &module, "nf.wasm.instantiate")?;
        let namespace = self.namespace(caller.scope);
        if self.standing_in(&key) {
            // Made at the next tick, as a real one is later; it holds nothing, but counts as one.
            let mut wasm = self.shared().wasm.borrow_mut();
            let most = wasm.config.max_instances_per_package;
            if wasm.held_by(&namespace) >= most {
                return Ok(refused(format!(
                    "package \"{namespace}\" already holds {most} WebAssembly instances, the server's limit (wasm.max-instances-per-package in config.yml): close some"
                )));
            }
            let number = wasm.next_number();
            wasm.stand_ins.insert(
                number,
                super::StandIn {
                    owner: caller.scope,
                    namespace,
                    key,
                },
            );
            return Ok(Box::pin(
                async move { Ok(handles::WasmInstance { number }) },
            ));
        }
        let mut wasm = self.shared().wasm.borrow_mut();
        let most = wasm.config.max_instances_per_package;
        if wasm.held_by(&namespace) >= most {
            return Ok(refused(format!(
                "package \"{namespace}\" already holds {most} WebAssembly instances, the server's limit (wasm.max-instances-per-package in config.yml): close some"
            )));
        }
        let threads = match wasm.threads() {
            Ok(threads) => threads,
            Err(e) => return Ok(refused(format!("the server can't run WebAssembly: {e}"))),
        };
        let compile = wasm.compiled.get(&key).cloned();
        *wasm.instantiating.entry(namespace.clone()).or_default() += 1;
        let counted = Instantiating {
            shared: Rc::downgrade(self.shared()),
            namespace: namespace.clone(),
        };
        let number = wasm.next_number();
        let budget = wasm.budget();
        let config = wasm.config.clone();
        let job_key = key.clone();
        let guard = threads.guard();
        let reply = wasm.submit(&namespace, On::Pool, move || {
            let what = format!("instantiating module \"{job_key}\"");
            let module = await_compiled(&job_key, compile.as_deref())?;
            let reservation = budget
                .reserve(config.max_pages())
                .ok_or_else(|| config.memory_taken())?;
            // Its start function runs as it's made, and a reactor (a library built for WASI) sets itself up once,
            // before its first call: both held to the time a call has.
            let running = guard
                .limited(config.max_call_seconds, || {
                    let mut running = module.instantiate(
                        Io::quiet(&job_key),
                        config.max_pages(),
                        guard.clone(),
                        reservation,
                    )?;
                    if module.signature("_initialize").is_some() {
                        run_export(&mut running, "_initialize")?;
                    }
                    Ok(running)
                })
                .map_err(|e| match e {
                    None => config.too_long(&what),
                    Some(f) => failed(f, &what),
                })?;
            Ok((module, guard, running))
        });
        drop(wasm);
        let (shared, scripts, scope) = (
            Rc::downgrade(self.shared()),
            self.game.scripts.downgrade(),
            caller.scope,
        );
        let pool = threads.pool.clone();
        Ok(Box::pin(async move {
            let _counted = counted;
            let (module, guard, running) = reply.await.map_err(ApiError)?;
            let (Some(shared), Some(scripts)) = (shared.upgrade(), scripts.upgrade()) else {
                return Err(err("the script that made it has unloaded"));
            };
            if !scripts.is_live(scope) {
                return Err(err("the script that made it has unloaded"));
            }
            let instance = Arc::new(LiveInstance {
                number,
                owner: scope,
                namespace,
                key,
                module,
                guard,
                held: std::sync::Mutex::new(Some(running)),
                lane: super::threads::Lane::new(pool),
                closed: Default::default(),
            });
            shared.wasm.borrow_mut().instances.insert(number, instance);
            Ok(handles::WasmInstance { number })
        }))
    }

    fn run(
        &self,
        caller: &Caller,
        module: String,
        options: Option<shapes::WasmRunOptions>,
    ) -> ApiResult<Pending<shapes::WasmRunResult>> {
        let key = self.resolve(caller.scope, &module, "nf.wasm.run")?;
        let options = options.unwrap_or(shapes::WasmRunOptions {
            stdin: None,
            arguments: None,
            environment: None,
        });
        let environment = Self::environment(options.environment)?;
        if self.standing_in(&key) {
            let (stdin, arguments) = (options.stdin, options.arguments.unwrap_or_default());
            let reply = self.stood_in(&key, move |scripts, scope, stub| {
                let Some(run) = &stub.run else {
                    return Err("the stand-in has no run".into());
                };
                let lua = scripts.host().lua().clone();
                let given = (|| -> mlua::Result<mlua::Table> {
                    let given = lua.create_table()?;
                    if let Some(stdin) = &stdin {
                        given.raw_set("stdin", stdin.as_str())?;
                    }
                    given.raw_set("arguments", lua.create_sequence_from(arguments.iter().map(String::as_str))?)?;
                    let table = lua.create_table()?;
                    for (name, value) in &environment {
                        table.raw_set(name.as_str(), value.as_str())?;
                    }
                    given.raw_set("environment", table)?;
                    Ok(given)
                })()
                .map_err(|e| e.to_string())?;
                let answer = match scripts.call_kept(scope, run, given) {
                    Ok(answer) => answer,
                    Err(failure) => return Err(format!("the stand-in's run failed: {failure}")),
                };
                if answer.is_nil() {
                    return Err(
                        "the stand-in's run returned nothing: return { exit_code = 0, stdout = \"\", stderr = \"\" }"
                            .into(),
                    );
                }
                codecs::WasmRunResult
                    .read(&lua, answer, "stub.run")
                    .map_err(|e| format!("the stand-in's run failed: {e}"))
            });
            return Ok(Box::pin(async move { reply.await.map_err(ApiError) }));
        }
        let namespace = self.namespace(caller.scope);
        let mut wasm = self.shared().wasm.borrow_mut();
        let compile = wasm.compiled.get(&key).cloned();
        let budget = wasm.budget();
        let config = wasm.config.clone();
        let guard = match wasm.threads() {
            Ok(threads) => threads.guard(),
            Err(e) => return Ok(refused(format!("the server can't run WebAssembly: {e}"))),
        };
        let (stdin, arguments) = (
            options.stdin.unwrap_or_default(),
            options.arguments.unwrap_or_default(),
        );
        let reply = wasm.submit(&namespace, On::Pool, move || {
            let module = await_compiled(&key, compile.as_deref())?;
            if !module.command() {
                return Err(format!(
                    "module \"{key}\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
                ));
            }
            let reservation = budget
                .reserve(config.max_pages())
                .ok_or_else(|| config.memory_taken())?;
            let stdout = super::program::CappedOutput::new(config.max_output_bytes);
            let stderr = super::program::CappedOutput::new(config.max_output_bytes);
            let io = Io {
                name: key.clone(),
                stdin: Box::new(super::wasi::GivenInput {
                    bytes: stdin.into_bytes(),
                    at: 0,
                }),
                stdout: Box::new(stdout.clone()),
                stderr: Box::new(stderr.clone()),
                arguments,
                environment,
            };
            let what = format!("module \"{key}\"");
            // Made and run within the one time limit: its start function runs as it's made.
            let code = guard
                .limited(config.max_call_seconds, || {
                    let mut running = module.instantiate(io, config.max_pages(), guard.clone(), reservation)?;
                    exit_code(run_export(&mut running, netherforge_format::wasm::COMMAND_ENTRY))
                })
                .map_err(|e| match e {
                    None => config.too_long(&what),
                    Some(f) => failed(f, &what),
                })?;
            Ok(shapes::WasmRunResult {
                exit_code: i64::from(code),
                stdout: stdout.text(),
                stderr: stderr.text(),
            })
        });
        Ok(Box::pin(async move { reply.await.map_err(ApiError) }))
    }

    fn start(
        &self,
        caller: &Caller,
        module: String,
        options: Option<shapes::WasmStartOptions>,
    ) -> ApiResult<handles::WasmProcess> {
        let key = self.resolve(caller.scope, &module, "nf.wasm.start")?;
        let options = options.unwrap_or(shapes::WasmStartOptions {
            arguments: None,
            environment: None,
        });
        let environment = Self::environment(options.environment)?;
        let arguments = options.arguments.unwrap_or_default();
        let namespace = self.namespace(caller.scope);
        let stand_in = self.standing_in(&key);
        let mut wasm = self.shared().wasm.borrow_mut();
        let number = wasm.next_number();
        let handle = handles::WasmProcess {
            number,
            module: key.clone(),
        };
        let config = wasm.config.clone();
        let threads = wasm.threads();
        let guard = threads
            .as_ref()
            .map_or_else(|_| Arc::default(), |t| t.guard());
        let shared = ProgramShared::new(config.max_queued_lines, config.max_line_bytes, guard);
        let running: Vec<&Program> = wasm.programs.values().filter(|p| !p.ended).collect();
        let refusal = if running.iter().filter(|p| p.namespace == namespace).count()
            >= config.max_processes_per_package
        {
            Some(format!(
                "package \"{namespace}\" already has {} WebAssembly programs running, the server's limit (wasm.max-processes-per-package in config.yml)",
                config.max_processes_per_package
            ))
        } else if running.len() >= config.max_processes_total {
            Some(format!(
                "the server already has {} WebAssembly programs running, its limit (wasm.max-processes-total in config.yml)",
                config.max_processes_total
            ))
        } else {
            None
        };
        wasm.programs.insert(
            number,
            Program {
                number,
                handle: handle.clone(),
                owner: caller.scope,
                namespace,
                key: key.clone(),
                shared: shared.clone(),
                ended: false,
                stand_in: stand_in.then_some(false),
            },
        );
        if let Some(why) = refusal {
            shared.ended(ProgramEnd::Limit, None, Some(why));
            return Ok(handle);
        }
        if stand_in {
            // Nothing runs: the test's `input` answers what it's sent, each tick. It can go no further on its own.
            shared
                .finished
                .store(true, std::sync::atomic::Ordering::SeqCst);
            return Ok(handle);
        }
        if let Err(e) = threads {
            shared.ended(
                ProgramEnd::Failed,
                None,
                Some(format!("the server can't run WebAssembly: {e}")),
            );
            return Ok(handle);
        }
        let compile = wasm.compiled.get(&key).cloned();
        let budget = wasm.budget();
        drop(wasm);
        let thread = shared.clone();
        let spawned = std::thread::Builder::new()
            .name(format!("NetherForge wasm program {number}"))
            .stack_size(8 * 1024 * 1024)
            .spawn(move || {
                let shared = thread;
                let ending = run_program(
                    &shared,
                    &key,
                    compile.as_deref(),
                    &budget,
                    &config,
                    arguments,
                    environment,
                );
                if let ProgramOutput::Ended {
                    reason,
                    exit_code,
                    detail,
                } = ending
                {
                    shared.ended(reason, exit_code, detail);
                }
            });
        if spawned.is_err() {
            shared.ended(
                ProgramEnd::Failed,
                None,
                Some("the server is stopping".into()),
            );
        }
        Ok(handle)
    }
}

/// A program's run on its own thread: how it ended.
fn run_program(
    shared: &Arc<ProgramShared>,
    key: &str,
    compile: Option<&super::Compile>,
    budget: &Arc<super::threads::Budget>,
    config: &super::WasmConfig,
    arguments: Vec<String>,
    environment: Vec<(String, String)>,
) -> ProgramOutput {
    let ended = |reason, exit_code, detail| ProgramOutput::Ended {
        reason,
        exit_code,
        detail,
    };
    let module = match await_compiled(key, compile) {
        Ok(module) => module,
        Err(why) => return ended(ProgramEnd::Failed, None, Some(why)),
    };
    if !module.command() {
        return ended(
            ProgramEnd::Failed,
            None,
            Some(format!(
                "module \"{key}\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
            )),
        );
    }
    let Some(reservation) = budget.reserve(config.max_pages()) else {
        return ended(ProgramEnd::Limit, None, Some(config.memory_taken()));
    };
    let stdout = Arc::new(std::sync::Mutex::new(LineOutput::new(
        shared.clone(),
        "stdout",
    )));
    let stderr = Arc::new(std::sync::Mutex::new(LineOutput::new(
        shared.clone(),
        "stderr",
    )));
    let io = Io {
        name: key.to_owned(),
        stdin: Box::new(ProgramInput(shared.clone())),
        stdout: Box::new(SharedOutput(stdout.clone())),
        stderr: Box::new(SharedOutput(stderr.clone())),
        arguments,
        environment,
    };
    let result = module
        .instantiate(io, config.max_pages(), shared.guard.clone(), reservation)
        .and_then(|mut running| {
            let code = exit_code(run_export(
                &mut running,
                netherforge_format::wasm::COMMAND_ENTRY,
            ));
            let finish = |out: &std::sync::Mutex<LineOutput>| {
                out.lock().unwrap_or_else(|e| e.into_inner()).finish()
            };
            let finished = finish(&stdout).and_then(|()| finish(&stderr));
            match (code, finished) {
                (Ok(code), Ok(())) => Ok(code),
                (Err(e), _) => Err(e),
                (Ok(_), Err(e)) => Err(e.into()),
            }
        });
    match result {
        Ok(code) => ended(ProgramEnd::Exited, Some(code), None),
        // Stopped from the main thread, which says why itself.
        Err(_) if shared.guard.closed() => ended(ProgramEnd::Stopped, None, None),
        Err(f) => ended(
            ProgramEnd::Failed,
            None,
            Some(failed(f, &format!("module \"{key}\""))),
        ),
    }
}

impl WasmInstanceApi for WasmApi {
    fn module(&self, this: &handles::WasmInstance) -> ApiResult<String> {
        let key = {
            let wasm = self.shared().wasm.borrow();
            wasm.instances
                .get(&this.number)
                .map(|i| i.key.clone())
                .or_else(|| wasm.stand_ins.get(&this.number).map(|s| s.key.clone()))
        }
        .ok_or_else(|| err("the instance's script has unloaded"))?;
        Ok(self.spell(&key))
    }

    fn call(
        &self,
        this: &handles::WasmInstance,
        name: String,
        arguments: Value,
    ) -> ApiResult<Pending<Vec<Value>>> {
        let stand_in = self
            .shared()
            .wasm
            .borrow()
            .stand_ins
            .get(&this.number)
            .cloned();
        if let Some(stand_in) = stand_in {
            let reply = self.stand_in_call(&stand_in, &name, &arguments)?;
            return Ok(Box::pin(async move {
                let answer = reply.await.map_err(ApiError)?;
                stub_values(answer, &name).map_err(ApiError)
            }));
        }
        let reply = WasmApi::call(self, this, &name, &arguments, false)?;
        let lua = self.game.scripts.host().lua().weak();
        Ok(Box::pin(async move {
            let Answer::Numbers(numbers) = reply.await.map_err(ApiError)? else {
                return Err(err("it didn't answer with numbers"));
            };
            let lua = lua
                .try_upgrade()
                .ok_or_else(|| err("the server is stopping"))?;
            numbers
                .into_iter()
                .map(|n| match n {
                    Number::Whole(n) if n.abs() <= SAFE => Ok(Value::Number(n as f64)),
                    Number::Whole(n) => lua
                        .create_string(n.to_string())
                        .map(Value::String)
                        .map_err(|e| err(e.to_string())),
                    Number::Real(n) => Ok(Value::Number(n)),
                })
                .collect()
        }))
    }

    fn call_text(
        &self,
        this: &handles::WasmInstance,
        name: String,
        arguments: Value,
    ) -> ApiResult<Pending<String>> {
        let stand_in = self
            .shared()
            .wasm
            .borrow()
            .stand_ins
            .get(&this.number)
            .cloned();
        if let Some(stand_in) = stand_in {
            let reply = self.stand_in_call(&stand_in, &name, &arguments)?;
            return Ok(Box::pin(async move {
                match reply.await.map_err(ApiError)? {
                    Value::String(text) => Ok(text.to_string_lossy()),
                    other => Err(err(format!(
                        "the stand-in's {name} returned {}, not text",
                        stub_kind(&other)
                    ))),
                }
            }));
        }
        let reply = WasmApi::call(self, this, &name, &arguments, true)?;
        Ok(Box::pin(async move {
            match reply.await.map_err(ApiError)? {
                Answer::Text(text) => Ok(text),
                Answer::Numbers(_) => Err(err("it didn't answer with text")),
            }
        }))
    }

    fn close(&self, this: &handles::WasmInstance) -> ApiResult<bool> {
        if self
            .shared()
            .wasm
            .borrow_mut()
            .stand_ins
            .shift_remove(&this.number)
            .is_some()
        {
            return Ok(true);
        }
        let instance = self
            .shared()
            .wasm
            .borrow()
            .instances
            .get(&this.number)
            .cloned();
        Ok(instance.is_some_and(|i| i.close()))
    }
}

impl WasmProcessApi for WasmApi {
    fn module(&self, this: &handles::WasmProcess) -> ApiResult<String> {
        Ok(self.spell(&this.module))
    }

    fn state(&self, this: &handles::WasmProcess) -> ApiResult<String> {
        Ok(match self.program_open(this.number) {
            Some(_) => "running",
            None => "ended",
        }
        .into())
    }

    fn send(&self, this: &handles::WasmProcess, line: String) -> ApiResult<bool> {
        let max = self.shared().wasm.borrow().config.max_line_bytes;
        if line.len() > max {
            return Err(err(format!(
                "the line is {} bytes, over the server's limit of {max} (wasm.max-line-bytes in config.yml)",
                line.len()
            )));
        }
        if line.contains('\n') {
            return Err(err(
                "send takes one line: it has a line break in it (send each line on its own)",
            ));
        }
        Ok(self
            .program_open(this.number)
            .is_some_and(|p| p.send(&line)))
    }

    fn close_input(&self, this: &handles::WasmProcess) -> ApiResult<bool> {
        Ok(self.program_open(this.number).is_some_and(|p| p.finish()))
    }

    fn stop(&self, this: &handles::WasmProcess) -> ApiResult<bool> {
        Ok(self.end(this.number, ProgramEnd::Stopped, None, None))
    }

    fn on(
        &self,
        this: &handles::WasmProcess,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::WasmProcess(this.clone()),
            "WasmProcess:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::WasmProcess,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::WasmProcess(this.clone()),
            "WasmProcess:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// WebAssembly's service: compiles the project's modules (again as they change), hands programs' lines to
/// scripts each tick, ends what a stopped scope ran, and says what didn't compile.
pub struct WasmService {
    pub shared: Rc<Shared>,
    pub api: std::cell::RefCell<Option<WasmApi>>,
}

/// A module file's bytes by project path (a package's `ns:path`): the session's reader, or the source's own.
fn bytes_of(define: &Define, sources: &super::Sources, path: &str) -> Option<Vec<u8>> {
    if let Some(read) = &sources.bytes
        && let Some(bytes) = (read.0)(path)
    {
        return Some(bytes);
    }
    match netherforge_format::kinds::split_package(path) {
        (Some(namespace), local) => define
            .project
            .packages
            .get(namespace)?
            .resolved
            .source
            .bytes(local),
        (None, local) => define.source.bytes(local),
    }
}

impl WasmService {
    /// Every module that read, and a compile of each that has no errors (`only`: just these).
    fn define(&self, define: &Define, only: Option<&BTreeSet<String>>) {
        use netherforge_format::kinds::{package_path, split_package};
        let present = define.project.everywhere("wasm");
        let mut wasm = self.shared.wasm.borrow_mut();
        let sources = wasm.sources.clone();
        wasm.known = present
            .iter()
            .map(|(key, resource)| {
                let found = &resource.found;
                let path = found.kind.path_of(&found.id, &found.group);
                let path = match split_package(key) {
                    (Some(namespace), _) => package_path(namespace, &path),
                    (None, _) => path,
                };
                (key.clone(), path)
            })
            .collect();
        for (key, resource) in &present {
            if only.is_some_and(|o| !o.contains(key)) {
                continue;
            }
            if resource.errors {
                wasm.compiled.remove(key);
                continue;
            }
            let found = &resource.found;
            let folder = found.kind.location_of(&found.id, &found.group);
            let place = |path: &str| match split_package(key) {
                (Some(namespace), _) => package_path(namespace, path),
                (None, _) => path.to_owned(),
            };
            let main = place(&format!("{folder}/{}", netherforge_format::wasm::MAIN));
            let mut data = BTreeMap::new();
            for name in found
                .inside
                .iter()
                .filter(|n| *n != netherforge_format::wasm::MAIN)
            {
                let path = place(&format!("{folder}/{name}"));
                let file = match sources.paths.as_ref().and_then(|p| (p.0)(&path)) {
                    Some(disk) => Some(DataFile::Disk(disk)),
                    None => bytes_of(define, &sources, &path).map(|b| DataFile::Bytes(b.into())),
                };
                if let Some(file) = file {
                    data.insert(name.clone(), file);
                }
            }
            let bytes = bytes_of(define, &sources, &main);
            wasm.compile(key, bytes, WasmData::new(data));
        }
        if let Some(only) = only {
            for key in only {
                if !wasm.known.contains_key(key) {
                    wasm.compiled.remove(key);
                }
            }
        }
    }

    fn api(&self) -> Option<WasmApi> {
        self.api.borrow().clone()
    }
}

impl Service for WasmService {
    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        let wasm = self.shared.wasm.borrow();
        let instances = wasm
            .instances
            .values()
            .filter(|i| i.owner == scope && !i.closed())
            .count();
        let programs = wasm.programs.values().filter(|p| p.owner == scope).count();
        vec![("wasm instances", instances), ("wasm programs", programs)]
    }

    fn name(&self) -> &'static str {
        "wasm"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["wasm"]
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.api.borrow_mut() = Some(WasmApi {
            game: GameContext::new(self.shared.clone(), room.scripts().clone()),
        });
        self.define(project, None);
    }

    fn restarts(&self, kind: &str, ids: &BTreeSet<String>) -> Vec<(&'static str, String)> {
        if kind != "wasm" {
            return Vec::new();
        }
        let users: BTreeSet<ScopeId> = {
            let mut wasm = self.shared.wasm.borrow_mut();
            ids.iter()
                .filter_map(|id| wasm.users.remove(id))
                .flatten()
                .collect()
        };
        let Some(modules) = self.shared.modules.borrow().clone() else {
            return Vec::new();
        };
        let mut out = Vec::new();
        for scope in users {
            match modules.owner(scope) {
                Some(crate::Owner::Module(id)) => out.push(("module", id)),
                Some(crate::Owner::Script(file)) => {
                    if let Some(found) = netherforge_format::kinds::classify(&file)
                        && let (Some(kind), Some(id), None) = (found.kind, found.id, found.package)
                    {
                        out.push((kind, id));
                    }
                }
                None => {}
            }
        }
        out
    }

    fn reload(&self, _room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        self.define(project, Some(ids));
    }

    fn tick(&self, _room: &Room, platform: &dyn Platform) {
        let (failures, warnings, programs) = {
            let mut wasm = self.shared.wasm.borrow_mut();
            let warnings = std::mem::take(&mut wasm.warnings);
            (wasm.newly_failed(), warnings, !wasm.programs.is_empty())
        };
        for warning in warnings {
            platform.log().warn(warning);
        }
        for (key, why) in failures {
            platform.log().warn(format!(
                "WebAssembly module \"{key}\" couldn't be compiled: {why}"
            ));
        }
        if programs && let Some(api) = self.api() {
            api.answer_stand_ins();
            api.deliver();
        }
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        if let Some(api) = self.api() {
            api.release_where(|owner| scopes.contains(&owner));
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        if let Some(api) = self.api.borrow_mut().take() {
            api.release_where(|_| true);
        }
        let mut wasm = self.shared.wasm.borrow_mut();
        wasm.close();
        let (config, sources) = (wasm.config.clone(), wasm.sources.clone());
        *wasm = Wasm {
            config,
            sources,
            ..Wasm::default()
        };
    }

    fn problems(&self) -> Vec<netherforge_format::Problem> {
        let wasm = self.shared.wasm.borrow();
        let mut failed: Vec<(&String, String)> = wasm
            .compiled
            .iter()
            .filter_map(|(key, compile)| match compile.peek() {
                Some(Err(why)) => Some((key, why)),
                _ => None,
            })
            .collect();
        failed.sort();
        failed
            .into_iter()
            .map(|(key, why)| {
                let place = wasm
                    .known
                    .get(key)
                    .cloned()
                    .unwrap_or_else(|| format!("wasm/{key}"));
                netherforge_format::codes::RUNTIME_WASM.at(
                    &place,
                    format!("The server couldn't compile module \"{key}\": {why}"),
                    None,
                )
            })
            .collect()
    }
}

/// What a stand-in's function returned when called with `arguments`, as the test's code; its failure named `what`.
fn stub_answer(
    scripts: &crate::Scripts,
    scope: ScopeId,
    function: &Function,
    arguments: Value,
    what: &str,
) -> Result<Value, String> {
    if !scripts.is_live(scope) {
        return Err("the test that stood in for the module has ended".into());
    }
    scripts
        .call_kept(scope, function, arguments)
        .map_err(|failure| format!("the stand-in's {what} failed: {failure}"))
}

/// The lines a stand-in program's `input` printed for `line`.
fn stub_lines(
    scripts: &crate::Scripts,
    scope: ScopeId,
    input: &Function,
    line: String,
    process: Value,
) -> Result<Vec<String>, String> {
    if !scripts.is_live(scope) {
        return Err("the test that stood in for the module has ended".into());
    }
    let answer = scripts
        .call_kept(scope, input, (line, process))
        .map_err(|failure| format!("the stand-in's input failed: {failure}"))?;
    match answer {
        Value::Nil => Ok(Vec::new()),
        Value::Table(lines) => lines
            .sequence_values::<Value>()
            .map(|v| match v {
                Ok(Value::String(s)) => Ok(s.to_string_lossy()),
                _ => {
                    Err("the stand-in's input returned something that isn't a list of lines".into())
                }
            })
            .collect(),
        _ => Err("the stand-in's input returned something that isn't a list of lines".into()),
    }
}

/// A stand-in function's answer as `call` gives it: a list of numbers.
fn stub_values(answer: Value, name: &str) -> Result<Vec<Value>, String> {
    let Value::Table(list) = answer else {
        return Err(format!(
            "the stand-in's {name} returned {}, not a list of results",
            stub_kind(&answer)
        ));
    };
    list.sequence_values::<Value>()
        .map(|v| match v {
            Ok(n @ (Value::Integer(_) | Value::Number(_))) => Ok(n),
            _ => Err(format!(
                "the stand-in's {name} returned a result that isn't a number"
            )),
        })
        .collect()
}

/// What a stand-in's answer is, for a mistake.
fn stub_kind(answer: &Value) -> &'static str {
    match answer {
        Value::Nil => "nothing",
        Value::String(_) => "text",
        Value::Table(_) => "a table",
        _ => "a value",
    }
}
