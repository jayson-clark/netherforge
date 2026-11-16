//! WASI preview 1, the one thing a module may import, written here rather than taken from `wasmtime-wasi`: what a
//! module gets is exactly what the runtime chose (Kotlin's Chicory WASI with `WasmFiles` in place of everything
//! that takes a descriptor or a path), and nothing of the host's (its directories, its environment, its sockets)
//! is there to be forgotten.
//!
//! - Standard input, output and error are the instance's `Input` and `Output`s: nothing for a library's instance,
//!   the given text and capped buffers for `nf.wasm.run`, a program's line queues for `nf.wasm.start`.
//! - Arguments (`argv[0]` the module's name) and the environment are only what the script gave.
//! - Clocks are the system's; `random_get` is the operating system's generator.
//! - Descriptor 3 and up are the module's data (`files.rs`), read-only; sockets aren't supported.
//! - `proc_exit` ends the run with its code (`Exit`); a blocking read or a `poll_oneoff` sleep checks the store's
//!   guard, so a stopped program or a call past its time doesn't hang there.

use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use wasmtime::{Caller, Extern, Linker, Result};

use super::files::{self, Files};
use super::threads::Guard;

/// The one module a module may import from.
pub const MODULE: &str = netherforge_format::wasm::WASI;

/// A WASI error number.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Errno(pub u16);

impl Errno {
    pub const SUCCESS: Errno = Errno(0);
    pub const BADF: Errno = Errno(8);
    pub const EXIST: Errno = Errno(20);
    pub const FAULT: Errno = Errno(21);
    pub const INVAL: Errno = Errno(28);
    pub const IO: Errno = Errno(29);
    pub const ISDIR: Errno = Errno(31);
    pub const MFILE: Errno = Errno(33);
    pub const NAMETOOLONG: Errno = Errno(37);
    pub const NOENT: Errno = Errno(44);
    pub const NOSYS: Errno = Errno(52);
    pub const NOTDIR: Errno = Errno(54);
    pub const NOTSUP: Errno = Errno(58);
    pub const ROFS: Errno = Errno(69);
    pub const SPIPE: Errno = Errno(70);
    pub const NOTCAPABLE: Errno = Errno(76);
}

/// A module called `proc_exit` with this code.
#[derive(Debug)]
pub struct Exit(pub i32);

impl std::fmt::Display for Exit {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "it exited with {}", self.0)
    }
}

impl std::error::Error for Exit {}

/// A module's memory as a host function sees it: every access checked, `None` past its end.
pub struct Mem<'a>(pub &'a mut [u8]);

impl Mem<'_> {
    fn range(&self, at: u32, len: usize) -> Option<std::ops::Range<usize>> {
        let start = at as usize;
        let end = start.checked_add(len)?;
        (end <= self.0.len()).then_some(start..end)
    }

    pub fn read(&self, at: u32, len: u32) -> Option<Vec<u8>> {
        Some(self.0[self.range(at, len as usize)?].to_vec())
    }

    pub fn write(&mut self, at: u32, bytes: &[u8]) -> Option<()> {
        let range = self.range(at, bytes.len())?;
        self.0[range].copy_from_slice(bytes);
        Some(())
    }

    pub fn u8(&mut self, at: u32, value: u8) -> Option<()> {
        self.write(at, &[value])
    }

    pub fn u16(&mut self, at: u32, value: u16) -> Option<()> {
        self.write(at, &value.to_le_bytes())
    }

    pub fn u32(&mut self, at: u32, value: u32) -> Option<()> {
        self.write(at, &value.to_le_bytes())
    }

    pub fn u64(&mut self, at: u32, value: u64) -> Option<()> {
        self.write(at, &value.to_le_bytes())
    }

    pub fn get_u8(&self, at: u32) -> Option<u8> {
        Some(self.0[self.range(at, 1)?][0])
    }

    pub fn get_u16(&self, at: u32) -> Option<u16> {
        Some(u16::from_le_bytes(
            self.0[self.range(at, 2)?].try_into().ok()?,
        ))
    }

    pub fn get_u32(&self, at: u32) -> Option<u32> {
        Some(u32::from_le_bytes(
            self.0[self.range(at, 4)?].try_into().ok()?,
        ))
    }

    pub fn get_u64(&self, at: u32) -> Option<u64> {
        Some(u64::from_le_bytes(
            self.0[self.range(at, 8)?].try_into().ok()?,
        ))
    }
}

