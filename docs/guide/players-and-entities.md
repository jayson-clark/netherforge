# Players, entities and inventories

Everything in a world that isn't a block is an `Entity`: a minecart, an arrow,
a dropped item, a mob, a player. A handle is the class the entity is, each
with the methods of the classes above it:

| Class         | Is a     | What it adds                                                   |
| ------------- | -------- | -------------------------------------------------------------- |
| `Entity`      |          | where it is, how it moves, names, tags, riding, its data       |
| `Living`      | `Entity` | health, attributes, status effects, equipment; `death`, `heal` |
| `Mob`         | `Living` | a target, AI, walking (`move_to`), goals; `path_end`, `target` |
| `Player`      | `Living` | everything about a player                                      |
| `DroppedItem` | `Entity` | the stack it is, its pickup delay                              |

So a zombie's handle is a `Mob`, an armour stand's a `Living`, an arrow's a
plain `Entity`, and each goes wherever a class above it is taken. Their
inventories are `Inventory` handles, as are container blocks'. Boss bars and
sidebars put things on a player's screen. Positions are world space, in
blocks.

```lua
local world = nf.worlds.default()
local guard = world:spawn_entity("minecraft:iron_golem", vec3(0, 64, 0), {
  custom_name = "<aqua>Guard",
  tags = { "guard" },
}) --[[@as Mob?]]
if guard then
  guard:on("damage", function(event)
    event:cancel()
  end)
  guard:set_target(nil)
end
```

`spawn_entity` and most events hand out an `Entity`; `is_living()`,
`is_mob()` and `is_player()` say which it is, and `--[[@as Mob]]` tells the
editor so. A handle to an entity that wasn't in the world when it was handed
out (a UUID from saved data, while its chunk is unloaded) is a plain `Entity`
until it's back, then the class it is.

## Entities

A handle is the entity's UUID (`entity:id()`), so it's fine to keep. Once the
entity has gone (killed, removed), or while its chunk is unloaded, its
methods answer `nil` or `false`, and `exists()` is `false`. The entities a centity is drawn with are never handed out as entities: a
centity is a [Centity](../reference/centity.md).

Entities come from `world:entities(filter?)` (players included, filtered by
`kind`, `tag`, `near` and `radius`, `living`), `world:spawn_entity(...)`,
`world:spawn_item(position, item)`, event payloads, `entity:passengers()`,
rays, and command arguments of type `entity` and `entities`.

| What           | Methods                                                                                                                   |
| -------------- | ------------------------------------------------------------------------------------------------------------------------- |
| what it is     | `kind()`, `exists()`, `is_living()`, `is_mob()`, `is_player()`, `name()`                                                  |
| where          | `location()`, `position()`, `world()`, `eye_position()`, `teleport(...)`                                                  |
| facing         | `yaw()`, `pitch()`, `direction()`, `set_yaw(d)`, `set_pitch(d)`, `look_at(point)`                                         |
| motion         | `velocity()`, `set_velocity(v)`, `add_velocity(v)`, `is_on_ground()`                                                      |
| names and tags | `custom_name()`, `set_custom_name(text?)`, `set_custom_name_visible(b)`, `tags()`, `add_tag(tag)`, …                      |
| looks          | `set_glowing(b)`, `set_visible(b)`, `hide_from(player)`, `show_to(player)`                                                |
| behaviour      | `set_silent(b)`, `set_gravity(b)`, `set_invulnerable(b)`                                                                  |
| riding         | `passengers()`, `add_passenger(entity)`, `remove_passenger(entity)`, `vehicle()`                                          |
| rays           | `target_block(max_distance?)`, `target_entity(max_distance?)`, `target_centity(max_distance?)`, from the eyes (20 blocks) |
| gone for good  | `remove()`: out of the world without dying (`false` for a player)                                                         |

