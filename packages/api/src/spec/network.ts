import { asyncFunction } from '../async.ts'
import type { EventSpec, LuaClass, Param } from '../types.ts'
import { eventFunctions } from './events.ts'

/** What crosses the network, as every value's doc says it. */
const VALUES =
  "Anything a `data()` table can hold, sent as JSON with the same codec: tables, strings, numbers, booleans, `Vec3`, `Location` (its world by name) and handles to entities and worlds (by UUID and name, which another server may not have). At most `max-value-bytes` as JSON (the proxy's `hub.properties`, 64 KiB unless its owner says otherwise); a value over it, or one that can't be written as JSON, is an error at the script's line."

/** What a channel's or map's name may be, as `channel` and `map` take it. */
const NAME_DOC =
  "Its name: lowercase letters, digits and `_ . - /`, at most 64 characters. It's the calling package's own (`shop` names `\"sales\"` `shop:sales`), so packages can't collide; `namespace:name` names one of a package it depends on."

const CHANNEL: Param = { name: 'name', type: 'string', doc: NAME_DOC, names: 'channel' }

const MAP: Param = { name: 'name', type: 'string', doc: NAME_DOC, names: 'network_map' }

/** Where `send_player` and `send_players` send players. */
const TARGET: Param = {
  name: 'target',
  type: 'string|NetworkSendOptions',
  doc: 'A server\'s name in the network, as the proxy names it (`"lobby"`; `nf.network.servers()` lists them), or a table naming a server or a group (`{ group = "arena" }`), when the hub picks the instance.',
  names: 'server',
}

/** Where a request goes. */
const REQUEST_TARGET: Param = {
  name: 'target',
  type: 'string|NetworkTarget',
  doc: 'A server\'s name in the network (`"lobby"`), or a table naming a server or a group (`{ group = "arena" }`): then one instance of the group that answers on the channel is asked, each in turn.',
  names: 'server',
}

/** How a send's data is documented. */
const SEND_DATA: Param = {
  name: 'data',
  type: 'any',
  doc: `What arrives with them, as \`event.data\` there; \`target.data\` when left out. ${VALUES}`,
  optional: true,
}

