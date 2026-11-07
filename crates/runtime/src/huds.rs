//! Every online player's HUD (Kotlin's `Huds` and `HudImpl`): what scripts set of the resource packs' HUD elements,
//! drawn in the player's action bar by format's `hud::compose`.
//!
//! The action bar fades a few seconds after it's sent, so a player whose HUD shows anything is sent it again every
//! `RESEND` ticks, and on the next tick when something changed. A script's own line (`player:send_actionbar`) is
//! drawn in the middle of the HUD's line for `MESSAGE` ticks when it can be measured, else shown on its own for that
//! long. Nothing here is saved: a player's HUD goes when they leave. A pack's rebuild renumbers its pictures, so every
//! HUD is drawn again after it.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::Rc;

use netherforge_api::api::HudApi;
use netherforge_api::{ApiError, ApiResult, handles, shapes, values};
use netherforge_format::kind::resource_pack::fonts;
use netherforge_format::kind::resource_pack::hud::{self, BarFill, CompiledHud, HudState};
use netherforge_format::reference::RefKind;
use netherforge_platform::{Platform, PlayerRef, Uuid};

use crate::Room;
use crate::events::parse_uuid;
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// Ticks between sending a HUD again: well before the action bar fades.
pub const RESEND: u64 = 20;
/// Ticks a script's action bar line shows with the HUD.
pub const MESSAGE: u64 = 60;

struct Shown {
    state: HudState,
    dirty: bool,
    sent: Option<u64>,
    message: Option<String>,
    message_until: u64,
}

impl Shown {
    fn new() -> Shown {
        Shown {
            state: HudState::default(),
            dirty: true,
            sent: None,
            message: None,
            message_until: 0,
        }
    }
}

fn is_empty(state: &HudState) -> bool {
    state.bars.is_empty() && state.icons.is_empty() && state.texts.is_empty()
}

#[derive(Default)]
pub struct Huds {
    shown: HashMap<Uuid, Shown>,
}

impl Huds {
    /// What `player`'s HUD shows.
    pub fn state(&self, player: Uuid) -> HudState {
        self.shown
            .get(&player)
            .map(|s| s.state.clone())
            .unwrap_or_default()
    }

    /// Changes what `player`'s HUD shows; drawn again on the next tick.
    pub fn change(
        &mut self,
        platform: &dyn Platform,
        player: Uuid,
        change: impl FnOnce(&mut HudState),
    ) {
        let entry = self.shown.entry(player).or_insert_with(Shown::new);
        let mut next = entry.state.clone();
        change(&mut next);
        if next == entry.state {
            return;
        }
        entry.state = next;
        entry.dirty = true;
        if is_empty(&entry.state) && entry.message.is_none() {
            self.shown.remove(&player);
            // What was drawn stays until it fades; nothing more is sent.
            platform.players().actionbar(player, String::new());
        }
    }

    /// A script's action bar line for `player`: shown with their HUD when they have one (true), else for the caller
    /// to send as it is (false).
    pub fn message(&mut self, player: Uuid, text: &str, now: u64) -> bool {
        let Some(entry) = self.shown.get_mut(&player).filter(|e| !is_empty(&e.state)) else {
            return false;
        };
        entry.message = Some(text.to_owned());
        entry.message_until = now + MESSAGE;
        entry.dirty = true;
        true
    }

    fn redraw_all(&mut self) {
        for entry in self.shown.values_mut() {
            entry.dirty = true;
        }
    }
}

/// The part of a state naming `pack`'s elements, by their key in it.
fn of_pack(state: &HudState, pack: &str) -> HudState {
    fn mine<V: Clone>(map: &BTreeMap<String, V>, pack: &str) -> BTreeMap<String, V> {
        map.iter()
            .filter_map(|(k, v)| {
                let (p, key) = k.rsplit_once('/')?;
                (p == pack).then(|| (key.to_owned(), v.clone()))
            })
            .collect()
    }
    HudState {
        bars: mine(&state.bars, pack),
        icons: mine(&state.icons, pack),
        texts: mine(&state.texts, pack),
    }
}

