import type { PlatformFile } from '../types.ts'

export default {
  source: 'WorldAdmin.kt',
  decls: [
    {
      kind: 'record',
      name: 'WorldSettings',
      doc: "How a new world is made. [generator] is `normal`, `flat`, `void`,\n`amplified` or `large_biomes`; [environment] is `normal`, `nether` or `end`.\nA world a project generator makes has [terrain], that generator's id as\n[WorldManagerOps.publishGenerators] was given it (and [generator] `normal`).\nA world of a project dimension type has [dimensionType], the type as the server\nknows it (`showcase:deep`, one of [WorldManagerOps.dimensionTypes]).",
      fields: [
        {
          name: 'generator',
          type: 'String',
        },
        {
          name: 'environment',
          type: 'String',
        },
        {
          name: 'seed',
          type: 'Long?',
          doc: 'Null: a random one.',
        },
        {
          name: 'structures',
          type: 'Boolean',
        },
        {
          name: 'keepSpawnLoaded',
          type: 'Boolean',
        },
        {
          name: 'terrain',
          type: 'String?',
          default: 'null',
        },
        {
          name: 'dimensionType',
          type: 'String?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'ProjectGenerator',
      doc: "A project's terrain as an adapter runs it on the server's chunk\nthreads: the compiled file, the block state (canonical text) each of its\n[palette][CompiledTerrain.palette] entries is written into a chunk as, and\nthe biome each of its [areas' biomes][CompiledTerrain.biomes] is on the\nserver. A custom block's state is the note block state it's held as, which\nthe project's blocks adopt when the chunk loads; a biome is the server's key\nfor it (`minecraft:plains`, a project biome's `showcase:ruby_grove`). Immutable,\nso it's shared with every thread.\n\nA file with a script has [scripts]: what its Lua runs with (luajava, its\nfiles read when it was published, and who hears of its failures), which\nthe adapter hands [CompiledTerrain.bind]. The states it runs in are the\nbound generator's, one per chunk thread at a time; the adapter closes a\nreplaced generator ([dev.netherforge.format.terrain.TerrainGenerator.close])\nso they go.\n\nThe Rust runtime has no [terrain] (format's Kotlin form) to hand over: it gives [source] instead, its own generator,\nwhich the adapter's chunk threads call across FFM. One of the two is there.",
      fields: [
        {
          name: 'terrain',
          type: 'CompiledTerrain?',
        },
        {
          name: 'states',
          type: 'List<String>',
        },
        {
          name: 'biomes',
          type: 'Map<String, String>',
        },
        {
          name: 'scripts',
          type: 'TerrainScripts?',
          default: 'null',
        },
        {
          name: 'source',
          type: 'dev.netherforge.plugin.platform.terrain.TerrainSource?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'WorldManagerOps',
      properties: [],
      methods: [
        {
          name: 'isSaved',
          doc: 'Whether the server has a world saved by this name, loaded or not.',
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'create',
          doc: "Makes a new world and loads it. False when the server wouldn't. A\n[WorldSettings.dimension] is kept with the world by the server (its saved\ngeneration settings name it), so [load] needn't be told it again.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'settings',
              type: 'WorldSettings',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'dimensionTypes',
          doc: "The dimension types the server has, as it knows them (`minecraft:overworld`, a project's `showcase:deep`): the\ngame's, and those of the datapacks it started with. A world can be made, or loaded, with one of these only.",
          params: [],
          returns: 'Set<String>',
        },
        {
          name: 'load',
          doc: "Loads a world the server has saved. False when there's none, or the\nserver wouldn't. [environment] is what it was made as (the server\ndoesn't keep it: a nether loaded as `normal` would be one no longer):\n`normal`, `nether` or `end`, and `normal` for one nobody here made.\n[terrain] is the id of the project generator it was made with (the\nserver doesn't keep that either), null for any other.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'environment',
              type: 'String',
            },
            {
              name: 'terrain',
              type: 'String?',
              default: 'null',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'publishGenerators',
          doc: "Replaces the project generators worlds are made with, by id (as\n[WorldSettings.terrain] names them). A world's chunks generated from now\non use the new ones; chunks already generated stay as they are. It's\nwhat hot reload does to a generator, and it's called from the main\nthread: the generators run on the server's chunk threads, off it.\nA world whose generator is no longer published keeps generating with the\nlast one it had.",
          params: [
            {
              name: 'generators',
              type: 'Map<String, ProjectGenerator>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'copy',
          doc: 'What copying the map folder [map] into a new world [name] takes:\nfile work that puts it where [load] then finds it (without its lock and\nidentity files). Asked on the main thread; the runtime runs the work on\na worker, after every copy and deletion asked for before, and then\n[load]s `normal` on the main thread. The work touches nothing but\nfiles, and leaves nothing half-copied when it fails.',
          params: [
            {
              name: 'map',
              type: 'Path',
            },
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'FileWork',
        },
        {
          name: 'unload',
          doc: 'Unloads a loaded world, saving it first with [save]. False when the server kept it.',
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'save',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'delete',
          doc: "What deleting an unloaded world's files takes: asked on the main thread\n(where the server says where they are), run by the runtime on a worker,\nafter every copy and deletion asked for before.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'FileWork',
        },
        {
          name: 'saveFiles',
          doc: "Saves a loaded world and flushes it to disk, so its files can be copied\nwhile the server runs (the dev bridge's `save_world`, which makes a\nmap). Null when no world by that name is loaded.",
          params: [
            {
              name: 'name',
              type: 'String',
            },
          ],
          returns: 'WorldFiles?',
        },
        {
          name: 'setSaving',
          doc: 'Whether the server writes a loaded world to disk (`save-off` for one world: autosaves and chunks it unloads),\nso a tool can copy its files whole while it runs. False when no world by that name is loaded.',
          params: [
            {
              name: 'name',
              type: 'String',
            },
            {
              name: 'saving',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'WorldFiles',
      doc: "Where a world's files are, as absolute paths: [level] is the `level.dat`\nof the storage it lives in, [dimension] the world's own folder (its region\nfiles and saved data). A map is the two together, the dimension\nas the map's overworld.",
      fields: [
        {
          name: 'level',
          type: 'Path',
        },
        {
          name: 'dimension',
          type: 'Path',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'FileWork',
      doc: "Blocking work on files that an adapter describes and the runtime runs off\nthe main thread (`Workers`), in order with the rest of its kind: a world's\nfiles copied or deleted. It may throw [IOException], which is the failure.",
      properties: [],
      methods: [
        {
          name: 'run',
          params: [],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'union',
      name: 'BorderOwner',
      doc: "Whose border: a world's, or one player's own.",
      cases: [
        {
          name: 'World',
          fields: [
            {
              name: 'name',
              type: 'String',
            },
          ],
        },
        {
          name: 'Player',
          fields: [
            {
              name: 'uuid',
              type: 'UUID',
            },
          ],
        },
      ],
    },
    {
      kind: 'record',
      name: 'BorderState',
      doc: 'Everything a border is. [warningTicks] is how soon a shrinking border warns, in ticks.',
      fields: [
        {
          name: 'centerX',
          type: 'Double',
        },
        {
          name: 'centerZ',
          type: 'Double',
        },
        {
          name: 'size',
          type: 'Double',
          doc: "Side to side, in blocks, now (mid-way, while it's moving).",
        },
        {
          name: 'damageAmount',
          type: 'Double',
        },
        {
          name: 'damageBuffer',
          type: 'Double',
        },
        {
          name: 'warningDistance',
          type: 'Int',
        },
        {
          name: 'warningTicks',
          type: 'Int',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'BorderOps',
      doc: "World borders, and borders players see alone. A player's own border is the\nadapter's to keep (and to show again when the server would draw their\nworld's over it, as it does on a respawn or a change of world) until\n[reset] or they leave. Everything answers null or false for a world that\nhas gone, a player who's offline, or a player without a border of their own.",
      properties: [
        {
          name: 'maxSize',
          doc: 'The widest a border can be.',
          type: 'Double',
        },
        {
          name: 'maxCenter',
          doc: "How far from 0 a border's centre can be, along x or z.",
          type: 'Double',
        },
      ],
      methods: [
        {
          name: 'get',
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
          ],
          returns: 'BorderState?',
        },
        {
          name: 'setCenter',
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
            {
              name: 'x',
              type: 'Double',
            },
            {
              name: 'z',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setSize',
          doc: "Changes the size at once, or moving there over [ticks] when that's above 0.",
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
            {
              name: 'size',
              type: 'Double',
            },
            {
              name: 'ticks',
              type: 'Long',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setDamage',
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
            {
              name: 'amount',
              type: 'Double',
            },
            {
              name: 'buffer',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setWarning',
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
            {
              name: 'distance',
              type: 'Int',
            },
            {
              name: 'ticks',
              type: 'Int',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'contains',
          doc: "Whether a point is inside. [world]: the point's world, which must be a world border's own; null for a bare position.",
          params: [
            {
              name: 'owner',
              type: 'BorderOwner',
            },
            {
              name: 'world',
              type: 'String?',
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
          ],
          returns: 'Boolean',
        },
        {
          name: 'personal',
          doc: "Gives a player a border of their own (a copy of their world's) unless they have one. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'reset',
          doc: "Takes a player's own border away: they see their world's again. False when they had none.",
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
    {
      kind: 'record',
      name: 'StructurePlacement',
      doc: 'How a structure is placed: [rotation] clockwise in degrees (0, 90, 180 or 270), [mirror] `none`, `left_right` or `front_back`.',
      fields: [
        {
          name: 'rotation',
          type: 'Int',
        },
        {
          name: 'mirror',
          type: 'String',
        },
        {
          name: 'integrity',
          type: 'Double',
        },
        {
          name: 'entities',
          type: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'BlockVector',
      doc: 'A block-aligned size or position, in blocks.',
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
      ],
    },
    {
      kind: 'record',
      name: 'StructureTransform',
      doc: "Where a structure file's blocks and entities go when what it's part of is placed with its smallest corner at\n[origin], mirrored by [mirror] then turned [rotation] degrees clockwise about that corner: the game's own\narithmetic (its structure template's `transform` with the pivot at the corner), so a gradual placement puts\nevery block where `/place` would. [offset] is where the file sits inside what's placed: a build's part, or\nnothing for a structure.",
      fields: [
        {
          name: 'origin',
          type: 'BlockVector',
        },
        {
          name: 'offset',
          type: 'BlockVector',
        },
        {
          name: 'rotation',
          type: 'Int',
        },
        {
          name: 'mirror',
          type: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'StructurePlacer',
      doc: "One structure file being placed a few blocks at a time ([StructureOps.placer]): its blocks in the file's own\norder (the game saves whole blocks first, bottom up, then blocks with data, then the rest, so what stands on\nsomething goes in after it), then its entities.",
      properties: [
        {
          name: 'size',
          doc: "The file's size, in blocks along x, y and z (before it's turned).",
          type: 'BlockVector',
        },
        {
          name: 'hasEntities',
          doc: "Whether the file holds entities (other than NetherForge's own) that [entities] would place.",
          type: 'Boolean',
        },
        {
          name: 'remaining',
          doc: "Blocks it hasn't gone through yet.",
          type: 'Int',
        },
        {
          name: 'placed',
          doc: "Blocks it has placed: not those integrity left out, nor those outside the world's heights.",
          type: 'Long',
        },
      ],
      methods: [
        {
          name: 'place',
          doc: 'Goes through up to [budget] more blocks, placing each as it was saved (its data with it, no updates to its\nneighbours) and loading its chunk if need be; answers how many it went through, or null when the world has gone.',
          params: [
            {
              name: 'budget',
              type: 'Int',
            },
          ],
          returns: 'Int?',
        },
        {
          name: 'entities',
          doc: "Places its entities (none of NetherForge's own), once every block is in. False when the world has gone.",
          params: [],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'StructureOps',
      doc: "Structures, in Minecraft's structure file format. A file is read once and\nkept until [forget] (a reload of it); placing loads the chunks it covers.",
      properties: [],
      methods: [
        {
          name: 'size',
          doc: "The size of the structure in [file], or null when it can't be read as one.",
          params: [
            {
              name: 'file',
              type: 'Path',
            },
          ],
          returns: 'BlockVector?',
        },
        {
          name: 'template',
          doc: "The blocks of the structure in [file] as a terrain places them (its first palette, air included, every\nstate in full as the server reads it), or null when it can't be read as one. A decoration of a generator is\nlinked to it ([dev.netherforge.format.terrain.CompiledTerrain.withStructures]).",
          params: [
            {
              name: 'file',
              type: 'Path',
            },
          ],
          returns: 'StructureTemplate?',
        },
        {
          name: 'place',
          doc: "Places the structure in [file] with its smallest corner at [at]. False when the world has gone or the file can't be read.",
          params: [
            {
              name: 'file',
              type: 'Path',
            },
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'at',
              type: 'BlockVector',
            },
            {
              name: 'placement',
              type: 'StructurePlacement',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'placer',
          doc: "The structure in [file] to place a few blocks at a time where [transform] says, with [placement]'s\nintegrity and entities ([placement]'s rotation and mirror are [transform]'s), integrity's chances drawn from\n[seed]. Null when the world has gone or the file can't be read. A file not already kept is read for this and\nlet go with it, so a build's parts don't all stay in memory.",
          params: [
            {
              name: 'file',
              type: 'Path',
            },
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'transform',
              type: 'StructureTransform',
            },
            {
              name: 'placement',
              type: 'StructurePlacement',
            },
            {
              name: 'seed',
              type: 'Long',
            },
          ],
          returns: 'StructurePlacer?',
        },
        {
          name: 'save',
          doc: "Saves the box from [min] to [max] (both included) into [file], replacing it. False when the world has gone or it can't be written.",
          params: [
            {
              name: 'file',
              type: 'Path',
            },
            {
              name: 'world',
              type: 'String',
            },
            {
              name: 'min',
              type: 'BlockVector',
            },
            {
              name: 'max',
              type: 'BlockVector',
            },
            {
              name: 'entities',
              type: 'Boolean',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'forget',
          doc: 'Drops what was read from [file]: it changed.',
          params: [
            {
              name: 'file',
              type: 'Path',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
  ],
} satisfies PlatformFile
