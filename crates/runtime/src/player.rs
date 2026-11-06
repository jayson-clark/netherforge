//! `Player` (Kotlin's `PlayerImpl`): a player by UUID. What needs them online answers nil or false while
//! they're offline; what works either way (bans, the whitelist, when they first played) needs only a UUID.
//! Their stats and buffs are the centities' stat sheets (`crate::stats`, `Holder::Player`).
//!
//! Not yet, each with its area: recipes (with items), menu templates, and resource packs.

use std::collections::BTreeMap;

use mlua::{Function, Value};
use netherforge_api::api::PlayerApi;
use netherforge_api::handles::{self, Handle};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, shapes, unions, values};
use netherforge_format::math::Vec3;
use netherforge_platform::{
    BanSpec, BorderOwner, BorderOwnerPlayer, EntityFlag, EntityNumber, InventoryRef,
    InventoryRefEnderChest, InventoryRefPlayer, InventoryRefStorage, ItemData, Location, PlayerOps,
    Uuid,
};

use crate::events::{inventory_handle, parse_uuid, uuid_text};
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// A world's name and a block's position in it.
type WorldBlock = (String, (i32, i32, i32));

/// Who a ban says made it when the script doesn't.
const BAN_SOURCE: &str = "NetherForge";
/// The most pages a written book has.
const BOOK_PAGES: usize = 100;
/// The most slots a storage has.
const STORAGE_MAX: i64 = 256;

/// Their UUID, for what works whether they're online or not; a handle that doesn't name anyone is an error.
fn anyone(this: &handles::Player) -> ApiResult<Uuid> {
    parse_uuid(&this.id).ok_or_else(|| err(format!("{} isn't a player's id", this.id)))
}

/// A permission node a script gave; anything else is an error.
fn permission_node(node: &str) -> ApiResult<String> {
    if !netherforge_format::names::is_permission_node(node) {
        return Err(err(format!(
            "\"{node}\" isn't a permission node ({})",
            netherforge_format::names::PERMISSION_NODE_RULE
        )));
    }
    Ok(node.to_owned())
}

/// A cooldown group's key (an item kind is its own group), namespaced.
fn cooldown_key(key: &str) -> ApiResult<String> {
    if !netherforge_format::game::is_id(key) {
        return Err(err(format!(
            "\"{key}\" isn't an item kind or a cooldown group (<namespace>:<name>)"
        )));
    }
    Ok(netherforge_format::game::normalize(key))
}

/// A number's text as Kotlin wrote it: a whole one without a point.
fn text(value: f64) -> String {
    if value == value.floor() && value.is_finite() {
        format!("{}", value as i64)
    } else {
        format!("{value}")
    }
}

fn unix_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

fn location_value(l: &Location) -> values::Location {
    values::Location {
        world: handles::World {
            name: l.world.clone(),
        },
        position: values::Vec3 {
            x: l.x,
            y: l.y,
            z: l.z,
        },
        yaw: Some(l.yaw),
        pitch: Some(l.pitch),
    }
}

impl GameContext {
    fn players(&self) -> &dyn PlayerOps {
        self.shared.platform.players()
    }

    /// Their UUID while they're online.
    fn player_online(&self, this: &handles::Player) -> Option<Uuid> {
        parse_uuid(&this.id).filter(|id| self.players().get(*id).is_some())
    }

    fn player_holder(&self, this: &handles::Player) -> Option<crate::stats::Holder> {
        parse_uuid(&this.id).map(crate::stats::Holder::Player)
    }

    fn player_flag(&self, this: &handles::Player, flag: EntityFlag) -> bool {
        self.player_online(this)
            .and_then(|id| self.shared.platform.world_entities().flag(id, flag))
            == Some(true)
    }

    fn player_number(&self, this: &handles::Player, number: EntityNumber) -> Option<f64> {
        self.player_online(this)
            .and_then(|id| self.shared.platform.world_entities().number(id, number))
    }

    /// Sets a number after checking it's from `low` to `high`; false when they're offline.
    fn set_player_number(
        &self,
        this: &handles::Player,
        number: EntityNumber,
        value: f64,
        name: &str,
        low: f64,
        high: f64,
    ) -> ApiResult<bool> {
        if value.is_nan() || value < low || value > high {
            return Err(err(format!(
                "{name} must be from {} to {}, not {}",
                text(low),
                text(high),
                text(value)
            )));
        }
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_number(id, number, value)
        }))
    }

    fn item_of(&self, item: Option<values::Item>) -> ApiResult<Option<ItemData>> {
        item.map(|i| crate::items::from_value(&i, "item", self.shared.platform.game()).map_err(err))
            .transpose()
    }

    /// The calling script's namespace: the project's for code no package's scope runs.
    pub(crate) fn calling_namespace(&self) -> String {
        self.scripts
            .host()
            .running_scope()
            .and_then(|scope| self.scripts.namespace(scope))
            .unwrap_or_else(|| self.shared.centities.borrow().home().to_owned())
    }

    /// The block a place is in, in their world when it names none; none when they're offline.
    fn block_for(&self, id: Uuid, place: unions::LocationOrVec3) -> ApiResult<Option<WorldBlock>> {
        let (world, position) = match place {
            unions::LocationOrVec3::Location(l) => (Some(l.world.name), l.position),
            unions::LocationOrVec3::Vec3(v) => (None, v),
        };
        let at = crate::world::block_at(&position)?;
        let Some(world) = world.or_else(|| self.players().location(id).map(|l| l.world)) else {
            return Ok(None);
        };
        Ok(Some((world, at)))
    }
}

