/**
 * Schematics from other tools, read into one box of blocks a build or a structure is cut from (`parts.ts`):
 * WorldEdit's Sponge schematics (`.schem`, versions 1 to 3) and Litematica's (`.litematic`, every region merged
 * into the box enclosing them). Pure, and shared by the editor's import and `netherforge import`.
 *
 * Block states are kept as the file names them, in its data version: nothing here renames anything, since what
 * the game renamed between versions isn't data NetherForge has. The parts are written with the file's data
 * version, and the server updates old names as it reads them, as it does any old structure file. Entities aren't
 * carried over (they're counted, so the import can say so); block entities (a chest's items, a sign's text) are.
 */
import type { CompoundTag, Tag } from 'nbtify'
import { nbtCompound, nbtList, nbtNumber, nbtString, type NbtRoot } from './nbt.ts'
import { stateOf } from './structure.ts'

export class SchematicError extends Error {}

/** A box of blocks: what a schematic holds, as the import writes it out. */
export interface Volume {
  /** Which tool's file it was read from. */
  format: 'sponge' | 'litematica'
  /** The Minecraft data version the file was saved in, when it says. */
  dataVersion: number | null
  size: [number, number, number]
  /** Block states, `minecraft:oak_stairs[facing=east,half=bottom]` (properties sorted). */
  palette: string[]
  /** The palette index of every block, x fastest, then z, then y: [index]. */
  blocks: Uint16Array | Uint32Array
  /** Block entities' data in a structure file's form (`{ id, …data }`), by block index. */
  blockEntities: Map<number, CompoundTag>
  /** Entities the file holds, which aren't imported. */
  entities: number
}

/** The most blocks one volume may hold: 2 bytes each, so a quarter of a gigabyte at most. */
export const MAX_VOLUME = 128 * 1024 * 1024

/** The index of the block at [x], [y], [z] in a volume [size]d: x fastest, then z, then y. */
export const indexOf = (size: readonly number[], x: number, y: number, z: number) =>
  x + size[0]! * (z + size[2]! * y)

/** A block state's properties sorted, as `stateOf` writes them, so the same state is always the same text. */
export function normalizeState(state: string): string {
  const open = state.indexOf('[')
  if (open < 0 || !state.endsWith(']')) return state
  const pairs = state
    .slice(open + 1, -1)
    .split(',')
    .filter((it) => it !== '')
    .sort()
  return pairs.length > 0 ? `${state.slice(0, open)}[${pairs.join(',')}]` : state.slice(0, open)
}

/** Reads a schematic file's NBT as whichever tool's it is. */
export function readSchematic(root: NbtRoot): Volume {
  const data = root.data
  if (nbtCompound(data.Regions)) return readLitematic(data)
  const sponge = nbtCompound(data.Schematic) ?? data
  if (sponge.Palette !== undefined || nbtCompound(sponge.Blocks)) return readSponge(sponge)
  if (sponge.Blocks !== undefined && sponge.Materials !== undefined) {
    throw new SchematicError(
      'this is an MCEdit schematic from before Minecraft 1.13, which only holds numeric block ids: ' +
        'load it in WorldEdit on a newer version and save it again as .schem',
    )
  }
  throw new SchematicError(
    "this isn't a schematic: it's neither WorldEdit's (.schem) nor Litematica's (.litematic)",
  )
}

function newVolume(
  format: Volume['format'],
  dataVersion: number | null,
  size: [number, number, number],
  palette: string[],
): Volume {
  if (size.some((it) => !Number.isInteger(it) || it < 1)) {
    throw new SchematicError(`its size, ${size.join(' × ')}, isn't a box of blocks`)
  }
  const volume = size[0] * size[1] * size[2]
  if (volume > MAX_VOLUME) {
    throw new SchematicError(
      `it's ${size.join(' × ')} blocks, ${volume} in all; one import holds at most ${MAX_VOLUME}`,
    )
  }
  const blocks = palette.length > 0xffff ? new Uint32Array(volume) : new Uint16Array(volume)
  return { format, dataVersion, size, palette, blocks, blockEntities: new Map(), entities: 0 }
}