/** `nf.network`: the other servers of the network this server is part of. */
export const nfNetwork: LuaClass = {
  name: 'nf.network',
  doc: "The other servers of the network this one is part of: a proxy (Velocity) running NetherForge's proxy plugin, which is the network's hub, and the servers behind it, each running NetherForge and connected to the hub (`network:` in their `config.yml`). Scripts publish messages on channels every server can listen to (`nf.network.channel`), ask one server and get its answer (`channel:request`), keep values every server sees in shared maps (`nf.network.map`), know who's online on which server (`players`, the `network_player_*` events), and send a player to another server with data that arrives with them (`send_player`). A package declares it with `\"requires\": { \"network\": true }`, and the server owner sees that (`/nf requires`). Channels and maps are named in the calling package's namespace, so packages never see each other's unless one depends on the other. Everything goes through the hub: a server that isn't connected (`is_connected()`) misses what's published meanwhile, and what it sends returns `false` or fails. Delivery is at most once, and in order between any two servers. On the editor's dev server, unless its `config.yml` puts it in a network, the server is a network of its own, named `\"dev\"`, so channels and maps work there with no proxy (sending players doesn't). A server whose owner hasn't put it in a network has none: its packages that declare it have the warning `runtime.network-disabled`.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'server_name',
      doc: 'This server\'s name in the network, as the proxy names it (`network.server-name` in `config.yml`); `"dev"` on the editor\'s dev server when it\'s a network of its own, `"test"` in a script test. `nil` on a server that isn\'t part of a network.',
      requires: 'network',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'server_role',
      doc: "What this server may do in the network, as the hub says: a server that joined with the network's shared secret is `trusted` and may do anything; one that joined with a credential of its own (`network.credential` in `config.yml`) has its credential's role, the namespaces whose channels and maps it owns, what the network shares with it of others', whether it sees players on other servers, and where it may send players. A call it may not make is an error at the script's line saying `network.forbidden` (a channel's or map's), or fails with `network.forbidden` in `err` (sending a player), and never reaches another server. `nil` until this server has connected.",
      requires: 'network',
      params: [],
      returns: [{ type: 'NetworkRole?' }],
      example:
        'local role = nf.network.server_role()\nif role and not role.trusted then\n  log("this server is " .. role.role .. ", owning " .. table.concat(role.owns, ", "))\nend',
    },
    {
      name: 'group',
      doc: 'This server\'s group: the game or role it\'s one copy of (`network.group` in `config.yml`, `"arena"`), which other servers send players and requests to (`{ group = "arena" }`) and the hub picks an instance of. A server with no group is a group of one, named as the server is, so this is `server_name()` then. `nil` on a server that isn\'t part of a network.',
      requires: 'network',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_accepting',
      doc: "Says whether this server takes players now: one that doesn't (a match is running) is passed over when players are sent to its group, though they can still be sent to it by name. It's said to the hub at once, and again whenever this server reconnects. Every server takes players until it says otherwise.",
      requires: 'network',
      params: [{ name: 'accepting', type: 'boolean', doc: 'Whether it takes players.' }],
      returns: [],
      example: 'nf.on("match_started", function()\n  nf.network.set_accepting(false)\nend)',
    },
    {
      name: 'state',
      doc: "Where this server is in its life: `starting` until its project has loaded and its modules have started (and nothing holds it back: `hold_ready`), then `ready`; `draining` when it's asked to take no new players (`/nf drain`, the hub's admin API, `set_draining`), and `stopping` as `/nf shutdown` sends its players elsewhere. Only a `ready` server is sent players by its group; a `draining` or `stopping` one gets none, even by name. `network_state` says when it changes.",
      requires: 'network',
      params: [],
      returns: [{ type: '"starting"|"ready"|"draining"|"stopping"' }],
    },
    {
      name: 'hold_ready',
      doc: 'Keeps this server `starting` (so its group sends it no players) until the hold is released: for a world to be made, a map loaded, a match set up. The server is `ready` once every hold is released, the first tick after. Holds go with the script that took them. Once a server is ready it stays so: a hold taken later does nothing.',
      requires: 'network',
      params: [],
      returns: [{ type: 'NetworkHold' }],
      example:
        'local hold = nf.network.hold_ready()\nnf.worlds.copy("arena", "match", function(world)\n  hold:release()\nend)',
    },
    {
      name: 'set_draining',
      doc: "Drains this server (`true`): it takes no new players, even sent by name, while it finishes what it's doing; or (`false`) makes it `ready` again. As `/nf drain` does. `/nf shutdown` drains too, and can't be undone.",
      requires: 'network',
      params: [{ name: 'draining', type: 'boolean', doc: 'Whether to drain.' }],
      returns: [],
    },
    {
      name: 'is_draining',
      doc: 'Whether this server is draining or stopping: taking no new players.',
      requires: 'network',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_accepting',
      doc: 'Whether this server takes players now, as `set_accepting` last said.',
      requires: 'network',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_connected',
      doc: "Whether this server is connected to its network's hub now. `network_connect` and `network_disconnect` say when that changes.",
      requires: 'network',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'servers',
      doc: "Every server of the network, as the proxy names them, with whether NetherForge on each is connected, how many players are on it, its group and its room. Empty while this server isn't connected. Players and room are as the hub last said, once a second at most.",
      requires: 'network',
      params: [],
      returns: [{ type: 'NetworkServer[]' }],
      example:
        'for _, server in ipairs(nf.network.servers()) do\n  log(server.name, server.connected and "up" or "down", server.players .. " online")\nend',
    },
    {
      name: 'groups',
      doc: "Every group of the network, with its instances: each server with a group is an instance of it, and each server with none is a group of one. Empty while this server isn't connected.",
      requires: 'network',
      params: [],
      returns: [{ type: 'NetworkGroup[]' }],
      example:
        'for _, group in ipairs(nf.network.groups()) do\n  log(group.name, group.players .. " playing on " .. #group.instances .. " servers")\nend',
    },
    {
      name: 'players',
      doc: "Everyone online in the network, on any server, this one included, as the proxy last said; or only those of one server or group. Empty while this server isn't connected. On a server that doesn't see other servers' players (`server_role().presence`), only its own.",
      requires: 'network',
      params: [
        {
          name: 'options',
          type: 'NetworkPlayersOptions',
          doc: "Only one server's or group's players.",
          optional: true,
        },
      ],
      returns: [{ type: 'NetworkPlayer[]' }],
      example: 'local playing = #nf.network.players({ group = "arena" })',
    },
    {
      name: 'find_player',
      doc: 'A player online anywhere in the network, by name (any case) or UUID; `nil` when nobody by that name or UUID is.',
      requires: 'network',
      params: [{ name: 'player', type: 'string', doc: 'Their name, or their UUID as a string.' }],
      returns: [{ type: 'NetworkPlayer?' }],
      example:
        'local friend = nf.network.find_player("Ann")\nif friend then\n  player:send_message("Ann is on " .. friend.server)\nend',
    },
    {
      name: 'channel',
      doc: "A channel: what's `publish`ed on it reaches every server whose scripts listen to its `message` event (this one included), and a `request` on it reaches one server's `request` handlers. Getting it subscribes nothing: a server hears a channel's messages while any of its scripts has a handler on it.",
      requires: 'network',
      params: [CHANNEL],
      returns: [{ type: 'NetworkChannel' }],
      example:
        'local chat = nf.network.channel("chat")\nchat:on("message", function(event)\n  nf.server.broadcast("<gray>[" .. event.server .. "]</gray> " .. event.value.text)\nend)\nnf.on("player_chat", function(event)\n  chat:publish({ text = event.player:name() .. ": " .. event.message })\nend)',
    },
    {
      name: 'map',
      doc: 'A shared map: keys (strings) and values the hub keeps for the whole network, which every server reads at once from its own copy and writes through the hub. The hub saves them, so they outlive any server and the proxy restarting. This server keeps a copy from the first time any script gets the map until the project reloads: `is_ready()` once the hub has sent it, and `change` events after.',
      requires: 'network',
      params: [MAP],
      returns: [{ type: 'NetworkMap' }],
    },
    asyncFunction({
      name: 'send_player',
      doc: "Sends a player to another server of the network, through the proxy, with `data` for that server's scripts: its `player_arrive` handlers hear it once they've joined there. The player may be on any server, this one or another (a server whose credential isn't trusted sends only its own). Sent to a group, the hub picks the instance as the group's balancing says (`least`: the one with the fewest players that has room; `fill`: the fullest that still has room) or `target.balance`, passing over instances that don't take players now (`set_accepting`) or are full (`network.capacity`). It fails (`nil, err`) when this server isn't connected, the player isn't online, there's no such server or group, no instance has room, they're already there, or the proxy couldn't move them (the server is down, a proxy plugin cancelled it).",
      requires: 'network',
      params: [
        {
          name: 'player',
          type: 'Player|string',
          doc: 'Who: a player on this server, or anyone online in the network by name or UUID.',
        },
        TARGET,
        SEND_DATA,
      ],
      value: { name: 'server', type: 'string', doc: 'the server they are on their way to' },
      example:
        'nf.task(function()\n  local server, err = nf.network.send_player(player, { group = "arena", data = { team = "red" } })\n  if not server then\n    player:send_message("<red>Can\'t go: " .. err)\n  end\nend)',
    }),
    asyncFunction({
      name: 'send_players',
      doc: "Sends several players together, as `send_player` sends one: to one server, or to one instance of a group with room for all of them, so a party lands together. Players already there stay. It fails as `send_player` does; when the proxy couldn't move some of them, `err` names each with why (the others are on their way).",
      requires: 'network',
      params: [
        {
          name: 'players',
          type: '(Player|string)[]',
          doc: 'Who: players on this server, or anyone online in the network by name or UUID.',
        },
        TARGET,
        SEND_DATA,
      ],
      value: { name: 'server', type: 'string', doc: 'the server they are on their way to' },
      example:
        'local function start_match(players)\n  nf.task(function()\n    local server, err = nf.network.send_players(players, { group = "arena", data = { mode = "duel" } })\n    if not server then\n      for _, player in ipairs(players) do\n        player:send_message("<red>No room: " .. err)\n      end\n    end\n  end)\nend\n\nreturn start_match',
    }),
  ],
}

