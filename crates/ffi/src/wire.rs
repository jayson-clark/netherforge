//! The wire: what a call's arguments and answer are written as (the rust-runtime skill's "The FFM
//! boundary" has the layout). [`Put`] writes a value, [`Take`] reads one; the spec's own types'
//! are generated (`generated/codecs.rs`), the language's are here.

use std::collections::HashMap;
use std::hash::Hash;
use std::path::PathBuf;

use netherforge_format::math::{BoundingBox, Matrix4, Vec3};
use netherforge_platform::{ChunkGrid, Dynamic, PlatformError, Uuid};

use crate::objects::{self, Dispatch};

/// A value that can be written to the wire. By value: what's written is handed over.
pub trait Put {
    fn put(self, w: &mut Writer);
}

/// A value that can be read from the wire. A malformed buffer is a bug in the glue: it panics.
pub trait Take: Sized {
    fn take(r: &mut Reader) -> Self;
}

/// Where a call's arguments or answer are written.
#[derive(Default)]
pub struct Writer {
    pub bytes: Vec<u8>,
}

impl Writer {
    pub fn new() -> Writer {
        Writer::default()
    }

    pub fn u8(&mut self, v: u8) {
        self.bytes.push(v);
    }

    pub fn u32(&mut self, v: u32) {
        self.bytes.extend_from_slice(&v.to_le_bytes());
    }

    pub fn u64(&mut self, v: u64) {
        self.bytes.extend_from_slice(&v.to_le_bytes());
    }

    /// A count, as a list or a string is preceded by.
    pub fn count(&mut self, n: usize) {
        self.u32(u32::try_from(n).expect("a count past u32 doesn't cross"));
    }

    pub fn str(&mut self, s: &str) {
        self.count(s.len());
        self.bytes.extend_from_slice(s.as_bytes());
    }

    /// A value as its file text (format's types).
    pub fn json<T: serde::Serialize>(&mut self, v: &T) {
        self.str(&serde_json::to_string(v).expect("format's types serialize"));
    }

    /// Hands out [dispatch] by id: the JVM calls it until it lets go.
    pub fn export(&mut self, dispatch: Dispatch) {
        self.u8(objects::RUST);
        self.u64(objects::export(dispatch));
    }

    /// One of Rust's objects made from the `Rc` at [key]: the same reference each time ([objects::export_keyed]).
    pub fn export_rc(&mut self, key: usize, make: impl FnOnce() -> Dispatch) {
        self.u8(objects::RUST);
        self.u64(objects::export_keyed(key, make));
    }
}

/// Where a call's arguments or answer are read from.
pub struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Reader<'a> {
        Reader { bytes, at: 0 }
    }

    /// Whether everything was read.
    pub fn done(&self) -> bool {
        self.at == self.bytes.len()
    }

    pub fn raw(&mut self, n: usize) -> &'a [u8] {
        let end = self
            .at
            .checked_add(n)
            .filter(|&end| end <= self.bytes.len());
        let Some(end) = end else {
            panic!(
                "the wire ended early: {n} bytes wanted at {} of {}",
                self.at,
                self.bytes.len()
            )
        };
        let out = &self.bytes[self.at..end];
        self.at = end;
        out
    }

    pub fn u8(&mut self) -> u8 {
        self.raw(1)[0]
    }

    pub fn u32(&mut self) -> u32 {
        u32::from_le_bytes(self.raw(4).try_into().unwrap())
    }

    pub fn u64(&mut self) -> u64 {
        u64::from_le_bytes(self.raw(8).try_into().unwrap())
    }

    pub fn count(&mut self) -> usize {
        self.u32() as usize
    }

    pub fn str(&mut self) -> String {
        let n = self.count();
        match std::str::from_utf8(self.raw(n)) {
            Ok(s) => s.to_string(),
            Err(_) => panic!("a string on the wire isn't UTF-8"),
        }
    }

    /// An enum's entry by its number.
    pub fn entry<T: Copy>(&mut self, all: &[T], what: &str) -> T {
        let n = self.u32() as usize;
        *all.get(n)
            .unwrap_or_else(|| panic!("{what} has no entry {n}"))
    }

    pub fn json<T: serde::de::DeserializeOwned>(&mut self, what: &str) -> T {
        let text = self.str();
        serde_json::from_str(&text)
            .unwrap_or_else(|e| panic!("the server's {what} doesn't read: {e} ({text})"))
    }
}

