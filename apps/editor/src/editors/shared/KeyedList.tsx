/**
 * A resource's entries kept by name (a centity's animation clips, a particle
 * effect's emitters), as an outline pane: a flat `Tree` with an add button,
 * selection, inline rename (F2) checked against the name rule and the names
 * taken, duplicate (Cmd/Ctrl+D), delete, the same context menu everywhere,
 * and each row's `data-path` (`emitters.ring`). Every edit is the caller's;
 * this is only the list.
 */
import type { ReactNode } from 'react'
import { ID_RULE, NODE_NAME_RULE } from '@/core/format'
import { ID_PATTERN, NODE_NAME_PATTERN } from '@/core/paths'
import { IconButton } from '@/ui/Button'
import type { MenuItem } from '@/ui/ContextMenu'
import type { IconName } from '@/ui/Icon'
import { Pane } from '@/ui/Pane'
import { Tree, type TreeNode } from '@/ui/Tree'
import { fieldPath } from './focus'

/** One entry's row. */
export interface KeyedEntry {
  key: string
  icon?: IconName
  /** The row's tooltip. */
  title?: string
  /** After its name: a badge. */
  detail?: ReactNode
}

/** A rule names follow: a pattern, and how it's said. */
export interface NameRule {
  pattern: RegExp
  text: string
}

/** Names of a resource's parts (nodes, clips, emitters). */
export const NODE_NAMES: NameRule = { pattern: NODE_NAME_PATTERN, text: NODE_NAME_RULE }

/** Names held to the id rule (a loot table's pools). */
export const ID_NAMES: NameRule = { pattern: ID_PATTERN, text: ID_RULE }

/** A name's problem, under [rule] (a part's name, by default): null when it's fine. */
export function nameProblem(
  value: string,
  taken: boolean,
  what: string,
  rule: NameRule = NODE_NAMES,
): string | null {
  if (!rule.pattern.test(value)) return `${rule.text.charAt(0).toUpperCase()}${rule.text.slice(1)}`
  return taken ? `${/^[aeiou]/.test(what) ? 'An' : 'A'} ${what} with that name exists` : null
}

export function KeyedList({
  title,
  one,
  pane,
  entries,
  lead,
  list,
  selected,
  onSelect,
  onAdd,
  onRename,
  onDuplicate,
  onDelete,
  empty,
  rule = NODE_NAMES,
}: {
  /** The pane's title and the tree's name ("Emitters"). */
  title: string
  /** One of them, for messages and labels ("emitter"). */
  one: string
  pane: { open: boolean; onToggle: (open: boolean) => void }
  entries: KeyedEntry[]
  /** A first row that isn't an entry (the effect's own): selectable, never renamed or deleted. */
  lead?: { key: string; label: string; icon: IconName }
  /** Where the entries are in the file (`emitters`): each row's `data-path` is under it. */
  list: string
  selected: string | null
  onSelect: (key: string) => void
  onAdd?: () => void
  onRename?: (from: string, to: string) => void
  onDuplicate?: (key: string) => void
  onDelete?: (key: string) => void
  empty?: ReactNode
  /** What names must look like: a part's name by default. */
  rule?: NameRule
}) {
  const keys = new Set(entries.map((it) => it.key))
  const isEntry = (id: string) => keys.has(id)
  const adder = onAdd && `Add ${one}`

  const nodes: TreeNode[] = [
    ...(lead ? [{ id: lead.key, label: lead.label, icon: lead.icon, fixed: true }] : []),
    ...entries.map((entry) => ({
      id: entry.key,
      label: entry.key,
      icon: entry.icon,
      title: entry.title,
      detail: entry.detail,
      dataPath: fieldPath([list, entry.key]),
    })),
  ]

  const menu = (id: string, actions: { rename: (id: string) => void }): MenuItem[] => {
    if (!isEntry(id)) return onAdd && adder ? [{ label: `${adder}…`, run: onAdd }] : []
    const items: MenuItem[] = []
    if (onRename) items.push({ label: 'Rename', shortcut: 'F2', run: () => actions.rename(id) })
    if (onDuplicate)
      items.push({ label: 'Duplicate', shortcut: 'Mod+D', run: () => onDuplicate(id) })
    if (onDelete) {
      if (items.length > 0) items.push('separator')
      items.push({ label: 'Delete', shortcut: 'Delete', danger: true, run: () => onDelete(id) })
    }
    return items
  }

  return (
    <Pane
      title={title}
      {...pane}
      actions={onAdd && adder && <IconButton icon="plus" label={adder} onClick={onAdd} />}
    >
      <Tree
        label={title}
        nodes={nodes}
        selected={selected}
        selectOnFocus
        onSelect={onSelect}
        isExpanded={() => false}
        onExpand={() => {}}
        rename={(id) =>
          onRename && isEntry(id)
            ? {
                initial: id,
                validate: (value) => nameProblem(value, value !== id && keys.has(value), one, rule),
                commit: (next) => {
                  if (next !== id) onRename(id, next)
                },
              }
            : null
        }
        onDelete={onDelete && ((id) => isEntry(id) && onDelete(id))}
        onDuplicate={onDuplicate && ((id) => isEntry(id) && onDuplicate(id))}
        menu={menu}
        empty={empty}
      />
    </Pane>
  )
}
