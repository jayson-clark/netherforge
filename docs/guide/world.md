# The world

Scripts reach the Minecraft world through two handles: a `World` (one of the
server's worlds) and a `Block` (one position in one). Particles and sounds go
through the world too. Everything here is world space, in blocks.

```lua
local world = nf.worlds.default()
world:set_block(vec3(0, 64, 0), "minecraft:gold_block")
world:spawn_particle("minecraft:happy_villager", vec3(0.5, 65, 0.5), { count = 10 })
world:play_sound("minecraft:block.note_block.pling", vec3(0, 64, 0), { pitch = 1.5 })
```

## Worlds

`nf.worlds.all()`, `nf.worlds.get(name)` and `nf.worlds.default()` hand out
`World` handles; so do `player:world()`, `centity:world()` and every
`Location`'s `world` field. A handle is the world's name, so it's fine to
keep: if the world is unloaded, its methods answer `nil` or `false`.

| What                  | Methods                                                                            |
| --------------------- | ---------------------------------------------------------------------------------- |
| about it              | `name()`, `exists()`, `environment()`, `min_height()`, `max_height()`              |
| the clock             | `time_of_day()`, `set_time_of_day(ticks)`, `day_count()`                           |
| the weather           | `weather()`, `set_weather("clear"\|"rain"\|"thunder", { ticks }?)`                 |
| spawn                 | `spawn_location()`, `set_spawn_location(location_or_position)`                     |
| game rules            | `game_rule(rule)`, `set_game_rule(rule, value)`                                    |
| who and what is in it | `players()`, `entities(filter?)`, `centities(filter?)`                             |
| entities              | `spawn_entity(kind, location_or_position, options?)`, `spawn_item(position, item)` |
| chunks                | `is_chunk_loaded(position)`, `load_chunk(position)`                                |
| blocks                | `block(position)`, `set_block(...)`, `fill_blocks(...)`, `highest_block(...)`      |
| effects               | `spawn_particle(...)`, `play_sound(...)`, `explode(...)`, `strike_lightning(...)`  |
| rays                  | `raycast(from, direction, max_distance, options?)`                                 |

`world:location(position, yaw?, pitch?)` builds a `Location` in that world,
for `player:teleport` and `nf.centities.spawn`.

## Blocks

`world:block(position)` is the block a point is in. The handle is the
position, not what's there: it reads the world live, so a `Block` kept from
earlier sees later changes.

```lua
local block = world:block(vec3(10, 64, 10))
if block:kind() == "minecraft:oak_door" then
  block:set_property("open", "true")
end
local above = block:relative("up")
```

- `kind()`, `state()`, `property(name)` and `properties()` read it;
  `is_air()`, `is_solid()` and `is_liquid()` ask about it;
  `light_level()` and `sky_light_level()` say how bright it is.
- `set_state(state, options?)` and `world:set_block(position, state, options?)`
  change it. A block state names a block and any properties
  (`"minecraft:oak_stairs[facing=east]"`); properties left out take their
  defaults. `{ update = false }` changes it without its neighbours reacting.
- `break_naturally(tool?)` breaks it with its drops.
- `inventory()` is a container block's `Inventory` (a chest, a barrel, a
  hopper), or `nil` for a block that isn't one: see
  [players, entities and inventories](./players-and-entities.md#inventories).
- `world:fill_blocks(from, to, state)` sets a box of blocks. One call
  changes at most 32768 (the same as `/fill`), so spread a big fill over
  several ticks in a task.

A block state the server doesn't have (a typo in the block, a property or a
value) is an error. **Blocks in chunks that aren't loaded can't be read or
changed**: those calls answer `nil` or `false`, and nothing here ever loads a
chunk except `world:load_chunk`.

### Data on a block

`block:data()` is a table kept for that position, saved with its chunk: the
same table every time, shared by every script. Change it in place.

```lua
local data = block:data()
data.uses = (data.uses or 0) + 1
```

It's saved when the server autosaves, when its chunk unloads and when the
server stops. It holds what every saved table does (see
[saving data](./scripting.md#saving-data)): strings, numbers, booleans, tables of
them, and the API's own values (`Vec3`s, `Location`s, `Item` tables, and
`Entity` handles of any kind (a `Player`, a `Mob`), `Centity` and `World` handles), which come back as the same types.

## Events

A world hears the block events in it, before `nf.on` does, with the same
event: `world:on("block_break", ...)` and `world:on("block_place", ...)`, and
`world:on("entity_spawn", ...)` for what comes into it (cancellable).
`event.block` is a `Block`, and `event.state` is its block state when it
happened (the block being broken, or the one being placed). A break's
`event.drops` (a list of `Item` tables) and `event.experience` are writable;
a place says what it was placed `against`.

```lua
nf.worlds.default():on("block_break", function(event)
  if event.state == "minecraft:diamond_ore" then
    event:cancel()
  end
end)
```

## Rays

`world:raycast(from, direction, max_distance)` answers the first thing a ray
hits, or `nil`: a block (by its collision shape), an entity (players
included) or a centity's hitbox (tested with the same shapes clicks are). The
`RaycastHit` says where (`position`, `normal`, `distance`) and what (`block`,
`entity`, or `centity` and `node`). `{ blocks = false }`,
`{ entities = false }` or `{ centities = false }` leave one out,
`{ ignore = { player } }` goes through some entities, and `{ fluids = true }`
stops at water and lava. An entity's `target_block()`, `target_entity()` and
`target_centity()` cast one from its eyes.

## Particles

`world:spawn_particle(particle, position, options?)` spawns a particle the
server has. Which options it takes depends on the particle:

| Particle kind                        | Options                       |
| ------------------------------------ | ----------------------------- |
| most (`minecraft:flame`)             | none beyond the common ones   |
| dust (`minecraft:dust`)              | `color` (`"#rrggbb"`), `size` |
| dust that changes colour             | `color`, `to_color`, `size`   |
| coloured (`minecraft:entity_effect`) | `color`                       |
| block (`minecraft:block`)            | `block_state` (required)      |
| item (`minecraft:item`)              | `item` (required)             |

Every particle takes `count`, `spread` (a `Vec3`), `speed`, `viewers` (a list
of players; everyone near enough by default) and `force` (seen from up to 128
blocks, past the client's particle setting). An option the particle doesn't
take is an error, so a typo is caught. `player:spawn_particle(...)` is the
same for one player alone.

For anything with a shape or a timeline (a ring, a burst that fades, a halo
that follows someone), design a [particle effect](./particles.md) in the
editor and play it with `nf.particles.play`.

## Sounds

`world:play_sound(sound, position, options?)` plays a sound for everyone near
enough; `player:play_sound(sound, options?)` for one player, following them;
`player:stop_sound(sound?)` stops one sound or all of them. Options are
`volume`, `pitch` (0.5 to 2) and `category` (which volume slider it follows).

A sound is a Minecraft sound (`"minecraft:ui.button.click"`) or one of the
project's own: a `.ogg` file under `resource_packs/<pack>/sounds/` is the sound
`"<pack>:<path>"` (see [resource packs](./resource-packs.md)). An unknown sound
is an error.
