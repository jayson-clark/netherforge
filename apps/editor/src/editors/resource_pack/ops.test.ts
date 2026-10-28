import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type ResourcePackFile } from '@/core/format'
import { exampleFiles, exampleProject } from '@/testing/fixtures'
import {
  addEntry,
  addHudElement,
  addSoundEntry,
  hudKeys,
  hudKindOf,
  newHudElement,
  removeHudElement,
  removeSoundEntry,
  renameHudElement,
  renameHudIcon,
  setGameModel,
  setHudHidden,
  setHudIcon,
  setHudReplace,
  duplicateEntry,
  freeEntryKey,
  idFrom,
  importPath,
  modelTextures,
  packOf,
  isOgg,
  newEntry,
  pngSize,
  removeEntry,
  renameEntry,
  selectionForFile,
  setSoundValue,
  soundEvents,
  soundFilesOf,
  soundImportPath,
  texturesOf,
  unusedTextures,
  setGameTexture,
  gameTextureCopyPath,
} from './ops'

const PACK = 'resource_packs/ui/pack.json'
const ui = () => JSON.parse(exampleFiles[PACK]!) as ResourcePackFile

describe('pack ops', () => {
  it("lists a pack's textures and which are unused", () => {
    const files = [
      ...Object.keys(exampleProject),
      'resource_packs/ui/textures/gui/extra.png',
      'resource_packs/ui/notes.txt',
    ]
    const textures = texturesOf(files, 'ui')
    expect(textures).toContain('gui/shop.png')
    expect(textures).not.toContain('notes.txt')
    // The pack's model files count too: the owl's and the dart's textures are only named by their models.
    const models = Object.fromEntries(
      Object.entries(exampleFiles)
        .filter(([path]) => path.startsWith('resource_packs/ui/models/'))
        .map(([path, text]) => [path.slice('resource_packs/ui/models/'.length), JSON.parse(text)]),
    )
    expect(unusedTextures(ui(), textures, models)).toEqual(['gui/extra.png'])
    expect(unusedTextures(ui(), ['dart/steel.png'])).toEqual(['dart/steel.png'])
    expect(
      modelTextures({ m: { textures: { a: 'x/y', b: '#a', c: 'minecraft:block/stone' } } }),
    ).toEqual(['x/y.png'])
  })

  it("finds a package's pack in the package's files, by package path", () => {
    expect(packOf('resource_packs/ui/pack.json')).toBe('ui')
    expect(packOf('library:resource_packs/gems/pack.json')).toBe('library:gems')
    const files = [
      'library:resource_packs/gems/pack.json',
      'library:resource_packs/gems/textures/item/gem.png',
      'library:resource_packs/gems/sounds/hum.ogg',
      // The project's pack of the same id isn't the package's.
      'resource_packs/gems/textures/other.png',
    ]
    expect(texturesOf(files, 'library:gems')).toEqual(['item/gem.png'])
    expect(soundFilesOf(files, 'library:gems')).toEqual(['hum.ogg'])
    expect(texturesOf(files, 'gems')).toEqual(['other.png'])
  })

  it('adds, renames and removes entries', () => {
    const pack = ui()
    const key = freeEntryKey(pack, 'skins', 'shop.png')
    expect(key).toBe('shop_2')
    addEntry(pack, 'skins', key, newEntry('skins', 'gui/extra.png'))
    renameEntry(pack, 'skins', 'shop_2', 'banner')
    expect(Object.keys(pack.skins!)).toEqual(['altar', 'shop', 'banner'])
    renameEntry(pack, 'skins', 'banner', 'shop')
    expect(pack.skins!.banner).toBeDefined()
    removeEntry(pack, 'glyphs', 'coin')
    expect(Object.keys(pack.glyphs!)).toEqual(['spark', 'wisp'])
    removeEntry(pack, 'glyphs', 'spark')
    removeEntry(pack, 'glyphs', 'wisp')
    expect(pack.glyphs).toBeUndefined()
    expect(newEntry('tooltips', 'a.png')).toEqual({ background: 'a.png' })
    expect(newEntry('equipment', 'armor/a.png')).toEqual({ humanoid: 'armor/a.png' })
    const text = canonicalizeModel('resource_pack', PACK, pack).text!
    expect(text.indexOf('"banner"')).toBeLessThan(text.indexOf('"shop"'))
  })

  it('names imports after the file', () => {
    expect(idFrom('My Banner (1).PNG')).toBe('my_banner_1')
    expect(importPath('My Banner.png', 'gui')).toBe('gui/my_banner.png')
    expect(importPath('###.png', '')).toBe('texture.png')
  })

  it('reads PNG sizes', () => {
    expect(
      pngSize(exampleProject['resource_packs/ui/textures/gui/shop.png'] as Uint8Array),
    ).toEqual({
      width: 176,
      height: 168,
    })
    expect(pngSize(new Uint8Array([1, 2, 3]))).toBeNull()
  })

  it("lists a pack's sounds: every file is an event, pack.json refines or adds", () => {
    const files = [
      'resource_packs/ui/sounds/click.ogg',
      'resource_packs/ui/sounds/step/a.ogg',
      'resource_packs/ui/sounds/readme.txt',
      'resource_packs/ui/sounds/Door Open.OGG',
      'resource_packs/other/sounds/x.ogg',
    ]
    // Listed whatever their name (format says what's wrong with it), but only a usable one is an event.
    const sounds = soundFilesOf(files, 'ui')
    expect(sounds).toEqual(['Door Open.OGG', 'click.ogg', 'step/a.ogg'])
    const pack: ResourcePackFile = { sounds: { step: { files: ['step/a.ogg'] } } }
    expect(soundEvents(pack, sounds)).toEqual([
      { key: 'click', files: ['click.ogg'] },
      { key: 'step', files: ['step/a.ogg'] },
      { key: 'step/a', files: ['step/a.ogg'] },
    ])
  })

  it('edits a sound entry, dropping it once empty', () => {
    const pack: ResourcePackFile = {}
    setSoundValue(pack, 'click', 'volume', 0.5)
    setSoundValue(pack, 'click', 'subtitle', 'Click')
    expect(pack.sounds).toEqual({ click: { volume: 0.5, subtitle: 'Click' } })
    setSoundValue(pack, 'click', 'stream', true)
    expect(pack.sounds?.click?.stream).toBe(true)
    setSoundValue(pack, 'click', 'stream', undefined)
    setSoundValue(pack, 'click', 'volume', undefined)
    setSoundValue(pack, 'click', 'subtitle', '')
    expect(pack.sounds).toBeUndefined()
    expect(
      canonicalizeModel('resource_pack', PACK, { sounds: { a: { pitch: 2 } } }).problems,
    ).toEqual([])
  })

  it('names sound imports and recognises Ogg files', () => {
    expect(soundImportPath('Door Open.OGG', 'fx')).toBe('fx/door_open.ogg')
    expect(soundImportPath('###.ogg', '')).toBe('sound.ogg')
    expect(isOgg(new TextEncoder().encode('OggS\0'))).toBe(true)
    expect(isOgg(new TextEncoder().encode('RIFF'))).toBe(false)
  })
})

