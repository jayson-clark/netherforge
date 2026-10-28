#!/usr/bin/env node
/**
 * One version for everything NetherForge ships. `VERSION` is the source of
 * truth (Gradle reads it for the plugin jars); every other place a version is
 * written must agree with it.
 *
 *   node tools/release.mjs <version>        set every version to <version>
 *   node tools/release.mjs --check          fail if any version disagrees with VERSION
 *   node tools/release.mjs --check v1.2.3   also: the tag matches, and CHANGELOG.md has an entry
 *   node tools/release.mjs --notes 1.2.3    print that version's CHANGELOG.md section (release notes)
 *
 * `pnpm lint` runs `--check`; the release workflow runs it with the tag.
 * Setting a version also requires a CHANGELOG.md entry for it, so a release
 * can't be cut without one.
 */
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SEMVER = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/

const read = (file) => readFileSync(path.join(root, file), 'utf8')
const write = (file, text) => writeFileSync(path.join(root, file), text)

/** Every package.json: the root's and each pnpm workspace package's. */
function packageJsons() {
  const workspace = read('pnpm-workspace.yaml')
  const patterns = [...workspace.matchAll(/^\s*-\s*['"]?([^'"\s#]+)['"]?\s*$/gm)].map((m) => m[1])
  // Entries are folders, or `parent/*` for every folder in parent (apps/*, packages/*).
  const dirs = patterns.flatMap((pattern) =>
    pattern.endsWith('/*')
      ? readdirSync(path.join(root, pattern.slice(0, -2)), { withFileTypes: true })
          .filter((entry) => entry.isDirectory())
          .map((entry) => `${pattern.slice(0, -2)}/${entry.name}`)
      : [pattern],
  )
  return [
    'package.json',
    ...dirs.map((dir) => `${dir}/package.json`).filter((file) => existsSync(path.join(root, file))),
  ]
}

/**
 * A place a version is written. `get` reads it (null if it's missing);
 * `set` returns the file's text with it replaced, changing nothing else.
 */
