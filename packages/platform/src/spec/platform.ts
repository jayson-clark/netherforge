import type { PlatformFile } from '../types.ts'

export default {
  source: 'Platform.kt',
  decls: [
    {
      kind: 'interface',
      name: 'Platform',
      doc: "Everything the runtime needs from a Minecraft server, in NetherForge's own\ntypes.\n\nThe runtime never sees a Paper class. One adapter per Minecraft version\n(`apps/plugin/paper-<version>`) implements this with the Paper API, and the tests\nimplement it with an in-memory fake. That split is what lets a new Minecraft\nversion be a new adapter rather than a hunt through the runtime.\n\nGrouped by subject rather than flat, so a new capability is a new group\nadded without reshaping the others. Every group is required: an adapter\nimplements all of them, and the contract suites (the runtime's test\nfixtures) check each against the fake and against Paper. A feature a\nMinecraft version lacks is gated by version, never answered with a\nmissing group.\n\nEvery call happens on the server's main thread unless it says otherwise.",
      properties: [
        {
          name: 'info',
          type: 'ServerInfo',
        },
        {
          name: 'game',
          doc: "Game facts from the live server's registries. Never a bundled table.",
          type: 'GameData',
        },
        {
          name: 'log',
          type: 'PlatformLog',
        },
        {
          name: 'worlds',
          type: 'WorldOps',
        },
        {
          name: 'entities',
          type: 'EntityOps',
        },
        {
          name: 'clientEntities',
          doc: "Entities only their viewers' clients know of: what private centities are made of.",
          type: 'ClientEntityOps',
        },
        {
          name: 'players',
          type: 'PlayerOps',
        },
        {
          name: 'commands',
          type: 'CommandOps',
        },
        {
          name: 'blocks',
          doc: 'Blocks in the world: reading and changing them, and the data scripts keep on them.',
          type: 'BlockOps',
        },
        {
          name: 'worldEntities',
          doc: 'Entities as scripts handle them, by UUID: mobs, dropped items, players (`platform/Entities.kt`).',
          type: 'WorldEntityOps',
        },
        {
          name: 'mannequins',
          doc: 'What only a mannequin has: its skin, the line under its name, its pose (`platform/Mannequins.kt`).',
          type: 'MannequinOps',
        },
        {
          name: 'inventories',
          doc: "Real inventories: players', ender chests, container blocks', entities'.",
          type: 'InventoryOps',
        },
        {
          name: 'attributes',
          doc: "Entities' attributes (`platform/Attributes.kt`).",
          type: 'AttributeOps',
        },
        {
          name: 'pathfinding',
          doc: 'Mobs walking paths (`platform/PathfindingOps.kt`).',
          type: 'PathfindingOps',
        },
        {
          name: 'mobGoals',
          doc: "Mobs' AI goals (`platform/MobGoalOps.kt`).",
          type: 'MobGoalOps',
        },
        {
          name: 'bossBars',
          doc: 'Boss bars.',
          type: 'BossBarOps',
        },
        {
          name: 'sidebars',
          doc: 'Per-player sidebars.',
          type: 'SidebarOps',
        },
        {
          name: 'teams',
          doc: 'Scoreboard teams and the line under name tags (`platform/Teams.kt`).',
          type: 'TeamOps',
        },
        {
          name: 'playerList',
          doc: "Players' entries in the player list (`platform/Teams.kt`).",
          type: 'PlayerListOps',
        },
        {
          name: 'particles',
          doc: 'Particles.',
          type: 'ParticleOps',
        },
        {
          name: 'screens',
          doc: 'Maps drawn as screens: their pixels, the frames they hang in (`platform/ScreenOps.kt`).',
          type: 'ScreenOps',
        },
        {
          name: 'sounds',
          doc: 'Sounds.',
          type: 'SoundOps',
        },
        {
          name: 'performance',
          doc: 'How the server is keeping up.',
          type: 'PerformanceOps',
        },
        {
          name: 'text',
          doc: 'MiniMessage, parsed the way the adapter parses everything players see.',
          type: 'TextOps',
        },
        {
          name: 'menus',
          doc: 'Menu windows.',
          type: 'MenuOps',
        },
        {
          name: 'dialogs',
          doc: 'Dialog screens.',
          type: 'DialogOps',
        },
        {
          name: 'resourcePacks',
          doc: 'Sending a resource pack to a player.',
          type: 'ResourcePackOps',
        },
        {
          name: 'projectItems',
          doc: 'Project items on real stacks: rewriting stale looks.',
          type: 'ProjectItemOps',
        },
        {
          name: 'itemViews',
          doc: "How stacks look to the one player they're sent to ([ItemViewOps]).",
          type: 'ItemViewOps',
        },
        {
          name: 'recipes',
          doc: "Recipes the project adds to the server, and players' recipe books.",
          type: 'RecipeOps',
        },
        {
          name: 'loot',
          doc: "The game's own loot tables, rolled by the server (`platform/Loot.kt`).",
          type: 'LootOps',
        },
        {
          name: 'worldManager',
          doc: 'Making, loading, copying and unloading worlds (`platform/WorldAdmin.kt`).',
          type: 'WorldManagerOps',
        },
        {
          name: 'borders',
          doc: "World borders and players' own.",
          type: 'BorderOps',
        },
        {
          name: 'structures',
          doc: 'Saving and placing structures.',
          type: 'StructureOps',
        },
        {
          name: 'playerViews',
          doc: "What one player is shown that isn't so, and their camera, compass and view distance (`platform/PlayerAdmin.kt`).",
          type: 'PlayerViewOps',
        },
        {
          name: 'serverAdmin',
          doc: "The server's settings, who has played, the whitelist and bans.",
          type: 'ServerAdminOps',
        },
        {
          name: 'permissions',
          doc: 'The permission nodes the project sets on players.',
          type: 'PermissionOps',
        },
        {
          name: 'advancements',
          doc: "Players' advancements.",
          type: 'AdvancementOps',
        },
        {
          name: 'plugins',
          doc: 'Which other plugins are on the server (`platform/Interop.kt`).',
          type: 'PluginOps',
        },
        {
          name: 'placeholders',
          doc: "PlaceholderAPI (`platform/Interop.kt`): used only while it's enabled.",
          type: 'PlaceholderOps',
        },
        {
          name: 'datapacks',
          doc: 'The datapack the server started with, and building one again (`platform/Datapacks.kt`).',
          type: 'DatapackOps',
        },
        {
          name: 'skies',
          doc: "What players are shown the world's biomes as: a sky's twins ([SkyOps]). Nothing by default (a server without).",
          type: 'SkyOps',
          defaulted: true,
        },
        {
          name: 'pause',
          type: 'PauseOps',
        },
        {
          name: 'bots',
          doc: "Fake players (`platform/Bots.kt`): the dev bridge's, for tests and\nagents, and scripts' (`nf.bots`). Whether anyone may make one is the\nruntime's to decide (the bridge, the owner's `bots:`), not the adapter's.",
          type: 'BotOps',
        },
      ],
      methods: [
        {
          name: 'economy',
          doc: "The server's economy (Vault's), looked up now; null when Vault isn't\nenabled or no economy plugin has registered with it. Economies register\nafter other plugins have enabled, so this is asked at each use and never\nkept.",
          params: [],
          returns: 'EconomyOps?',
        },
        {
          name: 'bind',
          doc: "Connects the adapter to the runtime, once, when it enables, before\nanything is shown: from then on the server's happenings go to [events]\n(the adapter registers whatever listeners it needs; nothing else wires\nthem), and `<glyph:ui/coin>` in any MiniMessage the adapter parses\n(names, lore, titles, dialogs, text displays, chat) inserts [glyphs]'\nanswer for the reference, in the default font, or nothing for null.\n[glyphs] may be called from any thread.\n\n[items] answers a project item's current look by reference (`ruby`, or\nthe `shop:ruby` a stack carries; null for an item the project doesn't\nhave): every item the adapter builds whose [ItemDef.item] names one is\nresolved against it (`ProjectItems.resolve`) and stamped with its\nnamespaced id and hash, and stacks stamped with an old hash are what\n[ProjectItemOps.refresh] rewrites.\n\n[namespace] is the project's (it changes only when the project\nrestarts): what the adapter names everything it registers or persists\nfor the project under. References the runtime hands over are as files\nand scripts write them (`ruby`, `ui/gem`); the adapter resolves them in\n[namespace] when it writes them to the game (`shop:ruby` on a stack,\nthe item model `shop:ui/gem`, the recipe `shop:ruby_sword`, a modifier's\ndefault id `shop:attack_damage_0`), and reads them back the same way,\nthe project's own without the namespace.\n\n[blocks] is the block state one of the project's blocks is held as now (canonical text,\n`minecraft:note_block[instrument=bit,note=3,powered=true]`), by the name the server knows it by\n(`ruby_ore`, `library:gem_ore`), or null for a block it doesn't hold: an item's `canBreak` and\n`canPlaceOn` name the project's blocks, and the adapter writes their states. They can move when the\nproject's blocks change, which is when [ProjectItemOps.refresh] finds a stack stale.",
          params: [
            {
              name: 'events',
              type: 'PlatformEvents',
            },
            {
              name: 'glyphs',
              type: '(reference: String) -> String?',
            },
            {
              name: 'items',
              type: '(reference: String) -> ItemLook?',
            },
            {
              name: 'blocks',
              type: '(name: String) -> String?',
            },
            {
              name: 'namespace',
              type: '() -> String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'bindText',
          doc: "How text moves its cursor (`SpaceTags`): [space] is what `<space:N>`\ninserts (the pack's space characters, or null while no pack is built to\ndraw them), and [layout] is MiniMessage with each `<at>` turned into\nthe moves that place it, which the adapter applies to every text before\nit parses it. Until it's called, `<space>` inserts nothing and `<at>`\ndraws what it holds in line.",
          params: [
            {
              name: 'space',
              type: '(px: Int) -> String?',
            },
            {
              name: 'layout',
              type: '(text: String) -> String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'watch',
          doc: 'Whether a script listens for [event] now. The adapter watches these\nonly while one does, because watching them costs even when nobody\ncares: the runtime calls this whenever the answer changes (never twice\nin a row with the same answer), starting from not listening.',
          params: [
            {
              name: 'event',
              type: 'WatchedEvent',
            },
            {
              name: 'listening',
              type: 'Boolean',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'exportGameData',
          doc: 'Everything the editor caches about this Minecraft version: blocks with\ntheir properties and defaults, items, entity types, and collision boxes.\nCalled rarely (once per version per machine), so it may take a moment.',
          params: [],
          returns: 'GameDataBundle',
        },
        {
          name: 'shutdownServer',
          doc: 'Stops the server cleanly (worlds saved, plugins disabled), as `/stop`\nwould. Only a dev server whose editor has gone away asks for this: see\n[dev.netherforge.plugin.bridge.BridgeClient]. Called on the main thread.',
          params: [
            {
              name: 'reason',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'record',
      name: 'ServerInfo',
      fields: [
        {
          name: 'minecraftVersion',
          type: 'String',
          doc: "The running server's Minecraft version, `26.3`.",
        },
        {
          name: 'pluginVersion',
          type: 'String',
        },
        {
          name: 'supportedTargets',
          type: 'List<String>',
          doc: 'Project targets this adapter runs. A project naming anything else is refused.',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PerformanceOps',
      doc: 'How the server is keeping up, as Minecraft measures it.',
      properties: [],
      methods: [
        {
          name: 'ticksPerSecond',
          doc: 'Ticks a second, averaged over the last minute: 20 when it keeps up.',
          params: [],
          returns: 'Double',
        },
        {
          name: 'tickMilliseconds',
          doc: 'How long a tick has taken lately, in milliseconds, averaged over the last 100 ticks.',
          params: [],
          returns: 'Double',
        },
        {
          name: 'lastTickMilliseconds',
          doc: 'How long the last completed tick took, in milliseconds.',
          params: [],
          returns: 'Double',
          defaulted: true,
        },
      ],
    },
    {
      kind: 'interface',
      name: 'TextOps',
      doc: "MiniMessage helpers, with the adapter's own MiniMessage (glyph tags included).",
      properties: [],
      methods: [
        {
          name: 'escape',
          doc: '[text] escaped so MiniMessage shows it as written rather than reading tags in it.',
          params: [
            {
              name: 'text',
              type: 'String',
            },
          ],
          returns: 'String',
        },
        {
          name: 'strip',
          doc: 'The plain text [miniMessage] reads as, tags removed.',
          params: [
            {
              name: 'miniMessage',
              type: 'String',
            },
          ],
          returns: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PauseOps',
      doc: "What a server needs while the debugger holds its main thread at a\nbreakpoint, for as long as the person debugging likes: nothing ticks, so\nthe server must not decide it has hung (Paper's watchdog dumps the threads\nafter a few seconds and stops the server after a minute), and its players\nmust not time out (a client drops a server it hears nothing from for 30\nseconds). Only a dev server ever pauses.",
      properties: [],
      methods: [
        {
          name: 'hold',
          doc: "Called on the held main thread about once a second while it's paused:\ntells the server's watchdog it's alive, and shows every online player\n[message] on their action bar, which is also what keeps their\nconnections from timing out.",
          params: [
            {
              name: 'message',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'PlatformLog',
      doc: 'The server console. The runtime decides what also goes to the editor.',
      properties: [],
      methods: [
        {
          name: 'info',
          params: [
            {
              name: 'message',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'warn',
          params: [
            {
              name: 'message',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'error',
          params: [
            {
              name: 'message',
              type: 'String',
            },
            {
              name: 'cause',
              type: 'Throwable?',
              default: 'null',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'record',
      name: 'Location',
      doc: "A world by name and a position in blocks. [yaw] and [pitch] are degrees, for a player's facing.",
      fields: [
        {
          name: 'world',
          type: 'String',
        },
        {
          name: 'x',
          type: 'Double',
        },
        {
          name: 'y',
          type: 'Double',
        },
        {
          name: 'z',
          type: 'Double',
        },
        {
          name: 'yaw',
          type: 'Double',
          default: '0.0',
        },
        {
          name: 'pitch',
          type: 'Double',
          default: '0.0',
        },
      ],
    },
    {
      kind: 'record',
      name: 'Ray',
      doc: "A ray in a world, as a player's line of sight. The direction is normalised.",
      fields: [
        {
          name: 'world',
          type: 'String',
        },
        {
          name: 'x',
          type: 'Double',
        },
        {
          name: 'y',
          type: 'Double',
        },
        {
          name: 'z',
          type: 'Double',
        },
        {
          name: 'dx',
          type: 'Double',
        },
        {
          name: 'dy',
          type: 'Double',
        },
        {
          name: 'dz',
          type: 'Double',
        },
      ],
    },
    {
      kind: 'record',
      name: 'PlayerRef',
      fields: [
        {
          name: 'uuid',
          type: 'UUID',
        },
        {
          name: 'name',
          type: 'String',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BlockRef',
      doc: 'A block in the world, for events: `id` is namespaced, `state` is the full canonical block state.',
      fields: [
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
          name: 'id',
          type: 'String',
        },
        {
          name: 'state',
          type: 'String',
        },
      ],
    },
  ],
} satisfies PlatformFile
