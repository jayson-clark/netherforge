/**
 * A structure file (`structures/<id>.nbt`, Minecraft's structure format, what
 * structure blocks and `/place template` use) as the editor previews it:
 * its size, its palette of block states, where each block is, and how many
 * entities and block entities it carries. Pure: tested against hand-built NBT
 * and a file a real server saved.
 */
import type { CompoundTag, Tag } from 'nbtify'
import { nbtAt, nbtCompound, nbtList, nbtNumber, nbtString, nbtVec3, type NbtRoot } from './nbt.ts'

export interface Structure {
  /** The Minecraft data version that saved it, when the file says. */
  dataVersion: number | null
  size: [number, number, number]
  /** Block states, `minecraft:oak_stairs[facing=east,half=bottom]` (properties sorted). */
  palette: string[]
  /** Four numbers per block: x, y, z and its palette index. Blocks outside [size] are left out. */
  blocks: Int32Array
  /** Entities saved with it. */
  entities: number
  /** Blocks carrying data of their own (chests, signs). */
  blockEntities: number
  /** Palettes besides the first (a structure can have several; the game picks one at random). */
  otherPalettes: number
}

export class StructureError extends Error {}

/**
 * A palette entry as the block state text the model code reads. Minecraft
 * 26.x writes `id` (and `properties`); older files `Name` and `Properties`.
 */
export function stateOf(entry: CompoundTag): string {
  const name = nbtString(entry.id) ?? nbtString(entry.Name) ?? ''
  const properties = nbtCompound(entry.properties) ?? nbtCompound(entry.Properties)
  if (!properties) return name
  const pairs = Object.keys(properties)
    .sort()
    .map((key) => `${key}=${nbtString(properties[key]) ?? ''}`)
  return pairs.length > 0 ? `${name}[${pairs.join(',')}]` : name
}

/**
 * A palette entry of any shape a save uses: a compound (`stateOf`), or the
 * bare id 26.x writes for a state without properties, either as a list of
 * strings or, in a list that mixes both, wrapped as `{"": id}` (NBT lists
 * hold one tag type, so the game wraps the odd ones out).
 */
export function stateOfTag(tag: Tag): string {
  if (typeof tag === 'string') return tag
  const entry = nbtCompound(tag) ?? {}
  const wrapped = nbtString(entry[''])
  return wrapped ?? stateOf(entry)
}

/** A block state's id: `minecraft:oak_stairs[facing=east]` → `minecraft:oak_stairs`. */
export const kindOf = (state: string) => {
  const open = state.indexOf('[')
  return open < 0 ? state : state.slice(0, open)
}

export function parseStructure(root: NbtRoot): Structure {
  const data = root.data
  const size = nbtVec3(data.size)
  if (!size || size.some((it) => !Number.isInteger(it) || it < 0)) {
    throw new StructureError("this isn't a structure file: it has no size")
  }
  const palettes = nbtList(data.palettes)
  const paletteTag = data.palette ?? palettes[0]
  if (!paletteTag) throw new StructureError("this isn't a structure file: it has no palette")
  const palette = nbtList(paletteTag).map(stateOfTag)

  const blockTags = nbtList(data.blocks)
  const blocks = new Int32Array(blockTags.length * 4)
  let count = 0
  let blockEntities = 0
  for (const tag of blockTags) {
    const block = nbtCompound(tag)
    if (!block) continue
    const pos = nbtVec3(block.pos)
    const state = nbtNumber(block.state)
    if (!pos || state === undefined || state < 0 || state >= palette.length) continue
    const [x, y, z] = pos
    if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) continue
    blocks.set([x, y, z, state], count * 4)
    count += 1
    if (block.nbt) blockEntities += 1
  }

  return {
    dataVersion: nbtNumber(nbtAt(data, 'DataVersion')) ?? null,
    size,
    palette,
    blocks: blocks.subarray(0, count * 4),
    entities: nbtList(data.entities).length,
    blockEntities,
    otherPalettes: data.palette ? 0 : Math.max(0, palettes.length - 1),
  }
}

/** How many blocks of each kind (block id), most first, ties by id. */
export function blockCounts(structure: Structure): { kind: string; count: number }[] {
  const byState = new Map<number, number>()
  for (let i = 3; i < structure.blocks.length; i += 4) {
    const state = structure.blocks[i]!
    byState.set(state, (byState.get(state) ?? 0) + 1)
  }
  const byKind = new Map<string, number>()
  for (const [state, count] of byState) {
    const kind = kindOf(structure.palette[state]!)
    byKind.set(kind, (byKind.get(kind) ?? 0) + count)
  }
  return [...byKind]
    .map(([kind, count]) => ({ kind, count }))
    .sort((a, b) => b.count - a.count || a.kind.localeCompare(b.kind))
}
