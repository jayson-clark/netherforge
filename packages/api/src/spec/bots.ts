import { asyncFunction } from '../async.ts'
import type { Fn, LuaClass } from '../types.ts'
import { FACE } from './events.ts'

const GONE = "`false` once it has left (or while it's still joining)"

/** What every action that aims at something says about reach. */
const REACH =
  "It must be within reach (3 blocks for an entity) and its client must have been sent it: an entity hidden from the bot, or too far away to be sent, can't be clicked."

const OFF_HAND = {
  name: 'off_hand',
  type: 'boolean',
  doc: 'With what it holds in its off hand. Default `false`.',
  optional: true,
}

const action = (name: string, doc: string, params: Fn['params'] = []): Fn => ({
  name,
  doc,
  requires: 'bots',
  params,
  returns: [{ type: 'boolean', doc: `Whether it did: ${GONE}.` }],
})

/** `nf.bots`: fake players scripts join and drive. */
export const nfBots: LuaClass = {
  name: 'nf.bots',
  doc: "Bots: fake players that scripts join to the server and drive, for opponents and teammates in games, crowds, and anything a player does that an NPC can't. A bot is a real player to the server and to every other plugin: it's on the player list and counted in the player count, its joins, moves, clicks, chat and deaths are heard like anyone's, and it does everything by sending what a player's client would (walking a step a tick, aiming before it clicks), so the game's rules and every other plugin's checks apply to it. Each is a new player every time it joins (a fresh UUID, nothing of it saved: no inventory, statistics or advancements kept), marked as an NPC for other plugins (the `NPC` metadata they check to leave fake players alone), and it loads only the chunks right around it. A bot belongs to the script that joined it and leaves when that script unloads. A package declares `\"requires\": { \"bots\": true }` in its `netherforge.json`, and the server owner allows bots (`bots.enabled` in `config.yml`, with `bots.max` online at once); until then a package that declares them has a warning (`runtime.bots-disabled`). The editor's dev server always allows them. For a figure that stands and looks like a player without being one, spawn a `Mannequin`: it costs far less.",
  methods: false,
  fields: [],
  functions: [
    asyncFunction({
      name: 'join',
      doc: "Joins a bot named `name` to the server, where it goes through everything a player's join does (the server's checks, `player_join`, the resource pack, which it reports loaded without fetching). `nil, err` when bots are off on this server, `bots.max` are online, someone of that name is online, or the server turned it away. A name that isn't 3 to 16 letters, digits or `_` is an error.",
      later: 'It takes ticks: joining loads the world around it',
      requires: 'bots',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Its name, as players see it: 3 to 16 letters, digits or `_`.',
        },
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: "Where it appears: a `Location` (facing its way), or a position in the main world. The main world's spawn when left out.",
          optional: true,
        },
        { name: 'options', type: 'BotJoinOptions', doc: '', optional: true },
      ],
      value: { name: 'bot', type: 'Bot', doc: 'the bot, in the world' },
      example:
        'nf.task(function()\n  local bot, err = nf.bots.join("Sparring", vec3(0.5, 64, 0.5))\n  if not bot then\n    log("no sparring partner: " .. err)\n    return\n  end\n  bot:walk_to(vec3(8.5, 64, 0.5))\nend)',
    }),
    {
      name: 'all',
      doc: 'Every bot online, whoever joined it.',
      requires: 'bots',
      params: [],
      returns: [{ type: 'Bot[]' }],
    },
  ],
}