const channelEvents: EventSpec[] = [
  {
    name: 'message',
    doc: 'Something was published on the channel, by any server of the network (this one included): to every server, or to this one alone. While a script of this server has a handler on it, the server listens to the channel; a message published while it had none never arrives.',
    payload: 'NetworkMessageEvent',
  },
  {
    name: 'request',
    doc: 'Another server (or this one) asked this server on the channel (`channel:request`): assign `event.reply` the answer, which goes back as it is once the handlers have run. Handlers run in order and the last value assigned is the answer; with none assigned the asker gets `nil`. Answer in the handler: the asker is waiting.',
    payload: 'NetworkRequestEvent',
    writable: ['reply'],
    example:
      'local price_list = { diamond = 42, emerald = 30 }\nlocal prices = nf.network.channel("prices")\nprices:on("request", function(event)\n  event.reply = price_list[event.value]\nend)',
  },
]

/** A channel, from `nf.network.channel`. */
export const networkChannelClass: LuaClass = {
  name: 'NetworkChannel',
  doc: 'A channel of the network, from `nf.network.channel`: publish on it with `publish`, ask one server with `request`, and hear messages and requests as its `message` and `request` events. A server listens to a channel while any of its scripts has a handler on it; handlers go with their scripts, as any handler does.',
  methods: true,
  handle: { key: [{ name: 'name', type: 'string' }] },
  fields: [],
  events: channelEvents,
  functions: [
    {
      name: 'name',
      doc: "Its name, as the calling package names it: bare when it's the caller's own, `namespace:name` otherwise.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'publish',
      doc: `Publishes \`value\` on the channel: every server listening hears it as a \`message\` event, this one included, or only \`options.server\`, or only the instances of \`options.group\`. It goes at once, through the hub; nothing waits for it. ${VALUES}`,
      params: [
        { name: 'value', type: 'any', doc: 'What to send.' },
        {
          name: 'options',
          type: 'NetworkPublishOptions',
          doc: 'Which server or group alone, if not every one.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when it didn't go: this server isn't connected, or its package went over `network.messages-per-minute`.",
        },
      ],
      example: 'nf.network.channel("alerts"):publish({ text = "Restarting in 5 minutes" })',
    },
    asyncFunction({
      name: 'request',
      doc: `Asks a server on this channel, or one instance of a group: its \`request\` handlers answer, and the answer comes back here. It fails (\`nil, err\`) when this server isn't connected, that server isn't (or no instance of the group is), nothing there handles requests on the channel, a handler there errored, or no answer came within \`network.request-timeout-seconds\`. A handler that answers \`nil\` gives \`nil\` with no \`err\`. ${VALUES}`,
      params: [
        REQUEST_TARGET,
        {
          name: 'data',
          type: 'any',
          doc: 'What to ask with: their handlers read it as `event.value`.',
        },
      ],
      value: {
        name: 'reply',
        type: 'any',
        doc: "the answer, what that server's handlers assigned to `event.reply`",
      },
      example:
        'nf.task(function()\n  local price, err = nf.network.channel("prices"):request("survival", "diamond")\n  if err then\n    log("survival didn\'t answer: " .. err)\n  else\n    log("a diamond costs", price)\n  end\nend)',
    }),
    ...eventFunctions(
      'NetworkChannel',
      'channel',
      'local alerts = nf.network.channel("alerts")\nalerts:on("message", function(event)\n  nf.server.broadcast("<red>" .. event.value.text)\nend)',
    ),
  ],
}

