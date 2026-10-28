import { describe, expect, it } from 'vitest'
import type { TerrainMap, TerrainSlice } from '@/core/format'
import { createPreviewCore, cropMap, fileKeyOf, type PreviewFile, type PreviewWorld } from './core'

const FILE = JSON.stringify({
  terrain: { base: 70, seaLevel: 50 },
  layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
  decorations: {
    poles: { structure: 'pole', count: 4, rotate: false },
    flowers: { block: 'minecraft:poppy', count: 8 },
  },
})

/** A pole three blocks high, as the editor reads one from its `.nbt`. */
const POLE = {
  size: [1, 3, 1],
  palette: ['minecraft:oak_log[axis=y]'],
  blocks: [0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0],
}

const file = (change: Partial<PreviewFile> = {}): PreviewFile => ({
  id: 'poles',
  text: FILE,
  structures: { pole: POLE },
  structuresKey: 'structures/pole.nbt@1',
  sources: {},
  sourcesKey: '',
  blocks: [],
  ...change,
})
const WORLD: PreviewWorld = { seed: '7', minY: -64, maxY: 320 }

/** What the old single request drew: a map and a slice of a file (the file sent along, as to a fresh worker). */
async function draw(
  preview: ReturnType<typeof core>,
  f: PreviewFile = file(),
  world: PreviewWorld = WORLD,
) {
  const fileKey = fileKeyOf(f)
  const tile = await preview.tile({ fileKey, file: f, world, x0: -32, z0: -32, cells: 32, step: 2 })
  const slice = await preview.slice({ fileKey, world, alongX: true, at: 0, from: -32, width: 64 })
  return { map: tile.result, pixels: tile.pixels, slice }
}

const core = () => createPreviewCore()

describe("the preview's worker", () => {
  it('draws the map and slice of a file with its structures, from the world heights it is given', async () => {
    const answer = await draw(core())
    const map = answer.map as TerrainMap
    const slice = answer.slice as TerrainSlice
    expect(map.type).toBe('map')
    expect(map.decorationNames).toEqual(['flowers', 'poles'])
    expect(map.decorationsShown).toBe(true)
    expect(map.decorations.length).toBeGreaterThan(0)
    expect(slice.minY).toBe(-64)
    expect(slice.maxY).toBe(320)
    const labels = slice.palette.map((it) => it.label)
    expect(labels).toContain('minecraft:oak_log[axis=y]')
    // A world of other heights is drawn as it is.
    const low = (await draw(core(), file(), { ...WORLD, minY: 0, maxY: 128 })).slice as TerrainSlice
    expect([low.minY, low.maxY]).toEqual([0, 128])
  })

  it("leaves out a structure it wasn't given, as a server that can't read one does", async () => {
    const answer = await draw(core(), file({ structures: {}, structuresKey: '' }))
    const slice = answer.slice as TerrainSlice
    expect(slice.palette.map((it) => it.label)).not.toContain('minecraft:oak_log[axis=y]')
  })

  it('answers the problems of a file that cannot be drawn', async () => {
    const answer = await draw(core(), file({ text: '{ "terrain": { "blend": 999 } }' }))
    expect(answer.map.type).toBe('failed')
    expect(answer.pixels).toBeNull()
  })

  it('draws a tile as the middle of a map one cell wider, so neighbours meet without a seam', async () => {
    const preview = core()
    const f = file()
    const fileKey = fileKeyOf(f)
    const tile = await preview.tile({
      fileKey,
      file: f,
      world: WORLD,
      x0: 0,
      z0: 0,
      cells: 16,
      step: 4,
    })
    const map = tile.result as TerrainMap
    expect([map.x0, map.z0, map.cells, map.step]).toEqual([0, 0, 16, 4])
    expect(map.heights).toHaveLength(256)
    expect(tile.pixels).toHaveLength(16 * 16 * 4)
    // The same cells as a map asked for whole, by key alone now the worker has the file.
    const whole = await preview.tile({ fileKey, world: WORLD, x0: -4, z0: -4, cells: 18, step: 4 })
    const inner = cropMap(whole.result as TerrainMap, 1)
    expect(inner.heights).toEqual(map.heights)
    expect(inner.areas).toEqual(map.areas)
  })

  it('asks for the file when it was sent only its key and has another', async () => {
    const preview = core()
    await expect(
      preview.tile({ fileKey: 'never sent', world: WORLD, x0: 0, z0: 0, cells: 4, step: 1 }),
    ).rejects.toThrow(/needs the file/)
  })
})

describe('cropping a map', () => {
  it('keeps the cells inside the border, and the decorations that start there', () => {
    // 4 by 4 cells, heights 0..15, a decoration in a corner (cell 0) and one inside (cell 5).
    const map = {
      type: 'map',
      x0: 10,
      z0: 20,
      cells: 4,
      step: 2,
      heights: Array.from({ length: 16 }, (_, i) => i),
      areas: Array.from({ length: 16 }, (_, i) => i % 3),
      decorations: [0, 1, 5, 0],
    } as unknown as TerrainMap
    const inner = cropMap(map, 1)
    expect([inner.x0, inner.z0, inner.cells]).toEqual([12, 22, 2])
    expect(inner.heights).toEqual([5, 6, 9, 10])
    expect(inner.areas).toEqual([2, 0, 0, 1])
    expect(inner.decorations).toEqual([0, 0])
  })
})
