import type { Fn, LuaClass } from '../types.ts'
import { DATA_SAVING, DATA_VALUES } from './data.ts'
import { nfCommands } from './commands.ts'
import { eventFunctions, nfEvents } from './events.ts'
import { nfCutscenes } from './cutscenes.ts'
import { nfScreens } from './screens.ts'
import { nfParticles } from './particles.ts'
import { nfStructures, nfWorlds } from './worlds.ts'
import { nfBossbars } from './inventories.ts'
import { nfItems } from './items.ts'
import { nfBlocks } from './blocks.ts'
import { nfRecipes } from './recipes.ts'
import { nfHttp } from './http.ts'
import { nfWeb } from './web.ts'
import { nfWasm } from './wasm.ts'
import { nfNetwork } from './network.ts'
import { nfBots } from './bots.ts'
import { nfLoot } from './loot.ts'
import { nfTeams } from './teams.ts'
import { nfSchedule } from './schedule.ts'
import { nfSpawning } from './spawning.ts'
import { nfEconomy, nfPlaceholders } from './interop.ts'
import { nfMath, nfRandom, nfTime } from './utilities.ts'
import { nfTest } from './test.ts'

/** A namespace under `nf`: plain functions on a table, called with a dot. */
const namespace = (name: string, doc: string, functions: LuaClass['functions']): LuaClass => ({
  name: `nf.${name}`,
  doc,
  methods: false,
  fields: [],
  functions,
})

/** `nf.server`: the server itself. */
export const nfServer = namespace(
  'server',
  'The server itself: its clock, its performance, its version, its settings (players, message of the day, whitelist), and saying or running things as the console.',
  [
    {
      name: 'tick',
      doc: 'Server ticks since NetherForge started (20 a second): the clock to measure durations with. Use `nf.server.unix_time()` only for timestamps that must mean something after a restart.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'unix_time',
      doc: 'The wall clock: milliseconds since 1970-01-01 UTC. For timestamps that outlive a restart (daily rewards, cooldowns saved to a file); measure durations in ticks.',
      params: [],
      returns: [{ type: 'integer' }],
      example:
        'local save = assert(nf.files.get("last_reward.json"))\nsave:write_json({ at = nf.server.unix_time() })',
    },
    {
      name: 'ticks_per_second',
      doc: 'How many ticks the server managed in the last second or so: 20 when it keeps up, less when it lags.',
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'tick_milliseconds',
      doc: 'How long a tick has been taking lately, in milliseconds, averaged over the last few seconds. Over 50 means the server is lagging.',
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'minecraft_version',
      doc: 'The running server\'s Minecraft version, like `"26.3"`.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'broadcast',
      doc: 'Sends a line to everyone online and the console, as the server.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage, like `"<gold>The gate opens"`.' }],
      returns: [],
    },
    {
      name: 'run',
      doc: "Runs a server command as the console, with the console's permissions: anything the console may do, which is why it needs a requirement of its own that whoever runs the server sees first. The leading slash is optional.",
      requires: 'console',
      params: [{ name: 'command', type: 'string', doc: 'One command line.' }],
      returns: [{ type: 'boolean', doc: 'Whether the server ran it.' }],
      example: 'nf.server.run("time set day")',
    },
    {
      name: 'max_players',
      doc: 'How many players the server lets in at once: what the server list shows as its maximum.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'set_max_players',
      doc: `Changes how many players the server lets in at once, until it restarts (\`server.properties\` is left alone). Nobody online is kicked when it drops below how many there are.`,
      requires: 'moderation',
      params: [{ name: 'count', type: 'integer', doc: 'At least 0.' }],
      returns: [],
    },
    {
      name: 'motd',
      doc: "The message of the day: the text under the server's name in the server list, as MiniMessage.",
      params: [],
      returns: [{ type: 'Text' }],
    },
    {
      name: 'set_motd',
      doc: `Changes the message of the day, until the server restarts (\`server.properties\` is left alone). To change it for one ping only, listen to \`server_list_ping\`.`,
      requires: 'moderation',
      params: [
        { name: 'text', type: 'Text', doc: 'MiniMessage; a line break starts the second line.' },
      ],
      returns: [],
      example: 'nf.server.set_motd("<gold>Event tonight at 8!")',
    },
    {
      name: 'is_whitelist_enabled',
      doc: 'Whether the whitelist is on: only players on it (`player:is_whitelisted()`) and operators can join.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_whitelist_enabled',
      doc: `Turns the whitelist on or off. Turning it on doesn't kick anyone online who isn't on it.`,
      requires: 'moderation',
      params: [{ name: 'enabled', type: 'boolean', doc: '' }],
      returns: [],
    },
  ],
)

