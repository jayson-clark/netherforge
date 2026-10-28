/**
 * A centity's picture in the explorer and the outline, and the hidden studio
 * that draws them: one small WebGL canvas for every centity (a canvas each
 * would run out of WebGL contexts), posing each at rest with format's
 * composer and framing it. Pictures are saved in the project
 * (`thumbnails.ts`), so an unchanged centity is drawn once, ever.
 */
import { useEffect, useMemo, useRef } from 'react'
import { Canvas, useThree } from '@react-three/fiber'
import * as THREE from 'three'
import { useApp, useWorkspace } from '@/state/providers'
import { usePendingPicture } from '@/ui/Reveal'
import { centityPoser, type CentityFile } from '@/core/format'
import { mainFileIn, resourceIdsOf } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import styles from './CentityThumbnail.module.css'
import {
  finishThumbnail,
  forgetSaved,
  loadThumbnails,
  pictureOf,
  requestThumbnail,
  thumbnailKey,
  useThumbnails,
  type ThumbnailJob,
} from './thumbnails'
import { DisplayVisual } from './Viewport'

const SIZE = 160
/** How long a picture waits for its models and textures to load before it's taken. */
const SETTLE_MS = 700

/** The centity's picture over [fallback] (its kind's icon), which shows until the picture is drawn. */
export function CentityThumbnail({ id, size, fallback }: ThumbnailProps) {
  return (
    <span className={styles.layered}>
      <span className={styles.under}>{fallback}</span>
      <CentityPicture id={id} size={size} />
    </span>
  )
}

function CentityPicture({ id, size }: { id: string; size: number }) {
  const { backend } = useApp()
  const root = useWorkspace((s) => s.project?.root ?? null)
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'centity', id)))
  const assets = useClientAssets()
  const key = text === undefined ? null : thumbnailKey(id, text, assets?.key ?? null)
  const ready = useThumbnails((s) => s.saved !== null)
  const drawn = useThumbnails((s) => (key ? s.done[key] : undefined))
  const savedKey = useThumbnails((s) => s.saved?.[id])
  const latest = useThumbnails((s) => s.latest[id])

  useEffect(() => {
    if (root) void loadThumbnails(backend, root)
  }, [backend, root])
  useEffect(() => {
    if (key && text !== undefined && ready) requestThumbnail({ key, id, text })
  }, [key, id, text, ready, savedKey])

  const { url, stale } = key
    ? pictureOf(backend, id, key, { drawn, savedKey, latest })
    : { url: undefined, stale: undefined }
  const shown = url ?? stale
  // Waiting for the saved pictures, or for one to be drawn (a centity that can't be drawn is done with null).
  usePendingPicture(key !== null && !shown && (!ready || drawn === undefined))
  if (!key) return null
  if (!shown) return null
  return (
    <img
      className={styles.picture}
      src={shown}
      alt=""
      width={size}
      height={size}
      draggable={false}
      // A saved picture deleted by hand: forget it, and it's drawn again.
      onError={() => forgetSaved(id)}
    />
  )
}

/**
 * Mount once where centity thumbnails show: it draws whatever is queued, one
 * job after another in the same canvas (a canvas is a WebGL context, and the
 * browser keeps only a handful).
 */
export function ThumbnailStudio() {
  const job = useThumbnails((s) => s.queue[0] ?? null)
  return (
    <div className={styles.studio} aria-hidden="true">
      <Canvas
        style={{ width: SIZE, height: SIZE }}
        // Idle between jobs, while it waits for the next.
        frameloop={job ? 'always' : 'never'}
        camera={{ fov: 30, near: 0.01, far: 500 }}
        gl={{ antialias: true, preserveDrawingBuffer: true, alpha: true }}
      >
        <ambientLight intensity={1} />
        {job && <Shot key={job.key} job={job} />}
      </Canvas>
    </div>
  )
}

function Shot({ job }: { job: ThumbnailJob }) {
  const { backend, workspace } = useApp()
  const assets = useClientAssets()
  const gameData = useWorkspace((s) => s.gameData)
  const { gl, camera, scene } = useThree()
  const group = useRef<THREE.Group>(null)
  const model = parseCached<CentityFile>(job.text)
  const nodes = useMemo(() => {
    if (!model) return []
    const result = centityPoser(job.id, job.text).pose(null, 0)
    return result.type === 'posed' ? result.nodes : []
  }, [job, model])

  useEffect(() => {
    if (nodes.length === 0) {
      void finishThumbnail(backend, job, null, [])
      return
    }
    const timer = setTimeout(() => {
      if (group.current) frame(group.current, camera)
      gl.render(scene, camera)
      const ids = resourceIdsOf(workspace.getState().files, 'centity')
      void finishThumbnail(backend, job, gl.domElement.toDataURL('image/png'), ids)
    }, SETTLE_MS)
    return () => clearTimeout(timer)
  }, [job, nodes, gl, scene, camera, backend, workspace])

  return (
    <group ref={group}>
      {nodes.map((posed) => {
        const node = model?.nodes?.[posed.name]
        if (!node?.display) return null
        return (
          <group
            key={posed.name}
            matrixAutoUpdate={false}
            matrix={new THREE.Matrix4().fromArray(posed.matrix)}
          >
            <DisplayVisual display={node.display} assets={assets} gameData={gameData} />
          </group>
        )
      })}
    </group>
  )
}

/** Points [camera] at what [group] holds, from the front-right and a little above (the editor's view). */
function frame(group: THREE.Object3D, camera: THREE.Camera) {
  const box = new THREE.Box3().setFromObject(group)
  const sphere = box.isEmpty()
    ? new THREE.Sphere(new THREE.Vector3(0.5, 0.5, 0.5), 1)
    : box.getBoundingSphere(new THREE.Sphere())
  const distance = Math.max(0.5, sphere.radius) / Math.sin(THREE.MathUtils.degToRad(15))
  const direction = new THREE.Vector3(0.75, 0.6, 1).normalize()
  camera.position.copy(sphere.center).addScaledVector(direction, distance)
  camera.lookAt(sphere.center)
  if (camera instanceof THREE.PerspectiveCamera) camera.updateProjectionMatrix()
}
