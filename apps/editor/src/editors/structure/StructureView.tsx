/**
 * The structure preview, in the shared viewport (`Viewport3D`): its blocks
 * drawn with the client's models (one merged mesh per texture, hidden faces
 * left out), the box it spans, and the camera orbiting its middle. Without
 * imported client assets it shows the box and says why, as the centity
 * viewport does.
 *
 * The mesh is built inside the scene, keyed by the file's version, so a tab
 * switch (which reads the file again) doesn't build it again.
 */
import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import type { Vec3 } from '@/core/format'
import { bakeStates } from '@/minecraft/client/bake'
import type { BlockMesh } from '@/minecraft/client/blockMesh'
import { MeshGroups } from '@/minecraft/client/MeshGroups'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { buildStructureMesh } from '@/minecraft/structure/mesh'
import type { Structure } from '@netherforge/structures/structure'
import { useWorkspace } from '@/state/providers'
import { useAsyncResult } from '@/ui/useAsync'
import { framing } from '@/editors/shared/viewport/camera'
import { ViewportNote } from '@/editors/shared/viewport/parts'
import { Viewport3D } from '@/editors/shared/viewport/Viewport3D'

/** What building the mesh came to: the mesh, or why there's none. */
export interface Built {
  mesh: BlockMesh | null
  error: string | null
}

export function StructureView({
  path,
  structure,
  version,
  built,
  onBuilt,
}: {
  path: string
  structure: Structure
  /** Which version of the file [structure] was read from: a new one builds a new mesh. */
  version: string
  built: Built | null
  /** Told what the preview draws once its mesh is built. */
  onBuilt: (built: Built) => void
}) {
  const assets = useClientAssets()
  const minecraft = useWorkspace((s) => s.minecraft)
  const size = structure.size
  const reach = Math.max(...size, 1)

  return (
    <Viewport3D
      kind="structure"
      path={path}
      label="Structure preview"
      scene={{
        content: <StructureScene structure={structure} version={version} onBuilt={onBuilt} />,
        home: framing([0, 0, 0], size),
        near: 0.05,
        far: Math.max(500, reach * 20),
        grid: { cell: 1, section: 16, fade: reach * 6 + 40 },
      }}
    >
      {!assets && (
        <ViewportNote role="note">
          Blocks show once Minecraft {minecraft ?? ''} client assets are imported (Settings →
          Minecraft); until then, only the structure's box.
        </ViewportNote>
      )}
      {built?.error && (
        <ViewportNote warning role="note">
          Couldn't draw the blocks: {built.error}
        </ViewportNote>
      )}
    </Viewport3D>
  )
}

function StructureScene({
  structure,
  version,
  onBuilt,
}: {
  structure: Structure
  version: string
  onBuilt: (built: Built) => void
}) {
  const assets = useClientAssets()
  const gameData = useWorkspace((s) => s.gameData)
  const result = useAsyncResult(`${assets?.key ?? 'none'}|${version}`, () =>
    assets
      ? bakeStates(assets, structure.palette, (id) => gameData?.blocks?.[id]?.defaults).then(
          (baked) => buildStructureMesh(structure, baked),
        )
      : null,
  )
  const mesh = result?.value ?? null
  const error = result?.error ?? null
  // Told again when the editor's tab comes back (it hands over a new [onBuilt]).
  useEffect(() => onBuilt({ mesh, error }), [mesh, error, onBuilt])

  return (
    <>
      <Bounds size={structure.size} />
      {assets && mesh && <MeshGroups groups={mesh.groups} assets={assets} />}
    </>
  )
}

/** The box the structure spans, as edges. */
function Bounds({ size }: { size: Vec3 }) {
  const [sx, sy, sz] = size
  const geometry = useMemo(
    () =>
      new THREE.EdgesGeometry(
        new THREE.BoxGeometry(Math.max(sx, 0.01), Math.max(sy, 0.01), Math.max(sz, 0.01)),
      ),
    [sx, sy, sz],
  )
  useEffect(() => () => geometry.dispose(), [geometry])
  return (
    <lineSegments geometry={geometry} position={[sx / 2, sy / 2, sz / 2]}>
      <lineBasicMaterial color="#5b6b8c" />
    </lineSegments>
  )
}
