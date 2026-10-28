/**
 * Project path helpers: which resource a path belongs to, and JSON paths
 * (`$.nodes.top.display`) as `format` writes them in problems. Where each
 * kind lives is format's kind table (`KINDS`) and which resource a path is
 * format's `classify`: nothing here lists kinds.
 */

import {
  classify,
  documentKindOf,
  KINDS,
  LOCK_FILE,
  MODULE_ENTRY,
  RESOURCE_PACK_SOUNDS,
  RESOURCE_PACK_TEXTURES,
  type BinaryKindId,
  type KindId,
  type PathRole,
} from './format'

export { MODULE_ENTRY }
/** Modules are folders of Lua: `modules/<id>/`, run from `init.lua`. */
export const MODULES = KINDS.module.folder
export const RESOURCE_PACKS = KINDS.resource_pack.folder
/** A pack's pictures live under `resource_packs/<ns>/textures/`. */
export const TEXTURES = RESOURCE_PACK_TEXTURES
/** A pack's sounds live under `resource_packs/<ns>/sounds/`, each `.ogg` a sound event. */
export const SOUNDS = RESOURCE_PACK_SOUNDS

/**
 * The UI's pre-checks for a new name, so a dialog can say no before anything
 * is written. They're format's own patterns; whatever gets past them is still
 * validated on load.
 */
export { ID_PATTERN, LUA_FILE_PATTERN, NODE_NAME_PATTERN } from './format'

/** Whether a kind's files are Minecraft's own (structures, maps), never read as text. */
export const isBinaryKind = (kind: KindId): kind is BinaryKindId =>
  KINDS[kind].contents === 'binary'

/** Whether a kind's resources are single files (`recipes/<id>.json`) rather than folders. */
export const isFileKind = (kind: KindId) => KINDS[kind].layout === 'file'

const BINARY_FOLDERS = (Object.keys(KINDS) as KindId[])
  .filter(isBinaryKind)
  .map((kind) => `${KINDS[kind].folder}/`)

/**
 * Whether a path is JSON the editor reads and hands format: every `.json`
 * file except those in a binary kind's folder (a map's player
 * stats and advancements), which are Minecraft's own, only the server reads;
 * every document format owns whatever it's called (a structure's generation
 * file, a datapack's `pack.mcmeta`); and `netherforge.lock`, which is JSON too.
 */
export const isProjectJson = (path: string) =>
  path === LOCK_FILE ||
  documentKindOf(path) !== null ||
  (path.endsWith('.json') && !BINARY_FOLDERS.some((folder) => path.startsWith(folder)))

/**
 * Whether format reads a file's text: the project's JSON ([isProjectJson]) and
 * its Lua, which format reads for the modules scripts `require`.
 */
export const readsText = (path: string) => isProjectJson(path) || path.endsWith('.lua')

/**
 * Group folders sort a kind's resources without changing their ids: `items/(weapons)/ruby_wand/` is the item
 * `ruby_wand`. A resource's `group` is the group folders it's in as on disk (`(weapons)/(swords)`), empty for one
 * in its kind's folder itself; every helper below takes it, and [groupOf] finds it in a file list.
 */
export const groupFolder = (name: string) => `(${name})`

/** The hidden file a new, empty group folder is made with, so it's there (and git keeps it) before anything's in it. */
export const GROUP_KEEP = '.gitkeep'

/** A group folder's name without its parentheses: `(weapons)` → `weapons`. */
export const groupName = (folder: string) => folder.replace(/^\(/, '').replace(/\)$/, '')

/** The folder of [kind]'s resources in [group] (`items/(weapons)`), or the kind's own folder. */
export const folderOf = (kind: KindId, group = '') =>
  group ? `${KINDS[kind].folder}/${group}` : KINDS[kind].folder

/** The document beside a single-file resource's file (a structure's generation `.json`), or null for a kind without one. */
export function companionOf(kind: KindId, id: string, group = ''): string | null {
  const where = KINDS[kind]
  return where.companion === null ? null : `${folderOf(kind, group)}/${id}${where.companion}`
}

/**
 * What a resource is on disk, for rename and delete: its folder
 * (`menus/shop`), or a file kind's one file (`recipes/ruby.json`), in its group folders.
 */
export function locationOf(kind: KindId, id: string, group = ''): string {
  const where = KINDS[kind]
  return where.layout === 'file'
    ? `${folderOf(kind, group)}/${id}${where.extension}`
    : `${folderOf(kind, group)}/${id}`
}

/** The file a resource is read from: its main file, or (a module, which has none) its folder. */
export function mainFileOf(kind: KindId, id: string, group = ''): string {
  const main = KINDS[kind].main
  const location = locationOf(kind, id, group)
  return main === null ? location : `${location}/${main}`
}

/** What opening a resource opens: its main file, a module's entry, or a map's folder. */
export function opensAs(kind: KindId, id: string, group = ''): string {
  if (KINDS[kind].contents === 'lua') return `${locationOf(kind, id, group)}/${MODULE_ENTRY}`
  return isBinaryKind(kind) ? locationOf(kind, id, group) : mainFileOf(kind, id, group)
}

