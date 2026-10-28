/** A noise's fields, lists of named noises, and the 3D ground's noises (`densityNoises`). */
import { setKey } from '@/core/draft'
import {
  NoiseFractalValues,
  NoiseTypeValues,
  TERRAIN_DEFAULTS,
  TERRAIN_LIMITS,
  type Islands,
  type NoiseDef,
  type NoiseFractal,
  type NoiseType,
} from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { Button } from '@/ui/Button'
import { CheckField, FieldGroup, NumberField, SelectField } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import {
  addEntry,
  deleteEntry,
  newEntry,
  noiseEntry,
  octavesOf,
  renameEntry,
  setDensity,
  setIslands,
  setNoiseField,
  type AreaNoises,
} from '@/editors/terrain/ops'
import { AreaFilter, type Edit, EntryHeader, type Parts, askName, whole } from './fields'

/** The fields of one noise, at [at] (a JSON path in the file). */
export function NoiseFields({
  noise,
  at,
  change,
  gesture,
}: {
  noise: NoiseDef
  at: (string | number)[]
  change: (recipe: (noise: NoiseDef) => void) => void
  gesture: Parts['gesture']
}) {
  const octaves = octavesOf(noise)
  const path = (key: string) => fieldPath([...at, key])
  return (
    <>
      <SelectField
        label="Type"
        dataPath={path('type')}
        value={noise.type ?? 'openSimplex2'}
        options={NoiseTypeValues}
        onChange={(value) =>
          change((d) =>
            setNoiseField(d, 'type', value === 'openSimplex2' ? undefined : (value as NoiseType)),
          )
        }
      />
      <NumberField
        label="Frequency"
        dataPath={path('frequency')}
        value={noise.frequency}
        placeholder={String(TERRAIN_DEFAULTS.noiseFrequency)}
        step={0.001}
        min={0}
        {...gesture}
        onChange={(value) => change((d) => setNoiseField(d, 'frequency', value))}
      />
      <NumberField
        label="Octaves"
        dataPath={path('octaves')}
        value={noise.octaves}
        placeholder="1"
        step={1}
        min={1}
        onChange={(value) => change((d) => setNoiseField(d, 'octaves', whole(value)))}
      />
      {octaves > 1 && (
        <>
          <SelectField
            label="Combined as"
            dataPath={path('fractal')}
            value={noise.fractal ?? 'fbm'}
            options={NoiseFractalValues}
            onChange={(value) =>
              change((d) =>
                setNoiseField(d, 'fractal', value === 'fbm' ? undefined : (value as NoiseFractal)),
              )
            }
          />
          <NumberField
            label="Lacunarity"
            dataPath={path('lacunarity')}
            value={noise.lacunarity}
            placeholder={String(TERRAIN_DEFAULTS.noiseLacunarity)}
            step={0.1}
            min={1}
            {...gesture}
            onChange={(value) => change((d) => setNoiseField(d, 'lacunarity', value))}
          />
          <NumberField
            label="Gain"
            dataPath={path('gain')}
            value={noise.gain}
            placeholder={String(TERRAIN_DEFAULTS.noiseGain)}
            step={0.05}
            min={0}
            {...gesture}
            onChange={(value) => change((d) => setNoiseField(d, 'gain', value))}
          />
        </>
      )}
    </>
  )
}

