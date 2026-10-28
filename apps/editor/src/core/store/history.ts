/**
 * One document's undo history, as immutable snapshots of its model.
 *
 * Every edit is a new model value. An edit made while a gesture is open (a
 * gizmo drag, a scrubbed number, a keyframe drag) replaces the present
 * instead of pushing, so the whole gesture undoes in one step. The first edit
 * of a gesture is what pushes the pre-gesture state.
 *
 * Each step also says when it was made and whether it belongs to a project
 * transaction (a rename, delete or copy that changed several files; see
 * `refactors.ts`): such a step is only ever undone with its transaction.
 */
export interface History<T> {
  past: T[]
  /** `pastSteps[i]` is the step that went from `past[i]` to the state after it. */
  pastSteps: Step[]
  present: T
  future: T[]
  /** `futureSteps[i]` is the step that goes to `future[i]` from the state before it. */
  futureSteps: Step[]
  /** True while a gesture is open. */
  gesture: boolean
  /** Whether the open gesture has pushed its starting state yet. */
  gesturePushed: boolean
}

export interface Step {
  /** When it was made, on the workspace's one clock ([nextSeq]): comparable with every other step and transaction. */
  seq: number
  /** The project transaction it's part of, or null for the document's own edit. */
  tx: number | null
}

let clock = 0
/** The workspace's undo clock: every document step and every transaction takes the next tick. */
export const nextSeq = () => (clock += 1)

/** Undo depth per document. Snapshots share structure, so this is cheap. */
const LIMIT = 200

export function createHistory<T>(present: T): History<T> {
  return {
    past: [],
    pastSteps: [],
    present,
    future: [],
    futureSteps: [],
    gesture: false,
    gesturePushed: false,
  }
}

/** [next] as the new present: one step, part of transaction [tx] when given. */
export function commit<T>(history: History<T>, next: T, tx: number | null = null): History<T> {
  if (Object.is(next, history.present)) return history
  if (history.gesture && history.gesturePushed) {
    return { ...history, present: next }
  }
  return {
    past: [...history.past, history.present].slice(-LIMIT),
    pastSteps: [...history.pastSteps, { seq: nextSeq(), tx }].slice(-LIMIT),
    present: next,
    future: [],
    futureSteps: [],
    gesture: history.gesture,
    gesturePushed: history.gesture,
  }
}

export function beginGesture<T>(history: History<T>): History<T> {
  return { ...history, gesture: true, gesturePushed: false }
}

export function endGesture<T>(history: History<T>): History<T> {
  return { ...history, gesture: false, gesturePushed: false }
}

export function undo<T>(history: History<T>): History<T> {
  if (history.past.length === 0) return history
  return {
    past: history.past.slice(0, -1),
    pastSteps: history.pastSteps.slice(0, -1),
    present: history.past[history.past.length - 1] as T,
    future: [history.present, ...history.future],
    futureSteps: [history.pastSteps[history.pastSteps.length - 1]!, ...history.futureSteps],
    gesture: false,
    gesturePushed: false,
  }
}

export function redo<T>(history: History<T>): History<T> {
  if (history.future.length === 0) return history
  const [next, ...rest] = history.future
  const [step, ...steps] = history.futureSteps
  return {
    past: [...history.past, history.present],
    pastSteps: [...history.pastSteps, step!],
    present: next as T,
    future: rest,
    futureSteps: steps,
    gesture: false,
    gesturePushed: false,
  }
}

/** The step [undo] would take back, or null. */
export const lastStep = (history: History<unknown>): Step | null =>
  history.pastSteps[history.pastSteps.length - 1] ?? null

/** The step [redo] would make again, or null. */
export const nextStep = (history: History<unknown>): Step | null => history.futureSteps[0] ?? null

/** The history with transaction [tx]'s steps made the document's own (the transaction is gone). */
export function untag<T>(history: History<T>, tx: number): History<T> {
  const mine = (steps: Step[]) => steps.some((it) => it.tx === tx)
  if (!mine(history.pastSteps) && !mine(history.futureSteps)) return history
  const own = (step: Step) => (step.tx === tx ? { ...step, tx: null } : step)
  return {
    ...history,
    pastSteps: history.pastSteps.map(own),
    futureSteps: history.futureSteps.map(own),
  }
}

export const canUndo = (history: History<unknown>) => history.past.length > 0
export const canRedo = (history: History<unknown>) => history.future.length > 0
