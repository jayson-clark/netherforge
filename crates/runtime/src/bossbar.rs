//! Boss bars and sidebars (Kotlin's `BossBars`, `Sidebars`, `BossBarImpl`, `SidebarImpl`).
//!
//! **Boss bars** scripts make (`nf.bossbars.create`): what each shows and to whom, by UUID, so a viewer who
//! leaves sees it again when they come back (the server forgets a bar's viewers on quit). Each belongs to the
//! scope that made it and goes when that scope is released. One attached to a centity shows to who's near it
//! and follows its health, and goes (or comes off) when the centity does.
//!
//! **Sidebars**: each online player's title, lines and whether it shows, forgotten when they leave.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::rc::Rc;

use indexmap::IndexSet;
use netherforge_api::api::{BossBarApi, NfBossbarsApi, SidebarApi};
use netherforge_api::{ApiError, ApiResult, Caller, handles, shapes, values};
use netherforge_format::reference::RefKind;
use netherforge_luau::ScopeId;
use netherforge_platform::{BossBarLook, Platform, PlayerRef, Uuid};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::session::Service;
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// How near a player must be to an attached bar's centity to see it, unless told.
const DEFAULT_ATTACH_RANGE: f64 = 48.0;
/// At most this many lines on a sidebar: what Minecraft shows.
const SIDEBAR_LINES: usize = 15;
/// Half a boss bar's width: its left end is this far left of the screen's middle, where the game centres text.
const HALF_BAR: i32 = 91;

fn check_progress(progress: f64, name: &str) -> ApiResult<()> {
    if progress.is_nan() || !(0.0..=1.0).contains(&progress) {
        return Err(err(format!("{name} must be from 0 to 1, not {progress}")));
    }
    Ok(())
}

/// A bar's tie to a centity, and who it shows to for being near it.
struct Attachment {
    centity: Uuid,
    range: f64,
    health: bool,
    remove_with: bool,
    near: IndexSet<Uuid>,
}

struct Bar {
    scope: ScopeId,
    look: BossBarLook,
    /// The glyph at its left end, as the server names it.
    icon: Option<String>,
    viewers: IndexSet<Uuid>,
    attachment: Option<Attachment>,
}

impl Bar {
    /// What the game shows: the look, the icon placed in front of its text.
    fn shown(&self) -> BossBarLook {
        match &self.icon {
            Some(icon) => BossBarLook {
                text: format!(
                    "<at:{}:from=center:align=right><glyph:{icon}></at>{}",
                    -(HALF_BAR + 1),
                    self.look.text
                ),
                ..self.look.clone()
            },
            None => self.look.clone(),
        }
    }
}

/// The session's boss bars and sidebars.
#[derive(Default)]
pub struct Bars {
    next: i32,
    bars: BTreeMap<i32, Bar>,
    sidebars: HashMap<Uuid, Sidebar>,
}

#[derive(Default, Clone)]
struct Sidebar {
    title: Option<String>,
    lines: Vec<String>,
    visible: bool,
}

impl Bars {
    fn remove(&mut self, platform: &dyn Platform, id: i32) {
        if self.bars.remove(&id).is_some() {
            platform.boss_bars().remove(id);
        }
    }

    fn detach(&mut self, platform: &dyn Platform, id: i32) -> bool {
        let Some(bar) = self.bars.get_mut(&id) else {
            return false;
        };
        let Some(attachment) = bar.attachment.take() else {
            return false;
        };
        for player in attachment.near {
            if !bar.viewers.contains(&player) {
                platform.boss_bars().hide(id, player);
            }
        }
        true
    }

