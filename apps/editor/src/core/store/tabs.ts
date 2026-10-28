/**
 * The tabs slice: which tabs are open, in what order, which one is active,
 * and where to put the caret when one is shown. A document lives as long as
 * a tab shows it (or, for a resource's main file, a file inside it).
 */
import { KINDS, type BinaryKindId, type EditedKind } from '@/core/format'
import {
  inPackage,
  isBinaryKind,
  locationIn,
  locationOf,
  mainFileOf,
  MODULE_ENTRY,
  opensAs,
  resourceOf,
} from '@/core/paths'
import { docKind, type Doc } from './documents'
import { filesIn } from './packages'
import { nextNonce, type SliceArgs } from './slice'

/**
 * What a tab shows: a resource's own editor, a file in a plain one (a
 * script, a module's Lua), a structure or map (binary: the path is
 * `structures/<id>.nbt` or the `maps/<id>` folder, and no document backs
 * it), or the project's settings (`netherforge.json`).
 */
export type TabType = EditedKind | BinaryKindId | 'script' | 'sql' | 'json' | 'project' | 'settings'

/**
 * The settings tab's path: no file, so nothing loads, saves or renames it.
 * Its open page is its view's selection, as an editor keeps what's selected.
 */
export const SETTINGS_PATH = 'netherforge:settings'
export const SETTINGS_TAB = `settings:${SETTINGS_PATH}`

export interface Tab {
  id: string
  type: TabType
  /** A file path, or a map's folder (`maps/arena`). */
  path: string
}

/** Where to put the caret or selection when a tab is shown (Problems, Console). */
export interface FocusRequest {
  path: string
  /** A JSON path from a problem, `$.nodes.top.display.block`. */
  jsonPath?: string
  line?: number
  column?: number
  nonce: number
}

export const tabIdOf = (type: TabType, path: string) => `${type}:${path}`

/** Which tab a file opens in: a resource's main JSON its kind's editor, the manifest the project's settings. */
export function tabTypeFor(path: string): TabType {
  const kind = docKind(path)
  if (kind === 'netherforge') return 'project'
  if (kind === 'lua') return 'script'
  if (kind === 'text' && path.endsWith('.sql')) return 'sql'
  if (kind !== 'text' && Object.hasOwn(KINDS, kind)) return kind as EditedKind
  return 'json'
}

/** The tabs after a rename: each one [moved] names shows its new path, and stays active if it was. */
export function movedTabs(
  tabs: Tab[],
  activeTab: string | null,
  moved: (path: string) => string | null,
): { tabs: Tab[]; activeTab: string | null } {
  let nextActive = activeTab
  const nextTabs = tabs.map((tab) => {
    const target = moved(tab.path)
    if (!target) return tab
    const next = { ...tab, path: target, id: tabIdOf(tab.type, target) }
    if (tab.id === activeTab) nextActive = next.id
    return next
  })
  return { tabs: nextTabs, activeTab: nextActive }
}

/** The documents and tabs left once everything [gone] says went is closed. */
export function withoutPaths(
  docs: Record<string, Doc>,
  tabs: Tab[],
  activeTab: string | null,
  gone: (path: string) => boolean,
): { docs: Record<string, Doc>; tabs: Tab[]; activeTab: string | null } {
  const nextTabs = tabs.filter((tab) => !gone(tab.path))
  return {
    docs: Object.fromEntries(Object.entries(docs).filter(([path]) => !gone(path))),
    tabs: nextTabs,
    activeTab: nextTabs.some((tab) => tab.id === activeTab)
      ? activeTab
      : (nextTabs[nextTabs.length - 1]?.id ?? null),
  }
}

export interface TabsState {
  tabs: Tab[]
  activeTab: string | null
  focus: FocusRequest | null
}

export interface TabsActions {
  /** Opens [path] in the right kind of tab and optionally puts the caret somewhere. */
  openFile(path: string, at?: { line?: number; column?: number; jsonPath?: string }): Promise<void>
  /**
   * Opens a structure's or map's screen (there may be no file yet: it's captured from there), in [group] folders
   * when given, else where the project has it.
   */
  openBinary(kind: BinaryKindId, id: string, group?: string): void
  /** Opens the editor's settings in a tab of their own, on [page] if given. */
  openSettings(page?: string): void
  activate(tabId: string): void
  closeTab(tabId: string): void
  /** Moves a tab to [index] in the strip (its index once moved, clamped). */
  moveTab(tabId: string, index: number): void
  requestFocus(focus: Omit<FocusRequest, 'nonce'>): void
}

