//! Calling a module's exported functions (Kotlin's `WasmCalls`): `arguments` reads a script's `args` and checks
//! them against the function on the main thread, so a mistake is an error at the script's line; `call` passes them
//! and reads what came back on the instance's thread. Text crosses through the module's memory: copied in with its
//! `alloc` (or `malloc`), freed after with its `dealloc(pointer, size)` (or `free(pointer)`), and read back from a
//! pointer and a length.
//!
//! Numbers under Luau are doubles: a whole number in the safe range is an integer argument, anything else a real
//! one (so `2.0` passes for an `i32`, as `2` does). An `i64` result outside the safe range comes back as a string of
//! its digits, as a database's does, so a script never holds one that lost its low bits.

use mlua::Value;
use netherforge_api::{ApiError, ApiResult};
use wasmtime::{Store, Val};

use super::compiled::{CompiledModule, Signature, Ty};
use super::wasi::StoreData;

const MAX_ARGUMENTS: usize = 64;
/// The largest whole number a double holds exactly.
const SAFE: f64 = 9_007_199_254_740_991.0;
const ALLOCATORS: [&str; 2] = ["alloc", "malloc"];

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// An argument to an exported function as a script gave it, read on the main thread.
#[derive(Clone, Debug, PartialEq)]
pub enum Argument {
    Whole(i64),
    Real(f64),
    /// Copied into the module's memory and passed as where it starts and how many bytes it is.
    Text(Vec<u8>),
}

/// A result a script takes.
#[derive(Clone, Debug, PartialEq)]
pub enum Number {
    Whole(i64),
    Real(f64),
}

fn describe(params: &[Ty]) -> String {
    if params.is_empty() {
        "no arguments".into()
    } else {
        let names: Vec<&str> = params.iter().map(|t| t.name()).collect();
        format!("({})", names.join(", "))
    }
}

/// The list at `table`: numbers and strings, with no holes.
fn read(table: &Value, what: &str) -> ApiResult<Vec<Argument>> {
    let table = match table {
        Value::Nil => return Ok(Vec::new()),
        Value::Table(t) => t,
        other => {
            return Err(err(format!(
                "{what} is a {}: give a list of the arguments",
                other.type_name()
            )));
        }
    };
    let mut found = std::collections::BTreeMap::new();
    for pair in table.pairs::<Value, Value>() {
        let (key, value) = pair.map_err(|e| err(e.to_string()))?;
        let position = match key {
            Value::Integer(n) => n as f64,
            Value::Number(n) => n,
            _ => 0.0,
        };
        if position.fract() != 0.0 || position < 1.0 || position > MAX_ARGUMENTS as f64 {
            return Err(err(format!(
                "{what} is a list of at most {MAX_ARGUMENTS} values, but it has a key that isn't a position in one"
            )));
        }
        let position = position as usize;
        let argument = match value {
            Value::Integer(n) => Argument::Whole(n),
            Value::Number(n) if n.fract() == 0.0 && n.abs() <= SAFE => Argument::Whole(n as i64),
            Value::Number(n) => Argument::Real(n),
            Value::String(s) => Argument::Text(s.as_bytes().to_vec()),
            other => {
                return Err(err(format!(
                    "args[{position}] is a {}: a module takes integers, numbers and strings",
                    other.type_name()
                )));
            }
        };
        found.insert(position, argument);
    }
    (1..=found.len())
        .map(|i| {
            found.remove(&i).ok_or_else(|| {
                err(format!(
                    "{what} has a hole at {i}: give every argument in order"
                ))
            })
        })
        .collect()
}

fn allocator(module: &CompiledModule) -> Option<&'static str> {
    let one = Signature {
        params: vec![Ty::I32],
        results: vec![Ty::I32],
    };
    ALLOCATORS
        .into_iter()
        .find(|name| module.signature(name) == Some(&one))
}

fn float_text(n: f64) -> String {
    netherforge_luau::json::number(n).unwrap_or_else(|| n.to_string())
}

