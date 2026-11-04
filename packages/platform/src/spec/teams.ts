import type { PlatformFile } from '../types.ts'

export default {
  source: 'Teams.kt',
  decls: [
    {
      kind: 'record',
      name: 'TeamLook',
      doc: 'What a scoreboard team shows and allows, in the Lua API\'s words: text is\nMiniMessage, [color] one of Minecraft\'s sixteen chat colour names\n(`"red"`, `"dark_blue"`) or null for none, [nametags] `always`, `never`,\n`hide_for_other_teams` or `hide_for_own_team`, and [collision] `always`,\n`never`, `push_other_teams` or `push_own_team`.',
      fields: [
        {
          name: 'displayName',
          type: 'String',
        },
        {
          name: 'prefix',
          type: 'String',
          default: '""',
        },
        {
          name: 'suffix',
          type: 'String',
          default: '""',
        },
        {
          name: 'color',
          type: 'String?',
          default: 'null',
        },
        {
          name: 'friendlyFire',
          type: 'Boolean',
          default: 'true',
        },
        {
          name: 'seeInvisibleTeammates',
          type: 'Boolean',
          default: 'true',
        },
        {
          name: 'nametags',
          type: 'String',
          default: '"always"',
        },
        {
          name: 'collision',
          type: 'String',
          default: '"always"',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'TeamOps',
      doc: "Teams on the server's main scoreboard, by their full name there, and the\nline under players' name tags (the below-name slot). A team's members are\nentries, as Minecraft keeps them: a player's name, or any other entity's\nUUID. Whatever gives a player a scoreboard of their own (a sidebar) keeps\nthese on it too; the adapter sees to that.",
      properties: [],
      methods: [
        {
          name: 'names',
          doc: 'Every team on the main scoreboard, whoever made it.',
          params: [],
          returns: 'List<String>',
        },
        {
          name: 'create',
          doc: 'Makes a team. False when the scoreboard already has one called [name].',
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'look',
              type: 'TeamLook',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'update',
          doc: "False when there's no such team.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'look',
              type: 'TeamLook',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'remove',
          doc: "Takes a team off the scoreboard, members and all. False when there's no such team.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'entries',
          doc: "A team's entries; null when there's no such team.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Set<String>?',
        },
        {
          name: 'addEntry',
          doc: "Puts [entry] in a team, out of any other. False when there's no such team.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'entry',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'removeEntry',
          doc: "False when there's no such team, or [entry] wasn't in it.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'entry',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'teamOf',
          doc: 'The team [entry] is in, or null.',
          params: [
            {
              name: 'entry',
              type: 'String',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setBelowName',
          doc: 'Shows [text] (MiniMessage) under the name tag of the player called [player], or takes it away for null.',
          params: [
            {
              name: 'player',
              type: 'String',
            },
            {
              name: 'text',
              type: 'String?',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'clearBelowNames',
          doc: 'Takes every line [setBelowName] put under a name tag away.',
          params: [],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PlayerListOps',
      doc: "The player list (what tab shows) beyond its header and footer: each\nplayer's entry's name and place, and whose list they're in. The server\nforgets names and places when the player leaves, and which lists a player\nis out of when either of them does. Everything answers null or false for a\nplayer who's offline.",
      properties: [],
      methods: [
        {
          name: 'name',
          doc: 'The name their entry shows, MiniMessage.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setName',
          doc: 'Changes the name their entry shows; null goes back to their name.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'miniMessage',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'order',
          doc: "Their entry's place: higher comes first.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setOrder',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'order',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'isListed',
          doc: "Whether [player] is in [viewer]'s list.",
          params: [
            {
              name: 'viewer',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean?',
        },
        {
          name: 'setListed',
          doc: "Takes [player] out of [viewer]'s list, or puts them back.",
          params: [
            {
              name: 'viewer',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'listed',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
