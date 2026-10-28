import { describe, expect, it } from 'vitest'
import { docFromText } from '@/core/store/documents'
import { exampleFiles } from '@/testing/fixtures'
import type { RecipeFile } from '@/core/format'
import { currentText, recipesUsing, recipeUse } from './projectItems'

const files = Object.keys(exampleFiles)
const diskTexts = Object.fromEntries(
  Object.entries(exampleFiles).filter(([p]) => p.endsWith('.json')),
)

describe('project items in the editor', () => {
  it("lists the example's recipes that make or take the ruby", () => {
    expect(recipesUsing({ files, docs: {}, diskTexts }, 'ruby')).toEqual([
      { id: 'ruby', path: 'recipes/ruby.json', makes: true, uses: false },
      { id: 'ruby_dust', path: 'recipes/ruby_dust.json', makes: false, uses: true },
      { id: 'ruby_sword', path: 'recipes/ruby_sword.json', makes: false, uses: true },
    ])
    expect(recipesUsing({ files, docs: {}, diskTexts }, 'gem')).toEqual([])
  })

  it('reads an open document before the disk, unsaved edits included', () => {
    const path = 'recipes/ruby.json'
    const doc = docFromText(
      path,
      '{ "type": "shapeless", "ingredients": [{ "item": "ruby" }], "result": {} }',
    )
    expect(currentText({ docs: { [path]: doc }, diskTexts }, path)).toContain('"ingredients"')
    const uses = recipesUsing({ files, docs: { [path]: doc }, diskTexts }, 'ruby')
    expect(uses.find((it) => it.id === 'ruby')).toMatchObject({ makes: false, uses: true })
  })
})

describe('which recipes touch an item', () => {
  const RUBY = { item: 'ruby' }
  const STICK = 'minecraft:stick'
  const sword: RecipeFile = {
    type: 'shaped',
    pattern: [' R ', ' R ', ' S '],
    key: { R: RUBY, S: STICK },
    result: { kind: 'minecraft:iron_sword' },
  }

  it('tells making from taking', () => {
    expect(recipeUse(sword, 'ruby')).toEqual({ makes: false, uses: true })
    expect(recipeUse({ type: 'shapeless', ingredients: [STICK], result: RUBY }, 'ruby')).toEqual({
      makes: true,
      uses: false,
    })
    expect(recipeUse(sword, 'gem')).toEqual({ makes: false, uses: false })
  })
})
