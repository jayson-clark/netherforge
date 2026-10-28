import { describe, expect, it, vi } from 'vitest'
import { occludedSides, type BakedState, type BakedStates } from '@/minecraft/client/blockMesh'
import { bakeFaces } from '@netherforge/models/geometry'
import { writeNbt } from '@netherforge/structures/nbt'
import {
  chunkRoot,
  regionFile,
  sectionIndices,
  type FixtureChunk,
  type FixtureSection,
} from '@/testing/regionFixtures'
import { COMPRESSION, regionFileName, regionOf } from './region'
import {
  chunkKey,
  chunksAround,
  serveTerrain,
  TerrainCore,
  type ChunkResult,
  type FromTerrain,
  type ToTerrain,
} from './terrain'
import { KEEP_MARGIN, TerrainClient, type TerrainConnection } from './terrainClient'

const all = (texture: string) =>
  Object.fromEntries(
    (['down', 'up', 'north', 'south', 'west', 'east'] as const).map((face) => [
      face,
      { texture, cullface: face },
    ]),
  )

const cube = (texture: string): BakedState => ({
  faces: bakeFaces([{ from: [0, 0, 0], to: [16, 16, 16], faces: all(texture) }], {}),
})

/** Bakes like the main thread would: anything with "air" in it draws nothing, glass is see-through. */
function bake(states: string[]): Promise<BakedStates> {
  const baked = states.map((state) => (state.includes('air') ? null : cube(`test:block/${state}`)))
  return Promise.resolve({
    states: baked,
    occludes: baked.map((state) => occludedSides(state, (texture) => !texture.includes('glass'))),
  })
}

/** A full section of one state at section height 0. */
const solid = (state: string): FixtureSection => ({ y: 0, palette: [state] })

const chunk = (
  cx: number,
  cz: number,
  sections: FixtureSection[],
  extra: Partial<FixtureChunk> = {},
): FixtureChunk => ({ cx, cz, root: chunkRoot({ x: cx, z: cz, sections }), ...extra })

/** Region files for [chunks], grouped by region, keyed by file name. */
async function regions(chunks: FixtureChunk[]) {
  const byRegion = new Map<string, FixtureChunk[]>()
  for (const it of chunks) {
    const name = regionFileName(regionOf(it.cx), regionOf(it.cz))
    byRegion.set(name, [...(byRegion.get(name) ?? []), it])
  }
  const files = new Map<string, Uint8Array>()
  for (const [name, list] of byRegion) files.set(name, await regionFile(list))
  return files
}

/** A core over [files], collecting what it sends. */
function coreOver(files: Map<string, Uint8Array>) {
  const results = new Map<string, ChunkResult>()
  const reads: string[] = []
  const baked: string[][] = []
  let idle = 0
  const core = new TerrainCore({
    readFile: (name) => {
      reads.push(name)
      return Promise.resolve(files.get(name) ?? null)
    },
    bake: (states) => {
      baked.push(states)
      return bake(states)
    },
    chunk: (result) => results.set(chunkKey(result.cx, result.cz), result),
    idle: (seq) => {
      idle = seq
    },
  })
  return { core, results, reads, baked, idle: () => idle }
}

/** Until [done] holds (the worker answers on real messages, not a clock). */
const until = (done: () => boolean) => vi.waitFor(() => expect(done()).toBe(true))

describe('which chunks a view draws', () => {
  it('takes a disc around the middle, nearest first', () => {
    expect(chunksAround(0, 0, 0)).toEqual([[0, 0]])
    const one = chunksAround(5, -3, 1)
    expect(one).toHaveLength(9)
    expect(one[0]).toEqual([5, -3])
    // A radius of 2 leaves out the far corners.
    const two = chunksAround(0, 0, 2)
    expect(two).toHaveLength(21)
    expect(two).not.toContainEqual([2, 2])
    const distance = ([x, z]: [number, number]) => x * x + z * z
    expect(two.map(distance)).toEqual([...two.map(distance)].sort((a, b) => a - b))
  })
})

