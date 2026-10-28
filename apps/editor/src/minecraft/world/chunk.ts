/**
 * A chunk's blocks from its NBT, as Minecraft 1.18 and later save it: a list
 * of `sections`, each 16×16×16 blocks at height `Y`, with `block_states` as a
 * palette of states and a packed long array of palette indices (none when the
 * palette has one entry: the whole section is that state). Indices are
 * packed low bits first, as many as fit whole in each long (none span two),
 * ordered y, then z, then x. Pure.
 */
import {
  nbtCompound,
  nbtList,
  nbtNumber,
  nbtString,
  type NbtRoot,
} from '@netherforge/structures/nbt'
import { stateOfTag } from '@netherforge/structures/structure'

/** Blocks along each side of a chunk section. */
export const SECTION_SIZE = 16
export const SECTION_BLOCKS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE

/**
 * The status of a chunk the game finished generating. Others are kept while
 * generation is under way (around the edge of what was explored) and may be
 * bare stone or half-decorated; the game shows none of them, and nor do we.
 * Save-format vocabulary, like `sections`.
 */
const FULL_STATUS = 'minecraft:full'

export interface ChunkSection {
  /** Height in sections: blocks y = 16 × [y] … 16 × [y] + 15. */
  y: number
  palette: string[]
  /** A palette index per block (`blockIndex`); null when every block is `palette[0]`. */
  indices: Uint8Array | Uint16Array | null
}

export interface Chunk {
  x: number | null
  z: number | null
  /** Whether the game finished generating it (and so would draw it). */
  full: boolean
  sections: ChunkSection[]
}

export class ChunkError extends Error {}

/** Where block ([x], [y], [z]) of a section (each 0–15) is in its indices. */
export const blockIndex = (x: number, y: number, z: number) =>
  (y * SECTION_SIZE + z) * SECTION_SIZE + x

/** Bits per block-state index for a palette of [size]: at least 4. */
export function bitsFor(size: number): number {
  return Math.max(4, Math.ceil(Math.log2(size)))
}

/** How many longs [count] indices of [bits] each take, none spanning two. */
export const longsFor = (bits: number, count = SECTION_BLOCKS) =>
  Math.ceil(count / Math.floor(64 / bits))

/** Wider than any section's palette needs (4096 states take 12 bits). */
const MAX_BITS = 16

/**
 * The palette indices packed in [longs], for a palette of [paletteSize].
 * The width follows from the palette; if the array's length says another
 * width (a file written by a tool that rounds differently), that one is
 * used as long as it can hold every index.
 */
export function unpackIndices(
  longs: BigInt64Array,
  paletteSize: number,
  count = SECTION_BLOCKS,
): Uint8Array | Uint16Array {
  if (paletteSize > 2 ** MAX_BITS) {
    throw new ChunkError(`a section's palette has ${paletteSize} states`)
  }
  let bits = bitsFor(paletteSize)
  if (longs.length !== longsFor(bits, count)) {
    const derived = longs.length > 0 ? Math.floor(64 / Math.ceil(count / longs.length)) : 0
    if (
      derived < 1 ||
      derived > MAX_BITS ||
      longsFor(derived, count) !== longs.length ||
      2 ** derived < paletteSize
    ) {
      throw new ChunkError(
        `a section's ${longs.length} longs don't fit its palette of ${paletteSize} states`,
      )
    }
    bits = derived
  }
  const perLong = Math.floor(64 / bits)
  const mask = 2 ** bits - 1
  const out = paletteSize <= 256 ? new Uint8Array(count) : new Uint16Array(count)
  let index = 0
  for (let l = 0; l < longs.length && index < count; l += 1) {
    // Halves as numbers: shifting doubles is far cheaper than BigInt per index.
    const value = longs[l]!
    const low = Number(value & 0xffffffffn)
    const high = Number((value >> 32n) & 0xffffffffn)
    for (let slot = 0; slot < perLong && index < count; slot += 1, index += 1) {
      const shift = slot * bits
      let entry: number
      if (shift + bits <= 32) entry = (low >>> shift) & mask
      else if (shift >= 32) entry = (high >>> (shift - 32)) & mask
      else entry = ((low >>> shift) | (high << (32 - shift))) & mask
      if (entry >= paletteSize) {
        throw new ChunkError(`a section points at state ${entry} of a palette of ${paletteSize}`)
      }
      out[index] = entry
    }
  }
  return out
}

/** A chunk's NBT (the root of what a region file stores for it) as sections of blocks. */
export function parseChunk(root: NbtRoot): Chunk {
  const data = root.data
  if (!data.sections) {
    throw new ChunkError(
      data.Level
        ? 'it was saved by a Minecraft older than 1.18, whose chunks the preview doesn’t read'
        : 'it has no sections',
    )
  }
  const sections: ChunkSection[] = []
  for (const tag of nbtList(data.sections)) {
    const section = nbtCompound(tag)
    const y = nbtNumber(section?.Y)
    const states = nbtCompound(section?.block_states)
    // Sections above and below the world carry only light.
    if (!section || y === undefined || !states) continue
    const palette = nbtList(states.palette).map(stateOfTag)
    if (palette.length === 0) continue
    const packed = states.data
    let indices: ChunkSection['indices'] = null
    if (palette.length > 1) {
      if (!(packed instanceof BigInt64Array)) {
        throw new ChunkError(`section ${y} has ${palette.length} states but no data`)
      }
      indices = unpackIndices(packed, palette.length)
    }
    sections.push({ y, palette, indices })
  }
  const status = nbtString(data.Status)
  return {
    x: nbtNumber(data.xPos) ?? null,
    z: nbtNumber(data.zPos) ?? null,
    // A chunk without a status is taken as finished.
    full: status === undefined || status === FULL_STATUS,
    sections,
  }
}
