/**
 * The centity editor's per-document view: the animation being previewed,
 * the playhead, the gizmo mode, the selected keyframe, the viewport's
 * camera, and the selected nodes (by name). None of it is in the file, so
 * none of it is undoable.
 */
import type { Channel } from '@/core/format'
import type { ViewStateSpec } from '@/editors/contributions'
import type { CameraPose } from '@/editors/shared/viewport/camera'

export type { GizmoMode } from '@/editors/shared/viewport/Gizmo'
import type { GizmoMode } from '@/editors/shared/viewport/Gizmo'

export interface KeyRef {
  node: string
  channel: Channel
  index: number
}

export interface CentityView {
  clip: string | null
  time: number
  playing: boolean
  gizmo: GizmoMode
  showHitboxes: boolean
  selectedKey: KeyRef | null
  /** The viewport's camera; null is where it starts. */
  camera: CameraPose | null
}

/** The centity's registration of its view (`editors/registry.tsx`): its selection is node names. */
export const CENTITY_VIEW: ViewStateSpec<CentityView, string> = {
  initial: {
    clip: null,
    time: 0,
    playing: false,
    gizmo: 'translate',
    showHitboxes: true,
    selectedKey: null,
    camera: null,
  },
  key: (node) => node,
}
