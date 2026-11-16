import type { PlatformFile } from '../types.ts'

export default {
  source: 'PlayerAdmin.kt',
  decls: [
    {
      kind: 'interface',
      name: 'PlayerViewOps',
      doc: "What one player's game is shown that isn't so (`player:send_block_change`,\n`send_equipment_change`, `open_book`), and their own view of the world: a\ncamera, a compass target, a view distance. Every call answers false (or\nnull) when [player] is offline.",
      properties: [],
      methods: [
        {
          name: 'sendBlock',
          doc: "Shows [player] a canonical block state the server has at a block, which the world doesn't have.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'x',
              type: 'Int',
            },
            {
              name: 'y',
              type: 'Int',
            },
            {
              name: 'z',
              type: 'Int',
            },
            {
              name: 'state',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'resetBlock',
          doc: "Shows [player] the real block again. False also when [world] isn't loaded.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'x',
              type: 'Int',
            },
            {
              name: 'y',
              type: 'Int',
            },
            {
              name: 'z',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'sendEquipment',
          doc: 'Shows [player] [entity] with [item] (null: nothing) in an equipment slot. False also when the entity has gone or has no equipment.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'slot',
              type: 'String',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'camera',
          doc: 'The entity [player] is spectating through, or null for their own eyes.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'setCamera',
          doc: "Spectates through [entity] (null: back to their own eyes): any entity the server has, the displays\ncentities and cutscenes are made of included. The server moves the player to it, and with it each\ntick after. False also when they aren't in spectator mode, or the entity has gone.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'entity',
              type: 'UUID?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'compassTarget',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Location?',
        },
        {
          name: 'setCompassTarget',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'target',
              type: 'Location',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'openBook',
          doc: 'Opens a written book of MiniMessage [pages] on their screen.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'pages',
              type: 'List<String>',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'viewDistance',
          doc: "The distance they're sent now, in chunks: a change shows from the next tick, lowered by their own game's setting.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setViewDistance',
          doc: 'In chunks, already checked to be 2 to 32.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'chunks',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BanSpec',
      doc: 'A ban as the server keeps it: why, when it runs out (Unix ms, null for never), and who made it.',
      fields: [
        {
          name: 'reason',
          type: 'String?',
        },
        {
          name: 'expires',
          type: 'Long?',
        },
        {
          name: 'source',
          type: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ServerAdminOps',
      doc: "The server's own settings and lists: who has played, the most players\nallowed, the message of the day, the whitelist and bans. Whatever changes\none lasts until the server restarts unless the server keeps it in its own\nfiles (the whitelist and bans do).",
      properties: [],
      methods: [
        {
          name: 'known',
          doc: 'Everyone who has ever joined, online or not, who has a name.',
          params: [],
          returns: 'List<PlayerRef>',
        },
        {
          name: 'firstPlayed',
          doc: 'When they first joined and were last here, Unix ms (now while online); null for someone who never has.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Long?',
        },
        {
          name: 'lastSeen',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Long?',
        },
        {
          name: 'maxPlayers',
          params: [],
          returns: 'Int',
        },
        {
          name: 'setMaxPlayers',
          params: [
            {
              name: 'count',
              type: 'Int',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'motd',
          doc: 'The message of the day, as MiniMessage.',
          params: [],
          returns: 'String',
        },
        {
          name: 'setMotd',
          params: [
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'isWhitelistEnabled',
          params: [],
          returns: 'Boolean',
        },
        {
          name: 'setWhitelistEnabled',
          params: [
            {
              name: 'enabled',
              type: 'Boolean',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'whitelisted',
          params: [],
          returns: 'List<PlayerRef>',
        },
        {
          name: 'isWhitelisted',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setWhitelisted',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'whitelisted',
              type: 'Boolean',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'banned',
          doc: "Everyone banned whose ban hasn't run out.",
          params: [],
          returns: 'List<PlayerRef>',
        },
        {
          name: 'isBanned',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'ban',
          doc: "Bans them, replacing any ban they had, and kicks them with the reason when they're online.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'ban',
              type: 'BanSpec',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'unban',
          doc: "False when they weren't banned.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PermissionOps',
      doc: 'The permission nodes the project sets on players, on top of whatever else\ngives them permissions (operators, a permissions plugin). The runtime keeps\nthem and decides which apply; the platform only holds them on the online\nplayer, which the server forgets when they leave.',
      properties: [],
      methods: [
        {
          name: 'apply',
          doc: 'Replaces everything the project set on an online [player] with [nodes] (true: granted, false: denied). Nothing when offline.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'nodes',
              type: 'Map<String, Boolean>',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'AdvancementOps',
      doc: "Advancements, by namespaced key. Calls about a player answer false (or null) when they're offline.",
      properties: [],
      methods: [
        {
          name: 'criteria',
          doc: "The names of the server's advancement [key]'s criteria; null when it has no such advancement.",
          params: [
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'List<String>?',
        },
        {
          name: 'grant',
          doc: "Awards [criterion], one of the advancement's (every criterion left when\nnull). False when there was nothing left to award.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
            {
              name: 'criterion',
              type: 'String?',
              default: 'null',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'revoke',
          doc: "Takes back [criterion] (every criterion they have when null). False when they didn't have it.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
            {
              name: 'criterion',
              type: 'String?',
              default: 'null',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'has',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'progress',
          doc: "The criteria they've met and those left.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'Pair<List<String>, List<String>>?',
        },
      ],
    },
  ],
} satisfies PlatformFile
