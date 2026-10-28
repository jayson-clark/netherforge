/** Every command (the table's, the kinds' and the panels'), the toolbar's, and running one by id or key. */
import { type KindId } from '@/core/format'
import type { CommandContext, CommandPlace, KindCommand } from '@/editors/contributions'
import { KIND_ORDER, kindContribution } from '@/editors/registry'
import { matchesShortcut, parseShortcut } from '@/core/shortcut'
import type { IconName } from '@/ui/Icon'
import { DOCK_PANELS, showPanel } from '@/workbench/contributions'
import { folderByKey } from '@/workbench/explorer/folders'
import { resourceActions } from '@/workbench/resourceActions'
import { SETTINGS_PAGES } from '@/workbench/settings/pages'
import { openProjectAt } from '@/workbench/welcome/welcomeState'
import { COMMANDS, type Command, activeOf, leaveProject, project } from './commands'

/** A View item per dock panel: `view.panel:<id>` shows it. */
const PANEL_COMMANDS: Record<string, Command> = Object.fromEntries(
  DOCK_PANELS.map((panel) => [
    `view.panel:${panel.id}`,
    {
      palette: () => `Show ${panel.menu.label}`,
      hint: panel.menu.hint,
      icon: panel.menu.icon,
      label: panel.menu.label,
      when: project,
      run: (c) => showPanel(c.app.layout, panel.id),
    } satisfies Command,
  ]),
)

/** A kind's command, with the kind it came from. */
interface Contributed {
  kind: KindId
  command: KindCommand
}

/** Every command a kind contributes, by id (`<kind>.<name>`). */
export const CONTRIBUTED: Record<string, Contributed> = Object.fromEntries(
  KIND_ORDER.flatMap((kind) =>
    (kindContribution(kind).commands ?? []).map((command) => [
      `${kind}.${command.name}`,
      { kind, command },
    ]),
  ),
)

/**
 * A kind's command as the menus and keys see it: a resource command acts on
 * the active tab's resource, so it can run only while one of its kind is
 * active; the palette offers it per resource instead (`paletteCommands`).
 */
function asCommand({ kind, command }: Contributed): Command {
  const allowed = (c: CommandContext) => command.when?.(c) ?? true
  if (command.scope === 'kind') {
    return {
      label: command.label,
      hint: command.hint,
      icon: command.icon,
      when: allowed,
      run: command.run,
    }
  }
  return {
    label: (c) => command.label(activeOf(c, kind)),
    hint: command.hint,
    icon: command.icon,
    palette: false,
    when: (c) => activeOf(c, kind) !== null && allowed(c),
    run: (c) => {
      const id = activeOf(c, kind)
      if (id !== null) return command.run(c, id)
    },
  }
}

/** Every command there is: the table's, the panels' and the kinds'. */
export const ALL_COMMANDS: Record<string, Command> = {
  ...COMMANDS,
  ...PANEL_COMMANDS,
  ...Object.fromEntries(Object.entries(CONTRIBUTED).map(([id, it]) => [id, asCommand(it)])),
}

/** The kinds' commands for one menu, in the registry's order. */
export const contributedIn = (place: CommandPlace) =>
  Object.entries(CONTRIBUTED)
    .filter(([, it]) => it.command.menu === place)
    .map(([id]) => id)

/** A toolbar button a kind contributes: what it says, and whether and how it runs now. */
export interface ToolbarCommand {
  id: string
  text: string
  icon: IconName
  /** Its tooltip: what it would do now, or what it needs. */
  title: (c: CommandContext) => string
  enabled: (c: CommandContext) => boolean
}

export const TOOLBAR_COMMANDS: ToolbarCommand[] = Object.entries(CONTRIBUTED).flatMap(
  ([id, { kind, command }]) =>
    command.scope === 'resource' && command.toolbar
      ? [
          {
            id,
            text: command.toolbar,
            icon: command.icon,
            title: (c: CommandContext) => {
              const active = activeOf(c, kind)
              return active ? command.label(active) : `Open a ${kindContribution(kind).one} first`
            },
            enabled: (c: CommandContext) => ALL_COMMANDS[id]!.when?.(c) ?? true,
          },
        ]
      : [],
)

/** Menu items made per project or per recent folder: `file.new:<explorer folder>`, `file.recent:<root>`. */
export const NEW_PREFIX = 'file.new:'

export const RECENT_PREFIX = 'file.recent:'

/** Only in the palette: `settings:<page>`, and a kind's resource command on one resource, `<kind>.<name>:<id>`. */
export const SETTINGS_PREFIX = 'settings:'

/** Runs a menu item's command; unknown ids (a stale menu) do nothing. */
export function runCommand(id: string, c: CommandContext) {
  if (id.startsWith(NEW_PREFIX)) {
    if (!project(c)) return
    const folder = folderByKey(id.slice(NEW_PREFIX.length))
    void resourceActions(c.app.workspace).create(folder)
    return
  }
  if (id.startsWith(RECENT_PREFIX)) {
    const root = id.slice(RECENT_PREFIX.length)
    void leaveProject(c).then(async (left) => {
      if (left) await openProjectAt(c.app.workspace, root)
    })
    return
  }
  if (id.startsWith(SETTINGS_PREFIX)) {
    const page = SETTINGS_PAGES.find((it) => it.id === id.slice(SETTINGS_PREFIX.length))
    if (project(c) && page) c.app.workspace.getState().openSettings(page.id)
    return
  }
  const at = id.indexOf(':')
  const contributed = at > 0 ? CONTRIBUTED[id.slice(0, at)] : undefined
  if (contributed?.command.scope === 'resource') {
    if (contributed.command.when?.(c) ?? true) void contributed.command.run(c, id.slice(at + 1))
    return
  }
  const command: Command | undefined = ALL_COMMANDS[id]
  if (command && (command.when?.(c) ?? true)) void command.run(c)
}

/**
 * The command [event] is the shortcut of, if the global handler should run
 * it. A disabled command still claims its key (Cmd+S with nothing to save
 * mustn't reach the browser), so it comes back with `enabled: false`.
 */
export function commandForKey(
  event: KeyboardEvent,
  c: CommandContext,
): { id: string; enabled: boolean } | null {
  for (const [id, command] of Object.entries(ALL_COMMANDS)) {
    const shortcuts = command.keys?.(c) ?? []
    if (!shortcuts.some((it) => matchesShortcut(event, parseShortcut(it), c.macKeys))) continue
    if (command.takesKey && !command.takesKey(event, c)) return null
    return { id, enabled: command.when?.(c) ?? true }
  }
  return null
}
