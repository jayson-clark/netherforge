//! What each side hands the other: references by id.
//!
//! - **The JVM's objects** reach Rust as a [`JavaRef`] (its id there), which the generated
//!   proxies call through ([`JavaRef::call`]); dropping one lets the JVM let go of it, from any
//!   thread.
//! - **Rust's objects** (the runtime's events, a command's handler, a closure) are handed out by
//!   id ([`export`]) as a [`Dispatch`], and called from the JVM by number until it lets go
//!   ([`release`]). They're `Rc`s, so they live on the thread that made them, the server's main
//!   thread: the JVM's garbage collector lets go of them from its own thread, so a release is only
//!   queued, and done on the main thread at its next call in.

use std::any::Any;
use std::cell::{OnceCell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use netherforge_format::game::{GameData, GameDataBundle};
use netherforge_platform::PlatformError;

use crate::wire::{Put, Reader, Take, Writer};

/// Whose an object is, as a reference's first byte says.
pub const JAVA: u8 = 0;
pub const RUST: u8 = 1;

/// A call's answer: its first byte.
pub const OK: u8 = 0;
pub const FAILED: u8 = 1;

/// What runs a call to one of Rust's objects: its method's number, the arguments, where the answer goes.
pub type Dispatch = Rc<dyn Fn(u32, &mut Reader, &mut Writer)>;

/// The JVM's entry points, handed over once by `nf_init`.
#[derive(Clone, Copy)]
pub struct Upcalls {
    /// Calls method `method` of the JVM's object `id` with the arguments at `args` (`len` bytes),
    /// and answers with a pointer to its answer, whose length it writes to `out_len`. The answer
    /// stays valid until this thread's next call into the JVM.
    pub call: extern "C" fn(
        id: u64,
        method: u32,
        args: *const u8,
        len: usize,
        out_len: *mut usize,
    ) -> *const u8,
    /// Lets go of the JVM's object `id`, once per time it was handed over.
    pub release: extern "C" fn(id: u64),
}

static UPCALLS: OnceLock<Upcalls> = OnceLock::new();

/// Sets the JVM's entry points; the first set stays.
pub fn set_upcalls(upcalls: Upcalls) {
    let _ = UPCALLS.set(upcalls);
}

fn upcalls() -> Upcalls {
    *UPCALLS
        .get()
        .expect("the JVM's entry points aren't set (nf_init)")
}

/// One of the JVM's objects, by its id there. Dropping it lets the JVM let go of it.
#[derive(Debug)]
pub struct JavaRef {
    id: u64,
}

impl JavaRef {
    pub fn id(&self) -> u64 {
        self.id
    }

    /// Calls method [method] with what [args] writes, and reads the answer. The server failing is
    /// a panic, as an exception is in Kotlin, saying [what] failed.
    pub fn call<R: Take>(&self, what: &str, method: u32, args: impl FnOnce(&mut Writer)) -> R {
        match self.answer(method, args, |r| R::take(r)) {
            Ok(v) => v,
            Err(e) => panic!("the server's {what} failed: {e}"),
        }
    }

    /// [call] for a method that answers a `Result`: the server failing is its error too.
    pub fn try_call<T: Take>(
        &self,
        _what: &str,
        method: u32,
        args: impl FnOnce(&mut Writer),
    ) -> Result<T, PlatformError> {
        self.answer(method, args, Result::<T, PlatformError>::take)?
    }

    fn answer<R>(
        &self,
        method: u32,
        args: impl FnOnce(&mut Writer),
        read: impl FnOnce(&mut Reader) -> R,
    ) -> Result<R, PlatformError> {
        let mut w = Writer::new();
        args(&mut w);
        let mut len = 0usize;
        let at = (upcalls().call)(self.id, method, w.bytes.as_ptr(), w.bytes.len(), &mut len);
        // SAFETY: the JVM answers with `len` bytes at `at`, valid until this thread calls it again.
        let bytes = unsafe { std::slice::from_raw_parts(at, len) };
        let mut r = Reader::new(bytes);
        match r.u8() {
            OK => {
                let v = read(&mut r);
                debug_assert!(
                    r.done(),
                    "the server's answer to method {method} was longer than its type"
                );
                Ok(v)
            }
            _ => Err(PlatformError::take(&mut r)),
        }
    }
}

impl Drop for JavaRef {
    fn drop(&mut self) {
        if let Some(upcalls) = UPCALLS.get() {
            (upcalls.release)(self.id);
        }
    }
}

impl Take for JavaRef {
    fn take(r: &mut Reader) -> Self {
        match r.u8() {
            JAVA => JavaRef { id: r.u64() },
            side => panic!("a reference to the server's object came as side {side}"),
        }
    }
}

impl Put for JavaRef {
    fn put(self, w: &mut Writer) {
        w.u8(JAVA);
        w.u64(self.id);
        // The JVM counts each time it hands an object over; this one goes back without being let go.
        std::mem::forget(self);
    }
}

/// The interfaces a proxy hands out borrowed (a platform's groups, `economy()`), made once each.
#[derive(Default)]
pub struct Kept {
    once: RefCell<HashMap<u32, Box<dyn Any>>>,
    by_id: RefCell<HashMap<u64, Box<dyn Any>>>,
    game: OnceCell<GameDataBundle>,
}

impl Kept {
    /// What [make] makes for member [member], made the first time.
    pub fn once<T: 'static>(&self, member: u32, make: impl FnOnce() -> T) -> &T {
        if let Some(v) = self.once.borrow().get(&member) {
            // SAFETY: a kept value is boxed and never removed while `self` lives.
            return unsafe { &*(v.downcast_ref::<T>().unwrap() as *const T) };
        }
        let made: Box<dyn Any> = Box::new(make());
        let at = made.downcast_ref::<T>().unwrap() as *const T;
        self.once.borrow_mut().entry(member).or_insert(made);
        // SAFETY: as above.
        unsafe { &*at }
    }

    /// A proxy for the JVM's object [obj], one per object: a second reference to the same one is let go.
    pub fn by_id<T: 'static>(&self, obj: JavaRef, make: impl FnOnce(JavaRef) -> T) -> &T {
        let id = obj.id();
        if let Some(v) = self.by_id.borrow().get(&id) {
            drop(obj);
            // SAFETY: as in `once`.
            return unsafe { &*(v.downcast_ref::<T>().unwrap() as *const T) };
        }
        let made: Box<dyn Any> = Box::new(make(obj));
        let at = made.downcast_ref::<T>().unwrap() as *const T;
        self.by_id.borrow_mut().insert(id, made);
        // SAFETY: as in `once`.
        unsafe { &*at }
    }

    /// The game's facts, read the first time.
    pub fn game(&self, read: impl FnOnce() -> GameDataBundle) -> &dyn GameData {
        self.game.get_or_init(read)
    }
}

