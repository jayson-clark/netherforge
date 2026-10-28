/**
 * An advancement in the outline dock: its criteria (a `KeyedList`, named as
 * ids are). Picking one brings up the advancement's tab with it selected.
 */
import { usePane } from '@/state/useExpanded'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import type { AdvancementFile } from '@/core/format'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { ID_NAMES, KeyedList } from '@/editors/shared/KeyedList'
import type { OutlineProps } from '@/editors/contributions'
import {
  addCriterion,
  criterionNames,
  duplicateCriterion,
  removeCriterion,
  renameCriterion,
} from './ops'

export function AdvancementOutline({ path, files }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<AdvancementFile>(path)
  const pane = usePane('advancement-criteria')
  const view = useView('advancement', path)
  const pick = usePrimary('advancement', path)
  if (!model) return files
  const names = criterionNames(model)
  const selected = pick && names.includes(pick) ? pick : (names[0] ?? null)

  const show = (name: string | null) => {
    if (name === null) view.select()
    else view.select(name)
    void workspace.getState().openFile(path)
  }

  return (
    <>
      <KeyedList
        title="Criteria"
        one="criterion"
        pane={pane}
        list="criteria"
        rule={ID_NAMES}
        entries={names.map((name) => ({
          key: name,
          icon: 'check',
          title: model.criteria![name]!.trigger ?? 'Met by a script',
        }))}
        selected={selected}
        onSelect={show}
        onAdd={() => {
          let name = ''
          edit((draft) => {
            name = addCriterion(draft)
          })
          show(name)
        }}
        onRename={(from, to) => {
          edit((draft) => renameCriterion(draft, from, to))
          if (selected === from) show(to)
        }}
        onDuplicate={(name) => {
          let copy: string | null = null
          edit((draft) => {
            copy = duplicateCriterion(draft, name)
          })
          if (copy) show(copy)
        }}
        onDelete={(name) => {
          edit((draft) => removeCriterion(draft, name))
          if (selected === name) show(names.find((it) => it !== name) ?? null)
        }}
      />
      {files}
    </>
  )
}
