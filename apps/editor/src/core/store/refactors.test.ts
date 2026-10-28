import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import type { CentityFile, MenuFile, ResourcePackFile, RecipeFile } from '@/core/format'
import { openExampleWorkspace, settle } from '@/testing/workspace'
import { modelOf } from './documents'
import { tabIdOf } from './tabs'
import type { WorkspaceStore } from './workspace'

const TOWER = 'centities/tower/centity.json'
const RUBY = 'items/ruby/item.json'
const GEM = 'items/gem/item.json'
const DUST = 'recipes/ruby_dust.json'
const SWORD = 'recipes/ruby_sword.json'
const MENU = 'menus/shop/menu.json'

let backend: MemoryBackend
let store: WorkspaceStore
const ws = () => store.getState()

beforeEach(async () => {
  vi.useFakeTimers()
  ;({ backend, workspace: store } = await openExampleWorkspace())
  await settle()
})
afterEach(() => vi.useRealTimers())

/** Every project file's bytes (base64), text and binary alike. */
async function disk(): Promise<Record<string, string | null>> {
  const files = await backend.listFiles()
  return Object.fromEntries(files.map(({ path }) => [path, backend.testBytes(path)]))
}

const json = <T>(path: string) => JSON.parse(backend.testFiles()[path]!) as T
const model = <T>(path: string) => modelOf<T>(ws().docs[path])

