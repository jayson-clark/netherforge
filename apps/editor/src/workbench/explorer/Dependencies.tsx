/**
 * A package the project depends on, in the explorer: where it came from,
 * and its resources by kind, read-only (they're the package's, used as they
 * are). Each can be copied into the project under an id of its own,
 * references and all, where it becomes the project's to change. A package
 * from git can be updated: every git dependency moves to its rev's newest
 * commit, and the lock with it.
 */
import { KINDS, type KindId, type PackageOutline } from '@/core/format'
import { isBinaryKind } from '@/core/paths'
import type { Backend } from '@/core/backend/types'
import { describeOrigin, workspaceMemberAt } from '@/core/store/packages'
import { TEMPLATES } from '@/core/templates'
import { useWorkspace } from '@/state/providers'
import type { WorkspaceStore } from '@/core/store/workspace'
import { Badge } from '@/ui/Badge'
import { Button } from '@/ui/Button'
import { openMenu, type MenuItem } from '@/ui/ContextMenu'
import { ask } from '@/ui/dialogs'
import { Bar, Spacer } from '@/ui/layout'
import { Muted } from '@/ui/text'
import {
  askFolderDependency,
  askGitDependency,
  type DependencyChoice,
} from '@/editors/project/dependencyPrompts'
import { copyId, idProblem } from '@/workbench/resourceActions'
import { focusMember } from '@/workbench/toolbar/WorkspaceSwitcher'
import styles from './Explorer.module.css'
import { EXPLORER_FOLDERS, isFileFolder, type ExplorerFolder } from './folders'
import { FolderIcon } from './Thumbnail'

/**
 * Asks for the copy's id (the same one when it's free) and copies the
 * package's resource in. The id it got, or null when cancelled or it failed.
 */
export async function copyIntoProject(
  workspace: WorkspaceStore,
  namespace: string,
  folder: ExplorerFolder,
  id: string,
): Promise<string | null> {
  const taken = folder.ids(workspace.getState().files)
  const to = await ask.prompt({
    title: `Copy ${namespace}:${id} into the project`,
    label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
    message: `It becomes the project's own ${folder.one}, to change as you like. What it names of "${namespace}" it keeps naming as ${namespace}:…; the package stays as it is.`,
    initial: taken.includes(id) ? copyId(id, taken) : id,
    confirmLabel: 'Copy',
    validate: (value) => idProblem(value, taken),
  })
  if (!to) return null
  return (await workspace.getState().copyFromPackage(namespace, folder.key, id, to)) ? to : null
}

/**
 * The explorer's **Add dependency…** menu: a folder (the native picker) or a
 * git repository, added to `netherforge.json`. [onAdded] gets the namespace
 * of the one that was added.
 */
export function dependencyMenu(
  backend: Backend,
  workspace: WorkspaceStore,
  onAdded: (namespace: string) => void,
): MenuItem[] {
  const add = async (choice: DependencyChoice | null) => {
    if (choice && (await workspace.getState().addDependency(choice.namespace, choice.source)))
      onAdded(choice.namespace)
  }
  return [
    { label: 'Folder…', run: () => void askFolderDependency(backend, workspace).then(add) },
    { label: 'Git repository…', run: () => void askGitDependency(workspace).then(add) },
  ]
}

/**
 * The menu of project templates (`core/templates.ts`): each copies its
 * resources into the project as the project's own.
 */
export function templateMenu(workspace: WorkspaceStore): MenuItem[] {
  return TEMPLATES.map((template) => ({
    label: `${template.title}: ${template.summary}`,
    run: () => void workspace.getState().applyTemplate(template.id),
  }))
}

