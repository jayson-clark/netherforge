/**
 * A document's 3D view: the workbench's one canvas (`ViewportHost`) drawing
 * this document's scene, with the shared light, grid and orbit controls,
 * the camera kept in the document's view (`camera`, so a tab switch or a
 * rename comes back to the same place), a picture of it to the clipboard,
 * and whatever the editor lays over it (`ViewportToolbar`, `ViewportNote`).
 *
 * The scene outlives this component: switching tabs hides it (undrawn,
 * deaf to the pointer) with everything in it, and closing the tab drops it.
 */
import { useContext, useLayoutEffect, useRef, type ReactNode } from 'react'
import { useStore } from 'zustand'
import { useApp } from '@/state/providers'
import { DocumentNamespace } from '@/state/useResourcePacks'
import { IconButton } from '@/ui/Button'
import { useView, useViewState } from '@/editors/views'
import type { ViewKind, ViewStateOf } from '@/editors/registry'
import type { CameraPose } from './camera'
import type { SceneSpec, ViewportStore } from './scenes'
import { useViewports } from './ViewportHost'
import { ViewportNote } from './parts'
import styles from './Viewport.module.css'

/** A kind whose view keeps a camera: one with a 3D view. */
export type CameraKind = {
  [K in ViewKind]: ViewStateOf<K> extends { camera: CameraPose | null } ? K : never
}[ViewKind]

export function Viewport3D<K extends CameraKind>({
  kind,
  path,
  label,
  scene,
  children,
}: {
  kind: K
  /** The document (its tab's path): the scene is kept by it. */
  path: string
  /** The viewport's accessible name ("Structure preview"). */
  label: string
  scene: Omit<SceneSpec, 'camera' | 'onCamera'>
  /** Laid over the canvas: a toolbar, notes. */
  children?: ReactNode
}) {
  const store = useViewports()
  const view = useView(kind, path)
  const { camera } = useViewState(kind, path) as { camera: CameraPose | null }
  const slot = useRef<HTMLDivElement>(null)
  // The canvas is the workbench's, outside the editor area: a package's document (opened read-only) names things in
  // its package, so its scene is given the namespace the editor area set here.
  const namespace = useContext(DocumentNamespace)

  const spec: SceneSpec = {
    ...scene,
    content: (
      <DocumentNamespace.Provider value={namespace}>{scene.content}</DocumentNamespace.Provider>
    ),
    camera,
    onCamera: (pose) => view.update({ camera: pose } as unknown as Partial<ViewStateOf<K>>),
  }
  // The scene as this render describes it (the canvas draws it after this commit).
  useLayoutEffect(() => {
    store?.getState().show(path, spec)
  })
  useLayoutEffect(() => {
    if (!store || !slot.current) return
    store.getState().attach(path, slot.current)
    return () => store.getState().detach(path)
  }, [store, path])

  return (
    <div className={styles.viewport} aria-label={label} data-viewport>
      <div className={styles.slot} ref={slot} />
      {children}
      {store && <Overlay store={store} path={path} />}
    </div>
  )
}

/** The picture button, and a scene's failure. */
function Overlay({ store, path }: { store: ViewportStore; path: string }) {
  const { workspace } = useApp()
  const error = useStore(store, (s) => s.errors[path] ?? null)
  const capture = useStore(store, (s) => s.capture)

  const copyPicture = async () => {
    const url = capture?.()
    if (!url) return
    try {
      const blob = await (await fetch(url)).blob()
      await navigator.clipboard.write([new ClipboardItem({ [blob.type]: blob })])
      workspace.getState().notify('info', 'Copied a picture of the viewport')
    } catch (e) {
      workspace
        .getState()
        .notify('error', `Couldn't copy the picture: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  return (
    <>
      <div className={styles.corner}>
        <IconButton
          icon="camera"
          label="Copy picture"
          disabled={!capture}
          onClick={() => void copyPicture()}
        />
      </div>
      {error && (
        <ViewportNote warning role="alert">
          The preview failed: {error}
        </ViewportNote>
      )}
    </>
  )
}
