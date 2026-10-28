/**
 * The menu editor: the window as a player sees it (skin, title, the
 * squares with their items, each with its tooltip on hover), and in the
 * inspector the window and the picked slot. Click a square to pick it; drag
 * one onto another to swap them (one undo step). Problems about a slot pick
 * it and focus the field.
 */
import { setKey } from '@/core/draft'
import { useRef, useState } from 'react'
import {
  newScript,
  newSiblingFile,
  MENU_ROWS,
  MenuTypeValues,
  type MenuFile,
  type MenuType,
  type ItemDef,
  type SlotDef,
} from '@/core/format'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { resourcePackRefs } from '@/minecraft/item/item'
import { useProjectItemIds } from '@/minecraft/item/projectItems'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { dirname } from '@/core/paths'
import { useWorkspace } from '@/state/providers'
import { useResourcePackOutlines } from '@/state/useResourcePacks'
import { usePrimary, useView } from '@/editors/views'
import { MiniText, TextPreview } from '@/minecraft/text/MiniText'
import { useItemTooltip } from '@/minecraft/item/ItemTooltip'
import { ask } from '@/ui/dialogs'
import { Button, IconButton } from '@/ui/Button'
import { Callout } from '@/ui/Callout'
import { cx } from '@/ui/cx'
import {
  Check,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
  TriStateField,
} from '@/ui/fields'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { shapeOf } from '@/minecraft/window/window'
import { moveSlot, setShape, setSlot, slotOf, slotsOutside } from './slots'
import type { SlotPosition } from '@/minecraft/window/window'
import { useSkin, WindowFrame } from '@/minecraft/window/WindowFrame'
import styles from './MenuEditor.module.css'

/** One empty map, so a selector returns the same value while there's no outline yet. */

const TYPE_LABELS: Record<MenuType, string> = {
  chest: 'Chest',
  barrel: 'Barrel',
  shulker_box: 'Shulker box',
  hopper: 'Hopper',
  dispenser: 'Dispenser',
  dropper: 'Dropper',
  crafter: 'Crafter',
}

/** The JSON path of a slot, as format writes it: `slots["13"]`. */
const slotPath = (index: number) => `slots["${index}"]`

