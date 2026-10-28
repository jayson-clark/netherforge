/**
 * Slot maths and pure edits for `menu.json` (docs/format/menu.md).
 * Slots are keyed by index as a string, `"0"` top left, left to right then
 * down; the canonical writer sorts them numerically.
 */
import type { MenuFile, MenuType, SlotDef } from '@/core/format'
import { plainText } from '@/minecraft/text/minimessage'
import { shapeOf, type WindowShape } from '@/minecraft/window/window'

export const slotAt = (shape: WindowShape, column: number, row: number) =>
  row * shape.columns + column

export const positionOf = (shape: WindowShape, index: number) => ({
  column: index % shape.columns,
  row: Math.floor(index / shape.columns),
})

/** Slot indices that have something but fall outside [shape] (after a type or rows change). */
export function slotsOutside(file: MenuFile, shape: WindowShape = shapeOf(file)): number[] {
  return Object.keys(file.slots ?? {})
    .map(Number)
    .filter((index) => !Number.isInteger(index) || index < 0 || index >= shape.size)
    .sort((a, b) => a - b)
}

export function slotOf(file: MenuFile, index: number): SlotDef | undefined {
  return file.slots?.[String(index)]
}

/** Replaces one slot; an empty slot (no item) is removed. */
export function setSlot(file: MenuFile, index: number, slot: SlotDef | undefined): void {
  const slots = { ...(file.slots ?? {}) }
  if (!slot || slot.item === undefined) delete slots[String(index)]
  else slots[String(index)] = slot
  if (Object.keys(slots).length === 0) delete file.slots
  else file.slots = slots
}

/**
 * Drags [from] onto [to]: the two squares swap whole, so dropping onto an
 * empty square moves and onto a full one
 * trades places. One edit, so one undo step.
 */
export function moveSlot(file: MenuFile, from: number, to: number): void {
  if (from === to) return
  const a = slotOf(file, from)
  const b = slotOf(file, to)
  setSlot(file, to, a)
  setSlot(file, from, b)
}

/**
 * Changes the type (and rows, for a chest), keeping slots and dropping a
 * `rows` that no longer applies. Slots past the new size stay (format flags
 * them) so nothing is lost by trying a smaller window.
 */
export function setShape(file: MenuFile, type: MenuType, rows?: number): void {
  if (type === 'chest') delete file.type
  else file.type = type
  if (type === 'chest' && rows !== undefined) file.rows = rows
  else if (type !== 'chest') delete file.rows
}

/** The slots that hold something, by index, in order. */
export function filledSlots(file: MenuFile): number[] {
  return Object.keys(file.slots ?? {})
    .map(Number)
    .filter((index) => Number.isInteger(index))
    .sort((a, b) => a - b)
}

/** A slot as a list names it: `13 · Ruby`, by its item's name, else its project item, else its kind. */
export function slotLabel(index: number, slot: SlotDef | undefined): string {
  const item = slot?.item
  if (!item) return `${index} · empty`
  const name = item.name ? plainText(item.name).trim() : ''
  return `${index} · ${name || item.item || item.kind || '?'}`
}
