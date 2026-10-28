/**
 * A tiny, hand-written stand-in for imported client assets: enough blockstate
 * and model files, shaped like the real ones, to draw the example project's
 * blocks and to fit a stair hitbox. Used by `?assets=fixture` (Playwright);
 * never shipped as game data. Textures are made-up 16×16
 * patterns.
 */
const PLANKS =
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAALElEQVR4nGOoCDX/j4yn5bmhYELyDMPAAFI1oMsPBwMGPhYG3oCBj4UBNwAA0vQmHw7+c60AAAAASUVORK5CYII='
const BRICKS =
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAKklEQVR4nGNoaur5TwlmABEpKXlk40FiAEVhQMgGQi4cBAaMpoPRdADEAJw3T9ei9bIUAAAAAElFTkSuQmCC'

const all = (texture: string) => ({
  north: { texture, cullface: 'north' },
  south: { texture, cullface: 'south' },
  east: { texture, cullface: 'east' },
  west: { texture, cullface: 'west' },
  up: { texture, cullface: 'up' },
  down: { texture, cullface: 'down' },
})

const json: Record<string, unknown> = {
  // What the import writes for the pickers: a few item ids, enough to search (no blocks, so block fields stay free text).
  'index.json': {
    minecraft: '26.3',
    blockstates: [],
    items: [
      'minecraft:emerald',
      'minecraft:oak_stairs',
      'minecraft:paper',
      'minecraft:stick',
      'minecraft:stone_bricks',
    ],
    fonts: [],
  },
  'assets/minecraft/models/item/stick.json': {
    textures: { layer0: 'minecraft:item/stick' },
  },
  'assets/minecraft/blockstates/stone_bricks.json': {
    variants: { '': { model: 'minecraft:block/stone_bricks' } },
  },
  'assets/minecraft/blockstates/sea_lantern.json': {
    variants: { '': { model: 'minecraft:block/stone_bricks' } },
  },
  'assets/minecraft/blockstates/stone.json': {
    variants: { '': { model: 'minecraft:block/stone_bricks' } },
  },
  'assets/minecraft/models/block/cube_all.json': {
    textures: { particle: '#all' },
    elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: all('#all') }],
  },
  'assets/minecraft/models/block/stone_bricks.json': {
    parent: 'minecraft:block/cube_all',
    textures: { all: 'minecraft:block/stone_bricks' },
  },
  'assets/minecraft/blockstates/oak_stairs.json': {
    variants: {
      'facing=east,half=bottom': { model: 'minecraft:block/oak_stairs' },
      'facing=north,half=bottom': { model: 'minecraft:block/oak_stairs', y: 270 },
      'facing=south,half=bottom': { model: 'minecraft:block/oak_stairs', y: 90 },
      'facing=west,half=bottom': { model: 'minecraft:block/oak_stairs', y: 180 },
    },
  },
  'assets/minecraft/models/block/oak_stairs.json': {
    textures: { side: 'minecraft:block/oak_planks' },
    elements: [
      { from: [0, 0, 0], to: [16, 8, 16], faces: all('#side') },
      { from: [8, 8, 0], to: [16, 16, 16], faces: all('#side') },
    ],
  },
}

const PNG: Record<string, string> = {
  'assets/minecraft/textures/block/stone_bricks.png': BRICKS,
  'assets/minecraft/textures/block/oak_planks.png': PLANKS,
  'assets/minecraft/textures/item/stick.png': PLANKS,
}

/** Asset path → data URL. */
export const clientFixture: Record<string, string> = {
  ...Object.fromEntries(
    Object.entries(json).map(([path, value]) => [
      path,
      `data:application/json,${encodeURIComponent(JSON.stringify(value))}`,
    ]),
  ),
  ...Object.fromEntries(
    Object.entries(PNG).map(([path, data]) => [path, `data:image/png;base64,${data}`]),
  ),
}

/**
 * Made-up default-font advances, shaped like what the import computes
 * (`{ "<code point>": pixels }`): 6 for most printable ASCII, narrower for a
 * few thin letters and punctuation, 4 for a space.
 */
export const fixtureGlyphAdvances: Record<string, number> = (() => {
  const narrow: Record<string, number> = { i: 2, l: 3, '!': 2, '.': 2, ',': 2, "'": 2, ' ': 4 }
  const out: Record<string, number> = {}
  for (let code = 32; code < 127; code += 1) {
    out[String(code)] = narrow[String.fromCharCode(code)] ?? 6
  }
  return out
})()
