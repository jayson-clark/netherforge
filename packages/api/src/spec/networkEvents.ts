import type { EventSpec, LuaClass } from '../types.ts'

/**
 * The network's events on `nf`: this server connecting to its network, other servers coming and going, players
 * joining, switching and leaving anywhere in it, and a player sent here arriving. Apart from `network.ts` so
 * `events.ts` can list them without importing what imports it.
 */
export const networkEvents: EventSpec[] = [
  {
    name: 'network_connect',
    doc: "This server connected to its network's hub: at start-up, and again after the connection dropped and came back. `nf.network.servers()` and `nf.network.players()` are the network as it is now. What was published while it was away didn't reach it, so this is where to ask again for what it should know.",
    payload: 'NetworkConnectEvent',
    example:
      'nf.on("network_connect", function(event)\n  log("in the network as " .. event.server .. " with " .. #nf.network.players() .. " players online")\nend)',
  },
  {
    name: 'network_disconnect',
    doc: "This server lost its connection to the network's hub: the proxy restarted, or the network between them broke. It dials again by itself, and `network_connect` says when it's back; meanwhile `publish`, `set` and the rest return `false` or fail, and requests waiting for an answer fail.",
    payload: 'NetworkDisconnectEvent',
  },
  {
    name: 'network_server_connect',
    doc: "Another server of the network connected to the hub (it started, or came back after losing its connection): from now on it hears what's published and answers requests.",
    payload: 'NetworkServerEvent',
  },
  {
    name: 'network_server_disconnect',
    doc: "Another server of the network lost its connection to the hub (it stopped, or the network between them broke). Requests to it fail until it's back.",
    payload: 'NetworkServerEvent',
  },
  {
    name: 'network_player_join',
    doc: 'A player joined the network, on any server of it, this one included (where `player_join` is heard too).',
    payload: 'NetworkPlayerEvent',
    example:
      'nf.on("network_player_join", function(event)\n  nf.server.broadcast("<gray>" .. nf.text.escape(event.player.name) .. " joined " .. event.player.server)\nend)',
  },
  {
    name: 'network_player_switch',
    doc: 'A player went from one server of the network to another, whoever moved them (a command on the proxy, a script).',
    payload: 'NetworkPlayerSwitchEvent',
  },
  {
    name: 'network_player_quit',
    doc: 'A player left the network: they were on `event.player.server` and are now on none.',
    payload: 'NetworkPlayerEvent',
  },
  {
    name: 'player_arrive',
    doc: 'A player a script on another server sent here (`nf.network.send_player`) has joined, with the data it sent along. Heard the tick after their `player_join`, so whatever `player_join` set up is there.',
    payload: 'PlayerArriveEvent',
    example:
      'nf.on("player_arrive", function(event)\n  if event.data and event.data.kit == "archer" then\n    event.player:give_item({ kind = "minecraft:bow" })\n  end\nend)',
  },
]

const payload = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  extends: 'Event',
})

/** The network events' payloads. */
export const networkEventShapes: LuaClass[] = [
  payload('NetworkConnectEvent', "This server connected to its network's hub.", [
    { name: 'server', type: 'string', doc: "This server's name in the network." },
  ]),
  payload('NetworkDisconnectEvent', "This server lost its connection to the network's hub.", [
    { name: 'detail', type: 'string', doc: 'What happened, in words, for the log.' },
  ]),
  payload('NetworkServerEvent', 'Another server of the network connected or disconnected.', [
    { name: 'server', type: 'string', doc: 'Its name in the network, as the proxy names it.' },
  ]),
  payload('NetworkPlayerEvent', 'A player joined or left the network.', [
    {
      name: 'player',
      type: 'NetworkPlayer',
      doc: 'Who, and the server they joined or were last on.',
    },
  ]),
  payload('NetworkPlayerSwitchEvent', 'A player went from one server of the network to another.', [
    { name: 'player', type: 'NetworkPlayer', doc: 'Who, and the server they are on now.' },
    { name: 'previous', type: 'string', doc: 'The server they came from.' },
  ]),
  payload('PlayerArriveEvent', 'A player sent here from another server joined.', [
    { name: 'player', type: 'Player', doc: 'The player, on this server now.' },
    { name: 'from', type: 'string', doc: 'The server that sent them.' },
    {
      name: 'data',
      type: 'any',
      doc: 'What the script that sent them gave `send_player`, as `nf.json` would read it back; `nil` when it gave nothing.',
    },
  ]),
]
