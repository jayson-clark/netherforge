/**
 * `netherforge setup`: writes the project's `.netherforge/` as the NetherForge
 * editor does when it opens the project, from the same code
 * (`@netherforge/project-support`): the JSON Schemas every file's `$schema`
 * points at, lua-language-server's library (the API's stubs marked for what the
 * project can't use, the project's names, its packages' modules), the docs for
 * agents and this command itself. So VS Code, Neovim or a coding agent on a
 * fresh clone gets what the editor would have given it.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { AGENT_FILES, LUARC, MANIFEST_FILE } from '@netherforge/format/constants'
import {
  loadApiStubs,
  packageSources,
  SCHEMA_FOLDER,
  schemaFiles,
  setupFiles,
} from '@netherforge/project-support'
import { load, type Outcome } from './commands.ts'
import { packageCache, readPackages, readProject } from './project.ts'

/** What `netherforge setup` takes besides the project. */
export interface SetupOptions {
  /** This version of NetherForge, which the docs are headed with. */
  version: string
  /** The `netherforge` command's own text, written into `.netherforge/bin/`; null to leave it out. */
  cli: string | null
  /** The project's workspace's dev network: each server's name → the project it runs. */
  servers?: Record<string, string> | null
}

/** Writes [text] at [file] unless it's there already; whether it wrote. */
function writeIfChanged(file: string, text: string): boolean {
  try {
    if (readFileSync(file, 'utf8') === text) return false
  } catch {
    // Not there yet.
  }
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, text)
  return true
}

/** Every file under [dir], as `/`-separated paths relative to [root]. */
function filesUnder(root: string, dir: string): string[] {
  let entries
  try {
    entries = readdirSync(path.join(root, dir), { withFileTypes: true })
  } catch {
    return []
  }
  return entries.flatMap((entry) =>
    entry.isDirectory()
      ? filesUnder(root, `${dir}/${entry.name}`)
      : entry.isFile()
        ? [`${dir}/${entry.name}`]
        : [],
  )
}

/** The project's direct dependencies: its manifest's `dependencies`, empty when it doesn't parse. */
function dependenciesOf(manifest: string | null | undefined): string[] {
  try {
    const parsed = JSON.parse(manifest ?? '') as { dependencies?: unknown }
    const dependencies = parsed?.dependencies
    return typeof dependencies === 'object' && dependencies !== null
      ? Object.keys(dependencies)
      : []
  } catch {
    return []
  }
}

/**
 * Writes the project at [root]'s `.netherforge/`, and its `.luarc.json` when it has none (a
 * project the editor made has one; it's the user's, committed, so one that's there is kept).
 */
export async function setup(
  root: string,
  options: SetupOptions,
  env: NodeJS.ProcessEnv = process.env,
): Promise<Outcome> {
  const input = readProject(root)
  const packages = readPackages(root, packageCache(env), input)
  const outline = load(root, input, env, null, packages)
  const { files, luals } = await setupFiles(
    options.version,
    await loadApiStubs(),
    {
      outline,
      files: input,
      packages: packageSources(outline, packages.folders, dependenciesOf(input[MANIFEST_FILE])),
      servers: options.servers ?? null,
    },
    options.cli,
  )
  let written = 0
  for (const [file, text] of Object.entries(files))
    if (writeIfChanged(path.join(root, file), text)) written++
  // A package no longer depended on, or a module it no longer exports: its stubs go.
  const wanted = new Set(luals)
  let removed = 0
  for (const file of filesUnder(root, AGENT_FILES.luals)) {
    if (wanted.has(file)) continue
    rmSync(path.join(root, file))
    removed++
  }
  const out = [
    `Set up .netherforge/ for NetherForge ${options.version}: ${Object.keys(schemaFiles).length} JSON Schemas, ` +
      `${luals.length} files for lua-language-server, the docs for agents` +
      (options.cli != null ? `, and the netherforge command (${AGENT_FILES.cli}).` : '.'),
    written === 0 && removed === 0
      ? 'Everything was up to date.'
      : `${written} file${written === 1 ? '' : 's'} written, ${removed} removed.`,
  ]
  if (!existsSync(path.join(root, LUARC.file))) {
    writeFileSync(path.join(root, LUARC.file), LUARC.text)
    out.push(
      `Wrote ${LUARC.file}, which points lua-language-server at .netherforge/luals/ (commit it).`,
    )
  }
  return { out, err: [], code: 0 }
}

/** The workspace's own `.netherforge/`: the schema its netherforge-workspace.json's `$schema` points at. */
export function setupWorkspace(root: string): Outcome {
  const name = 'workspace.schema.json'
  const text = schemaFiles[name]
  if (text !== undefined) writeIfChanged(path.join(root, SCHEMA_FOLDER, name), text)
  return { out: [], err: [], code: 0 }
}