macro_rules! numbers {
    ($($t:ty),*) => {$(
        impl Put for $t {
            fn put(self, w: &mut Writer) {
                w.bytes.extend_from_slice(&self.to_le_bytes());
            }
        }

        impl Take for $t {
            fn take(r: &mut Reader) -> Self {
                <$t>::from_le_bytes(r.raw(size_of::<$t>()).try_into().unwrap())
            }
        }
    )*};
}

numbers!(i8, i16, i32, i64, u8, u16, u32, u64, f32, f64);

impl Put for () {
    fn put(self, _: &mut Writer) {}
}

impl Take for () {
    fn take(_: &mut Reader) -> Self {}
}

impl Put for bool {
    fn put(self, w: &mut Writer) {
        w.u8(self as u8);
    }
}

impl Take for bool {
    fn take(r: &mut Reader) -> Self {
        r.u8() != 0
    }
}

impl Put for String {
    fn put(self, w: &mut Writer) {
        w.str(&self);
    }
}

impl Take for String {
    fn take(r: &mut Reader) -> Self {
        r.str()
    }
}

impl Put for Uuid {
    fn put(self, w: &mut Writer) {
        w.u64((self.0 >> 64) as u64);
        w.u64(self.0 as u64);
    }
}

impl Take for Uuid {
    fn take(r: &mut Reader) -> Self {
        let high = r.u64() as u128;
        Uuid((high << 64) | r.u64() as u128)
    }
}

impl<T: Put> Put for Option<T> {
    fn put(self, w: &mut Writer) {
        match self {
            None => w.u8(0),
            Some(v) => {
                w.u8(1);
                v.put(w);
            }
        }
    }
}

impl<T: Take> Take for Option<T> {
    fn take(r: &mut Reader) -> Self {
        match r.u8() {
            0 => None,
            _ => Some(T::take(r)),
        }
    }
}

impl<T: Put> Put for Vec<T> {
    fn put(self, w: &mut Writer) {
        w.count(self.len());
        for v in self {
            v.put(w);
        }
    }
}

impl<T: Take> Take for Vec<T> {
    fn take(r: &mut Reader) -> Self {
        let n = r.count();
        (0..n).map(|_| T::take(r)).collect()
    }
}

impl<K: Put, V: Put> Put for HashMap<K, V> {
    fn put(self, w: &mut Writer) {
        w.count(self.len());
        for (k, v) in self {
            k.put(w);
            v.put(w);
        }
    }
}

impl<K: Take + Eq + Hash, V: Take> Take for HashMap<K, V> {
    fn take(r: &mut Reader) -> Self {
        let n = r.count();
        (0..n).map(|_| (K::take(r), V::take(r))).collect()
    }
}

impl<A: Put, B: Put> Put for (A, B) {
    fn put(self, w: &mut Writer) {
        self.0.put(w);
        self.1.put(w);
    }
}

impl<A: Take, B: Take> Take for (A, B) {
    fn take(r: &mut Reader) -> Self {
        let a = A::take(r);
        (a, B::take(r))
    }
}

impl<A: Put, B: Put, C: Put> Put for (A, B, C) {
    fn put(self, w: &mut Writer) {
        self.0.put(w);
        self.1.put(w);
        self.2.put(w);
    }
}

impl<A: Take, B: Take, C: Take> Take for (A, B, C) {
    fn take(r: &mut Reader) -> Self {
        let a = A::take(r);
        let b = B::take(r);
        (a, b, C::take(r))
    }
}

impl Put for PlatformError {
    fn put(self, w: &mut Writer) {
        self.message.put(w);
        self.cause.put(w);
    }
}

