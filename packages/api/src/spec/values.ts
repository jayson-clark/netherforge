import type { Fn, TypeAlias, ValueType } from '../types.ts'

const method = (
  name: string,
  doc: string,
  params: Fn['params'],
  returns: Fn['returns'],
  example?: string,
): Fn => ({ name, impl: 'lua', doc, params, returns, ...(example ? { example } : {}) })

const number = (name: string, doc = ''): Fn['params'][number] => ({ name, type: 'number', doc })
const vector = (name: string, doc = ''): Fn['params'][number] => ({ name, type: 'Vec3', doc })

/** `Vec3`: three numbers, for every position, offset, velocity, direction and scale. */
export const vec3Value: ValueType = {
  name: 'Vec3',
  doc: 'Three numbers: a position, an offset, a velocity, a direction or a scale. Every place the API takes or gives one of those, it\'s a `Vec3`. Build one with `vec3(x, y, z)`. Vectors are immutable: methods and operators return a new vector, and assigning to `v.x` is an error. `a == b` compares the numbers exactly, and `tostring(v)` is `"vec3(1, 2, 3)"`.',
  fields: [
    { name: 'x', type: 'number', doc: 'East (+) and west (−).' },
    { name: 'y', type: 'number', doc: 'Up (+) and down (−).' },
    { name: 'z', type: 'number', doc: 'South (+) and north (−).' },
  ],
  operators: [
    { op: 'add', operand: 'Vec3', result: 'Vec3', doc: '`a + b`, component by component.' },
    { op: 'sub', operand: 'Vec3', result: 'Vec3', doc: '`a - b`, component by component.' },
    { op: 'unm', result: 'Vec3', doc: '`-a`, every component negated.' },
    { op: 'mul', operand: 'number', result: 'Vec3', doc: '`a * n` (or `n * a`): scaled.' },
    {
      op: 'mul',
      operand: 'Vec3',
      result: 'Vec3',
      doc: '`a * b`: component by component, for scales.',
    },
    { op: 'div', operand: 'number', result: 'Vec3', doc: '`a / n`: scaled down.' },
  ],
  functions: [
    method('length', 'How long it is.', [], [{ type: 'number' }]),
    method(
      'length_squared',
      'Its length squared: cheaper than `length()`, and enough for comparing distances.',
      [],
      [{ type: 'number' }],
    ),
    method(
      'normalized',
      'The same direction with length 1. The zero vector stays zero rather than becoming NaN.',
      [],
      [{ type: 'Vec3' }],
    ),
    method('dot', 'The dot product.', [vector('other')], [{ type: 'number' }]),
    method(
      'cross',
      'The cross product: a vector at right angles to both, by the right-hand rule.',
      [vector('other')],
      [{ type: 'Vec3' }],
    ),
    method('distance', 'How far it is to another point.', [vector('other')], [{ type: 'number' }]),
    method(
      'distance_squared',
      'The distance squared: cheaper than `distance()`, and enough for comparing.',
      [vector('other')],
      [{ type: 'number' }],
    ),
    method(
      'lerp',
      'The point `fraction` of the way to `other`: 0 is this vector, 1 is `other`.',
      [
        vector('other'),
        number('fraction', 'Usually 0 to 1; outside that it carries on past either end.'),
      ],
      [{ type: 'Vec3' }],
    ),
    method(
      'clamp_length',
      'The same direction, scaled down to at most `max_length` long: a copy if it is no longer than that already. Keeps a velocity under a top speed.',
      [number('max_length', 'The longest it may be, 0 or more.')],
      [{ type: 'Vec3' }],
      'local velocity = vec3(3, 0, 4):clamp_length(2.5)',
    ),
    method(
      'reflect',
      "Mirrored off a surface: `v - 2 * v:dot(n) * n` for the surface's unit normal `n`, so the part into the surface turns back out and the part along it carries on. How a shot bounces off a wall, with a sweep's `normal`.",
      [
        vector(
          'normal',
          'Which way the surface faces. Any length but zero: it is normalized first.',
        ),
      ],
      [{ type: 'Vec3' }],
      'local bounced = vec3(1, -2, 0):reflect(vec3.up)',
    ),
    method(
      'closest_on_segment',
      'The point on the line segment from `start` to `finish` nearest to this point: its foot on the segment, or the nearer end when that falls past one. `start` when the two are the same point. With `distance`, how far a point is from a beam or a path.',
      [vector('start', 'One end.'), vector('finish', 'The other end.')],
      [{ type: 'Vec3' }],
      'local nearest = player:position():closest_on_segment(vec3(0, 64, 0), vec3(10, 64, 0))',
    ),
    method(
      'flat',
      'A copy with `y` set to 0: the horizontal part.',
      [],
      [{ type: 'Vec3' }],
      'local toward = (target - player:position()):flat():normalized()',
    ),
    method('with_x', 'A copy with `x` replaced.', [number('x')], [{ type: 'Vec3' }]),
    method('with_y', 'A copy with `y` replaced.', [number('y')], [{ type: 'Vec3' }]),
    method('with_z', 'A copy with `z` replaced.', [number('z')], [{ type: 'Vec3' }]),
    method(
      'floor',
      'Each component rounded down: the block a position is in.',
      [],
      [{ type: 'Vec3' }],
    ),
    method(
      'round',
      'Each component rounded to the nearest whole number, halves up.',
      [],
      [{ type: 'Vec3' }],
    ),
    method(
      'rotated',
      'Turned about an axis through the origin, by the right-hand rule: with your right thumb along the axis, positive degrees turn the way your fingers curl. About `vec3.up`, 90 degrees turns east into north.',
      [vector('axis', 'Any length but zero.'), number('degrees')],
      [{ type: 'Vec3' }],
      'local left = forward:rotated(vec3.up, 90)',
    ),
    method(
      'unpack',
      'The three numbers, for code that wants them separately.',
      [],
      [
        { type: 'number', doc: 'x' },
        { type: 'number', doc: 'y' },
        { type: 'number', doc: 'z' },
      ],
      'local x, y, z = v:unpack()',
    ),
  ],
  library: {
    name: 'vec3',
    doc: 'Builds vectors (`vec3(x, y, z)`), and holds the common ones and `vec3.from_yaw_pitch`.',
    call: {
      name: 'vec3',
      impl: 'lua',
      doc: 'A new vector.',
      params: [number('x'), number('y'), number('z')],
      returns: [{ type: 'Vec3' }],
      example: 'local up_two = this:position() + vec3(0, 2, 0)',
    },
    fields: [
      { name: 'zero', type: 'Vec3', doc: '`vec3(0, 0, 0)`.' },
      { name: 'one', type: 'Vec3', doc: '`vec3(1, 1, 1)`.' },
      { name: 'up', type: 'Vec3', doc: '`vec3(0, 1, 0)`.' },
      { name: 'down', type: 'Vec3', doc: '`vec3(0, -1, 0)`.' },
      { name: 'north', type: 'Vec3', doc: '`vec3(0, 0, -1)`.' },
      { name: 'south', type: 'Vec3', doc: '`vec3(0, 0, 1)`.' },
      { name: 'east', type: 'Vec3', doc: '`vec3(1, 0, 0)`.' },
      { name: 'west', type: 'Vec3', doc: '`vec3(-1, 0, 0)`.' },
    ],
    functions: [
      {
        name: 'from_yaw_pitch',
        impl: 'lua',
        doc: "The unit direction a yaw and pitch face, in Minecraft's convention: yaw 0 faces south (+z), 90 west (−x), 180 north, −90 east; pitch −90 is straight up, 90 straight down.",
        params: [number('yaw', 'Degrees.'), number('pitch', 'Degrees.')],
        returns: [{ type: 'Vec3' }],
        example: 'local ahead = vec3.from_yaw_pitch(player:yaw() or 0, 0) * 2',
      },
    ],
  },
}

