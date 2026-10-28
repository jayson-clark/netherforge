/**
 * Everything NetherForge writes into a project's `.netherforge/`, put
 * together: the JSON Schemas every file's `$schema` points at, LuaLS's
 * library, the docs for agents and the `netherforge` command. The editor
 * writes each part as it changes; `netherforge setup` writes them all at once,
 * from the same functions, so a project set up without the editor reads the
 * same as one the editor opened.
 */
import { AGENT_FILES, MANIFEST_FILE } from '@netherforge/format/constants'
import type { ProjectOutline } from '@netherforge/format/types'
import { agentDocFiles } from './agentDocs.ts'
import { tailoredApiStubs, type ApiStubs } from './api.ts'
import { schemaFiles } from './bundled.ts'
import {
  lualsLibraryFiles,
  projectNames,
  type ModelReader,
  type NetworkNames,
  type PackageSource,
} from './luals.ts'

/** Where the JSON Schemas go, which every project file's `$schema` points into. */
export const SCHEMA_FOLDER = '.netherforge/schema'

/**
 * The packages the project depends on directly ([direct], its manifest's `dependencies`), as
 * LuaLS is told about them: each one's outline and its files as read ([folders], by location).
 */
export function packageSources(
  outline: ProjectOutline | null,
  folders: Record<string, { files: Record<string, string | null> } | null | undefined>,
  direct: string[],
): PackageSource[] {
  return direct.flatMap((namespace) => {
    const it = outline?.packages?.[namespace]
    const files = it ? folders[it.location]?.files : undefined
    return it && files ? [{ namespace, outline: it, files }] : []
  })
}

/**
 * Every file under `.netherforge/luals/`, by its path there: [api]'s stubs marked for what the
 * project's target and `netherforge.json` (as [modelAt] reads it) don't allow, the project's names
 * (with its [packages]' and [network]'s), and its packages' module stubs.
 */
export function lualsFiles(
  api: ApiStubs,
  outline: ProjectOutline | null,
  modelAt: ModelReader,
  packages: PackageSource[],
  network: NetworkNames,
): Record<string, string> {
  const tailored = tailoredApiStubs(api, {
    minecraft: outline?.minecraft ?? null,
    manifest: modelAt(MANIFEST_FILE),
  })
  return lualsLibraryFiles(projectNames(outline, modelAt, packages, network), packages, tailored)
}

/** A [ModelReader] over files as format reads them (JSON as text): null where one is missing or doesn't parse. */
export function modelsIn(files: Record<string, string | null | undefined>): ModelReader {
  return <T>(path: string): T | null => {
    const text = files[path]
    if (!text) return null
    try {
      return JSON.parse(text) as T
    } catch {
      return null
    }
  }
}

/** A project as `netherforge setup` reads it from disk. */
export interface ProjectOnDisk {
  /** format's outline of it, its packages loaded. */
  outline: ProjectOutline | null
  /** Its files as format reads them (text, or null for what format doesn't read), by project path. */
  files: Record<string, string | null>
  /** The packages it depends on directly. */
  packages: PackageSource[]
  /** Its workspace's dev network's servers (name → the project each runs), when it's in one with a network. */
  servers?: Record<string, string> | null
}

/**
 * Every file of `.netherforge/` for [project], by project path: the schemas, LuaLS's library,
 * the docs and, with [cliText], the `netherforge` command. [luals] lists the library's paths, so a
 * caller can remove what's no longer in it (a package no longer depended on).
 */
export async function setupFiles(
  version: string,
  api: ApiStubs,
  project: ProjectOnDisk,
  cliText: string | null,
): Promise<{ files: Record<string, string>; luals: string[] }> {
  const files: Record<string, string> = {}
  for (const [name, text] of Object.entries(schemaFiles)) files[`${SCHEMA_FOLDER}/${name}`] = text
  const library = lualsFiles(api, project.outline, modelsIn(project.files), project.packages, {
    scripts: project.files,
    servers: project.servers ?? null,
  })
  const luals = Object.keys(library).map((file) => `${AGENT_FILES.luals}/${file}`)
  for (const [file, text] of Object.entries(library)) files[`${AGENT_FILES.luals}/${file}`] = text
  // Always the whole bundle: whatever README is there may be another version's.
  Object.assign(files, await agentDocFiles(version, null, cliText))
  return { files, luals }
}
