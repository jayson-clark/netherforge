/**
 * What a particle looks like in the preview, from the imported client
 * assets: its definition's first sprite (`particles/<name>.json` →
 * `textures/particle/<sprite>.png`), a block particle's block `particle`
 * texture, an item particle's item sprite. Null when the assets don't say
 * (no import, an unknown id): the preview draws a soft dot instead.
 */
import type { SpawnData } from '@/core/format'
import {
  blockPlacements,
  itemModelRef,
  resolveModel,
  resolveTexture,
  splitId,
  spriteLayers,
  type AssetLoader,
} from '@netherforge/models/model'

const namespaced = (id: string) => (id.includes(':') ? id : `minecraft:${id}`)

/** The texture id one spawn of [particle] carrying [data] is drawn with. */
export async function spriteTexture(
  loader: AssetLoader,
  particle: string,
  data: SpawnData | null,
  blockDefaults?: Record<string, string>,
): Promise<string | null> {
  if (data?.type === 'block') {
    const placement = (await blockPlacements(loader, data.state, blockDefaults))[0]
    const model = placement ? await resolveModel(loader, placement.model) : null
    const reference = model?.textures.particle
    return model && reference ? resolveTexture(reference, model.textures) : null
  }
  if (data?.type === 'item') {
    // A project item's kind is its definition's; with none given here, there's nothing to draw.
    if (!data.item.kind) return null
    const model = await resolveModel(loader, await itemModelRef(loader, data.item.kind))
    if (!model) return null
    const reference = model.textures.particle
    return spriteLayers(model)[0] ?? (reference ? resolveTexture(reference, model.textures) : null)
  }
  const { namespace, path } = splitId(namespaced(particle))
  const definition = await loader.json<{ textures?: string[] }>(
    `assets/${namespace}/particles/${path}.json`,
  )
  const sprite = definition?.textures?.[0]
  if (!sprite) return null
  const id = splitId(namespaced(sprite))
  return `${id.namespace}:particle/${id.path}`
}

/** `0xRRGGBB` → `#rrggbb`. */
export const hexColor = (color: number) => `#${(color & 0xffffff).toString(16).padStart(6, '0')}`

/** The tint a spawn's data gives its sprite, or null for the sprite's own colours. */
export function tintOf(data: SpawnData | null): string | null {
  switch (data?.type) {
    case 'dust':
    case 'dust_transition':
    case 'color':
      return hexColor(data.color)
    default:
      return null
  }
}

/** How big a sprite is drawn, in blocks: roughly the game's usual size, scaled by dust's `size`. */
export function sizeOf(data: SpawnData | null): number {
  const base = 0.15
  if (data?.type === 'dust' || data?.type === 'dust_transition') return base * data.size
  return base
}
