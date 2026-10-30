# Changelog

All notable changes to NetherForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and NetherForge uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). One version covers
the editor, the plugin jars, the file format library and the `netherforge`
command; see the [release guide](.claude/skills/release/SKILL.md) for how a
release is cut.

## [Unreleased]

### Added

- **Image displays.** `{ "type": "image", "width": 96, "height": 54 }` on a
  centity node shows a picture a script sets at runtime
  (`node:set_image`), per centity, with no resource pack change: drawn as rows
  of the engine's pixel glyphs, so it moves and eases like any display, its
  pixels square and filling exactly its `size` in blocks. `nf.images` makes
  pictures from colours or base64 bytes, decodes image files and downloads
  them (`nf.images.download`, held to `nf.http`'s rules); an `Image` resizes,
  crops and encodes to PNG. A picture already shown isn't sent again, and a
  centity's changed pictures share about 64 KiB a tick, counted as the
  connection sends them (compressed). A picture is built into the game's text
  run by run, never parsed, so no piece of it is past the protocol's limit.
- **Rect displays and anchors.** `{ "type": "rect", "color", "size" }` is a
  rectangle exactly `size` blocks (recoloured and resized in place by a
  script), and `anchor` (`top_left` … `bottom_right`) places a rect, an image
  or a text display by that point, the engine accounting for how the game lays
  text out. `nf.text.measure` gives text's size in blocks. The characters
  `nf.text.glyph` gives measure as their glyph (`nf.text.width`,
  `nf.text.measure`), so a glyph tinted by its text's colour can be anchored
  too; before, only the `<glyph:…>` tag could, and an anchored tinted glyph
  was drawn unanchored.
- **On-demand resource packs.** `"onDemand": true` in a `pack.json` leaves the
  pack out of the one players join with; `player:push_resource_pack` and
  `pop_resource_pack` send and take it per player, `player_resource_pack_status`
  says what their game made of it, and `nf.packs.rebuild()` reads the
  packs from disk again and resends what changed.
- **Invisible items.** `invisible: true` on an item draws it as nothing, so a
  held one hides the first-person arm; with `hold`, it's a pointer whose right
  button is heard pressed (`player_use_item`) and let go
  (`player_stop_using_item`).
- **`{ instant = true }` for `set_translation`, `set_rotation` and
  `set_scale`**: the node jumps there once and still eases later moves.

- **`nf.encoding` and `nf.crypto`, for talking to web services.**
  `nf.encoding` writes and reads URL escapes, form bodies (nested tables as
  `name[key]`, lists from 0, as Stripe and similar APIs take them), base64 (both
  alphabets) and hex; `nf.crypto` hashes (SHA-256, SHA-1, SHA-512) and signs
  (HMAC), the signature `nf.web.verify_signature` checks.
- **Packages can have rules for the game's blocks.** A package's
  `game_blocks/` run beside the project's (and can be listed in `exports`),
  and a block has one set of rules across the whole server: a project's file
  naming a block a package already has rules for, or one a package's blocks
  are held in, is an error at that file, and packages that clash with each
  other or with the project's blocks are an error at the project's
  `netherforge.json` (`game_block.twice`, `game_block.carrier`).

### Security

- **Scripts can no longer stall or crash the server through the sandbox.** A
  loop that makes garbage is held to the instruction budget and time limit
  again; `table.insert`, `remove`, `sort` and `concat` can't be tricked by a
  table's `__len` or `__index` into unbounded work; a script's `__index` that
  errors while the server reads its table is the call's error instead of a
  JVM crash; and big tables handed to the API (`nf.json.encode`, …) are
  charged to the script.
- **A package can only use what it declared.** Calling a function from a
  coroutine no longer passes for the project's own code; `rawset` can't
  change the handles, events and `vec3` constants every script shares; and a
  package's database can't `ATTACH` other files.
- **`nf.server.run` needs `"requires": { "console": true }`** (breaking): it
  runs any command with the console's permissions, so it's now a requirement
  of its own, listed first. **`player:kick` needs `moderation`** (breaking),
  like `ban`.
- **`nf.files` keeps each package's files in a folder of its own**
  (`plugins/NetherForge/data/<namespace>/`), within per-package limits
  (`files:` in `config.yml`). Files kept before are moved into the project's
  folder the first time the server starts.
- **The network's hub traffic is encrypted and authenticated**, and strangers
  connecting to the hub are limited. Hub and servers must be updated together
  (network protocol 2).
- **The dev bridge proves the token both ways** instead of sending it, so a
  process that takes the editor's port gets nothing (bridge protocol 2).
- **Web and login hardening:** an OAuth login asks the browser to confirm
  which player it signs in as; `trust-proxy-headers` takes the address the
  proxy added; `nf.http` refuses the server's own addresses; dialog presses
  can't be replayed or carry answers the dialog couldn't give; a command alias
  can't remove another plugin's command; placeholders must start with the
  package's namespace; custom events say which package emitted them
  (`event.from`); oversized pictures and videos in a package are refused.

### Changed

- **The network's hub takes a server's resource packs off a player as they
  move to another server** (`clear-resource-packs` in `hub.properties`, on by
  default), so a lobby's pack (its hidden HUD) no longer follows them into a
  game that sends none.
- **3D views draw only when something changes.** A centity, particle effect,
  cutscene, structure or map that's on screen and standing still no longer
  redraws 60 times a second, so the editor uses far less CPU and GPU (and
  battery) while you work in it.

### Fixed

- **A hidden centity node shown again appears where it is**, rather than
  easing in from the centity's position; hiding one no longer shrinks it
  towards there either.

## [0.1.0-rc.1] - 2026-10-28

The first public release, as a release candidate.

- **The editor** (macOS, Windows, Linux): every kind of project file has its
  own editor, from 3D centities, menus and dialogs to terrains, screens and
  resource packs. It has Lua editing and debugging through lua-language-server,
  and Problems and Tests panels.
- **Real Minecraft is the preview.** The editor runs a Paper dev server for the
  project and reloads each resource as you save it.
- **The Paper plugin** runs a project folder on a server with no build step,
  one jar per supported Minecraft version (the last 1.21.x and every 26.x).
  Its scripts are Lua 5.4, sandboxed, with an API generated from one spec.
- **Packages and workspaces**: depend on other projects through git, and work
  on several projects and server networks side by side.
- **The `netherforge` command** on npm (`npx @netherforge/cli`): set up, check,
  format, test and preview projects from a terminal, CI or a coding agent.
- **Coding agents**: the editor's MCP server, and `AGENTS.md` with docs in
  every project.
- **The project format**: one canonical JSON form with JSON Schemas, specified
  in `docs/format/` (`formatVersion` 1).
