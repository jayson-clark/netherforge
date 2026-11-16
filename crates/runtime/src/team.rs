//! Teams, the lines under name tags, and who's listed for whom (Kotlin's `Teams`, `PlayerListings`, `TeamImpl`).
//!
//! A team scripts make (`nf.teams.create`) is on the server's main scoreboard as `nf.<name>`, so the project's
//! teams never collide with vanilla's or another plugin's, and only ours are ever removed. Each belongs to the
//! scope that made it and goes when that scope is released; every one goes when the session ends. Teams a
//! crash left behind (and lines under name tags) are removed as a session starts. What a team looks like is
//! kept here, as scripts set it; who's in it is the scoreboard's.
//!
//! Whose player list each player is out of is kept here too, because the server forgets it when either of them
//! leaves: it's applied again when they join, and everyone is listed again when the session ends.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use indexmap::IndexMap;
use netherforge_api::api::{NfTeamsApi, TeamApi};
use netherforge_api::{ApiError, ApiResult, Caller, handles, shapes, values};
use netherforge_luau::ScopeId;
use netherforge_platform::{Platform, PlayerRef, TeamLook, Uuid};

use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// What the project's teams are called on the scoreboard, before their own names.
pub const PREFIX: &str = "nf.";

/// What a team's name may be made of: what `/team` commands can type without quotes.
fn team_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_.+-".contains(c))
}

struct Team {
    scope: ScopeId,
    look: TeamLook,
}

/// The session's teams, lines under name tags, and players out of others' lists.
#[derive(Default)]
pub struct Teams {
    teams: IndexMap<String, Team>,
    below_names: HashMap<Uuid, String>,
    /// By viewer: the players out of their list.
    unlisted: HashMap<Uuid, HashSet<Uuid>>,
}

impl Teams {
    fn remove(&mut self, platform: &dyn Platform, name: &str) -> bool {
        if self.teams.shift_remove(name).is_none() {
            return false;
        }
        platform.teams().remove(format!("{PREFIX}{name}"));
        true
    }

    /// The project's team `entry` is in, or none (in none, or in someone else's).
    pub fn of(&self, platform: &dyn Platform, entry: &str) -> Option<String> {
        let full = platform.teams().team_of(entry.to_owned())?;
        let name = full.strip_prefix(PREFIX)?;
        self.teams.contains_key(name).then(|| name.to_owned())
    }
}

impl GameContext {
    /// What `member` is called on a scoreboard: a player's name, any other entity's UUID. None for a handle that
    /// names nothing, and, when `present`, for an entity that isn't in the world (a player counts offline too:
    /// the scoreboard keeps them by name).
    pub(crate) fn scoreboard_entry(
        &self,
        member: &handles::Entity,
        present: bool,
    ) -> Option<String> {
        let id = parse_uuid(&member.id)?;
        let players = self.shared.platform.players();
        if let Some(player) = players.get(id) {
            return Some(player.name);
        }
        if let Some(player) = players.known(member.id.clone()).filter(|p| p.uuid == id) {
            return Some(player.name);
        }
        if present && self.shared.platform.world_entities().info(id).is_none() {
            return None;
        }
        Some(uuid_text(&id))
    }

    fn with_team<R>(&self, this: &handles::Team, f: impl FnOnce(&mut Team) -> R) -> Option<R> {
        self.shared
            .teams
            .borrow_mut()
            .teams
            .get_mut(&this.name)
            .map(f)
    }

    fn change_team(&self, this: &handles::Team, change: impl FnOnce(&mut TeamLook)) -> bool {
        let platform = self.shared.platform.clone();
        self.with_team(this, |team| {
            change(&mut team.look);
            platform
                .teams()
                .update(format!("{PREFIX}{}", this.name), team.look.clone());
        })
        .is_some()
    }

