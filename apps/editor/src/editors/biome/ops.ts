/**
 * Pure edits for a biome (`biomes/<id>.json`, docs/format/biome.md): every
 * part the file can leave out goes when it's emptied, so the canonical file
 * holds only what was said.
 */
import type {
  AdditionsSound,
  BiomeClimate,
  BiomeColors,
  BiomeFile,
  BiomeMusic,
  BiomeSounds,
  BiomeSpawn,
  GenerationStep,
  MoodSound,
  SpawnCategory,
  SpawnCost,
} from '@/core/format'

/** Sets [key] of [target], or removes it when [value] is undefined. */
function put<T extends object, K extends keyof T>(target: T, key: K, value: T[K] | undefined) {
  if (value === undefined) delete target[key]
  else target[key] = value
}

/** Sets one part of the climate; a climate left empty goes. */
export function setClimate<K extends keyof BiomeClimate>(
  biome: BiomeFile,
  key: K,
  value: BiomeClimate[K] | undefined,
): void {
  const climate: BiomeClimate = { ...(biome.climate ?? {}) }
  put(climate, key, value)
  if (Object.keys(climate).length === 0) delete biome.climate
  else biome.climate = climate
}

/** Sets one colour (or the grass modifier); colours left empty go. */
export function setColor<K extends keyof BiomeColors>(
  biome: BiomeFile,
  key: K,
  value: BiomeColors[K] | undefined,
): void {
  const colors: BiomeColors = { ...(biome.colors ?? {}) }
  put(colors, key, value)
  if (Object.keys(colors).length === 0) delete biome.colors
  else biome.colors = colors
}

type SoundPart = Exclude<keyof BiomeSounds, 'ambient'>
type SoundOf<K extends SoundPart> = K extends 'mood'
  ? MoodSound
  : K extends 'additions'
    ? AdditionsSound
    : BiomeMusic

/** Sets the looping ambient sound; sounds left empty go. */
export function setAmbient(biome: BiomeFile, sound: string | undefined): void {
  const sounds: BiomeSounds = { ...(biome.sounds ?? {}) }
  put(sounds, 'ambient', sound || undefined)
  setSounds(biome, sounds)
}

/** Turns one of the sounds with settings on (with [sound]) or off. */
export function toggleSound(biome: BiomeFile, part: SoundPart, sound: string | undefined): void {
  const sounds: BiomeSounds = { ...(biome.sounds ?? {}) }
  if (sound === undefined) delete sounds[part]
  else if (part === 'additions') sounds.additions = { sound, chance: 0.01 }
  else sounds[part] = { sound }
  setSounds(biome, sounds)
}

/** Changes the settings of one of the sounds that has them, when it's on. */
export function changeSound<K extends SoundPart>(
  biome: BiomeFile,
  part: K,
  change: (sound: SoundOf<K>) => void,
): void {
  const current = biome.sounds?.[part] as SoundOf<K> | undefined
  if (!current) return
  const next = { ...current }
  change(next)
  biome.sounds = { ...biome.sounds, [part]: next }
}

function setSounds(biome: BiomeFile, sounds: BiomeSounds) {
  if (Object.keys(sounds).length === 0) delete biome.sounds
  else biome.sounds = sounds
}

/** Adds a spawn of [entity] to [category]. */
export function addSpawn(biome: BiomeFile, category: SpawnCategory, entity: string): void {
  const spawns = { ...(biome.spawns ?? {}) }
  spawns[category] = [...(spawns[category] ?? []), { entity }]
  biome.spawns = spawns
}

/** Changes one spawn; a group of one (or none) is written as no group. */
export function changeSpawn(
  biome: BiomeFile,
  category: SpawnCategory,
  index: number,
  change: (spawn: BiomeSpawn) => void,
): void {
  const list = biome.spawns?.[category]
  if (!list?.[index]) return
  const spawn = { ...list[index] }
  change(spawn)
  if (spawn.group && spawn.group.min === undefined && spawn.group.max === undefined) {
    delete spawn.group
  }
  const next = [...list]
  next[index] = spawn
  biome.spawns = { ...biome.spawns, [category]: next }
}

/** Removes one spawn; a category left empty goes, and spawns left empty go. */
export function removeSpawn(biome: BiomeFile, category: SpawnCategory, index: number): void {
  const list = biome.spawns?.[category]
  if (!list) return
  const spawns = { ...biome.spawns }
  const next = list.filter((_, at) => at !== index)
  if (next.length === 0) delete spawns[category]
  else spawns[category] = next
  if (Object.keys(spawns).length === 0) delete biome.spawns
  else biome.spawns = spawns
}

/** Sets (or with nothing, removes) the spawn cost of an entity type. */
export function setSpawnCost(biome: BiomeFile, entity: string, cost: SpawnCost | undefined): void {
  const costs = { ...(biome.spawnCosts ?? {}) }
  put(costs, entity, cost)
  if (Object.keys(costs).length === 0) delete biome.spawnCosts
  else biome.spawnCosts = costs
}

/** Adds a feature at the end of [step]'s list. */
export function addFeature(biome: BiomeFile, step: GenerationStep, feature: string): void {
  const features = { ...(biome.features ?? {}) }
  features[step] = [...(features[step] ?? []), feature]
  biome.features = features
}

/** Removes a step's feature; a step left empty goes, and features left empty go. */
export function removeFeature(biome: BiomeFile, step: GenerationStep, index: number): void {
  const list = biome.features?.[step]
  if (!list) return
  const features = { ...biome.features }
  const next = list.filter((_, at) => at !== index)
  if (next.length === 0) delete features[step]
  else features[step] = next
  if (Object.keys(features).length === 0) delete biome.features
  else biome.features = features
}

/** Moves a step's feature one place earlier (-1) or later (1): the order the game places them in. */
export function moveFeature(
  biome: BiomeFile,
  step: GenerationStep,
  index: number,
  by: -1 | 1,
): void {
  const list = biome.features?.[step]
  const to = index + by
  if (!list || to < 0 || to >= list.length) return
  const next = [...list]
  ;[next[index], next[to]] = [next[to]!, next[index]!]
  biome.features = { ...biome.features, [step]: next }
}

/**
 * The game's registries whose entries the project's own files can name besides
 * the game's, as `ProjectOutline.registryNames` keys them: a project biome or
 * one its datapacks define, a placed feature of its datapacks.
 */
export const BIOME_FOLDER = 'worldgen/biome'
export const PLACED_FEATURE_FOLDER = 'worldgen/placed_feature'

/**
 * What a biome (or placed feature) field may name, for suggestions: the
 * project's own by id first (a plain id is always the project's), then the
 * game's in full.
 */
export function biomeChoices(project: readonly string[], game: readonly string[]): string[] {
  return [...[...project].sort(), ...game]
}
