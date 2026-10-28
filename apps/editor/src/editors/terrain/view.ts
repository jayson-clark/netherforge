/**
 * The terrain editor's per-document view: the seed the preview draws, where its map looks and how close, how fine
 * it's drawn, and which line the slice is cut along. None of it is in the file, and none is undone.
 */
import type { ViewStateSpec } from '@/editors/contributions'

export interface PreviewState {
  /** The seed the preview generates for: the same seed is the same world, on the server too. */
  seed: number
  /** The block at the middle of the map. */
  x: number
  z: number
  /** How close the map is: screen pixels (CSS) a block is across. */
  zoom: number
  /** How fine the map is drawn: screen pixels (CSS) a map cell aims to be, so a smaller number is more work. */
  detail: number
  /** Whether the slice runs along the x axis (at the z below) or the z axis (at the x below). */
  alongX: boolean
  /** The slice's line: its z when it runs along x, else its x. */
  at: number
  /** The block along the line the slice is centred on. */
  along: number
  /** How many blocks the slice is across. */
  width: number
  /**
   * Which heights the preview draws a world of: a `netherforge.json` world's (its dimension's) by name, `''` for the
   * overworld's, null for the first world naming the generator with a dimension (the overworld's when none does).
   */
  heights: string | null
}

/** Nothing in a terrain is selected: its inspector shows everything. */
export const TERRAIN_VIEW: ViewStateSpec<PreviewState, never> = {
  initial: {
    seed: 1337,
    x: 0,
    z: 0,
    zoom: 1,
    detail: 4,
    alongX: true,
    at: 0,
    along: 0,
    width: 256,
    heights: null,
  },
  key: (item) => String(item),
}
