/**
 * What lua-language-server should know about this project beyond the API:
 * the names scripts pass as strings (centity, menu and dialog ids, each
 * centity's nodes and animations, each dialog's buttons, pack glyphs and
 * skins...), as LuaLS stubs in `.netherforge/luals/`, the `workspace.library`
 * the project's `.luarc.json` lists next to the API's own stubs.
 *
 * `nf.lua` types each such parameter as an alias that's a plain `string`
 * (`---@param name NodeName`, see `NAME_ALIASES` in `@netherforge/api`);
 * declaring the same alias again here with the project's names makes LuaLS
 * merge them, so `this:node("` completes the project's nodes while any string
 * still type-checks. The names are project-wide: LuaLS can't tell one
 * centity's script from another's.
 *
 * Network names come from scripts and the workspace: the channels and shared maps the project's
 * scripts and its packages' get by a literal name ([NetworkNames]), and, in a workspace with a
 * dev network, its servers' names (what `send_player` takes).
 *
 * The packages the project depends on add what they export: their names as
 * more of the same aliases (`library:gem`), and a stub per exported module
 * file that `require("library:greetings")` finds ([packageModuleStubs]).
 *
 * The API's own stubs go there too (`api.ts`), marked for what the project
 * can't use. Event payloads need nothing here (`nf.lua` types every handler), and
 * `require` across a resource's files is the LuaLS plugin's job
 * (the editor's `src-tauri/src/luals/plugin.lua`).
 *
 * Pure: the editor keeps the files up to date as the project changes
 * (`core/luals/follow.ts`), and `netherforge setup` writes them once.
 */
import type { NameKind } from '@netherforge/api'
// Not the package's index: that's the whole spec, which the app's first chunk doesn't need.
import { NAME_ALIASES } from '@netherforge/api/names'
import { AGENT_FILES, KINDS, MANIFEST_FILE, type KindId } from '@netherforge/format/constants'
import type { PackageOutline, ProjectOutline } from '@netherforge/format/types'
import { mainFileOf, networkUses, resourceOf } from './paths.ts'

/** Each name a project has, per kind, with where it comes from (`"tower"` for a node of the tower). */
export type ProjectNames = Record<NameKind, Map<string, string[]>>

/** Reads a resource's main file as a model, or null when it's missing or doesn't parse. */
export type ModelReader = <T>(path: string) => T | null

/**
 * A package the project depends on directly, as LuaLS is told about it: its
 * namespace, its outline, and its files as they were read (JSON and Lua as
 * text, the rest null), by path inside it.
 */
export interface PackageSource {
  namespace: string
  outline: PackageOutline
  files: Record<string, string | null>
}

/** What network names come from: the project's scripts (Lua source by path) and its workspace's dev network. */
export interface NetworkNames {
  scripts?: Record<string, string | null>
  /** The workspace network's servers: each one's name → the project it runs (its path in the workspace). */
  servers?: Record<string, string> | null
}

/**
 * The project's names, from its outline and its resources' files as
 * [modelAt] reads them, and what each package it depends on exports, as
 * `ns:name` (`library:gem`, `library:gems/gem`): what its scripts may name.
 */
export function projectNames(
  outline: ProjectOutline | null,
  modelAt: ModelReader,
  packages: PackageSource[] = [],
  network: NetworkNames = {},
): ProjectNames {
  const names = Object.fromEntries(
    Object.keys(NAME_ALIASES).map((kind) => [kind, new Map<string, string[]>()]),
  ) as ProjectNames
  const add = (kind: NameKind, name: unknown, where?: string) => {
    if (typeof name !== 'string' || name === '') return
    const owners = names[kind].get(name) ?? []
    if (where && !owners.includes(where)) owners.push(where)
    names[kind].set(name, owners)
  }
  if (!outline) return names
  const ids = (kind: KindId) => outline.resources[kind] ?? []
  // A name kind that's a resource kind (`centity`, `menu`...) names its resources.
  for (const kind of Object.keys(names) as NameKind[])
    if (kind in KINDS) for (const id of ids(kind as KindId)) add(kind, id)
  // Scripts name builds as they name structures (`world:place_gradually("castle", …)`).
  for (const id of ids('build')) add('structure', id)
  // Parts of resources: a centity's nodes and animations, a dialog's buttons.
  for (const id of ids('centity')) {
    const model = modelAt<{ nodes?: object; animations?: object }>(
      mainFileOf('centity', id, outline.groups?.centity?.[id]),
    )
    for (const node of Object.keys(model?.nodes ?? {})) add('node', node, id)
    for (const animation of Object.keys(model?.animations ?? {})) add('animation', animation, id)
  }
  for (const id of ids('dialog')) {
    const buttons = modelAt<{ buttons?: unknown }>(
      mainFileOf('dialog', id, outline.groups?.dialog?.[id]),
    )?.buttons
    for (const button of Array.isArray(buttons) ? buttons : [])
      add('button', (button as { key?: unknown } | null)?.key, id)
  }
  for (const [pack, it] of Object.entries(outline.resourcePacks)) {
    for (const key of it.glyphs) add('glyph', `${pack}/${key}`)
    for (const key of it.skins) add('skin', `${pack}/${key}`)
  }
  // The project's own server-owner settings, which `nf.config` reads by name (a package's are its own).
  for (const name of Object.keys(modelAt<{ settings?: object }>(MANIFEST_FILE)?.settings ?? {}))
    add('setting', name)
  for (const pkg of packages) addPackage(add, pkg)
  for (const [name, project] of Object.entries(network.servers ?? {})) add('server', name, project)
  addNetworkUses(add, network.scripts ?? {}, null)
  for (const pkg of packages) addNetworkUses(add, pkg.files, pkg.namespace)
  return names
}

