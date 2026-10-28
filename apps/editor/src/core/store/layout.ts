/**
 * The workbench's own state: dock sizes and which are open, where each
 * panel is in them (`docks.ts`), the project explorer's view, which tree rows are expanded, and
 * the tabs that were open. None of it is in the project; it's remembered per
 * project in the webview's storage, and a project opens as it was left.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import {
  DEFAULT_DOCKS,
  findPanel,
  movePanel,
  readDocks,
  resizeSplit,
  selectPanel,
  type DockArea,
  type Docks,
  type DockTarget,
} from './docks'
import { SETTINGS_PATH } from './tabs'
import type { WorkspaceStore } from './workspace'

export type ExplorerView = 'grid' | 'list'

export interface Layout {
  /** The left, right and bottom docks' sizes, and whether each shows (named for what they start with). */
  outlineWidth: number
  inspectorWidth: number
  bottomHeight: number
  outlineOpen: boolean
  inspectorOpen: boolean
  bottomOpen: boolean
  /** Where each panel is: every dock's splits and tab groups. */
  docks: Docks
  /** The explorer's folder: a kind's id, or null for the first. */
  explorerFolder: string | null
  /** Tile edge in pixels. */
  tileSize: number
  explorerView: ExplorerView
  /**
   * Tree rows and sections the user opened or closed, by key
   * (`files:centities/tower/lib`, `nodes:centities/tower/centity.json:base`).
   * A key that isn't here takes its tree's default.
   */
  expanded: Record<string, boolean>
  /** Paths of the open tabs, in order, and the active one: restored when the project opens. */
  openPaths: string[]
  activePath: string | null
}

export interface LayoutActions {
  set(patch: Partial<Layout>): void
  toggle(dock: 'outline' | 'inspector' | 'bottom'): void
  /** Shows panel [id]: its tab in its group, and its dock if hidden. */
  reveal(id: string): void
  /** Moves panel [id] to [target] (a group's tabs, beside a group, a dock), showing it. */
  movePanel(id: string, target: DockTarget): void
  /** A split's children's shares. */
  resizeSplit(id: string, sizes: number[]): void
  /** Every dock where it started, at its first size and showing; the rest (tabs, explorer, trees) stays. */
  resetLayout(): void
  setExpanded(key: string, open: boolean): void
}

export type LayoutState = Layout & LayoutActions
export type LayoutStore = StoreApi<LayoutState>

export const LIMITS = {
  outlineWidth: [160, 520],
  inspectorWidth: [240, 640],
  bottomHeight: [100, 700],
  tileSize: [56, 160],
} as const

export const DEFAULT_LAYOUT: Layout = {
  outlineWidth: 240,
  inspectorWidth: 320,
  bottomHeight: 260,
  outlineOpen: true,
  inspectorOpen: true,
  bottomOpen: true,
  docks: DEFAULT_DOCKS,
  explorerFolder: null,
  tileSize: 84,
  explorerView: 'grid',
  expanded: {},
  openPaths: [],
  activePath: null,
}

/** The flag that shows each dock. */
export const OPEN = {
  left: 'outlineOpen',
  right: 'inspectorOpen',
  bottom: 'bottomOpen',
} as const satisfies Record<DockArea, keyof Layout>

const clamp = (value: number, [min, max]: readonly [number, number]) =>
  Math.max(min, Math.min(max, value))

/**
 * What was stored, held to the shapes and limits above: storage is written
 * by older editors too, and by hand, so nothing in it is trusted.
 */
export function readLayout(stored: unknown): Layout {
  if (typeof stored !== 'object' || stored === null) return { ...DEFAULT_LAYOUT }
  const it = stored as Record<string, unknown>
  const num = (key: keyof typeof LIMITS) =>
    typeof it[key] === 'number' && Number.isFinite(it[key])
      ? clamp(it[key], LIMITS[key])
      : DEFAULT_LAYOUT[key]
  const bool = (key: 'outlineOpen' | 'inspectorOpen' | 'bottomOpen') =>
    typeof it[key] === 'boolean' ? it[key] : DEFAULT_LAYOUT[key]
  const expanded: Record<string, boolean> = {}
  if (typeof it.expanded === 'object' && it.expanded !== null) {
    for (const [key, value] of Object.entries(it.expanded)) {
      if (typeof value === 'boolean') expanded[key] = value
    }
  }
  const openPaths = Array.isArray(it.openPaths)
    ? it.openPaths.filter((path): path is string => typeof path === 'string')
    : []
  return {
    outlineWidth: num('outlineWidth'),
    inspectorWidth: num('inspectorWidth'),
    bottomHeight: num('bottomHeight'),
    tileSize: num('tileSize'),
    outlineOpen: bool('outlineOpen'),
    inspectorOpen: bool('inspectorOpen'),
    bottomOpen: bool('bottomOpen'),
    docks: readDocks(it.docks),
    explorerFolder: typeof it.explorerFolder === 'string' ? it.explorerFolder : null,
    explorerView: it.explorerView === 'list' ? 'list' : 'grid',
    expanded,
    openPaths,
    activePath: typeof it.activePath === 'string' ? it.activePath : null,
  }
}

