/**
 * A recipe (`recipes/<id>.json`): the station it's made at, drawn in the
 * game's style with its ingredient slots and the result (each showing its
 * item's tooltip on hover), and in the inspector the type, the recipe book
 * fields, cooking numbers and the result item.
 *
 * Clicking an ingredient slot opens the picker (Minecraft item, tag or
 * project item); right-click empties it. A shaped recipe is edited as its
 * grid: `pattern` and `key` are derived from the squares (`ops.ts`), and
 * squares can be dragged onto each other. Problems show where they are: on
 * the slot (outlined, and listed under the station) or under the field.
 */
import { setKey } from '@/core/draft'
import { useMemo, useRef, useState } from 'react'
import {
  RECIPE_GRID,
  RECIPE_STATION_RANGE,
  RECIPE_TYPES,
  RecipeTypeValues,
  type ItemDef,
  type Problem,
  type RecipeCategory,
  type RecipeFile,
  type RecipeType,
} from '@/core/format'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { useItemTooltip } from '@/minecraft/item/ItemTooltip'
import { useProjectItemIds } from '@/minecraft/item/projectItems'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { useApp, useWorkspace } from '@/state/providers'
import { useView, useViewState } from '@/editors/views'
import { NumberField, NumberInput, Section, SelectField, TextField } from '@/ui/fields'
import { fieldPath, focusField } from '@/editors/shared/focus'
import { Button } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { IngredientPicker } from './IngredientPicker'
import {
  cellsOf,
  ingredientLabel,
  ORIGIN,
  type GridOffset,
  ingredientStack,
  setCell,
  setMaterial,
  setMaterialCount,
  setRecipeType,
  setStations,
  setShapelessIngredient,
  setSlotIngredient,
  swapCells,
  symbolsOf,
  type Ingredient,
  type SlotField,
} from './ops'
import { problemField, problemsUnder } from './problems'
import styles from './RecipeEditor.module.css'

const TYPE_LABELS: Record<RecipeType, string> = {
  shaped: 'Crafting (shaped)',
  shapeless: 'Crafting (shapeless)',
  furnace: 'Furnace',
  blasting: 'Blast furnace',
  smoking: 'Smoker',
  campfire_cooking: 'Campfire',
  smithing_transform: 'Smithing',
  stonecutting: 'Stonecutter',
  station: 'Station (scripts make it)',
}

/** GUI pixels to screen pixels, as the menu editor draws windows. */
const SCALE = 3
const SLOT = 18 * SCALE

/** Where the picker writes: a grid square, a shapeless ingredient, or a one-ingredient field. */
type Target =
  | { kind: 'cell'; index: number }
  | { kind: 'shapeless'; index: number }
  | { kind: 'material'; index: number }
  | { kind: 'slot'; field: SlotField }

export function RecipeEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<RecipeFile>(path)
  const allProblems = useWorkspace((s) => s.problems)
  const problems = useMemo(() => allProblems.filter((it) => it.file === path), [allProblems, path])
  // The grid offset is view state, so it survives tab switches and renames.
  const { offset } = useViewState('recipe', path)
  const view = useView('recipe', path)
  const [picking, setPicking] = useState<{ target: Target; title: string } | null>(null)
  const tooltip = useItemTooltip()

  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const id = path.slice(path.indexOf('/') + 1).replace(/\.json$/, '')
  /** An edit to the grid: the pattern is shown where its squares are afterwards. */
  const editGrid = (change: (draft: RecipeFile) => GridOffset) => {
    let next = offset
    edit((draft) => {
      next = change(draft)
    })
    view.update({ offset: next })
  }
  const write = (target: Target, ingredient: Ingredient | null) => {
    if (target.kind === 'cell') {
      editGrid((draft) => setCell(draft, target.index, ingredient, offset))
      return
    }
    edit((draft) => {
      if (target.kind === 'shapeless') setShapelessIngredient(draft, target.index, ingredient)
      else if (target.kind === 'material') setMaterial(draft, target.index, ingredient)
      else setSlotIngredient(draft, target.field, ingredient)
    })
  }
  /** A new type lays its ingredients out afresh, from the grid's first square. */
  const changeType = (type: RecipeType) => {
    edit((draft) => setRecipeType(draft, type))
    view.update({ offset: ORIGIN })
  }
  const current = (target: Target): Ingredient | null => {
    if (target.kind === 'cell') return cellsOf(model, offset)[target.index] ?? null
    if (target.kind === 'shapeless') return model.ingredients?.[target.index] ?? null
    if (target.kind === 'material') return model.materials?.[target.index]?.ingredient ?? null
    return model[target.field] ?? null
  }
  const slots: SlotActions = {
    tooltip,
    open: (target, title) => setPicking({ target, title }),
    clear: (target) => write(target, null),
    swap: (from, to) => editGrid((draft) => swapCells(draft, from, to, offset)),
    problems,
    offset,
  }

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <label className={styles.typeField}>
          Type
          <select
            aria-label="Recipe type"
            data-path="type"
            value={model.type}
            onChange={(event) => changeType(event.target.value as RecipeType)}
          >
            {RecipeTypeValues.map((type) => (
              <option key={type} value={type}>
                {TYPE_LABELS[type]}
              </option>
            ))}
          </select>
        </label>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <Station model={model} slots={slots} edit={edit} />
        <RecipeProblems problems={problems} />
      </Stage>
      <InspectorPanel>
        <RecipeInspector model={model} edit={edit} problems={problems} onType={changeType} />
      </InspectorPanel>
      {picking && (
        <IngredientPicker
          title={picking.title}
          current={current(picking.target)}
          onPick={(ingredient) => write(picking.target, ingredient)}
          onClose={() => setPicking(null)}
        />
      )}
    </EditorScreen>
  )
}

