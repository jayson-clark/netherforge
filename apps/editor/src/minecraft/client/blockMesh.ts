/**
 * Blocks as a few big meshes, one per texture, with the faces nobody can see
 * left out: each block state is baked once (`bakeFaces`), and a face with a
 * `cullface` is dropped when the neighbour on that side covers the whole side
 * with an opaque face, as the game does. A solid 48³ cube is then its six
 * outer walls, not 110 592 blocks' worth of quads, which keeps a preview
 * interactive.
 *
 * Shared by the structure preview (one mesh for the whole box) and the
 * map preview (one mesh per chunk, built in a worker, whose
 * neighbours across chunk borders come from the chunks next to it). Where
 * the blocks are is a `BlockSource`; which states hide which sides comes in
 * as numbers. Pure, and free of three.js and the DOM, so it runs in a worker
 * and is tested without textures or WebGL.
 */
import type { BakedFace } from '@netherforge/models/geometry'
import { FACE_NAMES, type FaceName } from '@netherforge/models/model'

/** What one block state draws. */
export interface BakedState {
  faces: BakedFace[]
}

/** Block states baked for `meshBlocks`: what each draws and the sides it hides (`occludedSides`). */
export interface BakedStates {
  states: (BakedState | null)[]
  occludes: number[]
}

/** One texture's merged quads, ready for a `BufferGeometry`. */
export interface MeshGroup {
  texture: string
  positions: Float32Array
  uvs: Float32Array
  /** RGB per vertex, 0–255 (shade times tint). */
  colors: Uint8Array
  indices: Uint16Array | Uint32Array
}

export interface BlockMesh {
  groups: MeshGroup[]
  /** Quads drawn, and quads left out as hidden. */
  faces: number
  culled: number
}

/**
 * Where the blocks are. State numbers index the `states` and `occludes`
 * given to `meshBlocks`; a negative one is nothing (and hides nothing).
 */
export interface BlockSource {
  /** Calls [visit] for every block to draw, with its position and state. */
  forEach(visit: (x: number, y: number, z: number, state: number) => void): void
  /**
   * The state at a position, which may be outside what `forEach` visits (a
   * neighbouring chunk's block); negative for nothing.
   */
  stateAt(x: number, y: number, z: number): number
}

const OFFSET: Record<FaceName, [number, number, number]> = {
  down: [0, -1, 0],
  up: [0, 1, 0],
  north: [0, 0, -1],
  south: [0, 0, 1],
  west: [-1, 0, 0],
  east: [1, 0, 0],
}

const OPPOSITE: Record<FaceName, FaceName> = {
  down: 'up',
  up: 'down',
  north: 'south',
  south: 'north',
  west: 'east',
  east: 'west',
}

const BIT: Record<FaceName, number> = Object.fromEntries(
  FACE_NAMES.map((name, index) => [name, 1 << index]),
) as Record<FaceName, number>

/** The sides a state hides completely: a face on that side, covering it, with an opaque texture. */
export function occludedSides(
  state: BakedState | null | undefined,
  opaque: (texture: string) => boolean,
) {
  let mask = 0
  for (const face of state?.faces ?? []) {
    if (face.covers && opaque(face.texture)) mask |= BIT[face.covers]
  }
  return mask
}

/** A typed array that grows as values are appended. */
class Growing<T extends Float32Array | Uint8Array> {
  private data: T
  length = 0
  constructor(private make: (size: number) => T) {
    this.data = make(1024)
  }
  private room(count: number) {
    if (this.length + count > this.data.length) {
      const next = this.make(Math.max(this.data.length * 2, this.length + count))
      next.set(this.data.subarray(0, this.length))
      this.data = next
    }
  }
  /** Appends [values], adding [dx], [dy], [dz] to each xyz triple. */
  pushMoved(values: number[], dx: number, dy: number, dz: number) {
    this.room(values.length)
    const start = this.length
    for (let i = 0; i < values.length; i += 3) {
      this.data[start + i] = values[i]! + dx
      this.data[start + i + 1] = values[i + 1]! + dy
      this.data[start + i + 2] = values[i + 2]! + dz
    }
    this.length += values.length
  }
  /** Appends [values], each times [scale] (rounded, for bytes). */
  push(values: number[], scale = 1) {
    this.room(values.length)
    const start = this.length
    for (let i = 0; i < values.length; i += 1) {
      this.data[start + i] = scale === 1 ? values[i]! : Math.round(values[i]! * scale)
    }
    this.length += values.length
  }
  done(): T {
    return this.data.slice(0, this.length) as T
  }
}

