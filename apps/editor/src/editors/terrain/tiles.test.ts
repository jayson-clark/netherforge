import { describe, expect, it, vi } from 'vitest'
import type { TerrainMap, TerrainPreviewResult } from '@/core/format'
import type { PreviewFile, TileAnswer, TileRequest } from './worker/core'
import type { PreviewClient } from './worker/client'
import {
  TILE,
  TileScheduler,
  stepFor,
  tileKey,
  tilesIn,
  wantedTiles,
  type TileLane,
  type TileSource,
} from './tiles'

describe('tile maths', () => {
  it('picks cells about [detail] pixels across, in powers of two', () => {
    expect(stepFor(1, 4)).toBe(4)
    expect(stepFor(2, 4)).toBe(2)
    expect(stepFor(4, 4)).toBe(1)
    expect(stepFor(16, 4)).toBe(1)
    expect(stepFor(0.3, 4)).toBe(16)
    expect(stepFor(1 / 1000, 8)).toBe(256)
  })

  it('lists the tiles a view shows, nearest its middle first', () => {
    const span = TILE * 4
    const view = { x: span / 2, z: span / 2, zoom: 1, width: 10, height: 10 }
    expect(tilesIn(view, 4)).toEqual([{ step: 4, tx: 0, tz: 0 }])
    const ring = tilesIn(view, 4, 1)
    expect(ring).toHaveLength(9)
    expect(ring[0]).toEqual({ step: 4, tx: 0, tz: 0 })
  })

  it('wants the coarse level first, then the shown one, then a ring round it, each once', () => {
    const view = { x: 0, z: 0, zoom: 1, width: 600, height: 400 }
    const wanted = wantedTiles(view, 4)
    const keys = wanted.map(tileKey)
    expect(new Set(keys).size).toBe(keys.length)
    expect(wanted[0]!.step).toBe(16)
    const firstFine = wanted.findIndex((it) => it.step === 4)
    expect(wanted.slice(firstFine).every((it) => it.step === 4)).toBe(true)
  })
})

const FILE = { id: 'hills' } as PreviewFile
const source = (fileKey: string, seed = '1'): TileSource => ({
  fileKey,
  file: FILE,
  world: { seed, minY: -64, maxY: 320 },
})

function mapOf(request: TileRequest, height: number): TerrainMap {
  const n = request.cells
  return {
    type: 'map',
    x0: request.x0,
    z0: request.z0,
    cells: n,
    step: request.step,
    heights: new Array(n * n).fill(height),
    areas: new Array(n * n).fill(0),
    areaNames: ['plains'],
    biomes: ['minecraft:plains'],
    seaLevel: 63,
    minY: -64,
    maxY: 320,
    decorationNames: [],
    decorations: [],
    decorationsShown: true,
    scriptErrors: [],
  } as unknown as TerrainMap
}

/** Lanes that answer only when the test says, recording what they were asked. */
function fakeLanes(count: number) {
  const asked: {
    lane: number
    request: TileRequest
    answer: (a: TileAnswer) => void
    fail: (e: Error) => void
  }[] = []
  let disposed = 0
  const lanes = (): TileLane[] =>
    Array.from({ length: count }, (_, lane) => ({
      run: <T>(
        _fileKey: string,
        file: PreviewFile,
        ask: (c: PreviewClient, f?: PreviewFile) => Promise<T>,
      ) =>
        ask(
          {
            tile: (request: TileRequest) =>
              new Promise<TileAnswer>((answer, fail) =>
                asked.push({ lane, request, answer, fail }),
              ),
            slice: () => Promise.reject(new Error('unused')),
            dispose: () => {},
          },
          file,
        ),
      dispose: () => {
        disposed += 1
      },
    }))
  return { asked, lanes, disposed: () => disposed }
}

const pixels = (request: TileRequest) => new Uint8ClampedArray(request.cells * request.cells * 4)
const answerMap = (request: TileRequest, height: number): TileAnswer => ({
  result: mapOf(request, height),
  pixels: pixels(request),
})