interface SlotActions {
  /** Hover tooltips for the station's items, one for the whole station. */
  tooltip: ReturnType<typeof useItemTooltip>
  open: (target: Target, title: string) => void
  clear: (target: Target) => void
  swap: (from: number, to: number) => void
  problems: Problem[]
  /** Where the shaped grid shows its pattern. */
  offset: GridOffset
}

/** The station's window: its slots laid out like the game's, an arrow, and the result. */
function Station({
  model,
  slots,
  edit,
}: {
  model: RecipeFile
  slots: SlotActions
  edit: (recipe: (draft: RecipeFile) => void) => void
}) {
  const type = model.type
  let inputs: React.ReactNode
  let caption: string
  if (type === 'shaped') {
    inputs = <ShapedGrid model={model} slots={slots} />
    caption = 'Place ingredients in the squares; the pattern and key follow.'
  } else if (type === 'shapeless') {
    inputs = <ShapelessGrid model={model} slots={slots} />
    caption = 'Any order, any squares.'
  } else if (type === 'smithing_transform') {
    inputs = (
      <div className={styles.row}>
        {(['template', 'base', 'addition'] as const).map((field) => (
          <SingleSlot key={field} model={model} field={field} slots={slots} />
        ))}
      </div>
    )
    caption = "The base's components carry over to the result."
  } else if (type === 'station') {
    inputs = <Materials model={model} slots={slots} edit={edit} />
    caption =
      "Scripts make it (nf.recipes.craft) from what's in the crafter's inventory, with its stations near. Click the last slot to add a material; right-click removes one."
  } else if (type === 'stonecutting') {
    inputs = <SingleSlot model={model} field="ingredient" slots={slots} label="Input" />
    caption = 'One input, cut into the result.'
  } else {
    inputs = (
      <div className={styles.column}>
        <SingleSlot model={model} field="ingredient" slots={slots} label="Input" />
        <span className={styles.fire} aria-hidden="true">
          <Icon name="sparkles" size={SLOT * 0.6} />
        </span>
      </div>
    )
    caption = `${TYPE_LABELS[type]}: cooks the input into the result.`
  }
  return (
    <div className={styles.wrap}>
      <div className={styles.station} role="group" aria-label={`${TYPE_LABELS[type]} station`}>
        {inputs}
        <span className={styles.arrow} aria-hidden="true">
          <Icon name="arrow" size={SLOT * 0.8} />
        </span>
        <ResultSlot model={model} slots={slots} />
      </div>
      <Muted className={styles.caption}>{caption}</Muted>
      {slots.tooltip.tooltip}
    </div>
  )
}

/** A station recipe's materials: a slot each (click picks, right-click removes) with how many, and one to add. */
function Materials({
  model,
  slots,
  edit,
}: {
  model: RecipeFile
  slots: SlotActions
  edit: (recipe: (draft: RecipeFile) => void) => void
}) {
  const list = model.materials ?? []
  return (
    <ul className={styles.materials} aria-label="Materials" data-path="materials">
      {[...list, null].map((material, index) => {
        const at = fieldPath(['materials', index])
        const label = material ? `Material ${index + 1}` : 'New material'
        return (
          <li key={index} className={styles.material}>
            <IngredientSlot
              label={label}
              ingredient={material?.ingredient ?? null}
              tooltip={slots.tooltip}
              dataPath={material ? `${at}.ingredient` : undefined}
              problems={material ? problemsUnder(slots.problems, at) : []}
              onOpen={() => slots.open({ kind: 'material', index }, label)}
              onClear={() => slots.clear({ kind: 'material', index })}
            />
            {material && (
              <span className={styles.materialCount}>
                ×
                <NumberInput
                  label={`Material ${index + 1} count`}
                  dataPath={`${at}.count`}
                  value={material.count}
                  placeholder="1"
                  step={1}
                  min={1}
                  onChange={(value) => edit((draft) => setMaterialCount(draft, index, value))}
                />
              </span>
            )}
          </li>
        )
      })}
    </ul>
  )
}

