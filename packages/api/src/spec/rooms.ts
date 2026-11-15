import type { EventSpec, LuaClass } from '../types.ts'

/**
 * Rooms: one server running several rooms of the project (matches, parties, owners' worlds), each its own Lua state.
 * Inside a room, `nf` is the room. `nf.rooms` is the only way to another room: for games that run their own lobbies,
 * and for the server's owner deciding when a room's match starts and ends.
 */

const ROOM_STATE = '"opening"|"open"|"started"|"finished"|"closed"'

const LATER =
  "It happens at the end of this tick, once every script's tick is done, so a room never changes under a running script."

const TOLD =
  "On a server in a network, the network's hub is told of a room with a key starting, finishing and closing (its admin stream's `room_state`), so a matchmaker that placed players in it knows when its match is under way and when it's gone, with nothing in the game's scripts of its own."

/** A room's events, heard on `nf` in the room itself. */
export const roomEvents: EventSpec[] = [
  {
    name: 'room_start',
    doc: "This room's match began: `room:start()` was called (by this room's scripts, another room's, or the server's owner through the admin API). Before it, the room is open: players can be sent in and the world got ready.",
    payload: 'RoomEvent',
    example:
      'nf.on("room_start", function(event)\n  for _, player in ipairs(nf.players.online()) do\n    player:send_title("<green>Go!")\n  end\nend)',
  },
  {
    name: 'room_finish',
    doc: "This room's match ended: `room:finish()` was called. Its players are still here; the room closes when it's told to (`room:close()`), which is where a game says who won and sends everyone on.",
    payload: 'RoomEvent',
  },
  {
    name: 'room_close',
    doc: "This room is about to close: its scripts' last chance, before its players go back to the main room and its Lua state ends. Afterwards the worlds its scripts made or loaded are unloaded, saved for a kept room (`RoomOptions.keep`) and deleted for the rest, and those it was given are the main room's.",
    payload: 'RoomEvent',
  },
]

/** A room of the server. */
export const roomClass: LuaClass = {
  name: 'Room',
  doc: "A room of the server: one copy of the project with its own Lua state, its own players and its own worlds. The main room is always there (it holds every world and player no other room does, and it's the only one on a server that never opens another); the others are opened by `nf.rooms.open` or the server's owner, and closed once their match is over. A room's scripts reach nothing of another room's except through these methods. Once it has closed its methods answer `nil` or `false`.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'id',
      doc: 'Its number: `0` for the main room, then `1`, `2`... in the order rooms were opened, never used twice while the server runs.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'key',
      doc: "What it's for, as it was opened with it (an owner's id, a match's): `nil` for the main room and for a room opened without one.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'is_kept',
      doc: "Whether what it made outlasts it (`RoomOptions.keep`): its worlds saved when it closes and its `nf.data` tables kept under its key. `false` for the main room, whose worlds and tables are the server's anyway.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_main',
      doc: "Whether it's the main room.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'state',
      doc: '`"opening"` until the end of the tick it was opened in, then `"open"`, `"started"` once its match began, `"finished"` once it ended, and `"closed"` once it has gone. The main room is `"open"` until it starts.',
      params: [],
      returns: [{ type: ROOM_STATE }],
    },
    {
      name: 'players',
      doc: 'The players in it now. Empty once it has closed.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'worlds',
      doc: "The worlds it holds: those it was given or its scripts made or loaded. The main room's are every world no other room holds.",
      params: [],
      returns: [{ type: 'World[]' }],
    },
    {
      name: 'start',
      doc: `Begins its match: its scripts hear \`room_start\` (at the end of this tick for a room still opening, once it has). ${TOLD} \`false\` once it has started already, finished or closed.`,
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'finish',
      doc: "Ends its match: its scripts hear `room_finish`. `false` unless it's open or started.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'close',
      doc: `Closes it: its scripts hear \`room_close\`, its players go back to the main room (as if they'd joined the server), its Lua state ends, the worlds its scripts made or loaded are unloaded (saved for a kept room, deleted for the rest) and those it was given are the main room's. ${LATER} \`false\` for the main room, and once it's closing or closed.`,
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'send',
      doc: `Moves a player into it: their room's scripts hear them quit and this room's hear them join, as if they'd left the server and come back. Their \`player:data()\` goes with them; their \`player:session()\` table is the room's, so it starts empty here. ${LATER} \`false\` when the player isn't online or the room is closing or closed.`,
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean' }],
      example:
        'local room = nf.rooms.open({ key = "match-7" })\nfor _, player in ipairs(nf.players.online()) do\n  room:send(player)\nend',
    },
    {
      name: 'give_world',
      doc: "Gives it a world: the world's blocks, entities and events are this room's from now on. `false` when the world isn't loaded or the room is closed.",
      params: [{ name: 'world', type: 'World', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
  ],
}

/** `nf.rooms`. */
export const nfRooms: LuaClass = {
  name: 'nf.rooms',
  doc: "The server's rooms. Each runs the project in a Lua state of its own, with its own players and worlds, and `nf` in a room is that room: `nf.players.online()`, broadcasts, events, timers, teams and the rest are its own. What's shared between rooms is the store's tables of players and centities, the databases, the network, and these functions.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'current',
      doc: 'The room this script runs in.',
      params: [],
      returns: [{ type: 'Room' }],
    },
    {
      name: 'main',
      doc: 'The main room.',
      params: [],
      returns: [{ type: 'Room' }],
    },
    {
      name: 'all',
      doc: 'Every room open now (opening ones too), the main room first.',
      params: [],
      returns: [{ type: 'Room[]' }],
    },
    {
      name: 'get',
      doc: 'Room `id`, or `nil` when no room open has it.',
      params: [{ name: 'id', type: 'integer', doc: '' }],
      returns: [{ type: 'Room?' }],
    },
    {
      name: 'open',
      doc: `Opens a room: the project starts in it, in a Lua state of its own. ${LATER} It holds no world and no player until it's given them (\`room:give_world\`, \`room:send\`), or its scripts make or load a world.`,
      params: [{ name: 'options', type: 'RoomOptions', doc: '', optional: true }],
      returns: [{ type: 'Room' }],
      example:
        'nf.on("player_join", function(event)\n  local room = nf.rooms.open({ key = "match-" .. event.player:name() })\n  room:send(event.player)\nend)',
    },
  ],
}

const payload = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  extends: 'Event',
})

/** The rooms' shapes: what opening one takes, and their events' payload. */
export const roomShapes: LuaClass[] = [
  payload('RoomEvent', 'Something happened to this room.', [
    { name: 'room', type: 'Room', doc: 'The room: this one.' },
  ]),
  {
    name: 'RoomOptions',
    doc: 'How `nf.rooms.open` opens a room.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'key',
        type: 'string?',
        doc: "What it's for (an owner's id, a match's): the room's identity. A player the server's owner sends to a key goes into the room open with it, and its worlds are named after it. Two rooms can't be open with the same key at once.",
      },
      {
        name: 'keep',
        type: 'boolean?',
        doc: "Whether what it made outlasts it, for a room opened again with the same key to find (an owner's world, a party's): its worlds are saved when it closes rather than deleted, and its `nf.data` tables are kept under its key. A match's room isn't kept (the default): its worlds and `nf.data` tables go with it. A kept room needs a `key`.",
      },
    ],
  },
]
