/**
 * A centity document posed for the viewport, by format's composer (the
 * server's own code): the editor never composes transforms itself.
 */
import { useMemo } from 'react'
import {
  centityPoser,
  type CentityFile,
  type CentityPoser,
  type Posed,
  type Problem,
} from '@/core/format'
import { resourceOf } from '@/core/paths'
import type { History } from '@/core/store/history'

export interface PoseView {
  /** The nodes' world matrices, or null when nothing in the history composes. */
  pose: Posed | null
  /** The pose is an earlier model's: the current one has [problems]. */
  stale: boolean
  problems: Problem[]
}

/** Models are immutable snapshots: each is compiled once, for as long as a history holds it. */
const posers = new WeakMap<object, CentityPoser>()

function poserOf(id: string, model: CentityFile): CentityPoser {
  let poser = posers.get(model)
  if (!poser) {
    poser = centityPoser(id, JSON.stringify(model))
    posers.set(model, poser)
  }
  return poser
}

const clipIn = (model: CentityFile, clip: string | null) =>
  clip !== null && model.animations?.[clip] !== undefined ? clip : null

/**
 * The model to show: the present one when its base pose composes, else the
 * latest one in the undo history that does (stale), so the viewport keeps
 * showing the centity while an edit has errors rather than going empty.
 */
function composing(
  id: string,
  history: History<CentityFile>,
): { model: CentityFile | null; problems: Problem[] } {
  const present = poserOf(id, history.present).pose(null, 0)
  if (present.type === 'posed') return { model: history.present, problems: [] }
  for (let i = history.past.length - 1; i >= 0; i -= 1) {
    const earlier = history.past[i]!
    if (poserOf(id, earlier).pose(null, 0).type === 'posed')
      return { model: earlier, problems: present.problems }
  }
  return { model: null, problems: present.problems }
}

/** Document [path] (its [history]; null when it isn't a model) posed at [clip] and [time] seconds. */
export function usePose(
  path: string,
  history: History<CentityFile> | null,
  clip: string | null,
  time: number,
): PoseView {
  const id = resourceOf(path)?.id ?? 'centity'
  const shown = useMemo(() => (history ? composing(id, history) : null), [id, history])
  return useMemo(() => {
    if (!shown?.model) return { pose: null, stale: true, problems: shown?.problems ?? [] }
    const stale = shown.model !== history?.present
    const result = poserOf(id, shown.model).pose(clipIn(shown.model, clip), time)
    if (result.type === 'posed') return { pose: result, stale, problems: shown.problems }
    return { pose: null, stale: true, problems: result.problems }
  }, [id, shown, history, clip, time])
}
