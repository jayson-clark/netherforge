import { describe, expect, it } from 'vitest'
import { fitTextHitbox, layoutText } from '@/core/format'
import { fixtureGlyphAdvances } from '@/testing/clientFixture'

describe('glyph advances', () => {
  it("feed format's text metrics", () => {
    // "il" is 2 + 3 with the fixture font; unknown glyphs are estimated at 6.
    expect(layoutText('il', 0, fixtureGlyphAdvances)[0]!.width).toBe(5)
    expect(layoutText('il', 0, null)[0]!.width).toBe(12)
    const box = fitTextHitbox(
      { type: 'text', text: 'il', billboard: 'fixed' },
      fixtureGlyphAdvances,
    )!
    expect(box.max[0] - box.min[0]).toBeCloseTo((5 + 1) * 0.025)
    expect(box.max[1]).toBeCloseTo(0.25)
    expect(fitTextHitbox({ type: 'text', text: 'il' }, fixtureGlyphAdvances)).toBeNull()
  })
})
