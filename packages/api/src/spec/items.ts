import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

/** `then the player's own <event>, then nf.on("player_<event>")`: where an item event goes after the item. */
const thenPlayer = (event: string) =>
  `Then the player's own \`${event}\`, then \`nf.on("player_${event}")\`, with the same event, so \`event:cancel()\` here cancels it for all of them and \`event:stop()\` keeps it from them.`

const itemEvents: EventSpec[] = [
  {
    name: 'use',
    doc: `A player right-clicked with a stack of this item in hand: \`event.item\` is the stack, \`event.hand\` which hand. ${thenPlayer('use_item')} \`event:cancel()\` stops the item being used.`,
    payload: 'PlayerUseItemEvent',
    cancellable: true,
    bubbles: true,
    example:
      'this:on("use", function(event)\n  event.player:send_message("<red>The ruby glows.")\nend)',
  },
  {
    name: 'interact',
    doc: `A player clicked a block or the air holding a stack of this item: \`event.click\` is \`"left"\` or \`"right"\`, \`event.block\` the block (\`nil\` for the air), \`event.item\` the stack. ${thenPlayer('interact')}`,
    payload: 'PlayerInteractEvent',
    cancellable: true,
    bubbles: true,
  },
  {
    name: 'interact_entity',
    doc: `A player right-clicked an entity (not a centity) holding a stack of this item in \`event.hand\`; read the stack with \`event.player:held_item()\` (or \`off_hand_item()\`). The entity's own \`interact\` handlers hear it first, then this item's. ${thenPlayer('interact_entity')}`,
    payload: 'PlayerInteractEntityEvent',
    cancellable: true,
    bubbles: true,
  },
  {
    name: 'consume',
    doc: `A player finished eating or drinking a stack of this item (\`event.item\`). ${thenPlayer('consume_item')}`,
    payload: 'PlayerItemEvent',
    cancellable: true,
    bubbles: true,
  },
  {
    name: 'hit',
    doc: 'A player is attacking an entity with a stack of this item in their main hand: `event.attacker` is the player, `event.entity` who is hit, `event.amount` the damage (writable). Read the stack with `event.attacker:held_item()`. Then the entity\'s own `damage`, then `nf.on("entity_damage")`, with the same event.',
    payload: 'EntityDamageEvent',
    cancellable: true,
    bubbles: true,
    writable: ['amount'],
    example: 'this:on("hit", function(event)\n  event.amount = event.amount * 2\nend)',
  },
  {
    name: 'break_block',
    doc: 'A player is breaking a block with a stack of this item in their main hand (read it with `event.player:held_item()`). Then the world\'s own `block_break`, then `nf.on("block_break")`, with the same event; assign `event.drops` and `event.experience` to change what it drops.',
    payload: 'BlockBreakEvent',
    cancellable: true,
    bubbles: true,
    writable: ['drops', 'experience'],
  },
  {
    name: 'drop',
    doc: `A player is dropping a stack of this item (\`event.item\`). ${thenPlayer('drop_item')}`,
    payload: 'PlayerItemEvent',
    cancellable: true,
    bubbles: true,
  },
  {
    name: 'render',
    doc: "The server is making a stack of this item, or rewriting one to the item's file after it changed: assign `event.display_name` and `event.lore` to give it a name and lore worked out from the stack (its `data`, its count), which the file's can't say: a prefix in the name, stats in the lore. It's heard for every stack `nf.items.create`, `ProjectItem:create` and a loot roll make, and for every stack that takes a changed file's look, so a name worked out here survives the file changing (one set any other way goes back to the file's then). A stack whose `data` a script changes keeps its name until it's made again: hand it to `nf.items.create` with its `data` to work it out anew.",
    payload: 'ItemRenderEvent',
    writable: ['display_name', 'lore'],
    example:
      'this:on("render", function(event)\n  local prefix = event.item.data and event.item.data.prefix\n  if prefix then\n    event.display_name = "<green>" .. prefix .. " Copper Sword"\n  end\nend)',
  },
  {
    name: 'view',
    doc: "A stack of this item is being sent to one player's game: their inventory (hotbar, armour and offhand too), a window they have open, the stack on their cursor. Assign `event.display_name` and `event.lore` to change how it looks to `event.player` alone, now: a price while they have a shop open, a hint for their class. Nothing the server keeps changes: not the stack, how it stacks, what's saved, what anyone else sees, nor what scripts read from inventories. Both start as the stack's own (after its `render`), so this adds to them. The game is sent a stack again only when it changes, so call `player:refresh_items()` when what this works out from changes (a menu opening or closing). If a handler fails, the stack is sent as it is. Then `nf.on(\"item_view\")`, with the same event; `event:stop()` keeps it from there.",
    payload: 'ItemViewEvent',
    bubbles: true,
    writable: ['display_name', 'lore'],
    example:
      'this:on("view", function(event)\n  local menu = event.player:menu()\n  if menu and menu:kind() == "shop" then\n    local lore = event.lore or {}\n    lore[#lore + 1] = "<gold>Sells for 5 coins"\n    event.lore = lore\n  end\nend)',
  },
  {
    name: 'pickup',
    doc: `A player is picking up a stack of this item (\`event.item\`) from the item entity \`event.entity\`. ${thenPlayer('pickup_item')}`,
    payload: 'PlayerPickupItemEvent',
    cancellable: true,
    bubbles: true,
  },
]

