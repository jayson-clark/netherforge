/**
 * The files of one resource as a tree, and the rules for naming, moving and
 * copying them: pure, so the outline's Files pane stays a view.
 */
import { LUA_FILE_PATTERN, MODULES } from '@/core/paths'

export interface FileNode {
  /** The project path (a file's, or a folder's without a trailing slash). */
  id: string
  label: string
  /** Undefined for a file. */
  children?: FileNode[]
}

/**
 * The files under [root] (a resource's folder) as nested nodes, folders
 * first, then by name, the way VS Code sorts. [extraFolders] are folders the
 * user just made, which have no files yet (the disk has no empty folders to
 * tell us about).
 */
export function buildFileTree(
  files: string[],
  root: string,
  extraFolders: string[] = [],
): FileNode[] {
  const top: FileNode = { id: root, label: root, children: [] }
  const folders = new Map<string, FileNode>([[root, top]])
  const folderFor = (path: string): FileNode => {
    const existing = folders.get(path)
    if (existing) return existing
    const slash = path.lastIndexOf('/')
    const node: FileNode = { id: path, label: path.slice(slash + 1), children: [] }
    folders.set(path, node)
    folderFor(path.slice(0, slash)).children!.push(node)
    return node
  }
  const prefix = `${root}/`
  for (const folder of extraFolders) if (folder.startsWith(prefix)) folderFor(folder)
  for (const path of files) {
    if (!path.startsWith(prefix)) continue
    const slash = path.lastIndexOf('/')
    folderFor(path.slice(0, slash)).children!.push({ id: path, label: path.slice(slash + 1) })
  }
  const sort = (nodes: FileNode[]) => {
    nodes.sort((a, b) =>
      !!a.children !== !!b.children ? (a.children ? -1 : 1) : a.label.localeCompare(b.label),
    )
    for (const node of nodes) if (node.children) sort(node.children)
    return nodes
  }
  return sort(top.children!)
}

/** One path segment: letters, digits, _, - and . (not leading), as resource packs and Lua files allow. */
const SEGMENT = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/

/**
 * Why [name] can't be a new file or folder (or a renamed one) inside
 * [parent] of a resource at [root], or null. Lua files are held to format's
 * rule for script paths (it's checked on load anyway, and require names
 * can't have dots or dashes); everything else to a plain file-name rule.
 */
export function nameProblem(
  name: string,
  parent: string,
  root: string,
  files: string[],
  kind: 'file' | 'folder',
  current?: string,
): string | null {
  if (!name) return 'Give it a name'
  const segments = name.split('/')
  if (segments.some((it) => !SEGMENT.test(it))) {
    return 'Letters, digits, _, - and . (folders with /)'
  }
  const path = `${parent}/${name}`
  if (path !== current && (files.includes(path) || files.some((it) => it.startsWith(`${path}/`)))) {
    return 'Something with that name is already there'
  }
  if (
    kind === 'file' &&
    name.endsWith('.lua') &&
    !LUA_FILE_PATTERN.test(path.slice(root.length + 1))
  ) {
    return 'A Lua file is named with letters, digits and _ (a require name can’t have . or -)'
  }
  return null
}

/** `steps.lua` → `steps_copy.lua`, then `steps_copy2.lua`, … beside it. */
export function copyPathFor(path: string, files: string[]): string {
  const slash = path.lastIndexOf('/')
  const name = path.slice(slash + 1)
  const dot = name.lastIndexOf('.')
  const stem = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : ''
  const taken = (candidate: string) =>
    files.some((it) => it === candidate || it.startsWith(`${candidate}/`))
  for (let n = 1; ; n += 1) {
    const candidate = `${path.slice(0, slash + 1)}${stem}_copy${n > 1 ? n : ''}${ext}`
    if (!taken(candidate)) return candidate
  }
}

/** Where [path] lands when dropped into [folder]; null if that's where it already is, or inside itself. */
export function moveTarget(path: string, folder: string): string | null {
  if (folder === path || folder.startsWith(`${path}/`)) return null
  const target = `${folder}/${path.slice(path.lastIndexOf('/') + 1)}`
  return target === path ? null : target
}

/**
 * What a script writes to load [path]: a module's files by the module's id
 * (`require("greeter.lib.util")`), a resource's by their place beside its
 * script (`require("lib.steps")`). Null for anything that isn't Lua.
 */
export function requireNameOf(path: string): string | null {
  const [folder, id, ...rest] = path.split('/')
  if (!path.endsWith('.lua') || rest.length === 0) return null
  const inside = rest
    .join('/')
    .replace(/\.lua$/, '')
    .replace(/\//g, '.')
  if (folder === MODULES) return inside === 'init' ? id! : `${id}.${inside}`
  return inside
}
