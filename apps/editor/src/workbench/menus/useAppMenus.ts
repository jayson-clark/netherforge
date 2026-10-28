/**
 * Keeps the app's menus in step with the stores and answers their items and
 * shortcuts. On macOS the menus go to the system menu bar; on Windows and
 * Linux, `MenuBar` draws them from `useMenus`. One window keydown listener
 * runs every shortcut a menu shows (editors add their own and win when focus
 * is inside them, by handling the key first).
 */
import { useEffect } from 'react'
import { create } from 'zustand'
import type { AppStores } from '@/state/providers'
import type { AppMenu, RecentProject } from '@/core/backend/types'
import { IS_MAC } from '@/workbench/shortcuts'
import { buildMenus } from './buildMenus'
import type { CommandContext } from './commands'
import { commandForKey, runCommand } from './runCommand'

const NO_MENUS: AppMenu[] = []

/**
 * The menus as they stand and how to run an item, for the in-app menu bar
 * (on macOS `menus` stays empty); and the commands' context, for the palette.
 */
export const useMenus = create<{
  menus: AppMenu[]
  run: (id: string) => void
  context: () => CommandContext | null
}>(() => ({
  menus: NO_MENUS,
  run: () => {},
  context: () => null,
}))

export function useAppMenus(app: AppStores) {
  useEffect(() => {
    const { backend, workspace, layout, run, debug, updates } = app
    const native = workspace.getState().appInfo?.os === 'macos'
    let recent: RecentProject[] = []
    const context = (): CommandContext => ({ app, mac: native, macKeys: IS_MAC, recent })
    const runItem = (id: string) => runCommand(id, context())

    let published = ''
    let scheduled = false
    const publish = () => {
      scheduled = false
      const menus = buildMenus(context())
      const json = JSON.stringify(menus)
      if (json === published) return
      published = json
      if (native) void backend.setAppMenu(menus)
      else useMenus.setState({ menus, run: runItem })
    }
    // Coalesced: a burst of store changes (a save, a project opening) rebuilds once.
    const refresh = () => {
      if (scheduled) return
      scheduled = true
      queueMicrotask(publish)
    }
    const loadRecent = () =>
      backend.recentProjects().then(
        (list) => {
          recent = list
          refresh()
        },
        () => {},
      )

    useMenus.setState({ run: runItem, context })
    void loadRecent()
    publish()
    const unsubscribe = [
      workspace.subscribe((state, previous) => {
        // Opening a project puts it first in the recent list.
        if (state.project !== previous.project) void loadRecent()
        refresh()
      }),
      layout.subscribe(refresh),
      run.subscribe(refresh),
      debug.subscribe(refresh),
      updates.subscribe(refresh),
      backend.onMenuAction(runItem),
    ]

    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return
      const command = commandForKey(event, context())
      if (!command) return
      event.preventDefault()
      if (command.enabled) runItem(command.id)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      for (const it of unsubscribe) it()
      useMenus.setState({ menus: NO_MENUS, run: () => {}, context: () => null })
    }
  }, [app])
}
