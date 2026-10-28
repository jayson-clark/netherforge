import { describe, expect, it } from 'vitest'
import { BUILD_LIMITS, EMPTY_BLOCKS } from '@netherforge/format/constants'
import type { Structure } from '@netherforge/structures/structure'
import {
  boxSize,
  buildSize,
  drawBuild,
  fitView,
  lastPart,
  missingParts,
  partBoxes,
  partNames,
  partShows,
  tooBig,
} from './ops'

const SIDE = BUILD_LIMITS.partSide

const structure = (size: [number, number, number], state = 'test:stone'): Structure => ({
  dataVersion: null,
  size,
  palette: [EMPTY_BLOCKS[0]!, state],
  blocks: Int32Array.from([0, 0, 0, 1]),
  entities: 0,
  blockEntities: 0,
  otherPalettes: 0,
})

describe('the build screen', () => {
  it('cuts a box into part boxes, both corners included, whichever corners were given', () => {
    expect(boxSize([10, 64, -5], [-40, 60, 5])).toEqual([51, 5, 11])
    const boxes = partBoxes([10, 64, -5], [-40, 60, 5])
    expect(boxes).toEqual([
      { file: '0_0_0.nbt', from: { x: -40, y: 60, z: -5 }, to: { x: -40 + SIDE - 1, y: 64, z: 5 } },
      { file: '1_0_0.nbt', from: { x: -40 + SIDE, y: 60, z: -5 }, to: { x: 10, y: 64, z: 5 } },
    ])
    expect(tooBig([SIDE * BUILD_LIMITS.maxParts, 1, 1])).toBe(false)
    expect(tooBig([SIDE * BUILD_LIMITS.maxParts + 1, 1, 1])).toBe(true)
  })

  it("knows its parts by name, the grid's gaps and its size from the last part", () => {
    const names = partNames(['1_0_0.nbt', 'notes.txt', '0_0_0.nbt', '1_0_1.nbt'])
    expect(names).toEqual(['0_0_0.nbt', '1_0_0.nbt', '1_0_1.nbt'])
    expect(missingParts(names)).toEqual(['0_0_1.nbt'])
    expect(lastPart(names)).toBe('1_0_1.nbt')
    expect(buildSize(names, structure([3, 7, 9]))).toEqual([SIDE + 3, 7, SIDE + 9])
  })

  it('keeps a slice inside the build, and reads only the parts it cuts', async () => {
    expect(fitView({ kind: 'slice', axis: 'x', at: 500 }, [50, 2, 2])).toEqual({
      kind: 'slice',
      axis: 'x',
      at: 49,
    })
    expect(partShows({ kind: 'slice', axis: 'x', at: SIDE + 1 }, [0, 0, 0])).toBe(false)
    expect(partShows({ kind: 'slice', axis: 'x', at: SIDE + 1 }, [1, 0, 0])).toBe(true)
    const read: string[] = []
    const picture = await drawBuild(
      [SIDE + 2, 1, 1],
      ['0_0_0.nbt', '1_0_0.nbt'],
      { kind: 'slice', axis: 'x', at: SIDE },
      async (file) => {
        read.push(file)
        return structure([2, 1, 1], 'test:gold')
      },
    )
    expect(read).toEqual(['1_0_0.nbt'])
    expect(picture.legend.map((it) => it.kind)).toEqual(['test:gold'])
  })
})