    fn team_look<R>(&self, this: &handles::Team, read: impl FnOnce(&TeamLook) -> R) -> Option<R> {
        self.with_team(this, |team| read(&team.look))
    }

    /// Whether `player` is in `viewer`'s player list.
    pub(crate) fn is_listed(&self, viewer: Uuid, player: Uuid) -> bool {
        !self
            .shared
            .teams
            .borrow()
            .unlisted
            .get(&viewer)
            .is_some_and(|p| p.contains(&player))
    }

    pub(crate) fn set_listed(&self, viewer: Uuid, player: Uuid, listed: bool) -> bool {
        if !self
            .shared
            .platform
            .player_list()
            .set_listed(viewer, player, listed)
        {
            return false;
        }
        let mut teams = self.shared.teams.borrow_mut();
        if listed {
            if let Some(out) = teams.unlisted.get_mut(&viewer) {
                out.remove(&player);
                if out.is_empty() {
                    teams.unlisted.remove(&viewer);
                }
            }
        } else {
            teams.unlisted.entry(viewer).or_default().insert(player);
        }
        true
    }

    pub(crate) fn below_name(&self, player: Uuid) -> Option<String> {
        self.shared.teams.borrow().below_names.get(&player).cloned()
    }

    pub(crate) fn set_below_name(&self, player: &PlayerRef, text: Option<String>) {
        let mut teams = self.shared.teams.borrow_mut();
        match &text {
            Some(text) => teams.below_names.insert(player.uuid, text.clone()),
            None => teams.below_names.remove(&player.uuid),
        };
        self.shared
            .platform
            .teams()
            .set_below_name(player.name.clone(), text);
    }

    /// The project's team `member` is in, for `entity:team()`.
    pub(crate) fn team_of(&self, member: &handles::Entity) -> Option<handles::Team> {
        let entry = self.scoreboard_entry(member, true)?;
        let name = self
            .shared
            .teams
            .borrow()
            .of(&*self.shared.platform, &entry)?;
        Some(handles::Team { name })
    }
}

impl NfTeamsApi for GameContext {
    fn create(
        &self,
        caller: &Caller,
        name: String,
        options: Option<shapes::TeamOptions>,
    ) -> ApiResult<handles::Team> {
        if !team_name(&name) {
            return Err(err(format!(
                "a team's name is letters, digits and _ - . +, not \"{name}\""
            )));
        }
        let o = options;
        let look = TeamLook {
            display_name: o
                .as_ref()
                .and_then(|o| o.display_name.clone())
                .map_or_else(|| name.clone(), |t| t.0),
            prefix: o
                .as_ref()
                .and_then(|o| o.prefix.clone())
                .map(|t| t.0)
                .unwrap_or_default(),
            suffix: o
                .as_ref()
                .and_then(|o| o.suffix.clone())
                .map(|t| t.0)
                .unwrap_or_default(),
            color: o.as_ref().and_then(|o| o.color.clone()),
            friendly_fire: o.as_ref().and_then(|o| o.friendly_fire).unwrap_or(true),
            see_invisible_teammates: o
                .as_ref()
                .and_then(|o| o.see_invisible_teammates)
                .unwrap_or(true),
            nametags: o
                .as_ref()
                .and_then(|o| o.nametags.clone())
                .unwrap_or_else(|| "always".into()),
            collision: o
                .as_ref()
                .and_then(|o| o.collision.clone())
                .unwrap_or_else(|| "always".into()),
        };
        let mut teams = self.shared.teams.borrow_mut();
        if teams.teams.contains_key(&name) {
            return Err(err(format!(
                "the project already has a team called \"{name}\" (nf.teams.get finds it)"
            )));
        }
        let ops = self.shared.platform.teams();
        let full = format!("{PREFIX}{name}");
        // Only ever ours: a name with our prefix that a crash left behind.
        if !ops.create(full.clone(), look.clone()) {
            ops.remove(full.clone());
            ops.create(full, look.clone());
        }
        teams.teams.insert(
            name.clone(),
            Team {
                scope: caller.scope,
                look,
            },
        );
        Ok(handles::Team { name })
    }

