//! The events registry's types: what `:on` checks a name against, and what the
//! runtime raises. `registry` is generated from the spec.

#[rustfmt::skip]
#[allow(clippy::all)]
#[path = "generated/events.rs"]
mod registry;

pub use registry::{BY_CLASS, EVENTS};

/// One event on one class. `owner` is the class that declares it (`"Node"`),
/// or `"nf"` for a server-wide event.
#[derive(Debug, PartialEq, Eq)]
pub struct EventType {
    pub owner: &'static str,
    pub name: &'static str,
    /// The payload's shape (its codec is `codecs::<payload>`), or `None` for a
    /// bare `Event`.
    pub payload: Option<&'static str>,
    /// `event:cancel()` cancels what caused it.
    pub cancellable: bool,
    /// It goes on along a path after this handle: a node's click to its
    /// parent's, and so on.
    pub bubbles: bool,
    /// Payload fields handlers may assign, read back after they have all run.
    pub writable: &'static [&'static str],
    /// The fields of `EventOptions` that `:on` takes for it (`every`, for `tick`).
    pub options: &'static [&'static str],
    /// Delivered only to the scope that registered it.
    pub local: bool,
    /// The oldest Minecraft version that has it, or `None` for every supported one.
    pub since: Option<&'static str>,
}

/// The event `name` means on a handle of `class` (`"nf"` for `nf.on`), its
/// own or inherited, or `None` when it has none.
pub fn of(class: &str, name: &str) -> Option<&'static EventType> {
    let (_, events) = BY_CLASS.iter().find(|(c, _)| *c == class)?;
    events
        .iter()
        .find(|(n, _)| *n == name)
        .map(|(_, index)| &EVENTS[*index])
}

/// Every event name `class` has, for a mistake's "one of".
pub fn names(class: &str) -> Vec<&'static str> {
    BY_CLASS
        .iter()
        .find(|(c, _)| *c == class)
        .map(|(_, events)| events.iter().map(|(n, _)| *n).collect())
        .unwrap_or_default()
}

/// A custom event's name has a `:` (`shop:bought`): any script may raise one.
pub fn is_custom(name: &str) -> bool {
    name.contains(':')
}
