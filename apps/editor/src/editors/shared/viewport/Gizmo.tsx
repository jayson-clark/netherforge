/**
 * The move/rotate/scale handles on one object in a scene, for any editor:
 * a stand-in object sits at [world] (except mid-drag, when the drag owns
 * it), and every change of a drag hands the editor the stand-in, whose
 * world matrix is where the user put it. A drag is one undo gesture
 * (`onBegin`/`onEnd`), and the click that ends it isn't a click into
 * nothing. Only the scene on screen has handles (they listen to the
 * pointer on the shared canvas).
 */
import { useEffect, useRef, useState } from 'react'
import { TransformControls } from '@react-three/drei'
import * as THREE from 'three'
import { useScene } from './ViewportCanvas'

export type GizmoMode = 'translate' | 'rotate' | 'scale'

export function Gizmo({
  world,
  mode,
  translationSnap = 1 / 16,
  rotationSnap = 5,
  onBegin,
  onChange,
  onEnd,
}: {
  world: THREE.Matrix4
  mode: GizmoMode
  /** Blocks. */
  translationSnap?: number
  /** Degrees. */
  rotationSnap?: number
  onBegin: () => void
  /** [proxy]'s `matrixWorld` is where the drag has it now. */
  onChange: (proxy: THREE.Object3D) => void
  onEnd: () => void
}) {
  const scene = useScene()
  const [proxy, setProxy] = useState<THREE.Object3D | null>(null)
  const dragging = useRef(false)

  // Park the stand-in on the object, except mid-drag when the drag owns it.
  useEffect(() => {
    if (!proxy || dragging.current) return
    world.decompose(proxy.position, proxy.quaternion, proxy.scale)
    proxy.updateMatrixWorld()
  }, [proxy, world])

  return (
    <>
      <object3D ref={setProxy} />
      {proxy && scene.active && (
        <TransformControls
          object={proxy}
          mode={mode}
          size={1}
          translationSnap={translationSnap}
          rotationSnap={THREE.MathUtils.degToRad(rotationSnap)}
          onMouseDown={() => {
            dragging.current = true
            scene.dragStart()
            onBegin()
          }}
          onMouseUp={() => {
            dragging.current = false
            scene.dragEnd()
            onEnd()
          }}
          onObjectChange={() => {
            if (!dragging.current) return
            proxy.updateMatrixWorld()
            onChange(proxy)
          }}
        />
      )}
    </>
  )
}
