import { produce } from 'immer'
import { describe, expect, it } from 'vitest'
import { canonicalizeModel, loadProject, type RecipeFile } from '@/core/format'
import {
  cellsOf,
  describeIngredient,
  emptyCells,
  ingredientLabel,
  ingredientOf,
  ingredientStack,
  offsetOf,
  ORIGIN,
  sameIngredient,
  setCell,
  setMaterial,
  setMaterialCount,
  setRecipeType,
  setShapelessIngredient,
  setStations,
  setSlotIngredient,
  shapedOf,
  swapCells,
  symbolsOf,
  type Cells,
  type Ingredient,
} from './ops'

const STICK = 'minecraft:stick'
const PLANKS = '#minecraft:planks'
const RUBY = { item: 'ruby' }

/** A grid from rows of three, `.` for an empty square. */
function grid(rows: string[], legend: Record<string, Ingredient>): Cells {
  const cells = emptyCells()
  rows.forEach((row, y) =>
    [...row].forEach((c, x) => {
      if (c !== '.') cells[y * 3 + x] = legend[c]!
    }),
  )
  return cells
}

const sword: RecipeFile = {
  type: 'shaped',
  pattern: [' R ', ' R ', ' S '],
  key: { R: RUBY, S: STICK },
  result: { kind: 'minecraft:iron_sword' },
}

const edit = (recipe: RecipeFile, recipeFn: (draft: RecipeFile) => void) =>
  produce(recipe, recipeFn)

/** The text format writes for a recipe model; undefined if it doesn't parse. */
const canonical = (recipe: RecipeFile) =>
  canonicalizeModel('recipe', 'recipes/test.json', recipe).text

describe('ingredients', () => {
  it('convert between the picker and the file', () => {
    expect(ingredientOf({ type: 'vanilla', id: STICK })).toBe(STICK)
    expect(ingredientOf({ type: 'tag', id: 'minecraft:planks' })).toBe(PLANKS)
    // Typed with its #, it still gets only one.
    expect(ingredientOf({ type: 'tag', id: ' #minecraft:planks ' })).toBe(PLANKS)
    expect(ingredientOf({ type: 'project', id: 'ruby' })).toEqual(RUBY)

    expect(describeIngredient(STICK)).toEqual({ type: 'vanilla', id: STICK })
    expect(describeIngredient(PLANKS)).toEqual({ type: 'tag', id: 'minecraft:planks' })
    expect(describeIngredient(RUBY)).toEqual({ type: 'project', id: 'ruby' })
    for (const ingredient of [STICK, PLANKS, RUBY])
      expect(ingredientOf(describeIngredient(ingredient))).toEqual(ingredient)
  })

  it('compare, label and draw', () => {
    expect(sameIngredient({ item: 'ruby' }, RUBY)).toBe(true)
    expect(sameIngredient('ruby', RUBY)).toBe(false)
    expect(sameIngredient(null, null)).toBe(true)
    expect(ingredientLabel(STICK)).toBe('stick')
    expect(ingredientLabel(PLANKS)).toBe('#planks')
    expect(ingredientLabel(RUBY)).toBe('ruby')
    expect(ingredientStack(STICK)).toEqual({ kind: STICK })
    expect(ingredientStack(RUBY)).toEqual({ item: 'ruby' })
    expect(ingredientStack(PLANKS)).toBeNull()
  })
})

