# Changelog

All notable changes to NetherForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and NetherForge uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). One version covers
the editor, the plugin jars, the file format library and the `netherforge`
command; see the [release guide](.claude/skills/release/SKILL.md) for how a
release is cut.

## [Unreleased]

### Added

- **One world saved and paused on its own through the control endpoint.**
  `POST /save` and `POST /resume` take a `world`: only its writes stop, other
  worlds carry on, several can be paused at once, and a world's pause and the
  whole server's stack (its writes go on once neither holds it). Its timeout
  resumes only it, unloading it ends its pause, an unknown world is `404`, and
  `/state`'s `paused_worlds` and `/events`' `writes` name the world.
- **`POST /worlds/create` on the control endpoint**, taking what
  `nf.worlds.create` takes (`name`, `terrain`, `seed`, ...): the world is made
  by the project's terrain and is the project's from then on. A terrain or
  option that isn't one is `400`; a name in use or `max-loaded` reached is
  `409`.
- **Which world each player is in, on the control endpoint.** `/state`'s
  `players` are `{ uuid, name, world }` (`player_count` and `player_names`
  beside them), each of its `worlds` is `{ name, players, empty_seconds }`,
  `player_join` and `player_quit` carry `uuid` and `world`, and
  `player_world` (`uuid`, `from`, `to`) says when a player changes worlds.
- **A request rate per private allowance.** `requests-per-minute` on an
  `http.allow-private` entry counts the package's requests to that host and
  port against a rate of their own (`0` for none), leaving its general
  `http.requests-per-minute` for everywhere else.
- **`nf.test.http` answers `nf.http.request` in script tests.** Requests are
  checked as on a server, then answered by the test's handler; one it doesn't
  answer (or with no handler) fails the test with its URL. Script tests no
  longer make real web requests.
- **`nf.test.network_publish("admin:<client>", ...)`** publishes as an admin
  API client, delivered as the hub delivers it (`event.server` is
  `admin:<client>`).

