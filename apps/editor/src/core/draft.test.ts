import { describe, expect, it } from 'vitest'
import { setKey } from './draft'

interface Thing {
  name?: string
  count?: number
  on?: boolean
}

describe('setKey', () => {
  it('sets a value, including falsy ones that mean something', () => {
    const thing: Thing = {}
    setKey(thing, 'name', 'tower')
    setKey(thing, 'count', 0)
    setKey(thing, 'on', false)
    expect(thing).toEqual({ name: 'tower', count: 0, on: false })
  })

  it('deletes the key for undefined or an empty string, so the file leaves it out', () => {
    const thing: Thing = { name: 'tower', count: 2 }
    setKey(thing, 'name', '')
    setKey(thing, 'count', undefined)
    expect(thing).toEqual({})
    expect('name' in thing).toBe(false)
  })
})
