/**
 * A document's view, typed by its kind's registration: its state (the
 * kind's `initial`, as changed) and its selection (oldest first; the last
 * item is the primary, the one an inspector shows). The workspace keeps both
 * per document (`core/store/views.ts`), so they survive tab switches, follow
 * renames and go when the document closes; none of it is undone.
 */
import { useMemo } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { EMPTY_VIEW } from '@/core/store/views'
import type { WorkspaceStore } from '@/core/store/workspace'
import type { ViewStateSpec } from './contributions'
import { KIND_CONTRIBUTIONS, type SelectionOf, type ViewKind, type ViewStateOf } from './registry'

/** Reading and changing one document's view. */
export interface DocView<V, S> {
  state(): V
  selection(): readonly S[]
  /** The last item selected, or null with nothing selected. */
  primary(): S | null
  isSelected(item: S): boolean
  /** Changes the view state: [patch], or what it makes of the state as it is. */
  update(patch: Partial<V> | ((state: V) => Partial<V>)): void
  /** Selects exactly [items] (the last is the primary); nothing, with none. */
  select(...items: S[]): void
  /** Adds [item] to the selection as its primary, or takes it out if it's in it. */
  toggle(item: S): void
  /**
   * Replaces each selected item with what [change] makes of it (null drops
   * it): after an edit renamed, moved or deleted what was selected.
   */
  mapSelection(change: (item: S) => S | null): void
}

const specOf = <K extends ViewKind>(kind: K) =>
  KIND_CONTRIBUTIONS[kind].viewState as ViewStateSpec<ViewStateOf<K> & object, SelectionOf<K>>

/** [items] without repeats (by [key]), each kept where it was last. */
export function distinct<S>(items: readonly S[], key: (item: S) => string): S[] {
  const seen = new Set<string>()
  const out: S[] = []
  for (let i = items.length - 1; i >= 0; i -= 1) {
    const id = key(items[i]!)
    if (seen.has(id)) continue
    seen.add(id)
    out.unshift(items[i]!)
  }
  return out
}

/** [selection] with [item] added last, or without it if it was there. */
export function toggled<S>(selection: readonly S[], item: S, key: (item: S) => string): S[] {
  const id = key(item)
  return selection.some((it) => key(it) === id)
    ? selection.filter((it) => key(it) !== id)
    : [...selection, item]
}

/** Document [path]'s view, for a kind that registered one. */
export function viewOf<K extends ViewKind>(
  workspace: WorkspaceStore,
  kind: K,
  path: string,
): DocView<ViewStateOf<K>, SelectionOf<K>> {
  type V = ViewStateOf<K>
  type S = SelectionOf<K>
  const spec = specOf(kind)
  const stored = () => workspace.getState().views[path] ?? EMPTY_VIEW
  const selection = () => stored().selection as readonly S[]
  const state = (): V => ({ ...spec.initial, ...stored().state }) as V
  const select = (items: readonly S[]) =>
    workspace.getState().select(path, distinct(items, spec.key))
  return {
    state,
    selection,
    primary: () => selection().at(-1) ?? null,
    isSelected: (item) => selection().some((it) => spec.key(it) === spec.key(item)),
    update: (patch) =>
      workspace.getState().updateView(path, typeof patch === 'function' ? patch(state()) : patch),
    select: (...items) => select(items),
    toggle: (item) => select(toggled(selection(), item, spec.key)),
    mapSelection: (change) =>
      select(
        selection().flatMap((item) => {
          const next = change(item)
          return next === null ? [] : [next]
        }),
      ),
  }
}

/** Document [path]'s view, for changing it from a component (stable while [path] is). */
export function useView<K extends ViewKind>(
  kind: K,
  path: string,
): DocView<ViewStateOf<K>, SelectionOf<K>> {
  const { workspace } = useApp()
  return useMemo(() => viewOf(workspace, kind, path), [workspace, kind, path])
}

/** Document [path]'s view state: its kind's `initial`, as changed. */
export function useViewState<K extends ViewKind>(kind: K, path: string): ViewStateOf<K> {
  const stored = useWorkspace((s) => s.views[path]?.state ?? EMPTY_VIEW.state)
  return useMemo(() => ({ ...specOf(kind).initial, ...stored }) as ViewStateOf<K>, [kind, stored])
}

/** What's selected in document [path], oldest first. */
export function useSelection<K extends ViewKind>(
  _kind: K,
  path: string,
): readonly SelectionOf<K>[] {
  return useWorkspace(
    (s) => (s.views[path]?.selection ?? EMPTY_VIEW.selection) as readonly SelectionOf<K>[],
  )
}

/** The primary selection in document [path]: the last item selected, or null. */
export function usePrimary<K extends ViewKind>(_kind: K, path: string): SelectionOf<K> | null {
  return useWorkspace((s) => (s.views[path]?.selection.at(-1) ?? null) as SelectionOf<K> | null)
}
