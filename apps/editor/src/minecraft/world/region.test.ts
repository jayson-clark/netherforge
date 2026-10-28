import { describe, expect, it } from 'vitest'
import { TAG, type CompoundTag } from 'nbtify'
import { nbtRoot, readNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { nbt } from '@/testing/nbtFixtures'
import {
  chunkRoot,
  packIndices,
  regionFile,
  sectionIndices,
  type FixtureSection,
} from '@/testing/regionFixtures'
import { bitsFor, blockIndex, ChunkError, parseChunk, unpackIndices } from './chunk'
import { lz4Block, lz4Stream, Lz4Error } from './lz4'
import {
  chunkPayload,
  COMPRESSION,
  decompressChunk,
  externalChunkFileName,
  regionFileName,
  regionOf,
  RegionError,
} from './region'

const section = (y: number, palette: string[], fill?: FixtureSection['indices']) => ({
  y,
  palette,
  indices: fill,
})

/** The first section's `block_states` in [root], to break. */
const blockStates = (root: NbtRoot) =>
  (root.data.sections as CompoundTag[])[0]!.block_states as CompoundTag

/** Reads chunk ([cx], [cz]) out of [region] the way the preview does. */
async function readChunk(region: Uint8Array, cx: number, cz: number) {
  const payload = chunkPayload(region, cx, cz)
  if (!payload) return null
  return parseChunk(await readNbt(await decompressChunk(payload.compression, payload.data), null))
}

describe('region files', () => {
  it('finds chunks by their place in the region, with every compression the game writes', async () => {
    const root = (x: number, z: number) =>
      chunkRoot({ x, z, sections: [section(0, ['test:stone'])] })
    const region = await regionFile([
      { cx: 0, cz: 0, root: root(0, 0), compression: COMPRESSION.zlib },
      { cx: 5, cz: 0, root: root(5, 0), compression: COMPRESSION.gzip },
      { cx: 31, cz: 31, root: root(31, 31), compression: COMPRESSION.none },
    ])
    for (const [cx, cz] of [
      [0, 0],
      [5, 0],
      [31, 31],
    ] as const) {
      const chunk = await readChunk(region, cx, cz)
      expect(chunk?.x).toBe(cx)
      expect(chunk?.z).toBe(cz)
      expect(chunk?.sections[0]?.palette).toEqual(['test:stone'])
    }
    // Never saved: nothing, not an error.
    expect(chunkPayload(region, 1, 0)).toBeNull()
  })

  it('places negative chunks in negative regions', async () => {
    expect(regionOf(-1)).toBe(-1)
    expect(regionOf(-32)).toBe(-1)
    expect(regionOf(-33)).toBe(-2)
    expect(regionOf(31)).toBe(0)
    expect(regionFileName(-1, -2)).toBe('r.-1.-2.mca')
    const region = await regionFile([
      { cx: -1, cz: -33, root: chunkRoot({ x: -1, z: -33, sections: [] }) },
    ])
    // -1 & 31 = 31, -33 & 31 = 31: the region's last slot.
    expect((await readChunk(region, -1, -33))?.x).toBe(-1)
    expect(chunkPayload(region, 31, 31)).not.toBeNull()
    expect(chunkPayload(region, 0, 0)).toBeNull()
  })

  it('treats an empty file as no chunks, and says what is wrong with a broken one', async () => {
    expect(chunkPayload(new Uint8Array(0), 0, 0)).toBeNull()
    expect(() => chunkPayload(new Uint8Array(100), 0, 0)).toThrow(RegionError)

    // A location past the end of the file.
    const header = new Uint8Array(8192)
    new DataView(header.buffer).setUint32(0, (40 << 8) | 1)
    expect(() => chunkPayload(header, 0, 0)).toThrow(/outside the file/)
    // A location inside the header.
    new DataView(header.buffer).setUint32(0, (1 << 8) | 1)
    expect(() => chunkPayload(header, 0, 0)).toThrow(RegionError)

    // A length past the end.
    const region = await regionFile([{ cx: 0, cz: 0, bytes: new Uint8Array(10) }])
    new DataView(region.buffer).setUint32(8192, 999_999)
    expect(() => chunkPayload(region, 0, 0)).toThrow(/runs past the end/)

    // Garbage where compressed NBT should be.
    const garbage = await regionFile([{ cx: 0, cz: 0, bytes: new Uint8Array(64).fill(7) }])
    await expect(readChunk(garbage, 0, 0)).rejects.toThrow(/its zlib data is broken/)
  })

  it('points oversized chunks at their .mcc file, and names compressions it cannot read', async () => {
    const region = await regionFile([
      { cx: 3, cz: 4, bytes: new Uint8Array(0), compression: COMPRESSION.zlib | 0x80 },
    ])
    expect(chunkPayload(region, 3, 4)).toEqual({
      compression: COMPRESSION.zlib,
      data: new Uint8Array(0),
      external: true,
    })
    expect(externalChunkFileName(-3, 4)).toBe('c.-3.4.mcc')

    const name = new TextEncoder().encode('test:zstd')
    const custom = new Uint8Array([0, name.length, ...name, 1, 2, 3])
    await expect(decompressChunk(COMPRESSION.custom, custom)).rejects.toThrow(/test:zstd/)
    await expect(decompressChunk(9, new Uint8Array(1))).rejects.toThrow(/unknown way/)
  })
})

describe('LZ4', () => {
  const ascii = (text: string) => new TextEncoder().encode(text)
  const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes)

  /** lz4-java's framing around [block] (method LZ4) and its end marker. */
  const framed = (block: Uint8Array, original: number) => {
    const header = (method: number, compressed: number, size: number) => {
      const bytes = new Uint8Array(21)
      bytes.set(ascii('LZ4Block'))
      bytes[8] = method
      const view = new DataView(bytes.buffer)
      view.setInt32(9, compressed, true)
      view.setInt32(13, size, true)
      return bytes
    }
    return new Uint8Array([
      ...header(0x20 | 6, block.length, original),
      ...block,
      ...header(0x10, 0, 0),
    ])
  }

  it('decodes literals and overlapping matches', () => {
    // "abc", then 9 bytes copied from 3 back (a run over what it writes), then "X".
    const block = new Uint8Array([0x35, ...ascii('abc'), 3, 0, 0x10, ...ascii('X')])
    expect(text(lz4Block(block, 13))).toBe('abcabcabcabcX')
    expect(text(lz4Stream(framed(block, 13)))).toBe('abcabcabcabcX')
  })

  it('reads lengths past 15 from the bytes after the token', () => {
    const literals = 'L'.repeat(20)
    // 15 + 5 literals, then a match of 4 + 15 + 255 + 30 bytes, one back.
    const block = new Uint8Array([0xff, 5, ...ascii(literals), 1, 0, 255, 30, 0x00])
    const out = lz4Block(block, 20 + 4 + 15 + 255 + 30)
    expect(text(out)).toBe('L'.repeat(out.length))
  })

  it('reads stored blocks, and rejects broken ones', () => {
    const stored = new Uint8Array([...ascii('LZ4Block'), 0x10, 2, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0])
    expect(text(lz4Stream(new Uint8Array([...stored, ...ascii('hi')])))).toBe('hi')
    expect(() => lz4Stream(ascii('LZ4Blocc and so on, long enough'))).toThrow(Lz4Error)
    // A match reaching before the start.
    expect(() => lz4Block(new Uint8Array([0x10, 65, 9, 0, 0x00]), 6)).toThrow(Lz4Error)
    // Shorter than it says.
    expect(() => lz4Block(new Uint8Array([0x20, 65, 66]), 5)).toThrow(Lz4Error)
  })

  it('is what a region file uses for compression 4', async () => {
    const raw = new Uint8Array([0x30, ...ascii('abc')])
    expect(text(await decompressChunk(COMPRESSION.lz4, framed(raw, 3)))).toBe('abc')
  })
})