/// What a module reads as its standard input. `Ok(0)` is the end of it.
pub trait Input: Send {
    fn read(&mut self, into: &mut [u8], guard: &Guard) -> Result<usize>;
}

/// Where a module's standard output or error goes.
pub trait Output: Send {
    fn write(&mut self, bytes: &[u8], guard: &Guard) -> Result<()>;
}

/// An input that's already at its end.
pub struct NoInput;

impl Input for NoInput {
    fn read(&mut self, _: &mut [u8], _: &Guard) -> Result<usize> {
        Ok(0)
    }
}

/// Input that's all there from the start (`nf.wasm.run`'s `stdin`).
pub struct GivenInput {
    pub bytes: Vec<u8>,
    pub at: usize,
}

impl Input for GivenInput {
    fn read(&mut self, into: &mut [u8], _: &Guard) -> Result<usize> {
        let n = into.len().min(self.bytes.len() - self.at);
        into[..n].copy_from_slice(&self.bytes[self.at..self.at + n]);
        self.at += n;
        Ok(n)
    }
}

/// Output thrown away.
pub struct NoOutput;

impl Output for NoOutput {
    fn write(&mut self, _: &[u8], _: &Guard) -> Result<()> {
        Ok(())
    }
}

/// What one instance's WASI holds.
pub struct Wasi {
    pub args: Vec<Vec<u8>>,
    pub environment: Vec<Vec<u8>>,
    pub stdin: Box<dyn Input>,
    pub stdout: Box<dyn Output>,
    pub stderr: Box<dyn Output>,
    pub files: Files,
    pub guard: Arc<Guard>,
    pub started: Instant,
}

/// What an instance's store holds: its WASI and how far its memory may grow.
pub struct StoreData {
    pub wasi: Wasi,
    pub limits: Limits,
}

/// The most memory (in bytes) an instance may have: a growth past it fails (`memory.grow` answers -1), so the cap
/// is held, not only counted. Tables are held to wasmtime's own limits.
pub struct Limits {
    pub max_memory_bytes: usize,
}

impl wasmtime::ResourceLimiter for Limits {
    fn memory_growing(
        &mut self,
        _current: usize,
        desired: usize,
        _maximum: Option<usize>,
    ) -> Result<bool> {
        Ok(desired <= self.max_memory_bytes)
    }

    fn table_growing(
        &mut self,
        _current: usize,
        desired: usize,
        _maximum: Option<usize>,
    ) -> Result<bool> {
        Ok(desired <= MAX_TABLE_ELEMENTS)
    }
}

/// Elements one table may hold.
const MAX_TABLE_ELEMENTS: usize = 1 << 20;

type Call<'a> = Caller<'a, StoreData>;

/// Runs `f` with the module's memory and its WASI; `EFAULT` for a module with no memory to pass anything in.
fn with(caller: &mut Call, f: impl FnOnce(&mut Mem, &mut Wasi) -> Result<Errno>) -> Result<i32> {
    let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
        return Ok(i32::from(Errno::FAULT.0));
    };
    let (bytes, data) = memory.data_and_store_mut(caller);
    let errno = f(&mut Mem(bytes), &mut data.wasi)?;
    Ok(i32::from(errno.0))
}

fn errno(e: Errno) -> Result<i32> {
    Ok(i32::from(e.0))
}

/// `argv` or `environ` and their buffer: a pointer to each, each ending in a zero.
fn strings_get(mem: &mut Mem, strings: &[Vec<u8>], pointers: u32, buffer: u32) -> Errno {
    let mut at = buffer;
    for (i, s) in strings.iter().enumerate() {
        if mem.u32(pointers + 4 * i as u32, at).is_none()
            || mem.write(at, s).is_none()
            || mem.u8(at + s.len() as u32, 0).is_none()
        {
            return Errno::FAULT;
        }
        at += s.len() as u32 + 1;
    }
    Errno::SUCCESS
}

