/**
 * The effect's timeline, on the shared `Timeline` in ticks: the playhead,
 * play/pause and the preview's loop toggle; a row per emitter showing its
 * window and bursts; under the selected emitter, a row per curve channel
 * with its keys. Double-click a curve row to key it there; drag a
 * key (or move it with the arrow keys) to retime it in whole ticks, one
 * undo step each.
 */
import {
  particleCurveAt,
  type ColorKey,
  type EmitterDef,
  type NumberKey,
  type ParticleDataKind,
  type ParticleEffectFile,
} from '@/core/format'
import { useApp } from '@/state/providers'
import { cx } from '@/ui/cx'
import { Divider } from '@/ui/layout'
import { Timeline, type KeyRef, type TimelineTrack } from '@/editors/shared/timeline/Timeline'
import styles from '@/editors/shared/timeline/Timeline.module.css'
import {
  addCurve,
  addKey,
  burstTicks,
  channelsFor,
  constantOf,
  isCurved,
  kindOf,
  moveKey,
  removeKey,
  type Channel,
  type ParticlePick,
} from './ops'
import type { PreviewState } from './view'

type Edit = (recipe: (draft: ParticleEffectFile) => void) => void

/** The timeline tick the preview's playhead shows. */
export function playheadTick(preview: PreviewState, duration: number): number {
  return preview.loop ? preview.tick % duration : Math.min(preview.tick, duration)
}

/** A curve row's id: `curve:ring:radius`. */
const curveId = (emitter: string, channel: Channel) => `curve:${emitter}:${channel}`

