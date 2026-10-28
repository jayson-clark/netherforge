/**
 * Pure edits for a terrain (`terrain/<id>.json`, docs/format/terrain.md): its named
 * entries (terrain noises, a biome area's own noises, 3D noises, caves, ores, decorations, biome areas), its
 * lists of layers, a noise's fields, the block a layer, the stone, the floor, an ore or a
 * decoration names, and the biome areas an ore, a cave or a decoration keeps to. Nothing here
 * knows React; the editor calls these through `edit`.
 */
import { setKey } from '@/core/draft'
import type {
  BiomeArea,
  Cave,
  Decoration,
  DensityNoise,
  Layer,
  NoiseDef,
  Ore,
  TerrainNoise,
  TerrainFile,
} from '@/core/format'
import { TERRAIN_DEFAULTS } from '@netherforge/format/constants'

/** A biome area's own terrain noises, as a collection: its 3D ones with [density]. */
export interface AreaNoises {
  area: string
  density?: boolean
}

/**
 * The collections of a generator that are kept by name; `densityNoises` are the file's 3D noises, `scriptNoises` the
 * ones its script asks for.
 */
export type Collection =
  | 'noises'
  | 'densityNoises'
  | 'caves'
  | 'ores'
  | 'decorations'
  | 'biomes'
  | 'scriptNoises'
  | AreaNoises

/** The collections whose entries keep to biome areas by name (their `biomes` lists). */
const FILTERED = ['caves', 'ores', 'decorations'] as const

/** Where a list of layers is: the file's `layers` or `underwater`, or a biome area's own. */
export interface LayerList {
  /** The biome area whose list it is; absent for the file's. */
  area?: string
  key: 'layers' | 'underwater'
}

/** `name`, or `name_2`, `name_3`… the first not in [taken]. */
export function freshName(taken: Iterable<string>, base: string): string {
  const names = new Set(taken)
  if (!names.has(base)) return base
  for (let n = 2; ; n += 1) if (!names.has(`${base}_${n}`)) return `${base}_${n}`
}

type Entries<T> = Record<string, T>

function entries(file: TerrainFile, collection: Collection, create: boolean): Entries<unknown> {
  if (typeof collection === 'object') {
    const area = file.biomes?.[collection.area]
    if (!area) return {}
    if (collection.density) {
      if (create) {
        area.terrain ??= {}
        area.terrain.density ??= {}
        area.terrain.density.noises ??= {}
      }
      return area.terrain?.density?.noises ?? {}
    }
    if (create) {
      area.terrain ??= {}
      area.terrain.noises ??= {}
    }
    return area.terrain?.noises ?? {}
  }
  if (collection === 'densityNoises') {
    if (create) {
      file.terrain ??= {}
      file.terrain.density ??= {}
      file.terrain.density.noises ??= {}
    }
    return file.terrain?.density?.noises ?? {}
  }
  if (collection === 'noises') {
    if (create) {
      file.terrain ??= {}
      file.terrain.noises ??= {}
    }
    return file.terrain?.noises ?? {}
  }
  if (collection === 'scriptNoises') {
    if (create) {
      file.script ??= {}
      file.script.noises ??= {}
    }
    return file.script?.noises ?? {}
  }
  if (create) file[collection] ??= {}
  return file[collection] ?? {}
}

/** Drops what an edit left empty, so the file doesn't hold `"caves": {}` and the like (the writer would keep it). */
function tidy(file: TerrainFile, collection: Collection): void {
  if (typeof collection === 'object') {
    // An area's terrain stays, even with nothing in it: having its own is a choice the inspector shows.
    const terrain = file.biomes?.[collection.area]?.terrain
    if (terrain?.noises && Object.keys(terrain.noises).length === 0) delete terrain.noises
    const density = terrain?.density
    if (density?.noises && Object.keys(density.noises).length === 0) delete density.noises
    return
  }
  if (collection === 'densityNoises') {
    // The density stays: a 3D ground with no noises of its own is still one (islands, an area's noises).
    const density = file.terrain?.density
    if (density?.noises && Object.keys(density.noises).length === 0) delete density.noises
    return
  }
  if (collection === 'noises') {
    if (file.terrain?.noises && Object.keys(file.terrain.noises).length === 0)
      delete file.terrain.noises
    if (file.terrain && Object.keys(file.terrain).length === 0) delete file.terrain
  } else if (collection === 'scriptNoises') {
    // The script itself stays: having one is what the file says, noises or not.
    if (file.script?.noises && Object.keys(file.script.noises).length === 0)
      delete file.script.noises
  } else if (file[collection] && Object.keys(file[collection]!).length === 0) {
    delete file[collection]
  }
}

/** The entries of [collection], in the file's order. */
export function namesOf(file: TerrainFile, collection: Collection): string[] {
  return Object.keys(entries(file, collection, false))
}

export function addEntry(
  file: TerrainFile,
  collection: Collection,
  name: string,
  value: TerrainNoise | DensityNoise | Cave | Ore | Decoration | BiomeArea | NoiseDef,
): void {
  entries(file, collection, true)[name] = value
}