    /// An attached bar shows to who's near its centity and follows its health; one whose centity went goes.
    fn follow(&mut self, shared: &Shared, id: i32) {
        let platform = &*shared.platform;
        let Some(bar) = self.bars.get(&id) else {
            return;
        };
        let Some(attachment) = &bar.attachment else {
            return;
        };
        let located = shared
            .centities
            .borrow()
            .get(attachment.centity)
            .filter(|i| !i.removed)
            .map(|i| {
                let at = i.anchor().clone();
                let health = i
                    .health
                    .as_ref()
                    .filter(|h| h.max > 0.0)
                    .map(|h| h.current / h.max);
                (at, health)
            });
        let Some((at, health)) = located else {
            if attachment.remove_with {
                self.remove(platform, id);
            } else {
                self.detach(platform, id);
            }
            return;
        };
        let bar = self.bars.get_mut(&id).expect("there");
        let attachment = bar.attachment.as_mut().expect("attached");
        if attachment.health
            && let Some(health) = health
            && health != bar.look.progress
        {
            bar.look.progress = health.clamp(0.0, 1.0);
            platform.boss_bars().update(id, bar.shown());
        }
        let attachment = bar.attachment.as_mut().expect("attached");
        let range = attachment.range * attachment.range;
        let players = platform.players();
        let now: HashSet<Uuid> = players
            .online()
            .into_iter()
            .filter_map(|p| {
                let l = players.location(p.uuid)?;
                let d = (l.x - at.x).powi(2) + (l.y - at.y).powi(2) + (l.z - at.z).powi(2);
                (l.world == at.world && d <= range).then_some(p.uuid)
            })
            .collect();
        for player in &now {
            if attachment.near.insert(*player) && !bar.viewers.contains(player) {
                platform.boss_bars().show(id, *player);
            }
        }
        let gone: Vec<Uuid> = attachment
            .near
            .iter()
            .filter(|p| !now.contains(p))
            .copied()
            .collect();
        for player in gone {
            attachment.near.shift_remove(&player);
            if !bar.viewers.contains(&player) {
                platform.boss_bars().hide(id, player);
            }
        }
    }
}

impl GameContext {
    fn bar_id(this: &handles::BossBar) -> Option<i32> {
        i32::try_from(this.id).ok()
    }

    fn with_bar<R>(
        &self,
        this: &handles::BossBar,
        f: impl FnOnce(&mut Bar, i32) -> R,
    ) -> Option<R> {
        let id = Self::bar_id(this)?;
        let mut bars = self.shared.bars.borrow_mut();
        bars.bars.get_mut(&id).map(|bar| f(bar, id))
    }

    fn change_bar(&self, this: &handles::BossBar, change: impl FnOnce(&mut BossBarLook)) -> bool {
        let platform = self.shared.platform.clone();
        self.with_bar(this, |bar, id| {
            change(&mut bar.look);
            platform.boss_bars().update(id, bar.shown());
        })
        .is_some()
    }
}

impl NfBossbarsApi for GameContext {
    fn create(
        &self,
        caller: &Caller,
        options: Option<shapes::BossBarOptions>,
    ) -> ApiResult<handles::BossBar> {
        let options = options.unwrap_or(shapes::BossBarOptions {
            text: None,
            color: None,
            style: None,
            progress: None,
            icon: None,
        });
        if let Some(progress) = options.progress {
            check_progress(progress, "options.progress")?;
        }
        let icon = match &options.icon {
            Some(icon) => Some(self.with_names(|names| names.look_name(RefKind::Glyph, icon))?),
            None => None,
        };
        let look = BossBarLook {
            text: options.text.map(|t| t.0).unwrap_or_default(),
            progress: options.progress.unwrap_or(1.0),
            color: options.color.unwrap_or_else(|| "pink".into()),
            style: options.style.unwrap_or_else(|| "progress".into()),
        };
        let mut bars = self.shared.bars.borrow_mut();
        bars.next += 1;
        let id = bars.next;
        let bar = Bar {
            scope: caller.scope,
            look,
            icon,
            viewers: IndexSet::new(),
            attachment: None,
        };
        self.shared.platform.boss_bars().create(id, bar.shown());
        bars.bars.insert(id, bar);
        Ok(handles::BossBar { id: i64::from(id) })
    }
}

