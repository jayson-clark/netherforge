/**
 * Pure edits to an `ItemDef` (docs/format/menu.md#items), shared by
 * menu slots and dialog item bodies. An absent key means "leave it
 * alone", so a cleared field deletes its key rather than writing a default.
 */
import type {
  AttributeModifierDef,
  CooldownDef,
  EquipmentDef,
  FoodDef,
  ItemDef,
  ItemFile,
  ResourcePackOutline,
  RefKind,
  UseAnimation,
  UseEffectsDef,
} from '@/core/format'
import { resolveReference } from '@/core/format'

/**
 * Makes the stack one of project item [id] (undefined: none). Its kind is
 * then the definition's, so a kind written here goes: one that disagreed
 * would be an error, and one that agrees says nothing.
 */
export function setProjectItem(item: ItemDef, id: string | undefined): void {
  if (id === undefined) {
    delete item.item
    return
  }
  item.item = id
  delete item.kind
}

/** Lore as the textarea shows it: one line per entry. */
export const loreToText = (lore: string[] | undefined) => (lore ?? []).join('\n')

/** The textarea's text as lore; trailing blank lines are dropped, none at all is no lore. */
export function loreFromText(text: string): string[] | undefined {
  const lines = text.split('\n')
  while (lines.length > 0 && lines[lines.length - 1]!.trim() === '') lines.pop()
  return lines.length > 0 ? lines : undefined
}

/** Sets or renames one enchantment, keeping the others' order. */
export function setEnchantment(
  item: ItemDef,
  id: string,
  level: number,
  previousId: string = id,
): void {
  const next: Record<string, number> = {}
  let placed = false
  for (const [key, value] of Object.entries(item.enchantments ?? {})) {
    if (key === previousId) {
      next[id] = level
      placed = true
    } else if (key !== id) {
      next[key] = value
    }
  }
  if (!placed) next[id] = level
  item.enchantments = next
}

export function removeEnchantment(item: ItemDef, id: string): void {
  if (!item.enchantments) return
  delete item.enchantments[id]
  if (Object.keys(item.enchantments).length === 0) delete item.enchantments
}

/** An enchantment id not yet on the item, for "Add enchantment". */
export function freeEnchantmentId(item: ItemDef, base = 'enchantment'): string {
  const taken = new Set(Object.keys(item.enchantments ?? {}))
  if (!taken.has(base)) return base
  for (let n = 2; ; n += 1) if (!taken.has(`${base}_${n}`)) return `${base}_${n}`
}

/** Whether the stack shimmers: `glint` wins; otherwise any enchantment does. */
export function hasGlint(item: ItemDef): boolean {
  if (item.glint !== undefined) return item.glint
  return Object.keys(item.enchantments ?? {}).length > 0
}

export type ResourcePackEntryKind = keyof ResourcePackOutline

/** Every `<pack>/<key>` of [kind] in the project, as a file refers to it, for the pickers. */
export function resourcePackRefs(
  packs: Record<string, ResourcePackOutline>,
  kind: ResourcePackEntryKind,
): string[] {
  return Object.entries(packs)
    .flatMap(([pack, outline]) => outline[kind].map((key) => `${pack}/${key}`))
    .sort()
}

/**
 * A reference of [kind] to a pack entry, written in a project or package whose
 * namespace is [namespace], → the pack as the packs store keys it and the
 * entry's key, by format's rule: `ui/ruby` (or `shop:ui/ruby` in `shop`) →
 * `['ui', 'ruby']`, and a package's `library:gems/gem` → `['library:gems', 'gem']`.
 * [home] is the project's namespace (the packs store's), [namespace] by default.
 * Null when it isn't shaped like one.
 */
export function splitResourcePackRef(
  kind: RefKind,
  value: string | undefined,
  namespace: string,
  home: string = namespace,
): [string, string] | null {
  if (!value) return null
  const key = resolveReference(kind, value, namespace)
  if (!key) return null
  const colon = key.indexOf(':')
  const owner = key.slice(0, colon)
  const path = key.slice(colon + 1)
  const slash = path.indexOf('/')
  const pack = path.slice(0, slash)
  return [owner === home ? pack : `${owner}:${pack}`, path.slice(slash + 1)]
}

/**
 * What a stack of a project item looks like: the definition fills in every
 * field the stack leaves out, and the kind is always the definition's (the
 * rule format's `ProjectItems.resolve` follows on the server). A stack that
 * names no project item, or one that doesn't exist, is shown as it is.
 */
export function stackLook(stack: ItemDef, definition: ItemFile | null): ItemDef {
  if (!stack.item || !definition) return stack
  // Everything but the file's own keys (its script and schema) is an item field.
  const look = Object.fromEntries(
    Object.entries(definition).filter(([key]) => key !== 'script' && key !== '$schema'),
  )
  const own = Object.fromEntries(Object.entries(stack).filter(([, value]) => value !== undefined))
  return { ...look, ...own, kind: definition.kind }
}

