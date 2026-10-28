/**
 * Edits to a particle effect, as functions that mutate a draft (the store
 * hands them an Immer draft via `workspace.edit`). The emitter list, the
 * inspector and the timeline share these, so "switch to a ring" or "turn a
 * constant into a curve" means one tested thing.
 *
 * Every edit keeps the file saying one thing: a curve replaces its constant
 * (except `rate`, which marks the emitter as rate-driven), and fields the
 * particle or shape doesn't take go. Defaults and limits come from format's
 * generated constants.
 */
import { freshName } from '@/editors/centity/ops'
import * as keys from '@/editors/shared/timeline/keys'
import {
  PARTICLE_DEFAULTS,
  PARTICLE_LIMITS,
  PARTICLE_OPTIONS,
  type ColorKey,
  type Curves,
  type Easing,
  type EmitterDef,
  type EmitterShape,
  type NumberKey,
  type ParticleDataKind,
  type ParticleEffectFile,
} from '@/core/format'

export type Channel = keyof Curves
export const CHANNELS: Channel[] = ['rate', 'size', 'speed', 'radius', 'color']

export type ShapeType = EmitterShape['type']
export const SHAPE_TYPES: ShapeType[] = ['point', 'line', 'ring', 'disc', 'sphere', 'box']

export type DataField = 'color' | 'toColor' | 'size' | 'blockState' | 'item'
export const DATA_FIELDS: DataField[] = ['color', 'toColor', 'size', 'blockState', 'item']

/**
 * The data fields a particle of [kind] takes; every field when the kind isn't
 * known (no game data yet), so nothing the file says is hidden.
 */
export function fieldsTaken(kind: ParticleDataKind | null): DataField[] {
  if (kind === null) return DATA_FIELDS
  return PARTICLE_OPTIONS[kind].takes as DataField[]
}

/** The particle's data kind from the server's export, or null when it isn't known. */
export function kindOf(
  particles: Record<string, ParticleDataKind> | null | undefined,
  id: string,
): ParticleDataKind | null {
  if (!particles) return null
  return particles[id.includes(':') ? id : `minecraft:${id}`] ?? null
}

export const shapeOf = (emitter: EmitterDef): EmitterShape => emitter.shape ?? { type: 'point' }

export const hasRadius = (shape: EmitterShape) =>
  shape.type === 'ring' || shape.type === 'disc' || shape.type === 'sphere'

/** Whether `distribution: "even"` means anything for [shape]. */
export const evenAllowed = (shape: EmitterShape) =>
  shape.type === 'line' || shape.type === 'ring' || (shape.type === 'sphere' && !!shape.surface)

export const spinAllowed = (shape: EmitterShape) =>
  shape.type === 'ring' || shape.type === 'disc' || shape.type === 'line'

/** The curve channels [emitter] can have, given its particle's [kind]. */
export function channelsFor(emitter: EmitterDef, kind: ParticleDataKind | null): Channel[] {
  const taken = fieldsTaken(kind)
  return CHANNELS.filter((channel) => {
    switch (channel) {
      case 'rate':
        return emitter.rate !== undefined
      case 'size':
        return taken.includes('size')
      case 'speed':
        return true
      case 'radius':
        return hasRadius(shapeOf(emitter))
      case 'color':
        return taken.includes('color')
    }
  })
}

const hasKeys = (keys: unknown[] | undefined): boolean => (keys?.length ?? 0) > 0

/** Whether [channel] has keys: the field then shows "curve" instead of its value. */
export const isCurved = (emitter: EmitterDef, channel: Channel) =>
  hasKeys(emitter.curves?.[channel])

// ---- emitters -------------------------------------------------------------------------------------

/** A particle (or item) id as the game writes it: `minecraft:end_rod`, or bare `end_rod`. */
export const PARTICLE_ID = /^([a-z0-9_.-]+:)?[a-z0-9_./-]+$/

/** Adds an emitter playing [particle], one point at tick 0; returns its name. */
export function addEmitter(draft: ParticleEffectFile, particle: string, base = 'emitter'): string {
  draft.emitters ??= {}
  const name = freshName(Object.keys(draft.emitters), base)
  draft.emitters[name] = { particle, burst: 1 }
  return name
}

export function renameEmitter(draft: ParticleEffectFile, from: string, to: string) {
  const emitters = draft.emitters
  if (!emitters?.[from] || from === to || to in emitters) return
  emitters[to] = emitters[from]!
  delete emitters[from]
}

/** A copy of [name] under a fresh name; returns the new name. */
export function duplicateEmitter(draft: ParticleEffectFile, name: string): string | null {
  const emitter = draft.emitters?.[name]
  if (!emitter) return null
  const copy = freshName(Object.keys(draft.emitters!), name)
  // A plain copy: [emitter] is a draft proxy, which structuredClone can't take.
  draft.emitters![copy] = JSON.parse(JSON.stringify(emitter)) as EmitterDef
  return copy
}

