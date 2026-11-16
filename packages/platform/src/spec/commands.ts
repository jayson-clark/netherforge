import type { PlatformFile } from '../types.ts'

export default {
  source: 'Commands.kt',
  decls: [
    {
      kind: 'object',
      name: 'CommandSender',
      doc: "Who ran a command and how to answer them.\n\nThe adapter builds one per command run or completion, with what only the\nserver knows about the sender: where they stand and which way they face\n(for a `location` argument's world and facing), and what they may do.",
      fields: [
        {
          name: 'player',
          type: 'PlayerRef?',
        },
        {
          name: 'name',
          type: 'String',
        },
        {
          name: 'location',
          type: 'Location?',
          doc: "Where they are and which way they face; null when they're nowhere.",
        },
      ],
      properties: [],
      methods: [
        {
          name: 'reply',
          doc: 'Sends a MiniMessage line back to whoever ran the command.',
          params: [
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'hasPermission',
          params: [
            {
              name: 'permission',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SelectedEntity',
      doc: "An entity a selector, a name or a UUID picked: [player] is set when it's a player.",
      fields: [
        {
          name: 'uuid',
          type: 'UUID',
        },
        {
          name: 'player',
          type: 'PlayerRef?',
        },
      ],
    },
    {
      kind: 'enum',
      name: 'ArgumentReading',
      doc: "What the server reads an argument as, before the runtime sees it: the\nargument type the adapter gives the server's command tree (a Brigadier\ntype on Paper), and so the [ArgumentValue] it hands over. Each\n[ArgumentType] (generated from the spec's `commandArguments`) names one;\nthe runtime turns the [ArgumentValue] into the handler's value\n([dev.netherforge.plugin.command.ArgumentValues]), and [ArgumentType.names]\nmarks the word-typed ones only the runtime can resolve and complete (the\nproject's own things and the server's worlds).",
      params: [],
      entries: [
        {
          name: 'WORD',
          doc: "One word (Brigadier's `word`): an [ArgumentValue.Text].",
          args: [],
        },
        {
          name: 'TEXT',
          doc: 'The rest of the line, spaces and all: an [ArgumentValue.Text].',
          args: [],
        },
        {
          name: 'INTEGER',
          doc: "A whole number within the argument's `min` and `max`: an [ArgumentValue.Integer].",
          args: [],
        },
        {
          name: 'NUMBER',
          doc: 'Any number within its `min` and `max`: an [ArgumentValue.Decimal].',
          args: [],
        },
        {
          name: 'BOOLEAN',
          doc: '`true` or `false`: an [ArgumentValue.Bool].',
          args: [],
        },
        {
          name: 'CHOICE',
          doc: "One of the argument's `choices`: an [ArgumentValue.Text].",
          args: [],
        },
        {
          name: 'PLAYER',
          doc: "A player's name or a selector picking exactly one player: an [ArgumentValue.Players] of one.",
          args: [],
        },
        {
          name: 'PLAYERS',
          doc: 'A name or a selector picking at least one player: an [ArgumentValue.Players].',
          args: [],
        },
        {
          name: 'ENTITY',
          doc: 'A name, UUID or selector picking exactly one entity: an [ArgumentValue.Entities] of one.',
          args: [],
        },
        {
          name: 'ENTITIES',
          doc: 'A name, UUID or selector picking at least one entity: an [ArgumentValue.Entities].',
          args: [],
        },
        {
          name: 'POSITION',
          doc: 'Three coordinates, absolute, `~` or `^`, resolved against the sender: an [ArgumentValue.Position].',
          args: [],
        },
        {
          name: 'BLOCK_STATE',
          doc: 'A block state the server has: an [ArgumentValue.BlockState], every property filled in.',
          args: [],
        },
        {
          name: 'ITEM',
          doc: 'An item, components and all: an [ArgumentValue.Item].',
          args: [],
        },
      ],
    },
    {
      kind: 'record',
      name: 'ArgumentSyntax',
      doc: 'One argument: its name, its type, whether it can be left out, the bounds\nof an `integer` or `number`, the words a `choice` takes, and whether the\nscript completes it itself (then [CommandHandler.suggest] answers for it).',
      fields: [
        {
          name: 'name',
          type: 'String',
        },
        {
          name: 'type',
          type: 'ArgumentType',
        },
        {
          name: 'optional',
          type: 'Boolean',
          default: 'false',
        },
        {
          name: 'min',
          type: 'Double?',
          default: 'null',
        },
        {
          name: 'max',
          type: 'Double?',
          default: 'null',
        },
        {
          name: 'choices',
          type: 'List<String>',
          default: 'emptyList()',
        },
        {
          name: 'completes',
          type: 'Boolean',
          default: 'false',
        },
      ],
    },
    {
      kind: 'record',
      name: 'CommandSyntax',
      doc: 'A command or one of its subcommands: the arguments that follow it, the\nsubcommands that can follow instead, who may use it, and whether typing it\n(with its required arguments) runs anything. One with [runs] false only\nleads to its subcommands; typed alone, it answers with their usage.',
      fields: [
        {
          name: 'description',
          type: 'String',
          default: '""',
        },
        {
          name: 'permission',
          type: 'String?',
          default: 'null',
        },
        {
          name: 'playersOnly',
          type: 'Boolean',
          default: 'false',
        },
        {
          name: 'arguments',
          type: 'List<ArgumentSyntax>',
          default: 'emptyList()',
        },
        {
          name: 'subcommands',
          type: 'Map<String, CommandSyntax>',
          default: 'emptyMap()',
          doc: "By name, in the order they're listed in usage messages.",
        },
        {
          name: 'runs',
          type: 'Boolean',
          default: 'true',
        },
      ],
    },
    {
      kind: 'record',
      name: 'CommandSpec',
      doc: "A command: its name, other names for it, and its [syntax] (whose description and permission are the command's).",
      fields: [
        {
          name: 'name',
          type: 'String',
        },
        {
          name: 'aliases',
          type: 'List<String>',
          default: 'emptyList()',
        },
        {
          name: 'syntax',
          type: 'CommandSyntax',
          default: 'CommandSyntax()',
        },
      ],
    },
    {
      kind: 'union',
      name: 'ArgumentValue',
      doc: "An argument's value as the server read it ([ArgumentReading]): typed and\nchecked against what the server knows (who's online, which blocks and items\nit has, where the sender stands), not yet against the project.",
      cases: [
        {
          name: 'Text',
          doc: 'A word, the rest of the line, or a choice: as typed.',
          fields: [
            {
              name: 'text',
              type: 'String',
            },
          ],
        },
        {
          name: 'Integer',
          fields: [
            {
              name: 'value',
              type: 'Long',
            },
          ],
        },
        {
          name: 'Decimal',
          fields: [
            {
              name: 'value',
              type: 'Double',
            },
          ],
        },
        {
          name: 'Bool',
          fields: [
            {
              name: 'value',
              type: 'Boolean',
            },
          ],
        },
        {
          name: 'Players',
          fields: [
            {
              name: 'players',
              type: 'List<PlayerRef>',
            },
          ],
        },
        {
          name: 'Entities',
          fields: [
            {
              name: 'entities',
              type: 'List<SelectedEntity>',
            },
          ],
        },
        {
          name: 'Position',
          fields: [
            {
              name: 'x',
              type: 'Double',
            },
            {
              name: 'y',
              type: 'Double',
            },
            {
              name: 'z',
              type: 'Double',
            },
          ],
        },
        {
          name: 'BlockState',
          fields: [
            {
              name: 'state',
              type: 'String',
            },
          ],
        },
        {
          name: 'Item',
          fields: [
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
        },
      ],
    },
    {
      kind: 'record',
      name: 'CommandInput',
      doc: "A command as the server read it: the subcommands typed ([path]), the syntax\nthey lead to, each argument typed there by name (an optional one left out\nisn't in [arguments]), and everything typed after the label ([text]).",
      fields: [
        {
          name: 'path',
          type: 'List<String>',
        },
        {
          name: 'arguments',
          type: 'Map<String, ArgumentValue>',
        },
        {
          name: 'text',
          type: 'String',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SuggestionRequest',
      doc: "Tab completion asking about [argument] of the syntax [path] leads to:\nthe arguments before it as the server read them, and what's typed of it\nso far ([partial]; for a `text` argument, everything after its start).",
      fields: [
        {
          name: 'path',
          type: 'List<String>',
        },
        {
          name: 'argument',
          type: 'String',
        },
        {
          name: 'arguments',
          type: 'Map<String, ArgumentValue>',
        },
        {
          name: 'partial',
          type: 'String',
        },
      ],
    },
    {
      kind: 'record',
      name: 'Completion',
      doc: 'Suggestions for the word being typed, which starts at [start] within what was asked about.',
      fields: [
        {
          name: 'start',
          type: 'Int',
        },
        {
          name: 'suggestions',
          type: 'List<String>',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'CommandHandler',
      properties: [],
      methods: [
        {
          name: 'run',
          doc: '[label] is what was typed to run it, the name or an alias; [input] is what the server read after it.',
          params: [
            {
              name: 'sender',
              type: 'CommandSender',
            },
            {
              name: 'label',
              type: 'String',
            },
            {
              name: 'input',
              type: 'CommandInput',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'suggest',
          doc: 'Suggestions for an argument whose suggestions come from the runtime\n([ArgumentSyntax.suggestedByRuntime]): [Completion.start] is within\n[SuggestionRequest.partial]. Every other argument the server completes itself.',
          params: [
            {
              name: 'sender',
              type: 'CommandSender',
            },
            {
              name: 'request',
              type: 'SuggestionRequest',
            },
          ],
          returns: 'Completion',
          defaulted: true,
        },
      ],
    },
    {
      kind: 'interface',
      name: 'CommandOps',
      doc: "The project's commands on the server. The server reads what's typed against\na command's [CommandSyntax] (its own parser: Brigadier on Paper), answers a\nmistake there itself, and hands the handler what it read; the runtime never\nreads a command line.",
      properties: [],
      methods: [
        {
          name: 'register',
          doc: 'Adds `/name` to the server. False when the name is already taken by the server or another plugin.',
          params: [
            {
              name: 'spec',
              type: 'CommandSpec',
            },
            {
              name: 'handler',
              type: 'CommandHandler',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'unregister',
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'runConsole',
          doc: "Runs a command as the console. False when there's no such command.",
          params: [
            {
              name: 'line',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
