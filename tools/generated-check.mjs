#!/usr/bin/env node
// Fails if the committed generated files aren't what their sources produce, so a
// change to a source of truth can't land without its outputs.
//
// It never writes into the working tree: the generated folders are copied into a
// temporary directory, the generator runs over that copy (`--out`, which also
// deletes files it no longer produces), and the copy is compared with the
// working tree byte for byte. Uncommitted edits stay where they are.
//
// A generator that fails is reported by name, with how it failed (its exit
// status, signal or spawn error) and its command line, never just as a failed
// check: `cargo run --quiet` prints nothing of its own when the program fails.
import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const generated = [
  'packages/api/generated',
  'docs/reference',
  'apps/plugin/runtime/src/generated',
  // tauri-specta's output: the UI's command bindings and the commands permission.
  'apps/editor/src/core/backend/generated',
  'apps/editor/src-tauri/permissions/generated',
]

/**
 * Each generator, run with `--out <temp>` so it writes into the copy. Spawned
 * without a shell on every OS: Node finds `cargo` (`cargo.exe`) on PATH itself,
 * and a shell would split `process.execPath` at its spaces (`C:\Program Files\nodejs\node.exe`).
 */
const generators = [
  {
    name: 'the Lua API outputs (packages/api)',
    command: [process.execPath, '--experimental-strip-types', 'packages/api/scripts/generate.ts'],
  },
  {
    name: "the editor's command bindings (src-tauri's `bindings` example)",
    command: [
      'cargo',
      'run',
      '--quiet',
      '--manifest-path',
      'apps/editor/src-tauri/Cargo.toml',
      '--example',
      'bindings',
      '--',
    ],
  },
]

/**
 * Why a `spawnSync` [result] isn't a success, or null if it is. On Windows a
 * crash's exit status is an NTSTATUS, which only reads in hex: 0xC0000139 is
 * STATUS_ENTRYPOINT_NOT_FOUND (a DLL lacks a function the program imports),
 * 0xC0000135 STATUS_DLL_NOT_FOUND, 0xC0000005 an access violation.
 */
function failure(result) {
  if (result.error) return `it couldn't be started (${result.error.message})`
  if (result.signal) return `it was killed by ${result.signal}`
  if (result.status === 0) return null
  if (result.status === null) return 'it exited without a status'
  const hex = result.status > 0xffff ? ` (0x${result.status.toString(16).toUpperCase()})` : ''
  return `it exited with status ${result.status}${hex}`
}

/** Every file under [dir], as paths relative to it with forward slashes. */
function files(dir, prefix = '') {
  if (!existsSync(dir)) return []
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? files(path.join(dir, entry.name), `${prefix}${entry.name}/`)
      : [`${prefix}${entry.name}`],
  )
}

const temp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-generated-'))
try {
  process.exitCode = check()
} finally {
  rmSync(temp, { recursive: true, force: true })
}

/** Generates into [temp] and compares; the exit code. */
function check() {
  for (const dir of generated) {
    const from = path.join(root, dir)
    if (existsSync(from)) cpSync(from, path.join(temp, dir), { recursive: true })
  }

  for (const { name, command } of generators) {
    const [program, ...args] = [...command, '--out', temp]
    const why = failure(spawnSync(program, args, { cwd: root, stdio: 'inherit' }))
    if (why) {
      console.error(
        `Generating ${name} failed, so nothing was compared: ${why}.\n` +
          `  command: ${[program, ...args].join(' ')}`,
      )
      return 1
    }
  }

  const stale = []
  for (const dir of generated) {
    const ours = new Set(files(path.join(root, dir)))
    const theirs = new Set(files(path.join(temp, dir)))
    for (const file of new Set([...ours, ...theirs])) {
      const [mine, fresh] = [path.join(root, dir, file), path.join(temp, dir, file)]
      if (!theirs.has(file)) stale.push(`  no longer generated: ${dir}/${file}`)
      else if (!ours.has(file)) stale.push(`  missing:             ${dir}/${file}`)
      else if (!readFileSync(mine).equals(readFileSync(fresh)))
        stale.push(`  changed:             ${dir}/${file}`)
    }
  }
  if (stale.length) {
    console.error(
      'Generated files are out of date. Run `pnpm generate` and commit the result:\n' +
        stale.sort().join('\n'),
    )
    return 1
  }
  console.log('Generated files are up to date.')
  return 0
}
