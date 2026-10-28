import type { Field, LuaClass, Param } from '../types.ts'

const table: Param = {
  name: 'table',
  type: 'string',
  doc: 'A loot table of the project\'s (`"treasure"`, `loot/treasure.json`; a bare id is the calling package\'s own), one a package exports (`"acme:treasure"`), or one of the game\'s by its id (`"minecraft:chests/simple_dungeon"`).',
  names: 'loot_table',
}

const context: Param = {
  name: 'context',
  type: 'LootContext',
  doc: 'Who and what the roll is for: its player, tool, luck and seed. Leave it out for a roll with none of them.',
  optional: true,
}

/** `nf.loot`: rolling loot tables. */
export const nfLoot: LuaClass = {
  name: 'nf.loot',
  doc: "Loot tables: the project's own (`loot/<id>.json`) and the game's. A roll picks from each of a table's pools, weighing its entries against each other, and gives the items it picked. The same `seed` always gives the same items (the editor rolls the same way). A table the project doesn't have, or that the game doesn't, is an error; so is a game table that needs something the roll doesn't say (a mob's table needs the mob, as `looted`).",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'roll',
      doc: "Rolls a loot table and gives the items it picked, in the order it picked them: a stack each, more than one when a pick gives more than a stack holds. Nothing is put anywhere: give them to a player, drop them or fill a chest with them. A game table is rolled by the server where the context says (`location`, else its player's or looted entity's place), and needs one of them.",
      params: [table, context],
      returns: [{ type: 'Item[]' }],
      example:
        'local items = nf.loot.roll("treasure", { player = player, tool = player:equipment("main_hand") })\nfor _, item in ipairs(items) do\n  player:inventory():add_item(item)\nend',
    },
    {
      name: 'fill',
      doc: "Rolls a loot table into an inventory the way the game fills a chest: each stack into an empty slot picked at random (from the roll's seed), leaving what's there. In a player's inventory only the hotbar and main inventory take loot. What finds no empty slot is left out. A game table is rolled where the inventory is, unless the context says otherwise.",
      params: [table, { name: 'inventory', type: 'Inventory', doc: '' }, context],
      returns: [
        {
          type: 'integer?',
          doc: "How many items didn't fit: 0 when all of it went in. `nil` once the inventory's gone, and then nothing is rolled.",
        },
      ],
      example:
        'local block = world:block(vec3(10, 64, 10))\nlocal chest = block and block:inventory()\nif chest then\n  nf.loot.fill("treasure", chest, { luck = 1 })\nend',
    },
  ],
}

const field = (name: string, type: string, doc: string): Field => ({ name, type, doc })

/** The plain tables loot rolls take. */
export const lootShapes: LuaClass[] = [
  {
    name: 'LootContext',
    doc: "What `nf.loot.roll` and `nf.loot.fill` roll with: who and what the roll is for, which the table's conditions ask about. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      field(
        'player',
        'Player?',
        'The player behind the roll: who killed the mob, broke the block or opened the chest. A `player` condition passes only with one. Default none.',
      ),
      field(
        'tool',
        'Item?',
        "What the player killed or broke it with, for `tool` and `enchantment` conditions. Default none: those conditions don't pass.",
      ),
      field(
        'luck',
        'number?',
        "The roll's luck: more `bonusRolls`, and each entry's weight shifted by its `quality`. Default 0.",
      ),
      field(
        'looted',
        'Entity?',
        'The entity whose loot this is, which a game table for a mob needs. Default none.',
      ),
      field(
        'location',
        'Location?',
        "Where a game table is rolled. Default the player's place, else the looted entity's.",
      ),
      field(
        'seed',
        'integer?',
        'Rolls with this seed give the same items every time, on the server and in the editor. Default a new one each roll.',
      ),
    ],
  },
]
