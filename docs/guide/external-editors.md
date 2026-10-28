# Editing outside the editor

Everything in a project is a plain file, so you can edit it with anything:
VS Code, Neovim, a script, a coding agent. NetherForge gives other editors the
same two things it uses itself: **JSON Schemas** for the project files and
**Lua type stubs** for the API.

You can keep the NetherForge editor open while you do this. It watches the
folder and picks up outside changes; see
[Hot reload](dev-loop.md#edits-from-outside-the-editor) for getting them onto
the dev server.

## Setting up without the editor

Both live in the project's `.netherforge/` folder, which the NetherForge
editor writes when it opens the project. It isn't committed, so a fresh clone
doesn't have it. To set it up without the editor, run this in the project's
folder (it needs [Node](https://nodejs.org) 22 or newer):

```sh
npx @netherforge/cli setup
```

It writes exactly what the editor would, from the same code: the JSON Schemas,
lua-language-server's stubs marked for your project and with its own names,
the [docs for coding agents](agents.md), and the `netherforge` command itself
(`.netherforge/bin/netherforge.mjs`). A project without a `.luarc.json` gets
one. Run it again after changing `netherforge.json`, its dependencies or the
names scripts use (a new centity, node or dialog button); the editor does
that by itself while it's open. In a workspace's folder it sets up every
project in the workspace.

Use the version that matches your editor and the plugin your server runs,
`npx @netherforge/cli@1.0.0 setup`, or install it once with
`npm install --global @netherforge/cli` and run `netherforge setup`.

## JSON: schemas

Every project JSON file starts with a `$schema` that points into the project's
`.netherforge/schema/` folder:

```json
{
  "$schema": "../../.netherforge/schema/centity.schema.json",
  "nodes": { "root": {} }
}
```

VS Code (and any editor with a JSON language server) follows that path and
gives you validation, completion and hover docs for every key, with no
configuration.

The NetherForge editor writes those schemas every time it opens the project,
and `netherforge setup` writes them without it (see
[above](#setting-up-without-the-editor)).

The schemas check the shape of a file. Some rules need the whole project or
the game's data (does this block state exist, does this script file exist,
is this hitbox fit stale): the NetherForge editor's Problems panel and the
server's reload report those.

## Lua: LuaLS stubs

[lua-language-server](https://luals.github.io/) (the "Lua" extension by
sumneko in VS Code, `lua_ls` in Neovim) gives you completion, signatures, hover
docs and type checking for the whole NetherForge API from one stub file,
`nf.lua`, generated from the same spec as the [API reference](../reference/index.md).

1. A project the editor creates comes with this `.luarc.json` in its root
   (commit it); for an older project, add it yourself:

   ```json
   {
     "runtime.version": "Lua 5.4",
     "runtime.path": ["modules/?.lua", "modules/?/init.lua"],
     "runtime.builtin": { "io": "disable", "os": "disable", "debug": "disable" },
     "workspace.library": [".netherforge/luals"],
     "diagnostics.disable": ["lowercase-global"],
     "diagnostics.neededFileStatus": { "not-yieldable": "Any" },
     "hint.awaitPropagate": true
   }
   ```

   `workspace.library` points LuaLS at `.netherforge/luals/`, which the
   NetherForge editor keeps up to date while the project is open: the copy of
   `nf.lua` for your NetherForge version, with every function the project
   can't use marked deprecated (one newer than the Minecraft it targets, or
   one that needs something `netherforge.json` doesn't allow, like
   `player:ban` without `"requires": { "moderation": true }`), so a call to one
   is flagged; `surfaces/centity.lua`, `menu.lua` and `dialog.lua`, which say
   exactly what `this` is in each kind of script; and your project's own names
   (its centities, their nodes and animations, its dialogs' buttons, menus,
   items, glyphs...), so `this:node("` completes them.
   The functions that wait (`nf.wait`, `dialog:ask`, an asynchronous one like
   `nf.worlds.copy` without its callback) are `---@async` there, and
   `nf.task`'s callback is the one place that may call them:
   `hint.awaitPropagate` and the `not-yieldable` diagnostic flag an event
   handler, a timer or a command handler that waits.
   `runtime.path` makes
   `require("greeter.messages")` resolve to `modules/greeter/messages.lua`, as
   it does on the server. `runtime.builtin` flags the libraries the server's
   sandbox removes. The disabled diagnostic quiets LuaLS about globals a
   script defines on purpose, like a module's shared functions.

2. Open the project in the NetherForge editor once, or run
   `npx @netherforge/cli setup` in its folder, so `.netherforge/luals/` exists
   (see [Setting up without the editor](#setting-up-without-the-editor)). Both
   write the same stubs, marked for your project and with its names.

`examples/showcase` has exactly this `.luarc.json`.

Now `nf.`, `this:`, `player:` and every event payload complete, and reading
`event.playr` in a click handler is flagged before you ever run it.

### Which `this`

`this` means a different class in each kind of script: the
[`Centity`](../reference/centity.md) instance in a centity's script, the
[`Menu`](../reference/menu.md) window in a menu's, the
[`Dialog`](../reference/dialog.md) in a dialog's. LuaLS can't tell which
from the folder (it ignores a `.luarc.json` in a subfolder), so `nf.lua`
declares `this` as `Centity|Menu|Dialog`.

Every script the editor creates starts with one line that says which:

```lua
local this = this --[[@as Centity]]
```

(or `Menu`, or `Dialog`). That makes LuaLS type `this` exactly for the whole
file, closures included, with no warning. Start a script you write by hand with
the same line.

Why that form: a `---@type Centity` above the line warns "Cannot assign
`Centity|Dialog|Menu` to `Centity`", and a bare `---@cast this Centity` is
meant for locals, and warns about an unknown cast variable when nothing after
it reads `this` directly.

`:on` and `:once` are typed per class and event, so a handler's `event` gets
the right payload, and the event name completes from what that class has.
`nf.wait_for(handle, event)` returns that same payload.

### What LuaLS can't check

The stubs make a correct script check clean, and catch most mistakes. A few
things are beyond what LuaLS can express:

- **Custom event names.** A custom event is any name with a `:`, so on `nf`
  and on a centity any string is accepted as an event name, and the handler's
  `event` takes any field (its payload is whatever `emit` raised it with).
  A misspelt built-in event there (`this:on("clik", ...)`) isn't flagged; the
  server rejects it when the script loads. On a node, a window, a slot, a
  dialog or a button, a wrong event name is flagged.
- **Unknown keys in option tables.** `{ sped = 2 }` passed as animation
  options isn't flagged (a missing required key is).
- **Which names exist.** Node, button, animation, menu and dialog names
  complete from the project's own, but any string is accepted (and LuaLS
  can't tell one centity's nodes from another's); the server's reload
  report catches a name that doesn't exist.
- **Things that may be `nil`.** A handle's methods return `nil` once its
  thing is gone (`this:node("box")` is `Node?`), so LuaLS asks you to check.
  Where you know it's there, `assert(this:node("box"))` says so.
- **Files beside a resource's script.** On the server, `require("helpers")`
  in `centities/tower/script.lua` loads `centities/tower/helpers.lua` before
  any module. lua-language-server can't follow that kind of `require` in
  other editors, so there it's untyped. The NetherForge editor resolves them
  as the server does.

### Checking from the command line

`lua-language-server --check` runs the same checks over a whole project
without an editor:

```sh
lua-language-server --check=. --checklevel=Information
```

It reads the project's `.luarc.json`, and prints each problem with its file
and line.

`.netherforge/luals/surfaces/` has one stub per kind of script
(`centity.lua`, `menu.lua`, `dialog.lua`...), describing exactly what that kind
of script sees. LuaLS merges them with `nf.lua`'s union rather than replacing
it, so they don't replace the first line. Modules have no `this` at all.

## Formatting

The editor rewrites JSON files in their canonical form when it saves them, so
how you format JSON by hand doesn't matter: the next save from the editor
normalises it. If you never open a file in the editor, it stays as you wrote
it, which is valid as long as it parses. To normalise every file at once
without the editor, run `node .netherforge/bin/netherforge.mjs format` from the
project root, and `check` instead of `format` to validate the whole project;
see [Check its work](agents.md#check-its-work).

For Lua, use whatever formatter you like. [StyLua](https://github.com/JohnnyMorganz/StyLua)
with two-space indentation matches the examples.

## Generators

A project may write some of its files with code of its own (items from a
table, art from shapes, recipes from a spreadsheet). List the commands in
`netherforge.json`'s `generate`, and `netherforge generate` runs them in order,
then formats the project:

```json
"generate": ["node tools/items.mjs", "node tools/recipes.mjs"]
```

A Node generator writes the project with the SDK the command line exports,
so it never spells out where a kind's files go or formats afterwards:

```js
import { project } from '../.netherforge/bin/netherforge.mjs'

const p = project()
for (const gem of ['ruby', 'sapphire']) {
  // In items/(gems)/<id>/item.json, unless it's already somewhere else.
  p.writeResource('items', gem, { kind: 'minecraft:paper', name: gem }, { group: 'gems' })
}
p.resources('items') // every item, by id: { kind, id, group, path }
```

`place(kind, id, group)` says where a resource's file is or would go,
`write(file, text)` and `writeJson(file, value)` write any project file (JSON
of the project in canonical form), and `read`/`readJson` read one. An
unchanged file isn't touched, so a generator that runs again changes nothing.
