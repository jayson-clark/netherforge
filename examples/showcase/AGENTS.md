# NetherForge Showcase

A NetherForge project: Minecraft server content (centities, Lua modules,
menus, dialogs, resource packs) as plain files, run by the NetherForge
plugin on a Paper server. `netherforge.json` names the target Minecraft version.

## Before you change anything

Read `.netherforge/docs/README.md`. The NetherForge editor writes it when it opens
the project, and `npx @netherforge/cli setup` writes it without the editor,
with the file format, the Lua API and the guides for the exact version in use.
Trust it over anything you remember or find online. If `.netherforge/` is
missing, run `npx @netherforge/cli setup` in the project's folder.

## Rules

- Read the format page for a kind of file (`.netherforge/docs/format/<kind>.md`)
  before changing one. Every JSON file's `$schema` points at its JSON Schema.
- Use only the Lua API declared in `.netherforge/luals/nf.lua`. Scripts run in a
  sandbox without `io`, `os`, `package`, `load` or `debug`.
- Lua never goes inside JSON: scripts are `.lua` files next to the JSON that
  names them.
- Ids are folder names: lowercase letters, digits and `_`.
- Never edit `.netherforge/`: it's generated and not committed.

## Check your work

- `node .netherforge/bin/netherforge.mjs check` validates the whole project as the editor does and
  prints `file:line: severity: message (at $.json.path)`. Finish with no errors.
- `node .netherforge/bin/netherforge.mjs format` rewrites project JSON in canonical form, so diffs
  stay small.
- `node .netherforge/bin/netherforge.mjs test` runs the project's script tests (`*_test.lua`, written
  with `nf.test`: see `.netherforge/docs/guide/testing.md`) on a fake server and
  prints each test's verdict and the line it failed at. Add one for logic you
  write, and finish with every test passing.
- `node .netherforge/bin/netherforge.mjs preview terrain/<id>.json --seed 42 --out map.png` draws a
  terrain as the editor's preview does (`--slice x --at 0` for the ground through a line),
  so you can look at terrain you changed. See `.netherforge/docs/guide/world-generation.md`.
- With the NetherForge editor open, its `netherforge` MCP server (`.mcp.json`) has tools for the
  rest: `get_problems` after editing, `reload` and then `get_console` to see a change run on the
  dev server, and `lookup_game_data` instead of guessing Minecraft ids. `.mcp.json` holds this
  computer's token for it: never commit it or copy the token elsewhere.
- You can't see the game. Say what to look at in game after a reload when a change is visual.
