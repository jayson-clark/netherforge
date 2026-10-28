/**
 * The particle effect editor: the preview over the timeline, with the
 * inspector in the inspector dock (the emitters are the outline's:
 * `ParticleOutline`).
 * The preview draws format's own spawn points (`particleEffectSampler`), with
 * an approximate simulation of what the client does with them after.
 * Selection is `""` (the effect), `"emitters:<name>"`, or a curve key
 * `"emitters:<name>:curves:<channel>:<index>"`; problems about any of them
 * select it and focus the field.
 */
import { setKey } from '@/core/draft'
import { useCallback, useMemo } from 'react'
import {
  DistributionValues,
  EasingValues,
  MotionValues,
  PARTICLE_DEFAULTS,
  PARTICLE_LIMITS,
  particleEffectSampler,
  type ColorKey,
  type Distribution,
  type Easing,
  type EmitterDef,
  type Motion,
  type NumberKey,
  type ParticleDataKind,
  type ParticleEffectFile,
  type Vec3,
} from '@/core/format'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { parseBlockState } from '@netherforge/models/model'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { resourceOf } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { usePrimary, useView, useViewState } from '@/editors/views'
import { ask } from '@/ui/dialogs'
import {
  CheckField,
  Datalist,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
  Vec3Field,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Stack } from '@/ui/layout'
import { Hint } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import styles from './ParticleEditor.module.css'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { EffectViewport } from './EffectViewport'
import { TICKS_PER_SECOND } from '@/editors/shared/timeline/time'
import { usePlayback } from '@/editors/shared/timeline/usePlayback'
import {
  evenAllowed,
  fieldsTaken,
  hasRadius,
  isCurved,
  kindOf,
  moveKey,
  PARTICLE_ID,
  removeKey,
  setEmission,
  setKeyEasing,
  setKeyValue,
  setParticle,
  setShapeType,
  setSurface,
  shapeOf,
  SHAPE_TYPES,
  spinAllowed,
  type Channel,
  type ParticlePick,
} from './ops'
import { ParticleTimeline, playheadTick } from './ParticleTimeline'
import { PlayOnServer } from './PlayOnServer'
import { LIFETIME, Simulator } from './simulate'
import { pickOf, type PreviewState } from './view'

type Edit = (recipe: (draft: ParticleEffectFile) => void) => void

const int = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))

export function ParticleEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<ParticleEffectFile>(path)
  const view = useView('particle_effect', path)
  const pick = pickOf(usePrimary('particle_effect', path))
  const particles = useWorkspace((s) => s.gameData?.particles ?? null)
  // The preview is the document's view state, so it survives a tab switch.
  const preview = useViewState('particle_effect', path)
  const setPreview = useCallback(
    (update: (state: PreviewState) => PreviewState) => view.update(update(view.state())),
    [view],
  )
  const id = resourceOf(path)?.id ?? 'effect'
  const select = (next: ParticlePick) =>
    next.kind === 'effect' ? view.select() : view.select(next)

  useFocusRequests(path, (segments) => {
    const [first, emitter, third, channel, index] = segments
    if (first === 'emitters' && typeof emitter === 'string') {
      if (third === 'curves' && typeof channel === 'string' && typeof index === 'number') {
        select({ kind: 'key', emitter, channel: channel as Channel, index })
        return segments.slice(5)
      }
      select({ kind: 'emitter', emitter })
      return segments.slice(2)
    }
    select({ kind: 'effect' })
    return segments
  })

  // The sampler compiles once per model; the simulator steps it as the playhead moves.
  const simulator = useMemo(
    () => (model ? new Simulator(particleEffectSampler(id, JSON.stringify(model), 1)) : null),
    [id, model],
  )
  const frame = simulator?.at(preview.tick, preview.loop) ?? null
  const duration = model?.duration ?? 1

  // The preview plays at the game's 20 ticks a second; without the loop it runs on past the
  // end until the last sprites fade.
  usePlayback({
    playing: preview.playing,
    from: preview.tick,
    restart: [duration],
    onFrame: (from, elapsed) => {
      const tick = from + Math.floor(elapsed * TICKS_PER_SECOND)
      if (!preview.loop && tick >= duration - 1 + LIFETIME)
        setPreview((it) => ({ ...it, playing: false }))
      else if (tick !== preview.tick) setPreview((it) => ({ ...it, tick }))
    },
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const emitters = model.emitters ?? {}
  const selectedEmitter = pick.kind === 'effect' ? null : pick.emitter
  const emitter = selectedEmitter ? emitters[selectedEmitter] : undefined

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <PlayOnServer path={path} id={id} loop={preview.loop} />
        <EditAsJson path={path} />
      </EditorBar>
      <EffectViewport
        path={path}
        model={model}
        frame={frame}
        selected={emitter ? selectedEmitter : null}
        tick={playheadTick(preview, duration)}
      />
      <ParticleTimeline
        path={path}
        model={model}
        pick={pick}
        particles={particles}
        preview={preview}
        setPreview={setPreview}
        onSelect={select}
        edit={edit}
      />
      <InspectorPanel>
        {pick.kind === 'key' && emitter ? (
          <KeyInspector
            emitterName={pick.emitter}
            emitter={emitter}
            channel={pick.channel}
            index={pick.index}
            duration={duration}
            onSelect={select}
            edit={edit}
          />
        ) : emitter && selectedEmitter ? (
          <EmitterInspector
            path={path}
            name={selectedEmitter}
            emitter={emitter}
            kind={kindOf(particles, emitter.particle)}
            particles={particles}
            onSelect={select}
            edit={edit}
          />
        ) : (
          <EffectInspector model={model} edit={edit} />
        )}
      </InspectorPanel>
    </EditorScreen>
  )
}

