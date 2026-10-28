import { setKey } from '@/core/draft'
import { TERRAIN_DEFAULTS, type TerrainFile } from '@/core/format'
import { NumberField, Section, TextField } from '@/ui/fields'
import { Muted } from '@/ui/text'
import { addEntry, namesOf, newEntry } from '@/editors/terrain/ops'
import { AddButton, BLOCKS_LIST, type Parts, askName, whole } from './fields'
import { DensityFields, NoiseEntries } from './noise'

export function TerrainSection({ model, edit, gesture, blocks }: Parts) {
  const terrain = model.terrain ?? {}
  const names = namesOf(model, 'noises')
  const setTerrain = (change: (terrain: NonNullable<TerrainFile['terrain']>) => void) =>
    edit((d) => {
      d.terrain ??= {}
      change(d.terrain)
      if (Object.keys(d.terrain).length === 0) delete d.terrain
    })
  const add = async () => {
    const name = await askName('terrain noise', names, names.length ? '' : 'hills')
    if (name) edit((d) => addEntry(d, 'noises', name, newEntry('noises', '')))
  }
  return (
    <Section
      title="Terrain"
      icon="mountain"
      actions={<AddButton one="terrain noise" onAdd={add} />}
    >
      <NumberField
        label="Base height"
        dataPath="terrain.base"
        value={terrain.base}
        placeholder={String(TERRAIN_DEFAULTS.base)}
        step={1}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'base', whole(value)))}
      />
      <NumberField
        label="Sea level"
        dataPath="terrain.seaLevel"
        value={terrain.seaLevel}
        placeholder={String(TERRAIN_DEFAULTS.seaLevel)}
        step={1}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'seaLevel', whole(value)))}
      />
      <TextField
        label="Sea"
        dataPath="terrain.fluid"
        value={terrain.fluid}
        placeholder={TERRAIN_DEFAULTS.fluid}
        list={blocks.length ? BLOCKS_LIST : undefined}
        onChange={(value) => setTerrain((t) => setKey(t, 'fluid', value.trim() || undefined))}
      />
      <NumberField
        label="Blend borders over"
        dataPath="terrain.blend"
        value={terrain.blend}
        placeholder={String(TERRAIN_DEFAULTS.blend)}
        step={1}
        min={0}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'blend', whole(value)))}
      />
      {names.length === 0 && <Muted>No noises: the ground is flat at the base height.</Muted>}
      <NoiseEntries
        collection="noises"
        noises={terrain.noises}
        at={['terrain', 'noises']}
        edit={edit}
        gesture={gesture}
      />
      <DensityFields model={model} edit={edit} gesture={gesture} />
    </Section>
  )
}
