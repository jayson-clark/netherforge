#!/usr/bin/env node
/**
 * The repo's one entry point for building, testing, linting and formatting
 * everything, on every OS: `node tools/check.mjs <build|test|lint|format|generate>`
 * (or `pnpm test`, `pnpm lint`, …).
 *
 * Every step runs even if an earlier one failed, so one run reports
 * everything; the exit code is non-zero if any step failed.
 *
 * `--skip <prefix>` skips the steps whose label starts with it, ignoring case
 * (CI lints Rust on Linux only: `node tools/check.mjs lint --skip rust`).
 */
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const win = process.platform === 'win32'
/** A Gradle invocation; the build's root and wrapper live in tools/gradle. */
const gradle = (...args) => ['node', 'tools/gradle.mjs', ...args]
const cargoManifest = 'apps/editor/src-tauri/Cargo.toml'
const hasEditor = existsSync(path.join(root, cargoManifest))

/**
 * The editor and its tests, and the Lua API's generator, import format's Kotlin/JS
 * build and generated contract (`@netherforge/format`), so the TypeScript steps need it first; on a
 * fresh clone it doesn't exist yet. Gradle skips it when nothing changed.
 */
const formatJs = ['Kotlin: format JS library and contract', gradle(':format:jsLibrary')]

/** Where our Lua is: stylua formats it all. */
const luaDirs = ['examples', 'apps/plugin', 'packages/format/src']

/** Each step: a label and a command. Steps whose `when` is false are skipped. */
const plans = {
  build: [
    ['Kotlin: format JS library, plugin jars', gradle(':format:jsLibrary', 'assemble')],
    ['TypeScript packages', ['pnpm', '-r', '--if-present', 'build']],
  ],
  test: [
    formatJs,
    [
      'Kotlin: format (JVM + JS), plugin',
      gradle('check', '-x', 'ktlintCheck', '-x', 'integrationTest'),
    ],
    // The tests that drive the real lua-language-server run the pinned one (cached after the first fetch).
    ['lua-language-server (pinned)', ['node', 'tools/luals.mjs']],
    ['TypeScript: unit tests', ['pnpm', '-r', '--if-present', 'test']],
    ['Rust: editor backend', ['cargo', 'test', '--manifest-path', cargoManifest], hasEditor],
  ],
  lint: [
    formatJs,
    ['Kotlin: ktlint', gradle('ktlintCheck')],
    ['Prettier', ['pnpm', 'exec', 'prettier', '--check', '.']],
    ['TypeScript: eslint + typecheck', ['pnpm', '-r', '--if-present', 'lint']],
    ['Rust: rustfmt', ['cargo', 'fmt', '--manifest-path', cargoManifest, '--check'], hasEditor],
    [
      'Rust: clippy',
      [
        'cargo',
        'clippy',
        '--manifest-path',
        cargoManifest,
        '--all-targets',
        '--',
        '-D',
        'warnings',
      ],
      hasEditor,
    ],
    ['Lua: stylua', ['pnpm', 'exec', 'stylua', '--check', ...luaDirs]],
    // The prelude's modules and format's terrain glue, type-checked by the pinned LuaLS (the editor's own; fetched, cached).
    ['Lua: lua-language-server (the plugin, format)', ['node', 'tools/lint-prelude.mjs']],
    ['Generated files are up to date', ['node', 'tools/generated-check.mjs']],
    ['Editor skills name files that exist', ['node', 'tools/skill-paths.mjs']],
    ['Versions agree with VERSION', ['node', 'tools/release.mjs', '--check']],
  ],
  format: [
    ['Kotlin: ktlint', gradle('ktlintFormat')],
    ['Prettier', ['pnpm', 'exec', 'prettier', '--write', '.']],
    ['Rust: rustfmt', ['cargo', 'fmt', '--manifest-path', cargoManifest], hasEditor],
    ['Lua: stylua', ['pnpm', 'exec', 'stylua', ...luaDirs]],
  ],
  generate: [
    // The Lua API's `since` names features in format's table (its generated constants).
    formatJs,
    [
      'Lua API outputs',
      ['pnpm', '--filter', '@netherforge/api', 'generate'],
      existsSync(path.join(root, 'packages/api/package.json')),
    ],
    // The UI's command bindings and the commands permission, from the Rust command list.
    [
      'Editor command bindings',
      ['cargo', 'run', '--quiet', '--manifest-path', cargoManifest, '--example', 'bindings'],
      hasEditor,
    ],
  ],
}

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: { skip: { type: 'string', multiple: true, default: [] } },
})
const which = positionals[0]
const plan = plans[which]
if (!plan || positionals.length !== 1) {
  console.error(`usage: node tools/check.mjs <${Object.keys(plans).join('|')}> [--skip <prefix>]…`)
  process.exit(2)
}
const skipped = (label) =>
  values.skip.some((prefix) => label.toLowerCase().startsWith(prefix.toLowerCase()))

const failed = []
for (const [label, [command, ...args], when = true] of plan) {
  if (!when || skipped(label)) {
    console.log(`\n— ${label}: skipped`)
    continue
  }
  console.log(`\n▶ ${label}`)
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', shell: win })
  if (result.status !== 0) failed.push(label)
}

if (failed.length) {
  console.error(`\n✗ ${which} failed: ${failed.join(', ')}`)
  process.exit(1)
}
console.log(`\n✓ ${which} passed`)
