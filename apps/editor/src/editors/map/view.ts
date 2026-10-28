/**
 * A map's per-document view: its camera and how far around it the map
 * is drawn. Nothing is selected.
 */
import type { ViewStateSpec } from '@/editors/contributions'
import type { CameraPose } from '@/editors/shared/viewport/camera'

/** Chunks around the middle drawn at first: about 140 chunks, half a million faces of real terrain. */
export const DEFAULT_RADIUS = 6

export interface MapViewState {
  /** The map's camera; null is where it starts, over the world's spawn. */
  camera: CameraPose | null
  /** How many chunks around the camera's middle are drawn. */
  radius: number
}

export const MAP_VIEW: ViewStateSpec<MapViewState, never> = {
  initial: { camera: null, radius: DEFAULT_RADIUS },
  key: (item) => item,
}
