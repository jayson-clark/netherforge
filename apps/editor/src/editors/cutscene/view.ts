/**
 * The cutscene editor's per-document view: the preview's playhead (seconds)
 * and whether it plays, its camera, and the selected key (with none, the
 * inspector shows the cutscene).
 */
import type { ViewStateSpec } from '@/editors/contributions'
import type { CameraPose } from '@/editors/shared/viewport/camera'
import type { CutscenePick } from './ops'

/** The preview's own state: not in the file, not undoable. */
export interface PreviewState {
  /** Seconds into the cutscene. */
  time: number
  playing: boolean
  /** The viewport's camera; null is the scene's home, framing the path. */
  camera: CameraPose | null
}

/** `position:2`: a key's row and place in it. */
export const selectionKey = (pick: CutscenePick) => `${pick.track}:${pick.index}`

export const CUTSCENE_VIEW: ViewStateSpec<PreviewState, CutscenePick> = {
  initial: { time: 0, playing: false, camera: null },
  key: selectionKey,
}
