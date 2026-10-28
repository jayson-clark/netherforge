import { describe, expect, it } from 'vitest'
import { ConsoleBuffer } from './consoleBuffer'

const line = (id: number) => ({ id })

describe('the console buffer', () => {
  it('keeps the newest lines once full, oldest first', () => {
    const buffer = new ConsoleBuffer(3)
    expect(buffer.lines()).toEqual([])
    expect(buffer.last).toBeUndefined()
    for (let id = 1; id <= 5; id += 1) buffer.push(line(id))
    expect(buffer.lines()).toEqual([line(3), line(4), line(5)])
    expect(buffer.size).toBe(3)
    expect(buffer.last).toEqual(line(5))
  })

  it('hands out the same snapshot until something changes', () => {
    const buffer = new ConsoleBuffer(3)
    buffer.push(line(1))
    const first = buffer.lines()
    expect(buffer.lines()).toBe(first)
    buffer.push(line(2))
    expect(buffer.lines()).not.toBe(first)
    expect(first).toEqual([line(1)])
  })

  it('finds what came after a line, even one that has dropped out', () => {
    const buffer = new ConsoleBuffer(4)
    for (let id = 1; id <= 6; id += 1) buffer.push(line(id))
    expect(buffer.since(4)).toEqual([line(5), line(6)])
    expect(buffer.since(0)).toEqual([line(3), line(4), line(5), line(6)])
    expect(buffer.since(6)).toEqual([])
    buffer.clear()
    expect(buffer.since(0)).toEqual([])
  })
})