/// `table` (the script's `args`) checked against `name`'s `signature` in `module`.
pub fn arguments(
    module: &CompiledModule,
    name: &str,
    signature: &Signature,
    table: &Value,
) -> ApiResult<Vec<Argument>> {
    let given = read(table, &format!("{name}'s args"))?;
    let params = &signature.params;
    let mut at = 0;
    for (index, argument) in given.iter().enumerate() {
        let place = format!("args[{}]", index + 1);
        let Some(param) = params.get(at).copied() else {
            return Err(err(format!(
                "{name} takes {}, but it was given more arguments ({place} is one too many)",
                describe(params)
            )));
        };
        match argument {
            Argument::Text(_) => {
                if param != Ty::I32 || params.get(at + 1) != Some(&Ty::I32) {
                    return Err(err(format!(
                        "{place} is a string, which is passed as two i32s (where it starts and its size), but {name} takes {} there",
                        param.name()
                    )));
                }
                if allocator(module).is_none() {
                    return Err(err(format!(
                        "{place} is a string, but module \"{}\" exports no alloc(size) or malloc(size) to copy it into",
                        module.key
                    )));
                }
                at += 2;
            }
            Argument::Whole(value) => {
                match param {
                    Ty::I32 => {
                        if !(i64::from(i32::MIN)..=0xFFFF_FFFF).contains(value) {
                            return Err(err(format!(
                                "{place} is {value}, which doesn't fit {name}'s i32 there"
                            )));
                        }
                    }
                    Ty::I64 | Ty::F32 | Ty::F64 => {}
                    other => {
                        return Err(err(format!(
                            "{name} takes a {} at {place}, which a script can't pass",
                            other.name()
                        )));
                    }
                }
                at += 1;
            }
            Argument::Real(value) => {
                match param {
                    Ty::F32 | Ty::F64 => {}
                    Ty::I32 | Ty::I64 => {
                        return Err(err(format!(
                            "{place} is {}, but {name} takes an {} there: give an integer",
                            float_text(*value),
                            param.name()
                        )));
                    }
                    other => {
                        return Err(err(format!(
                            "{name} takes a {} at {place}, which a script can't pass",
                            other.name()
                        )));
                    }
                }
                at += 1;
            }
        }
    }
    if at != params.len() {
        return Err(err(format!(
            "{name} takes {}, but it was given fewer arguments",
            describe(params)
        )));
    }
    Ok(given)
}

fn val(ty: Ty, argument: &Argument) -> Val {
    match (ty, argument) {
        (Ty::I32, Argument::Whole(v)) => Val::I32(*v as i32),
        (Ty::I64, Argument::Whole(v)) => Val::I64(*v),
        (Ty::F32, Argument::Whole(v)) => Val::F32((*v as f32).to_bits()),
        (Ty::F64, Argument::Whole(v)) => Val::F64((*v as f64).to_bits()),
        (Ty::F32, Argument::Real(v)) => Val::F32((*v as f32).to_bits()),
        (_, Argument::Real(v)) => Val::F64(v.to_bits()),
        _ => Val::I32(0),
    }
}

fn zero(ty: Ty) -> Val {
    match ty {
        Ty::I64 => Val::I64(0),
        Ty::F32 => Val::F32(0),
        Ty::F64 => Val::F64(0),
        Ty::V128 => Val::V128(0u128.into()),
        Ty::FuncRef => Val::FuncRef(None),
        Ty::ExternRef => Val::ExternRef(None),
        _ => Val::I32(0),
    }
}

/// Calls the export `name` with raw values; its results.
fn invoke(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
    name: &str,
    signature: &Signature,
    params: &[Val],
) -> Result<Vec<Val>, wasmtime::Error> {
    let func = instance
        .get_func(&mut *store, name)
        .ok_or_else(|| wasmtime::Error::msg(format!("it exports no function \"{name}\"")))?;
    let mut results: Vec<Val> = signature.results.iter().map(|t| zero(*t)).collect();
    func.call(&mut *store, params, &mut results)?;
    Ok(results)
}

/// Why a call failed: wasmtime's error (a trap, a stop, an exit), or the runtime's own words.
#[derive(Debug)]
pub enum Failed {
    Module(wasmtime::Error),
    Said(String),
}

impl From<wasmtime::Error> for Failed {
    fn from(e: wasmtime::Error) -> Failed {
        Failed::Module(e)
    }
}

/// Calls `name` in `instance` with `arguments`, already checked, and reads what came back with `read`.
pub fn call<T>(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
    module: &CompiledModule,
    name: &str,
    arguments: &[Argument],
    read: impl FnOnce(&mut Store<StoreData>, &wasmtime::Instance, &[Val]) -> Result<T, Failed>,
) -> Result<T, Failed> {
    let signature = module
        .signature(name)
        .ok_or_else(|| Failed::Said(format!("it exports no function \"{name}\"")))?
        .clone();
    let mut copied = Vec::new();
    let mut raw = Vec::new();
    let mut at = 0;
    for argument in arguments {
        match argument {
            Argument::Text(bytes) => {
                let pointer = copy_in(store, instance, module, bytes)?;
                copied.push((pointer, bytes.len() as i32));
                raw.push(Val::I32(pointer));
                raw.push(Val::I32(bytes.len() as i32));
                at += 2;
            }
            other => {
                raw.push(val(signature.params[at], other));
                at += 1;
            }
        }
    }
    let results = invoke(store, instance, name, &signature, &raw)?;
    // Only after a call that returned: one that trapped or was stopped leaves its instance to be closed, and
    // running its code again (its free) could only fail or hide why it failed.
    for (pointer, size) in copied {
        free(store, instance, module, pointer, size)?;
    }
    read(store, instance, &results)
}

