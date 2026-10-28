import { describe, expect, it } from 'vitest'
import { elementBox, modelBoxes } from './boxes'
import type { RawElement } from '@netherforge/models/model'

const el = (
  from: [number, number, number],
  to: [number, number, number],
  extra: Partial<RawElement> = {},
) => ({
  from,
  to,
  faces: {},
  ...extra,
})

describe('element → box', () => {
  it('converts model units to block units', () => {
    expect(elementBox(el([0, 0, 0], [16, 8, 16]))).toEqual({ min: [0, 0, 0], max: [1, 0.5, 1] })
  })

  it('applies the blockstate rotation clockwise about the centre', () => {
    // The tall half of an east-facing stair, turned to face south (y=90).
    const east = el([8, 8, 0], [16, 16, 16])
    expect(elementBox(east, { x: 0, y: 90 })).toEqual({ min: [0, 0.5, 0.5], max: [1, 1, 1] })
    // x=180 flips a bottom slab to the top.
    expect(elementBox(el([0, 0, 0], [16, 8, 16]), { x: 180, y: 0 })).toEqual({
      min: [0, 0.5, 0],
      max: [1, 1, 1],
    })
  })

  it('bounds an element rotated about its origin', () => {
    const cross = el([0.8, 0, 8], [15.2, 16, 8], {
      rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true },
    })
    const box = elementBox(cross)
    // Rescaled to the full diagonal, the plane spans the block corner to corner.
    expect(box.min[0]).toBeCloseTo(0.05, 2)
    expect(box.max[0]).toBeCloseTo(0.95, 2)
    expect(box.min[1]).toBe(0)
    expect(box.max[1]).toBe(1)
  })
})

describe('modelBoxes', () => {
  it('gives flat elements a pixel of thickness and drops contained boxes', () => {
    const boxes = modelBoxes([
      {
        placement: { model: 'm', x: 0, y: 0 },
        elements: [
          el([0, 0, 0], [16, 16, 16]),
          el([2, 2, 2], [4, 4, 4]), // inside the cube
          el([0, 0, 0], [16, 16, 16]), // duplicate
        ],
      },
      { placement: { model: 'ladder', x: 0, y: 0 }, elements: [el([0, 0, 15.2], [16, 16, 15.2])] },
    ])
    expect(boxes).toHaveLength(1)

    const ladder = modelBoxes([
      { placement: { model: 'l', x: 0, y: 0 }, elements: [el([0, 0, 16], [16, 16, 16])] },
    ])
    expect(ladder).toEqual([{ min: [0, 0, 0.9375], max: [1, 1, 1] }])
  })
})