/** `nf.players`: everyone who has played here. */
export const nfPlayers = namespace(
  'players',
  'Players: everyone online, or anyone who has ever joined.',
  [
    {
      name: 'online',
      doc: 'Everyone online.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'get',
      doc: 'A player by name (any case) or UUID: anyone who has ever joined this server, online or not, or `nil` for someone who never has. For someone offline, `exists()` is `false` and what needs them in the world (`position()`, `send_message`) answers `nil` or `false` until they come back.',
      params: [
        { name: 'name_or_id', type: 'string', doc: 'A name, or a UUID like `player:id()`.' },
      ],
      returns: [{ type: 'Player?' }],
      example:
        'local owner = nf.players.get(saved.owner)\nif owner and owner:exists() then\n  owner:send_message("<gold>Your crop is ready")\nend',
    },
    {
      name: 'known',
      doc: "Everyone who has ever joined this server, online or not, in no particular order. On a server that's been running a long time that's a lot of players, so call it when you need it (building a leaderboard) rather than every tick.",
      params: [],
      returns: [{ type: 'Player[]' }],
      example:
        'local players = nf.players.known()\ntable.sort(players, function(a, b)\n  return a:last_seen() > b:last_seen()\nend)',
    },
    {
      name: 'banned',
      doc: "Everyone who's banned, whose ban hasn't run out.",
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'whitelisted',
      doc: "Everyone on the server's whitelist, whether it's on or not.",
      params: [],
      returns: [{ type: 'Player[]' }],
    },
  ],
)

/** `nf.centities`: spawning and finding centity instances. */
export const nfCentities = namespace(
  'centities',
  'Centity instances: spawning new ones and finding live ones.',
  [
    {
      name: 'properties',
      doc: "A centity's `properties` from its `centity.json`, without one spawned: what `centity:properties()` answers for every instance of it. An empty table when the file has none; a centity the project doesn't have is an error. A new table each call.",
      params: [
        {
          name: 'kind',
          type: 'string',
          doc: 'Its folder name under `centities/`.',
          names: 'centity',
        },
      ],
      returns: [{ type: 'table' }],
      example: 'local stats = nf.centities.properties("green_slime")\nlog(stats.hp)',
    },
    {
      name: 'spawn',
      doc: "Puts a new instance of a centity into the world. Its entities exist, its script's body has run and its `spawn` handlers have heard it by the time this returns. A centity the project doesn't have is an error.",
      params: [
        {
          name: 'kind',
          type: 'string',
          doc: 'Which centity: its folder name under `centities/`.',
          names: 'centity',
        },
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: "Where its position (the anchor) goes: a `Location` (it faces the location's `yaw`, when it has one), or a position in the calling centity's world (the server's first world, in a module). Without a yaw it faces south (yaw 0).",
        },
        { name: 'options', type: 'CentitySpawnOptions', doc: '', optional: true },
      ],
      returns: [
        { type: 'Centity?', doc: "The new instance, or `nil` when the world doesn't exist." },
      ],
      example: 'local tower = nf.centities.spawn("tower", player:location():offset(vec3(2, 0, 0)))',
    },
    {
      name: 'get',
      doc: "The live centity instance with this id, or `nil` once it's gone. Ids come from `centity:id()` and are fine to keep.",
      params: [{ name: 'id', type: 'string', doc: 'An instance id.' }],
      returns: [{ type: 'Centity?' }],
    },
    {
      name: 'all',
      doc: "Every live centity instance, or those the filter picks. Includes the caller's own instance. A filter key that isn't one of `CentityFilter`'s is an error.",
      params: [
        {
          name: 'filter',
          type: 'CentityFilter',
          doc: 'Only instances of one kind, in one world, or near a point.',
          optional: true,
        },
      ],
      returns: [{ type: 'Centity[]' }],
      example:
        'for _, crate in ipairs(nf.centities.all({ kind = "crate", world = nf.worlds.default() })) do\n  crate:remove()\nend',
    },
  ],
)

