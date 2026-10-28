import { setKey } from '@/core/draft'
import {
  TERRAIN_DEFAULTS,
  type BlockRange,
  type ClimateRange,
  type NoiseDef,
  type TerrainFile,
} from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { Button } from '@/ui/Button'
import { CheckField, FieldGroup, NumberField, Section, TextField } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import {
  addEntry,
  deleteEntry,
  namesOf,
  newEntry,
  renameEntry,
  setAreaDensity,
  setAreaOwnLayers,
  type AreaNoises,
  type Collection,
} from '@/editors/terrain/ops'
import {
  AddButton,
  BIOMES_LIST,
  type Edit,
  EntryHeader,
  LayerRows,
  type Parts,
  askName,
  whole,
} from './fields'
import { NoiseEntries, NoiseFields } from './noise'

/** Whether a biome area is a volume: limited by `y`, `depth` or `surface`, a biome inside the columns' areas. */
const isVolume = (area: { y?: unknown; depth?: unknown; surface?: unknown }) =>
  area.y !== undefined || area.depth !== undefined || area.surface !== undefined

function RangeFields({
  label,
  at,
  range,
  change,
  gesture,
}: {
  label: string
  at: (string | number)[]
  range: ClimateRange | undefined
  change: (range: ClimateRange | undefined) => void
  gesture: Parts['gesture']
}) {
  const set = (key: 'min' | 'max', value: number | undefined) => {
    const next: ClimateRange = { ...range }
    if (value === undefined) delete next[key]
    else next[key] = value
    change(Object.keys(next).length === 0 ? undefined : next)
  }
  return (
    <>
      <NumberField
        label={`${label} from`}
        dataPath={fieldPath([...at, 'min'])}
        value={range?.min}
        placeholder="-1"
        step={0.05}
        {...gesture}
        onChange={(value) => set('min', value)}
      />
      <NumberField
        label={`${label} to`}
        dataPath={fieldPath([...at, 'max'])}
        value={range?.max}
        placeholder="1"
        step={0.05}
        {...gesture}
        onChange={(value) => set('max', value)}
      />
    </>
  )
}

/** A range of heights in blocks (a volume area's `y`, `depth` or `surface`), each end open when it's empty. */
function BlockRangeFields({
  label,
  at,
  range,
  change,
  gesture,
}: {
  label: string
  at: (string | number)[]
  range: BlockRange | undefined
  change: (range: BlockRange | undefined) => void
  gesture: Parts['gesture']
}) {
  const set = (key: 'min' | 'max', value: number | undefined) => {
    const next: BlockRange = { ...range }
    if (value === undefined) delete next[key]
    else next[key] = Math.round(value)
    change(Object.keys(next).length === 0 ? undefined : next)
  }
  return (
    <>
      <NumberField
        label={`${label} from`}
        dataPath={fieldPath([...at, 'min'])}
        value={range?.min}
        placeholder="any"
        step={1}
        {...gesture}
        onChange={(value) => set('min', value)}
      />
      <NumberField
        label={`${label} to`}
        dataPath={fieldPath([...at, 'max'])}
        value={range?.max}
        placeholder="any"
        step={1}
        {...gesture}
        onChange={(value) => set('max', value)}
      />
    </>
  )
}

const VOLUME_KEYS = ['y', 'depth', 'surface'] as const

const VOLUME_LABELS: Record<(typeof VOLUME_KEYS)[number], string> = {
  y: 'Height',
  depth: 'Depth below the surface',
  surface: "Columns' surface",
}