- **Credentials added and removed through the hub's admin API.**
  `PUT /v1/credentials/<id>`, `DELETE /v1/credentials/<id>` and
  `GET /v1/credentials`, under a new `credentials` scope that `*` doesn't
  give: a credential put is usable at once (servers joining, and
  `POST /v1/servers`), replacing or removing one disconnects the servers using
  it, and it's kept in the hub's store with the maps, so it survives a restart
  and reloads of `credentials.properties` (an id in both is the API's). Its
  secret is never answered, logged or sent to events.
- **A package's own database on a shared server.** `own-databases` on a
  `databases:` entry puts those packages' `nf.db()` on the MySQL or
  PostgreSQL connection, each in a schema (PostgreSQL) or database (MySQL) of
  its own, so several servers of a project share their data without the
  project changing. Their migrations run there once across every server,
  under a database lock, from `migrations/postgres/` or `migrations/mysql/`
  beside the SQLite ones (`migration.dialects` when a dialect isn't numbered
  like them, `migration.dialect` when it's missing, `migration.ahead` when a
  newer copy already migrated the database).
- **Click and hover in the admin API's messages, held to what's safe.** The
  `text` of `POST /v1/players/<player>/message` was already MiniMessage; the
  hub now refuses (400) a click action other than `run_command`,
  `suggest_command`, `copy_to_clipboard` and `open_url`, a hover other than
  `show_text`, and text over the network's value limit, and the proxy shows
  nothing else.

- **One package may reach one private address.** `http.allow-private` in
  `config.yml` lists `{ package, host, port }` allowances: that package (the
  one whose code makes the request) may connect to the addresses `host` stands
  for on that port though they aren't public, redirects, streams and
  WebSockets included, while every other package and the project's own
  scripts are still refused. The package still declares the host under
  `requires.http`; `/nf requires` and the startup log list the allowances.
- **Packages the server provides.** `packages.provided` in `config.yml` lists
  package folders the server loads in place of any copy a project or bundle
  brings. A dependency can take a `version` range (`^1.2.0`,
  `>=1.0.0 <2.0.0`; `package.version` when the package found is outside it,
  for any dependency) and be marked `"provided": true`, which `netherforge
build` leaves out of the bundle (as does `--exclude <namespace>`), listing
  it in the manifest's `provided`. A bundle that carries its own copy of a
  package the server provides is refused (`runtime.provided-package`).

- **A network-wide fallback group.** `fallback=<group>` in `hub.properties`
  is where the players of every group without its own
  `group.<name>.fallback` go when their server stops or drains, so groups made
  while the network runs (one per game) need no settings, and a network with
  no `lobby` keeps its players. Default `lobby`, as before.
- **Game data without the editor.** `netherforge game-data <version>`
  (with `--accept-eula`) downloads the version's plugin and its Paper build,
  starts Paper headless, exports the game data `check` and `test` read into
  the same per-user cache the editor uses (or `--out`), and stops: what CI
  needs to run a project's tests. A server can also write it at every start
  with `export-game-data: <file>` in `config.yml`.
- **Server credentials and roles.** A network's servers can join with
  credentials of their own (`/nfhub credentials add <id> [role]
[namespaces]`, kept in the proxy's `credentials.properties`), each with a
  role the hub enforces on every frame: `trusted` (everything, as the shared
  secret still is), `standard` (its own namespaces' channels and maps, and
  what the network shares with it) or `isolated` (only what's shared, its own
  players, sending them only back where they came from). Shares
  (`share.standard=lobby:return(request)` in `hub.properties`) grant `read`,
  `write`, `request` and `answer`. Revoking a credential drops its servers at
  once. `nf.network.server_role()` says what a server may do; what it may not
  is refused with `network.forbidden` and never reaches another server.
- **Servers added while the network runs.** A server not in `velocity.toml`
  adds itself to the proxy as it joins (`network.register.address`, and
  `server-name: auto` for `<group>-<n>`) and comes off as it shuts down, or
  after `unregister-after-seconds` gone; a credential registers only with
  `register=true`, as the `names` and `addresses` it allows. Or an
  orchestrator adds and removes servers through the hub's admin API.
- **The hub's admin API.** HTTP and JSON (`admin.port` in `hub.properties`)
  for services outside the servers, each a client with its own token and
  scopes (`/nfhub admins add`, `admin.properties`): servers (list, add,
  remove, drain), presence, sending players, chat, titles and action bars
  shown by the proxy, kicks, publishing on channels, and a Server-Sent Events
  stream of joins, moves, leaves, server changes and channel messages.
  Scripts ask a service as they ask a server
  (`channel:request({ admin = "matchmaker" }, ...)`). A service can place a
  group's players, or logins: a server, a hold with a status, or a failure,
  with the group's own balancing when it doesn't answer in time.
- **A server's life.** Every server is `starting`, `ready`, `draining` or
  `stopping` (`nf.network.state()`, `network_state`), and only a ready one
  gets new players. `nf.network.hold_ready()` keeps it starting until a
  script is set up; `/nf drain`, `set_draining` and the admin API drain it;
  `/nf shutdown [--after-empty] [--timeout <s>]` sends its players to the
  group's fallback and stops it. A send can `wait` for an instance with room
  (queued in order, its place on the player's action bar or `on_wait`), and
  the hub's new webhook hears `server_ready`, `server_idle`
  (`network.idle-after-seconds`), `server_gone` and `group_wanted`. The time
  from start to ready is logged.
- **A local control endpoint.** `control:` in `config.yml` (a Unix socket, or
  `127.0.0.1` with a token) lets the tool running a server read its state
  (`GET /state`, a stream of changes at `GET /events`) and drain it, shut it
  down, save it, and pause writes for a consistent copy until `POST /resume`
  or a timeout.
- **Worlds imported at run time.** `nf.worlds.import(folder, name)` makes a
  world from a folder a tool put under `worlds.import-root` (a player's
  island), with a project terrain for its new chunks; `worlds.max-loaded`
  caps how many are loaded at once, and the log says how long a slow load
  took. The control endpoint loads and unloads worlds too
  (`POST /worlds/load`, `/worlds/unload`).
- **The hub on its own.** `NetherForge-<version>-hub.jar` runs the network's
  hub apart from the proxy, with the same files (read again when they change)
  and a command line for credentials and admin clients; the proxy plugin
  joins it with `remote.hub` and a `proxy`-role credential, telling it who's
  where and doing what it asks. `store.url` keeps the shared maps in
  PostgreSQL. Several proxies can join one hub: each player's moves,
  messages and kicks go through the proxy they're on (`player.proxy`), a
  second login through another proxy is refused or takes over
  (`duplicate-login`), and the admin API lists proxies and drains one before
  it restarts.
- **Network-wide moderation.** The hub keeps bans (applied by the proxy
  before a player reaches any server) and mutes (every server holds back a
  muted player's chat), with the reason, who and until when:
  `nf.network.ban/unban/mute/unmute/is_banned/is_muted` (with `moderation`
  declared and a credential allowed to moderate), `/nfhub ban|mute|unban|
unmute`, and the admin API's `moderation` scope. `nf.network.report` sends
  reports to the hub (`/nfhub reports`, the webhook's `report`).
  `/nfhub disable <server|group>` takes one off the network until `enable`.
  The hub writes moderation, credential and admin changes to `audit.log`.
- **Resource packs from shared storage.** `resource-pack.storage` uploads
  packs to S3-compatible storage under their hash (once, whichever server
  builds one first) and sends players the store's URL. With
  `clear-resource-packs=false`, the hub remembers which pack each player has
  loaded, and a server whose pack they have doesn't send it again.
- **Dev networks run servers as production does.** Each server of the
  editor's dev network (and of a workspace's, in `network.options`) can have
  a group, a capacity and a role; a role other than `trusted` joins with a
  credential of its own, owning its project's namespace, so the hub holds it
  to that role on your computer too.
- **Resource packs built into bundles.** With the game's data,
  `netherforge build` makes the project's resource pack into the bundle
  (`resource-pack.zip`, its SHA-1 and pack format in the manifest's
  `resourcePack`), and a server of that pack format sends it rather than
  building its own. `netherforge publish --packs-to s3://…` uploads it where
  `resource-pack.storage` keeps packs.
- **Signed and published bundles.** `netherforge keygen`, `build --sign`
  and `sign` (a network countersigning what it checked) put Ed25519
  signatures in a bundle's manifest; a server with
  `bundles.require-signature` runs only one signed by its `trusted-keys`, and
  refuses a signature that doesn't match on any server
  (`runtime.bundle-signature`). `build --archive` makes one `.nfbundle` file,
  and `netherforge publish --to s3://…` builds, signs and uploads it with its
  listing to S3-compatible storage; `project:` takes the URL it prints
  (`…#sha256=…`), fetched once and checked. `netherforge.json` gets an
  optional `listing` (description, authors, icon, screenshots, tags,
  players), checked by `netherforge check`.
- **A policy over what projects require.** `policy.json` beside `config.yml`
  allows and denies requirement kinds and HTTP hosts whatever a project
  asks for, and lowers limits (script memory and budget, call time, network
  and HTTP rates). A project it refuses doesn't load, or with `degrade` loads
  with the refused calls failing, and `runtime.policy` says which rule. A
  network sends each role's servers a policy too (`policy.<role>` in
  `hub.properties`), and `netherforge check --policy policy.json` tells an
  author whether a project runs under one. `network.wait-for-hub-seconds` and
  `network.require-hub` keep a project from running before the network's
  policy is known.
- **Metrics.** `metrics.port` in `config.yml` serves a server's numbers for
  Prometheus: TPS and tick time, players, entities, centities and chunks,
  each package's script cost, memory and network traffic, its state and
  start-up time. The hub serves its own (`metrics.port` in
  `hub.properties`): servers and players by group, tick times, frames, map
  usage, what waits. `nf.network.servers()` and `groups()` give each
  server's `tick_milliseconds`.
- **Script tests for networks.** `nf.test.network_server` takes an instance's
  `state` and `accepting`, `nf.test.network_status` changes them,
  `nf.test.network_role` joins the test's server under a role, and
  `nf.test.network_reports` gives what scripts reported.
- **Groups of servers.** `network.group` and `network.capacity` make a server
  an instance of a group. `send_player` and the new `send_players` (a party,
  landing together) take `{ group = "arena" }`, and the hub picks the
  instance with room: `least` players, or `fill` (`group.<name>.balance` in
  `hub.properties`, or per send). `publish` and `request` take a group too.
  `nf.network.group()`, `groups()`, `players({ group = ... })` and
  `set_accepting(false)` (an instance passed over while a match runs).

- **Natural spawning in water.** `"placement": "water"` in a centity's
  `spawning` block makes it appear in water (waterlogged blocks and kelp too,
  never lava, and not under a solid block) instead of on the ground, anywhere
  from the floor to the surface; the editor's spawning section has it as
  **Appears**. Ground centities still never appear in water.
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

- **The control endpoint's `/state` lists players and worlds as objects.**
  `players` was a count and is now a list of `{ uuid, name, world }` (the count
  is `player_count`), and `worlds` was a list of names and is now a list of
  `{ name, players, empty_seconds }`. `player_names` is unchanged.
- **`*` in `admin.properties` no longer gives every scope**: it gives every
  scope but the new `credentials`, which a client has only when it's named.

- **The network's protocol is version 3**: update NetherForge on the proxy
  and on every server together. `nf.network.send_player` now gives the server
  the player is on their way to (still truthy where it gave `true`), and
  `nf.network.servers()` gives each server's group, room and whether it takes
  players.

- **The network's hub takes a server's resource packs off a player as they
  move to another server** (`clear-resource-packs` in `hub.properties`, on by
  default), so a lobby's pack (its hidden HUD) no longer follows them into a
  game that sends none.
- **3D views draw only when something changes.** A centity, particle effect,
  cutscene, structure or map that's on screen and standing still no longer
  redraws 60 times a second, so the editor uses far less CPU and GPU (and
  battery) while you work in it.

### Fixed

- **A dead admin stream no longer holds up logins.** A service placing
  players whose network vanished (a killed pod) left its stream open for about
  15 minutes, and every login offered to it waited out the placement timeout
  and got no server. A stream that doesn't answer a placement in time is now
  closed, and the placement goes to the group's next stream, or its own
  balancing.
- **The proxy counts logins it has no server for.**
  `netherforge_proxy_logins_refused_total` has the reason `no_server` for a
  login Velocity turns away because no server was chosen ("There are no
  available servers").
- A server the hub refused over TCP heard the connection close without the
  reason: the hub now sends the refusal before closing.

- **A hidden centity node shown again appears where it is**, rather than
  easing in from the centity's position; hiding one no longer shrinks it
  towards there either.
- **A package's terrain runs its script.** A generator from a package
  (`acme:hills`) looked for its script at `acme:terrain/hills.lua` among its
  package's own files, never found it, and generated only the file's own
  stages; its script's problems are now at its package path too. Its script
  names its own loot tables and blocks bare
  (`chunk:set_loot(x, y, z, "treasure")`), as its package writes them, and
  places another package's blocks named in full (`"lib:altar"`) without
  listing them.

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