/** A file in a project resource, as format's `classify` places it. */
export interface ResourcePath {
  kind: KindId
  id: string
  /** The resource's main file, another file in its folder, or the folder itself. */
  role: Exclude<PathRole, 'project'>
  /** The path inside its folder (`lib/util.lua`); empty for the folder itself and a file kind's one file. */
  rest: string
  /** The group folders it's in (`(weapons)`), empty when none. */
  group: string
  /** The package it's in, for a package path (`library:items/gem/item.json` → `library`); null for the project's. */
  pkg: string | null
}

/**
 * [path] in package [pkg] (`library:items/gem`), or as it is for the project's own (null). What a path built from
 * a [ResourcePath] is written with, so a package's resource names its own files and not the project's.
 */
export const inPackage = (pkg: string | null, path: string): string =>
  pkg === null ? path : `${pkg}:${path}`

/**
 * The resource a path belongs to: `centities/tower/script.lua` → centity
 * `tower`, rest `script.lua`; `menus/shop` → menu `shop`, its folder;
 * `recipes/torch.json` → recipe `torch`, its main file. Null for anything
 * else (the manifest, a README, a stray file in a kind's folder).
 */
export function resourceOf(path: string): ResourcePath | null {
  const found = classify(path)
  if (!found?.kind || !found.id || found.role === 'project' || found.role === 'group') return null
  return {
    kind: found.kind,
    id: found.id,
    role: found.role,
    rest: found.rest ?? '',
    group: found.group ?? '',
    pkg: found.package ?? null,
  }
}

/** A group folder a path is (`items/(weapons)`), or null when it isn't one. */
export function groupAt(path: string): { kind: KindId; group: string } | null {
  const found = classify(path)
  return found?.role === 'group' && found.kind && found.group && !found.package
    ? { kind: found.kind, group: found.group }
    : null
}

/** What a file list holds, by kind: the ids, and the group of each in a group folder. */
interface ListIndex {
  ids: Map<KindId, string[]>
  /** Each resource's group folders, empty for one in its kind's folder. */
  groups: Map<KindId, Map<string, string>>
  /** Every group folder that has a file in it, by kind (`(weapons)`, `(weapons)/(swords)`), sorted. */
  folders: Map<KindId, string[]>
}

/** Each file list's index, worked out in one pass: the store's list is replaced, never changed. */
const indexByList = new WeakMap<string[], ListIndex>()

function indexOf(paths: string[]): ListIndex {
  let index = indexByList.get(paths)
  if (!index) {
    const sets = new Map<KindId, Set<string>>()
    const groups = new Map<KindId, Map<string, string>>()
    const folders = new Map<KindId, Set<string>>()
    const addFolders = (kind: KindId, group: string) => {
      let inKind = folders.get(kind)
      if (!inKind) folders.set(kind, (inKind = new Set()))
      const parts = group.split('/')
      for (let i = 1; i <= parts.length; i++) inKind.add(parts.slice(0, i).join('/'))
    }
    for (const path of paths) {
      const resource = resourceOf(path)
      // A group made empty keeps a hidden file (`items/(weapons)/.gitkeep`), so the folder is there, and in git.
      if (!resource) {
        const group = path.slice(path.lastIndexOf('/') + 1).startsWith('.')
          ? groupAt(dirname(path))
          : null
        if (group) addFolders(group.kind, group.group)
        continue
      }
      // A list holds files: one shaped like a folder is a stray in the kind's folder.
      if (resource.role === 'folder') continue
      let ids = sets.get(resource.kind)
      if (!ids) sets.set(resource.kind, (ids = new Set()))
      ids.add(resource.id)
      // Format reads the place whose group sorts first, none at all before any (another is a duplicate it ignores).
      let byId = groups.get(resource.kind)
      if (!byId) groups.set(resource.kind, (byId = new Map()))
      const kept = byId.get(resource.id)
      if (kept === undefined || resource.group < kept) byId.set(resource.id, resource.group)
      if (resource.group) addFolders(resource.kind, resource.group)
    }
    index = {
      ids: new Map([...sets].map(([it, ids]) => [it, [...ids].sort()])),
      groups,
      folders: new Map([...folders].map(([it, all]) => [it, [...all].sort()])),
    }
    indexByList.set(paths, index)
  }
  return index
}

/** Every resource id of [kind] in a file list, sorted. Treat the answer as read-only: it's shared. */
export const resourceIdsOf = (paths: string[], kind: KindId): string[] =>
  indexOf(paths).ids.get(kind) ?? NO_IDS

/** The group folders resource [id] of [kind] is in, in a file list; empty when it's in its kind's folder (or not there). */
export const groupOf = (paths: string[], kind: KindId, id: string): string =>
  indexOf(paths).groups.get(kind)?.get(id) ?? ''

