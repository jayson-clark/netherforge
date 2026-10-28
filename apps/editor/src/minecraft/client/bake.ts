/**
 * Block states baked for the block meshes (`blockMesh.ts`) with the client's
 * own models: each state resolved and baked once (cached per asset set, as
 * the viewport's blocks are), and every texture that could hide a neighbour
 * checked once for transparency (a glass or leaves face hides nothing behind
 * it). Main thread: it loads models and pictures; the template preview's
 * worker gets the results as plain data.
 */
import type { ClientAssets } from './assets'
import { occludedSides, type BakedState, type BakedStates } from './blockMesh'
import { bakeFaces, placementMatrix } from '@netherforge/models/geometry'
import { blockPlacements, parseBlockState, resolveModel } from '@netherforge/models/model'
import { tintFor } from './render'
import { loadImageInfo } from '@/core/image'

const states = new WeakMap<ClientAssets, Map<string, Promise<BakedState | null>>>()
const opacity = new WeakMap<ClientAssets, Map<string, Promise<boolean>>>()

function cacheOf<T>(map: WeakMap<ClientAssets, Map<string, T>>, assets: ClientAssets) {
  let cache = map.get(assets)
  if (!cache) {
    cache = new Map()
    map.set(assets, cache)
  }
  return cache
}

/** What a block state draws; null when the client has nothing for it (air has no model). */
export function bakeState(
  assets: ClientAssets,
  state: string,
  defaults: Record<string, string> = {},
): Promise<BakedState | null> {
  const cache = cacheOf(states, assets)
  const key = `${state}|${JSON.stringify(defaults)}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const placements = await blockPlacements(assets, state, defaults)
      const tint = tintFor(state)
      const faces = (
        await Promise.all(
          placements.map(async (placement) => {
            const model = await resolveModel(assets, placement.model)
            if (!model) return []
            return bakeFaces(model.elements, model.textures, {
              tint,
              transform: placementMatrix(placement),
            })
          }),
        )
      ).flat()
      return faces.length > 0 ? { faces } : null
    })().catch(() => null)
    cache.set(key, pending)
  }
  return pending
}

/**
 * Whether every pixel of a texture's first frame is fully opaque. A texture
 * that can't be loaded is drawn as the opaque missing-texture chequer.
 */
export function textureOpaque(assets: ClientAssets, texture: string): Promise<boolean> {
  const cache = cacheOf(opacity, assets)
  let pending = cache.get(texture)
  if (!pending) {
    pending = loadImageInfo(assets.textureUrl(texture)).then((info) => {
      if (!info?.alpha) return true
      // An animated texture is a strip of square frames; the preview shows the first.
      const pixels = Math.min(info.width * info.width, info.alpha.length)
      for (let i = 0; i < pixels; i += 1) if (info.alpha[i]! !== 255) return false
      return true
    })
    cache.set(texture, pending)
  }
  return pending
}

/**
 * [stateTexts] baked, in order. [defaultsOf] gives a block's default
 * properties (game data), for states that leave some out.
 */
export async function bakeStates(
  assets: ClientAssets,
  stateTexts: string[],
  defaultsOf: (id: string) => Record<string, string> | undefined,
): Promise<BakedStates> {
  const baked = await Promise.all(
    stateTexts.map((state) =>
      bakeState(assets, state, defaultsOf(parseBlockState(state).id) ?? {}),
    ),
  )
  // Only faces that cover a side can hide anything: just their textures need checking.
  const covering = new Set<string>()
  for (const state of baked) {
    for (const face of state?.faces ?? []) if (face.covers) covering.add(face.texture)
  }
  const opaque = new Map<string, boolean>()
  await Promise.all(
    [...covering].map(async (texture) => opaque.set(texture, await textureOpaque(assets, texture))),
  )
  return {
    states: baked,
    occludes: baked.map((state) => occludedSides(state, (texture) => opaque.get(texture) ?? false)),
  }
}
