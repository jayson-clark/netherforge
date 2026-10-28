/**
 * The main window once a project is open: the toolbar; the left dock, the
 * editor area and the right dock side by side; the bottom dock under them;
 * the status bar. The docks hold the panels (`DOCK_PANELS`: by default the
 * outline on the left, the inspector on the right, the project explorer and
 * the rest at the bottom), in tab groups that split and that a tab drags
 * between (`DockArea`). Every dock resizes, hides (toolbar buttons, the View
 * menu, Ctrl/Cmd+B, J, Alt+B) and is remembered per project; View → Reset
 * Layout puts them back.
 */
import { Suspense, useEffect, useState, useSyncExternalStore } from 'react'
import { Group, Panel, usePanelRef, type PanelProps, type PanelSize } from 'react-resizable-panels'
import { useApp, useLayout, useRun, useWorkspace } from '@/state/providers'
import { DEFAULT_LAYOUT, LIMITS, OPEN } from '@/core/store/layout'
import type { KindContribution } from '@/editors/contributions'
import { KIND_CONTRIBUTIONS } from '@/editors/registry'
import { InspectorTarget } from '@/editors/shared/docks'
import { ViewportHost } from '@/editors/shared/viewport/ViewportHost'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Spacer } from '@/ui/layout'
import { Splitter } from '@/ui/Splitter'
import { showPanel } from './contributions'
import { AreaDrops, DockAreaView } from './docks/DockArea'
import { useInspectorTarget } from './docks/InspectorDock'
import { EditorArea } from './editorArea/EditorArea'
import { useFollowDebugStops } from './debugStops'
import { QuickOpen } from './quickOpen/QuickOpen'
import { openQuickOpen, useQuickOpen } from './quickOpen/store'
import { Toolbar } from './toolbar/Toolbar'
import { TrustBanner } from './TrustBanner'
import styles from './Workbench.module.css'

const clamp = (value: number, [min, max]: readonly [number, number]) =>
  Math.round(Math.max(min, Math.min(max, value)))

type PanelRef = ReturnType<typeof usePanelRef>
/** Every dock scrolls inside itself; a panel doesn't scroll around it. */
const OWN_SCROLL = { overflow: 'hidden' } as const
type DockSize = 'outlineWidth' | 'inspectorWidth' | 'bottomHeight'

export function Workbench() {
  const { workspace, run, layout } = useApp()
  const quickOpen = useQuickOpen((s) => s.open)
  const inspectorTarget = useInspectorTarget()
  const dock = useLayout((s) => s)
  const left = dock.outlineOpen && dock.docks.left !== null
  const right = dock.inspectorOpen && dock.docks.right !== null
  const bottomShown = dock.bottomOpen && dock.docks.bottom !== null

  // Quick open is this project's; it isn't open when the next one opens.
  useEffect(() => () => useQuickOpen.setState({ open: false }), [])

  // A new script error brings the console forward.
  useEffect(() => {
    let seen = run.getState().console.last?.id ?? 0
    return run.subscribe((state, previous) => {
      if (state.consoleVersion === previous.consoleVersion) return
      const added = state.console.since(seen)
      seen = state.console.last?.id ?? 0
      if (added.some((line) => line.kind === 'script_error')) showPanel(layout, 'console')
    })
  }, [run, layout])

  // A breakpoint brings the Debug panel and the line it stopped at forward.
  useFollowDebugStops()

  // A dock's size as the user drags it, in pixels (held to its limits by the panel).
  const sized = (key: DockSize) => (size: PanelSize) => {
    if (size.inPixels > 0) layout.getState().set({ [key]: clamp(size.inPixels, LIMITS[key]) })
  }
  // Double-click on a splitter: the dock's default size.
  const reset = (panel: PanelRef, key: DockSize) => panel.current?.resize(DEFAULT_LAYOUT[key])

  // The side docks stay in their group while hidden or empty, collapsed: taking a panel out
  // would lay the group out again from every panel's first size, undoing the other side's.
  const outline = usePanelRef()
  const inspector = usePanelRef()
  const bottom = usePanelRef()
  useCollapsed(outline, left, 'outlineWidth')
  useCollapsed(inspector, right, 'inspectorWidth')
  // Dragged past its edge, a side dock closes like its toggle does.
  const sideSized =
    (key: 'outlineWidth' | 'inspectorWidth', area: 'left' | 'right') => (size: PanelSize) => {
      const state = layout.getState()
      const flag = OPEN[area]
      if (size.inPixels === 0 && state[flag] && state.docks[area] !== null) {
        state.set({ [flag]: false })
      } else sized(key)(size)
    }

  return (
    <div className={styles.workbench}>
      <Toolbar onSettings={() => workspace.getState().openSettings()} onQuickOpen={openQuickOpen} />
      <TrustBanner />
      <InspectorTarget.Provider value={inspectorTarget}>
        <div className={styles.docks}>
          <Group orientation="vertical" className={styles.main}>
            <Panel id="middle" className={styles.middle} style={OWN_SCROLL}>
              <Group orientation="horizontal">
                <DockPanel
                  id="outline"
                  size="outlineWidth"
                  collapsed={!left}
                  panelRef={outline}
                  className={styles.side}
                  onResize={sideSized('outlineWidth', 'left')}
                >
                  {left && <DockAreaView area="left" />}
                </DockPanel>
                <Splitter
                  label="Resize the left dock"
                  hidden={!left}
                  onReset={() => reset(outline, 'outlineWidth')}
                />
                <Panel id="editor" className={styles.editor} style={OWN_SCROLL}>
                  {/* The one 3D canvas every editor's viewport draws in. */}
                  <ViewportHost>
                    <EditorArea />
                  </ViewportHost>
                </Panel>
                <Splitter
                  label="Resize the right dock"
                  hidden={!right}
                  onReset={() => reset(inspector, 'inspectorWidth')}
                />
                <DockPanel
                  id="inspector"
                  size="inspectorWidth"
                  collapsed={!right}
                  panelRef={inspector}
                  className={styles.side}
                  onResize={sideSized('inspectorWidth', 'right')}
                >
                  {right && <DockAreaView area="right" />}
                </DockPanel>
              </Group>
            </Panel>
            {bottomShown && (
              <>
                <Splitter
                  label="Resize the bottom dock"
                  onReset={() => reset(bottom, 'bottomHeight')}
                />
                <DockPanel
                  id="bottom"
                  size="bottomHeight"
                  panelRef={bottom}
                  className={styles.bottom}
                  onResize={sized('bottomHeight')}
                >
                  <DockAreaView area="bottom" />
                </DockPanel>
              </>
            )}
          </Group>
          <AreaDrops />
        </div>
      </InspectorTarget.Provider>
      <StatusBar />
      {quickOpen && <QuickOpen onClose={() => useQuickOpen.setState({ open: false })} />}
      <Notices />
      {STUDIOS.map((studio, index) => (
        <Studio key={index} studio={studio} />
      ))}
    </div>
  )
}

