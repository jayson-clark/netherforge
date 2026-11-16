//! A centity's health as it runs (Kotlin's `Health` and the bar half of `Healths`): `centity.json`'s
//! `health`, or `set_max_health`'s, with what it has, whether it's dead, and its bar, a text node (the file's
//! own `health_bar`, or one added a little over its hitboxes) shown for a while after a change. Not saved.
//! Damage and death, which raise events, are `CentityContext`'s.

use netherforge_format::game::GameData;
use netherforge_format::kind::centity::compiled::{ResolvedBar, ResolvedHealth, compile_node};
use netherforge_format::kind::centity::{DisplayDef, HEALTH_BAR_NODE, NodeDef, Transform};
use netherforge_format::math::Vec3;

use super::Instance;

/// The added bar's text, a little smaller than a name tag.
const BAR_SCALE: f64 = 0.7;

#[derive(Clone, Debug, PartialEq)]
pub struct Health {
    pub max: f64,
    pub current: f64,
    pub remove_on_death: bool,
    pub bar: Option<ResolvedBar>,
    pub dead: bool,
    /// The server tick its bar hides at, or none while it's hidden (or always shown).
    pub shown_until: Option<u64>,
}

impl Health {
    pub fn of(resolved: &ResolvedHealth) -> Health {
        Health {
            max: resolved.max,
            current: resolved.max,
            remove_on_death: resolved.remove_on_death,
            bar: resolved.bar.clone(),
            dead: false,
            shown_until: None,
        }
    }
}

/// Gives a new or reloaded instance the health its file says, keeping what it had (within the new most).
pub fn adopt(game: &dyn GameData, instance: &mut Instance) {
    let had = instance.health.take();
    instance.health = instance.definition.health.as_ref().map(|r| {
        let mut fresh = Health::of(r);
        if let Some(had) = &had {
            fresh.current = had.current.min(fresh.max);
            fresh.dead = had.dead;
        }
        fresh
    });
    if instance.health.as_ref().is_some_and(|h| h.bar.is_some()) {
        bar_node(game, instance);
        draw(instance, false, 0);
    }
}

/// The bar's node: the file's own `health_bar` text node, or one added a little over its hitboxes.
fn bar_node(game: &dyn GameData, instance: &mut Instance) {
    if instance.index_of(HEALTH_BAR_NODE).is_some() {
        return;
    }
    let Some(bar) = instance.health.as_ref().and_then(|h| h.bar.clone()) else {
        return;
    };
    let world = instance.placed().to_vec();
    let mut top: Option<f64> = None;
    for (index, node) in instance.definition.nodes.iter().enumerate() {
        if node.hitbox.is_none() || !instance.shown(index) {
            continue;
        }
        let b = super::hitboxes::bounds_of(&super::boxes_of(game, instance, index), &world[index]);
        top = Some(top.map_or(b.max.1, |t: f64| t.max(b.max.1)));
    }
    let node = NodeDef {
        transform: Some(Transform {
            translation: Some(Vec3(0.0, top.unwrap_or(0.0) + bar.offset, 0.0)),
            rotation: None,
            scale: Some(Vec3(BAR_SCALE, BAR_SCALE, BAR_SCALE)),
        }),
        display: Some(DisplayDef::Text {
            text: String::new(),
            billboard: None,
            alignment: None,
            background: Some("#00000000".into()),
            line_width: None,
            see_through: None,
            shadow: Some(true),
            anchor: None,
            runs: None,
        }),
        ..NodeDef::default()
    };
    instance.add_node(compile_node(HEALTH_BAR_NODE, &node, -1));
}

/// Writes the bar's text and shows it (for its `show_ticks` from `now`, or always), or keeps it as it is
/// shown or hidden.
pub fn draw(instance: &mut Instance, show: bool, now: u64) {
    let Some(health) = instance.health.clone() else {
        return;
    };
    let Some(bar) = &health.bar else {
        return;
    };
    let Some(index) = instance.index_of(HEALTH_BAR_NODE) else {
        return;
    };
    let Some(DisplayDef::Text { .. }) = instance.display(index) else {
        return;
    };
    let full = if health.max <= 0.0 {
        0
    } else {
        ((health.current / health.max * f64::from(bar.segments)).ceil() as i32)
            .clamp(0, bar.segments)
    };
    let text = format!(
        "{}{}{}{}",
        bar.filled,
        bar.glyph.repeat(full as usize),
        bar.empty,
        bar.glyph.repeat((bar.segments - full).max(0) as usize)
    );
    if let Some(mut display @ DisplayDef::Text { .. }) = instance.display(index).cloned()
        && let DisplayDef::Text { text: now, .. } = &mut display
        && *now != text
    {
        *now = text;
        instance.set_display(index, display);
    }
    let mut shown_until = health.shown_until;
    if bar.show_ticks == 0 {
        instance.set_visible(index, true);
    } else if show {
        instance.set_visible(index, true);
        shown_until = Some(now + bar.show_ticks as u64);
    } else if shown_until.is_none() {
        instance.set_visible(index, false);
    }
    if let Some(h) = &mut instance.health {
        h.shown_until = shown_until;
    }
}

/// Hides a bar whose time to show is up: once a tick.
pub fn hide_bar(instance: &mut Instance, now: u64) {
    let Some(until) = instance.health.as_ref().and_then(|h| h.shown_until) else {
        return;
    };
    if now < until {
        return;
    }
    if let Some(h) = &mut instance.health {
        h.shown_until = None;
    }
    if let Some(index) = instance.index_of(HEALTH_BAR_NODE) {
        instance.set_visible(index, false);
    }
}
