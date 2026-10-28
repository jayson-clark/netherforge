import { describe, expect, it } from 'vitest'
import { base64ToBytes } from '@/core/backend/base64'
import { TAG } from 'nbtify'
import { nbtRoot, readNbt, writeNbt } from '@netherforge/structures/nbt'
import { exampleProject } from '@/testing/fixtures'
import { nbt, structureRoot } from '@/testing/nbtFixtures'
import {
  blockCounts,
  kindOf,
  parseStructure,
  StructureError,
} from '@netherforge/structures/structure'

describe('structure files', () => {
  it('reads size, palette, blocks, entities and the data version', async () => {
    const root = structureRoot({
      size: [2, 1, 3],
      palette: [
        { Name: 'test:planks' },
        { Name: 'test:stairs', Properties: { half: 'bottom', facing: 'east' } },
      ],
      blocks: [
        { pos: [0, 0, 0], state: 0 },
        { pos: [1, 0, 0], state: 1, nbt: true },
        { pos: [1, 0, 2], state: 0 },
        // Outside the box and pointing past the palette: left out.
        { pos: [5, 0, 0], state: 0 },
        { pos: [0, 0, 1], state: 7 },
      ],
      entities: 2,
      dataVersion: 4321,
    })
    // Through a real gzip round trip, as a file would come.
    const structure = parseStructure(await readNbt(await writeNbt(root)))
    expect(structure.size).toEqual([2, 1, 3])
    expect(structure.dataVersion).toBe(4321)
    // Properties sorted, so one state is one text.
    expect(structure.palette).toEqual(['test:planks', 'test:stairs[facing=east,half=bottom]'])
    expect([...structure.blocks]).toEqual([0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 0])
    expect(structure.entities).toBe(2)
    expect(structure.blockEntities).toBe(1)
    expect(blockCounts(structure)).toEqual([
      { kind: 'test:planks', count: 2 },
      { kind: 'test:stairs', count: 1 },
    ])
    expect(kindOf('test:stairs[facing=east]')).toBe('test:stairs')
  })

  it('reads a structure a real server saved', async () => {
    // Captured over the bridge by the integration test (NETHERFORGE_IT_FIXTURES): a diamond block and
    // a gold block at opposite corners of a 2³ box, a stair, air between. 26.x writes `id` and `properties`.
    const [url] = Object.values(
      import.meta.glob<string>('../../testing/fixtures/captured.nbt', {
        query: '?inline',
        import: 'default',
        eager: true,
      }),
    )
    const bytes = base64ToBytes(url!.slice(url!.indexOf(',') + 1))
    const structure = parseStructure(await readNbt(bytes))
    expect(structure.size).toEqual([2, 2, 2])
    expect(structure.dataVersion).toBeGreaterThan(0)
    expect(structure.entities).toBe(0)
    expect(blockCounts(structure)).toEqual([
      { kind: 'minecraft:air', count: 5 },
      { kind: 'minecraft:diamond_block', count: 1 },
      { kind: 'minecraft:gold_block', count: 1 },
      { kind: 'minecraft:oak_stairs', count: 1 },
    ])
    const at = (x: number, y: number, z: number) => {
      for (let i = 0; i < structure.blocks.length; i += 4) {
        const [bx, by, bz, state] = structure.blocks.subarray(i, i + 4)
        if (bx === x && by === y && bz === z) return structure.palette[state!]
      }
      return undefined
    }
    expect(at(0, 0, 0)).toBe('minecraft:diamond_block')
    expect(at(1, 1, 1)).toBe('minecraft:gold_block')
    expect(at(1, 0, 0)).toBe(
      'minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]',
    )
  })

  it('takes the first of several palettes', () => {
    const root = structureRoot({
      size: [1, 1, 1],
      palette: [],
      blocks: [{ pos: [0, 0, 0], state: 0 }],
    })
    delete root.data.palette
    root.data.palettes = nbt.list(TAG.LIST, [
      nbt.list(TAG.COMPOUND, [nbt.compound({ Name: nbt.string('test:a') })]),
      nbt.list(TAG.COMPOUND, [nbt.compound({ Name: nbt.string('test:b') })]),
    ])
    const structure = parseStructure(root)
    expect(structure.palette).toEqual(['test:a'])
    expect(structure.otherPalettes).toBe(1)
  })

  it("says so when a file isn't a structure", () => {
    expect(() => parseStructure(nbtRoot({}))).toThrow(StructureError)
    expect(() => parseStructure(nbtRoot({ size: nbt.intList(1, 1, 1) }))).toThrow(/no palette/)
  })

  it("reads the example's tree, which the editor's seed bundles as bytes", async () => {
    const bytes = exampleProject['structures/oak_tree.nbt']
    expect(bytes).toBeInstanceOf(Uint8Array)
    const structure = parseStructure(await readNbt(bytes as Uint8Array))
    expect(structure.size).toEqual([5, 7, 5])
    expect(blockCounts(structure).map((it) => it.kind)).toEqual([
      'minecraft:oak_leaves',
      'minecraft:oak_log',
    ])
  })
})