export function PackageView({
  workspace,
  namespace,
  pkg,
  onCopied,
}: {
  workspace: WorkspaceStore
  namespace: string
  pkg: PackageOutline
  onCopied: (folder: ExplorerFolder, id: string) => void
}) {
  const groups = EXPLORER_FOLDERS.flatMap((folder) => {
    const ids = pkg.resources[folder.key as KindId] ?? []
    return ids.length ? [{ folder, ids }] : []
  })
  const exported = (folder: ExplorerFolder, id: string) =>
    // `exports` names a kind by its folder.
    (pkg.exports[KINDS[folder.key].folder] ?? []).includes(id)
  const copy = async (folder: ExplorerFolder, id: string) => {
    const to = await copyIntoProject(workspace, namespace, folder, id)
    if (to) onCopied(folder, to)
  }
  /** The group folders the package keeps [id] in. */
  const groupIn = (folder: ExplorerFolder, id: string) => pkg.groups?.[folder.key]?.[id] ?? ''
  /** Opens it as the package has it: read-only, unless the package is one of the workspace's projects. */
  const openable = (folder: ExplorerFolder) => !isBinaryKind(folder.key)
  const open = (folder: ExplorerFolder, id: string) => {
    if (openable(folder))
      void workspace.getState().openFile(`${namespace}:${folder.openPath(id, groupIn(folder, id))}`)
  }
  const fetching = useWorkspace((s) => s.fetchingPackages)
  // A project of the workspace: edited in place, not copied.
  const member = workspaceMemberAt(
    useWorkspace((s) => s.project),
    pkg.location,
  )
  const menuFor = (folder: ExplorerFolder, id: string): MenuItem[] => [
    ...(openable(folder)
      ? [{ label: member ? 'Open' : 'Open (read-only)', run: () => open(folder, id) }]
      : []),
    ...(member ? [] : [{ label: 'Copy into project…', run: () => void copy(folder, id) }]),
    {
      label: 'Copy reference',
      run: () => void navigator.clipboard?.writeText(`${namespace}:${id}`),
    },
  ]

  return (
    <>
      <Bar>
        <span className={styles.title}>{pkg.name ?? namespace}</span>
        <Muted>
          {namespace}
          {pkg.version ? ` ${pkg.version}` : ''} · {describeOrigin(pkg.origin)}
        </Muted>
        <Spacer />
        {pkg.origin.type === 'git' && (
          <Button
            size="small"
            disabled={fetching}
            onClick={() => void workspace.getState().updatePackages()}
          >
            {fetching ? 'Fetching…' : 'Update from git'}
          </Button>
        )}
        {member ? (
          <>
            <Button size="small" onClick={() => void focusMember({ workspace }, member)}>
              Open its project
            </Button>
            <Badge title="One of the workspace's projects: edited in place, and saving it reloads every server that uses it">
              in the workspace
            </Badge>
          </>
        ) : (
          <Badge title="A dependency is used as it is: copy a resource into the project to change it">
            read-only
          </Badge>
        )}
      </Bar>
      {groups.length === 0 ? (
        <div className={styles.empty}>
          <p>This package has no resources that read.</p>
        </div>
      ) : (
        <div
          className={`${styles.items} ${styles.list}`}
          role="list"
          aria-label={`Package ${namespace}`}
        >
          {groups.map(({ folder, ids }) => (
            <div key={folder.key} role="group" aria-label={folder.title}>
              <div className={styles.groupTitle}>{folder.title}</div>
              {ids.map((id) => (
                <div
                  key={id}
                  role="listitem"
                  aria-label={`${namespace}:${id}`}
                  className={styles.row}
                  data-readonly={member ? undefined : true}
                  onDoubleClick={() => open(folder, id)}
                  onContextMenu={(event) => openMenu(event, menuFor(folder, id))}
                >
                  <span className={styles.picture}>
                    <FolderIcon folder={folder} />
                  </span>
                  <span
                    className={styles.label}
                    title={`${namespace}:${folder.location(id, groupIn(folder, id))}`}
                  >
                    {id}
                  </span>
                  {exported(folder, id) && (
                    <Badge title="Other projects may use it: the package exports it">
                      exported
                    </Badge>
                  )}
                  {!member && (
                    <Button
                      size="small"
                      aria-label={`Copy ${namespace}:${id} into project`}
                      onClick={() => void copy(folder, id)}
                    >
                      Copy into project
                    </Button>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </>
  )
}
