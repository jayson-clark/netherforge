/**
 * What a map (`maps/<id>/`) is, read from its files: the name,
 * spawn and data version in `level.dat`, the seed and game rules of the
 * world copies are made from, and which dimensions it has. Pure.
 *
 * Since Minecraft 26.1 a world folder keeps most of this per dimension
 * (`dimensions/<namespace>/<path>/data/minecraft/world_gen_settings.dat`,
 * `game_rules.dat`); older saves had it all in `level.dat`. Both are read.
 */
import { Float32, Int32, Int8, NBTData, TAG, getTagType, type CompoundTag, type Tag } from 'nbtify'
import {
  nbtAt,
  nbtCompound,
  nbtNumber,
  nbtString,
  nbtVec3,
  type NbtRoot,
} from '@netherforge/structures/nbt'

/**
 * The dimension `nf.worlds.copy` makes a world from: Paper imports a world
 * folder's overworld (the runtime loads copies as normal worlds), and a
 * captured world is put there. A path in the world folder's layout, like
 * `level.dat`, not a game id.
 */
export const MAP_DIMENSION = 'dimensions/minecraft/overworld'
/** Where a dimension keeps its saved data. */
const SAVED_DATA = 'data/minecraft'
export const WORLD_GEN_FILE = `${SAVED_DATA}/world_gen_settings.dat`
export const GAME_RULES_FILE = `${SAVED_DATA}/game_rules.dat`

export interface GameRule {
  rule: string
  value: string
}

export interface WorldInfo {
  name: string | null
  /** The Minecraft data version that last saved it. */
  dataVersion: number | null
  /** The Minecraft version's name, when the file says. */
  version: string | null
  /** A long: text, since it doesn't fit a double. */
  seed: string | null
  spawn: [number, number, number] | null
  gameRules: GameRule[]
}

/** A tag as text for the screen: numbers and text as they are, bytes in game rules as true/false. */
function ruleValue(tag: Tag | undefined): string {
  if (tag instanceof Int8) return tag.valueOf() === 0 ? 'false' : 'true'
  if (typeof tag === 'string' || typeof tag === 'bigint') return tag.toString()
  const number = nbtNumber(tag)
  if (number !== undefined) return String(number)
  // A compound, list or array: its kind, as there's no one value to show.
  return tag === undefined ? '' : TAG[getTagType(tag)].toLowerCase()
}

function rulesOf(compound: CompoundTag | undefined): GameRule[] {
  return Object.entries(compound ?? {})
    .map(([rule, tag]) => ({ rule, value: ruleValue(tag) }))
    .sort((a, b) => a.rule.localeCompare(b.rule))
}

/**
 * The map's facts from [level] (`level.dat`) and, when present, its
 * world's [worldGen] and [gameRules] files (26.1 and later).
 */
export function readWorldInfo(level: NbtRoot, worldGen?: NbtRoot, gameRules?: NbtRoot): WorldInfo {
  const data = nbtCompound(level.data.Data) ?? level.data
  const seedTag =
    nbtAt(worldGen?.data, 'data', 'seed') ??
    nbtAt(data, 'WorldGenSettings', 'seed') ??
    nbtAt(data, 'RandomSeed')
  const spawn =
    nbtVec3(nbtAt(data, 'spawn', 'pos')) ??
    (() => {
      const xyz = ['SpawnX', 'SpawnY', 'SpawnZ'].map((key) => nbtNumber(data[key]))
      return xyz.every((it) => it !== undefined) ? (xyz as [number, number, number]) : null
    })()
  const rules = gameRules
    ? rulesOf(nbtCompound(gameRules.data.data))
    : rulesOf(nbtCompound(data.GameRules))
  return {
    name: nbtString(data.LevelName) ?? null,
    dataVersion: nbtNumber(data.DataVersion) ?? nbtNumber(nbtAt(data, 'Version', 'Id')) ?? null,
    version: nbtString(nbtAt(data, 'Version', 'Name')) ?? null,
    seed:
      typeof seedTag === 'bigint' ? seedTag.toString() : (nbtNumber(seedTag)?.toString() ?? null),
    spawn,
    gameRules: rules,
  }
}

/**
 * The dimensions a world folder has, from its file list (paths inside the
 * folder): `dimensions/<namespace>/<path>/` as `<namespace>:<path>`, and an
 * older save's `region/`, `DIM-1/`, `DIM1/` by their folder names.
 */
export function dimensionsOf(relativePaths: string[]): string[] {
  const found = new Set<string>()
  for (const path of relativePaths) {
    const parts = path.split('/')
    if (parts[0] === 'dimensions' && parts.length >= 4) found.add(`${parts[1]}:${parts[2]}`)
    else if (parts.length >= 2 && (parts[0] === 'region' || /^DIM-?\d+$/.test(parts[0]!)))
      found.add(`${parts[0]}/ (older layout)`)
  }
  return [...found].sort()
}

/**
 * [level] with its spawn moved to [spawn]: a captured world that isn't the
 * server's main one shares the main world's `level.dat`, whose spawn is the
 * main world's, and that's where a copy's spawn comes from.
 */
export function withSpawn(
  level: NbtRoot,
  spawn: { x: number; y: number; z: number; yaw?: number; pitch?: number },
): NbtRoot {
  const data = nbtCompound(level.data.Data)
  if (!data) return level
  const next: CompoundTag = { ...data }
  const current = nbtCompound(data.spawn)
  if (current || !('SpawnX' in data)) {
    next.spawn = {
      ...current,
      pos: Int32Array.from([spawn.x, spawn.y, spawn.z]),
      yaw: new Float32(spawn.yaw ?? 0),
      pitch: new Float32(spawn.pitch ?? 0),
    }
  } else {
    next.SpawnX = new Int32(spawn.x)
    next.SpawnY = new Int32(spawn.y)
    next.SpawnZ = new Int32(spawn.z)
  }
  // Written back as it was read: same root name, same compression.
  const moved = new NBTData(level)
  moved.data = { ...level.data, Data: next }
  return moved
}