function EffectInspector({ model, edit }: { model: ParticleEffectFile; edit: Edit }) {
  return (
    <Section title="Effect" icon="sparkles">
      <NumberField
        label="Duration (ticks)"
        dataPath="duration"
        value={model.duration}
        step={1}
        min={PARTICLE_LIMITS.minDuration}
        onChange={(value) =>
          value !== undefined &&
          edit(
            (draft) => (draft.duration = Math.min(PARTICLE_LIMITS.maxDuration, Math.round(value))),
          )
        }
      />
      <CheckField
        label="Loop"
        dataPath="loop"
        value={model.loop ?? false}
        onChange={(value) => edit((draft) => setKey(draft, 'loop', value || undefined))}
      />
    </Section>
  )
}

/** A field a curve drives shows that, and a way to its timeline row. */
function CurvedRow({
  label,
  channel,
  emitterName,
  onSelect,
}: {
  label: string
  channel: Channel
  emitterName: string
  onSelect: (pick: ParticlePick) => void
}) {
  return (
    <Row label={label}>
      <Button
        size="small"
        data-path={channel === 'radius' ? 'shape.radius' : channel}
        title={`${label} follows a curve on the timeline`}
        onClick={() => onSelect({ kind: 'key', emitter: emitterName, channel, index: 0 })}
      >
        curve
      </Button>
    </Row>
  )
}

