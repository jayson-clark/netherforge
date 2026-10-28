/**
 * Format's terrain preview (the server's own generator), typed: the one entry point the editor's worker and
 * `netherforge preview` both draw through.
 */
import * as nf from '@netherforge/format'
import type { TerrainPreviewResult, TerrainStructureInput } from '@netherforge/format/types'

/** A terrain compiled once and drawn as often as asked (see [terrainPreviewer]). */
export interface TerrainPreviewer {
  /** A [cells] by [cells] map, a cell every [step] blocks from ([x0], [z0]), of a world from [minY] up to [maxY]. */
  map(
    seed: string,
    x0: number,
    z0: number,
    cells: number,
    step: number,
    minY: number,
    maxY: number,
  ): TerrainPreviewResult
  /** [width] columns of ground from block [from] along x (at z [at]), or along z (at x [at]) when [alongX] is false. */
  slice(
    seed: string,
    alongX: boolean,
    at: number,
    from: number,
    width: number,
    minY: number,
    maxY: number,
  ): TerrainPreviewResult
  /** Lets go of the Lua state its script ran in; nothing more is drawn. */
  close(): void
}

/**
 * The server's own generator for a terrain's text: the terrain, biomes, ores and decorations a world made
 * with it and [seed] has, so the preview draws the world rather than a guess at it. [seed] is a whole number as
 * text. [structures] are the project structures its decorations name, read from their `.nbt` files, by the name
 * the file gives each: one left out isn't placed. [sources] is the Lua a file with a `script` runs (its
 * `terrain/<id>.lua` and the modules', by project path: [scriptPaths]); such a file needs [loadLua] to have
 * finished first. [blocks] are the project's block ids, so a script that names one places it without listing it.
 */
export function terrainPreviewer(
  id: string,
  text: string,
  structures: Record<string, TerrainStructureInput> = {},
  sources: Record<string, string> = {},
  blocks: readonly string[] = [],
): TerrainPreviewer {
  const previewer = nf.terrainPreviewer(
    id,
    text,
    Object.keys(structures).length ? JSON.stringify(structures) : null,
    Object.keys(sources).length ? JSON.stringify(sources) : null,
    blocks.length ? JSON.stringify(blocks) : null,
  )
  return {
    map: (seed, x0, z0, cells, step, minY, maxY) =>
      JSON.parse(previewer.map(seed, x0, z0, cells, step, minY, maxY)) as TerrainPreviewResult,
    slice: (seed, alongX, at, from, width, minY, maxY) =>
      JSON.parse(
        previewer.slice(seed, alongX, at, from, width, minY, maxY),
      ) as TerrainPreviewResult,
    close: () => previewer.close(),
  }
}

/**
 * Loads the Lua a terrain's script runs on in JS (Lua 5.4 compiled to WebAssembly, wasmoon's: the same Lua
 * as the server's), once: [wasm] is where its `glue.wasm` is (`./lua.browser.ts` and `./lua.node.ts` say).
 */
export function loadLua(wasm: string): Promise<void> {
  return nf.loadTerrainLua(wasm) as Promise<unknown> as Promise<void>
}

/** The Lua a terrain [id]'s script may run, among a project's [files] (project paths): its own and the modules'. */
export function scriptPaths(id: string, files: readonly string[]): string[] {
  const own = `terrain/${id}.lua`
  return files
    .filter((it) => it === own || (it.startsWith('modules/') && it.endsWith('.lua')))
    .sort()
}

/** Whether a terrain's text hands stages to a script (its `script`), so its preview runs Lua. */
export function hasScript(text: string): boolean {
  try {
    const parsed = JSON.parse(text) as { script?: unknown }
    return typeof parsed === 'object' && parsed !== null && parsed.script != null
  } catch {
    return false
  }
}
