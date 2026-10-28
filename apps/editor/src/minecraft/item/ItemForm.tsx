/**
 * The form for one `ItemDef`, shared by menu slots and dialog item
 * bodies. Each control commits one whole value (one undo step) through
 * [onEdit], which hands it a draft of the item; `dataPath`s are the item's
 * JSON path plus the key, so a problem at `$.slots["13"].item.count` focuses
 * the count field.
 */
import { setKey } from '@/core/draft'
import { useId, useState } from 'react'
import {
  AttributeOperationValues,
  AttributeSlotValues,
  EquipSlotValues,
  ITEM_STACK_FIELDS,
  ItemRarityValues,
  type AttributeOperation,
  type AttributeSlot,
  type EquipSlot,
  type ItemDef,
  type ItemRarity,
  type TooltipComponent,
  UseAnimationValues,
} from '@/core/format'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { useResourcePackOutlines } from '@/state/useResourcePacks'
import type { GlyphMap } from '@/minecraft/text/MiniText'
import {
  CheckField,
  Datalist,
  JsonObjectField,
  NumberField,
  NumberInput,
  Row,
  SelectField,
  TextField,
  TriStateField,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import {
  addModifier,
  blocksFromText,
  blocksToText,
  freeEnchantmentId,
  loreFromText,
  loreToText,
  resourcePackRefs,
  removeEnchantment,
  removeModifier,
  setCooldown,
  setEnchantment,
  setEquipment,
  setFood,
  setHold,
  holdAnimation,
  setModifier,
  setProjectItem,
  setUseEffects,
} from './item'
import styles from './ItemForm.module.css'
import { TooltipFrame } from './ItemTooltip'
import { tooltipOf } from './tooltip'

/** One empty map, so a selector returns the same value while there's no outline yet. */

export function ItemEditor({
  item,
  onEdit,
  dataPath,
  glyphs,
  look = false,
  projectItems,
}: {
  item: ItemDef
  /** Applies a recipe to a draft of this item, as one edit. */
  onEdit: (recipe: (draft: ItemDef) => void) => void
  /** The item's own path inside the file (`slots["13"].item`), or `''` when it's the whole file. */
  dataPath: string
  glyphs?: GlyphMap
  /**
   * Edits what an item is rather than one stack of it (a project item's
   * file): the stack's own fields (format's `ITEM_STACK_FIELDS`: count,
   * damage) aren't offered.
   */
  look?: boolean
  /** The project's items, to offer "Project item" (`item`); left out where an item can't name one. */
  projectItems?: string[]
}) {
  const { items } = usePickerIds()
  const packs = useResourcePackOutlines()
  const listId = useId()
  const itemModels = resourcePackRefs(packs, 'items')
  const tooltipStyles = resourcePackRefs(packs, 'tooltips')
  const equipmentLooks = resourcePackRefs(packs, 'equipment')
  const at = (key: string) => (dataPath ? `${dataPath}.${key}` : key)
  const set = <K extends keyof ItemDef>(key: K, value: ItemDef[K] | undefined) =>
    onEdit((draft) => setKey(draft, key, value))
  const int = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))
  const offers = (key: keyof ItemDef) =>
    !look || !(ITEM_STACK_FIELDS as readonly string[]).includes(key)

  return (
    <div className={styles.form}>
      {projectItems && (
        <SelectField
          label="Project item"
          dataPath={at('item')}
          value={item.item ?? ''}
          options={[
            { value: '', label: '(none: a Minecraft item)' },
            ...projectItems.map((it) => ({ value: it, label: it })),
            ...(item.item && !projectItems.includes(item.item)
              ? [{ value: item.item, label: `${item.item} (missing)` }]
              : []),
          ]}
          onChange={(value) => onEdit((draft) => setProjectItem(draft, value || undefined))}
        />
      )}
      <TextField
        label="Item"
        dataPath={at('kind')}
        value={item.kind}
        list={items.length ? listId : undefined}
        placeholder={item.item ? "(the project item's)" : 'minecraft:…'}
        onChange={(kind) => onEdit((draft) => setKey(draft, 'kind', kind.trim()))}
      />
      {items.length > 0 && <Datalist id={listId} values={items} />}
      {offers('count') && (
        <NumberField
          label="Count"
          dataPath={at('count')}
          value={item.count}
          placeholder="1"
          step={1}
          min={1}
          onChange={(value) => set('count', int(value))}
        />
      )}
      <TextField
        label="Name"
        dataPath={at('name')}
        value={item.name}
        placeholder="(the item's own)"
        onChange={(name) => set('name', name)}
      />
      <TextField
        label="Lore"
        multiline
        dataPath={at('lore')}
        value={loreToText(item.lore)}
        placeholder="One line per row"
        onChange={(text) => set('lore', loreFromText(text))}
      />
      {(item.name || item.lore) && (
        <Row label="">
          <TooltipFrame lines={tooltipOf({ ...item, hideTooltip: false }) ?? []} glyphs={glyphs} />
        </Row>
      )}
      <Enchantments item={item} onEdit={onEdit} dataPath={at('enchantments')} />
      <TriStateField
        label="Glint"
        dataPath={at('glint')}
        value={item.glint}
        defaultLabel="when enchanted"
        onChange={(value) => set('glint', value)}
      />
      <CheckField
        label="Hide tooltip"
        dataPath={at('hideTooltip')}
        value={item.hideTooltip ?? false}
        onChange={(value) => set('hideTooltip', value || undefined)}
      />
      <CheckField
        label="Unbreakable"
        dataPath={at('unbreakable')}
        value={item.unbreakable ?? false}
        onChange={(value) => set('unbreakable', value || undefined)}
      />
      {offers('damage') && (
        <NumberField
          label="Damage"
          dataPath={at('damage')}
          value={item.damage}
          placeholder="0"
          step={1}
          min={0}
          onChange={(value) => set('damage', int(value))}
        />
      )}
      <SelectField
        label="Item model"
        dataPath={at('itemModel')}
        value={item.itemModel ?? ''}
        options={refOptions(itemModels, item.itemModel, "(the item's own)")}
        onChange={(value) => set('itemModel', value)}
      />
      <SelectField
        label="Tooltip style"
        dataPath={at('tooltipStyle')}
        value={item.tooltipStyle ?? ''}
        options={refOptions(tooltipStyles, item.tooltipStyle, '(vanilla)')}
        onChange={(value) => set('tooltipStyle', value)}
      />
      <SelectField
        label="Equipment look"
        dataPath={at('equipment.asset')}
        value={item.equipment?.asset ?? ''}
        options={refOptions(equipmentLooks, item.equipment?.asset, '(not worn)')}
        onChange={(value) => onEdit((draft) => setEquipment(draft, 'asset', value || undefined))}
      />
      {item.equipment && (
        <SelectField
          label="Worn in"
          dataPath={at('equipment.slot')}
          value={item.equipment.slot}
          options={EquipSlotValues.map((it) => ({ value: it, label: it }))}
          onChange={(value) => onEdit((draft) => setEquipment(draft, 'slot', value as EquipSlot))}
        />
      )}
      <ColorField value={item.color} dataPath={at('color')} onChange={(v) => set('color', v)} />
      <TextField
        label="Profile"
        dataPath={at('profile')}
        value={item.profile}
        placeholder="Player name or UUID (heads)"
        onChange={(value) => set('profile', value.trim())}
      />
      <NumberField
        label="Max stack"
        dataPath={at('maxStackSize')}
        value={item.maxStackSize}
        placeholder="(the item's own)"
        step={1}
        min={1}
        onChange={(value) => set('maxStackSize', int(value))}
      />
      <SelectField
        label="Rarity"
        dataPath={at('rarity')}
        value={item.rarity ?? ''}
        options={[
          { value: '', label: "(the item's own)" },
          ...ItemRarityValues.map((it) => ({ value: it, label: it })),
        ]}
        onChange={(value) => set('rarity', (value || undefined) as ItemRarity | undefined)}
      />
      <Modifiers item={item} onEdit={onEdit} dataPath={at('attributeModifiers')} />
      <TextField
        label="Can break"
        multiline
        dataPath={at('canBreak')}
        value={blocksToText(item.canBreak)}
        placeholder="One per row: ruby_ore, minecraft:stone, #minecraft:logs, * (adventure mode)"
        onChange={(text) => set('canBreak', blocksFromText(text))}
      />
      <TextField
        label="Can place on"
        multiline
        dataPath={at('canPlaceOn')}
        value={blocksToText(item.canPlaceOn)}
        placeholder="One per row: ruby_ore, minecraft:stone, #minecraft:logs, * (adventure mode)"
        onChange={(text) => set('canPlaceOn', blocksFromText(text))}
      />
      <TextField
        label="Hidden tooltip lines"
        multiline
        dataPath={at('hiddenTooltipComponents')}
        value={blocksToText(item.hiddenTooltipComponents)}
        placeholder="One per row, by component: unbreakable, attribute_modifiers, can_break"
        // Whatever's typed is kept, so the problem names a line that isn't one.
        onChange={(text) =>
          set('hiddenTooltipComponents', blocksFromText(text) as TooltipComponent[] | undefined)
        }
      />
      <NumberField
        label="Nutrition"
        dataPath={at('food.nutrition')}
        value={item.food?.nutrition}
        placeholder="(not food)"
        step={1}
        min={0}
        onChange={(value) => onEdit((draft) => setFood(draft, 'nutrition', int(value)))}
      />
      {item.food && (
        <>
          <NumberField
            label="Saturation"
            dataPath={at('food.saturation')}
            value={item.food.saturation}
            step={0.1}
            min={0}
            onChange={(value) => onEdit((draft) => setFood(draft, 'saturation', value ?? 0))}
          />
          <NumberField
            label="Eat seconds"
            dataPath={at('food.eatSeconds')}
            value={item.food.eatSeconds}
            placeholder="1.6"
            step={0.1}
            min={0}
            onChange={(value) => onEdit((draft) => setFood(draft, 'eatSeconds', value))}
          />
          <CheckField
            label="Always edible"
            dataPath={at('food.canAlwaysEat')}
            value={item.food.canAlwaysEat ?? false}
            onChange={(value) => onEdit((draft) => setFood(draft, 'canAlwaysEat', value))}
          />
        </>
      )}
      <NumberField
        label="Cooldown"
        dataPath={at('cooldown.seconds')}
        value={item.cooldown?.seconds}
        placeholder="(none) seconds"
        step={0.5}
        min={0}
        onChange={(value) => onEdit((draft) => setCooldown(draft, 'seconds', value))}
      />
      {item.cooldown && (
        <TextField
          label="Cooldown group"
          dataPath={at('cooldown.group')}
          value={item.cooldown.group}
          placeholder="(the item's kind)"
          onChange={(value) => onEdit((draft) => setCooldown(draft, 'group', value.trim()))}
        />
      )}
      <CheckField
        label="Hold until let go"
        dataPath={at('hold')}
        value={!!item.hold}
        onChange={(value) => onEdit((draft) => setHold(draft, value))}
      />
      {item.hold && (
        <SelectField
          label="Hold animation"
          dataPath={at('hold.animation')}
          value={holdAnimation(item)}
          options={UseAnimationValues}
          onChange={(value) => onEdit((draft) => setHold(draft, true, value))}
        />
      )}
      <NumberField
        label="Speed while used"
        dataPath={at('useEffects.speedMultiplier')}
        value={item.useEffects?.speedMultiplier}
        placeholder={item.hold ? '1' : '0.2'}
        step={0.1}
        min={0}
        onChange={(value) => onEdit((draft) => setUseEffects(draft, 'speedMultiplier', value))}
      />
      <TriStateField
        label="Sprint while used"
        dataPath={at('useEffects.canSprint')}
        value={item.useEffects?.canSprint}
        defaultLabel={item.hold ? 'on' : 'off'}
        onChange={(value) => onEdit((draft) => setUseEffects(draft, 'canSprint', value))}
      />
      <TriStateField
        label="Sculk hears use"
        dataPath={at('useEffects.interactVibrations')}
        value={item.useEffects?.interactVibrations}
        defaultLabel={item.hold ? 'off' : 'on'}
        onChange={(value) => onEdit((draft) => setUseEffects(draft, 'interactVibrations', value))}
      />
      {offers('data') && (
        <JsonObjectField
          label="Script data"
          dataPath={at('data')}
          value={item.data}
          onChange={(value) => set('data', value)}
        />
      )}
    </div>
  )
}

