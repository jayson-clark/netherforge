/**
 * Pure edits to a loot table (`loot/<id>.json`), each on an Immer draft, and
 * what the editor shows of one: labels, a pool's ranges and each entry's
 * share of a pick. What a roll gives is format's (`rollLoot`); nothing here
 * rolls.
 */
import type {
  ItemDef,
  LootCondition,
  LootEntry,
  LootPool,
  LootRange,
  LootTableFile,
} from '@/core/format'

/** What the editor has selected: a pool, or one of its entries. */
export interface LootPick {
  pool: string
  entry?: number
}

export type EntryType = LootEntry['type']
export type ConditionType = LootCondition['type']

export const ENTRY_TYPES: { type: EntryType; label: string; one: string }[] = [
  { type: 'item', label: 'Item', one: 'an item' },
  { type: 'table', label: 'Loot table', one: 'another of the project’s loot tables' },
  { type: 'vanilla', label: 'Game loot table', one: 'one of the game’s loot tables' },
  { type: 'empty', label: 'Nothing', one: 'nothing' },
]

export const CONDITION_TYPES: { type: ConditionType; label: string }[] = [
  { type: 'chance', label: 'Chance' },
  { type: 'player', label: 'A player did it' },
  { type: 'tool', label: 'Tool' },
  { type: 'enchantment', label: 'Enchantment' },
]

/** The pools' names, in the order a roll rolls them. */
export const poolNames = (model: LootTableFile): string[] => Object.keys(model.pools ?? {}).sort()

/** The first name `<base>`, `<base>_2`, … that [taken] doesn't have. */
export function freeName(base: string, taken: Iterable<string>): string {
  const names = new Set(taken)
  if (!names.has(base)) return base
  for (let n = 2; ; n++) if (!names.has(`${base}_${n}`)) return `${base}_${n}`
}

/** Adds an empty pool; its name. */
export function addPool(draft: LootTableFile): string {
  draft.pools ??= {}
  const name = freeName('pool', Object.keys(draft.pools))
  draft.pools[name] = {}
  return name
}

export function renamePool(draft: LootTableFile, from: string, to: string) {
  const pools = draft.pools
  if (!pools?.[from] || pools[to] || from === to) return
  pools[to] = pools[from]
  delete pools[from]
}

/** A copy of pool [name] under a free name; that name, or null when there's no such pool. */
export function duplicatePool(draft: LootTableFile, name: string): string | null {
  const pool = draft.pools?.[name]
  if (!draft.pools || !pool) return null
  const copy = freeName(`${name}_copy`, Object.keys(draft.pools))
  // A plain copy: [pool] is a draft proxy, which structuredClone can't take.
  draft.pools[copy] = JSON.parse(JSON.stringify(pool)) as LootPool
  return copy
}

export function removePool(draft: LootTableFile, name: string) {
  if (draft.pools) delete draft.pools[name]
}

/**
 * A new entry of [type]: an item with no kind yet (the inspector's item form
 * is where it's chosen), [table] for a table entry, the game's table to
 * fill in, or nothing.
 */
export function newEntry(type: EntryType, table = ''): LootEntry {
  switch (type) {
    case 'item':
      return { type, item: { kind: '' } }
    case 'table':
      return { type, table }
    case 'vanilla':
      return { type, table: '' }
    case 'empty':
      return { type }
  }
}

/** Appends [entry] to [pool]; its index. */
export function addEntry(draft: LootTableFile, pool: string, entry: LootEntry): number {
  const target = draft.pools?.[pool]
  if (!target) return -1
  target.entries ??= []
  target.entries.push(entry)
  return target.entries.length - 1
}

export function removeEntry(draft: LootTableFile, pool: string, index: number) {
  const entries = draft.pools?.[pool]?.entries
  if (!entries || index < 0 || index >= entries.length) return
  entries.splice(index, 1)
  if (entries.length === 0) delete draft.pools![pool]!.entries
}

/** Moves entry [from] to [to] (order is the order a pick weighs them in). */
export function moveEntry(draft: LootTableFile, pool: string, from: number, to: number) {
  const entries = draft.pools?.[pool]?.entries
  if (
    !entries ||
    from === to ||
    to < 0 ||
    to >= entries.length ||
    from < 0 ||
    from >= entries.length
  )
    return
  const [moved] = entries.splice(from, 1)
  entries.splice(to, 0, moved!)
}

/** A range's two ends, with [fallback] for one that isn't set. */
export function rangeEnds(
  range: LootRange | undefined,
  fallback: number,
): { min: number; max: number } {
  if (range === undefined) return { min: fallback, max: fallback }
  if (typeof range === 'number') return { min: range, max: range }
  return { min: range.min, max: range.max }
}

/**
 * A range from its ends as the fields give them: a number when they're equal,
 * `undefined` (the default) when it's [fallback] at both ends.
 */
