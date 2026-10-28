/**
 * The project's resource packs as the editors use them: selectors over the
 * app's resource packs store (core/store/resourcePacks.ts, worked out once per change), and project files
 * as URLs the webview can load. Glyphs for text are `minecraft/text/glyphs.ts`.
 */
import { createContext, useContext, useMemo } from 'react'
import type { ResourcePackFile, ResourcePackOutline, ResourcePacksPreview } from '@/core/format'
import { fileUrl } from '@/core/store/packages'
import type { PackModels } from '@/core/store/resourcePacks'
import { useApp, useResourcePacks, useWorkspace } from './providers'

/** Every pack that parses, by id (the app's packs store; see store/resourcePacks.ts). */
export const useResourcePackFiles = (): Record<string, ResourcePackFile> =>
  useResourcePacks((s) => s.files)

/** The project's namespace: what the packs store keys its own packs by, bare. */
export const useHomeNamespace = (): string => useResourcePacks((s) => s.namespace)

/**
 * The namespace the document on show names things in, when it's a
 * package's (opened read-only from the explorer's Dependencies): set by the
 * editor area around its view. Null for the project's own documents.
 */
export const DocumentNamespace = createContext<string | null>(null)

/**
 * The namespace references in the document on show resolve in: its
 * package's ([DocumentNamespace]), else the project's.
 */
export function useReferenceNamespace(): string {
  const document = useContext(DocumentNamespace)
  const home = useHomeNamespace()
  return document ?? home
}

const NO_PACK_OUTLINES: Record<string, ResourcePackOutline> = {}

/**
 * What the document on show can name of the packs' entries, by pack id as it names them (`<pack>/<key>`): the
 * project's own packs (its outline's), or for a package's document ([DocumentNamespace]) that package's, from the
 * packs store (keyed `ns:id` there), so a dependency's item or centity offers the looks it uses.
 */
export function useResourcePackOutlines(): Record<string, ResourcePackOutline> {
  const document = useContext(DocumentNamespace)
  const own = useWorkspace((s) => s.outline?.resourcePacks ?? NO_PACK_OUTLINES)
  const files = useResourcePackFiles()
  return useMemo(() => {
    if (document === null) return own
    const prefix = `${document}:`
    const keys = (entries: Record<string, unknown> | undefined) => Object.keys(entries ?? {})
    return Object.fromEntries(
      Object.entries(files)
        .filter(([id]) => id.startsWith(prefix))
        .map(([id, pack]): [string, ResourcePackOutline] => [
          id.slice(prefix.length),
          {
            skins: keys(pack.skins),
            glyphs: keys(pack.glyphs),
            items: keys(pack.items),
            tooltips: keys(pack.tooltips),
            equipment: keys(pack.equipment),
            blocks: keys(pack.blocks),
            sounds: [],
          },
        ]),
    )
  }, [document, own, files])
}

/** Each pack's model files and textures, by pack id: what a model look is drawn from. */
export const useResourcePackModels = (): Record<string, PackModels> =>
  useResourcePacks((s) => s.models)

/** Every pack's skins and glyphs as the server will place them. */
export const useCompiledResourcePacks = (): ResourcePacksPreview =>
  useResourcePacks((s) => s.compiled)

/**
 * A project file's URL that reloads when the file changes; a package's by
 * its package path (`library:resource_packs/gems/textures/gem.png`), as previews of
 * the packages' resources draw them.
 */
export function useProjectFileUrl(): (path: string) => string {
  const { backend } = useApp()
  const stamps = useWorkspace((s) => s.fileStamps)
  const packages = useWorkspace((s) => s.outline?.packages)
  return useMemo(
    () => (path: string) => fileUrl(backend, { packages }, path, stamps[path]),
    [backend, stamps, packages],
  )
}
