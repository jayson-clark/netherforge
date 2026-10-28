/**
 * The editor's zoom: the whole UI scaled, as Cmd/Ctrl +, - and 0 do in a
 * browser. It's a preference of this machine, not of a project, so it's kept
 * in the webview's storage beside the layouts.
 */
import type { Backend } from './backend/types'
import type { LayoutStorage } from './store/layout'

/** The steps Cmd/Ctrl +/- move through, as browsers and VS Code offer them. */
export const ZOOM_LEVELS = [0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2] as const

export const DEFAULT_ZOOM = 1
export const MIN_ZOOM = ZOOM_LEVELS[0]
export const MAX_ZOOM = ZOOM_LEVELS[ZOOM_LEVELS.length - 1]!

const STORAGE_KEY = 'netherforge.zoom'

/**
 * The next level from [zoom] in [direction]; a zoom between levels (an old
 * stored value) moves to the nearest level that way. Stays put at either end.
 */
export function stepZoom(zoom: number, direction: 1 | -1): number {
  const next =
    direction > 0
      ? ZOOM_LEVELS.find((it) => it > zoom + 1e-6)
      : ZOOM_LEVELS.findLast((it) => it < zoom - 1e-6)
  return next ?? zoom
}

/** The stored zoom, or the default when nothing usable is there. */
export function loadZoom(storage: LayoutStorage): number {
  const value = Number(storage.get(STORAGE_KEY))
  return value >= MIN_ZOOM && value <= MAX_ZOOM ? value : DEFAULT_ZOOM
}

export function saveZoom(storage: LayoutStorage, zoom: number) {
  storage.set(STORAGE_KEY, String(zoom))
}

export interface Zoom {
  readonly level: number
  /** Applies the stored level (at start, before the first frame). */
  apply(): Promise<void>
  step(direction: 1 | -1): void
  reset(): void
}

/** The app's one zoom: the View menu and Cmd/Ctrl +, - and 0 move it. */
export function createZoom(backend: Pick<Backend, 'setZoom'>, storage: LayoutStorage): Zoom {
  let level = loadZoom(storage)
  const set = (next: number) => {
    if (next === level) return
    level = next
    saveZoom(storage, level)
    void backend.setZoom(level)
  }
  return {
    get level() {
      return level
    },
    apply: () => backend.setZoom(level),
    step: (direction) => set(stepZoom(level, direction)),
    reset: () => set(DEFAULT_ZOOM),
  }
}
