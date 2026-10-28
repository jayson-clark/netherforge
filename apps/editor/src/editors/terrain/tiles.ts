/**
 * The map as tiles: squares of TILE by TILE cells, a cell every `step` blocks (a power of two), on a grid fixed in the
 * world, so a tile once drawn is kept while the view pans and zooms around it. The view says which tiles it wants
 * (`wantedTiles`: a coarse level first, which fills the screen quickly, then the level it shows, nearest the middle
 * first), and the `TileScheduler` keeps every worker drawing the first one it hasn't got. A tile of an older file or
 * seed is kept (and drawn) until the new one replaces it, so an edit never blanks the map.
 */
import type { TerrainMap, TerrainPreviewResult, TerrainScriptError } from '@/core/format'
import type { PreviewFile, PreviewWorld, TileAnswer, TileRequest } from './worker/core'
import type { PreviewClient } from './worker/client'

/** Cells a tile is across. */
export const TILE = 32
/** The finest and coarsest cells, in blocks. */
export const MIN_STEP = 1
export const MAX_STEP = 256
/** How close the map can be (screen pixels a block is across). */
export const MIN_ZOOM = 1 / 16
export const MAX_ZOOM = 16
/** How many tiles are kept: about 10 kB each. */
const MAX_TILES = 2048
/** How much coarser the level that fills the screen first is. */
const COARSE = 4

export interface TilePos {
  step: number
  tx: number
  tz: number
}

export const tileKey = (pos: TilePos) => `${pos.step}:${pos.tx}:${pos.tz}`

/** Where the map looks: the block at its middle, how close, and how big it is on screen (CSS pixels). */
export interface MapCamera {
  x: number
  z: number
  zoom: number
}
export interface Viewport extends MapCamera {
  width: number
  height: number
}

export const clampZoom = (zoom: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))

/** The cell size (blocks) for a map [zoom] close whose cells should be about [detail] screen pixels. */
export function stepFor(zoom: number, detail: number): number {
  const step = 2 ** Math.ceil(Math.log2(detail / zoom) - 1e-9)
  return Math.min(MAX_STEP, Math.max(MIN_STEP, step))
}

/** The blocks a view shows: its left and top edge, and how many blocks across and down. */
export function viewBounds(view: Viewport) {
  const across = view.width / view.zoom
  const down = view.height / view.zoom
  return { left: view.x - across / 2, top: view.z - down / 2, across, down }
}

/** The tiles of cells [step] that a view shows (and [margin] tiles round it), nearest its middle first. */
export function tilesIn(view: Viewport, step: number, margin = 0): TilePos[] {
  const { left, top, across, down } = viewBounds(view)
  const span = TILE * step
  const tx0 = Math.floor(left / span) - margin
  const tx1 = Math.floor((left + across) / span) + margin
  const tz0 = Math.floor(top / span) - margin
  const tz1 = Math.floor((top + down) / span) + margin
  const out: (TilePos & { d: number })[] = []
  for (let tz = tz0; tz <= tz1; tz += 1) {
    for (let tx = tx0; tx <= tx1; tx += 1) {
      const dx = (tx + 0.5) * span - view.x
      const dz = (tz + 0.5) * span - view.z
      out.push({ step, tx, tz, d: dx * dx + dz * dz })
    }
  }
  return out.sort((a, b) => a.d - b.d).map(({ step, tx, tz }) => ({ step, tx, tz }))
}

/**
 * What a view wants drawn, first first: the tile under the pointer ([focus], a block) at the level shown, the coarse
 * level over the screen, the level shown, then a ring round it.
 */
export function wantedTiles(
  view: Viewport,
  detail: number,
  focus: { x: number; z: number } | null = null,
): TilePos[] {
  const step = stepFor(view.zoom, detail)
  const coarse = Math.min(MAX_STEP, step * COARSE)
  const seen = new Set<string>()
  const out: TilePos[] = []
  const add = (list: TilePos[]) => {
    for (const pos of list) {
      const key = tileKey(pos)
      if (seen.has(key)) continue
      seen.add(key)
      out.push(pos)
    }
  }
  if (focus) {
    const span = TILE * step
    add([{ step, tx: Math.floor(focus.x / span), tz: Math.floor(focus.z / span) }])
  }
  if (coarse !== step) add(tilesIn(view, coarse))
  add(tilesIn(view, step))
  add(tilesIn(view, step, 1))
  return out
}

