/**
 * Pure edits for a dimension type (`dimension_types/<id>.json`, docs/format/dimension-type.md):
 * every key is optional, and a cleared value or an emptied part goes, so the
 * canonical file holds only what was said.
 */
import { setKey } from '@/core/draft'
import {
  DIMENSION_TYPE_DEFAULTS,
  type DimensionTypeColors,
  type DimensionTypeFile,
  type LightRange,
} from '@/core/format'

/** The keys of the file that are a plain value (not the colours or the light range). */
export type DimensionValueKey = Exclude<
  keyof DimensionTypeFile,
  '$schema' | 'colors' | 'monsterSpawnLight'
>

/** Sets one plain key, or removes it for `undefined`. */
export function setValue<K extends DimensionValueKey>(
  dimension: DimensionTypeFile,
  key: K,
  value: DimensionTypeFile[K] | undefined,
): void {
  setKey(dimension, key, value)
}

/** Sets one colour; colours left empty go. */
export function setColor(
  dimension: DimensionTypeFile,
  key: keyof DimensionTypeColors,
  value: string | undefined,
): void {
  const colors: DimensionTypeColors = { ...(dimension.colors ?? {}) }
  setKey(colors, key, value?.trim() || undefined)
  if (Object.keys(colors).length === 0) delete dimension.colors
  else dimension.colors = colors
}

/** Sets one end of the monster spawn light range; a range left empty goes. */
export function setMonsterLight(
  dimension: DimensionTypeFile,
  end: keyof LightRange,
  value: number | undefined,
): void {
  const range: LightRange = { ...(dimension.monsterSpawnLight ?? {}) }
  setKey(range, end, value === undefined ? undefined : Math.round(value))
  if (Object.keys(range).length === 0) delete dimension.monsterSpawnLight
  else dimension.monsterSpawnLight = range
}

/** The lowest block and the highest a world of [dimension] holds, defaults filled in. */
export function blockRange(dimension: DimensionTypeFile): { lowest: number; highest: number } {
  const lowest = dimension.minY ?? DIMENSION_TYPE_DEFAULTS.minY
  return { lowest, highest: lowest + (dimension.height ?? DIMENSION_TYPE_DEFAULTS.height) - 1 }
}
