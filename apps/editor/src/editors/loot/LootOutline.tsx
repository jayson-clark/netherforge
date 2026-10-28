/**
 * A loot table in the outline dock: its pools (a `KeyedList`, named as ids
 * are). Picking one brings up the table's tab with that pool.
 */
import { usePane } from '@/state/useExpanded'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import type { LootTableFile } from '@/core/format'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { ID_NAMES, KeyedList } from '@/editors/shared/KeyedList'
import { Badge } from '@/ui/Badge'
import type { OutlineProps } from '@/editors/contributions'
import { addPool, duplicatePool, poolNames, rangeLabel, removePool, renamePool } from './ops'

export function LootOutline({ path, files }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<LootTableFile>(path)
  const pane = usePane('loot-pools')
  const view = useView('loot_table', path)
  const pick = usePrimary('loot_table', path)
  if (!model) return files
  const names = poolNames(model)
  const selected = pick && names.includes(pick.pool) ? pick.pool : (names[0] ?? null)

  const show = (pool: string | null) => {
    if (pool === null) view.select()
    else view.select({ pool })
    void workspace.getState().openFile(path)
  }

  return (
    <>
      <KeyedList
        title="Pools"
        one="pool"
        pane={pane}
        list="pools"
        rule={ID_NAMES}
        entries={names.map((name) => {
          const pool = model.pools![name]!
          return {
            key: name,
            icon: 'list',
            title: `${pool.entries?.length ?? 0} entries`,
            detail: <Badge>×{rangeLabel(pool.rolls, 1)}</Badge>,
          }
        })}
        selected={selected}
        onSelect={show}
        onAdd={() => {
          let name = ''
          edit((draft) => {
            name = addPool(draft)
          })
          show(name)
        }}
        onRename={(from, to) => {
          edit((draft) => renamePool(draft, from, to))
          if (selected === from) show(to)
        }}
        onDuplicate={(name) => {
          let copy: string | null = null
          edit((draft) => {
            copy = duplicatePool(draft, name)
          })
          if (copy) show(copy)
        }}
        onDelete={(name) => {
          edit((draft) => removePool(draft, name))
          if (selected === name) show(names.find((it) => it !== name) ?? null)
        }}
      />
      {files}
    </>
  )
}
