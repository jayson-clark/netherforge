import type { PlatformFile } from '../types.ts'

export default {
  source: 'ResourcePacks.kt',
  decls: [
    {
      kind: 'record',
      name: 'PackOffer',
      doc: "A built resource pack offered to clients: where to fetch it and its SHA-1, so a client that has it skips the\ndownload. [replacing] takes every other pack the player has off first (what the project's pack does as they join,\nso nothing another server sent stays); otherwise it's added beside them, replacing only one of the same id.",
      fields: [
        {
          name: 'id',
          type: 'UUID',
        },
        {
          name: 'url',
          type: 'String',
        },
        {
          name: 'sha1',
          type: 'String',
        },
        {
          name: 'required',
          type: 'Boolean',
        },
        {
          name: 'prompt',
          type: 'String?',
        },
        {
          name: 'replacing',
          type: 'Boolean',
          default: 'true',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ResourcePackOps',
      properties: [],
      methods: [
        {
          name: 'send',
          doc: "Asks a player's client to load [pack], replacing the one sent before under the same id. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'pack',
              type: 'PackOffer',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'remove',
          doc: "Asks a player's client to take off the pack sent under [id]. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