| Class         | Methods                                                                                                                      |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `Living`      | `health()`, `set_health(n)`, `max_health()`, `damage(amount, source?)`, `heal(n)`, effects, `equipment(slot)`, …             |
| `Living`      | `attribute(id)`, `attribute_base(id)`, `set_attribute_base(id, n)`, `attribute_modifiers(id)`, `add_attribute_modifier(...)` |
| `Mob`         | `target()`, `set_target(entity?)`, `has_ai()`, `set_ai(b)`, `move_to(target, options?)`, `stop_pathing()`, `has_path()`      |
| `Mob`         | `goals()`, `remove_goal(key)`, `clear_goals(options?)`, `add_goal(id, definition)`                                           |
| `DroppedItem` | `item()`, `set_item(item)`, `pickup_delay()`, `set_pickup_delay(ticks)`                                                      |

`hide_from` is remembered across the player leaving and coming back, until
`show_to`; the server forgets it if the entity's chunk unloads.

### Attributes

Most of what a living entity's numbers are made of is an attribute: its size
(`scale`), how fast it walks (`movement_speed`), how high it jumps
(`jump_strength`), its gravity, reach, armour and the rest. `attribute(id)` is
the value now, with every modifier applied; `set_attribute_base` changes what
the modifiers start from. An attribute the server doesn't have is an error;
one this entity doesn't have (a pig's `attack_damage`) answers `nil`.

A modifier changes an attribute until it's removed, and is saved with the
entity, so give each a fixed id. Ids are in the project's namespace (`"slow_zone"` is
`"shop:slow_zone"` in a project whose namespace is `shop`), so a script can't remove the game's modifiers, an
item's or another plugin's.

```lua
local function enter_mud(entity)
  entity:add_attribute_modifier("movement_speed", "mud", -0.5, "add_multiplied_total")
end

local function leave_mud(entity)
  entity:remove_attribute_modifier("movement_speed", "mud")
end
```

### Walking mobs somewhere

`mob:move_to(target, options?)` finds a path with the game's own pathfinder
and walks the mob along it, to a position, a `Location` in its world, or after
another entity (finding a new path to it every half second). It answers whether
it found a path: a mob in the air (one just spawned, before it has landed)
finds none. The mob hears `path_end` when it stops: `event.reached` says
whether it got there or gave up. Its own AI still runs, so a zombie that sees
a player goes for them instead (the walk is then given up); `set_ai(false)`
stops a mob walking at all.

```lua
-- Walks a mob back and forth between two points, for ever.
local function patrol(mob, a, b)
  local heading = b
  mob:on("path_end", function()
    heading = heading == a and b or a
    mob:move_to(heading, { speed = 0.6 })
  end)
  mob:move_to(heading, { speed = 0.6 })
end
```

### A mob's goals

What a mob does by itself comes from its **goals**: floating in water,
strolling about, attacking, looking at players. Its AI runs the goals that
want to run, one per control at a time (`"move"`, `"look"`, `"jump"`), the most
important first: a goal with a smaller priority number takes a control from
a less important one. Picking a fight is a separate set, the **targeting
goals**, which claim `"target"`.

`mob:goals()` lists them, the game's and any added, each with its `key`, its
`priority`, the `controls` it claims and whether it's `running`. `remove_goal(key)` takes one
off, and `clear_goals({ keep = { ... } })` takes off everything else
(`"float"` is short for `"minecraft:float"`, as with every game id).