describe('undo for refactors', () => {
  it('undoes and redoes a rename as one step: files, references, open documents, tabs and views', async () => {
    // The item open with a selection; one recipe open and clean, one open with unsaved edits.
    await ws().openFile(RUBY)
    ws().updateView(RUBY, { zoom: 2 })
    await ws().openFile(SWORD)
    await ws().openFile(DUST)
    ws().edit<RecipeFile>(DUST, (draft) => {
      draft.experience = 1
    })
    const before = await disk()

    await ws().renamePath('items/ruby', 'items/gem')
    await settle()
    const after = await disk()
    expect(json<RecipeFile>('recipes/ruby.json').result).toEqual({ item: 'gem' })
    expect(json<MenuFile>(MENU).slots?.['13']?.item).toMatchObject({ item: 'gem' })
    expect(ws().refactors.done.map((it) => it.label)).toEqual(['Rename items/ruby to items/gem'])

    expect(await ws().undoRefactor()).toBe(true)
    await settle()
    // Every file as it was, the ones no tab had open included.
    expect(await disk()).toEqual(before)
    expect(ws().files).toContain(RUBY)
    expect(ws().files.some((it) => it.startsWith('items/gem'))).toBe(false)
    // The documents, tabs and view moved back; the clean recipe is clean, the dirty one keeps its own edit.
    expect(ws().tabs.map((it) => it.path)).toEqual([RUBY, SWORD, DUST])
    expect(ws().activeTab).toBe(tabIdOf('recipe', DUST))
    expect(ws().views[RUBY]?.state).toEqual({ zoom: 2 })
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'ruby' })
    expect(ws().docs[SWORD]!.dirty).toBe(false)
    expect(model<RecipeFile>(DUST)).toMatchObject({ ingredient: { item: 'ruby' }, experience: 1 })
    expect(ws().docs[DUST]!.dirty).toBe(true)
    // The watcher's echo of the undo is our own: nothing conflicts or reloads.
    expect(Object.values(ws().docs).filter((it) => it.conflict)).toEqual([])
    await ws().validateNow()
    expect(ws().problems).toEqual([])

    expect(await ws().redoRefactor()).toBe(true)
    await settle()
    expect(await disk()).toEqual(after)
    expect(ws().tabs.map((it) => it.path)).toEqual([GEM, SWORD, DUST])
    expect(ws().views[GEM]?.state).toEqual({ zoom: 2 })
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'gem' })
    expect(ws().docs[SWORD]!.dirty).toBe(false)
    expect(model<RecipeFile>(DUST)).toMatchObject({ ingredient: { item: 'gem' }, experience: 1 })
    expect(Object.values(ws().docs).filter((it) => it.conflict)).toEqual([])
  })

  it("is one step in each document's undo, after the edits made before it and before the edits made after", async () => {
    await ws().openFile(SWORD)
    await ws().openFile(TOWER)
    ws().edit<CentityFile>(TOWER, (draft) => {
      draft.name = 'Before'
    })
    await ws().renamePath('items/ruby', 'items/gem')
    ws().edit<RecipeFile>(SWORD, (draft) => {
      draft.group = 'after'
    })

    // In the recipe: its own later edit, then the rename (its reference step is the rename's).
    await ws().undo(SWORD)
    expect(model<RecipeFile>(SWORD)).toMatchObject({ key: { R: { item: 'gem' } } })
    expect(model<RecipeFile>(SWORD)!.group).toBeUndefined()
    expect(ws().files).toContain(GEM)
    await ws().undo(SWORD)
    expect(ws().files).toContain(RUBY)
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'ruby' })
    // In the centity, which the rename never touched: its edit was older, so it's next.
    expect(model<CentityFile>(TOWER)!.name).toBe('Before')
    await ws().undo(TOWER)
    expect(model<CentityFile>(TOWER)!.name).not.toBe('Before')

    // Redo in the order undone, newest undo first.
    await ws().redo(TOWER)
    expect(model<CentityFile>(TOWER)!.name).toBe('Before')
    await ws().redo(TOWER)
    expect(ws().files).toContain(GEM)
    await ws().redo(SWORD)
    expect(model<RecipeFile>(SWORD)!.group).toBe('after')
  })

  it('undoes a refactor from a document it never touched when the refactor came later', async () => {
    await ws().openFile(TOWER)
    ws().edit<CentityFile>(TOWER, (draft) => {
      draft.name = 'Mine'
    })
    await ws().renamePath('dialogs/welcome', 'dialogs/hello')
    await ws().undo(TOWER)
    expect(ws().files).toContain('dialogs/welcome/dialog.json')
    expect(model<CentityFile>(TOWER)!.name).toBe('Mine')
    await ws().undo(TOWER)
    expect(model<CentityFile>(TOWER)!.name).not.toBe('Mine')
  })

  it('undoes a delete: the files, binary ones included, and what was open there, unsaved edits and all', async () => {
    const pack = 'resource_packs/ui/pack.json'
    await ws().openFile(TOWER)
    await ws().openFile(pack)
    ws().edit<ResourcePackFile>(pack, (draft) => {
      draft.description = 'Unsaved'
    })
    ws().select(pack, [{ kind: 'skins', key: 'shop' }])
    await ws().openFile(SWORD)
    ws().activate(tabIdOf('resource_pack', pack))
    const before = await disk()

    await ws().deletePath('resource_packs/ui')
    await settle()
    expect(ws().files.some((it) => it.startsWith('resource_packs/ui/'))).toBe(false)
    expect(ws().tabs.map((it) => it.path)).toEqual([TOWER, SWORD])
    expect(ws().docs[pack]).toBeUndefined()

    await ws().undoRefactor()
    await settle()
    expect(await disk()).toEqual(before)
    expect(backend.testBytes('resource_packs/ui/textures/gui/shop.png')).toBe(
      before['resource_packs/ui/textures/gui/shop.png'],
    )
    // The tab where it was, active again, with its unsaved edit and its selection.
    expect(ws().tabs.map((it) => it.path)).toEqual([TOWER, pack, SWORD])
    expect(ws().activeTab).toBe(tabIdOf('resource_pack', pack))
    expect(model<ResourcePackFile>(pack)!.description).toBe('Unsaved')
    expect(ws().docs[pack]!.dirty).toBe(true)
    expect(ws().views[pack]?.selection).toEqual([{ kind: 'skins', key: 'shop' }])

    await ws().redoRefactor()
    await settle()
    expect(ws().files.some((it) => it.startsWith('resource_packs/ui/'))).toBe(false)
    expect(ws().docs[pack]).toBeUndefined()
    // And back again, still unsaved.
    await ws().undoRefactor()
    expect(model<ResourcePackFile>(pack)!.description).toBe('Unsaved')
  })

  it('undoes a copy, closing what was opened in it, and redoes it', async () => {
    const before = await disk()
    expect(await ws().copyPath('resource_packs/ui', 'resource_packs/ui_copy')).toBe(true)
    await ws().openFile('resource_packs/ui_copy/pack.json')
    const after = await disk()

    await ws().undoRefactor()
    expect(await disk()).toEqual(before)
    expect(ws().tabs).toEqual([])

    await ws().redoRefactor()
    expect(await disk()).toEqual(after)
    expect(ws().tabs.map((it) => it.path)).toEqual(['resource_packs/ui_copy/pack.json'])
  })

  it("undoes copying a package's resource into the project", async () => {
    const before = await disk()
    expect(await ws().copyFromPackage('library', 'item', 'gem', 'gem')).toBe(true)
    const after = await disk()
    expect(after[GEM]).toBeDefined()
    expect(ws().refactors.done.map((it) => it.label)).toEqual([
      'Copy library:gem into the project as gem',
    ])
    await ws().undoRefactor()
    expect(await disk()).toEqual(before)
    await ws().redoRefactor()
    expect(await disk()).toEqual(after)
  })

  it('renames a pack entry and what refers to it as one step', async () => {
    const pack = 'resource_packs/ui/pack.json'
    await ws().openFile(pack)
    await ws().transact('Rename ui/coin to gold', async () => {
      ws().edit<ResourcePackFile>(pack, (draft) => {
        draft.glyphs!.gold = draft.glyphs!.coin!
        delete draft.glyphs!.coin
      })
      await ws().renameReferences(
        { type: 'resource_pack_entry', pack: 'ui', entry: 'glyph', key: 'coin' },
        'gold',
      )
    })
    expect(backend.testFiles()[MENU]).toContain('<glyph:ui/gold>')
    await ws().undo(pack)
    expect(backend.testFiles()[MENU]).toContain('<glyph:ui/coin>')
    expect(Object.keys(model<ResourcePackFile>(pack)!.glyphs!)).toContain('coin')
    expect(ws().docs[pack]!.dirty).toBe(false)
  })

  it('refuses to undo over a change on disk, and forgets the refactor', async () => {
    await ws().renamePath('items/ruby', 'items/gem')
    await settle()
    backend.testWrite('recipes/ruby.json', '{ "type": "smelting" }\n')
    await settle()
    const changed = await disk()

    expect(await ws().undoRefactor()).toBe(false)
    expect(ws().notice?.text).toContain('recipes/ruby.json changed since')
    expect(await disk()).toEqual(changed)
    expect(ws().refactors.done).toEqual([])

    // A file added inside what it would take away is a change too.
    await ws().renamePath('menus/shop', 'menus/store')
    backend.testWrite('menus/store/notes.md', 'mine')
    await settle()
    expect(await ws().undoRefactor()).toBe(false)
    expect(ws().notice?.text).toContain('menus/store/notes.md was added since')
    expect(backend.testFiles()['menus/store/notes.md']).toBe('mine')
  })

  it("keeps the refactor while a document it touched has later edits, and its step goes back to that document's own when it's forgotten", async () => {
    await ws().openFile(SWORD)
    await ws().renamePath('items/ruby', 'items/gem')
    ws().edit<RecipeFile>(SWORD, (draft) => {
      draft.group = 'after'
    })
    expect(await ws().undoRefactor()).toBe(false)
    expect(ws().notice?.text).toContain(`${SWORD} was edited since`)
    expect(ws().refactors.done).toHaveLength(1)
    // Undone there, the refactor is next.
    await ws().undo(SWORD)
    expect(await ws().undoRefactor()).toBe(true)
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'ruby' })

    // Redone, then the disk moves on: the refactor is forgotten, and the recipe's
    // reference step is an edit of its own again.
    await ws().redoRefactor()
    await settle()
    backend.testWrite(GEM, backend.testFiles()[GEM]!.replace('}', ', "name": "x" }'))
    await settle()
    expect(await ws().undoRefactor()).toBe(false)
    expect(ws().refactors.done).toEqual([])
    await ws().undo(SWORD)
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'ruby' })
    expect(ws().files).toContain(GEM)
  })

  it('undoes over a document closed and opened again since, which reloads from disk', async () => {
    await ws().openFile(SWORD)
    await ws().renamePath('items/ruby', 'items/gem')
    ws().closeTab(tabIdOf('recipe', SWORD))
    await ws().openFile(SWORD)
    expect(await ws().undoRefactor()).toBe(true)
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'ruby' })
    expect(ws().docs[SWORD]!.dirty).toBe(false)
    expect(await ws().redoRefactor()).toBe(true)
    expect(model<RecipeFile>(SWORD)!.key!.R).toEqual({ item: 'gem' })
  })

  it('a new refactor ends the redo stack; a failed one records nothing', async () => {
    await ws().renamePath('items/ruby', 'items/gem')
    await ws().undoRefactor()
    expect(ws().refactors.undone).toHaveLength(1)
    await ws().copyPath('menus/shop', 'menus/store')
    expect(ws().refactors.undone).toEqual([])
    expect(ws().refactors.done.map((it) => it.label)).toEqual(['Copy menus/shop to menus/store'])
    await ws().renamePath('nowhere', 'elsewhere')
    expect(ws().refactors.done).toHaveLength(1)
  })

  it('renames a file in a resource and back, following its reference in the resource', async () => {
    const script = 'centities/tower/script.lua'
    await ws().openFile(script)
    await ws().renamePath(script, 'centities/tower/main.lua')
    expect(backend.testFiles()[TOWER]).toContain('"file": "main.lua"')
    expect(ws().tabs.map((it) => it.path)).toEqual(['centities/tower/main.lua'])
    await ws().undoRefactor()
    expect(backend.testFiles()[TOWER]).toContain('"file": "script.lua"')
    expect(ws().tabs.map((it) => it.path)).toEqual([script])
  })
})