function ShapedGrid({ model, slots }: { model: RecipeFile; slots: SlotActions }) {
  const cells = cellsOf(model, slots.offset)
  const symbols = symbolsOf(model, slots.offset)
  const dragging = useRef<number | null>(null)
  const [over, setOver] = useState<number | null>(null)
  const patternProblems = problemsUnder(slots.problems, 'pattern')
  return (
    <div
      className={cx(styles.grid, patternProblems.length > 0 && styles.gridError)}
      data-path="pattern"
      title={patternProblems.map((it) => it.message).join('\n') || undefined}
      style={{ gridTemplateColumns: `repeat(${RECIPE_GRID.size}, ${SLOT}px)` }}
    >
      {cells.map((cell, index) => {
        const symbol = symbols[index]
        const at = symbol ? fieldPath(['key', symbol]) : undefined
        return (
          <IngredientSlot
            key={index}
            label={`Square ${index + 1}`}
            ingredient={cell}
            tooltip={slots.tooltip}
            dataPath={at}
            problems={at ? problemsUnder(slots.problems, at) : []}
            over={over === index}
            onOpen={() => slots.open({ kind: 'cell', index }, `Square ${index + 1}`)}
            onClear={() => slots.clear({ kind: 'cell', index })}
            drag={{
              draggable: cell !== null,
              onDragStart: (event) => {
                dragging.current = index
                slots.tooltip.hide()
                event.dataTransfer.effectAllowed = 'move'
                event.dataTransfer.setData('text/plain', String(index))
              },
              onDragEnd: () => {
                dragging.current = null
                setOver(null)
              },
              onDragOver: (event) => {
                if (dragging.current === null) return
                event.preventDefault()
                setOver(index)
              },
              onDragLeave: () => setOver((it) => (it === index ? null : it)),
              onDrop: (event) => {
                event.preventDefault()
                const from = dragging.current
                dragging.current = null
                setOver(null)
                if (from !== null && from !== index) slots.swap(from, index)
              },
            }}
          />
        )
      })}
    </div>
  )
}

function ShapelessGrid({ model, slots }: { model: RecipeFile; slots: SlotActions }) {
  const list = model.ingredients ?? []
  return (
    <div
      className={styles.grid}
      data-path="ingredients"
      style={{ gridTemplateColumns: `repeat(${RECIPE_GRID.size}, ${SLOT}px)` }}
    >
      {Array.from({ length: RECIPE_GRID.mostIngredients }, (_, index) => {
        const at = fieldPath(['ingredients', index])
        return (
          <IngredientSlot
            key={index}
            label={`Ingredient ${index + 1}`}
            ingredient={list[index] ?? null}
            tooltip={slots.tooltip}
            dataPath={at}
            problems={index < list.length ? problemsUnder(slots.problems, at) : []}
            disabled={index > list.length}
            onOpen={() => slots.open({ kind: 'shapeless', index }, `Ingredient ${index + 1}`)}
            onClear={() => slots.clear({ kind: 'shapeless', index })}
          />
        )
      })}
    </div>
  )
}

const SLOT_LABELS: Record<SlotField, string> = {
  ingredient: 'Input',
  template: 'Template',
  base: 'Base',
  addition: 'Addition',
}

function SingleSlot({
  model,
  field,
  slots,
  label = SLOT_LABELS[field],
}: {
  model: RecipeFile
  field: SlotField
  slots: SlotActions
  label?: string
}) {
  return (
    <div className={styles.slotBox}>
      <IngredientSlot
        label={label}
        tooltip={slots.tooltip}
        ingredient={model[field] ?? null}
        dataPath={field}
        problems={problemsUnder(slots.problems, field)}
        onOpen={() => slots.open({ kind: 'slot', field }, label)}
        onClear={() => slots.clear({ kind: 'slot', field })}
      />
      <span className={styles.slotLabel}>{label}</span>
    </div>
  )
}

