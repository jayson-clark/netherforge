import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { decode } from 'fast-png'
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { WORLD_HEIGHT_OVERWORLD } from '@netherforge/format/constants'
import type { TerrainMap } from '@netherforge/format/types'
import { mapPixels, structureInput, terrainPreviewer } from '@netherforge/terrain-preview'
import { readNbt } from '@netherforge/structures/nbt'
import { parseStructure } from '@netherforge/structures/structure'
import { run } from './main.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/showcase')

/** Everything this file writes, removed after its last test. */
let root: string
/** A copy of examples/showcase (and the library beside it) that no test changes, made once for the file. */
let shared: string
/** The project the current test runs on: [shared], unless it asked for a copy of its own ([ownCopy]). */
let project: string
let out: string
let made = 0

/** examples/showcase, with examples/library beside it where its netherforge.json finds it, in a new folder of [root]. */
function copyExample(): string {
  const dir = path.join(root, `copy-${(made += 1)}`)
  cpSync(example, path.join(dir, 'showcase'), {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  cpSync(path.resolve(example, '../library'), path.join(dir, 'library'), { recursive: true })
  return path.join(dir, 'showcase')
}

/** For a test that changes the project's files: a copy of its own. */
const ownCopy = () => {
  project = copyExample()
}

beforeAll(() => {
  root = mkdtempSync(path.join(os.tmpdir(), 'netherforge-preview-'))
  shared = copyExample()
})
afterAll(() => rmSync(root, { recursive: true, force: true }))
beforeEach(() => {
  project = shared
  out = path.join(root, `picture-${(made += 1)}.png`)
})

const file = () => path.join(project, 'terrain/ruby_hills.json')
const picture = () => {
  const png = decode(readFileSync(out))
  return { width: png.width, height: png.height, data: png.data as Uint8Array }
}
/** The RGBA of pixel ([x], [y]) of [png]. */
const pixel = (png: ReturnType<typeof picture>, x: number, y: number) => [
  ...png.data.slice((y * png.width + x) * 4, (y * png.width + x) * 4 + 4),
]
/** The y a slice's picture says it reaches up to, from the line `netherforge preview` prints about it. */
const sliceTopOf = (line: string) => Number(/to (-?\d+);/.exec(line)?.[1])

/** The structures the example's generator places, read as the editor reads them. */
async function oakTree() {
  const bytes = readFileSync(path.join(project, 'structures/oak_tree.nbt'))
  return { oak_tree: structureInput(parseStructure(await readNbt(bytes))) }
}

describe('preview: map', () => {
  // The one plumbing check: the command hands the library what it was asked and writes what it draws.
  // What the pictures should look like is checked independently below ("preview: what it draws").
  it('writes the map the format preview draws for the seed, pixel for pixel', async () => {
    const outcome = await run(
      ['preview', file(), '--seed', '42', '--size', '24', '--scale', '1', '--out', out],
      {},
    )
    expect(outcome.code).toBe(0)
    expect(outcome.out[0]).toBe(`${out}: 24x24`)
    const expected = terrainPreviewer(
      'ruby_hills',
      readFileSync(file(), 'utf8'),
      await oakTree(),
    ).map(
      '42',
      -48,
      -48,
      24,
      4,
      WORLD_HEIGHT_OVERWORLD.minY,
      WORLD_HEIGHT_OVERWORLD.maxY,
    ) as TerrainMap
    expect(expected.type).toBe('map')
    const png = picture()
    expect([png.width, png.height]).toEqual([24, 24])
    expect([...png.data]).toEqual([...mapPixels(expected)])
  })

  it('names the areas it draws, and a different seed is a different world', async () => {
    const first = await run(['preview', file(), '--seed', '1', '--out', out], {})
    expect(first.out.some((it) => / area \w+ \(minecraft:/.test(it))).toBe(true)
    const a = picture()
    // The default scale draws each cell as 4 pixels.
    expect([a.width, a.height]).toEqual([96 * 4, 96 * 4])
    await run(['preview', file(), '--seed', '2', '--out', out], {})
    expect([...picture().data]).not.toEqual([...a.data])
  })

  it('centres the map where asked, and enlarges each cell by the scale', async () => {
    const at = ['--x', '1000', '--z', '-500', '--size', '8']
    await run(['preview', file(), ...at, '--scale', '1', '--out', out], {})
    const small = picture()
    await run(['preview', file(), ...at, '--scale', '3', '--out', out], {})
    const big = picture()
    expect([big.width, big.height]).toEqual([24, 24])
    // Cell (2, 5) is the 3x3 block at (6, 15).
    for (const [dx, dy] of [
      [0, 0],
      [2, 2],
      [1, 2],
    ] as const) {
      expect(pixel(big, 6 + dx, 15 + dy)).toEqual(pixel(small, 2, 5))
    }
  })
})

describe('preview: slice', () => {
  it('cuts the ground through a line, each block scale pixels, and names the blocks it drew', async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'x',
        '--at',
        '7',
        '--x',
        '10',
        '--width',
        '40',
        '--seed',
        '42',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const png = picture()
    // 40 columns of 3 pixels; from the overworld's bottom up to the top the command printed, 3 pixels a block.
    expect(png.width).toBe(40 * 3)
    expect(png.height).toBe((sliceTopOf(outcome.out[1]!) + 1 - WORLD_HEIGHT_OVERWORLD.minY) * 3)
    expect(outcome.out.some((it) => it.includes('minecraft:grass_block'))).toBe(true)
  })

  it('cuts along z too, and the heights are the options', async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'z',
        '--width',
        '16',
        '--scale',
        '1',
        '--min-y',
        '0',
        '--max-y',
        '128',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const png = picture()
    expect(png.width).toBe(16)
    // From y 0 up to just above the highest block or the sea: never taller than the world.
    expect(png.height).toBeLessThanOrEqual(128)
    expect(png.height).toBeGreaterThan(62)
  })

  it('draws 3D ground: islands floating over the ground, with sky under them', async () => {
    ownCopy()
    // Format's fixture of 3D terrain: ground leaning into overhangs, arches in one area, islands over the other.
    const fixture = path.resolve(
      import.meta.dirname,
      '../../../packages/format/testdata/terrain/density.json',
    )
    const islands = path.join(project, 'terrain/islands.json')
    writeFileSync(islands, readFileSync(fixture, 'utf8'))
    const outcome = await run(
      [
        'preview',
        islands,
        '--slice',
        'x',
        '--at',
        '-96',
        '--x',
        '0',
        '--width',
        '96',
        '--seed',
        '7',
        '--scale',
        '1',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    // The islands float at 150: the picture reaches up to them, and a column has sky under its island.
    const top = sliceTopOf(outcome.out[1]!)
    expect(top).toBeGreaterThan(140)
    const png = picture()
    expect(png.height).toBe(top + 1 - WORLD_HEIGHT_OVERWORLD.minY)
    const row = (y: number) => top - y
    const alpha = (x: number, y: number) => pixel(png, x, row(y))[3]
    const columns = Array.from({ length: png.width }, (_, x) => x)
    expect(columns.some((x) => alpha(x, 150) === 255 && alpha(x, 120) === 0)).toBe(true)
  })

  it('draws up to --top, the empty sky too, and refuses one outside the world', async () => {
    const args = ['preview', file(), '--slice', 'x', '--width', '8', '--scale', '1', '--out', out]
    const outcome = await run([...args, '--top', '319'], {})
    expect(outcome.code).toBe(0)
    expect(sliceTopOf(outcome.out[1]!)).toBe(319)
    expect(picture().height).toBe(320 - WORLD_HEIGHT_OVERWORLD.minY)
    // Nothing up there but air: the top row is transparent.
    expect(pixel(picture(), 0, 0)[3]).toBe(0)
    const outside = await run([...args, '--top', '320'], {})
    expect(outside.code).toBe(2)
    expect(outside.err.join('\n')).toContain('--top')
  })

  it('draws slices wider than 512 columns', async () => {
    const outcome = await run(
      ['preview', file(), '--slice', 'z', '--width', '600', '--scale', '1', '--out', out],
      {},
    )
    expect(outcome.code).toBe(0)
    expect(picture().width).toBe(600)
  })

  it("draws a world of a project dimension type's heights", async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'x',
        '--width',
        '8',
        '--scale',
        '1',
        '--dimension-type',
        'deep',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    // examples/showcase's `deep`: from y -128, 448 tall.
    expect(outcome.out[1]).toMatch(/; y -128 to \d+;/)
    const json = JSON.parse(readFileSync(path.join(project, 'dimension_types/deep.json'), 'utf8'))
    expect(json).toMatchObject({ minY: -128, height: 448 })
    expect(picture().height).toBeGreaterThan(128 + 62)
  })
})

/**
 * What the pictures look like, worked out by hand from the drawing rules (packages/terrain-preview's draw.ts) for
 * terrain so plain every column is the same, never through the code that draws them: no noises, so a column's top
 * block is `base`; no areas, so every column is area 0; no caves, ores, floor or decorations.
 */
describe('preview: what it draws', () => {
  let flat: string
  const write = (id: string, json: unknown) => {
    const at = path.join(flat, 'terrain', `${id}.json`)
    writeFileSync(at, JSON.stringify(json))
    return at
  }

  beforeAll(() => {
    flat = path.join(root, 'flat')
    mkdirSync(path.join(flat, 'terrain'), { recursive: true })
    writeFileSync(
      path.join(flat, 'netherforge.json'),
      JSON.stringify({
        formatVersion: 1,
        name: 'Flat',
        namespace: 'flat',
        version: '1.0.0',
        minecraft: '26.3',
      }),
    )
  })

  // Ground at 60, sea up to 64, all of it stone: only colours draw.ts gives fixed values (the sea's, the stone's).
  const sunken = () =>
    write('sunken', {
      terrain: { base: 60, seaLevel: 64 },
      layers: [{ block: 'minecraft:stone' }],
    })

  it('a slice: the sea translucent blue over the ground, the stone grey, the sky empty', async () => {
    const outcome = await run(
      [
        'preview',
        sunken(),
        '--slice',
        'x',
        '--width',
        '4',
        '--scale',
        '1',
        '--min-y',
        '48',
        '--max-y',
        '96',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const png = picture()
    // The picture stops one block over the higher of the ground (60) and the sea (64): y 48 to 64, 17 rows; row 0 is y 64.
    expect([png.width, png.height]).toEqual([4, 17])
    const at = (y: number) => pixel(png, 2, 64 - y)
    const sea = [48, 96, 184, 150] // SEA, drawn at alpha 150 so the ground under it shows
    const stone = [126, 126, 130, 255] // STONE, the file's `stone` (the default, minecraft:stone)
    // Water fills every block above the ground up to and including the sea level.
    for (const y of [64, 63, 62, 61]) expect(at(y)).toEqual(sea)
    // The top block (60) is the one layer, which is stone too, and so is everything under it.
    for (const y of [60, 59, 52, 48]) expect(at(y)).toEqual(stone)
    // Every column is the same.
    expect(pixel(png, 0, 64 - 60)).toEqual(stone)
    expect(pixel(png, 3, 0)).toEqual(sea)
  })

  it('a map: a flooded cell is the sea, darker for the depth', async () => {
    expect(
      (await run(['preview', sunken(), '--size', '4', '--scale', '1', '--out', out], {})).code,
    ).toBe(0)
    const png = picture()
    expect([png.width, png.height]).toEqual([4, 4])
    // 4 blocks under the sea: SEA times 1.1 - min(1, 4 / 40) * 0.5 = 1.05, rounded: 48 * 1.05 = 50.4, 96 * 1.05 =
    // 100.8, 184 * 1.05 = 193.2.
    for (const [x, y] of [
      [0, 0],
      [3, 3],
      [1, 2],
    ] as const)
      expect(pixel(png, x, y)).toEqual([50, 101, 193, 255])
  })

  it("a map: dry ground is its area's colour, brighter the higher it is", async () => {
    const dry = write('dry', {
      terrain: { base: 70, seaLevel: 62 },
      layers: [{ block: 'minecraft:stone' }],
    })
    expect(
      (await run(['preview', dry, '--size', '4', '--scale', '1', '--out', out], {})).code,
    ).toBe(0)
    // Area 0's colour is hsl(38°, 50%, 50%) = (191.25, 144.5, 63.75). Flat ground has no slope, and is 8 blocks over
    // the sea: lit by 0.85 + 8 / 120 * 0.25 = 0.8667, which makes (165.8, 125.2, 55.3) from the rounded channels. Within
    // one of that, for where each step rounds.
    const [r, g, b, a] = pixel(picture(), 1, 1)
    expect(Math.abs(r! - 165.8)).toBeLessThanOrEqual(1)
    expect(Math.abs(g! - 125.2)).toBeLessThanOrEqual(1)
    expect(Math.abs(b! - 55.3)).toBeLessThanOrEqual(1)
    expect(a).toBe(255)
  })
})

describe('preview: errors', () => {
  it('prints a generator with problems as check does and fails', async () => {
    ownCopy()
    const json = JSON.parse(readFileSync(file(), 'utf8'))
    json.terrain.bogus = 1
    writeFileSync(file(), JSON.stringify(json))
    const outcome = await run(['preview', file(), '--out', out], {})
    expect(outcome.code).toBe(1)
    expect(outcome.out[0]).toMatch(
      /^terrain\/ruby_hills\.json:\d+(:\d+)?: error.*Unknown key "bogus".*\(at \$\.terrain\)$/,
    )
    expect(outcome.out.at(-1)).toMatch(/^1 error, 0 warnings\.$/)
  })

  it('says what is wrong with the command line, and exits 2', async () => {
    const cases: [string[], RegExp][] = [
      [['preview', file()], /--out/],
      [['preview', path.join(project, 'netherforge.json'), '--out', out], /isn't a terrain/],
      [['preview', file(), '--out', out, '--seed', 'abc'], /--seed is a whole number/],
      [['preview', file(), '--out', out, '--slice', 'y'], /--slice is x or z/],
      [['preview', file(), '--out', out, '--size', '0'], /--size is a whole number from 1/],
      [['preview', file(), '--out', out, '--x', '1.5'], /--x is a whole number/],
      [['preview', file(), '--out', out, '--min-y', '10', '--max-y', '5'], /--min-y must be below/],
      [
        ['preview', file(), '--out', out, '--dimension-type', 'nope'],
        /there's no dimension_types\/nope\.json/,
      ],
      [['preview', file(), '--out', out, '--dimension-type', 'other:deep'], /the project's own/],
      [
        ['preview', file(), '--out', out, '--dimension-type', 'deep', '--min-y', '0'],
        /--dimension-type or --min-y/,
      ],
    ]
    for (const [args, message] of cases) {
      const outcome = await run(args, {})
      expect({ args, code: outcome.code }).toEqual({ args, code: 2 })
      expect(outcome.err.join('\n')).toMatch(message)
    }
  })
})

describe("preview: a generator's script", () => {
  const scripted = () => {
    ownCopy()
    const json = JSON.parse(readFileSync(file(), 'utf8')) as Record<string, unknown>
    json.script = { blocks: ['minecraft:glowstone'] }
    writeFileSync(file(), JSON.stringify(json))
  }

  it("runs the file's script and the modules it requires, as the server does", async () => {
    scripted()
    mkdirSync(path.join(project, 'modules/lift'), { recursive: true })
    writeFileSync(path.join(project, 'modules/lift/init.lua'), 'return { by = 20 }')
    writeFileSync(
      path.join(project, 'terrain/ruby_hills.lua'),
      'local lift = require("lift")\nreturn { height = function(x, z, h) return h + lift.by end }',
    )
    const before = await run(['preview', file(), '--seed', '3', '--slice', 'x', '--out', out], {})
    expect(before.code).toBe(0)
    expect(before.out.some((it) => it.includes('terrain.script-failed'))).toBe(false)
    const raised = before.out[1]!
    writeFileSync(path.join(project, 'terrain/ruby_hills.lua'), 'return {}')
    const flat = await run(['preview', file(), '--seed', '3', '--slice', 'x', '--out', out], {})
    // The slice's top is 20 blocks lower without the script's height stage.
    expect(sliceTopOf(raised) - sliceTopOf(flat.out[1]!)).toBeGreaterThanOrEqual(19)
  })

  it('prints what the script failed at, and still draws the file', async () => {
    scripted()
    writeFileSync(
      path.join(project, 'terrain/ruby_hills.lua'),
      'return {\n  terrain = function() while true do end end,\n}',
    )
    const outcome = await run(
      ['preview', file(), '--slice', 'z', '--width', '16', '--out', out],
      {},
    )
    expect(outcome.code).toBe(0)
    expect(outcome.out.filter((it) => it.includes('terrain.script-failed'))).toEqual([
      "terrain/ruby_hills.lua:2: warning [terrain.script-failed]: its terrain stage failed, so the file's own result is drawn there: terrain/ruby_hills.lua:2: ran past its budget of 1000000 instructions",
    ])
  })
})
