/**
 * A structure's blocks as one mesh (`mc/blockMesh.ts` does the work): the
 * structure's box is the whole world, so a neighbour outside it is nothing.
 * Pure: the states come baked (`mc/bake.ts`).
 */
import { meshBlocks, type BakedStates, type BlockMesh } from '@/minecraft/client/blockMesh'
import type { Structure } from '@netherforge/structures/structure'

/**
 * Merges every block's faces into one group per texture. [baked] has a
 * state per palette index (null: draws nothing, as air does).
 */
export function buildStructureMesh(structure: Structure, baked: BakedStates): BlockMesh {
  const [sx, sy, sz] = structure.size
  // Palette index + 1 per cell, 0 for nothing there.
  const grid = new Int32Array(sx * sy * sz)
  const cell = (x: number, y: number, z: number) => (x * sy + y) * sz + z
  const { blocks } = structure
  for (let i = 0; i < blocks.length; i += 4) {
    grid[cell(blocks[i]!, blocks[i + 1]!, blocks[i + 2]!)] = blocks[i + 3]! + 1
  }
  return meshBlocks(
    {
      forEach(visit) {
        for (let i = 0; i < blocks.length; i += 4) {
          visit(blocks[i]!, blocks[i + 1]!, blocks[i + 2]!, blocks[i + 3]!)
        }
      },
      stateAt(x, y, z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1
        return grid[cell(x, y, z)]! - 1
      },
    },
    baked.states,
    baked.occludes,
  )
}
