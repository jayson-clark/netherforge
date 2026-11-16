import type { PlatformFile } from '../types.ts'

export default {
  source: 'Bots.kt',
  decls: [
    {
      kind: 'interface',
      name: 'BotOps',
      doc: "Bots: fake players, made in the server, that do what a player's client\nwould (format's `bridge/Bots.kt` says what they are and what each action\nmeans). The dev bridge drives them for tests and coding agents, and\nscripts drive their own (`nf.bots`, the runtime's `bots/ScriptBots`).\n\nA bot is a player like any other to the rest of the platform: it's in\n[PlayerOps.online], its joins, clicks and chat arrive through\n[PlatformEvents] as anyone's do. This group only makes them and drives\nthem.\n\nEverything is called on the main thread, and every callback is made on it.\nFailures (no such bot, an action that can't be done) are\n[IllegalArgumentException]s or [IllegalStateException]s with a sentence\nfor whoever asked; a callback gets them as a failed [Result].",
      properties: [],
      methods: [
        {
          name: 'join',
          doc: "Joins a bot as [join] says (its name not online already) and calls\n[done] once it's in the world: ticks later, since joining loads the\nworld around it.",
          params: [
            {
              name: 'join',
              type: 'BotJoin',
            },
            {
              name: 'done',
              type: '(Result<BotInfo>) -> Unit',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'leave',
          doc: 'Disconnects bot [name], as a player quitting: a bot still joining stops trying (its [join] fails).',
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'leaveAll',
          doc: 'Disconnects every bot (the plugin is stopping).',
          params: [],
          returns: 'Unit',
        },
        {
          name: 'list',
          params: [],
          returns: 'List<BotInfo>',
        },
        {
          name: 'isBot',
          doc: 'Whether the online player [id] is a bot (either kind).',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'act',
          doc: 'Has bot [name] do [action]; [done] is called when it has, now or ticks\nlater (walking, mining). [aim] is where an [BotAction.Interact] or\n[BotAction.Attack] naming a centity was resolved to, by the runtime.',
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'action',
              type: 'BotAction',
            },
            {
              name: 'aim',
              type: 'BotAim?',
            },
            {
              name: 'done',
              type: '(Result<BotActResult>) -> Unit',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'state',
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'BotState',
        },
        {
          name: 'events',
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'since',
              type: 'Int',
            },
          ],
          returns: 'BotEvents',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BotJoin',
      doc: "How a bot joins: as [name], at [at] when given (else where it last was,\nor the spawn), answering resource packs with [pack]. [script] makes it a\nscript's bot rather than the bridge's.",
      fields: [
        {
          name: 'name',
          type: 'String',
        },
        {
          name: 'at',
          type: 'BotPosition?',
          default: 'null',
        },
        {
          name: 'pack',
          type: 'BotPackAnswer',
          default: 'BotPackAnswer.DECLINE',
        },
        {
          name: 'script',
          type: 'ScriptBot?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'ScriptBot',
      doc: "A script's bot. The bridge's are the same player every time (a UUID from\nthe name, saved like anyone's, so a test can leave and come back); a\nscript's is a new player each time it joins: a random UUID, never saved\n(no player data, statistics or advancements), marked as an NPC for other\nplugins (the `NPC` metadata Citizens set, which plugins check to leave\nfake players alone), loading only the chunks right around it\n([VIEW_DISTANCE]), and wearing [skin] when given.",
      fields: [
        {
          name: 'skin',
          type: 'SkinTexture?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SkinTexture',
      doc: "A skin as Mojang's session servers sign it: the profile's `textures` property, base64, and its signature.",
      fields: [
        {
          name: 'value',
          type: 'String',
        },
        {
          name: 'signature',
          type: 'String?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BotAim',
      doc: 'An entity for a bot to click and the point on it to look at.',
      fields: [
        {
          name: 'entity',
          type: 'UUID',
        },
        {
          name: 'at',
          type: 'Vec3',
        },
      ],
    },
  ],
} satisfies PlatformFile
