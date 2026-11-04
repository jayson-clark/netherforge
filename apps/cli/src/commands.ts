import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import * as nf from '@netherforge/format'
import { archive, buildPack, put, signBundle, storage, type Storage } from './bundles.ts'
import {
  BUNDLE_FILE,
  GAME_DATA_SCHEMA,
  LOCK_FILE,
  MANIFEST_FILE,
} from '@netherforge/format/constants'
import type { CanonicalResult, Problem, ProjectOutline } from '@netherforge/format/types'
import {
  documentKindOf,
  editorDataDir,
  gameDataFile,
  hashPackage,
  pngSizes,
  wasmFacts,
  listFiles,
  packageCache,
  packageDir,
  readPackages,
  readProject,
  type PackageFolder,
  type PackageInputs,
} from './project.ts'

/** What a command prints and its exit code; main() does the printing. */
export interface Outcome {
  out: string[]
  err: string[]
  code: number
}

/** `file:line:column: severity: message (at $.json.path)`, the shape editors and agents parse. */
export function formatProblem(problem: Problem): string {
  const where = [problem.file, problem.line, problem.line != null ? problem.column : undefined]
    .filter((it) => it != null)
    .join(':')
  const at = problem.path ? ` (at ${problem.path})` : ''
  const code = problem.code ? ` [${problem.code}]` : ''
  // A broken reference's other end, so it can be found from either.
  const related = (problem.related ?? []).map(
    (it) => `\n  see ${it.file}${it.path ? ` (at ${it.path})` : ''}`,
  )
  return `${where}: ${problem.severity}${code}: ${problem.message}${at}${related.join('')}`
}

const RELEASE = /^\d+(\.\d+)*$/

/**
 * The game data to validate ids against: [explicit] if given, else the
 * editor's cache for the project's target version. Null when there is none;
 * validation then checks everything but whether ids exist in the game.
 */
export function loadGameData(
  input: Record<string, string | null>,
  explicit: string | undefined,
  env: NodeJS.ProcessEnv,
): { json: string | null; note: string | null } {
  if (explicit) {
    const json = readFileSync(explicit, 'utf8')
    if (isCurrent(json)) return { json, note: null }
    return {
      json: null,
      note:
        `${explicit} is game data of another shape than this NetherForge reads (schema ${GAME_DATA_SCHEMA}), ` +
        "so block, item and entity ids weren't checked.",
    }
  }
  const minecraft = projectMinecraft(input['netherforge.json'])
  if (minecraft == null) return { json: null, note: null }
  const file = gameDataFile(editorDataDir(env), minecraft)
  let json: string | null = null
  try {
    json = readFileSync(file, 'utf8')
  } catch {
    // No cache: said below.
  }
  if (json != null && isCurrent(json)) return { json, note: null }
  // A cache of another schema is as good as none: the editor exports it again on the next start.
  return {
    json: null,
    note:
      `No game data for Minecraft ${minecraft} (${file}), so block, item and entity ids weren't checked. ` +
      `Start the dev server from the NetherForge editor once to export it, run netherforge game-data ${minecraft} --accept-eula, or pass --game-data <file>.`,
  }
}

/** The release version the manifest's text targets (`minecraft`), or null when it names none. */
export function projectMinecraft(manifest: string | null | undefined): string | null {
  try {
    const minecraft = (JSON.parse(manifest ?? '{}') as { minecraft?: unknown }).minecraft
    return typeof minecraft === 'string' && RELEASE.test(minecraft) ? minecraft : null
  } catch {
    return null
  }
}

/** Whether [json] is a game data export of the shape format reads (`GameDataBundle.schema`). */
export function isCurrent(json: string): boolean {
  try {
    return (JSON.parse(json) as { schema?: unknown }).schema === GAME_DATA_SCHEMA
  } catch {
    return false
  }
}

