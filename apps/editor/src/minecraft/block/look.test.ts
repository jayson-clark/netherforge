import { describe, expect, it } from 'vitest'
import { compileResourcePacks } from '@/core/format'
import { lookOf } from './look'

describe('block looks', () => {
  it("reads a block's look from the packs as format builds them: faces by the fallback, and the particle", () => {
    const compiled = compileResourcePacks(
      'shop',
      {
        ui: JSON.stringify({
          blocks: {
            ore: { texture: 'block/ore.png' },
            log: { top: 'block/top.png', bottom: 'block/top.png', side: 'block/log.png' },
            half: { top: 'block/top.png' },
          },
        }),
      },
      undefined,
    )
    const ore = lookOf(compiled, 'ui/ore', 'shop', 'shop')
    expect(ore?.particle).toBe('resource_packs/ui/textures/block/ore.png')
    expect(
      Object.values(ore!.faces!).every((it) => it === 'resource_packs/ui/textures/block/ore.png'),
    ).toBe(true)
    const log = lookOf(compiled, 'shop:ui/log', 'shop', 'shop')!
    expect(log.faces).toMatchObject({
      up: 'resource_packs/ui/textures/block/top.png',
      north: 'resource_packs/ui/textures/block/log.png',
      west: 'resource_packs/ui/textures/block/log.png',
    })
    // A look that leaves a face out has no faces (the pack doesn't build), but its particle is known.
    const half = lookOf(compiled, 'ui/half', 'shop', 'shop')!
    expect(half.faces).toBeNull()
    expect(half.particle).toBe('resource_packs/ui/textures/block/top.png')
    expect(lookOf(compiled, 'ui/missing', 'shop', 'shop')).toBeNull()
    expect(lookOf(compiled, undefined, 'shop', 'shop')).toBeNull()
  })
})
