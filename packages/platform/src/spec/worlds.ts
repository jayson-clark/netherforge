import type { PlatformFile } from '../types.ts'

export default {
  source: 'Worlds.kt',
  decls: [
    {
      kind: 'record',
      name: 'BlockSnapshot',
      doc: 'What a block is right now. [state] is the full canonical block state (`minecraft:oak_stairs[facing=east,…]`).',
      fields: [
        {
          name: 'state',
          type: 'String',
        },
        {
          name: 'air',
          type: 'Boolean',
        },
        {
          name: 'solid',
          type: 'Boolean',
          doc: "Something players stand on and can't walk through.",
        },
        {
          name: 'liquid',
          type: 'Boolean',
          doc: 'Water or lava.',
        },
        {
          name: 'light',
          type: 'Int',
          doc: 'The brighter of block light and sky light, 0 to 15.',
        },
        {
          name: 'skyLight',
          type: 'Int',
        },
        {
          name: 'water',
          type: 'Boolean',
          doc: 'It holds water: water itself, or a block with water in it (waterlogged, kelp, seagrass). Never lava.',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'BlockOps',
      doc: "Blocks. Nothing here ever loads a chunk: a block in a chunk that isn't\nloaded (or a world that doesn't exist) reads as null and can't be changed.",
      properties: [],
      methods: [
        {
          name: 'get',
          params: [
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
          returns: 'BlockSnapshot?',
        },
        {
          name: 'set',
          doc: 'Sets a canonical block state the server has. [update]: neighbours react, as to a player placing it.',
          params: [
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
            {
              name: 'update',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'breakNaturally',
          doc: 'Breaks it with its drops for [tool] (none given: as by hand). False for air.',
          params: [
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
              name: 'tool',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'data',
          doc: "The JSON a script keeps for this block position (`block:data()`), stored with the chunk; null when there's none.",
          params: [
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
          returns: 'String?',
        },
        {
          name: 'setData',
          doc: "Stores (or with null, clears) a block position's JSON. False when its chunk isn't loaded.",
          params: [
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
              name: 'json',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'withData',
          doc: "The positions in a loaded chunk with JSON stored, or null when the chunk isn't loaded.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'List<BlockVector>?',
        },
        {
          name: 'records',
          doc: "Every custom block the server remembers in a chunk (what [setRecord] stored\nthere, saved with the chunk), or null when the chunk isn't loaded.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'List<BlockRecord>?',
        },
        {
          name: 'setRecord',
          doc: "Stores (or with null, clears) the record of the custom block at a position, saved with its chunk. False when the chunk isn't loaded.",
          params: [
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
              name: 'json',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'legend',
          doc: "What the runtime keeps about a whole chunk's custom blocks (which carrier state each of them was placed as,\nwhat [setLegend] stored, saved with the chunk), or null when there's none or the chunk isn't loaded.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'setLegend',
          doc: "Stores (or with null, clears) a chunk's [legend]. False when the chunk isn't loaded.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
            {
              name: 'json',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'find',
          doc: "Where in a loaded chunk's blocks any of [states] (canonical) is, and\nwhich of them is there (its index in [states] plus one), or null when\nthe chunk isn't loaded. Quick when the chunk has none of them, and\ncheap however many it has.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
            {
              name: 'states',
              type: 'List<String>',
            },
          ],
          returns: 'ChunkGrid?',
        },
        {
          name: 'box',
          doc: "Every block from [x0], [y0], [z0] to [x1], [y1], [z1] (both included, the first the lowest), as an index into\nthe states the box holds: x fastest, then z, then y. Null when any of it is in a chunk that isn't loaded, or\nthe world isn't there. This reads block by block; a server reads the chunks' sections at once.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'x0',
              type: 'Int',
            },
            {
              name: 'y0',
              type: 'Int',
            },
            {
              name: 'z0',
              type: 'Int',
            },
            {
              name: 'x1',
              type: 'Int',
            },
            {
              name: 'y1',
              type: 'Int',
            },
            {
              name: 'z1',
              type: 'Int',
            },
          ],
          returns: 'BlockBoxRead?',
          defaulted: true,
        },
        {
          name: 'fingerprint',
          doc: "A number that changes whenever the blocks of a loaded chunk that could be any of [states] change: the same\nnumber means [find] would find the same, so the chunk needn't be read again. Much cheaper than [find]. Null\nwhen the server can't tell that way, or the chunk isn't loaded: then anything may have changed.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
            {
              name: 'states',
              type: 'List<String>',
            },
          ],
          returns: 'Long?',
          defaulted: true,
        },
        {
          name: 'hardness',
          params: [
            {
              name: 'state',
              type: 'String',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'breakSpeed',
          doc: "How fast [tool] (null: a bare hand) mines [state], as the game counts it\nbefore the player's own multipliers: 1 for a tool that isn't a sped-up\none for it, more for one that is (a pickaxe on stone). Null for a state\nthe server doesn't have.",
          params: [
            {
              name: 'tool',
              type: 'ItemData?',
            },
            {
              name: 'state',
              type: 'String',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'breakProgress',
          doc: "What a tick of [player] (online) mining the block at the position adds to its progress (1 breaks it), as the\nserver counts it for the block that's there: their tool, enchantments, effects, attributes and footing. Null\nwhen the player isn't online or the chunk isn't loaded.",
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
          returns: 'Double?',
        },
        {
          name: 'showBreaking',
          doc: 'Draws the cracks of [player] mining the block at the position at [stage] (0 to 9; -1 takes them away) for\neveryone near it, [player] too: what the game shows of a block being mined, when the server counts it.',
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
              name: 'stage',
              type: 'Int',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'breakAs',
          doc: "[player] (online) breaks the block at the position as if they'd mined it: the server's own break, with its\n`BlockBreakEvent` (which protection plugins may refuse, and which the runtime hears as [PlatformEvents.blockBreak]).\nFalse when it wasn't broken.",
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
          name: 'targeted',
          doc: 'The block [player] (online) is looking at within reach, or null for none.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'BlockRef?',
        },
        {
          name: 'canPlace',
          doc: "Whether a block of [state] fits at the position: nothing standing there that a block can't be put into, as for\na player placing one. A block with nothing to collide with (tripwire) goes where anything stands, as string does.",
          params: [
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
          name: 'place',
          doc: "Places [state] at the position for [player] (online) as they would\nplace it, against [against] with [hand]: the block is set and the\nserver's own `BlockPlaceEvent` is raised, so protection plugins may\nrefuse it, in which case what was there is put back. False when it\nwas refused or the chunk isn't loaded.",
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
            {
              name: 'against',
              type: 'BlockVector',
            },
            {
              name: 'hand',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'harvests',
          doc: "Whether [tool] (null: a bare hand) gets [state]'s drops as the game counts it: true for a block that needs no\nparticular tool, or for the tool it needs (a pickaxe on stone). Null for a state the server doesn't have.",
          params: [
            {
              name: 'tool',
              type: 'ItemData?',
            },
            {
              name: 'state',
              type: 'String',
            },
          ],
          returns: 'Boolean?',
        },
        {
          name: 'showBroken',
          doc: "Shows the block at the position breaking as [state] (its sound and particles, as the game shows a block broken):\nto [player] (online) alone, or with null to everyone near it. The server's break for a player leaves that player\nout, since their own game shows its own, which a block the server mined never did.",
          params: [
            {
              name: 'player',
              type: 'UUID?',
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
          returns: 'Unit',
        },
        {
          name: 'watch',
          doc: "What the runtime wants to hear of blocks besides players breaking them: the world breaking one of [drops] (block\nids) itself, whose drops are then [PlatformEvents.worldBroke]'s to say (support lost, a fluid, a piston, leaves\ndecaying), and a neighbour of one of [falling] (canonical states) changing ([PlatformEvents.blockUpdated]), with\na falling block of any of them landing ([PlatformEvents.fallingLanded]). Each call replaces the last; nothing is\nheard while both are empty.",
          params: [
            {
              name: 'drops',
              type: 'Set<String>',
            },
            {
              name: 'falling',
              type: 'Set<String>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'fall',
          doc: "Drops the block at the position as a falling block, as sand falls, when what's under it is something a block\nfalls into (air, a fluid, a replaceable block): the block is gone and the falling block entity's id is returned.\nThe entity lands as the same state, or drops nothing where it can't. Null when it doesn't fall.",
          params: [
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
          returns: 'UUID?',
        },
        {
          name: 'freeze',
          doc: "Stops the server working out the states of [carrier]'s blocks for itself, so custom blocks held in them stay\nin the state they were set to. Note blocks (Paper's `disable-noteblock-updates`): no instrument from what's\nabove and below, `powered` from redstone, tuning or sounding; the adapter does the vanilla note blocks' part\nitself from then on, in the default instrument's column. Tripwire (`disable-tripwire-updates`): string goes\ndown in its default state and nothing attaches, powers or disarms it. Leaves: a persistent leaves block's\ndistance isn't worked out again, and a player's leaves go down at the farthest distance; natural leaves still\ndecay. Idempotent, and it stays in force while the plugin is enabled. False when this server can't (then\nnothing holds custom blocks in that carrier).",
          params: [
            {
              name: 'carrier',
              type: 'BlockCarrier',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BlockRecord',
      doc: 'A custom block the server remembers at a position: [json] is what the runtime stored with [BlockOps.setRecord].',
      fields: [
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
          name: 'json',
          type: 'String',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BlockBoxRead',
      doc: 'What [BlockOps.box] read: each block an index into [states], x fastest, then z, then y.',
      fields: [
        {
          name: 'states',
          type: 'List<BoxState>',
        },
        {
          name: 'cells',
          type: 'IntArray',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BoxState',
      doc: "One state a box holds (canonical), and whether it's solid and whether it's water or lava, as [BlockSnapshot] says.",
      fields: [
        {
          name: 'state',
          type: 'String',
        },
        {
          name: 'solid',
          type: 'Boolean',
        },
        {
          name: 'liquid',
          type: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BlockHit',
      doc: "What a ray cast through the world's blocks hit: the block, where, and the normal of the face.",
      fields: [
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
      kind: 'enum',
      name: 'GameRuleType',
      doc: 'Which value a game rule takes.',
      params: [],
      entries: [
        {
          name: 'BOOLEAN',
          args: [],
        },
        {
          name: 'INTEGER',
          args: [],
        },
      ],
    },
    {
      kind: 'record',
      name: 'ParticleSpawn',
      doc: "One particle spawn, in world space: what the game's spawn packet carries.\nWith [count] above 0 the game scatters that many around [position] with a\nGaussian [offset] and random [speed]; with [count] 0 it sends one moving\nalong [offset] at [speed]. [data] is the same `SpawnData` format's effect\nsampler emits, so `world:spawn_particle` and particle effects share one\npath to the client.",
      fields: [
        {
          name: 'particle',
          type: 'String',
        },
        {
          name: 'position',
          type: 'Vec3',
        },
        {
          name: 'count',
          type: 'Int',
        },
        {
          name: 'offset',
          type: 'Vec3',
        },
        {
          name: 'speed',
          type: 'Double',
        },
        {
          name: 'data',
          type: 'SpawnData?',
        },
        {
          name: 'force',
          type: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ParticleOps',
      properties: [],
      methods: [
        {
          name: 'spawn',
          doc: 'One batch of spawns in one world, each sent to [viewers] only.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'spawns',
              type: 'List<ParticleSpawn>',
            },
            {
              name: 'viewers',
              type: 'List<PlayerRef>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'forget',
          doc: "Drops whatever the adapter keeps between spawns (Bukkit particles, block\ndata, item stacks): the project reloaded, and an item's look may come\nfrom a pack that changed.",
          params: [],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'record',
      name: 'SoundPlay',
      doc: "A sound to play: an id (a Minecraft sound, or a pack's as the game names it: `shop:ui/click`), the volume slider it follows, how loud and how high.",
      fields: [
        {
          name: 'sound',
          type: 'String',
        },
        {
          name: 'category',
          type: 'String',
        },
        {
          name: 'volume',
          type: 'Double',
        },
        {
          name: 'pitch',
          type: 'Double',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'SoundOps',
      properties: [],
      methods: [
        {
          name: 'play',
          doc: "Plays at a position in a world, for everyone near enough. False when the world doesn't exist.",
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
              name: 'sound',
              type: 'SoundPlay',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'playTo',
          doc: "Plays for one player only, following them. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'sound',
              type: 'SoundPlay',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'stop',
          doc: "Stops [sound] for one player, or every sound for null. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'sound',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'WorldOps',
      properties: [],
      methods: [
        {
          name: 'names',
          params: [],
          returns: 'List<String>',
        },
        {
          name: 'defaultWorld',
          doc: "The world things go in when nobody says which: the server's first.",
          params: [],
          returns: 'String',
        },
        {
          name: 'exists',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'spawnLocation',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Location?',
        },
        {
          name: 'entitiesLoaded',
          doc: "Whether the entities of the chunk at [at] are loaded. While they are, an\nentity of ours that should stand there and can't be reached is gone\n(killed, removed by another plugin), not unloaded. Never loads anything.",
          params: [
            {
              name: 'at',
              type: 'Location',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'collisionBoxes',
          doc: "The collision boxes of every block overlapping the region from ([minX],\n[minY], [minZ]) to ([maxX], [maxY], [maxZ]), in world coordinates: what a\nphysics body can land on. The live collision shape of each block (a body\nlands on a fence where a player would and falls past a torch). Blocks in\nchunks that aren't loaded are left out, never loaded for this.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'minX',
              type: 'Double',
            },
            {
              name: 'minY',
              type: 'Double',
            },
            {
              name: 'minZ',
              type: 'Double',
            },
            {
              name: 'maxX',
              type: 'Double',
            },
            {
              name: 'maxY',
              type: 'Double',
            },
            {
              name: 'maxZ',
              type: 'Double',
            },
          ],
          returns: 'List<Box>',
        },
        {
          name: 'environment',
          doc: '`normal`, `nether` or `end` (a custom dimension is `normal`).',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'fullTime',
          doc: 'Ticks since the world began: the time of day is this modulo 24000, the day count this over 24000.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Long?',
        },
        {
          name: 'setFullTime',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'ticks',
              type: 'Long',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'weather',
          doc: '`clear`, `rain` or `thunder`.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'moonPhase',
          doc: "Tonight's moon as the server works it out: 0 full, 1 waning gibbous, … 4 new, … 7 waxing gibbous.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setWeather',
          doc: 'Changes the weather for [ticks], or for as long as the game picks when null.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'weather',
              type: 'String',
            },
            {
              name: 'ticks',
              type: 'Int?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setSpawnLocation',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'at',
              type: 'Location',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'heights',
          doc: 'The lowest block y, and one above the highest.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Pair<Int, Int>?',
        },
        {
          name: 'gameRuleType',
          doc: 'Which value the game rule with this namespaced id takes, or null when the server has no such rule.',
          params: [
            {
              name: 'rule',
              type: 'String',
            },
          ],
          returns: 'GameRuleType?',
        },
        {
          name: 'gameRule',
          doc: 'A Boolean or an Int, as [gameRuleType] says.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'rule',
              type: 'String',
            },
          ],
          returns: 'Any?',
        },
        {
          name: 'setGameRule',
          doc: '[value] is a Boolean or an Int, as [gameRuleType] says.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'rule',
              type: 'String',
            },
            {
              name: 'value',
              type: 'Any',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'spawnLimit',
          doc: "The mob cap per player for [category] in this world: its own setting, or the server's when it has none.\nNull when the world doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'category',
              type: 'SpawnCategory',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setSpawnLimit',
          doc: "Sets the world's own mob cap for [category]; a negative [limit] goes back to the server's. False when the world doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'category',
              type: 'SpawnCategory',
            },
            {
              name: 'limit',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'spawnInterval',
          doc: "Ticks between spawn attempts for [category] in this world: its own setting, or the server's. Null when the world doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'category',
              type: 'SpawnCategory',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'setSpawnInterval',
          doc: "Sets the world's own ticks between spawn attempts for [category]; a negative [ticks] goes back to the server's. False when the world doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'category',
              type: 'SpawnCategory',
            },
            {
              name: 'ticks',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'isChunkLoaded',
          doc: 'Whether the chunk (in chunk coordinates) is loaded: whether its blocks can be read now.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'loadedChunks',
          doc: "Every chunk loaded in the world now, as (chunk x, chunk z); empty for a world that doesn't exist.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'List<Pair<Int, Int>>',
        },
        {
          name: 'chunkCount',
          doc: 'How many chunks and entities a loaded world has now, for metrics: counted by the server, nothing listed.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Int',
        },
        {
          name: 'entityCount',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Int',
        },
        {
          name: 'loadChunk',
          doc: 'Loads (generating if need be) a chunk, now.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'holdChunk',
          doc: "Keeps a chunk loaded, its entities too, until it's released as many times as it was held: loaded (generated\nif need be) now if it isn't. [loadChunk] alone lasts the tick: the server unloads a chunk nothing holds. False\nwhen the world doesn't exist, and nothing is held then.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'releaseChunk',
          doc: 'Lets go of one [holdChunk] of the chunk; the server unloads it as any other once nothing holds it.',
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'chunkX',
              type: 'Int',
            },
            {
              name: 'chunkZ',
              type: 'Int',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'highestBlockY',
          doc: "The y of the highest block in a column that isn't air, or null when its chunk isn't loaded.",
          params: [
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'x',
              type: 'Int',
            },
            {
              name: 'z',
              type: 'Int',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'biome',
          doc: "The biome at a block, namespaced (`minecraft:plains`); null when its chunk isn't loaded or the world doesn't exist. Never loads a chunk.",
          params: [
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
          returns: 'String?',
        },
        {
          name: 'biomeSearch',
          doc: "A search for the nearest place within [radius] blocks across of ([x], [y], [z]) where the world's\ngenerator puts [biome] (namespaced), as `/locate biome` does: out from there in a square spiral,\nevery [BiomeSearch.ACROSS] blocks across and [BiomeSearch.UP] up and down, asking the world's\nbiome source, so it never loads or generates a chunk. Made on the main thread: null when the world\ndoesn't exist or the server has no such biome.",
          params: [
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
              name: 'radius',
              type: 'Int',
            },
            {
              name: 'biome',
              type: 'String',
            },
          ],
          returns: 'BiomeSearch?',
        },
        {
          name: 'explode',
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
              name: 'power',
              type: 'Double',
            },
            {
              name: 'fire',
              type: 'Boolean',
            },
            {
              name: 'breakBlocks',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'strikeLightning',
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
              name: 'effectOnly',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'raycastBlocks',
          doc: "The first block whose collision shape a ray hits within [maxDistance],\nor null. [direction] is a unit vector. Water and lava are hit as solid\nonly with [fluids]. Blocks in chunks that aren't loaded aren't hit.",
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
              name: 'fluids',
              type: 'Boolean',
            },
          ],
          returns: 'BlockHit?',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'BiomeSearch',
      doc: "A biome search [WorldOps.biomeSearch] made: it reads only what the world's\ngenerator reads while it generates chunks on the server's own worker\nthreads, so the runtime runs it off the main thread (it can take seconds\nwhen the biome is far or nowhere).",
      properties: [],
      methods: [
        {
          name: 'run',
          doc: "The block position it found the biome at, or null when there's none within the radius.",
          params: [],
          returns: 'Vec3?',
        },
      ],
    },
  ],
} satisfies PlatformFile
