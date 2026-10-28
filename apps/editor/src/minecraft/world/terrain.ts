/**
 * The map preview's worker side: which chunks to draw around a
 * point, reading them from region files, and meshing them with the shared
 * block mesher (`mc/blockMesh.ts`), one mesh per chunk, nearest first.
 *
 * Where things run: the main thread only fetches files (through the
 * backend's path-scoped `nfproject://`, as every binary resource is read)
 * and bakes block states (that needs the client's models and the texture
 * pixels, which need the DOM); everything per block (decompressing, NBT,
 * unpacking, meshing) runs here, in a worker, so a big map never blocks the
 * UI. The worker asks for both over messages (`TerrainIo`), so `TerrainCore`
 * runs the same in vitest with plain functions.
 *
 * Memory stays bounded by the view, not the map: decoded chunks are kept for
 * the chunks drawn plus a ring of one around them (a chunk's border faces
 * need its neighbours' blocks: a face against a neighbour's solid block is
 * left out, as inside a chunk); region files only while some kept chunk is
 * in them. Everything else is dropped when the view moves. The main thread
 * keeps meshes a little past the view (`TerrainClient`) and says which it
 * dropped, so moving back and forth near an edge doesn't re-mesh.
 */
import {
  meshBlocks,
  meshBuffers,
  type BakedState,
  type BakedStates,
  type BlockMesh,
  type BlockSource,
} from '@/minecraft/client/blockMesh'
import { readNbt } from '@netherforge/structures/nbt'
import { blockIndex, parseChunk, SECTION_SIZE, type Chunk } from './chunk'
import {
  chunkPayload,
  decompressChunk,
  externalChunkFileName,
  regionFileName,
  regionOf,
  RegionError,
} from './region'

/** How a chunk turned out: drawn, nothing to draw, never saved, or unreadable (with why). */
export type ChunkStatus = 'drawn' | 'empty' | 'missing' | 'failed'

export interface ChunkResult {
  cx: number
  cz: number
  status: ChunkStatus
  /** Block positions relative to the chunk's corner (x and z 0–16), y as in the world. */
  mesh: BlockMesh | null
  error?: string
}

/** What the worker needs from the main thread. */
export interface TerrainIo {
  /** A file in the dimension's region folder by name (`r.0.0.mca`, `c.3.-2.mcc`); null when there's none. */
  readFile(name: string): Promise<Uint8Array | null>
  /** Block states baked for meshing, in the order asked. */
  bake(states: string[]): Promise<BakedStates>
  /** A chunk is done. */
  chunk(result: ChunkResult): void
  /** Every chunk in the view is done (for view [seq]). */
  idle(seq: number): void
}

export const chunkKey = (cx: number, cz: number) => `${cx},${cz}`

/**
 * The chunks within [radius] of chunk ([cx], [cz]), a disc rather than a
 * square, nearest first (so the middle shows up first).
 */
export function chunksAround(cx: number, cz: number, radius: number): [number, number][] {
  const out: [number, number, number][] = []
  const reach = (radius + 0.5) ** 2
  for (let dz = -radius; dz <= radius; dz += 1) {
    for (let dx = -radius; dx <= radius; dx += 1) {
      const distance = dx * dx + dz * dz
      if (distance <= reach) out.push([cx + dx, cz + dz, distance])
    }
  }
  out.sort((a, b) => a[2] - b[2])
  return out.map(([x, z]) => [x, z])
}

const SIDES: [number, number][] = [
  [1, 0],
  [-1, 0],
  [0, 1],
  [0, -1],
]

interface Section {
  /** State numbers by palette index. */
  states: Int32Array
  indices: Uint8Array | Uint16Array | null
}

/** A chunk's blocks as state numbers; `sections[y - lowest]` is section height y. */
type Loaded =
  | { kind: 'blocks'; lowest: number; sections: (Section | undefined)[] }
  | { kind: 'empty' }
  | { kind: 'missing' }
  | { kind: 'failed'; error: string }

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

export class TerrainCore {
  /** State text → number, and back; numbers index `baked` and `occludes`. */
  private numbers = new Map<string, number>()
  private texts: string[] = []
  /** Baked state per number: undefined until asked for and answered. */
  private baked: (BakedState | null | undefined)[] = []
  private occludes: number[] = []
  private asked = 0
  private baking: Promise<void> = Promise.resolve()

  private chunks = new Map<string, Promise<Loaded>>()
  private regions = new Map<string, Promise<Uint8Array | null>>()
  /** Chunks the main thread has a result for (and keeps until it says it dropped them). */
  private sent = new Set<string>()
  private wanted = new Set<string>()
  private queue: [number, number][] = []
  private seq = 0
  private running = false

  constructor(private io: TerrainIo) {}