type DragHandlers = Pick<
  React.ButtonHTMLAttributes<HTMLButtonElement>,
  'draggable' | 'onDragStart' | 'onDragEnd' | 'onDragOver' | 'onDragLeave' | 'onDrop'
>

function IngredientSlot({
  label,
  ingredient,
  tooltip,
  dataPath,
  problems,
  onOpen,
  onClear,
  disabled = false,
  over = false,
  drag,
}: {
  label: string
  ingredient: Ingredient | null
  tooltip: SlotActions['tooltip']
  dataPath?: string
  problems: Problem[]
  onOpen: () => void
  onClear: () => void
  disabled?: boolean
  over?: boolean
  drag?: DragHandlers
}) {
  const stack = ingredient === null ? null : ingredientStack(ingredient)
  const [hovered, setHovered] = useState(false)
  const name = ingredient === null ? 'empty' : ingredientLabel(ingredient)
  const hover = tooltip.handlers(stack ?? undefined)
  return (
    <button
      type="button"
      className={cx(
        styles.slot,
        hovered && styles.hovered,
        problems.some((it) => it.severity === 'error') && styles.slotError,
        over && styles.over,
      )}
      style={{ width: SLOT, height: SLOT }}
      aria-label={`${label}: ${name}`}
      aria-invalid={problems.some((it) => it.severity === 'error')}
      title={problems.map((it) => it.message).join('\n') || undefined}
      data-path={dataPath}
      disabled={disabled}
      onClick={onOpen}
      onContextMenu={(event) => {
        event.preventDefault()
        if (ingredient !== null) onClear()
      }}
      {...drag}
      onPointerEnter={(event) => {
        setHovered(true)
        hover.onPointerEnter(event)
      }}
      onPointerMove={(event) => {
        setHovered(true)
        hover.onPointerMove(event)
      }}
      onPointerLeave={() => {
        setHovered(false)
        hover.onPointerLeave()
      }}
      onDragStart={(event) => {
        setHovered(false)
        drag?.onDragStart?.(event)
      }}
      onDragEnd={(event) => {
        setHovered(false)
        drag?.onDragEnd?.(event)
      }}
      onDrop={(event) => {
        setHovered(false)
        drag?.onDrop?.(event)
      }}
    >
      {stack ? (
        <ItemIcon item={stack} size={16 * SCALE} />
      ) : ingredient !== null ? (
        <span className={styles.tag}>{name}</span>
      ) : null}
    </button>
  )
}

function ResultSlot({ model, slots }: { model: RecipeFile; slots: SlotActions }) {
  const problems = problemsUnder(slots.problems, 'result')
  const [hovered, setHovered] = useState(false)
  const hover = slots.tooltip.handlers(model.result)
  return (
    <div className={styles.slotBox}>
      <button
        type="button"
        className={cx(
          styles.slot,
          hovered && styles.hovered,
          problems.some((it) => it.severity === 'error') && styles.slotError,
        )}
        style={{ width: 26 * SCALE, height: 26 * SCALE }}
        aria-label={`Result: ${model.result?.item ?? model.result?.kind ?? 'empty'}`}
        aria-invalid={problems.some((it) => it.severity === 'error')}
        title={problems.map((it) => it.message).join('\n') || undefined}
        onClick={() => focusField([document.querySelector('[data-inspector]')], 'result')}
        onPointerEnter={(event) => {
          setHovered(true)
          hover.onPointerEnter(event)
        }}
        onPointerMove={(event) => {
          setHovered(true)
          hover.onPointerMove(event)
        }}
        onPointerLeave={() => {
          setHovered(false)
          hover.onPointerLeave()
        }}
      >
        {model.result && <ItemIcon item={model.result} size={16 * SCALE} />}
      </button>
      <span className={styles.slotLabel}>Result</span>
    </div>
  )
}

/** Every problem in the file, each a link to where it is. */
function RecipeProblems({ problems }: { problems: Problem[] }) {
  const { workspace } = useApp()
  if (problems.length === 0) return null
  return (
    <ul className={styles.problems} aria-label="Recipe problems">
      {problems.map((problem, index) => (
        <li key={index}>
          <Icon
            name={problem.severity === 'error' ? 'error' : 'warning'}
            className={styles[problem.severity]}
          />
          <Button
            variant="link"
            onClick={() =>
              void workspace.getState().openFile(problem.file, {
                jsonPath: problem.path,
                line: problem.line,
              })
            }
          >
            {problemField(problem) || 'file'}
          </Button>
          <span>{problem.message}</span>
        </li>
      ))}
    </ul>
  )
}

