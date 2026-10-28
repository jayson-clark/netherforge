/**
 * The main thread's end of the terrain preview: module workers of its own (Vite bundles each with its own copy of
 * format's JS build). A `Lane` is one worker asked one thing at a time, which is sent the file only when it may not
 * have it compiled yet. The map's tiles are shared among a few lanes (`tiles.ts`); the slice has a lane of its own,
 * asked for the newest slice only (`LatestPreview`), so cutting it again never queues up work.
 */
import { wrap, type Remote } from 'comlink'
import { useEffect, useRef, useState } from 'react'
import type { TerrainPreviewResult } from '@/core/format'
import type { PreviewCore, PreviewFile, SliceRequest, TileAnswer, TileRequest } from './core'

export interface PreviewClient {
  tile(request: TileRequest): Promise<TileAnswer>
  slice(request: SliceRequest): Promise<TerrainPreviewResult>
  /** Stops the worker; nothing more is answered. */
  dispose(): void
}

/** A worker of its own. */
export function terrainPreviewWorker(): PreviewClient {
  const worker = new Worker(new URL('./preview.worker.ts', import.meta.url), {
    type: 'module',
    name: 'terrain-preview',
  })
  const api: Remote<PreviewCore> = wrap<PreviewCore>(worker)
  // A worker that fails to start (or dies) answers nothing: every request fails instead of waiting forever.
  const failed = new Promise<never>((_, reject) => {
    worker.addEventListener('error', (event) =>
      reject(new Error(event.message || 'The terrain preview stopped')),
    )
  })
  failed.catch(() => {})
  return {
    tile: (request) => Promise.race([api.tile(request) as Promise<TileAnswer>, failed]),
    slice: (request) => Promise.race([api.slice(request) as Promise<TerrainPreviewResult>, failed]),
    dispose: () => worker.terminate(),
  }
}

/** How many workers draw the map's tiles: a few, leaving the rest of the machine to the editor and the slice. */
export function tileLaneCount(): number {
  const cores = typeof navigator === 'undefined' ? 4 : (navigator.hardwareConcurrency ?? 4)
  return Math.max(1, Math.min(4, Math.floor(cores / 2)))
}

/**
 * One worker, asked one thing at a time (the caller waits for each). It's sent the file only when it may not have it
 * compiled (the first time, after it changes, after the worker was started over); one that fails is started over.
 */
export class Lane {
  private client: PreviewClient
  private sent: string | null = null

  constructor(private readonly connect: () => PreviewClient) {
    this.client = connect()
  }

  run<T>(
    fileKey: string,
    file: PreviewFile,
    ask: (client: PreviewClient, file: PreviewFile | undefined) => Promise<T>,
  ): Promise<T> {
    const client = this.client
    const resend = this.sent !== fileKey
    return ask(client, resend ? file : undefined).then(
      (answer) => {
        if (client === this.client) this.sent = fileKey
        return answer
      },
      (error: unknown) => {
        if (client === this.client) {
          client.dispose()
          this.client = this.connect()
          this.sent = null
        }
        throw error
      },
    )
  }

  dispose(): void {
    this.client.dispose()
  }
}

/** What [useSlicePreview] has: the newest answer, and whether a newer request is being drawn. */
export interface PreviewState<R, A> {
  answer: A | null
  /** The request [answer] is for. */
  drawn: R | null
  drawing: boolean
  /** Why the worker gave no answer, when it failed. */
  error: string | null
}

/**
 * Draws the newest request only: one is drawn at a time, and while it is, a newer one waits in the place of any that
 * was waiting. [draw] fails when its worker did (which [Lane] starts over for the next).
 */
export class LatestPreview<R, A> {
  private busy = false
  private waiting: R | null = null
  private state: PreviewState<R, A> = { answer: null, drawn: null, drawing: false, error: null }
  private closed = false

  constructor(
    private readonly draw: (request: R) => Promise<A>,
    private readonly changed: (state: PreviewState<R, A>) => void,
  ) {}

  request(request: R | null): void {
    this.waiting = request
    this.pump()
  }

  close(): void {
    this.closed = true
  }

  private set(state: PreviewState<R, A>): void {
    this.state = state
    if (!this.closed) this.changed(state)
  }

  private pump(): void {
    const next = this.waiting
    if (this.busy || this.closed || next === null) return
    this.waiting = null
    this.busy = true
    if (!this.state.drawing) this.set({ ...this.state, drawing: true })
    this.draw(next)
      .then(
        (answer) => this.set({ answer, drawn: next, drawing: this.waiting !== null, error: null }),
        (error: unknown) =>
          this.set({
            ...this.state,
            drawing: this.waiting !== null,
            error: error instanceof Error ? error.message : String(error),
          }),
      )
      .finally(() => {
        this.busy = false
        this.pump()
      })
  }
}

/** The slice to draw: the file, and the request without it (its lane adds the file when the worker needs it). */
export interface SliceAsk {
  file: PreviewFile
  request: Omit<SliceRequest, 'file'>
}

/**
 * The slice [ask] says (null for none), drawn by a worker this component owns ([connect] makes it: tests pass
 * one that answers in the same thread).
 */
export function useSlicePreview(
  ask: SliceAsk | null,
  connect: () => PreviewClient = terrainPreviewWorker,
): PreviewState<SliceAsk, TerrainPreviewResult> {
  const [state, setState] = useState<PreviewState<SliceAsk, TerrainPreviewResult>>({
    answer: null,
    drawn: null,
    drawing: false,
    error: null,
  })
  const queue = useRef<LatestPreview<SliceAsk, TerrainPreviewResult> | null>(null)
  const latest = useRef(ask)
  // Made first (effects run in order), so the request below always has one to go to.
  useEffect(() => {
    const lane = new Lane(connect)
    const made = new LatestPreview<SliceAsk, TerrainPreviewResult>(
      ({ file, request }) =>
        lane.run(request.fileKey, file, (client, sent) => client.slice({ ...request, file: sent })),
      setState,
    )
    queue.current = made
    made.request(latest.current)
    return () => {
      made.close()
      lane.dispose()
      if (queue.current === made) queue.current = null
    }
  }, [connect])
  useEffect(() => {
    latest.current = ask
    queue.current?.request(ask)
  }, [ask])
  return state
}
