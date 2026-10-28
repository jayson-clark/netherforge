/**
 * The map preview's main-thread side: tells the worker
 * (`terrain.ts`) where the camera is, answers what it asks for (a region
 * file's bytes, block states baked with the client's models), and keeps the
 * chunk meshes it sends for the view to draw. Meshes are kept a little past
 * the view's radius (`KEEP_MARGIN`), so panning back and forth near the edge
 * doesn't rebuild them, and dropped beyond it, so memory follows the radius
 * rather than how far the camera has been. React reads it as an external
 * store (`subscribe`, `getSnapshot`).
 */
import type { BakedStates } from '@/minecraft/client/blockMesh'
import {
  chunkKey,
  chunksAround,
  type ChunkResult,
  type FromTerrain,
  type TerrainPort,
  type ToTerrain,
} from './terrain'

/** Chunks past the radius whose meshes are kept anyway. */
export const KEEP_MARGIN = 2

/** What the worker's questions are answered with. */
export interface TerrainSource {
  /**
   * A file in the dimension's region folder, by name; null when there's
   * none. Its buffer is handed to the worker, so it must be the caller's own.
   */
  readFile(name: string): Promise<Uint8Array | null>
  bake(states: string[]): Promise<BakedStates>
}

export type TerrainConnection = TerrainPort<ToTerrain, FromTerrain> & { close(): void }

export interface TerrainSnapshot {
  /** Chunks with something to draw. */
  drawn: ChunkResult[]
  faces: number
  /** Chunks saved with nothing to draw (all air, or not finished generating). */
  empty: number
  /** Chunks never saved. */
  missing: number
  failed: number
  /** Why the first unreadable chunk couldn't be read. */
  error: string | null
  /** Whether the worker is still working through the view. */
  loading: boolean
}

const EMPTY: TerrainSnapshot = {
  drawn: [],
  faces: 0,
  empty: 0,
  missing: 0,
  failed: 0,
  error: null,
  loading: false,
}

/** How long chunk arrivals are gathered into one update, so a burst is one render. */
const BATCH_MS = 50

export class TerrainClient {
  private port: TerrainConnection | null = null
  private results = new Map<string, ChunkResult>()
  private dropped: string[] = []
  private last: { cx: number; cz: number; radius: number } | null = null
  private keep = new Set<string>()
  /** The connection the last view went to. */
  private viewed: TerrainConnection | null = null
  private seq = 0
  private idleSeq = 0
  private listeners = new Set<() => void>()
  private snapshot: TerrainSnapshot = EMPTY
  private timer: ReturnType<typeof setTimeout> | null = null

  constructor(
    private source: TerrainSource,
    private connect: () => TerrainConnection,
  ) {}

  /** Starts the worker (again, after `stop`) and sends it the last view. */
  start() {
    if (this.port) return
    const port = this.connect()
    this.port = port
    port.listen((message) => this.receive(port, message))
    if (this.last) this.view(this.last.cx, this.last.cz, this.last.radius)
  }

  /** Stops the worker and forgets every mesh. */
  stop() {
    this.port?.close()
    this.port = null
    this.results.clear()
    this.dropped = []
    this.publish()
  }

  /** Draw the chunks within [radius] of chunk ([cx], [cz]). */
  view(cx: number, cz: number, radius: number) {
    const same =
      this.last && this.last.cx === cx && this.last.cz === cz && this.last.radius === radius
    this.last = { cx, cz, radius }
    if (!this.port || (same && this.viewed === this.port)) return
    this.viewed = this.port
    this.keep = new Set(chunksAround(cx, cz, radius + KEEP_MARGIN).map(([x, z]) => chunkKey(x, z)))
    for (const key of this.results.keys()) {
      if (!this.keep.has(key)) {
        this.results.delete(key)
        this.dropped.push(key)
      }
    }
    this.seq += 1
    this.port.post({ type: 'view', seq: this.seq, cx, cz, radius, dropped: this.dropped })
    this.dropped = []
    this.publish()
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getSnapshot = () => this.snapshot

  private receive(port: TerrainConnection, message: FromTerrain) {
    if (port !== this.port) return
    switch (message.type) {
      case 'file':
        void this.source
          .readFile(message.name)
          .catch(() => null)
          .then((bytes) => {
            if (port !== this.port) return
            port.post({ type: 'file', id: message.id, bytes }, bytes ? [bytes.buffer] : [])
          })
        return
      case 'bake':
        void this.source.bake(message.states).then(
          (baked) => port === this.port && port.post({ type: 'baked', id: message.id, baked }),
          () => port === this.port && port.post({ type: 'baked', id: message.id, baked: null }),
        )
        return
      case 'chunk': {
        const key = chunkKey(message.result.cx, message.result.cz)
        // Sent for a view the camera has since left: tell the worker it's gone.
        if (!this.keep.has(key)) this.dropped.push(key)
        else this.results.set(key, message.result)
        this.schedule()
        return
      }
      case 'idle':
        this.idleSeq = Math.max(this.idleSeq, message.seq)
        this.schedule()
        return
    }
  }

  private schedule() {
    this.timer ??= setTimeout(() => {
      this.timer = null
      this.publish()
    }, BATCH_MS)
  }

  private publish() {
    const drawn: ChunkResult[] = []
    let faces = 0
    let empty = 0
    let missing = 0
    let failed = 0
    let error: string | null = null
    for (const result of this.results.values()) {
      if (result.status === 'drawn') {
        drawn.push(result)
        faces += result.mesh?.faces ?? 0
      } else if (result.status === 'empty') empty += 1
      else if (result.status === 'missing') missing += 1
      else {
        failed += 1
        error ??= `chunk ${result.cx}, ${result.cz}: ${result.error ?? 'unreadable'}`
      }
    }
    this.snapshot = {
      drawn,
      faces,
      empty,
      missing,
      failed,
      error,
      loading: this.port !== null && this.idleSeq < this.seq,
    }
    for (const listener of this.listeners) listener()
  }
}

/** A connection to a new terrain worker. */
export function terrainWorker(): TerrainConnection {
  const worker = new Worker(new URL('./terrain.worker.ts', import.meta.url), { type: 'module' })
  return {
    post: (message, transfer) => worker.postMessage(message, transfer ?? []),
    listen: (handler) => {
      worker.onmessage = (event: MessageEvent<FromTerrain>) => handler(event.data)
    },
    close: () => worker.terminate(),
  }
}
