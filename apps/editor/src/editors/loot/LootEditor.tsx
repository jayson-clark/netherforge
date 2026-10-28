/**
 * A loot table (`loot/<id>.json`): the selected pool's entries as cards
 * (each with its item's icon, its weight and its share of a pick, its count
 * and its conditions), buttons to add an item, another loot table, one of
 * the game's or nothing, and a preview roll by format's own roller with a
 * seed. The inspector edits the pool (rolls, bonus rolls, conditions) and the
 * selected entry: its item with the shared item form, the table it rolls,
 * count, weight, quality and conditions.
 *
 * Pools are listed in the outline (`LootOutline`). Problems focus the field
 * they're about: the pool or entry is selected first.
 */
import { useMemo, useState } from 'react'
import { setKey } from '@/core/draft'
import {
  ENCHANTMENT_REGISTRY,
  LOOT_TABLE_REGISTRY,
  rollLoot,
  type ItemDef,
  type LootCondition,
  type LootEntry,
  type LootPool,
  type LootPreview,
  type LootTableFile,
  type Problem,
} from '@/core/format'
import { mainFileIn, mainFileOf, resourceIdsOf, resourceOf } from '@/core/paths'
import { packageText, splitPackagePath } from '@/core/store/packages'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { itemLabel } from '@/minecraft/item/item'
import { useItemTooltip } from '@/minecraft/item/ItemTooltip'
import { currentText, useNameableIds, useProjectItemIds } from '@/minecraft/item/projectItems'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { useApp, useWorkspace } from '@/state/providers'
import { useReferenceNamespace } from '@/state/useResourcePacks'
import { usePrimary, useView, useViewState } from '@/editors/views'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { fieldPath } from '@/editors/shared/focus'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { IngredientPicker } from '@/editors/recipe/IngredientPicker'
import { problemsUnder } from '@/editors/recipe/problems'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { CheckField, Datalist, NumberField, Section, SelectField, TextField } from '@/ui/fields'
import { Icon } from '@/ui/Icon'
import { Empty, Muted } from '@/ui/text'
import {
  addCondition,
  addEntry,
  addPool,
  CONDITION_TYPES,
  conditionLabel,
  conditionsAt,
  ENTRY_TYPES,
  entryLabel,
  moveEntry,
  newCondition,
  newEntry,
  poolNames,
  rangeEnds,
  rangeLabel,
  rangeOf,
  removeCondition,
  removeEntry,
  shares,
  type ConditionsAt,
  type ConditionType,
  type EntryType,
  type LootPick,
} from './ops'
import styles from './LootEditor.module.css'

type Edit = (recipe: (draft: LootTableFile) => void) => void

/** The table's id in its own project or package (`treasure` for `loot/treasure.json`, `gems` for `library:loot/gems.json`). */
const idOf = (path: string) => resourceOf(path)?.id ?? ''

const percent = (share: number) => `${Math.round(share * 1000) / 10}%`

/** `pools.gems.entries[2]`: where a pool or an entry is, for `data-path`s and problems. */
const at = (pool: string, entry?: number, ...rest: (string | number)[]) =>
  fieldPath(
    entry === undefined ? ['pools', pool, ...rest] : ['pools', pool, 'entries', entry, ...rest],
  )