describe('the tile scheduler', () => {
  const at = (tx: number, tz: number) => ({ step: 4, tx, tz })

  it('keeps every lane drawing the first tile wanted that it has not got', async () => {
    const fake = fakeLanes(2)
    const scheduler = new TileScheduler(fake.lanes)
    scheduler.open()
    scheduler.setSource(source('f1'))
    scheduler.want([at(0, 0), at(1, 0), at(2, 0)])
    expect(fake.asked.map((it) => it.request.x0)).toEqual([0, TILE * 4])
    // Wanted elsewhere now: what's drawing finishes, then the new first comes next.
    scheduler.want([at(5, 5), at(0, 0)])
    fake.asked[0]!.answer(answerMap(fake.asked[0]!.request, 70))
    await vi.waitFor(() => expect(fake.asked).toHaveLength(3))
    expect(fake.asked[2]!.request).toMatchObject({ x0: 5 * TILE * 4, z0: 5 * TILE * 4 })
    expect(scheduler.get(at(0, 0))?.heights[0]).toBe(70)
    expect(scheduler.cellAt(1, 1, [4])?.tile.heights[0]).toBe(70)
    expect(scheduler.meta?.areaNames).toEqual(['plains'])
  })

  it('keeps an old tile drawn until the new file replaces it, and draws it again', async () => {
    const fake = fakeLanes(1)
    const scheduler = new TileScheduler(fake.lanes)
    scheduler.open()
    scheduler.setSource(source('f1'))
    scheduler.want([at(0, 0)])
    fake.asked[0]!.answer(answerMap(fake.asked[0]!.request, 70))
    await vi.waitFor(() => expect(scheduler.get(at(0, 0))).toBeDefined())
    const old = scheduler.get(at(0, 0))!
    scheduler.setSource(source('f2'))
    expect(scheduler.get(at(0, 0))).toBe(old)
    expect(scheduler.current(old)).toBe(false)
    await vi.waitFor(() => expect(fake.asked).toHaveLength(2))
    fake.asked[1]!.answer(answerMap(fake.asked[1]!.request, 80))
    await vi.waitFor(() => expect(scheduler.get(at(0, 0))?.heights[0]).toBe(80))
    expect(scheduler.current(scheduler.get(at(0, 0))!)).toBe(true)
    // The same file and world again is no new generation.
    const gen = scheduler.gen
    scheduler.setSource(source('f2'))
    expect(scheduler.gen).toBe(gen)
  })

  it('stops and says so when the file cannot be drawn, until it changes', async () => {
    const fake = fakeLanes(1)
    const scheduler = new TileScheduler(fake.lanes)
    scheduler.open()
    scheduler.setSource(source('bad'))
    scheduler.want([at(0, 0), at(1, 0)])
    const failed = {
      type: 'failed',
      problems: [{ message: 'base must be from' }],
    } as unknown as TerrainPreviewResult
    fake.asked[0]!.answer({ result: failed, pixels: null })
    await vi.waitFor(() => expect(scheduler.status.failed).toHaveLength(1))
    expect(fake.asked).toHaveLength(1)
    scheduler.setSource(source('good'))
    expect(scheduler.status.failed).toBeNull()
    await vi.waitFor(() => expect(fake.asked).toHaveLength(2))
  })

  it('says why a worker failed, and stops its workers when closed', async () => {
    const fake = fakeLanes(1)
    const scheduler = new TileScheduler(fake.lanes)
    scheduler.open()
    scheduler.setSource(source('f1'))
    scheduler.want([at(0, 0)])
    fake.asked[0]!.fail(new Error('it stopped'))
    await vi.waitFor(() => expect(scheduler.status.error).toBe('it stopped'))
    scheduler.close()
    expect(fake.disposed()).toBe(1)
    // Opened again (as React does in development): new workers, the same tiles.
    scheduler.open()
    scheduler.setSource(source('f1', '2'))
    await vi.waitFor(() => expect(fake.asked).toHaveLength(2))
  })
})
