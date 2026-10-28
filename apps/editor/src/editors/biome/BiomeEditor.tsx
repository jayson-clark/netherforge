/**
 * A project biome (`biomes/<id>.json`, docs/format/biome.md): a picture of
 * its colours (sky over fog, grass, leaves and water), and in the inspector
 * its climate, colours, particle, sounds, the game's mobs that spawn in it by
 * category and their spawn costs, and the placed features it's decorated
 * with, step by step (the game's, or its datapacks'). Ids are suggested from the target version's
 * game data when it's been exported.
 */
import { useMemo } from 'react'
import { setKey } from '@/core/draft'
import {
  BIOME_DEFAULTS,
  ENTITY_TYPE_REGISTRY,
  GenerationStepValues,
  GrassModifierValues,
  PLACED_FEATURE_REGISTRY,
  SOUND_EVENT_REGISTRY,
  SpawnCategoryValues,
  TemperatureModifierValues,
  type BiomeColors,
  type BiomeFile,
  type GenerationStep,
  type GrassModifier,
  type SpawnCategory,
  type TemperatureModifier,
} from '@/core/format'
import { InspectorPanel } from '@/editors/shared/docks'
import { BiomeScene } from './BiomeScene'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { fieldPath } from '@/editors/shared/focus'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useWorkspace } from '@/state/providers'
import { IconButton } from '@/ui/Button'
import {
  ColorField,
  Datalist,
  FieldGroup,
  NumberField,
  Section,
  SelectField,
  TextField,
  TriStateField,
} from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import {
  addFeature,
  addSpawn,
  biomeChoices,
  changeSound,
  changeSpawn,
  moveFeature,
  removeFeature,
  removeSpawn,
  setAmbient,
  setClimate,
  setColor,
  setSpawnCost,
  toggleSound,
  PLACED_FEATURE_FOLDER,
} from './ops'
import styles from './BiomeEditor.module.css'

const ENTITIES = 'nf-biome-entities'
const SOUNDS = 'nf-biome-sounds'
const PARTICLES = 'nf-biome-particles'
const FEATURES = 'nf-biome-features'
const NONE: string[] = []

type Edit = (recipe: (draft: BiomeFile) => void) => void

const COLORS: { key: Exclude<keyof BiomeColors, 'grassModifier'>; label: string; unset: string }[] =
  [
    { key: 'sky', label: 'Sky', unset: "the world's" },
    { key: 'fog', label: 'Fog', unset: "the world's" },
    { key: 'water', label: 'Water', unset: BIOME_DEFAULTS.water },
    { key: 'waterFog', label: 'Under water', unset: "the world's" },
    { key: 'grass', label: 'Grass', unset: 'from the climate' },
    { key: 'foliage', label: 'Leaves', unset: 'from the climate' },
    { key: 'dryFoliage', label: 'Dry leaves', unset: 'from the climate' },
  ]

const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))

/** The game's ids of one registry, for suggestions: none until game data is exported. */
function useRegistry(registry: string): string[] {
  return useWorkspace((s) => s.gameData?.registries?.[registry] ?? NONE)
}

export function BiomeEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<BiomeFile>(path)
  const entities = useRegistry(ENTITY_TYPE_REGISTRY)
  const sounds = useRegistry(SOUND_EVENT_REGISTRY)
  const gameFeatures = useRegistry(PLACED_FEATURE_REGISTRY)
  // The project's own (its datapacks' placed features) by id, then the game's in full.
  const projectFeatures = useWorkspace(
    (s) => s.outline?.registryNames?.[PLACED_FEATURE_FOLDER] ?? NONE,
  )
  const features = useMemo(
    () => biomeChoices(projectFeatures, gameFeatures),
    [projectFeatures, gameFeatures],
  )
  const particles = useWorkspace((s) => s.gameData?.particles)
  const id = path.split('/')[1]?.replace(/\.json$/, '') ?? ''
  // Every path in the file is an inspector field's.
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  // Only a particle that takes no options can drift through a biome.
  const plain = particles
    ? Object.entries(particles)
        .filter(([, kind]) => kind === 'none')
        .map(([particle]) => particle)
        .sort()
    : NONE
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <BiomeScene colors={model.colors ?? {}} />
      </Stage>
      <InspectorPanel>
        <Datalist id={ENTITIES} values={entities} />
        <Datalist id={SOUNDS} values={sounds} />
        <Datalist id={PARTICLES} values={plain} />
        <Datalist id={FEATURES} values={features} />
        <ClimateSection model={model} edit={edit} />
        <ColorsSection model={model} edit={edit} />
        <ParticleSection model={model} edit={edit} />
        <SoundsSection model={model} edit={edit} />
        <SpawnsSection model={model} edit={edit} />
        <FeaturesSection model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

function ClimateSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const climate = model.climate ?? {}
  return (
    <Section title="Climate" icon="cloud">
      <NumberField
        label="Temperature"
        dataPath="climate.temperature"
        value={climate.temperature}
        placeholder={String(BIOME_DEFAULTS.temperature)}
        step={0.05}
        onChange={(value) => edit((d) => setClimate(d, 'temperature', value))}
      />
      <NumberField
        label="Downfall"
        dataPath="climate.downfall"
        value={climate.downfall}
        placeholder={String(BIOME_DEFAULTS.downfall)}
        step={0.05}
        min={0}
        onChange={(value) => edit((d) => setClimate(d, 'downfall', value))}
      />
      <TriStateField
        label="Rain or snow"
        dataPath="climate.precipitation"
        value={climate.precipitation}
        defaultLabel="on"
        onChange={(value) => edit((d) => setClimate(d, 'precipitation', value))}
      />
      <SelectField
        label="Colder patches"
        dataPath="climate.temperatureModifier"
        value={climate.temperatureModifier ?? 'none'}
        options={TemperatureModifierValues}
        onChange={(value) =>
          edit((d) =>
            setClimate(
              d,
              'temperatureModifier',
              value === 'none' ? undefined : (value as TemperatureModifier),
            ),
          )
        }
      />
      <Hint>Below 0.15 it snows instead of raining, and water freezes.</Hint>
    </Section>
  )
}

function ColorsSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const colors = model.colors ?? {}
  return (
    <Section title="Colours" icon="palette">
      {COLORS.map(({ key, label, unset }) => (
        <ColorField
          key={key}
          label={label}
          dataPath={`colors.${key}`}
          value={colors[key]}
          placeholder={unset}
          onChange={(value) => edit((d) => setColor(d, key, value))}
        />
      ))}
      <SelectField
        label="Grass drawn"
        dataPath="colors.grassModifier"
        value={colors.grassModifier ?? 'none'}
        options={GrassModifierValues}
        onChange={(value) =>
          edit((d) =>
            setColor(d, 'grassModifier', value === 'none' ? undefined : (value as GrassModifier)),
          )
        }
      />
    </Section>
  )
}

function ParticleSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const particle = model.particle
  return (
    <Section
      title="Particle"
      enabled={particle !== undefined}
      onToggle={(on) =>
        edit((d) => setKey(d, 'particle', on ? { particle: '', probability: 0.01 } : undefined))
      }
    >
      {particle && (
        <>
          <TextField
            label="Particle"
            dataPath="particle.particle"
            value={particle.particle}
            list={PARTICLES}
            onChange={(value) => edit((d) => d.particle && (d.particle.particle = value.trim()))}
          />
          <NumberField
            label="Probability"
            dataPath="particle.probability"
            value={particle.probability}
            step={0.001}
            min={0}
            onChange={(value) =>
              edit((d) => d.particle && (d.particle.probability = value ?? particle.probability))
            }
          />
          <Hint>Per block, each tick: the game's own are around 0.01 or less.</Hint>
        </>
      )}
    </Section>
  )
}

function SoundsSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const sounds = model.sounds ?? {}
  const { mood, additions, music } = sounds
  return (
    <Section title="Sounds and music" icon="music">
      <TextField
        label="Ambient loop"
        dataPath="sounds.ambient"
        value={sounds.ambient}
        placeholder="none"
        list={SOUNDS}
        onChange={(value) => edit((d) => setAmbient(d, value.trim() || undefined))}
      />
      <SoundToggle
        label="Cave mood"
        on={mood !== undefined}
        onToggle={(on) => edit((d) => toggleSound(d, 'mood', on ? '' : undefined))}
      />
      {mood && (
        <FieldGroup>
          <TextField
            label="Sound"
            dataPath="sounds.mood.sound"
            value={mood.sound}
            list={SOUNDS}
            onChange={(value) =>
              edit((d) => changeSound(d, 'mood', (m) => (m.sound = value.trim())))
            }
          />
          <NumberField
            label="Delay (ticks)"
            dataPath="sounds.mood.tickDelay"
            value={mood.tickDelay}
            placeholder={String(BIOME_DEFAULTS.moodTickDelay)}
            step={100}
            min={1}
            onChange={(value) =>
              edit((d) => changeSound(d, 'mood', (m) => setKey(m, 'tickDelay', whole(value))))
            }
          />
          <NumberField
            label="Search (blocks)"
            dataPath="sounds.mood.blockSearchExtent"
            value={mood.blockSearchExtent}
            placeholder={String(BIOME_DEFAULTS.moodBlockSearchExtent)}
            step={1}
            min={0}
            onChange={(value) =>
              edit((d) =>
                changeSound(d, 'mood', (m) => setKey(m, 'blockSearchExtent', whole(value))),
              )
            }
          />
          <NumberField
            label="Offset"
            dataPath="sounds.mood.offset"
            value={mood.offset}
            placeholder={String(BIOME_DEFAULTS.moodOffset)}
            step={0.5}
            min={0}
            onChange={(value) =>
              edit((d) => changeSound(d, 'mood', (m) => setKey(m, 'offset', value)))
            }
          />
        </FieldGroup>
      )}
      <SoundToggle
        label="Random additions"
        on={additions !== undefined}
        onToggle={(on) => edit((d) => toggleSound(d, 'additions', on ? '' : undefined))}
      />
      {additions && (
        <FieldGroup>
          <TextField
            label="Sound"
            dataPath="sounds.additions.sound"
            value={additions.sound}
            list={SOUNDS}
            onChange={(value) =>
              edit((d) => changeSound(d, 'additions', (a) => (a.sound = value.trim())))
            }
          />
          <NumberField
            label="Chance a tick"
            dataPath="sounds.additions.chance"
            value={additions.chance}
            step={0.001}
            min={0}
            onChange={(value) =>
              edit((d) =>
                changeSound(d, 'additions', (a) => (a.chance = value ?? additions.chance)),
              )
            }
          />
        </FieldGroup>
      )}
      <SoundToggle
        label="Music"
        on={music !== undefined}
        onToggle={(on) => edit((d) => toggleSound(d, 'music', on ? '' : undefined))}
      />
      {music && (
        <FieldGroup>
          <TextField
            label="Track"
            dataPath="sounds.music.sound"
            value={music.sound}
            list={SOUNDS}
            onChange={(value) =>
              edit((d) => changeSound(d, 'music', (m) => (m.sound = value.trim())))
            }
          />
          <NumberField
            label="Min delay"
            dataPath="sounds.music.minDelay"
            value={music.minDelay}
            placeholder={String(BIOME_DEFAULTS.musicMinDelay)}
            step={100}
            min={0}
            onChange={(value) =>
              edit((d) => changeSound(d, 'music', (m) => setKey(m, 'minDelay', whole(value))))
            }
          />
          <NumberField
            label="Max delay"
            dataPath="sounds.music.maxDelay"
            value={music.maxDelay}
            placeholder={String(BIOME_DEFAULTS.musicMaxDelay)}
            step={100}
            min={0}
            onChange={(value) =>
              edit((d) => changeSound(d, 'music', (m) => setKey(m, 'maxDelay', whole(value))))
            }
          />
        </FieldGroup>
      )}
    </Section>
  )
}

