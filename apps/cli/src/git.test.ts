import { execFileSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { checkoutDir, fetchGit } from './git.ts'
import { run } from './main.ts'
import { hashPackage, listFiles } from './project.ts'

const examples = path.resolve(import.meta.dirname, '../../../examples')

/** examples/library published to a bare repository (`library.git`) from a work tree (`work`), made once and copied for each test. */
let template: string
let tmp: string
let project: string
let work: string
let url: string
let cache: string
let env: NodeJS.ProcessEnv

/** Runs git in [cwd] as a test author, nothing of the user's config. */
const git = (cwd: string, ...args: string[]) =>
  execFileSync('git', ['-c', 'user.name=Test', '-c', 'user.email=test@example.com', ...args], {
    cwd,
    encoding: 'utf8',
  }).trim()

/** Commits everything in the work tree and pushes [refs] to the bare repository. */
const publish = (message: string, ...refs: string[]) => {
  git(work, 'add', '-A')
  git(work, 'commit', '--quiet', '-m', message)
  for (const ref of refs) git(work, 'push', '--quiet', '--force', 'origin', ref)
  return git(work, 'rev-parse', 'HEAD')
}

const manifestPath = () => path.join(project, 'netherforge.json')
const lockText = () => readFileSync(path.join(project, 'netherforge.lock'), 'utf8')

/** The showcase's manifest, depending on the library through git at [rev] instead of by path. */
const dependOnGit = (rev?: string) => {
  const json = JSON.parse(readFileSync(manifestPath(), 'utf8'))
  json.dependencies = { library: { git: url, ...(rev ? { rev } : {}) } }
  writeFileSync(manifestPath(), JSON.stringify(json))
}

// examples/library, published to a bare repository the project fetches from by file:// URL. Made once: each git
// process is slow to start on Windows, and a test's own copy needs none.
beforeAll(() => {
  template = mkdtempSync(path.join(os.tmpdir(), 'netherforge-git-template-'))
  const bare = path.join(template, 'library.git')
  git(template, 'init', '--quiet', '--bare', bare)
  work = path.join(template, 'work')
  git(template, 'init', '--quiet', '--initial-branch=main', work)
  // Beside the work tree wherever it's copied, so each test's copy pushes to its own.
  git(work, 'remote', 'add', 'origin', '../library.git')
  cpSync(path.join(examples, 'library'), work, {
    recursive: true,
    filter: (source) => !source.includes(`${path.sep}.netherforge`),
  })
  publish('library', 'main')
  git(work, 'tag', 'v1')
  git(work, 'push', '--quiet', 'origin', 'v1')
  git(bare, 'symbolic-ref', 'HEAD', 'refs/heads/main')
}, 60_000)
afterAll(() => rmSync(template, { recursive: true, force: true }))

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-git-'))
  cpSync(template, tmp, { recursive: true })
  work = path.join(tmp, 'work')
  url = pathToFileURL(path.join(tmp, 'library.git')).href
  project = path.join(tmp, 'project')
  cpSync(path.join(examples, 'showcase'), project, {
    recursive: true,
    filter: (source) => !source.includes(`${path.sep}.netherforge`),
  })
  cache = path.join(tmp, 'data', 'packages')
  env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
})
// The repositories and the project are changed by the tests, so each test has its own copy, gone when it ends.
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

