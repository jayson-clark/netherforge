/**
 * The game's own textures, from the client import, for redrawing them with a
 * resource pack's pictures (`textures` in `pack.json`): their names and sizes
 * from the import's index, each one's picture, and its bytes to start a
 * replacement from.
 */
import { useAsync } from '@/ui/useAsync'
import type { ClientAssets, ClientIndex } from './assets'
import { useGameClientAssets } from './useClientAssets'

export interface GameTextures {
  /** Every texture's `[width, height]` by name (`block/stone`), sorted by name. */
  sizes: Record<string, number[]>
  /** The HUD's GUI sprites (`hud/heart/full`), sorted; none from an import before the editor listed them. */
  sprites: string[]
  /** The picture of [name], to show. */
  url(name: string): string
  /** The PNG of [name] and its `.mcmeta` (an animation's frames) when it has one; null when it can't be read. */
  files(name: string): Promise<{ png: Uint8Array; mcmeta: Uint8Array | null } | null>
}

const bytes = async (url: string) => {
  try {
    const response = await fetch(url)
    return response.ok ? new Uint8Array(await response.arrayBuffer()) : null
  } catch {
    return null
  }
}

function gameTextures(assets: ClientAssets, index: ClientIndex | null): GameTextures {
  const sizes = Object.fromEntries(
    Object.entries(index?.textures ?? {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  )
  const url = (name: string) => assets.textureUrl(`minecraft:${name}`)
  return {
    sizes,
    sprites: [...(index?.sprites ?? [])].sort(),
    url,
    async files(name) {
      const png = await bytes(url(name))
      return png ? { png, mcmeta: await bytes(`${url(name)}.mcmeta`) } : null
    },
  }
}

/** The game's textures for the project's Minecraft, or null before it's imported. */
export function useGameTextures(): GameTextures | null {
  const assets = useGameClientAssets()
  return useAsync(assets?.version ?? '', () =>
    assets
      ? assets.json<ClientIndex>('index.json').then((index) => gameTextures(assets, index))
      : null,
  )
}
