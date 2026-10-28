import { describe, expect, it } from 'vitest'
import type { ScreenFontFile } from '@/core/format'
import {
  addGlyph,
  glyphOrder,
  heightOf,
  removeGlyph,
  setHeight,
  setPixel,
  setType,
  setWidth,
} from './ops'

const font = (): ScreenFontFile => ({
  type: 'pixels',
  glyphs: { B: ['#.', '.#'], A: ['..', '..'] },
})

describe("the screen font editor's ops", () => {
  it('lists glyphs by code point, and adds one as tall as the rest', () => {
    const d = font()
    expect(glyphOrder(d)).toEqual(['A', 'B'])
    addGlyph(d, 'C', 3)
    expect(d.glyphs?.C).toEqual(['...', '...'])
    // One character only, and not twice.
    addGlyph(d, 'DE')
    addGlyph(d, 'A', 9)
    expect(Object.keys(d.glyphs ?? {}).sort()).toEqual(['A', 'B', 'C'])
    removeGlyph(d, 'C')
    expect(d.glyphs).not.toHaveProperty('C')
  })

  it('turns pixels on and off, and resizes glyphs', () => {
    const d = font()
    setPixel(d, 'A', 1, 0, true)
    setPixel(d, 'B', 0, 0, false)
    setPixel(d, 'A', 5, 5, true)
    expect(d.glyphs).toEqual({ A: ['.#', '..'], B: ['..', '.#'] })
    setWidth(d, 'A', 3)
    expect(d.glyphs?.A).toEqual(['.#.', '...'])
    setWidth(d, 'A', 1)
    expect(d.glyphs?.A).toEqual(['.', '.'])
    setHeight(d, 3)
    expect(heightOf(d)).toBe(3)
    expect(d.glyphs?.B).toEqual(['..', '.#', '..'])
    setHeight(d, 1)
    expect(d.glyphs?.B).toEqual(['..'])
  })

  it("switches type, dropping the old type's fields", () => {
    const d: ScreenFontFile = { type: 'truetype', file: 'a.ttf', size: 12, antialias: true }
    setType(d, 'bitmap')
    expect(d).toEqual({ type: 'bitmap', file: 'a.ttf' })
    setType(d, 'pixels')
    expect(d).toEqual({ type: 'pixels', glyphs: {} })
  })
})