impl BossBarApi for GameContext {
    fn attach(
        &self,
        this: &handles::BossBar,
        centity: handles::Centity,
        options: Option<shapes::BossBarAttachOptions>,
    ) -> ApiResult<bool> {
        let range = options
            .as_ref()
            .and_then(|o| o.range)
            .unwrap_or(DEFAULT_ATTACH_RANGE);
        if range.is_nan() || range <= 0.0 || !range.is_finite() {
            return Err(err(format!(
                "bad argument 'options.range' (more than 0 expected, got {range})"
            )));
        }
        let Some(id) =
            Self::bar_id(this).filter(|id| self.shared.bars.borrow().bars.contains_key(id))
        else {
            return Ok(false);
        };
        let Some(instance) = parse_uuid(&centity.id) else {
            return Ok(false);
        };
        if !self
            .shared
            .centities
            .borrow()
            .get(instance)
            .is_some_and(|i| !i.removed)
        {
            return Ok(false);
        }
        let platform = &*self.shared.platform;
        let mut bars = self.shared.bars.borrow_mut();
        bars.detach(platform, id);
        if let Some(bar) = bars.bars.get_mut(&id) {
            bar.attachment = Some(Attachment {
                centity: instance,
                range,
                health: options.as_ref().and_then(|o| o.health) != Some(false),
                remove_with: options.as_ref().and_then(|o| o.remove_with) != Some(false),
                near: IndexSet::new(),
            });
        }
        bars.follow(&self.shared, id);
        Ok(true)
    }

    fn detach(&self, this: &handles::BossBar) -> ApiResult<()> {
        if let Some(id) = Self::bar_id(this) {
            self.shared
                .bars
                .borrow_mut()
                .detach(&*self.shared.platform, id);
        }
        Ok(())
    }

    fn attached(&self, this: &handles::BossBar) -> ApiResult<Option<handles::Centity>> {
        Ok(self
            .with_bar(this, |bar, _| bar.attachment.as_ref().map(|a| a.centity))
            .flatten()
            .map(|id| handles::Centity { id: uuid_text(&id) }))
    }

    fn exists(&self, this: &handles::BossBar) -> ApiResult<bool> {
        Ok(self.with_bar(this, |_, _| ()).is_some())
    }

    fn show_to(&self, this: &handles::BossBar, player: handles::Player) -> ApiResult<bool> {
        let Some(uuid) = parse_uuid(&player.id) else {
            return Ok(false);
        };
        let platform = self.shared.platform.clone();
        Ok(self
            .with_bar(this, |bar, id| {
                bar.viewers.insert(uuid);
                platform.boss_bars().show(id, uuid);
            })
            .is_some())
    }

    fn hide_from(&self, this: &handles::BossBar, player: handles::Player) -> ApiResult<bool> {
        let Some(uuid) = parse_uuid(&player.id) else {
            return Ok(false);
        };
        let platform = self.shared.platform.clone();
        Ok(self
            .with_bar(this, |bar, id| {
                let had = bar.viewers.shift_remove(&uuid);
                if had {
                    platform.boss_bars().hide(id, uuid);
                }
                had
            })
            .unwrap_or(false))
    }

    fn viewers(&self, this: &handles::BossBar) -> ApiResult<Vec<handles::Player>> {
        let ids: IndexSet<Uuid> = self
            .with_bar(this, |bar, _| {
                let mut all = bar.viewers.clone();
                all.extend(bar.attachment.iter().flat_map(|a| a.near.iter().copied()));
                all
            })
            .unwrap_or_default();
        let players = self.shared.platform.players();
        Ok(ids
            .into_iter()
            .filter_map(|id| players.known(uuid_text(&id)))
            .map(|p| handles::Player {
                id: uuid_text(&p.uuid),
            })
            .collect())
    }

    fn text(&self, this: &handles::BossBar) -> ApiResult<Option<values::Text>> {
        Ok(self.with_bar(this, |bar, _| values::Text(bar.look.text.clone())))
    }

