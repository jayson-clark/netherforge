import { describe, expect, it } from 'vitest'
import type { TerrainMap, TerrainSlice } from '@netherforge/format/types'
import { terrainPreviewer } from './previewer.ts'
import {
  areaColor,
  blockAt,
  blockColor,
  decorationColor,
  decorationsAt,
  mapPixels,
  slicePixels,
  sliceTop,
} from './draw.ts'

const FILE = JSON.stringify({
  terrain: {
    base: 70,
    seaLevel: 66,
    noises: { hills: { noise: { frequency: 0.01, octaves: 3 }, amplitude: 14 } },
  },
  layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
  ores: { ruby: { customBlock: 'ruby_ore', veins: 20, size: 8, minY: -30, maxY: 40 } },
  biomes: {
    cold: { biome: 'minecraft:snowy_plains', temperature: { max: 0 } },
    warm: { biome: 'minecraft:desert', temperature: { min: 0 } },
  },
})

const draw = (text: string) => terrainPreviewer('hills', text)

describe('the generator preview is the format generator', () => {
  it('draws a map whose heights and areas are the generator numbers, the same for a seed', () => {
    const a = draw(FILE).map('42', -64, -64, 32, 4, -64, 320) as TerrainMap
    const b = draw(FILE).map('42', -64, -64, 32, 4, -64, 320) as TerrainMap
    expect(a.type).toBe('map')
    expect(a.heights).toHaveLength(32 * 32)
    expect(a.areaNames).toEqual(['cold', 'warm'])
    expect(a.biomes).toEqual(['minecraft:snowy_plains', 'minecraft:desert'])
    expect(b.heights).toEqual(a.heights)
    expect((draw(FILE).map('43', -64, -64, 32, 4, -64, 320) as TerrainMap).heights).not.toEqual(
      a.heights,
    )
    // A bigger cell is the same ground, sampled further apart.
    const wide = draw(FILE).map('42', -64, -64, 16, 8, -64, 320) as TerrainMap
    expect(wide.heights[1 * 16 + 2]).toBe(a.heights[2 * 32 + 4])
  })

  it('cuts a slice of real chunks: ground with layers, the sea above low ground, and the ore of a custom block', () => {
    const slice = draw(FILE).slice('42', true, 5, -32, 96, -64, 320) as TerrainSlice
    expect(slice.type).toBe('slice')
    expect(slice.columns).toHaveLength(96)
    for (const runs of slice.columns) {
      expect(runs.filter((_, i) => i % 2 === 1).reduce((sum, n) => sum + n, 0)).toBe(384)
    }
    const labels = slice.palette.map((it) => it.label)
    expect(labels[0]).toBe('minecraft:air')
    expect(labels).toContain('minecraft:grass_block')
    expect(slice.palette.find((it) => it.custom)?.label).toBe('ruby_ore')
    const ruby = slice.palette.findIndex((it) => it.custom)
    expect(
      slice.columns.some((runs) => runs.some((value, i) => i % 2 === 0 && value === ruby)),
    ).toBe(true)
    // The bottom of every column is stone, whatever the seed.
    expect(labels[blockAt(slice, 10, -63)]).toBe('minecraft:stone')
    expect(blockAt(slice, 10, 400)).toBe(0)
    expect(sliceTop(slice)).toBeGreaterThan(slice.minY)
  })

  it('draws 3D ground: a slice of islands over the ground, a map of the topmost', () => {
    const islands = JSON.stringify({
      terrain: {
        base: 60,
        seaLevel: 50,
        noises: { hills: { noise: { frequency: 0.01 }, amplitude: 8 } },
        density: {
          noises: { overhangs: { noise: { frequency: 0.03 }, amplitude: 10, squash: 2 } },
          islands: { y: 150, thickness: 30, threshold: -0.2 },
        },
      },
      layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
    })
    const previewer = draw(islands)
    const map = previewer.map('42', -192, -192, 96, 4, -64, 320) as TerrainMap
    // Along the map's row with the most island in it.
    const row = [...Array(96).keys()].reduce((best, z) => {
      const count = (r: number) =>
        map.heights.slice(r * 96, r * 96 + 96).filter((h) => h > 120).length
      return count(z) > count(best) ? z : best
    }, 0)
    const slice = previewer.slice('42', true, -192 + row * 4, -192, 384, -64, 320) as TerrainSlice
    expect(map.type).toBe('map')
    expect(slice.type).toBe('slice')
    // The islands are the map's tops, and the slice has columns with air between the island and the ground.
    expect(map.heights.filter((h) => h > 120).length).toBeGreaterThan(96 * 96 * 0.2)
    const grass = slice.palette.findIndex((it) => it.label === 'minecraft:grass_block')
    const water = slice.palette.findIndex((it) => it.label === 'minecraft:water')
    const solid = (column: number, y: number) => ![0, water].includes(blockAt(slice, column, y))
    const surfaces = (column: number) => {
      let count = 0
      for (let y = slice.minY; y < slice.maxY; y += 1) {
        if (solid(column, y) && !solid(column, y + 1)) count += 1
      }
      return count
    }
    expect(slice.columns.filter((_, column) => surfaces(column) >= 2).length).toBeGreaterThan(20)
    expect(slice.columns.some((runs) => runs.some((v, i) => i % 2 === 0 && v === grass))).toBe(true)
    expect(sliceTop(slice)).toBeGreaterThan(140)
  })

  it('says why a file that cannot be generated is not drawn', () => {
    const result = draw(JSON.stringify({ terrain: { base: 99999 } })).map('1', 0, 0, 8, 4, -64, 320)
    expect(result.type).toBe('failed')
    if (result.type === 'failed') expect(result.problems[0]?.code).toBe('terrain.height')
    expect(draw('{ nope').map('1', 0, 0, 8, 4, -64, 320).type).toBe('failed')
  })
})