impl GameContext {
    /// That the calling package allows `node` in its `netherforge.json` (Kotlin's `checkPermission`); code no
    /// package's scope runs needs every package to.
    fn check_permission(&self, node: &str, what: &str) -> ApiResult<()> {
        let state = self.shared.permissions.borrow();
        let Some(scope) = self.scripts.host().running_scope() else {
            if !state.allows.is_empty() && state.allows.values().all(|a| a.allows_permission(node))
            {
                return Ok(());
            }
            return Err(err(format!(
                "{what} needs permission \"{node}\" and was called by no package's code, so every package must allow it"
            )));
        };
        let namespace = self
            .scripts
            .namespace(scope)
            .unwrap_or_else(|| state.namespace.clone());
        let allow = state.allows.get(&namespace);
        if allow.is_some_and(|a| a.allows_permission(node)) {
            return Ok(());
        }
        let mut listed: Vec<String> = allow
            .and_then(|a| a.permissions.clone())
            .unwrap_or_default();
        listed.sort();
        let lists = if listed.is_empty() {
            " (it lists none)".to_owned()
        } else {
            format!(" (it lists: {})", listed.join(", "))
        };
        Err(err(format!(
            "{what} needs permission \"{node}\", which package \"{namespace}\" hasn't allowed: add it (or a node above it) to allow.permissions in its {}{lists}",
            netherforge_format::manifest::FILE_NAME
        )))
    }
}

impl GameContext {
    /// The server's key for an advancement a script names, once the server is known to have it (and
    /// `criterion`, when given): an error otherwise, even for someone offline. A namespace that's neither the
    /// project's nor a package's is the game's (`minecraft:…`, a datapack's) as written; anything else is the
    /// project's or a package's, named as every resource a script names.
    fn advancement(&self, name: &str, criterion: Option<&str>) -> ApiResult<String> {
        let (key, ours) = self.with_names(|names| -> ApiResult<(String, bool)> {
            let namespace = name.split_once(':').map(|(n, _)| n);
            if let Some(namespace) = namespace
                && !names.names.namespaces.contains_key(namespace)
            {
                if !netherforge_format::game::is_id(name) {
                    return Err(err(format!("\"{name}\" isn't an advancement's key (<namespace>:<path>)")));
                }
                return Ok((name.to_owned(), false));
            }
            let resolved = netherforge_format::reference::resolve_any(&names.from, name)
                .filter(|k| netherforge_format::names::is_id(&k.path));
            if resolved.is_none() {
                return Err(err(format!(
                    "\"{name}\" isn't an advancement: a project advancement's id, or a key like \"minecraft:story/mine_diamond\""
                )));
            }
            let named = names.resource("advancement", name)?;
            let key = netherforge_format::reference::resolve_any(&names.names.home, &named).expect("resolved");
            let ours = names
                .names
                .namespaces
                .get(&key.namespace)
                .is_some_and(|n| n.resources.contains(&("advancement".to_owned(), key.path.clone())));
            Ok((key.to_string(), ours))
        })?;
        let Some(criteria) = self.shared.platform.advancements().criteria(key.clone()) else {
            return Err(err(if ours {
                format!(
                    "the server has no advancement \"{key}\" yet: it learns the project's advancements as it starts, so restart it"
                )
            } else {
                format!("the server has no advancement \"{key}\"")
            }));
        };
        if let Some(criterion) = criterion
            && !criteria.iter().any(|c| c == criterion)
        {
            let has: Vec<String> = criteria.iter().map(|c| format!("\"{c}\"")).collect();
            return Err(err(format!(
                "advancement \"{key}\" has no criterion \"{criterion}\" (it has {})",
                has.join(", ")
            )));
        }
        Ok(key)
    }
}

