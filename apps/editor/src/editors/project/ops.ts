/**
 * Pure edits to `netherforge.json`'s model. `managedWorlds`, `requires.http`,
 * `requires.plugins` and `allow.permissions` are kept in order, as format
 * writes them (so an edit and its save agree). A list that's empty, a flag
 * turned off, and a `requires` or `allow` that says nothing leave the file
 * altogether, rather than becoming `[]`, `false` or `{}`.
 */
import { SpawnCategoryValues, type ProjectManifest, type SpawnCategory } from '@/core/format'
import type { DependencySource } from '@/core/store/packages'

export function addManagedWorld(manifest: ProjectManifest, name: string) {
  const worlds = manifest.managedWorlds ?? []
  if (worlds.includes(name)) return
  manifest.managedWorlds = [...worlds, name].sort()
}

export function removeManagedWorld(manifest: ProjectManifest, name: string) {
  const worlds = (manifest.managedWorlds ?? []).filter((it) => it !== name)
  if (worlds.length > 0) manifest.managedWorlds = worlds
  else delete manifest.managedWorlds
}

/** A yes-or-no requirement: `console`, `moderation`, `db`, `web`, `bots`, `wasm` or `network`. */
export type RequiredFlag = 'console' | 'moderation' | 'db' | 'web' | 'bots' | 'wasm' | 'network'

/** Declares the flag [key] (`requires.moderation`, `requires.db`), or (`false`) takes it out. */
export function setRequired(manifest: ProjectManifest, key: RequiredFlag, required: boolean) {
  if (required) {
    manifest.requires ??= {}
    manifest.requires[key] = true
  } else if (manifest.requires) {
    delete manifest.requires[key]
    dropEmpty(manifest)
  }
}

/** A list in `requires`: the hosts scripts may call (`http`), the plugins they may use (`plugins`). */
export type RequiredList = 'http' | 'plugins'

export function addRequired(manifest: ProjectManifest, key: RequiredList, name: string) {
  const names = manifest.requires?.[key] ?? []
  if (names.includes(name)) return
  manifest.requires ??= {}
  manifest.requires[key] = [...names, name].sort()
}

export function removeRequired(manifest: ProjectManifest, key: RequiredList, name: string) {
  if (!manifest.requires) return
  const names = (manifest.requires[key] ?? []).filter((it) => it !== name)
  if (names.length > 0) manifest.requires[key] = names
  else delete manifest.requires[key]
  dropEmpty(manifest)
}

export function addPermission(manifest: ProjectManifest, node: string) {
  const permissions = manifest.allow?.permissions ?? []
  if (permissions.includes(node)) return
  manifest.allow ??= {}
  manifest.allow.permissions = [...permissions, node].sort()
}

export function removePermission(manifest: ProjectManifest, node: string) {
  if (!manifest.allow) return
  const permissions = (manifest.allow.permissions ?? []).filter((it) => it !== node)
  if (permissions.length > 0) manifest.allow.permissions = permissions
  else delete manifest.allow.permissions
  dropEmpty(manifest)
}

/** The two per-world spawn maps in `worlds`: the mob cap and the ticks between spawn attempts. */
export type SpawnField = 'spawnLimits' | 'spawnIntervals'

/**
 * Sets a world's [field] for [category] to a whole number of 0 or more, or (`undefined`) back to
 * the server's own. Categories and worlds are kept in the order format writes them, and a map, a
 * world or `worlds` that says nothing leaves the file (format drops them, so keeping them would
 * leave the document dirty against what it saves).
 */
export function setWorldSpawn(
  manifest: ProjectManifest,
  world: string,
  field: SpawnField,
  category: SpawnCategory,
  value: number | undefined,
) {
  const config = manifest.worlds?.[world]
  if (value === undefined) {
    if (!config?.[field]) return
    delete config[field][category]
    dropEmptyWorld(manifest, world)
    return
  }
  manifest.worlds ??= {}
  const target = (manifest.worlds[world] ??= {})
  const values = { ...target[field], [category]: Math.max(0, Math.round(value)) }
  target[field] = Object.fromEntries(
    SpawnCategoryValues.filter((it) => it in values).map((it) => [it, values[it]!]),
  )
  manifest.worlds = Object.fromEntries(
    Object.entries(manifest.worlds).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  )
}

