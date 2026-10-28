/**
 * A box of blocks ([Volume]) cut into structure files: one, `structures/<id>.nbt`, when it fits in a structure,
 * or a build's grid of parts, `builds/<id>/<x>_<y>_<z>.nbt` (format's `BuildKind`: every part
 * `BUILD_LIMITS.partSide` a side but the last along each axis). Each part is Minecraft's structure format as the
 * game writes it, in the volume's data version, air and all (placing it is what clears the space), its blocks
 * from the bottom up; and what reading the parts back for a picture needs. Pure, for the editor and the CLI.
 */
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { Int32, TAG, TAG_TYPE, type CompoundTag, type Tag } from 'nbtify'
import { nbtRoot, writeNbt } from './nbt.ts'
import { indexOf, type Volume } from './schematic.ts'
import { kindOf } from './structure.ts'

/** How many parts a box [size] blocks across is cut into along each axis. */
export const gridOf = (size: readonly number[]): [number, number, number] =>
  size.map((it) => Math.ceil(it / BUILD_LIMITS.partSide)) as [number, number, number]

/** Whether a box [size] blocks across fits in one structure file. */
export const fitsOneFile = (size: readonly number[]) =>
  size.every((it) => it <= BUILD_LIMITS.partSide)

/** A build's part's file name: its place in the grid. */
export const partFile = (x: number, y: number, z: number) => `${x}_${y}_${z}.nbt`

/** A part's place in the grid, from its file name, or null when the name isn't a part's. */
export function partOf(file: string): [number, number, number] | null {
  const match = /^(0|[1-9]\d{0,3})_(0|[1-9]\d{0,3})_(0|[1-9]\d{0,3})\.nbt$/.exec(file)
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null
}

/** The grid a build's [files] (names in its folder) make: one past the furthest part along each axis. */
export function gridOfFiles(files: readonly string[]): [number, number, number] {
  const parts = files.map(partOf).filter((it) => it !== null)
  return [0, 1, 2].map((i) => Math.max(0, ...parts.map((it) => it[i]!)) + 1) as [
    number,
    number,
    number,
  ]
}

/** Every part of a [grid], bottom up, x fastest, then z: the order a build is placed in (format's `BuildGrid.parts`). */
export function partsOf(grid: readonly number[]): [number, number, number][] {
  const out: [number, number, number][] = []
  for (let y = 0; y < grid[1]!; y += 1)
    for (let z = 0; z < grid[2]!; z += 1) for (let x = 0; x < grid[0]!; x += 1) out.push([x, y, z])
  return out
}

/** A typed NBT list, as the game writes one (one element type per list). */
const list = (element: TAG, values: Tag[]): Tag =>
  Object.defineProperty(values, TAG_TYPE, { value: element, enumerable: false })
const ints = (...values: number[]) =>
  list(
    TAG.INT,
    values.map((it) => new Int32(it)),
  )

/** A palette entry as the game writes one: `Name`, and `Properties` when it has any. */
function paletteEntry(state: string): CompoundTag {
  const name = kindOf(state)
  if (name === state) return { Name: name }
  const properties: CompoundTag = {}
  for (const pair of state.slice(name.length + 1, -1).split(',')) {
    const equals = pair.indexOf('=')
    if (equals > 0) properties[pair.slice(0, equals)] = pair.slice(equals + 1)
  }
  return { Name: name, Properties: properties }
}

/**
 * The structure file holding the blocks of [volume] from [from] (its smallest corner, in the volume) to [to]
 * (exclusive): what the game reads, gzipped.
 */
export function structureFile(
  volume: Volume,
  from: readonly number[],
  to: readonly number[],
): Promise<Uint8Array> {
  const local = new Map<number, number>()
  const palette: CompoundTag[] = []
  const blocks: CompoundTag[] = []
  for (let y = from[1]!; y < to[1]!; y += 1) {
    for (let z = from[2]!; z < to[2]!; z += 1) {
      for (let x = from[0]!; x < to[0]!; x += 1) {
        const index = indexOf(volume.size, x, y, z)
        const global = volume.blocks[index]!
        let state = local.get(global)
        if (state === undefined) {
          state = palette.length
          local.set(global, state)
          palette.push(paletteEntry(volume.palette[global]!))
        }
        const block: CompoundTag = {
          pos: ints(x - from[0]!, y - from[1]!, z - from[2]!),
          state: new Int32(state),
        }
        const data = volume.blockEntities.get(index)
        if (data) block.nbt = data
        blocks.push(block)
      }
    }
  }
  const size = [0, 1, 2].map((i) => to[i]! - from[i]!)
  const root: CompoundTag = {
    size: ints(...size),
    palette: list(TAG.COMPOUND, palette),
    blocks: list(TAG.COMPOUND, blocks),
    entities: list(TAG.COMPOUND, []),
  }
  if (volume.dataVersion !== null) root.DataVersion = new Int32(volume.dataVersion)
  return writeNbt(nbtRoot(root))
}

/** One file of a build, ready to write: its name in the build's folder, and its bytes. */
export interface PartFile {
  file: string
  bytes: Uint8Array
}

/**
 * [volume]'s build parts, one at a time (each is written while the next is made, so a big import never holds
 * every part at once), bottom up. [onPart] hears how far it's got.
 */
export async function* buildParts(
  volume: Volume,
  onPart?: (done: number, total: number) => void,
): AsyncGenerator<PartFile> {
  const side = BUILD_LIMITS.partSide
  const all = partsOf(gridOf(volume.size))
  let done = 0
  for (const [x, y, z] of all) {
    const from = [x * side, y * side, z * side]
    const to = from.map((it, i) => Math.min(it + side, volume.size[i]!))
    yield { file: partFile(x, y, z), bytes: await structureFile(volume, from, to) }
    done += 1
    onPart?.(done, all.length)
  }
}

/** What an import found to tell about: how many blocks of each id [known] says the game doesn't have, most first. */
export function unknownBlocks(
  volume: Volume,
  known: (id: string) => boolean,
): { id: string; count: number }[] {
  const counts = new Array<number>(volume.palette.length).fill(0)
  for (const index of volume.blocks) counts[index] = counts[index]! + 1
  const byId = new Map<string, number>()
  volume.palette.forEach((state, index) => {
    const id = kindOf(state)
    if (counts[index]! > 0 && !known(id)) byId.set(id, (byId.get(id) ?? 0) + counts[index]!)
  })
  return [...byId]
    .map(([id, count]) => ({ id, count }))
    .sort((a, b) => b.count - a.count || a.id.localeCompare(b.id))
}