function jsonVersion(file) {
  return {
    file,
    get: () => JSON.parse(read(file)).version ?? null,
    set: (text, version) => text.replace(/^(\s*"version"\s*:\s*")[^"]*(")/m, `$1${version}$2`),
  }
}

/**
 * Kotlin/JS's npm lockfile (committed, so `kotlinStorePackageLock` fails on any
 * drift) records the Gradle build's version: the root package's and each
 * workspace package's (`packages/netherforge-format`, …).
 */
function npmLockVersion(file) {
  const entries = (lock) => [
    lock,
    lock.packages[''],
    ...Object.entries(lock.packages)
      .filter(([key]) => key.startsWith('packages/'))
      .map(([, entry]) => entry),
  ]
  return {
    file,
    get: () => {
      const versions = new Set(entries(JSON.parse(read(file))).map((entry) => entry.version))
      return versions.size === 1 ? [...versions][0] : [...versions].join(', ')
    },
    set: (text, version) => {
      const lock = JSON.parse(text)
      for (const entry of entries(lock)) entry.version = version
      return `${JSON.stringify(lock, null, 2)}\n`
    },
  }
}

const cargoToml = 'apps/editor/src-tauri/Cargo.toml'
const cargoLock = 'apps/editor/src-tauri/Cargo.lock'
const tauriConf = 'apps/editor/src-tauri/tauri.conf.json'

const CARGO_PACKAGE = /(\[package\][^[]*?\nversion\s*=\s*")([^"]*)(")/
/** The lock file's entry for the editor crate itself (its name is in Cargo.toml). */
const lockEntry = (name) =>
  new RegExp(`(\\[\\[package\\]\\]\\nname = "${name}"\\nversion = ")([^"]*)(")`)
const crateName = () => read(cargoToml).match(/\[package\][^[]*?\nname\s*=\s*"([^"]*)"/)?.[1]

const places = [
  { file: 'VERSION', get: () => read('VERSION').trim(), set: (_, v) => `${v}\n` },
  ...packageJsons().map(jsonVersion),
  npmLockVersion('tools/gradle/kotlin-js-store/package-lock.json'),
  {
    file: cargoToml,
    get: () => read(cargoToml).match(CARGO_PACKAGE)?.[2] ?? null,
    set: (text, v) => text.replace(CARGO_PACKAGE, `$1${v}$3`),
  },
  {
    file: cargoLock,
    get: () => read(cargoLock).match(lockEntry(crateName()))?.[2] ?? null,
    set: (text, v) => text.replace(lockEntry(crateName()), `$1${v}$3`),
  },
  {
    // Tauri may take its version from apps/editor/package.json ("../package.json"),
    // which is then already covered. A literal version must agree too.
    file: tauriConf,
    get: () => {
      const version = JSON.parse(read(tauriConf)).version
      return version === undefined || version.endsWith('package.json') ? undefined : version
    },
    set: (text, v) =>
      /"version"\s*:\s*"[^"]*package\.json"/.test(text)
        ? text
        : text.replace(/^(\s*"version"\s*:\s*")[^"]*(")/m, `$1${v}$2`),
  },
]

function changelogHas(version) {
  const escaped = version.replace(/[.+-]/g, '\\$&')
  let changelog
  try {
    changelog = read('CHANGELOG.md')
  } catch {
    return false
  }
  return new RegExp(`^## \\[${escaped}\\]`, 'm').test(changelog)
}

function fail(message) {
  console.error(`✗ ${message}`)
  process.exit(1)
}

function check(tag) {
  const expected = read('VERSION').trim()
  if (!SEMVER.test(expected)) fail(`VERSION is "${expected}", which isn't a semantic version`)
  const wrong = []
  for (const place of places) {
    const actual = place.get()
    if (actual === undefined) continue // not written there, by design
    if (actual !== expected) wrong.push(`  ${place.file}: ${actual ?? '(no version)'}`)
  }
  if (wrong.length) {
    fail(
      `Versions disagree with VERSION (${expected}):\n${wrong.join('\n')}\n` +
        `Run \`node tools/release.mjs ${expected}\` to set them all.`,
    )
  }
  if (tag !== undefined) {
    if (tag !== `v${expected}`)
      fail(`Tag ${tag} doesn't match VERSION (${expected}): expected v${expected}`)
    if (!changelogHas(expected)) fail(`CHANGELOG.md has no "## [${expected}]" entry`)
  }
  console.log(
    `✓ Every version is ${expected}${tag ? `, matching ${tag}, with a changelog entry` : ''}`,
  )
}

function bump(version) {
  if (!SEMVER.test(version))
    fail(`"${version}" isn't a semantic version (like 1.2.3 or 1.2.3-beta.1)`)
  if (!changelogHas(version)) {
    fail(
      `CHANGELOG.md has no entry for ${version}. Move what's under "## [Unreleased]" into\n` +
        `"## [${version}] - ${new Date().toISOString().slice(0, 10)}" (and leave an empty Unreleased section), then run this again.`,
    )
  }
  for (const place of places) {
    if (place.get() === undefined) continue
    const before = read(place.file)
    const after = place.set(before, version)
    if (after !== before) {
      write(place.file, after)
      console.log(`  ${place.file}`)
    }
  }
  check()
  console.log(
    `\nNext: commit, then tag and push:\n  git tag v${version} && git push origin v${version}`,
  )
}

/** The body of a version's CHANGELOG.md section, without its heading. */
function notes(version) {
  if (!changelogHas(version)) fail(`CHANGELOG.md has no "## [${version}]" entry`)
  const lines = read('CHANGELOG.md').split('\n')
  const start = lines.findIndex((line) => line.startsWith(`## [${version}]`))
  const end = lines.findIndex((line, i) => i > start && /^## \[/.test(line))
  const body = lines.slice(start + 1, end === -1 ? undefined : end)
  // Link reference definitions at the end of the file aren't part of the section.
  while (body.length && (!body.at(-1).trim() || /^\[[^\]]+\]:/.test(body.at(-1)))) body.pop()
  console.log(body.join('\n').trim())
}

const [arg, tag] = process.argv.slice(2)
if (arg === '--check') check(tag)
else if (arg === '--notes' && tag) notes(tag)
else if (arg && !arg.startsWith('-')) bump(arg)
else {
  console.error('usage: node tools/release.mjs <version> | --check [tag] | --notes <version>')
  process.exit(2)
}
