import { describe, expect, it } from 'vitest'
import type { Structure } from '@netherforge/structures/structure'
import { structureInput, structuresNamed } from './structures.ts'

describe("the structures a generator's decorations place", () => {
  it("names the project's own, once each and sorted, never a package's", () => {
    expect(
      structuresNamed({
        tree: { structure: 'oak_tree' },
        rock: { structure: 'boulder' },
        again: { structure: 'oak_tree' },
        theirs: { structure: 'acme:tower' },
        flowers: {},
        none: { structure: null },
      }),
    ).toEqual(['boulder', 'oak_tree'])
    expect(structuresNamed(undefined)).toEqual([])
  })

  it("hands format the parsed structure as plain data, the blocks' four numbers each", () => {
    const read: Structure = {
      dataVersion: 4440,
      size: [2, 1, 3],
      palette: ['test:planks', 'test:stairs[facing=east,half=bottom]'],
      blocks: Int32Array.from([0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 0]),
      entities: 2,
      blockEntities: 1,
      otherPalettes: 0,
    }
    const input = structureInput(read)
    expect(input).toEqual({
      size: [2, 1, 3],
      palette: ['test:planks', 'test:stairs[facing=east,half=bottom]'],
      blocks: [0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 0],
    })
    expect(Array.isArray(input.blocks)).toBe(true)
    // A copy: the preview may keep it while the structure is read again.
    expect(input.size).not.toBe(read.size)
  })
})