export function LootEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<LootTableFile>(path)
  const allProblems = useWorkspace((s) => s.problems)
  const problems = useMemo(() => allProblems.filter((it) => it.file === path), [allProblems, path])
  const view = useView('loot_table', path)
  const primary = usePrimary('loot_table', path)
  const tooltip = useItemTooltip()

  useFocusRequests(path, (segments) => {
    const [first, pool, third, entry] = segments
    if (first !== 'pools' || typeof pool !== 'string') return segments
    view.select(third === 'entries' && typeof entry === 'number' ? { pool, entry } : { pool })
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const id = idOf(path)
  const names = poolNames(model)
  const pool = primary && names.includes(primary.pool) ? primary.pool : names[0]
  const entry = pool !== undefined && primary?.pool === pool ? primary.entry : undefined
  const selected = pool !== undefined ? model.pools![pool]! : undefined
  const select = (pick: LootPick) => view.select(pick)

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.page}>
          {pool === undefined || selected === undefined ? (
            <div className={styles.empty}>
              <Empty>This loot table has no pools: a roll gives nothing.</Empty>
              <Button
                icon="plus"
                onClick={() => {
                  let name = ''
                  edit((draft) => {
                    name = addPool(draft)
                  })
                  select({ pool: name })
                }}
              >
                Add pool
              </Button>
            </div>
          ) : (
            <PoolView
              path={path}
              name={pool}
              pool={selected}
              selected={entry}
              edit={edit}
              select={select}
              problems={problems}
              tooltip={tooltip}
            />
          )}
          <RollPreview path={path} id={id} tooltip={tooltip} />
          {tooltip.tooltip}
        </div>
      </Stage>
      <InspectorPanel>
        {pool !== undefined && selected !== undefined && (
          <LootInspector
            path={path}
            name={pool}
            pool={selected}
            entry={entry}
            edit={edit}
            problems={problems}
          />
        )}
      </InspectorPanel>
    </EditorScreen>
  )
}

type Tooltip = ReturnType<typeof useItemTooltip>

/** One pool: a card per entry and the buttons that add them. */
function PoolView({
  path,
  name,
  pool,
  selected,
  edit,
  select,
  problems,
  tooltip,
}: {
  path: string
  name: string
  pool: LootPool
  selected: number | undefined
  edit: Edit
  select: (pick: LootPick) => void
  problems: Problem[]
  tooltip: Tooltip
}) {
  const tables = useNameableIds('loot_table')
  const entries = pool.entries ?? []
  const share = shares(pool)
  const self = idOf(path)
  const add = (type: EntryType) => {
    let index = -1
    edit((draft) => {
      index = addEntry(draft, name, newEntry(type, tables.find((it) => it !== self) ?? ''))
    })
    if (index >= 0) select({ pool: name, entry: index })
  }
  return (
    <section className={styles.pool} aria-label={`Pool ${name}`} data-path={at(name)}>
      <header className={styles.poolHeader}>
        <button type="button" className={styles.poolTitle} onClick={() => select({ pool: name })}>
          <Icon name="list" /> {name}
        </button>
        <Muted>
          {rangeLabel(pool.rolls, 1)} {rangeEnds(pool.rolls, 1).max === 1 ? 'pick' : 'picks'}
          {pool.bonusRolls ? `, +${pool.bonusRolls} per luck` : ''}
          {(pool.conditions ?? []).map((it) => `, ${conditionLabel(it)}`).join('')}
        </Muted>
      </header>
      {entries.length === 0 && <Empty>No entries: this pool gives nothing.</Empty>}
      <ol className={styles.entries} aria-label="Entries">
        {entries.map((entry, index) => (
          <EntryCard
            key={index}
            entry={entry}
            share={share[index] ?? 0}
            selected={selected === index}
            dataPath={at(name, index)}
            problems={problemsUnder(problems, at(name, index))}
            tooltip={tooltip}
            onSelect={() => select({ pool: name, entry: index })}
            onUp={index > 0 ? () => moveAndSelect(edit, select, name, index, index - 1) : undefined}
            onDown={
              index < entries.length - 1
                ? () => moveAndSelect(edit, select, name, index, index + 1)
                : undefined
            }
            onRemove={() => {
              edit((draft) => removeEntry(draft, name, index))
              select({ pool: name })
            }}
          />
        ))}
      </ol>
      <div className={styles.adders} role="group" aria-label="Add an entry">
        {ENTRY_TYPES.map((it) => (
          <Button
            key={it.type}
            size="small"
            icon="plus"
            title={`Add ${it.one}`}
            onClick={() => add(it.type)}
          >
            {it.label}
          </Button>
        ))}
      </div>
    </section>
  )
}

function moveAndSelect(
  edit: Edit,
  select: (pick: LootPick) => void,
  pool: string,
  from: number,
  to: number,
) {
  edit((draft) => moveEntry(draft, pool, from, to))
  select({ pool, entry: to })
}