describe('packed block states', () => {
  const roundTrip = (paletteSize: number, values: number[], bits = bitsFor(paletteSize)) =>
    Array.from(unpackIndices(packIndices(values, bits), paletteSize, values.length))

  it('uses at least 4 bits, and as many as the palette needs past that', () => {
    expect(bitsFor(2)).toBe(4)
    expect(bitsFor(16)).toBe(4)
    expect(bitsFor(17)).toBe(5)
    expect(bitsFor(256)).toBe(8)
    expect(bitsFor(257)).toBe(9)
  })

  it('unpacks every width without indices spanning two longs', () => {
    for (const size of [2, 16, 17, 33, 64, 65, 200, 300, 1000, 4096]) {
      const bits = bitsFor(size)
      const values = Array.from({ length: 4096 }, (_, i) => (i * 7919) % size)
      expect(roundTrip(size, values), `palette of ${size}`).toEqual(values)
      // 64 / bits whole indices per long, the rest of each long unused.
      expect(packIndices(values, bits)).toHaveLength(Math.ceil(4096 / Math.floor(64 / bits)))
    }
  })

  it('reads indices that straddle the two halves of a long', () => {
    // 6 bits: the sixth index sits at bits 30–35.
    const values = [0, 1, 2, 3, 4, 63, 62, 5, 6, 7]
    expect(roundTrip(64, values)).toEqual(values)
    // 5 bits: index 6 sits at bits 30–34.
    expect(roundTrip(32, [31, 30, 29, 28, 27, 26, 31, 1])).toEqual([31, 30, 29, 28, 27, 26, 31, 1])
  })

  it('keeps indices small: bytes for up to 256 states, else 16 bits', () => {
    expect(unpackIndices(packIndices([1, 0], 4), 2, 2)).toBeInstanceOf(Uint8Array)
    expect(unpackIndices(packIndices([300, 0], 9), 301, 2)).toBeInstanceOf(Uint16Array)
  })

  it('accepts a width the array length implies, and rejects arrays that fit nothing', () => {
    // A palette of 3 packed 5 wide (a tool that rounds up): 342 longs say 5 bits.
    const values = Array.from({ length: 4096 }, (_, i) => i % 3)
    expect(Array.from(unpackIndices(packIndices(values, 5), 3))).toEqual(values)
    // 100 longs fit no width that holds 4096 indices.
    expect(() => unpackIndices(new BigInt64Array(100), 3)).toThrow(ChunkError)
    // 4096 longs would be 64 bits an index.
    expect(() => unpackIndices(new BigInt64Array(4096), 3)).toThrow(ChunkError)
    // An index past the palette.
    expect(() => unpackIndices(packIndices([5], 4), 3, 1)).toThrow(/past|palette/)
  })
})