/** A hold on this server's being ready, from `nf.network.hold_ready`. */
export const networkHoldClass: LuaClass = {
  name: 'NetworkHold',
  doc: 'A hold on this server being ready, from `nf.network.hold_ready`: while any is held, the server stays `starting`. It goes with the script that took it.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'release',
      doc: 'Lets go of the hold. The server is ready the tick after its last hold goes. Releasing it again does nothing.',
      params: [],
      returns: [],
    },
    {
      name: 'is_released',
      doc: 'Whether it has been let go of.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

const mapEvents: EventSpec[] = [
  {
    name: 'change',
    doc: "A key of the map changed, on any server of the network (this one included), or expired: heard on every server that has the map, once the hub has made the change, in the order the hub made changes. This server's copy has the new value when it's heard.",
    payload: 'NetworkMapChangeEvent',
    example:
      'local scores = nf.network.map("scores")\nscores:on("change", function(event)\n  log(event.key .. " is " .. tostring(event.value) .. " (" .. event.cause .. " on " .. tostring(event.server) .. ")")\nend)',
  },
  {
    name: 'ready',
    doc: "This server's copy of the map arrived from the hub for the first time, so `get` and `all` answer with what the network holds. Heard once.",
    payload: 'NetworkMapReadyEvent',
  },
]

const EXPIRES: Param = {
  name: 'options',
  type: 'NetworkMapWriteOptions',
  doc: 'When the key expires, if it should.',
  optional: true,
}

/** A shared map, from `nf.network.map`. */
export const networkMapClass: LuaClass = {
  name: 'NetworkMap',
  doc: "A map the whole network shares, from `nf.network.map`: string keys, any values. Reads (`get`, `all`) answer at once from this server's copy. Writes go to the hub, which makes them in the order they arrive and tells every server that has the map: `set` and `delete` don't wait, and this server's copy has the new value once the hub has made the change (the next tick or so, when `change` is heard); `add` and `insert` wait for the hub's answer, so they're safe when two servers write one key at once. A key may expire. The hub keeps them for the whole network, saved on the proxy, within the limits of its `hub.properties` (how many keys and bytes each package may keep).",
  methods: true,
  handle: { key: [{ name: 'name', type: 'string' }] },
  fields: [],
  events: mapEvents,
  functions: [
    {
      name: 'name',
      doc: "Its name, as the calling package names it: bare when it's the caller's own, `namespace:name` otherwise.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'is_ready',
      doc: "Whether this server's copy has arrived from the hub: until then `get` answers `nil` and `all` an empty table. Its `ready` event says when.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'get',
      doc: "A key's value in this server's copy, or `nil` when it has none (or the copy hasn't arrived). A table comes back new each call: changing it changes nothing.",
      params: [{ name: 'key', type: 'string', doc: 'The key.' }],
      returns: [{ type: 'any' }],
    },
    {
      name: 'all',
      doc: "Every key and its value in this server's copy.",
      params: [],
      returns: [{ type: 'table<string, any>' }],
    },
    {
      name: 'set',
      doc: `Sets a key, for the whole network: through the hub, without waiting. ${VALUES} A key is 1 to 256 characters. What the hub refuses (a package over its limits) is logged.`,
      params: [
        { name: 'key', type: 'string', doc: 'The key.' },
        { name: 'value', type: 'any', doc: 'Its value. `nil` deletes it, as `delete` does.' },
        EXPIRES,
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when it didn't go: this server isn't connected, or its package went over `network.messages-per-minute`.",
        },
      ],
      example:
        'local parties = nf.network.map("parties")\nparties:set(player:id(), { leader = player:name() }, { expires = 20 * 60 * 30 })',
    },
    {
      name: 'delete',
      doc: 'Deletes a key, for the whole network: through the hub, without waiting.',
      params: [{ name: 'key', type: 'string', doc: 'The key.' }],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when it didn't go: this server isn't connected, or its package went over `network.messages-per-minute`.",
        },
      ],
    },
    asyncFunction({
      name: 'add',
      doc: "Adds `amount` to a key's number, made by the hub in one step: two servers adding at once both count. A key with no value starts at 0; one holding something that isn't a number fails. It keeps the expiry it had, unless `options.expires` gives a new one.",
      params: [
        { name: 'key', type: 'string', doc: 'The key.' },
        { name: 'amount', type: 'number', doc: 'What to add; negative to take away.' },
        EXPIRES,
      ],
      value: { name: 'total', type: 'number', doc: "the key's value after adding" },
      example:
        'nf.task(function()\n  local total = nf.network.map("stats"):add("games_played", 1)\n  log("games played across the network:", total)\nend)',
    }),
    asyncFunction({
      name: 'insert',
      doc: "Sets a key to `data` only if it has no value, made by the hub in one step: of two servers inserting one key at once, exactly one gets `true`. A claim, a lock or a reservation: give it `options.expires` so a server that stops holding it can't keep it forever.",
      params: [
        { name: 'key', type: 'string', doc: 'The key.' },
        { name: 'data', type: 'any', doc: `Its value. ${VALUES}` },
        EXPIRES,
      ],
      value: {
        name: 'inserted',
        type: 'boolean',
        doc: '`true` when it was set, `false` when the key already had a value',
      },
      example:
        'nf.task(function()\n  local mine = nf.network.map("arenas"):insert("arena_1", nf.network.server_name(), { expires = 20 * 60 })\n  if mine then\n    log("arena_1 is this server\'s for a minute")\n  end\nend)',
    }),
    ...eventFunctions(
      'NetworkMap',
      'map',
      'local scores = nf.network.map("scores")\nscores:on("change", function(event)\n  log(event.key, "is now", event.value)\nend)',
    ),
  ],
}