/** Collapses a side dock's panel while it's hidden or empty, and opens it at its remembered size. */
function useCollapsed(panel: PanelRef, shown: boolean, key: DockSize) {
  const { layout } = useApp()
  useEffect(() => {
    const it = panel.current
    if (!it) return
    if (!shown) it.collapse()
    else if (it.isCollapsed()) it.resize(layout.getState()[key])
  }, [shown, panel, layout, key])
}

/**
 * A dock's panel, in pixels within its limits, keeping its size as the library holds it: its
 * default is the remembered size when it appears (a changed default would lay the group out
 * again mid-drag). A collapsible one (a side dock) starts collapsed when [collapsed].
 */
function DockPanel({
  size,
  collapsed,
  ...props
}: Omit<PanelProps, 'defaultSize' | 'minSize' | 'maxSize'> & {
  size: DockSize
  collapsed?: boolean
}) {
  const { layout } = useApp()
  const [initial] = useState(() => (collapsed ? 0 : layout.getState()[size]))
  return (
    <Panel
      {...props}
      style={OWN_SCROLL}
      defaultSize={initial}
      minSize={LIMITS[size][0]}
      maxSize={LIMITS[size][1]}
      collapsible={collapsed !== undefined}
      collapsedSize={0}
      groupResizeBehavior="preserve-pixel-size"
    />
  )
}

/** The kinds' studios (centity pictures for the explorer's tiles and the outline's header), drawn here wherever they show. */
const STUDIOS = (Object.values(KIND_CONTRIBUTIONS) as KindContribution[]).flatMap((it) =>
  it.studio ? [it.studio] : [],
)

/** How long a studio stays mounted once it has nothing to do, so a burst of work (edits) keeps its one canvas. */
const STUDIO_LINGER_MS = 10_000

/**
 * Mounts a kind's studio while it has work, so its code loads only once
 * something needs drawing, and for a while after: each mount makes a WebGL
 * context, and the browser drops the oldest once there are too many.
 */
function Studio({ studio }: { studio: NonNullable<KindContribution['studio']> }) {
  const busy = useSyncExternalStore(studio.subscribe, studio.busy)
  const [kept, setKept] = useState(false)
  if (busy && !kept) setKept(true)
  useEffect(() => {
    if (busy || !kept) return
    const timer = setTimeout(() => setKept(false), STUDIO_LINGER_MS)
    return () => clearTimeout(timer)
  }, [busy, kept])
  const View = studio.view
  return busy || kept ? (
    <Suspense fallback={null}>
      <View />
    </Suspense>
  ) : null
}

function StatusBar() {
  const { layout } = useApp()
  const minecraft = useWorkspace((s) => s.minecraft)
  const cache = useWorkspace((s) => s.cache)
  const gameData = useWorkspace((s) => s.gameData)
  const problems = useWorkspace((s) => s.problems)
  const players = useRun((s) => s.players)
  const errors = problems.filter((it) => it.severity === 'error').length
  return (
    <footer className={styles.statusbar}>
      <span>Minecraft {minecraft ?? '?'}</span>
      <span>{cache?.client ? 'client assets ✓' : 'no client assets'}</span>
      <span>{gameData ? 'game data ✓' : 'no game data yet'}</span>
      <Spacer />
      {players.length > 0 && <span>{players.length} online</span>}
      <button
        type="button"
        className={styles.statusButton}
        onClick={() => showPanel(layout, 'problems')}
      >
        {errors === 0 ? 'No errors' : `${errors} error${errors > 1 ? 's' : ''}`}
      </button>
    </footer>
  )
}

function Notices() {
  const notice = useWorkspace((s) => s.notice)
  const [dismissed, setDismissed] = useState<number | null>(null)
  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setDismissed(notice.nonce), 6000)
    return () => clearTimeout(timer)
  }, [notice])
  if (!notice || dismissed === notice.nonce) return null
  return (
    <div
      className={cx(styles.toast, notice.kind === 'error' && styles.error)}
      role={notice.kind === 'error' ? 'alert' : 'status'}
    >
      {notice.text}
      <IconButton
        icon="close"
        label="Dismiss"
        size={12}
        onClick={() => setDismissed(notice.nonce)}
      />
    </div>
  )
}
