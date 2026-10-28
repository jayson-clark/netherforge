import { describe, expect, it } from 'vitest'
import type { MenuFile } from '@/core/format'
import {
  filledSlots,
  moveSlot,
  positionOf,
  setShape,
  setSlot,
  slotAt,
  slotLabel,
  slotsOutside,
} from './slots'
import { shapeOf } from '@/minecraft/window/window'

const shop = (): MenuFile => ({
  rows: 3,
  slots: {
    '11': { item: { kind: 'minecraft:bread', count: 4 } },
    '13': { item: { kind: 'minecraft:paper', data: { sold_out: true } } },
  },
})

describe('menu slots', () => {
  it('knows each type and clamps chest rows like format', () => {
    expect(shapeOf({})).toEqual({ type: 'chest', columns: 9, rows: 3, size: 27 })
    expect(shapeOf({ rows: 6 }).size).toBe(54)
    expect(shapeOf({ rows: 9 }).rows).toBe(6)
    expect(shapeOf({ type: 'hopper' })).toEqual({ type: 'hopper', columns: 5, rows: 1, size: 5 })
    expect(shapeOf({ type: 'dropper', rows: 5 })).toMatchObject({ columns: 3, rows: 3, size: 9 })
  })

  it('maps indices to grid positions and back', () => {
    const shape = shapeOf({ type: 'dispenser' })
    expect(positionOf(shape, 5)).toEqual({ column: 2, row: 1 })
    expect(slotAt(shape, 2, 1)).toBe(5)
    expect(slotAt(shapeOf({}), 4, 1)).toBe(13)
  })

  it('moves onto an empty square and swaps with a full one', () => {
    const file = shop()
    moveSlot(file, 11, 0)
    expect(Object.keys(file.slots!).sort()).toEqual(['0', '13'])
    expect(file.slots!['0']!.item!.kind).toBe('minecraft:bread')
    moveSlot(file, 0, 13)
    expect(file.slots!['13']!.item!.kind).toBe('minecraft:bread')
    // The whole item travels with its square, script data included.
    expect(file.slots!['0']).toEqual({
      item: { kind: 'minecraft:paper', data: { sold_out: true } },
    })
    moveSlot(file, 13, 13)
    expect(file.slots!['13']!.item!.kind).toBe('minecraft:bread')
  })

  it('drops empty slots and an empty slots map', () => {
    const file: MenuFile = { slots: { '1': { item: { kind: 'stone' } } } }
    setSlot(file, 1, {})
    expect(file.slots).toBeUndefined()
  })

  it('changes shape without losing slots, and lists the ones outside', () => {
    const file = shop()
    setShape(file, 'hopper')
    expect(file).toMatchObject({ type: 'hopper' })
    expect(file.rows).toBeUndefined()
    expect(slotsOutside(file)).toEqual([11, 13])
    setShape(file, 'chest', 2)
    expect(file.type).toBeUndefined()
    expect(file.rows).toBe(2)
    expect(slotsOutside(file)).toEqual([])
  })
})

describe('slot lists', () => {
  it('lists the filled slots in numeric order', () => {
    const file: MenuFile = {
      slots: { '10': { item: { kind: 'a' } }, '2': { item: { kind: 'b' } } },
    }
    expect(filledSlots(file)).toEqual([2, 10])
    expect(filledSlots({})).toEqual([])
  })

  it('names a slot by its item name, project item or kind', () => {
    expect(slotLabel(13, { item: { kind: 'minecraft:bread', name: '<gold>Fresh bread' } })).toBe(
      '13 · Fresh bread',
    )
    expect(slotLabel(1, { item: { item: 'ruby' } })).toBe('1 · ruby')
    expect(slotLabel(4, { item: { kind: 'minecraft:stone' } })).toBe('4 · minecraft:stone')
    expect(slotLabel(5, undefined)).toBe('5 · empty')
  })
})
