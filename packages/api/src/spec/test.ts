import type { EventSpec, LuaClass, Param } from '../types.ts'
import { FACE } from './events.ts'

/**
 * Every event a test can raise: the server-wide ones scripts hear with `nf.on`, but not one
 * only its own script hears (`unload`). Worked out from the events themselves, so a new event
 * is raisable the day it's written.
 */
const raisable = (events: EventSpec[]) =>
  events
    .filter((it) => !it.local)
    .map((it) => JSON.stringify(it.name))
    .join('|')

/** Another server of a test's network, as the `nf.test.network_*` functions take it. */
const SERVER: Param = {
  name: 'server',
  type: 'string',
  doc: "The other server's name, as `nf.test.network_server` added it.",
}

/**
 * `nf.test`: the script test runner's own functions. Only a `*_test.lua` file run by
 * `netherforge test` has it (`testOnly`), never a server.
 */
export const nfTest = (nfEvents: EventSpec[]): LuaClass => ({
  name: 'nf.test',
  testOnly: true,
  doc: "What a test file runs on. A project's tests are the `*_test.lua` files anywhere in it (`tests/shop_test.lua`), run by `netherforge test` (or the editor's Run tests) on a fake server: no Minecraft, no network, a world that holds only what the test puts in it. A test file runs like a module (it can `require` the project's modules, and files beside it) and registers its tests with `nf.test.case`; each test runs alone, on a fresh server with the project loaded and its scripts started, so one can never leave something behind for the next. The file's own code runs once for every test, before it, so it's where to `require` and build what the tests share. Nothing here exists on a real server: `nf.test` is `nil` there.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'case',
      doc: "Registers a test. It passes when `callback` returns, and fails at the line where an `assert` or `error` in it (or in something it calls) went wrong. A script of the project that errors while the test runs fails it too, naming that script and line. Two tests in one file can't have the same name.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'What the test checks, as a sentence: `"greets a new player"`.',
        },
        {
          name: 'callback',
          type: 'fun()',
          doc: 'The test. It runs synchronously: `nf.test.advance` is how time passes in it.',
        },
      ],
      returns: [],
      example:
        'nf.test.case("a player joining is greeted", function()\n  local alex = nf.test.player("Alex")\n  nf.test.advance(1)\n  assert(alex:name() == "Alex")\nend)',
    },
    {
      name: 'advance',
      doc: "Runs the server forward `ticks` ticks (20 are a second), whole ticks at a time: timers fall due, `tick` handlers run, centities tick and mobs think, exactly as on a server, and everything scripts started on other threads finishes first. When it returns, the last tick is done. Each call in those ticks (a handler, a timer, a centity's tick) has its own budget and time limit, as on a server: none of it counts against the test's own, which is for the test's code. One that runs out is reported as on a server, at its file and line. Script errors during it are collected and fail the test when it ends.",
      params: [{ name: 'ticks', type: 'integer', doc: 'At least 1.' }],
      returns: [],
      example:
        'local count = 0\nnf.every(20, function() count = count + 1 end)\nnf.test.advance(60)\nassert(count == 3)',
    },
    {
      name: 'player',
      doc: 'Joins a fake player and returns them: the server raises the join events (`player_join`) as it does for a real one, and the player can then be sent messages, given items, moved and clicked at like any other `Player`. Names are unique: a name already joined gives that player.',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Their name, 3 to 16 letters, digits and `_`: `"Alex"`.',
        },
      ],
      returns: [{ type: 'Player' }],
      example:
        'local alex = nf.test.player("Alex")\nalex:send_message("<green>hi")\nassert(alex:name() == "Alex")',
    },
    {
      name: 'raise',
      doc: 'Raises a server-wide event as the server would, to the scripts: whoever listens to `event` with `nf.on` hears it, and, for an event the server raises about a player, an entity or a world, the handlers on that handle (`player:on("chat")`) hear it first, in the order the server uses. Each can `stop()` or `cancel()` it or set its writable fields. The few events the runtime stages itself (a death, damage, a click on a centity) reach `nf.on` handlers only. `payload` is what the event carries, the same table its handlers read (`event.player`, `event.message`): a field the event does not have is an error naming the fields. What the server would then do with a cancelled event is not simulated: read the result.',
      params: [
        {
          name: 'event',
          type: raisable(nfEvents),
          doc: "The event's name, as `nf.on` takes it.",
        },
        {
          name: 'payload',
          type: 'table',
          doc: 'The payload: its fields by name. Leave it out for an event that has none.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'RaisedEvent',
          doc: 'Whether a handler cancelled it, and the event as the handlers left it.',
        },
      ],
      example:
        'local alex = nf.test.player("Alex")\nlocal result = nf.test.raise("player_chat", { player = alex, message = "hello", format = "<message>" })\nassert(not result.cancelled)\nassert(result.event.message == "hello")',
    },
    {
      name: 'terrain',
      impl: 'lua',
      doc: "A stand-in for a terrain script's `terrain` (what its `...` is), to test worldgen modules on their own: `noise(name):at(...)`, `height`, `area`, `biome`, `seed`, `min_y`, `max_y`, `sea_level` and `plan` as a terrain script has them, each answering what `options` says. `noises` maps a noise's name to its value everywhere, or to a function of the place (`x, y, z`, or `x, z` for a column) giving it; a noise it doesn't name is 0. `height`, `area` and `biome` are a value or a function of the place, `seed`, `min_y`, `max_y` and `sea_level` numbers (defaults 64, `\"default\"`, `minecraft:plains`, 0, -64, 319 and 63). A plan makes each cell once, with `math.random` seeded for that cell. Chunks, structures and scatters aren't stood in for: `netherforge preview` draws the real thing.",
      params: [{ name: 'options', type: 'table', doc: '', optional: true }],
      returns: [{ type: 'table' }],
      example:
        'local landforms = require("worldgen.landforms")\nnf.test.case("the spawn is a meadow", function()\n  landforms.init(nf.test.terrain({ noises = { ranges = 0.9 }, height = 70 }))\n  assert(landforms.kind(0, 0) == "plains")\nend)',
    },
    {
      name: 'click',
      doc: "Clicks for a player as their client would, with what they hold filled in, and raises what the server would for it: `player_interact` (the held project item's `interact` first, and a click on one of the project's blocks reaches it), then, for a right click with an item that nothing cancelled, `player_use_item` (the project item's `use` first). A left click is the attack button at a block or the air: it doesn't break the block (`world:break_block` does that). A click on an entity is `nf.test.raise(\"player_interact_entity\", ...)`. Nothing the game would then do (an item thrown, a door opened) is simulated: read what the handlers did.",
      params: [
        { name: 'player', type: 'Player', doc: 'Who clicks: one online, from `nf.test.player`.' },
        {
          name: 'button',
          type: '"left"|"right"',
          doc: 'Which button: `"right"` uses what they hold.',
        },
        {
          name: 'options',
          type: 'TestClickOptions',
          doc: 'Which hand, and the block clicked. Leave it out for the main hand at the air.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`true` when it went through, `false` when a handler cancelled it.',
        },
      ],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item({ kind = "minecraft:ender_pearl" })\nassert(nf.test.click(alex, "right"))',
    },
    {
      name: 'release',
      doc: 'Lets go of the right button for a player who was using an item (drawing a bow, raising a shield, eating) before it finished, as their client would: raises `player_stop_using_item` with the item in their main hand, or in their off hand when the main one is empty. Like the server, it is heard by the player before `nf.on`. An error when both hands are empty.',
      params: [
        { name: 'player', type: 'Player', doc: 'Who lets go: one online.' },
        {
          name: 'ticks',
          type: 'integer',
          doc: 'How long they held it, in ticks: a bow drawn for 20 is fully drawn. 0 or more.',
        },
      ],
      returns: [],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item({ kind = "minecraft:bow" })\nnf.test.click(alex, "right")\nnf.test.release(alex, 20)',
    },
    {
      name: 'input',
      doc: "The movement keys a player holds now, as their client says when they change: raises `player_input` (the player's `input` first, then `nf.on`). A key left out is not held, so `nf.test.input(player, {})` lets go of every key. It only raises the event: the player doesn't move, sneak or sprint for it.",
      params: [
        { name: 'player', type: 'Player', doc: 'Whose keys: one online.' },
        { name: 'held', type: 'TestInputKeys', doc: 'The keys held down, each `true`.' },
      ],
      returns: [],
      example:
        'local alex = nf.test.player("Alex")\nnf.test.input(alex, { forward = true, jump = true })',
    },
    {
      name: 'shown',
      doc: "A stack as a player's game was last sent it, after the `view` handlers: what their tooltip says, where the server keeps the real stack (read that from `player:inventory()` or `menu:item`). The fake server sends what changed at the end of each tick, as a real one does, and what `player:refresh_items` finds looks different, so `nf.test.advance(1)` after a change. `nil` for an empty slot, or one their game hasn't been sent.",
      params: [
        { name: 'player', type: 'Player', doc: 'Whose game: one online.' },
        {
          name: 'slot',
          type: 'integer|"cursor"',
          doc: 'A slot of their inventory, numbered as `player:inventory()` numbers them; with `menu`, of the window they have open. `"cursor"` for the stack on their cursor.',
        },
        {
          name: 'options',
          type: 'TestShownOptions',
          doc: 'Leave it out for their own inventory.',
          optional: true,
        },
      ],
      returns: [{ type: 'Item?' }],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item(nf.items.create("ruby"))\nnf.test.advance(1)\nlocal shown = assert(nf.test.shown(alex, 0))\nassert(shown.lore ~= nil)',
    },
    {
      name: 'connections',
      doc: "The connections scripts opened with `nf.http.stream` and `nf.http.websocket` in this test, oldest first, ended ones included. In a test nothing reaches the network: each connection is held by the test, which answers it with `nf.test.accept`, talks over it with `nf.test.receive` and `nf.test.sent`, and ends it with `nf.test.disconnect`. Everything else is as on a server: the package must declare the host, the owner's limits apply (the defaults, in a test), and what the test does reaches the scripts at the next tick, so `nf.test.advance(1)` after it.",
      params: [
        {
          name: 'url',
          type: 'string',
          doc: 'Only those whose URL starts with this: `"wss://chat.example.com"`.',
          optional: true,
        },
      ],
      returns: [{ type: '(HttpStream|WebSocket)[]' }],
      example:
        'nf.test.case("the bridge says hello", function()\n  require("bridge")\n  local socket = nf.test.connections("wss://chat.example.com")[1] --[[@as WebSocket]]\n  nf.test.accept(socket)\n  nf.test.advance(1)\n  assert(nf.test.sent(socket)[1] == "hello")\nend)',
    },
    {
      name: 'accept',
      doc: "Answers a connection that's still connecting, as its server would: its `open` event follows at the next tick. A stream opens with `options.status` (default `200`). Accepting one that isn't connecting any more is an error.",
      params: [
        {
          name: 'connection',
          type: 'HttpStream|WebSocket',
          doc: 'One from `nf.test.connections`.',
        },
        {
          name: 'options',
          type: 'TestAcceptOptions',
          doc: 'The status, headers and subprotocol to answer with.',
          optional: true,
        },
      ],
      returns: [],
      example:
        'local feed = nf.test.connections("https://feed.example.com")[1]\nnf.test.accept(feed, { status = 200, headers = { ["content-type"] = "application/x-ndjson" } })',
    },
    {
      name: 'receive',
      doc: 'Sends `text` from the far end. A WebSocket hears it as one `message`; a stream reads it as more of its response, split by its `format` exactly as a server\'s would be (so `"a\\nb\\n"` is two lines, and half a line waits for the rest). It accepts a connection that\'s still connecting first. An error when the connection has ended, or when as many messages as `connections.max-queued-messages` are already waiting for the scripts (advance to let them in).',
      params: [
        {
          name: 'connection',
          type: 'HttpStream|WebSocket',
          doc: 'One from `nf.test.connections`.',
        },
        { name: 'text', type: 'string', doc: 'What the far end sends.' },
      ],
      returns: [],
      example: 'nf.test.receive(feed, \'{"kind":"goal"}\\n\')\nnf.test.advance(1)',
    },
    {
      name: 'sent',
      doc: 'Every message scripts sent on a WebSocket so far, in order, whether or not it had opened (a stream sends nothing: its request body is in its options).',
      params: [{ name: 'connection', type: 'WebSocket', doc: 'One from `nf.test.connections`.' }],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'request',
      doc: "Makes a web request to the server, as a browser or a webhook sender would, and gives its answer: it goes through the routes scripts made with `nf.web.route` and the owner's limits (the defaults, in a test) exactly as a real one does, without a network, whether or not web routes are turned on. When the handler answers later (from a task, a callback), the server runs forward tick by tick until it does, as `nf.test.advance` would, up to `web.handler-timeout-seconds` (then it's `504`).",
      params: [
        { name: 'method', type: 'string', doc: '`"GET"`, `"POST"`, …' },
        {
          name: 'path',
          type: 'string',
          doc: 'The path, namespace and all, with any query: `"/shop/replays/12?full=1"`.',
        },
        {
          name: 'options',
          type: 'TestRequestOptions',
          doc: 'Its headers, body and the address it comes from.',
          optional: true,
        },
      ],
      returns: [{ type: 'TestWebResponse' }],
      example:
        'local answer = nf.test.request("POST", "/shop/hooks/store", {\n  headers = { ["content-type"] = "application/json" },\n  body = nf.json.encode({ order = 7 }),\n})\nassert(answer.status == 204, answer.body)',
    },
    {
      name: 'oauth_token',
      doc: 'What a login flow\'s token endpoint answers in this test: in a test the server doesn\'t call it, and trades every code for this instead (by default `{ access_token = "test-token", token_type = "Bearer" }`). A table without `access_token` makes the login `fail`, with its `error_description` or `error`.',
      params: [
        { name: 'flow', type: 'OAuthFlow', doc: 'The flow.' },
        { name: 'response', type: 'table', doc: "The token endpoint's JSON answer, as a table." },
      ],
      returns: [],
      example:
        'nf.test.oauth_token(flow, { access_token = "abc", refresh_token = "def", expires_in = 3600 })',
    },
    {
      name: 'disconnect',
      doc: 'Ends a connection from the far end, after what it already received: with `options.error` it breaks (reason `"failed"`, `detail` the error), otherwise the server finishes it (`"finished"`, a WebSocket with `options.code` and `options.reason`). Its `end` event follows at the next tick. Disconnecting one that has ended does nothing.',
      params: [
        {
          name: 'connection',
          type: 'HttpStream|WebSocket',
          doc: 'One from `nf.test.connections`.',
        },
        {
          name: 'options',
          type: 'TestDisconnectOptions',
          doc: 'How it ends.',
          optional: true,
        },
      ],
      returns: [],
      example: 'nf.test.disconnect(socket, { code = 4000, reason = "resume" })\nnf.test.advance(1)',
    },
    {
      name: 'stub_wasm',
      doc: "Stands in for a WebAssembly module for the rest of this test, so a test needn't run a heavy one (a chess engine) to check the script around it. In a test modules really run, as on a server; once stubbed, nothing of the module runs: an instance's `call` and `call_text` answer with `stub.functions` (a function the stub doesn't have fails, `nil, err`), `run` with `stub.run`, and a program `start`ed answers each line it's sent with the lines `stub.input` returns, as `output` events at the next tick (closing its input ends it, exit code 0). Each stub function runs on the main thread as the test's code.",
      params: [
        {
          name: 'module',
          type: 'string',
          doc: "The module, as `nf.wasm`'s functions name it.",
        },
        { name: 'stub', type: 'TestWasmStub', doc: 'What answers in its place.' },
      ],
      returns: [],
      example:
        'nf.test.case("the bot plays the engine\'s move", function()\n  nf.test.stub_wasm("stockfish", {\n    input = function(line)\n      if line:match("^go") then\n        return { "bestmove e7e5" }\n      end\n    end,\n  })\n  local game = require("chess").new_game()\n  game:play("e2e4")\n  nf.test.advance(2)\n  assert(game:last_move() == "e7e5")\nend)',
    },
    {
      name: 'network_server',
      doc: 'Adds another server to the test\'s network. In a test this server is part of a network of its own, named `"test"`, whose hub is a real one in the test: every other server is one the test adds and plays, with `network_publish`, `network_received`, `network_answer`, `network_write`, `network_join` and `network_send`. Adding one that\'s there does nothing; the network starts with none.',
      params: [{ name: 'name', type: 'string', doc: 'Its name in the network (`"lobby"`).' }],
      returns: [],
      example:
        'nf.test.case("chat reaches the lobby", function()\n  nf.test.network_server("lobby")\n  local player = nf.test.player("Ann")\n  nf.test.raise("player_chat", { player = player, message = "hi", format = "" })\n  nf.test.advance(1)\n  local said = nf.test.network_received("lobby", "chat")\n  assert(said[1].value.text == "Ann: hi")\nend)',
    },
    {
      name: 'network_publish',
      doc: "Another server publishes `value` on a channel, to every server listening: this one's `message` handlers hear it at the next tick (`nf.test.advance(1)`).",
      params: [
        SERVER,
        {
          name: 'channel',
          type: 'string',
          doc: "The channel's name, as `nf.network.channel` takes it.",
        },
        { name: 'value', type: 'any', doc: 'What it publishes.' },
      ],
      returns: [],
    },
    {
      name: 'network_received',
      doc: "What another server has heard of this test's network so far: every message published on a channel it listens to (it listens to every channel `network_received` or `network_answer` named for it, from when it's named), oldest first.",
      params: [
        SERVER,
        {
          name: 'channel',
          type: 'string',
          doc: "Only this channel's, by its name as `nf.network.channel` takes it; every channel's it listens to when left out.",
          optional: true,
        },
      ],
      returns: [{ type: 'TestNetworkMessage[]' }],
    },
    {
      name: 'network_answer',
      doc: "How another server answers requests on a channel: `callback` gets what it was asked and who asked, and returns the answer (an error in it is the asker's `err`). Requests are answered at the next tick, as a real server's would be.",
      params: [
        SERVER,
        {
          name: 'channel',
          type: 'string',
          doc: "The channel's name, as `nf.network.channel` takes it.",
        },
        {
          name: 'callback',
          type: 'fun(value: any, server: string): any',
          doc: 'Gets the request and the asking server; returns the answer.',
        },
      ],
      returns: [],
      example:
        'nf.test.network_server("bank")\nnf.test.network_answer("bank", "balance", function(name)\n  return name == "Ann" and 100 or 0\nend)',
    },
    {
      name: 'network_request',
      doc: "Another server asks this one on a channel, as `channel:request` there would, and gives this server's answer: the server runs forward tick by tick until its `request` handlers have answered (they answer the tick after, as a real server's would). A request that fails (nothing here handles requests on the channel, a handler errored) is an error at the test's line, saying why.",
      params: [
        SERVER,
        {
          name: 'channel',
          type: 'string',
          doc: "The channel's name, as `nf.network.channel` takes it.",
        },
        {
          name: 'value',
          type: 'any',
          doc: 'What it asks with: the handlers read it as `event.value`.',
        },
      ],
      returns: [{ type: 'any', doc: "What this server's handlers assigned to `event.reply`." }],
      example:
        'nf.test.case("the shop quotes prices to the lobby", function()\n  nf.test.network_server("lobby")\n  assert(nf.test.network_request("lobby", "prices", "diamond") == 42)\nend)',
    },
    {
      name: 'network_write',
      doc: 'Another server sets a key of a shared map (`nil` deletes it): every server with the map hears its `change` at the next tick.',
      params: [
        SERVER,
        { name: 'map', type: 'string', doc: "The map's name, as `nf.network.map` takes it." },
        { name: 'key', type: 'string', doc: 'The key.' },
        { name: 'value', type: 'any', doc: 'Its value, or `nil` to delete it.' },
      ],
      returns: [],
    },
    {
      name: 'network_join',
      doc: "A player joins the network on another server, as the proxy would say: `nf.network.players()` lists them and `network_player_join` is heard at the next tick. Joining them on a server they're not on moves them there (`network_player_switch`). A player on this server is joined with `nf.test.player`, which the network hears of too.",
      params: [
        SERVER,
        {
          name: 'name',
          type: 'string',
          doc: "The player's name: their UUID is worked out from it, as `nf.test.player`'s is.",
        },
      ],
      returns: [{ type: 'NetworkPlayer' }],
    },
    {
      name: 'network_quit',
      doc: 'A player on another server leaves the network: `network_player_quit` is heard at the next tick.',
      params: [{ name: 'name', type: 'string', doc: "The player's name." }],
      returns: [],
    },
    {
      name: 'network_send',
      doc: "Another server sends a player here with `data`, as `nf.network.send_player` there would: they join this server (`player_join`) and, the tick after, its `player_arrive` handlers hear `data`. They needn't be in the network yet.",
      params: [
        SERVER,
        { name: 'name', type: 'string', doc: "The player's name." },
        { name: 'data', type: 'any', doc: 'What arrives with them.', optional: true },
      ],
      returns: [{ type: 'Player' }],
      example:
        'nf.test.case("a sent player gets their kit", function()\n  nf.test.network_server("lobby")\n  local player = nf.test.network_send("lobby", "Ann", { kit = "archer" })\n  nf.test.advance(1)\n  assert(player:inventory():count_item("minecraft:bow") == 1)\nend)',
    },
  ],
})

