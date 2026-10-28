/** Starting the dev server from the toolbar or the Run menu, and the EULA prompt it can open. */
import { create } from 'zustand'
import type { AppStores } from '@/state/providers'
import { askToTrust } from '@/workbench/TrustBanner'

/** Whether the EULA is being asked for, which the Run menu's Start can do too. */
export const useEulaPrompt = create<{ open: boolean }>(() => ({ open: false }))

/**
 * Starts the dev server, asking to trust the project first if it isn't (the backend refuses an
 * untrusted project's server), then for the Minecraft EULA if it isn't accepted.
 */
export async function startServer(app: Pick<AppStores, 'run' | 'workspace'>) {
  const { run } = app
  if (!(await askToTrust(app.workspace))) return
  const eula = await run.getState().refreshEula()
  if (!eula.accepted) useEulaPrompt.setState({ open: true })
  else await run.getState().start()
}
