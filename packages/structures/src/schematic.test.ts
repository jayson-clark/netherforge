import { describe, expect, it } from 'vitest'
import { Int16, Int32, TAG, TAG_TYPE, type CompoundTag, type Tag } from 'nbtify'
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { nbtCompound, nbtList, nbtNumber, nbtRoot, readNbt } from './nbt.ts'
import {
  buildParts,
  fitsOneFile,
  gridOf,
  gridOfFiles,
  partFile,
  partOf,
  partsOf,
  structureFile,
  unknownBlocks,
} from './parts.ts'
import { indexOf, normalizeState, readSchematic, SchematicError, type Volume } from './schematic.ts'
import { parseStructure } from './structure.ts'

const list = (element: TAG, values: Tag[]): Tag =>
  Object.defineProperty([...values], TAG_TYPE, { value: element, enumerable: false })

/** Varints, as Sponge packs its block data. */
function varints(values: number[]): Int8Array {
  const out: number[] = []
  for (let value of values) {
    while (value >= 0x80) {
      out.push((value & 0x7f) | 0x80)
      value >>>= 7
    }
    out.push(value)
  }
  return Int8Array.from(out.map((it) => (it > 127 ? it - 256 : it)))
}

/** [values] packed [bits] each into longs, low bits first, spanning longs as Litematica packs them. */
function packed(values: number[], bits: number): BigInt64Array {
  const longs = new BigUint64Array(Math.ceil((values.length * bits) / 64))
  values.forEach((value, i) => {
    for (let b = 0; b < bits; b += 1) {
      if ((value >> b) & 1) {
        const k = i * bits + b
        longs[Math.floor(k / 64)]! |= 1n << BigInt(k % 64)
      }
    }
  })
  return new BigInt64Array(longs.buffer)
}

/** A 2 × 2 × 3 box: x fastest, then z, then y. */
const SIZE = [2, 2, 3] as const
const STATES = ['test:air', 'test:stone', 'test:stairs[half=bottom,facing=east]']
/** Palette index per block: the bottom layer stone, a stair at x 1 z 2 y 0, the rest air. */
const BLOCKS = [1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0]

function sponge(version: 2 | 3): CompoundTag {
  const palette = Object.fromEntries(STATES.map((state, i) => [state, new Int32(i)]))
  const blockEntity = { Pos: Int32Array.from([1, 0, 2]), Id: 'test:chest' }
  const common = {
    Version: new Int32(version),
    DataVersion: new Int32(3955),
    Width: new Int16(2),
    Height: new Int16(2),
    Length: new Int16(3),
    Entities: list(TAG.COMPOUND, [{ Id: 'test:pig' }]),
  }
  if (version === 3) {
    return {
      Schematic: {
        ...common,
        Blocks: {
          Palette: palette,
          Data: varints(BLOCKS),
          BlockEntities: list(TAG.COMPOUND, [{ ...blockEntity, Data: { Items: 'some' } }]),
        },
      },
    }
  }
  return {
    ...common,
    PaletteMax: new Int32(3),
    Palette: palette,
    BlockData: varints(BLOCKS),
    BlockEntities: list(TAG.COMPOUND, [{ ...blockEntity, Items: 'some' }]),
  }
}

/** The same box as one Litematica region whose size reaches back along z. */
function litematic(): CompoundTag {
  const palette = [
    { Name: 'test:air' },
    { Name: 'test:stone' },
    { Name: 'test:stairs', Properties: { half: 'bottom', facing: 'east' } },
  ]
  // The region's own order is the box's, from its smallest corner.
  return {
    MinecraftDataVersion: new Int32(3955),
    Version: new Int32(6),
    Regions: {
      main: {
        Position: { x: new Int32(0), y: new Int32(0), z: new Int32(2) },
        Size: { x: new Int32(2), y: new Int32(2), z: new Int32(-3) },
        BlockStatePalette: list(TAG.COMPOUND, palette),
        BlockStates: packed(BLOCKS, 2),
        TileEntities: list(TAG.COMPOUND, [
          { x: new Int32(1), y: new Int32(0), z: new Int32(2), id: 'test:chest', Items: 'some' },
        ]),
        Entities: list(TAG.COMPOUND, [{}, {}]),
      },
    },
  }
}

function expectBox(volume: Volume) {
  expect(volume.size).toEqual([...SIZE])
  expect(volume.dataVersion).toBe(3955)
  const states = Array.from(volume.blocks, (it) => volume.palette[it])
  expect(states).toEqual(BLOCKS.map((it) => normalizeState(STATES[it]!)))
  expect(volume.palette).toContain('test:stairs[facing=east,half=bottom]')
  expect(volume.blockEntities.get(indexOf(volume.size, 1, 0, 2))).toEqual({
    id: 'test:chest',
    Items: 'some',
  })
}

