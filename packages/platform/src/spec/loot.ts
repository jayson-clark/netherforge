import type { PlatformFile } from '../types.ts'

export default {
  source: 'Loot.kt',
  decls: [
    {
      kind: 'record',
      name: 'GameLootContext',
      doc: "Where and for whom the game rolls one of its own loot tables: what a\nscript's roll context says, as the server takes it.",
      fields: [
        {
          name: 'location',
          type: 'Location',
          doc: "Where it's rolled: a chest's tables ask where they are (a treasure map's nearest structure).",
        },
        {
          name: 'luck',
          type: 'Double',
          default: '0.0',
        },
        {
          name: 'player',
          type: 'UUID?',
          default: 'null',
          doc: 'The player behind the roll (who killed, broke or opened it), online.',
        },
        {
          name: 'looted',
          type: 'UUID?',
          default: 'null',
          doc: "The entity whose loot it is: a mob's table needs it.",
        },
      ],
    },
    {
      kind: 'interface',
      name: 'LootOps',
      doc: "The game's own loot tables (`minecraft:chests/simple_dungeon`, a datapack's),\nrolled by the server as the game rolls them. The project's own tables are\nthe runtime's (format's `LootRoller`); a table of theirs that names one of\nthe game's hands it here.",
      properties: [],
      methods: [
        {
          name: 'exists',
          doc: 'Whether the server has the loot table [table], a namespaced id.',
          params: [
            {
              name: 'table',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'roll',
          doc: "What one roll of the game's loot table [table] gives at [context],\ndrawn from a generator seeded with [seed]: the same seed in the same\nworld gives the same items. Null when the server has no such table. A\ntable that needs something [context] doesn't say (a mob's, without\n[GameLootContext.looted]), or a location in a world that isn't there,\nis an [IllegalArgumentException] saying so.",
          params: [
            {
              name: 'table',
              type: 'String',
            },
            {
              name: 'context',
              type: 'GameLootContext',
            },
            {
              name: 'seed',
              type: 'Long',
            },
          ],
          returns: 'List<ItemData>?',
        },
      ],
    },
  ],
} satisfies PlatformFile
