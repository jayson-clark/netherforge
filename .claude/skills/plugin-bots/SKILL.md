---
name: plugin-bots
description: The plugin's fake players - the bridge's bots extension (bots/join, bots/act), scripts' nf.bots behind requires.bots, the Bot handle, and how a bot joins and acts on a real server (Mojang-named code in paper-internals: BotConnection, the client's half, movement, BotScreen, BotDialogs, BotProtocol per version, aiming and what it looks at), with the contract suites that hold the fake to it. Read before touching .../bots, BotsBridge, PaperBots, FakeBots, or adding a player action or event.
---

# Plugin bots

Bots are how tests, coding agents and scripts play the server as a player would. They live in the plugin jar (paper-internals), so every adapter has them.

## Bots

Fake players, driven two ways: tests and coding agents over the bridge's
bots extension (`bots/…` requests, outside the core protocol; the protocol and
what each action means are in format's `bridge/Bots.kt`), and scripts through
`nf.bots` and the `Bot` class. The runtime sees them as `Platform.bots`
(`BotOps`, a required group like any other).

- **The bridge's** (`bridge/BotsBridge.kt`, always installed into
  `BridgeService`): answers `bots/join` and `bots/act` when they finish, ticks
  later (a deferred reply), and resolves an interact or attack naming a
  centity's node to its interaction entity and the middle of its hitbox
  (`Centities.aim`). A bridge bot is the same player every time (an
  offline-mode UUID from its name, saved like anyone's), so a test can leave
  and come back.
- **Scripts'** (`bots/ScriptBots`, a session service, `session.bots`): a
  package declares `requires.bots`, and the owner's `bots:` (`BotsConfig`: off
  by default, `max` online at once; the dev server always on) decides whether
  `nf.bots.join` may make one, else `nil, err` (and `runtime.bots-disabled` on
  each declaring package while off). `join` is async: a `BotJoin` with a
  `ScriptBot` (a fresh UUID, never saved: `Player.setPersistent(false)` keeps
  `PlayerList.save` away; the `NPC` metadata Citizens sets; view distance
  `ScriptBot.VIEW_DISTANCE`; the `Skin` as the profile's `textures`), the pack
  answered `LOADED` without fetching it. On Paper, `PaperBots` sets these at
  `PlayerJoinEvent` `LOWEST`, so other plugins hear an NPC join. Each belongs
  to the scope that joined it: `scopeReleased` and `stop` make it leave.
  `ScriptBots.act` is the one way the runtime drives any bot (a `BotAction`,
  done now or later, failures as `WorkFailed`); `BotImpl` maps `Bot`'s methods
  onto it (`walk_to`, `break_block` async; the rest `did`, answering whether
  it was done), and a click on a `Node` aims as the bridge's does.
- **Handles.** Every bot online is a `Bot` (extends `Player`):
  `entityHandle(info)` asks `BotOps.isBot`, and a `Player` handle refines to
  `Bot` when a `Bot` method is called on it. That gives `Player` the refining
  `__index`, 4 instructions a call (the goldens say so).

Otherwise a bot is a player like any other: it's in `players.online()`, and
its joins, clicks and chat come through `PlatformEvents`.

The bots are in the plugin jar (`apps/plugin/paper-internals/src/main/.../bots`,
made by `PaperVersion.bots`, since they're Mojang-named code each adapter
compiles against its server). They reach past the Paper API (as do the registries and goal priorities, see
"Mob goals") into the server itself (`net.minecraft`, Mojang-named, through
paperweight-userdev), through `BotProtocol` where versions differ:

- **`BotConnection`** is Minecraft's own `Connection` over an
  `EmbeddedChannel` with a loopback address: the server keeps it, ticks its
  listener and disconnects it like any. `send` hands each packet to the bot
  instead of encoding it (bundles unpacked), and completes the send's
  listener, which is how a kick closes the connection.
- **`Bot`** is the client's half. It joins where a client's login ends:
  `ServerConfigurationPacketListenerImpl` and `startConfiguration`, then
  answers what the vanilla client would (known packs, code of conduct, the
  resource pack, finish), so `PrepareSpawnTask`, the login checks
  (whitelist, bans, full server), player data and `PlayerJoinEvent` are the
  server's own. Packets it sends are handed straight to the listener
  (`packet.handle(listener)`, on the main thread, as the packet processor
  would). Each tick the server ticks its connection **first** (no server
  ticks it otherwise), then the bot reads only what had arrived before the
  tick began (what the server sends back meanwhile waits a tick, as over a
  network: answering a teleport before the listener has ticked once trips
  "moved too quickly"), acts, moves, and ends with a tick-end packet (the
  server allows one position packet between them).
- **Movement is the client's**: the server snaps players back to their last
  good position every tick, so the bot steps its own position (gravity, drag,
  `Entity.collideBoundingBox`, jumping by itself when walking into a step)
  and sends move packets, which the server checks and turns into
  `PlayerMoveEvent` like anyone's.
- **Loaded** is the vanilla client's rule: the chunk it stands in has
  arrived (Paper sends chunks without batches). It then sends
  `ServerboundPlayerLoadedPacket`, and a join answers two ticks later, once
  the entities in that chunk have been sent.
- **`BotScreen`** is what the client shows, from packets only: the open
  container and its contents (with the state id clicks need), the dialog,
  boss bars, the sidebar (objectives, scores and teams added up as the
  client does), titles, resource packs, and the entities it has been sent (so a
  hidden entity is absent, and clicking it is refused). Its events record
  what passes without staying on screen: chat, titles, sounds and sound
  stops, particles (`BotEvent.Particle`: the particle, where, how many,
  spread, speed and whether forced; 26.3's packet is a record with a speed
  per axis, so `BotProtocol.particleMotion` reads it per version), boss bars
  coming and going, blocks, equipment, the camera.
- **`BotDialogs`** presses a dialog button as the client's screen does:
  each input's value (given or initial) as an `Action.ValueGetter`, the
  button's `Action.createAction`, then the click event (custom click packet,
  command, or another dialog).
