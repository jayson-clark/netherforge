/**
 * What the screen editor does to a `screen.json` (docs/format/screen.md), kept apart from React so it's tested on
 * its own. Each takes the draft `workspace.edit` hands a recipe.
 */
import { setKey } from '@/core/draft'
import type { ScreenFacing, ScreenFile, ScreenPlace } from '@/core/format'

/** A map's side in pixels, as format's `MapScreen.TILE`. */
export const TILE = 128
/** The most maps a screen has along a side (`MapScreen.MAX_TILES`). */
export const MAX_TILES = 16
export const DEFAULT_RANGE = 64
export const DEFAULT_CLICK_RANGE = 24

export const FACINGS: readonly ScreenFacing[] = ['north', 'south', 'east', 'west']

/** Where a new placement starts: the main world's origin, facing south. */
export const NEW_PLACE: ScreenPlace = { world: 'world', x: 0, y: 64, z: 0, facing: 'south' }

/** A side in maps, held to 1 to [MAX_TILES]. */
export function setSize(draft: ScreenFile, side: 'width' | 'height', maps: number) {
  draft[side] = Math.min(MAX_TILES, Math.max(1, Math.round(maps)))
}

/** Hangs it at [place], or nowhere for null. */
export function setPlace(draft: ScreenFile, place: ScreenPlace | null) {
  setKey(draft, 'place', place ?? undefined)
}

/** Changes one field of where it hangs; nothing when it hangs nowhere. */
export function setPlaceField<K extends keyof ScreenPlace>(
  draft: ScreenFile,
  key: K,
  value: ScreenPlace[K],
) {
  if (draft.place) draft.place[key] = value
}

/** Plays [video] from the start (null: none), keeping how it loops and how loud. */
export function setVideo(draft: ScreenFile, video: string | null) {
  if (!video) {
    delete draft.video
    return
  }
  draft.video = { ...draft.video, video }
}

/** The blocks its maps hang in, by `column,row`: as format's `ScreenKind` works them out. */
export function blocksOf(file: ScreenFile): Map<string, [number, number, number]> {
  const out = new Map<string, [number, number, number]>()
  const place = file.place
  if (!place) return out
  const [rightX, rightZ] = RIGHT[place.facing]
  for (let column = 0; column < file.width; column++) {
    for (let row = 0; row < file.height; row++) {
      out.set(`${column},${row}`, [
        place.x + rightX * column,
        place.y - row,
        place.z + rightZ * column,
      ])
    }
  }
  return out
}

/** Which way is right, along x and z, for a player facing a screen that faces this way. */
const RIGHT: Record<ScreenFacing, [number, number]> = {
  north: [-1, 0],
  south: [1, 0],
  east: [0, -1],
  west: [0, 1],
}
