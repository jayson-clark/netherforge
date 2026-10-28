/** The menu bar's model: File, Edit, View, Run and Help (plus macOS's app and Window menus), from the commands. */
import type { AppMenu, MenuEntry } from '@/core/backend/types'
import type { CommandContext } from '@/editors/contributions'
import { DOCK_PANELS } from '@/workbench/contributions'
import { EXPLORER_FOLDERS } from '@/workbench/explorer/folders'
import { project } from './commands'
import { ALL_COMMANDS, NEW_PREFIX, RECENT_PREFIX, contributedIn } from './runCommand'

function item(id: string, c: CommandContext): MenuEntry {
  const command = ALL_COMMANDS[id]!
  const checked = command.checked?.(c)
  return {
    kind: 'item',
    id,
    label: typeof command.label === 'string' ? command.label : command.label(c),
    shortcut: command.keys?.(c)[0],
    enabled: command.when?.(c) ?? true,
    ...(checked === undefined ? {} : { checked }),
  }
}

const separator: MenuEntry = { kind: 'separator' }

/**
 * The menus as they stand: macOS gets its app menu (About, Settings,
 * Services, Hide, Quit) and Window menu and the system's own clipboard
 * items; Windows and Linux find Settings and Exit under File.
 */
export function buildMenus(c: CommandContext): AppMenu[] {
  const open = project(c)
  const i = (id: string) => item(id, c)
  const clipboard: MenuEntry[] = c.mac
    ? [
        { kind: 'native', role: 'cut' },
        { kind: 'native', role: 'copy' },
        { kind: 'native', role: 'paste' },
        i('edit.selectAll'),
      ]
    : [i('edit.cut'), i('edit.copy'), i('edit.paste'), i('edit.selectAll')]

  const file: MenuEntry[] = [
    {
      kind: 'submenu',
      label: 'New',
      enabled: open,
      items: EXPLORER_FOLDERS.map((folder) => ({
        kind: 'item',
        id: `${NEW_PREFIX}${folder.key}`,
        label: `${folder.one.charAt(0).toUpperCase()}${folder.one.slice(1)}…`,
        enabled: open,
      })),
    },
    separator,
    i('file.newProject'),
    i('file.openProject'),
    {
      kind: 'submenu',
      label: 'Open Recent',
      enabled: c.recent.length > 0,
      items: c.recent.map((it) => ({
        kind: 'item',
        id: `${RECENT_PREFIX}${it.root}`,
        label: it.name,
        enabled: true,
      })),
    },
    separator,
    i('file.save'),
    i('file.saveAll'),
    separator,
    i('file.closeTab'),
    i('file.closeProject'),
    separator,
    i('file.projectSettings'),
    ...(c.mac ? [] : [separator, i('app.settings'), separator, i('app.exit')]),
  ]

  const menus: AppMenu[] = [
    { label: 'File', items: file },
    {
      label: 'Edit',
      items: [
        i('edit.undo'),
        i('edit.redo'),
        separator,
        ...clipboard,
        separator,
        i('edit.goTo'),
        i('edit.commandPalette'),
      ],
    },
    {
      label: 'View',
      items: [
        i('view.outline'),
        i('view.inspector'),
        i('view.bottom'),
        separator,
        ...DOCK_PANELS.map((panel) => i(`view.panel:${panel.id}`)),
        separator,
        i('view.resetLayout'),
        separator,
        i('view.nextTab'),
        i('view.previousTab'),
        separator,
        i('view.zoomIn'),
        i('view.zoomOut'),
        i('view.actualSize'),
        ...(c.mac ? [separator, { kind: 'native', role: 'fullscreen' } as const] : []),
      ],
    },
    {
      label: 'Run',
      items: [
        i('run.server'),
        i('run.tests'),
        ...contributedIn('run').map(i),
        separator,
        i('run.continue'),
        i('run.stepOver'),
        i('run.stepInto'),
        i('run.stepOut'),
        i('run.pause'),
        i('run.stopDebugging'),
        separator,
        i('run.toggleBreakpoint'),
        i('run.breakpoints'),
        i('run.breakOnErrors'),
        separator,
        i('run.untrust'),
        i('run.launcher'),
      ],
    },
  ]
  if (c.mac) {
    menus.unshift({
      label: 'NetherForge',
      items: [
        { kind: 'native', role: 'about' },
        separator,
        i('app.settings'),
        i('app.checkForUpdates'),
        separator,
        { kind: 'native', role: 'services' },
        separator,
        { kind: 'native', role: 'hide' },
        { kind: 'native', role: 'hideOthers' },
        { kind: 'native', role: 'showAll' },
        separator,
        { kind: 'native', role: 'quit' },
      ],
    })
    menus.push({
      label: 'Window',
      items: [
        { kind: 'native', role: 'minimize' },
        { kind: 'native', role: 'maximize' },
      ],
    })
  }
  menus.push({
    label: 'Help',
    items: [
      i('help.github'),
      i('help.issue'),
      ...(c.mac ? [] : [separator, i('app.checkForUpdates')]),
    ],
  })
  return menus
}