/// The line `state` (and a script's `message`) draws: every pack's elements, the line in the middle.
fn line(
    huds: &BTreeMap<String, CompiledHud>,
    state: &HudState,
    message: Option<&str>,
    measure: &dyn Fn(&str) -> Option<i32>,
) -> String {
    let wide = message.and_then(measure);
    // A line that can't be measured would move every element by half its width: it's shown on its own.
    if let (Some(message), None) = (message, wide) {
        return message.to_owned();
    }
    let mut out = String::new();
    for (pack, compiled) in huds {
        let own = of_pack(state, pack);
        if !is_empty(&own) {
            out.push_str(&hud::compose(compiled, &own, measure));
        }
    }
    if let (Some(message), Some(wide)) = (message, wide) {
        let Some(font) = huds.values().next().map(|h| h.font.clone()) else {
            return message.to_owned();
        };
        let shift = |px: i32| {
            format!(
                "<white><!shadow><font:{font}>{}</font></!shadow></white>",
                fonts::offset(px)
            )
        };
        out.push_str(&shift(-wide / 2));
        out.push_str(message);
        out.push_str("<reset>");
        out.push_str(&shift(-(wide - wide / 2)));
    }
    out
}

/// What to say of an element that's the wrong kind: `ui/mana` is a bar.
fn kind_of(hud: &CompiledHud, key: &str) -> Option<&'static str> {
    if hud.bars.contains_key(key) {
        Some("a bar")
    } else if hud.icons.contains_key(key) {
        Some("a row of icons")
    } else if hud.texts.contains_key(key) {
        Some("a line of text")
    } else {
        None
    }
}

impl GameContext {
    /// The player, when they're online.
    fn hud_player(&self, this: &handles::Hud) -> Option<Uuid> {
        parse_uuid(&this.player).filter(|id| self.shared.platform.players().get(*id).is_some())
    }

    /// `name` as the server knows it (`ui/mana`, `library:ui/mana`), checked to be the element `wanted` says.
    fn hud_element(
        &self,
        name: &str,
        wanted: &str,
        has: impl Fn(&CompiledHud, &str) -> bool,
    ) -> ApiResult<String> {
        let known = self.with_names(|names| names.look_name(RefKind::Hud, name))?;
        let (pack, key) = known.rsplit_once('/').unwrap_or(("", known.as_str()));
        let huds = self.shared.packs.borrow().huds();
        let Some(compiled) = huds.get(pack) else {
            return Err(err(format!(
                "resource pack \"{pack}\" has errors, so its HUD isn't built"
            )));
        };
        if !has(compiled, key) {
            return Err(err(format!(
                "\"{name}\" is {}, not {wanted}",
                kind_of(compiled, key).unwrap_or("no HUD element")
            )));
        }
        Ok(known)
    }

    /// Bar `name` as the server knows it, and how many segments it has.
    fn hud_bar(&self, name: &str) -> ApiResult<(String, i32)> {
        let segments = std::cell::Cell::new(0);
        let known = self.hud_element(name, "a bar", |hud, key| {
            hud.bars
                .get(key)
                .map(|b| segments.set(b.def.segments))
                .is_some()
        })?;
        Ok((known, segments.get()))
    }

    fn change_hud(&self, player: Uuid, change: impl FnOnce(&mut HudState)) {
        self.shared
            .huds
            .borrow_mut()
            .change(&*self.shared.platform, player, change);
    }
}

fn check_fill(value: f64, max: f64) -> ApiResult<()> {
    if !(max.is_finite() && max > 0.0) {
        return Err(err(format!(
            "bad argument 'max' (a bar's max is more than 0, got {max})"
        )));
    }
    if !value.is_finite() {
        return Err(err(format!("bad argument 'value' (a number, got {value})")));
    }
    Ok(())
}

