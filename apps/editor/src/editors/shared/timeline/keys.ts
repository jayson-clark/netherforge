/**
 * Edits to a track's keys, whatever a key holds besides its time and
 * easing (a centity's vector, a particle curve's number or colour): kept in
 * time order, a key added at a time that has one replaces it, a moved key
 * is found again after the re-sort so a drag keeps hold of it, and linear
 * easing is written by leaving it out. The editors' ops (`centity/ops.ts`,
 * `particles/ops.ts`) apply these to their tracks; they work on Immer
 * drafts and plain arrays alike.
 */
import type { Easing } from '@/core/format'

/** What every key has. */
export interface TimedKey {
  time: number
  easing?: Easing
}

const byTime = (a: TimedKey, b: TimedKey) => a.time - b.time

/**
 * Puts [key] in [keys] in time order, or, where a key is within [same] of
 * its time, replaces that one with what [merge] makes of it (by default,
 * [key] itself). Returns the key's index.
 */
export function putKey<K extends TimedKey>(
  keys: K[],
  key: K,
  { same = 0, merge = () => key }: { same?: number; merge?: (existing: K) => K } = {},
): number {
  const at = keys.findIndex((it) => Math.abs(it.time - key.time) <= same)
  if (at >= 0) {
    keys[at] = merge(keys[at]!)
    return at
  }
  keys.push(key)
  keys.sort(byTime)
  return keys.indexOf(key)
}

/** Moves key [index] to [time]; returns where it is after the re-sort (−1 for no such key). */
export function moveKey<K extends TimedKey>(keys: K[], index: number, time: number): number {
  const key = keys[index]
  if (!key) return -1
  const moved = { ...key, time }
  keys[index] = moved
  keys.sort(byTime)
  return keys.indexOf(moved)
}

/** Removes key [index]; returns it (undefined for no such key). */
export function removeKey<K extends TimedKey>(keys: K[], index: number): K | undefined {
  return keys.splice(index, 1)[0]
}

/** Sets a key's easing; linear, the default, by leaving it out. */
export function setEasing(key: TimedKey, easing: Easing) {
  if (easing === 'linear') delete key.easing
  else key.easing = easing
}
