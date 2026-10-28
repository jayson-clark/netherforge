/**
 * Display strings → drawable geometry, cached per asset set and id: the
 * viewport re-renders on every edit, and re-baking an unchanged block each
 * time would be waste.
 */
import { invalidate } from '@react-three/fiber'
import * as THREE from 'three'
import type { ClientAssets } from './assets'
import {
  buildElements,
  buildSprite,
  displayMatrix,
  placementMatrix,
  tintFor,
  type BuiltGroup,
} from '@netherforge/models/geometry'
import {
  cubeModel,
  isSpriteModel,
  itemModel,
  packModel,
  placedModels,
  spriteLayers,
  type RawModel,
  type ResolvedModel,
} from '@netherforge/models/model'

export { tintFor }

const blocks = new WeakMap<ClientAssets, Map<string, Promise<BuiltGroup[]>>>()
const items = new WeakMap<ClientAssets, Map<string, Promise<ItemGeometry | null>>>()

function cacheFor<T>(map: WeakMap<ClientAssets, Map<string, T>>, assets: ClientAssets) {
  let cache = map.get(assets)
  if (!cache) {
    cache = new Map()
    map.set(assets, cache)
  }
  return cache
}

/** A block state's geometry, corner at the origin; empty when the assets don't know it. */
export function blockGeometry(
  assets: ClientAssets,
  state: string,
  defaults: Record<string, string> = {},
): Promise<BuiltGroup[]> {
  const cache = cacheFor(blocks, assets)
  const key = `${state}|${JSON.stringify(defaults)}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const tint = tintFor(state)
      return (await placedModels(assets, state, defaults)).flatMap(({ model, placement }) =>
        buildElements(model.elements, model.textures, {
          tint,
          transform: placementMatrix(placement),
        }),
      )
    })()
    cache.set(key, pending)
  }
  return pending
}

/**
 * [model] as an item in display [context]: a sprite's layers, or its elements; null when that draws nothing. The
 * same models `@netherforge/models/icons` bakes for the netherforge command, built as meshes.
 */
function modelItem(model: ResolvedModel, context: string, tint?: THREE.Color): ItemGeometry | null {
  const groups = isSpriteModel(model)
    ? buildSprite(spriteLayers(model))
    : buildElements(model.elements, model.textures, { tint })
  return groups.length > 0 ? { groups, matrix: displayMatrix(model.display[context]) } : null
}

export interface ItemGeometry {
  groups: BuiltGroup[]
  /** The model's display transform for the context, then centred on the entity. */
  matrix: THREE.Matrix4
}

export function itemGeometry(
  assets: ClientAssets,
  item: string,
  context: string,
): Promise<ItemGeometry | null> {
  const cache = cacheFor(items, assets)
  const key = `${item}#${context}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const model = await itemModel(assets, item)
      return model ? modelItem(model, context, tintFor(item)) : null
    })()
    cache.set(key, pending)
  }
  return pending
}

/**
 * A pack's block look drawn as its item: the game's `block/cube`, with the
 * block display transforms it inherits, its faces bound to [faces] (each
 * group's `texture` is then whatever the caller gave: a project file's URL).
 */
export function blockLookGeometry(
  assets: ClientAssets,
  faces: Record<string, string>,
  context: string,
): Promise<ItemGeometry | null> {
  const cache = cacheFor(items, assets)
  const key = `look:${JSON.stringify(faces)}#${context}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const cube = await cubeModel(assets, faces)
      return cube ? modelItem(cube, context) : null
    })()
    cache.set(key, pending)
  }
  return pending
}

/** A flat picture ([url], a project file) as an item drawn flat, centred, as a texture look is. */
export function pictureGeometry(url: string): ItemGeometry {
  return { groups: buildSprite([url]), matrix: displayMatrix(undefined) }
}

/**
 * One of a resource pack's own model files (`models/<path>.json`, as written)
 * as an item in display [context]: its parent chain from the client's assets,
 * as the game resolves one, with [bound] (texture variable → a project file's
 * URL) for the textures that are the pack's own.
 */
export function packModelGeometry(
  assets: ClientAssets,
  model: RawModel,
  bound: Record<string, string>,
  context: string,
): Promise<ItemGeometry | null> {
  const cache = cacheFor(items, assets)
  const key = `pack:${JSON.stringify(model)}|${JSON.stringify(bound)}#${context}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      return modelItem(await packModel(assets, model, bound), context)
    })()
    cache.set(key, pending)
  }
  return pending
}

/** Whether a model's texture is a URL already (a project file a pack model draws) rather than a client texture id. */
export const isUrl = (texture: string) => /^[a-z][a-z0-9+.-]*:\/\//i.test(texture)

const textures = new Map<string, THREE.Texture>()
let missing: THREE.Texture | null = null

/** The magenta/black chequer Minecraft draws for a missing texture. */
function missingTexture(): THREE.Texture {
  if (missing) return missing
  const canvas = document.createElement('canvas')
  canvas.width = 16
  canvas.height = 16
  const context = canvas.getContext('2d')
  if (context) {
    context.fillStyle = '#000'
    context.fillRect(0, 0, 16, 16)
    context.fillStyle = '#f800f8'
    context.fillRect(0, 0, 8, 8)
    context.fillRect(8, 8, 8, 8)
  }
  missing = pixelated(new THREE.CanvasTexture(canvas))
  return missing
}

function pixelated(texture: THREE.Texture): THREE.Texture {
  texture.magFilter = THREE.NearestFilter
  texture.minFilter = THREE.NearestFilter
  texture.generateMipmaps = false
  texture.colorSpace = THREE.SRGBColorSpace
  return texture
}

/** A texture with Minecraft's filtering; animated strips show their first frame. */
export function loadTexture(assets: ClientAssets, textureId: string): THREE.Texture {
  // A project file's URL (a pack's own texture) is loaded as it is; anything else is the client's.
  const url = isUrl(textureId) ? textureId : assets.textureUrl(textureId)
  const existing = textures.get(url)
  if (existing) return existing
  const texture = new THREE.TextureLoader().load(
    url,
    (loaded) => {
      const image = loaded.image as HTMLImageElement
      if (image.height > image.width && image.height % image.width === 0) {
        const frames = image.height / image.width
        loaded.repeat.set(1, 1 / frames)
        loaded.offset.set(0, 1 - 1 / frames)
        loaded.needsUpdate = true
      }
      // The viewport draws on demand: what's showing this texture is drawn again with it.
      invalidate()
    },
    undefined,
    () => {
      texture.image = missingTexture().image as HTMLImageElement
      texture.needsUpdate = true
      invalidate()
    },
  )
  pixelated(texture)
  textures.set(url, texture)
  return texture
}
