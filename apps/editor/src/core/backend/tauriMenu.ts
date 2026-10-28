/**
 * The macOS menu bar from the UI's menu model. Building it is dozens of IPC
 * calls, so it's built once per shape (which items, in which order) and
 * afterwards only labels, enabled and checked states are updated, which is
 * what changes as the user edits. Calls are serialised; a model that arrives
 * while one is applied replaces any other still waiting.
 */
import {
  CheckMenuItem,
  Menu,
  MenuItem,
  PredefinedMenuItem,
  Submenu,
  type PredefinedMenuItemOptions,
} from '@tauri-apps/api/menu'
import { acceleratorOf, parseShortcut } from '@/core/shortcut'
import type { AppMenu, MenuCommandEntry, MenuEntry, NativeMenuRole } from './types'

const PREDEFINED: Record<NativeMenuRole, PredefinedMenuItemOptions['item']> = {
  about: { About: null },
  services: 'Services',
  hide: 'Hide',
  hideOthers: 'HideOthers',
  showAll: 'ShowAll',
  quit: 'Quit',
  cut: 'Cut',
  copy: 'Copy',
  paste: 'Paste',
  minimize: 'Minimize',
  maximize: 'Maximize',
  fullscreen: 'Fullscreen',
}

/** What can't change without rebuilding: kinds, ids, roles, shortcuts, nesting, toggles. */
function shapeOf(entries: MenuEntry[]): unknown[] {
  return entries.map((entry) => {
    switch (entry.kind) {
      case 'item':
        return [entry.id, entry.shortcut ?? '', entry.checked !== undefined]
      case 'submenu':
        return shapeOf(entry.items)
      case 'separator':
        return '-'
      case 'native':
        return entry.role
    }
  })
}

const flatten = (entries: MenuEntry[], path: string, out: Map<string, MenuEntry>) => {
  entries.forEach((entry, index) => {
    const at = `${path}/${index}`
    out.set(at, entry)
    if (entry.kind === 'submenu') flatten(entry.items, at, out)
  })
  return out
}

type Handle = MenuItem | CheckMenuItem | Submenu

export class TauriMenuBar {
  private shape = ''
  private entries = new Map<string, MenuEntry>()
  private handles = new Map<string, Handle>()
  private pending: AppMenu[] | null = null
  private running: Promise<void> | null = null

  constructor(private readonly onAction: (id: string) => void) {}

  set(menus: AppMenu[]): Promise<void> {
    this.pending = menus
    this.running ??= this.drain().finally(() => {
      this.running = null
    })
    return this.running
  }

  private async drain() {
    while (this.pending) {
      const menus = this.pending
      this.pending = null
      const shape = JSON.stringify(menus.map((menu) => [menu.label, shapeOf(menu.items)]))
      const entries = flatten(
        menus.map((menu) => ({
          kind: 'submenu',
          label: menu.label,
          enabled: true,
          items: menu.items,
        })),
        '',
        new Map(),
      )
      if (shape === this.shape) await this.update(entries)
      else await this.build(menus)
      this.shape = shape
      this.entries = entries
    }
  }

  private async build(menus: AppMenu[]) {
    this.handles = new Map()
    const items = await Promise.all(
      menus.map(async (menu, index) => {
        const submenu = await Submenu.new({
          text: menu.label,
          items: await this.items(menu.items, `/${index}`),
        })
        this.handles.set(`/${index}`, submenu)
        return submenu
      }),
    )
    await (await Menu.new({ items })).setAsAppMenu()
    await Promise.all(
      menus.map(async (menu, index) => {
        const submenu = this.handles.get(`/${index}`) as Submenu
        if (menu.label === 'Window') await submenu.setAsWindowsMenuForNSApp()
        if (menu.label === 'Help') await submenu.setAsHelpMenuForNSApp()
      }),
    )
  }

  private items(entries: MenuEntry[], path: string): Promise<(Handle | PredefinedMenuItem)[]> {
    return Promise.all(
      entries.map(async (entry, index) => {
        const at = `${path}/${index}`
        switch (entry.kind) {
          case 'item': {
            const handle = await this.item(entry)
            this.handles.set(at, handle)
            return handle
          }
          case 'submenu': {
            const handle = await Submenu.new({
              text: entry.label,
              enabled: entry.enabled,
              items: await this.items(entry.items, at),
            })
            this.handles.set(at, handle)
            return handle
          }
          case 'separator':
            return PredefinedMenuItem.new({ item: 'Separator' })
          case 'native':
            return PredefinedMenuItem.new({ item: PREDEFINED[entry.role] })
        }
      }),
    )
  }

  private item(entry: MenuCommandEntry): Promise<MenuItem | CheckMenuItem> {
    const options = {
      id: entry.id,
      text: entry.label,
      enabled: entry.enabled,
      accelerator: entry.shortcut ? acceleratorOf(parseShortcut(entry.shortcut)) : undefined,
      action: () => this.onAction(entry.id),
    }
    return entry.checked === undefined
      ? MenuItem.new(options)
      : CheckMenuItem.new({ ...options, checked: entry.checked })
  }

  private async update(entries: Map<string, MenuEntry>) {
    const changes: Promise<void>[] = []
    for (const [at, entry] of entries) {
      const before = this.entries.get(at)
      const handle = this.handles.get(at)
      if (!before || !handle) continue
      if (entry.kind === 'item' && before.kind === 'item') {
        const item = handle as MenuItem | CheckMenuItem
        if (entry.label !== before.label) changes.push(item.setText(entry.label))
        if (entry.enabled !== before.enabled) changes.push(item.setEnabled(entry.enabled))
        if (entry.checked !== undefined && entry.checked !== before.checked) {
          changes.push((item as CheckMenuItem).setChecked(entry.checked))
        }
      } else if (entry.kind === 'submenu' && before.kind === 'submenu') {
        const submenu = handle as Submenu
        if (entry.label !== before.label) changes.push(submenu.setText(entry.label))
        if (entry.enabled !== before.enabled) changes.push(submenu.setEnabled(entry.enabled))
      }
    }
    await Promise.all(changes)
  }
}
