import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { Int32, TAG, TAG_TYPE, type CompoundTag, type Tag } from 'nbtify'
import { nbtRoot, readNbt, writeNbt } from './nbt.ts'
import {
  blockCounts,
  kindOf,
  parseStructure,
  stateOf,
  stateOfTag,
  StructureError,
  type Structure,
} from './structure.ts'

/**
 * A structure a real 26.x server saved (the integration test's capture scenario writes it): a diamond block and a
 * gold block at opposite corners of a 2³ box, an oak stair, air between. Read from the editor's fixtures.
 */
const CAPTURED = path.resolve(
  import.meta.dirname,
  '../../../apps/editor/src/testing/fixtures/captured.nbt',
)

/** A typed NBT list, as the game writes one (one element type per list). */
const list = (element: TAG, values: Tag[]): Tag =>
  Object.defineProperty([...values], TAG_TYPE, { value: element, enumerable: false })
const ints = (...values: number[]) =>
  list(
    TAG.INT,
    values.map((it) => new Int32(it)),
  )

/** A structure file's root with made-up blocks; [more] overrides or adds keys. */
function structure(more: CompoundTag = {}) {
  return nbtRoot({
    DataVersion: new Int32(4440),
    size: ints(2, 1, 3),
    palette: list(TAG.COMPOUND, [
      { Name: 'test:planks' },
      { Name: 'test:stairs', Properties: { half: 'bottom', facing: 'east' } },
    ]),
    blocks: list(TAG.COMPOUND, [
      { pos: ints(0, 0, 0), state: new Int32(0) },
      { pos: ints(1, 0, 0), state: new Int32(1), nbt: { id: 'test:chest' } },
      { pos: ints(1, 0, 2), state: new Int32(0) },
    ]),
    entities: list(TAG.COMPOUND, [{}, {}]),
    ...more,
  })
}

/** The palette state at a position, or undefined where no block is listed. */
function at(structure: Structure, x: number, y: number, z: number): string | undefined {
  for (let i = 0; i < structure.blocks.length; i += 4) {
    const [bx, by, bz, state] = structure.blocks.subarray(i, i + 4)
    if (bx === x && by === y && bz === z) return structure.palette[state!]
  }
  return undefined
}

describe('palette entries as block states', () => {
  it('writes properties sorted, so one state is one text, in either spelling the game used', () => {
    // Before 26.x: `Name` and `Properties`.
    expect(stateOf({ Name: 'test:stairs', Properties: { half: 'top', facing: 'east' } })).toBe(
      'test:stairs[facing=east,half=top]',
    )
    // 26.x: `id` and `properties`.
    expect(
      stateOf({ id: 'test:stairs', properties: { waterlogged: 'false', facing: 'north' } }),
    ).toBe('test:stairs[facing=north,waterlogged=false]')
    expect(stateOf({ id: 'test:planks' })).toBe('test:planks')
    expect(stateOf({ id: 'test:planks', properties: {} })).toBe('test:planks')
  })

  it("reads 26.x's bare ids, alone or wrapped in a list that mixes both", () => {
    expect(stateOfTag('test:planks')).toBe('test:planks')
    expect(stateOfTag({ '': 'test:planks' })).toBe('test:planks')
    expect(stateOfTag({ id: 'test:log', properties: { axis: 'y' } })).toBe('test:log[axis=y]')
    expect(kindOf('test:log[axis=y]')).toBe('test:log')
    expect(kindOf('test:log')).toBe('test:log')
  })
})

describe('structure files', () => {
  it('reads size, palette, the blocks as x, y, z and state, and what else it carries', async () => {
    // Through a real gzip round trip, as a file comes.
    const read = parseStructure(await readNbt(await writeNbt(structure())))
    expect(read.size).toEqual([2, 1, 3])
    expect(read.dataVersion).toBe(4440)
    expect(read.palette).toEqual(['test:planks', 'test:stairs[facing=east,half=bottom]'])
    expect([...read.blocks]).toEqual([0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 0])
    expect(read.entities).toBe(2)
    expect(read.blockEntities).toBe(1)
    expect(read.otherPalettes).toBe(0)
  })

  it('leaves out blocks outside the box, past the palette, or without a position', () => {
    const read = parseStructure(
      structure({
        blocks: list(TAG.COMPOUND, [
          { pos: ints(0, 0, 0), state: new Int32(1) },
          { pos: ints(2, 0, 0), state: new Int32(0) },
          { pos: ints(0, -1, 0), state: new Int32(0) },
          { pos: ints(0, 0, 1), state: new Int32(2) },
          { pos: ints(0, 0, 1), state: new Int32(-1) },
          { state: new Int32(0) },
          { pos: ints(1, 0, 1) },
        ]),
      }),
    )
    expect([...read.blocks]).toEqual([0, 0, 0, 1])
  })

  it('takes the first of several palettes, and counts the others', () => {
    const root = structure({
      palettes: list(TAG.LIST, [
        list(TAG.STRING, ['test:a', 'test:b']),
        list(TAG.STRING, ['test:c', 'test:d']),
        list(TAG.STRING, ['test:e', 'test:f']),
      ]),
    })
    delete root.data.palette
    const read = parseStructure(root)
    expect(read.palette).toEqual(['test:a', 'test:b'])
    expect(read.otherPalettes).toBe(2)
    expect(at(read, 1, 0, 0)).toBe('test:b')
  })

  it('has no data version when the file names none', () => {
    const root = structure()
    delete root.data.DataVersion
    expect(parseStructure(root).dataVersion).toBeNull()
  })

  it("says so when a file isn't a structure", () => {
    expect(() => parseStructure(nbtRoot({}))).toThrow(StructureError)
    expect(() => parseStructure(structure({ size: ints(1, 1) }))).toThrow(/no size/)
    expect(() => parseStructure(structure({ size: ints(1, -1, 1) }))).toThrow(/no size/)
    const noPalette = structure()
    delete noPalette.data.palette
    expect(() => parseStructure(noPalette)).toThrow(/no palette/)
  })

  it('counts blocks by kind, most first and ties by id, whatever their properties', () => {
    const read = parseStructure(
      structure({
        palette: list(TAG.STRING, ['test:b', 'test:a[x=1]', 'test:a[x=2]', 'test:c']),
        blocks: list(
          TAG.COMPOUND,
          [
            [0, 0, 0, 1],
            [1, 0, 0, 2],
            [0, 0, 1, 0],
            [1, 0, 1, 3],
          ].map(([x, y, z, state]) => ({ pos: ints(x!, y!, z!), state: new Int32(state!) })),
        ),
      }),
    )
    expect(blockCounts(read)).toEqual([
      { kind: 'test:a', count: 2 },
      { kind: 'test:b', count: 1 },
      { kind: 'test:c', count: 1 },
    ])
  })

  it('reads a structure a real server saved', async () => {
    const read = parseStructure(await readNbt(new Uint8Array(readFileSync(CAPTURED))))
    expect(read.size).toEqual([2, 2, 2])
    expect(read.dataVersion).toBeGreaterThan(0)
    expect(read.entities).toBe(0)
    expect(read.blocks.length).toBe(8 * 4)
    expect(at(read, 0, 0, 0)).toBe('minecraft:diamond_block')
    expect(at(read, 1, 1, 1)).toBe('minecraft:gold_block')
    expect(at(read, 1, 0, 0)).toBe(
      'minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]',
    )
    expect(blockCounts(read).map((it) => it.kind)).toEqual([
      'minecraft:air',
      'minecraft:diamond_block',
      'minecraft:gold_block',
      'minecraft:oak_stairs',
    ])
  })
})
