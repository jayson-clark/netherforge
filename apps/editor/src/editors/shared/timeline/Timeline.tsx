/**
 * The keyframe timeline under a preview, for any editor and either time
 * model (`time.ts`: seconds for a centity's clips, ticks for a particle
 * effect; a cutscene's camera path next): a toolbar with play/pause and
 * the playhead's time, a ruler, a row per track with its keys (and
 * whatever else the editor draws on it), the playhead across them, and the
 * editor's key fields beside them.
 *
 * Keyboard: the playhead is react-aria's `Slider` (arrows a tick, Page
 * Up/Down further, Home/End). Every key is a slider of its own, moved with
 * react-aria's `useMove`, so the pointer and the arrow keys share one path:
 * Left/Right move it a tick (Shift: ten), Delete removes it, focusing it
 * (Tab, a click) selects it. A drag or an arrow press is one undo gesture.
 * The editor owns the keys: this only says which key moved where, and
 * which index it has after the move (keys are re-sorted).
 */
import {
  useLayoutEffect,
  useRef,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { Slider, SliderThumb, SliderTrack } from 'react-aria-components'
import { mergeProps } from 'react-aria/mergeProps'
import { useMove } from 'react-aria/useMove'
import type { Easing } from '@/core/format'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { formatTime, fractionOf, rulerMarks, snap, stepOf, type TimeScale } from './time'
import styles from './Timeline.module.css'

/** A key as the timeline draws it. */
export interface TimelineKey {
  time: number
  easing?: Easing
}

/** One row. */
export interface TimelineTrack {
  id: string
  label: string
  /** The row's accessible name: `<label> track` without one. */
  name?: string
  keys?: readonly TimelineKey[]
  /** Drawn on the row under its keys (an emitter's window and bursts); [at] places a time. */
  band?: (at: (time: number) => string) => ReactNode
  /** A row under the one before it (a curve under its emitter). */
  nested?: boolean
  selected?: boolean
  /** Its label picks it, and so does pressing on its row. */
  onSelect?: () => void
  /** Double-clicking the row: a key at the playhead, which the press moved there. */
  onAddKey?: () => void
}

/** A key by its track and its index in the track. */
export interface KeyRef {
  track: string
  index: number
}

export interface TimelineProps {
  scale: TimeScale
  /** The playhead. */
  time: number
  onSeek: (time: number) => void
  /** With play/pause and the playhead's time; null without (nothing to play). */
  playback: { playing: boolean; onToggle: () => void; time: ReactNode } | null
  /** In the toolbar before play/pause (what's played). */
  leading?: ReactNode
  /** In the toolbar after the time. */
  toolbar?: ReactNode
  tracks: TimelineTrack[]
  /** In the label column when there are no tracks. */
  empty?: ReactNode
  selectedKey: KeyRef | null
  onSelectKey: (key: KeyRef) => void
  /** Moves a key; returns its index after the move. */
  onMoveKey: (key: KeyRef, time: number) => number
  onDeleteKey: (key: KeyRef) => void
  /** A drag (or an arrow press) on a key is one undo step. */
  gesture: { begin: () => void; end: () => void }
  /** A key's accessible name (`Keyframe top rotation`). */
  keyName: (track: TimelineTrack, index: number) => string
  /** Beside the tracks: the selected key's fields. */
  editor?: ReactNode
  /** Instead of the tracks (nothing to show yet). */
  placeholder?: ReactNode
}

/** How the playhead's value is read out: in seconds, or as a plain number of ticks. */
const SPOKEN: Record<TimeScale['unit'], Intl.NumberFormatOptions> = {
  seconds: { style: 'unit', unit: 'second', maximumFractionDigits: 3 },
  ticks: { maximumFractionDigits: 0 },
}

/** The time under [clientX] in [area]. */
const timeAt = (area: HTMLElement, scale: TimeScale, clientX: number) => {
  const rect = area.getBoundingClientRect()
  return ((clientX - rect.left) / Math.max(1, rect.width)) * scale.length
}

export function Timeline(props: TimelineProps) {
  const { scale, time, onSeek, playback, leading, toolbar, placeholder } = props
  return (
    <div className={styles.timeline} aria-label="Timeline">
      <div className={styles.toolbar}>
        {leading}
        {playback && (
          <>
            <IconButton
              icon={playback.playing ? 'pause' : 'play'}
              label={playback.playing ? 'Pause' : 'Play'}
              onClick={playback.onToggle}
            />
            {playback.time}
          </>
        )}
        {toolbar}
      </div>
      {placeholder ?? <Body {...props} scale={scale} time={time} onSeek={onSeek} />}
    </div>
  )
}

function Body({
  scale,
  time,
  onSeek,
  tracks,
  empty,
  selectedKey,
  onSelectKey,
  onMoveKey,
  onDeleteKey,
  gesture,
  keyName,
  editor,
}: TimelineProps) {
  const area = useRef<HTMLDivElement>(null)
  const at = (t: number) => `${fractionOf(scale, t) * 100}%`
  const step = stepOf(scale.unit)

  /** Pressing on a row (not a key) moves the playhead there, and follows the pointer. */
  const scrub = (event: React.PointerEvent) => {
    if (event.button !== 0 || !area.current) return
    const element = area.current
    const seek = (clientX: number) => onSeek(snap(scale.unit, timeAt(element, scale, clientX)))
    seek(event.clientX)
    const move = (e: PointerEvent) => seek(e.clientX)
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  /** The key a drag holds: found again after every move, since moving re-sorts. */
  const held = useRef<KeyRef | null>(null)
  const refocus = useRef<string | null>(null)
  useLayoutEffect(() => {
    const id = refocus.current
    if (id === null) return
    refocus.current = null
    area.current?.querySelector<HTMLElement>(`[data-key="${CSS.escape(id)}"]`)?.focus()
  })
  const move = (key: KeyRef, to: number, keyboard: boolean) => {
    const index = onMoveKey(key, snap(scale.unit, Math.min(scale.length, to)))
    held.current = { track: key.track, index }
    // The arrow keys keep focus on the key, which may now be at another index (focused once
    // the move has rendered, before the next key press).
    if (keyboard && index !== key.index) refocus.current = `${key.track}:${index}`
  }

  return (
    <div className={cx(styles.body, !editor && styles.bodyNoEditor)}>
      <div className={styles.labels}>
        <div className={styles.rulerSpacer} />
        {tracks.map((track) =>
          track.onSelect ? (
            <button
              key={track.id}
              type="button"
              className={cx(
                styles.label,
                styles.labelButton,
                track.nested && styles.nested,
                track.selected && styles.labelSelected,
              )}
              onClick={track.onSelect}
            >
              {track.label}
            </button>
          ) : (
            <div key={track.id} className={cx(styles.label, track.nested && styles.nested)}>
              {track.label}
            </div>
          ),
        )}
        {tracks.length === 0 && <div className={cx(styles.label, styles.labelEmpty)}>{empty}</div>}
      </div>
      <div className={styles.area} ref={area}>
        <Slider
          aria-label="Playhead"
          className={styles.ruler}
          minValue={0}
          maxValue={scale.length}
          step={step}
          value={Math.min(time, scale.length)}
          onChange={onSeek}
          formatOptions={SPOKEN[scale.unit]}
        >
          <SliderTrack className={styles.rulerTrack}>
            {rulerMarks(scale).map((mark) => (
              <span key={mark} className={styles.mark} style={{ left: at(mark) }}>
                {mark}
              </span>
            ))}
            <SliderThumb className={styles.thumb} />
          </SliderTrack>
        </Slider>
        {tracks.map((track) => (
          <div
            key={track.id}
            className={cx(styles.track, track.nested && styles.nestedTrack)}
            aria-label={track.name ?? `${track.label} track`}
            title={track.onAddKey ? 'Double-click to add a key here' : undefined}
            onPointerDown={(event) => {
              track.onSelect?.()
              scrub(event)
            }}
            onDoubleClick={track.onAddKey}
          >
            {track.band?.(at)}
            {track.keys?.map((key, index) => (
              <KeyHandle
                key={index}
                id={`${track.id}:${index}`}
                name={keyName(track, index)}
                timeKey={key}
                scale={scale}
                left={at(key.time)}
                selected={selectedKey?.track === track.id && selectedKey.index === index}
                onSelect={() => onSelectKey({ track: track.id, index })}
                onDelete={() => onDeleteKey({ track: track.id, index })}
                onMoveStart={() => {
                  held.current = { track: track.id, index }
                  gesture.begin()
                }}
                onMove={(to, keyboard) => {
                  if (held.current) move(held.current, to, keyboard)
                }}
                onMoveEnd={() => {
                  held.current = null
                  gesture.end()
                }}
                width={() => area.current?.getBoundingClientRect().width ?? 1}
              />
            ))}
          </div>
        ))}
        <div className={styles.playhead} style={{ left: at(time) }} />
      </div>
      {editor}
    </div>
  )
}

/** One key: a slider for its time, dragged or moved with the arrows. */
function KeyHandle({
  id,
  name,
  timeKey,
  scale,
  left,
  selected,
  onSelect,
  onDelete,
  onMoveStart,
  onMove,
  onMoveEnd,
  width,
}: {
  id: string
  name: string
  timeKey: TimelineKey
  scale: TimeScale
  left: string
  selected: boolean
  onSelect: () => void
  onDelete: () => void
  onMoveStart: () => void
  /** To [time] (unsnapped); [keyboard] for an arrow press. */
  onMove: (time: number, keyboard: boolean) => void
  onMoveEnd: () => void
  /** The track's width in pixels, for a drag. */
  width: () => number
}) {
  /** Where a drag started and how far the pointer has gone. */
  const drag = useRef({ from: 0, pixels: 0 })
  const { time, easing } = timeKey
  const { moveProps } = useMove({
    onMoveStart: () => {
      drag.current = { from: time, pixels: 0 }
      onMoveStart()
    },
    onMove: (event) => {
      if (event.pointerType === 'keyboard') {
        if (event.deltaX === 0) return
        const steps = event.deltaX * (event.shiftKey ? 10 : 1)
        onMove(time + steps * stepOf(scale.unit), true)
        return
      }
      drag.current.pixels += event.deltaX
      onMove(drag.current.from + (drag.current.pixels / Math.max(1, width())) * scale.length, false)
    },
    onMoveEnd,
  })

  const own = {
    // `useMove` keeps the press from focusing the key; focusing it is what selects it.
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => event.currentTarget.focus(),
    onFocus: () => {
      if (!selected) onSelect()
    },
    onKeyDown: (event: KeyboardEvent) => {
      if (event.key === 'Delete' || event.key === 'Backspace') {
        // Not the editor's Delete (a centity's deletes its selected nodes).
        event.preventDefault()
        event.stopPropagation()
        onDelete()
      }
    },
    // A key isn't a place to add one.
    onDoubleClick: (event: React.MouseEvent) => event.stopPropagation(),
  }

  return (
    <div
      role="slider"
      tabIndex={0}
      aria-label={name}
      aria-orientation="horizontal"
      aria-valuemin={0}
      aria-valuemax={scale.length}
      aria-valuenow={time}
      aria-valuetext={`${formatTime(scale.unit, time)}, ${easing ?? 'linear'}`}
      data-key={id}
      data-selected={selected || undefined}
      data-easing={easing ?? 'linear'}
      className={styles.key}
      style={{ left } as CSSProperties}
      title={`${formatTime(scale.unit, time)} ${easing ?? 'linear'}`}
      {...mergeProps(moveProps, own)}
    />
  )
}