impl HudApi for GameContext {
    fn player(&self, this: &handles::Hud) -> ApiResult<handles::Player> {
        Ok(handles::Player {
            id: this.player.clone(),
        })
    }

    fn fill_bar(
        &self,
        this: &handles::Hud,
        name: String,
        value: f64,
        max: f64,
        options: Option<shapes::HudFillOptions>,
    ) -> ApiResult<bool> {
        let (known, own) = self.hud_bar(&name)?;
        check_fill(value, max)?;
        let shown = match options.and_then(|o| o.segments) {
            Some(n) if !(1..=i64::from(own)).contains(&n) => {
                return Err(err(format!(
                    "bad argument 'options.segments' (1 to {own} for \"{name}\", got {n})"
                )));
            }
            n => n.map(|n| n as i32),
        };
        let Some(player) = self.hud_player(this) else {
            return Ok(false);
        };
        self.change_hud(player, |state| {
            state.bars.insert(
                known,
                BarFill {
                    value,
                    max,
                    segments: shown,
                },
            );
        });
        Ok(true)
    }

    fn fill_bars(
        &self,
        this: &handles::Hud,
        names: Vec<String>,
        value: f64,
        max: f64,
        per_segment: f64,
    ) -> ApiResult<i64> {
        let bars: Vec<(String, i32)> = names
            .iter()
            .map(|n| self.hud_bar(n))
            .collect::<ApiResult<_>>()?;
        check_fill(value, max)?;
        if !(per_segment.is_finite() && per_segment > 0.0) {
            return Err(err(format!(
                "bad argument 'per_segment' (more than 0, got {per_segment})"
            )));
        }
        let Some(player) = self.hud_player(this) else {
            return Ok(0);
        };
        // As many segments as the max needs (a hair under a whole one doesn't make another), as far as the bars go.
        let wanted = (max / per_segment - 1e-9).ceil() as i32;
        let mut left = wanted.min(bars.iter().map(|(_, n)| n).sum());
        let total = left;
        let mut from = 0.0;
        self.change_hud(player, |state| {
            for (known, own) in &bars {
                let count = (*own).min(left);
                left -= count;
                if count == 0 {
                    state.bars.remove(known);
                } else {
                    let span = f64::from(count) * per_segment;
                    state.bars.insert(
                        known.clone(),
                        BarFill {
                            value: (value - from).clamp(0.0, span),
                            max: span,
                            segments: Some(count),
                        },
                    );
                    from += span;
                }
            }
        });
        Ok(i64::from(total))
    }

    fn bar(&self, this: &handles::Hud, name: String) -> ApiResult<Option<shapes::HudBar>> {
        let (known, own) = self.hud_bar(&name)?;
        let Some(player) = self.hud_player(this) else {
            return Ok(None);
        };
        Ok(self
            .shared
            .huds
            .borrow()
            .state(player)
            .bars
            .get(&known)
            .map(|fill| shapes::HudBar {
                value: fill.value,
                max: fill.max,
                segments: i64::from(fill.segments.unwrap_or(own)),
            }))
    }

    fn set_icons(&self, this: &handles::Hud, name: String, icons: Vec<String>) -> ApiResult<bool> {
        let row = std::cell::RefCell::new(Vec::new());
        let known = self.hud_element(&name, "a row of icons", |hud, key| {
            hud.icons
                .get(key)
                .map(|r| *row.borrow_mut() = r.icons.keys().cloned().collect())
                .is_some()
        })?;
        let row = row.into_inner();
        if let Some((index, icon)) = icons.iter().enumerate().find(|(_, i)| !row.contains(i)) {
            return Err(err(format!(
                "bad argument 'icons[{}]' (\"{name}\" has no icon \"{icon}\": {})",
                index + 1,
                row.join(", ")
            )));
        }
        let Some(player) = self.hud_player(this) else {
            return Ok(false);
        };
        self.change_hud(player, |state| {
            state.icons.insert(known, icons);
        });
        Ok(true)
    }

