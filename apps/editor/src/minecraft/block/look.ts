/**
 * A pack's block look (`blocks` in pack.json, docs/format/resource-pack.md#block-models)
 * as the pack draws it, from format's `compileResourcePacks`: the block editor's
 * preview and the icon of an item drawn as a block both read it here.
 */
import type { ResourcePacksPreview } from '@/core/format'
import { resourcePackTexturePath } from '@/core/paths'
import { splitResourcePackRef } from '@/minecraft/item/item'

/** The faces of a cube as format's block look names them, in the order a preview draws them. */
export const BLOCK_FACES = ['up', 'down', 'north', 'south', 'east', 'west'] as const
export type BlockFace = (typeof BLOCK_FACES)[number]

/**
 * The look [model] names, as the pack draws it: each face's texture as a
 * project path, and the one its particles take. Null when [model] names no
 * look that's built (it doesn't exist, or its pack has an error).
 */
export function lookOf(
  compiled: ResourcePacksPreview,
  model: string | undefined,
  namespace: string,
  home: string,
): { faces: Record<BlockFace, string> | null; particle: string | null } | null {
  const ref = splitResourcePackRef('block_model', model, namespace, home)
  if (!ref) return null
  const look = compiled.resourcePacks[ref[0]]?.blocks?.[ref[1]]
  if (!look) return null
  const path = (texture: string) => resourcePackTexturePath(ref[0], texture)
  return {
    faces: look.faces
      ? (Object.fromEntries(
          Object.entries(look.faces).map(([face, it]) => [face, path(it)]),
        ) as Record<BlockFace, string>)
      : null,
    particle: look.particle ? path(look.particle) : null,
  }
}
