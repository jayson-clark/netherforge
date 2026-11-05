//! `WorldBorder` (Kotlin's `WorldBorderImpl`): a world's border, or a player's own, read and set through the
//! Platform; nil or false once it isn't there (the world unloaded, the player left or lost theirs).

use netherforge_api::api::WorldBorderApi;
use netherforge_api::{ApiError, ApiResult, handles, shapes, unions, values};
use netherforge_platform::{BorderOwner, BorderOwnerPlayer, BorderOwnerWorld, BorderState};

use crate::events::parse_uuid;
use crate::world::GameContext;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

impl GameContext {
    fn border_owner(this: &handles::WorldBorder) -> Option<BorderOwner> {
        match this.owner.as_str() {
            "world" => Some(BorderOwner::World(BorderOwnerWorld {
                name: this.id.clone(),
            })),
            "player" => {
                parse_uuid(&this.id).map(|uuid| BorderOwner::Player(BorderOwnerPlayer { uuid }))
            }
            _ => None,
        }
    }

    /// The owner and what the border is now, while it's there.
    fn live_border(&self, this: &handles::WorldBorder) -> Option<(BorderOwner, BorderState)> {
        let owner = Self::border_owner(this)?;
        let state = self.shared.platform.borders().get(owner.clone())?;
        Some((owner, state))
    }
}

impl WorldBorderApi for GameContext {
    fn center(&self, this: &handles::WorldBorder) -> ApiResult<Option<values::Vec3>> {
        Ok(self.live_border(this).map(|(_, s)| values::Vec3 {
            x: s.center_x,
            y: 0.0,
            z: s.center_z,
        }))
    }

    fn set_center(&self, this: &handles::WorldBorder, center: values::Vec3) -> ApiResult<bool> {
        let borders = self.shared.platform.borders();
        let limit = borders.max_center();
        if !center.x.is_finite()
            || !center.z.is_finite()
            || center.x.abs() > limit
            || center.z.abs() > limit
        {
            return Err(err(format!(
                "a border's centre can't be more than {} blocks out along x or z",
                limit as i64
            )));
        }
        let Some((owner, _)) = self.live_border(this) else {
            return Ok(false);
        };
        Ok(borders.set_center(owner, center.x, center.z))
    }

    fn size(&self, this: &handles::WorldBorder) -> ApiResult<Option<f64>> {
        Ok(self.live_border(this).map(|(_, s)| s.size))
    }

    fn set_size(
        &self,
        this: &handles::WorldBorder,
        size: f64,
        options: Option<shapes::BorderSizeOptions>,
    ) -> ApiResult<bool> {
        let borders = self.shared.platform.borders();
        let max = borders.max_size();
        if size.is_nan() || !(1.0..=max).contains(&size) {
            return Err(err(format!(
                "a border's size must be from 1 to {}, not {size}",
                max as i64
            )));
        }
        let ticks = options.and_then(|o| o.ticks).unwrap_or(0);
        if ticks < 0 {
            return Err(err("ticks can't be negative"));
        }
        if ticks > i64::from(i32::MAX) {
            return Err(err(format!("ticks can be at most {}", i32::MAX)));
        }
        let Some((owner, _)) = self.live_border(this) else {
            return Ok(false);
        };
        Ok(borders.set_size(owner, size, ticks))
    }

    fn damage(&self, this: &handles::WorldBorder) -> ApiResult<Option<shapes::BorderDamage>> {
        Ok(self.live_border(this).map(|(_, s)| shapes::BorderDamage {
            amount: Some(s.damage_amount),
            buffer: Some(s.damage_buffer),
        }))
    }

    fn set_damage(
        &self,
        this: &handles::WorldBorder,
        damage: shapes::BorderDamage,
    ) -> ApiResult<bool> {
        for (name, value) in [("amount", damage.amount), ("buffer", damage.buffer)] {
            if value.is_some_and(|v| v.is_nan() || v < 0.0) {
                return Err(err(format!("damage.{name} can't be negative")));
            }
        }
        let Some((owner, now)) = self.live_border(this) else {
            return Ok(false);
        };
        Ok(self.shared.platform.borders().set_damage(
            owner,
            damage.amount.unwrap_or(now.damage_amount),
            damage.buffer.unwrap_or(now.damage_buffer),
        ))
    }

    fn warning(&self, this: &handles::WorldBorder) -> ApiResult<Option<shapes::BorderWarning>> {
        Ok(self.live_border(this).map(|(_, s)| shapes::BorderWarning {
            distance: Some(i64::from(s.warning_distance)),
            ticks: Some(i64::from(s.warning_ticks)),
        }))
    }

    fn set_warning(
        &self,
        this: &handles::WorldBorder,
        warning: shapes::BorderWarning,
    ) -> ApiResult<bool> {
        for (name, value) in [("distance", warning.distance), ("ticks", warning.ticks)] {
            if let Some(v) = value
                && !(0..=i64::from(i32::MAX)).contains(&v)
            {
                return Err(err(format!(
                    "warning.{name} must be from 0 to {}, not {v}",
                    i32::MAX
                )));
            }
        }
        let Some((owner, now)) = self.live_border(this) else {
            return Ok(false);
        };
        Ok(self.shared.platform.borders().set_warning(
            owner,
            warning.distance.map_or(now.warning_distance, |d| d as i32),
            warning.ticks.map_or(now.warning_ticks, |t| t as i32),
        ))
    }

    fn contains(
        &self,
        this: &handles::WorldBorder,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let Some((owner, _)) = self.live_border(this) else {
            return Ok(false);
        };
        let (world, p) = match location_or_position {
            unions::LocationOrVec3::Location(l) => (Some(l.world.name), l.position),
            unions::LocationOrVec3::Vec3(v) => (None, v),
        };
        Ok(self
            .shared
            .platform
            .borders()
            .contains(owner, world, p.x, p.y, p.z))
    }
}
