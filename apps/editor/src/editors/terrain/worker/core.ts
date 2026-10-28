/**
 * The terrain preview's work, off the main thread: format's `terrainPreviewer` (the server's own
 * generator) compiled once per file and asked for map tiles and slices. A file with a `script` runs it here too, on
 * Lua 5.4 in WebAssembly (wasmoon's, loaded the first time a file needs it), with the same API and noise as the
 * server. Pure apart from the cache, so vitest drives it directly; `preview.worker.ts` puts it behind Comlink.
 *
 * A tile's pixels are made here too (`mapPixels`), from a map one cell wider on every side and cut back, so the
 * light on a slope is the same at a tile's edge as inside it and tiles meet without a seam.
 */
import { transfer } from 'comlink'
import {
  terrainPreviewer,
  type TerrainMap,
  type TerrainPreviewer,
  type TerrainPreviewResult,
  type TerrainStructureInput,
} from '@/core/format'
import { hasScript, mapPixels } from '@netherforge/terrain-preview'
import { loadBrowserLua } from '@netherforge/terrain-preview/lua.browser'

/** The file a preview draws, as the editor holds it, with the structures it places and the Lua it runs. */
export interface PreviewFile {
  /** The generator's id (`terrain/<id>.json`): what problems are reported against. */
  id: string
  /** The file's text as the editor holds it. */
  text: string
  /** The project structures its decorations name, read from their `.nbt`, by the name the file gives each. */
  structures: Record<string, TerrainStructureInput>
  /** Says when [structures] changed (their paths and stamps), so they needn't be compared. */
  structuresKey: string
  /** The Lua the file's script may run (its `terrain/<id>.lua` and the modules'), by project path: none without a script. */
  sources: Record<string, string>
  /** Says when [sources] changed. */
  sourcesKey: string
  /** The project's block ids: one the script names places without being listed. */
  blocks: readonly string[]
}

/** Says when a [PreviewFile] changed: a worker that has it compiled already is sent only this. */
export const fileKeyOf = (file: PreviewFile) =>
  `${file.id}\n${file.structuresKey}\n${file.sourcesKey}\n${file.blocks.join(',')}\n${file.text}`

/** The world a file is drawn for. */
export interface PreviewWorld {
  /** A whole number as text. */
  seed: string
  /** The world's heights: its lowest block, and one above its highest. */
  minY: number
  maxY: number
}

/** What every request says about the file: its key, and the file itself when the worker may not have it yet. */
interface FileRequest {
  fileKey: string
  file?: PreviewFile
  world: PreviewWorld
}

/** A square of the map: [cells] by [cells] cells, a cell every [step] blocks from ([x0], [z0]). */
export interface TileRequest extends FileRequest {
  x0: number
  z0: number
  cells: number
  step: number
}

/** A tile: the map (or why there's none) and, for a map, its RGBA pixels, one per cell. */
export interface TileAnswer {
  result: TerrainPreviewResult
  pixels: Uint8ClampedArray | null
}

/** [width] columns of ground from block [from] along x (at z [at]), or along z (at x [at]) unless [alongX]. */
export interface SliceRequest extends FileRequest {
  alongX: boolean
  at: number
  from: number
  width: number
}

/** What a worker says when asked by key alone for a file it no longer has: the client sends the file again. */
export const NEEDS_FILE = 'The terrain preview needs the file again'

export interface PreviewCore {
  tile(request: TileRequest): Promise<TileAnswer>
  slice(request: SliceRequest): Promise<TerrainPreviewResult>
}

/**
 * [map] (a map [border] cells wider on every side than wanted) without its border: the cells, heights, areas and
 * decorations of the square inside.
 */
export function cropMap(map: TerrainMap, border: number): TerrainMap {
  if (border === 0) return map
  const n = map.cells
  const m = n - 2 * border
  const heights: number[] = new Array(m * m)
  const areas: number[] = new Array(m * m)
  for (let row = 0; row < m; row += 1) {
    for (let col = 0; col < m; col += 1) {
      const from = (row + border) * n + col + border
      heights[row * m + col] = map.heights[from]!
      areas[row * m + col] = map.areas[from]!
    }
  }
  const decorations: number[] = []
  for (let k = 0; k + 1 < map.decorations.length; k += 2) {
    const cell = map.decorations[k]!
    const row = Math.floor(cell / n) - border
    const col = (cell % n) - border
    if (row >= 0 && row < m && col >= 0 && col < m)
      decorations.push(row * m + col, map.decorations[k + 1]!)
  }
  return {
    ...map,
    x0: map.x0 + border * map.step,
    z0: map.z0 + border * map.step,
    cells: m,
    heights,
    areas,
    decorations,
  }
}

/** RGBA pixels [pixels] (a [n] by [n] picture) without a [border] pixels wide. */
function cropPixels(pixels: Uint8ClampedArray, n: number, border: number): Uint8ClampedArray {
  const m = n - 2 * border
  const out = new Uint8ClampedArray(m * m * 4)
  for (let row = 0; row < m; row += 1) {
    const from = ((row + border) * n + border) * 4
    out.set(pixels.subarray(from, from + m * 4), row * m * 4)
  }
  return out
}

/** [loadLua] loads the Lua a script runs on (the worker's bundle has wasmoon's WebAssembly; tests load Node's). */
export function createPreviewCore(loadLua: () => Promise<void> = loadBrowserLua): PreviewCore {
  // The last file compiled: every tile and slice of it asks the same one again (its script's Lua state too).
  let cached: { key: string; previewer: TerrainPreviewer } | null = null
  let lua: Promise<void> | null = null

  async function previewerFor(request: FileRequest): Promise<TerrainPreviewer> {
    if (cached?.key === request.fileKey) return cached.previewer
    const file = request.file
    if (!file) throw new Error(NEEDS_FILE)
    if (hasScript(file.text)) await (lua ??= loadLua())
    cached?.previewer.close()
    cached = {
      key: request.fileKey,
      previewer: terrainPreviewer(file.id, file.text, file.structures, file.sources, file.blocks),
    }
    return cached.previewer
  }

  return {
    async tile(request) {
      const previewer = await previewerFor(request)
      const { seed, minY, maxY } = request.world
      const { x0, z0, cells, step } = request
      const result = previewer.map(seed, x0 - step, z0 - step, cells + 2, step, minY, maxY)
      if (result.type !== 'map') return { result, pixels: null }
      const pixels = cropPixels(mapPixels(result), cells + 2, 1)
      return transfer({ result: cropMap(result, 1), pixels }, [pixels.buffer])
    },
    async slice(request) {
      const previewer = await previewerFor(request)
      const { seed, minY, maxY } = request.world
      return previewer.slice(
        seed,
        request.alongX,
        request.at,
        request.from,
        request.width,
        minY,
        maxY,
      )
    },
  }
}