describe('the crafting grid', () => {
  it('lays a pattern on the grid from the top-left square', () => {
    expect(cellsOf(sword)).toEqual(grid(['.R.', '.R.', '.S.'], { R: RUBY, S: STICK }))
    expect(cellsOf({ pattern: ['RS'], key: { R: RUBY, S: STICK } })).toEqual(
      grid(['RS.', '...', '...'], { R: RUBY, S: STICK }),
    )
    // A character the key lacks is an empty square; format reports it.
    expect(cellsOf({ pattern: ['RX'], key: { R: RUBY } })).toEqual(
      grid(['R..', '...', '...'], { R: RUBY }),
    )
    expect(symbolsOf(sword)).toEqual([null, 'R', null, null, 'R', null, null, 'S', null])
  })

  it('trims empty rows and columns, so the same shape anywhere is one pattern', () => {
    const corner = grid(['R..', 'S..', '...'], { R: RUBY, S: STICK })
    const middle = grid(['...', '.R.', '.S.'], { R: RUBY, S: STICK })
    expect(shapedOf(corner)).toEqual({ pattern: ['R', 'S'], key: { R: RUBY, S: STICK } })
    expect(shapedOf(middle)).toEqual(shapedOf(corner))
    // Inner gaps stay: they're part of the shape.
    expect(shapedOf(grid(['R.R', '...', 'R.R'], { R: RUBY }))?.pattern).toEqual([
      'R R',
      '   ',
      'R R',
    ])
    expect(shapedOf(emptyCells())).toBeNull()
  })

  it('gives one letter to each distinct ingredient, however many squares it fills', () => {
    const shaped = shapedOf(grid(['PPP', 'P.P', 'PPP'], { P: PLANKS }))
    expect(shaped).toEqual({ pattern: ['PPP', 'P P', 'PPP'], key: { P: PLANKS } })
    // Symmetric shapes and equal project items share a key too.
    const symmetric = shapedOf(grid(['R.R', '.S.', 'R.R'], { R: { item: 'ruby' }, S: STICK }))
    expect(Object.keys(symmetric!.key)).toHaveLength(2)
  })

  it("picks each new ingredient's own letter, then the next free one", () => {
    // stick and stone_bricks both want S: stick comes first, so stone_bricks takes its next letter, T.
    const shaped = shapedOf(grid(['ab.', '...', '...'], { a: STICK, b: 'minecraft:stone_bricks' }))
    expect(shaped!.pattern).toEqual(['ST'])
    expect(shaped!.key).toEqual({ S: STICK, T: 'minecraft:stone_bricks' })
  })

  it('keeps the letters the key already had', () => {
    const previous = { X: RUBY, S: STICK }
    const shaped = shapedOf(grid(['X..', 'S..', '...'], { X: RUBY, S: STICK }), previous)
    expect(shaped).toEqual({ pattern: ['X', 'S'], key: { X: RUBY, S: STICK } })
    // An old letter for an ingredient that's gone is free again; one that's still here stays.
    const swapped = shapedOf(
      grid(['X..', 'E..', '...'], { X: RUBY, E: 'minecraft:emerald' }),
      previous,
    )
    expect(swapped).toEqual({ pattern: ['X', 'E'], key: { X: RUBY, E: 'minecraft:emerald' } })
  })

  it('edits one square and derives pattern and key again, canonically', () => {
    const next = edit(sword, (draft) => void setCell(draft, 1, null))
    // The top ruby went: the pattern shrinks to two rows and loses its empty columns.
    expect(next.pattern).toEqual(['R', 'S'])
    expect(next.key).toEqual({ R: RUBY, S: STICK })
    const added = edit(next, (draft) => void setCell(draft, 1, PLANKS))
    expect(added.pattern).toEqual(['RP', 'S '])
    expect(added.key).toEqual({ R: RUBY, S: STICK, P: PLANKS })

    const text = canonical(added)!
    expect(text).toContain('"pattern": ["RP", "S "]')
    // A canonical file reads back to the same grid.
    expect(cellsOf(JSON.parse(text) as RecipeFile)).toEqual(cellsOf(added))
  })

  it('keeps a trimmed pattern where its squares were, through the offset', () => {
    // A stick placed in the middle square of an empty grid: the pattern is one square, shown in the middle.
    let offset = ORIGIN
    const empty: RecipeFile = { type: 'shaped', result: { kind: STICK } }
    const one = edit(empty, (draft) => {
      offset = setCell(draft, 4, STICK, offset)
    })
    expect(one.pattern).toEqual(['S'])
    expect(offset).toEqual({ x: 1, y: 1 })
    expect(cellsOf(one, offset)).toEqual(grid(['...', '.S.', '...'], { S: STICK }))
    // A second one below it: still where it was put.
    const two = edit(one, (draft) => {
      offset = setCell(draft, 7, STICK, offset)
    })
    expect(two.pattern).toEqual(['S', 'S'])
    expect(cellsOf(two, offset)).toEqual(grid(['...', '.S.', '.S.'], { S: STICK }))
    // An offset that no longer fits is moved back until the pattern does.
    expect(cellsOf(sword, { x: 2, y: 2 })).toEqual(cellsOf(sword))
    expect(offsetOf(emptyCells())).toEqual(ORIGIN)
  })

  it('empties the grid to no pattern at all, which format then asks for', () => {
    let recipe = sword
    let offset = ORIGIN
    for (const index of [1, 4, 7])
      recipe = edit(recipe, (draft) => {
        offset = setCell(draft, index, null, offset)
      })
    expect(recipe.pattern).toBeUndefined()
    expect(recipe.key).toBeUndefined()
    const outline = loadProject(
      {
        'netherforge.json':
          '{ "formatVersion": 1, "name": "T", "namespace": "t", "version": "1.0.0", "minecraft": "26.3" }',
        'recipes/test.json': JSON.stringify(recipe),
      },
      null,
    )
    expect(outline.problems.map((it) => it.code)).toEqual(['recipe.missing', 'recipe.missing'])
  })

  it('swaps two squares, the way a drag does', () => {
    const next = edit(sword, (draft) => void swapCells(draft, 7, 8))
    expect(next.pattern).toEqual(['R ', 'R ', ' S'])
  })
})

