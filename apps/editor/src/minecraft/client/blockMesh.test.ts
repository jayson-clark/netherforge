import { describe, expect, it } from 'vitest'
import { buildStructureMesh } from '@/minecraft/structure/mesh'
import { parseStructure, type Structure } from '@netherforge/structures/structure'
import { filledBlocks, structureRoot } from '@/testing/nbtFixtures'
import { occludedSides, type BakedState } from './blockMesh'
import { bakeFaces, placementMatrix } from '@netherforge/models/geometry'
import type { RawElement } from '@netherforge/models/model'

const all = (texture: string, cull = true) =>
  Object.fromEntries(
    (['down', 'up', 'north', 'south', 'west', 'east'] as const).map((face) => [
      face,
      { texture, ...(cull ? { cullface: face } : {}) },
    ]),
  )

const cube = (texture: string): BakedState => ({
  faces: bakeFaces([{ from: [0, 0, 0], to: [16, 16, 16], faces: all(texture) }], {}),
})

const structureOf = (
  size: [number, number, number],
  blocks: { pos: [number, number, number]; state: number }[],
  palette = ['test:a', 'test:b', 'test:c'],
): Structure =>
  parseStructure(structureRoot({ size, palette: palette.map((Name) => ({ Name })), blocks }))

const opaque = (texture: string) => !texture.includes('glass')

/** A structure's mesh, with opacity from the texture's name. */
const meshOf = (structure: Structure, states: BakedState[]) =>
  buildStructureMesh(structure, {
    states,
    occludes: states.map((state) => occludedSides(state, opaque)),
  })

describe('block meshes', () => {
  it('leaves out the faces between two solid blocks', () => {
    const structure = structureOf(
      [2, 1, 1],
      [
        { pos: [0, 0, 0], state: 0 },
        { pos: [1, 0, 0], state: 0 },
      ],
    )
    const mesh = meshOf(structure, [cube('test:block/stone')])
    expect(mesh.faces).toBe(10)
    expect(mesh.culled).toBe(2)
    expect(mesh.groups).toHaveLength(1)
    const group = mesh.groups[0]!
    expect(group.positions).toHaveLength(10 * 4 * 3)
    expect(group.indices).toHaveLength(10 * 6)
    // The second block's quads are moved to where it stands.
    expect(Math.max(...group.positions.filter((_, i) => i % 3 === 0))).toBe(2)
  })

  it('draws what sits behind a see-through block, and faces with no cullface always', () => {
    const glassNextToStone = meshOf(
      structureOf(
        [2, 1, 1],
        [
          { pos: [0, 0, 0], state: 0 },
          { pos: [1, 0, 0], state: 1 },
        ],
      ),
      [cube('test:block/stone'), cube('test:block/glass')],
    )
    // The glass's face toward the stone is hidden; the stone's toward the glass isn't.
    expect(glassNextToStone.culled).toBe(1)

    const noCullface: BakedState = {
      faces: bakeFaces([{ from: [0, 0, 0], to: [16, 16, 16], faces: all('test:x', false) }], {}),
    }
    const mesh = meshOf(
      structureOf(
        [2, 1, 1],
        [
          { pos: [0, 0, 0], state: 0 },
          { pos: [1, 0, 0], state: 0 },
        ],
      ),
      [noCullface],
    )
    expect(mesh.culled).toBe(0)
  })

  it('hides only behind faces that cover the whole side: a slab hides what is under it, not beside it', () => {
    const slab: BakedState = {
      faces: bakeFaces(
        [{ from: [0, 0, 0], to: [16, 8, 16], faces: all('test:block/slab') }] as RawElement[],
        {},
      ),
    }
    expect(occludedSides(slab, opaque)).toBe(1) // down only
    const mesh = meshOf(
      structureOf(
        [2, 2, 1],
        [
          { pos: [0, 1, 0], state: 1 }, // a slab on top of
          { pos: [0, 0, 0], state: 0 }, // a cube, beside
          { pos: [1, 1, 0], state: 0 }, // another cube
        ],
      ),
      [cube('test:block/stone'), slab],
    )
    // The cube's top under the slab, the slab's bottom and the slab's side against the other cube go;
    // that cube's side toward the slab stays (half a side covers nothing).
    expect(mesh.culled).toBe(3)
  })

  it("turns cullfaces with the blockstate's rotation", () => {
    // A model whose only face is its north one, turned 90° clockwise: it faces east now.
    const north = bakeFaces(
      [
        {
          from: [0, 0, 0],
          to: [16, 16, 16],
          faces: { north: { texture: 'test:x', cullface: 'north' } },
        },
      ],
      {},
      { transform: placementMatrix({ x: 0, y: 90 }) },
    )
    expect(north[0]!.cull).toBe('east')
    expect(north[0]!.covers).toBe('east')
  })

  it('keeps a solid 48³ structure to its six outer walls, quickly', () => {
    const size: [number, number, number] = [48, 48, 48]
    const structure = parseStructure(
      structureRoot({ size, palette: [{ Name: 'test:stone' }], blocks: filledBlocks(size) }),
    )
    const started = performance.now()
    const mesh = meshOf(structure, [cube('test:block/stone')])
    const took = performance.now() - started
    expect(mesh.faces).toBe(6 * 48 * 48)
    expect(mesh.culled).toBe(48 * 48 * 48 * 6 - 6 * 48 * 48)
    // Generous for a slow CI machine; it takes tens of milliseconds.
    expect(took).toBeLessThan(3000)
  })
})