export function MenuEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<MenuFile>(path)
  const selected = usePrimary('menu', path)
  const view = useView('menu', path)
  const [outline, setOutline] = useState(false)

  useFocusRequests(path, (segments) => {
    if (segments[0] === 'slots' && typeof segments[1] === 'string') {
      view.select(Number(segments[1]))
      return segments
    }
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  return (
    <EditorScreen>
      <EditorBar title={model.name ?? path.split('/')[1]}>
        <Check label="Outline" checked={outline} onChange={setOutline} />
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <MenuWindow
          path={path}
          model={model}
          selected={selected}
          outline={outline}
          onSelect={(index) => (index === null ? view.select() : view.select(index))}
          onMove={(from, to) => {
            edit((draft) => moveSlot(draft, from, to))
            view.select(to)
          }}
        />
        <OutsideSlots
          model={model}
          onSelect={(index) => view.select(index)}
          onRemove={(index) => edit((draft) => setSlot(draft, index, undefined))}
        />
      </Stage>
      <InspectorPanel>
        {selected !== null && (
          <SlotInspector
            index={selected}
            slot={slotOf(model, selected)}
            edit={edit}
            onMove={(to) => {
              edit((draft) => moveSlot(draft, selected, to))
              view.select(to)
            }}
            onClose={() => view.select()}
          />
        )}
        <WindowInspector path={path} model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

function MenuWindow({
  path,
  model,
  selected,
  outline,
  onSelect,
  onMove,
}: {
  path: string
  model: MenuFile
  selected: number | null
  outline: boolean
  onSelect: (index: number | null) => void
  onMove: (from: number, to: number) => void
}) {
  const skin = useSkin(model.skin)
  // The server places a centred title's skin from the words' width, read from fonts/default.json; format says when it can't.
  const measured = useWorkspace(
    (s) => !s.problems.some((problem) => problem.file === path && problem.code === 'font.needed'),
  )
  const dragging = useRef<number | null>(null)
  const [over, setOver] = useState<number | null>(null)
  const [hovered, setHovered] = useState<number | null>(null)
  const tooltip = useItemTooltip()
  const scale = 3

  const renderSlot = ({ index, x, y }: SlotPosition) => {
    const slot = slotOf(model, index)
    const item = slot?.item
    const hover = tooltip.handlers(item)
    return (
      <button
        key={index}
        type="button"
        className={cx(
          styles.slot,
          index === hovered && styles.hovered,
          index === selected && styles.selected,
          index === over && styles.over,
        )}
        // A project item's stack names it (`ruby`) and may leave the kind to it.
        aria-label={`Slot ${index}${item ? `: ${item.kind ?? item.item}` : ', empty'}`}
        aria-pressed={index === selected}
        onPointerEnter={(event) => {
          setHovered(index)
          hover.onPointerEnter(event)
        }}
        onPointerMove={(event) => {
          setHovered(index)
          hover.onPointerMove(event)
        }}
        onPointerLeave={() => {
          setHovered((it) => (it === index ? null : it))
          hover.onPointerLeave()
        }}
        style={{
          left: (x - 1) * scale,
          top: (y - 1) * scale,
          width: 18 * scale,
          height: 18 * scale,
        }}
        draggable={slot !== undefined}
        onClick={() => onSelect(index === selected ? null : index)}
        onDragStart={(event) => {
          dragging.current = index
          // No pointer events reach the page during a drag: forget the hover now.
          setHovered(null)
          tooltip.hide()
          event.dataTransfer.effectAllowed = 'move'
          event.dataTransfer.setData('text/plain', String(index))
        }}
        onDragEnd={() => {
          dragging.current = null
          setOver(null)
          setHovered(null)
        }}
        onDragOver={(event) => {
          if (dragging.current === null) return
          // Without this the drop never fires.
          event.preventDefault()
          setOver(index)
        }}
        onDragLeave={() => setOver((it) => (it === index ? null : it))}
        onDrop={(event) => {
          event.preventDefault()
          const from = dragging.current ?? Number(event.dataTransfer.getData('text/plain'))
          dragging.current = null
          setOver(null)
          setHovered(null)
          if (Number.isInteger(from) && from !== index) onMove(from, index)
        }}
      >
        {item && <ItemIcon item={item} size={16 * scale} />}
      </button>
    )
  }

  return (
    <>
      <WindowFrame
        type={model.type}
        rows={model.rows}
        title={model.title}
        skin={skin}
        measured={measured}
        scale={scale}
        outline={outline}
        renderSlot={renderSlot}
        label="Menu window"
      />
      {tooltip.tooltip}
    </>
  )
}

/** Slots past the window's size (after shrinking it): kept, and listed so they can go. */
function OutsideSlots({
  model,
  onSelect,
  onRemove,
}: {
  model: MenuFile
  onSelect: (index: number) => void
  onRemove: (index: number) => void
}) {
  const outside = slotsOutside(model)
  if (outside.length === 0) return null
  return (
    <Callout tone="warning" role="note">
      <strong>
        {outside.length === 1 ? 'A slot is' : `${outside.length} slots are`} outside this window (
        {shapeOf(model).size} slots):
      </strong>
      <ul className={styles.outside}>
        {outside.map((index) => (
          <li key={index}>
            <Button variant="link" onClick={() => onSelect(index)}>
              Slot {index}
            </Button>
            <IconButton
              icon="trash"
              label={`Remove slot ${index}`}
              onClick={() => onRemove(index)}
            />
          </li>
        ))}
      </ul>
    </Callout>
  )
}

function WindowInspector({
  path,
  model,
  edit,
}: {
  path: string
  model: MenuFile
  edit: (recipe: (draft: MenuFile) => void) => void
}) {
  const packs = useResourcePackOutlines()
  const glyphs = useGlyphMap()
  const skins = resourcePackRefs(packs, 'skins')
  const shape = shapeOf(model)
  const set = <K extends keyof MenuFile>(key: K, value: MenuFile[K] | undefined) =>
    edit((draft) => setKey(draft, key, value))

  return (
    <Section title="Window" icon="slot">
      <TextField
        label="Name"
        dataPath="name"
        value={model.name}
        placeholder="(for people)"
        onChange={(value) => set('name', value)}
      />
      <SelectField
        label="Type"
        dataPath="type"
        value={shape.type}
        options={MenuTypeValues.map((it) => ({ value: it, label: TYPE_LABELS[it] }))}
        onChange={(type) => edit((draft) => setShape(draft, type, draft.rows))}
      />
      {shape.type === 'chest' && (
        <SelectField
          label="Rows"
          dataPath="rows"
          value={model.rows === undefined ? '' : String(model.rows)}
          options={[
            { value: '', label: 'Default (3)' },
            ...Array.from({ length: MENU_ROWS.max }, (_, i) => ({
              value: String(i + 1),
              label: String(i + 1),
            })),
          ]}
          onChange={(value) => set('rows', value ? Number(value) : undefined)}
        />
      )}
      <TextField
        label="Title"
        dataPath="title"
        value={model.title}
        placeholder="MiniMessage"
        onChange={(value) => set('title', value)}
      />
      {model.title && (
        <Row label="">
          <TextPreview>
            <MiniText text={model.title} glyphs={glyphs} scale={2} base={{ color: '#404040' }} />
          </TextPreview>
        </Row>
      )}
      <SelectField
        label="Skin"
        dataPath="skin"
        value={model.skin ?? ''}
        options={[
          { value: '', label: '(vanilla)' },
          ...skins.map((it) => ({ value: it, label: it })),
          ...(model.skin && !skins.includes(model.skin)
            ? [{ value: model.skin, label: `${model.skin} (missing)` }]
            : []),
        ]}
        onChange={(value) => set('skin', value)}
      />
      <TriStateField
        label="Shared"
        dataPath="shared"
        value={model.shared}
        defaultLabel="one per player"
        onChange={(value) => set('shared', value)}
      />
      <TriStateField
        label="Locked"
        dataPath="locked"
        value={model.locked}
        defaultLabel="on"
        onChange={(value) => set('locked', value)}
      />
      <ScriptField
        folder={dirname(path)}
        script={model.script}
        dataPath="script"
        suggestedName="script.lua"
        template={newScript('menu', '')}
        siblingTemplate={newSiblingFile('menu')}
        onChange={(script) => set('script', script)}
      />
    </Section>
  )
}

function SlotInspector({
  index,
  slot,
  edit,
  onMove,
  onClose,
}: {
  index: number
  slot: SlotDef | undefined
  edit: (recipe: (draft: MenuFile) => void) => void
  onMove: (to: number) => void
  onClose: () => void
}) {
  const glyphs = useGlyphMap()
  const projectItems = useProjectItemIds()
  const editSlot = (recipe: (draft: SlotDef) => void) =>
    edit((draft) => {
      // The draft's own slot when there is one (an Immer draft: edit it in place).
      const next = slotOf(draft, index) ?? {}
      recipe(next)
      setSlot(draft, index, next)
    })
  const editItem = (recipe: (draft: ItemDef) => void) =>
    editSlot((draft) => {
      if (draft.item) recipe(draft.item)
    })

  const addItem = async () => {
    const id = await ask.prompt({
      title: `Item in slot ${index}`,
      label: 'Item id',
      message: 'The item type, like minecraft:diamond. You can change everything else after.',
      confirmLabel: 'Add',
      validate: (value) =>
        /^([a-z0-9_.-]+:)?[a-z0-9_./-]+$/.test(value.trim())
          ? null
          : 'An item id, like minecraft:diamond',
    })
    if (id) editSlot((draft) => (draft.item = { kind: id.trim() }))
  }

  return (
    <Section
      title={`Slot ${index}`}
      actions={<IconButton icon="close" size={10} label="Deselect slot" onClick={onClose} />}
    >
      {slot?.item ? (
        <>
          <ItemEditor
            item={slot.item}
            onEdit={editItem}
            dataPath={`${slotPath(index)}.item`}
            glyphs={glyphs}
            projectItems={projectItems}
          />
          <Row label="">
            <Button
              size="small"
              icon="trash"
              onClick={() => editSlot((draft) => delete draft.item)}
            >
              Remove item
            </Button>
          </Row>
        </>
      ) : (
        <Row label="Item">
          <Button size="small" icon="plus" onClick={() => void addItem()}>
            Add item
          </Button>
        </Row>
      )}
      {slot && (
        <NumberField
          label="Move to"
          value={undefined}
          placeholder={String(index)}
          step={1}
          min={0}
          onChange={(to) => {
            if (to !== undefined && Math.round(to) !== index) onMove(Math.round(to))
          }}
        />
      )}
    </Section>
  )
}
