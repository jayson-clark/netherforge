import type { EventSpec, Field, Fn, LuaClass, Raised } from '../types.ts'
import { gameNkEvents } from './gameEvents.ts'
import { networkEvents } from './networkEvents.ts'

const shape = (
  name: string,
  doc: string,
  fields: LuaClass['fields'],
  more: Partial<LuaClass> = {},
): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  ...more,
})

/** A payload: a shape whose table is an event object, with `Event`'s members too. */
const payload = (name: string, doc: string, fields: Field[]): LuaClass =>
  shape(name, doc, fields, { extends: 'Event' })

const player = { name: 'player', type: 'Player', doc: 'Who it was.' }
const click = {
  name: 'click',
  type: '"left"|"right"',
  doc: 'Which kind of click: the left (attack) or right (use) button.',
}
const hand = {
  name: 'hand',
  type: '"main_hand"|"off_hand"',
  doc: 'Which hand: the game asks for each hand in turn.',
}
const blockField = {
  name: 'block',
  type: 'Block',
  doc: 'The block. A handle reads the world live, so what it says depends on when you ask: `state` is what it was when this happened.',
}
/** A block face, as `Block:relative` takes one. */
export const FACE = '"up"|"down"|"north"|"south"|"east"|"west"'
const eventMethod = (name: string, doc: string): Fn => ({
  name,
  impl: 'lua',
  doc,
  params: [],
  returns: [],
})

/** What every handler receives: the payload's fields plus these. */
export const eventShape = shape(
  'Event',
  "What every event handler receives: a table with the event's own fields (its payload, named `<Something>Event` in this reference) and these. What a handler returns means nothing; it changes what happens through `stop`, `cancel` and the event's writable fields. A field the event doesn't list as writable can't be assigned.",
  [
    {
      name: 'name',
      type: 'string',
      doc: 'The event\'s name where the handler is listening: `"click"` on a node, `"centity_click"` on `nf`.',
    },
    {
      name: 'current',
      type: 'any',
      doc: 'The handle whose handler is running (the node, centity, slot, menu, button or dialog), or `nil` in an `nf.on` handler. As an event bubbles it changes; `target` stays what was hit.',
    },
    {
      name: 'cancelled',
      type: 'boolean',
      doc: 'Whether what caused it will be cancelled, as things stand. Read-only: use `cancel()` and `uncancel()`.',
    },
    {
      name: 'stopped',
      type: 'boolean',
      doc: 'Whether a handler called `stop()`. Read-only.',
    },
    {
      name: 'from',
      type: 'string?',
      doc: "On a custom event (`nf.emit`, `centity:emit`), the namespace of the package whose code emitted it; `nil` on the server's own events. Any package may emit any custom event, so a handler that grants something checks `event.from` is a package it trusts (its own namespace, as its `netherforge.json` says). Read-only.",
    },
  ],
  {
    functions: [
      eventMethod(
        'stop',
        'No later handler hears it: none further along its path (a parent node, the centity, `nf`), and none after this one on the same handle.',
      ),
      eventMethod(
        'cancel',
        "Cancels what caused it: the click doesn't move an item, the block isn't broken. Only for cancellable events; on any other, it's an error. A later handler can still `uncancel()`.",
      ),
      eventMethod('uncancel', "Undoes an earlier handler's `cancel()`, or a locked menu's."),
    ],
  },
)