export function removeEmitter(draft: ParticleEffectFile, name: string) {
  if (!draft.emitters?.[name]) return
  delete draft.emitters[name]
  if (Object.keys(draft.emitters).length === 0) delete draft.emitters
}

/** What a required data field starts as when a particle needs one: picked by the caller (from game data). */
export interface RequiredDefaults {
  blockState?: string
  item?: string
}

/**
 * Switches the particle. With its [kind] known, data fields (and their
 * curves) it doesn't take go, and a field it can't do without is added from
 * [defaults] when one is given.
 */
export function setParticle(
  emitter: EmitterDef,
  id: string,
  kind: ParticleDataKind | null,
  defaults: RequiredDefaults = {},
) {
  emitter.particle = id
  if (kind === null) return
  const taken = fieldsTaken(kind)
  for (const field of DATA_FIELDS) if (!taken.includes(field)) delete emitter[field]
  if (!taken.includes('color')) dropChannel(emitter, 'color')
  if (!taken.includes('size')) dropChannel(emitter, 'size')
  const required = PARTICLE_OPTIONS[kind].requires as DataField | null
  if (required === 'blockState' && !emitter.blockState && defaults.blockState)
    emitter.blockState = defaults.blockState
  if (required === 'item' && !emitter.item && defaults.item) emitter.item = { kind: defaults.item }
}

/** Burst ↔ rate. Leaving rate drops its curve; leaving burst drops `every`. */
export function setEmission(emitter: EmitterDef, mode: 'burst' | 'rate') {
  if (mode === 'burst') {
    if (emitter.burst !== undefined && emitter.rate === undefined) return
    const peak = Math.max(emitter.rate ?? 1, ...(emitter.curves?.rate ?? []).map((it) => it.value))
    emitter.burst = Math.min(PARTICLE_LIMITS.maxBurst, Math.max(1, Math.ceil(peak)))
    delete emitter.rate
    dropChannel(emitter, 'rate')
  } else {
    if (emitter.rate !== undefined && emitter.burst === undefined) return
    emitter.rate = Math.min(PARTICLE_LIMITS.maxRate, emitter.burst ?? 1)
    delete emitter.burst
    delete emitter.every
  }
}

/**
 * Switches the shape type. A radius carries between ring, disc and sphere
 * (and its curve with it); what the new type lacks goes, and so does a
 * distribution or spin it no longer allows.
 */
export function setShapeType(emitter: EmitterDef, type: ShapeType) {
  const old = shapeOf(emitter)
  if (old.type === type) return
  const radius = hasRadius(old) ? (old as { radius?: number }).radius : undefined
  const surface = 'surface' in old ? old.surface : undefined
  let next: EmitterShape
  switch (type) {
    case 'point':
      next = { type }
      break
    case 'line':
      next = { type, to: [0, 1, 0] }
      break
    case 'ring':
    case 'disc':
      next = { type }
      break
    case 'sphere':
      next = { type }
      if (surface) next.surface = surface
      break
    case 'box':
      next = { type, size: [1, 1, 1] }
      if (surface) next.surface = surface
      break
  }
  if (hasRadius(next)) {
    // A radius curve stands in for the radius; without either, start at one block.
    if (!isCurved(emitter, 'radius')) (next as { radius?: number }).radius = radius ?? 1
  } else {
    dropChannel(emitter, 'radius')
  }
  if (next.type === 'point') delete emitter.shape
  else emitter.shape = next
  if (emitter.distribution === 'even' && !evenAllowed(next)) delete emitter.distribution
  if (emitter.spin !== undefined && !spinAllowed(next)) delete emitter.spin
}

/** Sets a shape's `surface`, dropping an even distribution a volume can't have. */
export function setSurface(emitter: EmitterDef, surface: boolean) {
  const shape = emitter.shape
  if (!shape || (shape.type !== 'sphere' && shape.type !== 'box')) return
  if (surface) shape.surface = true
  else delete shape.surface
  if (emitter.distribution === 'even' && !evenAllowed(shape)) delete emitter.distribution
}

// ---- curves ---------------------------------------------------------------------------------------

/** The value a channel has with no curve: its constant, or what an absent one means. */
export function constantOf(emitter: EmitterDef, channel: Channel): number | string {
  switch (channel) {
    case 'rate':
      return emitter.rate ?? 1
    case 'size':
      return emitter.size ?? PARTICLE_DEFAULTS.size
    case 'speed':
      return emitter.speed ?? 0
    case 'radius': {
      const shape = shapeOf(emitter) as { radius?: number }
      return shape.radius ?? 1
    }
    case 'color':
      return emitter.color ?? PARTICLE_DEFAULTS.color
  }
}

