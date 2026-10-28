/**
 * The view state slice: per document, what's selected and what its editor
 * keeps besides (a previewed clip, a playhead, a gizmo mode, where a recipe's
 * shape sits on its grid). None of it is in the file, so none of it is saved
 * or undone.
 *
 * Views are kept by path, like documents and tabs: a rename moves them
 * (`movedViews`, with the documents and tabs), and a view goes once neither a
 * document nor a tab has its path (`liveViews`, the one place views are
 * dropped: closing a tab, deleting a file, a file gone from disk, closing the
 * project). What a kind's view holds, and its selection's items, are typed
 * per kind by the kind's registration (`editors/views.ts`); here they're
 * opaque.
 */
import type { Doc } from './documents'
import type { SliceArgs } from './slice'
import type { Tab } from './tabs'

export interface DocView {
  /** What's selected, oldest first: the last is the primary, the one an inspector shows. */
  selection: readonly unknown[]
  /** The kind's view state as changed from its registered `initial` (only the keys that were set). */
  state: Readonly<Record<string, unknown>>
}

/** No view yet: nothing selected, the kind's initial state. */
export const EMPTY_VIEW: DocView = { selection: [], state: {} }

export type ViewPatch = Record<string, unknown>

export interface ViewsState {
  views: Record<string, DocView>
}

export interface ViewsActions {
  /** Replaces what's selected in [path]'s view (nothing, for an empty list). */
  select(path: string, items: readonly unknown[]): void
  /** Merges [patch] into [path]'s view state. */
  updateView(path: string, patch: ViewPatch): void
}

export const VIEWS_INITIAL: ViewsState = { views: {} }

/** The views after a rename: each one [moved] names, at its new path. */
export function movedViews(
  views: Record<string, DocView>,
  moved: (path: string) => string | null,
): Record<string, DocView> {
  const next: Record<string, DocView> = {}
  for (const [path, view] of Object.entries(views)) next[moved(path) ?? path] = view
  return next
}

/**
 * The views still in use: a path keeps its view while a document or a tab
 * has it. [views] itself when nothing went, so the store doesn't change.
 */
export function liveViews(state: {
  views: Record<string, DocView>
  docs: Record<string, Doc>
  tabs: Tab[]
}): Record<string, DocView> {
  const { views, docs, tabs } = state
  const tabbed = new Set(tabs.map((tab) => tab.path))
  const kept = Object.entries(views).filter(([path]) => path in docs || tabbed.has(path))
  return kept.length === Object.keys(views).length ? views : Object.fromEntries(kept)
}

export function viewsSlice({ get, set }: SliceArgs): ViewsState & ViewsActions {
  const put = (path: string, change: (view: DocView) => DocView) => {
    const views = get().views
    set({ views: { ...views, [path]: change(views[path] ?? EMPTY_VIEW) } })
  }
  return {
    ...VIEWS_INITIAL,

    select(path, items) {
      const current = get().views[path]?.selection ?? EMPTY_VIEW.selection
      if (current.length === 0 && items.length === 0) return
      put(path, (view) => ({ ...view, selection: [...items] }))
    },

    updateView(path, patch) {
      put(path, (view) => ({
        ...view,
        state: { ...view.state, ...patch },
      }))
    },
  }
}
