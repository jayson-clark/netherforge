/**
 * The workbench's one 3D canvas, and the scenes it draws (`scenes.ts`).
 * Mounted once around the workbench; editors reach it through
 * `Viewport3D`. The canvas itself (three.js, react-three-fiber) loads with
 * the first scene, so a project without one never loads three.js.
 *
 * The canvas is rendered (through a React portal) into an element of its
 * own, which this moves into whichever viewport is on screen: React never
 * sees it move, the WebGL context and everything uploaded to it stay, and
 * the canvas sits inside the viewport like any element, under its
 * toolbar and notes.
 */
import {
  createContext,
  lazy,
  Suspense,
  useContext,
  useEffect,
  useLayoutEffect,
  useState,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { useStore } from 'zustand'
import { useApp } from '@/state/providers'
import { PreviewErrorBoundary } from '@/ui/PreviewErrorBoundary'
import { createViewports, type ViewportStore } from './scenes'
import styles from './Viewport.module.css'

const ViewportCanvas = lazy(() =>
  import('./ViewportCanvas').then((m) => ({ default: m.ViewportCanvas })),
)

const ViewportContext = createContext<ViewportStore | null>(null)

/** The workbench's viewports; null outside a workbench (a unit test), where nothing is drawn. */
export const useViewports = () => useContext(ViewportContext)

export function ViewportHost({ children }: { children: ReactNode }) {
  const { workspace } = useApp()
  const [store] = useState(createViewports)
  const [holder] = useState(() => {
    const element = document.createElement('div')
    element.className = styles.holder!
    return element
  })
  const slot = useStore(store, (s) => s.slot)
  const any = useStore(store, (s) => Object.keys(s.scenes).length > 0)

  // A scene lives while its document has a tab.
  useEffect(() => {
    const retain = () => {
      const open = new Set(workspace.getState().tabs.map((tab) => tab.path))
      store.getState().retain((key) => open.has(key))
    }
    retain()
    return workspace.subscribe((state, previous) => {
      if (state.tabs !== previous.tabs) retain()
    })
  }, [workspace, store])

  // The canvas goes where the viewport on screen is.
  useLayoutEffect(() => {
    if (slot) slot.appendChild(holder)
    else holder.remove()
  }, [slot, holder])

  return (
    <ViewportContext.Provider value={store}>
      {children}
      {any &&
        createPortal(
          <PreviewErrorBoundary>
            <Suspense fallback={null}>
              <ViewportCanvas store={store} />
            </Suspense>
          </PreviewErrorBoundary>,
          holder,
        )}
    </ViewportContext.Provider>
  )
}
