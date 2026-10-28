import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type ProjectManifest } from '@/core/format'
import {
  parseGameRule,
  removeDependency,
  setDependency,
  setExported,
  setGameRule,
  setGenerate,
  setWorldSpawn,
  setWorldValue,
} from './ops'

const manifest = (): ProjectManifest => ({
  formatVersion: 1,
  name: 'Test',
  namespace: 'test',
  version: '1.0.0',
  minecraft: '26.3',
})
const canonical = (model: ProjectManifest) =>
  JSON.parse(canonicalizeModel('netherforge', 'netherforge.json', model).text!) as ProjectManifest

describe('project settings: worlds', () => {
  it('sets a world’s terrain, seed and dimension type, and drops the world once it says nothing', () => {
    const model = manifest()
    setWorldValue(model, 'realm', 'terrain', 'ruby_hills')
    setWorldValue(model, 'realm', 'seed', 42)
    setWorldValue(model, 'deep', 'dimensionType', 'deep')
    expect(model.worlds).toEqual({
      deep: { dimensionType: 'deep' },
      realm: { terrain: 'ruby_hills', seed: 42 },
    })
    expect(canonical(model).worlds).toEqual(model.worlds)
    setWorldValue(model, 'deep', 'dimensionType', undefined)
    setWorldValue(model, 'realm', 'terrain', '')
    setWorldValue(model, 'realm', 'seed', undefined)
    expect('worlds' in model).toBe(false)
  })

  it('reads game rules as the game takes them and keeps them by name beside spawning', () => {
    expect(parseGameRule(' true ')).toBe(true)
    expect(parseGameRule('false')).toBe(false)
    expect(parseGameRule('-3')).toBe(-3)
    expect(parseGameRule('1.5')).toBeNull()
    expect(parseGameRule('yes')).toBeNull()
    const model = manifest()
    setGameRule(model, 'lobby', 'minecraft:random_tick_speed', 0)
    setGameRule(model, 'lobby', 'keep_inventory', true)
    setWorldSpawn(model, 'lobby', 'spawnLimits', 'monster', 0)
    expect(Object.keys(model.worlds!.lobby!.gameRules!)).toEqual([
      'keep_inventory',
      'minecraft:random_tick_speed',
    ])
    expect(canonical(model).worlds!.lobby!.gameRules).toEqual({
      keep_inventory: true,
      'minecraft:random_tick_speed': 0,
    })
    setGameRule(model, 'lobby', 'keep_inventory', undefined)
    setGameRule(model, 'lobby', 'minecraft:random_tick_speed', undefined)
    expect(model.worlds).toEqual({ lobby: { spawnLimits: { monster: 0 } } })
  })
})

describe('project settings: packages', () => {
  it('adds, changes and removes dependencies by namespace', () => {
    const model = manifest()
    setDependency(model, 'zeta', { path: '../zeta' })
    setDependency(model, 'library', { git: 'https://example.com/library.git', rev: 'v1' })
    expect(Object.keys(model.dependencies!)).toEqual(['library', 'zeta'])
    expect(canonical(model).dependencies).toEqual(model.dependencies)
    setDependency(model, 'library', { git: 'https://example.com/library.git' })
    expect(model.dependencies!.library).toEqual({ git: 'https://example.com/library.git' })
    removeDependency(model, 'library')
    removeDependency(model, 'zeta')
    expect('dependencies' in model).toBe(false)
  })

  it('exports resources by kind folder, sorted, and drops what lists nothing', () => {
    const model = manifest()
    setExported(model, 'items', 'ruby', true)
    setExported(model, 'items', 'gem', true)
    setExported(model, 'modules', 'greetings', true)
    expect(model.exports).toEqual({ items: ['gem', 'ruby'], modules: ['greetings'] })
    expect(canonical(model).exports).toEqual(model.exports)
    setExported(model, 'items', 'gem', false)
    setExported(model, 'items', 'ruby', false)
    setExported(model, 'modules', 'greetings', false)
    expect('exports' in model).toBe(false)
  })

  it('keeps generators one a line, and none as no key', () => {
    const model = manifest()
    setGenerate(model, 'node tools/items.mjs\n\n  node tools/menus.mjs  ')
    expect(model.generate).toEqual(['node tools/items.mjs', 'node tools/menus.mjs'])
    expect(canonical(model).generate).toEqual(model.generate)
    setGenerate(model, '  ')
    expect('generate' in model).toBe(false)
  })
})
