import { DEFAULT_DOCKS, findPanel } from '@/core/store/docks'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AppStores } from '@/state/providers'
import type { AppMenu, MenuEntry } from '@/core/backend/types'
import { parseShortcut } from '@/core/shortcut'
import { buildMenus } from './buildMenus'
import type { CommandContext } from './commands'
import { paletteCommands } from './palette'
import { commandForKey, runCommand } from './runCommand'
import { openExampleApp } from '@/testing/workspace'

const TOWER = 'centities/tower/centity.json'

let app: AppStores

beforeEach(async () => {
  // The layout (docks shown, the outline) is remembered per project: no test starts from another's.
  localStorage.clear()
  ;({ app } = await openExampleApp())
})

afterEach(async () => {
  document.body.innerHTML = ''
  // The layout remembers the project's open tabs (a moment after a change):
  // closing writes it now, and clearing it keeps the next test from reopening them.
  await app.workspace.getState().closeProject()
  localStorage.clear()
})

const context = (mac: boolean): CommandContext => ({ app, mac, macKeys: mac, recent: [] })

const entries = (menus: AppMenu[]): MenuEntry[] =>
  menus.flatMap((menu) =>
    menu.items.flatMap((it) => (it.kind === 'submenu' ? [it, ...it.items] : [it])),
  )

/** The key event a shortcut is, as a keyboard on [mac] (or not) sends it. */
function eventFor(text: string, mac: boolean, target: EventTarget = document.body) {
  const s = parseShortcut(text)
  const code = /^[A-Z]$/.test(s.key) ? `Key${s.key}` : /^[0-9]$/.test(s.key) ? `Digit${s.key}` : ''
  const event = new KeyboardEvent('keydown', {
    key: s.key.length === 1 ? s.key.toLowerCase() : s.key,
    code,
    metaKey: mac && s.mod,
    ctrlKey: s.ctrl || (!mac && s.mod),
    shiftKey: s.shift,
    altKey: s.alt,
  })
  Object.defineProperty(event, 'target', { value: target })
  return event
}

describe('buildMenus', () => {
  it('gives macOS its app and Window menus and the system clipboard', () => {
    const menus = buildMenus(context(true))
    expect(menus.map((it) => it.label)).toEqual([
      'NetherForge',
      'File',
      'Edit',
      'View',
      'Run',
      'Window',
      'Help',
    ])
    const all = entries(menus)
    expect(all).toContainEqual({ kind: 'native', role: 'copy' })
    expect(all.some((it) => it.kind === 'item' && it.id === 'app.exit')).toBe(false)
  })

  it('puts Settings and Exit under File elsewhere, with nothing native', () => {
    const menus = buildMenus(context(false))
    expect(menus.map((it) => it.label)).toEqual(['File', 'Edit', 'View', 'Run', 'Help'])
    const file = menus[0]!.items.flatMap((it) => (it.kind === 'item' ? [it.id] : []))
    expect(file).toContain('app.settings')
    expect(file).toContain('app.exit')
    expect(entries(menus).some((it) => it.kind === 'native')).toBe(false)
  })

  it('follows the stores: dirty documents, docks, recent projects', async () => {
    const item = (id: string) =>
      entries(buildMenus(context(false))).find((it) => it.kind === 'item' && it.id === id)
    expect(item('file.save')).toMatchObject({ enabled: false })
    await app.workspace.getState().openFile(TOWER)
    app.workspace.getState().edit(TOWER, (draft: { name?: string }) => {
      draft.name = 'Renamed tower'
    })
    expect(item('file.save')).toMatchObject({ enabled: true })
    expect(item('view.outline')).toMatchObject({ checked: true })
    app.layout.getState().toggle('outline')
    expect(item('view.outline')).toMatchObject({ checked: false })

    const recent = buildMenus({ ...context(false), recent: [{ root: '/a', name: 'A' }] as never })
    expect(entries(recent)).toContainEqual({
      kind: 'item',
      id: 'file.recent:/a',
      label: 'A',
      enabled: true,
    })
  })
})

describe('commandForKey', () => {
  it('runs every shortcut a menu shows, on both conventions', async () => {
    await app.workspace.getState().openFile(TOWER)
    for (const mac of [true, false]) {
      for (const entry of entries(buildMenus(context(mac)))) {
        if (entry.kind !== 'item' || !entry.shortcut) continue
        const found = commandForKey(eventFor(entry.shortcut, mac), context(mac))
        // The clipboard's keys stay the browser's (or the menu bar's).
        if (['edit.cut', 'edit.copy', 'edit.paste'].includes(entry.id)) {
          expect(found, entry.id).toBeNull()
        } else {
          expect(found?.id, `${entry.shortcut} (${mac ? 'mac' : 'other'})`).toBe(entry.id)
        }
      }
    }
  })

  it('claims Save even with nothing to save, so the browser never sees it', () => {
    expect(commandForKey(eventFor('Mod+S', true), context(true))).toEqual({
      id: 'file.save',
      enabled: false,
    })
  })

  it('leaves Select All to a text field, and swallows it anywhere else', () => {
    const input = document.body.appendChild(document.createElement('input'))
    expect(commandForKey(eventFor('Mod+A', true, input), context(true))).toBeNull()
    expect(commandForKey(eventFor('Mod+A', true), context(true))?.id).toBe('edit.selectAll')
  })

  it('leaves undo in a text field to the field', async () => {
    await app.workspace.getState().openFile(TOWER)
    const input = document.body.appendChild(document.createElement('input'))
    expect(commandForKey(eventFor('Mod+Z', true, input), context(true))).toBeNull()
    expect(commandForKey(eventFor('Mod+Z', true), context(true))?.id).toBe('edit.undo')
  })
})