describe('duplicateEntry', () => {
  it('copies an entry under the next free key, deeply', () => {
    const pack: ResourcePackFile = { skins: { shop: { texture: 'gui/shop.png', ascent: 13 } } }
    expect(duplicateEntry(pack, 'skins', 'shop')).toBe('shop_2')
    expect(duplicateEntry(pack, 'skins', 'shop')).toBe('shop_3')
    expect(pack.skins!.shop_2).toEqual(pack.skins!.shop)
    expect(pack.skins!.shop_2).not.toBe(pack.skins!.shop)
    expect(duplicateEntry(pack, 'glyphs', 'nope')).toBeNull()
  })

  it("selects a file's gallery entry: a texture, or a sound that can be an event", () => {
    expect(selectionForFile('textures/gui/shop.png')).toEqual({
      kind: 'textures',
      key: 'gui/shop.png',
    })
    expect(selectionForFile('sounds/menu/open.ogg')).toEqual({ kind: 'sounds', key: 'menu/open' })
    expect(selectionForFile('sounds/Door Open.ogg')).toBeNull()
    expect(selectionForFile('pack.json')).toBeNull()
  })
})

describe('game textures', () => {
  it('redraws one of the game’s textures with a picture of the pack, which counts as used, and stops', () => {
    const pack: ResourcePackFile = {}
    setGameTexture(pack, 'block/stone', gameTextureCopyPath('block/stone'))
    expect(pack.textures).toEqual({ 'block/stone': 'vanilla/block/stone.png' })
    expect(unusedTextures(pack, ['vanilla/block/stone.png', 'other.png'])).toEqual(['other.png'])
    setGameTexture(pack, 'block/stone', undefined)
    expect(pack).toEqual({})
  })
})