const shape = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

const event = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  ...shape(name, doc, fields),
  extends: 'Event',
})

/** The tables `nf.network` takes and gives, and its handles' events. */
export const networkShapes: LuaClass[] = [
  shape('NetworkServer', 'A server of the network, from `nf.network.servers`.', [
    { name: 'name', type: 'string', doc: 'Its name, as the proxy names it.' },
    {
      name: 'connected',
      type: 'boolean',
      doc: 'Whether NetherForge on it is connected to the hub now (a server without NetherForge never is).',
    },
    { name: 'players', type: 'integer', doc: 'How many players are on it.' },
    {
      name: 'group',
      type: 'string',
      doc: 'Its group (`network.group` in its `config.yml`), or its own name when it has none.',
    },
    {
      name: 'capacity',
      type: 'integer?',
      doc: 'How many players it takes (`network.capacity`); `nil` for any number.',
    },
    {
      name: 'accepting',
      type: 'boolean',
      doc: 'Whether it takes players now (`set_accepting` there).',
    },
    {
      name: 'state',
      type: '"starting"|"ready"|"draining"|"stopping"|nil',
      doc: "Where it is in its life (`nf.network.state()` there); `nil` when it isn't connected.",
    },
    {
      name: 'idle',
      type: 'boolean',
      doc: 'Whether it has been empty longer than its `network.idle-after-seconds`.',
    },
  ]),
  shape('NetworkGroup', 'A group of the network and its instances, from `nf.network.groups`.', [
    { name: 'name', type: 'string', doc: 'Its name.' },
    { name: 'players', type: 'integer', doc: 'How many players are on its instances together.' },
    {
      name: 'capacity',
      type: 'integer?',
      doc: 'How many its instances take together; `nil` when one of them takes any number.',
    },
    { name: 'instances', type: 'NetworkServer[]', doc: 'Its servers, by name.' },
  ]),
  shape(
    'NetworkPlayersOptions',
    "What `nf.network.players` takes. A key that isn't one of these is an error.",
    [
      { name: 'server', type: 'string?', doc: "Only this server's players." },
      { name: 'group', type: 'string?', doc: "Only the players on this group's instances." },
    ],
  ),
  shape(
    'NetworkTarget',
    "A server or a group, where a request goes. A key that isn't one of these is an error; it names one of the two.",
    [
      { name: 'server', type: 'string?', doc: 'A server, by its name in the network.' },
      { name: 'group', type: 'string?', doc: 'A group: one of its instances is asked.' },
    ],
  ),
  shape(
    'NetworkSendOptions',
    "Where `send_player` and `send_players` send players. A key that isn't one of these is an error; it names a server or a group.",
    [
      { name: 'server', type: 'string?', doc: 'A server, by its name in the network.' },
      {
        name: 'group',
        type: 'string?',
        doc: 'A group: the hub picks the instance with room.',
      },
      {
        name: 'balance',
        type: '"least"|"fill"|nil',
        doc: "How the hub picks the group's instance for this send: `least` (the fewest players) or `fill` (the fullest with room); the group's own when left out (`group.<name>.balance` in the hub's `hub.properties`, `least` unless its owner says).",
      },
      { name: 'data', type: 'any', doc: "What arrives with them, when `data` isn't given." },
      {
        name: 'wait',
        type: 'number?',
        doc: "Seconds it may wait, when there's no instance with room yet (none is ready, all are full, the server is starting): the hub holds it, first come first served, tells whatever runs the servers that the group is wanted (its webhook), and sends them as soon as one has room. They stay where they are meanwhile. At most 600; it fails after that, saying why it waited.",
      },
      {
        name: 'on_wait',
        type: 'fun(position: integer)?',
        doc: "Called while it waits, each time its place in the queue changes (1 is next). Without it, each player waiting who's on this server sees their place on their action bar.",
      },
    ],
  ),
  shape('NetworkRole', 'What this server may do in the network, from `nf.network.server_role`.', [
    {
      name: 'role',
      type: '"trusted"|"standard"|"isolated"',
      doc: "Its credential's role: `trusted` (everything), `standard` (its own namespaces and what's shared with it, seeing everyone) or `isolated` (only what's shared with it, seeing only its own players).",
    },
    {
      name: 'trusted',
      type: 'boolean',
      doc: 'Whether it may do anything: every channel and map, every player.',
    },
    {
      name: 'owns',
      type: 'string[]',
      doc: 'The namespaces whose channels and maps it has whole: reading, writing, asking and answering.',
    },
    {
      name: 'shares',
      type: 'NetworkShare[]',
      doc: "What the network shares with it of channels and maps it doesn't own.",
    },
    {
      name: 'presence',
      type: 'boolean',
      doc: "Whether it sees players on other servers. Without, `players()` and the `network_player_*` events are only this server's: a player joins as they come here and quits as they go.",
    },
    {
      name: 'send_to',
      type: 'string[]?',
      doc: 'Where it may send players, when it may not send them anywhere: server names, and `"back"` for wherever the player came from. A server that isn\'t trusted sends only players who are on it.',
    },
  ]),
  shape(
    'NetworkShare',
    'A channel or map shared with this server, from `nf.network.server_role`.',
    [
      {
        name: 'name',
        type: 'string',
        doc: 'Its full name, `namespace:name`, or a pattern ending in `*` for every name starting so (`events:*`).',
      },
      {
        name: 'access',
        type: '("read"|"write"|"request"|"answer")[]',
        doc: 'What it may do: `read` (hear its messages, keep a copy of the map), `write` (publish, write the map; reading comes with it), `request` (ask on it) and `answer` (be asked on it).',
      },
    ],
  ),
  shape('NetworkPlayer', 'A player online in the network.', [
    {
      name: 'id',
      type: 'string',
      doc: 'Their UUID, as a string: the same as `player:id()` on their server.',
    },
    { name: 'name', type: 'string', doc: 'Their name.' },
    {
      name: 'server',
      type: 'string?',
      doc: "The server they're on; `nil` for a moment while the proxy is moving them.",
    },
  ]),
  shape(
    'NetworkPublishOptions',
    "What `channel:publish` takes. A key that isn't one of these is an error.",
    [
      {
        name: 'server',
        type: 'string?',
        doc: 'Only this server hears it (if it listens), by its name in the network; every server listening when left out.',
      },
      {
        name: 'group',
        type: 'string?',
        doc: "Only this group's instances hear it (those listening).",
      },
    ],
  ),
  shape(
    'NetworkMapWriteOptions',
    "What `map:set`, `add` and `insert` take. A key that isn't one of these is an error.",
    [
      {
        name: 'expires',
        type: 'integer?',
        doc: 'Ticks (20 a second) from now until the hub deletes the key, as a `change` with cause `"expired"`; never when left out. Counted by the hub\'s clock, whatever any server\'s ticks do.',
      },
    ],
  ),
  event('NetworkMessageEvent', 'Something was published on a channel.', [
    { name: 'channel', type: 'NetworkChannel', doc: 'The channel.' },
    { name: 'value', type: 'any', doc: 'What was published.' },
    { name: 'server', type: 'string', doc: 'The server it was published on (maybe this one).' },
  ]),
  event('NetworkRequestEvent', 'A server asked this one on a channel.', [
    { name: 'channel', type: 'NetworkChannel', doc: 'The channel.' },
    { name: 'value', type: 'any', doc: 'What it asked with.' },
    { name: 'server', type: 'string', doc: 'The server asking (maybe this one).' },
    {
      name: 'reply',
      type: 'any',
      doc: `Writable: the answer, sent back once the handlers have run. ${VALUES}`,
    },
  ]),
  event('NetworkMapChangeEvent', 'A key of a shared map changed.', [
    { name: 'map', type: 'NetworkMap', doc: 'The map.' },
    { name: 'key', type: 'string', doc: 'The key.' },
    { name: 'value', type: 'any', doc: 'Its value now; `nil` when it was deleted or expired.' },
    { name: 'previous', type: 'any', doc: "Its value in this server's copy before." },
    {
      name: 'server',
      type: 'string?',
      doc: 'The server that changed it (maybe this one); `nil` when it expired.',
    },
    {
      name: 'cause',
      type: '"set"|"delete"|"expired"',
      doc: 'What changed it: a write that set it (`set`, `add` or `insert`), a `delete` (or `set` to `nil`), or its expiry.',
    },
  ]),
  event('NetworkMapReadyEvent', "A shared map's copy arrived on this server.", [
    { name: 'map', type: 'NetworkMap', doc: 'The map.' },
  ]),
]
