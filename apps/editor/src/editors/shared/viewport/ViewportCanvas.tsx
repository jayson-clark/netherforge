/**
 * The one `<Canvas>`: every document's scene, each in a portal of its own
 * (its own three.js scene, camera, raycaster and pointer), and only the
 * active one drawn and hit by the pointer. A scene's light, grid,
 * background and orbit controls are the same everywhere; its content is
 * what its editor gave it.
 *
 * Why one canvas: a browser keeps a handful of WebGL contexts (WebKit and
 * Chromium drop the oldest past 16), and a context per viewport, made again
 * on every tab switch, uploads every texture and compiles every shader
 * again. Here there's one context for the app's lifetime, and a scene whose
 * tab is hidden keeps its objects, its meshes and its camera.
 */
import {
  Component,
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ComponentRef,
  type ReactNode,
} from 'react'
import { Canvas, createPortal, useFrame, useThree } from '@react-three/fiber'
import { Grid, OrbitControls } from '@react-three/drei'
import * as THREE from 'three'
import { useStore } from 'zustand'
import type { Vec3 } from '@/core/format'
import { roundPose, samePose, type CameraPose } from './camera'
import type { SceneSpec, ViewportStore } from './scenes'

const BACKGROUND = '#121419'
/** A click this soon after a gizmo drag ends is the drag's release, not a click into nothing. */
const RELEASE_MS = 250
/** The camera goes into the document's view once it has been still this long (damping included). */
const SETTLE_MS = 300

type Controls = ComponentRef<typeof OrbitControls>

/** Whether a drag inside a scene (a gizmo's) is going on, or just ended. */
class DragGuard {
  private dragging = false
  private endedAt = 0
  start() {
    this.dragging = true
  }
  end() {
    this.dragging = false
    this.endedAt = performance.now()
  }
  /** A click now is a drag's release rather than a click into nothing. */
  releasing() {
    return this.dragging || performance.now() - this.endedAt < RELEASE_MS
  }
}

/** What a scene's content can ask of its viewport. */
export interface SceneContext {
  /** Whether it's the one on screen: only then may it listen to the pointer (a gizmo does). */
  active: boolean
  /** A drag inside the scene began: the click that ends it isn't a click into nothing. */
  dragStart(): void
  dragEnd(): void
}

const Scene = createContext<SceneContext>({
  active: false,
  dragStart: () => {},
  dragEnd: () => {},
})

/** For a scene's content: whether it's on screen, and how to say a drag is going on. */
export const useScene = () => useContext(Scene)

export function ViewportCanvas({ store }: { store: ViewportStore }) {
  const scenes = useStore(store, (s) => s.scenes)
  const active = useStore(store, (s) => s.active)
  const attempts = useStore(store, (s) => s.attempts)
  const [drag] = useState(() => new DragGuard())
  const [renderers] = useState(() => new Map<string, () => void>())

  return (
    <Canvas
      // Nothing to draw while no viewport is on screen.
      frameloop={active === null ? 'never' : 'always'}
      gl={{ antialias: true, preserveDrawingBuffer: false }}
      onPointerMissed={() => {
        if (drag.releasing()) return
        const key = store.getState().active
        if (key) store.getState().scenes[key]?.onMissed?.()
      }}
    >
      {Object.entries(scenes).map(([key, spec]) => (
        <SceneSlot
          key={key}
          sceneKey={key}
          spec={spec}
          active={key === active}
          attempt={attempts[key] ?? 0}
          store={store}
          drag={drag}
          renderers={renderers}
        />
      ))}
      <Capturer store={store} renderers={renderers} />
    </Canvas>
  )
}

/** Hands the store a way to picture the active scene (`Viewport3D`'s screenshot). */
function Capturer({
  store,
  renderers,
}: {
  store: ViewportStore
  renderers: Map<string, () => void>
}) {
  const gl = useThree((s) => s.gl)
  useEffect(() => {
    store.getState().setCapture(() => {
      const key = store.getState().active
      const draw = key ? renderers.get(key) : undefined
      if (!draw) return null
      // Drawn and read in one go: the drawing buffer isn't kept between frames.
      draw()
      return gl.domElement.toDataURL('image/png')
    })
    return () => store.getState().setCapture(null)
  }, [gl, store, renderers])
  return null
}

function SceneSlot({
  sceneKey,
  spec,
  active,
  attempt,
  store,
  drag,
  renderers,
}: {
  sceneKey: string
  spec: SceneSpec
  active: boolean
  attempt: number
  store: ViewportStore
  drag: DragGuard
  renderers: Map<string, () => void>
}) {
  const [scene] = useState(() => new THREE.Scene())
  const [camera] = useState(() => new THREE.PerspectiveCamera())
  const context = useMemo<SceneContext>(
    () => ({
      active,
      dragStart: () => drag.start(),
      dragEnd: () => drag.end(),
    }),
    [active, drag],
  )

  return createPortal(
    <Scene.Provider value={context}>
      <SceneRoot
        sceneKey={sceneKey}
        spec={spec}
        active={active}
        scene={scene}
        camera={camera}
        renderers={renderers}
      />
      <SceneBoundary key={attempt} onError={(error) => store.getState().failed(sceneKey, error)}>
        {spec.content}
      </SceneBoundary>
    </Scene.Provider>,
    scene,
    { camera },
  )
}