/** Deletes an entry; a biome area deleted is taken out of every `biomes` list that named it. */
export function deleteEntry(file: TerrainFile, collection: Collection, name: string): void {
  delete entries(file, collection, false)[name]
  tidy(file, collection)
  if (collection === 'biomes') renameArea(file, name, null)
}

/** Every `biomes` list naming area [from] names [to] instead, or (null) no longer names it: an emptied list goes. */
function renameArea(file: TerrainFile, from: string, to: string | null): void {
  const filtered: { biomes?: string[] }[] = FILTERED.flatMap((collection) =>
    Object.values(file[collection] ?? {}),
  )
  const islands = file.terrain?.density?.islands
  if (islands) filtered.push(islands)
  for (const entry of filtered) {
    const list = entry.biomes
    if (!list?.includes(from)) continue
    const next =
      to === null ? list.filter((it) => it !== from) : list.map((it) => (it === from ? to : it))
    setKey(entry, 'biomes', next.length ? [...new Set(next)] : undefined)
  }
}

/** Renames an entry, keeping its place among the others. */
export function renameEntry(
  file: TerrainFile,
  collection: Collection,
  from: string,
  to: string,
): void {
  const map = entries(file, collection, false)
  if (!(from in map) || from === to || to in map) return
  const kept = Object.entries(map).map(([key, value]) => [key === from ? to : key, value] as const)
  for (const key of Object.keys(map)) delete map[key]
  for (const [key, value] of kept) map[key] = value
  if (collection === 'biomes') renameArea(file, from, to)
}

/** The new entry a collection starts with: everything it can leave out, left out. */
export function newEntry(
  collection: Collection,
  block: string,
): TerrainNoise | DensityNoise | Cave | Ore | Decoration | BiomeArea | NoiseDef {
  if (collection === 'scriptNoises') return { frequency: 0.01 }
  // A 3D noise starts as overhangs: a pattern a few blocks across, flattened into ledges.
  if (collection === 'densityNoises' || (typeof collection === 'object' && collection.density)) {
    return { noise: { frequency: 0.03, octaves: 2 }, amplitude: 8, squash: 2 }
  }
  if (typeof collection === 'object' || collection === 'noises') {
    return { noise: { frequency: 0.01, octaves: 3 }, amplitude: TERRAIN_DEFAULTS.amplitude }
  }
  switch (collection) {
    case 'caves':
      return {}
    case 'ores':
      return { block, size: TERRAIN_DEFAULTS.oreSize, veins: TERRAIN_DEFAULTS.oreVeins }
    case 'decorations':
      return { block }
    case 'biomes':
      return { biome: TERRAIN_DEFAULTS.biome }
  }
}

/** The terrain noise or 3D noise [name] of [collection] (the file's or a biome area's) in [file]. */
export function noiseEntry(
  file: TerrainFile,
  collection: 'noises' | 'densityNoises' | AreaNoises,
  name: string,
): (TerrainNoise & DensityNoise) | undefined {
  return entries(file, collection, false)[name] as (TerrainNoise & DensityNoise) | undefined
}

// ---- 3D terrain -----------------------------------------------------------------------------------

/**
 * Whether the ground is 3D (`terrain.density`): on is an empty density (no 3D noises yet: the same ground), off takes
 * it away with the islands and every area's own density, which a file without one can't have.
 */
export function setDensity(file: TerrainFile, on: boolean): void {
  if (on) {
    file.terrain ??= {}
    file.terrain.density ??= {}
    return
  }
  if (file.terrain) {
    delete file.terrain.density
    if (Object.keys(file.terrain).length === 0) delete file.terrain
  }
  for (const area of Object.values(file.biomes ?? {})) delete area.terrain?.density
}

/** Whether islands float over the ground: on starts them at their defaults. */
export function setIslands(file: TerrainFile, on: boolean): void {
  const density = file.terrain?.density
  if (!density) return
  if (on) density.islands ??= {}
  else delete density.islands
}

/** Whether a biome area has 3D noises of its own (its `terrain.density`); off uses the file's. */
export function setAreaDensity(file: TerrainFile, area: string, on: boolean): void {
  const target = file.biomes?.[area]
  if (!target) return
  if (on) {
    target.terrain ??= {}
    target.terrain.density ??= {}
  } else {
    delete target.terrain?.density
  }
}

/** Sets (or with `undefined`, removes) one field of a noise; a noise left with none is an empty object, which the writer keeps as it is. */
export function setNoiseField<K extends keyof NoiseDef>(
  noise: NoiseDef,
  key: K,
  value: NoiseDef[K] | undefined,
): void {
  setKey(noise, key, value)
}

/** The octave count a noise has now (1 when it says none). */
export const octavesOf = (noise: NoiseDef | undefined) => noise?.octaves ?? 1

// ---- layers -------------------------------------------------------------------------------------