describe('reading schematics', () => {
  it('reads a Sponge schematic, version 2', () => {
    const volume = readSchematic(nbtRoot(sponge(2), 'Schematic'))
    expect(volume.format).toBe('sponge')
    expectBox(volume)
    expect(volume.entities).toBe(1)
  })

  it('reads a Sponge schematic, version 3, its blocks in a compound of their own', () => {
    expectBox(readSchematic(nbtRoot(sponge(3))))
  })

  it("reads a Litematica schematic's region, packed bits and all, from its smallest corner", () => {
    const volume = readSchematic(nbtRoot(litematic()))
    expect(volume.format).toBe('litematica')
    expectBox(volume)
    expect(volume.entities).toBe(2)
  })

  it('reads entries that span two longs, and merges regions into the box around them', () => {
    // 5 bits an entry (17 states): entry 12 starts at bit 60 and runs into the next long.
    const states = Array.from({ length: 17 }, (_, i) => ({ Name: `test:block_${i}` }))
    const values = Array.from({ length: 14 }, (_, i) => i % 17)
    const region = (x: number, values: number[]) => ({
      Position: { x: new Int32(x), y: new Int32(0), z: new Int32(0) },
      Size: { x: new Int32(values.length), y: new Int32(1), z: new Int32(1) },
      BlockStatePalette: list(TAG.COMPOUND, states),
      BlockStates: packed(values, 5),
    })
    const volume = readSchematic(
      nbtRoot({ Regions: { a: region(0, values), b: region(20, [16]) } }),
    )
    expect(volume.size).toEqual([21, 1, 1])
    expect(Array.from(volume.blocks.slice(0, 14), (it) => volume.palette[it])).toEqual(
      values.map((it) => `test:block_${it}`),
    )
    // Between the regions: the first region's air (its palette's first entry).
    expect(volume.palette[volume.blocks[17]!]).toBe('test:block_0')
    expect(volume.palette[volume.blocks[20]!]).toBe('test:block_16')
  })

  it('says what it is when it is something else', () => {
    expect(() =>
      readSchematic(nbtRoot({ Blocks: Int8Array.from([1]), Materials: 'Alpha' })),
    ).toThrow(/MCEdit schematic/)
    expect(() => readSchematic(nbtRoot({ name: 'x' }))).toThrow(SchematicError)
    const big = { ...sponge(2), Width: new Int16(-1), Height: new Int16(-1), Length: new Int16(-1) }
    expect(() => readSchematic(nbtRoot(big))).toThrow(/one import holds at most/)
    const short = { ...sponge(2), BlockData: varints([1, 1]) }
    expect(() => readSchematic(nbtRoot(short))).toThrow(/holds 2 blocks, but its size is 12/)
  })
})

describe('cutting a box into structure files', () => {
  it('names parts by their place in the grid, as format does', () => {
    const side = BUILD_LIMITS.partSide
    expect(gridOf([side, 1, side + 1])).toEqual([1, 1, 2])
    expect(fitsOneFile([side, side, side])).toBe(true)
    expect(fitsOneFile([side + 1, 1, 1])).toBe(false)
    expect(partFile(2, 0, 11)).toBe('2_0_11.nbt')
    expect(partOf('2_0_11.nbt')).toEqual([2, 0, 11])
    expect(partOf('02_0_0.nbt')).toBeNull()
    expect(gridOfFiles(['0_0_0.nbt', '2_0_1.nbt', 'notes.txt'])).toEqual([3, 1, 2])
    expect(partsOf([2, 2, 1])).toEqual([
      [0, 0, 0],
      [1, 0, 0],
      [0, 1, 0],
      [1, 1, 0],
    ])
  })

  it('writes a structure file the game reads: its palette, every block bottom up, block entities, its data version', async () => {
    const volume = readSchematic(nbtRoot(sponge(2), 'Schematic'))
    const root = await readNbt(await structureFile(volume, [0, 0, 0], volume.size))
    expect(nbtNumber(root.data.DataVersion)).toBe(3955)
    expect(nbtList(root.data.palette)[1]).toEqual({
      Name: 'test:stairs',
      Properties: { facing: 'east', half: 'bottom' },
    })
    const structure = parseStructure(root)
    expect(structure.size).toEqual([2, 2, 3])
    expect(structure.blocks.length / 4).toBe(12)
    expect(structure.blockEntities).toBe(1)
    const chest = nbtList(root.data.blocks)
      .map(nbtCompound)
      .find((it) => it?.nbt)
    expect(chest?.nbt).toEqual({ id: 'test:chest', Items: 'some' })
  })

  it("cuts a big box into a build's parts, each the right piece of it", async () => {
    const side = BUILD_LIMITS.partSide
    const size: [number, number, number] = [side + 2, 1, 1]
    const blocks = new Uint16Array(side + 2)
    blocks[side + 1] = 1
    const volume: Volume = {
      format: 'sponge',
      dataVersion: null,
      size,
      palette: ['test:air', 'test:gold'],
      blocks,
      blockEntities: new Map(),
      entities: 0,
    }
    const progress: number[] = []
    const parts = []
    for await (const part of buildParts(volume, (done) => progress.push(done))) parts.push(part)
    expect(parts.map((it) => it.file)).toEqual(['0_0_0.nbt', '1_0_0.nbt'])
    expect(progress).toEqual([1, 2])
    const last = parseStructure(await readNbt(parts[1]!.bytes))
    expect(last.size).toEqual([2, 1, 1])
    expect(last.dataVersion).toBeNull()
    expect(last.palette[last.blocks[7]!]).toBe('test:gold')
    expect(parseStructure(await readNbt(parts[0]!.bytes)).palette).toEqual(['test:air'])
  })

  it("lists the blocks the game doesn't know, most first", () => {
    const volume = readSchematic(nbtRoot(sponge(3)))
    expect(unknownBlocks(volume, (id) => id === 'test:stone')).toEqual([
      { id: 'test:air', count: 6 },
      { id: 'test:stairs', count: 1 },
    ])
  })
})
