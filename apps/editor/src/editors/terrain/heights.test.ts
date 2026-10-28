import { describe, expect, it } from 'vitest'
import type { ProjectManifest } from '@/core/format'
import { heightChoices, OVERWORLD_CHOICE, pickHeights } from './heights'

const manifest = (worlds: ProjectManifest['worlds']) =>
  ({
    formatVersion: 1,
    name: 'T',
    namespace: 'basic',
    version: '1.0.0',
    minecraft: '26.3',
    worlds,
  }) as ProjectManifest

describe('preview heights', () => {
  it('offers the overworld and each world naming the generator and a dimension of the project', () => {
    const choices = heightChoices(
      manifest({
        mine: { terrain: 'ruby_hills', dimensionType: 'deep' },
        alias: { terrain: 'basic:ruby_hills', dimensionType: 'basic:tall' },
        other: { terrain: 'plateau', dimensionType: 'deep' },
        plain: { terrain: 'ruby_hills' },
        package: { terrain: 'ruby_hills', dimensionType: 'acme:deep' },
        broken: { terrain: 'ruby_hills', dimensionType: 'gone' },
      }),
      'basic',
      'ruby_hills',
      { deep: { minY: -128, height: 448 }, tall: {} },
    )
    expect(choices.map((c) => [c.key, c.minY, c.maxY])).toEqual([
      ['', -64, 320],
      ['alias', -64, 320],
      ['mine', -128, 320],
    ])
    expect(choices[2]!.label).toBe('mine: deep (y -128 to 319)')
  })

  it('picks the chosen world, else the first world, else the overworld', () => {
    const deep = { key: 'mine', label: 'mine', minY: -128, maxY: 320 }
    expect(pickHeights([OVERWORLD_CHOICE, deep], null)).toBe(deep)
    expect(pickHeights([OVERWORLD_CHOICE, deep], '')).toBe(OVERWORLD_CHOICE)
    expect(pickHeights([OVERWORLD_CHOICE, deep], 'gone')).toBe(deep)
    expect(pickHeights([OVERWORLD_CHOICE], null)).toBe(OVERWORLD_CHOICE)
    expect(heightChoices(null, 'basic', 'x', {})).toEqual([OVERWORLD_CHOICE])
  })
})