// ---- Sponge (.schem) -------------------------------------------------------

/** A short read as the unsigned number Sponge's sizes are. */
const unsignedShort = (tag: Tag | undefined) => (nbtNumber(tag) ?? 0) & 0xffff

function readSponge(schematic: CompoundTag): Volume {
  const version = nbtNumber(schematic.Version) ?? 1
  const size: [number, number, number] = [
    unsignedShort(schematic.Width),
    unsignedShort(schematic.Height),
    unsignedShort(schematic.Length),
  ]
  // Version 3 keeps its blocks in a compound of their own; 1 and 2 at the top.
  const container = version >= 3 ? (nbtCompound(schematic.Blocks) ?? {}) : schematic
  const paletteTag = nbtCompound(container.Palette)
  if (!paletteTag) throw new SchematicError('its blocks have no palette')
  const entries = Object.entries(paletteTag)
  const palette: string[] = new Array<string>(entries.length)
  for (const [state, index] of entries) {
    const at = nbtNumber(index)
    if (at === undefined || at < 0 || at >= entries.length || palette[at] !== undefined) {
      throw new SchematicError(
        `its palette gives "${state}" the index ${String(at)}, which isn't one of its own`,
      )
    }
    palette[at] = normalizeState(state)
  }
  const volume = newVolume('sponge', nbtNumber(schematic.DataVersion) ?? null, size, palette)

  const data = version >= 3 ? container.Data : schematic.BlockData
  if (!(data instanceof Int8Array)) throw new SchematicError('its blocks have no data')
  // One varint per block: the low seven bits of each byte, most significant last.
  let block = 0
  let value = 0
  let shift = 0
  for (let i = 0; i < data.length; i += 1) {
    const byte = data[i]! & 0xff
    value |= (byte & 0x7f) << shift
    if (byte & 0x80) {
      shift += 7
      if (shift > 28) throw new SchematicError('its block data is corrupt (a number runs on)')
      continue
    }
    if (block >= volume.blocks.length)
      throw new SchematicError('its block data holds more blocks than its size')
    if (value >= palette.length) {
      throw new SchematicError(
        `its block data names palette entry ${value}, past the ${palette.length} it has`,
      )
    }
    volume.blocks[block] = value
    block += 1
    value = 0
    shift = 0
  }
  if (block !== volume.blocks.length) {
    throw new SchematicError(
      `its block data holds ${block} blocks, but its size is ${volume.blocks.length}`,
    )
  }

  const blockEntities = nbtList(container.BlockEntities ?? schematic.TileEntities)
  for (const tag of blockEntities) {
    const entity = nbtCompound(tag)
    const pos = entity?.Pos
    if (!entity || !(pos instanceof Int32Array) || pos.length !== 3) continue
    const [x, y, z] = pos as unknown as [number, number, number]
    if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) continue
    const { Pos: _pos, Id: id, Data: inner, ...rest } = entity
    const fields = version >= 3 ? (nbtCompound(inner) ?? {}) : rest
    const name = nbtString(id)
    volume.blockEntities.set(indexOf(size, x, y, z), name ? { ...fields, id: name } : { ...fields })
  }
  volume.entities = nbtList(schematic.Entities).length
  return volume
}

// ---- Litematica (.litematic) -----------------------------------------------

interface Region {
  name: string
  /** Its smallest corner, relative to the schematic's origin. */
  min: [number, number, number]
  size: [number, number, number]
  tag: CompoundTag
}

const vec = (tag: Tag | undefined): [number, number, number] | undefined => {
  const compound = nbtCompound(tag)
  const values = ['x', 'y', 'z'].map((key) => nbtNumber(compound?.[key]))
  return values.every((it) => it !== undefined) ? (values as [number, number, number]) : undefined
}

