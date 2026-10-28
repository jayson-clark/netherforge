/** The command palette's commands: every enabled one, plus ids only the palette has. */
import type { CommandContext } from '@/editors/contributions'
import { KIND_ORDER } from '@/editors/registry'
import type { IconName } from '@/ui/Icon'
import { EXPLORER_FOLDERS, folderByKey } from '@/workbench/explorer/folders'
import { SETTINGS_PAGES } from '@/workbench/settings/pages'
import { ALL_COMMANDS, CONTRIBUTED, NEW_PREFIX, RECENT_PREFIX, SETTINGS_PREFIX } from './runCommand'

export interface PaletteCommand {
  id: string
  label: string
  hint: string
  icon: IconName
  /** As the menus write it (`Mod+B`), for the palette to show. */
  shortcut?: string
}

/** The palette's order with nothing typed: running things, the docks, settings, files, then the rest. */
const PALETTE_GROUPS = [
  'run.',
  ...KIND_ORDER.map((kind) => `${kind}.`),
  'view.',
  'app.settings',
  SETTINGS_PREFIX,
  'file.projectSettings',
  NEW_PREFIX,
  'file.',
  RECENT_PREFIX,
  'help.',
  'app.',
]

const paletteGroup = (id: string) => PALETTE_GROUPS.findIndex((it) => id.startsWith(it))

/**
 * Everything the command palette can run right now: the commands that are
 * enabled, and one per settings page, new resource kind, recent project and
 * resource a kind's command can act on now (with the dev server's bridge up,
 * every centity to spawn).
 */
export function paletteCommands(c: CommandContext): PaletteCommand[] {
  const out: PaletteCommand[] = []
  for (const [id, command] of Object.entries(ALL_COMMANDS)) {
    if (command.palette === false || !(command.when?.(c) ?? true)) continue
    const label =
      command.palette?.(c) ?? (typeof command.label === 'string' ? command.label : command.label(c))
    const hint = typeof command.hint === 'function' ? command.hint(c) : (command.hint ?? '')
    out.push({
      id,
      label,
      hint,
      icon: command.icon ?? 'chevronRight',
      shortcut: command.keys?.(c)[0],
    })
  }
  // (The palette is the workbench's, so a project is open.)
  for (const page of SETTINGS_PAGES) {
    out.push({
      id: `${SETTINGS_PREFIX}${page.id}`,
      label: `Editor Settings: ${page.title}`,
      hint: page.hint,
      icon: page.icon,
    })
  }
  for (const folder of EXPLORER_FOLDERS) {
    out.push({
      id: `${NEW_PREFIX}${folder.key}`,
      label: `New ${folder.one.charAt(0).toUpperCase()}${folder.one.slice(1)}…`,
      hint: `Create a ${folder.one} in this project`,
      icon: folder.icon,
    })
  }
  const root = c.app.workspace.getState().project?.root
  for (const recent of c.recent) {
    if (recent.root === root) continue
    out.push({
      id: `${RECENT_PREFIX}${recent.root}`,
      label: `Open ${recent.name}`,
      hint: recent.root,
      icon: 'folderOpen',
    })
  }
  const files = c.app.workspace.getState().files
  for (const [id, { kind, command }] of Object.entries(CONTRIBUTED)) {
    if (command.scope !== 'resource' || !(command.when?.(c) ?? true)) continue
    for (const resource of folderByKey(kind).ids(files)) {
      out.push({
        id: `${id}:${resource}`,
        label: command.label(resource),
        hint: command.hint,
        icon: command.icon,
      })
    }
  }
  // Stable: within a group, the table's order.
  return out.sort((a, b) => paletteGroup(a.id) - paletteGroup(b.id))
}
