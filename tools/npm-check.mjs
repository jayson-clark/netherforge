#!/usr/bin/env node
/**
 * Tries the built `netherforge` command (`apps/cli/dist/netherforge.mjs`) as a
 * user would before it's released: it says the version in VERSION, checks the
 * showcase clean, and sets up a fresh copy of it (no `.netherforge/`) with
 * everything the editor would write, itself included. The release's `cli`
 * job and npm.yml run it.
 */
import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const repo = path.resolve(import.meta.dirname, '..')
const script = path.join(repo, 'apps/cli/dist/netherforge.mjs')
const version = readFileSync(path.join(repo, 'VERSION'), 'utf8').trim()

function netherforge(...args) {
  const ran = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' })
  if (ran.status !== 0) {
    console.error(`netherforge ${args.join(' ')} exited ${ran.status}\n${ran.stdout}${ran.stderr}`)
    process.exit(1)
  }
  return ran.stdout.trim()
}

const said = netherforge('--version')
if (said !== version) {
  console.error(`netherforge --version says ${said}, VERSION ${version}`)
  process.exit(1)
}
netherforge('check', path.join(repo, 'examples/showcase'))

const tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-npm-check-'))
try {
  const example = path.join(repo, 'examples/showcase')
  const project = path.join(tmp, 'showcase')
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  cpSync(path.join(repo, 'examples/library'), path.join(tmp, 'library'), { recursive: true })
  console.log(netherforge('setup', project))
  for (const file of [
    '.netherforge/schema/centity.schema.json',
    '.netherforge/luals/nf.lua',
    '.netherforge/luals/project.lua',
    '.netherforge/docs/README.md',
    '.netherforge/bin/netherforge.mjs',
  ]) {
    if (!existsSync(path.join(project, file))) {
      console.error(`netherforge setup didn't write ${file}`)
      process.exit(1)
    }
  }
} finally {
  rmSync(tmp, { recursive: true, force: true })
}
console.log(`netherforge ${version}: --version, check and setup work.`)
