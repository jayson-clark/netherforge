# Using coding agents

A coding agent (Claude Code, Codex, Cursor, Aider, or any other that edits
files) can work on a NetherForge project directly. The project is files, the
agent edits files, and the NetherForge editor shows its changes as they land.
While the editor is open, its [MCP server](#the-editor-s-mcp-server) also lets
the agent reload its changes onto the dev server and read what happened.

## What the agent gets

Every time it opens a project, the NetherForge editor writes what an agent needs
into the project's `.netherforge/` folder. It's for the editor's own version,
so it always describes the API and format that will run the project:

| Path                               | What it is                                                                                                                                |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `.netherforge/docs/README.md`      | An index of everything below. The agent's starting point.                                                                                 |
| `.netherforge/docs/format/`        | The [file format](../format/project.md), one page per kind of file.                                                                       |
| `.netherforge/docs/reference/`     | The [Lua API reference](../reference/index.md).                                                                                           |
| `.netherforge/docs/guide/`         | These guides.                                                                                                                             |
| `.netherforge/luals/nf.lua`        | The whole Lua API as typed [LuaLS stubs](external-editors.md#lua-luals-stubs) in one file.                                                |
| `.netherforge/schema/`             | The [JSON Schema](external-editors.md#json-schemas) of each kind of file.                                                                 |
| `.netherforge/bin/netherforge.mjs` | The [`netherforge` command](#check-its-work): validates, formats, tests and previews the project. Node 22 or newer; tests also need Java. |

`.netherforge/` isn't committed, so on a fresh clone it's empty until the
project has been opened in the editor once, or until `npx @netherforge/cli setup`
has been run in its folder (see [Editing outside the editor](external-editors.md#setting-up-without-the-editor)).

## Point the agent at it

Agents look for instructions in the project root. New projects come with
three files:

- **`AGENTS.md`**, which Codex, Cursor, Aider and most other agents read. It
  tells the agent to read `.netherforge/docs/README.md` first, gives it the
  rules every project follows (Lua never inside JSON, ids are folder names,
  only the API in `nf.lua`, never edit `.netherforge/`), and tells it to run
  `netherforge check` before it finishes.
- **`CLAUDE.md`**, one line, `@AGENTS.md`, so Claude Code reads the same file.
- **`.mcp.json`**, which connects Claude Code to the editor's
  [MCP server](#the-editor-s-mcp-server), with this computer's token. The
  project's `.gitignore` leaves it out.

For a project made before these existed, or cloned onto another computer,
open **Settings → Agents** and choose **Add**. A file that's already there is
left alone; an existing `.mcp.json` gets the `netherforge` server added to it.

`AGENTS.md` and `CLAUDE.md` are your files: commit them, and add what's
specific to your project, such as naming conventions, which modules own what,
or what not to touch. The editor never rewrites them. In `.mcp.json` it only
keeps the `netherforge` server's port and token current. Everything that
changes between NetherForge versions is in `.netherforge/docs/`, which the
editor does rewrite.

## Check its work

An agent can't see the game, so it needs another way to know its change is
right. The editor writes a `netherforge` command into the project for that, and
`AGENTS.md` tells the agent to use it:

```sh
node .netherforge/bin/netherforge.mjs check
```

```
centities/crate/centity.json: error: Minecraft 26.3 has no block "minecraft:oak_plank" (at $.nodes.box.display.block)
1 error, 0 warnings.
```

`check` validates the whole project exactly as the editor's **Problems**
panel does: one `file:line:column: severity: message (at $.json.path)` line
per problem, and exit code 1 if there are errors. Block, item and entity ids
are checked against the game data the dev server exported, which the editor
caches once you've started the dev server for that Minecraft version. If
there's none yet, `check` says so and checks everything else. `--json` prints
the problems as JSON, and `--game-data <file>` uses another export.

```sh
node .netherforge/bin/netherforge.mjs format
```

`format` rewrites every project JSON file in its canonical form, the way the
editor saves it, so an agent's edits don't come with formatting noise in the
diff. `format --check` changes nothing and fails if a file isn't canonical.

```sh
node .netherforge/bin/netherforge.mjs test
```

`test` runs the project's script tests (`*_test.lua`, see
[Testing your scripts](testing.md)) on a fake server and prints each test with
the line it failed at, so an agent can check logic it can't see running. It
needs Java and the test runner jar the editor carries.

```sh
node .netherforge/bin/netherforge.mjs preview terrain/hills.json --seed 42 --out map.png
```

`preview` draws a [terrain](world-generation.md#looking-without-the-editor)
as the editor's preview does, from the same code: a PNG of the map from above,
or with `--slice x --at 0` of the ground through a line. An agent can look at the
picture (and at the colour legend it prints) to check terrain, biomes and
decorations without the editor, and a terrain with problems prints them as
`check` does.

```sh
node .netherforge/bin/netherforge.mjs preview builds/castle --view iso --out castle.png
node .netherforge/bin/netherforge.mjs import ~/schematics/castle.litematic --as castle
```

`preview` draws a structure or a [build](../format/worlds.md#builds) too: from
above, isometric (`--view iso`), or a slice through one layer (`--slice y --at
12`), with a line per kind of block it shows, so an agent can check a build's
shape. `import` brings a WorldEdit `.schem` or Litematica `.litematic`
schematic into the project, as `structures/<id>.nbt` when it fits in one and
as a build when it doesn't, and names the blocks the game data doesn't know.

```sh
node .netherforge/bin/netherforge.mjs render centities/wisp/centity.json --out wisp.png
node .netherforge/bin/netherforge.mjs gallery --out build/gallery
```

`render` draws an item or a block as its inventory icon, or a centity in its
base pose from the front, its right and above, as the editor draws them: the
project's resource pack looks, and the game's own from the Minecraft client
the editor imported. `gallery` draws every item, block and centity into a
folder with an `index.html` of them all. An agent can look at the pictures to
check art it changed.

`lock` writes `netherforge.lock` as the project's
[dependencies](../format/packages.md) resolve now (the editor does it too,
whenever they change), and `build` writes the bundle a production server runs
(see [Deploying](deploying.md#projects-with-dependencies)).

The same command works in CI, from npm: `npx @netherforge/cli@1.0.0 check`,
with the version that matches your NetherForge.

Then close the loop yourself:

1. **Keep the NetherForge editor open.** Changes appear in it as they're
   written, and the Problems panel shows the same problems `check` does.
2. **Reload and look.** `/nf reload` (or `/nf reload <path>`) pushes the
   agent's changes onto the dev server. A failed reload says why, with file
   and line, and script errors show in the Console.
3. **Review the diff.** Files are canonical and Lua lives in `.lua` files, so
   `git diff` of an agent's work reads like a code review. Have agents work on
   a branch and merge what you'd accept from a person.

## The editor's MCP server

While it's open, the editor serves tools to coding agents over
[MCP](https://modelcontextprotocol.io), at `http://127.0.0.1:47615/mcp`. They
answer the questions files can't, from the same place the editor's own panels
do:

| Tool               | What it does                                                                                                                                                                                                                                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `get_status`       | The open project, whether the dev server is running, players, live centities, problem counts, and files with unsaved edits.                                                                                                                                                                                      |
| `get_problems`     | Every problem, as the Problems panel shows them: the editor's validation (re-read from disk) and what the dev server reported.                                                                                                                                                                                   |
| `reload`           | Hot reloads the given paths, or the whole project, onto the dev server, with each resource's result and any errors it logged.                                                                                                                                                                                    |
| `get_console`      | The dev server's console, including script errors with file, line and traceback. Takes a cursor, so the agent reads only what's new.                                                                                                                                                                             |
| `get_profile`      | Where the dev server's ticks go, as the Profiler panel shows it: every function and scope scripts ran by total, mean or worst time, the tick by step, and what each step is made of (`partsMeanMs`: each service's share and its own parts, like `world/centities/sync/poses`). Can reset, to measure one thing. |
| `run_command`      | Runs a server command, such as `nf list` or `time set day`, and returns what it printed.                                                                                                                                                                                                                         |
| `spawn_centity`    | Spawns a centity next to a player.                                                                                                                                                                                                                                                                               |
| `list_instances`   | Every live centity instance and where it is.                                                                                                                                                                                                                                                                     |
| `lookup_game_data` | Searches the target version's real ids in any of its registries (blocks with their states, items, biomes, loot tables…) or their tags.                                                                                                                                                                           |
| `start_server`     | Starts the dev server and waits for the plugin. It never accepts the Minecraft EULA: you do that once in the editor.                                                                                                                                                                                             |
| `stop_server`      | Stops the dev server.                                                                                                                                                                                                                                                                                            |
| `open_in_editor`   | Opens a file in the editor for you, at a line or a JSON path, to show you what it changed.                                                                                                                                                                                                                       |
| `bot_join`         | Joins a [bot](#bots), a fake player, to the dev server, and returns what it was sent while joining.                                                                                                                                                                                                              |
| `bot_act`          | Has a bot do one thing a player does, and returns what followed: its new events and any script errors.                                                                                                                                                                                                           |
| `bot_state`        | What a bot's screen shows: the open menu, a dialog, the sidebar, boss bars, titles, its inventory, the entities it can see, and what it's looking at (a block, an entity, or a centity's node).                                                                                                                  |
| `bot_events`       | What a bot was sent, in order: chat, titles, menus and dialogs opening, sounds, teleports, its death. Takes a cursor.                                                                                                                                                                                            |
| `bot_leave`        | Disconnects a bot.                                                                                                                                                                                                                                                                                               |

So an agent's loop becomes: edit files, `get_problems`, `reload`,
`get_console`, try it with a bot, and fix what it finds, without you relaying
error messages.

### Bots

Most of what a project does happens to players: a join message, a command, a
click on a centity, a menu, a dialog, a sidebar. An agent can try those
without you joining: `bot_join` puts a bot on the dev server, a fake player
that the server, NetherForge and your scripts treat as a real one. It logs in
the way a game does (so it gets the resource pack and fires `player_join`),
and `bot_act` has it do what a person would, by sending what their game would
send:

```json
{ "name": "Tester", "action": { "type": "command", "line": "/shop" } }
{ "name": "Tester", "action": { "type": "click_slot", "slot": 13 } }
{ "name": "Tester", "action": { "type": "interact", "centity": "<instance uuid>", "node": "lid" } }
{ "name": "Tester", "action": { "type": "dialog_button", "button": "Done", "inputs": { "nickname": "Robo" } } }
{ "name": "Tester", "action": { "type": "walk_to", "x": 12.5, "z": -3.5 } }
```

Each answer includes what the bot was sent afterwards (chat, a menu opening,
a dialog) and any script errors the server logged, and `bot_state` shows its
screen: the open menu's items, the dialog's inputs and buttons, the sidebar,
boss bars, titles, and the entities it can see (one hidden from it with
`hide_from` isn't there). A bot obeys the same rules as a player: it must be
within reach to click, it can't click what it can't see, a whitelist keeps it
out, and mobs can hurt it (`difficulty peaceful` helps). An agent's bots exist
only on the dev server and leave when it stops. (Scripts have bots of their
own, [`nf.bots`](../reference/nf.bots.md), and an agent's bot is a `Bot` to
them too.)

**Connecting.** Every request needs the editor's token, a random one made
when you first ran NetherForge, sent as `Authorization: Bearer <token>`.
Claude Code reads the project's `.mcp.json`, which has the URL and the token,
and asks you once whether to use the server. Without one, **Settings →
Agents** adds it, or shows the `claude mcp add … --header "Authorization:
Bearer <token>"` command to run. Other agents take the same URL and header as
an HTTP (Streamable HTTP) MCP server in their own settings. The editor must be
open for the tools to work; with no project open, they say so.

**Settings → Agents** shows whether the server is listening and its token, and
turns it off or moves it to another port. Opening a project brings its
`.mcp.json` up to date with this editor's port and token.

**Security.** The server listens on 127.0.0.1 only and answers only requests
with its token, so another program or another user on your computer can't
call it without reading your files. It also refuses requests from web pages
(any non-local `Host` or `Origin`, and anything that isn't JSON), so a site
you visit can't reach it. Because `.mcp.json` holds the token, a project's
`.gitignore` leaves it out: don't commit or share it. Agents ask before using
a tool unless you've allowed it, and `run_command` runs commands as the server
console, so allow it with care.

## Agents without a project

To ask a chat assistant about NetherForge, or to point an agent at the docs
without a project open, use the docs site's
[`llms.txt`](https://llmstxt.org): `/llms.txt` lists every page with a
one-line summary, `/llms-full.txt` is every page in one file, and each page's
Markdown is at its own URL with `.md` on the end. These describe the latest
release, so for a project, prefer what's in `.netherforge/docs/`.

## Good tasks for an agent

- Writing and refactoring Lua: modules, commands, centity behaviour.
- Bulk edits across files: rename a block in every centity, add lore to every
  shop item, retime an animation.
- Generating content from a description: "a menu with nine warp buttons,
  each slot teleporting the player when clicked".
- Explaining an unfamiliar project, since everything it needs is in the
  folder.

Things that need eyes, such as positioning nodes so a model looks right or
lining up a skin's artwork, are faster in the editor's viewport.
