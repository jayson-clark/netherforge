import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MEMORY_MCP_TOKEN, MemoryBackend } from '@/testing/backend/memory'
import { canonicalizeModel, type CentityFile, type RecipeFile } from '@/core/format'
import { EXAMPLE_ROOT, exampleFiles, exampleProject, exampleProjects } from '@/testing/fixtures'
import { exampleBackend, openExampleWorkspace, settle } from '@/testing/workspace'
import { modelOf, sameJson } from './documents'
import { readOnlyReason } from './project'
import { SETTINGS_PATH, SETTINGS_TAB } from './tabs'
import { createWorkspace, type WorkspaceStore } from './workspace'

const TOWER = 'centities/tower/centity.json'
const SCRIPT = 'centities/tower/script.lua'

let backend: MemoryBackend
let store: WorkspaceStore

// The memory backend's change events and the store's async work run on timers: `settle()`.
beforeEach(async () => {
  vi.useFakeTimers()
  ;({ backend, workspace: store } = await openExampleWorkspace())
})
afterEach(() => vi.useRealTimers())

const ws = () => store.getState()
const tower = () => modelOf<CentityFile>(ws().docs[TOWER])!

// The tower's turret: an empty pivot, at [0, 2, 0] in the file.
function setTranslation(y: number) {
  ws().edit<CentityFile>(TOWER, (draft) => {
    draft.nodes!.turret!.transform = { translation: [0, y, 0] }
  })
}

