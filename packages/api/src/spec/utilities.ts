import type { Field, LuaClass } from '../types.ts'

const shape = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

/** The curves a keyframe's `easing` names in `centity.json` and particle effects, so scripts and files ease alike. */
const EASING = '"linear"|"step"|"ease_in"|"ease_out"|"ease_in_out"'

const ZONE_DOC =
  'Which time zone the clock time is in: an IANA name like `"Europe/London"` or `"America/New_York"`, `"UTC"`, or an offset like `"+02:00"`. Default the server owner\'s zone (`schedules.time-zone` in `config.yml`, the server\'s own by default), the one `nf.schedule` runs in. A zone the server doesn\'t know is an error.'

const PATTERN_DOC =
  "Letters stand for parts of the date: `yyyy` the year, `MM` the month as a number (`MMM` `Oct`, `MMMM` `October`), `dd` the day, `EEE` the weekday (`Sat`; `EEEE` `Saturday`), `HH` the hour from 00 to 23 (`hh` 01 to 12, with `a` for AM or PM), `mm` minutes, `ss` seconds, `XXX` the zone's offset (`+01:00`, or `Z`). Text in single quotes is kept as it is (`'at'`); punctuation and spaces needn't be. A letter that isn't a pattern letter is an error."

/** `nf.time`: dates and durations as text. */
export const nfTime: LuaClass = {
  name: 'nf.time',
  doc: 'Dates and durations as text, in the same unit as `nf.server.unix_time()`: milliseconds. For timers, use ticks (`nf.after`, `nf.wait`); this is for showing and reading clock times and lengths of time.',
  methods: false,
  fields: [],
  functions: [
    {
      name: 'format',
      doc: 'A moment as clock time, in English, written the way a pattern says.',
      params: [
        {
          name: 'time',
          type: 'integer',
          doc: 'Milliseconds since 1970-01-01 UTC, like `nf.server.unix_time()` returns.',
        },
        { name: 'pattern', type: 'string', doc: PATTERN_DOC },
        { name: 'options', type: 'TimeOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'string' }],
      example:
        'local now = nf.server.unix_time()\nplayer:send_message("It\'s " .. nf.time.format(now, "EEEE, d MMMM \'at\' HH:mm", { zone = "UTC" }) .. " UTC")',
    },
    {
      name: 'parse',
      doc: "Reads a clock time written the way a pattern says, as milliseconds since 1970-01-01 UTC. A pattern with no time of day reads as midnight; one with an offset (`XXX`) in it uses the offset it reads rather than `zone`. `nil` when the text doesn't match the pattern (it may come from a player); a pattern without a year, month and day, or with a letter that isn't a pattern letter, is an error.",
      params: [
        { name: 'text', type: 'string', doc: 'Like `"2026-10-03 18:00"`.' },
        { name: 'pattern', type: 'string', doc: 'As for `nf.time.format`.' },
        { name: 'options', type: 'TimeOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'integer?' }],
      example:
        'local start = nf.time.parse("2026-10-31 20:00", "yyyy-MM-dd HH:mm", { zone = "Europe/London" })\nif start and nf.server.unix_time() >= start then\n  nf.server.broadcast("<gold>The event has begun!")\nend',
    },
    {
      name: 'duration',
      doc: 'A length of time as short text, largest unit first and units that are 0 left out: `"1h 2m 5s"`, `"3d 4h"`, `"45s"`. Less than a second is `"0s"`; a negative length starts with `-`.',
      params: [{ name: 'milliseconds', type: 'integer', doc: 'How long.' }],
      returns: [{ type: 'string' }],
      example:
        'local left = saved.ends_at - nf.server.unix_time()\nplayer:send_message("Ends in " .. nf.time.duration(left))',
    },
    {
      name: 'parse_duration',
      doc: 'Reads a length of time written as numbers with units, as milliseconds: `"1h30m"`, `"1h 30m"`, `"90s"`, `"2d"`, `"1.5h"`, `"250ms"`. The units are `d`, `h`, `m`, `s` and `ms`, in any order, each at most once; spaces between them are fine. `nil` for text that isn\'t a duration (it may come from a player).',
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'integer?' }],
      example:
        'local cooldown = nf.time.parse_duration(event.arguments.cooldown)\nif not cooldown then\n  event.sender:send_message("<red>Try something like 10m or 1h30m")\nend',
    },
  ],
}

/** `nf.random`: seeded generators, noise and ids. */
export const nfRandom: LuaClass = {
  name: 'nf.random',
  doc: "Randomness `math.random` doesn't give: generators of your own that repeat for a seed, smooth noise, and random ids.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'new',
      impl: 'lua',
      doc: 'A random generator of its own, apart from `math.random` and every other generator. Two made with the same seed give the same numbers in the same order, on every server, so a seed can stand for a whole dungeon layout or loot roll. One the script lets go of is collected like any table.',
      params: [
        {
          name: 'seed',
          type: 'integer',
          doc: 'Leave it out for a generator seeded at random.',
          optional: true,
        },
      ],
      returns: [{ type: 'Random' }],
      example:
        'local rng = nf.random.new(42)\nlocal roll = rng:integer(1, 6)\nlocal prize = rng:weighted({ diamond = 1, iron_ingot = 5, bread = 20 })',
    },
    {
      name: 'noise2',
      doc: 'Smooth 2D noise (simplex): a number from -1 to 1 that changes gradually as `x` and `z` do, the same every time for the same arguments. For terrain heights, patches of blocks, wobble over time.',
      params: [
        { name: 'x', type: 'number', doc: '' },
        { name: 'z', type: 'number', doc: '' },
        { name: 'options', type: 'NoiseOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'number' }],
      example:
        'for x = 0, 31 do\n  for z = 0, 31 do\n    local height = 64 + math.floor(nf.random.noise2(x, z, { seed = 7, scale = 0.05 }) * 6)\n    world:block(vec3(x, height, z)):set_state("minecraft:grass_block")\n  end\nend',
    },
    {
      name: 'noise3',
      doc: 'Smooth 3D noise (simplex), like `noise2` with a third coordinate: caves, clouds, or 2D noise that drifts over time (`y` as the tick).',
      params: [
        { name: 'x', type: 'number', doc: '' },
        { name: 'y', type: 'number', doc: '' },
        { name: 'z', type: 'number', doc: '' },
        { name: 'options', type: 'NoiseOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'number' }],
    },
    {
      name: 'uuid',
      doc: 'A new random UUID, like `"3f2b8c1e-7a4d-4e0b-9c55-0e8a6f1d2b7c"`: an id no one else will make.',
      params: [],
      returns: [{ type: 'string' }],
    },
  ],
}

/** `Random`: a seeded generator, held entirely in Lua. */
export const randomClass: LuaClass = {
  name: 'Random',
  doc: 'A random generator from `nf.random.new`: numbers that repeat for its seed, apart from `math.random` and every other generator.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'integer',
      impl: 'lua',
      doc: 'A whole number from `min` to `max`, both included, each as likely as the others. `min` greater than `max` is an error.',
      params: [
        { name: 'min', type: 'integer', doc: '' },
        { name: 'max', type: 'integer', doc: '' },
      ],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'number',
      impl: 'lua',
      doc: 'A number from 0 up to (not including) 1, or from `min` up to `max`.',
      params: [
        { name: 'min', type: 'number', doc: 'Give both or neither.', optional: true },
        { name: 'max', type: 'number', doc: '', optional: true },
      ],
      returns: [{ type: 'number' }],
    },
    {
      name: 'pick',
      impl: 'lua',
      doc: 'One item of a list, each as likely as the others. `nil` for an empty list.',
      params: [{ name: 'list', type: 'table', doc: 'A list, numbered from 1.' }],
      returns: [{ type: 'any' }],
      example: 'local greeting = rng:pick({ "Hello", "Welcome back", "Hi there" })',
    },
    {
      name: 'shuffle',
      impl: 'lua',
      doc: 'Puts a list in a random order, in place.',
      params: [{ name: 'list', type: 'table', doc: 'A list, numbered from 1.' }],
      returns: [],
    },
    {
      name: 'weighted',
      impl: 'lua',
      doc: 'One key of a table, picked with odds in proportion to its number: in `{ common = 3, rare = 1 }`, `"common"` comes up three times in four. Keys with weight 0 never come up; `nil` when there are none with more. A weight that is negative or not a number is an error. The same seed picks the same keys when the keys are strings, numbers or booleans.',
      params: [
        { name: 'weights', type: 'table<any, number>', doc: 'Each choice, with its weight.' },
      ],
      returns: [{ type: 'any' }],
      example:
        'local kind = rng:weighted({ ["minecraft:diamond"] = 1, ["minecraft:iron_ingot"] = 9 })',
    },
  ],
}

/** `nf.math`: the arithmetic hand-written animations keep needing. */
export const nfMath: LuaClass = {
  name: 'nf.math',
  doc: "Small arithmetic `math` doesn't have, for animating by hand: blending, clamping, rescaling and easing.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'lerp',
      impl: 'lua',
      doc: 'The number `fraction` of the way from `from` to `to`: 0 is `from`, 1 is `to`, and outside that it carries on past either end. (`Vec3` has its own `lerp`.)',
      params: [
        { name: 'from', type: 'number', doc: '' },
        { name: 'to', type: 'number', doc: '' },
        { name: 'fraction', type: 'number', doc: '' },
      ],
      returns: [{ type: 'number' }],
    },
    {
      name: 'clamp',
      impl: 'lua',
      doc: '`value`, kept from `min` to `max`. `min` greater than `max` is an error.',
      params: [
        { name: 'value', type: 'number', doc: '' },
        { name: 'min', type: 'number', doc: '' },
        { name: 'max', type: 'number', doc: '' },
      ],
      returns: [{ type: 'number' }],
    },
    {
      name: 'remap',
      impl: 'lua',
      doc: "Moves `value` from one range to another: `from_min` becomes `to_min`, `from_max` becomes `to_max`, and everything else in proportion (beyond the ends too; clamp it if it mustn't). A range from a number to itself is an error.",
      params: [
        { name: 'value', type: 'number', doc: '' },
        { name: 'from_min', type: 'number', doc: '' },
        { name: 'from_max', type: 'number', doc: '' },
        { name: 'to_min', type: 'number', doc: '' },
        { name: 'to_max', type: 'number', doc: '' },
      ],
      returns: [{ type: 'number' }],
      example: 'local pitch = nf.math.remap(health, 0, 20, 0.5, 2)',
    },
    {
      name: 'ease',
      impl: 'lua',
      doc: 'Reshapes a fraction from 0 to 1 by one of the curves animation keyframes use: `"linear"` as it is, `"step"` 0 until 1, `"ease_in"` starting slow, `"ease_out"` ending slow, `"ease_in_out"` both. Every curve takes 0 to 0 and 1 to 1; a fraction outside 0 to 1 is clamped first.',
      params: [
        { name: 'easing', type: EASING, doc: 'Which curve.' },
        { name: 'fraction', type: 'number', doc: 'How far along, 0 to 1.' },
      ],
      returns: [{ type: 'number' }],
      example:
        'nf.task(function()\n  for tick = 0, 20 do\n    local t = nf.math.ease("ease_in_out", tick / 20)\n    node:set_translation(vec3(0, nf.math.lerp(0, 3, t), 0))\n    nf.wait(1)\n  end\nend)',
    },
  ],
}

/** The plain tables `nf.time` and `nf.random` take. */
export const utilityShapes: LuaClass[] = [
  shape(
    'TimeOptions',
    "How `nf.time.format` and `nf.time.parse` read a clock time. A key that isn't one of these is an error.",
    [{ name: 'zone', type: 'string?', doc: ZONE_DOC }],
  ),
  shape(
    'NoiseOptions',
    "Which noise `nf.random.noise2` and `noise3` give. A key that isn't one of these is an error.",
    [
      {
        name: 'seed',
        type: 'integer?',
        doc: 'Which noise: each seed is a different pattern. Default 0.',
      },
      {
        name: 'scale',
        type: 'number?',
        doc: 'What the coordinates are multiplied by first: smaller is smoother and wider (0.05 changes over about 20 blocks). Default 1.',
      },
    ],
  ),
]
