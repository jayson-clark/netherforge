//! `Block` (Kotlin's `BlockImpl`): a position in a world, read live. Reads answer nil or false in a chunk that
//! isn't loaded (nothing here loads one); a block state the server doesn't have is an error.
//!
//! Not yet: `custom` (custom blocks: until then no block is one of the project's) and `data` (blocks' saved
//! data).

use std::collections::BTreeMap;

use mlua::Value;
use netherforge_api::api::BlockApi;
use netherforge_api::handles;
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, shapes, unions, values};
use netherforge_format::block_state::BlockState;
use netherforge_format::game::GameData;
use netherforge_platform::{BlockSnapshot, InventoryRef, InventoryRefBlock};

use crate::events::{inventory_handle, pack};
use crate::world::{GameContext, block_at, unpack};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// `text` as the canonical state the server takes (Kotlin's `Effects.blockState`): its id namespaced, every
/// property it leaves out at the block's default, sorted. `place` names it in an error.
pub fn canonical_state(game: &dyn GameData, text: &str, place: &str) -> ApiResult<String> {
    let state = BlockState::parse(text)
        .ok_or_else(|| err(format!("{place}: \"{text}\" isn't a block state")))?;
    let properties = game
        .block_properties(&state.id)
        .ok_or_else(|| err(format!("{place}: no block \"{}\" on this server", state.id)))?;
    for (property, value) in &state.properties {
        let Some(values) = properties.get(property) else {
            let has: Vec<&str> = properties.keys().map(String::as_str).collect();
            let has = if has.is_empty() {
                "none".to_owned()
            } else {
                has.join(", ")
            };
            return Err(err(format!(
                "{place}: {} has no property \"{property}\" (it has: {has})",
                state.id
            )));
        };
        if !values.contains(value) {
            return Err(err(format!(
                "{place}: {}'s {property} can't be \"{value}\" (it can be {})",
                state.id,
                values.join(", ")
            )));
        }
    }
    let mut full = state.clone();
    for property in properties.keys() {
        if !full.properties.iter().any(|(k, _)| k == property)
            && let Some(default) = game.block_default(&state.id, property)
        {
            full.properties.push((property.clone(), default));
        }
    }
    Ok(full.to_string())
}

/// The neighbour each face names, as `block:relative("up")` takes it.
fn face(name: &str) -> Option<(i32, i32, i32)> {
    Some(match name {
        "up" => (0, 1, 0),
        "down" => (0, -1, 0),
        "north" => (0, 0, -1),
        "south" => (0, 0, 1),
        "east" => (1, 0, 0),
        "west" => (-1, 0, 0),
        _ => return None,
    })
}

impl GameContext {
    fn snapshot(&self, this: &handles::Block) -> Option<BlockSnapshot> {
        let (x, y, z) = unpack(this.position);
        self.shared
            .platform
            .blocks()
            .get(this.world.clone(), x, y, z)
    }

    fn parsed(&self, this: &handles::Block) -> Option<BlockState> {
        BlockState::parse(&self.snapshot(this)?.state)
    }

    /// Sets the block to the canonical `state`; physics reads its section again.
    fn set_at(&self, this: &handles::Block, state: String, update: bool) -> bool {
        let (x, y, z) = unpack(this.position);
        let set =
            self.shared
                .platform
                .blocks()
                .set(this.world.clone(), x, y, z, state.clone(), update);
        if set {
            self.shared
                .physics
                .borrow_mut()
                .block_changed(&this.world, x, y, z);
            self.blocks().replaced(&this.world, x, y, z, &state);
        }
        set
    }

    fn corner(this: &handles::Block) -> values::Vec3 {
        let (x, y, z) = unpack(this.position);
        values::Vec3 {
            x: f64::from(x),
            y: f64::from(y),
            z: f64::from(z),
        }
    }
}

impl BlockApi for GameContext {
    fn world(&self, this: &handles::Block) -> ApiResult<handles::World> {
        Ok(handles::World {
            name: this.world.clone(),
        })
    }

    fn position(&self, this: &handles::Block) -> ApiResult<values::Vec3> {
        Ok(GameContext::corner(this))
    }

    fn location(&self, this: &handles::Block) -> ApiResult<values::Location> {
        Ok(values::Location {
            world: handles::World {
                name: this.world.clone(),
            },
            position: GameContext::corner(this),
            yaw: None,
            pitch: None,
        })
    }

    fn kind(&self, this: &handles::Block) -> ApiResult<Option<String>> {
        Ok(self.parsed(this).map(|s| s.id))
    }

    fn state(&self, this: &handles::Block) -> ApiResult<Option<String>> {
        Ok(self.parsed(this).map(|s| s.to_string()))
    }

    fn property(&self, this: &handles::Block, name: String) -> ApiResult<Option<String>> {
        Ok(self.parsed(this).and_then(|s| {
            s.properties
                .into_iter()
                .find(|(k, _)| *k == name)
                .map(|(_, v)| v)
        }))
    }

