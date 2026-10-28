import { setKey } from '@/core/draft'
import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type MenuFile, type ItemDef, type ItemFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  addModifier,
  blocksFromText,
  blocksToText,
  freeEnchantmentId,
  hasGlint,
  itemLabel,
  loreFromText,
  loreToText,
  resourcePackRefs,
  removeEnchantment,
  removeModifier,
  setCooldown,
  setEnchantment,
  setEquipment,
  setFood,
  setHold,
  holdAnimation,
  setModifier,
  setProjectItem,
  setUseEffects,
  splitResourcePackRef,
  stackLook,
} from './item'

const SHOP = 'menus/shop/menu.json'

describe('item form', () => {
  it('sets keys and leaves cleared ones out', () => {
    const item: ItemDef = { kind: 'minecraft:bread', count: 4 }
    setKey(item, 'count', undefined)
    setKey(item, 'name', '')
    setKey(item, 'glint', false)
    expect(item).toEqual({ kind: 'minecraft:bread', glint: false })
  })

  it('edits lore as lines', () => {
    expect(loreFromText('a\nb\n\n')).toEqual(['a', 'b'])
    expect(loreFromText('  \n')).toBeUndefined()
    expect(loreToText(['a', 'b'])).toBe('a\nb')
    expect(loreFromText(loreToText(['x', '', 'y']))).toEqual(['x', '', 'y'])
  })

  it('adds, renames and removes enchantments in place', () => {
    const item: ItemDef = { kind: 'x' }
    setEnchantment(item, freeEnchantmentId(item), 1)
    setEnchantment(item, 'minecraft:unbreaking', 3)
    expect(Object.keys(item.enchantments!)).toEqual(['enchantment', 'minecraft:unbreaking'])
    setEnchantment(item, 'minecraft:sharpness', 5, 'enchantment')
    expect(item.enchantments).toEqual({ 'minecraft:sharpness': 5, 'minecraft:unbreaking': 3 })
    expect(hasGlint(item)).toBe(true)
    removeEnchantment(item, 'minecraft:sharpness')
    removeEnchantment(item, 'minecraft:unbreaking')
    expect(item.enchantments).toBeUndefined()
    expect(hasGlint({ kind: 'x', glint: true })).toBe(true)
    expect(hasGlint({ kind: 'x', glint: false, enchantments: { a: 1 } })).toBe(false)
  })

  it('lists pack references and splits them', () => {
    const packs = {
      ui: {
        skins: ['shop'],
        glyphs: [],
        items: ['ruby', 'gem'],
        tooltips: [],
        equipment: ['ruby'],
        blocks: [],
        sounds: [],
      },
      art: {
        skins: [],
        glyphs: [],
        items: ['axe'],
        tooltips: [],
        equipment: [],
        blocks: [],
        sounds: [],
      },
    }
    expect(resourcePackRefs(packs, 'items')).toEqual(['art/axe', 'ui/gem', 'ui/ruby'])
    expect(splitResourcePackRef('item_model', 'ui/ruby', 'shop')).toEqual(['ui', 'ruby'])
    expect(splitResourcePackRef('item_model', 'shop:ui/ruby', 'shop')).toEqual(['ui', 'ruby'])
    // A package's entry: its pack as the packs store keys it, `ns:id`.
    expect(splitResourcePackRef('item_model', 'acme:ui/ruby', 'shop')).toEqual(['acme:ui', 'ruby'])
    // Written in a package's document: its own packs bare, the project's keyed bare too.
    expect(splitResourcePackRef('item_model', 'ui/ruby', 'acme', 'shop')).toEqual([
      'acme:ui',
      'ruby',
    ])
    expect(splitResourcePackRef('item_model', 'shop:ui/ruby', 'acme', 'shop')).toEqual([
      'ui',
      'ruby',
    ])
    expect(splitResourcePackRef('item_model', 'ruby', 'shop')).toBeNull()
    expect(splitResourcePackRef('item_model', 'ui:ruby', 'shop')).toBeNull()
    expect(itemLabel('minecraft:diamond_sword')).toBe('diamond sword')
  })

  it('round-trips the example slot through the form to canonical bytes', () => {
    const file = JSON.parse(exampleFiles[SHOP]!) as MenuFile
    const item = file.slots!['11']!.item!
    // Edit and undo every field the form has: the file must come back byte for byte.
    setKey(item, 'count', 9)
    setKey(item, 'unbreakable', true)
    setEnchantment(item, 'minecraft:mending', 1)
    setKey(item, 'lore', loreFromText(loreToText(item.lore)))
    setKey(item, 'count', 4)
    setKey(item, 'unbreakable', undefined)
    removeEnchantment(item, 'minecraft:mending')
    expect(canonicalizeModel('menu', SHOP, file).text).toBe(exampleFiles[SHOP])
  })
})