const layoutOf = (state: LayoutState): Layout => {
  const layout: Partial<LayoutState> = { ...state }
  delete layout.set
  delete layout.toggle
  delete layout.reveal
  delete layout.movePanel
  delete layout.resizeSplit
  delete layout.resetLayout
  delete layout.setExpanded
  return layout as Layout
}

/** Where layouts are kept: the webview's localStorage, or a map in tests. */
export interface LayoutStorage {
  get(key: string): string | null
  set(key: string, value: string): void
}

/** localStorage, which a private window or blocked site data can make throw: then nothing is kept. */
export const browserStorage: LayoutStorage = {
  get(key) {
    try {
      return window.localStorage.getItem(key)
    } catch {
      return null
    }
  },
  set(key, value) {
    try {
      window.localStorage.setItem(key, value)
    } catch {
      // Not kept; the layout still works for this session.
    }
  },
}

/** Stored text as JSON, or null when it isn't any. */
function parseStored(text: string | null): unknown {
  try {
    return JSON.parse(text ?? 'null')
  } catch {
    return null
  }
}

const storageKey = (root: string) => `netherforge.layout:${root}`
/** How long after a change the layout is written. */
export const SAVE_DELAY_MS = 300

export function createLayout(): LayoutStore {
  return createStore<LayoutState>()((set, get) => ({
    ...DEFAULT_LAYOUT,
    set: (patch) => set(patch),
    toggle(dock) {
      const key = `${dock}Open` as const
      set({ [key]: !get()[key] } as Partial<Layout>)
    },
    reveal(id) {
      const docks = selectPanel(get().docks, id)
      const found = findPanel(docks, id)
      if (!found) return
      set({ docks, [OPEN[found.area]]: true })
    },
    movePanel(id, target) {
      const docks = selectPanel(movePanel(get().docks, id, target), id)
      const found = findPanel(docks, id)
      set(found ? { docks, [OPEN[found.area]]: true } : { docks })
    },
    resizeSplit: (id, sizes) => set({ docks: resizeSplit(get().docks, id, sizes) }),
    resetLayout() {
      const {
        outlineWidth,
        inspectorWidth,
        bottomHeight,
        outlineOpen,
        inspectorOpen,
        bottomOpen,
        docks,
      } = DEFAULT_LAYOUT
      set({
        outlineWidth,
        inspectorWidth,
        bottomHeight,
        outlineOpen,
        inspectorOpen,
        bottomOpen,
        docks,
      })
    },
    setExpanded(key, open) {
      if (get().expanded[key] === open) return
      set({ expanded: { ...get().expanded, [key]: open } })
    },
  }))
}

/**
 * Keeps [layout] with the workspace's project: loads the project's layout
 * when it opens (and reopens its tabs), follows the tabs as they change, and
 * writes the layout back a moment after any change.
 */
export function followProject(
  layout: LayoutStore,
  workspace: WorkspaceStore,
  storage: LayoutStorage = browserStorage,
): () => void {
  let root: string | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  // While tabs are being reopened, their one-by-one opening isn't the user's.
  let restoring = false
  // Set when a project opens: its tabs come back once its file list arrives.
  let pendingRestore = false

  const flush = () => {
    if (timer !== null) clearTimeout(timer)
    timer = null
    if (root) storage.set(storageKey(root), JSON.stringify(layoutOf(layout.getState())))
  }
  const schedule = () => {
    if (!root) return
    if (timer !== null) clearTimeout(timer)
    timer = setTimeout(flush, SAVE_DELAY_MS)
  }

  const restoreTabs = async (paths: string[], active: string | null) => {
    restoring = true
    try {
      const ws = workspace.getState()
      for (const path of paths) {
        if (path === SETTINGS_PATH) {
          workspace.getState().openSettings()
          continue
        }
        // A path in a folder (a map) isn't in `files` itself.
        const exists = ws.files.some((it) => it === path || it.startsWith(`${path}/`))
        if (exists) await workspace.getState().openFile(path)
      }
      const state = workspace.getState()
      const tab = state.tabs.find((it) => it.path === active) ?? state.tabs[0]
      if (tab) state.activate(tab.id)
    } finally {
      restoring = false
      const state = workspace.getState()
      layout.getState().set({
        openPaths: state.tabs.map((it) => it.path),
        activePath: state.tabs.find((it) => it.id === state.activeTab)?.path ?? null,
      })
    }
  }

  const stopWorkspace = workspace.subscribe((state, previous) => {
    const next = state.project?.root ?? null
    if (next !== root) {
      flush()
      root = next
      if (!root) return
      layout.setState(readLayout(parseStored(storage.get(storageKey(root)))))
      pendingRestore = true
      return
    }
    // The project's files arrive after it opens: that's when its tabs can come back.
    if (root && pendingRestore && state.files !== previous.files) {
      pendingRestore = false
      const { openPaths, activePath } = layout.getState()
      if (openPaths.length > 0 && state.tabs.length === 0) void restoreTabs(openPaths, activePath)
      return
    }
    if (restoring || !root) return
    if (state.tabs !== previous.tabs || state.activeTab !== previous.activeTab) {
      layout.getState().set({
        openPaths: state.tabs.map((it) => it.path),
        activePath: state.tabs.find((it) => it.id === state.activeTab)?.path ?? null,
      })
    }
  })
  const stopLayout = layout.subscribe(schedule)

  return () => {
    flush()
    stopWorkspace()
    stopLayout()
  }
}
