/**
 * LZ4, as a region file holds a chunk when the server was set to
 * `region-file-compression=lz4`: lz4-java's `LZ4BlockOutputStream` framing
 * (blocks of `LZ4Block` magic, a method byte, compressed and original lengths
 * and a checksum, little-endian, ending at an empty block) around plain LZ4
 * blocks. The browser has no LZ4, and it's small enough not to need a
 * dependency. The xxHash checksum isn't checked: a broken chunk fails to
 * parse as NBT anyway, which is what the preview reports. Pure.
 */

export class Lz4Error extends Error {}

const MAGIC = 'LZ4Block'
const HEADER = MAGIC.length + 1 + 4 + 4 + 4
/** The method byte's high nibble: stored as it is, or LZ4-compressed. */
const METHOD_RAW = 0x10
const METHOD_LZ4 = 0x20

/** Decodes one LZ4 block (no framing) into exactly [size] bytes. */
export function lz4Block(src: Uint8Array, size: number): Uint8Array {
  const out = new Uint8Array(size)
  let ip = 0
  let op = 0
  const length = (base: number) => {
    let total = base
    if (base === 15) {
      for (;;) {
        if (ip >= src.length) throw new Lz4Error('LZ4 data ends inside a length')
        const byte = src[ip++]!
        total += byte
        if (byte !== 255) break
      }
    }
    return total
  }
  while (ip < src.length) {
    const token = src[ip++]!
    const literals = length(token >> 4)
    if (ip + literals > src.length || op + literals > size) {
      throw new Lz4Error('LZ4 literals run past the end')
    }
    out.set(src.subarray(ip, ip + literals), op)
    ip += literals
    op += literals
    // The last sequence is literals only.
    if (ip >= src.length) break
    if (ip + 2 > src.length) throw new Lz4Error('LZ4 data ends inside an offset')
    const offset = src[ip]! | (src[ip + 1]! << 8)
    ip += 2
    if (offset === 0 || offset > op) throw new Lz4Error('an LZ4 match points before the start')
    const match = length(token & 15) + 4
    if (op + match > size) throw new Lz4Error('an LZ4 match runs past the end')
    // Byte by byte: a match may overlap what it's writing (a run).
    for (let i = 0; i < match; i += 1, op += 1) out[op] = out[op - offset]!
  }
  if (op !== size) throw new Lz4Error(`LZ4 block is ${op} bytes, not ${size}`)
  return out
}

/** Decodes an `LZ4BlockOutputStream` stream. */
export function lz4Stream(bytes: Uint8Array): Uint8Array {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const parts: Uint8Array[] = []
  let offset = 0
  while (offset < bytes.length) {
    if (offset + HEADER > bytes.length) throw new Lz4Error('an LZ4 block header is cut short')
    for (let i = 0; i < MAGIC.length; i += 1) {
      if (bytes[offset + i] !== MAGIC.charCodeAt(i)) throw new Lz4Error('not an LZ4 block stream')
    }
    const method = bytes[offset + MAGIC.length]! & 0xf0
    const compressed = view.getInt32(offset + MAGIC.length + 1, true)
    const original = view.getInt32(offset + MAGIC.length + 5, true)
    offset += HEADER
    if (compressed < 0 || original < 0 || offset + compressed > bytes.length) {
      throw new Lz4Error('an LZ4 block runs past the end')
    }
    if (original === 0) break
    const data = bytes.subarray(offset, offset + compressed)
    if (method === METHOD_RAW) {
      if (compressed !== original) throw new Lz4Error('a stored LZ4 block has two lengths')
      parts.push(data)
    } else if (method === METHOD_LZ4) {
      parts.push(lz4Block(data, original))
    } else {
      throw new Lz4Error(`unknown LZ4 block method ${method}`)
    }
    offset += compressed
  }
  const out = new Uint8Array(parts.reduce((sum, it) => sum + it.length, 0))
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}