function EmitterInspector({
  path,
  name,
  emitter,
  kind,
  particles,
  onSelect,
  edit,
}: {
  path: string
  name: string
  emitter: EmitterDef
  kind: ParticleDataKind | null
  particles: Record<string, ParticleDataKind> | null
  onSelect: (pick: ParticlePick) => void
  edit: Edit
}) {
  const { workspace } = useApp()
  const { blocks, items } = usePickerIds()
  const gameData = useWorkspace((s) => s.gameData)
  const editEmitter = (recipe: (draft: EmitterDef) => void) =>
    edit((draft) => {
      const target = draft.emitters?.[name]
      if (target) recipe(target)
    })
  const gesture = {
    onGestureStart: () => workspace.getState().beginGesture(path),
    onGestureEnd: () => workspace.getState().endGesture(path),
  }
  const taken = fieldsTaken(kind)
  const shape = shapeOf(emitter)
  const motion = emitter.motion ?? 'random'
  const curved = (channel: Channel) => isCurved(emitter, channel)
  const particleIds = useMemo(
    () => Object.keys(particles ?? {}).filter((id) => particles?.[id] !== 'other'),
    [particles],
  )
  const blockId = emitter.blockState ? parseBlockState(emitter.blockState).id : null

  const curveRow = (label: string, channel: Channel) => (
    <CurvedRow label={label} channel={channel} emitterName={name} onSelect={onSelect} />
  )
  return (
    <>
      <Section title={`Emitter ${name}`}>
        <TextField
          label="Particle"
          dataPath="particle"
          value={emitter.particle}
          list={particleIds.length ? 'nf-particles' : undefined}
          onChange={(value) => {
            const id = value.trim()
            if (!id) return
            editEmitter((d) =>
              setParticle(d, id, kindOf(particles, id), { blockState: blocks[0], item: items[0] }),
            )
          }}
        />
        {particleIds.length > 0 && <Datalist id="nf-particles" values={particleIds} />}
        {kind === null && (
          <Hint>
            What this particle takes isn't known until a dev server has exported this version's game
            data, so every option shows.
          </Hint>
        )}
        {taken.includes('color') &&
          (curved('color') ? (
            curveRow('Colour', 'color')
          ) : (
            <ColorField
              label="Colour"
              dataPath="color"
              value={emitter.color}
              onChange={(value) => editEmitter((d) => setKey(d, 'color', value))}
            />
          ))}
        {taken.includes('toColor') && (
          <ColorField
            label="Fades to"
            dataPath="toColor"
            value={emitter.toColor}
            onChange={(value) => editEmitter((d) => setKey(d, 'toColor', value))}
          />
        )}
        {taken.includes('size') &&
          (curved('size') ? (
            curveRow('Size', 'size')
          ) : (
            <NumberField
              label="Size"
              dataPath="size"
              value={emitter.size}
              placeholder={String(PARTICLE_DEFAULTS.size)}
              step={0.1}
              min={PARTICLE_LIMITS.minSize}
              onChange={(value) =>
                editEmitter((d) =>
                  setKey(
                    d,
                    'size',
                    value === undefined ? undefined : Math.min(PARTICLE_LIMITS.maxSize, value),
                  ),
                )
              }
              {...gesture}
            />
          ))}
        {taken.includes('blockState') && (
          <>
            <TextField
              label="Block"
              dataPath="blockState"
              value={emitter.blockState}
              list={blocks.length ? 'nf-particle-blocks' : undefined}
              onChange={(value) =>
                editEmitter((d) => setKey(d, 'blockState', value.trim() || undefined))
              }
            />
            {blocks.length > 0 && <Datalist id="nf-particle-blocks" values={blocks} />}
            {blockId && gameData?.blocks?.[blockId] && (
              <Hint>
                Properties:{' '}
                {Object.keys(gameData.blocks[blockId]!.properties ?? {}).join(', ') || 'none'}
              </Hint>
            )}
          </>
        )}
        {taken.includes('item') &&
          (emitter.item ? (
            <ItemEditor
              item={emitter.item}
              dataPath="item"
              onEdit={(recipe) =>
                editEmitter((d) => {
                  if (d.item) recipe(d.item)
                })
              }
            />
          ) : (
            <Row label="Item">
              <Button
                size="small"
                icon="plus"
                data-path="item"
                onClick={async () => {
                  const id = await askItemId(items[0])
                  if (id) editEmitter((d) => (d.item = { kind: id }))
                }}
              >
                Add item
              </Button>
            </Row>
          ))}
      </Section>

      <Section title="Emission" icon="sparkles">
        <SelectField
          label="Emits"
          value={emitter.rate !== undefined ? 'rate' : 'burst'}
          options={[
            { value: 'burst', label: 'Bursts' },
            { value: 'rate', label: 'A rate' },
          ]}
          onChange={(mode) => editEmitter((d) => setEmission(d, mode))}
        />
        {emitter.rate === undefined ? (
          <>
            <NumberField
              label="Burst"
              dataPath="burst"
              value={emitter.burst}
              step={1}
              min={1}
              onChange={(value) =>
                value !== undefined &&
                editEmitter(
                  (d) => (d.burst = Math.min(PARTICLE_LIMITS.maxBurst, Math.round(value))),
                )
              }
              {...gesture}
            />
            <NumberField
              label="Every (ticks)"
              dataPath="every"
              value={emitter.every}
              placeholder="once"
              step={1}
              min={1}
              onChange={(value) => editEmitter((d) => setKey(d, 'every', int(value)))}
            />
          </>
        ) : curved('rate') ? (
          curveRow('Rate', 'rate')
        ) : (
          <NumberField
            label="Rate (per tick)"
            dataPath="rate"
            value={emitter.rate}
            step={0.25}
            min={0.01}
            onChange={(value) =>
              value !== undefined &&
              editEmitter((d) => (d.rate = Math.min(PARTICLE_LIMITS.maxRate, value)))
            }
            {...gesture}
          />
        )}
        <NumberField
          label="Start (tick)"
          dataPath="start"
          value={emitter.start}
          placeholder="0"
          step={1}
          min={0}
          onChange={(value) => editEmitter((d) => setKey(d, 'start', int(value) || undefined))}
        />
        <NumberField
          label="End (tick)"
          dataPath="end"
          value={emitter.end}
          placeholder="the end"
          step={1}
          min={1}
          onChange={(value) => editEmitter((d) => setKey(d, 'end', int(value)))}
        />
      </Section>

      <Section title="Shape" icon="cube">
        <SelectField
          label="Shape"
          dataPath="shape"
          value={shape.type}
          options={SHAPE_TYPES}
          onChange={(type) => editEmitter((d) => setShapeType(d, type))}
        />
        {hasRadius(shape) &&
          (curved('radius') ? (
            curveRow('Radius', 'radius')
          ) : (
            <NumberField
              label="Radius"
              dataPath="shape.radius"
              value={(shape as { radius?: number }).radius}
              step={0.1}
              min={0.01}
              onChange={(value) =>
                value !== undefined &&
                editEmitter((d) => {
                  if (d.shape && hasRadius(d.shape)) (d.shape as { radius?: number }).radius = value
                })
              }
              {...gesture}
            />
          ))}
        {shape.type === 'line' && (
          <Vec3Field
            label="To"
            dataPath="shape.to"
            value={shape.to}
            defaultValue={[0, 1, 0]}
            onChange={(value) =>
              editEmitter((d) => {
                if (d.shape?.type === 'line') d.shape.to = value
              })
            }
            {...gesture}
          />
        )}
        {shape.type === 'box' && (
          <Vec3Field
            label="Size"
            dataPath="shape.size"
            value={shape.size}
            defaultValue={[1, 1, 1]}
            onChange={(value) =>
              editEmitter((d) => {
                if (d.shape?.type === 'box') d.shape.size = value
              })
            }
            {...gesture}
          />
        )}
        {(shape.type === 'sphere' || shape.type === 'box') && (
          <CheckField
            label="Surface only"
            dataPath="shape.surface"
            value={shape.surface ?? false}
            onChange={(value) => editEmitter((d) => setSurface(d, value))}
          />
        )}
        <SelectField
          label="Spacing"
          dataPath="distribution"
          value={emitter.distribution ?? 'random'}
          options={DistributionValues.filter((it) => it === 'random' || evenAllowed(shape)).map(
            (it) => ({
              value: it,
              label: it === 'even' ? 'Even' : 'Random',
            }),
          )}
          onChange={(value: Distribution) =>
            editEmitter((d) => setKey(d, 'distribution', value === 'random' ? undefined : value))
          }
        />
        {spinAllowed(shape) && (
          <NumberField
            label="Spin (°/tick)"
            dataPath="spin"
            value={emitter.spin}
            placeholder="0"
            step={1}
            onChange={(value) => editEmitter((d) => setKey(d, 'spin', value || undefined))}
            {...gesture}
          />
        )}
        <Vec3Field
          label="Offset"
          dataPath="offset"
          value={emitter.offset}
          defaultValue={[0, 0, 0]}
          onChange={(value) =>
            editEmitter((d) => setKey(d, 'offset', isZero(value) ? undefined : value))
          }
          {...gesture}
        />
        <Vec3Field
          label="Rotation"
          dataPath="rotation"
          value={emitter.rotation}
          defaultValue={[0, 0, 0]}
          step={5}
          onChange={(value) =>
            editEmitter((d) => setKey(d, 'rotation', isZero(value) ? undefined : value))
          }
          {...gesture}
        />
      </Section>

      <Section title="Motion" icon="wind">
        <SelectField
          label="Motion"
          dataPath="motion"
          value={motion}
          options={MotionValues}
          onChange={(value: Motion) =>
            editEmitter((d) => {
              setKey(d, 'motion', value === 'random' ? undefined : value)
              if (value !== 'direction') delete d.direction
              else d.direction ??= [0, 1, 0]
              if (value !== 'random') {
                delete d.count
                delete d.spread
              }
            })
          }
        />
        {motion === 'direction' && (
          <Vec3Field
            label="Direction"
            dataPath="direction"
            value={emitter.direction}
            defaultValue={[0, 1, 0]}
            onChange={(value) => editEmitter((d) => (d.direction = value))}
            {...gesture}
          />
        )}
        {motion === 'random' && (
          <>
            <NumberField
              label="Count"
              dataPath="count"
              value={emitter.count}
              placeholder={String(PARTICLE_DEFAULTS.count)}
              step={1}
              min={1}
              onChange={(value) =>
                editEmitter((d) =>
                  setKey(
                    d,
                    'count',
                    value === undefined
                      ? undefined
                      : Math.min(PARTICLE_LIMITS.maxCount, Math.round(value)),
                  ),
                )
              }
            />
            <Vec3Field
              label="Spread"
              dataPath="spread"
              value={emitter.spread}
              defaultValue={[0, 0, 0]}
              onChange={(value) =>
                editEmitter((d) =>
                  setKey(
                    d,
                    'spread',
                    isZero(value) ? undefined : (value.map((it) => Math.max(0, it)) as Vec3),
                  ),
                )
              }
              {...gesture}
            />
          </>
        )}
        {curved('speed') ? (
          curveRow('Speed', 'speed')
        ) : (
          <NumberField
            label="Speed"
            dataPath="speed"
            value={emitter.speed}
            placeholder="0"
            step={0.05}
            min={0}
            onChange={(value) => editEmitter((d) => setKey(d, 'speed', value || undefined))}
            {...gesture}
          />
        )}
        <CheckField
          label="Force"
          dataPath="force"
          value={emitter.force ?? false}
          onChange={(value) => editEmitter((d) => setKey(d, 'force', value || undefined))}
        />
      </Section>
    </>
  )
}

