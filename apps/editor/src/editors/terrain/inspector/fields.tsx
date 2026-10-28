/** What the terrain inspector's sections share: their props, block pickers, area filters and named entries. */
import { TERRAIN_DEFAULTS, TERRAIN_LIMITS, type TerrainFile } from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { ID_NAMES, nameProblem } from '@/editors/shared/KeyedList'
import { Button, IconButton } from '@/ui/Button'
import { ask } from '@/ui/dialogs'
import { CheckField, FieldGroup, NumberField, SelectField, TextField } from '@/ui/fields'
import { Muted } from '@/ui/text'
import {
  addLayer,
  blockKind,
  layersAt,
  moveLayer,
  namesOf,
  removeLayer,
  setInArea,
  setLayer,
  type BlockChoice,
  type BlockKind,
  type LayerList,
} from '@/editors/terrain/ops'

export type Edit = (recipe: (draft: TerrainFile) => void) => void

export const BLOCKS_LIST = 'nf-terrain-blocks'

export const BIOMES_LIST = 'nf-terrain-biomes'

export const whole = (value: number | undefined) =>
  value === undefined ? undefined : Math.round(value)

/** Adds a named entry after asking its name; the name is checked against the ones taken. */
export async function askName(one: string, taken: string[], initial = ''): Promise<string | null> {
  return ask.prompt({
    title: `New ${one}`,
    label: 'Name',
    initial,
    confirmLabel: 'Add',
    validate: (value) => nameProblem(value, taken.includes(value), one, ID_NAMES),
  })
}

export const NO_IDS: string[] = []

export interface Parts {
  model: TerrainFile
  edit: Edit
  gesture: { onGestureStart: () => void; onGestureEnd: () => void }
  blocks: string[]
  customBlocks: string[]
  structures: string[]
  biomes: string[]
  lootTables: string[]
}

const KIND_LABELS: Record<BlockKind, string> = {
  block: "the game's",
  customBlock: "the project's",
  structure: 'a structure',
}

/** A select of [ids] that keeps a value it doesn't list (a missing one) and says when there's none to pick. */
function IdSelect({
  label,
  dataPath,
  value,
  ids,
  none,
  onChange,
}: {
  label: string
  dataPath: string
  value: string | undefined
  ids: string[]
  none: string
  onChange: (value: string) => void
}) {
  return (
    <SelectField
      label={label}
      dataPath={dataPath}
      value={value ?? ''}
      options={[
        ...ids.map((id) => ({ value: id, label: id })),
        ...(value && !ids.includes(value) ? [{ value, label: `${value} (missing)` }] : []),
        ...(ids.length === 0 && !value ? [{ value: '', label: none }] : []),
      ]}
      onChange={onChange}
    />
  )
}

/**
 * The block something places: a block state of the game's, one of the project's blocks (a plain cube), or, where
 * [kinds] has it, one of the project's structures. [fallback] is the game's block it starts as, and what an empty
 * one means when [optional].
 */
export function BlockPicker({
  choice,
  at,
  kinds,
  fallback,
  optional = false,
  blocks,
  customBlocks,
  structures,
  onChange,
}: {
  choice: BlockChoice | undefined
  at: (string | number)[]
  kinds: readonly BlockKind[]
  fallback: string
  optional?: boolean
  blocks: string[]
  customBlocks: string[]
  structures: string[]
  onChange: (kind: BlockKind, value: string) => void
}) {
  const kind = blockKind(choice)
  const path = (key: string) => fieldPath([...at, key])
  return (
    <>
      <SelectField
        label="Block of"
        dataPath={path(kind)}
        value={kind}
        options={kinds.map((it) => ({ value: it, label: KIND_LABELS[it] }))}
        onChange={(value) => {
          const next = value as BlockKind
          onChange(
            next,
            next === 'block'
              ? optional
                ? ''
                : fallback
              : ((next === 'customBlock' ? customBlocks : structures)[0] ?? ''),
          )
        }}
      />
      {kind === 'block' && (
        <TextField
          label="Block"
          dataPath={path('block')}
          value={choice?.block}
          placeholder={optional ? fallback : undefined}
          list={blocks.length ? BLOCKS_LIST : undefined}
          onChange={(value) => onChange('block', value.trim())}
        />
      )}
      {kind === 'customBlock' && (
        <IdSelect
          label="Block"
          dataPath={path('customBlock')}
          value={choice?.customBlock}
          ids={customBlocks}
          none="(the project has no blocks)"
          onChange={(value) => onChange('customBlock', value)}
        />
      )}
      {kind === 'structure' && (
        <IdSelect
          label="Structure"
          dataPath={path('structure')}
          value={choice?.structure}
          ids={structures}
          none="(the project has no structures)"
          onChange={(value) => onChange('structure', value)}
        />
      )}
    </>
  )
}

