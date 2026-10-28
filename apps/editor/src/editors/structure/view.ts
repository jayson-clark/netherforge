/** A structure's per-document view: its preview's camera. Nothing is selected. */
import type { ViewStateSpec } from '@/editors/contributions'
import type { CameraPose } from '@/editors/shared/viewport/camera'

export interface StructureView {
  /** The preview's camera; null is where it starts, looking at the whole structure. */
  camera: CameraPose | null
}

export const STRUCTURE_VIEW: ViewStateSpec<StructureView, never> = {
  initial: { camera: null },
  key: (item) => item,
}