impl Take for PlatformError {
    fn take(r: &mut Reader) -> Self {
        let message = r.str();
        PlatformError {
            message,
            cause: Take::take(r),
        }
    }
}

impl<T: Put> Put for Result<T, PlatformError> {
    fn put(self, w: &mut Writer) {
        match self {
            Ok(v) => {
                w.u8(0);
                v.put(w);
            }
            Err(e) => {
                w.u8(1);
                e.put(w);
            }
        }
    }
}

impl<T: Take> Take for Result<T, PlatformError> {
    fn take(r: &mut Reader) -> Self {
        match r.u8() {
            0 => Ok(T::take(r)),
            _ => Err(PlatformError::take(r)),
        }
    }
}

/// A JSON-like value: a tag (0 null, 1 a boolean, 2 a number, 3 a string, 4 a list, 5 a map) and
/// what it holds.
impl Put for Dynamic {
    fn put(self, w: &mut Writer) {
        match self {
            Dynamic::Null => w.u8(0),
            Dynamic::Bool(b) => {
                w.u8(1);
                b.put(w);
            }
            Dynamic::Number(n) => {
                w.u8(2);
                n.put(w);
            }
            Dynamic::String(s) => {
                w.u8(3);
                w.str(&s);
            }
            Dynamic::List(items) => {
                w.u8(4);
                items.put(w);
            }
            Dynamic::Map(entries) => {
                w.u8(5);
                entries.put(w);
            }
        }
    }
}

impl Take for Dynamic {
    fn take(r: &mut Reader) -> Self {
        match r.u8() {
            0 => Dynamic::Null,
            1 => Dynamic::Bool(bool::take(r)),
            2 => Dynamic::Number(f64::take(r)),
            3 => Dynamic::String(r.str()),
            4 => Dynamic::List(Take::take(r)),
            5 => Dynamic::Map(Take::take(r)),
            tag => panic!("a dynamic value has no tag {tag}"),
        }
    }
}

impl Put for PathBuf {
    fn put(self, w: &mut Writer) {
        w.str(&self.to_string_lossy());
    }
}

impl Take for PathBuf {
    fn take(r: &mut Reader) -> Self {
        PathBuf::from(r.str())
    }
}

impl Put for Vec3 {
    fn put(self, w: &mut Writer) {
        (self.0, self.1, self.2).put(w);
    }
}

impl Take for Vec3 {
    fn take(r: &mut Reader) -> Self {
        let (x, y, z) = Take::take(r);
        Vec3(x, y, z)
    }
}

impl Put for BoundingBox {
    fn put(self, w: &mut Writer) {
        self.min.put(w);
        self.max.put(w);
    }
}

impl Take for BoundingBox {
    fn take(r: &mut Reader) -> Self {
        let min = Vec3::take(r);
        BoundingBox {
            min,
            max: Vec3::take(r),
        }
    }
}

/// Its sixteen numbers, column by column.
impl Put for Matrix4 {
    fn put(self, w: &mut Writer) {
        for v in self.values {
            v.put(w);
        }
    }
}

impl Take for Matrix4 {
    fn take(r: &mut Reader) -> Self {
        let mut values = [0.0; 16];
        for v in &mut values {
            *v = f64::take(r);
        }
        Matrix4 { values }
    }
}

/// Its positions that hold something: their count, then each one's x, y, z (i32) and value (u16).
impl Put for ChunkGrid {
    fn put(self, w: &mut Writer) {
        w.count(self.size());
        self.for_each(|x, y, z, value| {
            (x, y, z, value).put(w);
        });
    }
}

impl<A: Put, B: Put, C: Put, D: Put> Put for (A, B, C, D) {
    fn put(self, w: &mut Writer) {
        self.0.put(w);
        self.1.put(w);
        self.2.put(w);
        self.3.put(w);
    }
}

impl Take for ChunkGrid {
    fn take(r: &mut Reader) -> Self {
        let mut grid = ChunkGrid::new();
        for _ in 0..r.count() {
            let (x, y, z) = <(i32, i32, i32)>::take(r);
            grid.set(x, y, z, u16::take(r));
        }
        grid
    }
}
