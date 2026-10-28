/**
 * Pure edits over a structure's generation file (`structures/<id>.json`,
 * format's `StructureGeneration`): what the structure screen's "Generates in
 * the world" section changes. The file is written through format only
 * (`canonicalizeModel`), so none of this orders keys or fills defaults.
 */
import { setKey } from '@/core/draft'
import type { PoolProjection, StructureGeneration } from '@/core/format'

/** A new generation file: the overworld, with every other setting at the game's default. */
export const newGeneration = (): StructureGeneration => ({ biomes: ['#minecraft:is_overworld'] })

/** Biomes as typed: ids and a lone tag, separated by commas or spaces. */
export const biomesText = (generation: StructureGeneration) => generation.biomes.join(', ')

/** The ids (or the one tag) a [text] lists, in order, none twice. */
export function parseBiomes(text: string): string[] {
  return [...new Set(text.split(/[\s,]+/).filter((it) => it !== ''))]
}

export function setBiomes(draft: StructureGeneration, text: string): void {
  draft.biomes = parseBiomes(text)
}

/** Sets a whole-number [key], or removes it (back to the game's default) when cleared. */
export function setCount(
  draft: StructureGeneration,
  key: 'spacing' | 'separation' | 'salt' | 'depth' | 'maxDistance' | 'startHeight',
  value: number | undefined,
): void {
  setKey(draft, key, value === undefined ? undefined : Math.round(value))
}

/** The names of a structure's pools, in the file's order: the screen lists them, the file edits them. */
export const poolNames = (generation: StructureGeneration) => Object.keys(generation.pools ?? {})

/** The pool every structure starts from, which a file's own pools can't be called. */
export const START_POOL = 'start'

/** Adds an empty pool [name] (its first piece is added next). */
export function addPool(draft: StructureGeneration, name: string): void {
  draft.pools = { ...draft.pools, [name]: { elements: [] } }
}

/** Renames pool [from] to [to], keeping its place among the others. */
export function renamePool(draft: StructureGeneration, from: string, to: string): void {
  const pools = draft.pools
  if (!pools?.[from] || from === to) return
  draft.pools = Object.fromEntries(
    Object.entries(pools).map(([name, pool]) => [name === from ? to : name, pool]),
  )
}

/** Removes pool [name]; with none left, the structure is one piece again. */
export function deletePool(draft: StructureGeneration, name: string): void {
  if (!draft.pools) return
  delete draft.pools[name]
  if (Object.keys(draft.pools).length === 0) delete draft.pools
}

/** A pool's projection: `rigid` (the default) leaves the key out. */
export function setProjection(
  draft: StructureGeneration,
  pool: string,
  projection: PoolProjection,
): void {
  const target = draft.pools?.[pool]
  if (target) setKey(target, 'projection', projection === 'rigid' ? undefined : projection)
}

/** Adds a piece to [pool]: [structure], weight 1. */
export function addElement(draft: StructureGeneration, pool: string, structure: string): void {
  draft.pools?.[pool]?.elements.push({ structure })
}

export function removeElement(draft: StructureGeneration, pool: string, index: number): void {
  draft.pools?.[pool]?.elements.splice(index, 1)
}

/** Sets a piece's structure, or its weight (1, the default, or cleared leaves it out). */
export function setElement(
  draft: StructureGeneration,
  pool: string,
  index: number,
  change: { structure?: string; weight?: number | null },
): void {
  const element = draft.pools?.[pool]?.elements[index]
  if (!element) return
  if (change.structure !== undefined) element.structure = change.structure
  if (change.weight !== undefined) {
    const weight = change.weight === null ? 1 : Math.round(change.weight)
    setKey(element, 'weight', weight === 1 ? undefined : weight)
  }
}