/** `minecraft:diamond_sword` → `diamond sword`, for placeholder labels. */
export function itemLabel(id: string): string {
  return (id.includes(':') ? id.slice(id.indexOf(':') + 1) : id).replace(/_/g, ' ')
}

/** Block kinds as the textarea shows them: one per line. */
export const blocksToText = (blocks: string[] | undefined) => (blocks ?? []).join('\n')

/** The textarea's text as block kinds; blank lines are dropped, none at all is no list. */
export function blocksFromText(text: string): string[] | undefined {
  const blocks = text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  return blocks.length > 0 ? blocks : undefined
}

/** A new modifier for "Add modifier": adds nothing until it's filled in. */
export function addModifier(item: ItemDef): void {
  const modifier: AttributeModifierDef = {
    attribute: 'minecraft:attack_damage',
    amount: 0,
    operation: 'add_value',
  }
  item.attributeModifiers = [...(item.attributeModifiers ?? []), modifier]
}

/** Sets one field of modifier [index]; an optional field (`id`, `slot`) cleared is deleted. */
export function setModifier<K extends keyof AttributeModifierDef>(
  item: ItemDef,
  index: number,
  key: K,
  value: AttributeModifierDef[K] | undefined,
): void {
  const modifier = item.attributeModifiers?.[index]
  if (!modifier) return
  if (value === undefined || value === '') {
    if (key === 'id' || key === 'slot') delete modifier[key]
  } else {
    modifier[key] = value
  }
}

/** Removes modifier [index]; none left is no list. */
export function removeModifier(item: ItemDef, index: number): void {
  if (!item.attributeModifiers) return
  item.attributeModifiers.splice(index, 1)
  if (item.attributeModifiers.length === 0) delete item.attributeModifiers
}

/**
 * Sets one field of `food`. Nutrition is what makes an item food: setting it
 * starts the table (saturation 0), clearing it removes food altogether.
 * Other fields need food there already; an optional one cleared is deleted.
 */
export function setFood<K extends keyof FoodDef>(
  item: ItemDef,
  key: K,
  value: FoodDef[K] | undefined,
): void {
  if (key === 'nutrition' && value === undefined) {
    delete item.food
    return
  }
  if (!item.food) {
    if (key !== 'nutrition') return
    item.food = { nutrition: 0, saturation: 0 }
  }
  if (value === undefined || value === false) {
    if (key === 'canAlwaysEat' || key === 'eatSeconds') delete item.food[key]
  } else {
    item.food[key] = value
  }
}

/** Sets one field of `cooldown`: seconds starts and (cleared) removes it; the group needs a cooldown. */
export function setCooldown<K extends keyof CooldownDef>(
  item: ItemDef,
  key: K,
  value: CooldownDef[K] | undefined,
): void {
  if (key === 'seconds' && value === undefined) {
    delete item.cooldown
    return
  }
  if (!item.cooldown) {
    if (key !== 'seconds') return
    item.cooldown = { seconds: 1 }
  }
  if (value === undefined || value === '') delete item.cooldown.group
  else item.cooldown[key] = value
}

/** The pose a hold item holds the player in: `none` for a plain `true` (or no hold). */
export function holdAnimation(item: ItemDef): UseAnimation {
  return typeof item.hold === 'object' ? (item.hold.animation ?? 'none') : 'none'
}

/**
 * Turns `hold` on or off. On keeps the pose it had; `none` is written as `true`,
 * any other pose as `{ animation }`.
 */
export function setHold(
  item: ItemDef,
  on: boolean,
  animation: UseAnimation = holdAnimation(item),
): void {
  if (!on) delete item.hold
  else item.hold = animation === 'none' ? true : { animation }
}

/**
 * Sets one field of `useEffects`: undefined leaves it to the default (the
 * game's, or a hold's), and the table goes once nothing's left in it.
 */
export function setUseEffects<K extends keyof UseEffectsDef>(
  item: ItemDef,
  key: K,
  value: UseEffectsDef[K] | undefined,
): void {
  const effects: UseEffectsDef = { ...item.useEffects }
  if (value === undefined) delete effects[key]
  else effects[key] = value
  if (Object.keys(effects).length === 0) delete item.useEffects
  else item.useEffects = effects
}

/**
 * Sets one field of `equipment`: the asset starts it (worn in the head slot
 * until the slot says otherwise) and, cleared, removes it; the slot needs an asset.
 */
export function setEquipment<K extends keyof EquipmentDef>(
  item: ItemDef,
  key: K,
  value: EquipmentDef[K] | undefined,
): void {
  if (key === 'asset' && !value) {
    delete item.equipment
    return
  }
  if (!item.equipment) {
    if (key !== 'asset') return
    item.equipment = { asset: value as string, slot: 'head' }
    return
  }
  if (value !== undefined) item.equipment[key] = value
}
