/**
 * A centity's animation timeline, on the shared `Timeline` in seconds: pick
 * a clip, play/pause/scrub (each frame is a `poseCentity` at that time), and
 * add, move, retime, re-ease and delete keyframes. A keyframe drag is one
 * undo gesture.
 */
import { useState } from 'react'
import * as THREE from 'three'
import {
  EasingValues,
  type AnimationDef,
  type AnimationInfo,
  type CentityFile,
  type Channel,
  type Easing,
  type PosedNode,
} from '@/core/format'
import { NODE_NAME_PATTERN } from '@/core/paths'
import { useApp } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { NumberInput, Vec3Field } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Divider } from '@/ui/layout'
import { Empty } from '@/ui/text'
import { Timeline, type KeyRef, type TimelineTrack } from '@/editors/shared/timeline/Timeline'
import { roundSeconds } from '@/editors/shared/timeline/time'
import { usePlayback } from '@/editors/shared/timeline/usePlayback'
import styles from '@/editors/shared/timeline/Timeline.module.css'
import { localChannels } from './gizmo'
import {
  addAnimation,
  deleteAnimation,
  CHANNELS,
  channelDefault,
  deleteKey,
  moveKey,
  renameAnimation,
  setEasing,
  setKeyframe,
} from './ops'
import { usePrimary, useView, useViewState } from '@/editors/views'
import type { KeyRef as CentityKey } from './view'

/** A track's id: `top.rotation`. */
const trackId = (node: string, channel: Channel) => `${node}.${channel}`

