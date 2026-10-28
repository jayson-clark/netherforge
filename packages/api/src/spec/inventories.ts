import type { Field, LuaClass } from '../types.ts'

const shape = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

const GONE = "`false` once it's gone"

const MATCH_DOC =
  'An item kind (`"minecraft:diamond"`), or a partial `Item` that matches a stack when every field it gives matches: `{ kind = "minecraft:paper", name = "<gold>Ticket" }`. Its `data` matches as a subset, so `{ data = { coin = true } }` finds coins whatever else they carry. `count` and `raw` have no place in a match; a field `Item` doesn\'t have is an error.'

const match = { name: 'match', type: 'string|ItemMatch', doc: MATCH_DOC }

/** A real inventory: a player's, a container block's, an entity's. */
export const inventoryClass: LuaClass = {
  name: 'Inventory',
  doc: "A real inventory: a player's (`player:inventory()`), their ender chest, a storage of the project's that they keep (`player:storage(name, size)`), a container block's (`block:inventory()`: a chest, a barrel, a hopper, a furnace) or an entity's (a chest boat, a donkey, a villager). Its item methods work like `Menu`'s. The handle is where the inventory is, so it reads live; once that's gone (a chest broken, a player offline, a chunk unloaded) its methods answer `nil`, `false` or nothing. Slots are numbered from 0. A player's: the hotbar 0 to 8, the rest of the main inventory 9 to 35, armour 36 (feet), 37 (legs), 38 (chest) and 39 (head), the offhand 40, then body armour 41 and a saddle 42 (what the game keeps there for any entity). A double chest's inventory is both halves, 0 to 53.",
  methods: true,
  // Where it is, as one string: `player/<uuid>`, `ender_chest/<uuid>`, `entity/<uuid>` or `block/<x>/<y>/<z>/<world>`.
  handle: { key: [{ name: 'holder', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'exists',
      doc: "Whether it's there now: the player online, the container still a container, the entity in the world and its chunk loaded.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'kind',
      doc: 'What kind of inventory it is: `"player"`, `"ender_chest"`, `"storage"`, `"chest"`, `"barrel"`, `"shulker_box"`, `"hopper"`, `"dispenser"`, `"dropper"`, `"furnace"`, `"brewing"`, … `nil` once it\'s gone.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'size',
      doc: "How many slots it has: 43 for a player, 27 for a chest, 54 for a double chest. 0 once it's gone.",
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'holder',
      doc: "Whose it is: the `Player` (for a player's inventory, ender chest and storage), the `Entity`, or the `Block`. `nil` once it's gone.",
      params: [],
      returns: [{ type: 'Entity|Block|nil' }],
    },
    {
      name: 'item',
      doc: "What is in a slot, or `nil` for an empty one (or once it's gone). A slot outside it is an error.",
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_item',
      doc: "Puts an item in a slot, replacing what was there, or empties it. A slot outside it, an item table with a misspelled field, or an item the server doesn't have is an error.",
      params: [
        { name: 'index', type: 'integer', doc: 'From 0.' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'items',
      doc: 'Every item in it, keyed by slot. Empty slots are left out, so `pairs` visits only what is there.',
      params: [],
      returns: [{ type: 'table<integer, Item>' }],
      example:
        'for index, item in pairs(player:inventory():items()) do\n  log(index, item.kind, item.count)\nend',
    },
    {
      name: 'add_item',
      doc: "Puts an item wherever it fits, topping up matching stacks first, the way picking something up does. In a player's inventory only the hotbar and main inventory take it, never armour or the offhand.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [
        {
          type: 'integer',
          doc: "How many didn't fit: 0 when all of it went in, all of it once the inventory's gone.",
        },
      ],
    },
    {
      name: 'remove_item',
      doc: 'Takes away items that match, from the first slot on, until `count` are gone or none are left.',
      params: [
        match,
        {
          name: 'count',
          type: 'integer',
          doc: 'How many at most. Leave it out for every one that matches.',
          optional: true,
        },
      ],
      returns: [{ type: 'integer', doc: 'How many it took.' }],
      example:
        'local taken = player:inventory():remove_item({ kind = "minecraft:gold_nugget", data = { coin = true } }, 10)',
    },
    {
      name: 'count_item',
      doc: 'How many items match, across every slot.',
      params: [match],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'has_item',
      doc: 'Whether at least `count` items match.',
      params: [match, { name: 'count', type: 'integer', doc: 'Default 1.', optional: true }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'first_slot',
      doc: 'The first slot holding an item that matches, or `nil` when none does.',
      params: [match],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'clear',
      doc: 'Empties every slot. To empty one, `set_item(index, nil)`.',
      params: [],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'viewers',
      doc: "The players who have it open on their screen now: for a storage, those looking at a window with a slot bound to it. (A player's own inventory, open in their own inventory screen, doesn't count: the server can't tell.)",
      params: [],
      returns: [{ type: 'Player[]' }],
    },
  ],
}

const BAR_COLOR = '"pink"|"blue"|"red"|"green"|"yellow"|"purple"|"white"'
const BAR_STYLE = '"progress"|"notched_6"|"notched_10"|"notched_12"|"notched_20"'

/** A boss bar a script made. */
export const bossBarClass: LuaClass = {
  name: 'BossBar',
  doc: "A bar across the top of players' screens, made by `nf.bossbars.create`. It shows to the players it's shown to (`show_to`), and stays theirs when they leave and come back. It belongs to the script that made it and goes when that script unloads (or `remove()`): from then on its methods answer `nil` or `false`.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'exists',
      doc: "Whether it's still there: `false` once it's removed or its script has unloaded.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'show_to',
      doc: "Shows it to a player, now if they're online and again whenever they join.",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'hide_from',
      doc: 'Stops showing it to a player.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: `Whether they were among its viewers: ${GONE}.` }],
    },
    {
      name: 'viewers',
      doc: "Everyone it's shown to, online or not: by `show_to`, and for an attached bar, whoever is near its centity now. Empty once it's gone.",
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'text',
      doc: "What it says, as MiniMessage, or `nil` once it's gone.",
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_text',
      doc: 'Changes what it says.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'icon',
      doc: "The glyph drawn at its left end, or `nil` when it has none (or it's gone).",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_icon',
      doc: 'Draws a glyph at its left end, or none with `nil`. A glyph no resource pack has is an error.',
      params: [
        {
          name: 'icon',
          type: 'string?',
          doc: '`<pack>/<key>`, like `"ui/boss_king_slime"`, or another loaded package\'s as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)).',
          names: 'glyph',
        },
      ],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'progress',
      doc: "How full it is, 0 to 1, or `nil` once it's gone.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_progress',
      doc: 'Fills it to a fraction. Outside 0 to 1 is an error.',
      params: [{ name: 'progress', type: 'number', doc: '0 to 1.' }],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
      example: 'bar:set_progress(time_left / round_length)',
    },
    {
      name: 'color',
      doc: "Its colour, or `nil` once it's gone.",
      params: [],
      returns: [{ type: `${BAR_COLOR}|nil` }],
    },
    {
      name: 'set_color',
      doc: 'Changes its colour.',
      params: [{ name: 'color', type: BAR_COLOR, doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'style',
      doc: "Whether it's one bar or split into notches, or `nil` once it's gone.",
      params: [],
      returns: [{ type: `${BAR_STYLE}|nil` }],
    },
    {
      name: 'set_style',
      doc: 'Makes it one bar, or splits it into 6, 10, 12 or 20 notches.',
      params: [{ name: 'style', type: BAR_STYLE, doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
    },
    {
      name: 'attach',
      doc: "Ties it to a centity: it shows to every player within `range` of it (in its world) and hides from them as they go, and with `health` its progress follows the centity's health: what a boss's bar does. Players it was shown to with `show_to` keep it. A bar attached again moves to the new centity.",
      params: [
        { name: 'centity', type: 'Centity', doc: '' },
        { name: 'options', type: 'BossBarAttachOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: `${GONE}, or the centity has.` }],
      example:
        'local bar = nf.bossbars.create({ text = "<red>King Slime", color = "blue" })\nbar:attach(this, { range = 64 })',
    },
    {
      name: 'detach',
      doc: 'Unties it from its centity: the players it showed to by being near it stop seeing it.',
      params: [],
      returns: [],
    },
    {
      name: 'attached',
      doc: "The centity it's attached to, or `nil`.",
      params: [],
      returns: [{ type: 'Centity?' }],
    },
    {
      name: 'remove',
      doc: 'Takes it off every screen for good.',
      params: [],
      returns: [{ type: 'boolean', doc: `${GONE} already.` }],
    },
  ],
}

/** One player's sidebar. */
export const sidebarClass: LuaClass = {
  name: 'Sidebar',
  doc: "The panel of lines at the right of one player's screen, from `player:sidebar()`: up to 15 lines of MiniMessage under a title, without the score numbers vanilla shows. Setting its title or lines shows it. A sidebar needs a scoreboard of the player's own, so while it shows NetherForge gives them one that copies the server's teams and the lines under name tags (the project's own changes at once, anyone else's within a second), keeping team colours and name tags working; hiding it gives them the server's back. It's cleared when they leave. For someone offline its methods answer `nil` or `false`.",
  methods: true,
  handle: { key: [{ name: 'player', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'title',
      doc: "Its title, as MiniMessage, or `nil` when it has none (or they're offline).",
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_title',
      doc: 'Changes its title, and shows it.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: "`false` when they're offline." }],
    },
    {
      name: 'lines',
      doc: "Its lines, top first, as MiniMessage. Empty when it has none (or they're offline).",
      params: [],
      returns: [{ type: 'Text[]' }],
    },
    {
      name: 'set_lines',
      doc: 'Replaces its lines, and shows it. More than 15 is an error.',
      params: [
        {
          name: 'lines',
          type: 'Text[]',
          doc: 'MiniMessage, top first. Lines may repeat; an empty string is a blank line.',
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when they're offline." }],
      example:
        'player:sidebar():set_title("<gold><b>Arena")\nplayer:sidebar():set_lines({ "Kills: " .. kills, "", "<gray>play.example.net" })',
    },
    {
      name: 'is_visible',
      doc: "Whether they see it now. `false` when they're offline.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_visible',
      doc: "Shows it, or hides it and gives them the server's scoreboard back, keeping its title and lines for next time.",
      params: [{ name: 'visible', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when they're offline." }],
    },
    {
      name: 'clear',
      doc: "Takes its title and lines away and hides it, giving them the server's scoreboard back.",
      params: [],
      returns: [{ type: 'boolean', doc: "`false` when they're offline." }],
    },
  ],
}

/** `nf.bossbars`. */
export const nfBossbars: LuaClass = {
  name: 'nf.bossbars',
  doc: "Boss bars: bars across the top of players' screens.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'create',
      doc: "Makes a boss bar, shown to no one yet (`bar:show_to(player)`). It belongs to this script and goes when it unloads. A key the options don't have is an error.",
      params: [{ name: 'options', type: 'BossBarOptions', doc: '', optional: true }],
      returns: [{ type: 'BossBar' }],
      example:
        'local bar = nf.bossbars.create({ text = "<red>The Warden", color = "red", progress = 1 })\nfor _, player in ipairs(nf.players.online()) do\n  bar:show_to(player)\nend',
    },
  ],
}

/** The plain tables inventories, boss bars and sidebars take. */
export const inventoryShapes: LuaClass[] = [
  shape(
    'BossBarAttachOptions',
    "How `bossbar:attach` ties a bar to a centity. Every key is optional; a key that isn't one of these is an error.",
    [
      {
        name: 'range',
        type: 'number?',
        doc: 'How near a player must be to see it, in blocks. Default 48.',
      },
      {
        name: 'health',
        type: 'boolean?',
        doc: "Its progress follows the centity's `health` (when it has any). Default `true`.",
      },
      {
        name: 'remove_with',
        type: 'boolean?',
        doc: 'Removed when the centity is. Default `true`.',
      },
    ],
  ),
  shape(
    'BossBarOptions',
    "What `nf.bossbars.create` makes a bar with. Every key is optional; a key that isn't one of these is an error.",
    [
      { name: 'text', type: 'Text?', doc: 'What it says, MiniMessage. Default none.' },
      {
        name: 'color',
        type: `${BAR_COLOR}?`,
        doc: 'Default `"pink"`, the way vanilla bars start.',
      },
      {
        name: 'style',
        type: `${BAR_STYLE}?`,
        doc: 'One bar, or split into notches. Default `"progress"`, one bar.',
      },
      { name: 'progress', type: 'number?', doc: 'How full, 0 to 1. Default 1.' },
      {
        name: 'icon',
        type: 'string?',
        doc: "A glyph (`<pack>/<key>`) drawn just left of the bar's left end, wherever the centred text puts its words: a boss's head, say. It's drawn on the text's line at the glyph's own `ascent`: the bar sits 2 pixels below the text's baseline, so a lower ascent (even a negative one) hangs it down beside the bar. Placing it needs the text's width, so text the server can't measure (no `fonts/default.json`) draws it in front of the words instead. Default none.",
      },
    ],
  ),
]