fn strings_sizes(mem: &mut Mem, strings: &[Vec<u8>], count: u32, size: u32) -> Errno {
    let bytes: usize = strings.iter().map(|s| s.len() + 1).sum();
    match mem
        .u32(count, strings.len() as u32)
        .and_then(|()| mem.u32(size, bytes as u32))
    {
        Some(()) => Errno::SUCCESS,
        None => Errno::FAULT,
    }
}

const CLOCK_REALTIME: i32 = 0;
const CLOCK_MONOTONIC: i32 = 1;
const CLOCK_PROCESS: i32 = 2;
const CLOCK_THREAD: i32 = 3;

fn now(wasi: &Wasi, clock: i32) -> Option<u64> {
    match clock {
        CLOCK_REALTIME => SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .ok()
            .map(|d| d.as_nanos() as u64),
        CLOCK_MONOTONIC | CLOCK_PROCESS | CLOCK_THREAD => {
            Some(wasi.started.elapsed().as_nanos() as u64)
        }
        _ => None,
    }
}

/// The `iovec`s at `iovs`: where each starts and how long it is.
fn iovecs(mem: &Mem, iovs: u32, count: u32) -> Option<Vec<(u32, u32)>> {
    (0..count)
        .map(|i| Some((mem.get_u32(iovs + i * 8)?, mem.get_u32(iovs + i * 8 + 4)?)))
        .collect()
}

/// The longest one read of standard input takes at once.
const MAX_READ: usize = 1 << 20;

fn stdin_read(mem: &mut Mem, wasi: &mut Wasi, iovs: u32, count: u32, out: u32) -> Result<Errno> {
    let Some(vecs) = iovecs(mem, iovs, count) else {
        return Ok(Errno::FAULT);
    };
    let want: usize = vecs
        .iter()
        .map(|(_, n)| *n as usize)
        .sum::<usize>()
        .min(MAX_READ);
    let mut buffer = vec![0u8; want];
    let read = if want == 0 {
        0
    } else {
        wasi.stdin.read(&mut buffer, &wasi.guard)?
    };
    let mut done = 0usize;
    for (base, length) in vecs {
        if done >= read {
            break;
        }
        let n = (length as usize).min(read - done);
        if mem.write(base, &buffer[done..done + n]).is_none() {
            return Ok(Errno::FAULT);
        }
        done += n;
    }
    Ok(mem
        .u32(out, done as u32)
        .map_or(Errno::FAULT, |()| Errno::SUCCESS))
}

fn stream_write(
    mem: &mut Mem,
    wasi: &mut Wasi,
    fd: i32,
    iovs: u32,
    count: u32,
    out: u32,
) -> Result<Errno> {
    let Some(vecs) = iovecs(mem, iovs, count) else {
        return Ok(Errno::FAULT);
    };
    let mut bytes = Vec::new();
    for (base, length) in vecs {
        let Some(part) = mem.read(base, length) else {
            return Ok(Errno::FAULT);
        };
        bytes.extend_from_slice(&part);
    }
    let guard = wasi.guard.clone();
    if fd == 1 {
        wasi.stdout.write(&bytes, &guard)?;
    } else {
        wasi.stderr.write(&bytes, &guard)?;
    }
    Ok(mem
        .u32(out, bytes.len() as u32)
        .map_or(Errno::FAULT, |()| Errno::SUCCESS))
}

const EVENT_CLOCK: u8 = 0;
const EVENT_FD_READ: u8 = 1;
const EVENT_FD_WRITE: u8 = 2;
const ABSTIME: u16 = 1;
const SUBSCRIPTION: u32 = 48;
const EVENT: u32 = 32;