/**
 * The channels and maps [scripts] get by a literal name: as they're named there, or for a
 * package's ([namespace]) as `ns:name`, which is how a project depending on it names them.
 */
function addNetworkUses(
  add: (kind: NameKind, name: unknown, where?: string) => void,
  scripts: Record<string, string | null>,
  namespace: string | null,
) {
  for (const [path, text] of Object.entries(scripts)) {
    if (text == null || !path.endsWith('.lua') || !text.includes('network')) continue
    for (const use of networkUses(text)) {
      if (use.kind === 'server') continue
      const name = namespace && !use.name.includes(':') ? `${namespace}:${use.name}` : use.name
      add(use.kind, name)
    }
  }
}

const HEADER = [
  '---@meta',
  "-- This project's names, for lua-language-server. Written by NetherForge (the editor whenever",
  "-- they change, or `netherforge setup`): don't edit it.",
  '',
]

/** `.netherforge/luals/project.lua`: an alias per kind of name, with the project's names. */
export function projectStub(names: ProjectNames): string {
  const lines = [...HEADER]
  for (const [kind, alias] of Object.entries(NAME_ALIASES) as [NameKind, string][]) {
    const found = [...names[kind]].sort(([a], [b]) => a.localeCompare(b))
    if (found.length === 0) continue
    lines.push(`---@alias ${alias}`)
    for (const [name, owners] of found) {
      const where = owners.length ? ` # ${kind.replace('_', ' ')} of ${owners.join(', ')}` : ''
      lines.push(`---| ${JSON.stringify(name)}${where}`)
    }
    lines.push('')
  }
  return lines.join('\n')
}

/**
 * Every file under `.netherforge/luals/`, by its path there: the API's
 * stubs as this project reads them ([api], `tailoredApiStubs` in `api.ts`),
 * the project's names (with what its packages export), and a stub for each
 * file of each module a package it depends on exports ([packageModuleStubs]).
 */
export function lualsLibraryFiles(
  names: ProjectNames,
  packages: PackageSource[] = [],
  api: Record<string, string> = {},
): Record<string, string> {
  return { ...api, 'project.lua': projectStub(names), ...packageModuleStubs(packages) }
}

/** Where [packageModuleStubs] are in the project: what the LuaLS plugin resolves `require("ns:module")` to. */
export const PACKAGE_STUBS = `${AGENT_FILES.luals}/packages`

/**
 * Each file of each module a package exports, as a stub `require` finds by
 * the name a script requires it by: `---@meta library:greetings` and then the
 * file itself, so LuaLS knows what `require("library:greetings")` returns
 * (and `library:greetings.messages` for a file inside it). By path under
 * `.netherforge/luals/`: `packages/library/greetings/init.lua`. A module the
 * package keeps to itself gets none: requiring it is an error.
 */
export function packageModuleStubs(packages: PackageSource[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const { namespace, outline, files } of packages) {
    const exported = new Set(outline.exports[KINDS.module.folder] ?? [])
    for (const [path, text] of Object.entries(files)) {
      // A module's file in whichever group folder the package keeps it: the stub goes by the name it's required by.
      const found = text != null && path.endsWith('.lua') ? resourceOf(path) : null
      if (found?.kind !== 'module' || found.role !== 'file' || !exported.has(found.id)) continue
      const id = found.id
      const inside = found.rest.slice(0, -'.lua'.length).replace(/(^|\/)init$/, '')
      const name = `${namespace}:${[id, ...(inside ? inside.split('/') : [])].join('.')}`
      out[`packages/${namespace}/${id}/${found.rest}`] = [
        `---@meta ${name}`,
        `-- ${namespace}'s ${path}, for lua-language-server: what require("${name}") gives.`,
        "-- Written by NetherForge from the package as it is: don't edit it.",
        text,
      ].join('\n')
    }
  }
  return out
}

/** [text] as JSON, or null when there's none or it doesn't parse. */
function parsedJson<T>(text: string | null | undefined): T | null {
  if (!text) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}

/** What package [pkg] exports, as more of the names its users' scripts may pass (`library:gem`). */
function addPackage(
  add: (kind: NameKind, name: unknown, where?: string) => void,
  pkg: PackageSource,
) {
  const { namespace, outline, files } = pkg
  for (const kind of Object.keys(NAME_ALIASES) as NameKind[]) {
    if (!(kind in KINDS)) continue
    const folder = KINDS[kind as KindId].folder
    const has = new Set(outline.resources[kind] ?? [])
    for (const id of outline.exports[folder] ?? []) if (has.has(id)) add(kind, `${namespace}:${id}`)
  }
  // An exported pack's glyphs and skins, from its pack.json as it was read.
  for (const pack of outline.exports[KINDS.resource_pack.folder] ?? []) {
    const text = files[`${KINDS.resource_pack.folder}/${pack}/${KINDS.resource_pack.main}`]
    const model = parsedJson<{ glyphs?: object; skins?: object }>(text)
    for (const key of Object.keys(model?.glyphs ?? {})) add('glyph', `${namespace}:${pack}/${key}`)
    for (const key of Object.keys(model?.skins ?? {})) add('skin', `${namespace}:${pack}/${key}`)
  }
}