  /**
   * Draw the chunks within [radius] of ([cx], [cz]); the main thread no
   * longer has [dropped]. Chunks already sent aren't sent again.
   */
  view(cx: number, cz: number, radius: number, dropped: string[] = [], seq = this.seq + 1) {
    this.seq = seq
    for (const key of dropped) this.sent.delete(key)
    const around = chunksAround(cx, cz, radius)
    this.wanted = new Set(around.map(([x, z]) => chunkKey(x, z)))
    const keep = new Set(this.wanted)
    for (const [x, z] of around) for (const [dx, dz] of SIDES) keep.add(chunkKey(x + dx, z + dz))
    for (const key of this.chunks.keys()) if (!keep.has(key)) this.chunks.delete(key)
    const regions = new Set<string>()
    for (const key of keep) {
      const [x, z] = key.split(',').map(Number) as [number, number]
      regions.add(regionFileName(regionOf(x), regionOf(z)))
    }
    for (const name of this.regions.keys()) if (!regions.has(name)) this.regions.delete(name)
    this.queue = around.filter(([x, z]) => !this.sent.has(chunkKey(x, z)))
    void this.pump()
  }

  /** How many chunks' blocks and region files it holds now (for tests of the bounds). */
  held() {
    return { chunks: this.chunks.size, regions: this.regions.size, states: this.texts.length }
  }

  private async pump() {
    if (this.running) return
    this.running = true
    try {
      for (;;) {
        const next = this.queue.shift()
        if (!next) break
        const [cx, cz] = next
        const key = chunkKey(cx, cz)
        if (this.sent.has(key) || !this.wanted.has(key)) continue
        const result = await this.build(cx, cz)
        // The view may have moved on while it was built.
        if (!this.wanted.has(key) || this.sent.has(key)) continue
        this.sent.add(key)
        this.io.chunk(result)
      }
    } finally {
      this.running = false
    }
    this.io.idle(this.seq)
  }

  private async build(cx: number, cz: number): Promise<ChunkResult> {
    const loaded = await this.load(cx, cz)
    if (loaded.kind === 'missing') return { cx, cz, status: 'missing', mesh: null }
    if (loaded.kind === 'empty') return { cx, cz, status: 'empty', mesh: null }
    if (loaded.kind === 'failed') {
      return { cx, cz, status: 'failed', mesh: null, error: loaded.error }
    }
    const neighbours = await Promise.all(SIDES.map(([dx, dz]) => this.load(cx + dx, cz + dz)))
    await this.bakeAll()
    try {
      const mesh = meshBlocks(this.source(loaded, neighbours), this.baked, this.occludes)
      return mesh.faces > 0
        ? { cx, cz, status: 'drawn', mesh }
        : { cx, cz, status: 'empty', mesh: null }
    } catch (error) {
      return { cx, cz, status: 'failed', mesh: null, error: message(error) }
    }
  }

  /** A chunk's blocks, looking into [neighbours] (east, west, south, north) past its edges. */
  private source(chunk: Loaded & { kind: 'blocks' }, neighbours: Loaded[]): BlockSource {
    const baked = this.baked
    // Block coordinates are integers: y >> 4 is the section (floored, below zero too).
    const stateIn = (loaded: Loaded | undefined, x: number, y: number, z: number) => {
      if (loaded?.kind !== 'blocks') return -1
      const section = loaded.sections[(y >> 4) - loaded.lowest]
      if (!section) return -1
      return section.states[section.indices ? section.indices[blockIndex(x, y & 15, z)]! : 0]!
    }
    return {
      forEach(visit) {
        chunk.sections.forEach((section, at) => {
          // A section of one state that draws nothing (air) has nothing to visit.
          if (!section || (!section.indices && !baked[section.states[0]!])) return
          const base = (chunk.lowest + at) * SECTION_SIZE
          for (let i = 0; i < 4096; i += 1) {
            const state = section.states[section.indices ? section.indices[i]! : 0]!
            if (!baked[state]) continue
            visit(i & 15, base + (i >> 8), (i >> 4) & 15, state)
          }
        })
      },
      stateAt(x, y, z) {
        if (x >= SECTION_SIZE) return stateIn(neighbours[0], x - SECTION_SIZE, y, z)
        if (x < 0) return stateIn(neighbours[1], x + SECTION_SIZE, y, z)
        if (z >= SECTION_SIZE) return stateIn(neighbours[2], x, y, z - SECTION_SIZE)
        if (z < 0) return stateIn(neighbours[3], x, y, z + SECTION_SIZE)
        return stateIn(chunk, x, y, z)
      },
    }
  }

