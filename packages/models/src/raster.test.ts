import { describe, expect, it } from 'vitest'
import { bakeFaces, displayMatrix } from './geometry.ts'
import { blankPicture, layered, MISSING, rasterize, type Picture } from './raster.ts'

/** A [size]-pixel square of one colour. */
function solid(size: number, rgba: number[]): Picture {
  const picture = blankPicture(size, size)
  for (let i = 0; i < size * size; i += 1) picture.data.set(rgba, i * 4)
  return picture
}

const pixel = (picture: Picture, x: number, y: number) => [
  ...picture.data.subarray((y * picture.width + x) * 4, (y * picture.width + x) * 4 + 4),
]

describe('rasterize', () => {
  const cube = bakeFaces(
    [
      {
        from: [0, 0, 0],
        to: [16, 16, 16],
        faces: { south: { texture: 'red' }, up: { texture: 'blue' } },
      },
    ],
    {},
  )

  it("draws a block's faces with the game's shade, nearest face first", () => {
    // The south face straight on: the whole square, red at full shade... less the game's 0.8 for south.
    const front = rasterize(
      cube,
      displayMatrix(undefined),
      (id) => (id === 'red' ? solid(16, [250, 0, 0, 255]) : solid(16, [0, 0, 250, 255])),
      32,
    )
    expect(pixel(front, 16, 16)).toEqual([200, 0, 0, 255])
    // Turned to look down on it: the top (full shade) in front.
    const above = rasterize(
      cube,
      displayMatrix({ rotation: [90, 0, 0] }),
      (id) => (id === 'red' ? solid(16, [250, 0, 0, 255]) : solid(16, [0, 0, 250, 255])),
      32,
    )
    expect(pixel(above, 16, 16)).toEqual([0, 0, 250, 255])
  })

  it('cuts out clear pixels and draws a missing texture as the chequer', () => {
    const clear = rasterize(cube, displayMatrix(undefined), () => solid(16, [255, 255, 255, 0]), 8)
    expect(pixel(clear, 4, 4)[3]).toBe(0)
    const missing = rasterize(cube, displayMatrix(undefined), () => null, 16)
    expect(pixel(missing, 0, 0)).toEqual(
      [...MISSING.data.subarray(0, 3)].map((it) => Math.round(it * 0.8)).concat(255),
    )
  })
})

describe('layered', () => {
  it('draws layers over each other, scaled by nearest pixel', () => {
    const top = blankPicture(2, 2)
    top.data.set([0, 255, 0, 255], 0)
    const out = layered([solid(2, [255, 0, 0, 255]), top], 4)
    expect(pixel(out, 0, 0)).toEqual([0, 255, 0, 255])
    expect(pixel(out, 3, 3)).toEqual([255, 0, 0, 255])
  })
})
