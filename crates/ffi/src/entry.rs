//! The entry points the JVM calls (by these names, through FFM's linker). Nothing unwinds out of
//! them: a panic becomes a failed answer, which the JVM throws.

use std::cell::RefCell;
use std::panic::{AssertUnwindSafe, catch_unwind};

use netherforge_platform::PlatformError;

use crate::objects::{self, FAILED, OK, Upcalls};
use crate::wire::{Put, Reader, Writer};

thread_local! {
    /// The last answer this thread gave the JVM, kept until its next call.
    static ANSWER: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

/// The wire's version: the JVM's glue says which it was generated with, and both must agree.
pub const WIRE_VERSION: u32 = 1;

/// What a panic says.
pub fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    panic
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| panic.downcast_ref::<&str>().map(|s| s.to_string()))
        .unwrap_or_else(|| "the Rust runtime panicked".to_string())
}

/// Runs [work] as one call in: its answer written after [OK], or a panic's message after [FAILED],
/// kept in [ANSWER] for the JVM to read.
///
/// # Safety
/// [out_len] has room for a usize.
pub unsafe fn answer(out_len: *mut usize, work: impl FnOnce(&mut Writer)) -> *const u8 {
    let mut w = Writer::new();
    w.u8(OK);
    if let Err(panic) = catch_unwind(AssertUnwindSafe(|| work(&mut w))) {
        w = Writer::new();
        w.u8(FAILED);
        PlatformError {
            message: panic_message(&*panic),
            cause: None,
        }
        .put(&mut w);
    }
    ANSWER.with(|a| {
        let mut a = a.borrow_mut();
        *a = w.bytes;
        // SAFETY: the JVM hands a place for one usize.
        unsafe { *out_len = a.len() };
        a.as_ptr()
    })
}

/// The bytes the JVM handed over.
///
/// # Safety
/// [args] holds [len] bytes, valid for the call.
pub unsafe fn bytes<'a>(args: *const u8, len: usize) -> &'a [u8] {
    if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(args, len) }
    }
}

/// Takes the JVM's entry points and answers the wire's version, which the JVM checks against its own.
#[unsafe(no_mangle)]
pub extern "C" fn nf_init(
    call: extern "C" fn(u64, u32, *const u8, usize, *mut usize) -> *const u8,
    release: extern "C" fn(u64),
) -> u32 {
    objects::set_upcalls(Upcalls { call, release });
    WIRE_VERSION
}

/// Calls method [method] of Rust's object [id] with the [len] bytes at [args].
///
/// # Safety
/// [args] holds [len] bytes and [out_len] has room for a usize, both for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nf_call(
    id: u64,
    method: u32,
    args: *const u8,
    len: usize,
    out_len: *mut usize,
) -> *const u8 {
    objects::drain_released();
    let args = unsafe { bytes(args, len) };
    unsafe {
        answer(out_len, |w| {
            let Some(dispatch) = objects::exported(id) else {
                panic!(
                    "the Rust runtime has no object {id} on this thread (let go, or called off the main thread)"
                )
            };
            let mut r = Reader::new(args);
            dispatch(method, &mut r, w);
        })
    }
}

/// Lets go of Rust's object [id] (from any thread: it's done at the main thread's next call).
#[unsafe(no_mangle)]
pub extern "C" fn nf_release(id: u64) {
    objects::release(id);
}

/// Starts the runtime ([crate::compose::start]) with the [len] bytes at [args]: the answer is the runtime's events,
/// or none when there's no project; a failure says why `config.yml` can't be acted on.
///
/// # Safety
/// As [nf_call].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nf_start(args: *const u8, len: usize, out_len: *mut usize) -> *const u8 {
    objects::drain_released();
    let args = unsafe { bytes(args, len) };
    unsafe {
        answer(out_len, |w| {
            let start =
                <crate::compose::StartArgs as crate::wire::Take>::take(&mut Reader::new(args));
            match crate::compose::start(start) {
                Ok(events) => events.put(w),
                Err(why) => panic!("{why}"),
            }
        })
    }
}

/// Stops the runtime ([crate::compose::stop]).
///
/// # Safety
/// [out_len] has room for a usize.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nf_stop(out_len: *mut usize) -> *const u8 {
    objects::drain_released();
    unsafe { answer(out_len, |_| crate::compose::stop()) }
}
