//! Where players aim at screens, and what they click (Kotlin's `ScreenAim`): a click on a screen's frame (the pixel
//! from the player's sight through `Placed::hit`), or a swing of the arm aimed at one from further away, a block in
//! front in the way; one click a player a tick, however the server heard of it. `hover` and `leave` run only while
//! something listens.

use std::collections::HashMap;

use mlua::Value;
use netherforge_api::codec::Codec;
use netherforge_api::{codecs, handles, shapes};
use netherforge_format::kind::screen::TILE;
use netherforge_format::math::Vec3;
use netherforge_platform::{ClickButton, PlayerRef, Ray, Uuid};

use super::{CLICK_REACH, Hit, OCCLUSION_SLACK, ScreensContext};
use crate::events::uuid_text;
use crate::scripts::Target;

fn player_handle(player: Uuid) -> handles::Player {
    handles::Player {
        id: uuid_text(&player),
    }
}

impl ScreensContext {
    /// A click on one of our frames: the screen's, when it's one of theirs. True when the frame is a screen's.
    pub fn click(
        &self,
        entity: Uuid,
        player: &PlayerRef,
        button: ClickButton,
        sight: Option<Ray>,
    ) -> bool {
        let (id, x, y) = {
            let screens = self.shared.screens.borrow();
            let Some((id, tile)) = screens.owners.get(&entity).cloned() else {
                return false;
            };
            let Some(screen) = screens.screens.get(&id) else {
                return true;
            };
            if !screen.viewers.contains_key(&player.uuid) {
                return true;
            }
            let Some(placement) = &screen.placement else {
                return true;
            };
            // Where along their sight they aimed, or the middle of the map when the server didn't say.
            let hit = sight
                .as_ref()
                .and_then(|ray| placement.hit(ray, screen.columns, screen.rows, CLICK_REACH));
            let tile = tile as i32;
            let x = hit.map_or((tile % screen.columns) * TILE + TILE / 2, |h| h.x);
            let y = hit.map_or((tile / screen.columns) * TILE + TILE / 2, |h| h.y);
            (id, x, y)
        };
        self.click_at(&id, player.uuid, x, y, button);
        true
    }

    /// A swing of the arm (or a right click in the air) by `player`, aimed at a screen within its click range: its
    /// click, and true. False when they aimed at none, so the swing is the world's.
    pub fn swing(&self, player: &PlayerRef, button: ClickButton) -> bool {
        // Their sight is asked for only when a screen that hangs somewhere is drawn for them.
        let seen =
            self.shared.screens.borrow().screens.values().any(|s| {
                !s.removed && s.placement.is_some() && s.viewers.contains_key(&player.uuid)
            });
        if !seen {
            return false;
        }
        let Some(eye) = self.platform().players().eye(player.uuid) else {
            return false;
        };
        let Some((id, hit)) = self.aim(player.uuid, &eye, None) else {
            return false;
        };
        self.click_at(&id, player.uuid, hit.x, hit.y, button);
        true
    }

    fn click_at(&self, id: &str, player: Uuid, x: i32, y: i32, button: ClickButton) {
        let (handle, path) = {
            let mut screens = self.shared.screens.borrow_mut();
            let now = screens.ticks;
            if screens.clicked.insert(player, now) == Some(now) {
                return;
            }
            let Some(screen) = screens.screens.get(id) else {
                return;
            };
            (screen.handle_of(player), screen.path_of(player))
        };
        self.raise_on(&path, "click", |lua| {
            codecs::ScreenClickEvent.push(
                lua,
                shapes::ScreenClickEvent {
                    screen: handle,
                    player: player_handle(player),
                    x: i64::from(x),
                    y: i64::from(y),
                    click: button.lua_name().to_owned(),
                },
            )
        });
    }

    /// The nearest screen `player`'s `eye` meets that's drawn for them, within `reach` blocks (each screen's click
    /// range when none), with nothing solid in front of it.
    pub fn aim(&self, player: Uuid, eye: &Ray, reach: Option<f64>) -> Option<(String, Hit)> {
        let found = {
            let screens = self.shared.screens.borrow();
            let mut best: Option<(String, Hit)> = None;
            for screen in screens.screens.values() {
                if screen.removed || !screen.viewers.contains_key(&player) {
                    continue;
                }
                let Some(placement) = &screen.placement else {
                    continue;
                };
                let range = reach.unwrap_or(screen.settings.click_range);
                let Some(hit) = placement.hit(eye, screen.columns, screen.rows, range) else {
                    continue;
                };
                if best.as_ref().is_none_or(|(_, b)| hit.distance < b.distance) {
                    best = Some((screen.id.clone(), hit));
                }
            }
            best?
        };
        let block = self.platform().worlds().raycast_blocks(
            eye.world.clone(),
            Vec3(eye.x, eye.y, eye.z),
            Vec3(eye.dx, eye.dy, eye.dz),
            found.1.distance,
            false,
        );
        if let Some(block) = block {
            let p = block.position;
            let (dx, dy, dz) = (p.0 - eye.x, p.1 - eye.y, p.2 - eye.z);
            if (dx * dx + dy * dy + dz * dz).sqrt() < found.1.distance - OCCLUSION_SLACK {
                return None;
            }
        }
        Some(found)
    }