export function check(
  root: string,
  options: {
    json?: boolean
    gameData?: string
    servers?: string[]
    policy?: string
    provided?: string[]
  },
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const bundle = isBundle(root) ? readBundle(root, options.provided ?? []) : null
  const projectRoot = bundle?.root ?? root
  const input = bundle?.input ?? readProject(root)
  const game = loadGameData(input, options.gameData, env)
  const packages = bundle?.packages ?? readPackages(root, packageCache(env), input)
  const outline = load(projectRoot, input, env, game.json, packages)
  const problems = [
    ...(bundle?.problems ?? []),
    ...outline.problems,
    ...networkProblems(input, options.servers),
    ...policyProblems(input, packages, options.policy),
  ]
  const errors = problems.filter((it) => it.severity === 'error').length
  const warnings = problems.length - errors
  const code = errors > 0 ? 1 : 0
  if (options.json) {
    const report = { problems, gameData: game.json != null, errors, warnings }
    return { out: [JSON.stringify(report, null, 2)], err: [], code }
  }
  const out = problems.map(formatProblem)
  const err = game.note ? [game.note] : []
  out.push(
    problems.length === 0
      ? 'No problems.'
      : `${errors} error${errors === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'}.`,
  )
  return { out, err, code }
}

/** Whether [dir] is a bundle `netherforge build` wrote (or an unpacked `.nfbundle`): it has a `netherforge-bundle.json`. */
export function isBundle(dir: string): boolean {
  return existsSync(path.join(dir, BUNDLE_FILE))
}

/** A bundle as `check` loads it: its project's folder and files, its packages by namespace, and what's wrong with it. */
interface ReadBundle {
  root: string
  input: Record<string, string | null>
  packages: PackageInputs
  problems: Problem[]
}

/**
 * The bundle at [dir] as a server loads it (`ProjectFiles.loadBundle`): its project and every package in its own
 * folders, by the names `netherforge-bundle.json` lists, never the project's dependencies' locations; each held to the
 * hash the manifest has for it. [provided] are the folders of packages the server provides (its `packages.provided`),
 * which the bundle may leave out but mustn't carry.
 */
function readBundle(dir: string, provided: string[]): ReadBundle {
  const problems: Problem[] = []
  const at = (code: string, message: string, where?: string): Problem => ({
    severity: 'error',
    file: BUNDLE_FILE,
    message,
    code,
    ...(where ? { path: where } : {}),
  })
  const parsed = JSON.parse(
    nf.canonicalize('bundle', BUNDLE_FILE, readFileSync(path.join(dir, BUNDLE_FILE), 'utf8')),
  ) as CanonicalResult
  problems.push(...parsed.problems)
  const manifest =
    parsed.text == null
      ? null
      : (JSON.parse(parsed.text) as {
          project: string
          packages: Record<string, { hash: string }>
          provided?: string[]
        })
  const server: Record<string, PackageFolder> = {}
  for (const folder of provided) {
    const file = path.join(folder, MANIFEST_FILE)
    let namespace: string | undefined
    try {
      namespace = (JSON.parse(readFileSync(file, 'utf8')) as { namespace?: string }).namespace
    } catch {
      // Said below.
    }
    if (!namespace) {
      problems.push(
        at(
          'runtime.provided-package',
          `--provided ${folder}: there's no readable ${MANIFEST_FILE} there`,
        ),
      )
      continue
    }
    server[namespace] = { files: readProject(folder), hash: hashPackage(folder) }
  }
  const bundled: Record<string, PackageFolder> = {}
  for (const [name, { hash }] of Object.entries(manifest?.packages ?? {})) {
    const where = `$.packages.${name}`
    const folder = path.join(dir, name)
    if (!/^[a-z0-9_]+$/.test(name) || !existsSync(path.join(folder, MANIFEST_FILE))) {
      problems.push(at('runtime.bundle-hash', `The bundle has no folder for "${name}"`, where))
      continue
    }
    const actual = hashPackage(folder)
    if (actual !== hash)
      problems.push(
        at(
          'runtime.bundle-hash',
          `The package "${name}" in this bundle isn't the one it was built with (its files hash to ${actual}, not ${hash}): ` +
            'build the bundle again rather than changing it',
          where,
        ),
      )
    if (name in server)
      problems.push(
        at(
          'runtime.provided-package',
          `This bundle carries its own "${name}", which the server provides (--provided): build it without it ` +
            `(mark the dependency "provided": true, or netherforge build --exclude ${name})`,
          where,
        ),
      )
    bundled[name] = { files: readProject(folder), hash: actual }
  }
  const project = manifest?.project
  if (manifest && !(project! in bundled))
    problems.push(
      at('runtime.bundle-hash', `The bundle lists no package "${manifest.project}"`, '$.project'),
    )
  const root = project && project in bundled ? path.join(dir, project) : dir
  const input = project && project in bundled ? bundled[project]!.files : {}
  return {
    root,
    input,
    packages: {
      folders: {},
      git: {},
      bundled,
      provided: server,
      leftOut: manifest?.provided ?? [],
    },
    problems,
  }
}

