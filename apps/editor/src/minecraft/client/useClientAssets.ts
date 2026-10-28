import type { ResourcePackFile } from '@/core/format'
import { resourcePackTexturePath } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { useProjectFileUrl, useResourcePackFiles } from '@/state/useResourcePacks'
import { clientAssets, withReplacedTextures, type ClientAssets } from './assets'

/**
 * The imported client assets for the project's version, or null before an import, with the game's
 * textures the project's resource packs redraw drawn as the packs draw them: a vanilla block or item
 * looks as it does in the game.
 */
export function useClientAssets(): ClientAssets | null {
  const assets = useGameClientAssets()
  const packs = useResourcePackFiles()
  const url = useProjectFileUrl()
  return assets && withReplacedTextures(assets, replacedUrls(packs, url))
}

let last: { packs: unknown; url: unknown; urls: Record<string, string> } | null = null

/** Each redrawn texture's picture URL by name (`block/stone`), worked out once however many icons ask. */
function replacedUrls(
  packs: Record<string, ResourcePackFile>,
  url: (path: string) => string,
): Record<string, string> {
  if (last?.packs === packs && last.url === url) return last.urls
  const urls: Record<string, string> = {}
  for (const [pack, file] of Object.entries(packs)) {
    for (const [name, picture] of Object.entries(file.textures ?? {})) {
      // Only one pack may redraw a texture (format says so); the first keeps it meanwhile.
      if (typeof picture === 'string' && !(name in urls))
        urls[name] = url(resourcePackTexturePath(pack, picture))
    }
  }
  last = { packs, url, urls }
  return urls
}

/** The client assets as the game ships them, none redrawn: what a resource pack starts a replacement from. */
export function useGameClientAssets(): ClientAssets | null {
  const { backend } = useApp()
  const minecraft = useWorkspace((s) => s.minecraft)
  const client = useWorkspace((s) => s.cache?.client ?? false)
  return client && minecraft ? clientAssets(backend, minecraft) : null
}
