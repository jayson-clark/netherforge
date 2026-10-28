import { describe, expect, it } from 'vitest'
import type { CentityFile } from '@/core/format'
import {
  formatList,
  parseList,
  setKeepOnInteract,
  setList,
  setNumber,
  setCondition,
  setRangeEnd,
  setSpawning,
  setTimeEnd,
  setTimeMode,
  timeEnd,
  timeMode,
  toggleChoice,
} from './spawning'

const file = (): CentityFile => ({ nodes: { root: {} } })

describe("a centity's spawning edits", () => {
  it('turns the block on empty and off again', () => {
    const f = file()
    setSpawning(f, true)
    expect(f.spawning).toEqual({})
    setSpawning(f, true)
    expect(f.spawning).toEqual({})
    setSpawning(f, false)
    expect('spawning' in f).toBe(false)
  })

  it('reads typed lists in any separator, and an empty box removes the key', () => {
    expect(parseList('a, b  c\nd,,')).toEqual(['a', 'b', 'c', 'd'])
    expect(parseList('  ,, ')).toBeUndefined()
    expect(formatList(['x', '#y'])).toBe('x, #y')
    expect(formatList(undefined)).toBe('')

    const f = file()
    setSpawning(f, true)
    setList(f, 'biomes', 'minecraft:plains, #minecraft:is_forest')
    expect(f.spawning?.biomes).toEqual(['minecraft:plains', '#minecraft:is_forest'])
    setList(f, 'biomes', '')
    expect(f.spawning).toEqual({})
  })

  it('sets whole numbers, and clearing one removes it', () => {
    const f = file()
    setSpawning(f, true)
    setNumber(f, 'weight', 4.6)
    expect(f.spawning?.weight).toBe(5)
    setNumber(f, 'weight', undefined)
    expect(f.spawning).toEqual({})
  })

  it('keeps a range while either end is set, and drops it with the last', () => {
    const f = file()
    setSpawning(f, true)
    setRangeEnd(f, 'light', 'max', 7)
    expect(f.spawning?.light).toEqual({ max: 7 })
    setRangeEnd(f, 'light', 'min', 2)
    expect(f.spawning?.light).toEqual({ max: 7, min: 2 })
    setRangeEnd(f, 'light', 'max', undefined)
    setRangeEnd(f, 'light', 'min', undefined)
    expect(f.spawning).toEqual({})
  })

  it('turns interaction keeping on, and off by dropping the key', () => {
    const f = file()
    setSpawning(f, true)
    setKeepOnInteract(f, true)
    expect(f.spawning).toEqual({ keepOnInteract: true })
    setKeepOnInteract(f, false)
    expect(f.spawning).toEqual({})
  })

  it('does nothing without a spawning block', () => {
    const f = file()
    setList(f, 'worlds', 'a')
    setNumber(f, 'cap', 3)
    setRangeEnd(f, 'group', 'min', 2)
    setKeepOnInteract(f, true)
    expect(f).toEqual(file())
  })

  it('gives the time as a half of the day or a range of ticks, and clears it', () => {
    const f = file()
    setSpawning(f, true)
    expect(timeMode(f.spawning?.time)).toBe('any')
    setTimeMode(f, 'night')
    expect(f.spawning?.time).toBe('night')
    setTimeMode(f, 'range')
    expect(f.spawning?.time).toEqual({ min: 13000, max: 22999 })
    expect(timeMode(f.spawning?.time)).toBe('range')
    setTimeEnd(f, 'max', 2000.4)
    expect(timeEnd(f.spawning?.time, 'max')).toBe(2000)
    setTimeEnd(f, 'min', undefined)
    setTimeEnd(f, 'max', undefined)
    expect(f.spawning).toEqual({})
    setTimeMode(f, 'day')
    setTimeMode(f, 'any')
    expect(f.spawning).toEqual({})
  })

  it('toggles moon phases and weather, and an empty list goes', () => {
    const f = file()
    setSpawning(f, true)
    toggleChoice(f, 'moonPhase', 'full_moon', true)
    toggleChoice(f, 'moonPhase', 'new_moon', true)
    toggleChoice(f, 'moonPhase', 'full_moon', true)
    expect(f.spawning?.moonPhase).toEqual(['full_moon', 'new_moon'])
    toggleChoice(f, 'weather', 'rain', true)
    toggleChoice(f, 'weather', 'rain', false)
    toggleChoice(f, 'moonPhase', 'full_moon', false)
    toggleChoice(f, 'moonPhase', 'new_moon', false)
    expect(f.spawning).toEqual({})
  })

  it('names a script condition, and an empty box removes it', () => {
    const f = file()
    setSpawning(f, true)
    setCondition(f, ' blood_moon ')
    expect(f.spawning?.when).toBe('blood_moon')
    setCondition(f, '')
    expect(f.spawning).toEqual({})
  })
})
