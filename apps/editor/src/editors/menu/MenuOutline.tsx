/**
 * A menu in the outline dock: its filled slots, in order, each named by its
 * item. Picking one picks it in the editor (and shows the menu's tab);
 * Delete empties it. Slots past the window are flagged.
 */
import type { MenuFile } from '@/core/format'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { usePane, useTreeExpansion } from '@/state/useExpanded'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { Badge } from '@/ui/Badge'
import { Pane } from '@/ui/Pane'
import { Tree, type TreeNode } from '@/ui/Tree'
import { Empty } from '@/ui/text'
import { shapeOf } from '@/minecraft/window/window'
import { filledSlots, setSlot, slotLabel, slotOf } from './slots'
import type { OutlineProps } from '@/editors/contributions'

export function MenuOutline({ path, files }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<MenuFile>(path)
  const selected = usePrimary('menu', path)
  const view = useView('menu', path)
  const pane = usePane('menu-slots')
  const expansion = useTreeExpansion('menu-slots', () => true)
  if (!model) return files
  const ws = () => workspace.getState()
  const size = shapeOf(model).size

  const nodes: TreeNode[] = filledSlots(model).map((index) => ({
    id: String(index),
    label: slotLabel(index, slotOf(model, index)),
    icon: 'slot',
    detail: index >= size ? <Badge title="Past the window's size">outside</Badge> : undefined,
  }))

  const select = (id: string) => {
    view.select(Number(id))
    void ws().openFile(path)
  }
  const clear = (id: string) => {
    edit((draft) => setSlot(draft, Number(id), undefined))
    view.mapSelection((it) => (it === Number(id) ? null : it))
  }

  return (
    <>
      <Pane title="Slots" {...pane}>
        <Tree
          label="Slots"
          nodes={nodes}
          selected={selected === null ? null : String(selected)}
          onSelect={select}
          selectOnFocus
          {...expansion}
          onDelete={clear}
          menu={(id) =>
            id
              ? [
                  { label: 'Show in window', run: () => select(id) },
                  'separator',
                  { label: 'Empty slot', shortcut: 'Delete', danger: true, run: () => clear(id) },
                ]
              : []
          }
          empty={<Empty>No slot has an item yet.</Empty>}
        />
      </Pane>
      {files}
    </>
  )
}
