/**
 * Keyboard conventions shared by the app's shortcuts. The shortcuts
 * themselves are the menus' commands (`menus/commands.ts`); editors add
 * their own (the centity editor's Delete/W/E/R, Monaco's text editing) and
 * win when focus is inside them.
 */
import { IS_MAC } from '@/core/shortcut'
import type { WorkspaceStore } from '@/core/store/workspace'
import { isEditableTarget } from '@/ui/editable'

export { IS_MAC }

/**
 * Whether undo in [target] belongs to the document rather than the field: a
 * field that commits on Enter or blur, with nothing typed since (`data-commit`).
 */
export function undoesDocument(target: EventTarget | null): boolean {
  if (!isEditableTarget(target)) return true
  return target instanceof HTMLElement && target.dataset.commit === 'clean'
}

/** The document behind the active tab: none for a tab no document backs (a map, the settings). */
export function activeDocPath(workspace: WorkspaceStore): string | null {
  const { tabs, activeTab, docs } = workspace.getState()
  const tab = tabs.find((it) => it.id === activeTab)
  return tab && docs[tab.path] ? tab.path : null
}