describe('other slots', () => {
  const shapeless: RecipeFile = {
    type: 'shapeless',
    ingredients: [STICK, PLANKS],
    result: RUBY,
  }

  it('adds, replaces and removes shapeless ingredients, up to nine', () => {
    let next = edit(shapeless, (draft) => setShapelessIngredient(draft, 2, RUBY))
    expect(next.ingredients).toEqual([STICK, PLANKS, RUBY])
    next = edit(next, (draft) => setShapelessIngredient(draft, 0, PLANKS))
    expect(next.ingredients).toEqual([PLANKS, PLANKS, RUBY])
    next = edit(next, (draft) => setShapelessIngredient(draft, 1, null))
    expect(next.ingredients).toEqual([PLANKS, RUBY])
    for (let i = 0; i < 12; i += 1)
      next = edit(next, (draft) => setShapelessIngredient(draft, draft.ingredients!.length, STICK))
    expect(next.ingredients).toHaveLength(9)
  })

  it('sets and empties a single slot', () => {
    const smithing: RecipeFile = { type: 'smithing_transform', result: { kind: STICK } }
    const next = edit(smithing, (draft) => setSlotIngredient(draft, 'base', RUBY))
    expect(next.base).toEqual(RUBY)
    expect(edit(next, (draft) => setSlotIngredient(draft, 'base', null)).base).toBeUndefined()
  })
})

describe('switching type', () => {
  it('carries the ingredients over, and drops what the new type does not take', () => {
    const withGroup = { ...sword, group: 'swords', category: 'equipment' as const }
    const shapeless = edit(withGroup, (draft) => setRecipeType(draft, 'shapeless'))
    expect(shapeless).toEqual({
      type: 'shapeless',
      ingredients: [RUBY, RUBY, STICK],
      result: sword.result,
      group: 'swords',
      category: 'equipment',
    })
    const furnace = edit(shapeless, (draft) => setRecipeType(draft, 'furnace'))
    // A crafting category isn't a cooking one.
    expect(furnace).toEqual({
      type: 'furnace',
      ingredient: RUBY,
      result: sword.result,
      group: 'swords',
    })
    const smoking = edit({ ...furnace, cookingTime: 50, experience: 1 }, (draft) =>
      setRecipeType(draft, 'smoking'),
    )
    expect(smoking).toMatchObject({ cookingTime: 50, experience: 1, ingredient: RUBY })
    const smithing = edit(smoking, (draft) => setRecipeType(draft, 'smithing_transform'))
    expect(smithing).toEqual({ type: 'smithing_transform', template: RUBY, result: sword.result })
    const shaped = edit(shapeless, (draft) => setRecipeType(draft, 'shaped'))
    expect(shaped.pattern).toEqual(['RRS'])
    expect(shaped.key).toEqual({ R: RUBY, S: STICK })
    for (const recipe of [shapeless, furnace, smoking, smithing, shaped])
      expect(canonical(recipe)).toBeDefined()
  })
})

describe('a station recipe', () => {
  it('takes the ingredients over as materials, one of each, and drops what it has no use for', () => {
    const recipe: RecipeFile = {
      type: 'shapeless',
      ingredients: ['minecraft:stick', { item: 'ruby' }],
      result: { kind: 'minecraft:torch' },
      category: 'misc',
    }
    setRecipeType(recipe, 'station')
    expect(recipe).toEqual({
      type: 'station',
      materials: [{ ingredient: 'minecraft:stick' }, { ingredient: { item: 'ruby' } }],
      result: { kind: 'minecraft:torch' },
    })
    setRecipeType(recipe, 'shapeless')
    expect(recipe.ingredients).toEqual(['minecraft:stick', { item: 'ruby' }])
    expect(recipe.materials).toBeUndefined()
  })
})

describe('station recipes', () => {
  it('adds, changes and removes materials, a count of 1 being no key', () => {
    const recipe: RecipeFile = { type: 'station', materials: [], result: { item: 'torch' } }
    const next = produce(recipe, (draft) => {
      setMaterial(draft, 0, { item: 'gel' })
      setMaterial(draft, 1, '#minecraft:planks')
      setMaterialCount(draft, 1, 2.4)
      setMaterial(draft, 1, '#minecraft:logs')
    })
    expect(next.materials).toEqual([
      { ingredient: { item: 'gel' } },
      { ingredient: '#minecraft:logs', count: 2 },
    ])
    const cleared = produce(next, (draft) => {
      setMaterialCount(draft, 1, 1)
      setMaterial(draft, 0, null)
    })
    expect(cleared.materials).toEqual([{ ingredient: '#minecraft:logs' }])
  })

  it('reads stations from commas and lines, keeping either-or ones whole', () => {
    const recipe: RecipeFile = {
      type: 'station',
      materials: [],
      result: { kind: 'minecraft:torch' },
    }
    const next = produce(recipe, (draft) =>
      setStations(draft, 'work_bench,\n minecraft:furnace|minecraft:blast_furnace '),
    )
    expect(next.stations).toEqual(['work_bench', 'minecraft:furnace | minecraft:blast_furnace'])
    expect(
      JSON.parse(canonicalizeModel('recipe', 'recipes/torch.json', next).text!).stations,
    ).toEqual(next.stations)
    expect('stations' in produce(next, (draft) => setStations(draft, ' , '))).toBe(false)
  })
})
