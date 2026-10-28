---
name: run-netherforge
description: Launching the real editor (pnpm dev), opening examples/showcase, starting the dev server, joining it from Minecraft, where every log lives, and how to check a change end to end rather than only in tests. Read when you need to see a change working for real.
---

# Running NetherForge for real

Tests prove the parts; this proves they connect. Do it before calling a
cross-cutting change (bridge, hot reload, server setup) done.

## Before the first run

1. `pnpm install` at the repo root.
2. **Build the plugin jar** for the target version; the editor installs it
   into every dev server, and refuses to start without one:
   `node tools/gradle.mjs :plugin:paper-26.3:assemble` → `apps/plugin/paper-26.3/build/libs/NetherForge-<v>-paper-26.3.jar`
   (each supported version has its own `paper-<mc>`).
   Debug builds of the editor find it there directly; rebuild it after a
   plugin change and restart the dev server. A dev network (the Network panel)
   also needs the proxy plugin: `node tools/gradle.mjs :proxy:velocity:assemble`.
3. **Java 25+** is found automatically (JAVA_HOME, PATH, the usual install
   folders). If there isn't one, the first start downloads Temurin 25
   (~200 MB) into the app data folder.

## Launch and open a project

```sh
pnpm dev          # repo root: tauri dev = Vite on :1420 + the Rust app, hot reloads the UI
```

Open folder → `examples/showcase`. Its `netherforge.json` targets `26.3`.
The dev server and its world go in the data dir (table below), in
`servers/<hash>/`: the first 16 hex digits of the SHA-256 of the project's
canonical path, with `netherforge-project.txt` naming the project. Never in
the project, so nothing a project ships can run on Start. Delete that folder
for a fresh world; `grep -l showcase <data>/servers/*/netherforge-project.txt`
finds it.

To keep a test run away from your real settings and caches:
`NETHERFORGE_DATA_DIR=/tmp/nf pnpm dev` (one folder for config and data).

## Start the server and join

1. Server panel → accept the Minecraft EULA once (remembered per user;
   nothing writes `eula=true` without it).
2. Start. Phases: preparing (Java, Paper download, folder setup) →
   starting → running when Paper prints `Done (`. The bridge indicator turns
   on when the plugin connects. The first start for a Minecraft version also
   exports the server's game data into the cache (console line
   "Cached game data for Minecraft 26.3").
3. In Minecraft (the same version, `26.3`), Multiplayer → Direct connect →
   `localhost:25565` (or the port from Settings). `online-mode=true`, so use a
   real, logged-in account. Op yourself from the editor console: `op <name>`.

### "You are not whitelisted on this server"

Minecraft 26.x writes `white-list=true` into `server.properties` by default.
The editor turns it off at every start while `whitelist.json` lists nobody
(`server/setup.rs`, `properties`), so this only happens on a server started
before that fix, or one whose whitelist someone filled in. Run `whitelist off`
(or `whitelist add <name>`) in the editor's console: it applies immediately.

## Where the logs are

| What                                      | Where                                                                                                                |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Server console (live)                     | the editor's console panel (`server://output`)                                                                       |
| Server log files                          | `<data dir>/servers/<hash>/logs/latest.log`                                                                          |
| Script files, plugin state                | `<data dir>/servers/<hash>/plugins/NetherForge/` (`data/` is `nf.files`)                                             |
| Plugin/script errors                      | console panel + problems (bridge `log`, `script_error`, `problems`)                                                  |
| Rust backend                              | the terminal running `pnpm dev` (`[netherforge] ...` lines)                                                          |
| UI                                        | webview devtools (right-click → Inspect in a debug build)                                                            |
| Settings, recent, EULA                    | config dir: `~/Library/Application Support/NetherForge/`, `%APPDATA%\NetherForge\`, `~/.config/NetherForge/`         |
| JDKs, Paper jars, game cache, dev servers | data dir: `~/Library/Application Support/NetherForge/`, `%LOCALAPPDATA%\NetherForge\`, `~/.local/share/NetherForge/` |

## Checking a change end to end

- **Hot reload**: with the server running and you in game, edit
  `examples/showcase/centities/*/root.lua` in the editor (or any editor; the
  watcher sees external saves too), save, and watch the console for the
  reload result; `/nf spawn <centity>` to see it live.
- **Server setup / lifecycle**: stop, change the port in Settings, start,
  check `server.properties` and that you can join on the new port. Kill the
  Java process from outside: the state must become `crashed` with a message.
- **Client assets**: Settings → Minecraft → import `26.3` from a detected
  launcher; textures in previews should load from `nfasset://`. If the
  target isn't installed, "Open launcher" starts the player's launcher. The
  import also writes `glyph-advances.json`: a fixed text display's "Fit to
  display" then measures with the real font.
- **Resources**: open `menus/shop`, `dialogs/welcome`, `resource_packs/ui`.
  Resource pack textures load from `nfproject://`; import a PNG and check it lands in
  `resource_packs/ui/textures/`. With the server running, saving hot-reloads them; a
  script shows them (`nf.menus.shared("shop")`, `nf.dialogs.get("welcome"):open_for(player)`).
- **Updates**: a quiet check runs at start; Settings → Updates checks on
  demand. Until a signed release with `latest.json` exists (and the pubkey
  is set, see the release skill) it reports an error there and nothing at
  start. The e2e build (`pnpm exec vite build --mode e2e && pnpm exec vite
preview` in `apps/editor/`) opened with `?update=9.9.9` shows the update
  prompt on the memory backend. There is no other browser mode: `pnpm dev`
  is the editor.
- **Without the UI**: `cargo test --manifest-path apps/editor/src-tauri/Cargo.toml -- --ignored`
  boots real Paper through the backend's own start/stop path (network + Java 25).
  `pnpm test:integration` drives the plugin over the bridge headlessly.