/** The attribute modifiers, one row each: attribute, amount, operation, slot, and an optional id. */
function Modifiers({
  item,
  onEdit,
  dataPath,
}: {
  item: ItemDef
  onEdit: (recipe: (draft: ItemDef) => void) => void
  dataPath: string
}) {
  const modifiers = item.attributeModifiers ?? []
  return (
    <Row label="Attributes">
      <div className={styles.list} data-path={dataPath}>
        {modifiers.map((modifier, index) => (
          <div className={styles.modifier} key={index} data-path={`${dataPath}[${index}]`}>
            <input
              aria-label={`Modifier ${index + 1} attribute`}
              defaultValue={modifier.attribute}
              key={`${index}:${modifier.attribute}`}
              placeholder="minecraft:attack_damage"
              onBlur={(event) => {
                const next = event.target.value.trim()
                if (next && next !== modifier.attribute)
                  onEdit((draft) => setModifier(draft, index, 'attribute', next))
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') (event.target as HTMLInputElement).blur()
              }}
            />
            <NumberInput
              label={`Modifier ${index + 1} amount`}
              value={modifier.amount}
              step={0.1}
              onChange={(value) =>
                value !== undefined && onEdit((draft) => setModifier(draft, index, 'amount', value))
              }
            />
            <select
              aria-label={`Modifier ${index + 1} operation`}
              value={modifier.operation}
              onChange={(event) =>
                onEdit((draft) =>
                  setModifier(draft, index, 'operation', event.target.value as AttributeOperation),
                )
              }
            >
              {AttributeOperationValues.map((it) => (
                <option key={it} value={it}>
                  {it}
                </option>
              ))}
            </select>
            <select
              aria-label={`Modifier ${index + 1} slot`}
              value={modifier.slot ?? ''}
              onChange={(event) =>
                onEdit((draft) =>
                  setModifier(
                    draft,
                    index,
                    'slot',
                    (event.target.value || undefined) as AttributeSlot | undefined,
                  ),
                )
              }
            >
              <option value="">any</option>
              {AttributeSlotValues.filter((it) => it !== 'any').map((it) => (
                <option key={it} value={it}>
                  {it}
                </option>
              ))}
            </select>
            <IconButton
              icon="close"
              size={10}
              label={`Remove modifier ${index + 1}`}
              onClick={() => onEdit((draft) => removeModifier(draft, index))}
            />
          </div>
        ))}
        <Button size="small" icon="plus" onClick={() => onEdit((draft) => addModifier(draft))}>
          Add modifier
        </Button>
      </div>
    </Row>
  )
}

