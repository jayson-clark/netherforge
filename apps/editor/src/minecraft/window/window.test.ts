import { describe, expect, it } from 'vitest'
import { compileResourcePacks } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import { opaqueWidth } from '@/core/image'
import { BASELINE, placeTitle, windowLayout } from './window'

describe('window layout', () => {
  it('sizes and places a chest like the game', () => {
    const three = windowLayout(undefined, 3)
    expect([three.width, three.height]).toEqual([176, 168])
    expect(three.slots[0]).toEqual({ index: 0, x: 8, y: 18 })
    expect(three.slots[13]).toEqual({ index: 13, x: 8 + 4 * 18, y: 18 + 18 })
    expect(three.player[0]).toEqual({ x: 8, y: 85 })
    expect(three.player[27]).toEqual({ x: 8, y: 143 })
    expect(three.playerLabel).toEqual({ x: 8, y: 74 })
    expect(windowLayout('chest', 6).height).toBe(222)
  })

  it('knows the other containers', () => {
    const hopper = windowLayout('hopper')
    expect(hopper.height).toBe(133)
    expect(hopper.slots.map((it) => it.x)).toEqual([44, 62, 80, 98, 116])
    const dispenser = windowLayout('dispenser')
    expect(dispenser.slots[4]).toEqual({ index: 4, x: 80, y: 35 })
    expect(dispenser.title.centered).toBe(true)
    expect(windowLayout('crafter').extras).toHaveLength(1)
    expect(windowLayout('shulker_box').height).toBe(167)
  })
})

describe('skin placement', () => {
  it('finds the opaque width the advance is measured from', () => {
    // 4×2, opaque up to column 2.
    expect(opaqueWidth([0, 0, 255, 0, 255, 0, 0, 0], 4, 2)).toBe(3)
    expect(opaqueWidth([0, 0, 0, 0], 2, 2)).toBe(0)
  })

  it('puts the example skin where the server draws it, with the words over it', () => {
    const skin = compileResourcePacks('basic', { ui: exampleFiles['resource_packs/ui/pack.json']! })
      .resourcePacks.ui!.skins.shop!
    const layout = windowLayout(undefined, 3)
    const placed = placeTitle(layout, skin, 40)
    // -8 from the title's x of 8: flush with the window's left edge, and an
    // ascent of 13 above a baseline 7 below the title's top of 6: its top edge.
    expect(placed.skin).toEqual({ left: 0, top: 6 + BASELINE - 13, height: 168 })
    expect(placed.skin!.top).toBe(0)
    // The prefix is zero wide: the words start where a vanilla title does.
    expect(placed.titleX).toBe(8)
  })

  it('centres a dispenser or crafter title on its words; the server moves the skin back when it can measure them', () => {
    const layout = windowLayout('dispenser')
    expect(windowLayout('crafter').title.centered).toBe(true)
    expect(placeTitle(layout, null, 40).titleX).toBe(68)
    const skin = { offset: -8, height: 166, ascent: 13 }
    const placed = placeTitle(layout, skin, 40)
    expect(placed.titleX).toBe(68)
    // Where it would be on a window whose title starts at 8.
    expect(placed.skin!.left).toBe(0)
    // Unmeasured words drag the skin along with them.
    expect(placeTitle(layout, skin, 40, false).skin!.left).toBe(60)
    // No words: nothing to measure, so it's always put back.
    expect(placeTitle(layout, skin, 0, false).skin!.left).toBe(0)
  })
})