/** Event payloads and the other plain tables the API hands out or takes. */
export const shapes: LuaClass[] = [
  eventShape,
  shape(
    'EventOptions',
    "How `:on` listens. Every key is optional, and each applies only to the events that say so: a key that isn't one of these, or one the event doesn't take, is an error.",
    [
      {
        name: 'every',
        type: 'integer?',
        doc: "For `tick`: run every this many ticks instead of every tick (at least 1). `every = 20` is once a second. Which of those ticks is fixed by the server's tick count and the thing it's on (a centity's handlers all agree, and different centities fall on different ticks; each `nf.on` handler has its own), not by when the handler was added, so the first call can come sooner than `every` ticks.",
      },
    ],
  ),
  payload('SettingChangedEvent', "One of the package's settings changed.", [
    {
      name: 'setting',
      type: 'string',
      doc: "The setting's name, as `netherforge.json` declares it.",
    },
    { name: 'value', type: 'any', doc: 'What it is now, as `nf.config` gives it.' },
    { name: 'previous', type: 'any', doc: 'What it was.' },
  ]),
  payload('DaylightEvent', 'Night is falling or day is breaking in a world.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    {
      name: 'time_of_day',
      type: 'integer',
      doc: "The world's time of day when it was seen, in ticks, as `world:time_of_day()` says: 13000 or a little past at dusk, 23000 or a little past at dawn, or wherever `/time set` put it.",
    },
  ]),
  payload('TickEvent', 'A server tick went by.', [
    {
      name: 'tick',
      type: 'integer',
      doc: 'The server tick it is, the same as `nf.server.tick()`.',
    },
  ]),
  payload(
    'ClickEvent',
    'A player clicked a centity\'s hitbox. Heard by the clicked node, then each node above it, then the centity, then `nf.on("centity_click")`.',
    [
      player,
      {
        name: 'target',
        type: 'Node',
        doc: 'The node whose hitbox was clicked. `event.current` is the node or centity whose handler is running.',
      },
      click,
      {
        name: 'hit_position',
        type: 'Vec3?',
        doc: "Where the player's line of sight met the hitbox, as a world position: what `node:apply_impulse_at` takes. `nil` when the server couldn't tell.",
      },
      {
        name: 'hit_normal',
        type: 'Vec3?',
        doc: "The unit direction straight out of the face that was hit, in world space. `nil` when the server couldn't tell.",
      },
    ],
  ),
  payload('CentityEvent', 'Something happened to a centity instance as a whole.', [
    { name: 'centity', type: 'Centity', doc: 'The instance.' },
  ]),
  payload(
    'CentityNaturalSpawnEvent',
    'A centity is about to appear by itself, from its `spawning` rules, near a player.',
    [
      {
        name: 'centity',
        type: 'string',
        doc: 'Which centity, as the project names it: its folder name under `centities/` (`wisp`), or `acme:wisp` for one a package brings.',
      },
      {
        name: 'location',
        type: 'Location',
        doc: 'Where it will stand, facing included (its yaw is the way the centity turns). Writable: assign another `Location` to put it somewhere else, in any world the server has; the spawner does not check its rules again there.',
      },
      { name: 'player', type: 'Player', doc: 'The player it is appearing near.' },
    ],
  ),
  payload('AnimationEvent', "One of a centity's animations started or ended.", [
    { name: 'centity', type: 'Centity', doc: 'The instance.' },
    { name: 'animation', type: 'string', doc: "The animation's name." },
  ]),
  payload('NodeEvent', "Something happened to a node's physics body.", [
    { name: 'node', type: 'Node', doc: 'The node.' },
  ]),
  payload(
    'CollideEvent',
    "A physics body started touching something it wasn't touching the tick before: the moment of impact, not every tick while it rests there.",
    [
      { name: 'node', type: 'Node', doc: 'The body that hit something.' },
      {
        name: 'other',
        type: 'Node?',
        doc: "The other body when it was one of the same centity's, or `nil` for a block or another centity's hitbox.",
      },
      {
        name: 'hit_position',
        type: 'Vec3',
        doc: 'Where they touched, as a world position.',
      },
      {
        name: 'hit_normal',
        type: 'Vec3',
        doc: 'The unit direction from what it hit towards the body, in world space.',
      },
      {
        name: 'speed',
        type: 'number',
        doc: 'How fast they were closing, in blocks per second: small for a touch, large for a crash. Handy for how loud a sound should be.',
      },
    ],
  ),
  payload('PlayerJoinEvent', 'A player joined the server.', [
    player,
    { name: 'first_join', type: 'boolean', doc: 'Whether they have never played here before.' },
    {
      name: 'message',
      type: 'Text?',
      doc: 'The join message everyone sees, as MiniMessage. Writable: assign another, or `nil` for none.',
    },
  ]),
  payload('PlayerQuitEvent', 'A player left the server.', [
    player,
    {
      name: 'message',
      type: 'Text?',
      doc: 'The leave message everyone sees, as MiniMessage. Writable: assign another, or `nil` for none.',
    },
  ]),
  payload('PlayerChatEvent', 'A player is saying something in chat.', [
    player,
    {
      name: 'message',
      type: 'string',
      doc: 'What they said, as plain text: never MiniMessage, so it can be shown as it is. Writable: assign other text to change what everyone sees.',
    },
    {
      name: 'format',
      type: 'Text',
      doc: 'How the line is shown, as MiniMessage, where `<player>` is the player\'s display name and `<message>` what they said (both shown as they are, never read as tags). Starts as Minecraft\'s own look, `"\\\\<<player>> <message>"`. Writable: assign another, like `"<gray><player>: <white><message>"`.',
    },
  ]),
  payload(
    'PlayerInteractEvent',
    'A player clicked a block or the air with one hand. The game asks for each hand in turn, so one right click can arrive twice: once for the main hand and, unless that did something, once for the off hand.',
    [
      player,
      click,
      { name: 'block', type: 'Block?', doc: 'The block, or `nil` for a click on the air.' },
      {
        name: 'face',
        type: FACE + '?',
        doc: 'Which face of the block was clicked, or `nil` for a click on the air.',
      },
      {
        name: 'item',
        type: 'Item?',
        doc: "What's in the hand the click was with, or `nil` for an empty hand.",
      },
      hand,
    ],
  ),
  payload(
    'BlockBreakEvent',
    'A player is breaking a block: heard by its world, then `nf.on("block_break")`.',
    [
      player,
      blockField,
      {
        name: 'state',
        type: 'string',
        doc: 'Its whole block state as it was being broken, like `"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"`.',
      },
      {
        name: 'drops',
        type: 'Item[]',
        doc: "What it will drop, for the tool they're breaking it with: an empty list for nothing. Writable: assign another list of items (or change this one) to drop those instead.",
      },
      {
        name: 'experience',
        type: 'integer',
        doc: "The experience it will drop (an ore's, say). Writable: assign another to change it.",
      },
    ],
  ),
  payload(
    'BlockPlaceEvent',
    'A player is placing a block: heard by its world, then `nf.on("block_place")`.',
    [
      player,
      blockField,
      {
        name: 'state',
        type: 'string',
        doc: 'The whole block state being placed, like `"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"`.',
      },
      {
        name: 'against',
        type: 'Block',
        doc: 'The block it was placed against: the one they clicked.',
      },
    ],
  ),
  payload('PlayerEvent', 'Something a player did that needs no more said.', [player]),
  payload('PlayerSwapHandsEvent', 'A player pressed the swap-hands key.', [
    player,
    {
      name: 'item',
      type: 'Item?',
      doc: 'What is going to their off hand: what is in their main hand, or in the slot they point at with an inventory screen open.',
    },
    { name: 'off_hand_item', type: 'Item?', doc: 'What is leaving their off hand.' },
    {
      name: 'in_inventory',
      type: 'boolean',
      doc: 'Whether they pressed it over a slot with an inventory screen open, rather than with their hands.',
    },
  ]),
  payload('PlayerMoveEvent', 'A player walked, swam, flew or fell into another block.', [
    player,
    {
      name: 'from',
      type: 'Location',
      doc: 'Where they were, facing included: in the block they are leaving.',
    },
    {
      name: 'to',
      type: 'Location',
      doc: 'Where they are going, facing included: in another block from `from`.',
    },
  ]),
  payload('PlayerTeleportEvent', 'A player is being teleported.', [
    player,
    { name: 'from', type: 'Location', doc: 'Where they are, facing included.' },
    {
      name: 'to',
      type: 'Location',
      doc: "Where they are going, facing included. Writable: assign another `Location` to send them there instead (a world the server doesn't have leaves it as it was).",
    },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"command"`, `"plugin"` (a plugin or a script), `"ender_pearl"`, `"chorus_fruit"`, `"nether_portal"`, `"end_portal"`, `"end_gateway"`, `"spectate"`, `"dismount"`, `"exit_bed"`, `"unknown"`, …',
    },
  ]),
  payload('PlayerChangeWorldEvent', 'A player went from one world to another.', [
    player,
    { name: 'from', type: 'World', doc: 'The world they left.' },
    { name: 'to', type: 'World', doc: 'The world they are in now.' },
  ]),
  payload('PlayerDeathEvent', 'A player died.', [
    player,
    { name: 'killer', type: 'Entity?', doc: 'Who killed them, when someone did.' },
    {
      name: 'cause',
      type: 'string',
      doc: 'What killed them, as Minecraft names it, the same as `EntityDamageEvent`\'s `cause`: `"entity_attack"`, `"fall"`, `"lava"`, `"void"`, …',
    },
    {
      name: 'message',
      type: 'Text?',
      doc: 'The death message everyone sees, as MiniMessage. Writable: assign another, or `nil` for none.',
    },
    {
      name: 'keep_inventory',
      type: 'boolean',
      doc: 'Whether they keep what they carry. Writable: `true` keeps their inventory, and then nothing from it drops (unless `drops` is assigned too).',
    },
    {
      name: 'drops',
      type: 'Item[]',
      doc: 'What they drop where they died. Writable: assign another list of items (or change this one) to drop those instead.',
    },
  ]),
  payload('PlayerRespawnEvent', 'A player is coming back after dying (or leaving the end).', [
    player,
    {
      name: 'location',
      type: 'Location',
      doc: "Where they will appear: their bed, respawn anchor or the world's spawn. Writable: assign another `Location` to bring them back there (a world the server doesn't have leaves it as it was).",
    },
  ]),
  payload('PlayerItemEvent', 'A player did something with an item stack.', [
    player,
    { name: 'item', type: 'Item', doc: 'The item stack.' },
  ]),
  payload('PlayerPickupItemEvent', 'A player is picking up a dropped item.', [
    player,
    { name: 'item', type: 'Item', doc: 'The stack they are picking up.' },
    { name: 'entity', type: 'DroppedItem', doc: 'The dropped item it lies in.' },
  ]),
  payload('PlayerUseItemEvent', 'A player is using the item in one hand.', [
    player,
    { name: 'item', type: 'Item', doc: "What's in that hand." },
    hand,
  ]),
  payload('PlayerSneakEvent', 'A player started or stopped sneaking.', [
    player,
    { name: 'sneaking', type: 'boolean', doc: 'Whether they are sneaking now.' },
  ]),
  payload('PlayerCommandEvent', 'A player typed a command.', [
    player,
    {
      name: 'input',
      type: 'string',
      doc: 'What they typed, without the leading `/`: `"give @s minecraft:diamond 2"`.',
    },
  ]),
  shape(
    'CentityFilter',
    "Which centities `nf.centities.all` lists. Every key is optional; an instance is listed when it matches all of those given. A key that isn't one of these is an error.",
    [
      { name: 'kind', type: 'string?', doc: 'Only instances of this centity: its folder name.' },
      { name: 'world', type: 'World?', doc: 'Only instances in this world.' },
      {
        name: 'near',
        type: 'Vec3?',
        doc: 'Only instances whose position is within `radius` of this point (in any world, unless `world` says which). Needs `radius`.',
      },
      {
        name: 'radius',
        type: 'number?',
        doc: 'How far from `near`, in blocks. Needs `near`.',
      },
    ],
  ),
]

/** How the server raises a `player_*` event: heard by the player first, then `nf`. */
const byPlayer = (name: string, more: Omit<Raised, 'first'> = {}): Raised => ({
  first: { class: 'Player', event: name.slice('player_'.length), field: 'player' },
  ...more,
})

/** How the server raises an event in a world: heard by the world of [field] first, then `nf`. */
const inWorld = (name: string, field: string): Raised => ({
  first: { class: 'World', event: name, field },
})

/** The server-wide events `nf.on` accepts. */
export const nfEvents: EventSpec[] = [
  {
    name: 'tick',
    doc: 'Every server tick, twenty times a second. Keep handlers cheap, or listen less often with `{ every = n }`.',
    payload: 'TickEvent',
    options: ['every'],
    example: 'nf.on("tick", function(event)\n  log("tick", event.tick)\nend, { every = 20 })',
  },
  {
    name: 'unload',
    doc: 'This script is about to stop: a reload, the server stopping, its centity being removed, or its menu window going. Delivered only to the script that registered it, last of all. Put back anything it changed outside itself.',
    local: true,
  },
  {
    name: 'player_join',
    doc: 'A player joined the server. Assign `event.message` to change what everyone is told, or `nil` to say nothing.',
    payload: 'PlayerJoinEvent',
    writable: ['message'],
    example:
      'nf.on("player_join", function(event)\n  event.message = "<yellow>" .. nf.text.escape(event.player:name()) .. " is here"\nend)',
    raised: byPlayer('player_join'),
  },
  {
    name: 'player_quit',
    doc: 'A player left. Assign `event.message` to change what everyone is told, or `nil` to say nothing.',
    payload: 'PlayerQuitEvent',
    writable: ['message'],
    raised: byPlayer('player_quit'),
  },
  {
    name: 'player_chat',
    doc: 'A player is saying something in chat. `event:cancel()` stops anyone seeing it; assign `event.message` to change what they said, or `event.format` to change how the line looks. Handlers run on the main thread while the chat waits for them, so keep them quick.',
    payload: 'PlayerChatEvent',
    cancellable: true,
    writable: ['message', 'format'],
    example:
      'nf.on("player_chat", function(event)\n  event.format = "<gray><player> <dark_gray>»</dark_gray> <white><message>"\nend)',
    raised: byPlayer('player_chat', { watched: true }),
  },
  {
    name: 'player_interact',
    doc: 'A player clicked a block or the air (not an entity), once for each hand the game asks about. `event:cancel()` stops it: no door opens, nothing is placed or used. A right click with an item goes on to `player_use_item` unless it was cancelled.',
    payload: 'PlayerInteractEvent',
    cancellable: true,
  },
  {
    name: 'player_interact_entity',
    doc: "A player right-clicked an entity that isn't part of a centity (those are `centity_click`): after the entity's `interact` and the player's `interact_entity`. `event:cancel()` stops what the click would do.",
    payload: 'PlayerInteractEntityEvent',
    cancellable: true,
  },
  {
    name: 'player_move',
    doc: 'A player moved into another block: walking, swimming, flying, falling, riding. Only moves that change block are heard, not every step or turn of the head, and teleports are `player_teleport` instead. `event:cancel()` puts them back where they were. The server only watches moves while something listens, so it costs nothing otherwise.',
    payload: 'PlayerMoveEvent',
    cancellable: true,
    example:
      'nf.on("player_move", function(event)\n  if event.to.position.y < 0 then\n    event:cancel()\n  end\nend)',
    raised: byPlayer('player_move', { watched: true }),
  },
  {
    name: 'player_teleport',
    doc: 'A player is being teleported: a command, a script, an ender pearl, a portal. `event:cancel()` keeps them where they are; assign `event.to` to send them somewhere else.',
    payload: 'PlayerTeleportEvent',
    cancellable: true,
    writable: ['to'],
    raised: byPlayer('player_teleport'),
  },
  {
    name: 'player_change_world',
    doc: 'A player arrived in another world: through a portal, or teleported there.',
    payload: 'PlayerChangeWorldEvent',
    raised: byPlayer('player_change_world'),
  },
  {
    name: 'player_death',
    doc: 'A player died: before `nf.on("entity_death")` hears the same death. Assign `event.message` to change what everyone is told (or `nil` for nothing), `event.keep_inventory` to let them keep what they carry, and `event.drops` to change what falls.',
    payload: 'PlayerDeathEvent',
    writable: ['message', 'keep_inventory', 'drops'],
    example:
      'nf.on("player_death", function(event)\n  event.keep_inventory = true\n  event.message = "<red>" .. nf.text.escape(event.player:name()) .. " will be back"\nend)',
  },
  {
    name: 'player_respawn',
    doc: 'A player is coming back after dying, or after leaving the end. Assign `event.location` to bring them back somewhere else.',
    payload: 'PlayerRespawnEvent',
    writable: ['location'],
    raised: byPlayer('player_respawn'),
  },
  {
    name: 'player_drop_item',
    doc: 'A player is dropping an item from their inventory. `event:cancel()` keeps it in their inventory.',
    payload: 'PlayerItemEvent',
    cancellable: true,
  },
  {
    name: 'player_pickup_item',
    doc: 'A player is picking up a dropped item. `event:cancel()` leaves it lying there (it is asked again while they stand on it).',
    payload: 'PlayerPickupItemEvent',
    cancellable: true,
  },
  {
    name: 'player_use_item',
    doc: 'A player right-clicked with an item, on a block or the air, after `player_interact`: starting to eat or drink, drawing a bow, throwing a pearl, emptying a bucket, placing a block. `event:cancel()` stops the item being used, but not what the click does to the block (a door still opens).',
    payload: 'PlayerUseItemEvent',
    cancellable: true,
  },
  {
    name: 'player_consume_item',
    doc: 'A player finished eating or drinking something. `event:cancel()` stops it: the item stays and does nothing.',
    payload: 'PlayerItemEvent',
    cancellable: true,
  },
  {
    name: 'player_swap_hands',
    doc: "A player pressed the key that swaps the items in their hands (F). With an inventory screen open (their own, a vanilla one or one of the project's menus) the key swaps the slot they point at with their off hand instead, and this is heard too, after that click's `click_inventory` or `click` handlers and only when they let it through; `event.in_inventory` says which. `event:cancel()` leaves them where they are, so the key can be used for something else.",
    payload: 'PlayerSwapHandsEvent',
    cancellable: true,
    raised: byPlayer('player_swap_hands'),
  },
  {
    name: 'player_sneak',
    doc: 'A player started or stopped sneaking.',
    payload: 'PlayerSneakEvent',
    raised: byPlayer('player_sneak'),
  },
  {
    name: 'player_command',
    doc: "A player typed a command (any command: Minecraft's, a plugin's or a script's), before it runs. `event:cancel()` stops it running.",
    payload: 'PlayerCommandEvent',
    cancellable: true,
    raised: byPlayer('player_command'),
  },
  {
    name: 'block_break',
    doc: "A player is breaking a block: after the world's own `block_break` handlers. `event:cancel()` stops it; assign `event.drops` and `event.experience` to change what it drops.",
    payload: 'BlockBreakEvent',
    cancellable: true,
    writable: ['drops', 'experience'],
    example:
      'nf.on("block_break", function(event)\n  if event.block:kind() == "minecraft:diamond_ore" then\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'block_place',
    doc: "A player is placing a block: after the world's own `block_place` handlers. `event:cancel()` stops it.",
    payload: 'BlockPlaceEvent',
    cancellable: true,
    raised: inWorld('block_place', 'block'),
  },
  {
    name: 'entity_damage',
    doc: "Something is hurting an entity (a player included): after the entity's own `damage` handlers. `event:cancel()` stops it; assign `event.amount` to change how much it takes.",
    payload: 'EntityDamageEvent',
    cancellable: true,
    writable: ['amount'],
    example:
      'nf.on("entity_damage", function(event)\n  if event.cause == "fall" and event.entity:is_player() then\n    event.amount = event.amount / 2\n  end\nend)',
  },
  {
    name: 'entity_death',
    doc: "An entity died (a player included): after the entity's own `death` handlers, and for a player after `player_death`. Assign `event.drops` and `event.experience` to change what it drops.",
    payload: 'EntityDeathEvent',
    writable: ['drops', 'experience'],
  },
  {
    name: 'entity_spawn',
    doc: "An entity is coming into a world (not a player joining, and never the entities centities are drawn with): after the world's own `entity_spawn` handlers. `event:cancel()` stops it.",
    payload: 'EntitySpawnEvent',
    cancellable: true,
    raised: inWorld('entity_spawn', 'world'),
  },
  {
    name: 'world_dusk',
    doc: "Night fell in a world: its time of day went from day (23000 to 12999) into night (13000 to 22999), by the clock or by `/time set`. After the world's own `dusk` handlers.",
    payload: 'DaylightEvent',
    example:
      'nf.on("world_dusk", function(event)\n  log("night falls over", event.world:name())\nend)',
  },
  {
    name: 'world_dawn',
    doc: "Day broke in a world: its time of day went from night (13000 to 22999) into day (23000 to 12999), by the clock or by `/time set`. After the world's own `dawn` handlers.",
    payload: 'DaylightEvent',
  },
  ...gameNkEvents,
  {
    name: 'centity_click',
    doc: "A player clicked any centity: the end of a click's path, after the clicked node, the nodes above it and the centity itself, unless one of them called `event:stop()`.",
    payload: 'ClickEvent',
  },
  {
    name: 'recipe_craft',
    doc: '`nf.recipes.craft` is making a `station` recipe once: its materials are taken, and `event.result` is about to be given. Assign `event.result` to give something else (the same item with a rolled prefix, a stack of a different size). Heard once for each making, so each gets its own; while nobody listens, the makings are given in stacks.',
    payload: 'RecipeCraftEvent',
    writable: ['result'],
    example:
      'nf.on("recipe_craft", function(event)\n  if event.result.item == "copper_sword" then\n    local sword = event.result\n    sword.data = { prefix = "Sharp" }\n    event.result = nf.items.create("copper_sword", sword)\n  end\nend)',
  },
  {
    name: 'item_view',
    doc: "Any stack is being sent to one player's game (their inventory, a window they have open, their cursor): after its project item's own `view` handlers, for a stack of one, and for every other stack too. Assign `event.display_name` and `event.lore` to change how it looks to `event.player` alone; nothing the server keeps changes. While nobody listens, stacks are sent as they are; while someone does, every stack sent is worked out here first, so prefer an item's own `view` when only some items change.",
    payload: 'ItemViewEvent',
    writable: ['display_name', 'lore'],
    example:
      'nf.on("item_view", function(event)\n  if event.player:menu() and event.item.kind == "minecraft:diamond" then\n    event.display_name = "<aqua>Diamond <gray>(sells for 50)"\n  end\nend)',
  },
  {
    name: 'centity_natural_spawn',
    doc: "A centity is about to appear by itself, from the `spawning` block of its `centity.json`: once for each one of a group, before it exists. `event:cancel()` refuses it (it doesn't count against anything, and the spawner tries again later); assign `event.location` to move it. It is asked after the spawner has checked the place against the centity's rules, and before the centity's own `spawn`.",
    payload: 'CentityNaturalSpawnEvent',
    cancellable: true,
    writable: ['location'],
    example:
      'nf.on("centity_natural_spawn", function(event)\n  if event.centity == "wisp" and event.player:name() == "Notch" then\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'menu_open',
    doc: "Someone opened any menu window: after that window's own `open` handlers.",
    payload: 'MenuEvent',
  },
  {
    name: 'menu_close',
    doc: "Someone closed any menu window: after that window's own `close` handlers.",
    payload: 'MenuEvent',
  },
  {
    name: 'menu_click',
    doc: "Someone clicked in any menu window: the end of a click's path, after the slot's and the menu's handlers, unless one of them called `event:stop()`. `event:cancel()` stops the click moving anything.",
    payload: 'MenuClickEvent',
    cancellable: true,
  },
  {
    name: 'dialog_press',
    doc: "Someone pressed any dialog's button: the end of a press's path, after the button's and the dialog's handlers, unless one of them called `event:stop()`.",
    payload: 'DialogPressEvent',
  },
  {
    name: 'setting_changed',
    doc: "Whoever runs the server changed one of this package's settings (see `nf.config`): from the editor, with `/nf settings` or its dialog, or by editing the settings file and running `/nf settings reload`. Only the package's own scripts hear it. A script that listens keeps running and takes the new value from here; one that read a setting without listening is restarted instead, so what it set up with the old value is set up again.",
    payload: 'SettingChangedEvent',
    example:
      'local greeting = nf.config("greeting")\nnf.on("setting_changed", function(event)\n  if event.setting == "greeting" then\n    greeting = event.value\n  end\nend)',
  },
  ...networkEvents,
]

/**
 * `on` and `once` for a class with events (and `emit` where it takes custom ones):
 * written by hand in the prelude's event core, the same for every class.
 */
export function eventFunctions(
  cls: string,
  receiver: string,
  example: string,
  custom = false,
): Fn[] {
  const separator = cls === 'nf' ? '.' : ':'
  const nameType = `${cls}.Event`
  const names = custom
    ? 'A built-in event\'s name, or a custom event\'s: any name with a `:` in it, like `"shop:purchased"`.'
    : "The event's name."
  const functions: Fn[] = [
    {
      name: 'on',
      impl: 'lua',
      doc: `Calls \`handler\` every time \`event\` happens${cls === 'nf' ? '' : ' to this'}, until the subscription is cancelled or its script unloads${cls === 'nf' ? '' : ', or this goes away'}. A handler lives as long as both the script that registered it and the handle it's on. Handlers run in the order they were registered, each with the event object; what one returns means nothing. A handler that errors is logged and the rest still run; after 20 errors in a row it's cancelled. An event this ${cls === 'nf' ? 'table' : 'class'} doesn't have is an error, so a typo is caught where it was made.`,
      params: [
        { name: 'event', type: nameType, doc: names },
        {
          name: 'handler',
          type: 'fun(event: Event)',
          doc: 'Called with the event: its payload, plus `stop()`, `cancel()` and the rest of `Event`.',
        },
        {
          name: 'options',
          type: 'EventOptions',
          doc: 'How to listen: `{ every = n }` for `tick`.',
          optional: true,
        },
      ],
      returns: [{ type: 'Subscription', doc: 'Call `:cancel()` on it to stop listening.' }],
      example,
    },
    {
      name: 'once',
      impl: 'lua',
      doc: `Like \`${receiver}${separator}on\`, but the handler runs only the next time the event happens, then stops listening.`,
      params: [
        { name: 'event', type: nameType, doc: names },
        { name: 'handler', type: 'fun(event: Event)', doc: 'Called with the event.' },
      ],
      returns: [
        { type: 'Subscription', doc: 'Call `:cancel()` on it to stop waiting before then.' },
      ],
    },
  ]
  if (custom)
    functions.push({
      name: 'emit',
      impl: 'lua',
      doc: `Raises a custom event${cls === 'nf' ? ' for every script' : ' on this centity'}: every \`${receiver}${separator}on(event)\` handler hears it now, in the order they were registered, before this returns. The payload is handed to them as it is, not copied, so a field a handler assigns is there for the caller to read afterwards. A name without a \`:\` is an error: built-in events are the server's to raise.`,
      params: [
        {
          name: 'event',
          type: 'string',
          doc: 'A custom event\'s name, with a `:`: `"shop:purchased"`.',
        },
        {
          name: 'payload',
          type: 'table',
          doc: "The event's fields. Leave it out for none.",
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: 'Whether a handler cancelled it (`event:cancel()`), so a custom event can be vetoed.',
        },
      ],
      example:
        cls === 'nf'
          ? 'if not nf.emit("shop:purchased", { player = player, item = "ruby" }) then\n  player:send_message("<green>Bought!")\nend'
          : 'door:emit("door:open", { by = event.player })',
    })
  return functions
}
