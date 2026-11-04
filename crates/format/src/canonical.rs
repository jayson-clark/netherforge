//! What every model's canonical form is built from: maps in format's order
//! (`CanonicalJson.ordered`), and writing a model as a file.

use std::cmp::Ordering;
use std::fmt;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::json::print;

/// A map key in format's order: whole-number keys first, as numbers (slot "2"
/// before "13"), then the rest as strings. Enum keys don't need this: their
/// derived order is their declaration's, which is format's for them.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Key(pub String);

impl Key {
    fn number(&self) -> Option<i32> {
        // Kotlin's `toIntOrNull`: an optional sign and digits, within an i32.
        self.0.parse::<i32>().ok()
    }
}

impl Ord for Key {
    fn cmp(&self, other: &Self) -> Ordering {
        let a = self.number().unwrap_or(i32::MAX);
        let b = other.number().unwrap_or(i32::MAX);
        a.cmp(&b).then_with(|| self.0.cmp(&other.0))
    }
}

impl PartialOrd for Key {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl fmt::Display for Key {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl From<&str> for Key {
    fn from(s: &str) -> Key {
        Key(s.to_owned())
    }
}

impl Serialize for Key {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for Key {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Key, D::Error> {
        String::deserialize(d).map(Key)
    }
}

/// A string-keyed map in format's order.
pub type Map<V> = std::collections::BTreeMap<Key, V>;

/// `value` as its file: printed by the canonical rules, and a newline.
pub fn write<T: Serialize>(value: &T) -> String {
    let json = serde_json::to_value(value).expect("a model is always JSON");
    format!("{}\n", print(&json))
}

/// Sorted and without repeats: the canonical form of a list that's a set.
pub fn set(list: &[String]) -> Vec<String> {
    let mut out = list.to_vec();
    out.sort();
    out.dedup();
    out
}
