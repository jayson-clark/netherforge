/**
 * The debugger in a script's editor: breakpoints in the glyph margin (a
 * click there or F9 toggles one on a line), drawn from the debug store and
 * moved with the text as it's edited (Monaco tracks each decoration; after
 * an edit their lines go back to the store, which sends them on), and the
 * line the server is stopped at in this file (the selected frame's), marked
 * whole.
 */
import { useEffect } from 'react'
import { useApp, useDebug } from '@/state/providers'
import { monaco } from './monaco'
import styles from './CodeEditor.module.css'

const NO_LINES: number[] = []

export function useBreakpoints(
  editor: monaco.editor.IStandaloneCodeEditor | null,
  path: string,
  enabled: boolean,
) {
  const { debug } = useApp()
  const lines = useDebug((s) => s.breakpoints[path] ?? NO_LINES)
  const active = useDebug((s) => s.active)
  const frame = useDebug((s) => s.frames.find((it) => it.id === s.frameId) ?? null)
  const stoppedLine = frame?.source?.path === path ? frame.line : null

  // The margin, the click and F9, for this file's model.
  useEffect(() => {
    if (!editor || !enabled) return
    editor.updateOptions({ glyphMargin: true })
    const click = editor.onMouseDown((event) => {
      if (event.target.type !== monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN) return
      const line = event.target.position?.lineNumber
      if (line) debug.getState().toggleBreakpoint(path, line)
    })
    const toggle = editor.addAction({
      id: 'netherforge.toggleBreakpoint',
      label: 'Toggle Breakpoint',
      keybindings: [monaco.KeyCode.F9],
      run: (it) => {
        const line = it.getPosition()?.lineNumber
        if (line) debug.getState().toggleBreakpoint(path, line)
      },
    })
    return () => {
      click.dispose()
      toggle.dispose()
    }
  }, [editor, enabled, path, debug])

  // The breakpoints, following edits.
  useEffect(() => {
    if (!editor || !enabled) return
    const model = editor.getModel()
    if (!model) return
    const decorations = editor.createDecorationsCollection(
      lines.map((line) => ({
        range: new monaco.Range(line, 1, line, 1),
        options: {
          glyphMarginClassName: active ? styles.breakpoint : styles.breakpointOff,
          glyphMarginHoverMessage: { value: active ? 'Breakpoint' : 'Breakpoint (deactivated)' },
          stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
        },
      })),
    )
    const edited = model.onDidChangeContent(() => {
      const now = decorations.getRanges().map((range) => range.startLineNumber)
      debug.getState().setBreakpointLines(path, now)
    })
    return () => {
      edited.dispose()
      decorations.clear()
    }
  }, [editor, enabled, path, lines, active, debug])

  // Where the server is stopped, in this file.
  useEffect(() => {
    if (!editor || !enabled || stoppedLine === null) return
    const decorations = editor.createDecorationsCollection([
      {
        range: new monaco.Range(stoppedLine, 1, stoppedLine, 1),
        options: {
          isWholeLine: true,
          className: styles.stoppedLine,
          glyphMarginClassName: styles.stoppedArrow,
          glyphMarginHoverMessage: { value: 'Paused here' },
        },
      },
    ])
    return () => decorations.clear()
  }, [editor, enabled, stoppedLine])
}
