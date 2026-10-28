/**
 * Edits to a centity model, as functions that mutate a draft (the store hands
 * them a structured clone via `workspace.edit`). Keeping them here, free of
 * React, is what lets the tree, the inspector, the gizmo and the timeline
 * share one tested definition of "rename a node" or "move a keyframe".
 */
import * as keys from '@/editors/shared/timeline/keys'
import { roundSeconds } from '@/editors/shared/timeline/time'
import type {
  AnimationDef,
  CentityFile,
  Channel,
  Easing,
  Keyframe,
  NodeDef,
  Transform,
  Vec3,
} from '@/core/format'

export const CHANNELS: Channel[] = ['translation', 'rotation', 'scale']

const DEFAULTS: Record<Channel, Vec3> = {
  translation: [0, 0, 0],
  rotation: [0, 0, 0],
  scale: [1, 1, 1],
}

export const channelDefault = (channel: Channel): Vec3 => [...DEFAULTS[channel]]

const sameVec = (a: Vec3, b: Vec3) => a[0] === b[0] && a[1] === b[1] && a[2] === b[2]

export function nodesOf(file: CentityFile): Record<string, NodeDef> {
  return file.nodes ?? {}
}

/** Children of [name] (null for roots), in file order (which is by name). */
export function childrenOf(file: CentityFile, name: string | null): string[] {
  return Object.entries(nodesOf(file))
    .filter(([, node]) => (node.parent ?? null) === name)
    .map(([child]) => child)
    .sort()
}

export function descendantsOf(file: CentityFile, name: string): Set<string> {
  const out = new Set<string>()
  const visit = (parent: string) => {
    for (const child of childrenOf(file, parent)) {
      if (out.has(child)) continue
      out.add(child)
      visit(child)
    }
  }
  visit(name)
  return out
}

/** A name not yet used, `base`, `base_2`, … */
export function freshName(taken: Iterable<string>, base: string): string {
  const used = new Set(taken)
  if (!used.has(base)) return base
  for (let i = 2; ; i += 1) if (!used.has(`${base}_${i}`)) return `${base}_${i}`
}

export function addNode(draft: CentityFile, parent: string | null, base = 'node'): string {
  draft.nodes ??= {}
  const name = freshName(Object.keys(draft.nodes), base)
  draft.nodes[name] = parent ? { parent } : {}
  return name
}

/** Renames a node and everything that refers to it: children and animation tracks. */
export function renameNode(draft: CentityFile, from: string, to: string) {
  const nodes = draft.nodes
  if (!nodes || !(from in nodes) || from === to || to in nodes) return
  const rebuilt: Record<string, NodeDef> = {}
  for (const [name, node] of Object.entries(nodes)) {
    const next = node.parent === from ? { ...node, parent: to } : node
    rebuilt[name === from ? to : name] = next
  }
  draft.nodes = rebuilt
  for (const clip of Object.values(draft.animations ?? {})) {
    if (clip.tracks && from in clip.tracks) {
      clip.tracks[to] = clip.tracks[from]!
      delete clip.tracks[from]
    }
  }
}

/** Deletes a node with its subtree and their animation tracks. */
export function deleteNode(draft: CentityFile, name: string) {
  const doomed = new Set([name, ...descendantsOf(draft, name)])
  for (const it of doomed) delete draft.nodes?.[it]
  for (const clip of Object.values(draft.animations ?? {})) {
    for (const it of doomed) delete clip.tracks?.[it]
  }
}

/**
 * Copies [name] and its whole subtree beside it (same parent), each copy
 * named fresh from its original's name. Animation tracks aren't copied: a
 * copy starts in the base pose. Returns the copy of [name], or null when
 * there's no such node.
 */
