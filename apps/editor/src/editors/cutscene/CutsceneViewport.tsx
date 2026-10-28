/**
 * The 3D preview of a cutscene, in the shared viewport (`Viewport3D`): the
 * camera's path as format samples it (the server's own `CameraPath`), a
 * marker on each position key (a click selects it) and the camera itself at
 * the playhead, drawn as the frustum it looks through. There's no world to
 * look at: the path's coordinates are the world's, or measured from where a
 * script plays it.
 */
import { useMemo } from 'react'
import { Line } from '@react-three/drei'
import * as THREE from 'three'
import type { CutsceneFile, CutsceneResult, Vec3 } from '@/core/format'
import { ViewportNote } from '@/editors/shared/viewport/parts'
import { framing } from '@/editors/shared/viewport/camera'
import { BLOCK_GRID } from '@/editors/shared/viewport/scenes'
import { Viewport3D } from '@/editors/shared/viewport/Viewport3D'
import { lookDirection, type CutscenePick } from './ops'

const PATH = '#4da3ff'
const MARKER = '#e3b341'
const SELECTED = '#ffffff'

/** Points the path is drawn through. */
export const TRAIL_POINTS = 96

export function CutsceneViewport({
  path,
  model,
  shot,
  trail,
  selected,
  onSelect,
  onMissed,
}: {
  path: string
  model: CutsceneFile
  /** The camera at the playhead. */
  shot: CutsceneResult
  /** The whole path. */
  trail: CutsceneResult
  selected: CutscenePick | null
  onSelect: (pick: CutscenePick) => void
  onMissed: () => void
}) {
  const points = trail.type === 'trail' ? trail.points : null
  const keys = model.camera?.position
  // Frames the whole path (and a little around it) until the user moves the camera.
  const home = useMemo(() => {
    const all: Vec3[] = [...(points ?? []), ...(keys ?? []).map((it) => it.value)]
    if (all.length === 0) return framing([-4, 60, -4], [4, 68, 4])
    const min: Vec3 = [Infinity, Infinity, Infinity]
    const max: Vec3 = [-Infinity, -Infinity, -Infinity]
    for (const point of all)
      for (let i = 0; i < 3; i += 1) {
        min[i] = Math.min(min[i]!, point[i]!)
        max[i] = Math.max(max[i]!, point[i]!)
      }
    return framing(min, max)
  }, [points, keys])

  return (
    <Viewport3D
      kind="cutscene"
      path={path}
      label="Cutscene preview"
      scene={{
        content: (
          <CutsceneScene
            points={points}
            keys={(keys ?? []).map((it) => it.value)}
            shot={shot}
            selected={selected?.track === 'position' ? selected.index : null}
            onSelect={(index) => onSelect({ track: 'position', index })}
          />
        ),
        home,
        grid: BLOCK_GRID,
        onMissed,
        far: 5000,
        maxDistance: 2000,
      }}
    >
      {trail.type === 'failed' && (
        <ViewportNote warning role="note">
          This cutscene has errors, so there's no path to show. See Problems.
        </ViewportNote>
      )}
      <ViewportNote role="note">
        The path is drawn in the world's coordinates; a script may move it with its `origin`.
      </ViewportNote>
    </Viewport3D>
  )
}

function CutsceneScene({
  points,
  keys,
  shot,
  selected,
  onSelect,
}: {
  points: Vec3[] | null
  keys: Vec3[]
  shot: CutsceneResult
  selected: number | null
  onSelect: (index: number) => void
}) {
  return (
    <>
      {points && points.length >= 2 && <Line points={points} color={PATH} lineWidth={2} />}
      {keys.map((key, index) => (
        <mesh
          key={index}
          position={key}
          onClick={(event) => {
            event.stopPropagation()
            onSelect(index)
          }}
        >
          <sphereGeometry args={[index === selected ? 0.22 : 0.15, 12, 8]} />
          <meshBasicMaterial color={index === selected ? SELECTED : MARKER} />
        </mesh>
      ))}
      {shot.type === 'shot' && (
        <CameraFrustum position={shot.position} yaw={shot.yaw} pitch={shot.pitch} />
      )}
    </>
  )
}

/** Half the width and height of the frustum's far end, and how far it reaches, in blocks. */
const WIDTH = 0.8
const HEIGHT = 0.5
const REACH = 1.6

/** The camera: a pyramid from where it is out along where it looks. */
function CameraFrustum({ position, yaw, pitch }: { position: Vec3; yaw: number; pitch: number }) {
  const quaternion = useMemo(() => {
    const [dx, dy, dz] = lookDirection(yaw, pitch)
    const aim = new THREE.Object3D()
    aim.position.set(...position)
    // A plain object's +Z is what it faces after lookAt.
    aim.lookAt(position[0] + dx, position[1] + dy, position[2] + dz)
    return aim.quaternion.clone()
  }, [position, yaw, pitch])
  const corners: Vec3[] = [
    [-WIDTH, HEIGHT, REACH],
    [WIDTH, HEIGHT, REACH],
    [WIDTH, -HEIGHT, REACH],
    [-WIDTH, -HEIGHT, REACH],
  ]
  return (
    <group position={position} quaternion={quaternion} name="camera">
      {corners.map((corner, i) => (
        <Line key={i} points={[[0, 0, 0], corner]} color={SELECTED} lineWidth={1.5} />
      ))}
      <Line points={[...corners, corners[0]!]} color={SELECTED} lineWidth={1.5} />
      <mesh>
        <boxGeometry args={[0.3, 0.2, 0.3]} />
        <meshBasicMaterial color={SELECTED} />
      </mesh>
    </group>
  )
}
