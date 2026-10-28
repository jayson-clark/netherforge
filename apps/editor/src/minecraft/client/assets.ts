/**
 * The cached client assets for one Minecraft version, read through the
 * backend's asset protocol (`Backend.assetUrl`). Every JSON fetch is cached
 * for the session: the same few parents (`block/cube_all`, `item/generated`)
 * serve most of the registry.
 */
import type { Backend } from '@/core/backend/types'
import { splitId, textureAssetPath, type AssetLoader } from '@netherforge/models/model'

/** `index.json`, written by the import: ids for pickers, so nothing lists folders. */
export interface ClientIndex {
  minecraft: string
  blockstates: string[]
  items: string[]
  fonts: string[]
  /** The HUD's GUI sprites (`hud/heart/full`); missing from imports before the editor listed them. */
  sprites?: string[]
  /**
   * Every texture (`block/stone`) with its `[width, height]`; only the container screens' from imports before the
   * editor listed them all, and missing from older ones.
   */
  textures?: Record<string, number[]>
}

export interface ClientAssets extends AssetLoader {
  version: string
  /**
   * Tells asset sets apart in caches: the version, and which of the game's textures the project's
   * resource packs draw instead ([withReplacedTextures]).
   */
  key: string
  textureUrl(textureId: string): string
}

const loaders = new Map<string, ClientAssets>()

export function clientAssets(backend: Backend, version: string): ClientAssets {
  const existing = loaders.get(version)
  if (existing) return existing
  const cache = new Map<string, Promise<unknown>>()
  const assets: ClientAssets = {
    version,
    key: version,
    json<T>(path: string): Promise<T | null> {
      let pending = cache.get(path)
      if (!pending) {
        pending = fetch(backend.assetUrl(version, path))
          .then((response) => (response.ok ? response.json() : null))
          .catch(() => null)
        cache.set(path, pending)
      }
      return pending as Promise<T | null>
    },
    textureUrl(textureId: string) {
      return backend.assetUrl(version, textureAssetPath(textureId))
    },
  }
  loaders.set(version, assets)
  return assets
}

/** Forgets cached assets, after an import replaced them. */
export function forgetClientAssets(version: string) {
  loaders.delete(version)
}

const replaced = new WeakMap<ClientAssets, { key: string; assets: ClientAssets }>()

/**
 * [assets] with the game's textures that resource packs redraw (`textures` in `pack.json`: `block/stone` →
 * the picture's URL) drawn with the packs' pictures, as the client draws them. The same object while
 * [urls] are the same, so what's cached per asset set stays cached.
 */
export function withReplacedTextures(
  assets: ClientAssets,
  urls: Record<string, string>,
): ClientAssets {
  const names = Object.keys(urls)
  if (names.length === 0) return assets
  const key = `${assets.version}|${hash(JSON.stringify(names.sort().map((it) => [it, urls[it]])))}`
  const known = replaced.get(assets)
  if (known?.key === key) return known.assets
  const result: ClientAssets = {
    version: assets.version,
    key,
    json<T>(path: string) {
      return assets.json<T>(path)
    },
    textureUrl(textureId) {
      const { namespace, path } = splitId(textureId)
      return (namespace === 'minecraft' ? urls[path] : undefined) ?? assets.textureUrl(textureId)
    },
  }
  replaced.set(assets, { key, assets: result })
  return result
}

function hash(text: string): string {
  let value = 0x811c9dc5
  for (let i = 0; i < text.length; i += 1) {
    value ^= text.charCodeAt(i)
    value = Math.imul(value, 0x01000193)
  }
  return (value >>> 0).toString(36)
}