export function ParticleTimeline({
  path,
  model,
  pick,
  particles,
  preview,
  setPreview,
  onSelect,
  edit,
}: {
  path: string
  model: ParticleEffectFile
  pick: ParticlePick
  particles: Record<string, ParticleDataKind> | null
  preview: PreviewState
  setPreview: (update: (state: PreviewState) => PreviewState) => void
  onSelect: (pick: ParticlePick) => void
  edit: Edit
}) {
  const { workspace } = useApp()
  const duration = Math.max(1, model.duration)
  const playhead = playheadTick(preview, duration)
  const names = Object.keys(model.emitters ?? {}).sort()
  const selected = pick.kind === 'effect' ? null : pick.emitter
  const emitter = selected ? model.emitters?.[selected] : undefined
  const channels = emitter ? channelsFor(emitter, kindOf(particles, emitter.particle)) : []
  const curved = emitter ? channels.filter((channel) => isCurved(emitter, channel)) : []
  const addable = emitter ? channels.filter((channel) => !isCurved(emitter, channel)) : []

  const editEmitter = (name: string, recipe: (draft: EmitterDef) => void) =>
    edit((draft) => {
      const target = draft.emitters?.[name]
      if (target) recipe(target)
    })

  /** A new key at the playhead, holding the value the curve (or the constant) has there. */
  const keyAtPlayhead = (name: string, channel: Channel) => {
    const current = model.emitters?.[name]
    if (!current) return
    const keys = current.curves?.[channel]
    const value =
      keys && keys.length > 0
        ? channel === 'color'
          ? particleCurveAt(keys as ColorKey[], playhead)
          : particleCurveAt(keys as NumberKey[], playhead)
        : constantOf(current, channel)
    let index = 0
    editEmitter(name, (target) => {
      index = addKey(target, channel, playhead, value ?? constantOf(current, channel))
    })
    onSelect({ kind: 'key', emitter: name, channel, index })
  }

  const tracks: TimelineTrack[] = names.flatMap((name) => {
    const current = model.emitters![name]!
    const start = current.start ?? 0
    const end = Math.min(current.end ?? duration, duration)
    const row: TimelineTrack = {
      id: `emitter:${name}`,
      label: name,
      selected: name === selected,
      onSelect: () => onSelect({ kind: 'emitter', emitter: name }),
      band: (at) => (
        <>
          <div
            className={cx(styles.emitterWindow, name === selected && styles.emitterWindowSelected)}
            style={{ left: at(start), width: `calc(${at(end)} - ${at(start)})` }}
            title={`${name}: ticks ${start} to ${end}`}
          />
          {burstTicks(current, duration).map((t) => (
            <span key={t} className={styles.burstTick} style={{ left: at(t) }} />
          ))}
        </>
      ),
    }
    if (name !== selected) return [row]
    return [
      row,
      ...curved.map((channel): TimelineTrack => ({
        id: curveId(name, channel),
        label: channel,
        name: `${name} ${channel} curve`,
        nested: true,
        keys: current.curves?.[channel] ?? [],
        onAddKey: () => keyAtPlayhead(name, channel),
      })),
    ]
  })

  /** A key on one of the selected emitter's curve rows. */
  const keyOf = (ref: KeyRef) => {
    const channel = curved.find((it) => selected && curveId(selected, it) === ref.track)
    return selected && channel ? { emitter: selected, channel, index: ref.index } : null
  }

  return (
    <Timeline
      scale={{ unit: 'ticks', length: duration }}
      time={playhead}
      onSeek={(tick) => setPreview((state) => ({ ...state, tick, playing: false }))}
      playback={{
        playing: preview.playing,
        onToggle: () =>
          setPreview((state) => ({
            ...state,
            playing: !state.playing,
            // Play from the start again once a non-looping preview has run out.
            tick: !state.playing && !state.loop && state.tick >= duration ? 0 : state.tick,
          })),
        time: (
          <span className={styles.time} aria-label="Playhead tick">
            {playhead} / {duration}
          </span>
        ),
      }}
      toolbar={
        <>
          <label
            className={styles.inline}
            title="Loop the preview (the file's own loop is in the inspector)"
          >
            <input
              type="checkbox"
              aria-label="Loop preview"
              checked={preview.loop}
              onChange={(event) =>
                setPreview((state) => ({ ...state, loop: event.target.checked, tick: 0 }))
              }
            />
            Loop
          </label>
          {emitter && selected && (
            <>
              <Divider />
              <select
                aria-label="Add curve"
                value=""
                disabled={addable.length === 0}
                onChange={(event) => {
                  const channel = event.target.value as Channel
                  if (!channel) return
                  let index = 0
                  editEmitter(selected, (target) => {
                    index = addCurve(target, channel, playhead)
                  })
                  onSelect({ kind: 'key', emitter: selected, channel, index })
                }}
              >
                <option value="">{addable.length ? 'Add curve…' : 'No more curves'}</option>
                {addable.map((channel) => (
                  <option key={channel} value={channel}>
                    {channel}
                  </option>
                ))}
              </select>
            </>
          )}
        </>
      }
      tracks={tracks}
      empty="Add an emitter."
      selectedKey={
        pick.kind === 'key'
          ? { track: curveId(pick.emitter, pick.channel), index: pick.index }
          : null
      }
      onSelectKey={(ref) => {
        const key = keyOf(ref)
        if (key) onSelect({ kind: 'key', ...key })
      }}
      onMoveKey={(ref, tick) => {
        const key = keyOf(ref)
        if (!key) return ref.index
        let index = key.index
        editEmitter(key.emitter, (target) => {
          index = moveKey(target, key.channel, key.index, tick)
        })
        onSelect({ kind: 'key', ...key, index })
        return index
      }}
      onDeleteKey={(ref) => {
        const key = keyOf(ref)
        if (!key) return
        editEmitter(key.emitter, (target) => removeKey(target, key.channel, key.index))
        onSelect({ kind: 'emitter', emitter: key.emitter })
      }}
      gesture={{
        begin: () => workspace.getState().beginGesture(path),
        end: () => workspace.getState().endGesture(path),
      }}
      keyName={(track, index) => `${track.label} key ${index + 1}`}
    />
  )
}
