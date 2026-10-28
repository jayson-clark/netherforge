/**
 * A map in 3D, in the shared viewport (`Viewport3D`): its chunks
 * drawn with the client's models, starting at the world's spawn, within a
 * radius the user picks. Moving the camera (right-drag or shift-drag pans
 * the point it orbits) loads the chunks around the new middle and lets far
 * ones go. The work happens in a worker (`terrain.ts`); the scene answers
 * its questions (`TerrainClient`), packs textures into one atlas (one draw
 * call a chunk), and draws a mesh per chunk. Read-only, like the rest of
 * the map's screen.
 *
 * The terrain client lives in the scene, so a tab switch keeps the chunks
 * it loaded; the camera and the radius are the document's view.
 */
import { memo, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { useThree } from '@react-three/fiber'
import type { Vec3 } from '@/core/format'
import type { ClientAssets } from '@/minecraft/client/assets'
import { blockAtlas, type BlockAtlas } from '@/minecraft/client/atlas'
import { bakeStates } from '@/minecraft/client/bake'
import { MeshGroups } from '@/minecraft/client/MeshGroups'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { EditorBar } from '@/editors/shared/EditorLayout'
import { ViewportFallback, ViewportNote } from '@/editors/shared/viewport/parts'
import { Viewport3D } from '@/editors/shared/viewport/Viewport3D'
import { useView, useViewState } from '@/editors/views'
import styles from './MapView.module.css'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { SECTION_SIZE } from '@/minecraft/world/chunk'
import type { ChunkResult } from '@/minecraft/world/terrain'
import {
  KEEP_MARGIN,
  TerrainClient,
  terrainWorker,
  type TerrainSnapshot,
  type TerrainSource,
} from '@/minecraft/world/terrainClient'

/**
 * The furthest the view reaches. What's kept grows with its square (a disc
 * of this radius plus `KEEP_MARGIN`, about 450 chunks at 10), and real
 * terrain is about 4000 faces a chunk, so this keeps meshes to a couple of
 * hundred megabytes at most.
 */
export const MAX_RADIUS = 10
const MIN_RADIUS = 1

const NO_SNAPSHOT: TerrainSnapshot = {
  drawn: [],
  faces: 0,
  empty: 0,
  missing: 0,
  failed: 0,
  error: null,
  loading: false,
}

/** The chunk a block coordinate is in. */
const chunkOf = (block: number) => Math.floor(block / SECTION_SIZE)

export function MapView({
  path,
  crumb,
  regionDir,
  files,
  spawn,
}: {
  /** The map's folder: its tab, and the document its view belongs to. */
  path: string
  crumb: string
  /** The project path of the dimension's region folder. */
  regionDir: string
  /** The files directly in it, with their workspace stamps (so a changed file is read again). */
  files: Record<string, number>
  spawn: [number, number, number] | null
}) {
  const assets = useClientAssets()
  const minecraft = useWorkspace((s) => s.minecraft)
  const { radius } = useViewState('map', path)
  const view = useView('map', path)
  const [snapshot, setSnapshot] = useState(NO_SNAPSHOT)

  const origin: Vec3 = spawn ?? [0, 0, 0]
  const [x, y, z] = origin
  return (
    <>
      <EditorBar title={crumb}>
        {assets && (
          <>
            <label className={styles.radius}>
              View radius
              <input
                type="range"
                min={MIN_RADIUS}
                max={MAX_RADIUS}
                value={radius}
                aria-label="View radius in chunks"
                onChange={(event) => view.update({ radius: Number(event.target.value) })}
              />
              <span aria-hidden>{radius}</span>
            </label>
            <Button
              size="small"
              icon="spawn"
              title="Back to the world's spawn"
              onClick={() => view.update({ camera: null })}
            >
              Spawn
            </Button>
          </>
        )}
      </EditorBar>
      {!assets ? (
        <ViewportFallback>
          <span role="note">
            The map shows once Minecraft {minecraft ?? ''} client assets are imported (Settings →
            Minecraft).
          </span>
        </ViewportFallback>
      ) : (
        <Viewport3D
          kind="map"
          path={path}
          label="Map preview"
          scene={{
            content: (
              <MapScene
                regionDir={regionDir}
                files={files}
                origin={origin}
                radius={radius}
                assets={assets}
                onSnapshot={setSnapshot}
              />
            ),
            home: { position: [x + 40, y + 48, z + 64], target: [x + 0.5, y, z + 0.5] },
            fov: 50,
            near: 0.1,
            far: (MAX_RADIUS + KEEP_MARGIN + 2) * SECTION_SIZE * 4,
            background: '#9fb8d9',
            grid: null,
            // Panning moves along the ground, the way a map is browsed.
            groundPanning: true,
            maxDistance: radius * SECTION_SIZE * 3 + 64,
          }}
        >
          <TerrainStatus snapshot={snapshot} radius={radius} />
        </Viewport3D>
      )}
    </>
  )
}

/**
 * The map's chunks around the point the camera orbits: when it enters
 * another chunk, the terrain client draws around that one.
 */
function MapScene({
  regionDir,
  files,
  origin,
  radius,
  assets,
  onSnapshot,
}: {
  regionDir: string
  files: Record<string, number>
  origin: Vec3
  radius: number
  assets: ClientAssets
  onSnapshot: (snapshot: TerrainSnapshot) => void
}) {
  const { backend } = useApp()
  const gameData = useWorkspace((s) => s.gameData)
  const controls = useThree((s) => s.controls) as unknown as {
    target: { x: number; z: number }
    addEventListener(type: 'change', listener: () => void): void
    removeEventListener(type: 'change', listener: () => void): void
  } | null
  const filesKey = Object.entries(files)
    .map(([name, stamp]) => `${name}@${stamp}`)
    .sort()
    .join('|')

  const atlas = useMemo(() => blockAtlas(assets), [assets])
  const client = useMemo(() => {
    const source: TerrainSource = {
      readFile: (name) =>
        name in files
          ? readProjectBytes(backend, `${regionDir}/${name}`, files[name])
          : Promise.resolve(null),
      bake: async (states) => {
        const baked = await bakeStates(assets, states, (id) => gameData?.blocks?.[id]?.defaults)
        return {
          states: baked.states.map((state) => state && atlas.remap(state)),
          occludes: baked.occludes,
        }
      },
    }
    return new TerrainClient(source, terrainWorker)
    // `files` is described by `filesKey`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assets, atlas, backend, regionDir, filesKey, gameData])

  const centre = useRef({ cx: chunkOf(origin[0]), cz: chunkOf(origin[2]) })
  useEffect(() => {
    client.start()
    return () => client.stop()
  }, [client])
  useEffect(() => {
    client.view(centre.current.cx, centre.current.cz, radius)
  }, [client, radius])

  // The camera's orbit point moved: draw around the chunk it's in now.
  useEffect(() => {
    if (!controls) return
    const moved = () => {
      const cx = chunkOf(controls.target.x)
      const cz = chunkOf(controls.target.z)
      if (cx === centre.current.cx && cz === centre.current.cz) return
      centre.current = { cx, cz }
      client.view(cx, cz, radius)
    }
    moved()
    controls.addEventListener('change', moved)
    return () => controls.removeEventListener('change', moved)
  }, [controls, client, radius])

  const snapshot = useSyncExternalStore(client.subscribe, client.getSnapshot)
  // Told again when the editor's tab comes back (it hands over a new [onSnapshot]).
  useEffect(() => onSnapshot(snapshot), [snapshot, onSnapshot])

  return (
    <>
      <SpawnMarker at={origin} />
      {snapshot.drawn.map((chunk) => (
        <ChunkMesh key={`${chunk.cx},${chunk.cz}`} chunk={chunk} assets={assets} atlas={atlas} />
      ))}
    </>
  )
}

const ChunkMesh = memo(function ChunkMesh({
  chunk,
  assets,
  atlas,
}: {
  chunk: ChunkResult
  assets: ClientAssets
  atlas: BlockAtlas
}) {
  if (!chunk.mesh) return null
  return (
    <MeshGroups
      groups={chunk.mesh.groups}
      assets={assets}
      position={[chunk.cx * SECTION_SIZE, 0, chunk.cz * SECTION_SIZE]}
      material={() => atlas.material}
    />
  )
})

/** Where players appear: a two-block-tall outline. */
function SpawnMarker({ at }: { at: [number, number, number] }) {
  return (
    <mesh position={[at[0] + 0.5, at[1] + 1, at[2] + 0.5]}>
      <boxGeometry args={[0.8, 2, 0.8]} />
      <meshBasicMaterial color="#ffd23f" wireframe />
    </mesh>
  )
}

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`

function TerrainStatus({ snapshot, radius }: { snapshot: TerrainSnapshot; radius: number }) {
  const { drawn, faces, missing, empty, failed, error, loading } = snapshot
  const nothing = !loading && drawn.length === 0
  return (
    <>
      <ViewportNote role="note" aria-label="Chunks drawn">
        {nothing
          ? `No blocks saved within ${plural(radius, 'chunk')} of here.`
          : `Drawing ${plural(drawn.length, 'chunk')} (${faces} faces)${loading ? ', loading more…' : '.'}`}
        {!nothing &&
          missing + empty > 0 &&
          ` ${missing + empty} around ${drawn.length === 1 ? 'it' : 'them'} have nothing to draw.`}
      </ViewportNote>
      {failed > 0 && (
        <ViewportNote warning role="note" aria-label="Unreadable chunks">
          {plural(failed, 'chunk')} can&apos;t be read: {error}
        </ViewportNote>
      )}
    </>
  )
}
