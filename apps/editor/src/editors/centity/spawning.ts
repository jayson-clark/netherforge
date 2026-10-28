/**
 * Edits to a centity's `spawning` block (docs/format/centity.md), free of
 * React like the rest of `ops.ts`: the inspector's section calls these
 * through `workspace.edit`, and the tests call them on plain objects.
 */
import type { CentityFile, MoonPhase, SpawnRange, SpawnTime, SpawnWeather } from '@/core/format'

/** The lists a `spawning` block has, edited as comma-separated text. */
export type SpawningList = 'worlds' | 'biomes' | 'blocks'

/** The ranges it has, each `{ min?, max? }`. */
export type SpawningRange = 'light' | 'height' | 'group'

/** The whole numbers it has. */
export type SpawningNumber = 'weight' | 'cap' | 'despawnDistance'

/** Words typed in a box (commas, spaces or lines between them) as a list; none at all is `undefined`, so the key goes. */
export function parseList(text: string): string[] | undefined {
  const words = text.split(/[\s,]+/).filter((word) => word !== '')
  return words.length === 0 ? undefined : words
}

export const formatList = (list: readonly string[] | undefined): string => (list ?? []).join(', ')

/** Turns natural spawning on (an empty block: anywhere a player is) or off. */
export function setSpawning(file: CentityFile, on: boolean): void {
  if (on) file.spawning ??= {}
  else delete file.spawning
}

export function setList(file: CentityFile, key: SpawningList, text: string): void {
  const spawning = file.spawning
  if (!spawning) return
  const list = parseList(text)
  if (list) spawning[key] = list
  else delete spawning[key]
}

export function setNumber(file: CentityFile, key: SpawningNumber, value: number | undefined): void {
  const spawning = file.spawning
  if (!spawning) return
  if (value === undefined) delete spawning[key]
  else spawning[key] = Math.round(value)
}

/** Whether a player clicking a natural one keeps it; off leaves the key out (it's the default). */
export function setKeepOnInteract(file: CentityFile, on: boolean): void {
  const spawning = file.spawning
  if (!spawning) return
  if (on) spawning.keepOnInteract = true
  else delete spawning.keepOnInteract
}

/** One end of a range; a range with neither end is no range, and its key goes. */
export function setRangeEnd(
  file: CentityFile,
  key: SpawningRange,
  end: keyof SpawnRange,
  value: number | undefined,
): void {
  const spawning = file.spawning
  if (!spawning) return
  const range: SpawnRange = { ...spawning[key] }
  if (value === undefined) delete range[end]
  else range[end] = Math.round(value)
  if (range.min === undefined && range.max === undefined) delete spawning[key]
  else spawning[key] = range
}

/** How the time of day is given: not at all, a half of the day, or a range of ticks. */
export type SpawnTimeMode = 'any' | 'day' | 'night' | 'range'

export function timeMode(time: SpawnTime | undefined): SpawnTimeMode {
  if (time === undefined) return 'any'
  return typeof time === 'string' ? time : 'range'
}

/** Switches how the time is given; a range starts at night's ticks, so it means something at once. */
export function setTimeMode(file: CentityFile, mode: SpawnTimeMode): void {
  const spawning = file.spawning
  if (!spawning) return
  if (mode === 'any') delete spawning.time
  else if (mode === 'range')
    spawning.time = typeof spawning.time === 'object' ? spawning.time : { min: 13000, max: 22999 }
  else spawning.time = mode
}

/** One end of a time range: either end may go, but a range with neither is no time at all. */
export function setTimeEnd(file: CentityFile, end: 'min' | 'max', value: number | undefined): void {
  const spawning = file.spawning
  if (!spawning) return
  const range = typeof spawning.time === 'object' ? { ...spawning.time } : {}
  if (value === undefined) delete range[end]
  else range[end] = Math.round(value)
  if (range.min === undefined && range.max === undefined) delete spawning.time
  else spawning.time = range
}

/** Ticks one end of a time range is at, if the time is a range. */
export function timeEnd(time: SpawnTime | undefined, end: 'min' | 'max'): number | undefined {
  return typeof time === 'object' ? time[end] : undefined
}

/** Adds or takes away one value of a list of choices (moon phases, weather); an empty list goes. */
export function toggleChoice<K extends 'moonPhase' | 'weather'>(
  file: CentityFile,
  key: K,
  value: K extends 'moonPhase' ? MoonPhase : SpawnWeather,
  on: boolean,
): void {
  const spawning = file.spawning
  if (!spawning) return
  const current = (spawning[key] ?? []) as string[]
  const next = on ? [...new Set([...current, value])] : current.filter((it) => it !== value)
  if (next.length === 0) delete spawning[key]
  else (spawning as Record<string, unknown>)[key] = next
}

/** The script condition's name; an empty box removes it. */
export function setCondition(file: CentityFile, name: string): void {
  const spawning = file.spawning
  if (!spawning) return
  const trimmed = name.trim()
  if (trimmed === '') delete spawning.when
  else spawning.when = trimmed
}