/** `nf.menus`: the project's menus. */
export const nfMenus = namespace(
  'menus',
  "The project's menus (`menus/<id>/`), and menus scripts make.",
  [
    {
      name: 'shared',
      doc: 'The window of a menu whose file says `"shared": true`: the one every player sees. A menu that isn\'t shared has a window per player and no one to hand back, so this is `nil`; open one with `player:open_menu`. A menu the project doesn\'t have is an error.',
      params: [
        {
          name: 'menu',
          type: 'string',
          doc: 'Its folder name under `menus/`.',
          names: 'menu',
        },
      ],
      returns: [{ type: 'Menu?' }],
      example:
        'local stock = nf.menus.shared("bank")\nif stock then\n  stock:add_item({ kind = "minecraft:gold_ingot", count = 3 })\nend',
    },
    {
      name: 'create',
      doc: 'Makes a menu from a table instead of a file: a `MenuTemplate`, which `player:open_menu(template)` opens a new window of, as often as you like. The table says what `menu.json` would, in Lua spelling: `type` (`"chest"`, the default, `"barrel"`, `"shulker_box"`, `"hopper"`, `"dispenser"`, `"dropper"` or `"crafter"`), `rows` (a chest\'s 1 to 6, default 3), `title` (MiniMessage), `skin` (`<pack>/<key>`), `locked` (default `true`) and `slots`, items by slot number from 0. A template has no script of its own: put handlers on it (`template:on("click", ...)`), and they hear every window made from it. It lasts as long as the script that created it. A key it doesn\'t take, a value of the wrong type, a slot outside the window, an item the server doesn\'t have or a skin the project\'s resource packs don\'t have is an error, checked by the same rules as `menu.json`.',
      params: [
        {
          name: 'definition',
          type: 'table',
          doc: '`{ type?, rows?, title?, skin?, locked?, slots? }`: what the menu is, as `menu.json` would say it, with `slots` as `{ [index] = item }`.',
        },
      ],
      returns: [{ type: 'MenuTemplate' }],
      example:
        'local confirm = nf.menus.create({\n  rows = 1,\n  title = "<red>Are you sure?",\n  slots = {\n    [3] = { kind = "minecraft:lime_wool", name = "<green>Yes" },\n    [5] = { kind = "minecraft:red_wool", name = "<red>No" },\n  },\n})\nconfirm:on("click", function(event)\n  if event.index == 3 then\n    event.player:send_message("<green>Done.")\n  end\n  event.player:close_menu()\nend)\n\nnf.commands.register("reset", { players_only = true }, function(event)\n  event.player:open_menu(confirm)\nend)',
    },
  ],
)

/** `nf.dialogs`: the project's dialogs. */
export const nfDialogs = namespace(
  'dialogs',
  "The project's dialogs (`dialogs/<id>/`), and dialogs scripts make.",
  [
    {
      name: 'get',
      doc: "A dialog by id. There's one of each, so this always finds it; a dialog the project doesn't have is an error.",
      params: [
        {
          name: 'dialog',
          type: 'string',
          doc: 'Its folder name under `dialogs/`.',
          names: 'dialog',
        },
      ],
      returns: [{ type: 'Dialog' }],
      example: 'nf.dialogs.get("welcome"):open_for(event.player)',
    },
    {
      name: 'create',
      doc: "Makes a dialog from a table instead of a file. The table says what `dialog.json` would, in Lua spelling (`after_action`, `can_close_with_escape`, an input's `max_length`, items as `Item` tables): `title` (required), `type`, `body`, `inputs`, `buttons`, `columns`, `dialogs`, and the rest of the file's fields except `script`. The `Dialog` it returns works like any other: `open_for`, `ask`, `button(key)`, and `press` and `close` handlers on it and its buttons. Its id is made up and never names a folder under `dialogs/`. It lasts as long as the script that created it; after that its methods answer `nil` or `false` and its handlers are gone. A key it doesn't take, a value of the wrong type, or anything `dialog.json` would have an error for (two buttons with one key, a `dialog_list` naming a dialog there isn't) is an error.",
      params: [
        {
          name: 'definition',
          type: 'table',
          doc: 'What the dialog is, as `dialog.json` would say it, with its keys in snake_case.',
        },
      ],
      returns: [{ type: 'Dialog' }],
      example:
        'local rename = nf.dialogs.create({\n  type = "confirmation",\n  title = "Rename your pet",\n  inputs = { { type = "text", key = "name", label = "Name", max_length = 16 } },\n  buttons = { { key = "ok", label = "Rename" }, { key = "cancel", label = "Cancel" } },\n})\nrename:button("ok"):on("press", function(event)\n  event.player:send_message("Now called " .. nf.text.escape(tostring(event.values.name)))\nend)',
    },
  ],
)

