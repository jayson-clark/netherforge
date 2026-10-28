import { describe, expect, it } from 'vitest'
import { putKey, moveKey, removeKey, setEasing, type TimedKey } from './keys'
import { formatTime, fractionOf, rulerMarks, snap, stepOf } from './time'

describe('time on a timeline', () => {
  it('snaps to game ticks, in ticks or seconds, never before 0', () => {
    expect(snap('ticks', 9.4)).toBe(9)
    expect(snap('ticks', -3)).toBe(0)
    expect(snap('seconds', 0.512)).toBe(0.5)
    expect(snap('seconds', 0.53)).toBe(0.55)
    expect(stepOf('ticks')).toBe(1)
    expect(stepOf('seconds')).toBe(0.05)
  })

  it('labels the ruler about ten times, a round step apart', () => {
    expect(rulerMarks({ unit: 'ticks', length: 20 })).toEqual([
      0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20,
    ])
    expect(rulerMarks({ unit: 'ticks', length: 100 })).toEqual([
      0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
    ])
    expect(rulerMarks({ unit: 'seconds', length: 2 })).toEqual([
      0, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2,
    ])
    expect(rulerMarks({ unit: 'seconds', length: 1 })).toEqual([
      0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1,
    ])
  })

  it('writes times in their unit, and places them across the timeline', () => {
    expect(formatTime('seconds', 0.5)).toBe('0.5s')
    expect(formatTime('ticks', 20)).toBe('tick 20')
    expect(fractionOf({ unit: 'ticks', length: 20 }, 5)).toBe(0.25)
    expect(fractionOf({ unit: 'ticks', length: 20 }, 40)).toBe(1)
  })
})

interface Key extends TimedKey {
  value: number
}

describe('a track of keys', () => {
  it('stays in time order, and a key at a time that has one replaces it', () => {
    const keys: Key[] = [{ time: 0, value: 1 }]
    expect(putKey(keys, { time: 2, value: 3 })).toBe(1)
    expect(putKey(keys, { time: 1, value: 2 })).toBe(1)
    const merge = (it: Key): Key => ({ ...it, value: 9 })
    expect(putKey(keys, { time: 1.00001, value: 5 }, { same: 1e-4, merge })).toBe(1)
    expect(keys).toEqual([
      { time: 0, value: 1 },
      { time: 1, value: 9 },
      { time: 2, value: 3 },
    ])
  })

  it('finds a moved key again after the re-sort, and removes one', () => {
    const keys: Key[] = [
      { time: 0, value: 1 },
      { time: 1, value: 2 },
      { time: 2, value: 3 },
    ]
    expect(moveKey(keys, 0, 1.5)).toBe(1)
    expect(keys.map((it) => it.value)).toEqual([2, 1, 3])
    expect(moveKey(keys, 7, 0)).toBe(-1)
    expect(removeKey(keys, 0)).toEqual({ time: 1, value: 2 })
    expect(removeKey(keys, 5)).toBeUndefined()
    expect(keys).toHaveLength(2)
  })

  it('writes linear easing by leaving it out', () => {
    const key: Key = { time: 0, value: 1 }
    setEasing(key, 'ease_in')
    expect(key.easing).toBe('ease_in')
    setEasing(key, 'linear')
    expect('easing' in key).toBe(false)
  })
})
