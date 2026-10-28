/**
 * The workbench's side of `core/store/workspaceMembers.ts`: asking for a new server's name, and
 * where a module goes and whether to move it, then saying how it went.
 */
import type { WorkspaceMember } from '@/core/backend/types'
import {
  moveModuleToPackage,
  newServer,
  planModuleMove,
  serverFolderFor,
  serverNameProblem,
  type MemberStores,
} from '@/core/store/workspaceMembers'
import { errorText } from '@/core/store/slice'
import { ask } from '@/ui/dialogs'
import { focusMember } from '@/workbench/toolbar/WorkspaceSwitcher'

/** Asks for a new server's name, makes it (a project of the workspace, in its network), and offers to open it. */
export async function askNewServer(app: MemberStores): Promise<WorkspaceMember | null> {
  const info = app.workspaceFile.getState().info
  if (!info) return null
  const name = await ask.prompt({
    title: `New server in ${info.name}`,
    icon: 'plus',
    label: 'Its name in the network',
    message:
      "A project of its own beside the others, with a module that greets players sent here (and a command back to where players arrive), added to the workspace's network.",
    confirmLabel: 'Create',
    validate: (value) =>
      value.trim() === ''
        ? null
        : serverNameProblem(app.workspaceFile.getState().file, value.trim()),
  })
  if (!name?.trim()) return null
  try {
    const member = await newServer(app, name.trim())
    const open = await ask.confirm({
      title: `${name.trim()} is ready`,
      icon: 'check',
      message: `It's at ${serverFolderFor(info, name.trim())}, and the network runs it on the next start. Open it now?`,
      confirmLabel: 'Open it',
    })
    if (open === true) await focusMember(app, member)
    return member
  } catch (error) {
    app.workspace.getState().notify('error', `Couldn't make the server: ${errorText(error)}`)
    return null
  }
}

/**
 * Asks which of the workspace's projects (or a new package's folder) module [id] (at [folder]) moves into,
 * says what that does, and moves it.
 */
export async function askMoveModule(
  app: MemberStores,
  id: string,
  folder: string,
): Promise<boolean> {
  const info = app.workspaceFile.getState().info
  const root = app.workspace.getState().project?.root
  if (!info) return false
  const others = info.members.filter((it) => it.root !== root)
  const where = await ask.prompt({
    title: `Move ${id} into a package`,
    icon: 'folder',
    label: "A project of the workspace, or a new package's folder",
    message: `The module goes into that project's modules/, and this project depends on it there: servers running this project still run it. ${others.length > 0 ? `In the workspace: ${others.map((it) => it.path).join(', ')}.` : ''}`,
    initial: others.find((it) => it.package)?.path ?? `packages/${id}`,
    suggestions: others.map((it) => it.path),
    confirmLabel: 'Next',
  })
  if (!where?.trim()) return false
  try {
    const plan = await planModuleMove(app, id, folder, where.trim())
    if (plan.problem) {
      app.workspace.getState().notify('error', `Can't move ${id} there: ${plan.problem}`)
      return false
    }
    const lines = [
      plan.target
        ? `${id} moves into ${plan.target.name} (${plan.targetPath}), named ${plan.namespace}:${id} from here.`
        : `A new package, ${plan.namespace}, is made at ${plan.targetPath}, and ${id} moves into it.`,
      plan.requiredBy.length > 0
        ? `The package exports it, and ${plan.requiredBy.length} script${plan.requiredBy.length === 1 ? '' : 's'} here require it as ${plan.namespace}:${id}.`
        : null,
      Object.keys(plan.dependencies).length > 0
        ? `The package depends on ${Object.keys(plan.dependencies).join(', ')}, which ${id} uses.`
        : null,
      plan.leftBehind.length > 0
        ? `It requires ${plan.leftBehind.join(', ')}, which stay${plan.leftBehind.length === 1 ? 's' : ''} here, where the package can't reach: move them too, or change ${id}.`
        : null,
      "Undo doesn't take this back.",
    ].filter(Boolean)
    const go = await ask.confirm({
      title: `Move ${id} into ${plan.namespace}`,
      icon: 'folder',
      message: lines.join(' '),
      confirmLabel: 'Move',
    })
    if (go !== true) return false
    await moveModuleToPackage(app, id, plan)
    app.workspace.getState().notify('info', `Moved ${id} into ${plan.targetPath}.`)
    return true
  } catch (error) {
    app.workspace.getState().notify('error', `Couldn't move ${id}: ${errorText(error)}`)
    return false
  }
}
