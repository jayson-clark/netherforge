import { describe, expect, it } from 'vitest'
import { stepTab } from './tabs'

describe('stepTab', () => {
  const tabs = ['a', 'b', 'c']

  it('moves through the strip in order and wraps round at either end', () => {
    expect(stepTab(tabs, 'a', 1)).toBe('b')
    expect(stepTab(tabs, 'c', 1)).toBe('a')
    expect(stepTab(tabs, 'b', -1)).toBe('a')
    expect(stepTab(tabs, 'a', -1)).toBe('c')
  })

  it('starts at an end when no tab is active, and goes nowhere with one tab or none', () => {
    expect(stepTab(tabs, null, 1)).toBe('a')
    expect(stepTab(tabs, null, -1)).toBe('c')
    expect(stepTab(['a'], 'a', 1)).toBeNull()
    expect(stepTab([], null, 1)).toBeNull()
  })
})