/// `poll_oneoff`: a descriptor is always ready (a read then waits in `fd_read`, as a pipe's would); with none, it
/// sleeps until the first clock's time, checking the guard, and answers the clocks that are due.
fn poll(
    mem: &mut Mem,
    wasi: &mut Wasi,
    subscriptions: u32,
    events: u32,
    count: u32,
    out: u32,
) -> Result<Errno> {
    if count == 0 {
        return Ok(Errno::INVAL);
    }
    struct Sub {
        userdata: u64,
        tag: u8,
        due: Option<Instant>,
    }
    let mut subs = Vec::new();
    for i in 0..count {
        let at = subscriptions + i * SUBSCRIPTION;
        let (Some(userdata), Some(tag)) = (mem.get_u64(at), mem.get_u8(at + 8)) else {
            return Ok(Errno::FAULT);
        };
        let due = if tag == EVENT_CLOCK {
            let (Some(clock), Some(timeout), Some(flags)) = (
                mem.get_u32(at + 16),
                mem.get_u64(at + 24),
                mem.get_u16(at + 40),
            ) else {
                return Ok(Errno::FAULT);
            };
            let wait = if flags & ABSTIME != 0 {
                let Some(now) = now(wasi, clock as i32) else {
                    return Ok(Errno::INVAL);
                };
                timeout.saturating_sub(now)
            } else {
                timeout
            };
            Some(Instant::now() + Duration::from_nanos(wait))
        } else {
            None
        };
        subs.push(Sub { userdata, tag, due });
    }
    let fds_ready = subs
        .iter()
        .any(|s| s.tag == EVENT_FD_READ || s.tag == EVENT_FD_WRITE);
    if !fds_ready && let Some(first) = subs.iter().filter_map(|s| s.due).min() {
        loop {
            wasi.guard.check()?;
            let now = Instant::now();
            if now >= first {
                break;
            }
            std::thread::sleep((first - now).min(super::threads::EPOCH));
        }
    }
    let now = Instant::now();
    let mut written = 0u32;
    for sub in subs {
        let ready = match sub.tag {
            EVENT_CLOCK => sub.due.is_some_and(|d| d <= now),
            EVENT_FD_READ | EVENT_FD_WRITE => true,
            _ => false,
        };
        if !ready {
            continue;
        }
        let at = events + written * EVENT;
        let ok = mem
            .u64(at, sub.userdata)
            .and_then(|()| mem.u16(at + 8, 0))
            .and_then(|()| mem.u8(at + 10, sub.tag))
            .and_then(|()| mem.u64(at + 16, u64::from(sub.tag != EVENT_CLOCK)))
            .and_then(|()| mem.u16(at + 24, 0));
        if ok.is_none() {
            return Ok(Errno::FAULT);
        }
        written += 1;
    }
    Ok(mem
        .u32(out, written)
        .map_or(Errno::FAULT, |()| Errno::SUCCESS))
}

const CHARACTER_DEVICE: u8 = 2;

