import { setKey } from '@/core/draft'
import { CaveTypeValues, TERRAIN_DEFAULTS, type CaveType, type TerrainFile } from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { FieldGroup, NumberField, Section, SelectField } from '@/ui/fields'
import { Muted } from '@/ui/text'
import { addEntry, deleteEntry, namesOf, newEntry, renameEntry } from '@/editors/terrain/ops'
import { AddButton, AreaFilter, EntryHeader, type Parts, askName, whole } from './fields'
import { NoiseFields } from './noise'

export function CavesSection({ model, edit, gesture }: Parts) {
  const names = namesOf(model, 'caves')
  const add = async () => {
    const name = await askName('cave', names, names.length ? '' : 'caverns')
    if (name) edit((d) => addEntry(d, 'caves', name, newEntry('caves', '')))
  }
  return (
    <Section title="Caves" icon="pickaxe" actions={<AddButton one="cave" onAdd={add} />}>
      {names.length === 0 && <Muted>No caves: the ground is solid.</Muted>}
      {names.map((name) => {
        const cave = model.caves?.[name]
        if (!cave) return null
        const at = ['caves', name]
        const change = (recipe: (cave: NonNullable<TerrainFile['caves']>[string]) => void) =>
          edit((d) => {
            const target = d.caves?.[name]
            if (target) recipe(target)
          })
        const type = cave.type ?? 'cheese'
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="cave"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'caves', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'caves', name))}
              />
            }
          >
            <SelectField
              label="Shape"
              dataPath={fieldPath([...at, 'type'])}
              value={type}
              options={CaveTypeValues}
              onChange={(value) =>
                change((c) =>
                  setKey(c, 'type', value === 'cheese' ? undefined : (value as CaveType)),
                )
              }
            />
            <NumberField
              label="Threshold"
              dataPath={fieldPath([...at, 'threshold'])}
              value={cave.threshold}
              placeholder={String(
                type === 'cheese' ? TERRAIN_DEFAULTS.caveCheese : TERRAIN_DEFAULTS.caveSpaghetti,
              )}
              step={0.01}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'threshold', value))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={cave.minY}
              placeholder={String(TERRAIN_DEFAULTS.caveMinY)}
              step={1}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={cave.maxY}
              placeholder={String(TERRAIN_DEFAULTS.caveMaxY)}
              step={1}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'maxY', whole(value)))}
            />
            <NumberField
              label="Fades over"
              dataPath={fieldPath([...at, 'fade'])}
              value={cave.fade}
              placeholder="0"
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'fade', whole(value)))}
            />
            <NumberField
              label="Below the surface"
              dataPath={fieldPath([...at, 'depth'])}
              value={cave.depth}
              placeholder={String(TERRAIN_DEFAULTS.caveDepth)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'depth', whole(value)))}
            />
            <AreaFilter model={model} at={at} areas={cave.biomes} change={change} />
            <Muted>Pattern</Muted>
            <NoiseFields
              noise={cave.noise ?? {}}
              at={[...at, 'noise']}
              gesture={gesture}
              change={(recipe) =>
                change((c) => {
                  c.noise ??= {}
                  recipe(c.noise)
                })
              }
            />
          </FieldGroup>
        )
      })}
    </Section>
  )
}