/** What `nf.test.raise` returns, and what `nf.test.click` and `nf.test.input` take. */
export const testShapes: LuaClass[] = [
  {
    name: 'TestNetworkMessage',
    doc: "A message another server of a test's network heard, from `nf.test.network_received`.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'channel',
        type: 'string',
        doc: "The channel's name, as the calling package names it.",
      },
      { name: 'value', type: 'any', doc: 'What was published.' },
      { name: 'server', type: 'string', doc: 'The server it was published on.' },
    ],
  },
  {
    name: 'TestWasmStub',
    doc: "What `nf.test.stub_wasm` answers a module with. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'functions',
        type: 'table<string, fun(arguments: any[]): any>?',
        doc: "An instance's functions by name: each gets the call's `arguments` and returns its results, a list for `call` (`{ 5 }`) or a string for `call_text`.",
      },
      {
        name: 'run',
        type: '(fun(options: table): WasmRunResult)?',
        doc: "What `module:run` gives: it gets the run's options (`stdin`, `arguments`, `environment`) and returns a result table.",
      },
      {
        name: 'input',
        type: '(fun(line: string, process: WasmProcess): string[]?)?',
        doc: "A started program's answer to each line it's sent: the lines it prints (to its standard output), or `nil` for none.",
      },
    ],
  },
  {
    name: 'TestShownOptions',
    doc: "Where `nf.test.shown` looks. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'menu',
        type: 'boolean?',
        doc: 'Whether `slot` is one of the window they have open, numbered from 0 as `menu:slot` numbers them.',
      },
    ],
  },
  {
    name: 'TestClickOptions',
    doc: "How `nf.test.click` clicks. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'hand',
        type: '"main_hand"|"off_hand"?',
        doc: 'Which hand clicks, and whose item is used. Default `"main_hand"`.',
      },
      {
        name: 'block',
        type: 'Block?',
        doc: 'The block clicked, which must be in a loaded chunk. Default: none, a click at the air.',
      },
      {
        name: 'face',
        type: FACE + '?',
        doc: 'Which face of `block` was clicked. Default `"up"`; only with `block`.',
      },
    ],
  },
  {
    name: 'TestInputKeys',
    doc: "The keys `nf.test.input` holds down: each `true` is held, and one left out (or `false`) isn't. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'forward', type: 'boolean?', doc: 'Forward (W).' },
      { name: 'backward', type: 'boolean?', doc: 'Back (S).' },
      { name: 'left', type: 'boolean?', doc: 'Left (A).' },
      { name: 'right', type: 'boolean?', doc: 'Right (D).' },
      { name: 'jump', type: 'boolean?', doc: 'Jump (space).' },
      { name: 'sneak', type: 'boolean?', doc: 'Sneak (shift).' },
      { name: 'sprint', type: 'boolean?', doc: 'Sprint (control).' },
    ],
  },
  {
    name: 'TestAcceptOptions',
    doc: "How `nf.test.accept` answers. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'status', type: 'integer?', doc: "A stream's status: 100 to 599. Default `200`." },
      {
        name: 'headers',
        type: 'table<string, string>?',
        doc: 'The response headers (names are lowercased). Default: none.',
      },
      {
        name: 'protocol',
        type: 'string?',
        doc: "A WebSocket's chosen subprotocol: one of those it offered. Default: none.",
      },
    ],
  },
  {
    name: 'TestDisconnectOptions',
    doc: "How `nf.test.disconnect` ends a connection. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'code', type: 'integer?', doc: "A WebSocket's close code. Default `1000`." },
      {
        name: 'reason',
        type: 'string?',
        doc: "A WebSocket's close reason, its `end` event's `detail`.",
      },
      {
        name: 'error',
        type: 'string?',
        doc: 'Break it instead: it ends `"failed"`, with this as `detail`.',
      },
    ],
  },
  {
    name: 'TestRequestOptions',
    doc: "What `nf.test.request` sends. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'headers', type: 'table<string, string>?', doc: 'Request headers by name.' },
      { name: 'body', type: 'string?', doc: 'The body. Default none.' },
      { name: 'ip', type: 'string?', doc: 'The address it comes from. Default `"127.0.0.1"`.' },
    ],
  },
  {
    name: 'TestWebResponse',
    doc: 'What `nf.test.request` gives back: the answer as the client got it.',
    methods: false,
    functions: [],
    fields: [
      { name: 'status', type: 'integer', doc: 'The status code.' },
      {
        name: 'headers',
        type: 'table<string, string>',
        doc: 'The response headers by name in lowercase.',
      },
      { name: 'body', type: 'string', doc: 'The body (empty for a `HEAD`).' },
    ],
  },
  {
    name: 'RaisedEvent',
    doc: 'What `nf.test.raise` gives back.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'cancelled',
        type: 'boolean',
        doc: "Whether a handler called `event:cancel()` (always `false` for an event that can't be cancelled).",
      },
      {
        name: 'event',
        type: 'table',
        doc: 'The payload as the handlers left it: a writable field they assigned (`message`) holds what they set.',
      },
    ],
  },
]
