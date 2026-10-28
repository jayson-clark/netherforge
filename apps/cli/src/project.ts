import { createHash } from 'node:crypto'
import { closeSync, openSync, readdirSync, readFileSync, readSync, statSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import * as nf from '@netherforge/format'
import { KINDS, LOCK_FILE, MANIFEST_FILE, type DocumentKindId } from '@netherforge/format/constants'
import type { ClassifiedPath, GitFetched, PackagesNeeded } from '@netherforge/format/types'
import { fetchGit } from './git.ts'

/**
 * Every file in the project, as `/`-separated project paths, sorted: what the
 * editor lists. `.git` (anywhere), the top-level `.netherforge/` and the
 * editor's half-written temp files are left out.
 */
export function listFiles(root: string): string[] {
  const files: string[] = []
  const walk = (dir: string, prefix: string) => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      // A folder we can't read shouldn't hide the rest of the project.
      return
    }
    for (const entry of entries) {
      const relative = prefix + entry.name
      if (entry.name === '.git' || relative === '.netherforge') continue
      if (/^\..+\.nftmp-[0-9a-f]+$/.test(entry.name)) continue
      if (entry.isDirectory()) walk(path.join(dir, entry.name), `${relative}/`)
      else if (entry.isFile()) files.push(relative)
    }
  }
  walk(root, '')
  return files.sort()
}

/** The folders of the kinds whose files are Minecraft's own: never read as text. */
const BINARY_FOLDERS = Object.values(KINDS)
  .filter((kind) => kind.contents === 'binary')
  .map((kind) => `${kind.folder}/`)

/**
 * What `loadProject` takes: JSON files' text (and `netherforge.lock`'s, JSON
 * by another name, and every document format owns whatever it's called: a
 * datapack's `pack.mcmeta`), Lua's (for the modules scripts `require`),
 * `null` for the rest (PNGs, structures).
 * JSON in a binary kind's folder (a map's player stats) is the
 * game's own, never read, as in the editor, unless format owns it (a
 * structure's generation file, `structures/<id>.json`).
 */
export function readProject(root: string): Record<string, string | null> {
  const input: Record<string, string | null> = {}
  for (const file of listFiles(root)) {
    const text =
      file === LOCK_FILE ||
      documentKindOf(file) !== null ||
      file.endsWith('.lua') ||
      (file.endsWith('.json') && !BINARY_FOLDERS.some((it) => file.startsWith(it)))
    input[file] = text ? readFileSync(path.join(root, file), 'utf8') : null
  }
  return input
}

/**
 * The pixel size of each PNG of a resource pack's `textures/` in [files], from its header, which is all that holding
 * a replaced game texture to the game's size needs (a file that isn't a PNG is left out).
 */
export function pngSizes(root: string, files: string[]): Record<string, [number, number]> {
  const sizes: Record<string, [number, number]> = {}
  for (const file of files) {
    if (!/^resource_packs\/[^/]+\/textures\/.+\.png$/.test(file)) continue
    const fd = openSync(path.join(root, file), 'r')
    try {
      const header = Buffer.alloc(24)
      const read = readSync(fd, header, 0, 24, 0)
      if (
        read === 24 &&
        header.subarray(0, 8).equals(PNG_SIGNATURE) &&
        header.toString('latin1', 12, 16) === 'IHDR'
      ) {
        sizes[file] = [header.readUInt32BE(16), header.readUInt32BE(20)]
      }
    } finally {
      closeSync(fd)
    }
  }
  return sizes
}

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/**
 * What each WebAssembly module in [files] (a `wasm/<id>/module.wasm`) says about itself, read by format's own reader,
 * which is what checks what it imports and exports (the server says the rest when it compiles it).
 */
export function wasmFacts(root: string, files: string[]): Record<string, unknown> {
  const facts: Record<string, unknown> = {}
  for (const file of files) {
    const classified = JSON.parse(nf.classify(file)) as ClassifiedPath | null
    if (classified?.kind !== 'wasm' || classified.role !== 'main') continue
    const bytes = readFileSync(path.join(root, file))
    facts[file] = JSON.parse(
      nf.readWasm(new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)),
    )
  }
  return facts
}

const sha256 = (data: Buffer | string) => createHash('sha256').update(data).digest('hex')

/**
 * A package's content hash (format's `PackageHash`): every file's SHA-256 in
 * format's listing (which leaves out what isn't the package's content), hashed.
 */