function readLitematic(data: CompoundTag): Volume {
  const regions: Region[] = []
  for (const [name, tag] of Object.entries(nbtCompound(data.Regions) ?? {})) {
    const region = nbtCompound(tag)
    const position = vec(region?.Position)
    const signed = vec(region?.Size)
    if (!region || !position || !signed)
      throw new SchematicError(`its region "${name}" has no position or size`)
    // A negative size reaches back from the position.
    const min = position.map((at, i) => (signed[i]! < 0 ? at + signed[i]! + 1 : at)) as [
      number,
      number,
      number,
    ]
    const size = signed.map(Math.abs) as [number, number, number]
    if (size.some((it) => it === 0)) continue
    regions.push({ name, min, size, tag: region })
  }
  if (regions.length === 0) throw new SchematicError('it has no regions with blocks in them')

  const low = [0, 1, 2].map((i) => Math.min(...regions.map((it) => it.min[i]!)))
  const high = [0, 1, 2].map((i) => Math.max(...regions.map((it) => it.min[i]! + it.size[i]! - 1)))
  const size = [0, 1, 2].map((i) => high[i]! - low[i]! + 1) as [number, number, number]

  // One palette for the whole box; the first entry of every region's palette is its air.
  const palette: string[] = []
  const byState = new Map<string, number>()
  const paletteIndex = (state: string) => {
    let at = byState.get(state)
    if (at === undefined) {
      at = palette.length
      palette.push(state)
      byState.set(state, at)
    }
    return at
  }
  const regionPalettes = regions.map((region) =>
    nbtList(region.tag.BlockStatePalette).map((entry) =>
      paletteIndex(stateOf(nbtCompound(entry) ?? {})),
    ),
  )
  if (palette.length === 0) throw new SchematicError('its regions have no block palettes')
  const volume = newVolume(
    'litematica',
    nbtNumber(data.MinecraftDataVersion) ?? null,
    size,
    palette,
  )
  // Where no region reaches, the first region's air (index 0 of its palette) fills the box.
  volume.blocks.fill(regionPalettes[0]![0] ?? 0)

  regions.forEach((region, r) => {
    const local = regionPalettes[r]!
    if (local.length === 0) return
    const states = region.tag.BlockStates
    if (!(states instanceof BigInt64Array))
      throw new SchematicError(`its region "${region.name}" has no block states`)
    const bits = Math.max(2, 32 - Math.clz32(local.length - 1))
    const count = region.size[0] * region.size[1] * region.size[2]
    if (states.length * 64 < count * bits) {
      throw new SchematicError(`its region "${region.name}" has too few block states for its size`)
    }
    // The longs as 32-bit words, low word first: bit k of the packed stream is bit (k % 32) of word k / 32.
    const words = new Uint32Array(states.buffer, states.byteOffset, states.length * 2)
    const mask = bits === 32 ? 0xffffffff : (1 << bits) - 1
    const [sx, sy, sz] = region.size
    const [ox, oy, oz] = [0, 1, 2].map((i) => region.min[i]! - low[i]!) as [number, number, number]
    let k = 0
    for (let y = 0; y < sy; y += 1) {
      for (let z = 0; z < sz; z += 1) {
        for (let x = 0; x < sx; x += 1) {
          const word = Math.floor(k / 32)
          const bit = k % 32
          let value = words[word]! >>> bit
          if (bit + bits > 32) value |= words[word + 1]! << (32 - bit)
          value = (value & mask) >>> 0
          k += bits
          if (value >= local.length) {
            throw new SchematicError(
              `its region "${region.name}" names palette entry ${value}, past the ${local.length} it has`,
            )
          }
          volume.blocks[indexOf(size, ox + x, oy + y, oz + z)] = local[value]!
        }
      }
    }
    for (const tag of nbtList(region.tag.TileEntities)) {
      const entity = nbtCompound(tag)
      const at = vec(entity)
      if (!entity || !at) continue
      const [x, y, z] = at
      if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue
      const { x: _x, y: _y, z: _z, ...fields } = entity
      volume.blockEntities.set(indexOf(size, ox + x, oy + y, oz + z), fields)
    }
    volume.entities += nbtList(region.tag.Entities).length
  })
  return volume
}
