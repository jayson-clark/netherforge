import type { PlatformFile } from '../types.ts'

export default {
  source: 'PathfindingOps.kt',
  decls: [
    {
      kind: 'interface',
      name: 'PathfindingOps',
      doc: "Mobs' own navigation (`mob:move_to`): the path the game finds and walks\nthem along, whoever started it (a script, or the mob's AI). Everything\nanswers null or false for an entity that isn't a mob, or isn't there now.\nPaper has no event for a path ending, so the runtime watches [hasPath]\neach tick for the walks scripts started (`mob/MobPaths.kt`).",
      properties: [],
      methods: [
        {
          name: 'moveTo',
          doc: "Finds a path to [to], in the mob's own world, and starts walking it at\n[speed] times its walking speed, replacing the path it was on. False\nwhen there's no path from where it is.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'to',
              type: 'Vec3',
            },
            {
              name: 'speed',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'stop',
          doc: 'Stops it walking.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hasPath',
          doc: "Whether it's walking a path now.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'pathEnd',
          doc: "Where the path it's walking ends, or null when it isn't walking one.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Vec3?',
        },
      ],
    },
  ],
} satisfies PlatformFile