export function hashPackage(dir: string): string {
  const digests: Record<string, string> = {}
  for (const file of listFiles(dir)) digests[file] = sha256(readFileSync(path.join(dir, file)))
  return nf.packageHash(sha256(nf.packageListing(JSON.stringify(digests))))
}

/** The editor's package cache: git packages are fetched into it (see `git.ts`). */
export function packageCache(env: NodeJS.ProcessEnv = process.env): string {
  return path.join(editorDataDir(env), 'packages')
}

/**
 * The folder a package location names: a folder relative to the project
 * at [root] (`../economy`), or a git package's checkout in [cache] (`git:<commit>`).
 */
export function packageDir(root: string, cache: string, location: string): string {
  const checkout = nf.gitCheckout(location)
  return checkout == null ? path.resolve(root, location) : path.join(cache, ...checkout.split('/'))
}

/** A package folder as format's `loadProject` takes it: what `readProject` reads of it, and its content hash. */
export interface PackageFolder {
  files: Record<string, string | null>
  hash: string
}

/** What format's `loadProject` takes of a project's packages (its `PackageInputs`): null where there's no project. */
export interface PackageInputs {
  folders: Record<string, PackageFolder | null>
  git: Record<string, GitFetched>
}

/**
 * Every package the project at [root] depends on, its dependencies'
 * dependencies included, as format's `loadProject` takes them: each git
 * dependency fetched into [cache] (at the commit `netherforge.lock` pins,
 * when [input] holds the lock), and each folder read as format asks for it
 * (null where there's no project).
 */
export function readPackages(
  root: string,
  cache: string,
  input: Record<string, string | null> = readProject(root),
): PackageInputs {
  const packages: PackageInputs = { folders: {}, git: {} }
  for (;;) {
    const needed = JSON.parse(
      nf.packagesNeeded(JSON.stringify(input), JSON.stringify(packages)),
    ) as PackagesNeeded
    if (needed.locations.length === 0 && needed.git.length === 0) return packages
    for (const request of needed.git) {
      try {
        packages.git[request.key] = { commit: fetchGit(cache, request) }
      } catch (error) {
        packages.git[request.key] = { error: (error as Error).message }
      }
    }
    for (const location of needed.locations) {
      const dir = packageDir(root, cache, location)
      packages.folders[location] = isProject(dir)
        ? { files: readProject(dir), hash: hashPackage(dir) }
        : null
    }
  }
}

function isProject(dir: string): boolean {
  try {
    return statSync(path.join(dir, MANIFEST_FILE)).isFile()
  } catch {
    return false
  }
}

/** Which document kind a project path is (format's `classify`), or null for files format doesn't write. */
export function documentKindOf(file: string): DocumentKindId | null {
  const found = JSON.parse(nf.classify(file)) as ClassifiedPath | null
  return (found?.document as DocumentKindId | undefined) ?? null
}

/** The nearest folder at or above [start] with a netherforge.json, or null. */
export function findProjectRoot(start: string): string | null {
  let dir = path.resolve(start)
  for (;;) {
    try {
      readFileSync(path.join(dir, MANIFEST_FILE))
      return dir
    } catch {
      const parent = path.dirname(dir)
      if (parent === dir) return null
      dir = parent
    }
  }
}

/**
 * The editor's data folder, as the Rust side picks it (`app/dirs.rs`):
 * `NETHERFORGE_DATA_DIR`, else the OS's local data folder + `NetherForge`.
 */
export function editorDataDir(
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
  home: string = os.homedir(),
): string {
  if (env.NETHERFORGE_DATA_DIR) return env.NETHERFORGE_DATA_DIR
  if (platform === 'win32') {
    return path.win32.join(
      env.LOCALAPPDATA || path.win32.join(home, 'AppData', 'Local'),
      'NetherForge',
    )
  }
  if (platform === 'darwin')
    return path.posix.join(home, 'Library', 'Application Support', 'NetherForge')
  return path.posix.join(
    env.XDG_DATA_HOME || path.posix.join(home, '.local', 'share'),
    'NetherForge',
  )
}

/** Where the dev server's export of [minecraft]'s game data is cached. */
export function gameDataFile(dataDir: string, minecraft: string): string {
  return path.join(dataDir, 'minecraft', minecraft, 'server', 'game-data.json')
}