describe('the pictures', () => {
  const map = draw(FILE).map('7', 0, 0, 16, 4, -64, 320) as TerrainMap

  it('colours the map one RGBA pixel a cell, the sea blue where the ground is under it', () => {
    const pixels = mapPixels(map)
    expect(pixels).toHaveLength(16 * 16 * 4)
    expect(pixels.every((_, i) => (i % 4 === 3 ? pixels[i] === 255 : true))).toBe(true)
    const flooded = { ...map, heights: map.heights.map(() => map.seaLevel - 10) }
    const [r, , b] = mapPixels(flooded)
    expect(b!).toBeGreaterThan(r!)
  })

  it('marks where a decoration starts in its own colour, and says which start in a cell', () => {
    const marked: TerrainMap = {
      ...map,
      decorationNames: ['flowers', 'rocks'],
      decorations: [5, 1, 5, 0, 9, 1],
    }
    const pixels = mapPixels(marked)
    expect([...pixels.slice(9 * 4, 9 * 4 + 3)]).toEqual([...decorationColor(1)])
    expect(decorationColor(0)).not.toEqual(decorationColor(1))
    expect(decorationsAt(marked, 5)).toEqual([0, 1])
    expect(decorationsAt(marked, 6)).toEqual([])
  })

  it('gives every area and block a colour, the custom ones their own, and air none', () => {
    expect(areaColor(0)).not.toEqual(areaColor(1))
    expect(
      blockColor(
        0,
        { label: 'minecraft:air', custom: false },
        'minecraft:water',
        'minecraft:stone',
      ),
    ).toBeNull()
    const custom = blockColor(
      3,
      { label: 'ruby_ore', custom: true },
      'minecraft:water',
      'minecraft:stone',
    )
    const stone = blockColor(
      2,
      { label: 'minecraft:stone', custom: false },
      'minecraft:water',
      'minecraft:stone',
    )
    const water = blockColor(
      1,
      { label: 'minecraft:water', custom: false },
      'minecraft:water',
      'minecraft:stone',
    )
    expect(new Set([custom, stone, water].map((it) => it?.join(','))).size).toBe(3)
  })

  it('paints a slice from the bottom up, air transparent', () => {
    const slice: TerrainSlice = {
      type: 'slice',
      axis: 'x',
      from: 0,
      at: 0,
      width: 2,
      minY: 0,
      maxY: 4,
      seaLevel: 1,
      palette: [
        { label: 'minecraft:air', custom: false },
        { label: 'minecraft:stone', custom: false },
      ],
      // Column 0: two stone then air; column 1: all stone.
      columns: [
        [1, 2, 0, 2],
        [1, 4],
      ],
      scriptErrors: [],
    }
    const colors = slice.palette.map((it, i) =>
      blockColor(i, it, 'minecraft:water', 'minecraft:stone'),
    )
    const { width, height, pixels } = slicePixels(slice, colors, sliceTop(slice), 1)
    expect([width, height]).toEqual([2, 4])
    const alpha = (x: number, row: number) => pixels[(row * width + x) * 4 + 3]
    // Row 3 is the lowest block.
    expect([alpha(0, 3), alpha(0, 2), alpha(0, 1), alpha(0, 0)]).toEqual([255, 255, 0, 0])
    expect([alpha(1, 3), alpha(1, 0)]).toEqual([255, 255])
    expect(blockAt(slice, 0, 1)).toBe(1)
    expect(blockAt(slice, 0, 2)).toBe(0)
  })
})