`add_goal(id, definition)` gives a mob a goal written in Lua: its `priority`,
the `controls` it claims, and callbacks the AI calls with the mob:
`should_start` (every tick or two while it isn't running), `start`, `tick`
while it runs, `should_continue`, and `stop`. A goal that claims `"target"`
joins the targeting goals and claims nothing else.

```lua
-- A guard that walks back to its post whenever it's more than 4 blocks off,
-- and otherwise does what its kind does.
local function guard(mob, post)
  mob:add_goal("guard_post", {
    priority = 1,
    controls = { "move" },
    should_start = function()
      local here = mob:position()
      return here ~= nil and here:distance(post) > 4
    end,
    should_continue = function()
      return mob:has_path()
    end,
    start = function()
      mob:move_to(post)
    end,
  })
end
```

Goal ids are in the project's namespace (`"guard_post"` is
`"shop:guard_post"`), and a goal with an id the mob has replaces it.
Goals aren't saved, the game's removals included: a mob that unloads comes
back with its kind's goals, and the goals a script added come off when it
stops (a reload), so set them up where the mob spawns, or in the script's
body. A callback runs with its script's instruction budget; one that errors
is reported with its line and its goal taken off the mob. Changing goals from
inside a goal's own callbacks happens before the mob's next tick.

### Data on an entity