/** `Location`: a position in a world, with an optional facing. */
export const locationValue: ValueType = {
  name: 'Location',
  doc: 'A position in a particular world, with an optional facing. Immutable, like `Vec3`. What `location()` returns on a player or a centity; build one with `world:location(position, yaw?, pitch?)`. Methods that place things (`teleport`, `nf.centities.spawn`) take a `Location`, or a bare `Vec3` meaning "in the same world". `a == b` when every field is equal.',
  fields: [
    { name: 'world', type: 'World', doc: 'Which world.' },
    { name: 'position', type: 'Vec3', doc: 'Where in the world.' },
    {
      name: 'yaw',
      type: 'number?',
      doc: 'Which way it faces horizontally, in degrees (0 south, 90 west), or `nil` for a location with no facing.',
    },
    {
      name: 'pitch',
      type: 'number?',
      doc: 'How far up or down it faces, in degrees (−90 up, 90 down), or `nil` for a location with no facing.',
    },
  ],
  operators: [],
  functions: [
    method(
      'with_position',
      'A copy somewhere else in the same world, keeping the facing.',
      [vector('position')],
      [{ type: 'Location' }],
    ),
    method(
      'offset',
      'A copy moved by an offset, keeping the facing.',
      [vector('offset')],
      [{ type: 'Location' }],
      'player:teleport(player:location():offset(vec3(0, 10, 0)))',
    ),
    method(
      'direction',
      'The unit direction it faces (`vec3.from_yaw_pitch`, a missing pitch counting as level), or `nil` when it has no facing.',
      [],
      [{ type: 'Vec3?' }],
    ),
  ],
}

export const values: ValueType[] = [vec3Value, locationValue]

/** `Text`: MiniMessage a player reads. */
export const textAlias: TypeAlias = {
  name: 'Text',
  type: 'string',
  doc: "MiniMessage a player reads, like `\"<green>Done!\"`: a string. A `<glyph:pack/key>` tag in it names a glyph the way the script's own files would: one of its own package's resource packs bare, or `<glyph:namespace:pack/key>` for one a package it depends on exports. The tag means that glyph wherever and whenever the text is shown, whoever shows it; one naming a glyph the script can't use draws nothing, and the server log says why. Text handed back to a script names its glyphs the same way.",
}

export const aliases: TypeAlias[] = [textAlias]
