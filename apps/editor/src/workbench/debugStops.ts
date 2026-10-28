/** What the workbench does when the debugger stops the dev server. */
import { useEffect } from 'react'
import { useApp } from '@/state/providers'
import { showPanel } from './contributions'

/**
 * Brings a stop forward: the Debug panel shows, and the stop's frame's file
 * opens at its line. A frame picked in the panel opens its file from there.
 */
export function useFollowDebugStops() {
  const { debug, workspace, layout } = useApp()
  useEffect(
    () =>
      debug.subscribe((state, previous) => {
        if (state.session === 'paused' && previous.session !== 'paused') showPanel(layout, 'debug')
        // The stop's own selection: none before it, one now.
        if (previous.frameId !== null || state.frameId === null) return
        const frame = state.frames.find((it) => it.id === state.frameId)
        if (frame?.source?.path)
          void workspace.getState().openFile(frame.source.path, { line: frame.line })
      }),
    [debug, workspace, layout],
  )
}