describe('the terrain worker', () => {
  it('culls faces against the neighbouring chunk, across chunk and region borders', async () => {
    const files = await regions([
      chunk(0, 0, [solid('stone')]),
      chunk(1, 0, [solid('stone')]),
      // Region 0 ends at chunk 31; 32 is in r.1.0.
      chunk(31, 5, [solid('stone')]),
      chunk(32, 5, [solid('stone')]),
    ])
    const { core, results, idle } = coreOver(files)
    core.view(0, 0, 0, [], 1)
    await until(() => idle() === 1)
    // A 16³ cube is 6 × 256 faces; the side against chunk 1's stone goes.
    expect(results.get('0,0')).toMatchObject({ status: 'drawn', mesh: { faces: 5 * 256 } })
    expect(results.get('0,0')!.mesh!.culled).toBe(16 * 16 * 16 * 6 - 6 * 256 + 256)

    core.view(31, 5, 0, [], 2)
    await until(() => idle() === 2)
    expect(results.get('31,5')!.mesh!.faces).toBe(5 * 256)
    // Its mesh is relative to its own corner.
    const positions = results.get('31,5')!.mesh!.groups[0]!.positions
    expect(Math.max(...positions.filter((_, i) => i % 3 === 0))).toBe(16)
  })

  it('draws the faces toward a missing, unfinished or see-through neighbour', async () => {
    const files = await regions([
      chunk(0, 0, [solid('stone')]),
      // East: not finished generating, so drawn as nothing; west: glass.
      {
        cx: 1,
        cz: 0,
        root: chunkRoot({ x: 1, z: 0, sections: [solid('stone')], status: 'minecraft:noise' }),
      },
      chunk(-1, 0, [solid('glass')]),
      // North (z - 1): all air.
      chunk(0, -1, [solid('air')]),
    ])
    const { core, results, idle } = coreOver(files)
    core.view(0, 0, 1, [], 1)
    await until(() => idle() === 1)
    expect(results.get('0,0')!.mesh!.faces).toBe(6 * 256)
    expect(results.get('1,0')).toMatchObject({ status: 'empty', mesh: null })
    expect(results.get('0,-1')).toMatchObject({ status: 'empty' })
    // The stone beside the glass hides the glass's face toward it. Glass hides nothing, not
    // even glass behind it (the game's same-block rule for glass isn't modelled).
    expect(results.get('-1,0')!.mesh!.faces).toBe(6 * 4096 - 256)
    // Never saved: in a region that exists, and in one that doesn't.
    expect(results.get('1,1')).toMatchObject({ status: 'missing' })
    expect(results.get('-1,-1')).toMatchObject({ status: 'missing' })
  })

  it('meshes only what the palette says is there, across sections', async () => {
    // A floor at y = 0 and a pillar going up through the next section.
    const files = await regions([
      chunk(0, 0, [
        {
          y: 0,
          palette: ['air', 'stone'],
          indices: sectionIndices((x, y, z) => (y === 0 || (x === 4 && z === 4) ? 1 : 0)),
        },
        {
          y: 1,
          palette: ['air', 'stone'],
          indices: sectionIndices((x, _y, z) => (x === 4 && z === 4 ? 1 : 0)),
        },
        { y: -1, palette: ['air'] },
      ]),
    ])
    const { core, results, idle } = coreOver(files)
    core.view(0, 0, 0, [], 1)
    await until(() => idle() === 1)
    const mesh = results.get('0,0')!.mesh!
    // Floor: top minus the pillar's foot, bottom, four edges; pillar: 31 blocks, four sides each and a top.
    expect(mesh.faces).toBe(255 + 256 + 4 * 16 + 31 * 4 + 1)
    const ys = mesh.groups[0]!.positions.filter((_, i) => i % 3 === 1)
    expect(Math.max(...ys)).toBe(32)
    expect(Math.min(...ys)).toBe(0)
  })

  it('says which chunks it could not read, and why, and carries on', async () => {
    const files = await regions([
      chunk(0, 0, [solid('stone')]),
      { cx: 1, cz: 0, bytes: new Uint8Array(40).fill(9) },
      // Moved out to c.0.1.mcc, which is there; and c.-1.0.mcc, which isn't.
      { cx: 0, cz: 1, bytes: new Uint8Array(0), compression: COMPRESSION.none | 0x80 },
    ])
    files.set(
      'r.-1.0.mca',
      await regionFile([{ cx: -1, cz: 0, bytes: new Uint8Array(0), compression: 0x82 }]),
    )
    files.set(
      'c.0.1.mcc',
      await writeNbt(chunkRoot({ x: 0, z: 1, sections: [solid('stone')] }), null),
    )
    const { core, results, idle } = coreOver(files)
    core.view(0, 0, 1, [], 1)
    await until(() => idle() === 1)
    expect(results.get('1,0')).toMatchObject({ status: 'failed', mesh: null })
    expect(results.get('1,0')!.error).toBeTruthy()
    expect(results.get('0,1')).toMatchObject({ status: 'drawn' })
    expect(results.get('-1,0')).toMatchObject({ status: 'failed' })
    expect(results.get('-1,0')!.error).toMatch(/c\.-1\.0\.mcc/)
    // An unreadable neighbour hides nothing: the stone's faces toward it are drawn.
    expect(results.get('0,0')!.mesh!.faces).toBe(5 * 256)
  })

  it('reads each region file once, bakes each state once, and holds only around the view', async () => {
    const chunks: FixtureChunk[] = []
    for (let cx = -3; cx <= 40; cx += 1)
      chunks.push(chunk(cx, 0, [solid(cx % 2 ? 'stone' : 'dirt')]))
    const { core, results, reads, baked, idle } = coreOver(await regions(chunks))
    core.view(0, 0, 2, [], 1)
    await until(() => idle() === 1)
    expect(reads.filter((it) => it === 'r.0.0.mca')).toHaveLength(1)
    expect(baked.flat().sort()).toEqual(['dirt', 'stone'])
    const first = results.size
    expect(first).toBe(21)
    // The view, and the neighbours of the chunks that have blocks (one each end of the row),
    // from the four regions around chunk 0, 0.
    expect(core.held()).toMatchObject({ chunks: 21 + 2, regions: 4 })

    // Far away: the old chunks and regions are let go.
    core.view(36, 0, 2, [], 2)
    await until(() => idle() === 2)
    expect(core.held()).toMatchObject({ chunks: 21 + 2, regions: 2 })
    expect(reads.filter((it) => it === 'r.1.0.mca')).toHaveLength(1)

    // Coming back sends again only what the main thread said it dropped.
    results.clear()
    core.view(0, 0, 2, ['0,0'], 3)
    await until(() => idle() === 3)
    expect([...results.keys()]).toEqual(['0,0'])
  })
})

