import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryBackend, type FileContents } from '@/testing/backend/memory'
import { LOCK_FILE, MANIFEST_FILE, type ItemFile } from '@/core/format'
import { EXAMPLE_ROOT, exampleProject, libraryProject } from '@/testing/fixtures'
import { exampleWorkspace, settle } from '@/testing/workspace'
import { directDependencies, fileUrl, filesIn, packageText } from './packages'
import { readOnlyReason } from './project'
import { VALIDATE_DELAY_MS } from './validation'
import { createWorkspace } from './workspace'

/** Past the store's validation delay: a change on disk has been read and validated. */
const revalidated = () => settle(VALIDATE_DELAY_MS)

/**
 * Until [check] holds, moving the fake clock on as it polls. Reading a package hashes its
 * files with WebCrypto, which answers off the JS thread rather than on the fake clock, so
 * what follows a package being read is waited for as state.
 */
const eventually = (check: () => void) => vi.waitFor(check, { timeout: 5000, interval: 20 })

const LOCK = exampleProject[LOCK_FILE] as string

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

async function open(project: Record<string, FileContents> = exampleProject) {
  const { backend, workspace, ws } = exampleWorkspace({ project })
  const writes = vi.spyOn(backend, 'writeText')
  await ws().openProject(EXAMPLE_ROOT)
  await settle()
  return { backend, store: workspace, ws, writes }
}

describe('packages', () => {
  it('resolves the dependencies and hashes each package as the committed lock does', async () => {
    const { ws, writes } = await open()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.packages?.library).toMatchObject({
      location: '../library',
      version: '0.1.0',
      exports: { items: ['gem'], modules: ['greetings'] },
    })
    expect(ws().outline?.packages?.library?.resources.item).toEqual(['gem'])
    // The webview's hash of examples/library is the one format and the JVM wrote into the lock.
    const locked = (JSON.parse(LOCK) as { packages: Record<string, { hash: string }> }).packages
    expect(ws().packages.folders['../library']?.hash).toBe(locked.library!.hash)
    expect(ws().outline?.lock).toBe(LOCK)
    // Current: nothing to write.
    expect(writes.mock.calls.filter(([path]) => path === LOCK_FILE)).toEqual([])
  })

  it('rewrites a lock that says something else', async () => {
    const stale = LOCK.replace('"version": "0.1.0"', '"version": "0.0.9"')
    expect(stale).not.toBe(LOCK)
    const { backend, ws } = await open({ ...exampleProject, [LOCK_FILE]: stale })
    expect(backend.testFiles()[LOCK_FILE]).toBe(LOCK)
    await eventually(() => expect(ws().problems).toEqual([]))
  })

  it('writes a missing lock, and removes one once nothing is depended on', async () => {
    const unlocked = { ...exampleProject }
    delete unlocked[LOCK_FILE]
    const { backend, ws } = await open(unlocked)
    expect(backend.testFiles()[LOCK_FILE]).toBe(LOCK)

    const manifest = JSON.parse(exampleProject[MANIFEST_FILE] as string) as Record<string, unknown>
    delete manifest.dependencies
    // As git or another editor would change it.
    await backend.writeText(MANIFEST_FILE, JSON.stringify(manifest, null, 2))
    await eventually(() => expect(backend.testFiles()[LOCK_FILE]).toBeUndefined())
    await eventually(() => expect(ws().outline?.packages ?? {}).toEqual({}))
  })

  it('reports a dependency that points nowhere, without a lock to write', async () => {
    const backend = new MemoryBackend({ projects: { [EXAMPLE_ROOT]: exampleProject } })
    const store = createWorkspace(backend)
    await store.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(store.getState().problems.map((it) => it.code)).toContain('package.missing')
    expect(backend.testFiles()[LOCK_FILE]).toBe(LOCK)
  })

  it("copies a package's resource in, still naming the package's own things", async () => {
    const { backend, ws } = await open()
    expect(await ws().copyFromPackage('library', 'item', 'gem', 'gem')).toBe(true)
    const files = backend.testFiles()
    const item = JSON.parse(files['items/gem/item.json'] as string) as ItemFile
    expect(item.itemModel).toBe('library:gems/gem')
    expect(files['items/gem/script.lua']).toContain('The gem hums.')
    await settle()
    expect(ws().files).toContain('items/gem/item.json')
    // The copy names a pack the library doesn't export: validation says so, at the copy.
    await revalidated()
    expect(ws().problems.map((it) => [it.file, it.code])).toEqual([
      ['items/gem/item.json', 'reference.not-exported'],
    ])
    // Taken: refused, with a notice.
    expect(await ws().copyFromPackage('library', 'item', 'gem', 'gem')).toBe(false)
    expect(ws().notice?.text).toContain('already exists')
  })

  it("opens a package's file read-only, from the package's folder", async () => {
    const { backend, ws } = await open()
    const path = 'library:items/gem/item.json'
    expect(readOnlyReason(ws().readOnly, path)).toContain('"library"')
    expect(readOnlyReason(ws().readOnly, 'items/ruby/item.json')).toBeNull()
    await ws().openFile(path)
    expect(ws().tabs.map((it) => it.path)).toEqual([path])
    expect(ws().docs[path]?.savedText).toBe(libraryProject['items/gem/item.json'])
    // Nothing writes it.
    ws().edit<ItemFile>(path, (draft) => {
      draft.name = '<red>Mine'
    })
    expect(await ws().save(path)).toBe(false)
    expect(ws().notice?.text).toContain('read-only')
    expect(Object.keys(backend.testFiles()).filter((it) => it.includes(':'))).toEqual([])
  })

  it("reads a package's files for previews: its JSON and modules' Lua as text, its pictures by URL", async () => {
    const { backend, ws } = await open()
    expect(directDependencies(ws())).toEqual(['library'])
    expect(packageText(ws(), 'library:items/gem/item.json')).toBe(
      libraryProject['items/gem/item.json'],
    )
    expect(packageText(ws(), 'items/ruby/item.json')).toBeUndefined()
    expect(packageText(ws(), 'nobody:items/x/item.json')).toBeUndefined()
    expect(ws().packages.folders['../library']?.files['modules/greetings/init.lua']).toBe(
      libraryProject['modules/greetings/init.lua'],
    )
    const texture = 'library:resource_packs/gems/textures/item/gem.png'
    const url = fileUrl(backend, ws().outline, texture)
    expect(url).toBe(
      backend.packageFileUrl('../library', 'resource_packs/gems/textures/item/gem.png'),
    )
    expect(url).toMatch(/^data:image\/png;base64,/)
    expect(fileUrl(backend, ws().outline, 'nobody:resource_packs/x.png')).toBe('')
    expect(fileUrl(backend, ws().outline, 'resource_packs/ui/pack.json')).toBe(
      backend.projectFileUrl('resource_packs/ui/pack.json'),
    )
  })

  it("lists a package's files by package path, for a resource of it opened read-only", async () => {
    const { ws } = await open()
    await eventually(() =>
      expect(filesIn(ws(), 'library')).toContain('library:items/gem/item.json'),
    )
    const files = filesIn(ws(), 'library')
    expect(files).toContain('library:resource_packs/gems/textures/item/gem.png')
    expect(files.every((it) => it.startsWith('library:'))).toBe(true)
    // The same list while the folder is the same read: a selector can hold it.
    expect(filesIn(ws(), 'library')).toBe(files)
    expect(filesIn(ws(), null)).toBe(ws().files)
    expect(filesIn(ws(), 'nobody')).toEqual([])
    // A package's module folder opens on its entry, in the package.
    await ws().openFile('library:modules/greetings')
    expect(ws().tabs.map((it) => it.path)).toEqual(['library:modules/greetings/init.lua'])
  })
})