export function rangeOf(min: number, max: number, fallback: number): LootRange | undefined {
  if (min === fallback && max === fallback) return undefined
  return min === max ? min : { min, max }
}

/** How a range reads: `2`, `1–3`. */
export function rangeLabel(range: LootRange | undefined, fallback: number): string {
  const { min, max } = rangeEnds(range, fallback)
  return min === max ? `${min}` : `${min}–${max}`
}

/**
 * Each entry's share of one pick, 0 to 1, with no luck and every condition
 * passing: its weight over the pool's. (Conditions and luck change it per
 * roll; this is what the weights say.)
 */
export function shares(pool: LootPool): number[] {
  const weights = (pool.entries ?? []).map((entry) => Math.max(0, entry.weight ?? 1))
  const total = weights.reduce((sum, it) => sum + it, 0)
  return weights.map((it) => (total > 0 ? it / total : 0))
}

/** What an entry gives, in a few words. */
export function entryLabel(entry: LootEntry): string {
  switch (entry.type) {
    case 'item':
      return entry.item.item ?? (entry.item.kind || 'Item (choose one)')
    case 'table':
      return entry.table ? `Loot table ${entry.table}` : 'Loot table (choose one)'
    case 'vanilla':
      return entry.table || 'Game loot table (choose one)'
    case 'empty':
      return 'Nothing'
  }
}

/** A new condition of [type], with values to change. */
export function newCondition(type: ConditionType): LootCondition {
  switch (type) {
    case 'chance':
      return { type, chance: 0.5 }
    case 'player':
      return { type }
    case 'tool':
      return { type, tool: '' }
    case 'enchantment':
      return { type, enchantment: '' }
  }
}

/** How a condition reads: `50% chance`, `unless a player did it`. */
export function conditionLabel(condition: LootCondition): string {
  const text = (() => {
    switch (condition.type) {
      case 'chance':
        return `${Math.round(condition.chance * 1000) / 10}% chance`
      case 'player':
        return 'a player did it'
      case 'tool': {
        const tool = condition.tool
        return `tool is ${typeof tool === 'string' ? tool || '…' : tool.item}`
      }
      case 'enchantment':
        return `tool has ${condition.enchantment || '…'} ${condition.level ?? 1}+`
    }
  })()
  return condition.invert ? `unless ${text}` : text
}

/** Where a pool's conditions or an entry's are. */
export type ConditionsAt = { pool: string; entry?: number }

/** The conditions list at [at] in [draft], made when [make] and missing. */
export function conditionsAt(
  draft: LootTableFile,
  at: ConditionsAt,
  make = false,
): LootCondition[] | undefined {
  const pool = draft.pools?.[at.pool]
  if (!pool) return undefined
  const owner: { conditions?: LootCondition[] } | undefined =
    at.entry === undefined ? pool : pool.entries?.[at.entry]
  if (!owner) return undefined
  if (make) owner.conditions ??= []
  return owner.conditions
}

export function addCondition(draft: LootTableFile, at: ConditionsAt, condition: LootCondition) {
  conditionsAt(draft, at, true)?.push(condition)
}

export function removeCondition(draft: LootTableFile, at: ConditionsAt, index: number) {
  const list = conditionsAt(draft, at)
  if (!list) return
  list.splice(index, 1)
  if (list.length === 0) {
    const pool = draft.pools![at.pool]!
    const owner: { conditions?: LootCondition[] } =
      at.entry === undefined ? pool : pool.entries![at.entry]!
    delete owner.conditions
  }
}

/**
 * The items [table] can give, in the order its pools and entries list them, each item once (its count,
 * name and the like aside): its own item entries', then those of the project's tables its table entries roll
 * ([read] gives one by the name an entry writes, or undefined when it isn't the project's). The game's
 * tables give nothing here: the editor doesn't know what's in them.
 */
export function lootItems(
  table: LootTableFile,
  read: (name: string) => LootTableFile | null | undefined,
  limit = Infinity,
): ItemDef[] {
  const out = new Map<string, ItemDef>()
  const seen = new Set<string>()
  const walk = (file: LootTableFile) => {
    for (const name of poolNames(file)) {
      for (const entry of file.pools?.[name]?.entries ?? []) {
        if (out.size >= limit) return
        if (entry.type === 'item' && typeof entry.item === 'object' && entry.item !== null) {
          const { kind, item, itemModel, glint, enchantments } = entry.item
          const key = JSON.stringify([kind, item, itemModel])
          if (!out.has(key)) out.set(key, { kind, item, itemModel, glint, enchantments })
        } else if (entry.type === 'table' && !seen.has(entry.table)) {
          seen.add(entry.table)
          const nested = read(entry.table)
          if (nested) walk(nested)
        }
      }
    }
  }
  walk(table)
  return [...out.values()]
}
