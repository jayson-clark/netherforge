import type { CommandArgumentType, LuaClass } from '../types.ts'

/**
 * `nf.commands` and the tables it takes and hands out: a command's
 * definition, its typed arguments and subcommands, and the `CommandEvent` a
 * handler receives.
 */

const shape = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

/**
 * What an argument's `type` can be: what may be typed, the value its handler gets (and its
 * `default` is), and what the server reads it as. The runtime's `ArgumentType` is generated
 * from these; each adapter gives the server a type for each reading.
 */
export const ARGUMENT_TYPES: CommandArgumentType[] = [
  { name: 'word', doc: 'one word', value: 'string', reading: 'word' },
  { name: 'text', doc: 'the rest of the line, spaces and all', value: 'string', reading: 'text' },
  {
    name: 'integer',
    doc: 'a whole number, within `min` and `max` when given',
    value: 'integer',
    reading: 'integer',
  },
  {
    name: 'number',
    doc: 'any number, within `min` and `max` when given',
    value: 'number',
    reading: 'number',
  },
  { name: 'boolean', doc: '`true` or `false`', value: 'boolean', reading: 'boolean' },
  { name: 'choice', doc: 'one of `choices`, as typed', value: 'string', reading: 'choice' },
  {
    name: 'player',
    doc: 'an online player by name, or a selector that picks exactly one (`@p`, `@s`)',
    value: 'Player',
    reading: 'player',
  },
  {
    name: 'players',
    doc: 'a name or a selector (`@a`, `@a[distance=..10]`) picking at least one player',
    value: 'Player[]',
    reading: 'players',
  },
  {
    name: 'entity',
    doc: "a selector that picks exactly one entity, an online player's name, or a loaded entity's UUID (a `Player` for a player)",
    value: 'Entity',
    reading: 'entity',
  },
  {
    name: 'entities',
    doc: 'a selector, name or UUID picking at least one entity',
    value: 'Entity[]',
    reading: 'entities',
  },
  { name: 'world', doc: 'a loaded world by name', value: 'World', reading: 'word', names: true },
  {
    name: 'position',
    doc: 'three coordinates, each a number, `~` (relative to the sender) or `^` (local to where they look); whole numbers are block centres in x and z, as in vanilla commands',
    value: 'Vec3',
    reading: 'position',
  },
  {
    name: 'location',
    doc: "the same three coordinates, in the sender's world facing the way they face (the default world and no facing from the console)",
    value: 'Location',
    reading: 'position',
  },
  {
    name: 'block_state',
    doc: 'a block state like `oak_stairs[facing=east]`, given back in full with every property (`"minecraft:oak_stairs[facing=east,half=bottom,…]"`)',
    value: 'string',
    reading: 'block_state',
  },
  {
    name: 'item',
    doc: 'an item like `diamond_sword`, or with components on Paper (`diamond_sword[enchantments={sharpness:3}]`)',
    value: 'Item',
    reading: 'item',
  },
  {
    name: 'centity',
    doc: 'a live centity by its instance id (completed from the live ones)',
    value: 'Centity',
    reading: 'word',
    names: true,
  },
  {
    name: 'centity_kind',
    doc: "one of the project's centities, by its folder name",
    value: 'string',
    reading: 'word',
    names: true,
  },
  {
    name: 'menu',
    doc: "one of the project's menus, by its id",
    value: 'string',
    reading: 'word',
    names: true,
  },
  {
    name: 'dialog',
    doc: "one of the project's dialogs, by its id",
    value: 'Dialog',
    reading: 'word',
    names: true,
  },
]

/** `"word"`: one word, a `string`. A line of the `type` field's doc per argument type. */
const typeLine = (it: CommandArgumentType) =>
  `\`"${it.name}"\`: ${it.doc}, ${/^[AEIOU]/.test(it.value) ? 'an' : 'a'} \`${it.value}\`.`

const handler = {
  name: 'handler',
  type: '(fun(event: CommandEvent))?',
  doc: 'Runs when someone types it. A handler that errors is logged and whoever typed the command is told; the command stays.',
}

const description = {
  name: 'description',
  type: 'string?',
  doc: "One line for the server's help.",
}

const permission = {
  name: 'permission',
  type: 'string?',
  doc: "A permission node the sender needs. On Paper the command (or subcommand) doesn't exist for a player without it: not in tab completion, not when typed. The console has every permission.",
}

const arguments_ = {
  name: 'arguments',
  type: 'CommandArgument[]?',
  doc: 'What comes after it, in order. Arguments with a `default` are optional and must come last; a `text` argument takes the rest of the line, so it must be the last one.',
}

const subcommands = {
  name: 'subcommands',
  type: 'table<string, Subcommand>?',
  doc: 'Words that can come next, each with its own arguments, permission and handler: `{ add = { ... }, remove = { ... } }`. Names are lowercase letters, digits, `_` and `-`. A subcommand wins over an argument when both could take the word.',
}

const playersOnly = {
  name: 'players_only',
  type: 'boolean?',
  doc: 'Refuses the console (and command blocks) with a message, so the handler can count on `event.player`. A subcommand under a `players_only` command is players-only too.',
}

