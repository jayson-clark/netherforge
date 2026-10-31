/** A display node's fields: what it shows (a block with its properties, an item, text) and how. */
import { setKey } from '@/core/draft'
import {
  AnchorValues,
  BillboardValues,
  ItemTransformValues,
  TEXT_DEFAULTS,
  TextAlignmentValues,
  type DisplayDef,
  type NodeDef,
} from '@/core/format'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { parseBlockState } from '@netherforge/models/model'
import { useWorkspace } from '@/state/providers'
import { useResourcePackOutlines } from '@/state/useResourcePacks'
import { Datalist, NumberField, Section, SelectField, TextField, TriStateField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import { resourcePackRefs } from '@/minecraft/item/item'
import type { EditNode } from './editNode'

export function DisplaySection({ node, editNode }: { node: NodeDef; editNode: EditNode }) {
  const gameData = useWorkspace((s) => s.gameData)
  const display = node.display
  const type = display?.type ?? 'none'
  const { blocks, items } = usePickerIds()
  const packs = useResourcePackOutlines()
  const looks = resourcePackRefs(packs, 'items')

  const switchType = (next: string) =>
    editNode((target) => {
      if (next === 'none') delete target.display
      else if (next === 'block') target.display = { type: 'block', block: 'minecraft:stone' }
      else if (next === 'item') target.display = { type: 'item', item: 'minecraft:stick' }
      else if (next === 'model') target.display = { type: 'model', model: looks[0] ?? '' }
      else if (next === 'rect') target.display = { type: 'rect', color: '#FFFFFF' }
      else if (next === 'image') target.display = { type: 'image', width: 64, height: 36 }
      else target.display = { type: 'text', text: 'Text' }
    })

  const editDisplay = <T extends DisplayDef>(recipe: (d: T) => void) =>
    editNode((target) => {
      if (target.display) recipe(target.display as T)
    })

  return (
    <Section title="Display" icon="eye">
      <SelectField
        label="Type"
        dataPath="display"
        value={type}
        options={['none', 'block', 'item', 'model', 'text', 'rect', 'image'] as const}
        onChange={switchType}
      />
      {display?.type === 'block' && (
        <>
          <TextField
            label="Block"
            dataPath="display.block"
            value={display.block}
            list={blocks.length ? 'nf-blocks' : undefined}
            placeholder="minecraft:stone"
            onChange={(block) => editDisplay<typeof display>((d) => (d.block = block))}
          />
          {blocks.length > 0 && <Datalist id="nf-blocks" values={blocks} />}
          <BlockProperties
            state={display.block}
            properties={gameData?.blocks?.[parseBlockState(display.block).id]?.properties}
            defaults={gameData?.blocks?.[parseBlockState(display.block).id]?.defaults}
            onChange={(block) => editDisplay<typeof display>((d) => (d.block = block))}
          />
          {!gameData && (
            <Hint>
              Block names aren't checked until a dev server has exported this version's game data.
            </Hint>
          )}
        </>
      )}
      {display?.type === 'item' && (
        <>
          <TextField
            label="Item"
            dataPath="display.item"
            value={display.item}
            list={items.length ? 'nf-items' : undefined}
            onChange={(item) => editDisplay<typeof display>((d) => (d.item = item))}
          />
          {items.length > 0 && <Datalist id="nf-items" values={items} />}
          <SelectField
            label="Item transform"
            dataPath="display.itemTransform"
            value={display.itemTransform ?? ''}
            options={[
              { value: '', label: 'Default (none)' },
              ...ItemTransformValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'itemTransform', value || undefined))
            }
          />
        </>
      )}
      {display?.type === 'model' && (
        <>
          <SelectField
            label="Look"
            dataPath="display.model"
            value={display.model}
            options={[
              ...looks,
              ...(looks.includes(display.model)
                ? []
                : [{ value: display.model, label: `${display.model || '(none)'} (missing)` }]),
            ]}
            onChange={(model) => editDisplay<typeof display>((d) => (d.model = model))}
          />
          <SelectField
            label="Item transform"
            dataPath="display.itemTransform"
            value={display.itemTransform ?? ''}
            options={[
              { value: '', label: 'Default (none: as modelled)' },
              ...ItemTransformValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'itemTransform', value || undefined))
            }
          />
          <Hint>
            An entry of a resource pack&apos;s items: a model file, a block look or a picture. A
            script swaps it with node:set_model.
          </Hint>
        </>
      )}
      {display?.type === 'text' && (
        <>
          <TextField
            label="Text"
            multiline
            dataPath="display.text"
            value={display.text}
            onChange={(text) => editDisplay<typeof display>((d) => (d.text = text))}
          />
          <SelectField
            label="Billboard"
            dataPath="display.billboard"
            value={display.billboard ?? ''}
            options={[
              { value: '', label: 'Default (center)' },
              ...BillboardValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'billboard', value || undefined))
            }
          />
          <SelectField
            label="Alignment"
            dataPath="display.alignment"
            value={display.alignment ?? ''}
            options={[
              { value: '', label: 'Default (center)' },
              ...TextAlignmentValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'alignment', value || undefined))
            }
          />
          <TextField
            label="Background"
            dataPath="display.background"
            value={display.background}
            placeholder="#40000000"
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'background', value || undefined))
            }
          />
          <NumberField
            label="Line width"
            dataPath="display.lineWidth"
            value={display.lineWidth}
            placeholder={String(TEXT_DEFAULTS.lineWidth)}
            step={10}
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'lineWidth', value))}
          />
          <TriStateField
            label="See through"
            dataPath="display.seeThrough"
            value={display.seeThrough}
            defaultLabel="off"
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'seeThrough', value))}
          />
          <TriStateField
            label="Shadow"
            dataPath="display.shadow"
            value={display.shadow}
            defaultLabel="off"
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'shadow', value))}
          />
          <SelectField
            label="Anchor"
            dataPath="display.anchor"
            value={display.anchor ?? ''}
            options={[
              { value: '', label: 'None (as the game draws text)' },
              ...AnchorValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) =>
                setKey(d, 'anchor', (value || undefined) as typeof d.anchor),
              )
            }
          />
          {display.anchor && (
            <Hint>
              Placed by that point of its lines, measured with the game&apos;s font
              (fonts/default.json). Lines break only at line breaks.
            </Hint>
          )}
        </>
      )}
      {display?.type === 'rect' && (
        <>
          <TextField
            label="Colour"
            dataPath="display.color"
            value={display.color}
            placeholder="#RRGGBB or #AARRGGBB"
            onChange={(color) => editDisplay<typeof display>((d) => (d.color = color))}
          />
          <SizeFields
            size={display.size ?? [1, 1]}
            onChange={(size) => editDisplay<typeof display>((d) => (d.size = size))}
          />
          <AnchorField
            value={display.anchor}
            onChange={(anchor) => editDisplay<typeof display>((d) => setKey(d, 'anchor', anchor))}
          />
        </>
      )}
      {display?.type === 'image' && (
        <>
          <NumberField
            label="Width (pixels)"
            dataPath="display.width"
            value={display.width}
            step={1}
            onChange={(value) => editDisplay<typeof display>((d) => (d.width = value ?? d.width))}
          />
          <NumberField
            label="Height (pixels)"
            dataPath="display.height"
            value={display.height}
            step={1}
            onChange={(value) => editDisplay<typeof display>((d) => (d.height = value ?? d.height))}
          />
          <SizeFields
            size={display.size ?? [display.width / 16, display.height / 16]}
            onChange={(size) => editDisplay<typeof display>((d) => (d.size = size))}
          />
          <AnchorField
            value={display.anchor}
            onChange={(anchor) => editDisplay<typeof display>((d) => setKey(d, 'anchor', anchor))}
          />
          <Hint>
            A script shows pictures on it with node:set_image. It&apos;s blank until then.
          </Hint>
        </>
      )}
    </Section>
  )
}