/** A checkbox row that turns one of the sounds with settings on or off. */
function SoundToggle({
  label,
  on,
  onToggle,
}: {
  label: string
  on: boolean
  onToggle: (on: boolean) => void
}) {
  return (
    <label className={styles.toggle}>
      <input type="checkbox" checked={on} onChange={(event) => onToggle(event.target.checked)} />
      {label}
    </label>
  )
}

function SpawnsSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const spawns = model.spawns ?? {}
  const costs = model.spawnCosts ?? {}
  const add = (category: SpawnCategory) => edit((d) => addSpawn(d, category, ''))
  return (
    <Section title="Mobs" icon="person">
      {SpawnCategoryValues.map((category) => {
        const list = spawns[category] ?? []
        return (
          <div key={category} className={styles.list} data-path={fieldPath(['spawns', category])}>
            <div className={styles.listHeader}>
              <span>{category.replaceAll('_', ' ')}</span>
              <IconButton
                icon="plus"
                size={14}
                label={`Add a spawn of ${category}`}
                onClick={() => add(category)}
              />
            </div>
            {list.map((spawn, index) => {
              const at = ['spawns', category, index]
              const change = (recipe: Parameters<typeof changeSpawn>[3]) =>
                edit((d) => changeSpawn(d, category, index, recipe))
              return (
                <FieldGroup
                  key={index}
                  role="group"
                  aria-label={`${category} spawn ${index + 1}`}
                  header={
                    <span className={styles.entryHeader}>
                      <span>{spawn.entity || 'a mob'}</span>
                      <IconButton
                        icon="trash"
                        size={14}
                        label={`Remove ${category} spawn ${index + 1}`}
                        onClick={() => edit((d) => removeSpawn(d, category, index))}
                      />
                    </span>
                  }
                >
                  <TextField
                    label="Entity"
                    dataPath={fieldPath([...at, 'entity'])}
                    value={spawn.entity}
                    list={ENTITIES}
                    onChange={(value) => change((s) => (s.entity = value.trim()))}
                  />
                  <NumberField
                    label="Weight"
                    dataPath={fieldPath([...at, 'weight'])}
                    value={spawn.weight}
                    placeholder={String(BIOME_DEFAULTS.spawnWeight)}
                    step={1}
                    min={1}
                    onChange={(value) => change((s) => setKey(s, 'weight', whole(value)))}
                  />
                  <NumberField
                    label="Group min"
                    dataPath={fieldPath([...at, 'group', 'min'])}
                    value={spawn.group?.min}
                    placeholder="1"
                    step={1}
                    min={1}
                    onChange={(value) =>
                      change((s) => (s.group = { ...s.group, min: whole(value) }))
                    }
                  />
                  <NumberField
                    label="Group max"
                    dataPath={fieldPath([...at, 'group', 'max'])}
                    value={spawn.group?.max}
                    placeholder={String(spawn.group?.min ?? 1)}
                    step={1}
                    min={1}
                    onChange={(value) =>
                      change((s) => (s.group = { ...s.group, max: whole(value) }))
                    }
                  />
                </FieldGroup>
              )
            })}
          </div>
        )
      })}
      <div className={styles.list} data-path="spawnCosts">
        <div className={styles.listHeader}>
          <span>spawn costs</span>
          <IconButton
            icon="plus"
            size={14}
            label="Add a spawn cost"
            onClick={() =>
              edit((d) => {
                // A new one is named by its entity type next, in its field.
                if (d.spawnCosts?.[''] === undefined)
                  setSpawnCost(d, '', { charge: 1, energyBudget: 1 })
              })
            }
          />
        </div>
        {Object.entries(costs).map(([entity, cost]) => {
          const at = ['spawnCosts', entity]
          return (
            <FieldGroup
              key={entity}
              header={
                <span className={styles.entryHeader}>
                  <span>{entity}</span>
                  <IconButton
                    icon="trash"
                    size={14}
                    label={`Remove the spawn cost of ${entity}`}
                    onClick={() => edit((d) => setSpawnCost(d, entity, undefined))}
                  />
                </span>
              }
            >
              <TextField
                label="Entity"
                dataPath={fieldPath(at)}
                value={entity}
                list={ENTITIES}
                onChange={(value) =>
                  edit((d) => {
                    const to = value.trim()
                    if (!to || to === entity || d.spawnCosts?.[to]) return
                    setSpawnCost(d, entity, undefined)
                    setSpawnCost(d, to, cost)
                  })
                }
              />
              <NumberField
                label="Charge"
                dataPath={fieldPath([...at, 'charge'])}
                value={cost.charge}
                step={0.05}
                min={0}
                onChange={(value) =>
                  edit((d) => setSpawnCost(d, entity, { ...cost, charge: value ?? cost.charge }))
                }
              />
              <NumberField
                label="Budget"
                dataPath={fieldPath([...at, 'energyBudget'])}
                value={cost.energyBudget}
                step={0.05}
                min={0}
                onChange={(value) =>
                  edit((d) =>
                    setSpawnCost(d, entity, { ...cost, energyBudget: value ?? cost.energyBudget }),
                  )
                }
              />
            </FieldGroup>
          )
        })}
      </div>
      <Hint>Only these mobs spawn here: none of the game&apos;s unless they&apos;re listed.</Hint>
    </Section>
  )
}