/**
 * What the policy in [file] (a network's `policy.json`, as `--policy` names it) refuses of what the project and its
 * packages require: whether the project would run under it.
 */
function policyProblems(
  input: Record<string, string | null>,
  packages: PackageInputs,
  file?: string,
): Problem[] {
  if (!file) return []
  let text: string
  try {
    text = readFileSync(file, 'utf8')
  } catch (e) {
    return [
      {
        severity: 'error',
        file,
        message: `The policy can't be read: ${(e as Error).message}`,
        code: 'runtime.policy',
      },
    ]
  }
  return JSON.parse(
    nf.checkPolicy(text, JSON.stringify(input), JSON.stringify(packages)),
  ) as Problem[]
}

/**
 * In a workspace with a dev network ([servers], its servers' names), what's wrong with the network
 * names [input]'s scripts use: players sent to a server it doesn't have.
 */
function networkProblems(input: Record<string, string | null>, servers?: string[]): Problem[] {
  if (!servers) return []
  const scripts: Record<string, string> = {}
  for (const [file, text] of Object.entries(input))
    if (text != null && file.endsWith('.lua')) scripts[file] = text
  return JSON.parse(
    nf.checkNetworkUses(JSON.stringify(scripts), JSON.stringify(servers)),
  ) as Problem[]
}

/**
 * The project at [root] and the packages it depends on, loaded as the editor
 * loads them: git packages fetched into the editor's package cache (or
 * [packages], when the caller has read them already).
 */
export function load(
  root: string,
  input: Record<string, string | null>,
  env: NodeJS.ProcessEnv,
  gameData: string | null = null,
  packages: PackageInputs = readPackages(root, packageCache(env), input),
): ProjectOutline {
  return JSON.parse(
    nf.loadProject(
      JSON.stringify(input),
      JSON.stringify(packages),
      gameData,
      JSON.stringify(pngSizes(root, Object.keys(input))),
      JSON.stringify(wasmFacts(root, Object.keys(input))),
    ),
  ) as ProjectOutline
}

/**
 * Writes `netherforge.lock` as the project's dependencies resolve now (as the
 * editor does whenever they change), or removes it from a project with none.
 * Git dependencies stay at the commits the lock pins while their `git` and
 * `rev` are unchanged; with [update], every one is resolved afresh (a
 * branch's newest commit).
 */
export function lock(
  root: string,
  options: { update?: boolean } = {},
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const input = readProject(root)
  const resolving = { ...input }
  if (options.update) delete resolving[LOCK_FILE]
  const outline = load(root, resolving, env)
  const file = path.join(root, LOCK_FILE)
  const errors = outline.problems.filter(
    (it) => it.severity === 'error' && it.code?.startsWith('package.'),
  )
  if (errors.length > 0)
    return {
      out: errors.map(formatProblem),
      err: ["Dependencies don't resolve, so nothing was locked."],
      code: 1,
    }
  if (outline.lock == null) {
    if (Object.keys(outline.packages ?? {}).length > 0)
      return { out: [], err: ["A package couldn't be hashed, so nothing was locked."], code: 1 }
    if (!existsSync(file)) return { out: ['No dependencies to lock.'], err: [], code: 0 }
    rmSync(file)
    return { out: [`${LOCK_FILE}: removed (no dependencies)`], err: [], code: 0 }
  }
  if (input[LOCK_FILE] === outline.lock)
    return { out: [`${LOCK_FILE}: up to date`], err: [], code: 0 }
  writeFileSync(file, outline.lock)
  return { out: [`${LOCK_FILE}: written`], err: [], code: 0 }
}