- **`PaperBots`** keeps them by name and ticks them while there are any. A
  script's bots leave as the session stops (`ScriptBots.stop`, while scripts
  still hear the quits); `NetherForgePlugin.onDisable` makes the rest leave
  after the runtime has stopped, as a real player's quit comes then.

- **Every player action has a `BotAction`** (walk, teleport, look, use, mine,
  drop, swap hands, `release_item`, `fly`, `edit_sign`, `click_button`,
  menu clicks, dialog buttons, respawn), so every player event a script can
  hear can be raised by a bot. `BotActionsContract` (runs on the fake and on
  Paper) covers each action and the event it makes: an event watched only
  while a script listens is heard only then, and a cancelled one undoes the
  action. `PaperGameEventsTest` (Paper only, last in the contract run)
  triggers every other `GameEvents` listener and checks the watch-only ones
  register and unregister. `BotProtocol.signUpdate` is per adapter, as the
  sign packet differs between versions. A new player event or action gets a
  case in these and a step in `BotScenario`.

- **Aiming a click.** `interact`/`attack` naming nothing click what's in the crosshair where the
  line of sight meets it, without turning (`Bot.aimed`, `FakeBots.aimed`): never re-aim at the
  middle, or a `look_at` at one spot of a big hitbox (a board's square) is lost. Naming an entity
  or a centity's node turns to its middle, or to the action's `x`/`y`/`z` when given (over the
  runtime's `BotAim.at`). `BotActionsContract` holds both servers to it with a wide flat hitbox.
- **What it looks at** (`BotState.target`, a `BotTarget`): picked as the client's crosshair is, the
  nearest pickable entity the client knows within entity reach in front of the block within block
  reach (`Bot.sight`, the same rule as `inSight` for a click; `FakeBots.target` with the fake's
  ray casts, NetherForge's hitboxes included). Which centity and node a hitbox is, the node chosen
  as a click there would choose it (`Centities.clickTarget`, the same `pick` as `click`), is filled
  in by `BotsBridge` on the way out: only the runtime knows. `BotActionsContract` holds both
  servers to it; `BridgeTest` the node.

Gotcha: Paper's packages are null-marked, so Kotlin trusts annotations like
`Connection.getPlayer()`'s non-null even where it's null (before the player is
in the world). Read such things from the listener instead.