describe('workspace', () => {
  it('opens a project, writes its schemas and validates it clean', () => {
    expect(ws().files).toContain(TOWER)
    expect(ws().minecraft).toBe('26.3')
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resources.centity).toEqual(expect.arrayContaining(['lamp', 'tower']))
    expect(ws().outline?.resources.menu).toEqual(['altar', 'backpack', 'board', 'shop'])
    expect(ws().outline?.resources.dialog).toEqual(['guestbook', 'portal', 'survey', 'welcome'])
    expect(ws().outline?.resourcePacks.ui?.skins).toEqual(['altar', 'shop'])
    // The schemas live in .netherforge/, which never shows up in the file list.
    expect(Object.keys(backend.testFiles())).toContain('.netherforge/schema/centity.schema.json')
    expect(ws().files.some((it) => it.startsWith('.netherforge/'))).toBe(false)
  })

  it("opens an untrusted project for editing, keeps this editor's token out of it, and runs it once trusted", async () => {
    const mcp = JSON.stringify({ mcpServers: { netherforge: {} } })
    backend = new MemoryBackend({
      projects: exampleProjects(EXAMPLE_ROOT, { ...exampleProject, '.mcp.json': mcp }),
      trusted: [],
    })
    store = createWorkspace(backend)
    await ws().openProject(EXAMPLE_ROOT)
    await settle()
    expect(ws().project?.trusted).toBe(false)
    expect(ws().problems).toEqual([])
    expect(JSON.parse(backend.testFiles()['.mcp.json']!).mcpServers.netherforge).toEqual({})
    await expect(backend.startServer()).rejects.toMatchObject({ code: 'untrusted' })

    await ws().trustProject(true)
    await settle()
    expect(ws().project?.trusted).toBe(true)
    expect(JSON.parse(backend.testFiles()['.mcp.json']!).mcpServers.netherforge.url).toBeTruthy()
    // Trusted where the backend keeps it: opened again, it still is.
    await ws().closeProject()
    await ws().openProject(EXAMPLE_ROOT)
    expect(ws().project?.trusted).toBe(true)
  })

  it('refuses a project in another format version, naming it', async () => {
    const newer = {
      ...exampleProject,
      'netherforge.json': '{ "formatVersion": 2, "name": "New", "minecraft": "26.3" }\n',
    }
    backend = new MemoryBackend({ projects: { '/new': newer } })
    store = createWorkspace(backend)
    await expect(ws().openProject('/new')).rejects.toThrow(/uses format 2; .* Update NetherForge/)
    expect(ws().project).toBeNull()
    expect(ws().notice?.text).toContain('format 2')
  })

  it("opens maps and structures without reading them: a world's files are the game's", async () => {
    const project = {
      ...exampleProject,
      'maps/arena/level.dat': new Uint8Array([0x1f, 0x8b, 0, 0xff]),
      'maps/arena/players/stats/0.json': 'not json, and none of format business',
      'structures/house.nbt': new Uint8Array([0x1f, 0x8b, 1, 2]),
    }
    backend = new MemoryBackend({ projects: exampleProjects('/worlds', project) })
    const read = vi.spyOn(backend, 'readText')
    store = createWorkspace(backend)
    await ws().openProject('/worlds')
    const readPaths = read.mock.calls.map(([path]) => path)
    // (A structure's generation file, `structures/<id>.json`, is the project's own JSON: that one is read.)
    expect(
      readPaths.filter(
        (path) =>
          (path.startsWith('maps/') || path.startsWith('structures/')) && !path.endsWith('.json'),
      ),
    ).toEqual([])
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resources.map).toEqual(['arena'])
    // The example's own (its terrains' decorations place a tree and a spire; shrines generate) beside the one this test adds.
    expect(ws().outline?.resources.structure).toEqual([
      'crystal_spire',
      'house',
      'oak_tree',
      'ruined_shrine',
    ])

    // Opening one (from the explorer, or a problem's file) shows its screen; still nothing is read as text.
    await ws().openFile('structures/house.nbt')
    await ws().openFile('maps/arena/level.dat')
    await ws().openFile('maps/arena')
    expect(ws().tabs.map((it) => [it.type, it.path])).toEqual([
      ['structure', 'structures/house.nbt'],
      ['map', 'maps/arena'],
    ])
    expect(Object.keys(ws().docs).filter((it) => !it.startsWith('netherforge'))).toEqual([])
    expect(
      read.mock.calls.some(([path]) => path.startsWith('maps/') || path.endsWith('.nbt')),
    ).toBe(false)
    // A rename follows the open screen.
    await ws().renamePath('structures/house.nbt', 'structures/hut.nbt')
    expect(ws().tabs[0]).toMatchObject({ type: 'structure', path: 'structures/hut.nbt' })
  })

  it('opens a module without an init.lua on the files it has', async () => {
    await ws().createFile('modules/helpers/util.lua', 'return {}\n')
    await ws().createFile('modules/helpers/lib/deep.lua', 'return {}\n')
    await ws().openFile('modules/helpers/init.lua')
    expect(ws().tabs.map((it) => it.path)).toEqual(['modules/helpers/util.lua'])
    // So does a problem about the module's folder.
    ws().closeTab(ws().tabs[0]!.id)
    await ws().openFile('modules/helpers')
    expect(ws().tabs.map((it) => it.path)).toEqual(['modules/helpers/util.lua'])
  })

  it('opens the editor settings as a tab of their own, on the page asked for, reading no file', async () => {
    await ws().openProject(EXAMPLE_ROOT)
    await ws().openFile(TOWER)
    const read = vi.spyOn(backend, 'readText')
    ws().openSettings()
    expect(ws().tabs.map((it) => it.type)).toEqual(['centity', 'settings'])
    expect(ws().activeTab).toBe(SETTINGS_TAB)
    expect(ws().views[SETTINGS_PATH]).toBeUndefined()

    // Asking again shows the same tab, on the page asked for.
    ws().activate(ws().tabs[0]!.id)
    ws().openSettings('updates')
    expect(ws().tabs).toHaveLength(2)
    expect(ws().activeTab).toBe(SETTINGS_TAB)
    expect(ws().views[SETTINGS_PATH]?.selection).toEqual(['updates'])
    expect(read).not.toHaveBeenCalled()
    expect(ws().docs[SETTINGS_PATH]).toBeUndefined()

    ws().closeTab(SETTINGS_TAB)
    expect(ws().tabs.map((it) => it.type)).toEqual(['centity'])
  })

  it('opens netherforge.json as the project settings, a model document saved canonically', async () => {
    await ws().openFile('netherforge.json')
    expect(ws().tabs.at(-1)).toMatchObject({ type: 'project', path: 'netherforge.json' })
    ws().edit<{ managedWorlds?: string[] }>('netherforge.json', (draft) => {
      draft.managedWorlds = ['arena', 'lobby']
    })
    expect(await ws().save('netherforge.json')).toBe(true)
    const written = backend.testFiles()['netherforge.json']!
    expect(JSON.parse(written).managedWorlds).toEqual(['arena', 'lobby'])
    expect(written).toBe(
      canonicalizeModel('netherforge', 'netherforge.json', JSON.parse(written)).text,
    )
  })

  it('writes the docs for coding agents on open, and only when they changed', async () => {
    await settle()
    const files = backend.testFiles()
    expect(files['.netherforge/docs/format/centity.md']).toMatch(/^# /m)
    expect(files['.netherforge/docs/reference/nf.md']).toBeDefined()
    expect(files['.netherforge/docs/README.md']).toContain('(format/centity.md)')

    const write = vi.spyOn(backend, 'writeText')
    await ws().openProject(EXAMPLE_ROOT)
    await settle()
    expect(write.mock.calls.filter(([path]) => path.startsWith('.netherforge/docs/'))).toEqual([])
  })

  it('adds AGENTS.md, CLAUDE.md and .mcp.json, keeping what is already there', async () => {
    // The example has its own; play a project that has only an AGENTS.md.
    backend.testWrite('AGENTS.md', '# Mine\n')
    backend.testDelete('CLAUDE.md')
    backend.testWrite('.mcp.json', JSON.stringify({ mcpServers: { other: { command: 'x' } } }))
    backend.testWrite('opencode.json', '{}\n')
    await ws().refreshFiles()
    expect(await ws().addAgentFiles()).toEqual(['CLAUDE.md', '.mcp.json', 'opencode.json'])
    expect(JSON.parse(backend.testFiles()['.mcp.json']!)).toEqual({
      mcpServers: {
        other: { command: 'x' },
        netherforge: {
          type: 'http',
          url: 'http://127.0.0.1:47615/mcp',
          headers: { Authorization: `Bearer ${MEMORY_MCP_TOKEN}` },
        },
      },
    })
    expect(JSON.parse(backend.testFiles()['opencode.json']!)['mcp'].netherforge).toEqual({
      type: 'remote',
      url: 'http://127.0.0.1:47615/mcp',
      headers: { authorization: `Bearer ${MEMORY_MCP_TOKEN}` },
    })
    expect(backend.testFiles()['AGENTS.md']).toBe('# Mine\n')
    expect(backend.testFiles()['CLAUDE.md']).toBe('@AGENTS.md\n')
    expect(ws().files).toContain('CLAUDE.md')
    expect(await ws().addAgentFiles()).toEqual([])
  })

  it('opens a document in a tab and tracks dirtiness', async () => {
    await ws().openFile(TOWER)
    expect(ws().tabs.map((it) => it.id)).toEqual([`centity:${TOWER}`])
    expect(ws().docs[TOWER]!.dirty).toBe(false)

    setTranslation(3)
    expect(ws().docs[TOWER]!.dirty).toBe(true)
    setTranslation(2)
    // Back to what's on disk: clean again.
    expect(ws().docs[TOWER]!.dirty).toBe(false)
  })

  it('reorders tabs', async () => {
    await ws().openFile(TOWER)
    await ws().openFile(SCRIPT)
    await ws().openFile('menus/shop/menu.json')
    const ids = () => ws().tabs.map((it) => it.path)
    ws().moveTab(`script:${SCRIPT}`, 0)
    expect(ids()).toEqual([SCRIPT, TOWER, 'menus/shop/menu.json'])
    ws().moveTab(`script:${SCRIPT}`, 99)
    expect(ids()).toEqual([TOWER, 'menus/shop/menu.json', SCRIPT])
    expect(ws().activeTab).toBe('menu:menus/shop/menu.json')
  })

  it('an edit that changes nothing is no undo step, and undoing to the saved state is clean', async () => {
    await ws().openFile(TOWER)
    const before = ws().docs[TOWER]!.history!.past.length
    ws().edit<CentityFile>(TOWER, () => {})
    expect(ws().docs[TOWER]!.history!.past.length).toBe(before)

    const name = tower().name
    ws().edit<CentityFile>(TOWER, (draft) => void delete draft.name)
    expect(ws().docs[TOWER]!.dirty).toBe(true)
    ws().edit<CentityFile>(TOWER, (draft) => void (draft.name = name))
    expect(ws().docs[TOWER]!.dirty).toBe(false)
  })

  it('compares models as JSON, whatever the key order', () => {
    expect(sameJson({ a: 1, b: [1, { c: 2 }] }, { b: [1, { c: 2 }], a: 1 })).toBe(true)
    expect(sameJson({ a: 1, b: undefined }, { a: 1 })).toBe(true)
    expect(sameJson({ a: [1, 2] }, { a: [2, 1] })).toBe(false)
    expect(sameJson({ a: {} }, { a: [] })).toBe(false)
  })

  it('saves through the canonical writer', async () => {
    await ws().openFile(TOWER)
    setTranslation(4)
    expect(await ws().save(TOWER)).toBe(true)
    const written = backend.testFiles()[TOWER]!
    expect(written).toBe(canonicalizeModel('centity', TOWER, tower()).text)
    expect(written).toContain('"translation": [0, 4, 0]')
    expect(ws().docs[TOWER]!.dirty).toBe(false)
  })

  it('undoes per document, with a drag as one step', async () => {
    await ws().openFile(TOWER)
    await ws().openFile('centities/lamp/centity.json')
    setTranslation(3)
    ws().beginGesture(TOWER)
    for (let y = 4; y <= 10; y += 1) setTranslation(y)
    ws().endGesture(TOWER)
    ws().edit<CentityFile>('centities/lamp/centity.json', (draft) => {
      draft.name = 'Changed'
    })

    await ws().undo(TOWER)
    expect(tower().nodes!.turret!.transform!.translation).toEqual([0, 3, 0])
    // The lamp's edit is in its own history and untouched.
    expect(modelOf<CentityFile>(ws().docs['centities/lamp/centity.json'])!.name).toBe('Changed')
    await ws().undo(TOWER)
    expect(tower().nodes!.turret!.transform!.translation).toEqual([0, 2, 0])
    expect(ws().docs[TOWER]!.dirty).toBe(false)
    await ws().redo(TOWER)
    await ws().redo(TOWER)
    expect(tower().nodes!.turret!.transform!.translation).toEqual([0, 10, 0])
  })

  it('tells the dev server to reload what it saved', async () => {
    const reloaded = vi.fn()
    ws().setRunHooks({ bridgeConnected: () => true, reloaded })
    backend.testConnect()
    await ws().openFile(SCRIPT)
    ws().setText(SCRIPT, '-- changed\n')
    await ws().save(SCRIPT)
    await settle()
    expect(backend.bridgeLog).toEqual([{ method: 'reload', params: { paths: [SCRIPT] } }])
    expect(reloaded).toHaveBeenCalledWith([SCRIPT], expect.anything())
  })

  it('tells the dev server to reload what it creates, renames and deletes', async () => {
    ws().setRunHooks({ bridgeConnected: () => true, reloaded: vi.fn() })
    backend.testConnect()
    const helper = 'centities/tower/helper.lua'
    await ws().createFile(helper, 'return {}\n')
    await ws().renamePath(helper, 'centities/tower/util.lua')
    await ws().deletePath('centities/tower/util.lua')
    // A whole folder: every file in it, so the server finds the resource gone.
    await ws().deletePath('centities/crate')
    await settle()
    expect(backend.bridgeLog).toEqual([
      { method: 'reload', params: { paths: [helper] } },
      { method: 'reload', params: { paths: [helper, 'centities/tower/util.lua'] } },
      { method: 'reload', params: { paths: ['centities/tower/util.lua'] } },
      {
        method: 'reload',
        params: {
          paths: Object.keys(exampleFiles)
            .filter((it) => it.startsWith('centities/crate/'))
            .sort(),
        },
      },
    ])
  })

  it('ignores the echo of its own save', async () => {
    await ws().openFile(TOWER)
    setTranslation(5)
    await ws().save(TOWER)
    setTranslation(6) // dirty again before the watcher reports the save
    await settle()
    const doc = ws().docs[TOWER]!
    expect(doc.conflict).toBeNull()
    expect(doc.dirty).toBe(true)
  })

  it('reloads a clean document silently when it changes on disk', async () => {
    await ws().openFile(TOWER)
    backend.testWrite(TOWER, exampleFiles[TOWER]!.replace('Stone tower', 'Renamed tower'))
    await settle()
    expect(tower().name).toBe('Renamed tower')
    expect(ws().docs[TOWER]!.dirty).toBe(false)
  })

  it('re-reads everything when the watcher says it lost events', async () => {
    await ws().openFile(TOWER)
    backend.testWriteMissed(TOWER, exampleFiles[TOWER]!.replace('Stone tower', 'Missed tower'))
    await settle()
    expect(tower().name).toBe('Missed tower')
  })

  it('asks before overwriting unsaved edits, and both answers work', async () => {
    await ws().openFile(TOWER)
    setTranslation(8)
    const theirs = exampleFiles[TOWER]!.replace('Stone tower', 'Theirs')
    backend.testWrite(TOWER, theirs)
    await settle()
    expect(ws().docs[TOWER]!.conflict).toEqual({ theirs })

    ws().resolveConflict(TOWER, 'mine')
    expect(ws().docs[TOWER]!.conflict).toBeNull()
    expect(tower().nodes!.turret!.transform!.translation).toEqual([0, 8, 0])
    expect(ws().docs[TOWER]!.dirty).toBe(true)
    await ws().save(TOWER)
    expect(backend.testFiles()[TOWER]).toContain('Stone tower')

    setTranslation(9)
    backend.testWrite(TOWER, theirs)
    await settle()
    ws().resolveConflict(TOWER, 'theirs')
    expect(tower().name).toBe('Theirs')
    expect(ws().docs[TOWER]!.dirty).toBe(false)
  })

  it('closes a clean deleted document and keeps a dirty one marked', async () => {
    await ws().openFile(SCRIPT)
    await ws().openFile(TOWER)
    setTranslation(3)
    backend.testDelete('centities/tower')
    await settle()
    expect(ws().docs[SCRIPT]).toBeUndefined()
    expect(ws().tabs.map((it) => it.path)).toEqual([TOWER])
    expect(ws().docs[TOWER]!.deleted).toBe(true)
    // Saving recreates it.
    await ws().save(TOWER)
    expect(backend.testFiles()[TOWER]).toBeDefined()
  })

  it('opens a file that does not parse as text, with its problem', async () => {
    backend.testWrite(TOWER, '{ "nodes": { "a": { "bogus": true } } }\n')
    await settle()
    await ws().openFile(TOWER)
    const doc = ws().docs[TOWER]!
    expect(doc.raw).toBe(true)
    expect(doc.parseProblem?.message).toContain('bogus')
    await ws().validateNow()
    expect(ws().problems.some((it) => it.file === TOWER && it.line === 1)).toBe(true)
  })

  it('validates unsaved edits', async () => {
    await ws().openFile(TOWER)
    ws().edit<CentityFile>(TOWER, (draft) => {
      draft.nodes!.cap!.parent = 'nowhere'
    })
    await ws().validateNow()
    expect(ws().problems.map((it) => it.code)).toContain('centity.unknown-parent')
  })

  it('creates a centity, and renames it with its open tab', async () => {
    await ws().createResource('centity', 'crate')
    expect(ws().activeTab).toBe('centity:centities/crate/centity.json')
    await ws().renamePath('centities/crate', 'centities/box')
    expect(ws().activeTab).toBe('centity:centities/box/centity.json')
    expect(ws().files).toContain('centities/box/centity.json')
    expect(ws().files).not.toContain('centities/crate/centity.json')
  })

  it('follows a renamed script in the centity that uses it', async () => {
    await ws().renamePath(SCRIPT, 'centities/tower/main.lua')
    expect(backend.testFiles()[TOWER]).toContain('"file": "main.lua"')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
  })

  it('creates menus, dialogs and packs from format templates', async () => {
    await ws().createResource('menu', 'bank')
    await ws().createResource('dialog', 'rules')
    await ws().createResource('resource_pack', 'art')
    const files = backend.testFiles()
    expect(files['menus/bank/menu.json']).toContain('"title": "Bank"')
    expect(files['dialogs/rules/dialog.json']).toContain('"key": "ok"')
    expect(files['resource_packs/art/pack.json']).toContain('resource_pack.schema.json')
    expect(ws().tabs.map((it) => it.type)).toEqual(['menu', 'dialog', 'resource_pack'])
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resourcePacks.art).toEqual({
      skins: [],
      glyphs: [],
      items: [],
      tooltips: [],
      equipment: [],
      blocks: [],
      sounds: [],
    })
  })

  it("creates a datapack for the server's data pack format, and says why it can't before the game data", async () => {
    await ws().createResource('datapack', 'trees')
    expect(backend.testFiles()['datapacks/trees/pack.mcmeta']).toBeUndefined()
    expect(ws().notice?.text).toContain('start the dev server once')

    const withGame = new MemoryBackend({
      projects: exampleProjects(),
      gameData: { '26.3': { minecraft: '26.3', dataPackFormat: [121, 0] } },
    })
    const store = createWorkspace(withGame)
    await store.getState().openProject(EXAMPLE_ROOT)
    await store.getState().createResource('datapack', 'trees')
    expect(JSON.parse(withGame.testFiles()['datapacks/trees/pack.mcmeta']!)).toMatchObject({
      pack: { description: 'Trees', min_format: 121, max_format: 121 },
    })
    expect(store.getState().activeTab).toBe('datapack:datapacks/trees/pack.mcmeta')
    await store.getState().validateNow()
    expect(store.getState().problems.filter((it) => it.file.startsWith('datapacks/trees'))).toEqual(
      [],
    )
  })

  it('creates a particle effect from its template, and renames it with its open tab', async () => {
    await ws().createResource('particle_effect', 'glow')
    expect(ws().activeTab).toBe('particle_effect:particles/glow/effect.json')
    expect(backend.testFiles()['particles/glow/effect.json']).toContain(
      'particle_effect.schema.json',
    )
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    await ws().renamePath('particles/glow', 'particles/halo')
    expect(ws().activeTab).toBe('particle_effect:particles/halo/effect.json')
    expect(ws().files).toContain('particles/halo/effect.json')
  })

  it('creates a cutscene from its template, which plays, and renames it with its open tab', async () => {
    await ws().createResource('cutscene', 'tour')
    expect(ws().activeTab).toBe('cutscene:cutscenes/tour.json')
    expect(backend.testFiles()['cutscenes/tour.json']).toContain('cutscene.schema.json')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    await ws().renamePath('cutscenes/tour.json', 'cutscenes/walk.json')
    expect(ws().activeTab).toBe('cutscene:cutscenes/walk.json')
    expect(ws().files).toContain('cutscenes/walk.json')
  })

  it('creates a terrain from its template, which validates, and renames it with its open tab', async () => {
    await ws().createResource('terrain', 'realm')
    expect(ws().activeTab).toBe('terrain:terrain/realm.json')
    expect(backend.testFiles()['terrain/realm.json']).toContain('terrain.schema.json')
    await ws().validateNow()
    expect(ws().problems.filter((it) => it.file === 'terrain/realm.json')).toEqual([])
    await ws().renamePath('terrain/realm.json', 'terrain/kingdom.json')
    expect(ws().activeTab).toBe('terrain:terrain/kingdom.json')
    expect(ws().files).toContain('terrain/kingdom.json')
  })

  it('opens a resource folder named by a problem at its main file', async () => {
    await ws().openFile('menus/shop')
    expect(ws().tabs.map((it) => it.id)).toEqual(['menu:menus/shop/menu.json'])
    await ws().openFile('resource_packs/ui')
    expect(ws().activeTab).toBe('resource_pack:resource_packs/ui/pack.json')
  })

  it('writes a texture, bumps its stamp and validates it as present', async () => {
    const path = 'resource_packs/ui/textures/gui/banner.png'
    const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3])
    expect(await ws().writeBinary(path, bytes)).toBe(true)
    expect(ws().files).toContain(path)
    expect(backend.testBytes(path)).toBe(btoa(String.fromCharCode(...bytes)))
    const stamp = ws().fileStamps[path]
    expect(stamp).toBeGreaterThan(0)
    await settle()
    // The watcher's echo bumps it again, so an image showing it reloads.
    expect(ws().fileStamps[path]).toBeGreaterThan(stamp!)
  })

  it('follows a renamed script in the menu that uses it', async () => {
    await ws().renamePath('menus/shop/script.lua', 'menus/shop/shop.lua')
    await settle()
    const shop = backend.testFiles()['menus/shop/menu.json']!
    expect(shop).toContain('"file": "shop.lua"')
    expect(shop).not.toContain('script.lua')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
  })

  it('follows a renamed texture in pack.json', async () => {
    await ws().renamePath('resource_packs/ui/textures/gui', 'resource_packs/ui/textures/menus')
    await settle()
    expect(backend.testFiles()['resource_packs/ui/pack.json']).toContain(
      '"texture": "menus/shop.png"',
    )
    await ws().validateNow()
    expect(ws().problems).toEqual([])
  })

  it('creates a project item from its template and opens it in its own tab', async () => {
    await ws().createResource('item', 'wand')
    expect(ws().activeTab).toBe('item:items/wand/item.json')
    expect(backend.testFiles()['items/wand/item.json']).toContain('"name": "Wand"')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resources.item).toEqual([
      'candy',
      'circlet',
      'crown',
      'crystal_glass',
      'glow_lamp',
      'glow_pickaxe',
      'guide',
      'lantern',
      'ruby',
      'ruby_ore',
      'ruby_pick',
      'ruby_wand',
      'shard',
      'wand',
    ])
  })

  it('creates, renames and deletes a recipe, which is one file rather than a folder', async () => {
    await ws().createResource('recipe', 'torch')
    expect(ws().activeTab).toBe('recipe:recipes/torch.json')
    expect(backend.testFiles()['recipes/torch.json']).toContain('recipe.schema.json')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resources.recipe).toContain('torch')

    await ws().renamePath('recipes/torch.json', 'recipes/lantern.json')
    expect(ws().activeTab).toBe('recipe:recipes/lantern.json')
    expect(ws().files).toContain('recipes/lantern.json')
    expect(ws().files).not.toContain('recipes/torch.json')
    expect(ws().diskTexts['recipes/lantern.json']).toContain('"type": "shaped"')
    await ws().validateNow()
    expect(ws().outline?.resources.recipe).toContain('lantern')
    expect(ws().outline?.resources.recipe).not.toContain('torch')

    await ws().deletePath('recipes/lantern.json')
    expect(ws().tabs).toEqual([])
    expect(ws().files).not.toContain('recipes/lantern.json')
    expect(ws().diskTexts['recipes/lantern.json']).toBeUndefined()
  })

  it('creates, renames and deletes a block from its template, and names it by id', async () => {
    await ws().createResource('block', 'amethyst')
    expect(ws().activeTab).toBe('block:blocks/amethyst/block.json')
    expect(backend.testFiles()['blocks/amethyst/block.json']).toContain('block.schema.json')
    await ws().validateNow()
    expect(ws().problems).toEqual([])
    expect(ws().outline?.resources.block).toEqual([
      'amethyst',
      'crystal_glass',
      'floating_lamp',
      'glow_lamp',
      'ruby_crystal',
      'ruby_ore',
    ])

    await ws().renamePath('blocks/amethyst', 'blocks/geode')
    expect(ws().activeTab).toBe('block:blocks/geode/block.json')
    await ws().validateNow()
    expect(ws().outline?.resources.block).toEqual([
      'crystal_glass',
      'floating_lamp',
      'geode',
      'glow_lamp',
      'ruby_crystal',
      'ruby_ore',
    ])

    await ws().deletePath('blocks/geode')
    expect(ws().files).not.toContain('blocks/geode/block.json')
  })

  it("maps a recipe's problems back to its file", async () => {
    await ws().openFile('recipes/ruby_sword.json')
    ws().edit<RecipeFile>('recipes/ruby_sword.json', (draft) => {
      draft.key!.R = { item: 'sapphire' }
    })
    await ws().validateNow()
    expect(ws().problems).toEqual([
      expect.objectContaining({
        file: 'recipes/ruby_sword.json',
        code: 'reference.item',
        path: '$.key.R.item',
      }),
    ])
  })

  it('follows a renamed project item in recipes, menus and open documents', async () => {
    // One recipe open and edited (unsaved), the rest on disk only.
    await ws().openFile('recipes/ruby_dust.json')
    ws().edit<RecipeFile>('recipes/ruby_dust.json', (draft) => {
      draft.experience = 1
    })
    await ws().renamePath('items/ruby', 'items/gem')
    await settle()

    const files = backend.testFiles()
    expect(JSON.parse(files['recipes/ruby_sword.json']!).key.R).toEqual({ item: 'gem' })
    expect(JSON.parse(files['recipes/ruby.json']!).result).toEqual({ item: 'gem' })
    expect(files['menus/shop/menu.json']).toContain('"item": "gem"')
    expect(files['menus/shop/menu.json']).not.toContain('"item": "ruby"')
    // Rewritten canonically: what format writes for the same model.
    expect(files['recipes/ruby_sword.json']).toBe(
      canonicalizeModel(
        'recipe',
        'recipes/ruby_sword.json',
        JSON.parse(files['recipes/ruby_sword.json']!),
      ).text,
    )
    // The open document with unsaved edits is edited (a step of the rename, see refactors.test.ts) and stays unsaved.
    const dust = modelOf<RecipeFile>(ws().docs['recipes/ruby_dust.json'])!
    expect(dust.ingredient).toEqual({ item: 'gem' })
    expect(ws().docs['recipes/ruby_dust.json']!.dirty).toBe(true)
    expect(files['recipes/ruby_dust.json']).toContain('"item": "ruby"')
    await ws().save('recipes/ruby_dust.json')

    await ws().validateNow()
    expect(ws().problems).toEqual([])
  })

  it('follows a renamed pack in every reference into it: skins, glyph tags, item models', async () => {
    await ws().renamePath('resource_packs/ui', 'resource_packs/art')
    await settle()
    const files = backend.testFiles()
    const menu = JSON.parse(files['menus/shop/menu.json']!)
    expect(menu.skin).toBe('art/shop')
    expect(menu.slots['11'].item.lore).toEqual(['<gray><glyph:art/coin> 2'])
    expect(JSON.parse(files['items/ruby/item.json']!)).toMatchObject({
      itemModel: 'art/ruby',
      tooltipStyle: 'art/fancy',
    })
    await ws().validateNow()
    expect(ws().problems).toEqual([])
  })

  it("follows a pack entry's new key, and a dialog's new id", async () => {
    await ws().renameReferences(
      { type: 'resource_pack_entry', pack: 'ui', entry: 'glyph', key: 'coin' },
      'gold',
    )
    await settle()
    expect(backend.testFiles()['menus/shop/menu.json']).toContain('<glyph:ui/gold> 50')
    expect(backend.testFiles()['menus/shop/menu.json']).not.toContain('ui/coin')

    await ws().createFile(
      'dialogs/hub/dialog.json',
      '{ "type": "dialog_list", "title": "Hub", "dialogs": ["welcome"] }\n',
    )
    await settle()
    await ws().renamePath('dialogs/welcome', 'dialogs/hello')
    await settle()
    expect(JSON.parse(backend.testFiles()['dialogs/hub/dialog.json']!).dialogs).toEqual(['hello'])
  })

  it('says what refers to a resource before it goes', () => {
    const users = ws().usagesOf({ type: 'resource', kind: 'item', id: 'ruby' })
    expect([...new Set(users.map((it) => it.file))].sort()).toEqual([
      'advancements/treasure_hunter.json',
      'dialogs/survey/dialog.json',
      'loot/junk.json',
      'loot/ruby_crystal.json',
      'loot/ruby_ore.json',
      'loot/treasure.json',
      'menus/shop/menu.json',
      'recipes/ruby.json',
      'recipes/ruby_dust.json',
      'recipes/ruby_sword.json',
    ])
    // Its own files' references (a pack's textures) aren't usages of it.
    expect(
      ws()
        .usagesOf({ type: 'resource', kind: 'resource_pack', id: 'ui' })
        .map((it) => it.file),
    ).not.toContain('resource_packs/ui/pack.json')
  })

  it('opens a module on its entry file, as a script tab', async () => {
    await ws().openFile('modules/greeter')
    expect(ws().tabs.map((it) => [it.type, it.path])).toEqual([
      ['script', 'modules/greeter/init.lua'],
    ])
  })

  it("keeps a resource's main file while one of its scripts is open, for the outline", async () => {
    await ws().openFile(TOWER)
    await ws().openFile(SCRIPT)
    setTranslation(4)
    // Closing the centity's tab throws its unsaved edit away, even though a script of it is open.
    ws().closeTab(`centity:${TOWER}`)
    expect(ws().docs[TOWER]).toBeUndefined()
    // The outline loads it again from disk, and it stays while the script's tab does.
    await ws().loadDoc(TOWER)
    await ws().openFile('centities/tower/turns.lua')
    ws().closeTab(`script:${SCRIPT}`)
    expect(ws().docs[TOWER]).toBeDefined()
    ws().closeTab('script:centities/tower/turns.lua')
    expect(ws().docs[TOWER]).toBeUndefined()
  })

  it('copies a resource folder, text and bytes alike, and validates the copy', async () => {
    expect(await ws().copyPath('resource_packs/ui', 'resource_packs/ui_copy')).toBe(true)
    const files = backend.testFiles()
    expect(files['resource_packs/ui_copy/pack.json']).toBe(files['resource_packs/ui/pack.json'])
    expect(files['resource_packs/ui_copy/textures/gui/shop.png']).toEqual(
      files['resource_packs/ui/textures/gui/shop.png'],
    )
    expect(ws().files).toContain('resource_packs/ui_copy/pack.json')
    expect(ws().diskTexts['resource_packs/ui_copy/pack.json']).toBe(
      files['resource_packs/ui/pack.json'],
    )

    expect(await ws().copyPath(SCRIPT, 'centities/tower/script_copy.lua')).toBe(true)
    expect(backend.testFiles()['centities/tower/script_copy.lua']).toBe(files[SCRIPT])
    expect(await ws().copyPath('nowhere', 'elsewhere')).toBe(false)
  })
})

