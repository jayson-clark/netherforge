/**
 * The particle editor's per-document view: the preview's playhead, whether
 * it plays and loops, its camera, and the selected emitters and curve keys (with nothing
 * selected, the inspector shows the effect).
 */
import type { ViewStateSpec } from '@/editors/contributions'
import type { CameraPose } from '@/editors/shared/viewport/camera'
import type { ParticlePick } from './ops'

/** The preview's own state: not in the file, not undoable. */
export interface PreviewState {
  /** Ticks since the preview started; past the duration while looping or fading out. */
  tick: number
  playing: boolean
  /** The preview's loop, not the file's. */
  loop: boolean
  /** The viewport's camera; null is where it starts. */
  camera: CameraPose | null
}

/** What can be selected: an emitter or one of its curve keys (the effect is what's shown with neither). */
export type ParticleSelection = Exclude<ParticlePick, { kind: 'effect' }>

/** `ring`, `ring:radius:1`: an emitter's name, or a key's place in its emitter's curve. */
export function selectionKey(item: ParticleSelection): string {
  return item.kind === 'emitter' ? item.emitter : `${item.emitter}:${item.channel}:${item.index}`
}

/** The inspector's pick for the primary selection. */
export const pickOf = (primary: ParticleSelection | null): ParticlePick =>
  primary ?? { kind: 'effect' }

export const PARTICLE_VIEW: ViewStateSpec<PreviewState, ParticleSelection> = {
  initial: { tick: 0, playing: false, loop: false, camera: null },
  key: selectionKey,
}