/** `nf.files`: the scripts' data directory. */
export const nfFiles = namespace(
  'files',
  "Files in the package's own folder (`plugins/NetherForge/data/<namespace>/`), which no other package's scripts see unless they're handed a file: the only state a script has that survives a restart. The server owner limits how much each package may keep there (`files:` in `config.yml`); a write past it fails.",
  [
    {
      name: 'get',
      doc: "A file or folder in the calling package's own folder, which survives restarts and reloads. A handle is a path, not an open file: nothing is read until you ask. Paths that would leave that folder (`..`, backslashes, names starting with a dot) give `nil` rather than an error. No argument is the folder itself.",
      params: [
        {
          name: 'path',
          type: 'string',
          doc: 'Relative, with `/` between folders.',
          optional: true,
        },
      ],
      returns: [{ type: 'File?' }],
      example:
        'local save = assert(nf.files.get("scores.json"))\nlocal scores = save:read_json() or {}\nscores[player:name()] = (scores[player:name()] or 0) + 1\nsave:write_json(scores)',
    },
  ],
)

/** `nf.text`: MiniMessage helpers. */
export const nfText = namespace(
  'text',
  'Helpers for the MiniMessage text players see: escaping what players typed, stripping tags, resource pack glyphs, moving the cursor, and measuring.',
  [
    {
      name: 'escape',
      doc: 'Escapes text so MiniMessage shows it as typed rather than reading tags in it. Use it on anything a player wrote (a name they chose, a dialog answer, a chat line) before putting it into MiniMessage: otherwise they can add colours, or worse, `<click:run_command:...>`.',
      params: [{ name: 'text', type: 'string', doc: 'Plain text.' }],
      returns: [{ type: 'Text', doc: 'MiniMessage that reads as exactly that text.' }],
      example:
        'player:send_message("<green>Hello, " .. nf.text.escape(tostring(event.values.nickname)))',
    },
    {
      name: 'strip',
      doc: 'MiniMessage with its tags taken out: the plain text a player would read, for logs and comparisons.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'string' }],
    },
    {
      name: 'glyph',
      doc: "The character a resource pack glyph is drawn as, for building text by hand: a glyph wider than 256 pixels is drawn in pieces, so it's those pieces' characters and the moves between them. In MiniMessage text the tag `<glyph:ui/coin>` is usually simpler: it works anywhere text does (names, lore, chat, titles, dialogs, text displays) and draws the picture in its own colours, never bold, while this text is tinted by the text colour around it like any other (wrap it in `<white>` to avoid that, and keep it out of `<bold>`, which pulls a wide glyph's pieces apart). `nil` while the project's resource packs have errors (nothing is built then); a glyph no resource pack has is an error.",
      params: [
        {
          name: 'glyph',
          type: 'string',
          doc: '`<pack>/<key>`, like `"ui/coin"`, or another loaded package\'s as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)).',
          names: 'glyph',
        },
      ],
      returns: [{ type: 'string?' }],
      example:
        'local coin = nf.text.glyph("ui/coin")\nplayer:send_message("<white>" .. coin .. "</white> <gold>" .. balance)',
    },
    {
      name: 'space',
      doc: "Characters that move the cursor `pixels` GUI pixels (negative is left) and draw nothing, for building text by hand: what the tag `<space:N>` inserts. In MiniMessage the tag is usually simpler, and `<at:N>…</at>` draws something N pixels away without moving what follows (`<at:-30><glyph:ui/head></at>King Slime`; add `from=center`, `from=start` or `from=end` to count N from the middle, start or end of the line, and `align=right` or `align=center` to put that edge of what it holds there). The characters are the resource pack's, so they need one: `nil` while there's none (the project has no resource packs or blocks). Inside `<bold>` each character is a pixel wider, which the tag isn't. Held to ±2047.",
      params: [{ name: 'pixels', type: 'integer', doc: 'How far, in GUI pixels.' }],
      returns: [{ type: 'string?' }],
      example:
        'local column = nf.text.width("Sword") or 0\nplayer:send_message("Sword" .. (nf.text.space(60 - column) or " ") .. "<gold>12 gold")',
    },
    {
      name: 'width',
      doc: "How wide a line of MiniMessage text draws in the game's default font, in pixels (GUI pixels: the font is 8 tall), for laying out menu titles and text displays. Tags draw nothing, except `<bold>` (a pixel more per character), `<glyph:ui/coin>` (the glyph's own width) and `<space:N>` (N, which may be negative); what an `<at:N>` holds takes no width. Text with line breaks measures as its widest line. It reads the default font's widths from `fonts/default.json`, which the editor writes into the project from your Minecraft install. `nil` when it can't be measured exactly: that file is missing (open the project in the editor with Minecraft imported), or the text uses a character it doesn't cover.",
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'integer?' }],
      example:
        'local width = nf.text.width("<bold>Shop")\nif width then\n  log("the title is " .. width .. " pixels wide")\nend',
    },
  ],
)

