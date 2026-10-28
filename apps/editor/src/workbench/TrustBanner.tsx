/**
 * Restricted mode, as VS Code has it: a project opened from a folder the user hasn't trusted
 * opens and edits, but its dev server, its Lua language features, fetching its git packages and
 * agents' tools stay off until they trust it. The backend decides and refuses (the Rust side's
 * `trust`); this only shows it and asks.
 */
import type { WorkspaceStore } from '@/core/store/workspace'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { Banner } from '@/ui/Callout'
import { ask } from '@/ui/dialogs'

/**
 * Asks the user to trust the open project, and trusts it if they agree. True when it's trusted
 * (already, or now).
 */
export async function askToTrust(workspace: WorkspaceStore): Promise<boolean> {
  const project = workspace.getState().project
  if (!project) return false
  if (project.trusted) return true
  // In a workspace, trust is the workspace's: once, for every project in it.
  const group = project.workspace
  if (group) {
    const ok = await ask.confirm({
      title: `Trust the workspace "${group.name}"?`,
      message:
        `Trusting the workspace trusts its folder and every project in it (${group.members.length}): ` +
        "NetherForge may run their scripts, and their packages' scripts, on your dev servers; start the Lua " +
        'language server in them; fetch the git packages they name; and let coding agents act on them through ' +
        'the editor. Only trust a workspace whose authors you trust.',
      confirmLabel: 'Trust workspace',
    })
    if (ok !== true) return false
    await workspace.getState().trustWorkspace(true)
    return workspace.getState().project?.trusted === true
  }
  const ok = await ask.confirm({
    title: `Trust "${project.name}"?`,
    message:
      "Trusting a project lets NetherForge run its scripts, and its packages' scripts, on your dev server; " +
      'start the Lua language server in it (which reads its .luarc.json); fetch the git packages it names; ' +
      'and let coding agents act on it through the editor. Only trust a project whose authors you trust.',
    confirmLabel: 'Trust project',
  })
  if (ok !== true) return false
  await workspace.getState().trustProject(true)
  return workspace.getState().project?.trusted === true
}

/**
 * Asks the user to stop trusting the open project, and does if they agree: its dev server stops
 * (the backend does it) and what waits for trust is off again. True when it's untrusted now.
 */
export async function askToUntrust(workspace: WorkspaceStore): Promise<boolean> {
  const project = workspace.getState().project
  if (!project) return false
  if (!project.trusted) return true
  const group = project.workspace
  if (group?.trusted) {
    const ok = await ask.confirm({
      title: `Stop trusting the workspace "${group.name}"?`,
      message:
        'Its dev servers stop, and until you trust it again no project in it runs scripts, the Lua ' +
        "language server and git packages' fetches are off, and coding agents can't act on them. " +
        'Editing works as usual.',
      confirmLabel: 'Untrust workspace',
      danger: true,
    })
    if (ok !== true) return false
    await workspace.getState().trustWorkspace(false)
    return workspace.getState().project?.trusted === false
  }
  const ok = await ask.confirm({
    title: `Stop trusting "${project.name}"?`,
    message:
      'Its dev server stops, and until you trust it again its scripts do not run, the Lua language ' +
      "server and its git packages' fetches are off, and coding agents can't act on it. " +
      'Editing works as usual.',
    confirmLabel: 'Untrust project',
    danger: true,
  })
  if (ok !== true) return false
  await workspace.getState().trustProject(false)
  return workspace.getState().project?.trusted === false
}

export function TrustBanner() {
  const { workspace } = useApp()
  const project = useWorkspace((s) => s.project)
  if (!project || project.trusted) return null
  const group = project.workspace
  return (
    <Banner role="region" aria-label="Restricted mode">
      <span>
        <strong>Restricted mode.</strong>{' '}
        {group
          ? `You haven't trusted the workspace "${group.name}", so its projects' dev servers,`
          : "You haven't trusted this project, so its dev server,"}{' '}
        Lua language features, git packages and agents&apos; tools are off. Editing works as usual.
      </span>
      <Button size="small" variant="primary" onClick={() => void askToTrust(workspace)}>
        {group ? 'Trust workspace…' : 'Trust project…'}
      </Button>
    </Banner>
  )
}
