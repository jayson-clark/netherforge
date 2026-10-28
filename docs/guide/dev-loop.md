# Hot reload and the dev loop

The loop NetherForge is built around: edit, save, look. With the dev server
running and you in game, a save shows up in the world in about a second, with
no restart and nothing to rebuild.

## Trusting a project

A project you open from a folder you haven't trusted before opens in
**restricted mode**, as in VS Code: you can read and edit everything, but the
dev server, Lua language features, fetching its git packages and coding
agents' tools stay off, because each of them runs or reaches out on the
project's behalf. The banner under the toolbar says so; **Trust project…**
(or starting the server) asks you once, and the editor remembers the folder.
A project you create in the editor is trusted already. Only trust a project
whose authors you trust. To take it back, **Run › Untrust Project…** (or the
Server page of the settings) asks first, then stops the dev server and puts the
project back in restricted mode.

## What happens when you save

When you save, the editor tells the dev server which file changed. The server
reloads just the resource that file belongs to, and the Console says what it
did ("Reloaded centity:tower (2 live)"). Then the whole project is checked
again, and anything wrong shows in the Problems panel.

## What a reload does

| You saved                                                             | What happens                                                                                                                                                                                                           |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| anything in `centities/<id>/`                                         | That centity. Spawned instances stay where they are and take the new definition (added nodes appear, removed ones go), and the script starts again on each: its `unload` handlers run, then its body, without `spawn`. |
| anything in `menus/<id>/`                                             | That menu. Open windows stay open when their size didn't change (their contents too, except slots whose item you changed in the file), and the script restarts on each window.                                         |
| anything in `dialogs/<id>/`                                           | That dialog's script restarts; the next time it's shown, it's drawn from the new file.                                                                                                                                 |
| anything in `items/<id>/` or `blocks/<id>/`                           | That item or block: its script restarts, and existing stacks of an item take its new look.                                                                                                                             |
| anything in `modules/<id>/`                                           | That module: its `unload` handlers run, its handlers, timers and commands go, and `init.lua` runs again. Every script that required it restarts too.                                                                   |
| anything in `resource_packs/`                                         | The resource pack is rebuilt and sent to everyone online.                                                                                                                                                              |
| a recipe, loot table, particle effect or cutscene                     | Used from the next time it's needed. Recipes are sent to everyone online; a playing particle effect takes the change at once.                                                                                          |
| advancements, biomes, dimensions, datapacks, structures that generate | The game only reads these as it starts, so the editor **restarts the dev server** for you.                                                                                                                             |
| a terrain                                                             | Chunks generated from then on use it; chunks already made stay as they are.                                                                                                                                            |
| `netherforge.json`                                                    | Everything restarts. Spawned centities stay.                                                                                                                                                                           |

Each centity, menu, dialog, item and block has one script, so saving anything
in its folder (its JSON, its script, or a `.lua` file beside the script)
restarts that one script.

**A broken save doesn't break the world.** If a centity file has errors, the
last good definition keeps running and the reload reports why it didn't take.
If a script fails while loading (a syntax error, an error in its body), that
script is switched off until you save again, and the error is in the Console
with a link to its line. A handler that errors later is reported the same way,
and the script keeps running.

Live state in Lua (top-level locals, a module's tables) starts fresh on each
reload. Keep what must survive in a [saved table](scripting.md#saving-data) (`player:data()`, `nf.data(name)`), which stays as it is across reloads.

## Edits from outside the editor

The editor watches the project folder, so a change from git, VS Code or a
coding agent shows up in its open tabs straight away: silently if you had no
unsaved changes, or as a banner offering **keep mine**, **take theirs** or a
diff if you did.

Those outside edits aren't pushed to the server by themselves. Reload them
from the game or the Console:

```
/nf reload                          everything
/nf reload centities/tower/         one centity
/nf reload modules/greeter/init.lua the module owning that file
```

## Problems and errors

- **Problems** (the Problems panel) are what's wrong with the files: an
  unknown key, a missing script file, a block state that doesn't exist, a
  stale hitbox fit. Each names the file and the place in it; clicking one opens
  it there.
- **Script errors** (the Console) are what went wrong while running: the
  message, the handler, the file and line, and a traceback. They're in the
  Problems panel too, until the script reloads.

A script that's slowing the server down shows up here too: a warning naming
the file and line of its slowest handler (see
[Performance](performance.md)), and `/nf scripts` lists what every
script costs.

The **Profiler** panel (beside the Console) shows where the dev server's
ticks go: a timeline of the last six seconds, each tick a bar split into what
it was spent on (hover a tick for the details and the scripts that cost the
most), and a table of every function your scripts ran (each handler, timer,
task, command and script body) with its total time, calls, average and worst
call, or, under **Scopes**, every script by what it cost. Click a function to
open it at that line. **Reset** starts the totals again. See
[Limits and performance](performance.md).

To see what a script is doing line by line, set a breakpoint and step through
it: see [Debugging scripts](debugging.md).

The editor checks files as you type, and the server checks them again on every
reload against the real game, so a block or item name that doesn't exist in
your Minecraft version is caught too.

## Spawning and inspecting

- **Spawn at me** spawns the open centity in front of you.
- The **Instances** panel lists every spawned centity on the dev server.
- `/nf list`, `/nf tp <instance>` and `/nf kill <instance|centity|all>` do the
  same in game.

## Restarting

Some things only take effect on a fresh start: a different plugin version,
server settings, JVM options. **Stop server** and **Start server** again.
Spawned centities, saved tables and `nf.files.get` files survive; they and the
world stay in the dev server's folder, in the editor's data folder, until you
delete it (see [where the editor keeps things](install.md#where-the-editor-keeps-things)).