export function BiomesSection(parts: Parts) {
  const { model, edit, gesture, biomes } = parts
  const names = namesOf(model, 'biomes')
  const add = async () => {
    const name = await askName('biome area', names, names.length ? '' : 'plains')
    if (name) edit((d) => addEntry(d, 'biomes', name, newEntry('biomes', '')))
  }
  const climate = model.climate ?? {}
  const climateNames = Object.keys(climate.noises ?? {}).sort()
  const addClimate = async () => {
    const name = await askName('climate value', climateNames, climateNames.length ? '' : 'evil')
    if (name)
      edit((d) => {
        d.climate ??= {}
        d.climate.noises = { ...d.climate.noises, [name]: {} }
      })
  }
  const setClimate = (key: 'temperature' | 'humidity', recipe: (noise: NoiseDef) => void) =>
    edit((d) => {
      d.climate ??= {}
      d.climate[key] ??= {}
      recipe(d.climate[key]!)
    })
  return (
    <Section title="Biomes" icon="leaf" actions={<AddButton one="biome area" onAdd={add} />}>
      {names.length === 0 && (
        <Muted>No biome areas: all of the world is {TERRAIN_DEFAULTS.biome}.</Muted>
      )}
      {names.map((name) => {
        const area = model.biomes?.[name]
        if (!area) return null
        const at = ['biomes', name]
        const change = (recipe: (area: NonNullable<TerrainFile['biomes']>[string]) => void) =>
          edit((d) => {
            const target = d.biomes?.[name]
            if (target) recipe(target)
          })
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="biome area"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'biomes', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'biomes', name))}
              />
            }
          >
            <TextField
              label="Biome"
              dataPath={fieldPath([...at, 'biome'])}
              value={area.biome}
              list={biomes.length ? BIOMES_LIST : undefined}
              onChange={(value) => change((a) => (a.biome = value.trim()))}
            />
            <RangeFields
              label="Temperature"
              at={[...at, 'temperature']}
              range={area.temperature}
              gesture={gesture}
              change={(range) => change((a) => setKey(a, 'temperature', range))}
            />
            <RangeFields
              label="Humidity"
              at={[...at, 'humidity']}
              range={area.humidity}
              gesture={gesture}
              change={(range) => change((a) => setKey(a, 'humidity', range))}
            />
            {climateNames.map((value) => (
              <RangeFields
                key={value}
                label={value}
                at={[...at, 'climate', value]}
                range={area.climate?.[value]}
                gesture={gesture}
                change={(range) =>
                  change((a) => {
                    a.climate ??= {}
                    setKey(a.climate, value, range)
                    if (Object.keys(a.climate).length === 0) delete a.climate
                  })
                }
              />
            ))}
            <CheckField
              label="Limited by height (a biome inside the columns' areas)"
              dataPath={fieldPath([...at, 'y'])}
              value={isVolume(area)}
              onChange={(on) =>
                change((a) => {
                  if (on) {
                    a.depth = { min: 16 }
                    delete a.layers
                    delete a.underwater
                    delete a.terrain
                  } else {
                    for (const key of VOLUME_KEYS) delete a[key]
                  }
                })
              }
            />
            {isVolume(area) ? (
              VOLUME_KEYS.map((key) => (
                <BlockRangeFields
                  key={key}
                  label={VOLUME_LABELS[key]}
                  at={[...at, key]}
                  range={area[key]}
                  gesture={gesture}
                  change={(range) => change((a) => setKey(a, key, range))}
                />
              ))
            ) : (
              <>
                <AreaLayers {...parts} name={name} area={area} />
                <AreaTerrainFields
                  name={name}
                  area={area}
                  threeD={model.terrain?.density !== undefined}
                  edit={edit}
                  gesture={gesture}
                />
              </>
            )}
          </FieldGroup>
        )
      })}
      <Muted>Climate noise</Muted>
      <Hint>Where each biome is found: each area is the one whose ranges a place fits best.</Hint>
      {(['temperature', 'humidity'] as const).map((key) => (
        <FieldGroup
          key={key}
          header={<strong>{key === 'temperature' ? 'Temperature' : 'Humidity'}</strong>}
        >
          <NoiseFields
            noise={climate[key] ?? {}}
            at={['climate', key]}
            gesture={gesture}
            change={(recipe) => setClimate(key, recipe)}
          />
        </FieldGroup>
      ))}
      {climateNames.map((value) => (
        <FieldGroup
          key={value}
          data-path={fieldPath(['climate', 'noises', value])}
          header={
            <span style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
              <strong>{value}</strong>
              <Button
                onClick={() =>
                  edit((d) => {
                    if (!d.climate?.noises) return
                    delete d.climate.noises[value]
                    if (Object.keys(d.climate.noises).length === 0) delete d.climate.noises
                    for (const area of Object.values(d.biomes ?? {})) {
                      if (!area.climate) continue
                      delete area.climate[value]
                      if (Object.keys(area.climate).length === 0) delete area.climate
                    }
                  })
                }
              >
                Remove
              </Button>
            </span>
          }
        >
          <NoiseFields
            noise={climate.noises?.[value] ?? {}}
            at={['climate', 'noises', value]}
            gesture={gesture}
            change={(recipe) =>
              edit((d) => {
                d.climate ??= {}
                d.climate.noises ??= {}
                d.climate.noises[value] ??= {}
                recipe(d.climate.noises[value]!)
              })
            }
          />
        </FieldGroup>
      ))}
      <Hint>
        More climate values (an &ldquo;evil&rdquo; one, oceans&apos; continentalness): each area
        picks a range of each.
      </Hint>
      <Button onClick={addClimate}>Add a climate value</Button>
      <FieldGroup data-path="climate.jitter" header={<strong>Borders</strong>}>
        <Hint>How far the borders between areas wander, so they aren&apos;t smooth curves.</Hint>
        <NumberField
          label="Wander"
          dataPath="climate.jitter.amplitude"
          value={climate.jitter?.amplitude}
          placeholder={String(TERRAIN_DEFAULTS.jitterAmplitude)}
          step={1}
          min={0}
          {...gesture}
          onChange={(value) =>
            edit((d) => {
              d.climate ??= {}
              d.climate.jitter ??= {}
              setKey(d.climate.jitter, 'amplitude', value)
              if (Object.keys(d.climate.jitter).length === 0) delete d.climate.jitter
              if (Object.keys(d.climate).length === 0) delete d.climate
            })
          }
        />
        <NoiseFields
          noise={climate.jitter?.noise ?? {}}
          at={['climate', 'jitter', 'noise']}
          gesture={gesture}
          change={(recipe) =>
            edit((d) => {
              d.climate ??= {}
              d.climate.jitter ??= {}
              d.climate.jitter.noise ??= {}
              recipe(d.climate.jitter.noise)
            })
          }
        />
      </FieldGroup>
    </Section>
  )
}

