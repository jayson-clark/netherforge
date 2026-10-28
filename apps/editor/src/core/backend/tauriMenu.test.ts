/**
 * `TauriMenuBar` against `@tauri-apps/api`'s menu IPC, mocked: what it asks
 * Tauri to build, what it only updates, and what an item's click reports.
 */
import { Channel } from '@tauri-apps/api/core'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TauriMenuBar } from './tauriMenu'
import type { AppMenu } from './types'

interface Call {
  cmd: string
  args: Record<string, unknown>
}

let calls: Call[]
let nextRid: number

beforeEach(() => {
  calls = []
  nextRid = 1
  mockIPC((cmd, args) => {
    calls.push({ cmd, args: (args ?? {}) as Record<string, unknown> })
    if (cmd === 'plugin:menu|new') {
      const options = (args as { options?: { id?: string } }).options
      const rid = nextRid++
      return [rid, options?.id ?? `menu-${rid}`]
    }
    return null
  })
})

afterEach(() => clearMocks())

const created = (kind?: string) =>
  calls
    .filter((c) => c.cmd === 'plugin:menu|new')
    .filter((c) => kind === undefined || c.args.kind === kind)
    .map((c) => c.args.options as Record<string, unknown>)

const menus = (
  overrides: { save?: string; checked?: boolean; enabled?: boolean } = {},
): AppMenu[] => [
  {
    label: 'File',
    items: [
      {
        kind: 'item',
        id: 'save',
        label: overrides.save ?? 'Save',
        shortcut: 'Mod+S',
        enabled: overrides.enabled ?? true,
      },
      { kind: 'separator' },
      { kind: 'native', role: 'quit' },
    ],
  },
  {
    label: 'View',
    items: [
      {
        kind: 'item',
        id: 'explorer',
        label: 'Explorer',
        enabled: true,
        checked: overrides.checked ?? true,
      },
      {
        kind: 'submenu',
        label: 'Zoom',
        enabled: true,
        items: [{ kind: 'item', id: 'zoom-in', label: 'Zoom In', enabled: true }],
      },
    ],
  },
  { label: 'Window', items: [{ kind: 'native', role: 'minimize' }] },
  { label: 'Help', items: [{ kind: 'item', id: 'docs', label: 'Docs', enabled: true }] },
]

describe('TauriMenuBar', () => {
  it('builds the menus as Tauri menu items and makes them the app menu', async () => {
    await new TauriMenuBar(() => {}).set(menus())

    expect(created('MenuItem')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: 'save',
          text: 'Save',
          enabled: true,
          accelerator: 'CmdOrCtrl+S',
        }),
        expect.objectContaining({ id: 'zoom-in', text: 'Zoom In', accelerator: undefined }),
      ]),
    )
    expect(created('Check')).toEqual([
      expect.objectContaining({ id: 'explorer', text: 'Explorer', checked: true }),
    ])
    expect(created('Predefined').map((o) => o.item)).toEqual(
      expect.arrayContaining(['Separator', 'Quit', 'Minimize']),
    )
    expect(created('Submenu').map((o) => o.text)).toEqual(
      expect.arrayContaining(['File', 'View', 'Zoom', 'Window', 'Help']),
    )
    expect(calls.filter((c) => c.cmd === 'plugin:menu|set_as_app_menu')).toHaveLength(1)
    expect(calls.filter((c) => c.cmd === 'plugin:menu|set_as_windows_menu_for_nsapp')).toHaveLength(
      1,
    )
    expect(calls.filter((c) => c.cmd === 'plugin:menu|set_as_help_menu_for_nsapp')).toHaveLength(1)
  })

  it('reports the id of the item chosen', async () => {
    const chosen: string[] = []
    await new TauriMenuBar((id) => chosen.push(id)).set(menus())
    const save = calls.find(
      (c) => c.cmd === 'plugin:menu|new' && (c.args.options as { id?: string }).id === 'save',
    )!
    // Tauri calls the item's channel when it's clicked.
    ;(save.args.handler as Channel<string>).onmessage('save')
    expect(chosen).toEqual(['save'])
  })

  it('only updates what changed while the shape stays the same', async () => {
    const bar = new TauriMenuBar(() => {})
    await bar.set(menus())
    const built = calls.length
    await bar.set(menus({ save: 'Save All', checked: false, enabled: false }))

    const after = calls.slice(built)
    expect(after.filter((c) => c.cmd === 'plugin:menu|new')).toEqual([])
    expect(after.map((c) => c.cmd).sort()).toEqual([
      'plugin:menu|set_checked',
      'plugin:menu|set_enabled',
      'plugin:menu|set_text',
    ])
    expect(after.find((c) => c.cmd === 'plugin:menu|set_text')!.args.text).toBe('Save All')
    expect(after.find((c) => c.cmd === 'plugin:menu|set_checked')!.args.checked).toBe(false)

    // The same model again changes nothing.
    const before = calls.length
    await bar.set(menus({ save: 'Save All', checked: false, enabled: false }))
    expect(calls.length).toBe(before)
  })

  it('rebuilds when the shape changes', async () => {
    const bar = new TauriMenuBar(() => {})
    await bar.set(menus())
    const built = calls.length
    const changed = menus()
    changed[0]!.items.push({ kind: 'item', id: 'close', label: 'Close', enabled: true })
    await bar.set(changed)
    const after = calls.slice(built)
    expect(after.filter((c) => c.cmd === 'plugin:menu|set_as_app_menu')).toHaveLength(1)
    expect(
      after.some(
        (c) => c.cmd === 'plugin:menu|new' && (c.args.options as { id?: string }).id === 'close',
      ),
    ).toBe(true)
  })

  it('applies only the newest of the models that arrive while one is applied', async () => {
    const bar = new TauriMenuBar(() => {})
    const first = bar.set(menus())
    void bar.set(menus({ save: 'Two' }))
    const last = bar.set(menus({ save: 'Three' }))
    await Promise.all([first, last])
    const texts = calls.filter((c) => c.cmd === 'plugin:menu|set_text').map((c) => c.args.text)
    expect(texts).toEqual(['Three'])
    expect(calls.filter((c) => c.cmd === 'plugin:menu|set_as_app_menu')).toHaveLength(1)
  })
})
