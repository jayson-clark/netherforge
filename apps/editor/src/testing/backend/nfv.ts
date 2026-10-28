/** A `.nfv` video's header, as the Rust side writes and reads it: enough for `videoInfo` to read what `convertVideo` wrote. */

/** A converted video's header (docs/format/video.md), as the Rust side writes one. */
interface NfvHeader {
  columns: number
  rows: number
  fps: number
  frames: number
  audio: boolean
  /** The source's SHA-256. */
  source: Uint8Array
}

const NFV_HEADER = 72

export function nfvHeader(header: NfvHeader): Uint8Array {
  const bytes = new Uint8Array(NFV_HEADER)
  const view = new DataView(bytes.buffer)
  bytes.set([0x4e, 0x46, 0x56, 0x31])
  view.setUint16(4, 1)
  view.setUint16(6, header.columns)
  view.setUint16(8, header.rows)
  view.setUint16(10, header.fps)
  view.setUint32(12, header.frames)
  view.setUint16(16, header.fps * 2)
  bytes[18] = 1
  bytes[28] = header.audio ? 1 : 0
  bytes.set(header.source, 32)
  return bytes
}

export function readNfvHeader(bytes: Uint8Array): NfvHeader | null {
  if (
    bytes.length < NFV_HEADER ||
    !sameBytes(bytes.subarray(0, 4), new Uint8Array([0x4e, 0x46, 0x56, 0x31]))
  )
    return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (view.getUint16(4) !== 1) return null
  return {
    columns: view.getUint16(6),
    rows: view.getUint16(8),
    fps: view.getUint16(10),
    frames: view.getUint32(12),
    audio: bytes[28] !== 0,
    source: bytes.slice(32, 64),
  }
}

export function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && a.every((value, i) => value === b[i])
}