    fn properties(&self, this: &handles::Block) -> ApiResult<Option<BTreeMap<String, String>>> {
        Ok(self
            .parsed(this)
            .map(|s| s.properties.into_iter().collect()))
    }

    fn is_air(&self, this: &handles::Block) -> ApiResult<bool> {
        Ok(self.snapshot(this).is_some_and(|s| s.air))
    }

    fn is_solid(&self, this: &handles::Block) -> ApiResult<bool> {
        Ok(self.snapshot(this).is_some_and(|s| s.solid))
    }

    fn is_liquid(&self, this: &handles::Block) -> ApiResult<bool> {
        Ok(self.snapshot(this).is_some_and(|s| s.liquid))
    }

    fn set_state(
        &self,
        this: &handles::Block,
        state: String,
        options: Option<shapes::BlockSetOptions>,
    ) -> ApiResult<bool> {
        let canonical = canonical_state(self.shared.platform.game(), &state, "state")?;
        let update = options.and_then(|o| o.update) != Some(false);
        Ok(self.set_at(this, canonical, update))
    }

    fn set_property(&self, this: &handles::Block, name: String, value: String) -> ApiResult<bool> {
        let Some(mut current) = self.parsed(this) else {
            return Ok(false);
        };
        let values = self
            .shared
            .platform
            .game()
            .block_properties(&current.id)
            .and_then(|p| p.get(&name).cloned())
            .ok_or_else(|| err(format!("{} has no property \"{name}\"", current.id)))?;
        if !values.contains(&value) {
            return Err(err(format!(
                "{}'s {name} can't be \"{value}\" (it can be {})",
                current.id,
                values.join(", ")
            )));
        }
        match current.properties.iter_mut().find(|(k, _)| *k == name) {
            Some(entry) => entry.1 = value,
            None => current.properties.push((name, value)),
        }
        Ok(self.set_at(this, current.to_string(), true))
    }

    fn break_naturally(
        &self,
        this: &handles::Block,
        tool: Option<values::Item>,
    ) -> ApiResult<bool> {
        let tool = match tool {
            Some(item) => Some(
                crate::items::from_value(&item, "tool", self.shared.platform.game())
                    .map_err(err)?,
            ),
            None => None,
        };
        let (x, y, z) = unpack(this.position);
        let broken =
            self.shared
                .platform
                .blocks()
                .break_naturally(this.world.clone(), x, y, z, tool);
        if broken {
            self.shared
                .physics
                .borrow_mut()
                .block_changed(&this.world, x, y, z);
        }
        Ok(broken)
    }

    fn relative(
        &self,
        this: &handles::Block,
        offset: unions::Vec3OrChoice,
    ) -> ApiResult<handles::Block> {
        let (x, y, z) = unpack(this.position);
        let (dx, dy, dz) = match offset {
            unions::Vec3OrChoice::Vec3(v) => {
                (v.x.floor() as i32, v.y.floor() as i32, v.z.floor() as i32)
            }
            unions::Vec3OrChoice::Choice(name) => {
                face(&name).ok_or_else(|| err(format!("\"{name}\" isn't a face")))?
            }
        };
        // Out of any world is an error, as a position is.
        block_at(&values::Vec3 {
            x: f64::from(x + dx),
            y: f64::from(y + dy),
            z: f64::from(z + dz),
        })?;
        Ok(handles::Block {
            world: this.world.clone(),
            position: pack(x + dx, y + dy, z + dz),
        })
    }

    fn inventory(&self, this: &handles::Block) -> ApiResult<Option<handles::Inventory>> {
        let (x, y, z) = unpack(this.position);
        let r#ref = InventoryRef::Block(InventoryRefBlock {
            world: this.world.clone(),
            x,
            y,
            z,
        });
        Ok(self
            .shared
            .platform
            .inventories()
            .kind(r#ref.clone())
            .map(|_| inventory_handle(&r#ref)))
    }

    fn custom(&self, this: &handles::Block) -> ApiResult<Option<handles::CustomBlock>> {
        Ok(self.custom_at(&this.world, this.position))
    }

    fn light_level(&self, this: &handles::Block) -> ApiResult<Option<i64>> {
        Ok(self.snapshot(this).map(|s| i64::from(s.light)))
    }

    fn sky_light_level(&self, this: &handles::Block) -> ApiResult<Option<i64>> {
        Ok(self.snapshot(this).map(|s| i64::from(s.sky_light)))
    }

    /// A game biome as the game names it; the project's own without its namespace.
    fn biome(&self, this: &handles::Block) -> ApiResult<Option<String>> {
        let (x, y, z) = unpack(this.position);
        let Some(key) = self
            .shared
            .platform
            .worlds()
            .biome(this.world.clone(), x, y, z)
        else {
            return Ok(None);
        };
        let home = self.shared.centities.borrow().home().to_owned();
        Ok(Some(match key.strip_prefix(&format!("{home}:")) {
            Some(own) if !home.is_empty() => own.to_owned(),
            _ => key,
        }))
    }

    fn data(&self, this: &handles::Block) -> ApiResult<Value> {
        <Unported as BlockApi>::data(&Unported, this)
    }
}