/** What every scene has: background, light, grid, the camera and its controls, and drawing it. */
function SceneRoot({
  sceneKey,
  spec,
  active,
  scene,
  camera,
  renderers,
}: {
  sceneKey: string
  spec: SceneSpec
  active: boolean
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  renderers: Map<string, () => void>
}) {
  const size = useThree((s) => s.size)
  const setEvents = useThree((s) => s.setEvents)
  const gl = useThree((s) => s.gl)
  const controls = useRef<Controls>(null)
  /** The pose last put on the camera or read from it: a view equal to it is shown already. */
  const shown = useRef<CameraPose | null>(null)
  const grid = spec.grid ?? null

  // A hidden scene isn't hit by the pointer.
  useLayoutEffect(() => setEvents({ enabled: active }), [active, setEvents])

  const { fov, near, far } = spec
  const { width, height } = size
  useLayoutEffect(
    () => lens(camera, { fov, near, far }, { width, height }),
    [camera, fov, near, far, width, height],
  )

  // The view's camera (home, without one) onto the camera, unless it's what's shown already.
  const wanted = spec.camera ?? spec.home
  const [px, py, pz] = wanted.position
  const [tx, ty, tz] = wanted.target
  useLayoutEffect(() => {
    const pose: CameraPose = { position: [px, py, pz], target: [tx, ty, tz] }
    if (samePose(shown.current, pose)) return
    shown.current = pose
    place(camera, controls.current, pose)
  }, [camera, px, py, pz, tx, ty, tz])

  // Where the user leaves the camera goes into the document's view: when a drag ends, and
  // again once it has settled (damping, the wheel).
  const settle = useRef<ReturnType<typeof setTimeout> | null>(null)
  const dragging = useRef(false)
  const onCamera = spec.onCamera
  const keep = () => {
    if (settle.current) clearTimeout(settle.current)
    settle.current = null
    const target = controls.current?.target
    if (!target) return
    const pose = roundPose({
      position: camera.position.toArray() as Vec3,
      target: target.toArray() as Vec3,
    })
    if (samePose(shown.current, pose)) return
    shown.current = pose
    onCamera(pose)
  }
  const moved = () => {
    if (settle.current) clearTimeout(settle.current)
    settle.current = setTimeout(keep, SETTLE_MS)
  }
  useEffect(
    () => () => {
      if (settle.current) clearTimeout(settle.current)
    },
    [],
  )

  useEffect(() => {
    const draw = () => gl.render(scene, camera)
    renderers.set(sceneKey, draw)
    return () => {
      if (renderers.get(sceneKey) === draw) renderers.delete(sceneKey)
    }
  }, [gl, scene, camera, sceneKey, renderers])

  // The active scene is the canvas's picture (a positive priority: r3f draws nothing itself).
  useFrame(() => {
    if (active) gl.render(scene, camera)
  }, 1)

  return (
    <>
      <color attach="background" args={[spec.background ?? BACKGROUND]} />
      <ambientLight intensity={1} />
      {grid && (
        <>
          <Grid
            infiniteGrid
            cellSize={grid.cell}
            cellThickness={0.4}
            cellColor="#1f232c"
            sectionSize={grid.section}
            sectionThickness={0.9}
            sectionColor="#323947"
            fadeDistance={grid.fade}
            followCamera={false}
          />
          <axesHelper args={[0.5]} />
        </>
      )}
      <OrbitControls
        ref={controls}
        camera={camera}
        // Every scene keeps its controls (and their target); only the one on screen listens.
        enabled={active}
        makeDefault
        enableDamping
        dampingFactor={0.15}
        screenSpacePanning={!spec.groundPanning}
        maxDistance={spec.maxDistance ?? Infinity}
        onStart={() => {
          dragging.current = true
        }}
        onEnd={() => {
          dragging.current = false
          keep()
        }}
        onChange={() => {
          // Mid-drag the camera isn't anywhere yet; after it, damping may still move it.
          if (active && !dragging.current) moved()
        }}
      />
    </>
  )
}

/**
 * Puts [camera] at [pose], its controls orbiting the pose's target, with no
 * momentum left from a drag (damping would carry it on from the new pose).
 */
function place(
  camera: THREE.PerspectiveCamera,
  controls: Controls | null,
  { position, target }: CameraPose,
) {
  if (controls) {
    // An update without damping spends what's left of the last drag's momentum.
    controls.enableDamping = false
    controls.update()
  }
  camera.position.set(...position)
  camera.lookAt(...target)
  if (controls) {
    controls.target.set(...target)
    controls.update()
    controls.enableDamping = true
  }
}

/** Sets [camera]'s lens: the scene's field of view and clipping, the canvas's aspect. */
function lens(
  camera: THREE.PerspectiveCamera,
  spec: Pick<SceneSpec, 'fov' | 'near' | 'far'>,
  size: { width: number; height: number },
) {
  camera.fov = spec.fov ?? 45
  camera.near = spec.near ?? 0.01
  camera.far = spec.far ?? 500
  camera.aspect = size.width / Math.max(1, size.height)
  camera.updateProjectionMatrix()
}

/** Keeps a scene's failure (a bad mesh) inside it: its viewport says what went wrong. */
class SceneBoundary extends Component<
  { children: ReactNode; onError: (error: string) => void },
  { failed: boolean }
> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  componentDidCatch(error: unknown) {
    this.props.onError(error instanceof Error ? error.message : String(error))
  }
  render() {
    return this.state.failed ? null : this.props.children
  }
}
