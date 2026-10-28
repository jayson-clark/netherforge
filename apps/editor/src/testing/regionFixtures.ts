/**
 * Region files and chunks built in tests, shaped like the ones a 26.x server
 * saves (made-up block ids, no game data): for vitest and for Playwright to
 * drop into a memory project as a map's region files.
 */
import { TAG, type Tag } from 'nbtify'
import { nbtRoot, writeNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { nbt } from './nbtFixtures'

/** A palette entry: a bare id, or an id with properties. */
export type FixtureState = string | { id: string; properties: Record<string, string> }

export interface FixtureSection {
  y: number
  palette: FixtureState[]
  /** The palette index of each block, `(y * 16 + z) * 16 + x` (4096 of them); all 0 when left out. */
  indices?: ArrayLike<number>
  /** Index width to pack with; the game's rule when left out. */
  bits?: number
}

/** ResourcePacks [indices] [bits] wide, low bits first, none spanning two longs. */
export function packIndices(indices: ArrayLike<number>, bits: number): BigInt64Array {
  const perLong = Math.floor(64 / bits)
  const longs = new BigInt64Array(Math.ceil(indices.length / perLong))
  for (let i = 0; i < indices.length; i += 1) {
    const at = Math.floor(i / perLong)
    longs[at] = BigInt.asIntN(
      64,
      longs[at]! | (BigInt(indices[i]!) << BigInt((i % perLong) * bits)),
    )
  }
  return longs
}

/** A palette list as 26.x writes it: plain strings when no state has properties, else compounds with the bare ids wrapped. */
function paletteTag(palette: FixtureState[]): Tag {
  if (palette.every((it) => typeof it === 'string')) {
    return nbt.list(
      TAG.STRING,
      palette.map((it) => nbt.string(it)),
    )
  }
  return nbt.list(
    TAG.COMPOUND,
    palette.map((entry) =>
      typeof entry === 'string'
        ? nbt.compound({ '': nbt.string(entry) })
        : nbt.compound({
            id: nbt.string(entry.id),
            properties: nbt.compound(
              Object.fromEntries(
                Object.entries(entry.properties).map(([key, value]) => [key, nbt.string(value)]),
              ),
            ),
          }),
    ),
  )
}

/** A chunk's NBT root, as a region file holds it. */
export function chunkRoot(options: {
  x: number
  z: number
  sections: FixtureSection[]
  status?: string
}): NbtRoot {
  return nbtRoot({
    DataVersion: nbt.int(5023),
    xPos: nbt.int(options.x),
    yPos: nbt.int(-4),
    zPos: nbt.int(options.z),
    Status: nbt.string(options.status ?? 'minecraft:full'),
    sections: nbt.list(
      TAG.COMPOUND,
      options.sections.map((section) => {
        const bits = section.bits ?? Math.max(4, Math.ceil(Math.log2(section.palette.length)))
        return nbt.compound({
          Y: nbt.byte(section.y),
          block_states: nbt.compound({
            palette: paletteTag(section.palette),
            ...(section.palette.length > 1
              ? {
                  data: packIndices(section.indices ?? new Uint8Array(4096), bits),
                }
              : {}),
          }),
          biomes: nbt.compound({ palette: nbt.list(TAG.STRING, [nbt.string('test:plains')]) }),
        })
      }),
    ),
  })
}

/** Indices for a section where [fill] says each block's palette index. */
export function sectionIndices(fill: (x: number, y: number, z: number) => number): Uint16Array {
  const out = new Uint16Array(4096)
  for (let y = 0; y < 16; y += 1)
    for (let z = 0; z < 16; z += 1)
      for (let x = 0; x < 16; x += 1) out[(y * 16 + z) * 16 + x] = fill(x, y, z)
  return out
}

export interface FixtureChunk {
  /** Absolute chunk coordinates. */
  cx: number
  cz: number
  /** Its NBT, or raw stored bytes (already compressed, or garbage on purpose). */
  root?: NbtRoot
  bytes?: Uint8Array
  /** The compression byte; zlib, as the game's default, when left out. */
  compression?: number
}

/** A region file holding [chunks] (all in the same region). */
export async function regionFile(chunks: FixtureChunk[]): Promise<Uint8Array> {
  const stored: { index: number; payload: Uint8Array; compression: number }[] = []
  for (const chunk of chunks) {
    const compression = chunk.compression ?? 2
    let payload = chunk.bytes
    if (!payload) {
      payload = await writeNbt(
        chunk.root!,
        compression === 1 ? 'gzip' : compression === 2 ? 'deflate' : null,
      )
    }
    stored.push({ index: (chunk.cx & 31) + (chunk.cz & 31) * 32, payload, compression })
  }
  const sectorsOf = (payload: Uint8Array) => Math.ceil((payload.length + 5) / 4096)
  const total = 2 + stored.reduce((sum, it) => sum + sectorsOf(it.payload), 0)
  const out = new Uint8Array(total * 4096)
  const view = new DataView(out.buffer)
  let sector = 2
  for (const { index, payload, compression } of stored) {
    const sectors = sectorsOf(payload)
    view.setUint32(index * 4, (sector << 8) | sectors)
    view.setUint32(sector * 4096, payload.length + 1)
    out[sector * 4096 + 4] = compression
    out.set(payload, sector * 4096 + 5)
    sector += sectors
  }
  return out
}