/** A biome area's own layers, if it has them. */
function AreaLayers({
  name,
  area,
  ...parts
}: Parts & { name: string; area: NonNullable<TerrainFile['biomes']>[string] }) {
  const { edit } = parts
  return (
    <>
      {(['layers', 'underwater'] as const).map((key) => (
        <div key={key}>
          <CheckField
            label={key === 'layers' ? 'Own ground layers' : 'Own sea floor layers'}
            dataPath={fieldPath(['biomes', name, key])}
            value={area[key] !== undefined}
            onChange={(own) => edit((d) => setAreaOwnLayers(d, name, key, own))}
          />
          {area[key] !== undefined && (
            <LayerRows {...parts} at={{ area: name, key }} base={['biomes', name, key]} />
          )}
        </div>
      ))}
    </>
  )
}

/**
 * A biome area's own terrain, if it has one: its base height, the file's noises scaled, and noises of its own; in a
 * file with a 3D ground ([threeD]), its own 3D noises too.
 */
function AreaTerrainFields({
  name,
  area,
  threeD,
  edit,
  gesture,
}: {
  name: string
  area: NonNullable<TerrainFile['biomes']>[string]
  threeD: boolean
  edit: Edit
  gesture: Parts['gesture']
}) {
  const terrain = area.terrain
  const at = ['biomes', name, 'terrain']
  const collection: Collection = { area: name }
  const densityCollection: AreaNoises = { area: name, density: true }
  const names = Object.keys(terrain?.noises ?? {})
  const densityNames = Object.keys(terrain?.density?.noises ?? {})
  const addDensity = async () => {
    const noise = await askName('3D noise', densityNames, densityNames.length ? '' : 'arches')
    if (noise) edit((d) => addEntry(d, densityCollection, noise, newEntry(densityCollection, '')))
  }
  const change = (recipe: (terrain: NonNullable<typeof area.terrain>) => void) =>
    edit((d) => {
      const target = d.biomes?.[name]?.terrain
      if (target) recipe(target)
    })
  const add = async () => {
    const noise = await askName('terrain noise', names, names.length ? '' : 'peaks')
    if (noise) edit((d) => addEntry(d, collection, noise, newEntry(collection, '')))
  }
  return (
    <div data-path={fieldPath(at)}>
      <CheckField
        label="Own terrain"
        value={terrain !== undefined}
        onChange={(own) =>
          edit((d) => {
            const target = d.biomes?.[name]
            if (!target) return
            if (own) target.terrain = {}
            else delete target.terrain
          })
        }
      />
      {terrain && (
        <>
          <NumberField
            label="Its base height"
            dataPath={fieldPath([...at, 'base'])}
            value={terrain.base}
            placeholder="the file's"
            step={1}
            {...gesture}
            onChange={(value) => change((t) => setKey(t, 'base', whole(value)))}
          />
          <NumberField
            label="Noises times"
            dataPath={fieldPath([...at, 'scale'])}
            value={terrain.scale}
            placeholder="1"
            step={0.1}
            min={0}
            {...gesture}
            onChange={(value) => change((t) => setKey(t, 'scale', value))}
          />
          <NoiseEntries
            collection={collection}
            noises={terrain.noises}
            at={[...at, 'noises']}
            edit={edit}
            gesture={gesture}
          />
          <Button size="small" icon="plus" onClick={add}>
            Add a noise of its own
          </Button>
          {(threeD || terrain.density) && (
            <CheckField
              label="Own 3D noises"
              dataPath={fieldPath([...at, 'density'])}
              value={terrain.density !== undefined}
              onChange={(own) => edit((d) => setAreaDensity(d, name, own))}
            />
          )}
          {terrain.density && (
            <>
              <NumberField
                label="3D noises times"
                dataPath={fieldPath([...at, 'density', 'scale'])}
                value={terrain.density.scale}
                placeholder="1"
                step={0.1}
                min={0}
                {...gesture}
                onChange={(value) =>
                  change((t) => {
                    if (t.density) setKey(t.density, 'scale', value)
                  })
                }
              />
              <NoiseEntries
                collection={densityCollection}
                noises={terrain.density.noises}
                at={[...at, 'density', 'noises']}
                edit={edit}
                gesture={gesture}
              />
              <Button size="small" icon="plus" onClick={addDensity}>
                Add a 3D noise of its own
              </Button>
            </>
          )}
        </>
      )}
    </div>
  )
}
