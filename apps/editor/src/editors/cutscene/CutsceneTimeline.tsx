/**
 * A cutscene's timeline, on the shared `Timeline` in seconds (the same
 * component and key model as a centity's animation): a row for the camera's
 * position, one for its rotation and one for the cues. Play/pause/scrub (each
 * frame is the server's own `CameraPath`, through `cutsceneDirector`), key the
 * camera where it is at the playhead, drag a key to retime it, re-ease it,
 * delete it. A key drag is one undo gesture.
 */
import {
  EasingValues,
  type CutsceneFile,
  type CutsceneResult,
  type Easing,
  type Vec3,
} from '@/core/format'
import { useApp } from '@/state/providers'
import { NumberInput, Vec3Field } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Divider } from '@/ui/layout'
import { Timeline, type KeyRef, type TimelineTrack } from '@/editors/shared/timeline/Timeline'
import { roundSeconds } from '@/editors/shared/timeline/time'
import { usePlayback } from '@/editors/shared/timeline/usePlayback'
import styles from '@/editors/shared/timeline/Timeline.module.css'
import {
  addCue,
  deleteKey,
  keyLabel,
  keysOf,
  lengthOf,
  moveKey,
  setCue,
  setEasing,
  setPosition,
  setRotation,
  TRACKS,
  type CutscenePick,
  type Track,
} from './ops'
import type { PreviewState } from './view'

type Edit = (recipe: (draft: CutsceneFile) => void) => void

/** Where a new position key goes when there's no camera to read it from. */
const ORIGIN: Vec3 = [0, 64, 0]

/** The timeline's length: the cutscene's own, never less than a second. */
export const timelineLength = (model: CutsceneFile) => Math.max(1, lengthOf(model))