function EntryCard({
  entry,
  share,
  selected,
  dataPath,
  problems,
  tooltip,
  onSelect,
  onUp,
  onDown,
  onRemove,
}: {
  entry: LootEntry
  share: number
  selected: boolean
  dataPath: string
  problems: Problem[]
  tooltip: Tooltip
  onSelect: () => void
  onUp?: () => void
  onDown?: () => void
  onRemove: () => void
}) {
  const label = entryLabel(entry)
  const error = problems.some((it) => it.severity === 'error')
  const hover = entry.type === 'item' ? tooltip.handlers(entry.item) : null
  return (
    <li
      className={cx(styles.entry, selected && styles.selected, error && styles.entryError)}
      data-path={dataPath}
    >
      <button
        type="button"
        className={styles.entryMain}
        aria-pressed={selected}
        aria-label={`Entry: ${label}`}
        title={problems.map((it) => it.message).join('\n') || undefined}
        onClick={onSelect}
        {...(hover ?? {})}
      >
        <span className={styles.icon}>
          {entry.type === 'item' && (entry.item.kind || entry.item.item) ? (
            <ItemIcon item={entry.item} size={32} />
          ) : (
            <Icon
              name={entry.type === 'empty' ? 'close' : entry.type === 'item' ? 'gem' : 'box'}
              size={20}
            />
          )}
        </span>
        <span className={styles.entryText}>
          <span className={styles.entryLabel}>{label}</span>
          <Muted>
            {entry.type === 'item' && `×${rangeLabel(entry.count, 1)} · `}
            weight {entry.weight ?? 1} · {percent(share)} of a pick
            {entry.quality ? ` · quality ${entry.quality}` : ''}
          </Muted>
          {(entry.conditions ?? []).length > 0 && (
            <Muted className={styles.conditions}>
              only if {(entry.conditions ?? []).map(conditionLabel).join(', and ')}
            </Muted>
          )}
        </span>
      </button>
      <span className={styles.entryActions}>
        <IconButton icon="arrowUp" label="Move up" disabled={!onUp} onClick={onUp} />
        <IconButton icon="arrowDown" label="Move down" disabled={!onDown} onClick={onDown} />
        <IconButton icon="trash" label={`Remove ${label}`} onClick={onRemove} />
      </span>
    </li>
  )
}

/**
 * A roll of the table as it is now (unsaved edits too), by format's roller,
 * with no player, tool or luck. Every table there is goes with it, the
 * packages' too (as read), each meaning what its names mean in its own
 * package, as on the server.
 */
function RollPreview({ path, id, tooltip }: { path: string; id: string; tooltip: Tooltip }) {
  const { workspace } = useApp()
  const namespace = useReferenceNamespace()
  const view = useView('loot_table', path)
  const { seed } = useViewState('loot_table', path)
  const [preview, setPreview] = useState<LootPreview | null>(null)
  const roll = (next: number) => {
    const state = workspace.getState()
    const home = state.outline?.namespace ?? ''
    const tables: Record<string, string> = {}
    for (const it of resourceIdsOf(state.files, 'loot_table'))
      tables[`${home}:${it}`] = currentText(state, mainFileIn(state.files, 'loot_table', it)) ?? ''
    for (const [pkg, outline] of Object.entries(state.outline?.packages ?? {}))
      for (const it of outline.resources.loot_table ?? []) {
        const file = mainFileOf('loot_table', it, outline.groups?.loot_table?.[it])
        const text = packageText(state, `${pkg}:${file}`)
        if (text !== undefined) tables[`${pkg}:${it}`] = text
      }
    const own = splitPackagePath(path)[0] === null ? currentText(state, path) : undefined
    if (own !== undefined) tables[`${namespace}:${id}`] = own
    view.update({ seed: next })
    setPreview(rollLoot(tables, `${namespace}:${id}`, next))
  }
  return (
    <section className={styles.preview} aria-label="Roll preview">
      <header className={styles.previewHeader}>
        <strong>Roll</strong>
        <NumberField
          label="Seed"
          value={seed}
          step={1}
          onChange={(value) => view.update({ seed: Math.round(value ?? 1) })}
        />
        <Button icon="refresh" onClick={() => roll(seed)}>
          Roll
        </Button>
        <Button onClick={() => roll(seed + 1)}>Next seed</Button>
      </header>
      <Muted>
        As the server rolls it with this seed, for no player, tool or luck: conditions that need
        them don't pass.
      </Muted>
      {preview?.error && <p role="alert">{preview.error}</p>}
      {preview && !preview.error && (
        <ul className={styles.drops} aria-label="Rolled">
          {preview.drops.length === 0 && <Empty>Nothing.</Empty>}
          {preview.drops.map((drop, index) =>
            drop.item ? (
              <li
                key={index}
                className={styles.drop}
                // The picture says nothing to a screen reader: the stack's name and count do.
                aria-label={`${drop.item.item ?? itemLabel(drop.item.kind || '?')} ×${drop.count}`}
                {...tooltip.handlers(drop.item)}
              >
                <ItemIcon item={drop.item} size={32} />
                <span className={styles.count}>{drop.count}</span>
              </li>
            ) : (
              <li key={index} className={styles.drop} title={`The server rolls ${drop.table} here`}>
                <Icon name="box" size={20} />
                <Muted>{drop.table}</Muted>
              </li>
            ),
          )}
        </ul>
      )}
    </section>
  )
}

