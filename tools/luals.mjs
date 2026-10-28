#!/usr/bin/env node
/**
 * Fetches the pinned lua-language-server (LuaLS) the editor runs for Lua
 * scripts, verified against the checksums below, into
 * `apps/editor/src-tauri/lua-language-server/`: where the dev build runs it
 * from, the release bundles it from (`tauri.bundle.conf.json`), and the
 * tests that drive the real LuaLS find it.
 *
 *   node tools/luals.mjs                     this machine's platform
 *   node tools/luals.mjs --target <target>   darwin-arm64 | darwin-x64 | darwin-universal |
 *                                            linux-arm64 | linux-x64 | win32-x64
 *   node tools/luals.mjs --print             print the binary's path (fetching first if needed)
 *   node tools/luals.mjs --optional          a failure (offline) warns instead of failing
 *
 * Nothing happens when the folder already holds that version for that target
 * (`.version`), so it's cheap to run before every build and test.
 * `darwin-universal` (the macOS release) joins the two macOS binaries with
 * `lipo` and signs the result ad hoc, as the linker signed each half; the
 * release signs it again with the Developer ID when there is one.
 *
 * To bump LuaLS: change VERSION, put each asset's sha256 (GitHub lists them on
 * the release page) in SHA256, run this, and check the editor and
 * `pnpm test` (which drives the real server) still pass.
 */
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const VERSION = '3.19.1'

/** The release asset per target, and its sha256. */
const ASSETS = {
  'darwin-arm64': {
    file: 'darwin-arm64.tar.gz',
    sha256: '0bc077f4447f076b4c92c14e9fd303f5b569eda2ec74b4dca2b55f75fae2e90c',
  },
  'darwin-x64': {
    file: 'darwin-x64.tar.gz',
    sha256: 'eb373c159cbe556711d7cd316315de2dce969bfd54b31edb7eb9cab2937f2cca',
  },
  'linux-arm64': {
    file: 'linux-arm64.tar.gz',
    sha256: 'abd2572e8fc929dc838a81ffb8473c5bce0bf39bfe8edb4b120b3b623176ce83',
  },
  'linux-x64': {
    file: 'linux-x64.tar.gz',
    sha256: 'e9235d2d72ef55bc41cf8c99cda2ed64777682024b4bb81f5dea425060c5cbb8',
  },
  'win32-x64': {
    file: 'win32-x64.zip',
    sha256: 'fdb9a59108cf62517813c97fa5549b0e16d1ef0688306bac728b08434db7e4cd',
  },
}
const TARGETS = [...Object.keys(ASSETS), 'darwin-universal']

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
export const LUALS_DIR = path.join(root, 'apps/editor/src-tauri/lua-language-server')
const STAMP = path.join(LUALS_DIR, '.version')

/** The executable inside [dir], for [target]'s OS. */
export const binaryIn = (dir, target = hostTarget()) =>
  path.join(
    dir,
    'bin',
    target.startsWith('win32') ? 'lua-language-server.exe' : 'lua-language-server',
  )

function hostTarget() {
  const target = `${process.platform}-${process.arch}`
  if (!ASSETS[target]) throw new Error(`LuaLS has no build for ${target}`)
  return target
}

async function download(target) {
  const { file, sha256 } = ASSETS[target]
  const url = `https://github.com/LuaLS/lua-language-server/releases/download/${VERSION}/lua-language-server-${VERSION}-${file}`
  const response = await fetch(url)
  if (!response.ok) throw new Error(`${url}: ${response.status} ${response.statusText}`)
  const bytes = Buffer.from(await response.arrayBuffer())
  const actual = createHash('sha256').update(bytes).digest('hex')
  if (actual !== sha256)
    throw new Error(`${url}: sha256 is ${actual}, expected ${sha256}. Refusing to use it.`)
  return { bytes, file }
}

/** Unpacks one target's archive into a new folder under [work] and returns it. */
async function unpack(target, work) {
  const { bytes, file } = await download(target)
  const archive = path.join(work, file)
  writeFileSync(archive, bytes)
  const out = path.join(work, target)
  mkdirSync(out)
  // bsdtar (macOS, Windows 10+) reads zips too; GNU tar on Linux only ever gets a .tar.gz.
  // On Windows, Windows's own bsdtar: Git Bash's GNU tar may come first on the PATH.
  const tar =
    process.platform === 'win32'
      ? path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe')
      : 'tar'
  run(tar, ['-xf', archive, '-C', out])
  return out
}

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit' })
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed`)
}

export async function fetchLuals(target = hostTarget()) {
  if (!TARGETS.includes(target)) throw new Error(`unknown target ${target}: ${TARGETS.join(', ')}`)
  const want = `${VERSION} ${target}`
  const have = existsSync(STAMP) ? readFileSync(STAMP, 'utf8').trim() : null
  // A universal macOS build runs on either Mac.
  const universal = `${VERSION} darwin-universal`
  if (have === want || (have === universal && target.startsWith('darwin-'))) return LUALS_DIR

  const work = mkdtempSync(path.join(tmpdir(), 'netherforge-luals-'))
  try {
    let folder
    if (target === 'darwin-universal') {
      const arm = await unpack('darwin-arm64', work)
      const intel = await unpack('darwin-x64', work)
      const binary = binaryIn(arm, target)
      run('lipo', ['-create', binary, binaryIn(intel, target), '-output', `${binary}.universal`])
      renameSync(`${binary}.universal`, binary)
      run('codesign', ['--force', '--sign', '-', binary])
      folder = arm
    } else {
      folder = await unpack(target, work)
    }
    writeFileSync(path.join(folder, '.version'), want + '\n')
    rmSync(LUALS_DIR, { recursive: true, force: true })
    mkdirSync(path.dirname(LUALS_DIR), { recursive: true })
    // A copy, not a rename: the temporary folder may be on another volume.
    cpSync(folder, LUALS_DIR, { recursive: true })
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
  console.error(`lua-language-server ${VERSION} (${target}) → ${path.relative(root, LUALS_DIR)}`)
  return LUALS_DIR
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2)
  const at = args.indexOf('--target')
  try {
    const target = at >= 0 ? args[at + 1] : hostTarget()
    const dir = await fetchLuals(target)
    if (args.includes('--print')) console.log(binaryIn(dir, target))
  } catch (error) {
    console.error(`lua-language-server: ${error.message}`)
    // `--optional` (the dev editor's start): without it, Lua just gets no language features.
    process.exit(args.includes('--optional') ? 0 : 1)
  }
}
