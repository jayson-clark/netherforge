import type { PlatformFile } from '../types.ts'

export default {
  source: 'PlayerOps.kt',
  decls: [
    {
      kind: 'interface',
      name: 'PlayerOps',
      properties: [],
      methods: [
        {
          name: 'online',
          params: [],
          returns: 'List<PlayerRef>',
        },
        {
          name: 'find',
          doc: 'An online player by exact name (case-insensitive) or UUID string.',
          params: [
            {
              name: 'nameOrUuid',
              type: 'String',
            },
          ],
          returns: 'PlayerRef?',
        },
        {
          name: 'known',
          doc: 'Anyone who has ever joined this server, online or not, by name (case-insensitive) or UUID string.',
          params: [
            {
              name: 'nameOrUuid',
              type: 'String',
            },
          ],
          returns: 'PlayerRef?',
        },
        {
          name: 'get',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'PlayerRef?',
        },
        {
          name: 'location',
          doc: 'Where they stand, with their facing; null when offline.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'Location?',
        },
        {
          name: 'eye',
          doc: 'Their line of sight from the eye; null when offline.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'Ray?',
        },
        {
          name: 'message',
          doc: "Sends a MiniMessage line to one player. False when they're offline.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'teleport',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'to',
              type: 'Location',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'runCommand',
          doc: "Runs a command as the player, with their permissions. False if it didn't run.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'line',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hasPermission',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'permission',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'isOperator',
          doc: "Whether they're an operator, online or not.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'displayName',
          doc: 'What chat calls them, as MiniMessage; null when offline.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setDisplayName',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'actionbar',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'title',
          doc: 'A title and subtitle, with fade-in, stay and fade-out in ticks.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'title',
              type: 'String',
            },
            {
              name: 'subtitle',
              type: 'String',
            },
            {
              name: 'fadeIn',
              type: 'Int',
            },
            {
              name: 'stay',
              type: 'Int',
            },
            {
              name: 'fadeOut',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'clearTitle',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'gameMode',
          doc: '`survival`, `creative`, `adventure` or `spectator`; null when offline.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setGameMode',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'gameMode',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'giveExperience',
          doc: 'Points, as orbs give them: the level goes up when the bar fills.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'points',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'locale',
          doc: "Their game's language, like `en_us`; null when offline.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'kick',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'reason',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'cooldown',
          doc: "Ticks left on the cooldown of a group (an item's kind is its default group); null when offline.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setCooldown',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
            {
              name: 'ticks',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'tabText',
          doc: "The player list's header (or footer, with [footer]) as MiniMessage; null when there's none or they're offline.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'footer',
              type: 'Boolean',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setTabText',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'footer',
              type: 'Boolean',
            },
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'closeInventory',
          doc: 'Closes whatever container screen they have open.',
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'cursor',
          doc: "What they carry on their cursor, whatever screen they have open; null for nothing, or when they're offline.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
          returns: 'ItemData?',
        },
        {
          name: 'setCursor',
          doc: "Puts [item] (null empties it) on their cursor, whatever screen they have open. False when they're offline, or it can't be built.",
          params: [
            {
              name: 'uuid',
              type: 'UUID',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'broadcast',
          doc: 'A MiniMessage line to everyone online and the console.',
          params: [
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'messageConsole',
          doc: 'A MiniMessage line to the server console alone.',
          params: [
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
  ],
} satisfies PlatformFile
