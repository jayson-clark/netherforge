import { setKey } from '@/core/draft'
import {
  DecorationPlacementValues,
  TERRAIN_DEFAULTS,
  type DecorationPlacement,
  type TerrainFile,
} from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { CheckField, FieldGroup, NumberField, Section, SelectField } from '@/ui/fields'
import { Muted } from '@/ui/text'
import {
  addEntry,
  blockKind,
  deleteEntry,
  namesOf,
  newEntry,
  renameEntry,
  setBlock,
  type BlockKind,
} from '@/editors/terrain/ops'
import {
  AddButton,
  AreaFilter,
  BlockPicker,
  EntryHeader,
  IdListField,
  type Parts,
  askName,
  whole,
} from './fields'
import { NoiseFields } from './noise'

/** What a decoration places: a block of the game's or the project's, or a structure. */
const DECORATION_KINDS: readonly BlockKind[] = ['block', 'customBlock', 'structure']

const PLACEMENT_LABELS: Record<DecorationPlacement, string> = {
  surface: 'on the ground',
  underwater: 'on the sea floor',
  underground: 'in the ground',
  caveFloor: "on a cave's floor",
  caveCeiling: "from a cave's ceiling",
}

/** Where a structure may go: what a column alone decides, so every chunk it reaches places it alike. */
const STRUCTURE_PLACEMENTS: readonly DecorationPlacement[] = [
  'surface',
  'underwater',
  'underground',
]

export function DecorationsSection({
  model,
  edit,
  gesture,
  blocks,
  customBlocks,
  structures,
  lootTables,
}: Parts) {
  const names = namesOf(model, 'decorations')
  const add = async () => {
    const name = await askName('decoration', names, names.length ? '' : 'rocks')
    if (name)
      edit((d) => addEntry(d, 'decorations', name, newEntry('decorations', TERRAIN_DEFAULTS.stone)))
  }
  return (
    <Section title="Decorations" icon="leaf" actions={<AddButton one="decoration" onAdd={add} />}>
      {names.length === 0 && <Muted>No decorations: nothing is scattered on the ground.</Muted>}
      {names.map((name) => {
        const decoration = model.decorations?.[name]
        if (!decoration) return null
        const at = ['decorations', name]
        const change = (
          recipe: (decoration: NonNullable<TerrainFile['decorations']>[string]) => void,
        ) =>
          edit((d) => {
            const target = d.decorations?.[name]
            if (target) recipe(target)
          })
        const structure = blockKind(decoration) === 'structure'
        const placement = decoration.placement ?? 'surface'
        const placements = structure ? STRUCTURE_PLACEMENTS : DecorationPlacementValues
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="decoration"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'decorations', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'decorations', name))}
              />
            }
          >
            <BlockPicker
              choice={decoration}
              at={at}
              kinds={DECORATION_KINDS}
              fallback={TERRAIN_DEFAULTS.stone}
              blocks={blocks}
              customBlocks={customBlocks}
              structures={structures}
              onChange={(kind, value) =>
                change((it) => {
                  setBlock(it, kind, value)
                  if (kind !== 'structure') delete it.rotate
                  else if (it.placement && !STRUCTURE_PLACEMENTS.includes(it.placement))
                    delete it.placement
                })
              }
            />
            <SelectField
              label="Placed"
              dataPath={fieldPath([...at, 'placement'])}
              value={placement}
              options={placements.map((it) => ({ value: it, label: PLACEMENT_LABELS[it] }))}
              onChange={(value) =>
                change((it) =>
                  setKey(
                    it,
                    'placement',
                    value === 'surface' ? undefined : (value as DecorationPlacement),
                  ),
                )
              }
            />
            <NumberField
              label="Tries per chunk"
              dataPath={fieldPath([...at, 'count'])}
              value={decoration.count}
              placeholder={String(TERRAIN_DEFAULTS.decorationCount)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'count', whole(value)))}
            />
            <NumberField
              label="Chance"
              dataPath={fieldPath([...at, 'chance'])}
              value={decoration.chance}
              placeholder="1"
              step={0.05}
              min={0}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'chance', value))}
            />
            <CheckField
              label="In patches"
              dataPath={fieldPath([...at, 'noise'])}
              value={decoration.noise !== undefined}
              onChange={(on) =>
                change((it) => {
                  if (on) it.noise = { frequency: 0.03 }
                  else {
                    delete it.noise
                    delete it.threshold
                  }
                })
              }
            />
            {decoration.noise && (
              <>
                <NumberField
                  label="Patches from"
                  dataPath={fieldPath([...at, 'threshold'])}
                  value={decoration.threshold}
                  placeholder="0"
                  step={0.05}
                  min={-1}
                  {...gesture}
                  onChange={(value) => change((it) => setKey(it, 'threshold', value))}
                />
                <NoiseFields
                  noise={decoration.noise}
                  at={[...at, 'noise']}
                  gesture={gesture}
                  change={(recipe) =>
                    change((it) => {
                      it.noise ??= {}
                      recipe(it.noise)
                    })
                  }
                />
              </>
            )}
            <IdListField
              label={
                placement === 'underground'
                  ? 'Replaces'
                  : placement === 'caveCeiling'
                    ? 'Hangs from'
                    : 'On'
              }
              dataPath={fieldPath([...at, 'on'])}
              value={decoration.on}
              placeholder={
                decoration.onCustomBlocks?.length
                  ? 'none'
                  : placement === 'underground'
                    ? 'the stone'
                    : 'any ground'
              }
              onChange={(ids) => change((it) => setKey(it, 'on', ids))}
            />
            <IdListField
              label={
                placement === 'underground'
                  ? 'Replaces project blocks'
                  : placement === 'caveCeiling'
                    ? 'Hangs from project blocks'
                    : 'On project blocks'
              }
              dataPath={fieldPath([...at, 'onCustomBlocks'])}
              value={decoration.onCustomBlocks}
              placeholder="none"
              onChange={(ids) => change((it) => setKey(it, 'onCustomBlocks', ids))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={decoration.minY}
              placeholder="the bottom"
              step={1}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={decoration.maxY}
              placeholder="the top"
              step={1}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'maxY', whole(value)))}
            />
            {structure && (
              <CheckField
                label="Turned at random"
                dataPath={fieldPath([...at, 'rotate'])}
                value={decoration.rotate ?? true}
                onChange={(on) => change((it) => setKey(it, 'rotate', on ? undefined : false))}
              />
            )}
            {blockKind(decoration) !== 'customBlock' && (
              <SelectField
                label="Containers filled from"
                dataPath={fieldPath([...at, 'loot'])}
                value={decoration.loot ?? ''}
                options={[
                  { value: '', label: 'no loot table' },
                  ...lootTables.map((id) => ({ value: id, label: id })),
                  ...(decoration.loot && !lootTables.includes(decoration.loot)
                    ? [{ value: decoration.loot, label: `${decoration.loot} (missing)` }]
                    : []),
                ]}
                onChange={(value) => change((it) => setKey(it, 'loot', value || undefined))}
              />
            )}
            <AreaFilter model={model} at={at} areas={decoration.biomes} change={change} />
          </FieldGroup>
        )
      })}
    </Section>
  )
}
