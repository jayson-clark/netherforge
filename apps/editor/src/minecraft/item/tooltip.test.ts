import { describe, expect, it } from 'vitest'
import { enchantmentLine, tooltipOf } from './tooltip'

describe('tooltipOf', () => {
  it("names a plain item by its id, in its rarity's colour", () => {
    expect(tooltipOf({ kind: 'minecraft:diamond_sword' })).toEqual([
      { text: 'Diamond Sword', style: { color: '#ffffff' } },
    ])
    expect(tooltipOf({ kind: 'minecraft:nether_star', rarity: 'epic' })?.[0]?.style.color).toBe(
      '#ff55ff',
    )
  })

  it('draws a custom name in italics, then enchantments, unbreakable and lore', () => {
    const lines = tooltipOf({
      kind: 'minecraft:stick',
      name: '<gold>Wand',
      enchantments: { 'minecraft:sharpness': 5, 'minecraft:knockback': 12 },
      unbreakable: true,
      lore: ['A stick', 'of power'],
    })!
    expect(lines.map((it) => it.text)).toEqual([
      '<gold>Wand',
      'Sharpness V',
      'Knockback 12',
      'Unbreakable',
      'A stick',
      'of power',
    ])
    expect(lines[0]!.style.italic).toBe(true)
    expect(lines[4]!.style).toEqual({ color: '#aa00aa', italic: true })
  })

  it('leaves out the lines the item hides, and only those', () => {
    const lines = tooltipOf({
      kind: 'minecraft:stick',
      enchantments: { 'minecraft:sharpness': 5 },
      unbreakable: true,
      lore: ['A stick'],
      hiddenTooltipComponents: ['unbreakable', 'attribute_modifiers'],
    })!
    expect(lines.map((it) => it.text)).toEqual(['Stick', 'Sharpness V', 'A stick'])
  })

  it('has no tooltip when the item hides it', () => {
    expect(tooltipOf({ kind: 'minecraft:stone', hideTooltip: true })).toBeNull()
  })

  it('writes levels as the game does', () => {
    expect(enchantmentLine('minecraft:fire_aspect', 2)).toBe('Fire Aspect II')
  })
})