export const TABS_INITIAL: TabsState = { tabs: [], activeTab: null, focus: null }

export function tabsSlice({ get, set }: SliceArgs): TabsState & TabsActions {
  /** Shows the tab [tab] (opening it at the end of the strip if it isn't open). */
  const show = (tab: Tab) => {
    const { tabs } = get()
    set({
      tabs: tabs.some((it) => it.id === tab.id) ? tabs : [...tabs, tab],
      activeTab: tab.id,
    })
  }

  return {
    ...TABS_INITIAL,

    async openFile(path, at) {
      const resource = resourceOf(path)
      // Minecraft's own files are never read as text: a structure or a map gets its own screen.
      if (resource && isBinaryKind(resource.kind))
        return get().openBinary(resource.kind, resource.id, resource.group)
      // A problem about a resource folder rather than a file: a module opens on the file it runs first.
      if (resource?.role === 'folder')
        return get().openFile(
          inPackage(resource.pkg, opensAs(resource.kind, resource.id, resource.group)),
          at,
        )
      // A module without an `init.lua` opens on one of the files it does have.
      if (resource && KINDS[resource.kind].contents === 'lua' && resource.rest === MODULE_ENTRY) {
        const files = filesIn(get(), resource.pkg)
        if (!files.includes(path)) {
          const folder = `${inPackage(resource.pkg, locationOf(resource.kind, resource.id, resource.group))}/`
          const lua = files.filter((it) => it.startsWith(folder) && it.endsWith('.lua'))
          // Its top-level files before those in subfolders, each in order.
          const depth = (it: string) => it.split('/').length
          const first = lua.sort((a, b) => depth(a) - depth(b) || a.localeCompare(b))[0]
          if (first) return get().openFile(first, at)
        }
      }
      const doc = await get().loadDoc(path)
      if (!doc) return
      const type = tabTypeFor(path)
      show({ id: tabIdOf(type, path), type, path })
      if (at) get().requestFocus({ path, ...at })
    },

    openBinary(kind, id, group) {
      const path =
        group === undefined ? locationIn(get().files, kind, id) : locationOf(kind, id, group)
      show({ id: tabIdOf(kind, path), type: kind, path })
    },

    openSettings(page) {
      show({ id: SETTINGS_TAB, type: 'settings', path: SETTINGS_PATH })
      if (page) get().select(SETTINGS_PATH, [page])
    },

    activate(tabId) {
      if (get().tabs.some((tab) => tab.id === tabId)) set({ activeTab: tabId })
    },

    closeTab(tabId) {
      const { tabs, activeTab, docs } = get()
      const index = tabs.findIndex((tab) => tab.id === tabId)
      if (index < 0) return
      const closing = tabs[index]!
      const nextTabs = tabs.filter((tab) => tab.id !== tabId)
      // Documents live as long as a tab shows them. A resource's main file
      // also lives while a tab shows a file of that resource (the outline
      // reads it), unless it's the tab being closed: its unsaved edits
      // were just saved or thrown away, and the outline reads it afresh.
      const mainOf = (path: string) => {
        const resource = resourceOf(path)
        return resource && KINDS[resource.kind].contents === 'json'
          ? inPackage(resource.pkg, mainFileOf(resource.kind, resource.id, resource.group))
          : undefined
      }
      const shown = (path: string) =>
        nextTabs.some(
          (tab) => tab.path === path || (path !== closing.path && mainOf(tab.path) === path),
        )
      const nextDocs = { ...docs }
      const main = mainOf(closing.path)
      const candidates = main && main !== closing.path ? [closing.path, main] : [closing.path]
      for (const path of candidates) if (!shown(path)) delete nextDocs[path]
      set({
        tabs: nextTabs,
        docs: nextDocs,
        activeTab:
          activeTab === tabId
            ? (nextTabs[Math.min(index, nextTabs.length - 1)]?.id ?? null)
            : activeTab,
      })
      get().validateSoon()
    },

    moveTab(tabId, index) {
      const { tabs } = get()
      const from = tabs.findIndex((tab) => tab.id === tabId)
      if (from < 0) return
      const to = Math.max(0, Math.min(index, tabs.length - 1))
      if (to === from) return
      const next = tabs.filter((tab) => tab.id !== tabId)
      next.splice(to, 0, tabs[from]!)
      set({ tabs: next })
    },

    requestFocus(focus) {
      set({ focus: { ...focus, nonce: nextNonce() } })
    },
  }
}
