import { create } from 'zustand'
import type { WorkspaceStore } from '@/core/store/workspace'

/**
 * What the welcome screen shows besides the recent list, kept outside it so
 * the File menu can ask for the create form or report a project that
 * wouldn't open.
 */
export const useWelcome = create<{ creating: boolean; error: string | null }>(() => ({
  creating: false,
  error: null,
}))

/** Opens [root], showing why on the welcome screen if it won't open. */
export async function openProjectAt(workspace: WorkspaceStore, root: string) {
  useWelcome.setState({ error: null })
  try {
    await workspace.getState().openProject(root)
    useWelcome.setState({ creating: false })
  } catch (e) {
    useWelcome.setState({ error: e instanceof Error ? e.message : String(e) })
  }
}