export function AnimationTimeline({
  path,
  model,
  animations,
  nodes,
}: {
  path: string
  model: CentityFile
  animations: AnimationInfo[]
  nodes: PosedNode[]
}) {
  const { workspace } = useApp()
  const view = useViewState('centity', path)
  const changeView = useView('centity', path)
  const selectedNode = usePrimary('centity', path)
  const [keyChannel, setKeyChannel] = useState<Channel>('translation')
  const clipName = view.clip && model.animations?.[view.clip] ? view.clip : null
  const clip = clipName ? model.animations![clipName]! : undefined
  const info = animations.find((it) => it.name === clipName)
  const length = Math.max(info?.length ?? 0, clip?.length ?? 0, 1)
  const ws = () => workspace.getState()

  const editClip = (recipe: (clip: AnimationDef) => void) =>
    ws().edit<CentityFile>(path, (draft) => {
      const target = clipName ? draft.animations?.[clipName] : undefined
      if (target) recipe(target)
    })

  // The clip plays in seconds, as its loop mode says once it reaches its end.
  usePlayback({
    playing: view.playing && !!info,
    from: view.time,
    restart: [info?.name, info?.length, info?.loop, path],
    onFrame: (from, elapsed) => {
      if (!info) return
      const end = Math.max(info.length, 0.001)
      const next = from + elapsed
      if (info.loop === 'loop') changeView.update({ time: next % end })
      else if (next < end) changeView.update({ time: next })
      else changeView.update({ time: info.loop === 'hold' ? end : 0, playing: false })
    },
  })

  const addClip = () => {
    let name = ''
    ws().edit<CentityFile>(path, (draft) => {
      name = addAnimation(draft)
    })
    changeView.update({ clip: name, time: 0, selectedKey: null })
  }

  const renameClip = async () => {
    if (!clipName) return
    const next = await ask.prompt({
      title: `Rename animation "${clipName}"`,
      label: 'Animation name',
      initial: clipName,
      confirmLabel: 'Rename',
      validate: (value) =>
        !NODE_NAME_PATTERN.test(value)
          ? 'Letters, digits, _ and -, at most 64 characters'
          : value !== clipName && model.animations?.[value]
            ? 'An animation with that name exists'
            : null,
    })
    if (!next || next === clipName) return
    ws().edit<CentityFile>(path, (draft) => renameAnimation(draft, clipName, next))
    changeView.update({ clip: next })
  }

  const deleteClip = async () => {
    if (!clipName) return
    const ok = await ask.confirm({
      title: 'Delete animation',
      message: `Delete "${clipName}" and all its keyframes?`,
      confirmLabel: 'Delete',
      danger: true,
    })
    if (ok !== true) return
    ws().edit<CentityFile>(path, (draft) => deleteAnimation(draft, clipName))
    changeView.update({ clip: null, time: 0, playing: false, selectedKey: null })
  }

  const addKey = () => {
    if (!selectedNode || !clipName) return
    // The node's current local value, as posed at the playhead.
    const posed = nodes.find((it) => it.name === selectedNode)
    const parentName = model.nodes?.[selectedNode]?.parent
    const parent = parentName ? nodes.find((it) => it.name === parentName) : undefined
    const value = posed
      ? localChannels(
          new THREE.Matrix4().fromArray(posed.matrix),
          parent ? new THREE.Matrix4().fromArray(parent.matrix) : undefined,
        )[keyChannel]
      : channelDefault(keyChannel)
    editClip((target) => setKeyframe(target, selectedNode, keyChannel, view.time, value))
  }

  const tracks = Object.entries(clip?.tracks ?? {}).flatMap(([node, channels]) =>
    CHANNELS.filter((channel) => channels[channel]).map((channel) => ({
      node,
      channel,
      keys: channels[channel]!,
    })),
  )
  const byId = new Map(tracks.map((track) => [trackId(track.node, track.channel), track]))
  const keyOf = (ref: KeyRef): CentityKey | null => {
    const track = byId.get(ref.track)
    return track ? { node: track.node, channel: track.channel, index: ref.index } : null
  }
  const selectedKey = view.selectedKey
  const key = selectedKey
    ? clip?.tracks?.[selectedKey.node]?.[selectedKey.channel]?.[selectedKey.index]
    : undefined

  const timelineTracks: TimelineTrack[] = tracks.map((track) => ({
    id: trackId(track.node, track.channel),
    label: trackId(track.node, track.channel),
    keys: track.keys,
  }))

  const clipPicker = (
    <>
      <select
        aria-label="Animation"
        value={clipName ?? ''}
        onChange={(event) =>
          changeView.update({
            clip: event.target.value || null,
            time: 0,
            playing: false,
            selectedKey: null,
          })
        }
      >
        <option value="">Base pose</option>
        {Object.keys(model.animations ?? {}).map((name) => (
          <option key={name} value={name}>
            {name}
          </option>
        ))}
      </select>
      <IconButton icon="plus" label="New animation" onClick={addClip} />
      {clipName && (
        <>
          <IconButton icon="edit" label="Rename animation" onClick={() => void renameClip()} />
          <IconButton icon="trash" label="Delete animation" onClick={() => void deleteClip()} />
          <Divider />
        </>
      )}
    </>
  )

  const clipFields = clipName && (
    <>
      <label className={styles.inline}>
        Loop
        <select
          aria-label="Loop mode"
          value={clip?.loop ?? 'once'}
          onChange={(event) =>
            editClip((target) => {
              if (event.target.value === 'once') delete target.loop
              else target.loop = event.target.value as AnimationDef['loop']
            })
          }
        >
          <option value="once">once</option>
          <option value="loop">loop</option>
          <option value="hold">hold</option>
        </select>
      </label>
      <label className={styles.inline}>
        Blend
        <select
          aria-label="Blend mode"
          title="replace: its tracks own their channels. additive: on top of the pose under it, scale multiplied."
          value={clip?.blend ?? 'replace'}
          onChange={(event) =>
            editClip((target) => {
              if (event.target.value === 'replace') delete target.blend
              else target.blend = event.target.value as AnimationDef['blend']
            })
          }
        >
          <option value="replace">replace</option>
          <option value="additive">additive</option>
        </select>
      </label>
      <label className={styles.inline}>
        <input
          type="checkbox"
          aria-label="Autoplay"
          checked={clip?.autoplay ?? false}
          onChange={(event) =>
            editClip((target) => {
              if (event.target.checked) target.autoplay = true
              else delete target.autoplay
            })
          }
        />
        Autoplay
      </label>
      <label className={styles.inline}>
        Length
        <NumberInput
          label="Animation length"
          value={clip?.length}
          placeholder={(info?.length ?? 0).toString()}
          step={0.1}
          onChange={(value) =>
            editClip((target) => {
              if (value === undefined || value <= 0) delete target.length
              else target.length = value
            })
          }
        />
      </label>
      <Divider />
      <select
        aria-label="Key channel"
        value={keyChannel}
        onChange={(e) => setKeyChannel(e.target.value as Channel)}
      >
        {CHANNELS.map((channel) => (
          <option key={channel} value={channel}>
            {channel}
          </option>
        ))}
      </select>
      <Button
        size="small"
        icon="key"
        disabled={!selectedNode}
        title={
          selectedNode ? `Key ${selectedNode}.${keyChannel} at the playhead` : 'Select a node first'
        }
        onClick={addKey}
      >
        Add key
      </Button>
    </>
  )

  return (
    <Timeline
      scale={{ unit: 'seconds', length }}
      time={view.time}
      onSeek={(time) => changeView.update({ time: roundSeconds(time), playing: false })}
      leading={clipPicker}
      playback={
        clipName
          ? {
              playing: view.playing,
              onToggle: () =>
                changeView.update({
                  playing: !view.playing,
                  time: view.time >= length ? 0 : view.time,
                }),
              time: (
                <span className={styles.time} aria-label="Playhead time">
                  {view.time.toFixed(2)}s
                </span>
              ),
            }
          : null
      }
      toolbar={clipFields}
      placeholder={
        clipName ? undefined : (
          <Empty>Showing the base pose. Pick or create an animation to edit keyframes.</Empty>
        )
      }
      tracks={timelineTracks}
      empty="Select a node and add a key."
      selectedKey={
        selectedKey
          ? { track: trackId(selectedKey.node, selectedKey.channel), index: selectedKey.index }
          : null
      }
      onSelectKey={(ref) => changeView.update({ selectedKey: keyOf(ref) })}
      onMoveKey={(ref, time) => {
        const target = keyOf(ref)
        if (!target) return ref.index
        let index = target.index
        editClip((clip) => {
          index = moveKey(clip, target.node, target.channel, target.index, time)
        })
        changeView.update({ selectedKey: { ...target, index } })
        return index
      }}
      onDeleteKey={(ref) => {
        const target = keyOf(ref)
        if (!target) return
        editClip((clip) => deleteKey(clip, target.node, target.channel, target.index))
        changeView.update({ selectedKey: null })
      }}
      gesture={{ begin: () => ws().beginGesture(path), end: () => ws().endGesture(path) }}
      keyName={(track, index) => `Keyframe ${track.label} ${index + 1}`}
      editor={
        key &&
        selectedKey && (
          <div className={styles.keyEditor} aria-label="Keyframe">
            <div className={styles.keyTitle}>
              {selectedKey.node}.{selectedKey.channel}
            </div>
            <label className={styles.inline}>
              Time
              <NumberInput
                label="Keyframe time"
                value={key.time}
                step={0.05}
                min={0}
                onChange={(value) => {
                  let index = selectedKey.index
                  editClip((target) => {
                    index = moveKey(
                      target,
                      selectedKey.node,
                      selectedKey.channel,
                      selectedKey.index,
                      value ?? 0,
                    )
                  })
                  changeView.update({ selectedKey: { ...selectedKey, index } })
                }}
              />
            </label>
            <Vec3Field
              label="Value"
              value={key.value}
              defaultValue={channelDefault(selectedKey.channel)}
              step={selectedKey.channel === 'rotation' ? 5 : 1 / 16}
              onChange={(value) =>
                editClip((target) => {
                  const k =
                    target.tracks?.[selectedKey.node]?.[selectedKey.channel]?.[selectedKey.index]
                  if (k) k.value = value
                })
              }
              onGestureStart={() => ws().beginGesture(path)}
              onGestureEnd={() => ws().endGesture(path)}
            />
            <label className={styles.inline}>
              Easing
              <select
                aria-label="Easing"
                value={key.easing ?? 'linear'}
                onChange={(event) =>
                  editClip((target) =>
                    setEasing(
                      target,
                      selectedKey.node,
                      selectedKey.channel,
                      selectedKey.index,
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
            <Button
              size="small"
              icon="trash"
              onClick={() => {
                editClip((target) =>
                  deleteKey(target, selectedKey.node, selectedKey.channel, selectedKey.index),
                )
                changeView.update({ selectedKey: null })
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