/** The pool's fields, then the selected entry's. */
function LootInspector({
  path,
  name,
  pool,
  entry,
  edit,
  problems,
}: {
  path: string
  name: string
  pool: LootPool
  entry: number | undefined
  edit: Edit
  problems: Problem[]
}) {
  const rolls = rangeEnds(pool.rolls, 1)
  const selected = entry !== undefined ? pool.entries?.[entry] : undefined
  const editPool = (recipe: (draft: LootPool) => void) =>
    edit((draft) => {
      const target = draft.pools?.[name]
      if (target) recipe(target)
    })
  return (
    <>
      <Section title={`Pool ${name}`}>
        <NumberField
          label="Rolls (min)"
          dataPath={at(name, undefined, 'rolls')}
          value={rolls.min}
          step={1}
          min={0}
          onChange={(value) =>
            editPool((draft) =>
              setKey(
                draft,
                'rolls',
                rangeOf(Math.round(value ?? 1), Math.max(rolls.max, Math.round(value ?? 1)), 1),
              ),
            )
          }
        />
        <NumberField
          label="Rolls (max)"
          value={rolls.max}
          step={1}
          min={0}
          onChange={(value) =>
            editPool((draft) =>
              setKey(
                draft,
                'rolls',
                rangeOf(Math.min(rolls.min, Math.round(value ?? 1)), Math.round(value ?? 1), 1),
              ),
            )
          }
        />
        <NumberField
          label="Bonus rolls per luck"
          dataPath={at(name, undefined, 'bonusRolls')}
          value={pool.bonusRolls}
          placeholder="0"
          step={0.5}
          onChange={(value) => editPool((draft) => setKey(draft, 'bonusRolls', value || undefined))}
        />
        <FieldProblems
          problems={except(
            problemsUnder(problems, at(name)),
            at(name, undefined, 'entries'),
            at(name, undefined, 'conditions'),
          )}
        />
      </Section>
      <ConditionsSection
        title="Pool conditions"
        where={{ pool: name }}
        conditions={pool.conditions}
        edit={edit}
        problems={problems}
      />
      {selected && entry !== undefined && (
        <EntryInspector
          path={path}
          pool={name}
          index={entry}
          entry={selected}
          edit={edit}
          problems={problems}
        />
      )}
    </>
  )
}

