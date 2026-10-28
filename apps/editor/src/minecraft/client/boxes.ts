/**
 * Model elements as axis-aligned boxes in block units: what "Fit to display"
 * writes into a hitbox. Pure maths, no three.js, so it's tested directly.
 *
 * Model space is Minecraft's 0–16 per axis. An element's own rotation (about
 * its origin, optionally rescaled) and the blockstate's whole-model `x`/`y`
 * rotation (clockwise, about the block centre) are both applied to the
 * element's eight corners, and the box is their bounds.
 */
import type { Placement, RawElement, V3 } from '@netherforge/models/model'

export interface Aabb {
  min: V3
  max: V3
}

const deg = (degrees: number) => (degrees * Math.PI) / 180

/** Right-handed rotation of [p] about [axis] through [origin]. */
function rotate(p: V3, axis: 'x' | 'y' | 'z', radians: number, origin: V3): V3 {
  const c = Math.cos(radians)
  const s = Math.sin(radians)
  const x = p[0] - origin[0]
  const y = p[1] - origin[1]
  const z = p[2] - origin[2]
  let out: V3
  switch (axis) {
    case 'x':
      out = [x, y * c - z * s, y * s + z * c]
      break
    case 'y':
      out = [x * c + z * s, y, -x * s + z * c]
      break
    case 'z':
      out = [x * c - y * s, x * s + y * c, z]
      break
  }
  return [out[0] + origin[0], out[1] + origin[1], out[2] + origin[2]]
}

function corners(from: V3, to: V3): V3[] {
  const out: V3[] = []
  for (const x of [from[0], to[0]])
    for (const y of [from[1], to[1]]) for (const z of [from[2], to[2]]) out.push([x, y, z])
  return out
}

/** An element's corners in model space, after its own rotation. */
function elementCorners(element: RawElement): V3[] {
  const points = corners(element.from, element.to)
  const rotation = element.rotation
  if (!rotation) return points
  const origin = rotation.origin
  // Angles about each axis (26.x's form): the X·Y·Z rotation the renderer draws (geometry.ts), so a point turns about z first.
  if (!rotation.axis) {
    const turns = (['z', 'y', 'x'] as const).filter((axis) => rotation[axis])
    return points.map((point) =>
      turns.reduce((p, axis) => rotate(p, axis, deg(rotation[axis]!), origin), point),
    )
  }
  if (!rotation.angle) return points
  const axis = rotation.axis
  const radians = deg(rotation.angle)
  return points.map((point) => {
    let p = point
    if (rotation.rescale) {
      // Rescale widens the two axes across the rotation so the rotated
      // element still spans the block (rails, stairs edges).
      const factor = 1 / Math.cos(radians)
      p = [
        axis === 'x' ? p[0] : origin[0] + (p[0] - origin[0]) * factor,
        axis === 'y' ? p[1] : origin[1] + (p[1] - origin[1]) * factor,
        axis === 'z' ? p[2] : origin[2] + (p[2] - origin[2]) * factor,
      ]
    }
    return rotate(p, axis, radians, origin)
  })
}

const CENTRE: V3 = [8, 8, 8]

/** Applies a blockstate's `x` then `y` rotation (degrees clockwise, about the block centre). */
export function placeCorner(p: V3, placement: Pick<Placement, 'x' | 'y'>): V3 {
  let out = p
  if (placement.x) out = rotate(out, 'x', deg(-placement.x), CENTRE)
  if (placement.y) out = rotate(out, 'y', deg(-placement.y), CENTRE)
  return out
}

const round = (value: number) => Math.round(value * 10000) / 10000 + 0

/** The bounds of one element as placed, in block units. */
export function elementBox(
  element: RawElement,
  placement: Pick<Placement, 'x' | 'y'> = { x: 0, y: 0 },
): Aabb {
  const points = elementCorners(element).map((it) => placeCorner(it, placement))
  const min: V3 = [Infinity, Infinity, Infinity]
  const max: V3 = [-Infinity, -Infinity, -Infinity]
  for (const p of points) {
    for (let axis = 0; axis < 3; axis += 1) {
      min[axis] = Math.min(min[axis]!, p[axis]!)
      max[axis] = Math.max(max[axis]!, p[axis]!)
    }
  }
  return {
    min: min.map((it) => round(it / 16)) as V3,
    max: max.map((it) => round(it / 16)) as V3,
  }
}

/** One pixel: the thickness a flat element (a ladder, a pane edge) gets so it can be clicked. */
const PIXEL = 1 / 16

/**
 * Every element of every placed model as a box, cleaned up for a hitbox:
 * flat elements get one pixel of thickness (a box must have volume), and
 * boxes inside another box are dropped.
 */
export function modelBoxes(models: { elements: RawElement[]; placement: Placement }[]): Aabb[] {
  const boxes: Aabb[] = []
  for (const { elements, placement } of models) {
    for (const element of elements) {
      if (!element.from || !element.to) continue
      boxes.push(thicken(elementBox(element, placement)))
    }
  }
  const unique = boxes.filter(
    (box, index) => boxes.findIndex((other) => sameBox(box, other)) === index,
  )
  return unique.filter((box) => !unique.some((other) => other !== box && contains(other, box)))
}

function thicken(box: Aabb): Aabb {
  const min = [...box.min] as V3
  const max = [...box.max] as V3
  for (let axis = 0; axis < 3; axis += 1) {
    if (max[axis]! - min[axis]! >= 1e-6) continue
    const centre = (min[axis]! + max[axis]!) / 2
    let low = centre - PIXEL / 2
    let high = centre + PIXEL / 2
    // Keep it inside the block when the flat face sits on its edge.
    if (low < 0) [low, high] = [0, PIXEL]
    if (high > 1 && centre <= 1) [low, high] = [1 - PIXEL, 1]
    min[axis] = round(low)
    max[axis] = round(high)
  }
  return { min, max }
}

const sameBox = (a: Aabb, b: Aabb) =>
  a.min.every((it, i) => it === b.min[i]) && a.max.every((it, i) => it === b.max[i])

const contains = (outer: Aabb, inner: Aabb) =>
  outer.min.every((it, i) => it <= inner.min[i]!) && outer.max.every((it, i) => it >= inner.max[i]!)
