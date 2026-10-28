/** Closing tabs the way the user expects: asking first about unsaved edits. */
import { basename } from '@/core/paths'
import type { WorkspaceStore } from '@/core/store/workspace'
import { ask } from '@/ui/dialogs'

export function closeTabAsking(workspace: WorkspaceStore, tabId: string): Promise<boolean> {
  return closeTabsAsking(workspace, [tabId])
}

/**
 * Closes [tabIds], asking once about all their unsaved edits (save them,
 * throw them away, or cancel and close nothing). True if they closed.
 */
export async function closeTabsAsking(
  workspace: WorkspaceStore,
  tabIds: string[],
): Promise<boolean> {
  const ws = workspace.getState()
  const tabs = ws.tabs.filter((it) => tabIds.includes(it.id))
  if (tabs.length === 0) return true
  if (
    !(await saveAsking(
      workspace,
      tabs.map((it) => it.path),
    ))
  )
    return false
  for (const tab of tabs) workspace.getState().closeTab(tab.id)
  return true
}

/**
 * Asks once about the unsaved edits among [paths] (every open document when
 * left out): save them, throw them away, or cancel. True to go ahead.
 */
export async function saveAsking(workspace: WorkspaceStore, paths?: string[]): Promise<boolean> {
  const ws = workspace.getState()
  const dirty = Object.values(ws.docs).filter(
    (doc) => doc.dirty && (!paths || paths.includes(doc.path)),
  )
  if (dirty.length === 0) return true
  const answer = await ask.confirm({
    title: 'Unsaved changes',
    message: `Save changes to ${dirty.map((it) => basename(it.path)).join(', ')} before closing?`,
    confirmLabel: 'Save',
    alternative: "Don't save",
  })
  if (answer === false) return false
  if (answer === true) for (const doc of dirty) if (!(await ws.save(doc.path))) return false
  return true
}

/**
 * The tab [step] places from [active] in the strip's order, wrapping round
 * at either end (Ctrl+Tab, Ctrl+Shift+Tab); null when there's nowhere to go.
 */
export function stepTab(tabIds: string[], active: string | null, step: 1 | -1): string | null {
  if (tabIds.length === 0) return null
  const at = active === null ? -1 : tabIds.indexOf(active)
  if (at === -1) return tabIds[step === 1 ? 0 : tabIds.length - 1]!
  const next = tabIds[(at + step + tabIds.length) % tabIds.length]!
  return next === active ? null : next
}

/** Closes the project after asking about unsaved edits; false if the user cancelled. */
export async function closeProjectAsking(workspace: WorkspaceStore): Promise<boolean> {
  if (!workspace.getState().project) return true
  if (!(await saveAsking(workspace))) return false
  await workspace.getState().closeProject()
  return true
}