/** Takes every spawn setting of [world] out. */
export function removeWorldSpawn(manifest: ProjectManifest, world: string) {
  if (!manifest.worlds) return
  delete manifest.worlds[world]
  dropEmptyWorld(manifest, world)
}

function dropEmptyWorld(manifest: ProjectManifest, world: string) {
  const config = manifest.worlds?.[world]
  if (config) {
    for (const field of ['spawnLimits', 'spawnIntervals', 'gameRules'] as const) {
      if (config[field] && Object.keys(config[field]).length === 0) delete config[field]
    }
    if (Object.keys(config).length === 0) delete manifest.worlds![world]
  }
  if (manifest.worlds && Object.keys(manifest.worlds).length === 0) delete manifest.worlds
}

function dropEmpty(manifest: ProjectManifest) {
  if (manifest.requires && Object.keys(manifest.requires).length === 0) delete manifest.requires
  if (manifest.allow && Object.keys(manifest.allow).length === 0) delete manifest.allow
}

/** A world's own setting other than spawning: its terrain, seed or dimension type; undefined takes it out. */
export function setWorldValue(
  manifest: ProjectManifest,
  world: string,
  key: 'terrain' | 'seed' | 'dimensionType',
  value: string | number | undefined,
) {
  if (value === undefined || value === '') {
    const config = manifest.worlds?.[world]
    if (!config) return
    delete config[key]
    dropEmptyWorld(manifest, world)
    return
  }
  manifest.worlds ??= {}
  const target = (manifest.worlds[world] ??= {})
  ;(target as Record<string, unknown>)[key] = value
  sortWorlds(manifest)
}

/** A game rule's value as typed: `true`, `false` or a whole number; anything else isn't one (null). */
export function parseGameRule(text: string): boolean | number | null {
  const trimmed = text.trim()
  if (trimmed === 'true') return true
  if (trimmed === 'false') return false
  return /^-?\d+$/.test(trimmed) ? Number(trimmed) : null
}

/** Sets game rule [rule] of [world] (`keep_inventory`), or with undefined takes it out. */
export function setGameRule(
  manifest: ProjectManifest,
  world: string,
  rule: string,
  value: boolean | number | undefined,
) {
  if (value === undefined) {
    const rules = manifest.worlds?.[world]?.gameRules
    if (!rules) return
    delete rules[rule]
    dropEmptyWorld(manifest, world)
    return
  }
  manifest.worlds ??= {}
  const target = (manifest.worlds[world] ??= {})
  // The generated type says string; the file holds the rule's own JSON value.
  const rules = { ...(target.gameRules ?? {}), [rule]: value } as Record<string, unknown>
  target.gameRules = Object.fromEntries(
    Object.keys(rules)
      .sort()
      .map((it) => [it, rules[it]]),
  ) as Record<string, string>
  sortWorlds(manifest)
}

function sortWorlds(manifest: ProjectManifest) {
  if (!manifest.worlds) return
  manifest.worlds = Object.fromEntries(
    Object.entries(manifest.worlds).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  )
}

export type { DependencySource }

/** Adds or replaces the dependency [namespace]. */
export function setDependency(
  manifest: ProjectManifest,
  namespace: string,
  source: DependencySource,
) {
  const next = { ...(manifest.dependencies ?? {}), [namespace]: source }
  manifest.dependencies = Object.fromEntries(
    Object.entries(next).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  )
}

export function removeDependency(manifest: ProjectManifest, namespace: string) {
  if (!manifest.dependencies) return
  delete manifest.dependencies[namespace]
  if (Object.keys(manifest.dependencies).length === 0) delete manifest.dependencies
}

/** Exports resource [id] of the kind kept in [folder] (`items`), or stops exporting it. */
export function setExported(manifest: ProjectManifest, folder: string, id: string, on: boolean) {
  const now = new Set(manifest.exports?.[folder] ?? [])
  if (on) now.add(id)
  else now.delete(id)
  const exports = { ...(manifest.exports ?? {}) }
  if (now.size > 0) exports[folder] = [...now].sort()
  else delete exports[folder]
  if (Object.keys(exports).length > 0) manifest.exports = exports
  else delete manifest.exports
}

/** `generate`'s commands from text, one a line; none takes it out. */
export function setGenerate(manifest: ProjectManifest, text: string) {
  const commands = text
    .split('\n')
    .map((it) => it.trim())
    .filter(Boolean)
  if (commands.length > 0) manifest.generate = commands
  else delete manifest.generate
}
