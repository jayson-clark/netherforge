import { asyncFunction } from '../async.ts'
import type { EventSpec, Field, LuaClass } from '../types.ts'
import { DATA_VALUES } from './data.ts'
import { eventFunctions } from './events.ts'
import { gameWorldEvents } from './gameEvents.ts'

const shape = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

/**
 * How many blocks one `world:fill_blocks` may change. The same as `/fill`'s
 * default limit: big enough for a room, small enough that one call can't stall
 * the server for long.
 */
export const FILL_LIMIT = 32768

/**
 * How many blocks one `world:fill_gradually` may change: 256 × 256 × 256. It goes a few thousand blocks a tick,
 * so this is minutes of work, not a stall; the cap only keeps a typo from queueing hours of it.
 */
export const FILL_GRADUALLY_LIMIT = 16777216

/** What `place_gradually` and `fill_gradually` say about how fast they go, the owner's settings named. */
const GRADUAL =
  "It goes a few thousand blocks a tick, shared with every other gradual placement and fill on the server (the owner's `structures.blocks-per-tick` in `config.yml`), so even a big one never stalls the server; each package may have a few going at once (`structures.max-jobs-per-package`), past which one fails at once."

/**
 * How far `world:locate_biome` may look, in blocks: `/locate biome`'s own
 * limit, which a search that finds nothing covers in a few seconds of one
 * background thread.
 */
export const BIOME_SEARCH_LIMIT = 6400

/** What the spatial queries (`overlap_*`, `sweep`, `nearest`) find, for their docs. */
const QUERY_FINDS =
  "It finds centities by their hitboxes (each shown node's boxes, as `raycast` and physics see them; a hidden node has none), players and other entities by theirs, as `options` allows: everything, by default. A centity is found once, by its nearest node."

/** What every spatial query's options take: `QueryOptions`, and the start of `SweepOptions`. */
const QUERY_FIELDS: Field[] = [
  { name: 'centities', type: 'boolean?', doc: 'Whether it finds centities. Default `true`.' },
  { name: 'players', type: 'boolean?', doc: 'Whether it finds players. Default `true`.' },
  {
    name: 'entities',
    type: 'boolean?',
    doc: "Whether it finds entities that aren't players (mobs, dropped items, arrows...). Default `true`.",
  },
  {
    name: 'kinds',
    type: 'string[]?',
    doc: 'Only centities of these kinds (their folder names), and entities of these types (`"minecraft:zombie"`). Default: any.',
  },
  {
    name: 'exclude',
    type: '(Centity|Entity)[]?',
    doc: 'What it leaves out: whatever is asking, usually. Default none.',
  },
  {
    name: 'limit',
    type: 'integer?',
    doc: 'At most this many, the nearest. At least 1. Default: all.',
  },
  {
    name: 'line_of_sight',
    type: 'boolean?',
    doc: "Only what a ray from the query's own point (the middle of a box, the centre of a sphere, a cone's tip, a sweep's start) reaches without a block in the way, aimed at its hitbox's nearest point. Default `false`.",
  },
]

/** Where the spatial queries look. */
const QUERY_LOADED =
  "Only what's in loaded chunks is found. An empty list (or `nil`) in a world that has gone."

const UNLOADED =
  "Blocks in chunks that aren't loaded can't be read or changed: that answers `nil` or `false`, and never loads the chunk (`world:load_chunk` does)."

const worldEvents: EventSpec[] = [
  {
    name: 'block_break',
    doc: 'A player is breaking a block in this world: before `nf.on("block_break")`, with the same event, so `event:cancel()` here stops it and `event:stop()` keeps it from `nf`. Assign `event.drops` and `event.experience` to change what it drops.',
    payload: 'BlockBreakEvent',
    cancellable: true,
    bubbles: true,
    writable: ['drops', 'experience'],
    example:
      'nf.worlds.default():on("block_break", function(event)\n  if event.state == "minecraft:diamond_ore" then\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'block_place',
    doc: 'A player is placing a block in this world: before `nf.on("block_place")`, with the same event.',
    payload: 'BlockPlaceEvent',
    cancellable: true,
    bubbles: true,
  },
  {
    name: 'entity_spawn',
    doc: 'An entity is coming into this world: before `nf.on("entity_spawn")`, with the same event, so `event:cancel()` here stops it and `event:stop()` keeps it from `nf`.',
    payload: 'EntitySpawnEvent',
    cancellable: true,
    bubbles: true,
    example:
      'nf.worlds.default():on("entity_spawn", function(event)\n  if event.entity:kind() == "minecraft:phantom" then\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'dusk',
    doc: 'Night fell here: the time of day went from day (23000 to 12999) into night (13000 to 22999), by the clock or by `/time set`. Heard before `nf.on("world_dusk")`, with the same event, so `event:stop()` keeps it from there. The runtime looks at the clock every tick, and only while something listens; a jump from one night to the next (a whole day skipped) is no change, so nothing is heard.',
    payload: 'DaylightEvent',
    bubbles: true,
    example:
      'nf.worlds.default():on("dusk", function(event)\n  for _, player in ipairs(event.world:players()) do\n    player:send_message("<gray>Night falls.")\n  end\nend)',
  },
  {
    name: 'dawn',
    doc: 'Day broke here: the time of day went from night (13000 to 22999) into day (23000 to 12999), by the clock or by `/time set` (sleeping through the night too). Heard before `nf.on("world_dawn")`, with the same event, so `event:stop()` keeps it from there.',
    payload: 'DaylightEvent',
    bubbles: true,
  },
  ...gameWorldEvents,
]