export function duplicateNode(draft: CentityFile, name: string): string | null {
  const nodes = draft.nodes
  const source = nodes?.[name]
  if (!nodes || !source) return null
  const taken = new Set(Object.keys(nodes))
  const renamed = new Map<string, string>()
  const copy = (from: string, parent: string | undefined) => {
    const to = freshName(taken, from.replace(/_\d+$/, '') || from)
    taken.add(to)
    renamed.set(from, to)
    // JSON, not structuredClone: [draft] is an Immer proxy.
    const node = JSON.parse(JSON.stringify(nodes[from])) as NodeDef
    if (parent === undefined) delete node.parent
    else node.parent = parent
    nodes[to] = node
    // Copies are never children of an original, so this lists originals only.
    for (const child of childrenOf(draft, from)) copy(child, to)
  }
  copy(name, source.parent)
  return renamed.get(name)!
}

/** Moves [name] under [parent]; refuses a move that would make a cycle. Returns whether it moved. */
export function reparent(draft: CentityFile, name: string, parent: string | null): boolean {
  const node = draft.nodes?.[name]
  if (!node) return false
  if (parent !== null && (parent === name || descendantsOf(draft, name).has(parent))) return false
  if (parent === null) delete node.parent
  else node.parent = parent
  return true
}

/** Sets one transform channel, leaving out values at their default so files stay minimal. */
export function setChannel(node: NodeDef, channel: Channel, value: Vec3) {
  const transform: Transform = { ...node.transform }
  if (sameVec(value, DEFAULTS[channel])) delete transform[channel]
  else transform[channel] = value
  if (Object.keys(transform).length === 0) delete node.transform
  else node.transform = transform
}

export function getChannel(node: NodeDef | undefined, channel: Channel): Vec3 {
  return node?.transform?.[channel] ?? channelDefault(channel)
}

// ---- animations -------------------------------------------------------------

export function addAnimation(draft: CentityFile, base = 'clip'): string {
  draft.animations ??= {}
  const name = freshName(Object.keys(draft.animations), base)
  draft.animations[name] = { tracks: {} }
  return name
}

export function renameAnimation(draft: CentityFile, from: string, to: string) {
  const clips = draft.animations
  if (!clips?.[from] || from === to || to in clips) return
  clips[to] = clips[from]!
  delete clips[from]
}

/** Deletes a clip (and `animations` with its last one, so the file stays minimal). */
export function deleteAnimation(draft: CentityFile, name: string) {
  delete draft.animations?.[name]
  if (draft.animations && Object.keys(draft.animations).length === 0) delete draft.animations
}

export function trackKeys(
  clip: AnimationDef | undefined,
  node: string,
  channel: Channel,
): Keyframe[] {
  return clip?.tracks?.[node]?.[channel] ?? []
}

/** Keyframes closer than this in time are the same key (a click at "the same" time). */
const SAME_TIME = 1e-4

/** Adds a key at [time] or updates the one already there. Keys stay sorted by time. */
export function setKeyframe(
  clip: AnimationDef,
  node: string,
  channel: Channel,
  time: number,
  value: Vec3,
) {
  clip.tracks ??= {}
  const track = (clip.tracks[node] ??= {})
  const list = [...(track[channel] ?? [])]
  keys.putKey(
    list,
    { time: roundSeconds(time), value },
    {
      same: SAME_TIME,
      merge: (existing) => ({ ...existing, value }),
    },
  )
  track[channel] = list
}

export function deleteKey(clip: AnimationDef, node: string, channel: Channel, index: number) {
  const track = clip.tracks?.[node]
  const list = track?.[channel]
  if (!track || !list) return
  keys.removeKey(list, index)
  if (list.length === 0) delete track[channel]
  if (Object.keys(track).length === 0) delete clip.tracks![node]
}

/** Moves a key in time; returns its index after re-sorting so a drag can keep hold of it. */
export function moveKey(
  clip: AnimationDef,
  node: string,
  channel: Channel,
  index: number,
  time: number,
): number {
  const list = clip.tracks?.[node]?.[channel]
  if (!list) return index
  const at = keys.moveKey(list, index, Math.max(0, roundSeconds(time)))
  return at < 0 ? index : at
}

export function setEasing(
  clip: AnimationDef,
  node: string,
  channel: Channel,
  index: number,
  easing: Easing,
) {
  const key = clip.tracks?.[node]?.[channel]?.[index]
  if (key) keys.setEasing(key, easing)
}