describe('paletteCommands', () => {
  it('lists what can run now, settings pages and new resources, never the clipboard', async () => {
    await app.workspace.getState().openFile(TOWER)
    const labels = paletteCommands(context(true)).map((it) => it.label)
    expect(labels).toEqual(
      expect.arrayContaining([
        'Hide Left Dock',
        'Project Settings',
        'Editor Settings: Agents',
        'New Centity…',
        'Rename tower…',
        'Start Dev Server',
      ]),
    )
    for (const hidden of ['Copy', 'Undo', 'Select All', 'Save', 'Spawn tower at Me']) {
      expect(labels, hidden).not.toContain(hidden)
    }
  })

  it("offers a kind's resource command for every resource once it can run", () => {
    const labels = () => paletteCommands(context(true)).map((it) => it.label)
    expect(labels()).not.toContain('Spawn tower at Me')
    expect(labels()).not.toContain('Stop Particle Effects')
    app.run.setState((s) => ({ server: { ...s.server, bridgeConnected: true } }))
    const spawn = paletteCommands(context(true)).find((it) => it.label === 'Spawn tower at Me')
    expect(spawn?.id).toBe('centity.spawn:tower')
    expect(labels()).toContain('Stop Particle Effects')
  })
})

describe("the kinds' commands", () => {
  const runItem = () =>
    buildMenus(context(false))
      .find((it) => it.label === 'Run')!
      .items.find((it) => it.kind === 'item' && it.id === 'centity.spawn')

  it('act on the active resource of their kind, when their `when` holds', async () => {
    expect(runItem()).toMatchObject({ label: 'Spawn at Me', enabled: false })
    await app.workspace.getState().openFile('centities/tower/script.lua')
    expect(runItem()).toMatchObject({ label: 'Spawn tower at Me', enabled: false })
    app.run.setState((s) => ({ server: { ...s.server, bridgeConnected: true } }))
    expect(runItem()).toMatchObject({ label: 'Spawn tower at Me', enabled: true })
    await app.workspace.getState().openFile('menus/shop/menu.json')
    expect(runItem()).toMatchObject({ label: 'Spawn at Me', enabled: false })
  })

  it('run on the resource the palette names', () => {
    const spawned: string[] = []
    app.run.setState((s) => ({
      server: { ...s.server, bridgeConnected: true },
      spawn: async (id: string) => void spawned.push(id),
    }))
    runCommand('centity.spawn:tower', context(false))
    expect(spawned).toEqual(['tower'])
  })

  it('give each dock panel a View item', () => {
    const view = buildMenus(context(false)).find((it) => it.label === 'View')!
    const ids = view.items.flatMap((it) => (it.kind === 'item' ? [it.id] : []))
    expect(ids).toEqual(
      expect.arrayContaining([
        'view.panel:project',
        'view.panel:instances',
        'view.panel:problems',
        'view.panel:console',
      ]),
    )
    app.layout.getState().set({ bottomOpen: false })
    runCommand('view.panel:console', context(false))
    expect(findPanel(app.layout.getState().docks, 'console')?.group.active).toBe('console')
    expect(app.layout.getState().bottomOpen).toBe(true)
  })

  it('resets the layout from the View menu', () => {
    const view = buildMenus(context(false)).find((it) => it.label === 'View')!
    expect(view.items.some((it) => it.kind === 'item' && it.id === 'view.resetLayout')).toBe(true)
    app.layout.getState().movePanel('inspector', { area: 'bottom' })
    runCommand('view.resetLayout', context(false))
    expect(app.layout.getState().docks).toBe(DEFAULT_DOCKS)
  })
})

describe('runCommand', () => {
  it('saves, undoes and toggles docks', async () => {
    await app.workspace.getState().openFile(TOWER)
    app.workspace.getState().edit(TOWER, (draft: { name?: string }) => {
      draft.name = 'Renamed tower'
    })
    runCommand('edit.undo', context(false))
    expect(app.workspace.getState().docs[TOWER]?.dirty).toBe(false)
    runCommand('edit.redo', context(false))
    expect(app.workspace.getState().docs[TOWER]?.dirty).toBe(true)
    runCommand('file.save', context(false))
    await expect.poll(() => app.workspace.getState().docs[TOWER]?.dirty).toBe(false)

    runCommand('view.bottom', context(false))
    expect(app.layout.getState().bottomOpen).toBe(false)
  })

  it('selects all in the focused field rather than the page', () => {
    const input = document.body.appendChild(document.createElement('input'))
    input.value = 'tower'
    input.focus()
    runCommand('edit.selectAll', context(false))
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 5])
  })

  it('selects nothing when no field has focus', () => {
    document.body.appendChild(document.createElement('p')).textContent = 'Tower'
    ;(document.activeElement as HTMLElement | null)?.blur()
    runCommand('edit.selectAll', context(false))
    expect(window.getSelection()?.toString() ?? '').toBe('')
  })

  it('does nothing for a disabled or unknown item', () => {
    expect(() => runCommand('nope', context(false))).not.toThrow()
    runCommand('file.save', context(false))
  })
})
