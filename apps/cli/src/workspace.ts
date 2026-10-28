/**
 * Workspaces: several projects under one `netherforge-workspace.json` (docs/format/workspace.md).
 * Run in a workspace's folder, `check`, `format`, `lock` and `test` go through every project in it,
 * packages first, each from its own folder, and fail if any did.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import { MANIFEST_FILE, WORKSPACE_FILE } from '@netherforge/format/constants'
import type { Problem, WorkspaceResult } from '@netherforge/format/types'
import { formatProblem, type Outcome } from './commands.ts'

/** Folders a workspace's projects are never in. */
const SKIPPED = new Set(['node_modules', 'build', 'dist', 'target'])

/** How deep under a workspace a project may be: `servers/hub` is 2. */
const DEPTH = 4

/** Whether [dir] is a workspace's folder: it holds a netherforge-workspace.json. */
export function isWorkspace(dir: string): boolean {
  try {
    return statSync(path.join(dir, WORKSPACE_FILE)).isFile()
  } catch {
    return false
  }
}

/** Every folder under [root] (relative, `/`-separated) that holds a netherforge.json, as format's check takes them. */
export function findProjects(root: string): string[] {
  const found: string[] = []
  const walk = (dir: string, depth: number) => {
    let entries
    try {
      entries = readdirSync(path.join(root, dir), { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.') || SKIPPED.has(entry.name)) continue
      const child = dir ? `${dir}/${entry.name}` : entry.name
      try {
        if (statSync(path.join(root, child, MANIFEST_FILE)).isFile()) found.push(child)
      } catch {
        // Not a project; one may still be inside it.
      }
      if (depth < DEPTH) walk(child, depth + 1)
    }
  }
  walk('', 1)
  return found.sort()
}

export interface Workspace {
  root: string
  name: string | null
  /** The project folders, in the order to run them: packages (those others depend on) first. */
  projects: string[]
  problems: Problem[]
  /** Its dev network's servers' names, when it has a network: what scripts may send players to. */
  servers: string[] | null
  /** Its dev network's servers, each one's name → the project it runs (its folder in the workspace). */
  network: Record<string, string> | null
}

/** The workspace at [root], read and checked. */
export function readWorkspace(root: string): Workspace {
  const text = readFileSync(path.join(root, WORKSPACE_FILE), 'utf8')
  const result = JSON.parse(
    nf.checkWorkspace(text, JSON.stringify(findProjects(root))),
  ) as WorkspaceResult
  return {
    root,
    name: result.workspace?.name ?? null,
    projects: dependenciesFirst(root, result.members),
    problems: result.problems,
    servers: result.workspace?.network ? Object.keys(result.workspace.network.servers) : null,
    network: result.workspace?.network?.servers ?? null,
  }
}

/**
 * The workspace the project at [root] is a member of: the nearest folder above it with a
 * netherforge-workspace.json (as far up as a member may be), when that workspace lists it.
 */
export function workspaceAround(root: string): Workspace | null {
  let dir = path.resolve(root)
  for (let depth = 0; depth < DEPTH; depth++) {
    const parent = path.dirname(dir)
    if (parent === dir) return null
    dir = parent
    if (!isWorkspace(dir)) continue
    const workspace = readWorkspace(dir)
    const member = path.relative(dir, path.resolve(root)).split(path.sep).join('/')
    return workspace.projects.includes(member) ? workspace : null
  }
  return null
}

/**
 * [members] with each project after the workspace's projects it depends on by path (packages
 * first), otherwise in the workspace's order. A cycle keeps the workspace's order.
 */
export function dependenciesFirst(root: string, members: string[]): string[] {
  const dependsOn = new Map<string, string[]>()
  for (const member of members) {
    let manifest: { dependencies?: Record<string, { path?: string }> } = {}
    try {
      manifest = JSON.parse(readFileSync(path.join(root, member, MANIFEST_FILE), 'utf8'))
    } catch {
      // A manifest that doesn't read is check's to report: it depends on nothing here.
    }
    const targets = Object.values(manifest.dependencies ?? {})
      .map((it) => it.path)
      .filter((it): it is string => typeof it === 'string')
      .map((it) => path.posix.normalize(path.posix.join(member, it)))
      .filter((it) => members.includes(it))
    dependsOn.set(member, targets)
  }
  const ordered: string[] = []
  const visiting = new Set<string>()
  const visit = (member: string) => {
    if (ordered.includes(member) || visiting.has(member)) return
    visiting.add(member)
    for (const target of dependsOn.get(member) ?? []) visit(target)
    visiting.delete(member)
    ordered.push(member)
  }
  members.forEach(visit)
  return ordered
}

/**
 * Runs [run] in each of the workspace's projects, under a heading per project, after the workspace
 * file's own problems. Fails (1) when the workspace file has errors or any project failed; 2 when
 * one couldn't run at all and nothing failed outright. With [live], each heading is printed through
 * it as its project starts (a command that prints as it goes, `test`), not gathered.
 */
export function eachProject(
  workspace: Workspace,
  run: (root: string) => Outcome,
  live?: (line: string) => void,
): Outcome {
  const out: string[] = []
  const err: string[] = []
  let code = 0
  if (workspace.problems.length > 0) {
    out.push(`▶ ${WORKSPACE_FILE}`, ...workspace.problems.map(formatProblem))
    if (workspace.problems.some((it) => it.severity === 'error')) code = 1
  }
  const failed: string[] = []
  for (const project of workspace.projects) {
    if (live) live(`\n▶ ${project}`)
    const outcome = run(path.join(workspace.root, project))
    if (!live) out.push('', `▶ ${project}`)
    out.push(...outcome.out)
    err.push(...outcome.err)
    if (outcome.code !== 0) {
      failed.push(project)
      code = code === 1 || outcome.code === 1 ? 1 : outcome.code
    }
  }
  out.push(
    '',
    failed.length === 0
      ? `Every project of ${workspace.name ?? 'the workspace'} passed (${workspace.projects.length}).`
      : `Failed: ${failed.join(', ')}.`,
  )
  return { out, err, code }
}