    fn icons(&self, this: &handles::Hud, name: String) -> ApiResult<Option<Vec<String>>> {
        let known = self.hud_element(&name, "a row of icons", |hud, key| {
            hud.icons.contains_key(key)
        })?;
        let Some(player) = self.hud_player(this) else {
            return Ok(None);
        };
        Ok(self
            .shared
            .huds
            .borrow()
            .state(player)
            .icons
            .get(&known)
            .cloned())
    }

    fn set_text(&self, this: &handles::Hud, name: String, text: values::Text) -> ApiResult<bool> {
        let known = self.hud_element(&name, "a line of text", |hud, key| {
            hud.texts.contains_key(key)
        })?;
        let Some(player) = self.hud_player(this) else {
            return Ok(false);
        };
        self.change_hud(player, |state| {
            state.texts.insert(known, text.0);
        });
        Ok(true)
    }

    fn text(&self, this: &handles::Hud, name: String) -> ApiResult<Option<values::Text>> {
        let known = self.hud_element(&name, "a line of text", |hud, key| {
            hud.texts.contains_key(key)
        })?;
        let Some(player) = self.hud_player(this) else {
            return Ok(None);
        };
        Ok(self
            .shared
            .huds
            .borrow()
            .state(player)
            .texts
            .get(&known)
            .cloned()
            .map(values::Text))
    }

    fn remove(&self, this: &handles::Hud, name: String) -> ApiResult<bool> {
        let known = self.hud_element(&name, "a HUD element", |hud, key| {
            kind_of(hud, key).is_some()
        })?;
        let Some(player) = self.hud_player(this) else {
            return Ok(false);
        };
        let before = self.shared.huds.borrow().state(player);
        if !before.bars.contains_key(&known)
            && !before.icons.contains_key(&known)
            && !before.texts.contains_key(&known)
        {
            return Ok(false);
        }
        self.change_hud(player, |state| {
            state.bars.remove(&known);
            state.icons.remove(&known);
            state.texts.remove(&known);
        });
        Ok(true)
    }

    fn clear(&self, this: &handles::Hud) -> ApiResult<()> {
        if let Some(player) = self.hud_player(this) {
            self.change_hud(player, |state| *state = HudState::default());
        }
        Ok(())
    }
}

/// The HUDs service: sends each player's line when it changed, and again before it fades.
pub struct HudsService {
    pub shared: Rc<Shared>,
}

impl Service for HudsService {
    fn name(&self) -> &'static str {
        "huds"
    }

    fn phase(&self) -> &'static str {
        "effects"
    }

    fn tick(&self, room: &Room, platform: &dyn Platform) {
        if self.shared.huds.borrow().shown.is_empty() {
            return;
        }
        let now = room.scripts().tick_count();
        let packs = self.shared.packs.borrow().huds();
        let measure = |text: &str| self.shared.measure_text(text).map(|(w, _)| w);
        let mut sends = Vec::new();
        {
            let mut huds = self.shared.huds.borrow_mut();
            for (player, entry) in huds.shown.iter_mut() {
                if entry.message.is_some() && now >= entry.message_until {
                    entry.message = None;
                    entry.dirty = true;
                }
                if !entry.dirty && entry.sent.is_some_and(|sent| now - sent < RESEND) {
                    continue;
                }
                entry.dirty = false;
                entry.sent = Some(now);
                sends.push((
                    *player,
                    line(&packs, &entry.state, entry.message.as_deref(), &measure),
                ));
            }
        }
        for (player, text) in sends {
            platform.players().actionbar(player, text);
        }
    }

    fn player_quit(&self, player: &PlayerRef) {
        self.shared.huds.borrow_mut().shown.remove(&player.uuid);
    }

    /// The packs rebuilt: their pictures may be other characters now.
    fn reloaded(&self, _room: &Room, kinds: &BTreeSet<&str>, _project: &Define) {
        if kinds.contains("resource_pack") {
            self.shared.huds.borrow_mut().redraw_all();
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        *self.shared.huds.borrow_mut() = Huds::default();
    }
}