/** The selected entry: what it gives, how likely, how many, when. */
function EntryInspector({
  path,
  pool,
  index,
  entry,
  edit,
  problems,
}: {
  path: string
  pool: string
  index: number
  entry: LootEntry
  edit: Edit
  problems: Problem[]
}) {
  const glyphs = useGlyphMap()
  const projectItems = useProjectItemIds()
  const nameable = useNameableIds('loot_table')
  const gameData = useWorkspace((s) => s.gameData)
  const self = idOf(path)
  const tables = useMemo(() => nameable.filter((it) => it !== self), [nameable, self])
  const editEntry = (recipe: (draft: LootEntry) => void) =>
    edit((draft) => {
      const target = draft.pools?.[pool]?.entries?.[index]
      if (target) recipe(target)
    })
  const here = at(pool, index)
  const count = entry.type === 'item' ? rangeEnds(entry.count, 1) : null
  return (
    <>
      <Section
        title={`Entry ${index + 1}: ${ENTRY_TYPES.find((it) => it.type === entry.type)?.label}`}
      >
        {entry.type === 'table' && (
          <SelectField
            label="Loot table"
            dataPath={`${here}.table`}
            value={entry.table}
            options={[
              ...(tables.includes(entry.table)
                ? []
                : [{ value: entry.table, label: entry.table || '(choose one)' }]),
              ...tables.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editEntry((draft) => draft.type === 'table' && (draft.table = value))
            }
          />
        )}
        {entry.type === 'vanilla' && (
          <>
            <TextField
              label="Game loot table"
              dataPath={`${here}.table`}
              value={entry.table}
              placeholder="minecraft:chests/…"
              list="loot-game-tables"
              onChange={(value) =>
                editEntry((draft) => draft.type === 'vanilla' && (draft.table = value.trim()))
              }
            />
            <Datalist
              id="loot-game-tables"
              values={gameData?.registries?.[LOOT_TABLE_REGISTRY] ?? []}
            />
          </>
        )}
        {count && (
          <>
            <NumberField
              label="Count (min)"
              dataPath={`${here}.count`}
              value={count.min}
              step={1}
              min={0}
              onChange={(value) =>
                editEntry(
                  (draft) =>
                    draft.type === 'item' &&
                    setKey(
                      draft,
                      'count',
                      rangeOf(
                        Math.round(value ?? 1),
                        Math.max(count.max, Math.round(value ?? 1)),
                        1,
                      ),
                    ),
                )
              }
            />
            <NumberField
              label="Count (max)"
              value={count.max}
              step={1}
              min={0}
              onChange={(value) =>
                editEntry(
                  (draft) =>
                    draft.type === 'item' &&
                    setKey(
                      draft,
                      'count',
                      rangeOf(
                        Math.min(count.min, Math.round(value ?? 1)),
                        Math.round(value ?? 1),
                        1,
                      ),
                    ),
                )
              }
            />
          </>
        )}
        <NumberField
          label="Weight"
          dataPath={`${here}.weight`}
          value={entry.weight}
          placeholder="1"
          step={1}
          min={1}
          onChange={(value) =>
            editEntry((draft) =>
              setKey(draft, 'weight', value === undefined ? undefined : Math.round(value)),
            )
          }
        />
        <NumberField
          label="Quality"
          dataPath={`${here}.quality`}
          value={entry.quality}
          placeholder="0"
          step={1}
          onChange={(value) =>
            editEntry((draft) =>
              setKey(
                draft,
                'quality',
                value === undefined || value === 0 ? undefined : Math.round(value),
              ),
            )
          }
        />
        <FieldProblems
          problems={except(problemsUnder(problems, here), `${here}.item`, `${here}.conditions`)}
        />
      </Section>
      {entry.type === 'item' && (
        <Section title="Item" icon="gem">
          <div data-path={`${here}.item`}>
            <ItemEditor
              item={entry.item}
              onEdit={(recipe) =>
                editEntry((draft) => draft.type === 'item' && recipe(draft.item as ItemDef))
              }
              dataPath={`${here}.item`}
              glyphs={glyphs}
              projectItems={projectItems}
              look
            />
          </div>
          <FieldProblems problems={problemsUnder(problems, `${here}.item`)} />
        </Section>
      )}
      <ConditionsSection
        title="Entry conditions"
        where={{ pool, entry: index }}
        conditions={entry.conditions}
        edit={edit}
        problems={problems}
      />
    </>
  )
}

/** A pool's or an entry's conditions: each with its fields, "unless", and remove; then add. */
function ConditionsSection({
  title,
  where,
  conditions,
  edit,
  problems,
}: {
  title: string
  where: ConditionsAt
  conditions: LootCondition[] | undefined
  edit: Edit
  problems: Problem[]
}) {
  const [adding, setAdding] = useState<ConditionType>('chance')
  const [picking, setPicking] = useState<number | null>(null)
  const gameData = useWorkspace((s) => s.gameData)
  const list = conditions ?? []
  const editAt = (index: number, recipe: (draft: LootCondition) => void) =>
    edit((draft) => {
      const target = conditionsAt(draft, where)?.[index]
      if (target) recipe(target)
    })
  const base = (index: number) => at(where.pool, where.entry, 'conditions', index)
  return (
    <Section title={title}>
      {list.length === 0 && <Muted>None: always.</Muted>}
      {list.map((condition, index) => (
        <div key={index} className={styles.condition} data-path={base(index)}>
          <div className={styles.conditionHead}>
            <span>{conditionLabel(condition)}</span>
            <IconButton
              icon="trash"
              label={`Remove condition ${index + 1}`}
              onClick={() => edit((draft) => removeCondition(draft, where, index))}
            />
          </div>
          {condition.type === 'chance' && (
            <NumberField
              label="Chance (0 to 1)"
              dataPath={`${base(index)}.chance`}
              value={condition.chance}
              step={0.05}
              min={0}
              onChange={(value) =>
                editAt(index, (draft) => draft.type === 'chance' && (draft.chance = value ?? 0))
              }
            />
          )}
          {condition.type === 'tool' && (
            <Button
              size="small"
              data-path={`${base(index)}.tool`}
              onClick={() => setPicking(index)}
            >
              Tool:{' '}
              {typeof condition.tool === 'string'
                ? condition.tool || 'choose…'
                : `project item ${condition.tool.item}`}
            </Button>
          )}
          {condition.type === 'enchantment' && (
            <>
              <TextField
                label="Enchantment"
                dataPath={`${base(index)}.enchantment`}
                value={condition.enchantment}
                placeholder="minecraft:…"
                list="loot-enchantments"
                onChange={(value) =>
                  editAt(
                    index,
                    (draft) => draft.type === 'enchantment' && (draft.enchantment = value.trim()),
                  )
                }
              />
              <Datalist
                id="loot-enchantments"
                values={gameData?.registries?.[ENCHANTMENT_REGISTRY] ?? []}
              />
              <NumberField
                label="Lowest level"
                dataPath={`${base(index)}.level`}
                value={condition.level}
                placeholder="1"
                step={1}
                min={1}
                onChange={(value) =>
                  editAt(
                    index,
                    (draft) =>
                      draft.type === 'enchantment' &&
                      setKey(draft, 'level', value === undefined ? undefined : Math.round(value)),
                  )
                }
              />
            </>
          )}
          <CheckField
            label="Unless (turn it around)"
            dataPath={`${base(index)}.invert`}
            value={condition.invert ?? false}
            onChange={(value) =>
              editAt(index, (draft) => setKey(draft, 'invert', value || undefined))
            }
          />
          <FieldProblems problems={problemsUnder(problems, base(index))} />
        </div>
      ))}
      <div className={styles.addCondition}>
        <SelectField
          label="New condition"
          value={adding}
          options={CONDITION_TYPES.map((it) => ({ value: it.type, label: it.label }))}
          onChange={setAdding}
        />
        <Button
          size="small"
          icon="plus"
          onClick={() => edit((draft) => addCondition(draft, where, newCondition(adding)))}
        >
          Add condition
        </Button>
      </div>
      {picking !== null && list[picking]?.type === 'tool' && (
        <IngredientPicker
          title="Tool"
          current={(list[picking] as { tool: string | { item: string } }).tool || null}
          onPick={(tool) =>
            editAt(picking, (draft) => draft.type === 'tool' && (draft.tool = tool ?? ''))
          }
          onClose={() => setPicking(null)}
        />
      )}
    </Section>
  )
}

/** Problems right under a place's fields. */
function FieldProblems({ problems }: { problems: Problem[] }) {
  if (problems.length === 0) return null
  return (
    <div className={styles.fieldProblems} role="alert">
      {problems.map((problem, index) => (
        <div key={index} className={styles[problem.severity]}>
          {problem.message}
        </div>
      ))}
    </div>
  )
}

/** [problems] but those under any of [places], which show elsewhere (an entry's on its card, an item's under its form). */
const except = (problems: Problem[], ...places: string[]) =>
  problems.filter((problem) =>
    places.every((place) => problemsUnder([problem], place).length === 0),
  )
