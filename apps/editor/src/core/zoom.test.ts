import { describe, expect, it } from 'vitest'
import type { LayoutStorage } from './store/layout'
import { DEFAULT_ZOOM, MAX_ZOOM, MIN_ZOOM, loadZoom, saveZoom, stepZoom } from './zoom'

function memoryStorage(): LayoutStorage {
  const map = new Map<string, string>()
  return { get: (key) => map.get(key) ?? null, set: (key, value) => void map.set(key, value) }
}

describe('stepZoom', () => {
  it('moves one level each way', () => {
    expect(stepZoom(1, 1)).toBe(1.1)
    expect(stepZoom(1, -1)).toBe(0.9)
    expect(stepZoom(1.25, -1)).toBe(1.1)
  })

  it('stays put at either end', () => {
    expect(stepZoom(MIN_ZOOM, -1)).toBe(MIN_ZOOM)
    expect(stepZoom(MAX_ZOOM, 1)).toBe(MAX_ZOOM)
  })

  it('moves a zoom between levels to the nearest level that way', () => {
    expect(stepZoom(1.2, 1)).toBe(1.25)
    expect(stepZoom(1.2, -1)).toBe(1.1)
  })
})

describe('loadZoom', () => {
  it('reads back what was saved', () => {
    const storage = memoryStorage()
    saveZoom(storage, 1.25)
    expect(loadZoom(storage)).toBe(1.25)
  })

  it('falls back to actual size for nothing, junk or out of range', () => {
    const storage = memoryStorage()
    expect(loadZoom(storage)).toBe(DEFAULT_ZOOM)
    for (const junk of ['abc', '0', '-1', '9', 'Infinity']) {
      storage.set('netherforge.zoom', junk)
      expect(loadZoom(storage)).toBe(DEFAULT_ZOOM)
    }
  })
})