const askItemId = (initial: string | undefined) =>
  ask
    .prompt({
      title: 'Item',
      label: 'Item id',
      message: 'The item the particle shows, like minecraft:apple.',
      initial: initial ?? '',
      confirmLabel: 'Add',
      validate: (value) =>
        PARTICLE_ID.test(value.trim()) ? null : 'An item id, like minecraft:apple',
    })
    .then((id) => id?.trim() || null)

const isZero = (value: Vec3) => value[0] === 0 && value[1] === 0 && value[2] === 0

/** `#rrggbb` with a colour picker beside the text. */
function ColorField({
  label,
  value,
  onChange,
  dataPath,
}: {
  label: string
  value: string | undefined
  onChange: (value: string | undefined) => void
  dataPath: string
}) {
  const shown = value ?? PARTICLE_DEFAULTS.color
  return (
    <Row label={label}>
      <span className={styles.colorField}>
        <input
          type="color"
          aria-label={label}
          data-path={dataPath}
          value={shown}
          onChange={(event) => onChange(event.target.value.toLowerCase())}
        />
        <code>{shown}</code>
        {value !== undefined && (
          <IconButton
            icon="close"
            label={`Reset ${label}`}
            title="Back to the default"
            onClick={() => onChange(undefined)}
          />
        )}
      </span>
    </Row>
  )
}