function FeaturesSection({ model, edit }: { model: BiomeFile; edit: Edit }) {
  const features = model.features ?? {}
  const steps = GenerationStepValues
  const listed = steps.filter((step) => (features[step]?.length ?? 0) > 0)
  return (
    <Section title="Features" icon="sparkles">
      {listed.length === 0 && <Muted>None: nothing grows here.</Muted>}
      {steps.map((step) => (
        <FeatureStep key={step} step={step} list={features[step] ?? []} edit={edit} />
      ))}
      <Hint>
        The game places each step&apos;s features in the order listed. Keep the order the
        game&apos;s own biomes use for the features you share with them.
      </Hint>
    </Section>
  )
}

function FeatureStep({ step, list, edit }: { step: GenerationStep; list: string[]; edit: Edit }) {
  return (
    <div className={styles.list} data-path={fieldPath(['features', step])}>
      <div className={styles.listHeader}>
        <span>{step.replaceAll('_', ' ')}</span>
        <IconButton
          icon="plus"
          size={14}
          label={`Add a feature to ${step}`}
          onClick={() => edit((d) => addFeature(d, step, ''))}
        />
      </div>
      {list.map((feature, index) => (
        <div key={index} className={styles.feature}>
          <TextField
            label={`${step} ${index + 1}`}
            dataPath={fieldPath(['features', step, index])}
            value={feature}
            list={FEATURES}
            onChange={(value) =>
              edit((d) => {
                const next = [...(d.features?.[step] ?? [])]
                next[index] = value.trim()
                d.features = { ...d.features, [step]: next }
              })
            }
          />
          <IconButton
            icon="arrowUp"
            size={12}
            label={`Move ${feature} earlier`}
            disabled={index === 0}
            onClick={() => edit((d) => moveFeature(d, step, index, -1))}
          />
          <IconButton
            icon="arrowDown"
            size={12}
            label={`Move ${feature} later`}
            disabled={index === list.length - 1}
            onClick={() => edit((d) => moveFeature(d, step, index, 1))}
          />
          <IconButton
            icon="trash"
            size={12}
            label={`Remove ${feature}`}
            onClick={() => edit((d) => removeFeature(d, step, index))}
          />
        </div>
      ))}
    </div>
  )
}