/** `nf.looks`: resource pack looks handed between packages. */
export const nfLooks = namespace(
  'looks',
  "Resource pack looks (item models, glyphs, skins, tooltips, equipment looks, sounds) handed from one package's code to another's. A look is a value, not a use: any script can show any look the server has, by its full name `<namespace>:<pack>/<key>`, whether or not its package exports it or is one the script's package depends on. That's how a project hands a package it uses looks of its own to draw with (see [Packages](packages.md#looks-are-values)).",
  [
    {
      name: 'qualify',
      doc: "A look's full name, `<namespace>:<pack>/<key>`, as the calling script's package names it: `\"nether/pawn\"` in the project `chess_server` is `\"chess_server:nether/pawn\"`, which means the same in any package's code. Hand that to a package, never the bare name: a bare name means the package's own look wherever it's used. A name that already has a namespace comes back as it is. It's a plain string, so it can be kept in saved data (`this:data()`). Whether the look is there isn't checked here, but where it's shown; a name that isn't shaped like a look is an error.",
      params: [
        {
          name: 'look',
          type: 'string',
          doc: '`<pack>/<key>`, or `<namespace>:<pack>/<key>`.',
        },
      ],
      returns: [{ type: 'string' }],
      example:
        '-- The project hands the chess package a skin of its own models.\nlocal chess = require("chess:board")\nchess.set_skin("black", {\n  pawn = nf.looks.qualify("nether/pawn"),\n  king = nf.looks.qualify("nether/king"),\n})',
    },
  ],
)

/** `nf.json`: JSON text. */
export const nfJson = namespace('json', 'JSON text to and from Lua values.', [
  {
    name: 'encode',
    doc: 'A value as JSON text, written the way a `data()` table is saved: tables numbered from 1 become arrays, other tables objects (with string keys, in order), and the API\'s own values one-key objects that `nf.json.decode` turns back into them: `vec3(1, 2, 3)` is `{"$vec3":[1,2,3]}`, and `Location`s and `Player`, `Entity`, `Centity` and `World` handles have tags of their own. A key of yours that would read as a tag (`"$vec3"`) is written with one more `$`; any other key (`"$ref"`) is written as it is. A value JSON can\'t hold (a function, another kind of handle, a table that contains itself, a number key outside a list) is an error naming where it was (`value.callback: a function can\'t be written as JSON`).',
    params: [{ name: 'value', type: 'any', doc: '' }],
    returns: [{ type: 'string' }],
    example: 'log(nf.json.encode({ name = "crate", sizes = { 1, 2, 3 } }))',
  },
  {
    name: 'decode',
    doc: "JSON text as a Lua value: objects and arrays become tables, and what `nf.json.encode` tags becomes a `Vec3`, `Location` or handle again. `nil` for text that isn't JSON (and for `null`), so text from a player or a file can be tried without guarding.",
    params: [{ name: 'text', type: 'string', doc: '' }],
    returns: [{ type: 'any' }],
  },
])

