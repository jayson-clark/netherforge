import { gzipSync, deflateSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { Float32, Int16, Int32, Int8, TAG, TAG_TYPE, type Tag } from 'nbtify'
import {
  NbtError,
  nbtAt,
  nbtCompound,
  nbtList,
  nbtNumber,
  nbtRoot,
  nbtString,
  nbtVec3,
  readNbt,
  writeNbt,
} from './nbt.ts'

const utf8 = (text: string) => [...new TextEncoder().encode(text)]
/** A tag's name as NBT writes it: a big-endian u16 length, then UTF-8. */
const named = (name: string) => [0, utf8(name).length, ...utf8(name)]

/** The NBT spec's own first example: `TAG_Compound("hello world") { TAG_String("name"): "Bananrama" }`. */
const HELLO_WORLD = Uint8Array.from([
  TAG.COMPOUND,
  ...named('hello world'),
  TAG.STRING,
  ...named('name'),
  ...named('Bananrama'),
  TAG.END,
])

describe('reading NBT', () => {
  it('reads a named root compound byte by byte', async () => {
    const root = await readNbt(HELLO_WORLD)
    expect(root.rootName).toBe('hello world')
    expect(root.data).toEqual({ name: 'Bananrama' })
    expect(root.compression).toBeNull()
  })

  it('reads every tag type big-endian, keeping each tag type', async () => {
    // prettier-ignore
    const bytes = Uint8Array.from([
      TAG.COMPOUND, ...named(''),
      TAG.BYTE, ...named('b'), 0x80, // -128
      TAG.SHORT, ...named('s'), 0x01, 0x02, // 258, not 513
      TAG.INT, ...named('i'), 0x80, 0, 0, 0x01, // -2147483647
      TAG.LONG, ...named('l'), 0, 0, 0, 0x01, 0, 0, 0, 0, // 2^32
      TAG.LONG, ...named('big'), 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, // past a double's integers
      TAG.FLOAT, ...named('f'), 0xc0, 0x20, 0, 0, // -2.5
      TAG.DOUBLE, ...named('d'), 0x3f, 0xb9, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9a, // 0.1
      TAG.BYTE_ARRAY, ...named('ba'), 0, 0, 0, 2, 0x7f, 0xff,
      TAG.INT_ARRAY, ...named('ia'), 0, 0, 0, 3, 0, 0, 0, 1, 0xff, 0xff, 0xff, 0xc4, 0, 0, 0, 3, // [1, -60, 3]
      TAG.LONG_ARRAY, ...named('la'), 0, 0, 0, 1, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
      TAG.LIST, ...named('list'), TAG.SHORT, 0, 0, 0, 2, 0, 5, 0xff, 0xfb, // [5, -5]
      TAG.COMPOUND, ...named('nested'),
        TAG.COMPOUND, ...named('deeper'),
          TAG.STRING, ...named('text'), ...named('Ä☃'),
        TAG.END,
      TAG.END,
      TAG.END,
    ])
    const { data } = await readNbt(bytes)
    expect(data.b).toBeInstanceOf(Int8)
    expect(data.s).toBeInstanceOf(Int16)
    expect(data.i).toBeInstanceOf(Int32)
    expect(data.f).toBeInstanceOf(Float32)
    expect([data.b, data.s, data.i, data.f].map(nbtNumber)).toEqual([-128, 258, -2147483647, -2.5])
    expect(data.l).toBe(2n ** 32n)
    expect(nbtNumber(data.l)).toBe(2 ** 32)
    // A long a double can't hold exactly is no number at all, rather than a wrong one.
    expect(data.big).toBe(2n ** 63n - 1n)
    expect(nbtNumber(data.big)).toBeUndefined()
    expect(data.d).toBe(0.1)
    expect(data.ba).toEqual(Int8Array.from([127, -1]))
    expect(data.ia).toEqual(Int32Array.from([1, -60, 3]))
    expect(data.la).toEqual(BigInt64Array.from([-1n]))
    expect(nbtList(data.list).map(nbtNumber)).toEqual([5, -5])
    expect(nbtString(nbtAt(data, 'nested', 'deeper', 'text'))).toBe('Ä☃')
    // What was read raw is written back raw, byte for byte.
    expect(await writeNbt(await readNbt(bytes))).toEqual(bytes)
  })

  it('recognises gzip and zlib by their headers, or takes the compression it is told', async () => {
    const gzipped = new Uint8Array(gzipSync(HELLO_WORLD))
    const zlib = new Uint8Array(deflateSync(HELLO_WORLD))
    expect((await readNbt(gzipped)).compression).toBe('gzip')
    expect((await readNbt(zlib)).compression).toBe('deflate')
    for (const bytes of [gzipped, zlib, HELLO_WORLD]) {
      expect((await readNbt(bytes)).data).toEqual({ name: 'Bananrama' })
    }
    expect((await readNbt(HELLO_WORLD, null)).rootName).toBe('hello world')
    expect((await readNbt(gzipped, 'gzip')).rootName).toBe('hello world')
    await expect(readNbt(HELLO_WORLD, 'gzip')).rejects.toThrow(NbtError)
  })

  it('refuses what is not one root compound, and broken files, with an NbtError', async () => {
    const refused = (bytes: number[], compression?: null) =>
      expect(readNbt(Uint8Array.from(bytes), compression)).rejects
    await refused([TAG.LIST, ...named(''), TAG.INT, 0, 0, 0, 0], null).toThrow(
      /list, not a compound/,
    )
    await refused([...HELLO_WORLD.subarray(0, 12)], null).toThrow(NbtError)
    await refused([TAG.COMPOUND, ...named(''), 0x0e], null).toThrow(NbtError)
    await refused([0x1f, 0x8b, 1, 2, 3]).toThrow(NbtError)
    await refused([]).toThrow(NbtError)
    // A count far past the bytes there are is refused before anything that size is made.
    for (const type of [TAG.INT_ARRAY, TAG.LONG_ARRAY, TAG.BYTE_ARRAY]) {
      await refused(
        [TAG.COMPOUND, ...named(''), type, ...named('x'), 0x7f, 0xff, 0xff, 0xff],
        null,
      ).toThrow(NbtError)
    }
  })
})

describe('writing NBT', () => {
  it('writes a root gzipped by default, and as asked otherwise', async () => {
    const root = nbtRoot({ seed: -7210487422168262260n, name: 'arena' }, 'level')
    const gzipped = await writeNbt(root)
    expect([gzipped[0], gzipped[1]]).toEqual([0x1f, 0x8b])
    const back = await readNbt(gzipped)
    expect(back.rootName).toBe('level')
    expect(back.data).toEqual(root.data)

    const raw = await writeNbt(root, null)
    expect(raw[0]).toBe(TAG.COMPOUND)
    expect((await readNbt(raw)).data).toEqual(root.data)
  })

  it('keeps an empty list typed, so it writes back as it was read', async () => {
    const empty = Object.defineProperty([], TAG_TYPE, { value: TAG.INT, enumerable: false })
    const raw = await writeNbt(nbtRoot({ empty }), null)
    expect([...raw]).toEqual([
      TAG.COMPOUND,
      0,
      0,
      TAG.LIST,
      ...named('empty'),
      TAG.INT,
      0,
      0,
      0,
      0,
      TAG.END,
    ])
    expect(await writeNbt(await readNbt(raw))).toEqual(raw)
  })
})

describe('reading values out of a tree', () => {
  const data = {
    pos: Int32Array.from([1, 2, 3]),
    list: [new Int32(4), 5, new Int16(6)] as Tag[],
    short: [new Int32(1), new Int32(2)] as Tag[],
    holes: [new Int32(1), 'two', new Int32(3)] as Tag[],
    inner: { name: 'x' },
  }

  it('walks a path of compounds, and is undefined anywhere it stops', () => {
    expect(nbtAt(data, 'inner', 'name')).toBe('x')
    expect(nbtAt(data, 'inner', 'missing')).toBeUndefined()
    expect(nbtAt(data, 'pos', 'anything')).toBeUndefined()
    expect(nbtAt(undefined, 'inner')).toBeUndefined()
    expect(nbtAt(data)).toBe(data)
  })

  it('answers a tag only when it is of the kind asked', () => {
    expect(nbtCompound(data.inner)).toBe(data.inner)
    expect(nbtCompound(data.list)).toBeUndefined()
    expect(nbtCompound(undefined)).toBeUndefined()
    expect(nbtString('a')).toBe('a')
    expect(nbtString(new Int32(1))).toBeUndefined()
    expect(nbtList(data.inner)).toEqual([])
    expect(nbtNumber('1')).toBeUndefined()
  })

  it('reads three numbers from an int array or a list of numbers, and nothing else', () => {
    expect(nbtVec3(data.pos)).toEqual([1, 2, 3])
    expect(nbtVec3(data.list)).toEqual([4, 5, 6])
    expect(nbtVec3(data.short)).toBeUndefined()
    expect(nbtVec3(data.holes)).toBeUndefined()
    expect(nbtVec3(Int32Array.from([1, 2]))).toBeUndefined()
    expect(nbtVec3(undefined)).toBeUndefined()
  })
})
