import type { PlatformFile } from '../types.ts'

export default {
  source: 'Entities.kt',
  decls: [
    {
      kind: 'enum',
      name: 'EntityCategory',
      doc: "What kind of entity it is, for what can be done with it: the API's classes\nbelow `Entity` (a player is [LIVING], with [EntityInfo.player] set).",
      params: [],
      entries: [
        {
          name: 'OTHER',
          doc: 'Anything else: an arrow, a minecart, a lightning bolt.',
          args: [],
        },
        {
          name: 'DROPPED_ITEM',
          doc: 'An item lying on the ground.',
          args: [],
        },
        {
          name: 'LIVING',
          doc: "Something with health that isn't a mob: an armour stand, a player.",
          args: [],
        },
        {
          name: 'MOB',
          doc: 'Something with health and AI: a zombie, a cow, a bat.',
          args: [],
        },
        {
          name: 'MANNEQUIN',
          doc: 'A figure that looks like a player (`minecraft:mannequin`): living, with no AI.',
          args: [],
        },
      ],
    },
    {
      kind: 'record',
      name: 'EntityInfo',
      doc: 'What an entity is now, for finding and filtering it. [player] is set for a player.',
      fields: [
        {
          name: 'id',
          type: 'UUID',
        },
        {
          name: 'kind',
          type: 'String',
          doc: 'Namespaced: `minecraft:zombie`.',
        },
        {
          name: 'location',
          type: 'Location',
          doc: 'Where its feet are, with its facing.',
        },
        {
          name: 'category',
          type: 'EntityCategory',
        },
        {
          name: 'player',
          type: 'PlayerRef?',
        },
        {
          name: 'tags',
          type: 'Set<String>',
          doc: 'Its scoreboard tags.',
        },
      ],
    },
    {
      kind: 'enum',
      name: 'EntityFlag',
      doc: "An entity's yes-or-no properties. Some only some entities have; asking another answers null.",
      params: [
        {
          name: 'settable',
          type: 'Boolean',
        },
      ],
      entries: [
        {
          name: 'ON_GROUND',
          args: ['false'],
        },
        {
          name: 'CUSTOM_NAME_VISIBLE',
          args: ['true'],
        },
        {
          name: 'GLOWING',
          args: ['true'],
        },
        {
          name: 'VISIBLE',
          doc: 'Shown to players by default (`setVisibleByDefault`).',
          args: ['true'],
        },
        {
          name: 'SILENT',
          args: ['true'],
        },
        {
          name: 'GRAVITY',
          args: ['true'],
        },
        {
          name: 'INVULNERABLE',
          args: ['true'],
        },
        {
          name: 'AI',
          doc: 'Mobs only.',
          args: ['true'],
        },
        {
          name: 'SNEAKING',
          args: ['false'],
        },
        {
          name: 'SPRINTING',
          args: ['false'],
        },
        {
          name: 'FLYING',
          args: ['true'],
        },
        {
          name: 'CAN_FLY',
          args: ['true'],
        },
      ],
    },
    {
      kind: 'enum',
      name: 'EntityNumber',
      doc: "An entity's numbers. Some only some entities have; asking another answers null.",
      params: [
        {
          name: 'settable',
          type: 'Boolean',
        },
      ],
      entries: [
        {
          name: 'HEALTH',
          args: ['true'],
        },
        {
          name: 'MAX_HEALTH',
          args: ['false'],
        },
        {
          name: 'FOOD',
          args: ['true'],
        },
        {
          name: 'SATURATION',
          args: ['true'],
        },
        {
          name: 'LEVEL',
          args: ['true'],
        },
        {
          name: 'EXPERIENCE_PROGRESS',
          args: ['true'],
        },
        {
          name: 'WALK_SPEED',
          args: ['true'],
        },
        {
          name: 'FLY_SPEED',
          args: ['true'],
        },
        {
          name: 'HELD_SLOT',
          doc: 'The selected hotbar slot, 0 to 8.',
          args: ['true'],
        },
        {
          name: 'PING',
          doc: 'Milliseconds, as the server estimates it.',
          args: ['false'],
        },
        {
          name: 'PICKUP_DELAY',
          doc: 'Dropped items: ticks until it can be picked up.',
          args: ['true'],
        },
      ],
    },
    {
      kind: 'record',
      name: 'StatusEffectData',
      doc: 'One status effect: [ticks] -1 for ever; [amplifier] 0 is level I.',
      fields: [
        {
          name: 'effect',
          type: 'String',
        },
        {
          name: 'ticks',
          type: 'Int',
        },
        {
          name: 'amplifier',
          type: 'Int',
          default: '0',
        },
        {
          name: 'ambient',
          type: 'Boolean',
          default: 'false',
        },
        {
          name: 'particles',
          type: 'Boolean',
          default: 'true',
        },
        {
          name: 'icon',
          type: 'Boolean',
          default: 'true',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SpawnSetup',
      doc: 'How a new entity is set up before anything (an `entity_spawn` handler included) sees it.',
      fields: [
        {
          name: 'customName',
          type: 'String?',
          default: 'null',
        },
        {
          name: 'tags',
          type: 'List<String>',
          default: 'emptyList()',
        },
        {
          name: 'data',
          type: 'String?',
          default: 'null',
          doc: 'Its script data, as `setData` takes it.',
        },
        {
          name: 'velocity',
          type: 'Vec3?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'EntityHit',
      doc: "What a ray hit: the entity, where it entered its box, and that face's normal.",
      fields: [
        {
          name: 'id',
          type: 'UUID',
        },
        {
          name: 'position',
          type: 'Vec3',
        },
        {
          name: 'normal',
          type: 'Vec3',
        },
      ],
    },
    {
      kind: 'record',
      name: 'EntityBox',
      doc: "An entity's bounding box in the world: [kind] namespaced (`minecraft:zombie`), [player] whether it's a player.",
      fields: [
        {
          name: 'id',
          type: 'UUID',
        },
        {
          name: 'kind',
          type: 'String',
        },
        {
          name: 'player',
          type: 'Boolean',
        },
        {
          name: 'box',
          type: 'Box',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'WorldEntityOps',
      doc: "Entities by UUID. Everything answers null or false for an entity that isn't\nthere now: gone, in an unloaded chunk, or one of NetherForge's own.",
      properties: [],
      methods: [
        {
          name: 'info',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'EntityInfo?',
        },
        {
          name: 'list',
          doc: "Every entity in a world's loaded chunks, players included; empty for a world that doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'List<EntityInfo>',
        },
        {
          name: 'eye',
          doc: 'Where it looks from and which way, with a normalised direction.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Ray?',
        },
        {
          name: 'velocity',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Vec3?',
        },
        {
          name: 'setVelocity',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'velocity',
              type: 'Vec3',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'teleport',
          doc: 'Moves it, passengers and all.',
          params: [
            {
              name: 'id',
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
          name: 'setRotation',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'yaw',
              type: 'Double',
            },
            {
              name: 'pitch',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'bodyYaw',
          doc: "Which way a living entity's body faces, in Minecraft degrees: not its look\n(a player's body follows their walking and turns only after their head has\ngone far enough). Null for anything not living, or gone.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'remove',
          doc: 'Takes it out of the world, without dying. Never a player.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'flag',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'flag',
              type: 'EntityFlag',
            },
          ],
          returns: 'Boolean?',
        },
        {
          name: 'setFlag',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'flag',
              type: 'EntityFlag',
            },
            {
              name: 'value',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'number',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'number',
              type: 'EntityNumber',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'setNumber',
          doc: "The runtime has checked [value]'s range.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'number',
              type: 'EntityNumber',
            },
            {
              name: 'value',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'name',
          doc: "What players see it called, as MiniMessage: its custom name or its kind's name.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'customName',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setCustomName',
          params: [
            {
              name: 'id',
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
          name: 'addTag',
          doc: 'False when it already had it.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'tag',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'removeTag',
          doc: "False when it didn't have it.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'tag',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'data',
          doc: "The JSON a script keeps on it (`entity:data()`), in its persistent data; null when there's none.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setData',
          doc: 'Stores (or with null, clears) its JSON.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'json',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hide',
          doc: 'Hides it from one online player, as `Player#hideEntity` does.',
          params: [
            {
              name: 'viewer',
              type: 'UUID',
            },
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'show',
          params: [
            {
              name: 'viewer',
              type: 'UUID',
            },
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'passengers',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'List<UUID>?',
        },
        {
          name: 'addPassenger',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'passenger',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'removePassenger',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'passenger',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'vehicle',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'damage',
          doc: 'Hurts a living entity as an attack would, by [source] when given.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'amount',
              type: 'Double',
            },
            {
              name: 'source',
              type: 'UUID?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'effects',
          doc: "Null for an entity that isn't living.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'List<StatusEffectData>?',
        },
        {
          name: 'addEffect',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'effect',
              type: 'StatusEffectData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'removeEffect',
          doc: "False when it didn't have it.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'effect',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'effectExists',
          doc: 'Whether the server has a status effect with this namespaced id.',
          params: [
            {
              name: 'effect',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'equipment',
          doc: '[slot] is the Lua name: `main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`, `body`.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'slot',
              type: 'String',
            },
          ],
          returns: 'ItemData?',
        },
        {
          name: 'setEquipment',
          params: [
            {
              name: 'id',
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
          name: 'target',
          doc: 'What a mob is after.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'setTarget',
          doc: "False for an entity that isn't a mob.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'target',
              type: 'UUID?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'item',
          doc: "A dropped item's stack.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'ItemData?',
        },
        {
          name: 'setItem',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'spawnable',
          doc: 'Whether [kind] (namespaced) is an entity type scripts can spawn: not a player, not something only the game makes.',
          params: [
            {
              name: 'kind',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'spawn',
          doc: "Spawns one, set up as [setup] says before it's added; null when it couldn't be (or something cancelled it).",
          params: [
            {
              name: 'kind',
              type: 'String',
            },
            {
              name: 'at',
              type: 'Location',
            },
            {
              name: 'setup',
              type: 'SpawnSetup',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'spawnItem',
          doc: "Drops an item stack; null when it couldn't be.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'position',
              type: 'Vec3',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'raycast',
          doc: "The first entity's box a ray hits within [maxDistance], leaving out [ignore]. [direction] is a unit vector.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'origin',
              type: 'Vec3',
            },
            {
              name: 'direction',
              type: 'Vec3',
            },
            {
              name: 'maxDistance',
              type: 'Double',
            },
            {
              name: 'ignore',
              type: 'Set<UUID>',
            },
          ],
          returns: 'EntityHit?',
        },
        {
          name: 'boxes',
          doc: "Every entity whose bounding box reaches into [region] (world coordinates) in [world], players\nincluded, as the server measures it now: only in loaded chunks, never one of ours (tagged) or a\nplayer in spectator mode, which nothing can touch. What the spatial queries (`world:overlap_*`,\n`sweep`, `nearest`) and centities' `touch` test.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'region',
              type: 'Box',
            },
          ],
          returns: 'List<EntityBox>',
        },
      ],
    },
    {
      kind: 'union',
      name: 'InventoryRef',
      doc: 'Where a real inventory is.',
      cases: [
        {
          name: 'Player',
          fields: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
        },
        {
          name: 'EnderChest',
          fields: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
        },
        {
          name: 'Entity',
          fields: [
            {
              name: 'entity',
              type: 'UUID',
            },
          ],
        },
        {
          name: 'Block',
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
          ],
        },
        {
          name: 'Storage',
          fields: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'size',
              type: 'Int',
            },
          ],
        },
      ],
    },
    {
      kind: 'record',
      name: 'StackFilter',
      doc: "What a stack must be to be worth reading in full for an [ItemMatch][dev.netherforge.plugin.item.ItemMatch]:\nits kind (`minecraft:paper`, as [GameIds] normalizes it) and the project\nitem it's a stack of (as the project's files name it: `ruby`, `acme:ruby`), each only when given. A stack that\nfails either can't match, whatever else the match says.",
      fields: [
        {
          name: 'kind',
          type: 'String?',
        },
        {
          name: 'item',
          type: 'String?',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'InventoryOps',
      doc: "Real inventories. Everything answers null or false for one that isn't\nthere: a player offline, a block that isn't a container (or in an unloaded\nchunk), an entity without one. A [InventoryRef.Storage] is never on anyone's\nscreen by itself ([viewers] none, [open] false): menus show it, slot by slot.",
      properties: [],
      methods: [
        {
          name: 'kind',
          doc: "`player`, `ender_chest`, `chest`, `barrel`, …: the inventory's type, lowercase.",
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'size',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'contents',
          doc: 'Every slot, in order, null for an empty one.',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
          ],
          returns: 'List<ItemData?>?',
        },
        {
          name: 'item',
          doc: "One slot (the runtime has checked it's in range), null for an empty\none: only that stack is read, where [contents] reads them all.",
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
            {
              name: 'slot',
              type: 'Int',
            },
          ],
          returns: 'ItemData?',
          defaulted: true,
        },
        {
          name: 'contents',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
            {
              name: 'filter',
              type: 'StackFilter',
            },
          ],
          returns: 'List<ItemData?>?',
          defaulted: true,
        },
        {
          name: 'setItem',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
            {
              name: 'slot',
              type: 'Int',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'add',
          doc: "Puts [item] wherever it fits, topping up matching stacks first: how many didn't fit, or null when it isn't there.",
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'clear',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'viewers',
          doc: 'Who has it open on their screen.',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
          ],
          returns: 'List<UUID>',
        },
        {
          name: 'open',
          doc: 'Shows it to an online player.',
          params: [
            {
              name: 'ref',
              type: 'InventoryRef',
            },
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
      kind: 'record',
      name: 'BossBarLook',
      doc: 'What a boss bar shows. [color] and [style] are the Lua names (`pink`, `notched_6`).',
      fields: [
        {
          name: 'text',
          type: 'String',
        },
        {
          name: 'progress',
          type: 'Double',
        },
        {
          name: 'color',
          type: 'String',
        },
        {
          name: 'style',
          type: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'BossBarOps',
      doc: 'Boss bars, by an id the runtime picks.',
      properties: [],
      methods: [
        {
          name: 'create',
          params: [
            {
              name: 'id',
              type: 'Int',
            },
            {
              name: 'look',
              type: 'BossBarLook',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'update',
          params: [
            {
              name: 'id',
              type: 'Int',
            },
            {
              name: 'look',
              type: 'BossBarLook',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'show',
          doc: 'False when the player is offline.',
          params: [
            {
              name: 'id',
              type: 'Int',
            },
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hide',
          params: [
            {
              name: 'id',
              type: 'Int',
            },
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'remove',
          doc: 'Off every screen, forgotten.',
          params: [
            {
              name: 'id',
              type: 'Int',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'SidebarOps',
      doc: "Per-player sidebars. Showing one gives the player a scoreboard of their\nown that mirrors the main scoreboard's teams (the adapter keeps them in\nstep); hiding it gives them the main scoreboard back.",
      properties: [],
      methods: [
        {
          name: 'show',
          doc: "Shows (or updates) a player's sidebar: MiniMessage title and at most 15 lines, top first, without score numbers.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'title',
              type: 'String',
            },
            {
              name: 'lines',
              type: 'List<String>',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hide',
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
  ],
} satisfies PlatformFile