/**
 * Writes a bundle: the project and every package it depends on, each in a
 * folder named after its namespace with only what its content hash covers
 * (and the default font, which the server measures text with),
 * and `netherforge-bundle.json` listing them with their hashes, for a
 * production server to run as it is. Refuses a project with errors, or whose
 * `netherforge.lock` isn't what its dependencies resolve to.
 *
 * A package the server provides is left out (a dependency marked `provided` anywhere in the tree, or one
 * [options.exclude] names) and listed in the manifest's `provided`, so the bundle's hashes are of what it carries and
 * the server loads its own copy. The resource pack is built still when none of them (as it resolved here) adds to it,
 * since it's the same without them; a server whose copy does add to it builds its own.
 */
export function build(
  root: string,
  options: {
    out?: string
    sign?: string
    archive?: boolean
    gameData?: string
    exclude?: string[]
  },
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const input = readProject(root)
  const game = loadGameData(input, options.gameData, env)
  const outline = load(root, input, env)
  const errors = outline.problems.filter(
    (it) => it.severity === 'error' || it.code === 'lock.missing' || it.code === 'lock.stale',
  )
  if (errors.length > 0) {
    const why = errors.some((it) => it.code?.startsWith('lock.'))
      ? ' Run netherforge lock (or open the project in the editor) to bring netherforge.lock up to date.'
      : ''
    return { out: errors.map(formatProblem), err: [`Not built: fix these first.${why}`], code: 1 }
  }
  const namespace = outline.namespace!
  const version = (JSON.parse(input[MANIFEST_FILE]!) as { version: string }).version
  const out = path.resolve(root, options.out ?? path.join('build', `${namespace}-${version}`))
  if (existsSync(out)) {
    // Only ever replace a bundle: never a folder of something else.
    if (readdirSync(out).length > 0 && !existsSync(path.join(out, BUNDLE_FILE)))
      return {
        out: [],
        err: [`${out} isn't empty and isn't a bundle, so it was left alone.`],
        code: 1,
      }
    rmSync(out, { recursive: true, force: true })
  }
  const folders: Record<string, string> = { [namespace]: root }
  for (const [name, pkg] of Object.entries(outline.packages ?? {}))
    folders[name] = packageDir(root, packageCache(env), pkg.location)
  const provided = new Set<string>()
  for (const name of options.exclude ?? []) {
    if (name === namespace)
      return { out: [], err: [`--exclude ${name}: that's the project itself.`], code: 2 }
    if (!(name in folders))
      return {
        out: [],
        err: [`--exclude ${name}: the project doesn't depend on a package "${name}".`],
        code: 2,
      }
    provided.add(name)
  }
  for (const dir of Object.values(folders)) {
    const { dependencies } = JSON.parse(readFileSync(path.join(dir, MANIFEST_FILE), 'utf8')) as {
      dependencies?: Record<string, { provided?: boolean }>
    }
    for (const [name, dependency] of Object.entries(dependencies ?? {}))
      if (dependency.provided === true && name in folders) provided.add(name)
  }
  for (const name of provided) delete folders[name]
  const packages: Record<string, { version: string; hash: string }> = {}
  for (const [name, dir] of Object.entries(folders).sort(([a], [b]) => a.localeCompare(b))) {
    for (const file of listFiles(dir).filter(nf.isPackageBundled)) {
      const target = path.join(out, name, file)
      mkdirSync(path.dirname(target), { recursive: true })
      copyFileSync(path.join(dir, file), target)
    }
    const manifest = JSON.parse(readFileSync(path.join(dir, MANIFEST_FILE), 'utf8')) as {
      version: string
    }
    packages[name] = { version: manifest.version, hash: hashPackage(path.join(out, name)) }
  }
  const { formatVersion } = JSON.parse(input[MANIFEST_FILE]!) as { formatVersion: number }
  // The resource pack servers would build as players join, built now with the game's data, so a server needn't.
  const layout = nf.resourcePackLayout(
    JSON.stringify(input),
    JSON.stringify(readPackages(root, packageCache(env), input)),
    game.json,
    JSON.stringify(pngSizes(root, Object.keys(input))),
    JSON.stringify([...provided]),
  )
  const pack = buildPack(out, namespace, layout)
  let resourcePack: { sha1: string; format: number[] } | undefined
  let packLine: string
  if ('zip' in pack) {
    writeFileSync(path.join(out, 'resource-pack.zip'), pack.zip)
    resourcePack = { sha1: pack.sha1, format: pack.format }
    packLine = `Built its resource pack (pack format ${pack.format.join('.')}, SHA-1 ${pack.sha1}).`
  } else {
    packLine = `No resource pack in the bundle: ${pack.reason}. A server builds it as it starts.`
  }
  const bundle = {
    formatVersion,
    project: namespace,
    packages,
    ...(resourcePack ? { resourcePack } : {}),
    ...(provided.size > 0 ? { provided: [...provided].sort() } : {}),
  }
  const written = JSON.parse(
    nf.canonicalize('bundle', BUNDLE_FILE, JSON.stringify(bundle)),
  ) as CanonicalResult
  writeFileSync(path.join(out, BUNDLE_FILE), written.text!)
  const names = Object.keys(packages).filter((it) => it !== namespace)
  const what = names.length === 0 ? '' : ` with ${names.join(', ')}`
  // Where it is, as short as it can say it: from here when it's under here.
  const here = path.relative(process.cwd(), out)
  const shown = here && !here.startsWith('..') && !path.isAbsolute(here) ? here : out
  const lines = [`Built ${shown}: ${namespace} ${version}${what}.`, packLine]
  if (provided.size > 0)
    lines.push(`Left out for the server to provide: ${[...provided].sort().join(', ')}.`)
  if (options.sign) {
    try {
      lines.push(`Signed as ${signBundle(out, path.resolve(options.sign))}.`)
    } catch (e) {
      return { out: lines, err: [`Not signed: ${(e as Error).message}`], code: 1 }
    }
  }
  if (options.archive) {
    const file = `${out}.nfbundle`
    lines.push(`Archived ${shown}.nfbundle, sha256 ${archive(out, file)}.`)
  }
  return { out: lines, err: [], code: 0 }
}