impl PlayerApi for GameContext {
    fn stat(&self, this: &handles::Player, name: String) -> ApiResult<f64> {
        Ok(self
            .player_holder(this)
            .map_or(0.0, |h| self.shared.stats.borrow_mut().stat(h, &name)))
    }
    fn stats(&self, this: &handles::Player) -> ApiResult<BTreeMap<String, f64>> {
        Ok(self
            .player_holder(this)
            .map(|h| self.shared.stats.borrow_mut().stats(h))
            .unwrap_or_default())
    }
    fn modifiers(
        &self,
        this: &handles::Player,
        source: String,
    ) -> ApiResult<Option<BTreeMap<String, f64>>> {
        Ok(self
            .player_holder(this)
            .and_then(|h| self.shared.stats.borrow().modifiers(h, &source)))
    }
    fn set_modifiers(
        &self,
        this: &handles::Player,
        source: String,
        modifiers: Option<BTreeMap<String, f64>>,
    ) -> ApiResult<bool> {
        let Some(holder) = self.player_holder(this) else {
            return Ok(false);
        };
        self.shared
            .stats
            .borrow_mut()
            .set_modifiers(holder, &source, modifiers)
            .map_err(err)?;
        Ok(true)
    }
    fn add_buff(&self, this: &handles::Player, buff: String, ticks: i64) -> ApiResult<bool> {
        if ticks < 1 {
            return Err(err(format!(
                "bad argument 'ticks' (at least 1 expected, got {ticks})"
            )));
        }
        let centities = self.centities();
        let id = centities.buff(&buff)?;
        let Some(holder) = self.player_holder(this) else {
            return Ok(false);
        };
        Ok(centities.give_buff(holder, &id, ticks))
    }
    fn remove_buff(&self, this: &handles::Player, buff: String) -> ApiResult<bool> {
        let centities = self.centities();
        let id = centities.buff(&buff)?;
        let Some(holder) = self.player_holder(this) else {
            return Ok(false);
        };
        let had = self.shared.stats.borrow_mut().remove(holder, &id);
        if had {
            centities.buffs_ended(vec![crate::stats::Ended {
                holder,
                buff: id,
                reason: "removed",
            }]);
        }
        Ok(had)
    }
    fn has_buff(&self, this: &handles::Player, buff: String) -> ApiResult<bool> {
        let id = self.centities().buff(&buff)?;
        Ok(self
            .player_holder(this)
            .is_some_and(|h| self.shared.stats.borrow().ticks(h, &id).is_some()))
    }
    fn buff_ticks(&self, this: &handles::Player, buff: String) -> ApiResult<Option<i64>> {
        let id = self.centities().buff(&buff)?;
        Ok(self
            .player_holder(this)
            .and_then(|h| self.shared.stats.borrow().ticks(h, &id)))
    }
    fn buffs(&self, this: &handles::Player) -> ApiResult<Vec<shapes::ActiveBuff>> {
        let Some(holder) = self.player_holder(this) else {
            return Ok(Vec::new());
        };
        let stats = self.shared.stats.borrow();
        Ok(stats
            .buffs(holder)
            .into_iter()
            .map(|active| {
                let file = stats.file(&active.id);
                shapes::ActiveBuff {
                    id: active.id.clone(),
                    ticks: active.ticks,
                    name: file.and_then(|f| f.name.clone()).map(values::Text),
                    icon: file.and_then(|f| f.icon.clone()),
                    color: file.and_then(|f| f.color.clone()),
                    debuff: file.and_then(|f| f.debuff).unwrap_or(false),
                }
            })
            .collect())
    }
    fn name(&self, this: &handles::Player) -> ApiResult<String> {
        let players = self.players();
        Ok(parse_uuid(&this.id)
            .and_then(|id| players.get(id))
            .or_else(|| players.known(this.id.clone()))
            .map_or_else(|| this.id.clone(), |p| p.name))
    }
    fn display_name(&self, this: &handles::Player) -> ApiResult<Option<values::Text>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().display_name(id))
            .map(values::Text))
    }
    fn set_display_name(&self, this: &handles::Player, text: values::Text) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_display_name(id, text.0)))
    }
    fn send_message(&self, this: &handles::Player, text: values::Text) -> ApiResult<bool> {
        Ok(parse_uuid(&this.id).is_some_and(|id| self.players().message(id, text.0)))
    }
    fn send_actionbar(&self, this: &handles::Player, text: values::Text) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().actionbar(id, text.0)))
    }
    fn send_title(
        &self,
        this: &handles::Player,
        title: values::Text,
        subtitle: Option<values::Text>,
        options: Option<shapes::TitleOptions>,
    ) -> ApiResult<bool> {
        let ticks = |value: Option<i64>, default: i32, name: &str| -> ApiResult<i32> {
            match value {
                None => Ok(default),
                Some(v) if (0..=i64::from(i32::MAX)).contains(&v) => Ok(v as i32),
                Some(_) => Err(err(format!("options.{name} can't be negative"))),
            }
        };
        let o = options.as_ref();
        let fade_in = ticks(o.and_then(|o| o.fade_in), 10, "fade_in")?;
        let stay = ticks(o.and_then(|o| o.stay), 70, "stay")?;
        let fade_out = ticks(o.and_then(|o| o.fade_out), 20, "fade_out")?;
        Ok(self.player_online(this).is_some_and(|id| {
            self.players().title(
                id,
                title.0,
                subtitle.map(|s| s.0).unwrap_or_default(),
                fade_in,
                stay,
                fade_out,
            )
        }))
    }
    fn clear_title(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().clear_title(id)))
    }
    fn play_sound(
        &self,
        this: &handles::Player,
        sound: String,
        options: Option<shapes::SoundOptions>,
    ) -> ApiResult<bool> {
        let play = self.with_names(|names| {
            crate::effects::sound(&*self.shared.platform, names, &sound, options.as_ref())
        })?;
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.shared.platform.sounds().play_to(id, play)))
    }
    fn stop_sound(&self, this: &handles::Player, sound: Option<String>) -> ApiResult<bool> {
        let id = match sound {
            Some(sound) => Some(self.with_names(|names| {
                crate::effects::sound_id(&*self.shared.platform, names, &sound)
            })?),
            None => None,
        };
        Ok(self
            .player_online(this)
            .is_some_and(|uuid| self.shared.platform.sounds().stop(uuid, id)))
    }
    /// For that player alone, in their world; checked even while they're offline (then false).
    fn spawn_particle(
        &self,
        this: &handles::Player,
        particle: String,
        position: values::Vec3,
        options: Option<shapes::ParticleOptions>,
    ) -> ApiResult<bool> {
        let options = options.unwrap_or_else(crate::world::no_particle_options);
        if options.viewers.is_some() {
            return Err(err(
                "options.viewers: player:spawn_particle is only for that player (world:spawn_particle takes viewers)",
            ));
        }
        let platform = &*self.shared.platform;
        let spawn = crate::effects::particle_spawn(platform, &particle, &position, &options)?;
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        let Some(world) = self.players().location(id).map(|l| l.world) else {
            return Ok(false);
        };
        Ok(crate::effects::send(
            platform,
            &world,
            spawn,
            Some(std::slice::from_ref(&this.id)),
        ))
    }
    fn run(&self, this: &handles::Player, command: String) -> ApiResult<bool> {
        Ok(parse_uuid(&this.id).is_some_and(|id| {
            self.players()
                .run_command(id, command.strip_prefix('/').unwrap_or(&command).to_owned())
        }))
    }
    fn has_permission(&self, this: &handles::Player, permission: String) -> ApiResult<bool> {
        Ok(parse_uuid(&this.id).is_some_and(|id| self.players().has_permission(id, permission)))
    }
    fn set_permission(
        &self,
        this: &handles::Player,
        permission: String,
        value: bool,
    ) -> ApiResult<()> {
        let node = permission_node(&permission)?;
        self.check_permission(&node, "Player:set_permission")?;
        crate::permissions::set(&self.shared, anyone(this)?, &node, value);
        Ok(())
    }
    fn unset_permission(&self, this: &handles::Player, permission: String) -> ApiResult<bool> {
        let node = permission_node(&permission)?;
        Ok(crate::permissions::unset(
            &self.shared,
            anyone(this)?,
            &node,
        ))
    }
    fn permissions(&self, this: &handles::Player) -> ApiResult<BTreeMap<String, bool>> {
        let id = anyone(this)?;
        Ok(self
            .shared
            .permissions
            .borrow()
            .nodes
            .get(&id)
            .cloned()
            .unwrap_or_default())
    }
    fn is_operator(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(parse_uuid(&this.id).is_some_and(|id| self.players().is_operator(id)))
    }
    fn game_mode(&self, this: &handles::Player) -> ApiResult<Option<String>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().game_mode(id)))
    }
    fn set_game_mode(&self, this: &handles::Player, game_mode: String) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_game_mode(id, game_mode)))
    }
    fn food(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_number(this, EntityNumber::Food)
            .map(|n| n as i64))
    }
    fn set_food(&self, this: &handles::Player, food: i64) -> ApiResult<bool> {
        self.set_player_number(this, EntityNumber::Food, food as f64, "food", 0.0, 20.0)
    }
    fn saturation(&self, this: &handles::Player) -> ApiResult<Option<f64>> {
        Ok(self.player_number(this, EntityNumber::Saturation))
    }
    fn set_saturation(&self, this: &handles::Player, saturation: f64) -> ApiResult<bool> {
        let food = self.player_number(this, EntityNumber::Food).unwrap_or(20.0);
        self.set_player_number(
            this,
            EntityNumber::Saturation,
            saturation,
            "saturation",
            0.0,
            food,
        )
    }
    fn level(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_number(this, EntityNumber::Level)
            .map(|n| n as i64))
    }
    fn set_level(&self, this: &handles::Player, level: i64) -> ApiResult<bool> {
        self.set_player_number(
            this,
            EntityNumber::Level,
            level as f64,
            "level",
            0.0,
            f64::from(i32::MAX),
        )
    }
    fn experience_progress(&self, this: &handles::Player) -> ApiResult<Option<f64>> {
        Ok(self.player_number(this, EntityNumber::ExperienceProgress))
    }
    fn set_experience_progress(&self, this: &handles::Player, progress: f64) -> ApiResult<bool> {
        self.set_player_number(
            this,
            EntityNumber::ExperienceProgress,
            progress,
            "progress",
            0.0,
            1.0,
        )
    }
    fn give_experience(&self, this: &handles::Player, points: i64) -> ApiResult<bool> {
        if !(0..=i64::from(i32::MAX)).contains(&points) {
            return Err(err(format!("points must be at least 0, not {points}")));
        }
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().give_experience(id, points as i32)))
    }
    fn is_flying(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.player_flag(this, EntityFlag::Flying))
    }
    fn set_flying(&self, this: &handles::Player, flying: bool) -> ApiResult<bool> {
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_flag(id, EntityFlag::Flying, flying)
        }))
    }
    fn can_fly(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.player_flag(this, EntityFlag::CanFly))
    }
    fn set_can_fly(&self, this: &handles::Player, can_fly: bool) -> ApiResult<bool> {
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_flag(id, EntityFlag::CanFly, can_fly)
        }))
    }
    fn walk_speed(&self, this: &handles::Player) -> ApiResult<Option<f64>> {
        Ok(self.player_number(this, EntityNumber::WalkSpeed))
    }
    fn set_walk_speed(&self, this: &handles::Player, speed: f64) -> ApiResult<bool> {
        self.set_player_number(this, EntityNumber::WalkSpeed, speed, "speed", -1.0, 1.0)
    }
    fn fly_speed(&self, this: &handles::Player) -> ApiResult<Option<f64>> {
        Ok(self.player_number(this, EntityNumber::FlySpeed))
    }
    fn set_fly_speed(&self, this: &handles::Player, speed: f64) -> ApiResult<bool> {
        self.set_player_number(this, EntityNumber::FlySpeed, speed, "speed", -1.0, 1.0)
    }
    fn is_sneaking(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.player_flag(this, EntityFlag::Sneaking))
    }
    fn is_sprinting(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.player_flag(this, EntityFlag::Sprinting))
    }
    fn locale(&self, this: &handles::Player) -> ApiResult<Option<String>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().locale(id)))
    }
    fn ping(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_number(this, EntityNumber::Ping)
            .map(|n| n as i64))
    }
    fn kick(&self, this: &handles::Player, reason: Option<values::Text>) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().kick(id, reason.map(|r| r.0))))
    }
    fn first_played(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .shared
            .platform
            .server_admin()
            .first_played(anyone(this)?))
    }
    fn last_seen(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self.shared.platform.server_admin().last_seen(anyone(this)?))
    }
    fn is_banned(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.shared.platform.server_admin().is_banned(anyone(this)?))
    }
    fn ban(&self, this: &handles::Player, options: Option<shapes::BanOptions>) -> ApiResult<()> {
        let options = options.unwrap_or(shapes::BanOptions {
            reason: None,
            expires: None,
            source: None,
        });
        if let Some(expires) = options.expires
            && expires <= unix_millis()
        {
            return Err(err(
                "options.expires is in the past: it's Unix time in milliseconds, like nf.server.unix_time()",
            ));
        }
        self.shared.platform.server_admin().ban(
            anyone(this)?,
            BanSpec {
                reason: options.reason,
                expires: options.expires,
                source: options.source.unwrap_or_else(|| BAN_SOURCE.to_owned()),
            },
        );
        Ok(())
    }
    fn unban(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.shared.platform.server_admin().unban(anyone(this)?))
    }
    fn is_whitelisted(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self
            .shared
            .platform
            .server_admin()
            .is_whitelisted(anyone(this)?))
    }
    fn set_whitelisted(&self, this: &handles::Player, whitelisted: bool) -> ApiResult<()> {
        self.shared
            .platform
            .server_admin()
            .set_whitelisted(anyone(this)?, whitelisted);
        Ok(())
    }
    fn ender_chest(&self, this: &handles::Player) -> ApiResult<Option<handles::Inventory>> {
        Ok(self.player_online(this).map(|id| {
            inventory_handle(&InventoryRef::EnderChest(InventoryRefEnderChest {
                player: id,
            }))
        }))
    }
    fn sky(&self, this: &handles::Player) -> ApiResult<Option<String>> {
        let Some(id) = self.player_online(this) else {
            return Ok(None);
        };
        let sky = self.shared.skies.borrow().of_player(id);
        Ok(sky.map(|s| self.with_names(|names| names.spell(&s))))
    }
    fn set_sky(
        &self,
        this: &handles::Player,
        sky: String,
        options: Option<shapes::SkyOptions>,
    ) -> ApiResult<()> {
        let name = self.with_names(|names| names.resource("sky", &sky))?;
        let fade = crate::sky::fade_ticks(options.and_then(|o| o.fade_ticks))?;
        let platform = &*self.shared.platform;
        let mut skies = self.shared.skies.borrow_mut();
        skies.check(platform, &name)?;
        if let Some(id) = self.player_online(this) {
            skies.set_player(platform, id, &name, fade)?;
        }
        Ok(())
    }
    fn clear_sky(
        &self,
        this: &handles::Player,
        options: Option<shapes::SkyOptions>,
    ) -> ApiResult<()> {
        let fade = crate::sky::fade_ticks(options.and_then(|o| o.fade_ticks))?;
        if let Some(id) = self.player_online(this) {
            self.shared.skies.borrow_mut().clear_player(id, fade);
        }
        Ok(())
    }
    fn storage(
        &self,
        this: &handles::Player,
        name: String,
        size: i64,
    ) -> ApiResult<Option<handles::Inventory>> {
        if !netherforge_format::names::is_id(&name) {
            return Err(err(format!(
                "\"{name}\" isn't a storage name ({})",
                netherforge_format::names::ID_RULE
            )));
        }
        if !(1..=STORAGE_MAX).contains(&size) {
            return Err(err(format!(
                "a storage has 1 to {STORAGE_MAX} slots, not {size}"
            )));
        }
        let Some(id) = self.player_online(this) else {
            return Ok(None);
        };
        // The calling script's namespace: a package's storages are its own.
        let namespace = self.calling_namespace();
        Ok(Some(inventory_handle(&InventoryRef::Storage(
            InventoryRefStorage {
                player: id,
                name: format!("{namespace}:{name}"),
                size: size as i32,
            },
        ))))
    }
    fn hud(&self, this: &handles::Player) -> ApiResult<handles::Hud> {
        Ok(handles::Hud {
            player: this.id.clone(),
        })
    }
    fn held_item(&self, this: &handles::Player) -> ApiResult<Option<values::Item>> {
        Ok(self
            .player_online(this)
            .and_then(|id| {
                self.shared
                    .platform
                    .world_entities()
                    .equipment(id, "main_hand".into())
            })
            .map(|i| crate::items::to_value(&i)))
    }
    fn set_held_item(&self, this: &handles::Player, item: Option<values::Item>) -> ApiResult<bool> {
        let item = self.item_of(item)?;
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_equipment(id, "main_hand".into(), item)
        }))
    }
    fn off_hand_item(&self, this: &handles::Player) -> ApiResult<Option<values::Item>> {
        Ok(self
            .player_online(this)
            .and_then(|id| {
                self.shared
                    .platform
                    .world_entities()
                    .equipment(id, "off_hand".into())
            })
            .map(|i| crate::items::to_value(&i)))
    }
    fn set_off_hand_item(
        &self,
        this: &handles::Player,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = self.item_of(item)?;
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .world_entities()
                .set_equipment(id, "off_hand".into(), item)
        }))
    }
    fn cursor_item(&self, this: &handles::Player) -> ApiResult<Option<values::Item>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().cursor(id))
            .map(|i| crate::items::to_value(&i)))
    }
    fn set_cursor_item(
        &self,
        this: &handles::Player,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = self.item_of(item)?;
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_cursor(id, item)))
    }
    fn held_slot(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_number(this, EntityNumber::HeldSlot)
            .map(|n| n as i64))
    }
    fn set_held_slot(&self, this: &handles::Player, index: i64) -> ApiResult<bool> {
        self.set_player_number(
            this,
            EntityNumber::HeldSlot,
            index as f64,
            "index",
            0.0,
            8.0,
        )
    }
    fn give_item(&self, this: &handles::Player, item: values::Item) -> ApiResult<Option<i64>> {
        let item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        let Some(id) = self.player_online(this) else {
            return Ok(None);
        };
        let Some(left) = self.shared.platform.inventories().add(
            InventoryRef::Player(InventoryRefPlayer { player: id }),
            item.clone(),
        ) else {
            return Ok(None);
        };
        if left > 0
            && let Some(at) = self.players().location(id)
        {
            let mut rest = item;
            rest.def.count = Some(left);
            self.shared.platform.world_entities().spawn_item(
                at.world,
                Vec3(at.x, at.y, at.z),
                rest,
            );
        }
        Ok(Some(i64::from(left)))
    }
    fn discover_recipe(&self, this: &handles::Player, recipe: String) -> ApiResult<bool> {
        <Unported as PlayerApi>::discover_recipe(&Unported, this, recipe)
    }
    fn undiscover_recipe(&self, this: &handles::Player, recipe: String) -> ApiResult<bool> {
        <Unported as PlayerApi>::undiscover_recipe(&Unported, this, recipe)
    }
    fn has_discovered_recipe(&self, this: &handles::Player, recipe: String) -> ApiResult<bool> {
        <Unported as PlayerApi>::has_discovered_recipe(&Unported, this, recipe)
    }
    fn grant_advancement(
        &self,
        this: &handles::Player,
        advancement: String,
        criterion: Option<String>,
    ) -> ApiResult<bool> {
        let key = self.advancement(&advancement, criterion.as_deref())?;
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .advancements()
                .grant(id, key, criterion)
        }))
    }
    fn revoke_advancement(
        &self,
        this: &handles::Player,
        advancement: String,
        criterion: Option<String>,
    ) -> ApiResult<bool> {
        let key = self.advancement(&advancement, criterion.as_deref())?;
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .advancements()
                .revoke(id, key, criterion)
        }))
    }
    fn has_advancement(&self, this: &handles::Player, advancement: String) -> ApiResult<bool> {
        let key = self.advancement(&advancement, None)?;
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.shared.platform.advancements().has(id, key)))
    }
    fn advancement_progress(
        &self,
        this: &handles::Player,
        advancement: String,
    ) -> ApiResult<Option<shapes::AdvancementProgress>> {
        let key = self.advancement(&advancement, None)?;
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.advancements().progress(id, key))
            .map(|(done, remaining)| shapes::AdvancementProgress { done, remaining }))
    }
    fn item_cooldown(&self, this: &handles::Player, key: String) -> ApiResult<Option<i64>> {
        let group = cooldown_key(&key)?;
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().cooldown(id, group))
            .map(i64::from))
    }
    fn set_item_cooldown(
        &self,
        this: &handles::Player,
        key: String,
        ticks: i64,
    ) -> ApiResult<bool> {
        let group = cooldown_key(&key)?;
        if !(0..=i64::from(i32::MAX)).contains(&ticks) {
            return Err(err("ticks can't be negative"));
        }
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_cooldown(id, group, ticks as i32)))
    }
    fn open_menu(
        &self,
        this: &handles::Player,
        menu: unions::StringOrMenuTemplate,
        options: Option<shapes::MenuOpenOptions>,
    ) -> ApiResult<Option<handles::Menu>> {
        GameContext::open_menu(self, this, menu, options)
    }
    fn menu(&self, this: &handles::Player) -> ApiResult<Option<handles::Menu>> {
        Ok(self.menu_of(this))
    }
    /// Only queued: the stacks go at the end of the tick, when the game has settled what each window shows.
    fn refresh_items(&self, this: &handles::Player) -> ApiResult<bool> {
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        self.items().refresh_view(id);
        Ok(true)
    }
    fn close_menu(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self.close_menu_of(this))
    }
    fn open_inventory(
        &self,
        this: &handles::Player,
        inventory: handles::Inventory,
    ) -> ApiResult<bool> {
        let Some(r#ref) = crate::events::inventory_ref(&inventory) else {
            return Ok(false);
        };
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.shared.platform.inventories().open(r#ref, id)))
    }
    fn close_inventory(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().close_inventory(id)))
    }
    fn open_dialog(
        &self,
        this: &handles::Player,
        dialog: unions::StringOrDialog,
        options: Option<shapes::DialogOpenOptions>,
    ) -> ApiResult<bool> {
        GameContext::open_dialog(self, this, dialog, options)
    }
    fn close_dialog(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(GameContext::close_dialog(self, this))
    }
    fn sidebar(&self, this: &handles::Player) -> ApiResult<handles::Sidebar> {
        Ok(handles::Sidebar {
            player: this.id.clone(),
        })
    }
    fn border(&self, this: &handles::Player) -> ApiResult<Option<handles::WorldBorder>> {
        let Some(id) = self.player_online(this) else {
            return Ok(None);
        };
        Ok(self
            .shared
            .platform
            .borders()
            .personal(id)
            .then(|| handles::WorldBorder {
                owner: "player".into(),
                id: uuid_text(&id),
            }))
    }
    fn has_own_border(&self, this: &handles::Player) -> ApiResult<bool> {
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .borders()
            .get(BorderOwner::Player(BorderOwnerPlayer { uuid: id }))
            .is_some())
    }
    fn reset_border(&self, this: &handles::Player) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.shared.platform.borders().reset(id)))
    }
    fn tab_header(&self, this: &handles::Player) -> ApiResult<Option<values::Text>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().tab_text(id, false))
            .map(values::Text))
    }
    fn set_tab_header(&self, this: &handles::Player, text: values::Text) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_tab_text(id, false, text.0)))
    }
    fn tab_footer(&self, this: &handles::Player) -> ApiResult<Option<values::Text>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.players().tab_text(id, true))
            .map(values::Text))
    }
    fn set_tab_footer(&self, this: &handles::Player, text: values::Text) -> ApiResult<bool> {
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.players().set_tab_text(id, true, text.0)))
    }
    fn tab_name(&self, this: &handles::Player) -> ApiResult<Option<values::Text>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.player_list().name(id))
            .map(values::Text))
    }
    fn set_tab_name(&self, this: &handles::Player, text: Option<values::Text>) -> ApiResult<bool> {
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .player_list()
                .set_name(id, text.map(|t| t.0))
        }))
    }
    fn tab_order(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.player_list().order(id))
            .map(i64::from))
    }
    fn set_tab_order(&self, this: &handles::Player, order: i64) -> ApiResult<bool> {
        let Ok(order) = i32::try_from(order) else {
            return Err(err(format!("order must fit in 32 bits, not {order}")));
        };
        Ok(self
            .player_online(this)
            .is_some_and(|id| self.shared.platform.player_list().set_order(id, order)))
    }
    fn is_listed_for(&self, this: &handles::Player, viewer: handles::Player) -> ApiResult<bool> {
        let (Some(player), Some(other)) = (self.player_online(this), self.player_online(&viewer))
        else {
            return Ok(false);
        };
        Ok(self.is_listed(other, player))
    }
    fn set_listed_for(
        &self,
        this: &handles::Player,
        viewer: handles::Player,
        listed: bool,
    ) -> ApiResult<bool> {
        let (Some(player), Some(other)) = (self.player_online(this), self.player_online(&viewer))
        else {
            return Ok(false);
        };
        Ok(self.set_listed(other, player, listed))
    }
    fn below_name(&self, this: &handles::Player) -> ApiResult<Option<values::Text>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.below_name(id))
            .map(values::Text))
    }
    fn set_below_name(
        &self,
        this: &handles::Player,
        text: Option<values::Text>,
    ) -> ApiResult<bool> {
        let Some(player) = self
            .player_online(this)
            .and_then(|id| self.players().get(id))
        else {
            return Ok(false);
        };
        GameContext::set_below_name(self, &player, text.map(|t| t.0));
        Ok(true)
    }
    fn bossbars(&self, this: &handles::Player) -> ApiResult<Vec<handles::BossBar>> {
        Ok(parse_uuid(&this.id)
            .map(|id| self.bars_of(id))
            .unwrap_or_default())
    }
    fn resource_pack_status(
        &self,
        this: &handles::Player,
        pack: Option<String>,
    ) -> ApiResult<Option<String>> {
        <Unported as PlayerApi>::resource_pack_status(&Unported, this, pack)
    }
    fn push_resource_pack(&self, this: &handles::Player, pack: String) -> ApiResult<bool> {
        <Unported as PlayerApi>::push_resource_pack(&Unported, this, pack)
    }
    fn pop_resource_pack(&self, this: &handles::Player, pack: String) -> ApiResult<bool> {
        <Unported as PlayerApi>::pop_resource_pack(&Unported, this, pack)
    }
    fn send_block_change(
        &self,
        this: &handles::Player,
        location_or_position: unions::LocationOrVec3,
        state: String,
    ) -> ApiResult<bool> {
        let canonical =
            crate::block::canonical_state(self.shared.platform.game(), &state, "state")?;
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        let Some((world, (x, y, z))) = self.block_for(id, location_or_position)? else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .player_views()
            .send_block(id, world, x, y, z, canonical))
    }
    fn reset_block(
        &self,
        this: &handles::Player,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        let Some((world, (x, y, z))) = self.block_for(id, location_or_position)? else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .player_views()
            .reset_block(id, world, x, y, z))
    }
    fn send_equipment_change(
        &self,
        this: &handles::Player,
        entity: handles::Entity,
        slot: String,
        item: Option<values::Item>,
    ) -> ApiResult<bool> {
        let item = self.item_of(item)?;
        let (Some(id), Some(other)) = (self.player_online(this), parse_uuid(&entity.id)) else {
            return Ok(false);
        };
        Ok(self
            .shared
            .platform
            .player_views()
            .send_equipment(id, other, slot, item))
    }
    fn camera(&self, this: &handles::Player) -> ApiResult<Option<handles::Entity>> {
        let lua = self.scripts.host().lua().clone();
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.player_views().camera(id))
            .map(|e| self.shared.entity_by_id(&lua, e)))
    }
    fn set_camera(
        &self,
        this: &handles::Player,
        entity: Option<handles::Entity>,
    ) -> ApiResult<bool> {
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        let target = match entity {
            Some(e) => match parse_uuid(&e.id) {
                Some(t) => Some(t),
                None => return Ok(false),
            },
            None => None,
        };
        Ok(self.shared.platform.player_views().set_camera(id, target))
    }
    fn compass_target(&self, this: &handles::Player) -> ApiResult<Option<values::Location>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.player_views().compass_target(id))
            .map(|l| location_value(&l)))
    }
    fn set_compass_target(
        &self,
        this: &handles::Player,
        location: values::Location,
    ) -> ApiResult<bool> {
        let Some(id) = self.player_online(this) else {
            return Ok(false);
        };
        let target = Location {
            world: location.world.name,
            x: location.position.x,
            y: location.position.y,
            z: location.position.z,
            yaw: 0.0,
            pitch: 0.0,
        };
        Ok(self
            .shared
            .platform
            .player_views()
            .set_compass_target(id, target))
    }
    fn open_book(&self, this: &handles::Player, pages: Vec<values::Text>) -> ApiResult<bool> {
        if pages.len() > BOOK_PAGES {
            return Err(err(format!(
                "a book has at most {BOOK_PAGES} pages, not {}",
                pages.len()
            )));
        }
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .player_views()
                .open_book(id, pages.into_iter().map(|p| p.0).collect())
        }))
    }
    fn view_distance(&self, this: &handles::Player) -> ApiResult<Option<i64>> {
        Ok(self
            .player_online(this)
            .and_then(|id| self.shared.platform.player_views().view_distance(id))
            .map(i64::from))
    }
    fn set_view_distance(&self, this: &handles::Player, distance: i64) -> ApiResult<bool> {
        if !(2..=32).contains(&distance) {
            return Err(err(format!(
                "distance must be from 2 to 32 chunks, not {distance}"
            )));
        }
        Ok(self.player_online(this).is_some_and(|id| {
            self.shared
                .platform
                .player_views()
                .set_view_distance(id, distance as i32)
        }))
    }
    /// Theirs whether they're online or not; a handle that doesn't name anyone is an error.
    fn data(&self, this: &handles::Player) -> ApiResult<Value> {
        let id = anyone(this)?;
        let label = format!("player {}", PlayerApi::name(self, this)?);
        let host = self.scripts.host();
        crate::data::ScriptData::table(&self.shared, &host, crate::data::Owner::Player(id), label)
            .map(Value::Table)
            .map_err(|e| err(e.to_string()))
    }
    /// Their table while they're online; one made before they left is still theirs until their `player_quit`
    /// handlers are done.
    fn session(&self, this: &handles::Player) -> ApiResult<Value> {
        let Some(id) = parse_uuid(&this.id) else {
            return Ok(Value::Nil);
        };
        if let Some(table) = self.shared.session_tables.borrow().get(&id) {
            return Ok(Value::Table(table.clone()));
        }
        if self.player_online(this).is_none() {
            return Ok(Value::Nil);
        }
        let table = self
            .scripts
            .host()
            .lua()
            .create_table()
            .map_err(|e| err(e.to_string()))?;
        self.shared
            .session_tables
            .borrow_mut()
            .insert(id, table.clone());
        Ok(Value::Table(table))
    }
    fn on(
        &self,
        this: &handles::Player,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Player(this.clone()),
            "Player:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::Player,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe_on(
            Handle::Player(this.clone()),
            "Player:once",
            event,
            handler,
            None,
            true,
        )
    }
}
