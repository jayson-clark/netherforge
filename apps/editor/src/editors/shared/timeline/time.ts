/**
 * A timeline's time model: seconds (a centity's clips, a cutscene) or game
 * ticks (a particle effect), and what each means for snapping, the arrow
 * keys, the ruler and how a time is written. Pure, so the timeline and
 * its tests agree.
 */

export type TimeUnit = 'seconds' | 'ticks'

/** A timeline's unit and how much of it shows. */
export interface TimeScale {
  unit: TimeUnit
  /** The time at the timeline's right edge (more than 0). */
  length: number
}

export const TICKS_PER_SECOND = 20

/** One step of the arrow keys and of a drag: a game tick, whichever the unit. */
export const stepOf = (unit: TimeUnit) => (unit === 'ticks' ? 1 : 1 / TICKS_PER_SECOND)

/** Seconds are kept to the millisecond; finer is noise from a drag. */
export const roundSeconds = (time: number) => Math.round(time * 1000) / 1000 + 0

/** [time] on the timeline's grid: whole ticks, or seconds a tick apart. Never before 0. */
export function snap(unit: TimeUnit, time: number): number {
  const at = Math.max(0, time)
  return unit === 'ticks'
    ? Math.round(at)
    : roundSeconds(Math.round(at * TICKS_PER_SECOND) / TICKS_PER_SECOND)
}

/** A time as the timeline writes it: `0.5s`, `tick 20`. */
export const formatTime = (unit: TimeUnit, time: number) =>
  unit === 'ticks' ? `tick ${time}` : `${roundSeconds(time)}s`

const NICE: Record<TimeUnit, number[]> = {
  seconds: [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600],
  ticks: [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000],
}

/** The ruler's labelled times: about ten, a round step apart, from 0 to the length. */
export function rulerMarks({ unit, length }: TimeScale): number[] {
  const rough = length / 10
  const steps = NICE[unit]
  const step = steps.find((it) => it >= rough) ?? steps[steps.length - 1]!
  const marks: number[] = []
  for (let i = 0; i * step <= length + 1e-9; i += 1) marks.push(roundSeconds(i * step))
  return marks
}

/** Where [time] is across the timeline, 0 to 1 (clamped). */
export const fractionOf = ({ length }: TimeScale, time: number) =>
  Math.min(1, Math.max(0, time / length))
