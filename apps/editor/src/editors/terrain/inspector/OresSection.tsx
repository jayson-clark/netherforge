import { setKey } from '@/core/draft'
import {
  OreDistributionValues,
  TERRAIN_DEFAULTS,
  type OreDistribution,
  type TerrainFile,
} from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { FieldGroup, NumberField, Section, SelectField } from '@/ui/fields'
import { Muted } from '@/ui/text'
import {
  addEntry,
  deleteEntry,
  firstOre,
  namesOf,
  newEntry,
  renameEntry,
  setBlock,
} from '@/editors/terrain/ops'
import {
  AddButton,
  AreaFilter,
  BlockPicker,
  EntryHeader,
  GROUND_KINDS,
  IdListField,
  NO_IDS,
  type Parts,
  askName,
  whole,
} from './fields'

export function OresSection({ model, edit, gesture, blocks, customBlocks }: Parts) {
  const stone = model.stone?.block ?? model.stone?.customBlock ?? TERRAIN_DEFAULTS.stone
  const names = namesOf(model, 'ores')
  const add = async () => {
    const name = await askName('ore', names, names.length ? '' : 'iron')
    if (name)
      edit((d) =>
        addEntry(d, 'ores', name, newEntry('ores', firstOre(blocks, TERRAIN_DEFAULTS.stone))),
      )
  }
  return (
    <Section title="Ores" icon="gem" actions={<AddButton one="ore" onAdd={add} />}>
      {names.length === 0 && <Muted>No ores.</Muted>}
      {names.map((name) => {
        const ore = model.ores?.[name]
        if (!ore) return null
        const at = ['ores', name]
        const change = (recipe: (ore: NonNullable<TerrainFile['ores']>[string]) => void) =>
          edit((d) => {
            const target = d.ores?.[name]
            if (target) recipe(target)
          })
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="ore"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'ores', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'ores', name))}
              />
            }
          >
            <BlockPicker
              choice={ore}
              at={at}
              kinds={GROUND_KINDS}
              fallback={TERRAIN_DEFAULTS.stone}
              blocks={blocks}
              customBlocks={customBlocks}
              structures={NO_IDS}
              onChange={(kind, value) => change((o) => setBlock(o, kind, value))}
            />
            <IdListField
              label="Replaces"
              dataPath={fieldPath([...at, 'replace'])}
              value={ore.replace}
              placeholder={ore.replaceCustomBlocks?.length ? 'none' : stone}
              onChange={(ids) => change((o) => setKey(o, 'replace', ids))}
            />
            <IdListField
              label="Replaces project blocks"
              dataPath={fieldPath([...at, 'replaceCustomBlocks'])}
              value={ore.replaceCustomBlocks}
              placeholder="none"
              onChange={(ids) => change((o) => setKey(o, 'replaceCustomBlocks', ids))}
            />
            <NumberField
              label="Vein size"
              dataPath={fieldPath([...at, 'size'])}
              value={ore.size}
              placeholder={String(TERRAIN_DEFAULTS.oreSize)}
              step={1}
              min={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'size', whole(value)))}
            />
            <NumberField
              label="Veins per chunk"
              dataPath={fieldPath([...at, 'veins'])}
              value={ore.veins}
              placeholder={String(TERRAIN_DEFAULTS.oreVeins)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'veins', whole(value)))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={ore.minY}
              placeholder={String(TERRAIN_DEFAULTS.oreMinY)}
              step={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={ore.maxY}
              placeholder={String(TERRAIN_DEFAULTS.oreMaxY)}
              step={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'maxY', whole(value)))}
            />
            <SelectField
              label="Spread"
              dataPath={fieldPath([...at, 'distribution'])}
              value={ore.distribution ?? 'uniform'}
              options={OreDistributionValues}
              onChange={(value) =>
                change((o) =>
                  setKey(
                    o,
                    'distribution',
                    value === 'uniform' ? undefined : (value as OreDistribution),
                  ),
                )
              }
            />
            <AreaFilter model={model} at={at} areas={ore.biomes} change={change} />
          </FieldGroup>
        )
      })}
    </Section>
  )
}