/** A rect's or image's size in blocks, before the node's scale. */
function SizeFields({ size, onChange }: { size: number[]; onChange: (size: number[]) => void }) {
  const [width = 1, height = 1] = size
  return (
    <>
      <NumberField
        label="Width (blocks)"
        dataPath="display.size"
        value={width}
        step={0.1}
        onChange={(value) => onChange([value ?? width, height])}
      />
      <NumberField
        label="Height (blocks)"
        dataPath="display.size"
        value={height}
        step={0.1}
        onChange={(value) => onChange([width, value ?? height])}
      />
    </>
  )
}

type AnchorName = (typeof AnchorValues)[number]

/** Which point of a rect or image is at the node's origin. */
function AnchorField({
  value,
  onChange,
}: {
  value: AnchorName | undefined
  onChange: (anchor: AnchorName | undefined) => void
}) {
  return (
    <SelectField
      label="Anchor"
      dataPath="display.anchor"
      value={value ?? ''}
      options={[
        { value: '', label: 'Default (center)' },
        ...AnchorValues.map((it) => ({ value: it, label: it })),
      ]}
      onChange={(next) => onChange((next || undefined) as AnchorName | undefined)}
    />
  )
}

/** A select per block property, when game data says which properties the block has. */
function BlockProperties({
  state,
  properties,
  defaults,
  onChange,
}: {
  state: string
  properties: Record<string, string[]> | undefined
  defaults: Record<string, string> | undefined
  onChange: (state: string) => void
}) {
  if (!properties || Object.keys(properties).length === 0) return null
  const parsed = parseBlockState(state)
  const write = (name: string, value: string) => {
    const next = { ...parsed.properties }
    if (value === '') delete next[name]
    else next[name] = value
    const entries = Object.entries(next)
    onChange(
      entries.length
        ? `${parsed.id}[${entries.map(([k, v]) => `${k}=${v}`).join(',')}]`
        : parsed.id,
    )
  }
  return (
    <>
      {Object.entries(properties).map(([name, values]) => (
        <SelectField
          key={name}
          label={name}
          dataPath="display.block"
          value={parsed.properties[name] ?? ''}
          options={[
            { value: '', label: `Default (${defaults?.[name] ?? values[0] ?? ''})` },
            ...values.map((value) => ({ value, label: value })),
          ]}
          onChange={(value) => write(name, value)}
        />
      ))}
    </>
  )
}