    fn get(&self, _: &Caller, name: String) -> ApiResult<Option<handles::Team>> {
        Ok(self
            .shared
            .teams
            .borrow()
            .teams
            .contains_key(&name)
            .then_some(handles::Team { name }))
    }

    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::Team>> {
        Ok(self
            .shared
            .teams
            .borrow()
            .teams
            .keys()
            .map(|name| handles::Team { name: name.clone() })
            .collect())
    }
}

impl TeamApi for GameContext {
    fn name(&self, this: &handles::Team) -> ApiResult<String> {
        Ok(this.name.clone())
    }

    fn exists(&self, this: &handles::Team) -> ApiResult<bool> {
        Ok(self.with_team(this, |_| ()).is_some())
    }

    fn add_member(&self, this: &handles::Team, member: handles::Entity) -> ApiResult<bool> {
        if self.with_team(this, |_| ()).is_none() {
            return Ok(false);
        }
        let Some(entry) = self.scoreboard_entry(&member, true) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .teams()
            .add_entry(format!("{PREFIX}{}", this.name), entry))
    }

    fn remove_member(&self, this: &handles::Team, member: handles::Entity) -> ApiResult<bool> {
        if self.with_team(this, |_| ()).is_none() {
            return Ok(false);
        }
        let Some(entry) = self.scoreboard_entry(&member, false) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .teams()
            .remove_entry(format!("{PREFIX}{}", this.name), entry))
    }

    fn has_member(&self, this: &handles::Team, member: handles::Entity) -> ApiResult<bool> {
        if self.with_team(this, |_| ()).is_none() {
            return Ok(false);
        }
        let Some(entry) = self.scoreboard_entry(&member, false) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .teams()
            .entries(format!("{PREFIX}{}", this.name))
            .is_some_and(|e| e.contains(&entry)))
    }

    fn members(&self, this: &handles::Team) -> ApiResult<Vec<handles::Entity>> {
        if self.with_team(this, |_| ()).is_none() {
            return Ok(Vec::new());
        }
        let platform = &*self.shared.platform;
        let mut entries = platform
            .teams()
            .entries(format!("{PREFIX}{}", this.name))
            .unwrap_or_default();
        entries.sort();
        let lua = self.scripts.host().lua().clone();
        Ok(entries
            .into_iter()
            .filter_map(|entry| match parse_uuid(&entry) {
                Some(id) => platform
                    .world_entities()
                    .info(id)
                    .map(|info| self.shared.entity(&lua, &info)),
                None => platform
                    .players()
                    .find(entry.clone())
                    .filter(|p| p.name == entry)
                    .map(|p| self.shared.player_entity(&lua, p.uuid)),
            })
            .collect())
    }

    fn display_name(&self, this: &handles::Team) -> ApiResult<Option<values::Text>> {
        Ok(self.team_look(this, |l| values::Text(l.display_name.clone())))
    }
    fn set_display_name(&self, this: &handles::Team, text: values::Text) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.display_name = text.0))
    }
    fn prefix(&self, this: &handles::Team) -> ApiResult<Option<values::Text>> {
        Ok(self.team_look(this, |l| values::Text(l.prefix.clone())))
    }
    fn set_prefix(&self, this: &handles::Team, text: values::Text) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.prefix = text.0))
    }
    fn suffix(&self, this: &handles::Team) -> ApiResult<Option<values::Text>> {
        Ok(self.team_look(this, |l| values::Text(l.suffix.clone())))
    }
    fn set_suffix(&self, this: &handles::Team, text: values::Text) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.suffix = text.0))
    }
    fn color(&self, this: &handles::Team) -> ApiResult<Option<String>> {
        Ok(self.team_look(this, |l| l.color.clone()).flatten())
    }
    fn set_color(&self, this: &handles::Team, color: Option<String>) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.color = color))
    }
    fn has_friendly_fire(&self, this: &handles::Team) -> ApiResult<bool> {
        Ok(self.team_look(this, |l| l.friendly_fire) == Some(true))
    }
    fn set_friendly_fire(&self, this: &handles::Team, enabled: bool) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.friendly_fire = enabled))
    }
    fn can_see_invisible_teammates(&self, this: &handles::Team) -> ApiResult<bool> {
        Ok(self.team_look(this, |l| l.see_invisible_teammates) == Some(true))
    }
    fn set_can_see_invisible_teammates(
        &self,
        this: &handles::Team,
        enabled: bool,
    ) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.see_invisible_teammates = enabled))
    }
    fn nametags(&self, this: &handles::Team) -> ApiResult<Option<String>> {
        Ok(self.team_look(this, |l| l.nametags.clone()))
    }
    fn set_nametags(&self, this: &handles::Team, visibility: String) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.nametags = visibility))
    }
    fn collision(&self, this: &handles::Team) -> ApiResult<Option<String>> {
        Ok(self.team_look(this, |l| l.collision.clone()))
    }
    fn set_collision(&self, this: &handles::Team, rule: String) -> ApiResult<bool> {
        Ok(self.change_team(this, |l| l.collision = rule))
    }
    fn remove(&self, this: &handles::Team) -> ApiResult<bool> {
        Ok(self
            .shared
            .teams
            .borrow_mut()
            .remove(&*self.shared.platform, &this.name))
    }
}

