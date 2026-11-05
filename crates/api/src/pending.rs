//! An `async` function's value, delivered later. The work runs elsewhere
//! (the runtime's pool); its future is polled at the room's tick boundary,
//! and the value reaches the script there as `value, err`: to its callback, or
//! (called without one, in a task) to the task waiting on it. Game logic never
//! runs anywhere but the room's own thread.

use std::collections::BTreeMap;
use std::task::{Context, Poll, Waker};

use mlua::{Function, Lua, Value};
use netherforge_luau::ScopeId;

use crate::Pending;
use crate::codec::Codec;

type Deliver = Box<dyn FnOnce(&Lua) -> mlua::Result<(Value, Value)>>;
type Poller = Box<dyn FnMut(&mut Context<'_>) -> Poll<Deliver>>;

struct Wait {
    scope: Option<ScopeId>,
    poll: Poller,
    callback: Option<Function>,
}

/// Every wait in a state, by id.
#[derive(Default)]
pub struct Waits {
    next: u64,
    waits: BTreeMap<u64, Wait>,
}

/// An async function's callback argument: a function, or none for its waiting form; anything else is a mistake,
/// said before any work starts.
pub fn callback(value: Option<&Value>) -> mlua::Result<Option<Function>> {
    match value {
        None | Some(Value::Nil) => Ok(None),
        Some(Value::Function(f)) => Ok(Some(f.clone())),
        Some(other) => Err(mlua::Error::runtime(format!(
            "bad argument 'callback' (function or nil expected, got {})",
            crate::codec::type_name(other)
        ))),
    }
}

/// Keeps `pending` until it's ready, then hands its value (pushed with
/// `codec`) or its error to `callback`, or, without one, to whoever waits on
/// the wait's id. Returns the id.
pub fn defer<C: Codec + 'static>(
    lua: &Lua,
    scope: Option<ScopeId>,
    mut pending: Pending<C::T>,
    codec: C,
    callback: Option<Function>,
) -> mlua::Result<u64> {
    let mut codec = Some(codec);
    let poll = Box::new(
        move |cx: &mut Context<'_>| match pending.as_mut().poll(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(result) => {
                let codec = codec.take().expect("polled once after it's ready");
                Poll::Ready(Box::new(move |lua: &Lua| match result {
                    Ok(value) => Ok((codec.push(lua, value)?, Value::Nil)),
                    Err(e) => Ok((Value::Nil, Value::String(lua.create_string(e.0)?))),
                }) as Deliver)
            }
        },
    );
    if lua.app_data_ref::<Waits>().is_none() {
        lua.set_app_data(Waits::default());
    }
    let mut waits = lua.app_data_mut::<Waits>().expect("set just now");
    waits.next += 1;
    let id = waits.next;
    waits.waits.insert(
        id,
        Wait {
            scope,
            poll,
            callback,
        },
    );
    Ok(id)
}

/// A wait whose value is ready: its id, the scope it belongs to, its callback (none: a task waits on it), and
/// `value, err`.
pub struct Ready {
    pub id: u64,
    pub scope: Option<ScopeId>,
    pub callback: Option<Function>,
    pub value: Value,
    pub err: Value,
}

/// Polls every wait once, and hands over those that are ready, in the order they were made. The room calls it
/// at each tick boundary, and calls each one's callback (through the host's guarded call, for its scope) or
/// wakes the task waiting on it.
pub fn deliver(lua: &Lua) -> mlua::Result<Vec<Ready>> {
    let mut ready = Vec::new();
    {
        let Some(mut waits) = lua.app_data_mut::<Waits>() else {
            return Ok(Vec::new());
        };
        let mut cx = Context::from_waker(Waker::noop());
        let ids: Vec<u64> = waits.waits.keys().copied().collect();
        for id in ids {
            let wait = waits.waits.get_mut(&id).expect("listed");
            if let Poll::Ready(deliver) = (wait.poll)(&mut cx) {
                let wait = waits.waits.remove(&id).expect("listed");
                ready.push((id, wait.scope, wait.callback, deliver));
            }
        }
    }
    let mut out = Vec::with_capacity(ready.len());
    for (id, scope, callback, deliver) in ready {
        let (value, err) = deliver(lua)?;
        out.push(Ready {
            id,
            scope,
            callback,
            value,
            err,
        });
    }
    Ok(out)
}

/// Forgets a wait (`Task:cancel`): its value is dropped when it comes.
pub fn cancel(lua: &Lua, id: u64) -> bool {
    lua.app_data_mut::<Waits>()
        .is_some_and(|mut w| w.waits.remove(&id).is_some())
}
