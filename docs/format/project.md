# Project format

A NetherForge project is a folder. Everything in it is plain files you can read,
diff, merge and review in git. The editor writes them and the server reads
them straight from the folder, and you (or a coding agent) can edit them by
hand.

This page covers the project as a whole. The editor also writes a JSON Schema
for every kind of file into the project's `.netherforge/schema/` folder
(`centity.schema.json`, `item.schema.json`, …), and every JSON file's
`$schema` points at its own, so editors like VS Code complete and check them.

## Layout

```
my-project/
  netherforge.json               the project manifest; its presence makes the folder a project
  netherforge.lock               what its dependencies resolved to (packages.md); commit it
  centities/
    <id>/
      centity.json             a composed, scripted entity
      **/*.lua                 its script, and the files beside it that the script requires
  menus/
    <id>/
      menu.json                a window: a shop, a class picker, a chest with rules
      **/*.lua                 its script, and the files beside it that the script requires
  dialogs/
    <id>/
      dialog.json              a screen of text, questions and buttons
      **/*.lua                 its script, and the files beside it that the script requires
  particles/
    <id>/
      effect.json              a timeline of particle spawns, played by scripts
  cutscenes/
    <id>.json                  a camera path played for one player: keyed position and rotation, cues
  items/
    <id>/
      item.json                a project item: an item kind of the project's own
      **/*.lua                 its script, and the files beside it that the script requires
  blocks/
    <id>/
      block.json               a block of the project's own: its look, hardness, drops and sounds
      **/*.lua                 its script, and the files beside it that the script requires
  recipes/
    <id>.json                  a recipe the server learns: one file each
  loot/
    <id>.json                  a loot table: what a roll gives, rolled by scripts
  game_blocks/
    <id>.json                  mining rules and drops for some of the game's own blocks
  buffs/
    <id>.json                  a buff or debuff scripts put on players and centities for a while
  advancements/
    <id>.json                  an advancement: a quest, in trees the game shows; learnt at start
  resource_packs/
    <id>/
      pack.json                named pictures: skins, glyphs, item models, tooltips, equipment
      textures/**/*.png
      sounds/**/*.ogg          each one a sound event, <id>/<path>
  modules/
    <id>/
      init.lua                 runs when the server loads the module (optional)
      **/*.lua                 everything else, reached with require()
  migrations/
    <NNN>_<name>.sql           one step of the package's own database (nf.db()), applied in order
  wasm/
    <id>/
      module.wasm              a WebAssembly module scripts run (nf.wasm): a chess engine, a solver
      **/*                     the data it reads, never writes, from its root
  biomes/
    <id>.json                  a biome: climate, colours, sounds, mobs and the features it's decorated with; learnt at start
  skies/
    <id>.json                  a sky: its colours, fog and water, for biomes and players
  dimension_types/
    <id>.json                  a dimension type: a world's build limits, light, sky and rules; learnt at start
  terrain/
    <id>.json                  a terrain: the land, layers, caves, ores and biomes of a world the project makes
  datapacks/
    <id>/                      a Minecraft datapack's worldgen, passed to the server as it is; learnt at start
  maps/
    <id>/                      a map: a Minecraft world folder, copied to make live worlds
      level.dat
  structures/
    <id>.nbt                   a saved region of blocks, placed by scripts
    <id>.json                  optional, beside its .nbt: where the world generates it by itself
  fonts/
    default.json               the default font's advances, written by the editor; commit it
  .netherforge/                  what the editor generates (schemas, agent docs); never commit it
  .gitignore
```

Every kind's folder holds only its resources, and the same rules apply to
all of them:

| What                                                                                      | Problem                                        |
| ----------------------------------------------------------------------------------------- | ---------------------------------------------- |
| A resource folder without its main file (`centities/tower/` with no `centity.json`)       | `project.missing-file` (warning; it's ignored) |
| A file directly in a folder kind's folder (`centities/notes.txt`)                         | `project.stray-file` (warning; it's ignored)   |
| Anything in a single-file kind's folder that isn't `<id>.<ext>` (`recipes/old/ruby.json`) | `project.stray-file` (warning; it's ignored)   |
| A folder or file name that isn't an [id](#ids)                                            | `project.id` (error; it's ignored)             |
| A hidden file or folder (`.DS_Store`, `.gitkeep`) directly in a kind's folder             | nothing: it's nobody's                         |