/// `results` as numbers: each `i32` and `i64` whole, each `f32` and `f64` real.
pub fn values(results: &[Val]) -> Result<Vec<Number>, Failed> {
    results
        .iter()
        .map(|v| match v {
            Val::I32(n) => Ok(Number::Whole(i64::from(*n))),
            Val::I64(n) => Ok(Number::Whole(*n)),
            Val::F32(bits) => Ok(Number::Real(f64::from(f32::from_bits(*bits)))),
            Val::F64(bits) => Ok(Number::Real(f64::from_bits(*bits))),
            _ => Err(Failed::Said(
                "it returned a value a script can't take".into(),
            )),
        })
        .collect()
}

/// Whether `signature` answers with text (two `i32`s, or one `i64` holding both): what `call_text` checks first.
pub fn answers_text(signature: &Signature) -> bool {
    signature.results == [Ty::I32, Ty::I32] || signature.results == [Ty::I64]
}

/// The text `results` point at in the instance's memory (at most `max_bytes`), then freed as an argument would be.
pub fn text(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
    module: &CompiledModule,
    results: &[Val],
    max_bytes: usize,
) -> Result<String, Failed> {
    let (pointer, size) = match results {
        [Val::I32(p), Val::I32(n)] => (*p, *n),
        [Val::I64(both)] => ((*both >> 32) as i32, *both as i32),
        _ => return Err(Failed::Said("it didn't answer with text".into())),
    };
    if size < 0 || size as usize > max_bytes {
        free(store, instance, module, pointer, size)?;
        return Err(Failed::Said(format!(
            "it answered {} bytes of text, more than a call may (wasm.max-output-bytes, {max_bytes})",
            size as u32
        )));
    }
    // Freed once read: a pointer its memory doesn't have fails the read, and isn't handed to its free.
    let memory = memory(store, instance)?;
    let bytes = memory
        .data(&*store)
        .get(pointer as u32 as usize..pointer as u32 as usize + size as usize)
        .ok_or_else(|| Failed::Said("its text runs past the end of its memory".into()))?
        .to_vec();
    free(store, instance, module, pointer, size)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn memory(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
) -> Result<wasmtime::Memory, Failed> {
    instance
        .get_memory(&mut *store, "memory")
        .ok_or_else(|| Failed::Said("the module exports no memory".into()))
}

fn copy_in(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
    module: &CompiledModule,
    bytes: &[u8],
) -> Result<i32, Failed> {
    let alloc = allocator(module)
        .ok_or_else(|| Failed::Said("the module exports no alloc(size) or malloc(size)".into()))?;
    let signature = module.signature(alloc).expect("found above").clone();
    let pointer = match invoke(
        store,
        instance,
        alloc,
        &signature,
        &[Val::I32(bytes.len() as i32)],
    )?
    .first()
    {
        Some(Val::I32(p)) => *p,
        _ => 0,
    };
    if pointer == 0 && !bytes.is_empty() {
        return Err(Failed::Said(format!(
            "{alloc}({}) gave no memory",
            bytes.len()
        )));
    }
    memory(store, instance)?
        .write(&mut *store, pointer as u32 as usize, bytes)
        .map_err(|_| {
            Failed::Said(format!(
                "{alloc}({}) gave memory it doesn't have",
                bytes.len()
            ))
        })?;
    Ok(pointer)
}

fn free(
    store: &mut Store<StoreData>,
    instance: &wasmtime::Instance,
    module: &CompiledModule,
    pointer: i32,
    size: i32,
) -> Result<(), Failed> {
    if let Some(signature) = module
        .signature("dealloc")
        .filter(|s| s.params == [Ty::I32, Ty::I32])
    {
        invoke(
            store,
            instance,
            "dealloc",
            &signature.clone(),
            &[Val::I32(pointer), Val::I32(size)],
        )?;
    } else if let Some(signature) = module.signature("free").filter(|s| s.params == [Ty::I32]) {
        invoke(
            store,
            instance,
            "free",
            &signature.clone(),
            &[Val::I32(pointer)],
        )?;
    }
    Ok(())
}
