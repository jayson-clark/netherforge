import { describe, expect, it } from 'vitest'
import { commandEntries, quickEntries, rankEntries } from './entries'

const FILES = [
  'netherforge.json',
  'centities/tower/centity.json',
  'centities/tower/script.lua',
  'centities/tower/lib/steps.lua',
  'menus/shop/menu.json',
  'modules/greeter/init.lua',
  'resource_packs/ui/textures/gui/shop.png',
]

describe('quick open', () => {
  it('offers resources by id and text files by path, not a resource’s own file twice', () => {
    const entries = quickEntries(FILES)
    expect(entries.map((it) => it.key)).toEqual(
      expect.arrayContaining([
        'centity:tower',
        'menu:shop',
        'module:greeter',
        'resource_pack:ui',
        'file:centities/tower/script.lua',
      ]),
    )
    expect(entries.some((it) => it.key === 'file:centities/tower/centity.json')).toBe(false)
    expect(entries.some((it) => 'open' in it.action && it.action.open.endsWith('.png'))).toBe(false)
    expect(entries.find((it) => it.key === 'centity:tower')?.detail).toBe(
      'Centity · centities/tower',
    )
    expect(entries.find((it) => it.key === 'file:centities/tower/script.lua')?.detail).toBe(
      'Lua script · centities/tower',
    )
  })

  it('ranks the resource first, and highlights within the label', () => {
    const ranked = rankEntries(quickEntries(FILES), 'shop')
    expect(ranked[0]!.key).toBe('menu:shop')
    const steps = rankEntries(quickEntries(FILES), 'steps')[0]!
    expect(steps.label).toBe('steps.lua')
    expect(steps.positions).toEqual([0, 1, 2, 3, 4])
  })

  it('mixes in commands, and lists them alone after a >', () => {
    const all = [
      ...quickEntries(FILES),
      ...commandEntries([
        { id: 'view.console', label: 'Show Console', hint: 'The log', icon: 'code' },
        { id: 'file.closeProject', label: 'Close Project', hint: '', icon: 'close' },
      ]),
    ]
    expect(rankEntries(all, 'console')[0]!.key).toBe('command:view.console')
    expect(rankEntries(all, '>').map((it) => it.key)).toEqual([
      'command:view.console',
      'command:file.closeProject',
    ])
    expect(rankEntries(all, '> tower')).toEqual([])
    expect(rankEntries(all, '').at(-1)!.key.startsWith('file:')).toBe(true)
  })
})