/** Two ends of an in-memory channel, delivering asynchronously like a worker. */
function channel() {
  let toMain: ((message: FromTerrain) => void) | null = null
  let toWorker: ((message: ToTerrain) => void) | null = null
  let closed = false
  const main: TerrainConnection = {
    post: (message) => setTimeout(() => !closed && toWorker?.(message)),
    listen: (handler) => {
      toMain = handler
    },
    close: () => {
      closed = true
    },
  }
  serveTerrain({
    post: (message) => setTimeout(() => !closed && toMain?.(message)),
    listen: (handler) => {
      toWorker = handler
    },
  })
  return main
}

describe('the terrain client', () => {
  it('shows what the worker meshed, and keeps meshes a little past the view', async () => {
    const chunks: FixtureChunk[] = []
    for (let cx = -12; cx <= 12; cx += 1) chunks.push(chunk(cx, 0, [solid('stone')]))
    chunks.push({ cx: 0, cz: 2, bytes: new Uint8Array(20) })
    const files = await regions(chunks)
    const connections: TerrainConnection[] = []
    const client = new TerrainClient(
      {
        // A copy: the client hands the buffer to the worker.
        readFile: (name) => Promise.resolve(files.get(name)?.slice() ?? null),
        bake,
      },
      () => {
        const connection = channel()
        connections.push(connection)
        return connection
      },
    )
    const changes = vi.fn()
    client.subscribe(changes)
    client.start()
    client.view(0, 0, 2)
    await vi.waitFor(() => expect(client.getSnapshot().loading).toBe(false), { timeout: 5000 })
    let snapshot = client.getSnapshot()
    // Five chunks along z = 0, a broken one at 0, 2, the rest of the disc never saved.
    expect(snapshot.drawn.map((it) => it.cx).sort((a, b) => a - b)).toEqual([-2, -1, 0, 1, 2])
    expect(snapshot.failed).toBe(1)
    expect(snapshot.error).toMatch(/^chunk 0, 2: /)
    expect(snapshot.missing).toBe(21 - 5 - 1)
    // The row goes on past the view, so even its end chunks' sides along it are hidden.
    expect(snapshot.faces).toBe(5 * 4 * 256)
    expect(changes).toHaveBeenCalled()

    // Moving by less than the margin keeps every mesh; past it, the far ones go.
    client.view(KEEP_MARGIN, 0, 2)
    await vi.waitFor(() => expect(client.getSnapshot().loading).toBe(false))
    snapshot = client.getSnapshot()
    expect(snapshot.drawn.map((it) => it.cx).sort((a, b) => a - b)).toEqual([-2, -1, 0, 1, 2, 3, 4])
    client.view(10, 0, 2)
    await vi.waitFor(() => expect(client.getSnapshot().loading).toBe(false))
    expect(client.getSnapshot().drawn.every((it) => it.cx >= 10 - 2 - KEEP_MARGIN)).toBe(true)

    // Back home: the dropped chunks come back.
    client.view(0, 0, 2)
    await vi.waitFor(() =>
      expect(client.getSnapshot().drawn.filter((it) => it.cx <= 2)).toHaveLength(5),
    )

    // Stopped (the screen closed), then started again (React's strict mode): a new worker, the same view.
    client.stop()
    expect(client.getSnapshot().drawn).toEqual([])
    client.start()
    await vi.waitFor(() => expect(client.getSnapshot().drawn).toHaveLength(5))
    expect(connections).toHaveLength(2)
    client.stop()
  })
})