    fn set_text(&self, this: &handles::BossBar, text: values::Text) -> ApiResult<bool> {
        Ok(self.change_bar(this, |look| look.text = text.0))
    }

    fn icon(&self, this: &handles::BossBar) -> ApiResult<Option<String>> {
        let icon = self.with_bar(this, |bar, _| bar.icon.clone()).flatten();
        Ok(icon.map(|i| self.with_names(|names| names.spell(&i))))
    }

    fn set_icon(&self, this: &handles::BossBar, icon: Option<String>) -> ApiResult<bool> {
        let name = match &icon {
            Some(icon) => Some(self.with_names(|names| names.look_name(RefKind::Glyph, icon))?),
            None => None,
        };
        let platform = self.shared.platform.clone();
        Ok(self
            .with_bar(this, |bar, id| {
                bar.icon = name;
                platform.boss_bars().update(id, bar.shown());
            })
            .is_some())
    }

    fn progress(&self, this: &handles::BossBar) -> ApiResult<Option<f64>> {
        Ok(self.with_bar(this, |bar, _| bar.look.progress))
    }

    fn set_progress(&self, this: &handles::BossBar, progress: f64) -> ApiResult<bool> {
        check_progress(progress, "progress")?;
        Ok(self.change_bar(this, |look| look.progress = progress))
    }

    fn color(&self, this: &handles::BossBar) -> ApiResult<Option<String>> {
        Ok(self.with_bar(this, |bar, _| bar.look.color.clone()))
    }

    fn set_color(&self, this: &handles::BossBar, color: String) -> ApiResult<bool> {
        Ok(self.change_bar(this, |look| look.color = color))
    }

    fn style(&self, this: &handles::BossBar) -> ApiResult<Option<String>> {
        Ok(self.with_bar(this, |bar, _| bar.look.style.clone()))
    }

    fn set_style(&self, this: &handles::BossBar, style: String) -> ApiResult<bool> {
        Ok(self.change_bar(this, |look| look.style = style))
    }

    fn remove(&self, this: &handles::BossBar) -> ApiResult<bool> {
        let Some(id) = Self::bar_id(this) else {
            return Ok(false);
        };
        let mut bars = self.shared.bars.borrow_mut();
        let had = bars.bars.contains_key(&id);
        bars.remove(&*self.shared.platform, id);
        Ok(had)
    }
}

impl GameContext {
    /// Their UUID while they're online.
    fn sidebar_player(&self, this: &handles::Sidebar) -> Option<Uuid> {
        parse_uuid(&this.player).filter(|id| self.shared.platform.players().get(*id).is_some())
    }

    /// Changes their sidebar, and shows it, or hides it as the change leaves it.
    fn change_sidebar(&self, this: &handles::Sidebar, change: impl FnOnce(&mut Sidebar)) -> bool {
        let Some(player) = self.sidebar_player(this) else {
            return false;
        };
        let platform = &*self.shared.platform;
        let mut bars = self.shared.bars.borrow_mut();
        let state = bars.sidebars.entry(player).or_default();
        let was = state.visible;
        change(state);
        if state.visible {
            platform.sidebars().show(
                player,
                state.title.clone().unwrap_or_default(),
                state.lines.clone(),
            );
        } else if was {
            platform.sidebars().hide(player);
        }
        true
    }

    fn sidebar_state(&self, this: &handles::Sidebar) -> Option<Sidebar> {
        let player = self.sidebar_player(this)?;
        self.shared.bars.borrow().sidebars.get(&player).cloned()
    }
}

impl SidebarApi for GameContext {
    fn title(&self, this: &handles::Sidebar) -> ApiResult<Option<values::Text>> {
        Ok(self
            .sidebar_state(this)
            .and_then(|s| s.title)
            .map(values::Text))
    }