/** A project item: an item kind of the project's own, from `items/<id>/`. */
export const projectItemClass: LuaClass = {
  name: 'ProjectItem',
  doc: "A project item from `items/<id>/item.json`: `this` in the item's script, and what `nf.items.get` hands out. It's the item itself, not one stack of it: its handlers hear what players do with any stack of it, wherever the stack came from. A stack of it is an `Item` table whose `item` is this id. If the item is deleted, its methods answer `nil` or `false` and its handlers go.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: itemEvents,
  functions: [
    {
      name: 'id',
      doc: 'Its id: its folder name under `items/`.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: 'Whether the project still has it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'create',
      doc: 'A stack of it, as an `Item` table: the same as `nf.items.create(this:id(), overrides)`. `nil` once the item is gone.',
      params: [
        {
          name: 'overrides',
          type: 'table',
          doc: '`Item` fields for this stack: `{ count = 3, data = { found = "cave" } }`. A field the item\'s file sets is replaced only until the file changes (see `nf.items.create`).',
          optional: true,
        },
      ],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'properties',
      doc: 'Its `properties` from its `item.json`: the project\'s own facts about it, any JSON (`{ damage = 12, class = "melee" }`), as a table. A new table each call, so keep it rather than ask in a loop. An empty table when the file has none, `nil` once the item is gone.',
      params: [],
      returns: [{ type: 'table?' }],
      example: 'local stats = assert(this:properties())\nlog(stats.damage)',
    },
    ...eventFunctions(
      'ProjectItem',
      'item',
      'this:on("use", function(event)\n  event.player:send_message("<red>The ruby glows.")\nend)',
    ),
  ],
}

/** `nf.items`: the project's items. */
export const nfItems: LuaClass = {
  name: 'nf.items',
  doc: "The project's items (`items/<id>/`): item kinds of its own, whose stacks the server can always tell apart from any other stack of the same Minecraft kind.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'create',
      doc: "A stack of a project item, as an `Item` table with every field its file sets, `item` naming it, and `count` 1: hand it to `player:give_item`, `menu:set_item` or anything else that takes an item. `overrides` sets fields of this stack: its `count`, its own `data`, or any other field. A field the file sets too is the stack's only until the file changes: then the stack takes the file's look again, keeping its count, damage and `data` (see the item format). A project item the project doesn't have is an error.",
      params: [
        {
          name: 'item',
          type: 'string',
          doc: 'Its folder name under `items/`, or `namespace:id` for one a package it depends on exports (see [Names across packages](packages.md)).',
          names: 'item',
        },
        {
          name: 'overrides',
          type: 'table',
          doc: '`Item` fields for this stack, like `{ count = 3 }`. Any `Item` field but `kind` and `item`.',
          optional: true,
        },
      ],
      returns: [{ type: 'Item' }],
      example:
        'event.player:give_item(nf.items.create("ruby", { count = 3, data = { found = "cave" } }))',
    },
    {
      name: 'id',
      doc: "Which project item a stack is (its `item` field), or `nil` for a stack that isn't one. A stack carries its project item wherever it goes, so this tells a ruby from any other paper.",
      params: [
        { name: 'stack', type: 'Item', doc: 'An item table, as any event or inventory hands out.' },
      ],
      returns: [{ type: 'string?' }],
      example:
        'nf.on("player_drop_item", function(event)\n  if nf.items.id(event.item) == "ruby" then\n    event:cancel()\n  end\nend)',
    },
    {
      name: 'get',
      doc: 'A project item by id, to listen to what players do with it (`nf.items.get("ruby"):on("use", ...)`), or `nil` when the project has no such item.',
      params: [
        {
          name: 'item',
          type: 'string',
          doc: 'Its folder name under `items/`, or `namespace:id` for one a package it depends on exports (see [Names across packages](packages.md)).',
          names: 'item',
        },
      ],
      returns: [{ type: 'ProjectItem?' }],
    },
    {
      name: 'all',
      doc: "Every item the calling script can use: its own package's, and those the packages it depends on export.",
      params: [],
      returns: [{ type: 'ProjectItem[]' }],
    },
  ],
}

/** The tables items' events take and give. */
export const itemShapes: LuaClass[] = [
  {
    name: 'ItemViewEvent',
    doc: "A stack is being sent to one player's game, to be shown as this player alone sees it: `view` on a project item, `item_view` on `nf`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'player', type: 'Player', doc: 'Whose game it is sent to.' },
      {
        name: 'item',
        type: 'Item',
        doc: 'The real stack, as the server keeps it. Changing this table changes nothing: assign `display_name` and `lore`.',
      },
      {
        name: 'display_name',
        type: 'Text?',
        doc: "The name this player is shown: the stack's own until a handler assigns another. `nil` for the game's own name.",
      },
      {
        name: 'lore',
        type: 'Text[]?',
        doc: 'The lore lines this player is shown, the same way. `nil` for none.',
      },
    ],
  },
  {
    name: 'ItemRenderEvent',
    doc: "A stack of a project item is being made, or rewritten to its item's changed file: `render`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      {
        name: 'item',
        type: 'Item',
        doc: 'The stack as it is about to be, with its `data` and `count`. Changing this table changes nothing: assign `display_name` and `lore`.',
      },
      {
        name: 'display_name',
        type: 'Text?',
        doc: "Its name: the file's (or what made it said), until a handler assigns another. `nil` for the game's own name.",
      },
      { name: 'lore', type: 'Text[]?', doc: 'Its lore lines, the same way. `nil` for none.' },
    ],
  },
]
