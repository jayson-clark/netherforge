# File format

A NetherForge project is a folder of plain files: JSON for what things are,
Lua for what they do, and pictures and models for how they look. The editor
writes all of them for you, so you rarely need these pages. They're here for
when you want to edit a file by hand, review a change in git, or know exactly
what a key does.

Every JSON file starts with a `$schema` line, so editors like VS Code complete
and check it as you type, and the editor and the [`netherforge check`](../guide/agents.md#check-its-work)
command report any mistake as a [problem](problems.md) with a stable code.

## The project

| Page                         | What it covers                                                                           |
| ---------------------------- | ---------------------------------------------------------------------------------------- |
| [Project format](project.md) | The folder layout, `netherforge.json`, ids and group folders, and how files are written. |
| [References](references.md)  | How one file names another: ids, namespaces, and the game's own things.                  |
| [Packages](packages.md)      | Using another project as a library, the lock file, bundles and templates.                |
| [Settings](settings.md)      | Settings a server owner can change without touching scripts.                             |
| [Migrations](migrations.md)  | A package's own SQLite database, set up by numbered SQL files.                           |
| [WebAssembly](wasm.md)       | Modules scripts hand heavy computation to, and the data they read.                       |
| [Problems](problems.md)      | Every problem the editor and the server can report, by code.                             |

## Centities and effects

| Page                                   | File                          | What it is                                               |
| -------------------------------------- | ----------------------------- | -------------------------------------------------------- |
| [Centities](centity.md)                | `centities/<id>/centity.json` | A composed, scripted entity: nodes, animations, physics. |
| [Particle effects](particle-effect.md) | `particles/<id>/effect.json`  | A timeline of particles, played by scripts.              |
| [Cutscenes](cutscene.md)               | `cutscenes/<id>.json`         | A camera path played for one player.                     |

## Menus, dialogs and looks

| Page                               | File                       | What it is                                                     |
| ---------------------------------- | -------------------------- | -------------------------------------------------------------- |
| [Menus](menu.md)                   | `menus/<id>/menu.json`     | A chest window: a shop, a picker. Also how an item is written. |
| [Dialogs](dialog.md)               | `dialogs/<id>/dialog.json` | A screen of text, questions and buttons.                       |
| [Resource packs](resource-pack.md) | `resource_packs/<id>/`     | Pictures, glyphs, item models, block looks, tooltips, sounds.  |

## Items and gameplay

| Page                               | File                     | What it is                                         |
| ---------------------------------- | ------------------------ | -------------------------------------------------- |
| [Items](item.md)                   | `items/<id>/item.json`   | An item kind of the project's own.                 |
| [Blocks](block.md)                 | `blocks/<id>/block.json` | A block of the project's own.                      |
| [The game's blocks](game-block.md) | `game_blocks/<id>.json`  | Mining rules and drops for Minecraft's own blocks. |
| [Recipes](recipe.md)               | `recipes/<id>.json`      | Something new to craft, cook, smith or cut.        |
| [Loot tables](loot.md)             | `loot/<id>.json`         | What a roll gives: chests, drops, rewards.         |
| [Buffs](buff.md)                   | `buffs/<id>.json`        | A timed effect that adds to stats.                 |
| [Advancements](advancement.md)     | `advancements/<id>.json` | A quest on the advancements screen.                |

## Worlds

| Page                                 | File                                | What it is                                                 |
| ------------------------------------ | ----------------------------------- | ---------------------------------------------------------- |
| [Worlds and structures](worlds.md)   | `maps/<id>/`, `structures/<id>.nbt` | Saved worlds to copy, and structures to place or generate. |
| [Terrain](terrain.md)                | `terrain/<id>.json`                 | How a world's ground is shaped.                            |
| [Biomes](biome.md)                   | `biomes/<id>.json`                  | A biome of the project's own.                              |
| [Skies](sky.md)                      | `skies/<id>.json`                   | Sky, fog and water colours.                                |
| [Dimension types](dimension-type.md) | `dimension_types/<id>.json`         | A world's height, light and rules.                         |
| [Datapacks](datapack.md)             | `datapacks/<id>/`                   | Anything else Minecraft's datapacks can say about a world. |