  private load(cx: number, cz: number): Promise<Loaded> {
    const key = chunkKey(cx, cz)
    let pending = this.chunks.get(key)
    if (!pending) {
      pending = this.read(cx, cz).catch((error: unknown): Loaded => ({
        kind: 'failed',
        error: message(error),
      }))
      this.chunks.set(key, pending)
    }
    return pending
  }

  private async read(cx: number, cz: number): Promise<Loaded> {
    const name = regionFileName(regionOf(cx), regionOf(cz))
    let region = this.regions.get(name)
    if (!region) {
      region = this.io.readFile(name)
      this.regions.set(name, region)
    }
    const bytes = await region
    if (!bytes) return { kind: 'missing' }
    const payload = chunkPayload(bytes, cx, cz)
    if (!payload) return { kind: 'missing' }
    let data = payload.data
    if (payload.external) {
      const file = externalChunkFileName(cx, cz)
      const external = await this.io.readFile(file)
      if (!external) throw new RegionError(`it's stored in ${file}, which isn't there`)
      data = external
    }
    const chunk = parseChunk(await readNbt(await decompressChunk(payload.compression, data), null))
    return chunk.full ? this.blocksOf(chunk) : { kind: 'empty' }
  }

  private blocksOf(chunk: Chunk): Loaded {
    const lowest = Math.min(0, ...chunk.sections.map((it) => it.y))
    const sections: (Section | undefined)[] = []
    for (const section of chunk.sections) {
      sections[section.y - lowest] = {
        states: Int32Array.from(section.palette, (state) => this.number(state)),
        indices: section.indices,
      }
    }
    return { kind: 'blocks', lowest, sections }
  }

  private number(state: string) {
    let number = this.numbers.get(state)
    if (number === undefined) {
      number = this.texts.length
      this.texts.push(state)
      this.numbers.set(state, number)
    }
    return number
  }

  /** Bakes every state seen so far that hasn't been asked for, after any bake under way. */
  private bakeAll(): Promise<void> {
    if (this.asked < this.texts.length) {
      const from = this.asked
      const states = this.texts.slice(from)
      this.asked = this.texts.length
      this.baking = this.baking.then(async () => {
        let result: BakedStates | null = null
        try {
          result = await this.io.bake(states)
        } catch {
          // Drawn as nothing rather than asked for forever.
        }
        states.forEach((_, i) => {
          this.baked[from + i] = result?.states[i] ?? null
          this.occludes[from + i] = result?.occludes[i] ?? 0
        })
      })
    }
    return this.baking
  }
}

// ---- messages --------------------------------------------------------------

/** Main thread → worker. */
export type ToTerrain =
  | { type: 'view'; seq: number; cx: number; cz: number; radius: number; dropped: string[] }
  | { type: 'file'; id: number; bytes: Uint8Array | null }
  | { type: 'baked'; id: number; baked: BakedStates | null }

/** Worker → main thread. */
export type FromTerrain =
  | { type: 'file'; id: number; name: string }
  | { type: 'bake'; id: number; states: string[] }
  | { type: 'chunk'; result: ChunkResult }
  | { type: 'idle'; seq: number }

/** One end of a message channel: a worker's `self`, a `Worker`, or a test's in-memory pair. */
export interface TerrainPort<Out, In> {
  post(message: Out, transfer?: Transferable[]): void
  listen(handler: (message: In) => void): void
}

/** The worker's side: a `TerrainCore` whose I/O is messages to the main thread. */
export function serveTerrain(port: TerrainPort<FromTerrain, ToTerrain>): TerrainCore {
  const pending = new Map<number, (value: never) => void>()
  let next = 0
  const ask = <T>(message: { type: 'file'; name: string } | { type: 'bake'; states: string[] }) =>
    new Promise<T>((resolve) => {
      const id = (next += 1)
      pending.set(id, resolve as (value: never) => void)
      port.post({ ...message, id })
    })
  const core = new TerrainCore({
    readFile: (name) => ask<Uint8Array | null>({ type: 'file', name }),
    bake: async (states) => {
      const baked = await ask<BakedStates | null>({ type: 'bake', states })
      if (!baked) throw new Error("the block states couldn't be baked")
      return baked
    },
    // The mesh's buffers move to the main thread rather than being copied.
    chunk: (result) =>
      port.post({ type: 'chunk', result }, result.mesh ? meshBuffers(result.mesh) : []),
    idle: (seq) => port.post({ type: 'idle', seq }),
  })
  port.listen((message) => {
    switch (message.type) {
      case 'view':
        core.view(message.cx, message.cz, message.radius, message.dropped, message.seq)
        return
      case 'file':
      case 'baked': {
        const resolve = pending.get(message.id)
        pending.delete(message.id)
        resolve?.((message.type === 'file' ? message.bytes : message.baked) as never)
        return
      }
    }
  })
  return core
}