/** Every namespace under `nf`, as `nf`'s fields. */
const namespaces = [
  nfServer,
  nfCommands,
  nfPlayers,
  nfBots,
  nfWorlds,
  nfStructures,
  nfCentities,
  nfSpawning,
  nfMenus,
  nfDialogs,
  nfItems,
  nfBlocks,
  nfRecipes,
  nfLoot,
  nfBossbars,
  nfTeams,
  nfSchedule,
  nfEconomy,
  nfPlaceholders,
  nfParticles,
  nfCutscenes,
  nfScreens,
  nfFiles,
  nfText,
  nfLooks,
  nfJson,
  nfHttp,
  nfWeb,
  nfWasm,
  nfNetwork,
  nfTime,
  nfRandom,
  nfMath,
  nfTest(nfEvents),
]

/** `a`, `a and b`, `a, b and c` (or [and] another word). */
const listed = (items: string[], and = 'and') =>
  items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} ${and} ${items.at(-1)}`

/** How a script calls a class's function: `nf.wait`, `dialog:ask`. */
const called = (cls: LuaClass, fn: { name: string }) =>
  cls.methods ? `${cls.name.toLowerCase()}:${fn.name}` : `${cls.name}.${fn.name}`

/**
 * What `nf.wait_for` can wait on: every class with events of its own but none up its chain
 * (an `Entity` stands for a `Mob` too), and `nf` for the server's events.
 */
function waitable(classes: LuaClass[]): string {
  const byName = new Map(classes.map((it) => [it.name, it]))
  const evented = (cls: LuaClass | undefined): boolean =>
    !!cls && ((cls.events?.length ?? 0) > 0 || evented(byName.get(cls.extends ?? '')))
  const top = classes.filter(
    (cls) => (cls.events?.length ?? 0) > 0 && !evented(byName.get(cls.extends ?? '')),
  )
  return [...top.map((it) => it.name), 'nf'].join('|')
}

/**
 * The `nf` table: everything outside the script itself. [classes] are the API's other classes,
 * which what `nf` says about them is worked out from: the namespaces it lists, what a task can
 * wait for and what `nf.wait_for` waits on.
 */
function nfClass(classes: LuaClass[]): LuaClass {
  const all = [nfTable, ...namespaces, ...classes]
  const named = (pick: (fn: Fn) => boolean | undefined) =>
    all.flatMap((cls) => cls.functions.filter(pick).map((fn) => `\`${called(cls, fn)}\``))
  const async = named((it) => it.async)
  const waits = [
    ...named((it) => it.waits),
    ...(async.length
      ? [`an asynchronous function like ${listed(async, 'or')} called without its callback`]
      : []),
  ]
  return {
    ...nfTable,
    doc: `Everything outside the script itself. The core verbs (events, timers, tasks and waiting, saved data, the budget) are on \`nf\`; everything else is grouped into namespaces under it: ${listed(namespaces.map((it) => `\`${it.name}\``))}. Plain functions on tables, so call them with a dot: \`nf.players.online()\`.`,
    functions: nfTable.functions.map((fn) => {
      if (fn.name === 'task') return { ...fn, doc: fn.doc.replace('{waits}', listed(waits, 'or')) }
      if (fn.name === 'wait_for')
        return {
          ...fn,
          params: fn.params.map((p) =>
            p.name === 'handle' ? { ...p, type: waitable(classes) } : p,
          ),
        }
      return fn
    }),
  }
}

