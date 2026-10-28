/**
 * Pure edits to a recipe (docs/format/recipe.md), for the recipe editor.
 *
 * The editor shows a shaped recipe as the crafting grid it is: the user
 * places ingredients in squares, and [shapedOf] derives `pattern` and `key`
 * canonically (empty rows and columns trimmed, the way the game reads a
 * pattern; one letter per distinct ingredient; letters kept from the key
 * already there). Which fields each type takes, its categories and its
 * cooking defaults are format's (`RECIPE_TYPES`), never repeated here.
 */
import {
  RECIPE_GRID,
  RECIPE_TYPES,
  type ItemDef,
  type RecipeFile,
  type RecipeType,
} from '@/core/format'

/** One ingredient as the file writes it: an item id, `#` and a tag, or `{ item }` for a project item. */
export type Ingredient = string | { item: string }

/** What an ingredient is, as the picker's three tabs see it. */
export type IngredientChoice =
  { type: 'vanilla'; id: string } | { type: 'tag'; id: string } | { type: 'project'; id: string }

const TAG = '#'

/** The ingredient a picker choice writes. A tag typed with its `#` keeps one. */
export function ingredientOf(choice: IngredientChoice): Ingredient {
  switch (choice.type) {
    case 'vanilla':
      return choice.id.trim()
    case 'tag':
      return TAG + choice.id.trim().replace(/^#/, '')
    case 'project':
      return { item: choice.id }
  }
}

/** Which tab an ingredient belongs to, and its id there. */
export function describeIngredient(ingredient: Ingredient): IngredientChoice {
  if (typeof ingredient !== 'string') return { type: 'project', id: ingredient.item }
  if (ingredient.startsWith(TAG)) return { type: 'tag', id: ingredient.slice(TAG.length) }
  return { type: 'vanilla', id: ingredient }
}

export function sameIngredient(a: Ingredient | null | undefined, b: Ingredient | null | undefined) {
  if (a == null || b == null) return a == b
  if (typeof a === 'string' || typeof b === 'string') return a === b
  return a.item === b.item
}

/** `minecraft:stick` → `stick`, `#minecraft:planks` → `#planks`, a project item → its id. */
export function ingredientLabel(ingredient: Ingredient): string {
  const choice = describeIngredient(ingredient)
  const name = choice.id.slice(choice.id.indexOf(':') + 1)
  return choice.type === 'tag' ? TAG + name : name
}

/** The stack a slot draws for an ingredient, or null for a tag (any of many items). */
export function ingredientStack(ingredient: Ingredient): ItemDef | null {
  const choice = describeIngredient(ingredient)
  switch (choice.type) {
    case 'vanilla':
      return { kind: choice.id }
    case 'project':
      return { item: choice.id }
    case 'tag':
      return null
  }
}

/* The crafting grid. */

const SIZE: number = RECIPE_GRID.size

/** The grid's squares, row by row (`SIZE × SIZE`), each an ingredient or empty. */
export type Cells = (Ingredient | null)[]

export const emptyCells = (): Cells => Array.from({ length: SIZE * SIZE }, () => null)

/**
 * Where on the grid a pattern is shown: its top-left square. Not part of the
 * recipe (the game places a pattern anywhere it fits), only of the view, so a
 * shape the user built in the middle stays there after its pattern is trimmed.
 */
export interface GridOffset {
  x: number
  y: number
}

export const ORIGIN: GridOffset = { x: 0, y: 0 }

/** [offset], moved back as far as needed for [pattern] to fit on the grid. */
function fit(pattern: string[], offset: GridOffset): GridOffset {
  const width = Math.max(0, ...pattern.map((row) => row.length))
  return {
    x: Math.max(0, Math.min(offset.x, SIZE - width)),
    y: Math.max(0, Math.min(offset.y, SIZE - pattern.length)),
  }
}

/** Each square's pattern character at [offset], or null where there's none (or a space). */
export function symbolsOf(
  recipe: Pick<RecipeFile, 'pattern'>,
  offset: GridOffset = ORIGIN,
): (string | null)[] {
  const pattern = recipe.pattern ?? []
  const at = fit(pattern, offset)
  const symbols: (string | null)[] = emptyCells().map(() => null)
  pattern.forEach((row, y) => {
    ;[...row].forEach((symbol, x) => {
      const gx = x + at.x
      const gy = y + at.y
      if (symbol !== ' ' && gx < SIZE && gy < SIZE) symbols[gy * SIZE + gx] = symbol
    })
  })
  return symbols
}

/**
 * A shaped recipe's pattern laid on the grid at [offset] (moved back to fit).
 * A character the key doesn't have is an empty square (format flags it);
 * rows and columns past the grid are cut off (format flags those too).
 */
export function cellsOf(
  recipe: Pick<RecipeFile, 'pattern' | 'key'>,
  offset: GridOffset = ORIGIN,
): Cells {
  const key = recipe.key ?? {}
  return symbolsOf(recipe, offset).map((symbol) =>
    symbol !== null && Object.hasOwn(key, symbol) ? key[symbol]! : null,
  )
}

/** Characters a key may use, in the order they're tried after an ingredient's own letters. */
const SYMBOLS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#'

/** The letters an ingredient would like as its key: its name's, first letter first, capitals first. */
function preferredSymbols(ingredient: Ingredient): string[] {
  const letters = [...ingredientLabel(ingredient).replace(/^#/, '')].filter((c) =>
    /[A-Za-z0-9]/.test(c),
  )
  return [
    ...letters.map((c) => c.toUpperCase()),
    ...letters.map((c) => c.toLowerCase()),
    ...SYMBOLS,
  ]
}

/**
 * The pattern and key for a grid, canonically: the smallest rectangle
 * holding every ingredient (the game trims empty rows and columns the same
 * way, so `[" R ", " S "]` and `["R", "S"]` are one recipe), and one key
 * letter per distinct ingredient, in reading order. A letter [previous]
 * already gave that ingredient is kept, so editing one square doesn't
 * reletter the rest; a new ingredient takes its name's first free letter.
 * Null for an empty grid.
 */
export function shapedOf(
  cells: Cells,
  previous: Record<string, Ingredient> = {},
): { pattern: string[]; key: Record<string, Ingredient> } | null {
  let top = SIZE
  let bottom = -1
  let left = SIZE
  let right = -1
  cells.forEach((cell, index) => {
    if (cell === null) return
    const y = Math.floor(index / SIZE)
    const x = index % SIZE
    top = Math.min(top, y)
    bottom = Math.max(bottom, y)
    left = Math.min(left, x)
    right = Math.max(right, x)
  })
  if (bottom < 0) return null

  const distinct: Ingredient[] = []
  for (let y = top; y <= bottom; y += 1) {
    for (let x = left; x <= right; x += 1) {
      const cell = cells[y * SIZE + x]!
      if (cell !== null && !distinct.some((it) => sameIngredient(it, cell))) distinct.push(cell)
    }
  }

  const symbols = new Map<Ingredient, string>()
  const taken = new Set<string>()
  // Letters the old key gave an ingredient that's still here come first, so they stay put.
  for (const ingredient of distinct) {
    const kept = Object.keys(previous)
      .sort()
      .find(
        (symbol) =>
          isSymbol(symbol) && !taken.has(symbol) && sameIngredient(previous[symbol], ingredient),
      )
    if (kept) {
      symbols.set(ingredient, kept)
      taken.add(kept)
    }
  }
  for (const ingredient of distinct) {
    if (symbols.has(ingredient)) continue
    const symbol = preferredSymbols(ingredient).find((it) => !taken.has(it))!
    symbols.set(ingredient, symbol)
    taken.add(symbol)
  }

  const symbolOf = (cell: Ingredient | null) =>
    cell === null ? ' ' : symbols.get(distinct.find((it) => sameIngredient(it, cell))!)!
  const pattern: string[] = []
  for (let y = top; y <= bottom; y += 1) {
    let row = ''
    for (let x = left; x <= right; x += 1) row += symbolOf(cells[y * SIZE + x]!)
    pattern.push(row)
  }
  const key: Record<string, Ingredient> = {}
  for (const ingredient of distinct) key[symbols.get(ingredient)!] = ingredient
  return { pattern, key }
}

/** A key a pattern can use: one character, not a space. */
const isSymbol = (symbol: string) => symbol.length === 1 && symbol !== ' '

/** The top-left square of the smallest rectangle holding every ingredient; the origin for none. */
export function offsetOf(cells: Cells): GridOffset {
  let x = SIZE
  let y = SIZE
  cells.forEach((cell, index) => {
    if (cell === null) return
    x = Math.min(x, index % SIZE)
    y = Math.min(y, Math.floor(index / SIZE))
  })
  return x === SIZE ? ORIGIN : { x, y }
}

/**
 * Writes [cells] into a shaped recipe's pattern and key (an empty grid
 * leaves neither), and returns where the pattern now sits on the grid.
 */
function setCells(recipe: RecipeFile, cells: Cells): GridOffset {
  const shaped = shapedOf(cells, recipe.key)
  if (shaped) {
    recipe.pattern = shaped.pattern
    recipe.key = shaped.key
  } else {
    delete recipe.pattern
    delete recipe.key
  }
  return offsetOf(cells)
}

/**
 * Puts [ingredient] in grid square [index] of the grid as shown at [offset]
 * (null empties it), and derives pattern and key again. Returns the offset
 * that shows the new pattern where the squares are, so nothing moves.
 */
export function setCell(
  recipe: RecipeFile,
  index: number,
  ingredient: Ingredient | null,
  offset: GridOffset = ORIGIN,
): GridOffset {
  const cells = cellsOf(recipe, offset)
  cells[index] = ingredient
  return setCells(recipe, cells)
}

/** Swaps two grid squares (dragging one onto the other); returns the new offset as [setCell] does. */
export function swapCells(
  recipe: RecipeFile,
  from: number,
  to: number,
  offset: GridOffset = ORIGIN,
): GridOffset {
  const cells = cellsOf(recipe, offset)
  ;[cells[from], cells[to]] = [cells[to]!, cells[from]!]
  return setCells(recipe, cells)
}

/* Shapeless and single-ingredient slots. */

/**
 * Sets shapeless ingredient [index]: one past the end adds one (up to the
 * grid's nine), null removes it.
 */
export function setShapelessIngredient(
  recipe: RecipeFile,
  index: number,
  ingredient: Ingredient | null,
): void {
  const list = recipe.ingredients ?? []
  if (ingredient === null) {
    if (index < list.length) list.splice(index, 1)
  } else if (index < list.length) {
    list[index] = ingredient
  } else if (list.length < RECIPE_GRID.mostIngredients) {
    list.push(ingredient)
  }
  recipe.ingredients = list
}

/**
 * Sets a station recipe's material [index]: one past the end adds one (one of it), null removes it.
 * A material keeps its count when its ingredient changes.
 */
export function setMaterial(
  recipe: RecipeFile,
  index: number,
  ingredient: Ingredient | null,
): void {
  const list = recipe.materials ?? []
  if (ingredient === null) {
    if (index < list.length) list.splice(index, 1)
  } else if (index < list.length) {
    list[index]!.ingredient = ingredient
  } else {
    list.push({ ingredient })
  }
  recipe.materials = list
}

/** How many of material [index] it takes: 1 (or none given) leaves `count` out. */
export function setMaterialCount(
  recipe: RecipeFile,
  index: number,
  count: number | undefined,
): void {
  const material = recipe.materials?.[index]
  if (!material) return
  if (count === undefined || Math.round(count) <= 1) delete material.count
  else material.count = Math.round(count)
}

/** A station recipe's `stations` from text: commas or lines between them (`a | b` is one either-or). */
export function setStations(recipe: RecipeFile, text: string): void {
  const stations = text
    .split(/[,\n]/)
    .map((it) => it.trim().replace(/\s*\|\s*/g, ' | '))
    .filter(Boolean)
  if (stations.length) recipe.stations = stations
  else delete recipe.stations
}

/** The one-ingredient places: cooking and stonecutting take `ingredient`, smithing the other three. */
export type SlotField = 'ingredient' | 'template' | 'base' | 'addition'

export function setSlotIngredient(
  recipe: RecipeFile,
  field: SlotField,
  ingredient: Ingredient | null,
): void {
  if (ingredient === null) delete recipe[field]
  else recipe[field] = ingredient
}

/** Every ingredient a recipe holds, in the order a player reads them. */
export function ingredientsIn(recipe: RecipeFile): Ingredient[] {
  const fromGrid = recipe.pattern ? cellsOf(recipe).filter((it) => it !== null) : []
  return [
    ...fromGrid,
    ...(recipe.ingredients ?? []),
    ...(['template', 'base', 'addition', 'ingredient'] as const)
      .map((field) => recipe[field])
      .filter((it) => it !== undefined),
    ...(recipe.materials ?? []).map((it) => it.ingredient),
  ]
}

const TYPE_FIELDS = [
  'pattern',
  'key',
  'ingredients',
  'ingredient',
  'template',
  'base',
  'addition',
  'experience',
  'cookingTime',
  'materials',
  'stations',
  'stationRange',
] as const

/**
 * Switches a recipe's type, keeping what carries over: its ingredients (laid
 * out row by row in a grid, listed for shapeless, the first for one slot,
 * template/base/addition in order for smithing), cooking numbers between
 * cooking types, the group and category where the new type takes them, and
 * the result. Fields the new type doesn't take go, so the file stays valid.
 */
export function setRecipeType(recipe: RecipeFile, type: RecipeType): void {
  if (recipe.type === type) return
  const ingredients = ingredientsIn(recipe)
  const takes = RECIPE_TYPES[type]
  const keep = new Set<string>([...takes.required, ...takes.optional])
  for (const field of TYPE_FIELDS) {
    // Ingredients are placed again below; cooking numbers carry over between cooking types.
    if (field === 'experience' || field === 'cookingTime') {
      if (!keep.has(field)) delete recipe[field]
    } else {
      delete recipe[field]
    }
  }
  if (recipe.category !== undefined && !takes.categories.includes(recipe.category))
    delete recipe.category
  if (!takes.group) delete recipe.group
  recipe.type = type

  if (keep.has('pattern')) {
    const cells = emptyCells()
    ingredients.slice(0, cells.length).forEach((it, index) => (cells[index] = it))
    setCells(recipe, cells)
  } else if (keep.has('materials')) {
    // A station recipe takes one of each, to start from.
    recipe.materials = ingredients.map((ingredient) => ({ ingredient }))
  } else if (keep.has('ingredients')) {
    if (ingredients.length > 0)
      recipe.ingredients = ingredients.slice(0, RECIPE_GRID.mostIngredients)
  } else {
    const slots = (['template', 'base', 'addition', 'ingredient'] as const).filter((it) =>
      keep.has(it),
    )
    slots.forEach((field, index) => {
      const ingredient = ingredients[index]
      if (ingredient !== undefined) recipe[field] = ingredient
    })
  }
}
