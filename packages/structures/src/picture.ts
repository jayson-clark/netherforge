/**
 * Pictures of a structure or a build without the game: from above, from the side at an angle (isometric), and a
 * slice through it along any axis. The editor's structure and build screens and `netherforge preview` draw the
 * same pixels. A build is added a part at a time, so a big one is never all in memory: what a picture keeps is
 * one number or two per column (or per cell of a slice).
 *
 * The colours are the editor's own, since it ships no Minecraft pictures: each block id gets a colour of its own
 * from its name (the same everywhere), shaded by height and side; what format calls empty (`EMPTY_BLOCKS`: air
 * and the like) isn't drawn. The legend names what shows.
 */
import { EMPTY_BLOCKS } from '@netherforge/format/constants'
import { hsl, shadeOf, type Rgb } from './color.ts'
import { kindOf, type Structure } from './structure.ts'

/** What a picture shows: from above, isometric, or a slice through block [at] along [axis] (counted from the corner). */
export type View =
  { kind: 'top' } | { kind: 'iso' } | { kind: 'slice'; axis: 'x' | 'y' | 'z'; at: number }

export interface StructureImage {
  width: number
  height: number
  /** Straight RGBA, row by row from the top: the shape of `@netherforge/models`' pictures. */
  data: Uint8ClampedArray
  /** The block ids that show, most first, each with its colour and how many cells show it. */
  legend: { kind: string; color: Rgb; count: number }[]
}

const EMPTY = new Set(EMPTY_BLOCKS)

/** A block id's colour: a hue, saturation and lightness from a hash of its name, so it's the same in every picture. */
export function kindColor(kind: string): Rgb {
  let hash = 0x811c9dc5
  for (let i = 0; i < kind.length; i += 1)
    hash = Math.imul(hash ^ kind.charCodeAt(i), 0x01000193) >>> 0
  return hsl(hash % 360, 0.35 + ((hash >>> 9) % 30) / 100, 0.42 + ((hash >>> 17) % 18) / 100)
}

/**
 * One picture being made: [size] is the whole structure's or build's, [view] what it shows. [add] each file,
 * then [draw].
 */
export class StructurePicture {
  private readonly kinds: string[] = []
  private readonly byKind = new Map<string, number>()
  /** Per column (x + z * width): the highest block's y, -1 for none. Top and isometric views. */
  private readonly heights: Int32Array | null
  /** Per column, or per cell of a slice: the kind there, -1 for none. */
  private readonly cells: Int32Array

  constructor(
    readonly size: readonly [number, number, number],
    readonly view: View,
  ) {
    const [sx, sy, sz] = size
    if (view.kind === 'slice') {
      const limit = { x: sx, y: sy, z: sz }[view.axis]
      if (!Number.isInteger(view.at) || view.at < 0 || view.at >= limit) {
        throw new RangeError(`a slice along ${view.axis} is at 0 to ${limit - 1}, not ${view.at}`)
      }
      this.heights = null
      this.cells = new Int32Array(
        view.axis === 'y' ? sx * sz : view.axis === 'x' ? sz * sy : sx * sy,
      ).fill(-1)
    } else {
      this.heights = new Int32Array(sx * sz).fill(-1)
      this.cells = new Int32Array(sx * sz).fill(-1)
    }
  }

  private kindIndex(state: string): number {
    const kind = kindOf(state)
    if (EMPTY.has(kind)) return -1
    let index = this.byKind.get(kind)
    if (index === undefined) {
      index = this.kinds.length
      this.kinds.push(kind)
      this.byKind.set(kind, index)
    }
    return index
  }

  /** Adds [structure]'s blocks, with its smallest corner at [origin] in the whole (a build's part's place). */
  add(structure: Structure, origin: readonly number[] = [0, 0, 0]): void {
    const [sx, sy, sz] = this.size
    const kinds = structure.palette.map((state) => this.kindIndex(state))
    const blocks = structure.blocks
    const view = this.view
    for (let i = 0; i < blocks.length; i += 4) {
      const kind = kinds[blocks[i + 3]!]!
      if (kind < 0) continue
      const x = origin[0]! + blocks[i]!
      const y = origin[1]! + blocks[i + 1]!
      const z = origin[2]! + blocks[i + 2]!
      if (x >= sx || y >= sy || z >= sz) continue
      if (view.kind === 'slice') {
        if (view.axis === 'y' && y === view.at) this.cells[x + z * sx] = kind
        else if (view.axis === 'x' && x === view.at) this.cells[z + (sy - 1 - y) * sz] = kind
        else if (view.axis === 'z' && z === view.at) this.cells[x + (sy - 1 - y) * sx] = kind
      } else {
        const column = x + z * sx
        if (y > this.heights![column]!) {
          this.heights![column] = y
          this.cells[column] = kind
        }
      }
    }
  }