describe('git packages', () => {
  const url = 'https://example.com/library.git'
  const first = 'a'.repeat(40)
  const second = 'b'.repeat(40)
  const newer = {
    ...libraryProject,
    'modules/greetings/init.lua': 'return { hello = function() return "hi" end }\n',
  }

  /** basic depending on the library through git at v1, with no lock yet; v1 is [first]. */
  async function openFromGit() {
    const manifest = JSON.parse(exampleProject[MANIFEST_FILE] as string) as Record<string, unknown>
    manifest.dependencies = { library: { git: url, rev: 'v1' } }
    const project: Record<string, FileContents> = {
      ...exampleProject,
      [MANIFEST_FILE]: JSON.stringify(manifest, null, 2),
    }
    delete project[LOCK_FILE]
    const repo = { refs: { v1: first }, commits: { [first]: libraryProject, [second]: newer } }
    const backend = new MemoryBackend({
      projects: { [EXAMPLE_ROOT]: project },
      gitRepos: { [url]: repo },
    })
    const store = createWorkspace(backend)
    const ws = () => store.getState()
    await ws().openProject(EXAMPLE_ROOT)
    // Fetched, locked, read again at the locked commit and validated.
    await eventually(() => {
      expect(backend.testFiles()[LOCK_FILE]).toBeDefined()
      expect(ws().outline?.packages?.library?.location).toBe(`git:${first}`)
      expect(ws().problems).toEqual([])
    })
    return { backend, repo, ws }
  }

  const lockedSource = (backend: MemoryBackend) =>
    (
      JSON.parse(backend.testFiles()[LOCK_FILE]!) as {
        packages: Record<string, { source: Record<string, unknown> }>
      }
    ).packages.library!.source

  it('are fetched into the cache, read at their commit and locked to it', async () => {
    const { backend, ws } = await openFromGit()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.packages?.library).toMatchObject({
      location: `git:${first}`,
      origin: { type: 'git', url, rev: 'v1', commit: first },
    })
    expect(lockedSource(backend)).toEqual({ type: 'git', url, rev: 'v1', commit: first })
    expect(packageText(ws(), 'library:items/gem/item.json')).toBe(
      libraryProject['items/gem/item.json'],
    )
    // Fetched once: after that, the lock pins it and the cache has it.
    expect(backend.gitFetchLog).toEqual([{ url, rev: 'v1', commit: null }])
  })

  it('stay at the pinned commit when the rev moves, until updated', async () => {
    const { backend, repo, ws } = await openFromGit()
    repo.refs.v1 = second
    await ws().refreshPackages()
    await revalidated()
    expect(ws().outline?.packages?.library?.location).toBe(`git:${first}`)
    expect(await ws().updatePackages()).toBe(true)
    await eventually(() => {
      expect(lockedSource(backend)).toMatchObject({ commit: second })
      expect(ws().outline?.packages?.library?.location).toBe(`git:${second}`)
    })
    expect(ws().problems).toEqual([])
  })

  it("report a repository that can't be fetched, and lock nothing", async () => {
    const { backend, ws } = await openFromGit()
    const manifest = JSON.parse(backend.testFiles()[MANIFEST_FILE]!) as Record<string, unknown>
    manifest.dependencies = { library: { git: 'https://example.com/gone.git' } }
    await backend.writeText(MANIFEST_FILE, JSON.stringify(manifest, null, 2))
    await eventually(() =>
      expect(ws().problems.find((it) => it.code === 'package.git')?.message).toContain('gone.git'),
    )
    expect(lockedSource(backend)).toMatchObject({ commit: first })
    expect(await ws().updatePackages()).toBe(false)
  })
})
