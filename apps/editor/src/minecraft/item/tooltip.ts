/**
 * What an item's tooltip says, line by line, the way the game lays it out:
 * the name (a custom one in italics; otherwise the item's own, in its
 * rarity's colour), its enchantments in grey, "Unbreakable", then its lore
 * in purple italics. A stack that hides its tooltip has none, and a line
 * `hiddenTooltipComponents` hides isn't there.
 */
import type { ItemDef, ItemRarity } from '@/core/format'
import type { TextStyle } from '@/minecraft/text/minimessage'
import { itemLabel } from './item'

export interface TooltipLine {
  /** MiniMessage. */
  text: string
  style: TextStyle
}

const RARITY_COLOR: Record<ItemRarity, string> = {
  common: '#ffffff',
  uncommon: '#ffff55',
  rare: '#55ffff',
  epic: '#ff55ff',
}

const GRAY = '#aaaaaa'
const BLUE = '#5555ff'
const LORE = '#aa00aa'

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X']

/** `minecraft:fire_aspect` → `Fire Aspect`. */
export function titleCase(id: string): string {
  return itemLabel(id).replace(/\b\w/g, (c) => c.toUpperCase())
}

/** An enchantment as the game writes it: `Sharpness V`, a level past X as a number. */
export function enchantmentLine(id: string, level: number): string {
  return `${titleCase(id)} ${ROMAN[level] ?? level}`
}

/** The lines of [item]'s tooltip (its look: a project item's stack through `stackLook`), or null when it hides it. */
export function tooltipOf(item: ItemDef): TooltipLine[] | null {
  if (item.hideTooltip) return null
  const hidden = new Set<string>(item.hiddenTooltipComponents ?? [])
  const color = RARITY_COLOR[item.rarity ?? 'common']
  const lines: TooltipLine[] = [
    item.name
      ? { text: item.name, style: { color, italic: true } }
      : { text: titleCase(item.kind || item.item || '?'), style: { color } },
  ]
  if (!hidden.has('enchantments')) {
    for (const [id, level] of Object.entries(item.enchantments ?? {})) {
      lines.push({ text: enchantmentLine(id, level), style: { color: GRAY } })
    }
  }
  if (item.unbreakable && !hidden.has('unbreakable')) {
    lines.push({ text: 'Unbreakable', style: { color: BLUE } })
  }
  if (!hidden.has('lore')) {
    for (const line of item.lore ?? [])
      lines.push({ text: line, style: { color: LORE, italic: true } })
  }
  return lines
}
