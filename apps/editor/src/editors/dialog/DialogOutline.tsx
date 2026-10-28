/**
 * A dialog in the outline dock: its body, inputs and buttons, each list a
 * group in screen order. Picking an entry picks it in the editor (and shows
 * the dialog's tab); Delete removes it; dragging an entry onto another in the
 * same list moves it there.
 */
import type { DialogFile } from '@/core/format'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { usePane, useTreeExpansion } from '@/state/useExpanded'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { Badge } from '@/ui/Badge'
import { Pane } from '@/ui/Pane'
import { Tree, type TreeNode } from '@/ui/Tree'
import { entryLabel, LISTS, moveEntry, removeEntry, type ListName } from './ops'
import type { OutlineProps } from '@/editors/contributions'
import { entryKey, type DialogEntry } from './view'

const ICONS: Record<ListName, TreeNode['icon']> = {
  body: 'file',
  inputs: 'edit',
  buttons: 'box',
}

export function DialogOutline({ path, files }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<DialogFile>(path)
  const selected = usePrimary('dialog', path)
  const view = useView('dialog', path)
  const pane = usePane('dialog-elements')
  const expansion = useTreeExpansion(`dialog:${path}`, () => true)
  if (!model) return files
  const ws = () => workspace.getState()

  // Each entry's row id is its key; a group row (`buttons`) is no entry.
  const entries = new Map<string, DialogEntry>()
  const nodes: TreeNode[] = LISTS.map(({ list, title }) => {
    const items = (model[list] ?? []) as Parameters<typeof entryLabel>[1][]
    return {
      id: list,
      label: title,
      icon: 'folder',
      fixed: true,
      detail: <Badge>{items.length}</Badge>,
      // Entries take drops (to move there), so they're containers with nothing in them.
      children: items.map((item, index) => {
        const entry = { list, index }
        entries.set(entryKey(entry), entry)
        return {
          id: entryKey(entry),
          label: entryLabel(list, item),
          icon: ICONS[list],
          children: [],
        }
      }),
    }
  })

  const parseEntry = (id: string) => entries.get(id) ?? null
  const select = (id: string) => {
    const entry = parseEntry(id)
    if (!entry) return
    view.select(entry)
    void ws().openFile(path)
  }
  const remove = (id: string) => {
    const entry = parseEntry(id)
    if (!entry) return
    edit((draft) => removeEntry(draft, entry.list, entry.index))
    view.mapSelection((it) => (entryKey(it) === id ? null : it))
  }
  const move = (id: string, to: number) => {
    const entry = parseEntry(id)
    if (!entry || to === entry.index) return
    edit((draft) => moveEntry(draft, entry.list, entry.index, to))
    view.mapSelection((it) => (entryKey(it) === id ? { list: entry.list, index: to } : it))
  }
  const count = (list: ListName) => (model[list] ?? []).length

  return (
    <>
      <Pane title="Elements" {...pane}>
        <Tree
          label="Dialog elements"
          nodes={nodes}
          selected={selected && entryKey(selected)}
          onSelect={select}
          selectOnFocus
          {...expansion}
          onDelete={remove}
          canMove={(id, target) => {
            const from = parseEntry(id)
            const to = target ? parseEntry(target) : null
            return !!from && !!to && from.list === to.list
          }}
          onMove={(id, target) => {
            const to = target ? parseEntry(target) : null
            if (to) move(id, to.index)
          }}
          menu={(id) => {
            const entry = parseEntry(id)
            if (!entry) return []
            return [
              {
                label: 'Move up',
                disabled: entry.index === 0,
                run: () => move(id, entry.index - 1),
              },
              {
                label: 'Move down',
                disabled: entry.index >= count(entry.list) - 1,
                run: () => move(id, entry.index + 1),
              },
              'separator',
              { label: 'Remove', shortcut: 'Delete', danger: true, run: () => remove(id) },
            ]
          }}
        />
      </Pane>
      {files}
    </>
  )
}