/** Picker options for `<pack>/<key>` references, keeping a value that names nothing visible. */
function refOptions(refs: string[], current: string | undefined, none: string) {
  return [
    { value: '', label: none },
    ...refs.map((it) => ({ value: it, label: it })),
    ...(current && !refs.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
}

function Enchantments({
  item,
  onEdit,
  dataPath,
}: {
  item: ItemDef
  onEdit: (recipe: (draft: ItemDef) => void) => void
  dataPath: string
}) {
  const entries = Object.entries(item.enchantments ?? {})
  return (
    <Row label="Enchantments">
      <div className={styles.list} data-path={dataPath}>
        {entries.map(([id, level]) => (
          <div className={styles.enchantment} key={id}>
            <input
              aria-label={`Enchantment ${id}`}
              defaultValue={id}
              key={id}
              onBlur={(event) => {
                const next = event.target.value.trim()
                if (next && next !== id) onEdit((draft) => setEnchantment(draft, next, level, id))
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') (event.target as HTMLInputElement).blur()
              }}
            />
            <NumberInput
              label={`${id} level`}
              value={level}
              step={1}
              min={1}
              onChange={(value) =>
                value !== undefined &&
                onEdit((draft) => setEnchantment(draft, id, Math.round(value)))
              }
            />
            <IconButton
              icon="close"
              size={10}
              label={`Remove ${id}`}
              onClick={() => onEdit((draft) => removeEnchantment(draft, id))}
            />
          </div>
        ))}
        <Button
          size="small"
          icon="plus"
          onClick={() => onEdit((draft) => setEnchantment(draft, freeEnchantmentId(draft), 1))}
        >
          Add enchantment
        </Button>
      </div>
    </Row>
  )
}

function ColorField({
  value,
  dataPath,
  onChange,
}: {
  value: string | undefined
  dataPath: string
  onChange: (value: string | undefined) => void
}) {
  const id = useId()
  // The picker reports every move; the value commits when it closes.
  const [picking, setPicking] = useState<string | null>(null)
  const valid = value && /^#[0-9a-f]{6}$/i.test(value) ? value : '#ffffff'
  return (
    <Row label="Color" htmlFor={id}>
      <div className={styles.colorField} data-path={dataPath}>
        <input
          type="color"
          aria-label="Pick color"
          value={picking ?? valid}
          onChange={(event) => setPicking(event.target.value)}
          onBlur={() => {
            if (picking !== null && picking !== value) onChange(picking)
            setPicking(null)
          }}
        />
        <input
          id={id}
          aria-label="Color"
          defaultValue={value ?? ''}
          key={value ?? ''}
          placeholder="#RRGGBB (dyes, potions)"
          onBlur={(event) => {
            const next = event.target.value.trim()
            if (next !== (value ?? '')) onChange(next || undefined)
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') (event.target as HTMLInputElement).blur()
          }}
        />
      </div>
    </Row>
  )
}