describe('chunks', () => {
  it('reads sections, with a palette of one as no indices at all', async () => {
    const stairs = { id: 'test:stairs', properties: { half: 'top', facing: 'east' } }
    const root = chunkRoot({
      x: 2,
      z: -1,
      sections: [
        { y: -4, palette: ['test:bedrock'] },
        {
          y: 0,
          palette: ['test:air', stairs, 'test:stone'],
          indices: sectionIndices((x, y) => (y === 0 ? 2 : x === 3 && y === 1 ? 1 : 0)),
        },
      ],
    })
    const chunk = parseChunk(root)
    expect(chunk).toMatchObject({ x: 2, z: -1, full: true })
    expect(chunk.sections).toHaveLength(2)
    expect(chunk.sections[0]).toEqual({ y: -4, palette: ['test:bedrock'], indices: null })
    const blocks = chunk.sections[1]!
    // A mixed palette: bare ids wrapped as {"": id}, states with properties sorted.
    expect(blocks.palette).toEqual(['test:air', 'test:stairs[facing=east,half=top]', 'test:stone'])
    expect(blocks.indices![blockIndex(5, 0, 9)]).toBe(2)
    expect(blocks.indices![blockIndex(3, 1, 0)]).toBe(1)
    expect(blocks.indices![blockIndex(4, 1, 0)]).toBe(0)
  })

  it('reads palettes written as plain strings, and old-style compounds', () => {
    const root = chunkRoot({ x: 0, z: 0, sections: [{ y: 1, palette: ['test:a', 'test:b'] }] })
    expect(parseChunk(root).sections[0]!.palette).toEqual(['test:a', 'test:b'])
    const states = blockStates(root)
    states.palette = nbt.list(TAG.COMPOUND, [
      nbt.compound({ Name: nbt.string('test:old') }),
      nbt.compound({ Name: nbt.string('test:b') }),
    ])
    expect(parseChunk(root).sections[0]!.palette).toEqual(['test:old', 'test:b'])
  })

  it('marks chunks the game had not finished generating', () => {
    const root = chunkRoot({ x: 0, z: 0, sections: [], status: 'minecraft:noise' })
    expect(parseChunk(root).full).toBe(false)
  })

  it('says why it cannot read a chunk', () => {
    expect(() => parseChunk(nbtRoot({ Level: nbt.compound({}) }))).toThrow(/older than 1\.18/)
    expect(() => parseChunk(nbtRoot({}))).toThrow(ChunkError)
    // Several states but no data.
    const root = chunkRoot({ x: 0, z: 0, sections: [{ y: 0, palette: ['test:a', 'test:b'] }] })
    delete blockStates(root).data
    expect(() => parseChunk(root)).toThrow(/no data/)
  })
})
