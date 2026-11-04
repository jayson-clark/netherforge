//! An `async` function's value, delivered later. The work runs elsewhere
//! (the runtime's pool); its future is polled at the room's tick boundary,
//! and the value reaches the script there as `value, err`, to its callback.
//! Game logic never runs anywhere but the room's own thread. (Tasks waiting
//! on a value come with the prelude's tasks.)

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

/// Keeps `pending` until it's ready, then hands its value (pushed with
/// `codec`) or its error to `callback`. Returns the wait's id.
pub fn defer<C: Codec + 'static>(
    lua: &Lua,
    scope: Option<ScopeId>,
    mut pending: Pending<C::T>,
    codec: C,
    callback: Option<Value>,
    called: &str,
) -> mlua::Result<u64> {
    let callback = match callback {
        None | Some(Value::Nil) => None,
        Some(Value::Function(f)) => Some(f),
        Some(_) => {
            return Err(mlua::Error::runtime(format!(
                "bad argument to {called} (a function to call with the result expected)"
            )));
        }
    };
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

/// Polls every wait once, and calls back those that are ready, through
/// `call` (the host's guarded call, for the wait's scope). The room calls it at
/// each tick boundary.
pub fn deliver(
    lua: &Lua,
    mut call: impl FnMut(Option<ScopeId>, &Function, Value, Value),
) -> mlua::Result<()> {
    let mut ready = Vec::new();
    {
        let Some(mut waits) = lua.app_data_mut::<Waits>() else {
            return Ok(());
        };
        let mut cx = Context::from_waker(Waker::noop());
        let ids: Vec<u64> = waits.waits.keys().copied().collect();
        for id in ids {
            let wait = waits.waits.get_mut(&id).expect("listed");
            if let Poll::Ready(deliver) = (wait.poll)(&mut cx) {
                let wait = waits.waits.remove(&id).expect("listed");
                ready.push((wait.scope, wait.callback, deliver));
            }
        }
    }
    for (scope, callback, deliver) in ready {
        let (value, err) = deliver(lua)?;
        if let Some(f) = callback {
            call(scope, &f, value, err);
        }
    }
    Ok(())
}

/// Forgets a wait (`Task:cancel`): its value is dropped when it comes.
pub fn cancel(lua: &Lua, id: u64) -> bool {
    lua.app_data_mut::<Waits>()
        .is_some_and(|mut w| w.waits.remove(&id).is_some())
}