describe('the default font file', () => {
  const FONT = 'fonts/default.json'
  const withClient = (extra: Record<string, string> = {}) =>
    exampleBackend({
      project: { ...exampleProject, ...extra },
      backend: { importedClients: ['26.3'], glyphAdvances: { '26.3': { '65': 6, '32': 4 } } },
    })

  it('is written from the import, canonically, and only when it differs', async () => {
    const client = withClient()
    const store = createWorkspace(client)
    await store.getState().openProject(EXAMPLE_ROOT)
    await settle()
    const text = client.testFiles()[FONT]!
    expect(text).toBe(
      canonicalizeModel('default_font', FONT, {
        minecraft: '26.3',
        advances: { '32': 4, '65': 6 },
      }).text,
    )
    expect(store.getState().files).toContain(FONT)
    expect(store.getState().problems).toEqual([])
    const write = vi.spyOn(client, 'writeText')
    await store.getState().refreshGameData()
    expect(write.mock.calls.filter(([path]) => path === FONT)).toEqual([])
  })

  it('is regenerated when stale or deleted, and left alone without an import', async () => {
    const client = withClient({ [FONT]: '{ "minecraft": "26.2", "advances": {} }' })
    const store = createWorkspace(client)
    await store.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(client.testFiles()[FONT]).toContain('"minecraft": "26.3"')
    client.testDelete(FONT)
    await settle()
    expect(client.testFiles()[FONT]).toContain('"65": 6')

    // No import: nothing to write from, and nothing to complain about.
    expect(backend.testFiles()[FONT]).toBeUndefined()
    expect(ws().problems).toEqual([])
  })

  it('reports a write that fails', async () => {
    const client = withClient()
    vi.spyOn(client, 'writeText').mockImplementation(async (path) => {
      if (path === FONT) throw new Error('disk full')
    })
    const store = createWorkspace(client)
    await store.getState().openProject(EXAMPLE_ROOT)
    await store.getState().validateNow()
    expect(store.getState().problems.map((it) => [it.file, it.code])).toEqual([
      [FONT, 'editor.default-font'],
    ])
  })
})

