import { produce } from 'immer'
import { describe, expect, it } from 'vitest'
import { canonicalizeModel, rollLoot, type LootTableFile } from '@/core/format'
import {
  addCondition,
  addEntry,
  addPool,
  conditionLabel,
  duplicatePool,
  entryLabel,
  lootItems,
  moveEntry,
  newCondition,
  newEntry,
  poolNames,
  rangeEnds,
  rangeLabel,
  rangeOf,
  removeCondition,
  removeEntry,
  removePool,
  renamePool,
  shares,
} from './ops'

const table: LootTableFile = {
  pools: {
    main: {
      entries: [{ type: 'item', item: { kind: 'minecraft:stick' }, weight: 3 }, { type: 'empty' }],
    },
  },
}

describe('loot table ops', () => {
  it('adds, renames, duplicates and removes pools by name', () => {
    let name = ''
    let next = produce(table, (draft) => {
      name = addPool(draft)
    })
    expect(name).toBe('pool')
    expect(poolNames(next)).toEqual(['main', 'pool'])
    next = produce(next, (draft) => renamePool(draft, 'pool', 'bonus'))
    expect(poolNames(next)).toEqual(['bonus', 'main'])
    next = produce(next, (draft) => renamePool(draft, 'bonus', 'main'))
    // A taken name renames nothing.
    expect(poolNames(next)).toEqual(['bonus', 'main'])
    let copy: string | null = null
    next = produce(next, (draft) => {
      copy = duplicatePool(draft, 'main')
    })
    expect(copy).toBe('main_copy')
    expect(next.pools!.main_copy).toEqual(next.pools!.main)
    next = produce(next, (draft) => removePool(draft, 'main_copy'))
    expect(poolNames(next)).toEqual(['bonus', 'main'])
  })

  it('adds, moves and removes entries, leaving no empty list behind', () => {
    let index = -1
    let next = produce(table, (draft) => {
      index = addEntry(draft, 'main', newEntry('table', 'junk'))
    })
    expect(index).toBe(2)
    expect(next.pools!.main!.entries![2]).toEqual({ type: 'table', table: 'junk' })
    next = produce(next, (draft) => moveEntry(draft, 'main', 2, 0))
    expect(next.pools!.main!.entries!.map((it) => it.type)).toEqual(['table', 'item', 'empty'])
    next = produce(next, (draft) => {
      removeEntry(draft, 'main', 0)
      removeEntry(draft, 'main', 0)
      removeEntry(draft, 'main', 0)
    })
    expect(next.pools!.main).toEqual({})
  })

  it('writes ranges in their shortest form, leaving the default out', () => {
    expect(rangeOf(1, 1, 1)).toBeUndefined()
    expect(rangeOf(2, 2, 1)).toBe(2)
    expect(rangeOf(1, 3, 1)).toEqual({ min: 1, max: 3 })
    expect(rangeEnds(undefined, 1)).toEqual({ min: 1, max: 1 })
    expect(rangeEnds({ min: 1, max: 4 }, 1)).toEqual({ min: 1, max: 4 })
    expect(rangeLabel({ min: 1, max: 4 }, 1)).toBe('1–4')
    // The canonical writer agrees: a number stays a number.
    const written = canonicalizeModel('loot_table', 'loot/x.json', {
      pools: { main: { rolls: 2, entries: [{ type: 'empty' }] } },
    })
    expect(written.text).toContain('"rolls": 2')
  })

  it("says each entry's share of a pick by its weight", () => {
    expect(shares(table.pools!.main!)).toEqual([0.75, 0.25])
    expect(shares({})).toEqual([])
  })

  it('adds and removes conditions on a pool or an entry', () => {
    let next = produce(table, (draft) => {
      addCondition(draft, { pool: 'main' }, newCondition('player'))
      addCondition(draft, { pool: 'main', entry: 0 }, {
        ...newCondition('chance'),
        invert: true,
      } as never)
    })
    expect(next.pools!.main!.conditions).toEqual([{ type: 'player' }])
    expect(conditionLabel(next.pools!.main!.entries![0]!.conditions![0]!)).toBe('unless 50% chance')
    next = produce(next, (draft) => removeCondition(draft, { pool: 'main' }, 0))
    expect(next.pools!.main!.conditions).toBeUndefined()
  })

  it('labels entries by what they give', () => {
    expect(entryLabel({ type: 'item', item: { item: 'ruby' } })).toBe('ruby')
    expect(entryLabel({ type: 'vanilla', table: 'minecraft:chests/simple_dungeon' })).toBe(
      'minecraft:chests/simple_dungeon',
    )
    expect(entryLabel({ type: 'empty' })).toBe('Nothing')
  })

  it("previews a roll with format's roller, the same for the same seed", () => {
    const texts = {
      treasure: JSON.stringify({
        pools: { main: { rolls: 3, entries: [{ type: 'table', table: 'junk' }] } },
      }),
      junk: JSON.stringify({
        pools: {
          main: {
            entries: [
              { type: 'item', item: { kind: 'minecraft:stick' }, count: { min: 1, max: 5 } },
            ],
          },
        },
      }),
    }
    const tables = { 'test:treasure': texts.treasure, 'test:junk': texts.junk }
    const first = rollLoot(tables, 'test:treasure', 4)
    expect(first.error).toBeUndefined()
    expect(first.drops).toHaveLength(3)
    expect(first.drops.every((it) => it.item?.kind === 'minecraft:stick')).toBe(true)
    expect(rollLoot(tables, 'test:treasure', 4)).toEqual(first)
    expect(rollLoot({ 'test:treasure': texts.treasure }, 'test:treasure', 4).error).toContain(
      'junk',
    )
  })

  it("rolls a package's table in its own names, from the project's or its own", () => {
    const tables = {
      'test:treasure': JSON.stringify({
        pools: { main: { entries: [{ type: 'table', table: 'lib:gems' }] } },
      }),
      // The library's own names, bare: its gem, its private table.
      'lib:gems': JSON.stringify({
        pools: {
          main: { entries: [{ type: 'item', item: { item: 'gem', name: '<glyph:gems/gem>' } }] },
          more: { entries: [{ type: 'table', table: 'hidden' }] },
        },
      }),
      'lib:hidden': JSON.stringify({
        pools: { main: { entries: [{ type: 'item', item: { item: 'coin' } }] } },
      }),
    }
    const rolled = rollLoot(tables, 'test:treasure', 1)
    expect(rolled.error).toBeUndefined()
    expect(rolled.drops.map((it) => it.item?.item)).toEqual(['lib:gem', 'lib:coin'])
    expect(rolled.drops[0]!.item?.name).toBe('<glyph:lib:gems/gem>')
    expect(rollLoot(tables, 'lib:gems', 1)).toEqual(rolled)
  })
})

describe('lootItems', () => {
  const chest: LootTableFile = {
    pools: {
      b: {
        entries: [
          { type: 'item', item: { kind: 'minecraft:torch', count: 4 } },
          { type: 'table', table: 'gems' },
          { type: 'vanilla', table: 'minecraft:chests/simple_dungeon' },
        ],
      },
      a: {
        entries: [
          { type: 'item', item: { kind: 'minecraft:bread' } },
          { type: 'item', item: { kind: 'minecraft:torch', name: 'Torch' } },
        ],
      },
    },
  }
  const gems: LootTableFile = {
    pools: {
      main: {
        entries: [
          { type: 'item', item: { item: 'ruby' } },
          { type: 'table', table: 'gems' },
        ],
      },
    },
  }
  const read = (name: string) => (name === 'gems' ? gems : undefined)

  it("gives each item once, in roll order, through the project's tables", () => {
    expect(lootItems(chest, read)).toEqual([
      { kind: 'minecraft:bread' },
      { kind: 'minecraft:torch' },
      { item: 'ruby' },
    ])
  })

  it('stops at the limit', () => {
    expect(lootItems(chest, read, 2)).toHaveLength(2)
  })
})
