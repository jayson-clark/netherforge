import type { PlatformFile } from '../types.ts'

export default {
  source: 'MobGoalOps.kt',
  decls: [
    {
      kind: 'enum',
      name: 'GoalControl',
      doc: 'What a goal claims of a mob while it runs: no less important goal claiming the same runs with it.',
      params: [],
      entries: [
        {
          name: 'MOVE',
          args: [],
        },
        {
          name: 'LOOK',
          args: [],
        },
        {
          name: 'JUMP',
          args: [],
        },
        {
          name: 'TARGET',
          doc: "What the mob is after: a goal claiming it is one of the mob's targeting goals, never one of its regular goals.",
          args: [],
        },
      ],
    },
    {
      kind: 'record',
      name: 'GoalInfo',
      doc: 'One goal a mob has. [key] is namespaced (`minecraft:random_stroll`,\n`netherforge:guard_post`); a smaller [priority] is more important.',
      fields: [
        {
          name: 'key',
          type: 'String',
        },
        {
          name: 'priority',
          type: 'Int',
        },
        {
          name: 'controls',
          type: 'Set<GoalControl>',
        },
        {
          name: 'running',
          type: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'GoalCallbacks',
      doc: "A goal written in Lua, as the mob's AI calls it (the runtime's\n`mob/MobGoals.kt`). Called on the main thread from inside the server's\nown goal loop, so none of these may change a mob's goals directly; and\nnone of them throws.",
      properties: [],
      methods: [
        {
          name: 'shouldStart',
          params: [],
          returns: 'Boolean',
        },
        {
          name: 'shouldContinue',
          params: [],
          returns: 'Boolean',
        },
        {
          name: 'start',
          params: [],
          returns: 'Unit',
        },
        {
          name: 'tick',
          params: [],
          returns: 'Unit',
        },
        {
          name: 'stop',
          params: [],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'MobGoalOps',
      doc: "Mobs' AI goals (`mob:goals()`, `mob:add_goal`): both the regular goals and\nthe targeting goals, by namespaced key. Everything answers null or false for\nan entity that isn't a mob, or isn't there now. Goals aren't saved: a mob\nthat unloads comes back with the game's.\n\nNever call these from inside [GoalCallbacks]: the server is going through\nthe mob's goals then, and changing them would break its loop. The runtime\nholds such changes until its next tick.",
      properties: [],
      methods: [
        {
          name: 'goals',
          doc: 'Every goal it has, in no particular order.',
          params: [
            {
              name: 'mob',
              type: 'UUID',
            },
          ],
          returns: 'List<GoalInfo>?',
        },
        {
          name: 'remove',
          doc: "Takes off every goal with [key], stopping one that's running. The keys of the goals taken off: empty when it had none.",
          params: [
            {
              name: 'mob',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'List<String>?',
        },
        {
          name: 'remove',
          doc: "Takes off the goal [goal] made, the one [add] was given, if it's still on the mob.",
          params: [
            {
              name: 'mob',
              type: 'UUID',
            },
            {
              name: 'goal',
              type: 'GoalCallbacks',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'clear',
          doc: 'Takes off every goal but those whose key is in [keep]. The keys of the goals taken off.',
          params: [
            {
              name: 'mob',
              type: 'UUID',
            },
            {
              name: 'keep',
              type: 'Set<String>',
            },
          ],
          returns: 'List<String>?',
        },
        {
          name: 'add',
          doc: 'Adds a goal with [key] (namespaced), replacing any the mob has with\nthat key. One claiming [GoalControl.TARGET] (and nothing else) goes with\nits targeting goals, any other with its regular goals.',
          params: [
            {
              name: 'mob',
              type: 'UUID',
            },
            {
              name: 'key',
              type: 'String',
            },
            {
              name: 'priority',
              type: 'Int',
            },
            {
              name: 'controls',
              type: 'Set<GoalControl>',
            },
            {
              name: 'goal',
              type: 'GoalCallbacks',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