describe('the HUD, game models and sound events', () => {
  const canonical = (pack: ResourcePackFile) =>
    JSON.parse(
      canonicalizeModel('resource_pack', 'resource_packs/ui/pack.json', pack).text!,
    ) as ResourcePackFile

  it('adds, renames and removes HUD elements, keyed across kinds, and drops an empty HUD', () => {
    const pack: ResourcePackFile = {}
    addHudElement(pack, 'bars', 'mana', newHudElement('bars', 'hud/mana.png'))
    addHudElement(pack, 'icons', 'effects', newHudElement('icons', 'hud/poison.png'))
    addHudElement(pack, 'texts', 'label', newHudElement('texts', ''))
    expect(hudKeys(pack)).toEqual(['effects', 'label', 'mana'])
    expect(hudKindOf(pack, 'effects')).toBe('icons')
    expect(canonical(pack).hud).toEqual(pack.hud)

    renameHudElement(pack, 'mana', 'label')
    expect(hudKindOf(pack, 'mana')).toBe('bars')
    renameHudElement(pack, 'mana', 'energy')
    expect(hudKindOf(pack, 'energy')).toBe('bars')

    setHudIcon(pack, 'effects', 'regen', 'hud/regen.png')
    renameHudIcon(pack, 'effects', 'icon', 'poison')
    setHudIcon(pack, 'effects', 'regen', undefined)
    setHudIcon(pack, 'effects', 'poison', undefined)
    expect(pack.hud!.icons!.effects!.icons).toEqual({ poison: 'hud/poison.png' })

    for (const key of hudKeys(pack)) removeHudElement(pack, key)
    expect('hud' in pack).toBe(false)
  })

  it('hides the game’s elements in its order and redraws its sprites', () => {
    const order = ['health', 'food', 'armor', 'air', 'experience'] as const
    const pack: ResourcePackFile = {}
    setHudHidden(pack, 'food', true, order)
    setHudHidden(pack, 'health', true, order)
    setHudReplace(pack, 'hud/hotbar', 'hud/hotbar.png')
    expect(pack.hud).toEqual({
      hide: ['health', 'food'],
      replace: { 'hud/hotbar': 'hud/hotbar.png' },
    })
    setHudHidden(pack, 'food', false, order)
    setHudHidden(pack, 'health', false, order)
    setHudReplace(pack, 'hud/hotbar', undefined)
    expect('hud' in pack).toBe(false)
  })

  it('redraws the game’s models and adds and removes sound events', () => {
    const pack: ResourcePackFile = {}
    setGameModel(pack, 'block/lantern', 'lantern.json')
    expect(pack.models).toEqual({ 'block/lantern': 'lantern.json' })
    setGameModel(pack, 'block/lantern', undefined)
    expect('models' in pack).toBe(false)

    addSoundEntry(pack, 'minecraft:block.stone.break', ['blocks/break_1.ogg'])
    addSoundEntry(pack, 'step', [])
    expect(pack.sounds).toEqual({
      'minecraft:block.stone.break': { files: ['blocks/break_1.ogg'] },
      step: {},
    })
    removeSoundEntry(pack, 'step')
    removeSoundEntry(pack, 'minecraft:block.stone.break')
    expect('sounds' in pack).toBe(false)
  })
})