interface PreparedFace {
  face: BakedFace
  /** Where the neighbour that can hide it is. */
  dx: number
  dy: number
  dz: number
  /** The neighbour's side that hides it (a `BIT`); 0 when nothing can. */
  hides: number
}

function prepare(face: BakedFace): PreparedFace {
  if (!face.cull) return { face, dx: 0, dy: 0, dz: 0, hides: 0 }
  const [dx, dy, dz] = OFFSET[face.cull]
  return { face, dx, dy, dz, hides: BIT[OPPOSITE[face.cull]] }
}

/**
 * Merges every block's faces into one group per texture. [states] is the
 * baked state per state number (null or missing: draws nothing, as air
 * does); [occludes] the sides each hides (`occludedSides`).
 */
export function meshBlocks(
  source: BlockSource,
  states: ArrayLike<BakedState | null | undefined>,
  occludes: ArrayLike<number>,
): BlockMesh {
  const buffers = new Map<
    string,
    {
      positions: Growing<Float32Array>
      uvs: Growing<Float32Array>
      colors: Growing<Uint8Array>
      quads: number
    }
  >()
  let faces = 0
  let culled = 0
  // Each state's faces with their cull lookups worked out once: this loop
  // runs for every face of every block of a chunk, hundreds of thousands.
  const prepared: (PreparedFace[] | undefined)[] = []
  source.forEach((x, y, z, index) => {
    const state = states[index]
    if (!state) return
    let list = prepared[index]
    if (!list) {
      list = state.faces.map(prepare)
      prepared[index] = list
    }
    for (const { face, dx, dy, dz, hides } of list) {
      if (hides) {
        const neighbour = source.stateAt(x + dx, y + dy, z + dz)
        if (neighbour >= 0 && (occludes[neighbour] ?? 0) & hides) {
          culled += 1
          continue
        }
      }
      let buffer = buffers.get(face.texture)
      if (!buffer) {
        buffer = {
          positions: new Growing((size) => new Float32Array(size)),
          uvs: new Growing((size) => new Float32Array(size)),
          colors: new Growing((size) => new Uint8Array(size)),
          quads: 0,
        }
        buffers.set(face.texture, buffer)
      }
      buffer.positions.pushMoved(face.positions, x, y, z)
      buffer.uvs.push(face.uvs)
      buffer.colors.push(face.colors, 255)
      buffer.quads += 1
      faces += 1
    }
  })

  const groups = [...buffers].map(([texture, buffer]) => {
    // Two bytes an index while the vertices allow (a chunk's do), else four.
    const indices =
      buffer.quads * 4 <= 0x10000
        ? new Uint16Array(buffer.quads * 6)
        : new Uint32Array(buffer.quads * 6)
    for (let quad = 0; quad < buffer.quads; quad += 1) {
      const base = quad * 4
      const at = quad * 6
      indices[at] = base
      indices[at + 1] = base + 1
      indices[at + 2] = base + 2
      indices[at + 3] = base
      indices[at + 4] = base + 2
      indices[at + 5] = base + 3
    }
    return {
      texture,
      positions: buffer.positions.done(),
      uvs: buffer.uvs.done(),
      colors: buffer.colors.done(),
      indices,
    }
  })
  return { groups, faces, culled }
}

/** The buffers of [mesh], for moving it between threads without copying. */
export function meshBuffers(mesh: BlockMesh): ArrayBuffer[] {
  return mesh.groups.flatMap((group) => [
    group.positions.buffer as ArrayBuffer,
    group.uvs.buffer as ArrayBuffer,
    group.colors.buffer as ArrayBuffer,
    group.indices.buffer as ArrayBuffer,
  ])
}
