/**
 * The files slice where it goes wrong or does less than everything: a
 * backend that fails, a copy of nothing, documents a rename mustn't touch.
 * The happy paths (renames followed through references, deletes, copies and
 * their undo) are workspace.test.ts's and refactors.test.ts's.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import type { RecipeFile } from '@/core/format'
import { openExampleWorkspace, settle } from '@/testing/workspace'
import { modelOf } from './documents'
import type { Workspace } from './workspace'

const TOWER = 'centities/tower/centity.json'
const SWORD = 'recipes/ruby_sword.json'
const DUST = 'recipes/ruby_dust.json'

let backend: MemoryBackend
let ws: () => Workspace

beforeEach(async () => {
  vi.useFakeTimers()
  ;({ backend, ws } = await openExampleWorkspace())
})
afterEach(() => vi.useRealTimers())

const failing = () => Promise.reject(new Error('disk full'))

describe('creating files', () => {
  it('writes a file, lists it and hot-reloads it', async () => {
    const reloaded = vi.fn()
    ws().setRunHooks({ bridgeConnected: () => true, reloaded })
    backend.testConnect()
    await ws().createFile('modules/greeter/extra.lua', 'return 1\n')
    expect(backend.testFiles()['modules/greeter/extra.lua']).toBe('return 1\n')
    expect(ws().files).toContain('modules/greeter/extra.lua')
    await settle()
    expect(reloaded).toHaveBeenCalledWith(['modules/greeter/extra.lua'], expect.anything())
  })

  it('creates a resource from its template and opens it', async () => {
    await ws().createResource('dialog', 'farewell')
    expect(Object.keys(backend.testFiles())).toContain('dialogs/farewell/dialog.json')
    expect(ws().tabs.map((it) => it.path)).toContain('dialogs/farewell/dialog.json')
    // Known to validation at once, before the watcher says so.
    expect(ws().diskTexts['dialogs/farewell/dialog.json']).toBeDefined()
  })
})

describe('copying', () => {
  it('copies nothing that is not there', async () => {
    const before = Object.keys(backend.testFiles())
    expect(await ws().copyPath('centities/nowhere', 'centities/elsewhere')).toBe(false)
    expect(Object.keys(backend.testFiles())).toEqual(before)
  })

  it('says why a copy failed, keeping what it wrote listed', async () => {
    let writes = 0
    const write = backend.writeText.bind(backend)
    vi.spyOn(backend, 'writeText').mockImplementation((path, text) =>
      ++writes > 1 ? failing() : write(path, text),
    )
    expect(await ws().copyPath('centities/tower', 'centities/keep')).toBe(false)
    expect(ws().notice).toMatchObject({ kind: 'error' })
    expect(ws().notice?.text).toMatch(/^Couldn't copy centities\/tower: disk full/)
    expect(ws().files.filter((it) => it.startsWith('centities/keep/'))).toHaveLength(1)
  })
})

describe('writing a picture', () => {
  it('adds a new file to the list in order', async () => {
    const path = 'resource_packs/ui/textures/gui/aaa.png'
    expect(await ws().writeBinary(path, new Uint8Array([1, 2, 3]))).toBe(true)
    const files = ws().files
    expect(files).toContain(path)
    expect(files).toEqual([...files].sort())
    expect(ws().fileStamps[path]).toBeDefined()
  })

  it("says why it couldn't, and lists nothing", async () => {
    vi.spyOn(backend, 'writeBytes').mockImplementation(failing)
    const path = 'resource_packs/ui/textures/gui/aaa.png'
    expect(await ws().writeBinary(path, new Uint8Array([1]))).toBe(false)
    expect(ws().notice?.text).toBe(`Couldn't write ${path}: disk full`)
    expect(ws().files).not.toContain(path)
  })
})

describe('a rename or delete the disk refuses', () => {
  it('leaves documents and tabs where they were, and says why', async () => {
    await ws().openFile(TOWER)
    vi.spyOn(backend, 'renamePath').mockImplementation(failing)
    await ws().renamePath('centities/tower', 'centities/keep')
    expect(ws().notice?.text).toBe("Couldn't rename centities/tower: disk full")
    expect(ws().tabs.map((it) => it.path)).toEqual([TOWER])
    expect(ws().docs[TOWER]).toBeDefined()
    expect(ws().files).toContain(TOWER)
  })

  it('keeps a document open when its delete failed', async () => {
    await ws().openFile(TOWER)
    vi.spyOn(backend, 'deletePath').mockImplementation(failing)
    await ws().deletePath('centities/tower')
    expect(ws().notice?.text).toBe("Couldn't delete centities/tower: disk full")
    expect(ws().docs[TOWER]).toBeDefined()
    expect(backend.testFiles()[TOWER]).toBeDefined()
  })
})

describe('following a rename in other files', () => {
  it('saves a clean open document it edits, and leaves unsaved text alone', async () => {
    await ws().openFile(SWORD)
    await ws().openFile(DUST)
    // Unsaved text edited as JSON: validation will point at it, the rename doesn't guess.
    ws().setRaw(DUST, true)
    ws().setText(DUST, backend.testFiles()[DUST]!.replace('{', '{ "broken": '))
    await ws().renameReferences({ type: 'resource', kind: 'item', id: 'ruby' }, 'gem')
    expect(ws().docs[SWORD]!.dirty).toBe(false)
    expect(modelOf<RecipeFile>(ws().docs[SWORD])!.key!.R).toEqual({ item: 'gem' })
    expect(JSON.parse(backend.testFiles()[SWORD]!).key.R).toEqual({ item: 'gem' })
    expect(ws().docs[DUST]!.dirty).toBe(true)
    expect(backend.testFiles()[DUST]).toContain('"item": "ruby"')
  })

  it('says which file it could not update and goes on with the rest', async () => {
    const write = backend.writeText.bind(backend)
    vi.spyOn(backend, 'writeText').mockImplementation((path, text) =>
      path === SWORD ? failing() : write(path, text),
    )
    await ws().renameReferences({ type: 'resource', kind: 'item', id: 'ruby' }, 'gem')
    expect(ws().notice?.text).toMatch(/^Couldn't update /)
    expect(backend.testFiles()[SWORD]).toContain('"item": "ruby"')
    expect(JSON.parse(backend.testFiles()['recipes/ruby.json']!).result).toEqual({ item: 'gem' })
  })
})
