/**
 * What the palette offers: every resource (by id), every file the editor
 * opens as text (by path) and every command it can run now, ranked by
 * `fuzzyMatch`. A query starting with `>` is for commands alone.
 */
import { basename, dirname } from '@/core/paths'
import type { IconName } from '@/ui/Icon'
import { EXPLORER_FOLDERS, type ExplorerFolder } from '@/workbench/explorer/folders'
import type { PaletteCommand } from '@/workbench/menus/palette'
import { fuzzyMatch } from './fuzzy'

export interface QuickEntry {
  key: string
  label: string
  /** The line under the label: a resource's kind and folder, a command's hint. */
  detail: string
  icon: IconName
  /** A resource's, which the palette draws its thumbnail for. */
  resource?: { folder: ExplorerFolder; id: string }
  /** A command's shortcut, as the menus write it. */
  shortcut?: string
  /** What choosing it does. */
  action: { open: string } | { command: string }
  /** What the query is matched against. */
  text: string
  /** Where the label's matched characters are, for highlighting. */
  positions: number[]
}

export type Candidate = Omit<QuickEntry, 'positions'>

const TEXT_FILE = /\.(lua|json|md|txt|mcmeta)$/

const capitalized = (text: string) => `${text.charAt(0).toUpperCase()}${text.slice(1)}`

/** "Lua script · centities/tower": a text file's kind, then where it is. */
function fileDetail(path: string): string {
  const kind = path.endsWith('.lua')
    ? 'Lua script'
    : path.endsWith('.json') || path.endsWith('.mcmeta')
      ? 'JSON'
      : path.endsWith('.md')
        ? 'Markdown'
        : 'Text'
  return `${kind} · ${dirname(path) || 'project root'}`
}

export function quickEntries(files: string[]): Candidate[] {
  const resources = EXPLORER_FOLDERS.flatMap((folder) =>
    folder.ids(files).map((id): Candidate => {
      const group = folder.groupOf(files, id)
      return {
        key: `${folder.key}:${id}`,
        label: id,
        detail: `${capitalized(folder.one)} · ${folder.location(id, group)}`,
        icon: folder.icon,
        resource: { folder, id },
        action: { open: folder.openPath(id, group) },
        text: id,
      }
    }),
  )
  const opened = new Set(resources.map((it) => 'open' in it.action && it.action.open))
  const texts = files
    .filter((path) => TEXT_FILE.test(path) && !opened.has(path))
    .map((path): Candidate => ({
      key: `file:${path}`,
      label: basename(path),
      detail: fileDetail(path),
      icon: path.endsWith('.lua') ? 'code' : 'file',
      action: { open: path },
      text: path,
    }))
  return [...resources, ...texts]
}

export function commandEntries(commands: PaletteCommand[]): Candidate[] {
  return commands.map((command) => ({
    key: `command:${command.id}`,
    label: command.label,
    detail: command.hint,
    icon: command.icon,
    shortcut: command.shortcut,
    action: { command: command.id },
    text: command.label,
  }))
}

/**
 * The best [limit] matches for [query]: commands alone after a `>`.
 * Resources win ties over commands, and commands over files.
 */
export function rankEntries(entries: Candidate[], query: string, limit = 50): QuickEntry[] {
  const commandsOnly = query.startsWith('>')
  const text = commandsOnly ? query.slice(1).trim() : query
  const ranked: (QuickEntry & { score: number })[] = []
  for (const entry of entries) {
    const command = 'command' in entry.action
    if (commandsOnly && !command) continue
    const match = fuzzyMatch(text, entry.text)
    if (!match) continue
    // Highlight within the label: a path's matches past its folder.
    const offset = entry.text.length - entry.label.length
    ranked.push({
      ...entry,
      score: match.score + (entry.resource ? 50 : command ? 25 : 0),
      positions: match.positions.filter((it) => it >= offset).map((it) => it - offset),
    })
  }
  // A stable sort: equal scores (an empty query) keep the given order.
  return ranked.sort((a, b) => b.score - a.score).slice(0, limit)
}