/// Teams, lines under name tags and player listings as a session's service.
pub struct TeamsService {
    pub shared: Rc<Shared>,
}

impl Service for TeamsService {
    fn name(&self) -> &'static str {
        "teams"
    }

    /// Before anything runs: teams of ours and lines under name tags a crash left on the scoreboard go.
    fn start(&self, _room: &Room, project: &Define) {
        let ops = project.platform.teams();
        for name in ops.names() {
            if name.starts_with(PREFIX) {
                ops.remove(name);
            }
        }
        ops.clear_below_names();
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let platform = &*self.shared.platform;
        let mut teams = self.shared.teams.borrow_mut();
        let doomed: Vec<String> = teams
            .teams
            .iter()
            .filter(|(_, t)| scopes.contains(&t.scope))
            .map(|(n, _)| n.clone())
            .collect();
        for name in doomed {
            teams.remove(platform, &name);
        }
    }

    /// Out of the lists they were out of, and the players out of theirs out again.
    fn player_joined(&self, player: &PlayerRef) {
        let ops = self.shared.platform.player_list();
        for (viewer, players) in &self.shared.teams.borrow().unlisted {
            if *viewer == player.uuid {
                for other in players {
                    ops.set_listed(*viewer, *other, false);
                }
            } else if players.contains(&player.uuid) {
                ops.set_listed(*viewer, player.uuid, false);
            }
        }
    }

    /// Their line goes.
    fn player_quit(&self, player: &PlayerRef) {
        if self
            .shared
            .teams
            .borrow_mut()
            .below_names
            .remove(&player.uuid)
            .is_some()
        {
            self.shared
                .platform
                .teams()
                .set_below_name(player.name.clone(), None);
        }
    }

    /// Every team goes, every line under a name tag, and everyone is back in every list.
    fn stop(&self, _room: &Room, platform: &dyn Platform) {
        let mut teams = self.shared.teams.borrow_mut();
        let names: Vec<String> = teams.teams.keys().cloned().collect();
        for name in names {
            teams.remove(platform, &name);
        }
        if !teams.below_names.is_empty() {
            platform.teams().clear_below_names();
        }
        for (viewer, players) in &teams.unlisted {
            for player in players {
                platform.player_list().set_listed(*viewer, *player, true);
            }
        }
        *teams = Teams::default();
    }
}
