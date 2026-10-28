/**
 * Where a viewport's camera is: a document's view state keeps it (each 3D
 * kind's view has `camera`), null meaning the scene's own starting point,
 * its `home`. Pure, so the scene and the tests agree on what "the same
 * pose" is.
 */
import type { Vec3 } from '@/core/format'

/** The camera's position and the point it orbits. */
export interface CameraPose {
  position: Vec3
  target: Vec3
}

/** Closer than this, two poses are the same (a pose read back from three.js isn't bit-identical). */
const SAME = 1e-6

const sameVec = (a: Vec3, b: Vec3) => a.every((it, i) => Math.abs(it - b[i]!) < SAME)

export function samePose(a: CameraPose | null, b: CameraPose | null): boolean {
  if (a === null || b === null) return a === b
  return sameVec(a.position, b.position) && sameVec(a.target, b.target)
}

/** A pose's numbers rounded to a thousandth of a block, as the view keeps them. */
export function roundPose(pose: CameraPose): CameraPose {
  const round = (v: Vec3): Vec3 => v.map((it) => Math.round(it * 1000) / 1000 + 0) as Vec3
  return { position: round(pose.position), target: round(pose.target) }
}

/**
 * Where a camera looking at a box from the front right and a little above
 * stands to see all of it: the structure preview's home, and a framing for
 * any scene with known bounds.
 */
export function framing(min: Vec3, max: Vec3): CameraPose {
  const target: Vec3 = [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2]
  const reach = Math.max(max[0] - min[0], max[1] - min[1], max[2] - min[2], 1)
  return {
    position: [
      target[0] + reach * 1.1 + 2,
      target[1] + reach * 0.8 + 2,
      target[2] + reach * 1.4 + 2,
    ],
    target,
  }
}