/// Every function of WASI preview 1, defined on `linker`.
pub fn define(linker: &mut Linker<StoreData>) -> Result<()> {
    let m = MODULE;
    linker.func_wrap(m, "args_get", |mut c: Call, argv: i32, buf: i32| {
        with(&mut c, |mem, w| {
            Ok(strings_get(mem, &w.args, argv as u32, buf as u32))
        })
    })?;
    linker.func_wrap(m, "args_sizes_get", |mut c: Call, count: i32, size: i32| {
        with(&mut c, |mem, w| {
            Ok(strings_sizes(mem, &w.args, count as u32, size as u32))
        })
    })?;
    linker.func_wrap(m, "environ_get", |mut c: Call, env: i32, buf: i32| {
        with(&mut c, |mem, w| {
            Ok(strings_get(mem, &w.environment, env as u32, buf as u32))
        })
    })?;
    linker.func_wrap(
        m,
        "environ_sizes_get",
        |mut c: Call, count: i32, size: i32| {
            with(&mut c, |mem, w| {
                Ok(strings_sizes(
                    mem,
                    &w.environment,
                    count as u32,
                    size as u32,
                ))
            })
        },
    )?;
    linker.func_wrap(m, "clock_res_get", |mut c: Call, clock: i32, out: i32| {
        with(&mut c, |mem, w| {
            Ok(match now(w, clock) {
                None => Errno::INVAL,
                Some(_) => mem
                    .u64(out as u32, 1_000)
                    .map_or(Errno::FAULT, |()| Errno::SUCCESS),
            })
        })
    })?;
    linker.func_wrap(
        m,
        "clock_time_get",
        |mut c: Call, clock: i32, _precision: i64, out: i32| {
            with(&mut c, |mem, w| {
                Ok(match now(w, clock) {
                    None => Errno::INVAL,
                    Some(t) => mem
                        .u64(out as u32, t)
                        .map_or(Errno::FAULT, |()| Errno::SUCCESS),
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "fd_advise",
        |mut c: Call, fd: i32, _: i64, _: i64, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::SUCCESS
                } else {
                    stream(fd, Errno::SPIPE)
                })
            })
        },
    )?;
    linker.func_wrap(m, "fd_allocate", |mut c: Call, fd: i32, _: i64, _: i64| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::ROFS
            } else {
                stream(fd, Errno::SPIPE)
            })
        })
    })?;
    linker.func_wrap(m, "fd_close", |mut c: Call, fd: i32| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                w.files.close(fd)
            } else {
                stream(fd, Errno::SUCCESS)
            })
        })
    })?;
    linker.func_wrap(m, "fd_datasync", |mut c: Call, fd: i32| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::SUCCESS
            } else {
                stream(fd, Errno::INVAL)
            })
        })
    })?;
    linker.func_wrap(m, "fd_fdstat_get", |mut c: Call, fd: i32, out: i32| {
        with(&mut c, |mem, w| {
            Ok(if w.files.has(fd) {
                w.files.fdstat(mem, fd, out as u32)
            } else {
                match fd {
                    0 => files::fdstat(
                        mem,
                        out as u32,
                        CHARACTER_DEVICE,
                        files::STREAM_READ_RIGHTS,
                        0,
                    ),
                    1 | 2 => files::fdstat(
                        mem,
                        out as u32,
                        CHARACTER_DEVICE,
                        files::STREAM_WRITE_RIGHTS,
                        0,
                    ),
                    _ => Errno::BADF,
                }
            })
        })
    })?;
    linker.func_wrap(m, "fd_fdstat_set_flags", |mut c: Call, fd: i32, _: i32| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::NOTSUP
            } else {
                stream(fd, Errno::NOTSUP)
            })
        })
    })?;
    linker.func_wrap(
        m,
        "fd_fdstat_set_rights",
        |mut c: Call, fd: i32, _: i64, _: i64| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::NOTSUP
                } else {
                    stream(fd, Errno::NOTSUP)
                })
            })
        },
    )?;
    linker.func_wrap(m, "fd_filestat_get", |mut c: Call, fd: i32, out: i32| {
        with(&mut c, |mem, w| {
            Ok(if w.files.has(fd) {
                w.files.filestat(mem, fd, out as u32)
            } else if (0..=2).contains(&fd) {
                files::filestat(mem, out as u32, CHARACTER_DEVICE, 0, 0)
            } else {
                Errno::BADF
            })
        })
    })?;
    linker.func_wrap(m, "fd_filestat_set_size", |mut c: Call, fd: i32, _: i64| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::ROFS
            } else {
                stream(fd, Errno::INVAL)
            })
        })
    })?;
    linker.func_wrap(
        m,
        "fd_filestat_set_times",
        |mut c: Call, fd: i32, _: i64, _: i64, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    stream(fd, Errno::NOTSUP)
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "fd_pread",
        |mut c: Call, fd: i32, iovs: i32, count: i32, offset: i64, out: i32| {
            with(&mut c, |mem, w| {
                Ok(if w.files.has(fd) {
                    w.files.read(
                        mem,
                        fd,
                        (iovs as u32, count as u32),
                        out as u32,
                        Some(offset as u64),
                    )
                } else {
                    stream(fd, Errno::SPIPE)
                })
            })
        },
    )?;
    linker.func_wrap(m, "fd_prestat_get", |mut c: Call, fd: i32, out: i32| {
        with(&mut c, |mem, w| {
            Ok(w.files.prestat_get(mem, fd, out as u32))
        })
    })?;
    linker.func_wrap(
        m,
        "fd_prestat_dir_name",
        |mut c: Call, fd: i32, path: i32, len: i32| {
            with(&mut c, |mem, w| {
                Ok(w.files.prestat_dir_name(mem, fd, path as u32, len as u32))
            })
        },
    )?;
    linker.func_wrap(
        m,
        "fd_pwrite",
        |mut c: Call, fd: i32, _: i32, _: i32, _: i64, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::BADF
                } else {
                    stream(fd, Errno::SPIPE)
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "fd_read",
        |mut c: Call, fd: i32, iovs: i32, count: i32, out: i32| {
            with(&mut c, |mem, w| {
                if w.files.has(fd) {
                    Ok(w.files
                        .read(mem, fd, (iovs as u32, count as u32), out as u32, None))
                } else if fd == 0 {
                    stdin_read(mem, w, iovs as u32, count as u32, out as u32)
                } else {
                    Ok(stream(fd, Errno::BADF))
                }
            })
        },
    )?;
    linker.func_wrap(
        m,
        "fd_readdir",
        |mut c: Call, fd: i32, buf: i32, len: i32, cookie: i64, out: i32| {
            with(&mut c, |mem, w| {
                Ok(if w.files.has(fd) {
                    w.files
                        .readdir(mem, fd, (buf as u32, len as u32), cookie as u64, out as u32)
                } else {
                    stream(fd, Errno::NOTDIR)
                })
            })
        },
    )?;
    linker.func_wrap(m, "fd_renumber", |mut c: Call, fd: i32, _: i32| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::NOTSUP
            } else {
                stream(fd, Errno::NOTSUP)
            })
        })
    })?;
    linker.func_wrap(
        m,
        "fd_seek",
        |mut c: Call, fd: i32, offset: i64, whence: i32, out: i32| {
            with(&mut c, |mem, w| {
                Ok(if w.files.has(fd) {
                    w.files.seek(mem, fd, offset, whence, out as u32)
                } else {
                    stream(fd, Errno::SPIPE)
                })
            })
        },
    )?;
    linker.func_wrap(m, "fd_sync", |mut c: Call, fd: i32| {
        with(&mut c, |_, w| {
            Ok(if w.files.has(fd) {
                Errno::SUCCESS
            } else {
                stream(fd, Errno::INVAL)
            })
        })
    })?;
    linker.func_wrap(m, "fd_tell", |mut c: Call, fd: i32, out: i32| {
        with(&mut c, |mem, w| {
            Ok(if w.files.has(fd) {
                w.files.tell(mem, fd, out as u32)
            } else {
                stream(fd, Errno::SPIPE)
            })
        })
    })?;
    linker.func_wrap(
        m,
        "fd_write",
        |mut c: Call, fd: i32, iovs: i32, count: i32, out: i32| {
            with(&mut c, |mem, w| {
                if w.files.has(fd) {
                    Ok(Errno::BADF)
                } else if fd == 1 || fd == 2 {
                    stream_write(mem, w, fd, iovs as u32, count as u32, out as u32)
                } else {
                    Ok(Errno::BADF)
                }
            })
        },
    )?;
    for name in [
        "path_create_directory",
        "path_remove_directory",
        "path_unlink_file",
    ] {
        linker.func_wrap(m, name, |mut c: Call, fd: i32, _: i32, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    Errno::BADF
                })
            })
        })?;
    }
    linker.func_wrap(
        m,
        "path_filestat_get",
        |mut c: Call, fd: i32, _: i32, path: i32, len: i32, out: i32| {
            with(&mut c, |mem, w| {
                Ok(w.files
                    .path_filestat(mem, fd, (path as u32, len as u32), out as u32))
            })
        },
    )?;
    linker.func_wrap(
        m,
        "path_filestat_set_times",
        |mut c: Call, fd: i32, _: i32, _: i32, _: i32, _: i64, _: i64, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    Errno::BADF
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "path_link",
        |mut c: Call, fd: i32, _: i32, _: i32, _: i32, _: i32, _: i32, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    Errno::BADF
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "path_open",
        |mut c: Call,
         fd: i32,
         _dirflags: i32,
         path: i32,
         len: i32,
         open_flags: i32,
         rights: i64,
         _inheriting: i64,
         fd_flags: i32,
         out: i32| {
            with(&mut c, |mem, w| {
                Ok(w.files.path_open(
                    mem,
                    fd,
                    (path as u32, len as u32),
                    open_flags,
                    rights as u64,
                    fd_flags,
                    out as u32,
                ))
            })
        },
    )?;
    linker.func_wrap(
        m,
        "path_readlink",
        |mut c: Call, fd: i32, path: i32, len: i32, _: i32, _: i32, _: i32| {
            with(&mut c, |mem, w| {
                Ok(w.files.readlink(mem, fd, (path as u32, len as u32)))
            })
        },
    )?;
    linker.func_wrap(
        m,
        "path_rename",
        |mut c: Call, fd: i32, _: i32, _: i32, _: i32, _: i32, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    Errno::BADF
                })
            })
        },
    )?;
    // path_symlink takes the descriptor third: (old path, its length, fd, new path, its length).
    linker.func_wrap(
        m,
        "path_symlink",
        |mut c: Call, _: i32, _: i32, fd: i32, _: i32, _: i32| {
            with(&mut c, |_, w| {
                Ok(if w.files.has(fd) {
                    Errno::ROFS
                } else {
                    Errno::BADF
                })
            })
        },
    )?;
    linker.func_wrap(
        m,
        "poll_oneoff",
        |mut c: Call, subs: i32, events: i32, count: i32, out: i32| {
            with(&mut c, |mem, w| {
                poll(mem, w, subs as u32, events as u32, count as u32, out as u32)
            })
        },
    )?;
    linker.func_wrap(m, "proc_exit", |_: Call, code: i32| -> Result<()> {
        Err(Exit(code).into())
    })?;
    linker.func_wrap(m, "proc_raise", |_: Call, _: i32| errno(Errno::NOSYS))?;
    linker.func_wrap(m, "sched_yield", |c: Call| {
        c.data().wasi.guard.check()?;
        std::thread::yield_now();
        errno(Errno::SUCCESS)
    })?;
    linker.func_wrap(m, "random_get", |mut c: Call, buf: i32, len: i32| {
        with(&mut c, |mem, _| {
            let mut bytes = vec![0u8; len as u32 as usize];
            if getrandom::fill(&mut bytes).is_err() {
                return Ok(Errno::IO);
            }
            Ok(mem
                .write(buf as u32, &bytes)
                .map_or(Errno::FAULT, |()| Errno::SUCCESS))
        })
    })?;
    linker.func_wrap(m, "sock_accept", |_: Call, _: i32, _: i32, _: i32| {
        errno(Errno::NOTSUP)
    })?;
    linker.func_wrap(
        m,
        "sock_recv",
        |_: Call, _: i32, _: i32, _: i32, _: i32, _: i32, _: i32| errno(Errno::NOTSUP),
    )?;
    linker.func_wrap(
        m,
        "sock_send",
        |_: Call, _: i32, _: i32, _: i32, _: i32, _: i32| errno(Errno::NOTSUP),
    )?;
    linker.func_wrap(m, "sock_shutdown", |_: Call, _: i32, _: i32| {
        errno(Errno::NOTSUP)
    })?;
    Ok(())
}

/// What a function answers for a standard stream (`answer`) or a descriptor nobody opened (`EBADF`).
fn stream(fd: i32, answer: Errno) -> Errno {
    if (0..=2).contains(&fd) {
        answer
    } else {
        Errno::BADF
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_function_of_wasi_preview_1_is_defined() {
        let engine = wasmtime::Engine::default();
        let mut linker = Linker::new(&engine);
        define(&mut linker).unwrap();
        let mut store = wasmtime::Store::new(
            &engine,
            StoreData {
                wasi: Wasi {
                    args: Vec::new(),
                    environment: Vec::new(),
                    stdin: Box::new(NoInput),
                    stdout: Box::new(NoOutput),
                    stderr: Box::new(NoOutput),
                    files: Files::new(Arc::default()),
                    guard: Arc::default(),
                    started: Instant::now(),
                },
                limits: Limits {
                    max_memory_bytes: 0,
                },
            },
        );
        for name in netherforge_format::wasm::FUNCTIONS {
            assert!(linker.get(&mut store, MODULE, name).is_some(), "{name}");
        }
    }
}