/** A world on the server. */
export const worldClass: LuaClass = {
  name: 'World',
  doc: 'A world on the server (the overworld, the nether, the end, or another the server loads). A handle is its name, so keeping one is fine: if the world is unloaded its methods answer `nil` or `false`. Positions are world space, in blocks.',
  methods: true,
  handle: { key: [{ name: 'name', type: 'string' }] },
  saveable: true,
  fields: [],
  events: worldEvents,
  functions: [
    {
      name: 'name',
      doc: 'Its name, as `nf.worlds.get` takes it: `"world"`, `"world_nether"`.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: 'Whether the server has it loaded now.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'environment',
      doc: 'What kind of world it is, or `nil` once it has gone. A world of a custom dimension type says `"normal"`.',
      params: [],
      returns: [{ type: '"normal"|"nether"|"end"|nil' }],
    },
    {
      name: 'location',
      impl: 'lua',
      doc: 'A `Location` in this world, for the functions that take one (`player:teleport`, `nf.centities.spawn`). Give both `yaw` and `pitch`, or neither for a location with no facing; a `yaw` alone faces level.',
      params: [
        { name: 'position', type: 'Vec3', doc: 'Where.' },
        {
          name: 'yaw',
          type: 'number',
          doc: 'Which way it faces horizontally, in degrees (0 south, 90 west).',
          optional: true,
        },
        {
          name: 'pitch',
          type: 'number',
          doc: 'How far up or down, in degrees (−90 up, 90 down).',
          optional: true,
        },
      ],
      returns: [{ type: 'Location' }],
      example: 'player:teleport(nf.worlds.default():location(vec3(0, 80, 0), 180, 0))',
    },
    {
      name: 'time_of_day',
      doc: "The time of day in ticks, 0 to 23999: 0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight. `nil` once the world has gone. It's the game's clock, not the wall clock (`nf.server.unix_time()`).",
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_time_of_day',
      doc: "Sets the time of day, keeping the day count. Taken modulo 24000. Doesn't stop the clock (that's the daylight cycle game rule).",
      params: [
        {
          name: 'ticks',
          type: 'integer',
          doc: '0 sunrise, 6000 noon, 12000 sunset, 18000 midnight.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
    },
    {
      name: 'is_night',
      doc: 'Whether it is night here: the time of day is 13000 to 22999, from dusk to just before sunrise, as `/time set night` and a centity\'s `"night"` spawn time mean it. `false` once the world has gone.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_day',
      doc: "Whether it is day here: the time of day is 23000 to 12999, the rest of `is_night`'s. `false` once the world has gone.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'day_count',
      doc: 'How many whole days have gone by in this world, or `nil` once it has gone.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'moon_phase',
      doc: "Tonight's moon, or `nil` once the world has gone. It moves on a phase each day: full, waning gibbous, last quarter, waning crescent, new, waxing crescent, first quarter, waxing gibbous, then full again.",
      params: [],
      returns: [
        {
          type: '"full_moon"|"waning_gibbous"|"last_quarter"|"waning_crescent"|"new_moon"|"waxing_crescent"|"first_quarter"|"waxing_gibbous"|nil',
        },
      ],
      example:
        'if world:moon_phase() == "full_moon" then\n  nf.server.broadcast("<red>The moon is full tonight.")\nend',
    },
    {
      name: 'weather',
      doc: 'The weather now, or `nil` once the world has gone. Thunder always comes with rain.',
      params: [],
      returns: [{ type: '"clear"|"rain"|"thunder"|nil' }],
    },
    {
      name: 'set_weather',
      doc: "Changes the weather, for `options.ticks` or for as long as the game would have kept it. A `ticks` below 1 is an error, as is a key `options` doesn't take.",
      params: [
        { name: 'weather', type: '"clear"|"rain"|"thunder"', doc: '' },
        { name: 'options', type: 'WeatherOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example: 'world:set_weather("thunder", { ticks = 20 * 60 })',
    },
    {
      name: 'sky',
      doc: "The sky set over the world (`world:set_sky`), as the calling script names it, or `nil` for none (or once the world has gone). A player's own (`player:set_sky`) is theirs alone.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_sky',
      doc: "Sets a sky of the project's over the world (`skies/<id>.json`): everyone in it sees its colours in place of the biomes' own, the sky, fog, clouds and water, fading over `options.fade_ticks`, until it's cleared or another is set. Only what players are shown changes: the world, its biomes, its mobs and its weather are as they were, and a restart clears it. A player's own sky (`player:set_sky`) is shown them instead. A sky the project has no file for is an error, and so is one added since the server started (its colours are biomes, which the game learns as it starts: restart the server).",
      params: [
        {
          name: 'sky',
          type: 'string',
          doc: "A sky of the project's (its file's name under `skies/`, or `namespace:id` for one a package it depends on exports).",
        },
        { name: 'options', type: 'SkyOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example: 'world:set_sky("blood_moon", { fade_ticks = 100 })',
    },
    {
      name: 'clear_sky',
      doc: "Takes the world's sky away (`world:set_sky`): its biomes' own colours come back over `options.fade_ticks`. Nothing when it has none.",
      params: [{ name: 'options', type: 'SkyOptions', doc: '', optional: true }],
      returns: [],
      example: 'world:clear_sky({ fade_ticks = 100 })',
    },
    {
      name: 'spawn_location',
      doc: 'Where players appear in this world when nothing else decides, or `nil` once the world has gone.',
      params: [],
      returns: [{ type: 'Location?' }],
    },
    {
      name: 'set_spawn_location',
      doc: "Moves the world's spawn. A `Location` in another world is an error.",
      params: [
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: 'Where: a `Location` in this world (its facing is used, when it has one) or a position.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
    },
    {
      name: 'min_height',
      doc: 'The lowest block y there can be, or `nil` once the world has gone.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'max_height',
      doc: 'One above the highest block y there can be, or `nil` once the world has gone.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'block',
      doc: "The block at a position: a handle that reads the world live, so it sees later changes. The position is floored to the block it's in. `nil` once the world has gone.",
      params: [{ name: 'position', type: 'Vec3', doc: 'Any point in the block.' }],
      returns: [{ type: 'Block?' }],
      example: 'local below = this:world():block(this:position() - vec3(0, 1, 0))',
    },
    {
      name: 'blocks',
      doc: "Every block in a box, read in one call: for code that needs many blocks at once (a mob's collision, a flood fill of a room), where a `world:block` and a `:kind()` per block would cost each one. The corners are floored to the blocks they're in and both are included, in any order. What each block is given as is `options.what`. At most 32,768 blocks (32 × 32 × 32): a bigger box is an error. `nil` when any of the box is in a chunk that isn't loaded, or the world has gone.",
      params: [
        { name: 'from', type: 'Vec3', doc: 'One corner: any point in its block.' },
        { name: 'to', type: 'Vec3', doc: 'The opposite corner, included: any point in its block.' },
        { name: 'options', type: 'BlockBoxOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'BlockBox?' }],
      example:
        'local box = world:blocks(vec3(0, 60, 0), vec3(15, 70, 15), { what = "class" })\nif box then\n  -- A block within the box, counted from its corner.\n  local function at(x, y, z)\n    return box.cells[1 + x + box.size_x * (z + box.size_z * y)]\n  end\n  if at(0, 0, 0) == 2 then log("solid") end\nend',
    },
    {
      name: 'flood',
      doc: `Fills outward from a block through every block \`options.through\` names, one neighbour at a time, as far as it reaches: the blocks of a tree (\`through = { "#minecraft:logs" }\`), the air of a room (\`through = { "passable" }\`), a lake. It answers every block it filled (\`positions\`, nearest first by steps) and every block it stopped at (\`edges\`: a room's walls), with what each is when asked (\`kinds\`), and \`stopped\` says why it didn't finish when it didn't: it hit its \`limit\`, a chunk that isn't loaded or the world's top or bottom (\`"unloaded"\`: a room open to the sky or the unknown), or its \`bounds\`. It runs in the server, reading the blocks a box at a time, so a fill of thousands of blocks costs a script one call. A start block it can't fill through answers no positions, and the start as an edge. ${UNLOADED}`,
      params: [
        { name: 'start', type: 'Vec3', doc: 'Any point in the block to start from.' },
        { name: 'options', type: 'FloodOptions', doc: '' },
      ],
      returns: [{ type: 'FloodResult?', doc: '`nil` once the world has gone.' }],
      example:
        'local room = world:flood(assert(player:position()), { through = { "passable" }, except = { "#minecraft:doors" }, limit = 750, kinds = true })\nif room and not room.stopped then\n  log(#room.positions .. " blocks of air, walled in by " .. #room.edges)\nend',
    },
    {
      name: 'set_block',
      doc: `Changes the block at a position. A block state the server doesn't have (an unknown block, property or value) is an error. ${UNLOADED}`,
      params: [
        { name: 'position', type: 'Vec3', doc: 'Any point in the block.' },
        {
          name: 'state',
          type: 'string',
          doc: 'A block state: `"minecraft:stone"`, `"minecraft:oak_stairs[facing=east]"`. Properties left out take their defaults.',
        },
        { name: 'options', type: 'BlockSetOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it changed: `false` in an unloaded chunk or a world that's gone.",
        },
      ],
      example: 'world:set_block(vec3(10, 64, 10), "minecraft:gold_block")',
    },
    {
      name: 'fill_blocks',
      doc: `Sets every block in the box between two corners (both included) to one state. One call changes at most ${FILL_LIMIT} blocks: a bigger box is an error (\`fill_gradually\` spreads a big fill over several ticks). A block state the server doesn't have is an error. Blocks in unloaded chunks are skipped.`,
      params: [
        { name: 'from', type: 'Vec3', doc: 'One corner.' },
        { name: 'to', type: 'Vec3', doc: 'The opposite corner.' },
        { name: 'state', type: 'string', doc: 'A block state, as `set_block` takes it.' },
        { name: 'options', type: 'BlockSetOptions', doc: '', optional: true },
      ],
      returns: [
        { type: 'integer', doc: 'How many blocks it changed: 0 in a world that has gone.' },
      ],
      example:
        'nf.task(function()\n  for y = 64, 127 do\n    world:fill_blocks(vec3(0, y, 0), vec3(63, y, 63), "minecraft:air")\n    nf.wait(1)\n  end\nend)',
    },
    asyncFunction({
      name: 'fill_gradually',
      doc: `Sets every block in the box between two corners (both included) to one state, like \`fill_blocks\`, but over as many ticks as it takes, for a box too big for one call: up to ${FILL_GRADUALLY_LIMIT} blocks (256 × 256 × 256). ${GRADUAL} It goes a chunk's column at a time, from the bottom up, loading the chunks the box covers as it reaches them (\`fill_blocks\` skips unloaded ones). A bigger box, a block state the server doesn't have, or a \`per_tick\` below 1 is an error at once. The world going, or this script stopping, stops it part way, and what was set stays.`,
      later: 'It takes as many ticks as the fill does',
      params: [
        { name: 'from', type: 'Vec3', doc: 'One corner.' },
        { name: 'to', type: 'Vec3', doc: 'The opposite corner.' },
        { name: 'state', type: 'string', doc: 'A block state, as `set_block` takes it.' },
        { name: 'options', type: 'GradualFillOptions', doc: '', optional: true },
      ],
      value: {
        name: 'changed',
        type: 'integer',
        doc: 'how many blocks it changed (a block that was already that state is not counted)',
      },
      example:
        'world:fill_gradually(vec3(-200, 0, -200), vec3(199, 120, 199), "minecraft:air", nil, function(changed, err)\n  if err then\n    print("The clear-out stopped: " .. err)\n  else\n    nf.server.broadcast(changed .. " blocks cleared")\n  end\nend)',
    }),
    {
      name: 'highest_block',
      doc: "The highest block at a column that isn't air, ignoring the position's `y`. `nil` in an unloaded chunk, or a world that has gone.",
      params: [{ name: 'position', type: 'Vec3', doc: 'Any point in the column.' }],
      returns: [{ type: 'Block?' }],
    },
    {
      name: 'is_chunk_loaded',
      doc: 'Whether the chunk a position is in is loaded: whether its blocks can be read and changed now.',
      params: [{ name: 'position', type: 'Vec3', doc: 'Any point in the chunk.' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'load_chunk',
      doc: "Loads the chunk a position is in, now, generating it if it's new: that can take a while, so do it rarely. The server unloads it again when nobody is near, as it does any chunk.",
      params: [{ name: 'position', type: 'Vec3', doc: 'Any point in the chunk.' }],
      returns: [
        { type: 'boolean', doc: "Whether it's loaded: `false` for a world that has gone." },
      ],
    },
    asyncFunction({
      name: 'locate_biome',
      doc: `Finds the nearest place where the world's generator puts a biome, as \`/locate biome\` does: it asks the generator, so it finds places in chunks nobody has been to, and never loads or generates a chunk. It looks out from \`options.near\` in a square spiral, every 32 blocks across and 64 up and down, so the place it gives is in the biome but may be up to 32 blocks past its nearest edge, and a biome narrower than that can be missed. A biome the world's generator never places (a nether biome in the overworld) is found nowhere at once. An id that isn't a biome on this server (a bare id that isn't one of the project's is an error saying how to write the game's), or a \`radius\` out of range, is an error at once. Finding nothing within the radius, or a world that has gone, fails.`,
      params: [
        {
          name: 'id',
          type: 'string',
          doc: 'The biome, named as a file names one: the project\'s own by its id (`"ruby_grove"`, `biomes/ruby_grove.json`), a package\'s `"acme:grove"` (if it exports it), the game\'s always written in full (`"minecraft:desert"`).',
        },
        { name: 'options', type: 'BiomeSearchOptions', doc: '', optional: true },
      ],
      value: {
        name: 'position',
        type: 'Vec3',
        doc: 'the place it found: a block position in this world, at the height it found the biome',
      },
      example:
        'nf.task(function()\n  local desert, err = world:locate_biome("minecraft:desert", { near = player:position() })\n  if not desert then\n    player:send_message("No desert near: " .. err)\n    return\n  end\n  local ground = world:highest_block(desert)\n  if ground then\n    player:teleport(world:location(ground:position() + vec3(0.5, 1, 0.5)))\n  end\nend)',
    }),
    {
      name: 'players',
      doc: 'Everyone in this world now. Empty once the world has gone.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'centities',
      doc: "The live centity instances in this world: the same as `nf.centities.all` with this world as the filter's `world`.",
      params: [
        {
          name: 'filter',
          type: 'CentityFilter',
          doc: 'Only some of them, as `nf.centities.all` takes it. Its `world`, if given, must be this one.',
          optional: true,
        },
      ],
      returns: [{ type: 'Centity[]' }],
    },
    {
      name: 'entities',
      doc: "The entities in this world's loaded chunks, players included (as `Player` handles), or those the filter picks. Never the entities centities are drawn with. Empty once the world has gone. A filter key that isn't one of `EntityFilter`'s is an error.",
      params: [
        {
          name: 'filter',
          type: 'EntityFilter',
          doc: 'Only one kind, one tag, living ones, or those near a point.',
          optional: true,
        },
      ],
      returns: [{ type: 'Entity[]' }],
      example:
        'for _, zombie in ipairs(world:entities({ kind = "minecraft:zombie", near = here, radius = 16 })) do\n  zombie:set_glowing(true)\nend',
    },
    {
      name: 'spawn_entity',
      doc: "Spawns an entity, set up as `options` say before anything sees it (its `entity_spawn` handlers included). A kind the server doesn't have, or one that can't be spawned (a player), is an error.",
      params: [
        { name: 'kind', type: 'string', doc: 'What: `"minecraft:zombie"`.' },
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: 'Where: a `Location` in this world (facing its way, when it has a facing), or a position.',
        },
        { name: 'options', type: 'EntitySpawnOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'Entity?',
          doc: "The new entity, or `nil` when the world has gone, its chunk isn't loaded, or an `entity_spawn` handler (or another plugin) cancelled it.",
        },
      ],
      example:
        'local guard = world:spawn_entity("minecraft:iron_golem", vec3(0, 64, 0), {\n  custom_name = "<aqua>Guard",\n  tags = { "guard" },\n  data = { post = vec3(0, 64, 0) },\n})',
    },
    {
      name: 'spawn_item',
      doc: "Drops an item stack at a position, as if a block broke there. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [
        { name: 'position', type: 'Vec3', doc: 'Where, in this world.' },
        { name: 'item', type: 'Item', doc: '' },
      ],
      returns: [
        {
          type: 'DroppedItem?',
          doc: "The dropped item, or `nil` when the world has gone, its chunk isn't loaded, or something cancelled it.",
        },
      ],
    },
    {
      name: 'spawn_particle',
      doc: "Spawns particles at a position. The particle's id comes from the server (`\"minecraft:flame\"`), and which options it takes depends on its kind: `color` and `size` for dust, `color` for coloured particles, `block_state` for block particles, `item` for item particles. An unknown particle, an option it doesn't take, or one it needs left out is an error. Particles that need data NetherForge can't send (vibrations, trails) are an error too. Players see them within 32 blocks, or 128 with `force`.",
      params: [
        {
          name: 'particle',
          type: 'string',
          doc: 'A particle id: `"minecraft:flame"`, `"minecraft:dust"`.',
        },
        { name: 'position', type: 'Vec3', doc: 'Where, in the world.' },
        { name: 'options', type: 'ParticleOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example:
        'world:spawn_particle("minecraft:dust", this:position() + vec3(0, 1, 0), {\n  count = 20, spread = vec3(0.3, 0.3, 0.3), color = "#ff8800", size = 1.5,\n})',
    },
    {
      name: 'play_sound',
      doc: 'Plays a sound at a position, for everyone near enough to hear it. A Minecraft sound (`"minecraft:block.note_block.pling"`, checked against the server\'s sounds) or a resource pack sound (`"<pack>/<key>"`, like `"ui/click"`, your package\'s own, or another loaded package\'s as `"<namespace>:<pack>/<key>"`: [looks are values](packages.md#looks-are-values)). An unknown sound is an error.',
      params: [
        { name: 'sound', type: 'string', doc: 'A sound id.' },
        { name: 'position', type: 'Vec3', doc: 'Where it comes from, in the world.' },
        { name: 'options', type: 'SoundOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example:
        'world:play_sound("minecraft:block.note_block.pling", block:position(), { pitch = 1.5 })',
    },
    {
      name: 'explode',
      doc: "Makes an explosion at a position, as TNT would (TNT's power is 4): it damages and knocks back entities, and breaks blocks unless `break_blocks` is `false`.",
      params: [
        { name: 'position', type: 'Vec3', doc: 'Its centre.' },
        {
          name: 'power',
          type: 'number',
          doc: 'How strong: 4 is TNT, 6 a charged creeper. At least 0.',
        },
        { name: 'options', type: 'ExplosionOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'boolean',
          doc: 'Whether it went off: `false` when another plugin stopped it, or the world has gone.',
        },
      ],
    },
    {
      name: 'strike_lightning',
      doc: 'Strikes lightning at a position: it sets fire and hurts what it hits, unless `effect_only`.',
      params: [
        { name: 'position', type: 'Vec3', doc: 'Where it lands.' },
        { name: 'options', type: 'LightningOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
    },
    {
      name: 'raycast',
      doc: "Casts a ray from a point and answers the first thing it hits within `max_distance`: a block's collision shape, an entity's hitbox, or a centity's hitbox (tested with the same shapes clicks are). `nil` when it hits nothing, and in a world that has gone. Blocks in unloaded chunks aren't hit.",
      params: [
        { name: 'from', type: 'Vec3', doc: 'Where the ray starts, in the world.' },
        { name: 'direction', type: 'Vec3', doc: 'Which way it goes: any length but zero.' },
        { name: 'max_distance', type: 'number', doc: 'How far it reaches, in blocks.' },
        { name: 'options', type: 'RaycastOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'RaycastHit?' }],
      example:
        'local world, eyes, facing = player:world(), player:eye_position(), player:direction()\nlocal hit = world and eyes and facing and world:raycast(eyes, facing, 20)\nif hit and hit.centity then\n  hit.centity:play_animation("wobble")\nend',
    },
    {
      name: 'overlap_box',
      doc: `Everything whose hitbox is inside or touches a box, nearest the box's middle first. ${QUERY_FINDS} ${QUERY_LOADED}`,
      params: [
        { name: 'min', type: 'Vec3', doc: 'One corner, in the world.' },
        {
          name: 'max',
          type: 'Vec3',
          doc: 'The opposite corner. The corners may come in either order.',
        },
        { name: 'options', type: 'QueryOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'QueryHit[]' }],
      example:
        'local corner = vec3(0, 64, 0)\nfor _, hit in ipairs(world:overlap_box(corner, corner + vec3(4, 3, 4), { players = false })) do\n  if hit.centity then\n    hit.centity:remove()\n  end\nend',
    },
    {
      name: 'overlap_sphere',
      doc: `Everything whose hitbox comes within \`radius\` of a point, nearest first: what an explosion or an aura reaches. ${QUERY_FINDS} ${QUERY_LOADED}`,
      params: [
        { name: 'center', type: 'Vec3', doc: 'The middle, in the world.' },
        { name: 'radius', type: 'number', doc: 'In blocks, at least 0.' },
        { name: 'options', type: 'QueryOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'QueryHit[]' }],
      example:
        'for _, hit in ipairs(world:overlap_sphere(vec3(0, 64, 0), 4, { entities = false })) do\n  log(hit.distance)\nend',
    },
    {
      name: 'overlap_cone',
      doc: `Everything whose hitbox reaches into a cone from a point, nearest first: what a swing or a breath in front of someone reaches. A hitbox counts when its point nearest the cone's axis is within \`length\` of the tip and within \`angle\` of the axis, or the tip is inside it. ${QUERY_FINDS} ${QUERY_LOADED}`,
      params: [
        { name: 'origin', type: 'Vec3', doc: "The cone's tip, in the world." },
        { name: 'direction', type: 'Vec3', doc: 'Which way it opens: any length but zero.' },
        {
          name: 'length',
          type: 'number',
          doc: 'How far it reaches from the tip, in blocks, at least 0.',
        },
        {
          name: 'angle',
          type: 'number',
          doc: 'How wide it opens, in degrees from its axis to its side: 0 to 180 (90 is everything in front, 180 a sphere).',
        },
        { name: 'options', type: 'QueryOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'QueryHit[]' }],
      example:
        'local eyes, facing = assert(player:eye_position()), assert(player:direction())\nfor _, hit in ipairs(world:overlap_cone(eyes, facing, 3, 45, { exclude = { player } })) do\n  if hit.centity then\n    hit.centity:emit("game:struck", { by = player })\n  end\nend',
    },
    {
      name: 'sweep',
      doc: `Moves a sphere of \`options.radius\` (0, a point, by default) from one point to another and answers everything it touches on the way, in the order it meets them: what a fast or piercing shot passes through in a tick, which a ray (stopping at the first thing) can't say. Blocks stop it unless \`blocks = false\`: the block it meets is then the last hit, with its \`normal\`, and nothing past it is found. ${QUERY_FINDS} ${QUERY_LOADED}`,
      params: [
        { name: 'from', type: 'Vec3', doc: 'Where it starts, in the world.' },
        { name: 'to', type: 'Vec3', doc: 'Where it would end.' },
        { name: 'options', type: 'SweepOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'QueryHit[]' }],
      example:
        'local position, velocity = vec3(0, 65, 0), vec3(2, 0, 0)\nlocal hits = world:sweep(position, position + velocity, { radius = 0.25, players = false })\nfor _, hit in ipairs(hits) do\n  if hit.block then\n    break -- it struck a wall\n  elseif hit.centity then\n    hit.centity:emit("game:hit", { damage = 5 })\n  end\nend',
    },
    {
      name: 'nearest',
      doc: `The nearest thing whose hitbox comes within \`radius\` of a point, or \`nil\` when there's nothing: what a homing shot or a mob looking for a target wants. With \`line_of_sight = true\`, only something a ray from the point reaches without a block in the way. ${QUERY_FINDS} ${QUERY_LOADED}`,
      params: [
        { name: 'position', type: 'Vec3', doc: 'Where to look from, in the world.' },
        { name: 'radius', type: 'number', doc: 'How far to look, in blocks, at least 0.' },
        { name: 'options', type: 'QueryOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'QueryHit?' }],
      example:
        'local target = world:nearest(assert(this:position()), 16, { centities = false, line_of_sight = true })\nif target and target.entity then\n  this:look_at(target.position)\nend',
    },
    {
      name: 'game_rule',
      doc: "A game rule's value: a `boolean` or an `integer`, depending on the rule. `nil` once the world has gone. A rule the server doesn't have is an error.",
      params: [
        {
          name: 'rule',
          type: 'string',
          doc: 'Its id, as the server names it: `"keep_inventory"`, `"random_tick_speed"`.',
        },
      ],
      returns: [{ type: 'any' }],
    },
    {
      name: 'set_game_rule',
      doc: "Sets a game rule in this world. A rule the server doesn't have, or a value of the wrong type for it (a `boolean` rule given a number), is an error.",
      params: [
        { name: 'rule', type: 'string', doc: 'Its id: `"keep_inventory"`.' },
        { name: 'value', type: 'any', doc: 'A `boolean` or an `integer`, as the rule takes.' },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example: 'world:set_game_rule("keep_inventory", true)',
    },
    {
      name: 'spawn_limit',
      doc: "How many mobs of a spawn category the game keeps around each player here before it stops spawning more (the mob cap, per player, scaled by how many chunks are loaded around them), or `nil` once the world has gone. It's this world's own setting, or the server's (`bukkit.yml`) when the world has none.",
      params: [
        {
          name: 'category',
          type: '"monster"|"animal"|"water_animal"|"water_ambient"|"water_underground_creature"|"ambient"|"axolotl"',
          doc: 'Which mobs count: `"monster"` for hostile ones, `"animal"` for farm animals, `"ambient"` for bats, and so on.',
        },
      ],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_spawn_limit',
      doc: "Sets this world's mob cap for a spawn category (see `world:spawn_limit`). `0` stops that category spawning naturally. A negative number goes back to the server's own setting. Mobs already there stay.",
      params: [
        {
          name: 'category',
          type: '"monster"|"animal"|"water_animal"|"water_ambient"|"water_underground_creature"|"ambient"|"axolotl"',
          doc: 'Which mobs count.',
        },
        { name: 'limit', type: 'integer', doc: 'The most of them there may be.' },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example: 'world:set_spawn_limit("monster", 30)',
    },
    {
      name: 'spawn_interval',
      doc: "How many ticks pass between the game's attempts to spawn mobs of a spawn category here, or `nil` once the world has gone. It's this world's own setting, or the server's (`bukkit.yml`) when the world has none.",
      params: [
        {
          name: 'category',
          type: '"monster"|"animal"|"water_animal"|"water_ambient"|"water_underground_creature"|"ambient"|"axolotl"',
          doc: 'Which mobs.',
        },
      ],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_spawn_interval',
      doc: "Sets how many ticks pass between spawn attempts for a spawn category in this world (see `world:spawn_interval`): lower is faster. `0` stops that category spawning naturally. A negative number goes back to the server's own setting.",
      params: [
        {
          name: 'category',
          type: '"monster"|"animal"|"water_animal"|"water_ambient"|"water_underground_creature"|"ambient"|"axolotl"',
          doc: 'Which mobs.',
        },
        { name: 'ticks', type: 'integer', doc: 'Ticks between attempts.' },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example: 'world:set_spawn_interval("monster", 2)',
    },
    {
      name: 'is_managed',
      doc: "Whether this project may unload it (`world:unload`): it's one the project's scripts created (`nf.worlds.create`, `nf.worlds.copy`) or named in `netherforge.json`'s `managedWorlds`, and it isn't the server's main world. Any script can find, load and use any world; only managed ones can be taken away.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'unload',
      doc: "Unloads the world: players in it are moved out first (to the spawn of `options.move_players_to`, else of the main world), then the server saves it (unless `save` is `false`) and lets it go. With `delete`, its files are deleted too, off the main thread, and the project forgets it. Only a world this project manages can be unloaded (see `world:is_managed`): any other, the server's main world included, is an error, as is a `move_players_to` in this same world. A world it created stays the project's after a restart; find it again with `nf.worlds.load`.",
      params: [{ name: 'options', type: 'WorldUnloadOptions', doc: '', optional: true }],
      returns: [
        {
          type: 'boolean',
          doc: 'Whether it was unloaded: `false` when it had gone already, or the server (another plugin) kept it.',
        },
      ],
      example:
        'arena:unload({ save = false, delete = true, move_players_to = nf.worlds.get("lobby") })',
    },
    {
      name: 'border',
      doc: "The world's border: the wall players can't pass, shown to everyone in the world (except a player given a border of their own with `player:border()`). `nil` once the world has gone.",
      params: [],
      returns: [{ type: 'WorldBorder?' }],
      example:
        'local border = world:border()\nif border then\n  border:set_center(vec3(0, 0, 0))\n  border:set_size(200)\n  border:set_size(20, { ticks = 20 * 60 })\nend',
    },
    {
      name: 'save_structure',
      doc: "Saves the box between two corners (both included) as a structure named `id`, to place later with `place_structure`: an arena to reset, a prefab to stamp. It's saved in the server's data folder, not the project, and kept across restarts; saving the same id again replaces it. An id that isn't an id (lowercase letters, digits and `_`), or that one of the project's structures (`structures/<id>.nbt`) or builds (`builds/<id>/`) already has, is an error, as is a box over 48 blocks along any side. Air is saved too, so placing it puts back the empty space as well; it loads the chunks the box covers, as `/place` does.",
      params: [
        { name: 'id', type: 'string', doc: 'Its name: `"arena_reset"`.', names: 'structure' },
        { name: 'from', type: 'Vec3', doc: 'One corner.' },
        { name: 'to', type: 'Vec3', doc: 'The opposite corner.' },
        { name: 'options', type: 'StructureSaveOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it was saved: `false` when the world has gone, or the file couldn't be written.",
        },
      ],
      example:
        'world:save_structure("arena_reset", vec3(0, 60, 0), vec3(47, 90, 47), { entities = false })',
    },
    {
      name: 'place_structure',
      doc: "Places a structure with its smallest corner at `position` (before it's turned): one of the project's (`structures/<id>.nbt`) or one a script saved (`save_structure`). Loads the chunks it covers, as `/place` does, all in this tick: `place_gradually` spreads a big one over several. A build (`builds/<id>/`) is always placed that way, so naming one here is an error, as is a structure there's none of, one whose file can't be read, a `rotation` that isn't a multiple of 90 or an `integrity` outside 0 to 1.",
      params: [
        { name: 'id', type: 'string', doc: 'Which structure.', names: 'structure' },
        {
          name: 'position',
          type: 'Vec3',
          doc: 'Where its smallest corner goes, floored to a block.',
        },
        { name: 'options', type: 'StructurePlaceOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when the world has gone.' }],
      example:
        'world:place_structure("arena_reset", vec3(0, 60, 0), { rotation = 90, mirror = "none", integrity = 1, entities = false })',
    },
    asyncFunction({
      name: 'place_gradually',
      doc: `Places a structure or a build (\`builds/<id>/\`, a structure too big for one file) like \`place_structure\`, its smallest corner at \`position\` before it's turned, but over as many ticks as it takes. ${GRADUAL} Its blocks go in from the bottom up (a build's a part at a time), loading the chunks it covers as it reaches them, then its entities, once every block is in. Blocks go in as they were saved, with no updates to their neighbours, as \`/place\` puts them. A structure or build there's none of, a \`rotation\` that isn't a multiple of 90, an \`integrity\` outside 0 to 1 or a \`per_tick\` below 1 is an error at once. A part that can't be read, the world going, or this script stopping stops it part way, and what was placed stays.`,
      later: 'It takes as many ticks as the placing does',
      params: [
        { name: 'id', type: 'string', doc: 'Which structure or build.', names: 'structure' },
        {
          name: 'position',
          type: 'Vec3',
          doc: 'Where its smallest corner goes, floored to a block.',
        },
        { name: 'options', type: 'GradualPlaceOptions', doc: '', optional: true },
      ],
      value: {
        name: 'placed',
        type: 'integer',
        doc: "how many blocks it placed (those `integrity` left out, or outside the world's heights, are not counted)",
      },
      example:
        'nf.task(function()\n  local placed, err = world:place_gradually("castle", vec3(0, 64, 0), { rotation = 90 })\n  if not placed then\n    print("The castle stopped part way: " .. err)\n    return\n  end\n  nf.server.broadcast("The castle is up: " .. placed .. " blocks")\nend)',
    }),
    ...eventFunctions(
      'World',
      'world',
      'nf.worlds.default():on("block_place", function(event)\n  event.player:send_message("Placed " .. event.state)\nend)',
    ),
  ],
}

/** A block, by position. */
export const blockClass: LuaClass = {
  name: 'Block',
  doc: `One block position in one world. The handle is the position, not what's there: it reads the world live, so a \`Block\` kept from earlier sees later changes. ${UNLOADED}`,
  methods: true,
  handle: {
    key: [
      { name: 'world', type: 'string' },
      // x, y and z packed into one integer, as Minecraft packs a block position.
      { name: 'position', type: 'integer' },
    ],
  },
  fields: [],
  functions: [
    {
      name: 'world',
      doc: "The world it's in.",
      params: [],
      returns: [{ type: 'World' }],
    },
    {
      name: 'position',
      doc: 'Its corner with the smallest x, y and z: whole numbers, in the world.',
      params: [],
      returns: [{ type: 'Vec3' }],
    },
    {
      name: 'location',
      doc: 'Its corner as a `Location`, with no facing.',
      params: [],
      returns: [{ type: 'Location' }],
    },
    {
      name: 'kind',
      doc: 'What block it is, like `"minecraft:stone"`, or `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'state',
      doc: 'Its whole block state, like `"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"` (properties sorted), or `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'property',
      doc: 'One property\'s value, like `"east"` for `facing`, or `nil` when the block has no such property or its chunk isn\'t loaded.',
      params: [{ name: 'name', type: 'string', doc: 'The property: `"facing"`.' }],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'properties',
      doc: 'Every property and its value; empty for a block without any, and `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'table<string, string>?' }],
    },
    {
      name: 'is_air',
      doc: "Whether it's air (any kind). `false` in an unloaded chunk.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_solid',
      doc: "Whether it's solid: something players stand on and can't walk through. `false` in an unloaded chunk.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_liquid',
      doc: "Whether it's water or lava. `false` in an unloaded chunk.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_state',
      doc: "Changes it, as `world:set_block` does. A block state the server doesn't have is an error.",
      params: [
        { name: 'state', type: 'string', doc: 'A block state: `"minecraft:stone"`.' },
        { name: 'options', type: 'BlockSetOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` in an unloaded chunk.' }],
    },
    {
      name: 'set_property',
      doc: "Changes one property, keeping the rest of its state. A property the block doesn't have, or a value it can't take, is an error.",
      params: [
        { name: 'name', type: 'string', doc: 'The property: `"facing"`.' },
        { name: 'value', type: 'string', doc: 'Its new value: `"north"`.' },
      ],
      returns: [{ type: 'boolean', doc: '`false` in an unloaded chunk.' }],
      example:
        'local door = world:block(vec3(10, 64, 10))\nif door then\n  door:set_property("open", "true")\nend',
    },
    {
      name: 'break_naturally',
      doc: 'Breaks it as a player would: it drops what it would drop (for that tool, when given) and turns to air.',
      params: [
        {
          name: 'tool',
          type: 'Item',
          doc: 'What it was broken with, for its drops: silk touch, fortune, or the wrong tool dropping nothing.',
          optional: true,
        },
      ],
      returns: [
        { type: 'boolean', doc: 'Whether it broke: `false` for air, or in an unloaded chunk.' },
      ],
    },
    {
      name: 'relative',
      doc: 'The block at an offset from this one.',
      params: [
        {
          name: 'offset',
          type: 'Vec3|"up"|"down"|"north"|"south"|"east"|"west"',
          doc: 'An offset in blocks (floored), or the face of the neighbour: `"up"` is the block above.',
        },
      ],
      returns: [{ type: 'Block' }],
      example: 'local above = block:relative("up")',
    },
    {
      name: 'inventory',
      doc: "What a container block holds (a chest, a barrel, a hopper, a furnace, a shulker box), as an `Inventory`: the same one players see. A double chest's is both halves. `nil` for a block that isn't a container, and in an unloaded chunk.",
      params: [],
      returns: [{ type: 'Inventory?' }],
      example:
        'local chest = world:block(vec3(10, 64, 10)):inventory()\nif chest then\n  chest:add_item({ kind = "minecraft:bread", count = 8 })\nend',
    },
    {
      name: 'custom',
      doc: "The project block that's here, as a `CustomBlock` (the same handle: one position, one block), or `nil` when what's here isn't one of the project's blocks, or its chunk isn't loaded.",
      params: [],
      returns: [{ type: 'CustomBlock?' }],
      example:
        'local custom = world:block(vec3(10, 64, 10)):custom()\nif custom then\n  log(custom:id())\nend',
    },
    {
      name: 'light_level',
      doc: 'How bright it is there, 0 to 15: the brighter of block light and sky light. `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'sky_light_level',
      doc: 'How much sky light reaches it, 0 to 15, whatever the time of day. `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'biome',
      doc: 'The biome here, named as `locate_biome` takes one: a biome of the project reads as its id (`"ruby_grove"`), a package\'s as `"acme:grove"` (to a package\'s own scripts, its own is bare), the game\'s as `"minecraft:plains"`. The game keeps biomes per 4×4×4 blocks, so neighbouring blocks share one. `nil` in an unloaded chunk.',
      params: [],
      returns: [{ type: 'string?' }],
      example:
        'if block:biome() == "minecraft:snowy_plains" then\n  block:set_state("minecraft:snow_block")\nend',
    },
    {
      name: 'data',
      doc: `This position's own table for scripts to keep things in, saved with the chunk: the same table every time, shared by every script. Change it in place; it's saved when the server autosaves, when the chunk unloads and when the server stops. It stays with the position, whatever happens to the block. ${DATA_VALUES} \`nil\` in an unloaded chunk.`,
      params: [],
      returns: [{ type: 'table?' }],
      example: 'local data = block:data()\nif data then\n  data.uses = (data.uses or 0) + 1\nend',
    },
  ],
}

/** A world border, or a player's own. */
export const worldBorderClass: LuaClass = {
  name: 'WorldBorder',
  doc: "A border: the wall at the edge of a square area, which players can't walk through and which hurts them outside it. Either a world's (`world:border()`), which everyone in the world sees, or one player's own (`player:border()`), which only they see and which the server draws for them but doesn't enforce: it doesn't hurt them, and doesn't stop what isn't them. Sizes and positions are blocks; times are ticks (20 a second). Its methods answer `nil` or `false` once its world has gone, or for a player's own, once they've left or gone back to their world's (`player:reset_border()`).",
  methods: true,
  handle: {
    key: [
      // "world" with the world's name, or "player" with the player's UUID.
      { name: 'owner', type: 'string' },
      { name: 'id', type: 'string' },
    ],
  },
  fields: [],
  functions: [
    {
      name: 'center',
      doc: 'Its centre, with `y` 0, or `nil` once it has gone.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_center',
      doc: 'Moves its centre. `y` is ignored. More than 29,999,984 blocks out is an error.',
      params: [{ name: 'center', type: 'Vec3', doc: 'Where.' }],
      returns: [{ type: 'boolean', doc: '`false` once it has gone.' }],
    },
    {
      name: 'size',
      doc: "How wide it is, side to side, in blocks: while it's growing or shrinking, how wide it is now. `nil` once it has gone.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_size',
      doc: 'Changes how wide it is: at once, or moving steadily there over `options.ticks`. A size below 1 or over 59,999,968, or negative `ticks`, is an error.',
      params: [
        { name: 'size', type: 'number', doc: 'Side to side, in blocks.' },
        { name: 'options', type: 'BorderSizeOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` once it has gone.' }],
      example: 'border:set_size(50, { ticks = 20 * 30 }) -- shrinks to 50 over 30 seconds',
    },
    {
      name: 'damage',
      doc: 'How it hurts players outside it, or `nil` once it has gone.',
      params: [],
      returns: [{ type: 'BorderDamage?' }],
    },
    {
      name: 'set_damage',
      doc: "Changes how it hurts players outside it; what `damage` leaves out stays as it was. A negative value is an error. A player's own border is only drawn, so this changes nothing for them.",
      params: [{ name: 'damage', type: 'BorderDamage', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` once it has gone.' }],
      example: 'border:set_damage({ amount = 0.5, buffer = 2 })',
    },
    {
      name: 'warning',
      doc: "When players' screens turn red as it comes near, or `nil` once it has gone.",
      params: [],
      returns: [{ type: 'BorderWarning?' }],
    },
    {
      name: 'set_warning',
      doc: "Changes when players' screens turn red; what `warning` leaves out stays as it was. A negative value is an error.",
      params: [{ name: 'warning', type: 'BorderWarning', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` once it has gone.' }],
      example: 'border:set_warning({ distance = 5, ticks = 20 * 15 })',
    },
    {
      name: 'contains',
      doc: "Whether a place is inside it. A `Location` in another world is outside a world's border; a player's own border has no world, so only the position counts. `false` once it has gone.",
      params: [
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: 'Where: a `Location`, or a position.',
        },
      ],
      returns: [{ type: 'boolean' }],
      example:
        'local here = player:location()\nif here and not border:contains(here) then\n  player:send_message("<red>Get back inside!")\nend',
    },
  ],
}

/** The plain tables World and Block take and give. */
export const worldShapes: LuaClass[] = [
  shape(
    'WorldCreateOptions',
    "How `nf.worlds.create` makes a world. A key that isn't one of these is an error.",
    [
      {
        name: 'generator',
        type: '"normal"|"flat"|"void"|"amplified"|"large_biomes"?',
        doc: 'Which of the game\'s own world types generates it: the usual (`"normal"`), the default superflat (`"flat"`), nothing at all (`"void"`, for maps built or placed by scripts), or one of the other vanilla world types (`"amplified"`, `"large_biomes"`). Default `"normal"`. Not with `terrain`, which is one of the project\'s instead. Only a `"normal"` environment takes it: a nether or an end always generates as the game\'s own.',
      },
      {
        name: 'terrain',
        type: 'string?',
        doc: 'One of the project\'s terrains (`terrain/<id>.json`; `"acme:hills"` for a package\'s), which generates it in place of the game\'s own. Not with `generator`. Kept with the world, so it generates the same way when it\'s loaded again; a terrain the project changes later only shapes chunks generated afterwards. A world with a terrain is a `"normal"` environment.',
      },
      {
        name: 'environment',
        type: '"normal"|"nether"|"end"?',
        doc: 'Which kind of dimension: its sky, its light, whether water boils. Default `"normal"`.',
      },
      {
        name: 'dimension_type',
        type: 'string?',
        doc: "One of the project's dimension types (`dimension_types/<id>.json`; `\"acme:deep\"` for a package's): the world's build limits, light, sky and rules (beds, respawn anchors, piglins, raids) are its. Kept with the world, so it loads the same again. The server learns dimension types only as it starts: one it didn't start with (added or fixed since) is an error until it restarts. Default: the environment's own (the game's overworld, nether or end).",
      },
      {
        name: 'seed',
        type: 'integer?',
        doc: 'Its seed: a project generator makes the same world for the same seed. Default: a random one.',
      },
      {
        name: 'structures',
        type: 'boolean?',
        doc: 'Whether villages, temples and the like generate. Default `true`.',
      },
      {
        name: 'keep_spawn_loaded',
        type: 'boolean?',
        doc: 'Whether the chunks around its spawn stay loaded with nobody near. Default `false`.',
      },
    ],
  ),
  shape(
    'WorldUnloadOptions',
    "How `world:unload` lets a world go. A key that isn't one of these is an error.",
    [
      {
        name: 'save',
        type: 'boolean?',
        doc: 'Whether it saves first. Default `true`; `false` throws away what changed since the last save (with `delete`, nothing is saved either way).',
      },
      {
        name: 'delete',
        type: 'boolean?',
        doc: 'Whether its files are deleted afterwards, and the project forgets it. Default `false`.',
      },
      {
        name: 'move_players_to',
        type: 'World?',
        doc: 'Which world the players in it go to, at its spawn. Default: the main world. To send them somewhere exact, teleport them before unloading.',
      },
    ],
  ),
  shape(
    'BorderSizeOptions',
    "How `border:set_size` changes it. A key that isn't one of these is an error.",
    [
      {
        name: 'ticks',
        type: 'integer?',
        doc: 'How long it takes to get there, moving steadily. Default 0: at once.',
      },
    ],
  ),
  shape(
    'BorderDamage',
    "How a border hurts players outside it: what `border:damage()` gives and `border:set_damage` takes. A key that isn't one of these is an error.",
    [
      {
        name: 'amount',
        type: 'number?',
        doc: 'Damage each second for every block past `buffer` (half a heart is 1). The game starts at 0.2.',
      },
      {
        name: 'buffer',
        type: 'number?',
        doc: 'How many blocks outside the border are safe. The game starts at 5.',
      },
    ],
  ),
  shape(
    'BorderWarning',
    "When players' screens turn red as a border comes near: what `border:warning()` gives and `border:set_warning` takes. A key that isn't one of these is an error.",
    [
      {
        name: 'distance',
        type: 'integer?',
        doc: 'Within this many blocks of it. The game starts at 5.',
      },
      {
        name: 'ticks',
        type: 'integer?',
        doc: 'When a shrinking border will reach them within this many ticks. The game starts at 300 (15 seconds).',
      },
    ],
  ),
  shape(
    'StructureSaveOptions',
    "What `world:save_structure` keeps. A key that isn't one of these is an error.",
    [
      {
        name: 'entities',
        type: 'boolean?',
        doc: 'Whether the entities in the box are saved with the blocks (never players, and never what centities are drawn with). Default `false`.',
      },
    ],
  ),
  shape(
    'StructurePlaceOptions',
    "How `world:place_structure` places one. A key that isn't one of these is an error.",
    [
      {
        name: 'rotation',
        type: 'integer?',
        doc: 'How far it turns clockwise (seen from above) about its smallest corner, in degrees: 0, 90, 180 or 270 (any multiple of 90). Default 0.',
      },
      {
        name: 'mirror',
        type: '"none"|"left_right"|"front_back"?',
        doc: 'Whether it\'s mirrored first: `"left_right"` flips it along z, `"front_back"` along x. Default `"none"`.',
      },
      {
        name: 'integrity',
        type: 'number?',
        doc: 'How much of it is placed, 0 to 1: each block is kept with this chance. Default 1: all of it.',
      },
      {
        name: 'entities',
        type: 'boolean?',
        doc: 'Whether the entities saved with it are placed too. Default `true`.',
      },
    ],
  ),
  shape(
    'GradualPlaceOptions',
    "How `world:place_gradually` places one. A key that isn't one of these is an error.",
    [
      {
        name: 'rotation',
        type: 'integer?',
        doc: 'How far it turns clockwise (seen from above) about its smallest corner, in degrees: 0, 90, 180 or 270 (any multiple of 90). Default 0.',
      },
      {
        name: 'mirror',
        type: '"none"|"left_right"|"front_back"?',
        doc: 'Whether it\'s mirrored first: `"left_right"` flips it along z, `"front_back"` along x. Default `"none"`.',
      },
      {
        name: 'integrity',
        type: 'number?',
        doc: 'How much of it is placed, 0 to 1: each block is kept with this chance. Default 1: all of it.',
      },
      {
        name: 'entities',
        type: 'boolean?',
        doc: 'Whether the entities saved with it are placed too, once its blocks are in. Default `true`.',
      },
      {
        name: 'per_tick',
        type: 'integer?',
        doc: "The most blocks it places in one tick, 1 or more: slower than the server's share, never faster. Default: as many as its share allows.",
      },
    ],
  ),
  shape(
    'GradualFillOptions',
    "How `world:fill_gradually` fills. A key that isn't one of these is an error.",
    [
      {
        name: 'update',
        type: 'boolean?',
        doc: 'Whether neighbours react to each block (sand falls, water flows), as `BlockSetOptions` says. Default `true`.',
      },
      {
        name: 'per_tick',
        type: 'integer?',
        doc: "The most blocks it goes through in one tick, 1 or more: slower than the server's share, never faster. Default: as many as its share allows.",
      },
    ],
  ),
  shape(
    'BlockBoxOptions',
    "What `world:blocks` gives for each block. A key that isn't one of these is an error.",
    [
      {
        name: 'what',
        type: '"kind"|"solid"|"class"?',
        doc: 'What a block is given as: `"kind"` its block id (`"minecraft:stone"`), `"solid"` whether it\'s solid, as `block:is_solid()` says, or `"class"` a number, `0` for a block nothing stands in the way of (air, grass, a torch), `1` for water or lava, `2` for a solid one. Default `"kind"`.',
      },
      {
        name: 'custom',
        type: 'boolean?',
        doc: 'Whether a block of the project\'s own gives its id (`"wooden_chair"`, as `block:custom():id()` says) in place of what `what` would make of the note block it\'s held as. Default `false`.',
      },
    ],
  ),
  shape(
    'BlockBox',
    "What `world:blocks` read: each block of the box, in one list, and the box's size and corner.",
    [
      {
        name: 'cells',
        type: '(string|integer|boolean)[]',
        doc: 'Each block, x fastest, then z, then y: the block `x`, `y`, `z` from the corner is at `1 + x + size_x * (z + size_z * y)`. What each is depends on `options.what` (and `options.custom`).',
      },
      { name: 'size_x', type: 'integer', doc: 'How many blocks the box is along x.' },
      { name: 'size_y', type: 'integer', doc: 'How many blocks the box is along y.' },
      { name: 'size_z', type: 'integer', doc: 'How many blocks the box is along z.' },
      {
        name: 'corner',
        type: 'Vec3',
        doc: 'The block the box starts from: the lowest x, y and z of its corners, floored.',
      },
    ],
  ),
  shape('SkyOptions', "How a sky comes or goes. A key that isn't one of these is an error.", [
    {
      name: 'fade_ticks',
      type: 'integer?',
      doc: "Ticks it takes to fade in or out, through the sky's steps (its file's `fade`). Default 0: at once.",
    },
  ]),
  shape('BlockSetOptions', "How a block is changed. A key that isn't one of these is an error.", [
    {
      name: 'update',
      type: 'boolean?',
      doc: 'Whether its neighbours react (sand falls, redstone updates, water flows), as when a player places a block. Default `true`; `false` sets it quietly.',
    },
  ]),
  shape(
    'ParticleOptions',
    "How particles are spawned. Every key is optional; which ones a particle takes depends on its kind, and one it doesn't take is an error, as is any key that isn't one of these.",
    [
      {
        name: 'count',
        type: 'integer?',
        doc: 'How many, scattered around the position by `spread`. Default 1. With 0, one particle is sent moving along `spread` (a direction) at `speed`.',
      },
      {
        name: 'spread',
        type: 'Vec3?',
        doc: 'How far they scatter along each axis (a Gaussian spread), in blocks. Default none. With `count = 0`, the direction the one particle moves in.',
      },
      {
        name: 'speed',
        type: 'number?',
        doc: 'How fast they move, for particles that move. Default 0.',
      },
      {
        name: 'color',
        type: 'string?',
        doc: '`"#rrggbb"`: dust, dust that changes colour, and coloured particles. Default white.',
      },
      {
        name: 'to_color',
        type: 'string?',
        doc: '`"#rrggbb"`: what dust that changes colour fades to. Default white.',
      },
      {
        name: 'size',
        type: 'number?',
        doc: 'How big dust is, 0.01 to 4. Default 1.',
      },
      {
        name: 'block_state',
        type: 'string?',
        doc: 'Which block block particles are made of: `"minecraft:stone"`. Required for those.',
      },
      {
        name: 'item',
        type: 'Item?',
        doc: 'Which item item particles show. Required for those.',
      },
      {
        name: 'viewers',
        type: 'Player[]?',
        doc: 'Only these players see them (those near enough). Default: everyone near enough.',
      },
      {
        name: 'force',
        type: 'boolean?',
        doc: 'Show them even to players who turned particles down, and from up to 128 blocks away. Default `false`.',
      },
    ],
  ),
  shape('SoundOptions', "How a sound plays. A key that isn't one of these is an error.", [
    {
      name: 'volume',
      type: 'number?',
      doc: 'How loud, from 0. Default 1. Above 1 it carries further rather than getting louder: 16 blocks per 1.',
    },
    {
      name: 'pitch',
      type: 'number?',
      doc: 'How high, 0.5 (an octave down) to 2 (an octave up). Default 1.',
    },
    {
      name: 'category',
      type: '"master"|"music"|"record"|"weather"|"block"|"hostile"|"neutral"|"player"|"ambient"|"voice"|"ui"?',
      doc: 'Which of the player\'s volume sliders it follows. Default `"master"`.',
    },
  ]),
  shape(
    'WeatherOptions',
    "How weather set with `world:set_weather` lasts. A key that isn't one of these is an error.",
    [
      {
        name: 'ticks',
        type: 'integer?',
        doc: 'How long it lasts before the game picks again (at least 1). Leave it out for a random length, as the game chooses.',
      },
    ],
  ),
  shape(
    'ExplosionOptions',
    "How an explosion behaves. A key that isn't one of these is an error.",
    [
      { name: 'fire', type: 'boolean?', doc: 'Whether it sets fires. Default `false`.' },
      { name: 'break_blocks', type: 'boolean?', doc: 'Whether it breaks blocks. Default `true`.' },
    ],
  ),
  shape(
    'BiomeSearchOptions',
    "Where `world:locate_biome` looks. A key that isn't one of these is an error.",
    [
      {
        name: 'near',
        type: 'Vec3?',
        doc: "Where it looks from, in this world. Default the world's spawn.",
      },
      {
        name: 'radius',
        type: 'integer?',
        doc: `How far it looks, in blocks across: 1 to ${BIOME_SEARCH_LIMIT}. Default ${BIOME_SEARCH_LIMIT}, as \`/locate biome\`.`,
      },
    ],
  ),
  shape('LightningOptions', "How lightning strikes. A key that isn't one of these is an error.", [
    {
      name: 'effect_only',
      type: 'boolean?',
      doc: 'Only the flash and the thunder: no fire, no damage. Default `false`.',
    },
  ]),
  shape('RaycastOptions', "What a ray can hit. A key that isn't one of these is an error.", [
    { name: 'blocks', type: 'boolean?', doc: 'Whether blocks stop it. Default `true`.' },
    {
      name: 'entities',
      type: 'boolean?',
      doc: 'Whether entities (players included) stop it, by their hitboxes. Default `true`.',
    },
    {
      name: 'ignore',
      type: 'Entity[]?',
      doc: "Entities it goes through: the one it's cast from, usually. Default none.",
    },
    {
      name: 'centities',
      type: 'boolean?',
      doc: "Whether centities' hitboxes stop it. Default `true`.",
    },
    {
      name: 'fluids',
      type: 'boolean?',
      doc: 'Whether water and lava stop it, as solid blocks. Default `false`: it goes through them.',
    },
  ]),
  shape(
    'FloodOptions',
    'How `world:flood` fills. A block is named as it is in a game block file: an id (`"minecraft:oak_log"`), a `#tag` (`"#minecraft:logs"`) or a project block\'s id (`"ruby_ore"`), or by what it is: `"solid"` (what players stand on), `"liquid"` (water and lava) or `"passable"` (neither: air, torches, flowers). A key that isn\'t one of these is an error.',
    [
      {
        name: 'through',
        type: 'string[]',
        doc: 'The blocks it fills: each one it reaches that any of these names. Required.',
      },
      {
        name: 'except',
        type: 'string[]?',
        doc: 'Blocks it doesn\'t fill even when `through` names them: `{ "#minecraft:doors" }` keeps a room\'s air from leaking out through a door. Default none.',
      },
      {
        name: 'directions',
        type: 'Vec3[]?',
        doc: 'Which neighbours it goes on to from each block, as offsets of whole blocks (`vec3(0, 1, 0)` is the one above), at most 2 on each axis. Default the six that share a face: `vec3(1, 0, 0)`, `vec3(-1, 0, 0)` and so on. A tree felled up and sideways but never down leaves out every offset with y = -1.',
      },
      {
        name: 'limit',
        type: 'integer?',
        doc: 'The most blocks it fills, at least 1, at most 32,768. Default 4,096. `stopped` is `"limit"` when there was more.',
      },
      {
        name: 'bounds',
        type: 'Bounds?',
        doc: '`{ min = Vec3, max = Vec3 }`: it never fills a block outside this box (both corners\' blocks included), and `stopped` is `"bounds"` when it would have. Default: no box.',
      },
      {
        name: 'kinds',
        type: 'boolean?',
        doc: 'Also answer what each block is (`kinds`, `edge_kinds`): its id without properties (`"minecraft:oak_log"`), or a project block\'s id. Default `false`.',
      },
    ],
  ),
  shape('FloodResult', 'What `world:flood` filled.', [
    {
      name: 'positions',
      type: 'Vec3[]',
      doc: 'Every block it filled, as its lowest corner (whole numbers), the start first, then outward a step at a time.',
    },
    {
      name: 'edges',
      type: 'Vec3[]',
      doc: "Every block next to one it filled that it didn't fill (a tree's leaves, a room's walls and door), once each. Not a block in an unloaded chunk or outside its `bounds`.",
    },
    {
      name: 'kinds',
      type: 'string[]?',
      doc: 'With `kinds = true`, what each of `positions` is, in the same order. `nil` otherwise.',
    },
    {
      name: 'edge_kinds',
      type: 'string[]?',
      doc: 'With `kinds = true`, what each of `edges` is, in the same order. `nil` otherwise.',
    },
    {
      name: 'stopped',
      type: '"limit"|"unloaded"|"bounds"?',
      doc: 'Why it didn\'t fill everything it reaches: `"limit"` (more past its limit), `"unloaded"` (a chunk that isn\'t loaded, or the world\'s top or bottom, in its way) or `"bounds"`. `nil` when it filled all of it: a closed room, a whole tree.',
    },
  ]),
  shape(
    'QueryOptions',
    "What a spatial query (`world:overlap_box`, `overlap_sphere`, `overlap_cone`, `nearest`) finds. A key that isn't one of these is an error.",
    QUERY_FIELDS,
  ),
  {
    name: 'SweepOptions',
    doc: "How `world:sweep` moves and what it finds: everything `QueryOptions` takes, and these. A key that isn't one of them is an error.",
    methods: false,
    functions: [],
    fields: [
      ...QUERY_FIELDS,
      {
        name: 'radius',
        type: 'number?',
        doc: "The moving sphere's radius, in blocks, at least 0. Default 0: a point.",
      },
      {
        name: 'blocks',
        type: 'boolean?',
        doc: "Whether blocks stop it (their collision shapes, as `raycast` sees them). Default `true`. A block stops the sphere's middle, not its edge.",
      },
      {
        name: 'fluids',
        type: 'boolean?',
        doc: 'Whether water and lava stop it, as solid blocks. Default `false`.',
      },
    ],
  },
  shape(
    'QueryHit',
    'One thing a spatial query found. Exactly one of `centity`, `entity` and `block` is set: a `block` only ends a `world:sweep`.',
    [
      { name: 'centity', type: 'Centity?', doc: 'The centity found, or `nil`.' },
      {
        name: 'node',
        type: 'Node?',
        doc: "Which of `centity`'s nodes was nearest (its hitbox), or `nil` for anything else.",
      },
      { name: 'entity', type: 'Entity?', doc: 'The player or entity found, or `nil`.' },
      { name: 'block', type: 'Block?', doc: 'The block a `world:sweep` stopped at, or `nil`.' },
      {
        name: 'position',
        type: 'Vec3',
        doc: "Where: for an overlap or `nearest`, the point of the hitbox nearest the query's own point; for a `sweep`, where the sphere's middle is when it first touches (on a block, where it stops).",
      },
      {
        name: 'distance',
        type: 'number',
        doc: "How far that is from the query's own point; along the way, for a `sweep`. 0 when the point is inside the hitbox.",
      },
      {
        name: 'normal',
        type: 'Vec3?',
        doc: "For a `sweep`'s block, the unit direction straight out of the face it met: what a bounce reflects off. `nil` otherwise.",
      },
    ],
  ),
  shape(
    'RaycastHit',
    'What `world:raycast` hit: where, and what. Exactly one of `block`, `entity` and `centity` is set.',
    [
      { name: 'position', type: 'Vec3', doc: 'Where the ray met it, in the world.' },
      {
        name: 'normal',
        type: 'Vec3',
        doc: 'The unit direction straight out of the face it hit, in the world.',
      },
      { name: 'distance', type: 'number', doc: 'How far along the ray that is, in blocks.' },
      { name: 'block', type: 'Block?', doc: 'The block it hit, or `nil`.' },
      { name: 'entity', type: 'Entity?', doc: 'The entity it hit, or `nil`.' },
      { name: 'centity', type: 'Centity?', doc: 'The centity whose hitbox it hit, or `nil`.' },
      {
        name: 'node',
        type: 'Node?',
        doc: 'Which node of `centity` it hit (its hitbox), or `nil` for anything else.',
      },
    ],
  ),
]

/** `nf.worlds`. */
export const nfWorlds: LuaClass = {
  name: 'nf.worlds',
  doc: "The server's worlds: finding them, and making, loading and copying them.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'all',
      doc: 'Every loaded world, the default one first.',
      params: [],
      returns: [{ type: 'World[]' }],
    },
    {
      name: 'get',
      doc: 'A world by name, or `nil` when the server has none by that name loaded.',
      params: [{ name: 'name', type: 'string', doc: 'Its name: `"world_nether"`.' }],
      returns: [{ type: 'World?' }],
    },
    {
      name: 'create',
      doc: "Makes a new world, and loads it: from then on it's the project's, so it can unload it (`world:unload`), and it stays the project's across reloads and restarts. A name that isn't an id (lowercase letters, digits and `_`), or that a world already has (loaded, or saved on the server), is an error: `nf.worlds.load` loads a saved one. A `terrain` that isn't one of the project's terrains is an error, and so are both a `generator` and a `terrain`, a `terrain` with another environment than `\"normal\"`, and a `dimension_type` that isn't one of the project's dimension types or that the server didn't start with. Making a world takes the server a moment.",
      params: [
        { name: 'name', type: 'string', doc: 'Its name, which is also its folder: `"arena_1"`.' },
        { name: 'options', type: 'WorldCreateOptions', doc: '', optional: true },
      ],
      returns: [
        { type: 'World?', doc: "The new world, or `nil` when the server couldn't make it." },
      ],
      example:
        'local arena = nf.worlds.get("arena_1")\n  or nf.worlds.load("arena_1")\n  or nf.worlds.create("arena_1", { generator = "void", structures = false })\nlocal realm = nf.worlds.create("realm", { terrain = "ruby_hills", seed = 42 })',
    },
    {
      name: 'load',
      doc: "Loads a world the server has saved but not loaded (one a script created and unloaded, or any other world the server has), and returns it; one already loaded is just returned. `nil` when the server has no world saved by that name. A name with characters a world's can't have is an error.",
      params: [{ name: 'name', type: 'string', doc: 'Its name: `"lobby"`.' }],
      returns: [{ type: 'World?' }],
    },
    asyncFunction({
      name: 'copy',
      doc: "Makes a new world from one of the project's maps (`maps/<map>/`): its files are copied, then the server loads the copy as a world named `name`, the project's from then on (as for `nf.worlds.create`). A map there's none of, or a `name` that isn't an id or that a world already has (or that another copy is being made into), is an error at once. A copy that can't be made (the disk, or the server refusing it) fails, and the server's log says so too.",
      params: [
        { name: 'map', type: 'string', doc: 'Which map.', names: 'map' },
        { name: 'name', type: 'string', doc: 'The new world\'s name: `"arena_2"`.' },
      ],
      value: { name: 'world', type: 'World', doc: 'the new world' },
      example:
        'nf.task(function()\n  local arena, err = nf.worlds.copy("arena", "arena_2")\n  if not arena then\n    log("no arena: " .. err)\n    return\n  end\n  local spawn = arena:spawn_location()\n  if spawn then\n    player:teleport(spawn)\n  end\nend)',
    }),
    {
      name: 'default',
      doc: "The server's main world: where things go when nothing says which.",
      params: [],
      returns: [{ type: 'World' }],
      example: 'local world = nf.worlds.default()\nworld:set_time_of_day(6000)',
    },
  ],
}

/** `nf.structures`. */
export const nfStructures: LuaClass = {
  name: 'nf.structures',
  doc: "Structures: saved boxes of blocks that `world:place_structure` places. The project's are `structures/<id>.nbt`; scripts save more with `world:save_structure`. A build (`builds/<id>/`) is a structure too big for one file, kept in parts, which `world:place_gradually` places over several ticks; these functions name both alike.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'exists',
      doc: "Whether there's a structure or build by that id: one of the project's, or a structure a script saved.",
      params: [{ name: 'id', type: 'string', doc: '', names: 'structure' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'size',
      doc: "How big a structure or build is, in blocks along x, y and z (before it's turned), or `nil` when there's none by that id. One whose file (for a build, its last part's) can't be read is an error.",
      params: [{ name: 'id', type: 'string', doc: '', names: 'structure' }],
      returns: [{ type: 'Vec3?' }],
      example:
        'local size = nf.structures.size("house")\nif size then\n  world:place_structure("house", here - vec3(size.x / 2, 0, size.z / 2))\nend',
    },
  ],
}
