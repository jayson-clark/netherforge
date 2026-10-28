/**
 * A particle effect in the outline dock: the effect itself, then its
 * emitters (a `KeyedList`). Picking one brings up the effect's tab and shows
 * it in the inspector.
 */
import { usePane } from '@/state/useExpanded'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import type { ParticleEffectFile } from '@/core/format'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { KeyedList } from '@/editors/shared/KeyedList'
import { Badge } from '@/ui/Badge'
import { ask } from '@/ui/dialogs'
import {
  addEmitter,
  duplicateEmitter,
  PARTICLE_ID,
  removeEmitter,
  renameEmitter,
  type ParticlePick,
} from './ops'
import type { OutlineProps } from '@/editors/contributions'
import { pickOf } from './view'

/** The effect's own row: not an emitter, so it can't be renamed, moved or deleted. */
const EFFECT = ''

export function ParticleOutline({ path, files }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<ParticleEffectFile>(path)
  const pane = usePane('particle-emitters')
  const view = useView('particle_effect', path)
  const pick = pickOf(usePrimary('particle_effect', path))
  if (!model) return files
  const emitters = model.emitters ?? {}
  const names = Object.keys(emitters).sort()
  const selected = pick.kind === 'effect' || !emitters[pick.emitter] ? EFFECT : pick.emitter

  const show = (next: ParticlePick) => {
    if (next.kind === 'effect') view.select()
    else view.select(next)
    void workspace.getState().openFile(path)
  }

  const add = async () => {
    const initial =
      (selected !== EFFECT ? emitters[selected]?.particle : undefined) ??
      names.map((it) => emitters[it]!.particle)[0]
    const particle = await ask.prompt({
      title: 'New emitter',
      label: 'Particle',
      message: 'The particle it spawns, like minecraft:end_rod. You can change it later.',
      initial: initial ?? '',
      confirmLabel: 'Add',
      validate: (value) =>
        PARTICLE_ID.test(value.trim()) ? null : 'A particle id, like minecraft:end_rod',
    })
    if (!particle) return
    let name = ''
    edit((draft) => {
      name = addEmitter(draft, particle.trim())
    })
    show({ kind: 'emitter', emitter: name })
  }

  return (
    <>
      <KeyedList
        title="Emitters"
        one="emitter"
        pane={pane}
        list="emitters"
        lead={{ key: EFFECT, label: 'Effect', icon: 'sparkles' }}
        entries={names.map((name) => ({
          key: name,
          title: emitters[name]!.particle,
          detail: <Badge>{emitters[name]!.rate !== undefined ? 'rate' : 'burst'}</Badge>,
        }))}
        selected={selected}
        onSelect={(id) =>
          show(id === EFFECT ? { kind: 'effect' } : { kind: 'emitter', emitter: id })
        }
        onAdd={() => void add()}
        onRename={(from, to) => {
          edit((draft) => renameEmitter(draft, from, to))
          if (selected === from) show({ kind: 'emitter', emitter: to })
        }}
        onDuplicate={(name) => {
          let copy: string | null = null
          edit((draft) => {
            copy = duplicateEmitter(draft, name)
          })
          if (copy) show({ kind: 'emitter', emitter: copy })
        }}
        onDelete={(name) => {
          edit((draft) => removeEmitter(draft, name))
          if (selected === name) show({ kind: 'effect' })
        }}
      />
      {files}
    </>
  )
}
