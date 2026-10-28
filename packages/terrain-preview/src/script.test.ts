import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import type { TerrainMap, TerrainSlice } from '@netherforge/format/types'
import { loadNodeLua } from './lua.node.ts'
import { hasScript, scriptPaths, terrainPreviewer } from './previewer.ts'

/** Format's fixture of a generator with a script (the one its JVM and JS goldens are made from). */
const FIXTURE = path.resolve(import.meta.dirname, '../../format/testdata/terrain/script')

function filesUnder(root: string, at = ''): string[] {
  return readdirSync(path.join(root, at)).flatMap((name) => {
    const rel = at ? `${at}/${name}` : name
    return statSync(path.join(root, rel)).isDirectory() ? filesUnder(root, rel) : [rel]
  })
}

const text = readFileSync(path.join(FIXTURE, 'terrain/ridges.json'), 'utf8')
const files = filesUnder(FIXTURE)
const sources = Object.fromEntries(
  scriptPaths('ridges', files).map((it) => [it, readFileSync(path.join(FIXTURE, it), 'utf8')]),
)

beforeAll(() => loadNodeLua())

describe("a generator's script in the preview", () => {
  it('runs the script the file hands its stages to, with the modules it requires', () => {
    expect(Object.keys(sources)).toEqual([
      'modules/rocks/init.lua',
      'modules/rocks/shapes.lua',
      'terrain/ridges.lua',
    ])
    expect(hasScript(text)).toBe(true)
    const scripted = terrainPreviewer('ridges', text, {}, sources)
    const map = scripted.map('7', -64, -64, 24, 4, -64, 320) as TerrainMap
    expect(map.type).toBe('map')
    expect(map.scriptErrors).toEqual([])
    // The same file without its script: the ridges are the script's.
    const plain = JSON.stringify({ ...JSON.parse(text), script: undefined })
    expect(hasScript(plain)).toBe(false)
    const flat = terrainPreviewer('ridges', plain).map('7', -64, -64, 24, 4, -64, 320) as TerrainMap
    expect(map.heights.some((h, i) => h > flat.heights[i]! + 3)).toBe(true)
    // A slice has the blocks only its script places.
    const slice = scripted.slice('7', true, 0, -64, 128, -64, 320) as TerrainSlice
    expect(slice.palette.map((it) => it.label)).toContain('minecraft:glowstone')
    const glowstone = slice.palette.findIndex((it) => it.label === 'minecraft:glowstone')
    expect(slice.columns.some((runs) => runs.some((v, i) => i % 2 === 0 && v === glowstone))).toBe(
      true,
    )
    scripted.close()
  })

  it("says what the script failed at, and draws the file's own ground there", () => {
    const broken = {
      ...sources,
      'terrain/ridges.lua': 'return { height = function() error("no") end }',
    }
    const map = terrainPreviewer('ridges', text, {}, broken).map(
      '7',
      0,
      0,
      8,
      4,
      -64,
      320,
    ) as TerrainMap
    expect(map.type).toBe('map')
    expect(map.scriptErrors).toEqual([
      {
        stage: 'height',
        message: 'terrain/ridges.lua:1: no',
        file: 'terrain/ridges.lua',
        line: 1,
      },
    ])
    // A script that doesn't load says so in every picture drawn with it, not only the first.
    const missing = terrainPreviewer('ridges', text)
    for (const drawn of [
      missing.map('7', 0, 0, 8, 4, -64, 320),
      missing.slice('7', true, 0, 0, 16, -64, 320),
      missing.map('7', 64, 0, 8, 4, -64, 320),
    ]) {
      expect((drawn as TerrainMap | TerrainSlice).scriptErrors.map((it) => it.stage)).toEqual([
        'load',
      ])
    }
  })
})