/**
 * Builds, signs and archives the bundle, and uploads it and its listing to [options.to] (S3-compatible storage): what
 * a server then runs by URL. The listing (`<namespace>-<version>.json`) has the version, the archive's SHA-256 and
 * size, who signed it, and the project's listing fields.
 */
export async function publish(
  root: string,
  options: {
    to: string
    endpoint?: string
    sign?: string
    publicUrl?: string
    packsTo?: string
    gameData?: string
  },
  env: NodeJS.ProcessEnv = process.env,
): Promise<Outcome> {
  let store: Storage
  try {
    store = storage(options.to, options.endpoint, env)
  } catch (e) {
    return { out: [], err: [(e as Error).message], code: 2 }
  }
  const work = mkdtempSync(path.join(os.tmpdir(), 'netherforge-publish-'))
  try {
    const manifest = JSON.parse(readFileSync(path.join(root, MANIFEST_FILE), 'utf8')) as {
      namespace: string
      version: string
      name?: string
      listing?: { icon?: string; screenshots?: string[]; [key: string]: unknown }
    }
    const name = `${manifest.namespace}-${manifest.version}`
    const out = path.join(work, name)
    const built = build(
      root,
      { out, sign: options.sign, archive: true, gameData: options.gameData },
      env,
    )
    if (built.code !== 0) return built
    const file = `${out}.nfbundle`
    const bytes = readFileSync(file)
    const hash = createHash('sha256').update(bytes).digest('hex')
    const key = (it: string) => (store.prefix ? `${store.prefix}/${it}` : it)
    const base = (
      options.publicUrl ??
      `${store.endpoint}/${store.bucket}/${store.prefix ? store.prefix + '/' : ''}`
    ).replace(/\/?$/, '/')
    const url = `${base}${name}.nfbundle`
    const bundle = JSON.parse(readFileSync(path.join(out, BUNDLE_FILE), 'utf8')) as {
      signatures?: { key: string }[]
    }
    const listing = {
      namespace: manifest.namespace,
      name: manifest.name,
      version: manifest.version,
      url,
      sha256: hash,
      size: bytes.length,
      signedBy: (bundle.signatures ?? []).map((it) => it.key),
      listing: manifest.listing ?? null,
    }
    // The listing's pictures aren't the bundle's (no server needs them): beside it, the listing naming them by URL.
    const pictures: [string, string][] = []
    if (manifest.listing) {
      const picture = (file: string) => {
        const target = `${name}/${pictures.length}-${path.basename(file)}`
        pictures.push([file, target])
        return `${base}${target}`
      }
      listing.listing = {
        ...manifest.listing,
        ...(manifest.listing.icon ? { icon: picture(manifest.listing.icon) } : {}),
        ...(manifest.listing.screenshots
          ? { screenshots: manifest.listing.screenshots.map(picture) }
          : {}),
      }
    }
    // The bundle's resource pack, where servers fetch packs from (resource-pack.storage): they find it there, built.
    const packed = (bundle as { resourcePack?: { sha1: string } }).resourcePack
    let packs: Storage | undefined
    if (options.packsTo) {
      try {
        packs = storage(options.packsTo, options.endpoint, env)
      } catch (e) {
        return { out: built.out, err: [(e as Error).message], code: 2 }
      }
    }
    try {
      if (packs && packed) {
        const packKey = packs.prefix ? `${packs.prefix}/${packed.sha1}.zip` : `${packed.sha1}.zip`
        await put(
          packs,
          packKey,
          readFileSync(path.join(out, 'resource-pack.zip')),
          'application/zip',
        )
      }
      for (const [file, target] of pictures)
        await put(store, key(target), readFileSync(path.join(root, file)), 'image/png')
      await put(store, key(`${name}.nfbundle`), bytes, 'application/zip')
      await put(
        store,
        key(`${name}.json`),
        Buffer.from(JSON.stringify(listing, null, 2) + '\n'),
        'application/json',
      )
    } catch (e) {
      return { out: built.out, err: [`Not published: ${(e as Error).message}`], code: 1 }
    }
    return {
      out: [
        ...built.out,
        `Published ${name}. A server runs it with:`,
        `project: ${url}#sha256=${hash}`,
      ],
      err: [],
      code: 0,
    }
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

/**
 * Rewrites every project JSON file in canonical form, as the editor saves it.
 * With [checkOnly], writes nothing and fails if any file would change.
 * A file that doesn't parse is reported and left alone.
 */
export function format(root: string, options: { check?: boolean }): Outcome {
  const out: string[] = []
  const err: string[] = []
  let code = 0
  for (const file of listFiles(root)) {
    const kind = documentKindOf(file)
    if (!kind) continue
    const full = path.join(root, file)
    const text = readFileSync(full, 'utf8')
    const result = JSON.parse(nf.canonicalize(kind, file, text)) as CanonicalResult
    if (result.text == null) {
      err.push(...result.problems.map(formatProblem))
      code = 1
      continue
    }
    if (result.text === text) continue
    if (options.check) {
      out.push(`${file}: not canonical`)
      code = 1
    } else {
      writeFileSync(full, result.text)
      out.push(`${file}: formatted`)
    }
  }
  return { out, err, code }
}

/**
 * `netherforge generate`: netherforge.json's `generate` commands, in order, from [root] (their output goes straight
 * to the terminal), stopping at the first that fails; then the project formatted.
 */
export function generate(root: string): Outcome {
  let commands: unknown
  try {
    commands = (
      JSON.parse(readFileSync(path.join(root, MANIFEST_FILE), 'utf8')) as { generate?: unknown }
    ).generate
  } catch (error) {
    return { out: [], err: [`Can't read netherforge.json: ${(error as Error).message}`], code: 1 }
  }
  if (commands === undefined)
    return { out: ['netherforge.json has no "generate" commands.'], err: [], code: 0 }
  if (!Array.isArray(commands) || commands.some((it) => typeof it !== 'string')) {
    return {
      out: [],
      err: ['netherforge.json\'s "generate" is a list of commands (strings).'],
      code: 2,
    }
  }
  const out: string[] = []
  for (const command of commands as string[]) {
    process.stdout.write(`> ${command}\n`)
    const result = spawnSync(command, { cwd: root, shell: true, stdio: 'inherit' })
    if (result.status !== 0) {
      return {
        out,
        err: [
          `"${command}" failed (${result.error?.message ?? `exit ${result.status}`}): stopped.`,
        ],
        code: 1,
      }
    }
  }
  const formatted = format(root, {})
  return { out: [...out, ...formatted.out], err: formatted.err, code: formatted.code }
}