export function CutsceneTimeline({
  path,
  model,
  shot,
  preview,
  setPreview,
  selected,
  onSelect,
  edit,
}: {
  path: string
  model: CutsceneFile
  /** The camera at the playhead, to key from. */
  shot: CutsceneResult
  preview: PreviewState
  setPreview: (patch: Partial<PreviewState>) => void
  selected: CutscenePick | null
  onSelect: (pick: CutscenePick | null) => void
  edit: Edit
}) {
  const { workspace } = useApp()
  const length = timelineLength(model)
  const time = Math.min(preview.time, length)
  const ws = () => workspace.getState()

  usePlayback({
    playing: preview.playing,
    from: preview.time,
    restart: [length, path],
    onFrame: (from, elapsed) => {
      const next = from + elapsed
      if (next >= length) setPreview({ time: length, playing: false })
      else setPreview({ time: next })
    },
  })

  const camera = shot.type === 'shot' ? shot : null
  const lastPosition = model.camera?.position?.at(-1)?.value

  /** Keys the camera at the playhead where it is now. */
  const keyPosition = () => {
    let index = 0
    edit((draft) => {
      index = setPosition(draft, time, camera?.position ?? lastPosition ?? ORIGIN)
    })
    onSelect({ track: 'position', index })
  }
  const keyRotation = () => {
    let index = 0
    edit((draft) => {
      const last = model.camera?.rotation?.at(-1)
      index = setRotation(
        draft,
        time,
        camera?.yaw ?? last?.yaw ?? 0,
        camera?.pitch ?? last?.pitch ?? 0,
      )
    })
    onSelect({ track: 'rotation', index })
  }
  const cue = () => {
    let index = 0
    edit((draft) => {
      index = addCue(draft, time)
    })
    onSelect({ track: 'cues', index })
  }

  const tracks: TimelineTrack[] = TRACKS.map((track) => ({
    id: track,
    label: track,
    keys: keysOf(model, track),
    selected: false,
  }))
  const keyOf = (ref: KeyRef): CutscenePick => ({ track: ref.track as Track, index: ref.index })

  const key = selected ? keysOf(model, selected.track)[selected.index] : undefined
  const position =
    selected?.track === 'position' ? model.camera?.position?.[selected.index] : undefined
  const rotation =
    selected?.track === 'rotation' ? model.camera?.rotation?.[selected.index] : undefined
  const cueKey = selected?.track === 'cues' ? model.cues?.[selected.index] : undefined

  /** The key's own JSON path, which its fields' `data-path`s start with. */
  const at = selected
    ? selected.track === 'cues'
      ? `cues[${selected.index}]`
      : `camera.${selected.track}[${selected.index}]`
    : ''

  const retime = (pick: CutscenePick, to: number) => {
    let index = pick.index
    edit((draft) => {
      index = moveKey(draft, pick.track, pick.index, to)
    })
    onSelect({ ...pick, index })
    return index
  }

  return (
    <Timeline
      scale={{ unit: 'seconds', length }}
      time={time}
      onSeek={(to) => setPreview({ time: roundSeconds(to), playing: false })}
      playback={{
        playing: preview.playing,
        onToggle: () =>
          setPreview({
            playing: !preview.playing,
            time: preview.time >= length ? 0 : preview.time,
          }),
        time: (
          <span className={styles.time} aria-label="Playhead time">
            {time.toFixed(2)}s
          </span>
        ),
      }}
      toolbar={
        <>
          <Divider />
          <Button
            size="small"
            icon="key"
            title="Key the camera's position where it is"
            onClick={keyPosition}
          >
            Key position
          </Button>
          <Button
            size="small"
            icon="key"
            title="Key the camera's look where it is"
            onClick={keyRotation}
          >
            Key rotation
          </Button>
          <Button size="small" icon="plus" title="A cue at the playhead" onClick={cue}>
            Add cue
          </Button>
        </>
      }
      tracks={tracks}
      empty="Add a key."
      selectedKey={selected ? { track: selected.track, index: selected.index } : null}
      onSelectKey={(ref) => onSelect(keyOf(ref))}
      onMoveKey={(ref, to) => retime(keyOf(ref), to)}
      onDeleteKey={(ref) => {
        const pick = keyOf(ref)
        edit((draft) => deleteKey(draft, pick.track, pick.index))
        onSelect(null)
      }}
      gesture={{ begin: () => ws().beginGesture(path), end: () => ws().endGesture(path) }}
      keyName={(track, index) => keyLabel(track.id as Track, index)}
      editor={
        key &&
        selected && (
          <div className={styles.keyEditor} aria-label="Key">
            <div className={styles.keyTitle}>{keyLabel(selected.track, selected.index)}</div>
            <label className={styles.inline}>
              Time
              <NumberInput
                label="Key time"
                dataPath={`${at}.time`}
                value={key.time}
                step={0.05}
                min={0}
                onChange={(value) => retime(selected, value ?? 0)}
              />
            </label>
            {position && (
              <Vec3Field
                label="Position"
                dataPath={`${at}.value`}
                value={position.value}
                defaultValue={ORIGIN}
                step={0.25}
                onChange={(value) =>
                  edit((draft) => {
                    const target = draft.camera?.position?.[selected.index]
                    if (target) target.value = value
                  })
                }
                onGestureStart={() => ws().beginGesture(path)}
                onGestureEnd={() => ws().endGesture(path)}
              />
            )}
            {rotation && (
              <>
                <label className={styles.inline}>
                  Yaw
                  <NumberInput
                    label="Yaw"
                    dataPath={`${at}.yaw`}
                    value={rotation.yaw}
                    step={5}
                    onChange={(value) =>
                      edit((draft) => {
                        const target = draft.camera?.rotation?.[selected.index]
                        if (target) target.yaw = value ?? 0
                      })
                    }
                    onGestureStart={() => ws().beginGesture(path)}
                    onGestureEnd={() => ws().endGesture(path)}
                  />
                </label>
                <label className={styles.inline}>
                  Pitch
                  <NumberInput
                    label="Pitch"
                    dataPath={`${at}.pitch`}
                    value={rotation.pitch}
                    step={5}
                    onChange={(value) =>
                      edit((draft) => {
                        const target = draft.camera?.rotation?.[selected.index]
                        if (target) target.pitch = value ?? 0
                      })
                    }
                    onGestureStart={() => ws().beginGesture(path)}
                    onGestureEnd={() => ws().endGesture(path)}
                  />
                </label>
              </>
            )}
            {cueKey && (
              <>
                <label className={styles.inline}>
                  Event
                  <input
                    aria-label="Cue event"
                    data-path={`${at}.event`}
                    defaultValue={cueKey.event ?? ''}
                    key={`event:${selected.index}:${cueKey.event ?? ''}`}
                    onBlur={(event) =>
                      edit((draft) =>
                        setCue(draft, selected.index, { event: event.target.value.trim() }),
                      )
                    }
                  />
                </label>
                <label className={styles.inline}>
                  Text
                  <input
                    aria-label="Cue text"
                    data-path={`${at}.text`}
                    defaultValue={cueKey.text ?? ''}
                    key={`text:${selected.index}:${cueKey.text ?? ''}`}
                    onBlur={(event) =>
                      edit((draft) => setCue(draft, selected.index, { text: event.target.value }))
                    }
                  />
                </label>
                <label className={styles.inline}>
                  Shown for
                  <NumberInput
                    label="Cue duration"
                    dataPath={`${at}.duration`}
                    value={cueKey.duration}
                    placeholder="3"
                    step={0.5}
                    min={0}
                    onChange={(value) =>
                      edit((draft) => setCue(draft, selected.index, { duration: value }))
                    }
                  />
                </label>
              </>
            )}
            {selected.track !== 'cues' && (
              <label className={styles.inline}>
                Easing
                <select
                  aria-label="Easing"
                  data-path={`${at}.easing`}
                  value={key.easing ?? 'linear'}
                  onChange={(event) =>
                    edit((draft) =>
                      setEasing(
                        draft,
                        selected.track,
                        selected.index,
                        event.target.value as Easing,
                      ),
                    )
                  }
                >
                  {EasingValues.map((easing) => (
                    <option key={easing} value={easing}>
                      {easing}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <Button
              size="small"
              icon="trash"
              onClick={() => {
                edit((draft) => deleteKey(draft, selected.track, selected.index))
                onSelect(null)
              }}
            >
              Delete key
            </Button>
          </div>
        )
      }
    />
  )
}
