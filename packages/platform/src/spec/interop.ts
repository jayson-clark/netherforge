import type { PlatformFile } from '../types.ts'

export default {
  source: 'Interop.kt',
  decls: [
    {
      kind: 'interface',
      name: 'PluginOps',
      doc: 'Other plugins on the server, for what a package declares with\n`requires: { "plugins": [...] }` (`nf.economy`, `nf.placeholders`). The\nruntime owns the types here; only the adapter knows the plugin\'s own API\n(Vault, PlaceholderAPI), and only behind these interfaces. There is no\ngeneral way to call another plugin.',
      properties: [],
      methods: [
        {
          name: 'isEnabled',
          doc: 'Whether a plugin called [name] is on the server and enabled. [name] is as\na package declares it (`vault`), compared without regard to case.\nAnswers for now: a plugin that enables later (the server enables plugins\nin its own order) is seen as soon as it has.',
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'EconomyOps',
      doc: "The server's economy (Vault's), in plain numbers. Players are offline or\nonline alike. Looked up at each call ([Platform.economy]), never kept.",
      properties: [],
      methods: [
        {
          name: 'balance',
          doc: 'What [player] has: 0 for someone the economy has no account for.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Double',
        },
        {
          name: 'deposit',
          doc: 'Gives [player] [amount] (above 0). Their new balance, or null when the economy refused.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'amount',
              type: 'Double',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'withdraw',
          doc: 'Takes [amount] (above 0) from [player]. Their new balance, or null when the economy refused (not enough, say).',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'amount',
              type: 'Double',
            },
          ],
          returns: 'Double?',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PlaceholderResolver',
      doc: 'Answers `%<namespace>_<key>%`: [key] is what follows the namespace and its `_`; null leaves the placeholder as written.',
      properties: [],
      methods: [
        {
          name: 'resolve',
          doc: "May be called from any thread, as the plugin asking decides (async chat\nand scoreboard plugins ask off the main thread): [player] is who it's\nfor, or null for none.",
          params: [
            {
              name: 'player',
              type: 'UUID?',
            },
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'String?',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PlaceholderOps',
      doc: 'PlaceholderAPI: filling placeholders in, and offering our own. Only used while the plugin is enabled ([PluginOps.isEnabled]).',
      properties: [],
      methods: [
        {
          name: 'parse',
          doc: '[text] with every placeholder filled in for [player] (none: only those that need nobody). Main thread.',
          params: [
            {
              name: 'player',
              type: 'UUID?',
            },
            {
              name: 'text',
              type: 'String',
            },
          ],
          returns: 'String',
        },
        {
          name: 'register',
          doc: 'Offers `%[namespace]_<key>%`, answered by [resolver], until [unregister]\nor the plugin goes. False when something else already offers that namespace.',
          params: [
            {
              name: 'namespace',
              type: 'String',
            },
            {
              name: 'resolver',
              type: 'PlaceholderResolver',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'isRegistered',
          doc: "Whether [namespace] is offered now by what [register] gave it (PlaceholderAPI forgets them when it's reloaded or disabled).",
          params: [
            {
              name: 'namespace',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'unregister',
          doc: 'Stops offering [namespace], if this registered it.',
          params: [
            {
              name: 'namespace',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
  ],
} satisfies PlatformFile
