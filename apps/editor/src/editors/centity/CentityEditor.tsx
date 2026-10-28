/**
 * The centity editor: the viewport over the timeline, with the inspector in
 * the inspector dock (the node tree is the outline's: `CentityOutline`). A
 * file that doesn't parse (or that the user chose to edit as text) shows as
 * JSON in Monaco instead, with its parse problem.
 */
import { useEffect } from 'react'
import type { CentityFile } from '@/core/format'
import type { History } from '@/core/store/history'
import { useApp } from '@/state/providers'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { isEditableTarget } from '@/ui/editable'
import { Inspector } from './inspector/Inspector'
import { deleteNode } from './ops'
import { AnimationTimeline } from './AnimationTimeline'
import { useView, useViewState } from '@/editors/views'
import { usePose } from './pose'
import { Viewport } from './Viewport'

export function CentityEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { doc, model } = useModelDoc<CentityFile>(path)
  const view = useViewState('centity', path)
  const changeView = useView('centity', path)
  const history = model ? (doc!.history as History<CentityFile>) : null
  const { pose, stale } = usePose(path, history, view.clip, view.time)

  // A problem's JSON path: select the node (or clip) it's about, then focus
  // the inspector field it names.
  useFocusRequests(path, (segments) => {
    if (segments[0] === 'nodes' && typeof segments[1] === 'string') {
      changeView.select(segments[1])
      return segments.slice(2)
    }
    if (segments[0] === 'animations' && typeof segments[1] === 'string') {
      changeView.update({ clip: segments[1], time: 0 })
      return null
    }
    if (
      segments[0] === 'name' ||
      segments[0] === 'script' ||
      segments[0] === 'spawning' ||
      segments[0] === 'body' ||
      segments[0] === 'health' ||
      segments[0] === 'properties'
    ) {
      changeView.select()
      return ['centity', ...segments]
    }
    return []
  })

  // Shortcuts that only make sense here.
  useEffect(() => {
    if (!model) return
    const onKey = (event: KeyboardEvent) => {
      // A tree or field that handled the key already has it.
      if (event.defaultPrevented || isEditableTarget(event.target)) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      const selected = changeView.selection()
      if ((event.key === 'Delete' || event.key === 'Backspace') && selected.length > 0) {
        event.preventDefault()
        workspace.getState().edit<CentityFile>(path, (draft) => {
          for (const node of selected) deleteNode(draft, node)
        })
        changeView.select()
      } else if (event.key === 'w' || event.key === 'e' || event.key === 'r') {
        changeView.update({
          gizmo: event.key === 'w' ? 'translate' : event.key === 'e' ? 'rotate' : 'scale',
        })
      } else if (event.key === ' ' && view.clip) {
        event.preventDefault()
        changeView.update({ playing: !view.playing })
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [model, path, workspace, changeView, view.clip, view.playing])

  if (!doc) return null

  if (!model) return <RawDocView path={path} />

  return (
    <EditorScreen>
      <EditorBar title={model.name ?? path}>
        <EditAsJson path={path} />
      </EditorBar>
      <Viewport path={path} model={model} nodes={pose?.nodes ?? []} stale={stale} />
      <AnimationTimeline
        path={path}
        model={model}
        animations={pose?.animations ?? []}
        nodes={pose?.nodes ?? []}
      />
      <Inspector path={path} model={model} />
    </EditorScreen>
  )
}
