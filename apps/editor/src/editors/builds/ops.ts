/**
 * What the build screen works out, pure: a dev server's box cut into the part boxes it captures one at a time,
 * a build's size from its parts, and its picture made from them (`StructurePicture`, the same pixels as
 * `netherforge preview` draws). The grid's rules are format's (`BUILD_LIMITS`, `BuildKind`), through
 * `@netherforge/structures/parts`.
 */
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { gridOf, gridOfFiles, partFile, partOf, partsOf } from '@netherforge/structures/parts'
import { StructurePicture, type StructureImage, type View } from '@netherforge/structures/picture'
import type { Structure } from '@netherforge/structures/structure'

export type Corner = [number, number, number]

/** One part's box in the world, both corners included, and the file it's captured into. */
export interface PartBox {
  file: string
  from: { x: number; y: number; z: number }
  to: { x: number; y: number; z: number }
}

/** The size of the box between two corners, both included. */
export const boxSize = (a: Corner, b: Corner): Corner =>
  [0, 1, 2].map((i) => Math.abs(a[i]! - b[i]!) + 1) as Corner

/** Whether a box that size is too big for a build. */
export const tooBig = (size: Corner) => gridOf(size).some((it) => it > BUILD_LIMITS.maxParts)

/** The boxes the parts of a build captured between [a] and [b] are, bottom up, each one capture's worth. */
export function partBoxes(a: Corner, b: Corner): PartBox[] {
  const min = [0, 1, 2].map((i) => Math.min(a[i]!, b[i]!))
  const max = [0, 1, 2].map((i) => Math.max(a[i]!, b[i]!))
  const side = BUILD_LIMITS.partSide
  return partsOf(gridOf(boxSize(a, b))).map(([x, y, z]) => {
    const from = [x, y, z].map((it, i) => min[i]! + it * side)
    const to = from.map((it, i) => Math.min(it + side - 1, max[i]!))
    return {
      file: partFile(x, y, z),
      from: { x: from[0]!, y: from[1]!, z: from[2]! },
      to: { x: to[0]!, y: to[1]!, z: to[2]! },
    }
  })
}

/** The parts' file names in a build's folder (what isn't one is left out), sorted. */
export const partNames = (names: readonly string[]) =>
  names.filter((it) => partOf(it) !== null).sort()

/** The parts a whole grid of [names] would have that aren't there. */
export function missingParts(names: readonly string[]): string[] {
  const have = new Set(names)
  return partsOf(gridOfFiles(names))
    .map(([x, y, z]) => partFile(x, y, z))
    .filter((it) => !have.has(it))
}

/** A build's size: its whole parts, and what its last part along each axis holds. */
export function buildSize(names: readonly string[], last: Structure): Corner {
  const grid = gridOfFiles(names)
  return [0, 1, 2].map((i) => (grid[i]! - 1) * BUILD_LIMITS.partSide + last.size[i]!) as Corner
}

/** The file of the last part along every axis: the one whose size says the build's. */
export function lastPart(names: readonly string[]): string {
  const [x, y, z] = gridOfFiles(names)
  return partFile(x - 1, y - 1, z - 1)
}

/** Whether a part at grid place [part] holds anything [view] shows (a slice only cuts some). */
export function partShows(view: View, part: readonly number[]): boolean {
  if (view.kind !== 'slice') return true
  const axis = { x: 0, y: 1, z: 2 }[view.axis]
  const from = part[axis]! * BUILD_LIMITS.partSide
  return view.at >= from && view.at < from + BUILD_LIMITS.partSide
}

/** A view clamped to [size]: a slice's layer kept inside the build. */
export function fitView(view: View, size: Corner): View {
  if (view.kind !== 'slice') return view
  const limit = size[{ x: 0, y: 1, z: 2 }[view.axis]]!
  return { ...view, at: Math.max(0, Math.min(limit - 1, Math.round(view.at))) }
}

/** The picture of a build of [size], from its parts, read in order by [read] (only those [view] shows). */
export async function drawBuild(
  size: Corner,
  names: readonly string[],
  view: View,
  read: (file: string) => Promise<Structure>,
): Promise<StructureImage> {
  const picture = new StructurePicture(size, fitView(view, size))
  const side = BUILD_LIMITS.partSide
  for (const part of partsOf(gridOfFiles(names))) {
    const file = partFile(...part)
    if (!names.includes(file) || !partShows(fitView(view, size), part)) continue
    picture.add(
      await read(file),
      part.map((it) => it * side),
    )
  }
  return picture.draw()
}