/** `nf` as written here, before what it says about the rest of the API is filled in ([nfClass]). */
const nfTable: LuaClass = {
  name: 'nf',
  doc: '',
  methods: false,
  fields: namespaces.map((it) => ({
    name: it.name.slice('nf.'.length),
    type: it.name,
    doc: it.doc,
  })),
  events: nfEvents,
  customEvents: true,
  functions: [
    ...eventFunctions(
      'nf',
      'nf',
      'nf.on("player_join", function(event)\n  event.player:send_message("<green>Welcome!")\nend)',
      true,
    ),
    {
      name: 'after',
      impl: 'lua',
      doc: 'Calls `callback` once, `ticks` server ticks from now (20 ticks are a second). Timers stop when the script unloads. One that errors is logged. To do several things with pauses in between, start a task (`nf.task`) and `nf.wait` in it.',
      params: [
        { name: 'ticks', type: 'integer', doc: 'How long to wait. 0 runs on the next tick.' },
        { name: 'callback', type: 'fun()', doc: '' },
      ],
      returns: [{ type: 'Task', doc: 'Call `:cancel()` on it to stop it first.' }],
    },
    {
      name: 'every',
      impl: 'lua',
      doc: 'Calls `callback` every `ticks` server ticks, starting `ticks` from now, until cancelled or the script unloads. One that errors is logged, and stops after 20 errors in a row.',
      params: [
        { name: 'ticks', type: 'integer', doc: 'At least 1.' },
        { name: 'callback', type: 'fun()', doc: '' },
      ],
      returns: [{ type: 'Task' }],
      example: 'nf.every(20, function()\n  log("another second")\nend)',
    },
    {
      name: 'task',
      impl: 'lua',
      doc: "Starts a task: `callback` runs now, with the extra arguments, until it first waits ({waits}), and carries on from there when the wait is over. Only a task can wait; event handlers can't, because whether an event is cancelled or stopped has to be known when it returns, so a handler that needs to wait starts a task. Each time a task carries on it gets the script's whole instruction budget again. A task belongs to the script that started it and ends when that script unloads, when `cancel()` is called, or when the handle it waits on goes. One that errors is logged with its file and line and ends; the code that started it carries on either way.",
      params: [
        {
          name: 'callback',
          type: 'async fun(...: any)',
          doc: 'The task: plain Lua that may wait.',
        },
        { name: '...', type: 'any', doc: 'Passed to `callback`.' },
      ],
      returns: [{ type: 'Task', doc: 'Call `:cancel()` on it to end it early.' }],
      example:
        'this:on("click", function(event)\n  nf.task(function()\n    this:play_animation("open")\n    nf.wait_for(this, "animation_end")\n    nf.wait(40)\n    this:play_animation("close")\n  end)\nend)',
    },
    {
      name: 'wait',
      impl: 'lua',
      waits: true,
      doc: "Pauses the task for `ticks` server ticks (20 are a second). It can't wait inside a function the server or Lua itself calls back (a `complete` function, `table.sort`'s comparator), nor inside a coroutine of your own; inside `pcall` is fine.",
      params: [
        { name: 'ticks', type: 'integer', doc: 'How long. 0 waits for the next tick, like 1.' },
      ],
      returns: [],
      example:
        'nf.task(function()\n  for i = 3, 1, -1 do\n    nf.server.broadcast(tostring(i))\n    nf.wait(20)\n  end\n  nf.server.broadcast("<green>Go!")\nend)',
    },
    {
      name: 'wait_until',
      impl: 'lua',
      waits: true,
      doc: "Pauses the task until `predicate()` returns something other than `false` or `nil`. It's called once straight away and then once every tick, as the task's code.",
      params: [
        { name: 'predicate', type: 'fun(): any', doc: '' },
        {
          name: 'timeout',
          type: 'integer',
          doc: 'Ticks to give up after. Leave it out to wait for as long as it takes.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`true` once `predicate` held, `false` when the timeout ran out first.',
        },
      ],
      example:
        'nf.task(function()\n  if nf.wait_until(function() return #nf.players.online() >= 2 end, 20 * 60) then\n    nf.server.broadcast("<green>Starting!")\n  end\nend)',
    },
    {
      name: 'wait_for',
      impl: 'lua',
      waits: true,
      doc: "Pauses the task until `event` next happens on `handle` (or, for `nf`, anywhere on the server) and returns that event. The task carries on straight away, while the event is still being delivered, so until its next wait it can still `cancel()` or `stop()` the event or set its writable fields. If the handle goes first (a removed centity, a closed window, a dialog taken away by a reload), the task ends. An event the handle doesn't have is an error.",
      params: [
        {
          name: 'handle',
          // Every class with events, and `nf`: filled in by `nfClass`.
          type: 'nf',
          doc: 'What the event happens to, as for `handle:on`; `nf` for a server-wide event.',
        },
        { name: 'event', type: 'string', doc: "The event's name, as `handle:on` takes it." },
        {
          name: 'filter',
          type: 'fun(event: Event): boolean',
          doc: "Waits for the first event this returns `true` for; the others pass by. Run as the task's code, so an error in it ends the task.",
          optional: true,
        },
      ],
      returns: [{ type: 'Event', doc: 'The event: its payload and its methods.' }],
      example:
        'nf.task(function()\n  local event = nf.wait_for(nf, "player_chat", function(e) return e.player == player end)\n  player:send_message("You said " .. nf.text.escape(event.message))\nend)',
    },
    {
      name: 'data',
      doc: `A saved table for the whole project, by name: \`nf.data("shop")\` is the same table in every script, so a module keeps its own under its own name and two modules never collide. Each name is its own file in the data directory, kept across restarts. ${DATA_VALUES} ${DATA_SAVING} A name that isn't lowercase letters, digits and \`_\` (starting with a letter or digit, at most 64 characters) is an error.`,
      params: [{ name: 'name', type: 'string', doc: 'Which table, like `"shop"`.' }],
      returns: [{ type: 'table' }],
      example:
        'local shop = nf.data("shop")\nshop.sales = (shop.sales or 0) + 1\nshop.last_buyer = event.player',
    },
    {
      name: 'db',
      doc: "A database. With no name it is this package's own: a SQLite file that belongs to it alone (each package, a dependency included, has its own, under the server's `plugins/NetherForge/databases/`), whose tables come from the package's `migrations/NNN_name.sql`, run in order when the project loads. With a name it is one of the MySQL or PostgreSQL connections the server's owner set up under `databases:` in the plugin's `config.yml`: the script names it and never sees a host, a user or a password, and the connection opens only to the packages the owner lists for it. Either way it's the same `Database`, so the same calls work (the SQL is the server's own dialect; see `Database` for what differs). A name that isn't set up, or isn't open to this package, is an error.",
      requires: 'db',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'The connection, as the server owner named it in `config.yml`, like `"network"`. Leave it out for the package\'s own SQLite database.',
          optional: true,
        },
      ],
      returns: [{ type: 'Database' }],
      example:
        'local db = nf.db()\nnf.task(function()\n  local rows, err = db:query("SELECT count(*) AS total FROM scores")\n  if rows then\n    log("scores: " .. rows[1].total)\n  else\n    log("no scores: " .. err)\n  end\nend)\n\n-- The same, on the shared MySQL or PostgreSQL server the owner called "network":\nlocal network = nf.db("network")',
    },
    {
      name: 'config',
      doc: "One of this package's settings: what whoever runs the server set it to, or its default when they haven't. Settings are declared in `netherforge.json`'s `settings` (each with a type, a default and a description) and changed by the server's owner, never by scripts; a package reads only its own. The value is a `boolean`, an `integer`, a `number` or a `string`, by the setting's type. A script that reads a setting and doesn't listen for `setting_changed` is restarted when that setting changes; one that listens hears the change instead. A name the package doesn't declare is an error.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: "The setting's name, as `netherforge.json` declares it.",
          names: 'setting',
        },
      ],
      returns: [{ type: 'any' }],
      example: 'local rolls = nf.config("treasure_rolls")',
    },
    {
      name: 'instructions_left',
      impl: 'lua',
      doc: "Lua instructions this call may still run before it's stopped. A loop over a lot of work can check it and leave the rest for the next tick. In a call nested in another (a task started by a handler, a centity spawned by one) it's the least of its own budget and what its caller has left, since what it runs is charged to the caller when it returns. Coarse: it's updated every thousand instructions.",
      params: [],
      returns: [{ type: 'integer' }],
    },
  ],
}

/** `nf` and every namespace under it, in the order the reference lists them. */
export const nfClasses = (classes: LuaClass[]): LuaClass[] => [nfClass(classes), ...namespaces]
