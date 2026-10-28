# What is NetherForge?

NetherForge is a desktop editor, built like a game engine editor, for making
content that runs on a Minecraft server, plus the Paper plugin that runs it.

You work in a **project**: a folder on your machine that holds everything as
plain files.

| In a project          | What it is                                                                                                           |
| --------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Centities**         | Composed, scripted entities: a tree of block, item and text displays that move, animate, collide and can be clicked. |
| **Modules**           | Server-wide Lua: listen for events, add commands, run schedules, keep saved data, talk to web services.              |
| **Menus and dialogs** | Chest windows in front of players (shops, pickers), and screens of text, questions and buttons.                      |
| **Items and blocks**  | Items and blocks of your own, with recipes, loot tables, buffs, advancements and mining rules.                       |
| **Effects**           | Particle effects, cutscenes that move the camera, and screens and videos drawn on maps.                              |
| **Worlds**            | Terrain, biomes, skies and dimension types for generated worlds; saved maps and structures to place.                 |
| **Resource packs**    | Your own pictures: menu backgrounds, glyphs for text, item and block looks, tooltip frames, sounds.                  |
| **Tests**             | `*_test.lua` files that run your scripts on a real server, players included.                                         |

A project can use others as [packages](packages.md), and a workspace keeps
several together, such as the servers of a [network](network.md) and the
packages they share.

The editor opens a project, shows it (a 3D viewport for centities, a Lua
editor with completions, a problems list), and runs a **local Paper server**
with the NetherForge plugin. When you save, the plugin reloads what changed.
There's no simulator: the real server is the preview.

## How the pieces fit

```
your project (a folder, in git)
   │
   ├── NetherForge editor ──── starts ───▶ local Paper server + NetherForge plugin
   │        │                                     ▲
   │        └──────────── hot reload on save ─────┘
   │
   └── your production server: the same plugin, pointed at a checkout of the project
```

- The **editor** writes the files, validates them, and drives the dev server.
- The **plugin** reads the project folder directly. There's no build step and
  no export: deploying is pointing a server at the folder.
- **Git** is how you version, share and review a project. NetherForge has no
  accounts and no cloud.

## Where to go next

1. [Install the editor](install.md).
2. [Create a project and join its dev server](first-project.md).
3. [Make your first centity](first-centity.md) and [give it a script](first-script.md).

After that, the guides cover each part in depth, the [file format](../format/index.md)
is the full specification of what a project contains, and the
[Lua API reference](../reference/index.md) lists everything scripts can call.