describe('item components', () => {
  it('reads block lists one per line', () => {
    expect(blocksFromText(' minecraft:stone \n\nminecraft:dirt\n')).toEqual([
      'minecraft:stone',
      'minecraft:dirt',
    ])
    expect(blocksFromText('  \n')).toBeUndefined()
    expect(blocksToText(['a', 'b'])).toBe('a\nb')
  })

  it('adds, edits and removes attribute modifiers, the list going with the last', () => {
    const item: ItemDef = { kind: 'minecraft:diamond_sword' }
    addModifier(item)
    setModifier(item, 0, 'amount', 6)
    setModifier(item, 0, 'slot', 'main_hand')
    setModifier(item, 0, 'id', 'shop:sharp')
    expect(item.attributeModifiers).toEqual([
      {
        attribute: 'minecraft:attack_damage',
        amount: 6,
        operation: 'add_value',
        slot: 'main_hand',
        id: 'shop:sharp',
      },
    ])
    setModifier(item, 0, 'slot', undefined)
    setModifier(item, 0, 'id', '')
    expect(item.attributeModifiers![0]).toEqual({
      attribute: 'minecraft:attack_damage',
      amount: 6,
      operation: 'add_value',
    })
    removeModifier(item, 0)
    expect(item.attributeModifiers).toBeUndefined()
  })

  it('starts food with nutrition and drops it when nutrition is cleared', () => {
    const item: ItemDef = { kind: 'minecraft:paper' }
    setFood(item, 'saturation', 2)
    expect(item.food).toBeUndefined()
    setFood(item, 'nutrition', 4)
    setFood(item, 'saturation', 2.5)
    setFood(item, 'canAlwaysEat', true)
    expect(item.food).toEqual({ nutrition: 4, saturation: 2.5, canAlwaysEat: true })
    setFood(item, 'canAlwaysEat', false)
    expect(item.food).toEqual({ nutrition: 4, saturation: 2.5 })
    setFood(item, 'nutrition', undefined)
    expect(item.food).toBeUndefined()
  })

  it('wears an item once it has an equipment look; the slot needs one', () => {
    const item: ItemDef = { kind: 'minecraft:leather_helmet' }
    setEquipment(item, 'slot', 'legs')
    expect(item.equipment).toBeUndefined()
    setEquipment(item, 'asset', 'gear/ruby')
    expect(item.equipment).toEqual({ asset: 'gear/ruby', slot: 'head' })
    setEquipment(item, 'slot', 'legs')
    setEquipment(item, 'asset', 'gear/other')
    expect(item.equipment).toEqual({ asset: 'gear/other', slot: 'legs' })
    setEquipment(item, 'asset', undefined)
    expect(item.equipment).toBeUndefined()
  })

  it('starts a cooldown with seconds; a group needs one', () => {
    const item: ItemDef = { kind: 'minecraft:ender_pearl' }
    setCooldown(item, 'group', 'shop:pearls')
    expect(item.cooldown).toBeUndefined()
    setCooldown(item, 'seconds', 2)
    setCooldown(item, 'group', 'shop:pearls')
    expect(item.cooldown).toEqual({ seconds: 2, group: 'shop:pearls' })
    setCooldown(item, 'group', '')
    expect(item.cooldown).toEqual({ seconds: 2 })
    setCooldown(item, 'seconds', undefined)
    expect(item.cooldown).toBeUndefined()
  })

  it('sets use effects field by field, and drops the table once it is empty', () => {
    const item: ItemDef = { kind: 'minecraft:stick', hold: true }
    setUseEffects(item, 'canSprint', false)
    setUseEffects(item, 'speedMultiplier', 0.5)
    expect(item.useEffects).toEqual({ canSprint: false, speedMultiplier: 0.5 })
    setUseEffects(item, 'canSprint', undefined)
    expect(item.useEffects).toEqual({ speedMultiplier: 0.5 })
    setUseEffects(item, 'speedMultiplier', undefined)
    expect(item).toEqual({ kind: 'minecraft:stick', hold: true })
  })

  it('turns hold on and off, writing a pose as an object and none as true', () => {
    const item: ItemDef = { kind: 'minecraft:stick' }
    setHold(item, true)
    expect(item.hold).toBe(true)
    expect(holdAnimation(item)).toBe('none')
    setHold(item, true, 'trident')
    expect(item.hold).toEqual({ animation: 'trident' })
    expect(holdAnimation(item)).toBe('trident')
    setHold(item, false)
    expect(item.hold).toBeUndefined()
    setHold(item, true, 'bow')
    setHold(item, true, 'none')
    expect(item.hold).toBe(true)
  })

  it('makes a stack a project item, leaving its kind to the definition', () => {
    const item: ItemDef = { kind: 'minecraft:paper', count: 3 }
    setProjectItem(item, 'ruby')
    expect(item).toEqual({ item: 'ruby', count: 3 })
    setProjectItem(item, undefined)
    expect(item).toEqual({ count: 3 })
  })

  it("draws a project item's stack with its definition's look under the stack's own fields", () => {
    const ruby: ItemFile = {
      $schema: '../../.netherforge/schema/item.schema.json',
      kind: 'minecraft:paper',
      name: '<red>Ruby',
      itemModel: 'ui:ruby',
      script: { file: 'script.lua' },
    }
    expect(stackLook({ item: 'ruby', count: 2, name: 'Shiny' }, ruby)).toEqual({
      kind: 'minecraft:paper',
      item: 'ruby',
      count: 2,
      name: 'Shiny',
      itemModel: 'ui:ruby',
    })
    // A kind on the stack never wins: a stack of a project item is always its kind.
    expect(stackLook({ item: 'ruby', kind: 'minecraft:stone' }, ruby).kind).toBe('minecraft:paper')
    // No definition (or none named): shown as it is.
    expect(stackLook({ item: 'gem' }, null)).toEqual({ item: 'gem' })
    expect(stackLook({ kind: 'minecraft:stick' }, ruby)).toEqual({ kind: 'minecraft:stick' })
  })
})