/** A bot: a player a script drives. */
export const botClass: LuaClass = {
  name: 'Bot',
  doc: "A fake player a script drives (see `nf.bots`): a `Player` with everything players have, and the controls a player's client has. Every action is done as the client would do it, so what it causes is heard like anyone's (`player_move`, `player_interact`, `entity_damage`, …) and a handler that cancels it stops it. Actions that take ticks (`walk_to`, `break_block`) are asynchronous; a new one ends the one before it with an error, as `stop()` does. Every bot online is a `Bot`, those the editor's agents and tests drive included. Once it has left, every action answers `false` (and an asynchronous one `nil, err`).",
  methods: true,
  extends: 'Player',
  fields: [],
  functions: [
    action(
      'leave',
      "Disconnects it, as a player quitting does: `player_quit` is heard, and it's gone for good (a bot never comes back: join a new one).",
    ),
    asyncFunction({
      name: 'walk_to',
      doc: "Walks in a straight line to a position's x and z, as a player holding forward would: a step a tick, jumping up a block in its way, falling down drops and swimming. It doesn't find a way around a wall: it stops, stuck, when it hasn't come closer for a second, and when it's still short after `timeout` ticks.",
      later: 'It takes as many ticks as the walk does',
      requires: 'bots',
      params: [
        { name: 'position', type: 'Vec3', doc: 'Where to: only its x and z matter.' },
        { name: 'options', type: 'BotWalkOptions', doc: '', optional: true },
      ],
      value: { name: 'position', type: 'Vec3', doc: 'where it arrived' },
      example:
        'nf.task(function()\n  local at, err = bot:walk_to(vec3(10, 64, 4), { sprint = true })\n  if not at then\n    bot:chat("I can\'t get there: " .. err)\n  end\nend)',
    }),
    action(
      'stop',
      'Stops what it is doing over several ticks (walking, mining): that action ends with `nil` and an error saying it was stopped. Nothing to stop is fine.',
    ),
    action(
      'look_at',
      "Turns its head to look at a point, as its player would with the mouse: its next click aims there at once. (An `Entity`'s `look_at` turns it from the server's side, which a bot's client hears a tick later.)",
      [{ name: 'point', type: 'Vec3', doc: 'A world position.' }],
    ),
    action('jump', 'Jumps, once it stands on something.'),
    action('set_sneaking', 'Holds sneak, or lets go.', [
      { name: 'sneaking', type: 'boolean', doc: '' },
    ]),
    action('set_sprinting', 'Sprints, or stops: it sprints as it walks.', [
      { name: 'sprinting', type: 'boolean', doc: '' },
    ]),
    action(
      'chat',
      'Says something in chat, as its player typing it. A message starting with `/` is a command it types.',
      [{ name: 'message', type: 'string', doc: '' }],
    ),
    action(
      'attack',
      `Hits an entity or a centity's node (a left click), turning to its middle first, and swings its arm. ${REACH}`,
      [
        {
          name: 'target',
          type: 'Entity|Node',
          doc: 'What to hit: an entity, or a node with a hitbox.',
        },
      ],
    ),
    action(
      'interact',
      `Right-clicks an entity or a centity's node, turning to its middle first (or to \`options.at\`). ${REACH}`,
      [
        {
          name: 'target',
          type: 'Entity|Node',
          doc: 'What to click: an entity, or a node with a hitbox.',
        },
        { name: 'options', type: 'BotInteractOptions', doc: '', optional: true },
      ],
    ),
    action(
      'use_item',
      'Right-clicks with what it holds, at nothing: eats, draws a bow, throws a pearl. `release_item` lets go.',
      [OFF_HAND],
    ),
    action(
      'release_item',
      'Lets go of right-click while using an item: a drawn bow shoots, a raised shield lowers.',
    ),
    action('swing', 'Swings its arm at nothing (a left click at the air).'),
    action(
      'use_block',
      'Right-clicks a face of a block, turning to it first: opens a door or a chest, places what it holds against it. It must be within reach (4.5 blocks).',
      [
        { name: 'position', type: 'Vec3', doc: "The block's position (any point inside it)." },
        { name: 'face', type: FACE, doc: 'Which face. Default `"up"`.', optional: true },
        OFF_HAND,
      ],
    ),
    asyncFunction({
      name: 'break_block',
      doc: "Mines a block, turning to it first, for as long as that takes with what it holds, as a player holding the button does. `nil, err` when there's no block there, it's out of reach (4.5 blocks), or the server didn't let it break.",
      later: 'It takes as many ticks as the block takes to break',
      requires: 'bots',
      params: [
        { name: 'position', type: 'Vec3', doc: "The block's position (any point inside it)." },
      ],
      value: { name: 'broken', type: 'boolean', doc: '`true`' },
    }),
    action('swap_hands', 'Swaps what it holds in its hands (F).'),
    action('drop_item', 'Drops one of what it holds (Q), or the whole stack.', [
      { name: 'stack', type: 'boolean', doc: 'The whole stack. Default `false`.', optional: true },
    ]),
    action(
      'click_slot',
      "Clicks a slot of the menu it has open, or of its inventory with none: the menu's own slots first, then its inventory's, as `slot` numbers them.",
      [
        { name: 'slot', type: 'integer', doc: 'Which slot, from 0.' },
        {
          name: 'click',
          type: '"left"|"right"|"shift_left"|"shift_right"|"drop"|"drop_all"|"double"|"swap_offhand"',
          doc: 'How. Default `"left"`. `"swap_offhand"` is the swap-hands key (F) over the slot.',
          optional: true,
        },
      ],
    ),
    action('respawn', "Presses Respawn on the death screen. `false` when it isn't dead."),
  ],
}

export const botShapes: LuaClass[] = [
  {
    name: 'BotJoinOptions',
    doc: 'How a bot joins (`nf.bots.join`).',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'skin',
        type: 'Skin?',
        doc: "Its skin. Without one, it wears one of the game's default skins.",
      },
    ],
  },
  {
    name: 'BotWalkOptions',
    doc: 'How a bot walks (`bot:walk_to`).',
    methods: false,
    functions: [],
    fields: [
      { name: 'sprint', type: 'boolean?', doc: 'Sprint there. Default `false`.' },
      {
        name: 'timeout',
        type: 'integer?',
        doc: 'Ticks it may take before it gives up, 1 to 500. Default 200.',
      },
    ],
  },
  {
    name: 'BotInteractOptions',
    doc: 'How a bot right-clicks (`bot:interact`).',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'off_hand',
        type: 'boolean?',
        doc: 'With what it holds in its off hand. Default `false`.',
      },
      {
        name: 'at',
        type: 'Vec3?',
        doc: 'A point on it to turn to and click (a square of a board), in the world. Its middle when left out.',
      },
    ],
  },
  {
    name: 'Skin',
    doc: "A skin, as Mojang's session servers sign it: a player profile's `textures` property. Sites that make skins for NPCs (MineSkin, …) give both values.",
    methods: false,
    functions: [],
    fields: [
      { name: 'texture', type: 'string', doc: 'The base64 `value` of the `textures` property.' },
      {
        name: 'signature',
        type: 'string?',
        doc: "Its signature. Players' clients draw an unsigned skin only when the server is in offline mode.",
      },
    ],
  },
]