A module's folder needs no particular file: any file makes one. A module
without an `init.lua` is a library, which only runs when required: it's
`module.no-init` (a warning) only while no `.lua` file in the project requires
it by a literal name (`require("util")`, `require "util.strings"`) and the
project doesn't export it. A name built at run time isn't seen.

### Group folders

A large project can sort a kind's resources into **group folders**: a folder
whose name is an [id](#ids) in parentheses, between the kind's folder and the
resource's, as deep as you like.

```
items/
  (weapons)/
    (wands)/
      ruby_wand/
        item.json
        script.lua
    sword/
      item.json
  ruby/
    item.json
recipes/
  (smelting)/
    ruby.json
modules/
  (economy)/
    shop/
      init.lua
```

Groups only sort. A resource's id is still its own folder's (or file's) name,
whichever group it's in: the item above is `ruby_wand`, the recipe `ruby` and
the module `shop` (`require("shop")`). Nothing names a group, so moving a
resource into one, out of one or between them changes no reference, script or
saved data. The parentheses are what tell a group from a resource from the
path alone, so a resource's own subfolders (`centities/tower/lib/`) are never
mistaken for groups.

Every kind can be grouped but resource packs: a pack's id is part of every
name in it (`ui/coin`) and of where its files go in the built pack, so packs
stay in `resource_packs/` itself.

| What                                                                                             | Problem                                                |
| ------------------------------------------------------------------------------------------------ | ------------------------------------------------------ |
| A group folder whose name isn't an id in parentheses (`items/(Weapons)/`)                        | `project.group` (error; everything in it is ignored)   |
| A resource pack in a group folder (`resource_packs/(art)/ui/`)                                   | `project.group` (error; everything in it is ignored)   |
| Two resources of a kind with the same id in different groups (`items/ruby`, `items/(gems)/ruby`) | `project.duplicate-id` (error; only the first is read) |

A group with nothing in it yet is kept by a hidden file (the editor writes
`.gitkeep`), so the folder is there and git keeps it; format ignores it, as it
does every hidden file.

An id is the kind's whole, groups or not: of two resources with one id, the one
whose group folders sort first is read (one in no group before any), and the
other is reported and ignored.

See [centities](centity.md), [menus](menu.md),
[dialogs](dialog.md), [particle effects](particle-effect.md), [cutscenes](cutscene.md), [terrains](terrain.md), [biomes](biome.md), [dimension types](dimension-type.md),
[items](item.md), [recipes](recipe.md), [loot tables](loot.md), [blocks](block.md), [game blocks](game-block.md), [advancements](advancement.md),
[resource packs](resource-pack.md), [migrations](migrations.md) and [worlds and structures](worlds.md).

## Ids

A resource's **id is its folder name** (or its file's, for a kind of single
files), whichever [group folders](#group-folders) it's in. It isn't repeated
inside the file.

Ids are lowercase letters, digits and `_`, start with a letter or digit, and
are at most 64 characters: `tower`, `shop_tier_2`. They're used as folder
names (which must not collide on case-insensitive filesystems), as Lua
strings (`nf.centities.spawn("tower")`, `require("combat")`) and as Minecraft
resource names (`shop:ruby`), and that rule satisfies all three.

Files and scripts name each other's resources by id, in the project's own
[namespace](#netherforge-json) or another package's: see
[references](references.md).

## `netherforge.json`

```json
{
  "$schema": ".netherforge/schema/netherforge.schema.json",
  "formatVersion": 1,
  "name": "My server",
  "namespace": "my_server",
  "version": "1.0.0",
  "minecraft": "26.3",
  "managedWorlds": ["lobby"],
  "worlds": {
    "lobby": {
      "spawnLimits": { "monster": 0 },
      "spawnIntervals": { "animal": 400 },
      "gameRules": { "keep_inventory": true }
    },
    "realm": { "terrain": "ruby_hills", "seed": 42 }
  },
  "requires": {
    "moderation": true,
    "db": true,
    "http": ["discord.com"],
    "plugins": ["vault"]
  },
  "allow": {
    "permissions": ["shop"]
  },
  "dependencies": {
    "acme_economy": { "path": "../economy" }
  },
  "exports": {
    "modules": ["api"]
  },
  "settings": {
    "max_players": {
      "type": "integer",
      "description": "How many players a round takes.",
      "default": 8
    }
  }
}
```

| Key             | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `formatVersion` | The version of this format the project is written in: `1`. NetherForge reads only this version. See [Format version](#format-version).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `name`          | Shown in the editor.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `namespace`     | What everything the project registers or saves on a server is named under: a stack of its item `ruby` carries `my_server:ruby`, its recipe `ruby_sword` is the server's `my_server:ruby_sword`, its resource pack assets are `my_server:<pack>/<key>`, and scripts' attribute modifiers and goals are `my_server:<name>`. References that name no namespace are in this one ([references](references.md)). Lowercase letters, digits and `_`, starting with a letter or digit, at most 64 characters (`project.namespace`), and none of `minecraft`, `realms`, `brigadier`, `c`, `paper`, `bukkit`, `spigot`, `netherforge` or `nf` (`project.namespace-reserved`). Changing it later leaves what a server already saved under the old one. |
| `version`       | The project's own version, in [Semantic Versioning](https://semver.org): `1.0.0`, `0.3.1-beta.2` (`project.version`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `minecraft`     | The Minecraft version the project targets: the last 1.21.x release (1.21.11) or any 26.x (older is `project.minecraft-old`). The editor runs this version's server with the plugin built for it; validation uses this version's game data, and something the project uses that arrived in a newer version is `project.feature`.                                                                                                                                                                                                                                                                                                                                                                                                             |
| `managedWorlds` | Optional. Worlds already on the server that the project's scripts may unload and delete (`world:unload`), by name. Worlds its scripts create are the project's anyway; the server's main world never is. A name is letters, digits, `_`, `-` and `.` (not starting with `-` or `.`), at most 64 characters (`project.world-name`). Kept in name order.                                                                                                                                                                                                                                                                                                                                                                                      |
| `worlds`        | Optional. Settings for worlds on the server, by name: their mob spawning, game rules, and the terrain and dimension of worlds the project makes. See [`worlds`](#worlds).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `requires`      | Optional. What the project's scripts need beyond what every project may do: its declared capabilities. See [Requirements](#requirements).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `allow`         | Optional. `permissions`: the permission nodes the package's scripts may grant or deny players with `player:set_permission`, each allowing itself and every node under it (`"shop"` allows `shop` and `shop.vip`). A node is lowercase letters, digits, `_` and `-`, in parts separated by dots, at most 255 characters (`project.permission-node`). Kept in order. Held to the package whose code makes the call, as `requires` is: a library's script needs the node in the library's own list.                                                                                                                                                                                                                                            |
| `dependencies`  | Optional. The [packages](packages.md) the project uses, by namespace, each with the `path` of its folder relative to this one.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `exports`       | Optional. What projects that depend on this one may use of it, by kind folder (`modules`, `items`, `resource_packs`, …); everything else is its own. See [packages](packages.md#what-a-package-exports).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `settings`      | Optional. What whoever runs the project may change without touching its scripts, by name, each with its `type`, `description` and `default`; scripts read them with `nf.config`. See [settings](settings.md).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `generate`      | Optional. Commands that write the project's generated files (`"node tools/items.mjs"`), run in order from the project's folder by `netherforge generate`, which stops at the first that fails and then formats the project. Never run by the server. See [generators](../guide/external-editors.md#generators).                                                                                                                                                                                                                                                                                                                                                                                                                             |

### `worlds`

```json partial
"worlds": {
  "lobby": {
    "spawnLimits": { "monster": 0 },
    "spawnIntervals": { "animal": 400 },
    "gameRules": { "keep_inventory": true }
  },
  "realm": { "terrain": "ruby_hills", "seed": 42 }
}
```

Each world is named as in `managedWorlds` (`project.world-name`), but needn't
be one the project manages. Everything in it is optional:

| Key              | Meaning                                                                                                                                                                                                                                                                                              |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `spawnLimits`    | The most mobs of each spawn category there may be around each player (as `world:set_spawn_limit`).                                                                                                                                                                                                   |
| `spawnIntervals` | The ticks between the game's spawn attempts for each category (as `world:set_spawn_interval`).                                                                                                                                                                                                       |
| `gameRules`      | Game rules by the game's id (`"keep_inventory": true`, `"minecraft:random_tick_speed": 0`), each `true`, `false` or a whole number as the rule takes (`project.world-rule`). A rule this Minecraft version doesn't have (names change between versions) is skipped, with a line in the server's log. |
| `terrain`        | A [terrain](terrain.md) of the project to generate the world with (`reference.terrain` when there's none by that name).                                                                                                                                                                              |
| `seed`           | The world's seed, with `terrain`.                                                                                                                                                                                                                                                                    |
| `dimensionType`  | A [dimension type](dimension-type.md) of the project for the world (`reference.dimension-type` when there's none by that name).                                                                                                                                                                      |

Spawn categories are `monster`, `animal`, `water_animal`, `water_ambient`,
`water_underground_creature`, `ambient` and `axolotl` (any other is an
error), each a whole number of 0 or more (`project.world-spawn`; `0` stops
that category spawning naturally). A category left out keeps the server's own
setting (`bukkit.yml`). These are applied when the project loads and reloads,
and whenever such a world is loaded or created later, so a script's
`world:set_spawn_limit` lasts until the next reload.

A world with a `terrain` or a `dimensionType` that the server doesn't have yet
is created with them when the project loads (loaded, if it was saved before),
and is the project's from then on, like one a script made. A world with both
has its terrain checked against the dimension's height limits
(`project.world-height`).

**The main world** (the server's own) is different. It takes its `terrain`
only as the server starts, and only when `bukkit.yml` asks NetherForge for it
(the editor's dev server does), with the server's own seed: naming another
terrain needs a restart (`runtime.restart`), and a `seed` for it is ignored
(`runtime.terrain`). See [the main world](terrain.md#the-main-world). Its
`dimensionType` replaces the game's overworld type from the next start (see
[the main world's dimension](dimension-type.md#the-main-world)); a world that
already exists keeps the dimension it was made with (`runtime.dimension-type`).

Worlds are kept in name order and categories in the order above; a world that
says nothing is dropped.

### Format version

This page describes format 1. NetherForge refuses a project in any other
version (`project.format-version`), with a message naming its version: the
editor won't open it and the plugin won't run it. A project made by a newer
NetherForge says to update NetherForge. Only `formatVersion` is read from a
manifest in another format, so one missing keys this format requires still
says which format it is.

### Requirements

A package (every project is one) declares what its scripts need that a
package must ask for, so whoever runs a server sees in one place what the
projects on it may do, and a package can't use what it didn't ask for:

| Key          | Lets the package's scripts                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `moderation` | `true`: ban and unban players, change the whitelist, the message of the day and the most players allowed (`player:ban`, `nf.server.set_motd`, …).                                                                                                                                                                                                                                                                                                                                                 |
| `web`        | `true`: answer web requests to the server under the package's own path, `/<namespace>/` ([`nf.web`](../reference/nf.web.md)), once the server owner has turned web routes on; until then it's a warning (`runtime.web-disabled`).                                                                                                                                                                                                                                                                 |
| `bots`       | `true`: join fake players to the server and drive them ([`nf.bots`](../reference/nf.bots.md)), once the server owner allows bots; until then it's a warning (`runtime.bots-disabled`). The editor's dev server always does.                                                                                                                                                                                                                                                                       |
| `db`         | `true`: keep a database of the package's own.                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `wasm`       | `true`: run the package's [WebAssembly modules](wasm.md) ([`nf.wasm`](../reference/nf.wasm.md)), off the main thread within the server owner's limits (see [WebAssembly](../guide/server-config.md#webassembly)).                                                                                                                                                                                                                                                                                 |
| `network`    | `true`: talk to the other servers of the network the server is part of: messages, requests, shared maps, who's online where, sending players ([`nf.network`](../reference/nf.network.md), see [Server networks](../guide/network.md)). On a server in no network it's a warning (`runtime.network-disabled`); the editor's dev server is a network of its own.                                                                                                                                    |
| `http`       | Make HTTP requests to these hosts: a host name in lowercase (`discord.com`), or `*.` and a name with a dot in it for any host under it (`*.example.com` is `api.example.com`, not `example.com`) (`project.requires-host`). Any port. Kept in order. `nf.http.request` checks each request's host, and each redirect's, against the calling package's list; the server owner decides whether private addresses are reachable at all (see [Web requests](../guide/server-config.md#web-requests)). |
| `plugins`    | Use these other plugins on the server, by name in lowercase (`vault`): letters, digits, `_` and `-`, at most 64 characters (`project.requires-plugin`). Kept in order.                                                                                                                                                                                                                                                                                                                            |

Each is held to the package whose code makes the call, not to the project
that depends on it: a package's script that calls `player:ban` needs
`moderation` in that package's own `netherforge.json`, and without it the
call is an error naming the package and what to declare. In the
[API reference](../reference/index.md), a function that needs one says so.

**Plugins.** `plugins` is how a package uses [Vault](../reference/nf.economy.md)
(`"vault"`: `nf.economy`) and [PlaceholderAPI](../reference/nf.placeholders.md)
(`"placeholderapi"`: `nf.placeholders`); there is no general way to call
another plugin. A declared plugin that isn't enabled on the server is a
problem on the package's `netherforge.json` (`runtime.plugin-missing`), and
calling a function that needs it is an error. A plugin that starts after NetherForge
clears the problem as it does. Other plugins can only be used through those functions.

The editor shows the requirements of the whole tree (the project and every
package it depends on, theirs included) in the project's settings, and the
server logs them when it loads the project and lists them with
`/nf requires`. In the API's terms a requirement is written `moderation`,
`db`, `web`, `bots`, `wasm`, `network`, `http:<host>` or `plugin:<name>`.

## `fonts/default.json`

The default font's glyph advances (how far each character moves the cursor)
for the target Minecraft version, so the server can measure text, for example
to lay out a menu or to place a skin behind a centred title. Only the game
client knows them, so the editor reads them from your imported Minecraft
client and writes this file whenever it's missing or doesn't match what it
would write: you never edit it, but you do commit it, because the server reads
it from the project like everything else. It isn't in `.netherforge/`, which is
neither committed nor read by the server.

```json
{
  "$schema": "../.netherforge/schema/default_font.schema.json",
  "minecraft": "26.3",
  "advances": { "32": 4, "33": 2, "65": 6 }
}
```

| Key         | Meaning                                                                                                                          |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `minecraft` | The version whose client the advances were read from. When it isn't the project's `minecraft`, the file is stale (`font.stale`). |
| `advances`  | Pixels per character, the gap after it included, by Unicode code point.                                                          |

Characters the font draws only from Unicode fallback fonts aren't in it, and
text that uses them can't be measured. Without the file, nothing that measures
text on the server can work; the editor warns when it can't write it (no client
import for the target version).

## `client/vanilla.json`

What only the client knows and the built resource pack needs. The game's own
blockstate files of the blocks the project's [blocks](block.md#carriers) are
held in, where only the client knows how the game draws their states:
tripwire, which draws a model per shape of string. The built resource pack
replaces the carrier's file, so it needs these to keep drawing string as the
game does. The names of the game's HUD sprites, which a resource pack's
[`hud.hide`](resource-pack.md#hiding-the-games-elements) replaces with empty
ones and [`hud.replace`](resource-pack.md#redrawing-the-games-sprites)
redraws. And the pixel sizes of the game's textures a resource pack's
[`textures`](resource-pack.md#redrawing-the-games-textures) redraws: every
container screen's, and every other one the project's packs name, with the
names they name that the game doesn't have. And which of the models a resource pack's
[`models`](resource-pack.md#redrawing-the-games-models) redraws the game has. Like `fonts/default.json`, the editor writes it from your
imported Minecraft client whenever a tripwire block, `hud.hide`, `hud.replace`,
`textures` or `models` needs it and it's missing or out of date: you never edit it, but
you commit it.

```json
{
  "$schema": "../.netherforge/schema/vanilla.schema.json",
  "minecraft": "26.3",
  "blockstates": {
    "minecraft:tripwire": {
      "variants": {
        "attached=false,east=false,north=false,south=false,west=false": {
          "model": "minecraft:block/tripwire_ns"
        }
      }
    }
  },
  "sprites": ["hud/air", "hud/armor_full", "hud/food_full", "hud/heart/full"],
  "textures": { "block/stone": [16, 16], "gui/container/inventory": [256, 256] },
  "unknownTextures": ["block/stoen"],
  "models": ["block/red_mushroom"],
  "unknownModels": ["block/red_mushrom"]
}
```

| Key               | Meaning                                                                                                                                                                                                                                                  |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `minecraft`       | The version whose client it was read from. When it isn't the project's `minecraft`, the file is stale (`block.vanilla-looks`).                                                                                                                           |
| `blockstates`     | Each block's blockstate file as the client has it, by block id.                                                                                                                                                                                          |
| `sprites`         | The HUD's GUI sprites (`textures/gui/sprites/hud/`), by name. An import from before the editor listed them has none: import again.                                                                                                                       |
| `textures`        | The container screens' textures (`textures/gui/container/`) and the other textures the project's packs redraw, by name, each with its `[width, height]` in pixels. An import from before the editor listed them all has only the screens': import again. |
| `unknownTextures` | The names the project's packs redraw that the game has no texture by. A name in neither list hasn't been looked up yet (`resource_pack.vanilla-missing`).                                                                                                |
| `models`          | The game's models the project's packs redraw (`models/block/red_mushroom.json` is `block/red_mushroom`), by name.                                                                                                                                        |
| `unknownModels`   | The names the project's packs redraw that the game has no model by. A name in neither list hasn't been looked up yet (`resource_pack.vanilla-missing`).                                                                                                  |

Without it, tripwire blocks look like string (`block.vanilla-looks`, at each tripwire block), `hud.hide` hides nothing (`resource_pack.hud-hide`), and `hud.replace`, `textures` and `models` replace nothing (`resource_pack.vanilla-missing`).

## How files are written

Every JSON file has one canonical form, and the editor always writes it:

- two-space indentation, a trailing newline, keys in a fixed order
- objects keyed by name (nodes, animations, tracks) sorted by key
- keyframes sorted by time
- keys that aren't set are left out (an explicit value is kept, even if it matches the default)
- `$schema` first, pointing into `.netherforge/schema/`

So saving a file you didn't change never produces a diff, and two people who
each add a different node to the same centity merge cleanly. Files you write
by hand don't have to be canonical; the editor rewrites them in canonical form
the next time it saves them.

Reading is strict. An unknown key is an error that names the key, the path to
it and its line, rather than something silently dropped on the next save.

## Scripts are files

Lua never appears inside JSON. A centity, a menu or a dialog has at most one
script: a `.lua` file next to its JSON, named by the file's top-level
`script`. So diffs are readable and any editor can open it. Behaviour several
resources share goes in a [module](../guide/modules.md).

## What's never in a project

- **Minecraft's own data**: textures, models, block lists. The editor reads
  those from your Minecraft install, and the plugin asks the server. They're
  cached per user, never per project. (The one fact the server needs from the
  client, the default font's advances, is resolved into `fonts/default.json`.)
- **Anything in `.netherforge/`**: the dev server, its world, caches. The
  project's `.gitignore` excludes it.