`entity:data()` is the entity's own table, saved on the entity itself, so it goes where the entity goes and is gone
when it is.
It holds what every [saved table](./scripting.md#saving-data) does, and it's
written back when the server autosaves, when the entity's chunk unloads and
when the server stops. `spawn_entity`'s `data` option is what it starts
with.

### Events

An entity hears `damage` (cancellable; `event.amount` is writable) and
`interact` (a player right-clicked it; cancellable), a living one `death`
(`event.drops` and `event.experience` are writable) and a mob `path_end`, each
before the server-wide `entity_damage`, `player_interact_entity` and
`entity_death`. A class has the events of those above it too. A world hears
`entity_spawn` for what comes into it. An entity's handlers go when it dies or
is removed.

`event.drops` is a list of `Item` tables. Assign another list, or change this
one in place; each item is checked as it's assigned, so a misspelled field is
an error at that line.

```lua
nf.on("entity_death", function(event)
  if event.entity:has_tag("boss") then
    event.experience = 500
    table.insert(event.drops, { kind = "minecraft:nether_star" })
  end
end)
```

## Players

A `Player` is a `Living`, with every `Living` and `Entity` method (but
`remove`, which answers `false`: that's `kick`), and more. `nf.players.get(name_or_id)` finds
anyone who has ever joined; for someone offline, `exists()` is `false` and
what needs them in the world answers `nil` or `false`. `name()` is looked up
each time, so it's the name they have now.

| What               | Methods                                                                                                    |
| ------------------ | ---------------------------------------------------------------------------------------------------------- |
| text               | `send_message(text)`, `send_actionbar(text)`, `send_title(title, subtitle?, options?)`, `clear_title()`    |
| names              | `name()`, `display_name()`, `set_display_name(text)`                                                       |
| standing           | `has_permission(permission)`, `is_operator()`, `game_mode()`, `set_game_mode(mode)`, `kick(reason?)`       |
| hunger, experience | `food()`, `saturation()`, `level()`, `experience_progress()`, `give_experience(points)`, and their setters |
| moving             | `is_flying()`, `set_flying(b)`, `can_fly()`, `set_can_fly(b)`, `walk_speed()`, `fly_speed()`, sneaking, …  |
| their client       | `locale()`, `ping()`, `resource_pack_status()`                                                             |
| items              | `inventory()`, `ender_chest()`, `held_item()`, `off_hand_item()`, `held_slot()`, `give_item(item)`         |
| cooldowns          | `item_cooldown(key)`, `set_item_cooldown(key, ticks)`: an item kind or a cooldown group                    |
| screens            | `open_menu(...)`, `open_dialog(...)`, `open_inventory(inventory)`, `close_inventory()`                     |
| the HUD            | `sidebar()`, `bossbars()`, `set_tab_header(text)`, `set_tab_footer(text)`                                  |

A player hears the `player_*` server events about them, without the prefix,
before `nf.on` does, with the same event (so `event:stop()` keeps it from
`nf`), plus every `Living` and `Entity` event (`damage`, `heal`, `mount`, …)
but `interact` and `death`, which are these:

| Event                      | When                                                  | Cancel | Writable                             |
| -------------------------- | ----------------------------------------------------- | ------ | ------------------------------------ |
| `join`, `quit`             | they joined or left                                   |        | `message`                            |
| `chat`                     | they're saying something                              | yes    | `message`, `format`                  |
| `interact`                 | they clicked a block or the air, once per hand        | yes    |                                      |
| `use_item`                 | a right click uses the item in a hand                 | yes    |                                      |
| `interact_entity`          | they right-clicked an entity                          | yes    |                                      |
| `move`                     | they moved into another block (not every step)        | yes    |                                      |
| `teleport`                 | they're being teleported (`event.cause`)              | yes    | `to`                                 |
| `change_world`             | they arrived in another world                         |        |                                      |
| `death`                    | they died, before `nf.on("entity_death")`             |        | `message`, `keep_inventory`, `drops` |
| `respawn`                  | they're coming back                                   |        | `location`                           |
| `drop_item`, `pickup_item` | they're dropping or picking up an item                | yes    |                                      |
| `consume_item`             | they finished eating or drinking                      | yes    |                                      |
| `swap_hands`, `sneak`      | the swap-hands key; sneaking started or stopped       | swap   |                                      |
| `command`                  | they typed a command (`event.input`, without the `/`) | yes    |                                      |

`format` is MiniMessage with `<player>` and `<message>` in it, both inserted as
they are (a player can't sneak tags into chat through them). `to` and
`location` take a `Location`; `drops` a list of `Item` tables. Moves are only
watched while something listens, so a `move` handler costs nothing when there
isn't one, and a teleport is never a move.

```lua
nf.on("player_chat", function(event)
  event.format = "<gray><player> <dark_gray>»</dark_gray> <white><message>"
end)

nf.on("player_death", function(event)
  if event.player:has_permission("vip") then
    event.keep_inventory = true -- and then nothing from their inventory drops
  end
end)
```

## Inventories

`player:inventory()`, `player:ender_chest()`, `block:inventory()` (a chest, a
barrel, a hopper, a furnace) and `entity:inventory()` (a chest boat, a donkey)
are `Inventory` handles. The handle is where the inventory is, so it reads
live, and answers `nil` or `false` once that's gone. Its item methods work
like a menu's: `item(index)`, `set_item(index, item?)`, `items()`,
`add_item(item)` (what didn't fit), `clear()`.

A player's slots are numbered the hotbar 0 to 8, the rest of the main
inventory 9 to 35, armour 36 (feet), 37 (legs), 38 (chest) and 39 (head), the
offhand 40, then body armour 41 and a saddle 42.

`remove_item(match, count?)`, `count_item(match)`, `has_item(match, count?)`
and `first_slot(match)` find items by a match: an item kind, or a partial
`Item` that matches a stack when every field it gives matches. `data`
matches as a subset, so a script's own tag on an item is enough:

```lua
player:give_item({ kind = "minecraft:gold_nugget", count = 5, data = { coin = true } })
local coins = player:inventory():count_item({ data = { coin = true } })
player:inventory():remove_item({ data = { coin = true } }, 3)
```

## Boss bars

`nf.bossbars.create({ text, color, style, progress })` makes a bar shown to
no one yet. `bar:show_to(player)` shows it, and again whenever they join;
`hide_from`, `set_text`, `set_progress` (0 to 1), `set_color` and `set_style`
change it. A bar belongs to the script that made it and goes when that script
unloads, or with `bar:remove()`.

## Sidebars

`player:sidebar()` is the panel of lines at the right of their screen: up to
15 MiniMessage lines under a title, without vanilla's score numbers. Setting
its title or lines shows it; `set_visible(false)` hides it. While it shows,
the player has a scoreboard of their own that copies the server's teams once
a second, so team colours and name tags keep working; hiding it gives them
the server's scoreboard back. It's cleared when they leave.

```lua
local sidebar = player:sidebar()
sidebar:set_title("<gold><b>Arena")
sidebar:set_lines({ "Kills: " .. kills, "", "<gray>play.example.net" })
```