    fn set_title(&self, this: &handles::Sidebar, text: values::Text) -> ApiResult<bool> {
        Ok(self.change_sidebar(this, |s| {
            s.title = Some(text.0);
            s.visible = true;
        }))
    }

    fn lines(&self, this: &handles::Sidebar) -> ApiResult<Vec<values::Text>> {
        Ok(self
            .sidebar_state(this)
            .map(|s| s.lines.into_iter().map(values::Text).collect())
            .unwrap_or_default())
    }

    fn set_lines(&self, this: &handles::Sidebar, lines: Vec<values::Text>) -> ApiResult<bool> {
        if lines.len() > SIDEBAR_LINES {
            return Err(err(format!(
                "a sidebar has at most {SIDEBAR_LINES} lines, not {}",
                lines.len()
            )));
        }
        Ok(self.change_sidebar(this, |s| {
            s.lines = lines.into_iter().map(|t| t.0).collect();
            s.visible = true;
        }))
    }

    fn is_visible(&self, this: &handles::Sidebar) -> ApiResult<bool> {
        Ok(self.sidebar_state(this).is_some_and(|s| s.visible))
    }

    fn set_visible(&self, this: &handles::Sidebar, visible: bool) -> ApiResult<bool> {
        Ok(self.change_sidebar(this, |s| s.visible = visible))
    }

    fn clear(&self, this: &handles::Sidebar) -> ApiResult<bool> {
        Ok(self.change_sidebar(this, |s| *s = Sidebar::default()))
    }
}

impl GameContext {
    /// The bars `player` is a viewer of, for `player:bossbars()`.
    pub(crate) fn bars_of(&self, player: Uuid) -> Vec<handles::BossBar> {
        self.shared
            .bars
            .borrow()
            .bars
            .iter()
            .filter(|(_, b)| b.viewers.contains(&player))
            .map(|(id, _)| handles::BossBar { id: i64::from(*id) })
            .collect()
    }
}

/// Boss bars and sidebars as a session's service.
pub struct BarsService {
    pub shared: Rc<Shared>,
}

impl Service for BarsService {
    fn name(&self) -> &'static str {
        "boss bars and sidebars"
    }

    /// Attached bars follow their centity.
    fn tick(&self, _room: &Room, _platform: &dyn Platform) {
        let attached: Vec<i32> = self
            .shared
            .bars
            .borrow()
            .bars
            .iter()
            .filter(|(_, b)| b.attachment.is_some())
            .map(|(id, _)| *id)
            .collect();
        for id in attached {
            self.shared.bars.borrow_mut().follow(&self.shared, id);
        }
    }

    /// Every bar they're a viewer of shows again.
    fn player_joined(&self, player: &PlayerRef) {
        let bars = self.shared.bars.borrow();
        for (id, bar) in &bars.bars {
            if bar.viewers.contains(&player.uuid) {
                self.shared.platform.boss_bars().show(*id, player.uuid);
            }
        }
    }

    fn player_quit(&self, player: &PlayerRef) {
        self.shared.bars.borrow_mut().sidebars.remove(&player.uuid);
    }

    /// A scope is going: its bars go with it.
    fn scope_released(&self, scopes: &[ScopeId]) {
        let platform = &*self.shared.platform;
        let mut bars = self.shared.bars.borrow_mut();
        let doomed: Vec<i32> = bars
            .bars
            .iter()
            .filter(|(_, b)| scopes.contains(&b.scope))
            .map(|(id, _)| *id)
            .collect();
        for id in doomed {
            bars.remove(platform, id);
        }
    }

    /// Every bar goes, and everyone gets the main scoreboard back.
    fn stop(&self, _room: &Room, platform: &dyn Platform) {
        let mut bars = self.shared.bars.borrow_mut();
        let ids: Vec<i32> = bars.bars.keys().copied().collect();
        for id in ids {
            bars.remove(platform, id);
        }
        for (player, state) in &bars.sidebars {
            if state.visible {
                platform.sidebars().hide(*player);
            }
        }
        bars.sidebars.clear();
    }
}