/**
 * Every group folder of [kind] a file list has files in (`(weapons)`, `(weapons)/(swords)`), sorted: a resource,
 * or the hidden file that keeps an empty one ([GROUP_KEEP]).
 */
export const groupsOf = (paths: string[], kind: KindId): string[] =>
  indexOf(paths).folders.get(kind) ?? NO_IDS

/** The file a resource is read from, found where it is in a file list: [mainFileOf] in its group. */
export const mainFileIn = (paths: string[], kind: KindId, id: string) =>
  mainFileOf(kind, id, groupOf(paths, kind, id))

/** What a resource is on disk, found where it is in a file list: [locationOf] in its group. */
export const locationIn = (paths: string[], kind: KindId, id: string) =>
  locationOf(kind, id, groupOf(paths, kind, id))

const NO_IDS: string[] = []

/**
 * A file in a pack by its path there (`textures/gem.png`): `resource_packs/<id>/<file>`; for a package's pack
 * (`library:gems`, as the packs store keys it) its package path, `library:resource_packs/gems/<file>`.
 */
export function resourcePackFilePath(pack: string, file: string): string {
  const colon = pack.indexOf(':')
  return inPackage(
    colon < 0 ? null : pack.slice(0, colon),
    `${RESOURCE_PACKS}/${pack.slice(colon + 1)}/${file}`,
  )
}

/** A pack sound file's path: `resource_packs/<id>/sounds/<file>` (a package's pack's, its package path). */
export const resourcePackSoundPath = (pack: string, file: string) =>
  resourcePackFilePath(pack, `${SOUNDS}/${file}`)
/**
 * A pack texture's project path: `resource_packs/<id>/textures/<texture>`; for a
 * package's pack (`library:gems`, as the packs store keys it) its package path,
 * `library:resource_packs/gems/textures/<texture>` (format's `CompiledResourcePack.texturePath`).
 */
export const resourcePackTexturePath = (pack: string, texture: string): string =>
  resourcePackFilePath(pack, `${TEXTURES}/${texture}`)

export const dirname = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')))
export const basename = (path: string) => path.slice(path.lastIndexOf('/') + 1)

export type JsonPathSegment = string | number

/**
 * Parses a JSON path as `format` writes it: `$.nodes.top`, `$.nodes["a b"]`,
 * `$.boxes[0]`. Unparseable tails are dropped rather than thrown on.
 */
export function parseJsonPath(path: string): JsonPathSegment[] {
  const segments: JsonPathSegment[] = []
  let i = path.startsWith('$') ? 1 : 0
  while (i < path.length) {
    const c = path[i]
    if (c === '.') {
      let j = i + 1
      while (j < path.length && path[j] !== '.' && path[j] !== '[') j += 1
      segments.push(path.slice(i + 1, j))
      i = j
    } else if (c === '[') {
      const close = path.indexOf(']', i)
      if (close < 0) break
      const inner = path.slice(i + 1, close)
      if (inner.startsWith('"')) {
        try {
          segments.push(JSON.parse(inner) as string)
        } catch {
          break
        }
      } else {
        const index = Number(inner)
        if (!Number.isInteger(index)) break
        segments.push(index)
      }
      i = close + 1
    } else {
      break
    }
  }
  return segments
}

/** The folders Lua files live in: the kinds whose resources run Lua. */
const SCRIPT_FOLDERS = Object.values(KINDS)
  .filter((kind) => kind.scripted)
  .map((kind) => kind.folder)

/** Finds `file:line` references to project Lua files in a line of console text. */
export function findSourceRefs(
  text: string,
): { start: number; end: number; file: string; line: number }[] {
  const refs: { start: number; end: number; file: string; line: number }[] = []
  const pattern = new RegExp(
    `((?:${SCRIPT_FOLDERS.join('|')})/[A-Za-z0-9_./()-]+\\.lua):(\\d+)`,
    'g',
  )
  for (const match of text.matchAll(pattern)) {
    refs.push({
      start: match.index,
      end: match.index + match[0].length,
      file: match[1]!,
      line: Number(match[2]),
    })
  }
  return refs
}

/**
 * [to] relative to [from], both absolute folders, with `/` between segments
 * as a `path` dependency names it (`/a/game` to `/a/library` is
 * `../library`); null when there's no way between them (another drive).
 */
export function relativeFolder(from: string, to: string): string | null {
  // A Windows drive (`C:`, any case) is where both must start alike.
  const parts = (path: string) =>
    path
      .replace(/\\/g, '/')
      .split('/')
      .filter(Boolean)
      .map((it, i) => (i === 0 && /^[A-Za-z]:$/.test(it) ? it.toLowerCase() : it))
  const a = parts(from)
  const b = parts(to)
  const drive = (it: string[]) => (it[0]?.endsWith(':') ? it[0] : '')
  if (drive(a) !== drive(b)) return null
  let same = 0
  while (same < a.length && same < b.length && a[same] === b[same]) same++
  const up = a.slice(same).map(() => '..')
  return [...up, ...b.slice(same)].join('/') || '.'
}
