/**
 * Pure edits to `dialog.json` (docs/format/dialog.md), and how its buttons
 * are arranged on screen. Body, inputs and buttons are ordered lists: their
 * order is what the player sees.
 */
import {
  DIALOG_BUTTON_LIMITS,
  DIALOG_COLUMNS,
  type DialogBody,
  type DialogButton,
  type DialogFile,
  type DialogInput,
  type DialogType,
} from '@/core/format'
import { plainText } from '@/minecraft/text/minimessage'

export type ListName = 'body' | 'inputs' | 'buttons'

/** Pixels, the game's defaults. */
export const DEFAULT_MESSAGE_WIDTH = 200
export const DEFAULT_INPUT_WIDTH = 200
export const DEFAULT_BUTTON_WIDTH = 150

/** The first `base`, `base_2`, `base_3`… not in [taken]. */
export function freeKey(taken: Iterable<string>, base: string): string {
  const used = new Set(taken)
  if (!used.has(base)) return base
  for (let n = 2; ; n += 1) if (!used.has(`${base}_${n}`)) return `${base}_${n}`
}

function listOf<T>(file: DialogFile, list: ListName): T[] {
  return ((file[list] as T[] | undefined) ?? []) as T[]
}

/** Writes a list back, leaving the key out when it's empty (the default). */
function setList(file: DialogFile, list: ListName, items: unknown[]) {
  if (items.length === 0) delete file[list]
  else (file as Record<ListName, unknown[]>)[list] = items
}

/** Moves one entry of a list; out-of-range moves do nothing. */
export function moveEntry(file: DialogFile, list: ListName, from: number, to: number): void {
  const items = [...listOf<unknown>(file, list)]
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return
  const [moved] = items.splice(from, 1)
  items.splice(to, 0, moved)
  setList(file, list, items)
}

export function removeEntry(file: DialogFile, list: ListName, index: number): void {
  const items = [...listOf<unknown>(file, list)]
  items.splice(index, 1)
  setList(file, list, items)
}

/** Appends a body part and returns its index. */
export function addBody(file: DialogFile, part: DialogBody): number {
  const items = [...listOf<DialogBody>(file, 'body'), part]
  setList(file, 'body', items)
  return items.length - 1
}

/** A new input of [type] with a free key; returns its index. */
export function addInput(file: DialogFile, type: DialogInput['type']): number {
  const inputs = listOf<DialogInput>(file, 'inputs')
  const key = freeKey(
    inputs.map((it) => it.key),
    type === 'single_option' ? 'choice' : type === 'number_range' ? 'amount' : type,
  )
  let input: DialogInput
  switch (type) {
    case 'text':
      input = { type, key, label: 'Text' }
      break
    case 'boolean':
      input = { type, key, label: 'Yes?' }
      break
    case 'single_option':
      input = { type, key, label: 'Choose', options: [{ id: 'a' }, { id: 'b' }] }
      break
    case 'number_range':
      input = { type, key, label: 'Amount', start: 0, end: 10 }
      break
  }
  setList(file, 'inputs', [...inputs, input])
  return inputs.length
}

/** A new button with a free key; returns its index. */
export function addButton(file: DialogFile, base = 'button'): number {
  const buttons = listOf<DialogButton>(file, 'buttons')
  const key = freeKey(
    buttons.map((it) => it.key),
    base,
  )
  setList(file, 'buttons', [...buttons, { key, label: key.replace(/_/g, ' ') }])
  return buttons.length
}

/**
 * Changes the type, dropping what no longer applies: `columns` is only for
 * `multi_action`, `dialogs` only for `dialog_list`.
 */
export function setType(file: DialogFile, type: DialogType): void {
  if (type === 'notice') delete file.type
  else file.type = type
  if (type !== 'multi_action') delete file.columns
  if (type !== 'dialog_list') delete file.dialogs
}

/** Whether another button fits the type. */
export function canAddButton(file: DialogFile): boolean {
  const limit = DIALOG_BUTTON_LIMITS[file.type ?? 'notice']
  return limit === null || (file.buttons?.length ?? 0) < limit
}

export interface ButtonCell {
  label: string
  width: number
  /** Index in `buttons`, or null for one the game makes (a listed dialog). */
  index: number | null
}

export interface ButtonArrangement {
  /** Rows of buttons in the body, laid out in columns. */
  grid: ButtonCell[][]
  /** Buttons along the bottom. */
  footer: ButtonCell[]
}

const chunk = <T>(items: T[], size: number): T[][] => {
  const rows: T[][] = []
  for (let i = 0; i < items.length; i += size) rows.push(items.slice(i, i + size))
  return rows
}

/**
 * Where each button goes. A notice's button and a confirmation's yes and no
 * sit along the bottom; a multi_action's sit in `columns`; a dialog_list
 * shows a button per listed dialog (titled by each one's `externalTitle`,
 * else `title`) in columns, with its own button, the exit, at the bottom.
 */
export function arrangeButtons(
  file: DialogFile,
  listed: Record<string, Pick<DialogFile, 'title' | 'externalTitle'> | undefined> = {},
): ButtonArrangement {
  const type = file.type ?? 'notice'
  const columns = Math.min(
    DIALOG_COLUMNS.max,
    Math.max(DIALOG_COLUMNS.min, file.columns ?? DIALOG_COLUMNS.default),
  )
  const cells = (file.buttons ?? []).map((button, index): ButtonCell => ({
    label: button.label ?? button.key,
    width: button.width ?? DEFAULT_BUTTON_WIDTH,
    index,
  }))
  switch (type) {
    case 'notice':
    case 'confirmation':
      return { grid: [], footer: cells }
    case 'multi_action':
      return { grid: chunk(cells, columns), footer: [] }
    case 'dialog_list': {
      const entries = (file.dialogs ?? []).map((id): ButtonCell => ({
        label: listed[id]?.externalTitle ?? listed[id]?.title ?? id,
        width: DEFAULT_BUTTON_WIDTH,
        index: null,
      }))
      return { grid: chunk(entries, columns), footer: cells }
    }
  }
}

/** The lists in screen order, with the names the outline gives them. */
export const LISTS: { list: ListName; title: string }[] = [
  { list: 'body', title: 'Body' },
  { list: 'inputs', title: 'Inputs' },
  { list: 'buttons', title: 'Buttons' },
]

/** One entry as a list names it, its text without tags: `Welcome to…`, `name (text)`, `Close`. */
export function entryLabel(list: ListName, entry: DialogBody | DialogInput | DialogButton): string {
  if (list === 'body') {
    const part = entry as DialogBody
    if (part.type === 'item') return `Item: ${part.item.item ?? part.item.kind ?? '?'}`
    const text = plainText(part.text).replace(/\s+/g, ' ').trim()
    return text.length > 32 ? `${text.slice(0, 31)}…` : text || '(empty message)'
  }
  if (list === 'inputs') {
    const input = entry as DialogInput
    return `${input.key} (${input.type.replace('_', ' ')})`
  }
  const button = entry as DialogButton
  return (button.label ? plainText(button.label).trim() : '') || button.key
}
