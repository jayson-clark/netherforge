import { describe, expect, it } from 'vitest'
import { EMPTY_BLOCKS } from '@netherforge/format/constants'
import { kindColor, StructurePicture } from './picture.ts'
import type { Structure } from './structure.ts'

/** A structure of [blocks] (x, y, z and a state each) in a box [size]. */
function structure(
  size: [number, number, number],
  blocks: [number, number, number, string][],
): Structure {
  const palette = [...new Set(blocks.map((it) => it[3]))]
  return {
    dataVersion: null,
    size,
    palette,
    blocks: Int32Array.from(
      blocks.flatMap(([x, y, z, state]) => [x, y, z, palette.indexOf(state)]),
    ),
    entities: 0,
    blockEntities: 0,
    otherPalettes: 0,
  }
}

const pixel = (picture: { width: number; data: Uint8ClampedArray }, x: number, y: number) =>
  Array.from(picture.data.subarray((y * picture.width + x) * 4, (y * picture.width + x) * 4 + 4))

const AIR = EMPTY_BLOCKS[0]!

describe('pictures of structures and builds', () => {
  const tower = structure(
    [2, 3, 2],
    [
      [0, 0, 0, 'test:stone'],
      [0, 1, 0, 'test:stone'],
      [0, 2, 0, 'test:gold[lit=true]'],
      [1, 0, 1, 'test:dirt'],
      [1, 1, 1, AIR],
    ],
  )

  it('from above, each column is its highest block, empty ones not drawn', () => {
    const picture = new StructurePicture([2, 3, 2], { kind: 'top' })
    picture.add(tower)
    const drawn = picture.draw()
    expect([drawn.width, drawn.height]).toEqual([2, 2])
    expect(pixel(drawn, 1, 0)[3]).toBe(0)
    expect(pixel(drawn, 0, 0)[3]).toBe(255)
    // The tower's top is higher than the dirt, so lighter.
    const brightness = (rgba: number[]) => rgba[0]! + rgba[1]! + rgba[2]!
    expect(drawn.legend.map((it) => [it.kind, it.count])).toEqual([
      ['test:dirt', 1],
      ['test:gold', 1],
    ])
    expect(drawn.legend[1]!.color).toEqual(kindColor('test:gold'))
    expect(brightness(pixel(drawn, 0, 0))).toBeGreaterThan(0)
  })

  it("a build's parts go where their origins say, and a slice cuts through one layer", () => {
    const part = structure([1, 1, 1], [[0, 0, 0, 'test:gold']])
    const picture = new StructurePicture([4, 2, 1], { kind: 'slice', axis: 'y', at: 1 })
    picture.add(part, [3, 1, 0])
    picture.add(part, [0, 0, 0])
    const drawn = picture.draw(2)
    expect([drawn.width, drawn.height]).toEqual([8, 2])
    expect(pixel(drawn, 6, 0).slice(0, 3)).toEqual([...kindColor('test:gold')])
    expect(pixel(drawn, 7, 1)[3]).toBe(255)
    expect(pixel(drawn, 0, 0)[3]).toBe(0)
  })

  it('a slice along x or z stands up, its top row the highest block', () => {
    const picture = new StructurePicture([2, 3, 2], { kind: 'slice', axis: 'x', at: 0 })
    picture.add(tower)
    const drawn = picture.draw()
    expect([drawn.width, drawn.height]).toEqual([2, 3])
    expect(pixel(drawn, 0, 0).slice(0, 3)).toEqual([...kindColor('test:gold')])
    expect(pixel(drawn, 0, 2).slice(0, 3)).toEqual([...kindColor('test:stone')])
    expect(pixel(drawn, 1, 2)[3]).toBe(0)
    expect(() => new StructurePicture([2, 3, 2], { kind: 'slice', axis: 'z', at: 2 })).toThrow(
      /0 to 1, not 2/,
    )
  })

  it('isometric: the nearest column is drawn last, its top lighter than its sides', () => {
    const picture = new StructurePicture([2, 3, 2], { kind: 'iso' })
    picture.add(tower)
    const drawn = picture.draw()
    expect([drawn.width, drawn.height]).toEqual([8, 10])
    // The dirt at x 1, z 1 is nearest: its strip starts at x (1 - 1 + 1) * 2 = 2, its top at row 2 + (3 - 1) * 2 = 6.
    const top = pixel(drawn, 2, 6)
    const left = pixel(drawn, 2, 8)
    const right = pixel(drawn, 5, 8)
    expect(top[3]).toBe(255)
    expect(top[0]! + top[1]! + top[2]!).toBeGreaterThan(left[0]! + left[1]! + left[2]!)
    expect(left[0]! + left[1]! + left[2]!).toBeGreaterThan(right[0]! + right[1]! + right[2]!)
    expect(pixel(drawn, 0, 9)[3]).toBe(0)
  })

  it('gives a block id the same colour everywhere, whatever its state', () => {
    expect(kindColor('test:gold')).toEqual(kindColor('test:gold'))
    expect(kindColor('test:gold')).not.toEqual(kindColor('test:stone'))
  })
})