/// One object handed out: what runs its calls, how many references the JVM holds, and what it was made from.
struct Exported {
    dispatch: Dispatch,
    count: usize,
    key: Option<usize>,
}

thread_local! {
    static EXPORTED: RefCell<HashMap<u64, Exported>> = RefCell::new(HashMap::new());
    /// The objects made from an `Rc`, by its address: handed out again, one is the same id (and counted), so the JVM
    /// sees the same object (a goal it's asked to remove is the one it was given).
    static KEYED: RefCell<HashMap<usize, u64>> = RefCell::new(HashMap::new());
}

/// Every object's id, the main thread's and the thread-safe ones' (`crate::terrain`) alike.
pub(crate) static NEXT: AtomicU64 = AtomicU64::new(1);
static RELEASED: Mutex<Vec<u64>> = Mutex::new(Vec::new());

/// Hands [dispatch] out: its id, which the JVM calls it by.
pub fn export(dispatch: Dispatch) -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    EXPORTED.with(|e| {
        e.borrow_mut().insert(
            id,
            Exported {
                dispatch,
                count: 1,
                key: None,
            },
        )
    });
    id
}

/// Hands out the object made from the `Rc` at [key] (made by [make] the first time): the same id each time, counted, so
/// it stays one object to the JVM until it lets go of every reference.
pub fn export_keyed(key: usize, make: impl FnOnce() -> Dispatch) -> u64 {
    if let Some(id) = KEYED.with(|k| k.borrow().get(&key).copied()) {
        let counted = EXPORTED.with(|e| {
            e.borrow_mut()
                .get_mut(&id)
                .map(|exported| exported.count += 1)
                .is_some()
        });
        if counted {
            return id;
        }
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    EXPORTED.with(|e| {
        e.borrow_mut().insert(
            id,
            Exported {
                dispatch: make(),
                count: 1,
                key: Some(key),
            },
        )
    });
    KEYED.with(|k| k.borrow_mut().insert(key, id));
    id
}

/// What object [id] runs, if this thread handed it out and it wasn't let go.
pub fn exported(id: u64) -> Option<Dispatch> {
    EXPORTED.with(|e| {
        e.borrow()
            .get(&id)
            .map(|exported| exported.dispatch.clone())
    })
}

/// Queues the JVM letting go of [id], from any thread.
pub fn release(id: u64) {
    if crate::terrain::release(id) {
        return;
    }
    RELEASED.lock().unwrap_or_else(|e| e.into_inner()).push(id);
}

/// Lets go of what was released and is this thread's.
pub fn drain_released() {
    let ids = std::mem::take(&mut *RELEASED.lock().unwrap_or_else(|e| e.into_inner()));
    if ids.is_empty() {
        return;
    }
    let mut elsewhere = Vec::new();
    // Dropped outside the borrow: a dispatcher's captures may hold JavaRefs, whose drop calls the JVM.
    let mut gone = Vec::new();
    EXPORTED.with(|e| {
        let mut e = e.borrow_mut();
        for id in ids {
            let Some(exported) = e.get_mut(&id) else {
                elsewhere.push(id);
                continue;
            };
            exported.count -= 1;
            if exported.count > 0 {
                continue;
            }
            let exported = e.remove(&id).expect("just found");
            if let Some(key) = exported.key {
                KEYED.with(|k| k.borrow_mut().remove(&key));
            }
            gone.push(exported.dispatch);
        }
    });
    drop(gone);
    if !elsewhere.is_empty() {
        RELEASED
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .extend(elsewhere);
    }
}

/// How many of this thread's objects are handed out (for tests).
pub fn exported_count() -> usize {
    EXPORTED.with(|e| e.borrow().len())
}
