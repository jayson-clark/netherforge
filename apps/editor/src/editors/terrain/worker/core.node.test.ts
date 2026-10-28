// @vitest-environment node
/**
 * The preview's worker running a generator's script, in Node: the worker's own bundle loads wasmoon's WebAssembly
 * by URL, which jsdom (a browser and Node at once, to wasmoon) can't fetch, so this loads Node's.
 */
import { describe, expect, it } from 'vitest'
import type { TerrainMap } from '@/core/format'
import { loadNodeLua } from '@netherforge/terrain-preview/lua.node'
import { createPreviewCore, fileKeyOf, type PreviewFile } from './core'

const FILE = JSON.stringify({
  terrain: { base: 70, seaLevel: 50 },
  layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
})

const request = (change: Partial<PreviewFile> = {}) => {
  const file: PreviewFile = {
    id: 'poles',
    text: FILE,
    structures: {},
    structuresKey: '',
    sources: {},
    sourcesKey: '',
    blocks: [],
    ...change,
  }
  return {
    fileKey: fileKeyOf(file),
    file,
    world: { seed: '7', minY: -64, maxY: 320 },
    x0: -32,
    z0: -32,
    cells: 32,
    step: 2,
  }
}

const core = () => createPreviewCore(loadNodeLua)

describe("the preview's worker and a generator's script", () => {
  it("runs the file's script with the sources it's given, again when they change", async () => {
    const scripted = JSON.stringify({
      ...JSON.parse(FILE),
      script: { blocks: ['minecraft:glowstone'] },
    })
    const lifted = (by: number) =>
      request({
        text: scripted,
        sources: {
          'terrain/poles.lua':
            'local lift = require("lift")\nreturn { height = function(x, z, h) return h + lift.by end }',
          'modules/lift/init.lua': `return { by = ${by} }`,
        },
        sourcesKey: `lift ${by}`,
      })
    const preview = core()
    const plain = (await preview.tile(request())).result as TerrainMap
    const five = (await preview.tile(lifted(5))).result as TerrainMap
    expect(five.scriptErrors).toEqual([])
    expect(five.heights[0]).toBe(plain.heights[0]! + 5)
    const nine = (await preview.tile(lifted(9))).result as TerrainMap
    expect(nine.heights[0]).toBe(plain.heights[0]! + 9)
    // A script that fails is said, and the file's own ground drawn.
    const broken = (
      await preview.tile(
        request({
          text: scripted,
          sources: { 'terrain/poles.lua': 'return {\n  height = function() error("oops") end }' },
          sourcesKey: 'broken',
        }),
      )
    ).result as TerrainMap
    expect(broken.heights).toEqual(plain.heights)
    expect(broken.scriptErrors[0]).toMatchObject({
      stage: 'height',
      file: 'terrain/poles.lua',
      line: 2,
    })
  })
})
