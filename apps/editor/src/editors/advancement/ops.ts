/**
 * Pure edits to an advancement (`advancements/<id>.json`), each on an Immer
 * draft. What the game does with them (the datapack, the triggers) is the
 * server's; nothing here knows the game's advancement format.
 */
import type {
  AdvancementCriterion,
  AdvancementDisplay,
  AdvancementFile,
  AdvancementFrame,
} from '@/core/format'
import { setKey } from '@/core/draft'
import { freeName } from '@/editors/loot/ops'

/** The criteria's names, in the order the file has them. */
export const criterionNames = (model: AdvancementFile): string[] =>
  Object.keys(model.criteria ?? {})

/** Adds a criterion no script or trigger meets yet; its name. */
export function addCriterion(draft: AdvancementFile): string {
  draft.criteria ??= {}
  const name = freeName('criterion', Object.keys(draft.criteria))
  draft.criteria[name] = {}
  // A custom list of requirements must name every criterion.
  if (draft.requirements) draft.requirements.push([name])
  return name
}

/** Renames a criterion, in the requirements too. */
export function renameCriterion(draft: AdvancementFile, from: string, to: string) {
  const criteria = draft.criteria
  if (!criteria?.[from] || criteria[to] || from === to) return
  draft.criteria = Object.fromEntries(
    Object.entries(criteria).map(([name, value]) => [name === from ? to : name, value]),
  )
  if (draft.requirements) {
    draft.requirements = draft.requirements.map((group) =>
      group.map((name) => (name === from ? to : name)),
    )
  }
}

/** Removes a criterion and its place in the requirements (a group left empty goes). */
export function removeCriterion(draft: AdvancementFile, name: string) {
  if (!draft.criteria) return
  delete draft.criteria[name]
  if (draft.requirements) {
    const groups = draft.requirements
      .map((group) => group.filter((it) => it !== name))
      .filter((group) => group.length > 0)
    draft.requirements = groups
  }
}

/** A copy of criterion [name] under a free name; that name, or null when there's no such criterion. */
export function duplicateCriterion(draft: AdvancementFile, name: string): string | null {
  const source = draft.criteria?.[name]
  if (!draft.criteria || !source) return null
  const copy = freeName(`${name}_copy`, Object.keys(draft.criteria))
  draft.criteria[copy] = JSON.parse(JSON.stringify(source)) as AdvancementCriterion
  if (draft.requirements) draft.requirements.push([copy])
  return copy
}

/** The groups that complete it: the file's, or every criterion on its own (the default). */
export const groupsOf = (model: AdvancementFile): string[][] =>
  model.requirements ?? criterionNames(model).map((name) => [name])

/** Custom requirements start as the default written out, one group per criterion. */
export function customizeRequirements(draft: AdvancementFile) {
  draft.requirements = groupsOf(draft)
}

/** Back to every criterion needed. */
export function resetRequirements(draft: AdvancementFile) {
  delete draft.requirements
}

export function addGroup(draft: AdvancementFile) {
  draft.requirements ??= []
  draft.requirements.push([])
}

export function removeGroup(draft: AdvancementFile, index: number) {
  draft.requirements?.splice(index, 1)
}

/** A group's criteria from the comma-separated names a field holds. */
export const parseGroup = (text: string): string[] =>
  text
    .split(',')
    .map((it) => it.trim())
    .filter((it) => it.length > 0)

export function setGroup(draft: AdvancementFile, index: number, names: string[]) {
  if (draft.requirements?.[index]) draft.requirements[index] = names
}

/** Turns the display on (with the placeholder it needs) or off. */
export function setDisplayEnabled(draft: AdvancementFile, on: boolean) {
  if (!on) delete draft.display
  else draft.display ??= { icon: { kind: 'minecraft:stone' }, title: '' }
}

/** Sets one of the display's keys; `undefined` or `''` leaves it out. */
export function setDisplay<K extends keyof AdvancementDisplay>(
  draft: AdvancementFile,
  key: K,
  value: AdvancementDisplay[K] | undefined,
) {
  if (draft.display) setKey(draft.display, key, value)
}

/** The icon is a game item or a project item, never both: choosing one drops the other. */
export function setIconKind(draft: AdvancementFile, kind: string | undefined) {
  const icon = draft.display?.icon
  if (!icon) return
  setKey(icon, 'kind', kind)
  if (kind) delete icon.item
}

export function setIconItem(draft: AdvancementFile, item: string | undefined) {
  const icon = draft.display?.icon
  if (!icon) return
  setKey(icon, 'item', item)
  if (item) delete icon.kind
}

export const FRAMES: { value: AdvancementFrame; label: string }[] = [
  { value: 'task', label: 'Task' },
  { value: 'goal', label: 'Goal' },
  { value: 'challenge', label: 'Challenge' },
]

/** A criterion's conditions as the text the field holds: JSON, empty for none. */
export const conditionsText = (conditions: Record<string, unknown> | undefined): string =>
  conditions && Object.keys(conditions).length > 0 ? JSON.stringify(conditions, null, 2) : ''

/** The conditions a field's text says: undefined for none, null when it isn't a JSON object. */
export function parseConditions(text: string): Record<string, unknown> | undefined | null {
  if (text.trim() === '') return undefined
  try {
    const value: unknown = JSON.parse(text)
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/** Sets a criterion's trigger; without one it has no conditions either. */
export function setTrigger(draft: AdvancementFile, name: string, trigger: string | undefined) {
  const criterion = draft.criteria?.[name]
  if (!criterion) return
  setKey(criterion, 'trigger', trigger)
  if (!trigger) delete criterion.conditions
}

export function setConditions(
  draft: AdvancementFile,
  name: string,
  conditions: Record<string, unknown> | undefined,
) {
  const criterion = draft.criteria?.[name]
  if (criterion) setKey(criterion, 'conditions', conditions)
}
