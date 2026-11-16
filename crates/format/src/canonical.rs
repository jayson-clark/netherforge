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

impl Ord for Key {
    fn cmp(&self, other: &Self) -> Ordering {
        key_order(&self.0, &other.0)
    }
}

/// Format's order of two map keys: whole numbers first, as numbers, then the rest as strings.
pub fn key_order(a: &str, b: &str) -> Ordering {
    let number = |k: &str| k.parse::<i32>().ok().unwrap_or(i32::MAX);
    number(a).cmp(&number(b)).then_with(|| a.cmp(b))
}

impl PartialOrd for Key {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl std::ops::Deref for Key {
    type Target = str;
    fn deref(&self) -> &str {
        &self.0
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

/// A map as a file has it: read in the file's order (which is the order its
/// checks report problems in, as format's), written in format's order (by
/// `K`'s: [`Key`]'s for strings, declaration order for an enum).
#[derive(Clone, Debug, PartialEq)]
pub struct Entries<K, V>(pub Vec<(K, V)>);

/// A string-keyed map.
pub type Map<V> = Entries<Key, V>;

impl<K, V> Default for Entries<K, V> {
    fn default() -> Self {
        Entries(Vec::new())
    }
}

impl<K: PartialEq, V> Entries<K, V> {
    pub fn iter(&self) -> impl Iterator<Item = (&K, &V)> {
        self.0.iter().map(|(k, v)| (k, v))
    }
    pub fn len(&self) -> usize {
        self.0.len()
    }
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
    pub fn get(&self, key: &K) -> Option<&V> {
        self.0.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }
}

impl<V> Entries<Key, V> {
    /// The value for string key `key`.
    pub fn get_str(&self, key: &str) -> Option<&V> {
        self.0.iter().find(|(k, _)| k.0 == key).map(|(_, v)| v)
    }
}

impl<K, V> FromIterator<(K, V)> for Entries<K, V> {
    fn from_iter<I: IntoIterator<Item = (K, V)>>(iter: I) -> Self {
        Entries(iter.into_iter().collect())
    }
}

impl<K, V> IntoIterator for Entries<K, V> {
    type Item = (K, V);
    type IntoIter = std::vec::IntoIter<(K, V)>;
    fn into_iter(self) -> Self::IntoIter {
        self.0.into_iter()
    }
}

impl<K: Serialize + Ord, V: Serialize> Serialize for Entries<K, V> {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut sorted: Vec<&(K, V)> = self.0.iter().collect();
        sorted.sort_by(|a, b| a.0.cmp(&b.0));
        let mut map = s.serialize_map(Some(sorted.len()))?;
        for (k, v) in sorted {
            map.serialize_entry(k, v)?;
        }
        map.end()
    }
}

impl<'de, K: Deserialize<'de>, V: Deserialize<'de>> Deserialize<'de> for Entries<K, V> {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct Visit<K, V>(std::marker::PhantomData<(K, V)>);
        impl<'de, K: Deserialize<'de>, V: Deserialize<'de>> serde::de::Visitor<'de> for Visit<K, V> {
            type Value = Entries<K, V>;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("an object")
            }
            fn visit_map<A: serde::de::MapAccess<'de>>(
                self,
                mut map: A,
            ) -> Result<Self::Value, A::Error> {
                let mut out = Vec::new();
                while let Some(entry) = map.next_entry()? {
                    out.push(entry);
                }
                Ok(Entries(out))
            }
        }
        d.deserialize_map(Visit(std::marker::PhantomData))
    }
}

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

impl schemars::JsonSchema for Key {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "Key".into()
    }
    fn inline_schema() -> bool {
        true
    }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({ "type": "string" })
    }
}

/// A map's schema: an object of `V`s, keyed as `K` says (an enum's names).
impl<K: schemars::JsonSchema, V: schemars::JsonSchema> schemars::JsonSchema for Entries<K, V> {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        format!("Map_of_{}", V::schema_name()).into()
    }
    fn inline_schema() -> bool {
        true
    }
    fn json_schema(generator: &mut schemars::SchemaGenerator) -> schemars::Schema {
        let values = generator.subschema_for::<V>();
        let keys = generator.subschema_for::<K>();
        schemars::json_schema!({
            "type": "object",
            "additionalProperties": values,
            "propertyNames": keys,
        })
    }
}

/// Free-form JSON a file keeps for a script (an item's `data`): any value,
/// written with every object's keys sorted however deep (kotlinx's
/// `sortedJson`), so the same data is always the same text.
#[derive(Clone, Debug, PartialEq, Deserialize, schemars::JsonSchema)]
#[serde(transparent)]
pub struct Free(pub serde_json::Value);

impl Serialize for Free {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        fn sorted(v: &serde_json::Value) -> serde_json::Value {
            match v {
                serde_json::Value::Object(m) => {
                    let mut keys: Vec<&String> = m.keys().collect();
                    keys.sort();
                    serde_json::Value::Object(
                        keys.into_iter()
                            .map(|k| (k.clone(), sorted(&m[k])))
                            .collect(),
                    )
                }
                serde_json::Value::Array(items) => {
                    serde_json::Value::Array(items.iter().map(sorted).collect())
                }
                other => other.clone(),
            }
        }
        sorted(&self.0).serialize(s)
    }
}

/// An enum's name as files spell it: serde's, so it's written in one place.
pub fn name_of<T: Serialize>(value: &T) -> String {
    match serde_json::to_value(value) {
        Ok(serde_json::Value::String(name)) => name,
        _ => panic!("a name-only enum serializes as its name"),
    }
}
