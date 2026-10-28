import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type DimensionTypeFile } from '@/core/format'
import { blockRange, setColor, setMonsterLight, setValue } from './ops'

const canonical = (dimension: DimensionTypeFile) =>
  canonicalizeModel('dimension_type', 'dimension_types/deep.json', dimension).text ?? ''

describe('dimension ops', () => {
  it('sets values and leaves a cleared one out', () => {
    const dimension: DimensionTypeFile = {}
    setValue(dimension, 'minY', -128)
    setValue(dimension, 'height', 512)
    setValue(dimension, 'bedWorks', false)
    expect(dimension).toEqual({ minY: -128, height: 512, bedWorks: false })
    setValue(dimension, 'bedWorks', undefined)
    expect(dimension).toEqual({ minY: -128, height: 512 })
  })

  it('drops the colours and the light range once they are emptied', () => {
    const dimension: DimensionTypeFile = {}
    setColor(dimension, 'sky', '#102030')
    setColor(dimension, 'clouds', ' #ccffffff ')
    setMonsterLight(dimension, 'max', 7.4)
    expect(dimension).toEqual({
      colors: { sky: '#102030', clouds: '#ccffffff' },
      monsterSpawnLight: { max: 7 },
    })
    setColor(dimension, 'sky', undefined)
    setColor(dimension, 'clouds', '')
    setMonsterLight(dimension, 'max', undefined)
    expect(dimension).toEqual({})
  })

  it('says the blocks a world holds, the overworld when nothing is set', () => {
    expect(blockRange({})).toEqual({ lowest: -64, highest: 319 })
    expect(blockRange({ minY: -128, height: 512 })).toEqual({ lowest: -128, highest: 383 })
  })

  it('writes back canonically', () => {
    const dimension: DimensionTypeFile = {}
    setValue(dimension, 'height', 448)
    setValue(dimension, 'minY', -128)
    const text = canonical(dimension)
    expect(text).toContain('"$schema": "../.netherforge/schema/dimension_type.schema.json"')
    expect(text.indexOf('"minY"')).toBeLessThan(text.indexOf('"height"'))
    expect(canonical(JSON.parse(text) as DimensionTypeFile)).toBe(text)
  })
})
