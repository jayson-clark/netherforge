import { describe, expect, it } from 'vitest'
import {
  companionOf,
  findSourceRefs,
  groupAt,
  groupOf,
  groupsOf,
  isFileKind,
  locationIn,
  locationOf,
  mainFileIn,
  mainFileOf,
  opensAs,
  resourcePackTexturePath,
  parseJsonPath,
  relativeFolder,
  resourceIdsOf,
  resourceOf,
} from './paths'

describe('paths', () => {
  it('writes a folder relative to the project, as a path dependency names it', () => {
    expect(relativeFolder('/home/me/game', '/home/me/library')).toBe('../library')
    expect(relativeFolder('/home/me/game', '/home/me/game/libs/a')).toBe('libs/a')
    expect(relativeFolder('/home/me/game/', '/srv/shared')).toBe('../../../srv/shared')
    expect(relativeFolder('C:\\work\\game', 'c:\\work\\library')).toBe('../library')
    expect(relativeFolder('C:\\work\\game', 'D:\\library')).toBeNull()
  })

  it('parses format JSON paths', () => {
    expect(parseJsonPath('$.nodes.top.display.block')).toEqual(['nodes', 'top', 'display', 'block'])
    expect(parseJsonPath('$.nodes["a b"].hitbox.boxes[1]')).toEqual([
      'nodes',
      'a b',
      'hitbox',
      'boxes',
      1,
    ])
    expect(parseJsonPath('$')).toEqual([])
  })

  it('maps paths to resources', () => {
    expect(resourceOf('centities/tower/script.lua')).toEqual({
      kind: 'centity',
      id: 'tower',
      role: 'file',
      rest: 'script.lua',
      group: '',
      pkg: null,
    })
    expect(resourceOf('modules/greeter/lib/a.lua')).toMatchObject({
      kind: 'module',
      id: 'greeter',
      rest: 'lib/a.lua',
    })
    expect(resourceOf('menus/shop/menu.json')).toMatchObject({ kind: 'menu', role: 'main' })
    expect(resourceOf('resource_packs/ui/textures/gui/shop.png')?.kind).toBe('resource_pack')
    expect(resourceOf('dialogs/welcome')).toEqual({
      kind: 'dialog',
      id: 'welcome',
      role: 'folder',
      rest: '',
      group: '',
      pkg: null,
    })
    expect(resourceOf('maps/arena/region/r.0.0.mca')).toMatchObject({ kind: 'map' })
    expect(resourcePackTexturePath('ui', 'gui/shop.png')).toBe(
      'resource_packs/ui/textures/gui/shop.png',
    )
    expect(resourcePackTexturePath('library:gems', 'gem.png')).toBe(
      'library:resource_packs/gems/textures/gem.png',
    )
    expect(resourceOf('netherforge.json')).toBeNull()
    expect(
      resourceIdsOf(
        ['centities/a/centity.json', 'centities/b/x.lua', 'centities/.gitkeep', 'centities/c'],
        'centity',
      ),
    ).toEqual(['a', 'b'])
  })

  it('knows where each kind keeps a resource, folder or file', () => {
    expect(isFileKind('recipe')).toBe(true)
    expect(isFileKind('item')).toBe(false)
    expect(mainFileOf('recipe', 'torch')).toBe('recipes/torch.json')
    expect(mainFileOf('item', 'ruby')).toBe('items/ruby/item.json')
    expect(locationOf('recipe', 'torch')).toBe('recipes/torch.json')
    expect(locationOf('item', 'ruby')).toBe('items/ruby')
    expect(opensAs('module', 'greeter')).toBe('modules/greeter/init.lua')
    expect(opensAs('map', 'arena')).toBe('maps/arena')
    expect(opensAs('structure', 'house')).toBe('structures/house.nbt')
    // A migration is one SQL file, opened as that file.
    expect(isFileKind('migration')).toBe(true)
    expect(opensAs('migration', '001_init')).toBe('migrations/001_init.sql')
    expect(resourceOf('migrations/001_init.sql')).toMatchObject({
      kind: 'migration',
      id: '001_init',
      role: 'main',
    })
    expect(resourceOf('recipes/torch.json')).toEqual({
      kind: 'recipe',
      id: 'torch',
      role: 'main',
      rest: '',
      group: '',
      pkg: null,
    })
    expect(resourceOf('recipes/.json')).toBeNull()
    expect(resourceOf('recipes/a/b.json')).toBeNull()
    expect(resourceOf('recipes/notes.txt')).toBeNull()
    const files = ['recipes/b.json', 'recipes/a.json', 'recipes/notes.txt', 'items/ruby/item.json']
    expect(resourceIdsOf(files, 'recipe')).toEqual(['a', 'b'])
    expect(resourceIdsOf(files, 'item')).toEqual(['ruby'])
  })

  it('places resources in group folders without changing their ids', () => {
    expect(resourceOf('items/(weapons)/(wands)/ruby_wand/script.lua')).toEqual({
      kind: 'item',
      id: 'ruby_wand',
      role: 'file',
      rest: 'script.lua',
      group: '(weapons)/(wands)',
      pkg: null,
    })
    expect(resourceOf('items/(weapons)')).toBeNull()
    expect(groupAt('items/(weapons)')).toEqual({ kind: 'item', group: '(weapons)' })
    expect(groupAt('items/ruby')).toBeNull()
    expect(locationOf('item', 'ruby_wand', '(weapons)')).toBe('items/(weapons)/ruby_wand')
    expect(mainFileOf('recipe', 'torch', '(smelting)')).toBe('recipes/(smelting)/torch.json')
    expect(opensAs('module', 'shop', '(economy)')).toBe('modules/(economy)/shop/init.lua')
    expect(companionOf('structure', 'house', '(town)')).toBe('structures/(town)/house.json')

    const files = [
      'items/(weapons)/(wands)/ruby_wand/item.json',
      'items/(weapons)/sword/item.json',
      'items/(gems)/ruby/item.json',
      // The same id twice: format reads the one whose group sorts first, none before any.
      'items/ruby/item.json',
      'items/plain/item.json',
    ]
    expect(resourceIdsOf(files, 'item')).toEqual(['plain', 'ruby', 'ruby_wand', 'sword'])
    expect(groupOf(files, 'item', 'ruby_wand')).toBe('(weapons)/(wands)')
    expect(groupOf(files, 'item', 'ruby')).toBe('')
    expect(groupOf(files, 'item', 'missing')).toBe('')
    expect(groupsOf(files, 'item')).toEqual(['(gems)', '(weapons)', '(weapons)/(wands)'])
    expect(mainFileIn(files, 'item', 'sword')).toBe('items/(weapons)/sword/item.json')
    expect(locationIn(files, 'item', 'plain')).toBe('items/plain')
  })

  it('finds file:line references in console output', () => {
    const refs = findSourceRefs('error at centities/tower/script.lua:12: attempt to index nil')
    expect(refs).toEqual([{ start: 9, end: 38, file: 'centities/tower/script.lua', line: 12 }])
    expect(findSourceRefs('at menus/shop/script.lua:3')[0]?.file).toBe('menus/shop/script.lua')
    expect(findSourceRefs('at items/(tools)/ruby_wand/script.lua:7')[0]).toMatchObject({
      file: 'items/(tools)/ruby_wand/script.lua',
      line: 7,
    })
  })
})