  /** The picture, each cell [scale] pixels across. */
  draw(scale = 1): StructureImage {
    if (!Number.isInteger(scale) || scale < 1)
      throw new RangeError(`a picture's scale is 1 or more, not ${scale}`)
    const counts = new Array<number>(this.kinds.length).fill(0)
    const colors = this.kinds.map(kindColor)
    const drawn = this.view.kind === 'iso' ? this.iso(colors, counts) : this.flat(colors, counts)
    const legend = this.kinds
      .map((kind, i) => ({ kind, color: colors[i]!, count: counts[i]! }))
      .filter((it) => it.count > 0)
      .sort((a, b) => b.count - a.count || a.kind.localeCompare(b.kind))
    return { ...enlarge(drawn, scale), legend }
  }

  /** From above, or a slice: a cell a pixel. From above, higher ground is lighter, and slopes are lit from the north-west. */
  private flat(colors: Rgb[], counts: number[]): Raw {
    const [sx, sy, sz] = this.size
    const view = this.view
    const [width, height] =
      view.kind === 'slice' && view.axis !== 'y' ? [view.axis === 'x' ? sz : sx, sy] : [sx, sz]
    const pixels = new Uint8ClampedArray(width * height * 4)
    const heights = this.heights
    const at = (x: number, z: number) =>
      heights![Math.max(0, Math.min(sz - 1, z)) * sx + Math.max(0, Math.min(sx - 1, x))]!
    for (let cell = 0; cell < this.cells.length; cell += 1) {
      const kind = this.cells[cell]!
      if (kind < 0) continue
      counts[kind] = counts[kind]! + 1
      let color = colors[kind]!
      if (heights) {
        const x = cell % sx
        const z = Math.floor(cell / sx)
        const slope = (at(x + 1, z + 1) - at(x - 1, z - 1)) / 2
        const altitude = sy > 1 ? heights[cell]! / (sy - 1) : 1
        color = shadeOf(color, 0.75 + altitude * 0.35 - Math.max(-0.3, Math.min(0.3, slope * 0.1)))
      }
      put(pixels, cell * 4, color)
    }
    return { width, height, data: pixels }
  }

  /**
   * From the south-east, looking down at an angle: each column a strip four pixels wide, its top face lighter,
   * its two sides darker, two pixels a block high; columns further back drawn first.
   */
  private iso(colors: Rgb[], counts: number[]): Raw {
    const [sx, sy, sz] = this.size
    const width = 2 * (sx + sz)
    const height = sx + sz + 2 * sy
    const pixels = new Uint8ClampedArray(width * height * 4)
    const heights = this.heights!
    for (let depth = 0; depth <= sx + sz - 2; depth += 1) {
      for (let x = Math.max(0, depth - sz + 1); x <= Math.min(sx - 1, depth); x += 1) {
        const z = depth - x
        const column = x + z * sx
        const kind = this.cells[column]!
        if (kind < 0) continue
        counts[kind] = counts[kind]! + 1
        const top = heights[column]!
        const color = colors[kind]!
        const faces = [shadeOf(color, 1.08), shadeOf(color, 0.8), shadeOf(color, 0.62)] as const
        const left = (x - z + sz - 1) * 2
        const row = x + z + (sy - 1 - top) * 2
        for (let dy = 0; dy < 2 * top + 4; dy += 1) {
          for (let dx = 0; dx < 4; dx += 1) {
            const face = dy < 2 ? faces[0] : dx < 2 ? faces[1] : faces[2]
            put(pixels, ((row + dy) * width + left + dx) * 4, face)
          }
        }
      }
    }
    return { width, height, data: pixels }
  }
}

interface Raw {
  width: number
  height: number
  data: Uint8ClampedArray
}

function put(pixels: Uint8ClampedArray, at: number, color: Rgb) {
  pixels[at] = color[0]
  pixels[at + 1] = color[1]
  pixels[at + 2] = color[2]
  pixels[at + 3] = 255
}

/** [raw] with each pixel [scale] by [scale]. */
function enlarge(raw: Raw, scale: number): Raw {
  if (scale === 1) return raw
  const { width, height, data: pixels } = raw
  const out = new Uint8ClampedArray(width * scale * height * scale * 4)
  for (let y = 0; y < height * scale; y += 1) {
    for (let x = 0; x < width * scale; x += 1) {
      const from = (Math.floor(y / scale) * width + Math.floor(x / scale)) * 4
      out.set(pixels.subarray(from, from + 4), (y * width * scale + x) * 4)
    }
  }
  return { width: width * scale, height: height * scale, data: out }
}
