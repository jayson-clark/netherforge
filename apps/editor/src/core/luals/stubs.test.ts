import { describe, expect, it, vi } from 'vitest'
import type { Workspace } from '@/core/store/workspace'
import { openExampleWorkspace } from '@/testing/workspace'
import { followLualsStubs, lualsFilesOf, modelReader, packageSources } from './follow'
import {
  loadApiStubs,
  lualsLibraryFiles,
  projectNames,
  projectStub,
} from '@netherforge/project-support'

const namesOf = (state: Workspace) => projectNames(state.outline, modelReader(state))

async function example() {
  const { backend, workspace } = await openExampleWorkspace()
  return { backend, store: workspace }
}

describe("the project's names for lua-language-server", () => {
  it('come from the outline and each resource file', async () => {
    const { store } = await example()
    const names = namesOf(store.getState())
    expect([...names.centity.keys()]).toContain('tower')
    // A node or animation says which centity it's in.
    expect(names.node.get('cap')).toEqual(['tower'])
    expect([...names.animation.keys()]).toContain('spin')
    expect([...names.dialog.keys()]).toContain('welcome')
    expect([...names.button.keys()].length).toBeGreaterThan(0)
    expect([...names.menu.keys()]).toContain('shop')
    expect([...names.glyph.keys()]).toContain('ui/coin')
    expect([...names.glyph.keys()].every((it) => it.includes('/'))).toBe(true)
    // The project's own server-owner settings, for nf.config; the library's are the library's.
    expect([...names.setting.keys()].sort()).toEqual([
      'expo',
      'greeting',
      'show_welcome',
      'tour_bossbar',
      'treasure_rolls',
    ])
  })

  it('see unsaved edits', async () => {
    const { store } = await example()
    await store.getState().openFile('centities/tower/centity.json')
    store
      .getState()
      .edit<{ nodes: Record<string, unknown> }>('centities/tower/centity.json', (draft) => {
        draft.nodes.flag = { display: { type: 'item', item: { kind: 'minecraft:stick' } } }
      })
    expect(namesOf(store.getState()).node.get('flag')).toEqual(['tower'])
  })

  it('are written as the aliases nf.lua types those parameters with', async () => {
    const { store } = await example()
    const stub = projectStub(namesOf(store.getState()))
    expect(stub.startsWith('---@meta\n')).toBe(true)
    expect(stub).toContain('---@alias NodeName\n')
    expect(stub).toContain('---| "cap" # node of tower\n')
    expect(stub).toMatch(/---@alias CentityId\n(---\| .*\n)*---\| "tower"\n/)
    // A kind with no names gets no alias, rather than an empty one LuaLS would reject.
    const empty = projectStub(projectNames(null, () => null))
    expect(empty).not.toContain('---@alias')
    expect(Object.keys(lualsLibraryFiles(namesOf(store.getState())))).toEqual(['project.lua'])
  })

  it("include the network's names: channels and maps scripts get, and the workspace's servers", async () => {
    const { store } = await example()
    const state = store.getState()
    const library = {
      ...packageSources(state)[0]!,
      files: { 'modules/chat/init.lua': 'local c = nf.network.channel("chat")' },
    }
    const names = projectNames(state.outline, modelReader(state), [library], {
      scripts: {
        'modules/lobby/init.lua':
          'local seats = nf.network.map("seats")\nnf.network.send_player(p, "arena")',
        'tower.json': null,
      },
      servers: { hub: 'servers/hub', arena: 'servers/arena' },
    })
    expect([...names.channel.keys()]).toEqual(['library:chat'])
    expect([...names.network_map.keys()]).toEqual(['seats'])
    expect([...names.server.keys()].sort()).toEqual(['arena', 'hub'])
    expect(projectStub(names)).toContain(
      '---@alias NetworkServerName\n---| "arena" # server of servers/arena\n',
    )
  })

  it('include what the packages it depends on export, and a stub per exported module file', async () => {
    const { store } = await example()
    const state = store.getState()
    const packages = packageSources(state)
    expect(packages.map((it) => it.namespace)).toEqual(['library'])
    const names = projectNames(state.outline, modelReader(state), packages)
    expect([...names.item.keys()]).toEqual(expect.arrayContaining(['ruby', 'library:gem']))
    const files = lualsFilesOf(state)
    // The library exports `greetings`; `phrases` is its own, so requiring it is an error and it has no stub.
    expect(Object.keys(files).sort()).toEqual([
      'packages/library/greetings/init.lua',
      'project.lua',
    ])
    const stub = files['packages/library/greetings/init.lua']!
    expect(stub.startsWith('---@meta library:greetings\n')).toBe(true)
    expect(stub).toContain('function greetings.hello(name)')
  })

  it('are kept in .netherforge/luals/, rewritten only when they change', async () => {
    // The API's stubs are a lazy chunk: loaded before the clock is faked.
    await loadApiStubs()
    vi.useFakeTimers()
    try {
      const { backend, store } = await example()
      const write = vi.spyOn(backend, 'writeText')
      const stop = followLualsStubs(store, backend, 10)
      // Opening a document changes the store, so the first write happens.
      await store.getState().openFile('centities/tower/centity.json')
      await vi.advanceTimersByTimeAsync(20)
      expect(write).toHaveBeenCalledWith('.netherforge/luals/project.lua', expect.any(String))
      expect(await backend.readText('.netherforge/luals/project.lua')).toContain('"cap"')

      write.mockClear()
      store.getState().select('centities/tower/centity.json', ['cap'])
      await vi.advanceTimersByTimeAsync(20)
      // Only the stubs: the agent docs opening the project started may still be landing.
      expect(write.mock.calls.filter(([path]) => path.startsWith('.netherforge/luals/'))).toEqual(
        [],
      )

      store
        .getState()
        .edit<{ nodes: Record<string, unknown> }>('centities/tower/centity.json', (draft) => {
          draft.nodes.flag = { display: { type: 'item', item: { kind: 'minecraft:stick' } } }
        })
      await vi.advanceTimersByTimeAsync(20)
      expect(await backend.readText('.netherforge/luals/project.lua')).toContain('"flag"')
      stop()
    } finally {
      vi.useRealTimers()
    }
  })

  it("include the API's stubs, marked for what netherforge.json doesn't allow", async () => {
    await loadApiStubs()
    vi.useFakeTimers()
    try {
      const { backend, store } = await example()
      const stop = followLualsStubs(store, backend, 10)
      await store.getState().openFile('netherforge.json')
      await vi.advanceTimersByTimeAsync(20)
      await vi.waitFor(async () =>
        expect(await backend.readText('.netherforge/luals/nf.lua')).toContain(
          '---@deprecated Needs `"requires": { "moderation": true }`',
        ),
      )
      expect(await backend.readText('.netherforge/luals/surfaces/centity.lua')).toMatch(/^---@meta/)
      // Allowing moderation and a host (unsaved) lifts the marks.
      store
        .getState()
        .edit<{ requires?: { moderation?: boolean; http?: string[] } }>(
          'netherforge.json',
          (draft) => {
            draft.requires = { ...draft.requires, moderation: true, http: ['example.com'] }
          },
        )
      await vi.advanceTimersByTimeAsync(20)
      await vi.waitFor(async () => {
        const stubs = await backend.readText('.netherforge/luals/nf.lua')
        expect(stubs).not.toContain('---@deprecated Needs `"requires": { "moderation": true }`')
        // What the project still hasn't declared (a plugin, here) stays marked.
        expect(stubs).toContain('---@deprecated Needs `"requires": { "plugins": ["vault"] }`')
      })
      stop()
    } finally {
      vi.useRealTimers()
    }
  })
})