describe('read-only files', () => {
  const READ_ONLY = 'centities/tower'
  const REASON = 'from the package acme_towers'

  it('are found by their innermost read-only folder', () => {
    const marked = { centities: 'everything', [READ_ONLY]: REASON }
    expect(readOnlyReason(marked, TOWER)).toBe(REASON)
    expect(readOnlyReason(marked, 'centities/barrel/centity.json')).toBe('everything')
    expect(readOnlyReason(marked, 'centities_extra/a.json')).toBeNull()
    expect(readOnlyReason(marked, 'menus/shop/menu.json')).toBeNull()
  })

  it('open and show, but are never edited, saved, renamed or deleted', async () => {
    await ws().openFile(TOWER)
    await ws().openFile(SCRIPT)
    ws().setReadOnly(READ_ONLY, REASON)
    const before = backend.testFiles()

    setTranslation(10)
    expect(ws().docs[TOWER]?.dirty).toBe(false)
    expect(ws().notice?.text).toBe(`${TOWER} is read-only: ${REASON}.`)
    ws().setText(SCRIPT, '-- mine')
    expect(ws().docs[SCRIPT]?.text).not.toBe('-- mine')
    expect(await ws().save(TOWER)).toBe(false)
    await ws().renamePath(READ_ONLY, 'centities/castle')
    await ws().deletePath(SCRIPT)
    await ws().createFile(`${READ_ONLY}/new.lua`, '')
    // Nor is anything copied into it.
    expect(await ws().copyPath('menus/shop', `${READ_ONLY}/shop`)).toBe(false)
    expect(backend.testFiles()).toEqual(before)

    ws().setReadOnly(READ_ONLY, null)
    setTranslation(10)
    expect(ws().docs[TOWER]?.dirty).toBe(true)
  })

  it('are forgotten with their project', async () => {
    ws().setReadOnly(READ_ONLY, REASON)
    await ws().openProject(EXAMPLE_ROOT)
    // Only what the project opens with: its one dependency, read-only as a whole.
    expect(Object.keys(ws().readOnly)).toEqual(['library:'])
  })
})

describe('glyph advances', () => {
  it('come from the client import, apart from the game data validation reads', async () => {
    const { workspace: store } = await openExampleWorkspace({
      backend: { importedClients: ['26.3'], glyphAdvances: { '26.3': { '65': 6 } } },
    })
    expect(store.getState().glyphAdvances).toEqual({ '65': 6 })
    // No server export: validation gets no game data, text measuring still has the advances.
    expect(store.getState().gameData).toBeNull()
  })
})