/** A field's problems, right under it. */
function FieldProblems({ problems, field }: { problems: Problem[]; field: string }) {
  const here = problemsUnder(problems, field)
  if (here.length === 0) return null
  return (
    <div className={styles.fieldProblems} role="alert">
      {here.map((problem, index) => (
        <div key={index} className={styles[problem.severity]}>
          {problem.message}
        </div>
      ))}
    </div>
  )
}

function RecipeInspector({
  model,
  edit,
  problems,
  onType,
}: {
  model: RecipeFile
  edit: (recipe: (draft: RecipeFile) => void) => void
  problems: Problem[]
  onType: (type: RecipeType) => void
}) {
  const glyphs = useGlyphMap()
  const projectItems = useProjectItemIds()
  const takes = RECIPE_TYPES[model.type]
  const cooking = takes.optional.includes('cookingTime')
  return (
    <>
      <Section title="Recipe" icon="recipe">
        <SelectField
          label="Type"
          dataPath="type"
          value={model.type}
          options={RecipeTypeValues.map((it) => ({ value: it, label: TYPE_LABELS[it] }))}
          onChange={onType}
        />
        <FieldProblems problems={problems} field="type" />
        {takes.group && (
          <>
            <TextField
              label="Group"
              dataPath="group"
              value={model.group}
              placeholder="(its own entry)"
              onChange={(value) => edit((draft) => setKey(draft, 'group', value.trim()))}
            />
            <FieldProblems problems={problems} field="group" />
          </>
        )}
        {(takes.categories.length > 0 || model.category !== undefined) && (
          <>
            <SelectField
              label="Category"
              dataPath="category"
              value={model.category ?? ''}
              options={[
                { value: '', label: '(misc)' },
                ...takes.categories.map((it) => ({ value: it, label: it })),
                ...(model.category && !takes.categories.includes(model.category)
                  ? [{ value: model.category, label: `${model.category} (not for this type)` }]
                  : []),
              ]}
              onChange={(value) =>
                edit((draft) =>
                  setKey(draft, 'category', (value || undefined) as RecipeCategory | undefined),
                )
              }
            />
            <FieldProblems problems={problems} field="category" />
          </>
        )}
        {cooking && (
          <>
            <NumberField
              label="Cooking time"
              dataPath="cookingTime"
              value={model.cookingTime}
              placeholder={`${takes.cookingTime} ticks`}
              step={10}
              min={1}
              onChange={(value) =>
                edit((draft) =>
                  setKey(draft, 'cookingTime', value === undefined ? undefined : Math.round(value)),
                )
              }
            />
            <FieldProblems problems={problems} field="cookingTime" />
            <NumberField
              label="Experience"
              dataPath="experience"
              value={model.experience}
              placeholder="0"
              step={0.1}
              min={0}
              onChange={(value) => edit((draft) => setKey(draft, 'experience', value))}
            />
            <FieldProblems problems={problems} field="experience" />
          </>
        )}
        {model.type === 'station' && (
          <>
            <TextField
              label="Stations near"
              dataPath="stations"
              value={(model.stations ?? []).join(', ')}
              placeholder="none (minecraft:crafting_table, #tag, work_bench, a | b)"
              onChange={(text) => edit((draft) => setStations(draft, text))}
            />
            <FieldProblems problems={problems} field="stations" />
            <NumberField
              label="Within (blocks)"
              dataPath="stationRange"
              value={model.stationRange}
              placeholder={String(RECIPE_STATION_RANGE.default)}
              step={1}
              min={1}
              onChange={(value) =>
                edit((draft) =>
                  setKey(
                    draft,
                    'stationRange',
                    value === undefined
                      ? undefined
                      : Math.min(RECIPE_STATION_RANGE.max, Math.round(value)),
                  ),
                )
              }
            />
            <FieldProblems problems={problems} field="stationRange" />
          </>
        )}
        {model.type === 'stonecutting' && (
          <NumberField
            label="Result count"
            dataPath="result.count"
            value={model.result?.count}
            placeholder="1"
            step={1}
            min={1}
            onChange={(value) =>
              edit((draft) => {
                if (value === undefined) delete draft.result.count
                else draft.result.count = Math.round(value)
              })
            }
          />
        )}
      </Section>
      <Section title="Result" icon="arrowRight">
        <div data-path="result">
          <ItemEditor
            item={model.result ?? {}}
            onEdit={(recipe) =>
              edit((draft) => {
                draft.result ??= {}
                recipe(draft.result as ItemDef)
              })
            }
            dataPath="result"
            glyphs={glyphs}
            projectItems={projectItems}
          />
        </div>
        <FieldProblems problems={problems} field="result" />
      </Section>
    </>
  )
}