/** A tile drawn: its cells' ground and areas, the decorations that start in it, and its pixels. */
export interface Tile {
  /** Which file and world it was drawn for ([TileScheduler.gen] then). */
  gen: number
  x0: number
  z0: number
  cells: number
  step: number
  heights: Int32Array
  areas: Uint16Array
  /** Pairs of a cell and the decoration that starts in it. */
  decorations: number[]
  decorationsShown: boolean
  /** RGBA, one pixel per cell. */
  pixels: Uint8ClampedArray
}

/** The names a map's numbers stand for, the same in every tile of a file. */
export interface TileMeta {
  areaNames: string[]
  biomes: string[]
  decorationNames: string[]
  seaLevel: number
}

type Problems = Extract<TerrainPreviewResult, { type: 'failed' }>['problems']

/** What's wrong, for the editor to say: nothing can be drawn, the workers failed, or the script did. */
export interface TileStatus {
  failed: Problems | null
  error: string | null
  scriptErrors: TerrainScriptError[]
}

const OK: TileStatus = { failed: null, error: null, scriptErrors: [] }

/** What the tiles are drawn from. */
export interface TileSource {
  fileKey: string
  file: PreviewFile
  world: PreviewWorld
}

/** A worker the scheduler sends tiles to ([Lane]'s shape). */
export interface TileLane {
  run<T>(
    fileKey: string,
    file: PreviewFile,
    ask: (client: PreviewClient, file: PreviewFile | undefined) => Promise<T>,
  ): Promise<T>
  dispose(): void
}

/** A tile's square, as asked of a worker. */
export const tileRequest = (pos: TilePos): Pick<TileRequest, 'x0' | 'z0' | 'cells' | 'step'> => ({
  x0: pos.tx * TILE * pos.step,
  z0: pos.tz * TILE * pos.step,
  cells: TILE,
  step: pos.step,
})

/** A worker's answer as a [Tile]. */
function tileOf(gen: number, map: TerrainMap, pixels: Uint8ClampedArray): Tile {
  return {
    gen,
    x0: map.x0,
    z0: map.z0,
    cells: map.cells,
    step: map.step,
    heights: Int32Array.from(map.heights),
    areas: Uint16Array.from(map.areas),
    decorations: map.decorations,
    decorationsShown: map.decorationsShown,
    pixels,
  }
}

/** Keeps the lanes drawing the tiles wanted most, and the tiles they drew. */
export class TileScheduler {
  /** Goes up whenever a tile arrives or the source changes: what a view redraws on. */
  version = 0
  /** Goes up whenever the file or world changes. */
  gen = 0
  status: TileStatus = OK
  /** The names of the newest file's numbers, once one of its tiles has come. */
  meta: TileMeta | null = null

  private source: TileSource | null = null
  private sourceKey = ''
  private wanted: TilePos[] = []
  private wantedKeys = new Set<string>()
  private tiles = new Map<string, Tile>()
  private flying = new Map<string, number>()
  private busy = new Set<TileLane>()
  private stopped = false
  /** The workers, while open. */
  private lanes: TileLane[] | null = null
  private listeners = new Set<() => void>()

  /** [makeLanes] starts the workers, each time the scheduler is opened. */
  constructor(private readonly makeLanes: () => TileLane[]) {}

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** What the tiles are drawn from; a different file or world starts a new generation. */
  setSource(source: TileSource | null): void {
    const key = source
      ? `${source.fileKey}\n${source.world.seed}\n${source.world.minY}\n${source.world.maxY}`
      : ''
    this.source = source
    if (key === this.sourceKey) return
    this.sourceKey = key
    this.gen += 1
    this.stopped = false
    this.status = OK
    this.emit()
    this.pump()
  }

  /** The tiles to draw, first first; any wanted before and not now is left (once it's drawing, it finishes). */
  want(positions: TilePos[]): void {
    this.wanted = positions
    this.wantedKeys = new Set(positions.map(tileKey))
    this.pump()
  }

  get(pos: TilePos): Tile | undefined {
    return this.tiles.get(tileKey(pos))
  }

  /** Whether [tile] is of the file and world now shown. */
  current(tile: Tile): boolean {
    return tile.gen === this.gen
  }

