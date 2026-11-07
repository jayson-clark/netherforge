import type { PlatformFile } from '../types.ts'

export default {
  source: 'SkyOps.kt',
  decls: [
    {
      kind: 'interface',
      name: 'SkyOps',
      doc: "Showing a player the world they're in under a sky (format's `SkyTwins`): the biomes of every chunk they have, and\nof every one they're sent later, told them as the sky's twins at a step of its fade, until shown otherwise; and a\nsky held through the night, its clock at that step ([SkyView.clock]). The world itself never changes: only what\nthe player is told it's made of, and what time its clock is.",
      properties: [],
      methods: [
        {
          name: 'show',
          doc: "Shows [player] [sky]'s twins (null: the world's own biomes again). Nothing for one who's offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'sky',
              type: 'SkyView?',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SkyView',
      doc: "Which twins a player is shown: those of the sky [sky] (`showcase:blood_moon`) at [step] of its fade, in the\nproject's namespace [home] (where every sky's twins are). [clock] is the world clock of the sky's timeline when\nit holds its colours through the night (format's `SkyNight`) and the server started with one: the player is told\nit's at [step], and at 0 again once they're shown anything else.",
      fields: [
        {
          name: 'home',
          type: 'String',
        },
        {
          name: 'sky',
          type: 'String',
        },
        {
          name: 'step',
          type: 'Int',
        },
        {
          name: 'clock',
          type: 'String?',
          default: 'null',
        },
      ],
    },
  ],
} satisfies PlatformFile
