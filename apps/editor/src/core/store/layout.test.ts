import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleWorkspace, settle } from '@/testing/workspace'
import {
  createLayout,
  DEFAULT_LAYOUT,
  followProject,
  readLayout,
  SAVE_DELAY_MS,
  type LayoutStorage,
} from './layout'
import { DEFAULT_DOCKS, findPanel, groupsOf } from './docks'
import { SETTINGS_PATH } from './tabs'
import { createWorkspace } from './workspace'

function mapStorage(): LayoutStorage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return { map, get: (key) => map.get(key) ?? null, set: (key, value) => void map.set(key, value) }
}

describe('readLayout', () => {
  it('falls back to defaults for anything missing or malformed', () => {
    expect(readLayout(null)).toEqual(DEFAULT_LAYOUT)
    expect(readLayout('nope')).toEqual(DEFAULT_LAYOUT)
    const layout = readLayout({
      outlineWidth: 10_000,
      bottomHeight: 'tall',
      docks: { left: { type: 'tabs', id: 'l', tabs: ['console', 'nowhere'], active: 'x' } },
      explorerView: 'list',
      expanded: { 'files:a': true, 'files:b': 'yes' },
      openPaths: ['a.json', 3],
      inspectorOpen: false,
    })
    expect(layout.outlineWidth).toBe(520)
    expect(layout.bottomHeight).toBe(DEFAULT_LAYOUT.bottomHeight)
    // What's gone is left out; what's missing joins a panel it starts beside.
    expect(layout.docks.left).toMatchObject({ id: 'l', active: 'console' })
    expect(groupsOf(layout.docks.left)[0]!.tabs).toEqual([
      'console',
      'outline',
      'instances',
      'tests',
      'profiler',
      'debug',
      'network',
    ])
    // Panels starting alone go to their dock, which the stored layout left empty: one group there.
    expect(layout.docks.bottom).toMatchObject({ type: 'tabs', tabs: ['project', 'problems'] })
    expect(readLayout({ docks: 3 }).docks).toEqual(DEFAULT_DOCKS)
    expect(layout.explorerView).toBe('list')
    expect(layout.expanded).toEqual({ 'files:a': true })
    expect(layout.openPaths).toEqual(['a.json'])
    expect(layout.inspectorOpen).toBe(false)
  })
})

describe('reveal', () => {
  it("opens the panel's dock with its tab showing, leaving other groups alone", () => {
    const layout = createLayout()
    layout.getState().set({ bottomOpen: false })
    layout.getState().reveal('debug')
    expect(layout.getState().bottomOpen).toBe(true)
    expect(groupsOf(layout.getState().docks.bottom).map((it) => it.active)).toEqual([
      'project',
      'problems',
      'debug',
    ])
    layout.getState().set({ inspectorOpen: false })
    layout.getState().reveal('inspector')
    expect(layout.getState().inspectorOpen).toBe(true)
  })
})

describe('movePanel and resetLayout', () => {
  it('opens the dock a panel moves to, and puts every dock back as it started', () => {
    const layout = createLayout()
    layout.getState().set({ inspectorOpen: false, outlineWidth: 400, explorerView: 'list' })
    layout.getState().movePanel('console', { group: 'right', edge: 'bottom' })
    expect(layout.getState().inspectorOpen).toBe(true)
    expect(findPanel(layout.getState().docks, 'console')?.area).toBe('right')
    layout.getState().resetLayout()
    expect(layout.getState()).toMatchObject({
      docks: DEFAULT_DOCKS,
      inspectorOpen: true,
      outlineWidth: DEFAULT_LAYOUT.outlineWidth,
      // Not the docks': kept.
      explorerView: 'list',
    })
  })
})

describe('followProject', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('remembers the layout and open tabs per project, and reopens them', async () => {
    const storage = mapStorage()
    const key = `netherforge.layout:${EXAMPLE_ROOT}`
    const example = exampleWorkspace()
    const { backend } = example
    let workspace = example.workspace
    let layout = createLayout()
    let stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await workspace.getState().openFile('centities/tower/script.lua')
    await workspace.getState().openFile('menus/shop/menu.json')
    workspace.getState().openSettings()
    workspace.getState().activate('script:centities/tower/script.lua')
    layout.getState().set({ outlineWidth: 300 })
    layout.getState().movePanel('console', { group: 'left-outline', edge: 'bottom' })
    layout.getState().toggle('inspector')
    // Written a moment after the last change, not on every one.
    await vi.advanceTimersByTimeAsync(SAVE_DELAY_MS - 1)
    expect(storage.map.has(key)).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    const saved = JSON.parse(storage.map.get(key)!)
    expect(saved).toMatchObject({ outlineWidth: 300, inspectorOpen: false })
    await workspace.getState().closeProject()
    stop()

    expect(saved.openPaths).toEqual([
      'centities/tower/script.lua',
      'menus/shop/menu.json',
      SETTINGS_PATH,
    ])

    workspace = createWorkspace(backend)
    layout = createLayout()
    stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(layout.getState().outlineWidth).toBe(300)
    expect(findPanel(layout.getState().docks, 'console')?.area).toBe('left')
    expect(layout.getState().inspectorOpen).toBe(false)
    expect(workspace.getState().tabs.map((it) => it.path)).toEqual([
      'centities/tower/script.lua',
      'menus/shop/menu.json',
      SETTINGS_PATH,
    ])
    expect(workspace.getState().activeTab).toBe('script:centities/tower/script.lua')
    stop()
  })

  it("doesn't reopen files that are gone", async () => {
    const storage = mapStorage()
    storage.set(
      `netherforge.layout:${EXAMPLE_ROOT}`,
      JSON.stringify({ openPaths: ['menus/gone/menu.json', 'menus/shop/menu.json'] }),
    )
    const { workspace } = exampleWorkspace()
    const layout = createLayout()
    const stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(workspace.getState().tabs.map((it) => it.path)).toEqual(['menus/shop/menu.json'])
    expect(layout.getState().openPaths).toEqual(['menus/shop/menu.json'])
    stop()
  })
})
