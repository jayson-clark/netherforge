/**
 * Changes that make or reshape a workspace's projects: a new project in it (`createMember`, the
 * workspace file naming it when no `projects` entry does), a new server of its network with a
 * module to start from (`newServer`), and a module moved out of the open project into a package
 * of the workspace, the project depending on it (`moveModuleToPackage`). They write other projects'
 * files, so Undo doesn't take them back.
 */
import type { Backend, WorkspaceInfo, WorkspaceMember } from '@/core/backend/types'
import {
  canonicalizeModel,
  isWorkspaceServerName,
  KINDS,
  MANIFEST_FILE,
  newProjectFiles,
  WORKSPACE_FILE,
  type ProjectManifest,
  type WorkspaceFile,
} from '@/core/format'
import { readsText } from '@/core/paths'
import { normalizeFolder, type DependencySource } from './packages'
import { errorText, isUnder } from './slice'
import type { WorkspaceStore } from './workspace'
import type { WorkspaceFileStore } from './workspaceFile'

export interface MemberStores {
  backend: Backend
  workspace: WorkspaceStore
  workspaceFile: WorkspaceFileStore
}

/** Whether one of [projects] (the workspace file's entries) names the folder [path]: itself, or its parent and `/*`. */
export function namedBy(projects: string[], path: string): boolean {
  const parent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
  return projects.some((raw) => {
    const it = raw.trim().replace(/^\.\//, '').replace(/\/+$/, '')
    return it === path || it === (parent ? `${parent}/*` : '*')
  })
}

/** The path from the workspace member at [from] to the one at [to] (both relative to the workspace), as a dependency's `path`. */
export function relativeTo(from: string, to: string): string {
  const a = from.split('/').filter(Boolean)
  const b = to.split('/').filter(Boolean)
  let same = 0
  while (same < a.length && same < b.length && a[same] === b[same]) same++
  const up = a.slice(same).map(() => '..')
  return [...up, ...b.slice(same)].join('/') || '.'
}

/** A manifest's canonical text, or why it can't be written. */
function manifestText(manifest: unknown): string {
  const written = canonicalizeModel('netherforge', MANIFEST_FILE, manifest)
  if (!written.text)
    throw new Error(written.problems[0]?.message ?? `Couldn't write ${MANIFEST_FILE}`)
  return written.text
}

/** The open project's member of its workspace. */
function openMember(stores: MemberStores): { info: WorkspaceInfo; member: WorkspaceMember } {
  const info = stores.workspaceFile.getState().info
  const root = stores.workspace.getState().project?.root
  const member = info?.members.find((it) => it.root === root)
  if (!info || !member) throw new Error("The open project isn't in a workspace")
  return { info, member }
}

/**
 * Makes a project of the workspace at [path] from [files], and names it in the workspace file
 * when no entry does. Answers it as a member.
 */
export async function createMember(
  stores: MemberStores,
  path: string,
  files: Record<string, string>,
): Promise<WorkspaceMember> {
  const { backend, workspaceFile } = stores
  const file = workspaceFile.getState().file
  if (!file) throw new Error(`${WORKSPACE_FILE} doesn't read: fix it first`)
  await backend.workspaceCreateMember(path, files)
  if (!namedBy(file.projects, path)) {
    const next: WorkspaceFile = { ...file, projects: [...file.projects, path] }
    const written = canonicalizeModel('workspace', WORKSPACE_FILE, next)
    if (!written.text)
      throw new Error(written.problems[0]?.message ?? `Couldn't write ${WORKSPACE_FILE}`)
    await backend.workspaceWrite(written.text)
  }
  await workspaceFile.getState().refresh()
  const member = workspaceFile.getState().info?.members.find((it) => it.path === path)
  if (!member) throw new Error(`${path} was made, but the workspace doesn't list it`)
  return member
}

/** Why [name] can't name a new server of the workspace's network, or null when it can. */
export function serverNameProblem(file: WorkspaceFile | null, name: string): string | null {
  if (!isWorkspaceServerName(name)) return 'Lowercase letters, digits, _ and - (at most 32)'
  if (file?.network?.servers[name] !== undefined) return `There's already a server named "${name}"`
  return null
}

/** Where a new server named [name] goes: beside the other servers (`servers/<name>`), or at the top. */
export function serverFolderFor(info: WorkspaceInfo, name: string): string {
  return info.members.some((it) => it.path.startsWith('servers/')) ? `servers/${name}` : name
}

/** The module a new server of a network starts with: players arriving, and a command back to where they arrive. */
export function serverModule(name: string, arrival: string | null): string {
  const back =
    arrival && arrival !== name
      ? `

-- /${arrival} sends a player back to ${arrival}, where players arrive when they join.
nf.commands.register("${arrival}", { description = "Go back to ${arrival}" }, function(event)
  local player = event.player
  if not player then
    event.sender:send_message("Only a player can go to ${arrival}.")
    return
  end
  nf.network.send_player(player, "${arrival}", { from = nf.network.server_name() }, function(moved, err)
    if not moved and player:exists() then
      player:send_message("<red>Couldn't go to ${arrival}: " .. tostring(err))
    end
  end)
end)`
      : ''
  return `-- ${name}: a server of the network, behind its proxy. Other servers send players here with
-- nf.network.send_player(player, "${name}", data), and the data arrives with them.

nf.on("player_arrive", function(event)
  event.player:send_message("<gray>Welcome to <white>${name}</white>.")
end)${back}
`
}

/**
 * A new project for a server named [name] of the workspace's network: made beside the others
 * ([serverFolderFor]), on the open project's Minecraft version, with a module to start from, and
 * added to the network (the network made, arriving on it, when there's none).
 */
export async function newServer(stores: MemberStores, name: string): Promise<WorkspaceMember> {
  const { workspace, workspaceFile } = stores
  const { info } = openMember(stores)
  const file = workspaceFile.getState().file
  const problem = serverNameProblem(file, name)
  if (problem) throw new Error(problem)
  const minecraft = workspace.getState().minecraft
  if (!minecraft)
    throw new Error(`The open project's ${MANIFEST_FILE} names no Minecraft version to start from`)
  const network = file?.network ?? null
  const files = newProjectFiles(name, minecraft)
  const manifest = JSON.parse(files[MANIFEST_FILE]!) as ProjectManifest
  const module = manifest.namespace
  const written: Record<string, string> = {
    ...files,
    [MANIFEST_FILE]: manifestText({
      ...manifest,
      requires: { ...manifest.requires, network: true },
    }),
    [`${KINDS.module.folder}/${module}/init.lua`]: serverModule(name, network?.arrival ?? null),
  }
  delete written[`${KINDS.module.folder}/.gitkeep`]
  const folder = serverFolderFor(info, name)
  const member = await createMember(stores, folder, written)
  await workspaceFile.getState().saveNetwork({
    arrival: network?.arrival ?? name,
    servers: { ...(network?.servers ?? {}), [name]: folder },
  })
  const error = workspaceFile.getState().error
  if (error) throw new Error(`${folder} was made, but the network couldn't name it: ${error}`)
  return member
}

/** `require("id")` and `require("id.inner")` of a module (an id is letters, digits and _), with what's around the id. */
const requireOf = (id: string) =>
  new RegExp(`(\\brequire\\s*\\(?\\s*)(["'])${id}((?:\\.[^"']*)?)\\2`, 'g')

/** The bare module names [text] requires (`require("expo")`), not a package's (`ns:name`). */
function requiredModules(text: string): string[] {
  const found = new Set<string>()
  for (const match of text.matchAll(/\brequire\s*\(?\s*["']([^"':]+)["']/g))
    found.add(match[1]!.split('.')[0]!)
  return [...found]
}

/** Where a module would go, and what moving it there would do, for the dialog to say. */
export interface ModuleMove {
  /** The module's folder in the project (`modules/(world)/greeter`), and its files there. */
  folder: string
  files: string[]
  /** The package: a member of the workspace, or a new one at [targetPath]. */
  target: WorkspaceMember | null
  targetPath: string
  /** The package's namespace: what the project names the module by after (`ns:greeter`). */
  namespace: string
  /** The project's scripts that require it, so the package exports it and they name it `ns:id`. */
  requiredBy: string[]
  /** Other modules of the project it requires, which the package can't. */
  leftBehind: string[]
  /**
   * The project's packages the module names (`library:greetings`), which the package then
   * depends on too, from where it is (the target itself names its own bare).
   */
  dependencies: Record<string, DependencySource>
  /** Why it can't be moved, or null. */
  problem: string | null
}

/**
 * What moving the open project's module [id] (its [folder], `modules/(world)/greeter`) into the
 * workspace project at [targetPath] (a member's, or a new package's) would do.
 */
export async function planModuleMove(
  stores: MemberStores,
  id: string,
  folder: string,
  targetPath: string,
): Promise<ModuleMove> {
  const { backend, workspace } = stores
  const { info, member: home } = openMember(stores)
  const state = workspace.getState()
  const files = state.files.filter((it) => isUnder(it, folder) && !it.endsWith('/.gitkeep'))
  const path = normalizeFolder(targetPath)
  const target = info.members.find((it) => it.path === path) ?? null
  let namespace = path.split('/').at(-1) ?? ''
  let problem: string | null = null
  const fail = (why: string) => (problem ??= why)
  if (files.length === 0) fail(`${id} has no files`)
  if (files.some((it) => !readsText(it)))
    fail(`${id} has files that aren't text, which can't be moved yet`)
  if (files.some((it) => state.docs[it]?.dirty)) fail(`Save ${id}'s files first`)
  if (state.docs[MANIFEST_FILE]?.dirty) fail(`Save ${MANIFEST_FILE} first`)
  if (path === home.path) fail("That's this project")
  if (target) {
    try {
      const manifest = JSON.parse(
        await backend.workspaceMemberReadText(path, MANIFEST_FILE),
      ) as ProjectManifest
      namespace = manifest.namespace
      const theirs = await backend.workspaceMemberFiles(path)
      if (theirs.files.some((it) => isUnder(it.path, `${KINDS.module.folder}/${id}`)))
        fail(`${target.name} already has a module ${id}`)
    } catch (error) {
      fail(`Couldn't read ${path}: ${errorText(error)}`)
    }
  } else {
    if (!/^[a-z0-9][a-z0-9_]{0,63}$/.test(namespace))
      fail(`A new package's folder name is its namespace: lowercase letters, digits and _`)
    if (info.members.some((it) => isUnder(path, it.path) || isUnder(it.path, path)))
      fail(`${path} is inside another of the workspace's projects, or holds one`)
  }
  if (namespace === state.outline?.namespace) fail(`${namespace} is this project's own namespace`)
  const requiredBy: string[] = []
  const leftBehind = new Set<string>()
  const modules = new Set(state.outline?.resources.module ?? [])
  const declared = projectManifest(state)?.dependencies ?? {}
  const dependencies: Record<string, DependencySource> = {}
  for (const file of state.files) {
    if (!file.endsWith('.lua')) continue
    const text = state.diskTexts[file] ?? (await backend.readText(file).catch(() => ''))
    if (isUnder(file, folder)) {
      for (const name of requiredModules(text))
        if (name !== id && modules.has(name)) leftBehind.add(name)
      for (const ns of namespacesIn(text)) {
        const source = declared[ns]
        if (!source || ns === namespace) continue
        dependencies[ns] =
          'path' in source && source.path !== undefined
            ? { path: relativeTo(path, normalizeFolder(`${home.path}/${source.path}`)) }
            : (source as DependencySource)
      }
    } else if (requireOf(id).test(text)) {
      requiredBy.push(file)
    }
  }
  return {
    folder,
    files,
    target,
    targetPath: path,
    namespace,
    requiredBy,
    leftBehind: [...leftBehind].sort(),
    dependencies,
    problem,
  }
}

/** The open project's manifest as it is on disk, or null when it doesn't read. */
function projectManifest(state: { diskTexts: Record<string, string> }): ProjectManifest | null {
  try {
    return JSON.parse(state.diskTexts[MANIFEST_FILE] ?? 'null') as ProjectManifest | null
  } catch {
    return null
  }
}

/** The namespaces [text]'s string literals start with (`"library:gem"` → `library`). */
function namespacesIn(text: string): string[] {
  return [...new Set([...text.matchAll(/["']([a-z0-9_]+):/g)].map((it) => it[1]!))]
}

/**
 * Moves the open project's module as [plan] says: its files into the package (a new one made
 * first), exported there when the project requires it, the project depending on the package (by
 * path) and its scripts requiring it as `ns:id`, and the module gone from the project.
 */
export async function moveModuleToPackage(
  stores: MemberStores,
  id: string,
  plan: ModuleMove,
): Promise<void> {
  if (plan.problem) throw new Error(plan.problem)
  const { backend, workspace } = stores
  const { member: home } = openMember(stores)
  const state = workspace.getState()
  const minecraft = state.minecraft
  const texts: Record<string, string> = {}
  for (const file of plan.files)
    texts[file] = state.diskTexts[file] ?? (await backend.readText(file))

  // The package: made now when it's new.
  let target = plan.target
  if (!target) {
    if (!minecraft)
      throw new Error(`${MANIFEST_FILE} names no Minecraft version for the new package`)
    const files = newProjectFiles(plan.namespace, minecraft)
    delete files[`${KINDS.module.folder}/.gitkeep`]
    target = await createMember(stores, plan.targetPath, files)
  }
  // In the package its own things are named bare: `library:greetings` moved into library is `greetings`.
  const own = new RegExp(`(["'])${plan.namespace}:`, 'g')
  for (const [file, text] of Object.entries(texts)) {
    const inside = file.slice(plan.folder.length + 1)
    await backend.workspaceMemberWriteText(
      target.path,
      `${KINDS.module.folder}/${id}/${inside}`,
      text.replace(own, '$1'),
    )
  }
  const theirs = JSON.parse(
    await backend.workspaceMemberReadText(target.path, MANIFEST_FILE),
  ) as ProjectManifest
  const exported = new Set(theirs.exports?.modules ?? [])
  if (plan.requiredBy.length > 0) exported.add(id)
  const missing = Object.entries(plan.dependencies).filter(([ns]) => !theirs.dependencies?.[ns])
  if (exported.size !== (theirs.exports?.modules ?? []).length || missing.length > 0) {
    await backend.workspaceMemberWriteText(
      target.path,
      MANIFEST_FILE,
      manifestText({
        ...theirs,
        ...(exported.size > 0
          ? { exports: { ...theirs.exports, modules: [...exported].sort() } }
          : {}),
        ...(missing.length > 0
          ? { dependencies: { ...theirs.dependencies, ...Object.fromEntries(missing) } }
          : {}),
      }),
    )
  }

  // The project: depending on the package, its scripts naming the module in it, the module gone.
  const written: Record<string, string> = {}
  const manifest = JSON.parse(
    state.diskTexts[MANIFEST_FILE] ?? (await backend.readText(MANIFEST_FILE)),
  ) as ProjectManifest
  if (!manifest.dependencies?.[plan.namespace]) {
    written[MANIFEST_FILE] = manifestText({
      ...manifest,
      dependencies: {
        ...manifest.dependencies,
        [plan.namespace]: { path: relativeTo(home.path, target.path) },
      },
    })
  }
  for (const file of plan.requiredBy) {
    const text = state.diskTexts[file] ?? (await backend.readText(file))
    written[file] = text.replace(requireOf(id), `$1$2${plan.namespace}:${id}$3$2`)
  }
  for (const [file, text] of Object.entries(written)) await backend.writeText(file, text)
  await backend.deletePath(plan.folder)
  workspace.getState().shelvePaths(plan.folder)
  const diskTexts = Object.fromEntries(
    Object.entries(workspace.getState().diskTexts).filter(([it]) => !isUnder(it, plan.folder)),
  )
  workspace.setState({ diskTexts: { ...diskTexts, ...written } })
  await workspace.getState().refreshFiles()
  await workspace.getState().refreshPackages()
  workspace.getState().validateSoon()
  workspace.getState().hotReload([...plan.files, ...Object.keys(written)])
}