export const commandShapes: LuaClass[] = [
  shape(
    'CommandDefinition',
    "How `nf.commands.register` describes a command: everything optional. A key that isn't one of these is an error.",
    [
      description,
      permission,
      { name: 'aliases', type: 'string[]?', doc: 'Other names for it.' },
      arguments_,
      subcommands,
      playersOnly,
    ],
  ),
  shape(
    'Subcommand',
    "A word after a command (or after another subcommand) with its own arguments and handler: `/shop give <player>`. A key that isn't one of these is an error.",
    [description, permission, arguments_, subcommands, playersOnly, handler],
  ),
  shape(
    'CommandArgument',
    "One argument of a command. Its `type` decides what may be typed, what tab completion offers, the usage message, and the value the handler gets in `event.arguments[name]`. On Paper it's a real Brigadier argument, so the player's client checks and completes it as they type. A key that isn't one of these is an error.",
    [
      {
        name: 'name',
        type: 'string',
        doc: 'Its key in `event.arguments`, and what the usage message shows (`<amount>`). Letters, digits and `_`, not starting with a digit; unique within the command.',
      },
      {
        name: 'type',
        type: ARGUMENT_TYPES.map((it) => JSON.stringify(it.name)).join('|'),
        doc: ['What it takes, and what the handler gets:', ...ARGUMENT_TYPES.map(typeLine)].join(
          ' ',
        ),
      },
      {
        name: 'default',
        type: 'any',
        doc: 'Makes it optional: the value the handler gets when it\'s left out. A value of the argument\'s type (a number for `integer`, a `Vec3` for `position`, one of `choices` for `choice`), or `false` for "nothing", the way to make a `player` or `item` argument optional: then `event.arguments.target or event.player`.',
      },
      {
        name: 'min',
        type: 'number?',
        doc: 'The smallest value an `integer` or `number` argument takes.',
      },
      {
        name: 'max',
        type: 'number?',
        doc: 'The largest value an `integer` or `number` argument takes.',
      },
      {
        name: 'choices',
        type: 'string[]?',
        doc: 'What a `choice` argument takes: single words. Required for `choice`, and only for it.',
      },
      {
        name: 'complete',
        type: '(fun(event: CommandEvent, partial: string): string[])?',
        doc: "Tab completion of its own, instead of what its type offers. Gets the event so far (`event.arguments` holds the arguments before this one) and what has been typed of this argument; returns the suggestions. NetherForge keeps those starting with `partial`. Runs in the module's scope, within its instruction budget, every time a player's client asks, so keep it cheap.",
      },
    ],
  ),
  shape('CommandEvent', "What a command handler, and an argument's `complete`, receives.", [
    { name: 'name', type: 'string', doc: "The command's name, as registered." },
    {
      name: 'label',
      type: 'string',
      doc: 'What they typed to run it: the name, or one of its aliases.',
    },
    {
      name: 'arguments',
      type: 'table<string, any>',
      doc: 'The arguments by name, typed by their declaration: a `Player` for a `player` argument, a `Vec3` for a `position`, an `integer` for an `integer`. An optional argument left out has its `default`.',
    },
    {
      name: 'input',
      type: 'string',
      doc: "Everything typed after the command's name, subcommands included, as typed.",
    },
    {
      name: 'sender',
      type: 'Sender',
      doc: 'Who ran it, player or console. Answer them with `event.sender:send_message(text)`.',
    },
    {
      name: 'player',
      type: 'Player?',
      doc: 'The player who ran it, or `nil` from the console. Never `nil` in a `players_only` command.',
    },
  ]),
]

/** `nf.commands`: the module's own slash commands. */
export const nfCommands: LuaClass = {
  name: 'nf.commands',
  doc: 'Slash commands a module adds to the server for as long as it runs, with typed arguments, tab completion and usage messages from their declaration.',
  methods: false,
  fields: [],
  functions: [
    {
      name: 'register',
      doc: "Adds `/name` to the server for as long as the module runs. Only modules can register commands, because a command outlives any one centity. The definition declares its arguments and subcommands; NetherForge checks what's typed against them, answers mistakes with a usage message, completes them as players type, and hands the handler typed values. Returns `false` when the name is already a command, the server's or another module's. A handler that errors is logged and whoever typed the command is told; the command stays. A name that isn't usable, or a definition with a mistake in it (an unknown key, an unknown argument type, an optional argument before a required one), is an error.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Lowercase letters, digits, `_` and `-`, starting with a letter. Without the slash.',
        },
        {
          name: 'definition',
          type: 'CommandDefinition|fun(event: CommandEvent)',
          doc: 'Description, permission, aliases, arguments and subcommands. Can be left out, with the handler in its place: `nf.commands.register(name, handler)`.',
          optional: true,
        },
        {
          ...handler,
          type: 'fun(event: CommandEvent)',
          optional: true,
          doc: 'Runs when someone types the command (with no subcommand). Can be left out when the definition has subcommands: typing the bare command then shows its usage.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`true` once the command is registered.' }],
      example: [
        'nf.commands.register("give-coins", {',
        '  description = "Give someone coins",',
        '  permission = "shop.admin",',
        '  arguments = {',
        '    { name = "target", type = "player" },',
        '    { name = "amount", type = "integer", min = 1, default = 1 },',
        '  },',
        '}, function(event)',
        '  local target, amount = event.arguments.target, event.arguments.amount',
        '  local wallet = assert(nf.files.get("coins/" .. target:id() .. ".json"))',
        '  local coins = wallet:read_json() or { coins = 0 }',
        '  coins.coins = coins.coins + amount',
        '  wallet:write_json(coins)',
        '  event.sender:send_message("<green>Gave " .. amount .. " coins to " .. target:name())',
        'end)',
      ].join('\n'),
    },
  ],
}