/** Removes a constant now that its curve holds the value (rate stays: it says the emitter is rate-driven). */
function clearConstant(emitter: EmitterDef, channel: Channel) {
  if (channel === 'size') delete emitter.size
  else if (channel === 'speed') delete emitter.speed
  else if (channel === 'color') delete emitter.color
  else if (channel === 'radius' && emitter.shape && hasRadius(emitter.shape))
    delete (emitter.shape as { radius?: number }).radius
}

/** Puts a value back as the constant, when a channel's last key goes. */
function restoreConstant(emitter: EmitterDef, channel: Channel, value: number | string) {
  if (channel === 'size') emitter.size = value as number
  else if (channel === 'speed') {
    if (value !== 0) emitter.speed = value as number
  } else if (channel === 'color') emitter.color = value as string
  else if (channel === 'radius' && emitter.shape && hasRadius(emitter.shape))
    (emitter.shape as { radius?: number }).radius = value as number
}

function dropChannel(emitter: EmitterDef, channel: Channel) {
  if (!emitter.curves) return
  delete emitter.curves[channel]
  if (Object.keys(emitter.curves).length === 0) delete emitter.curves
}

type AnyKey = NumberKey | ColorKey

function keysOf(emitter: EmitterDef, channel: Channel): AnyKey[] {
  emitter.curves ??= {}
  return ((emitter.curves[channel] as AnyKey[] | undefined) ??= [])
}

const keyOf = (channel: Channel, time: number, value: number | string): AnyKey =>
  channel === 'color' ? { time, color: value as string } : { time, value: value as number }

/**
 * Adds a key at [time] with [value] (or sets the one already there); a
 * channel with no keys yet takes over its constant. Returns the key's index.
 */
export function addKey(
  emitter: EmitterDef,
  channel: Channel,
  time: number,
  value: number | string,
): number {
  const at = Math.max(0, Math.round(time))
  if (!isCurved(emitter, channel)) clearConstant(emitter, channel)
  const fresh = keyOf(channel, at, value)
  return keys.putKey(keysOf(emitter, channel), fresh, {
    merge: (existing) => ({ ...fresh, ...(existing.easing ? { easing: existing.easing } : {}) }),
  })
}

/** Starts a curve on [channel] from its constant, one key at [time]. Returns the key's index. */
export function addCurve(emitter: EmitterDef, channel: Channel, time = 0): number {
  if (isCurved(emitter, channel)) return 0
  return addKey(emitter, channel, time, constantOf(emitter, channel))
}

/** Changes a key's value (a number, or `#rrggbb` for colour). */
export function setKeyValue(
  emitter: EmitterDef,
  channel: Channel,
  index: number,
  value: number | string,
) {
  const key = emitter.curves?.[channel]?.[index]
  if (!key) return
  if (channel === 'color') (key as ColorKey).color = value as string
  else (key as NumberKey).value = value as number
}

export function setKeyEasing(emitter: EmitterDef, channel: Channel, index: number, easing: Easing) {
  const key = emitter.curves?.[channel]?.[index]
  if (key) keys.setEasing(key, easing)
}

/** Moves a key to a whole tick; returns its index after re-sorting so a drag keeps hold of it. */
export function moveKey(
  emitter: EmitterDef,
  channel: Channel,
  index: number,
  time: number,
): number {
  const list = emitter.curves?.[channel] as AnyKey[] | undefined
  if (!list) return index
  const at = keys.moveKey(list, index, Math.max(0, Math.round(time)))
  return at < 0 ? index : at
}

/** Removes a key; removing the last one puts its value back as the constant. */
export function removeKey(emitter: EmitterDef, channel: Channel, index: number) {
  const list = emitter.curves?.[channel] as AnyKey[] | undefined
  const key = list && keys.removeKey(list, index)
  if (!list || !key) return
  if (list.length > 0) return
  dropChannel(emitter, channel)
  restoreConstant(emitter, channel, 'color' in key ? key.color : key.value)
}

/** Removes a whole channel, keeping its first key's value as the constant. */
export function removeCurve(emitter: EmitterDef, channel: Channel) {
  const keys = emitter.curves?.[channel] as AnyKey[] | undefined
  const first = keys?.[0]
  dropChannel(emitter, channel)
  if (first) restoreConstant(emitter, channel, 'color' in first ? first.color : first.value)
}

// ---- selection ------------------------------------------------------------------------------------

/** What the inspector shows: the effect, an emitter, or one curve key. */
export type ParticlePick =
  | { kind: 'effect' }
  | { kind: 'emitter'; emitter: string }
  | { kind: 'key'; emitter: string; channel: Channel; index: number }

/** The ticks a burst emitter bursts on, for the timeline. */
export function burstTicks(emitter: EmitterDef, duration: number): number[] {
  if (emitter.burst === undefined) return []
  const start = emitter.start ?? 0
  const end = Math.min(emitter.end ?? duration, duration)
  if (start >= end) return []
  if (!emitter.every || emitter.every < 1) return [start]
  const out: number[] = []
  for (let t = start; t < end; t += emitter.every) out.push(t)
  return out
}
