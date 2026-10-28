/**
 * Open/closed state kept in the layout store (so it's remembered per
 * project): for panes, by key, and for tree rows, by a key per tree.
 */
import { useCallback } from 'react'
import { useApp, useLayout } from './providers'

/** A pane's open state and how to change it; open unless the user closed it. */
export function usePane(key: string, initiallyOpen = true) {
  const { layout } = useApp()
  const open = useLayout((s) => s.expanded[`pane:${key}`] ?? initiallyOpen)
  return { open, onToggle: (next: boolean) => layout.getState().setExpanded(`pane:${key}`, next) }
}

/**
 * A tree's expansion, for `Tree`'s `isExpanded`/`onExpand`: rows under
 * [tree] are open by default when [initiallyOpen] says so.
 */
export function useTreeExpansion(tree: string, initiallyOpen: (id: string) => boolean) {
  const { layout } = useApp()
  const expanded = useLayout((s) => s.expanded)
  return {
    isExpanded: (node: { id: string }) => expanded[`${tree}:${node.id}`] ?? initiallyOpen(node.id),
    onExpand: (id: string, open: boolean) => layout.getState().setExpanded(`${tree}:${id}`, open),
  }
}

/** Sets a row of [tree] open or closed (for revealing a row, or collapsing all). */
export function useExpansionSetter(tree: string) {
  const { layout } = useApp()
  return useCallback(
    (id: string, open: boolean) => layout.getState().setExpanded(`${tree}:${id}`, open),
    [layout, tree],
  )
}
