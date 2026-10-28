/**
 * Asking which package to depend on, for the explorer's **Add dependency…**
 * and the project settings' Dependencies section alike: a folder from the
 * native picker (its namespace read from its `netherforge.json`, which a
 * dependency's name must be), or a git repository's URL and the namespace it
 * has.
 */
import type { Backend } from '@/core/backend/types'
import { MANIFEST_FILE, NAMESPACE_PATTERN, NAMESPACE_RULE } from '@/core/format'
import { relativeFolder } from '@/core/paths'
import { directDependencies, type DependencySource } from '@/core/store/packages'
import { namespaceOf } from '@/core/store/validation'
import type { WorkspaceStore } from '@/core/store/workspace'
import { ask } from '@/ui/dialogs'

export interface DependencyChoice {
  namespace: string
  source: DependencySource
}

/** Why [name] can't be another dependency of the project, or null. */
function nameProblem(workspace: WorkspaceStore, name: string): string | null {
  if (!NAMESPACE_PATTERN.test(name))
    return NAMESPACE_RULE.charAt(0).toUpperCase() + NAMESPACE_RULE.slice(1)
  if (name === namespaceOf(workspace.getState())) return "That's this project's own namespace"
  if (directDependencies(workspace.getState()).includes(name))
    return 'It depends on that one already'
  return null
}

/**
 * A folder picked natively, as a path relative to the project, under the
 * namespace its `netherforge.json` says. Null, with a notice when it's not
 * one that can be, if there's none.
 */
export async function askFolderDependency(
  backend: Backend,
  workspace: WorkspaceStore,
): Promise<DependencyChoice | null> {
  const { project, notify } = workspace.getState()
  if (!project) return null
  const picked = await backend.pickFolder("Choose the package's folder")
  if (!picked) return null
  const path = relativeFolder(project.root, picked)
  if (path === null || path === '.') {
    notify(
      'error',
      path === null
        ? 'A package has to be on the same drive as the project.'
        : "That's the project's own folder.",
    )
    return null
  }
  let namespace: unknown
  try {
    namespace = (
      JSON.parse(await backend.packageReadText(path, MANIFEST_FILE)) as { namespace?: unknown }
    ).namespace
  } catch {
    namespace = undefined
  }
  if (typeof namespace !== 'string') {
    notify(
      'error',
      `${path} isn't a NetherForge project: it has no ${MANIFEST_FILE} with a namespace.`,
    )
    return null
  }
  const problem = nameProblem(workspace, namespace)
  if (problem) {
    notify('error', `Can't depend on ${namespace} (${path}): ${problem.toLowerCase()}.`)
    return null
  }
  return { namespace, source: { path } }
}

/** A repository's name as a namespace guess: `https://…/acme-economy.git` → `acme_economy`. */
export function namespaceFromUrl(url: string): string {
  const last = url.replace(/\/+$/, '').split(/[/:]/).pop() ?? ''
  return last
    .replace(/\.git$/, '')
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '')
}

/** A git repository's URL, then the namespace it has (guessed from the URL). Null if cancelled. */
export async function askGitDependency(
  workspace: WorkspaceStore,
): Promise<DependencyChoice | null> {
  const url = await ask.prompt({
    title: 'Depend on a git repository',
    icon: 'box',
    label: 'Repository URL',
    initial: 'https://',
    confirmLabel: 'Next',
    validate: (value) =>
      /^\S+$/.test(value.trim()) && value.trim() !== 'https://' ? null : 'Required',
  })
  if (!url) return null
  const namespace = await ask.prompt({
    title: 'Depend on a git repository',
    icon: 'box',
    label: "Its namespace (the repository's netherforge.json says it)",
    initial: namespaceFromUrl(url.trim()),
    confirmLabel: 'Add',
    validate: (value) => nameProblem(workspace, value.trim()),
  })
  if (!namespace) return null
  return { namespace: namespace.trim(), source: { git: url.trim() } }
}
