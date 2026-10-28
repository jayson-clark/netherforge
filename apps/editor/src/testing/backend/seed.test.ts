import { describe, expect, it } from 'vitest'
import { exampleProject } from './seed'

describe('seed', () => {
  it('bundles the example textures as real PNG bytes', () => {
    const png = exampleProject['resource_packs/ui/textures/gui/shop.png']
    expect(png).toBeInstanceOf(Uint8Array)
    expect([...(png as Uint8Array).slice(0, 4)]).toEqual([0x89, 0x50, 0x4e, 0x47])
    expect(typeof exampleProject['resource_packs/ui/pack.json']).toBe('string')
  })
})
