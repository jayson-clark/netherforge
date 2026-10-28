/**
 * The workbench's 3D scenes, one per open document with a viewport, kept
 * here rather than in the editor that shows them, so a tab switch doesn't
 * throw a scene away (see "Viewports" in the editor-ui skill).
 *
 * An editor's `Viewport3D` hands its scene over on every render (`show`);
 * the one `ViewportCanvas` draws whichever scene is `active`, in the slot
 * element the active viewport `attach`ed (the canvas element moves there).
 * A scene stays, with the last content it was given, while its document has
 * a tab (`retain`); a hidden scene isn't drawn and doesn't take pointer
 * events. No three.js here: the workbench holds this store before any 3D
 * view has loaded.
 */
import type { ReactNode } from 'react'
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { CameraPose } from './camera'

/** The ground grid: a fine cell and a coarser section line, in blocks. */
export interface GridSpec {
  cell: number
  section: number
  /** How far it reaches before fading out. */
  fade: number
}

/** A grid of pixels (sixteenths) with a line every block: what a centity or an effect sits on. */
export const BLOCK_GRID: GridSpec = { cell: 1 / 16, section: 1, fade: 40 }

/** What a document's viewport draws and how its camera behaves. */
export interface SceneSpec {
  /** The scene's own objects, under the shared lights and grid. */
  content: ReactNode
  /** Where the camera starts, and where it goes when the view's camera is null. */
  home: CameraPose
  /** The camera as the document's view keeps it; null is [home]. */
  camera: CameraPose | null
  /** The user moved the camera (written to the document's view once it settles). */
  onCamera: (pose: CameraPose) => void
  /** A click that hit nothing in the scene (and wasn't the end of a gizmo drag). */
  onMissed?: () => void
  fov?: number
  near?: number
  far?: number
  background?: string
  /** null for none. */
  grid?: GridSpec | null
  /** Panning along the ground, as a map is browsed, rather than across the screen. */
  groundPanning?: boolean
  /** How far the camera can back off its target. */
  maxDistance?: number
}

/** Draws the active scene into the canvas now and returns it as a PNG data URL; null without one. */
export type Capture = () => string | null

export interface ViewportState {
  scenes: Record<string, SceneSpec>
  /** The scene being drawn: the document whose viewport is on screen. */
  active: string | null
  /** Where the canvas goes: the active viewport's slot element. */
  slot: HTMLElement | null
  /** The canvas's capture, once it's up. */
  capture: Capture | null
  /** A scene's content threw: what it said, until its viewport is attached again (a tab switch). */
  errors: Record<string, string>
  /** How many times each scene was given another try after an error (its error boundary's key). */
  attempts: Record<string, number>
}

export interface ViewportActions {
  /** Gives document [key] its scene (new, or as it is now). */
  show(key: string, spec: SceneSpec): void
  /** Draws [key]'s scene in [slot]. */
  attach(key: string, slot: HTMLElement): void
  /** [key]'s viewport left the screen: its scene stays, undrawn. */
  detach(key: string): void
  /** Drops every scene whose document [open] doesn't name (its tab closed). */
  retain(open: (key: string) => boolean): void
  setCapture(capture: Capture | null): void
  failed(key: string, error: string): void
}

export type ViewportStore = StoreApi<ViewportState & ViewportActions>

export function createViewports(): ViewportStore {
  return createStore<ViewportState & ViewportActions>()((set, get) => ({
    scenes: {},
    active: null,
    slot: null,
    capture: null,
    errors: {},
    attempts: {},

    show(key, spec) {
      const { scenes } = get()
      if (scenes[key] !== spec) set({ scenes: { ...scenes, [key]: spec } })
    },

    attach(key, slot) {
      const { errors, attempts } = get()
      if (!(key in errors)) {
        set({ active: key, slot })
        return
      }
      const rest = { ...errors }
      delete rest[key]
      set({
        active: key,
        slot,
        errors: rest,
        attempts: { ...attempts, [key]: (attempts[key] ?? 0) + 1 },
      })
    },

    detach(key) {
      if (get().active === key) set({ active: null, slot: null })
    },

    retain(open) {
      const { scenes, active, errors, attempts } = get()
      const gone = Object.keys(scenes).filter((key) => !open(key))
      if (gone.length === 0) return
      const kept = { ...scenes }
      const keptErrors = { ...errors }
      const keptAttempts = { ...attempts }
      for (const key of gone) {
        delete kept[key]
        delete keptErrors[key]
        delete keptAttempts[key]
      }
      set({
        scenes: kept,
        errors: keptErrors,
        attempts: keptAttempts,
        ...(active !== null && gone.includes(active) ? { active: null, slot: null } : {}),
      })
    },

    setCapture(capture) {
      set({ capture })
    },

    failed(key, error) {
      set({ errors: { ...get().errors, [key]: error } })
    },
  }))
}