    /// Each tick: who aims at which pixel of a screen whose `hover` or `leave` someone hears. A player's aim is
    /// worked out once a tick (every screen they see, then a ray against the blocks), however many screens listen.
    pub(super) fn hover(&self) {
        let mut aims: HashMap<Uuid, Option<(String, Hit)>> = HashMap::new();
        let ids: Vec<String> = self
            .shared
            .screens
            .borrow()
            .screens
            .keys()
            .cloned()
            .collect();
        for id in ids {
            let (viewers, handles) = {
                let screens = self.shared.screens.borrow();
                let Some(screen) = screens
                    .screens
                    .get(&id)
                    .filter(|s| !s.removed && s.placement.is_some())
                else {
                    continue;
                };
                let mut handles = vec![screen.handle()];
                handles.extend(screen.personal.keys().map(|p| screen.handle_for(Some(*p))));
                (screen.viewers.keys().copied().collect::<Vec<_>>(), handles)
            };
            let hears = handles.into_iter().any(|handle| {
                self.target(handle).is_some_and(|target| {
                    self.scripts.listening(&target, "hover")
                        || self.scripts.listening(&target, "leave")
                })
            });
            if !hears {
                continue;
            }
            for player in viewers {
                let aim = match aims.get(&player) {
                    Some(aim) => aim.clone(),
                    None => {
                        let aim = self
                            .platform()
                            .players()
                            .eye(player)
                            .and_then(|eye| self.aim(player, &eye, None));
                        aims.insert(player, aim.clone());
                        aim
                    }
                };
                let hit = aim.filter(|(on, _)| *on == id).map(|(_, hit)| hit);
                let (handle, path) = {
                    let mut screens = self.shared.screens.borrow_mut();
                    let Some(screen) = screens.screens.get_mut(&id) else {
                        break;
                    };
                    let before = screen.hovering.get(&player).copied();
                    let Some(hit) = hit else {
                        drop(screens);
                        if before.is_some() {
                            self.leave(&id, player);
                        }
                        continue;
                    };
                    let packed = (hit.x << 16) | hit.y;
                    if before == Some(packed) {
                        continue;
                    }
                    screen.hovering.insert(player, packed);
                    (screen.handle_of(player), screen.path_of(player))
                };
                let (x, y) = hit.map_or((0, 0), |h| (h.x, h.y));
                self.raise_on(&path, "hover", |lua| {
                    codecs::ScreenHoverEvent.push(
                        lua,
                        shapes::ScreenHoverEvent {
                            screen: handle,
                            player: player_handle(player),
                            x: i64::from(x),
                            y: i64::from(y),
                        },
                    )
                });
                let removed = self
                    .shared
                    .screens
                    .borrow()
                    .screens
                    .get(&id)
                    .is_none_or(|s| s.removed);
                if removed {
                    break;
                }
            }
        }
    }

    /// `player` no longer aims at screen `id`: its `leave`, when a `hover` was heard for them.
    pub(super) fn leave(&self, id: &str, player: Uuid) {
        let (handle, path) = {
            let mut screens = self.shared.screens.borrow_mut();
            let Some(screen) = screens.screens.get_mut(id) else {
                return;
            };
            if screen.hovering.remove(&player).is_none() {
                return;
            }
            (screen.handle_of(player), screen.path_of(player))
        };
        self.raise_on(&path, "leave", |lua| {
            codecs::ScreenLeaveEvent.push(
                lua,
                shapes::ScreenLeaveEvent {
                    screen: handle,
                    player: player_handle(player),
                },
            )
        });
    }

    /// Everyone aiming at screen `id` stops: their `leave`s.
    pub(super) fn leave_all(&self, id: &str) {
        let players: Vec<Uuid> = self
            .shared
            .screens
            .borrow()
            .screens
            .get(id)
            .map(|s| s.hovering.keys().copied().collect())
            .unwrap_or_default();
        for player in players {
            self.leave(id, player);
        }
    }

    /// `player` left: their aim at every screen ends, and when they last clicked doesn't matter any more.
    pub(super) fn forget(&self, player: Uuid) {
        self.shared.screens.borrow_mut().clicked.remove(&player);
        let ids: Vec<String> = self
            .shared
            .screens
            .borrow()
            .screens
            .keys()
            .cloned()
            .collect();
        for id in ids {
            self.leave(&id, player);
        }
    }

    /// Raises `event` along the screen handles of `path`, its payload made only when something listens.
    fn raise_on(
        &self,
        path: &[handles::Screen],
        event: &str,
        payload: impl FnOnce(&mlua::Lua) -> mlua::Result<Value>,
    ) {
        let stages: Vec<(Target, &str)> = path
            .iter()
            .filter_map(|handle| Some((self.target(handle.clone())?, event)))
            .collect();
        if !stages.iter().any(|(t, e)| self.scripts.listening(t, e)) {
            return;
        }
        let lua = self.scripts.host().lua().clone();
        if let Ok(Value::Table(fields)) = payload(&lua) {
            self.scripts.raise_path(&stages, fields, false);
        }
    }
}