function KeyInspector({
  emitterName,
  emitter,
  channel,
  index,
  duration,
  onSelect,
  edit,
}: {
  emitterName: string
  emitter: EmitterDef
  channel: Channel
  index: number
  duration: number
  onSelect: (pick: ParticlePick) => void
  edit: Edit
}) {
  const key = emitter.curves?.[channel]?.[index] as NumberKey | ColorKey | undefined
  const editEmitter = (recipe: (draft: EmitterDef) => void) =>
    edit((draft) => {
      const target = draft.emitters?.[emitterName]
      if (target) recipe(target)
    })
  if (!key) {
    return (
      <Section title={`${emitterName} · ${channel}`}>
        <Hint>That key is gone.</Hint>
        <Button size="small" onClick={() => onSelect({ kind: 'emitter', emitter: emitterName })}>
          Back to {emitterName}
        </Button>
      </Section>
    )
  }
  return (
    <Section title={`${emitterName} · ${channel} curve`}>
      <NumberField
        label="Tick"
        dataPath="time"
        value={key.time}
        step={1}
        min={0}
        onChange={(value) => {
          let next = index
          editEmitter((d) => {
            next = moveKey(d, channel, index, Math.min(duration, value ?? 0))
          })
          onSelect({ kind: 'key', emitter: emitterName, channel, index: next })
        }}
      />
      {'color' in key ? (
        <ColorField
          label="Colour"
          dataPath="color"
          value={key.color}
          onChange={(value) => value && editEmitter((d) => setKeyValue(d, channel, index, value))}
        />
      ) : (
        <NumberField
          label="Value"
          dataPath="value"
          value={key.value}
          step={channel === 'rate' ? 0.25 : 0.1}
          min={0}
          onChange={(value) =>
            value !== undefined && editEmitter((d) => setKeyValue(d, channel, index, value))
          }
        />
      )}
      <SelectField
        label="Easing"
        dataPath="easing"
        value={key.easing ?? 'linear'}
        options={EasingValues}
        onChange={(value: Easing) => editEmitter((d) => setKeyEasing(d, channel, index, value))}
      />
      <Stack inline>
        <Button
          size="small"
          icon="trash"
          onClick={() => {
            const last = (emitter.curves?.[channel]?.length ?? 0) <= 1
            editEmitter((d) => removeKey(d, channel, index))
            onSelect(
              last
                ? { kind: 'emitter', emitter: emitterName }
                : { kind: 'key', emitter: emitterName, channel, index: Math.max(0, index - 1) },
            )
          }}
        >
          Delete key
        </Button>
        <Button size="small" onClick={() => onSelect({ kind: 'emitter', emitter: emitterName })}>
          Back to {emitterName}
        </Button>
      </Stack>
    </Section>
  )
}
