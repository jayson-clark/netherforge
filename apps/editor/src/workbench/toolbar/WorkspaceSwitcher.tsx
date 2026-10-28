/**
 * The project's name in the toolbar, and, for a project in a workspace, the workspace's name with
 * a menu of its projects: picking one asks about unsaved edits and opens it. A workspace's running
 * dev network keeps running (the backend attaches the editor to the server running the new one).
 */
import type { WorkspaceMember } from '@/core/backend/types'
import { useApp, useNetwork, useWorkspace } from '@/state/providers'
import { openMenu } from '@/ui/ContextMenu'
import { Icon } from '@/ui/Icon'
import { Logo } from '@/ui/Logo'
import { saveAsking } from '@/workbench/editorArea/tabs'
import { askNewServer } from '@/workbench/workspaceActions'
import styles from './Toolbar.module.css'

/** Opens [member] in place of the open project, after asking about unsaved edits. */
export async function focusMember(
  app: Pick<ReturnType<typeof useApp>, 'workspace'>,
  member: WorkspaceMember,
) {
  if (!(await saveAsking(app.workspace))) return
  await app.workspace.getState().openProject(member.root)
}

export function WorkspaceSwitcher() {
  const app = useApp()
  const project = useWorkspace((s) => s.project)
  const members = useNetwork((s) => s.members)
  if (!project) return null
  const group = project.workspace
  if (!group) {
    return (
      <span className={styles.project} title={project.root} data-tauri-drag-region>
        <Logo size={16} /> {project.name}
      </span>
    )
  }
  /** What a member is to the running network: the servers that run it, if any are up. */
  const running = (member: WorkspaceMember) =>
    members
      .filter((m) => m.project === member.root && m.state.phase === 'running')
      .map((m) => m.name)
  const label = (member: WorkspaceMember) => {
    const up = running(member)
    const kind = member.package ? 'package' : member.path
    return `${member.name} · ${kind}${up.length > 0 ? ` · running as ${up.join(', ')}` : ''}`
  }
  return (
    <button
      type="button"
      className={styles.switcher}
      title={`${group.root}: switch to another of its projects`}
      aria-label={`Workspace ${group.name}: ${project.name}. Switch project`}
      onClick={(event) =>
        openMenu(event, [
          ...group.members.map((member) => ({
            label: label(member),
            disabled: member.root === project.root,
            run: () => void focusMember(app, member),
          })),
          'separator',
          { label: 'New Server…', run: () => void askNewServer(app) },
        ])
      }
    >
      <Logo size={16} />
      <span className={styles.workspaceName}>{group.name}</span>
      <Icon name="chevronRight" size={12} />
      <span>{project.name}</span>
      <Icon name="chevronDown" size={12} />
    </button>
  )
}