/** The ground made 3D (`terrain.density`): its 3D noises and the islands floating over it. */
export function DensityFields({ model, edit, gesture }: Pick<Parts, 'model' | 'edit' | 'gesture'>) {
  const density = model.terrain?.density
  const names = Object.keys(density?.noises ?? {})
  const islands = density?.islands
  const at = ['terrain', 'density']
  const add = async () => {
    const name = await askName('3D noise', names, names.length ? '' : 'overhangs')
    if (name) edit((d) => addEntry(d, 'densityNoises', name, newEntry('densityNoises', '')))
  }
  const changeIslands = (recipe: (target: Islands) => void) =>
    edit((d) => {
      const target = d.terrain?.density?.islands
      if (target) recipe(target)
    })
  return (
    <div data-path={fieldPath(at)}>
      <CheckField
        label="3D ground (overhangs, arches, islands)"
        value={density !== undefined}
        onChange={(on) => edit((d) => setDensity(d, on))}
      />
      {density && (
        <>
          <Hint>
            Each 3D noise moves the ground&apos;s surface up or down by its amplitude, differently
            at each height, so it leans out over itself.
          </Hint>
          <NoiseEntries
            collection="densityNoises"
            noises={density.noises}
            at={[...at, 'noises']}
            edit={edit}
            gesture={gesture}
          />
          <Button size="small" icon="plus" onClick={add}>
            Add a 3D noise
          </Button>
          <CheckField
            label="Floating islands"
            value={islands !== undefined}
            onChange={(on) => edit((d) => setIslands(d, on))}
          />
          {islands && (
            <FieldGroup data-path={fieldPath([...at, 'islands'])} header={<strong>Islands</strong>}>
              <NumberField
                label="Height"
                dataPath={fieldPath([...at, 'islands', 'y'])}
                value={islands.y}
                placeholder={String(TERRAIN_DEFAULTS.islandsY)}
                step={1}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'y', whole(value)))}
              />
              <NumberField
                label="Thickness"
                dataPath={fieldPath([...at, 'islands', 'thickness'])}
                value={islands.thickness}
                placeholder={String(TERRAIN_DEFAULTS.islandsThickness)}
                step={1}
                min={TERRAIN_LIMITS.minIslandsThickness}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'thickness', whole(value)))}
              />
              <NumberField
                label="Threshold"
                dataPath={fieldPath([...at, 'islands', 'threshold'])}
                value={islands.threshold}
                placeholder={String(TERRAIN_DEFAULTS.islandsThreshold)}
                step={0.05}
                min={-1}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'threshold', value))}
              />
              <AreaFilter
                model={model}
                at={[...at, 'islands']}
                areas={islands.biomes}
                change={changeIslands}
              />
              <Muted>Pattern</Muted>
              <NoiseFields
                noise={islands.noise ?? {}}
                at={[...at, 'islands', 'noise']}
                gesture={gesture}
                change={(recipe) =>
                  changeIslands((i) => {
                    i.noise ??= {}
                    recipe(i.noise)
                  })
                }
              />
            </FieldGroup>
          )}
        </>
      )}
    </div>
  )
}

/**
 * Named terrain noises, each its amplitude and pattern: the file's, or a biome area's own; or (a `densityNoises`
 * collection, or an area's with `density`) 3D noises, with their squash too.
 */
export function NoiseEntries({
  collection,
  noises,
  at,
  edit,
  gesture,
}: {
  collection: 'noises' | 'densityNoises' | AreaNoises
  noises: Record<string, { amplitude?: number; squash?: number; noise?: NoiseDef }> | undefined
  at: string[]
  edit: Edit
  gesture: Parts['gesture']
}) {
  const names = Object.keys(noises ?? {})
  const threeD =
    collection === 'densityNoises' || (typeof collection === 'object' && !!collection.density)
  const one = threeD ? '3D noise' : 'terrain noise'
  return (
    <>
      {names.map((name) => {
        const entry = noises?.[name]
        if (!entry) return null
        const here = [...at, name]
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(here)}
            header={
              <EntryHeader
                name={name}
                one={one}
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, collection, name, to))}
                onDelete={() => edit((d) => deleteEntry(d, collection, name))}
              />
            }
          >
            <NumberField
              label="Amplitude"
              dataPath={fieldPath([...here, 'amplitude'])}
              value={entry.amplitude}
              placeholder={String(TERRAIN_DEFAULTS.amplitude)}
              step={1}
              {...gesture}
              onChange={(value) =>
                edit((d) => {
                  const target = noiseEntry(d, collection, name)
                  if (target) setKey(target, 'amplitude', value)
                })
              }
            />
            {threeD && (
              <NumberField
                label="Squash"
                dataPath={fieldPath([...here, 'squash'])}
                value={entry.squash}
                placeholder={String(TERRAIN_DEFAULTS.squash)}
                step={0.1}
                min={0.05}
                {...gesture}
                onChange={(value) =>
                  edit((d) => {
                    const target = noiseEntry(d, collection, name)
                    if (target) setKey(target, 'squash', value)
                  })
                }
              />
            )}
            <NoiseFields
              noise={entry.noise ?? {}}
              at={[...here, 'noise']}
              gesture={gesture}
              change={(recipe) =>
                edit((d) => {
                  const target = noiseEntry(d, collection, name)
                  if (!target) return
                  target.noise ??= {}
                  recipe(target.noise)
                })
              }
            />
          </FieldGroup>
        )
      })}
    </>
  )
}