function layersOf(file: TerrainFile, at: LayerList, create: boolean): Layer[] | undefined {
  if (at.area === undefined) {
    if (create) file[at.key] ??= []
    return file[at.key]
  }
  const area = file.biomes?.[at.area]
  if (!area) return undefined
  if (create) area[at.key] ??= []
  return area[at.key]
}

export function layersAt(file: TerrainFile, at: LayerList): Layer[] {
  return layersOf(file, at, false) ?? []
}

export function addLayer(file: TerrainFile, at: LayerList, block: string): void {
  layersOf(file, at, true)?.push({ block })
}

export function removeLayer(file: TerrainFile, at: LayerList, index: number): void {
  const list = layersOf(file, at, false)
  if (!list) return
  list.splice(index, 1)
  // A file's empty list is no list; a biome's is "no layers at all", which is a choice and stays.
  if (list.length === 0 && at.area === undefined) delete file[at.key]
}

export function moveLayer(file: TerrainFile, at: LayerList, from: number, to: number): void {
  const list = layersOf(file, at, false)
  if (!list || from === to || to < 0 || to >= list.length) return
  const [layer] = list.splice(from, 1)
  list.splice(to, 0, layer!)
}

export function setLayer(
  file: TerrainFile,
  at: LayerList,
  index: number,
  change: { block?: [BlockKind, string]; thickness?: number | undefined },
): void {
  const layer = layersOf(file, at, false)?.[index]
  if (!layer) return
  if (change.block !== undefined) setBlock(layer, change.block[0], change.block[1])
  if ('thickness' in change) setKey(layer, 'thickness', change.thickness)
}

/** Gives a biome area its own list (a copy of what it'd use), or takes its own away so it uses the file's. */
export function setAreaOwnLayers(
  file: TerrainFile,
  area: string,
  key: 'layers' | 'underwater',
  own: boolean,
): void {
  const target = file.biomes?.[area]
  if (!target) return
  if (!own) {
    delete target[key]
    return
  }
  target[key] = structuredClone(file[key] ?? [])
}

// ---- blocks --------------------------------------------------------------------------------------

/** Anything that names what it places: a vanilla block state, a block of the project's, or (a decoration) a structure. */
export interface BlockChoice {
  block?: string
  customBlock?: string
  structure?: string
}

/** Which of those ways. */
export type BlockKind = keyof BlockChoice

/** Which way [choice] names its block (a vanilla block when it names none). */
export const blockKind = (choice: BlockChoice | undefined): BlockKind =>
  choice?.structure !== undefined
    ? 'structure'
    : choice?.customBlock !== undefined
      ? 'customBlock'
      : 'block'

/** Names [choice]'s block one way and drops the others: a file names exactly one. An empty vanilla block is none. */
export function setBlock(choice: BlockChoice, kind: BlockKind, value: string): void {
  for (const key of ['block', 'customBlock', 'structure'] as const)
    if (key !== kind) delete choice[key]
  if (kind === 'block' && value === '') delete choice.block
  else choice[kind] = value
}

/** The file's stone named one way or another; the default (a vanilla block left empty) is no `stone` at all. */
export function setStone(file: TerrainFile, kind: BlockKind, value: string): void {
  file.stone ??= {}
  setBlock(file.stone, kind, value)
  if (Object.keys(file.stone).length === 0) delete file.stone
}

/** Whether an ore, a cave or a decoration keeps to biome area [area]; with none named it's in all of them. */
export function setInArea(entry: { biomes?: string[] }, area: string, on: boolean): void {
  const list = (entry.biomes ?? []).filter((it) => it !== area)
  if (on) list.push(area)
  setKey(entry, 'biomes', list.length ? list : undefined)
}

// ---- ores ----------------------------------------------------------------------------------------

export const oreBlockKind = (ore: Ore): BlockKind => blockKind(ore)

/** Names the ore's block one way or the other, and drops the other: an ore has exactly one. */
export function setOreBlock(ore: Ore, kind: 'block' | 'customBlock', value: string): void {
  for (const key of ['block', 'customBlock'] as const) if (key !== kind) delete ore[key]
  ore[kind] = value
}

/** The block ids of [ids] that read as ores (what a new ore starts as), the first of them or [fallback]. */
export function firstOre(ids: readonly string[], fallback: string): string {
  return ids.find((id) => id.endsWith('_ore')) ?? fallback
}

/**
 * Whether the file hands stages to its script (`terrain/<id>.lua`): on is an empty `script` (everything left to
 * its defaults), off takes away what it declared too. The `.lua` file is the caller's to make or keep.
 */
export function setScripted(file: TerrainFile, on: boolean): void {
  if (on) file.script ??= {}
  else delete file.script
}

/** The script's `blocks` or `customBlocks`, a list; empty takes the key away. */
export function setScriptBlocks(
  file: TerrainFile,
  key: 'blocks' | 'customBlocks' | 'loot',
  ids: string[] | undefined,
): void {
  if (!file.script) return
  setKey(file.script, key, ids?.length ? ids : undefined)
}
