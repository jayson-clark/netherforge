/**
 * Minecraft's region files (Anvil, `region/r.<x>.<z>.mca`): 32×32 chunks
 * each, found through a 4 KiB table of where each chunk's sectors start
 * (three bytes, in 4 KiB sectors) and how many there are (one byte), then
 * 4 KiB of timestamps. A chunk's sectors start with its length (four bytes,
 * big-endian, counting the next byte) and how it's compressed (one byte:
 * gzip, zlib, none, LZ4, or a named custom one); with the high bit set, the
 * chunk was too big for the region file and lives in `c.<x>.<z>.mcc` next to
 * it, compressed the same way. Pure apart from decompression, which is the
 * platform's own `DecompressionStream` (in a worker, the webview and Node).
 */
import { decompress } from 'nbtify'
import { lz4Stream } from './lz4'

export const SECTOR = 4096
/** Chunks along each side of a region. */
export const REGION_SIZE = 32
const HEADER = 2 * SECTOR

export const COMPRESSION = { gzip: 1, zlib: 2, none: 3, lz4: 4, custom: 127 } as const
const EXTERNAL = 0x80

export class RegionError extends Error {}

/** The region a chunk is in (floor division, so -1 is in region -1). */
export const regionOf = (chunk: number) => chunk >> 5

export const regionFileName = (rx: number, rz: number) => `r.${rx}.${rz}.mca`

/** Where a chunk too big for its region file is kept (absolute chunk coordinates). */
export const externalChunkFileName = (cx: number, cz: number) => `c.${cx}.${cz}.mcc`

/** A chunk's stored bytes, still compressed. */
export interface ChunkPayload {
  compression: number
  /** Empty when [external]: the bytes are in `externalChunkFileName`. */
  data: Uint8Array
  external: boolean
}

/**
 * Chunk ([cx], [cz]) as stored in [region], its region's bytes; null when it
 * was never saved. A location that points outside the file is a `RegionError`.
 */
export function chunkPayload(region: Uint8Array, cx: number, cz: number): ChunkPayload | null {
  // The game creates region files lazily; an empty one holds no chunks.
  if (region.length === 0) return null
  if (region.length < HEADER) {
    throw new RegionError(`the region file is ${region.length} bytes, shorter than its header`)
  }
  const view = new DataView(region.buffer, region.byteOffset, region.byteLength)
  const index = (cx & (REGION_SIZE - 1)) + (cz & (REGION_SIZE - 1)) * REGION_SIZE
  const location = view.getUint32(index * 4)
  if (location === 0) return null
  const sector = location >>> 8
  const sectors = location & 0xff
  const start = sector * SECTOR
  if (sector < HEADER / SECTOR || sectors === 0 || start + 5 > region.length) {
    throw new RegionError(`its place in the region file (sector ${sector}) is outside the file`)
  }
  const length = view.getUint32(start)
  const kind = region[start + 4]!
  const external = (kind & EXTERNAL) !== 0
  if (external) return { compression: kind & ~EXTERNAL, data: new Uint8Array(0), external }
  if (length < 1 || start + 4 + length > region.length) {
    throw new RegionError(`its length (${length} bytes) runs past the end of the region file`)
  }
  return { compression: kind, data: region.subarray(start + 5, start + 4 + length), external }
}

async function inflate(data: Uint8Array, format: 'gzip' | 'deflate'): Promise<Uint8Array> {
  try {
    return await decompress(data, format)
  } catch {
    throw new RegionError(`its ${format === 'gzip' ? 'gzip' : 'zlib'} data is broken`)
  }
}

/** A chunk's NBT bytes from its stored ones. */
export async function decompressChunk(compression: number, data: Uint8Array): Promise<Uint8Array> {
  switch (compression) {
    case COMPRESSION.gzip:
      return inflate(data, 'gzip')
    case COMPRESSION.zlib:
      return inflate(data, 'deflate')
    case COMPRESSION.none:
      return data
    case COMPRESSION.lz4:
      return lz4Stream(data)
    case COMPRESSION.custom: {
      // A namespaced algorithm id (a modded server's) comes first: say which.
      const length = data.length >= 2 ? (data[0]! << 8) | data[1]! : 0
      const name = new TextDecoder().decode(data.subarray(2, 2 + length))
      throw new RegionError(`it's compressed with ${name || 'a custom algorithm'}`)
    }
    default:
      throw new RegionError(`it's compressed in an unknown way (${compression})`)
  }
}