  /** The cell at block ([x], [z]) in the first of [steps] with a tile there (one of the file now shown first). */
  cellAt(x: number, z: number, steps: number[]): { tile: Tile; index: number } | null {
    let stale: { tile: Tile; index: number } | null = null
    for (const step of steps) {
      const span = TILE * step
      const tile = this.get({ step, tx: Math.floor(x / span), tz: Math.floor(z / span) })
      if (!tile) continue
      const col = Math.floor((x - tile.x0) / step)
      const row = Math.floor((z - tile.z0) / step)
      const found = { tile, index: row * tile.cells + col }
      if (this.current(tile)) return found
      stale ??= found
    }
    return stale
  }

  /** Starts the workers (again, after [close]); the tiles drawn are kept. */
  open(): void {
    if (this.lanes) return
    this.lanes = this.makeLanes()
    this.pump()
  }

  /** Stops the workers: what they were drawing is dropped. */
  close(): void {
    for (const lane of this.lanes ?? []) lane.dispose()
    this.lanes = null
    this.busy.clear()
    this.flying.clear()
  }

  private emit(): void {
    this.version += 1
    for (const listener of this.listeners) listener()
  }

  private setStatus(change: Partial<TileStatus>): void {
    this.status = { ...this.status, ...change }
  }

  private pump(): void {
    const source = this.source
    const lanes = this.lanes
    if (!source || this.stopped || !lanes) return
    for (const lane of lanes) {
      if (this.busy.has(lane)) continue
      const pos = this.wanted.find((it) => {
        const key = tileKey(it)
        return this.flying.get(key) !== this.gen && this.tiles.get(key)?.gen !== this.gen
      })
      if (!pos) return
      this.dispatch(lane, pos, source)
    }
  }

  private dispatch(lane: TileLane, pos: TilePos, source: TileSource): void {
    const key = tileKey(pos)
    const gen = this.gen
    this.flying.set(key, gen)
    this.busy.add(lane)
    lane
      .run(source.fileKey, source.file, (client, file) =>
        client.tile({ fileKey: source.fileKey, file, world: source.world, ...tileRequest(pos) }),
      )
      .then(
        (answer) => {
          if (this.lanes?.includes(lane)) this.answered(key, gen, answer)
        },
        (error: unknown) => {
          if (!this.lanes?.includes(lane) || gen !== this.gen) return
          // A worker that fails on a tile would fail on it again: nothing more until the file or world changes.
          this.stopped = true
          this.setStatus({ error: error instanceof Error ? error.message : String(error) })
          this.emit()
        },
      )
      .finally(() => {
        if (!this.lanes?.includes(lane)) return
        this.busy.delete(lane)
        if (this.flying.get(key) === gen) this.flying.delete(key)
        this.pump()
      })
  }

  private answered(key: string, gen: number, { result, pixels }: TileAnswer): void {
    if (result.type === 'failed') {
      if (gen !== this.gen) return
      this.stopped = true
      this.setStatus({ failed: result.problems })
      this.emit()
      return
    }
    if (result.type !== 'map' || !pixels) return
    const existing = this.tiles.get(key)
    if (existing && existing.gen > gen) return
    this.tiles.delete(key)
    this.tiles.set(key, tileOf(gen, result, pixels))
    if (gen === this.gen) {
      this.meta = {
        areaNames: result.areaNames,
        biomes: result.biomes,
        decorationNames: result.decorationNames,
        seaLevel: result.seaLevel,
      }
      const known = new Set(this.status.scriptErrors.map((it) => `${it.stage}\n${it.message}`))
      const added = result.scriptErrors.filter((it) => !known.has(`${it.stage}\n${it.message}`))
      if (added.length > 0 || this.status.error) {
        this.setStatus({ error: null, scriptErrors: [...this.status.scriptErrors, ...added] })
      }
    }
    this.evict()
    this.emit()
  }

  /** Lets go of the tiles drawn longest ago, past MAX_TILES, keeping any wanted now. */
  private evict(): void {
    if (this.tiles.size <= MAX_TILES) return
    for (const key of this.tiles.keys()) {
      if (this.tiles.size <= MAX_TILES) return
      if (!this.wantedKeys.has(key)) this.tiles.delete(key)
    }
  }
}
