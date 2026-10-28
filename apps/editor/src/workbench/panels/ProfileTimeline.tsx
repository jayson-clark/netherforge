/**
 * The profiler's tick timeline: one stacked bar per tick, a segment per step
 * of NetherForge's tick in the order they run, newest on the right. Hovering
 * a tick says what each step took and which scripts cost the most. Drawn on a
 * canvas (a hundred and twenty bars redrawn once a second); the legend names
 * each step, so colour is never the only way to tell them apart.
 */
import { useEffect, useRef, useState } from 'react'
import type { ProfileTick } from '@netherforge/format/types'
import { TIMELINE_TICKS } from '@/core/store/profiler'
import styles from './Profiler.module.css'

/**
 * The tick's steps, in the order they run, each with its colour: the
 * categorical palette (dark steps) in its fixed order, checked for colour
 * vision deficiencies against the panel's background.
 */
export const PHASES: { name: string; label: string; color: string }[] = [
  { name: 'timers', label: 'Timers', color: '#3987e5' },
  { name: 'async', label: 'Async', color: '#d95926' },
  { name: 'events', label: 'Events', color: '#199e70' },
  { name: 'world', label: 'World', color: '#c98500' },
  { name: 'effects', label: 'Effects', color: '#d55181' },
  { name: 'upkeep', label: 'Upkeep', color: '#008300' },
  { name: 'accounts', label: 'Accounts', color: '#9085e9' },
  { name: 'save', label: 'Save', color: '#e66767' },
]

const HEIGHT = 72
/** The surface between segments and bars. */
const GAP = 1

export const ms = (nanos: number) => (nanos / 1_000_000).toFixed(nanos >= 10_000_000 ? 1 : 2)

/** The scale's top: the slowest tick shown, rounded up to a step that reads well. */
export function scaleTop(ticks: ProfileTick[]): number {
  const slowest = Math.max(...ticks.map((it) => it.nanos), 100_000)
  const steps = [0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 500, 1000].map((it) => it * 1e6)
  return steps.find((it) => it >= slowest) ?? slowest
}

export function ProfileTimeline({ ticks }: { ticks: ProfileTick[] }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [width, setWidth] = useState(0)
  const [hovered, setHovered] = useState<ProfileTick | null>(null)
  const top = scaleTop(ticks)
  const slot = width / TIMELINE_TICKS

  useEffect(() => {
    const element = canvas.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => setWidth(entry!.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context || width === 0) return
    const scale = window.devicePixelRatio || 1
    element.width = Math.round(width * scale)
    element.height = Math.round(HEIGHT * scale)
    context.setTransform(scale, 0, 0, scale, 0, 0)
    context.clearRect(0, 0, width, HEIGHT)
    // Newest on the right: the last tick fills the last slot.
    const first = TIMELINE_TICKS - ticks.length
    ticks.forEach((tick, i) => {
      const x = (first + i) * slot
      let y = HEIGHT
      for (const phase of PHASES) {
        const height = ((tick.phases[phase.name] ?? 0) / top) * HEIGHT
        if (height < 0.5) continue
        context.fillStyle = phase.color
        context.fillRect(x, y - height, Math.max(slot - GAP, 1), Math.max(height - GAP, 0.5))
        y -= height
      }
    })
  }, [ticks, width, slot, top])

  const hover = (clientX: number) => {
    const box = canvas.current?.getBoundingClientRect()
    if (!box || slot === 0) return
    const index = Math.floor((clientX - box.left) / slot) - (TIMELINE_TICKS - ticks.length)
    setHovered(ticks[index] ?? null)
  }

  return (
    <div className={styles.timeline}>
      <div className={styles.chart}>
        <span className={styles.scale}>{ms(top)} ms</span>
        <canvas
          ref={canvas}
          className={styles.canvas}
          style={{ height: HEIGHT }}
          role="img"
          aria-label={`The last ${ticks.length} ticks, by step; the slowest took ${ms(Math.max(0, ...ticks.map((it) => it.nanos)))} ms`}
          onPointerMove={(event) => hover(event.clientX)}
          onPointerLeave={() => setHovered(null)}
        />
        {hovered && (
          <div
            className={styles.tooltip}
            role="tooltip"
            style={{
              left: Math.min(
                (TIMELINE_TICKS - ticks.length + ticks.indexOf(hovered)) * slot,
                width - 180,
              ),
            }}
          >
            <strong>
              Tick {hovered.tick}: {ms(hovered.nanos)} ms
            </strong>
            {PHASES.filter((phase) => (hovered.phases[phase.name] ?? 0) > 0).map((phase) => (
              <div key={phase.name} className={styles.tooltipRow}>
                <span className={styles.swatch} style={{ background: phase.color }} />
                {phase.label}{' '}
                <span className={styles.number}>{ms(hovered.phases[phase.name]!)}</span>
              </div>
            ))}
            {(hovered.top ?? []).map((scope) => (
              <div key={scope.scope} className={styles.tooltipRow}>
                {scope.scope} <span className={styles.number}>{ms(scope.nanos)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      <ul className={styles.legend} aria-label="Steps of a tick">
        {PHASES.map((phase) => (
          <li key={phase.name}>
            <span className={styles.swatch} style={{ background: phase.color }} />
            {phase.label}
          </li>
        ))}
      </ul>
    </div>
  )
}
