import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type TerrainFile } from '@/core/format'
import {
  addEntry,
  addLayer,
  deleteEntry,
  firstOre,
  freshName,
  layersAt,
  moveLayer,
  namesOf,
  newEntry,
  oreBlockKind,
  removeLayer,
  renameEntry,
  noiseEntry,
  setAreaDensity,
  setAreaOwnLayers,
  setBlock,
  setDensity,
  setIslands,
  setInArea,
  setLayer,
  setNoiseField,
  setOreBlock,
  setStone,
  blockKind,
} from './ops'

const PATH = 'terrain/hills.json'
const text = (file: TerrainFile) => canonicalizeModel('terrain', PATH, file).text ?? ''

describe('terrain ops', () => {
  it('names new entries after the ones taken', () => {
    expect(freshName([], 'ore')).toBe('ore')
    expect(freshName(['ore', 'ore_2'], 'ore')).toBe('ore_3')
  })

  it('adds, renames in place and deletes named entries, leaving no empty collection', () => {
    const file: TerrainFile = {}
    addEntry(file, 'noises', 'hills', newEntry('noises', ''))
    addEntry(file, 'noises', 'detail', newEntry('noises', ''))
    addEntry(file, 'ores', 'iron', newEntry('ores', 'minecraft:iron_ore'))
    expect(namesOf(file, 'noises')).toEqual(['hills', 'detail'])
    renameEntry(file, 'noises', 'hills', 'mountains')
    expect(namesOf(file, 'noises')).toEqual(['mountains', 'detail'])
    // A name that's taken, or the same, changes nothing.
    renameEntry(file, 'noises', 'mountains', 'detail')
    renameEntry(file, 'noises', 'mountains', 'mountains')
    expect(namesOf(file, 'noises')).toEqual(['mountains', 'detail'])
    deleteEntry(file, 'noises', 'mountains')
    deleteEntry(file, 'noises', 'detail')
    deleteEntry(file, 'ores', 'iron')
    expect(file).toEqual({})
  })

  it('writes what it edits as the file format says, a new ore with no more than it needs', () => {
    const file: TerrainFile = {}
    addEntry(file, 'ores', 'iron', newEntry('ores', 'minecraft:iron_ore'))
    addEntry(file, 'biomes', 'plains', newEntry('biomes', ''))
    addEntry(file, 'caves', 'caverns', newEntry('caves', ''))
    const written = text(file)
    expect(written).toContain('"block": "minecraft:iron_ore"')
    expect(written).toContain('"biome": "minecraft:plains"')
    expect(written).toContain('"caverns": {}')
    expect(JSON.parse(written).ores.iron).toEqual({
      block: 'minecraft:iron_ore',
      size: 8,
      veins: 8,
    })
  })

  it('keeps an ore to one block, a vanilla one or a custom one', () => {
    const ore = { block: 'minecraft:iron_ore' }
    expect(oreBlockKind(ore)).toBe('block')
    setOreBlock(ore, 'customBlock', 'ruby_ore')
    expect(ore).toEqual({ customBlock: 'ruby_ore' })
    expect(oreBlockKind(ore)).toBe('customBlock')
    setOreBlock(ore, 'block', 'minecraft:stone')
    expect(ore).toEqual({ block: 'minecraft:stone' })
    expect(firstOre(['minecraft:stone', 'minecraft:coal_ore', 'minecraft:gold_ore'], 'x')).toBe(
      'minecraft:coal_ore',
    )
    expect(firstOre(['minecraft:stone'], 'fallback')).toBe('fallback')
  })

  it("edits a list of layers, the file's or a biome area's own", () => {
    const file: TerrainFile = { biomes: { snowy: { biome: 'minecraft:snowy_plains' } } }
    addLayer(file, { key: 'layers' }, 'minecraft:grass_block')
    addLayer(file, { key: 'layers' }, 'minecraft:dirt')
    setLayer(file, { key: 'layers' }, 1, { thickness: 3 })
    moveLayer(file, { key: 'layers' }, 1, 0)
    expect(layersAt(file, { key: 'layers' })).toEqual([
      { block: 'minecraft:dirt', thickness: 3 },
      { block: 'minecraft:grass_block' },
    ])
    setLayer(file, { key: 'layers' }, 0, { thickness: undefined })
    expect(layersAt(file, { key: 'layers' })[0]).toEqual({ block: 'minecraft:dirt' })
    // An area starts its own list as a copy of what it'd use, and gives it back to use the file's again.
    setAreaOwnLayers(file, 'snowy', 'layers', true)
    addLayer(file, { area: 'snowy', key: 'layers' }, 'minecraft:snow_block')
    expect(layersAt(file, { area: 'snowy', key: 'layers' }).map((it) => it.block)).toEqual([
      'minecraft:dirt',
      'minecraft:grass_block',
      'minecraft:snow_block',
    ])
    expect(file.layers).toHaveLength(2)
    setAreaOwnLayers(file, 'snowy', 'layers', false)
    expect(file.biomes?.snowy?.layers).toBeUndefined()
    // The file's last layer going leaves no list.
    removeLayer(file, { key: 'layers' }, 0)
    removeLayer(file, { key: 'layers' }, 0)
    expect(file.layers).toBeUndefined()
  })

  it("names a block one way at a time: the game's, the project's or a structure", () => {
    const decoration: { block?: string; customBlock?: string; structure?: string } = {
      block: 'minecraft:poppy',
    }
    expect(blockKind(decoration)).toBe('block')
    setBlock(decoration, 'structure', 'oak_tree')
    expect(decoration).toEqual({ structure: 'oak_tree' })
    expect(blockKind(decoration)).toBe('structure')
    setBlock(decoration, 'customBlock', 'ruby_ore')
    expect(decoration).toEqual({ customBlock: 'ruby_ore' })
    // The stone is the default when it names no block at all, and then isn't written.
    const file: TerrainFile = {}
    setStone(file, 'customBlock', 'loam')
    expect(file.stone).toEqual({ customBlock: 'loam' })
    setStone(file, 'block', '')
    expect(file.stone).toBeUndefined()
    const layers: TerrainFile = { layers: [{ block: 'minecraft:dirt', thickness: 2 }] }
    setLayer(layers, { key: 'layers' }, 0, { block: ['customBlock', 'loam'] })
    expect(layers.layers).toEqual([{ customBlock: 'loam', thickness: 2 }])
  })

  it('keeps an ore, a cave or a decoration to biome areas, following an area renamed or deleted', () => {
    const file: TerrainFile = {
      biomes: { plains: { biome: 'minecraft:plains' }, snowy: { biome: 'minecraft:snowy_plains' } },
      ores: { ruby: { customBlock: 'ruby_ore' } },
      decorations: { flowers: { block: 'minecraft:poppy' } },
    }
    setInArea(file.ores!.ruby!, 'snowy', true)
    setInArea(file.decorations!.flowers!, 'plains', true)
    setInArea(file.decorations!.flowers!, 'snowy', true)
    setInArea(file.decorations!.flowers!, 'snowy', false)
    expect(file.decorations!.flowers!.biomes).toEqual(['plains'])
    renameEntry(file, 'biomes', 'snowy', 'tundra')
    expect(file.ores!.ruby!.biomes).toEqual(['tundra'])
    // Deleted, it's in no list: one left empty means every area again.
    deleteEntry(file, 'biomes', 'plains')
    expect(file.decorations!.flowers!.biomes).toBeUndefined()
    setInArea(file.ores!.ruby!, 'tundra', false)
    expect(file.ores!.ruby).toEqual({ customBlock: 'ruby_ore' })
  })

  it("keeps a biome area's own terrain noises by name, the area's terrain staying when they're gone", () => {
    const file: TerrainFile = { biomes: { hills: { biome: 'minecraft:plains', terrain: {} } } }
    addEntry(file, { area: 'hills' }, 'peaks', newEntry({ area: 'hills' }, ''))
    expect(namesOf(file, { area: 'hills' })).toEqual(['peaks'])
    renameEntry(file, { area: 'hills' }, 'peaks', 'crags')
    expect(Object.keys(file.biomes!.hills!.terrain!.noises!)).toEqual(['crags'])
    deleteEntry(file, { area: 'hills' }, 'crags')
    expect(file.biomes!.hills!.terrain).toEqual({})
    addEntry(file, 'decorations', 'rocks', newEntry('decorations', 'minecraft:stone'))
    expect(text(file)).toContain('"rocks": {\n      "block": "minecraft:stone"\n    }')
  })

  it('sets and clears the fields of a noise', () => {
    const noise = { frequency: 0.01, octaves: 3 }
    setNoiseField(noise, 'type', 'perlin')
    setNoiseField(noise, 'octaves', undefined)
    expect(noise).toEqual({ frequency: 0.01, type: 'perlin' })
  })

  it('makes the ground 3D and back: its noises, islands and areas, and nothing a file without one may hold', () => {
    const file: TerrainFile = {
      terrain: { base: 70 },
      biomes: {
        sky: { biome: 'minecraft:plains' },
        cliffs: { biome: 'minecraft:windswept_hills', terrain: { base: 90 } },
      },
    }
    setDensity(file, true)
    expect(file.terrain!.density).toEqual({})
    addEntry(file, 'densityNoises', 'overhangs', newEntry('densityNoises', ''))
    expect(namesOf(file, 'densityNoises')).toEqual(['overhangs'])
    expect(noiseEntry(file, 'densityNoises', 'overhangs')).toEqual({
      noise: { frequency: 0.03, octaves: 2 },
      amplitude: 8,
      squash: 2,
    })
    setIslands(file, true)
    setInArea(file.terrain!.density!.islands!, 'sky', true)
    // An area's own 3D noises, renamed and deleted as its height noises are; its density stays.
    setAreaDensity(file, 'cliffs', true)
    const cliffs = { area: 'cliffs', density: true }
    addEntry(file, cliffs, 'arches', newEntry(cliffs, ''))
    renameEntry(file, cliffs, 'arches', 'bridges')
    expect(Object.keys(file.biomes!.cliffs!.terrain!.density!.noises!)).toEqual(['bridges'])
    expect(JSON.parse(text(file)).terrain.density.islands).toEqual({ biomes: ['sky'] })
    // Renaming an area renames it in the islands' list; deleting the file's last 3D noise keeps the density.
    renameEntry(file, 'biomes', 'sky', 'heaven')
    expect(file.terrain!.density!.islands!.biomes).toEqual(['heaven'])
    deleteEntry(file, 'densityNoises', 'overhangs')
    deleteEntry(file, cliffs, 'bridges')
    expect(file.terrain!.density).toEqual({ islands: { biomes: ['heaven'] } })
    expect(file.biomes!.cliffs!.terrain).toEqual({ base: 90, density: {} })
    // Flat again: the islands and the areas' densities go with it.
    setDensity(file, false)
    expect(file.terrain).toEqual({ base: 70 })
    expect(file.biomes!.cliffs!.terrain).toEqual({ base: 90 })
  })
})
