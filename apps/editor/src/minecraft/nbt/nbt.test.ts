import { describe, expect, it } from 'vitest'
import { TAG } from 'nbtify'
import { nbt } from '@/testing/nbtFixtures'
import {
  NbtError,
  nbtAt,
  nbtList,
  nbtNumber,
  nbtRoot,
  nbtString,
  nbtVec3,
  readNbt,
  writeNbt,
} from '@netherforge/structures/nbt'

/** `TAG_Compound("hello world") { TAG_String("name"): "Bananrama" }`, byte by byte, as the NBT spec's own example. */
// prettier-ignore
const HELLO_WORLD = Uint8Array.from([
  0x0a, 0x00, 0x0b, ...new TextEncoder().encode('hello world'),
  0x08, 0x00, 0x04, ...new TextEncoder().encode('name'),
  0x00, 0x09, ...new TextEncoder().encode('Bananrama'),
  0x00,
])

async function compress(bytes: Uint8Array, format: CompressionFormat): Promise<Uint8Array> {
  const stream = new CompressionStream(format)
  const writer = stream.writable.getWriter()
  void writer.write(bytes as Uint8Array<ArrayBuffer>)
  void writer.close()
  return new Uint8Array(await new Response(stream.readable).arrayBuffer())
}

describe('NBT', () => {
  it('reads a hand-written compound byte by byte', async () => {
    const root = await readNbt(HELLO_WORLD)
    expect(root.rootName).toBe('hello world')
    expect(root.data).toEqual({ name: 'Bananrama' })
  })

  it('reads every tag type big-endian, longs as bigint', async () => {
    // prettier-ignore
    const bytes = Uint8Array.from([
      0x0a, 0, 0, // root compound, no name
      0x01, 0, 1, 0x62, 0xff, // byte b = -1
      0x02, 0, 1, 0x73, 0x01, 0x02, // short s = 258
      0x03, 0, 1, 0x69, 0xff, 0xff, 0xff, 0xfe, // int i = -2
      0x04, 0, 1, 0x6c, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, // long l = max
      0x05, 0, 1, 0x66, 0x3f, 0xc0, 0, 0, // float f = 1.5
      0x06, 0, 1, 0x64, 0x40, 0x09, 0x21, 0xfb, 0x54, 0x44, 0x2d, 0x18, // double d = pi
      0x07, 0, 1, 0x41, 0, 0, 0, 2, 0x01, 0x80, // byte array A = [1, -128]
      0x09, 0, 1, 0x4c, 0x03, 0, 0, 0, 2, 0, 0, 0, 7, 0, 0, 0, 8, // list L of ints = [7, 8]
      0x0b, 0, 1, 0x49, 0, 0, 0, 1, 0x80, 0, 0, 0, // int array I = [min]
      0x0c, 0, 1, 0x4a, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 9, // long array J = [9]
      0x0a, 0, 1, 0x43, 0x00, // compound C = {}
      0x00,
    ])
    const root = await readNbt(bytes)
    const value = root.data
    expect(value.b).toEqual(nbt.byte(-1))
    expect(nbtNumber(value.b)).toBe(-1)
    expect(nbtNumber(value.s)).toBe(258)
    expect(nbtNumber(value.i)).toBe(-2)
    expect(value.l).toBe(9223372036854775807n)
    expect(nbtNumber(value.l)).toBeUndefined()
    expect(nbtNumber(value.f)).toBe(1.5)
    expect(nbtNumber(value.d)).toBeCloseTo(Math.PI, 12)
    expect(value.A).toEqual(Int8Array.from([1, -128]))
    expect(nbtList(value.L).map(nbtNumber)).toEqual([7, 8])
    expect(value.I).toEqual(Int32Array.from([-2147483648]))
    expect(value.J).toEqual(BigInt64Array.from([9n]))
    expect(value.C).toEqual({})
    // What it reads raw, it writes back raw, byte for byte.
    expect(root.compression).toBeNull()
    expect(await writeNbt(root)).toEqual(bytes)
  })

  it('reads gzipped and zlib-wrapped files, and raw ones as they are', async () => {
    expect((await readNbt(await compress(HELLO_WORLD, 'gzip'))).rootName).toBe('hello world')
    expect((await readNbt(await compress(HELLO_WORLD, 'deflate'))).rootName).toBe('hello world')
    expect((await readNbt(HELLO_WORLD)).rootName).toBe('hello world')
    // Or exactly as the caller says (a region file's chunk, already inflated).
    expect((await readNbt(HELLO_WORLD, null)).rootName).toBe('hello world')
    await expect(readNbt(HELLO_WORLD, 'gzip')).rejects.toThrow(NbtError)
  })

  it('writes gzipped NBT the game reads back the same', async () => {
    const root = nbtRoot({
      Data: nbt.compound({
        LevelName: nbt.string('Ärena ☃ 𝄞\u0000'),
        seed: nbt.long(-7210487422168262260n),
        spawn: nbt.compound({ pos: nbt.ints(1, -60, 3) }),
        empty: nbt.list(TAG.END, []),
      }),
    })
    const written = await writeNbt(root)
    expect([written[0], written[1]]).toEqual([0x1f, 0x8b])
    const back = await readNbt(written)
    expect(back.data).toEqual(root.data)
    expect(back.compression).toBe('gzip')
    expect(nbtString(nbtAt(back.data, 'Data', 'LevelName'))).toBe('Ärena ☃ 𝄞\u0000')
    expect(nbtVec3(nbtAt(back.data, 'Data', 'spawn', 'pos'))).toEqual([1, -60, 3])
    // An empty list keeps its element type: the game reads `end` lists as empty of anything.
    expect(await writeNbt(back)).toEqual(written)
  })

  it('refuses broken files with a sentence, never a crash or a huge allocation', async () => {
    const refused = (bytes: number[]) => expect(readNbt(Uint8Array.from(bytes), null)).rejects
    await refused([...HELLO_WORLD.subarray(0, 20)]).toThrow(NbtError)
    await refused([0x08, 0, 0, 0, 0]).toThrow(NbtError)
    await refused([0x09, 0, 0, 0x01, 0, 0, 0, 0]).toThrow(/root tag is a list/)
    await refused([0x0a, 0, 0, 0x0e]).toThrow(/unsupported tag type '14'/)
    // A list, and int and long arrays, claiming millions of elements in a ten-byte file
    // (the arrays are the patch to nbtify: it allocated them before looking).
    for (const [type, length] of [
      [0x09, [0x03, 0x7f, 0xff, 0xff, 0xff]],
      [0x0b, [0x10, 0, 0, 0]],
      [0x0c, [0x08, 0, 0, 0]],
    ] as const) {
      await refused([0x0a, 0, 0, type, 0, 1, 0x4c, ...length]).toThrow(/Ran out of bytes/)
    }
    // Nested deeper than the stack: refused, not a crash.
    await refused([
      0x0a,
      0,
      0,
      ...Array.from({ length: 100_000 }, () => [0x0a, 0, 0]).flat(),
    ]).toThrow(NbtError)
    await expect(readNbt(Uint8Array.from([0x1f, 0x8b, 1, 2, 3]))).rejects.toThrow(NbtError)
  })
})