describe('git packages', () => {
  it('are fetched into the cache, locked at their commit and hashed', async () => {
    dependOnGit('v1')
    expect(await run(['lock', project], env)).toMatchObject({
      code: 0,
      out: ['netherforge.lock: written'],
    })
    const commit = git(work, 'rev-parse', 'v1')
    const lock = JSON.parse(lockText())
    expect(lock.packages.library.source).toEqual({ type: 'git', url, rev: 'v1', commit })
    const checkout = checkoutDir(cache, commit)
    expect(lock.packages.library.hash).toBe(hashPackage(checkout))
    // What was committed is what's there.
    expect(readFileSync(path.join(checkout, 'netherforge.json'), 'utf8')).toBe(
      readFileSync(path.join(work, 'netherforge.json'), 'utf8'),
    )
    expect(await run(['check', project], env)).toMatchObject({ code: 0, out: ['No problems.'] })
  })

  it('stay at the pinned commit until the lock is updated', async () => {
    dependOnGit('main')
    await run(['lock', project], env)
    const first = JSON.parse(lockText()).packages.library.source.commit
    writeFileSync(path.join(work, 'README.md'), 'news\n')
    const second = publish('news', 'main')
    expect((await run(['lock', project], env)).out).toEqual(['netherforge.lock: up to date'])
    expect(await run(['check', project], env)).toMatchObject({ code: 0, out: ['No problems.'] })
    expect((await run(['lock', project, '--update'], env)).out).toEqual([
      'netherforge.lock: written',
    ])
    expect(JSON.parse(lockText()).packages.library.source.commit).toBe(second)
    expect(second).not.toBe(first)
  })

  it('resolve the default branch when there is no rev, and are found again from the cache alone', async () => {
    dependOnGit()
    await run(['lock', project], env)
    expect(JSON.parse(lockText()).packages.library.source.commit).toBe(
      git(work, 'rev-parse', 'main'),
    )
    // The repository goes away: the pinned checkout is all a check needs.
    rmSync(path.join(tmp, 'library.git'), { recursive: true, force: true })
    expect(await run(['check', project], env)).toMatchObject({ code: 0, out: ['No problems.'] })
  })

  it("aren't loaded once their checkout is changed", async () => {
    dependOnGit('v1')
    await run(['lock', project], env)
    const commit = JSON.parse(lockText()).packages.library.source.commit
    writeFileSync(path.join(checkoutDir(cache, commit), 'modules/phrases/init.lua'), 'os.exit()\n')
    const outcome = await run(['check', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.out.join('\n')).toContain('[package.hash]')
    // And the lock isn't rewritten to match.
    expect((await run(['lock', project], env)).code).toBe(1)
  })

  it('report a rev the repository lacks', async () => {
    dependOnGit('nope')
    const outcome = await run(['check', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.out.join('\n')).toMatch(/\[package\.git\]: Couldn't fetch .* at nope/)
  })

  it('are bundled like any package', async () => {
    dependOnGit('v1')
    await run(['lock', project], env)
    const out = path.join(tmp, 'bundle')
    expect(await run(['build', project, '--out', out], env)).toMatchObject({ code: 0 })
    const bundle = JSON.parse(readFileSync(path.join(out, 'netherforge-bundle.json'), 'utf8'))
    expect(bundle.packages.library.hash).toBe(JSON.parse(lockText()).packages.library.hash)
  })
})

describe('fetchGit', () => {
  it('writes only the regular files of the commit: no link, no hook, no filter of the repository', () => {
    // Windows makes links only with Developer Mode on: the rest holds there regardless.
    const links = process.platform !== 'win32'
    if (links) symlinkSync('/etc/passwd', path.join(work, 'items/gem/linked.json'))
    writeFileSync(path.join(work, '.gitattributes'), '* filter=evil\n')
    mkdirSync(path.join(work, 'hooks'), { recursive: true })
    writeFileSync(path.join(work, 'hooks/post-checkout'), '#!/bin/sh\ntouch "$HOME/pwned"\n')
    const commit = publish('more', 'main')
    expect(fetchGit(cache, { url, rev: 'main', commit: null })).toBe(commit)
    const files = listFiles(checkoutDir(cache, commit))
    expect(files).not.toContain('items/gem/linked.json')
    expect(files).toContain('.gitattributes')
    expect(readFileSync(path.join(checkoutDir(cache, commit), 'netherforge.json'), 'utf8')).toBe(
      readFileSync(path.join(work, 'netherforge.json'), 'utf8'),
    )
    // The database is ours, bare and without hooks.
    const db = path.join(cache, 'git', 'db')
    expect(existsSync(db)).toBe(true)
  })

  it('fetches a pinned commit once, then needs nothing but the cache', () => {
    const commit = git(work, 'rev-parse', 'v1')
    expect(fetchGit(cache, { url, rev: 'v1', commit })).toBe(commit)
    expect(fetchGit(cache, { url: 'file:///nowhere', rev: null, commit })).toBe(commit)
    expect(() => fetchGit(cache, { url, rev: null, commit: 'f'.repeat(40) })).toThrow(
      /has no commit/,
    )
  })
})
