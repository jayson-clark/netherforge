/**
 * `client/vanilla.json` (docs/format/project.md#clientvanillajson): what only
 * the client knows and the built pack needs: the game's own blockstate files
 * of the blocks custom blocks are held in (tripwire: a model per shape), and
 * the names of the HUD's sprites (which `hud.hide` blanks and `hud.replace` redraws) and the sizes of the game's
 * textures a resource pack's `textures` redraws (every container screen's, and the others the project names: the
 * game has thousands, so only those), and which of the models a resource pack's `models` redraws the game has. The
 * editor writes
 * it from the import, as it writes `fonts/default.json`; it's committed and
 * deployed. Pure: the workspace decides when to ask, which is when validation
 * says something needs it (`block.vanilla-looks`, `resource_pack.hud-hide`, `resource_pack.vanilla-missing`).
 */
import type { Backend } from '@/core/backend/types'
import { canonicalizeModel, VANILLA_FILE, VANILLA_LOOK_BLOCKS } from '@/core/format'

export { VANILLA_FILE }

/** Reads one of the imported client's JSON files (`minecraft/client/assets`' `ClientAssets` is one). */
export interface ClientJson {
  json<T>(path: string): Promise<T | null>
}

/** [ClientJson] for [version]'s import, through the backend's asset protocol; uncached, as it's read rarely. */
export function clientJson(backend: Pick<Backend, 'assetUrl'>, version: string): ClientJson {
  return {
    async json<T>(path: string): Promise<T | null> {
      try {
        const response = await fetch(backend.assetUrl(version, path))
        return response.ok ? ((await response.json()) as T) : null
      } catch {
        return null
      }
    },
  }
}

/** What `client/vanilla.json` holds, from the client. */
export interface VanillaLooks {
  /** The client's blockstate file of each block that needs one. */
  blockstates: Record<string, unknown>
  /** The HUD's sprites (`hud/heart/full`), from the import's index; none from an import older than the list. */
  sprites: string[]
  /** The container screens' textures (`gui/container/inventory`) and the others asked for, with their `[width, height]`. */
  textures: Record<string, number[]>
  /** The names asked for that the game has no texture by; none from an import that listed only the screens'. */
  unknownTextures: string[]
  /** The game's models asked for (`block/red_mushroom`) that the client has. */
  models: string[]
  /** The models asked for that the client has no file of. */
  unknownModels: string[]
}

/** The container screens' textures, which the file always lists. */
const SCREENS = 'gui/container/'

/**
 * What [assets] has of [VanillaLooks], with the sizes of the game's textures [replaced] names (the outline's
 * `replacedTextures`) and which of the models [models] names it has (`replacedModels`); empty without an import.
 */
export async function vanillaLooks(
  assets: ClientJson,
  replaced: readonly string[] = [],
  models: readonly string[] = [],
): Promise<VanillaLooks> {
  const blockstates: Record<string, unknown> = {}
  for (const block of VANILLA_LOOK_BLOCKS) {
    const colon = block.indexOf(':')
    const json = await assets.json<unknown>(
      `assets/${block.slice(0, colon)}/blockstates/${block.slice(colon + 1)}.json`,
    )
    if (json && typeof json === 'object') blockstates[block] = json
  }
  // The import's index (`ClientIndex`), which lists the HUD's sprites.
  const index = await assets.json<{ sprites?: unknown; textures?: unknown }>('index.json')
  const sprites = Array.isArray(index?.sprites)
    ? index.sprites.filter((it): it is string => typeof it === 'string')
    : []
  const all: Record<string, number[]> = {}
  if (index?.textures && typeof index.textures === 'object') {
    for (const [name, size] of Object.entries(index.textures)) {
      if (Array.isArray(size) && size.length === 2 && size.every((it) => Number.isInteger(it))) {
        all[name] = size
      }
    }
  }
  const wanted = new Set(replaced)
  const textures: Record<string, number[]> = {}
  for (const [name, size] of Object.entries(all)) {
    if (name.startsWith(SCREENS) || wanted.has(name)) textures[name] = size
  }
  // An import from before the editor listed every texture has only the screens': it can't say what the game lacks.
  const everything = Object.keys(all).some((it) => !it.startsWith(SCREENS))
  const unknownTextures = everything ? [...wanted].filter((it) => !(it in all)).sort() : []
  // A model is a file of the import (every model is extracted), so it's there or the game has none: without an
  // import at all nothing is known, and nothing is said.
  const known: string[] = []
  const unknownModels: string[] = []
  if (index) {
    for (const name of [...new Set(models)].sort()) {
      const json = await assets.json<unknown>(`assets/minecraft/models/${name}.json`)
      if (json && typeof json === 'object') known.push(name)
      else unknownModels.push(name)
    }
  }
  return { blockstates, sprites, textures, unknownTextures, models: known, unknownModels }
}

/**
 * The text to write for [minecraft] from the client's [looks], when it
 * differs from what's on disk ([diskText], undefined when there's no file);
 * null when there's nothing to do or nothing to write from.
 */
export function vanillaToWrite(
  minecraft: string | null,
  looks: VanillaLooks,
  diskText: string | undefined,
): string | null {
  if (
    !minecraft ||
    (Object.keys(looks.blockstates).length === 0 &&
      looks.sprites.length === 0 &&
      Object.keys(looks.textures).length === 0 &&
      looks.unknownTextures.length === 0 &&
      looks.models.length === 0 &&
      looks.unknownModels.length === 0)
  )
    return null
  const text = canonicalizeModel('vanilla', VANILLA_FILE, {
    minecraft,
    blockstates: looks.blockstates,
    sprites: looks.sprites,
    textures: looks.textures,
    unknownTextures: looks.unknownTextures,
    models: looks.models,
    unknownModels: looks.unknownModels,
  }).text
  return text !== undefined && text !== null && text !== diskText ? text : null
}
