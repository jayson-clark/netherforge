import { describe, expect, it } from 'vitest'
import { KIND_ORDER } from '@/editors/registry'
import { exampleFiles } from '@/testing/fixtures'
import {
  EXPLORER_FOLDERS,
  EXPLORER_SECTIONS,
  folderByKey,
  groupPreview,
  isFileFolder,
  resourceAt,
} from './folders'

const files = Object.keys(exampleFiles)

describe("the explorer's folders", () => {
  it('are one per kind, in the registry order, each in exactly one section', () => {
    expect(EXPLORER_FOLDERS.map((it) => it.key)).toEqual(KIND_ORDER)
    const sectioned = EXPLORER_SECTIONS.flatMap((it) => it.folders.map((folder) => folder.key))
    expect([...sectioned].sort()).toEqual([...KIND_ORDER].sort())
  })

  it('fall back to the first folder for no kind or one that is gone', () => {
    expect(folderByKey(null)).toBe(EXPLORER_FOLDERS[0])
    expect(folderByKey('gone')).toBe(EXPLORER_FOLDERS[0])
    expect(folderByKey('menu').key).toBe('menu')
  })

  it("know a folder resource's ids, where it lives and what opening it opens", () => {
    const centities = folderByKey('centity')
    expect(centities.ids(files)).toEqual(expect.arrayContaining(['lamp', 'tower']))
    expect(centities.location('tower')).toBe('centities/tower')
    expect(centities.openPath('tower')).toBe('centities/tower/centity.json')
    expect(isFileFolder(centities)).toBe(false)
  })

  it('know a single-file resource by its file', () => {
    const recipes = folderByKey('recipe')
    expect(isFileFolder(recipes)).toBe(true)
    expect(recipes.ids(files)).toContain('ruby_dust')
    expect(recipes.location('ruby_dust')).toBe('recipes/ruby_dust.json')
    expect(recipes.openPath('ruby_dust')).toBe('recipes/ruby_dust.json')
  })

  it('open a module on its entry file', () => {
    const modules = folderByKey('module')
    expect(modules.ids(files)).toContain('greeter')
    expect(modules.openPath('greeter')).toBe('modules/greeter/init.lua')
  })

  it('find the resource a path is in, or none', () => {
    expect(resourceAt('centities/tower/script.lua', files)).toMatchObject({
      folder: { key: 'centity' },
      id: 'tower',
    })
    expect(resourceAt('centities/tower', files)?.id).toBe('tower')
    expect(resourceAt('recipes/ruby_dust.json', files)).toMatchObject({
      folder: { key: 'recipe' },
      id: 'ruby_dust',
    })
    // A prefix of a resource's folder isn't inside it.
    expect(resourceAt('centities/towers/x.lua', files)).toBeNull()
    expect(resourceAt('netherforge.json', files)).toBeNull()
  })

  it("pick a group's tile pictures: up to four, those right in it before its own groups'", () => {
    const items = folderByKey('item')
    expect(groupPreview(items, files, '(tools)')).toEqual([
      'glow_pickaxe',
      'ruby_pick',
      'ruby_wand',
    ])
    const nested = [
      ...files,
      ...['a', 'b', 'c'].map((id) => `items/(gear)/(deep)/${id}/item.json`),
      ...['d', 'e'].map((id) => `items/(gear)/${id}/item.json`),
    ]
    expect(groupPreview(items, nested, '(gear)')).toEqual(['d', 'e', 'a', 'b'])
    expect(groupPreview(items, nested, '(gear)/(deep)')).toEqual(['a', 'b', 'c'])
  })
})