/** Which of the file's biome areas an ore, a cave or a decoration keeps to: none ticked is all of them. */
export function AreaFilter({
  model,
  at,
  areas,
  change,
}: {
  model: TerrainFile
  at: (string | number)[]
  areas: string[] | undefined
  change: (recipe: (entry: { biomes?: string[] }) => void) => void
}) {
  const names = namesOf(model, 'biomes')
  if (names.length < 2 && !areas?.length) return null
  return (
    <div data-path={fieldPath([...at, 'biomes'])}>
      <Muted>{areas?.length ? 'Only in' : 'In every biome area, or only in'}</Muted>
      {names.map((name) => (
        <CheckField
          key={name}
          label={name}
          value={areas?.includes(name) ?? false}
          onChange={(on) => change((entry) => setInArea(entry, name, on))}
        />
      ))}
    </div>
  )
}

/** Block ids as a comma-separated list; empty is none. */
export function IdListField({
  label,
  dataPath,
  value,
  placeholder,
  onChange,
}: {
  label: string
  dataPath: string
  value: string[] | undefined
  placeholder: string
  onChange: (ids: string[] | undefined) => void
}) {
  return (
    <TextField
      label={label}
      dataPath={dataPath}
      value={(value ?? []).join(', ')}
      placeholder={placeholder}
      list={BLOCKS_LIST}
      onChange={(text) => {
        const ids = text
          .split(',')
          .map((it) => it.trim())
          .filter(Boolean)
        onChange(ids.length ? ids : undefined)
      }}
    />
  )
}

/** A named entry's header: its name, rename and delete. */
export function EntryHeader({
  name,
  one,
  taken,
  onRename,
  onDelete,
}: {
  name: string
  one: string
  taken: string[]
  onRename: (to: string) => void
  onDelete: () => void
}) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <strong style={{ flex: 1 }}>{name}</strong>
      <IconButton
        icon="edit"
        label={`Rename ${one} ${name}`}
        size={14}
        onClick={async () => {
          const to = await ask.prompt({
            title: `Rename ${one}`,
            label: 'Name',
            initial: name,
            confirmLabel: 'Rename',
            validate: (value) =>
              nameProblem(value, value !== name && taken.includes(value), one, ID_NAMES),
          })
          if (to && to !== name) onRename(to)
        }}
      />
      <IconButton icon="trash" label={`Delete ${one} ${name}`} size={14} onClick={onDelete} />
    </span>
  )
}

/** The "add" button of a named collection's section. */
export function AddButton({ one, onAdd }: { one: string; onAdd: () => void }) {
  return <IconButton icon="plus" label={`Add ${one}`} size={14} onClick={onAdd} />
}

/** One list of layers, from the surface down. */
export function LayerRows({
  model,
  at,
  edit,
  blocks,
  customBlocks,
  structures,
  gesture,
  base,
}: Parts & { at: LayerList; base: (string | number)[] }) {
  const layers = layersAt(model, at)
  return (
    <>
      {layers.map((layer, index) => (
        <FieldGroup
          key={index}
          data-path={fieldPath([...base, index])}
          header={<strong>Layer {index + 1}</strong>}
        >
          <BlockPicker
            choice={layer}
            at={[...base, index]}
            kinds={GROUND_KINDS}
            fallback={TERRAIN_DEFAULTS.stone}
            blocks={blocks}
            customBlocks={customBlocks}
            structures={structures}
            onChange={(kind, value) =>
              edit((d) => setLayer(d, at, index, { block: [kind, value] }))
            }
          />
          <NumberField
            label="Thickness"
            dataPath={fieldPath([...base, index, 'thickness'])}
            value={layer.thickness}
            placeholder="1"
            step={1}
            min={1}
            {...gesture}
            onChange={(value) => edit((d) => setLayer(d, at, index, { thickness: whole(value) }))}
          />
          <span style={{ display: 'flex', gap: 4 }}>
            <IconButton
              icon="arrowUp"
              label={`Move layer ${index + 1} up`}
              size={14}
              disabled={index === 0}
              onClick={() => edit((d) => moveLayer(d, at, index, index - 1))}
            />
            <IconButton
              icon="arrowDown"
              label={`Move layer ${index + 1} down`}
              size={14}
              disabled={index === layers.length - 1}
              onClick={() => edit((d) => moveLayer(d, at, index, index + 1))}
            />
            <IconButton
              icon="trash"
              label={`Delete layer ${index + 1}`}
              size={14}
              onClick={() => edit((d) => removeLayer(d, at, index))}
            />
          </span>
        </FieldGroup>
      ))}
      {layers.length < TERRAIN_LIMITS.maxLayers && (
        <Button
          size="small"
          icon="plus"
          onClick={() => edit((d) => addLayer(d, at, TERRAIN_DEFAULTS.stone))}
        >
          Add a layer
        </Button>
      )}
    </>
  )
}

/** What the ground can be made of: blocks of the game's or the project's (plain cubes). */
export const GROUND_KINDS: readonly BlockKind[] = ['block', 'customBlock']
