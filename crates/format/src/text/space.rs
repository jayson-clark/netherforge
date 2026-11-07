//! Moving the cursor inside MiniMessage text (format's `SpaceTags`).
//!
//! - `<space:N>` moves it N GUI pixels (negative is left), as the space
//!   characters the pack's default font holds (`fonts::offset`). It inserts
//!   them and closes nothing; N is held to ±`MAX_OFFSET`.
//! - `<at:N>…</at>` draws what's inside N pixels from the cursor and takes no
//!   width: the server measures the inside (`TextWidth::layout`) and moves the
//!   cursor back after it, so the text after it doesn't shift. Options follow
//!   as `key=value` arguments: `from=cursor|start|center|end` (what N is
//!   counted from: the cursor, or the start, centre or end of the line the tag
//!   is on, measured without anything inside an `<at>`) and
//!   `align=left|center|right` (which edge of the inside N puts there).
//!   N may be any whole number (the moves are as many spaces as it takes).
//!   `<at:-92:from=center:align=right><glyph:ui/head></at>` puts a picture's
//!   right edge 92 pixels left of a centred title's middle, whatever the title.
//!
//! Shared so the server's measure, its expansion and the editor's previews read
//! the same tags.

use crate::kind::resource_pack::MAX_OFFSET;

pub const SPACE: &str = "space";
pub const AT: &str = "at";

/// What an `<at>`'s N is counted from.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum From {
    #[default]
    Cursor,
    Start,
    Center,
    End,
}

/// Which edge of an `<at>`'s inside lands at N.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Align {
    #[default]
    Left,
    Center,
    Right,
}

/// An `<at>` tag read: how far, from what, and aligned how.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct At {
    pub x: i32,
    pub from: From,
    pub align: Align,
}

impl At {
    pub fn new(x: i32) -> At {
        At {
            x,
            from: From::Cursor,
            align: Align::Left,
        }
    }
}

/// A whole number as Kotlin's `toIntOrNull` reads one, after trimming.
fn whole(text: &str) -> Option<i32> {
    text.trim().parse().ok()
}

/// How far `<space:N>` moves, from the tag's arguments (after `space:`); none when they aren't one whole number.
pub fn space(arguments: &[String]) -> Option<i32> {
    let [only] = arguments else { return None };
    Some(whole(only)?.clamp(-MAX_OFFSET, MAX_OFFSET))
}

/// An `<at>` from its arguments (after `at:`): a whole number, then `from=…`
/// and `align=…` in any order. None when they don't read, and then the tag
/// only scopes what's inside it (it moves nothing).
pub fn at(arguments: &[String]) -> Option<At> {
    let mut at = At::new(whole(arguments.first()?)?);
    for option in &arguments[1..] {
        let (key, value) = option.split_once('=').unwrap_or((option, ""));
        let value = value.trim().to_uppercase();
        match key.trim().to_lowercase().as_str() {
            "from" => {
                at.from = match value.as_str() {
                    "CURSOR" => From::Cursor,
                    "START" => From::Start,
                    "CENTER" => From::Center,
                    "END" => From::End,
                    _ => return None,
                }
            }
            "align" => {
                at.align = match value.as_str() {
                    "LEFT" => Align::Left,
                    "CENTER" => Align::Center,
                    "RIGHT" => Align::Right,
                    _ => return None,
                }
            }
            _ => return None,
        }
    }
    Some(at)
}

/// The arguments of a tag's inside (`space:-3` → `-3`), split as MiniMessage splits them.
pub fn arguments(inside: &str) -> Vec<String> {
    match inside.split_once(':') {
        Some((_, rest)) => super::glyph::arguments(rest),
        None => Vec::new(),
    }
}

/// `<space:N>` as written.
pub fn tag(px: i32) -> String {
    format!("<{SPACE}:{px}>")
}
