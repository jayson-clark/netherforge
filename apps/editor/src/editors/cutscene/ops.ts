/**
 * Pure edits to a cutscene document, applied to Immer drafts through
 * `Workspace.edit` (one undo step each). Keys are kept in time order by the
 * shared timeline's `keys` helpers; the camera's two tracks and the cues are
 * three rows of the timeline.
 */
import type { Cue, CutsceneFile, Easing, RotationKey, Vec3 } from '@/core/format'
import * as keys from '@/editors/shared/timeline/keys'
import { roundSeconds } from '@/editors/shared/timeline/time'

/** The timeline's rows: the camera's position and rotation, and the cues. */
export const TRACKS = ['position', 'rotation', 'cues'] as const
export type Track = (typeof TRACKS)[number]

/** A key of one row: what's selected, and what the inspector shows. */
export interface CutscenePick {
  track: Track
  index: number
}

/** Keys within this many seconds of a new one are the same key (a tick is 0.05). */
const SAME_TIME = 0.025

/** What a new cue is named: a placeholder the author renames. */
export const NEW_CUE_EVENT = 'cue'

type Key = { time: number; easing?: Easing }

/** Seconds the cutscene lasts: its `length`, or the last key's or cue's time (format's own rule). */
export function lengthOf(model: CutsceneFile): number {
  if (model.length !== undefined) return model.length
  const times = [
    ...(model.camera?.position ?? []),
    ...(model.camera?.rotation ?? []),
    ...(model.cues ?? []),
  ].map((it) => it.time)
  return times.length ? Math.max(...times) : 0
}

/** The row's keys (the cue row's are the cues themselves). */
export function keysOf(model: CutsceneFile, track: Track): readonly Key[] {
  return track === 'cues' ? (model.cues ?? []) : (model.camera?.[track] ?? [])
}

/** The row's list in [draft], made when there's none. */
function listOf(draft: CutsceneFile, track: Track): Key[] {
  if (track === 'cues') return (draft.cues ??= [])
  draft.camera ??= {}
  return (draft.camera[track] ??= [])
}

/** Drops a row's list, and the camera's object, once they're empty (what's written leaves them out anyway). */
function tidy(draft: CutsceneFile, track: Track) {
  if (track === 'cues') {
    if (draft.cues?.length === 0) delete draft.cues
    return
  }
  if (draft.camera?.[track]?.length === 0) delete draft.camera[track]
  if (draft.camera && Object.keys(draft.camera).length === 0) delete draft.camera
}

/** Keys the camera at [time] with [value] (replacing a key already there); returns the key's index. */
export function setPosition(draft: CutsceneFile, time: number, value: Vec3): number {
  draft.camera ??= {}
  const list = (draft.camera.position ??= [])
  return keys.putKey(
    list,
    { time: roundSeconds(time), value },
    { same: SAME_TIME, merge: (existing) => ({ ...existing, value }) },
  )
}

/** Keys the camera's look at [time] (replacing a key already there); returns the key's index. */
export function setRotation(draft: CutsceneFile, time: number, yaw: number, pitch: number): number {
  draft.camera ??= {}
  const list = (draft.camera.rotation ??= [])
  const key: RotationKey = { time: roundSeconds(time), yaw, pitch }
  return keys.putKey(list, key, {
    same: SAME_TIME,
    merge: (existing) => ({ ...existing, yaw, pitch }),
  })
}

/** A cue at [time], which names an event of its own to be renamed; returns its index. */
export function addCue(draft: CutsceneFile, time: number): number {
  const list = (draft.cues ??= [])
  const cue: Cue = { time: roundSeconds(time), event: NEW_CUE_EVENT }
  return keys.putKey(list, cue, { same: -1 })
}

/** Moves a key in time (never before 0); returns its index after the re-sort, so a drag keeps hold of it. */
export function moveKey(draft: CutsceneFile, track: Track, index: number, time: number): number {
  const at = keys.moveKey(listOf(draft, track), index, Math.max(0, roundSeconds(time)))
  return at < 0 ? index : at
}

export function deleteKey(draft: CutsceneFile, track: Track, index: number) {
  keys.removeKey(listOf(draft, track), index)
  tidy(draft, track)
}

/** Sets the easing of the segment leaving a position or rotation key. */
export function setEasing(draft: CutsceneFile, track: Track, index: number, easing: Easing) {
  const key = listOf(draft, track)[index]
  if (key) keys.setEasing(key, easing)
}

/** Changes a cue: a field set to `undefined` is taken out. */
export function setCue(
  draft: CutsceneFile,
  index: number,
  patch: Partial<Pick<Cue, 'event' | 'text' | 'duration'>>,
) {
  const cue = draft.cues?.[index]
  if (!cue) return
  for (const [field, value] of Object.entries(patch) as [keyof typeof patch, unknown][]) {
    if (value === undefined || value === '') delete cue[field]
    else (cue as unknown as Record<string, unknown>)[field] = value
  }
}

/** Sets the length; `undefined` leaves it to the last key or cue. */
export function setLength(draft: CutsceneFile, length: number | undefined) {
  if (length === undefined || length <= 0) delete draft.length
  else draft.length = roundSeconds(length)
}

/** What a key's accessible name says: `Position key 1`. */
export const keyLabel = (track: Track, index: number) =>
  `${track === 'cues' ? 'Cue' : track === 'position' ? 'Position key' : 'Rotation key'} ${index + 1}`

/** Where the camera looks, as a unit vector in the world: Minecraft's yaw 0 faces +Z, 90 faces -X, positive pitch looks down. */
export function lookDirection(yaw: number, pitch: number): [number, number, number] {
  const y = (yaw * Math.PI) / 180
  const p = (pitch * Math.PI) / 180
  return [-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)]
}

/**
 * [points] seen from above (north up: x across, z down), fitted into a [size] square with [pad] around it and
 * the path's shape kept: each point's `[x, y]` in that square. A path that doesn't move sideways is a dot in the
 * middle.
 */
export function topDown(points: Vec3[], size: number, pad: number): [number, number][] {
  if (points.length === 0) return []
  let minX = Infinity
  let maxX = -Infinity
  let minZ = Infinity
  let maxZ = -Infinity
  for (const [x, , z] of points) {
    minX = Math.min(minX, x)
    maxX = Math.max(maxX, x)
    minZ = Math.min(minZ, z)
    maxZ = Math.max(maxZ, z)
  }
  const span = Math.max(maxX - minX, maxZ - minZ)
  const scale = span > 0 ? (size - 2 * pad) / span : 0
  const offsetX = (size - (maxX - minX) * scale) / 2
  const offsetY = (size - (maxZ - minZ) * scale) / 2
  return points.map(([x, , z]) => [offsetX + (x - minX) * scale, offsetY + (z - minZ) * scale])
}
