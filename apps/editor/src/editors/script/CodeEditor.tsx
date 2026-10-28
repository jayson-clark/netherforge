/**
 * A Monaco editor bound to one project file's text in the workspace store.
 * Text documents (Lua, raw JSON) use Monaco's own undo; the store holds the
 * text and the dirty flag.
 */
import { useEffect, useRef, useState } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { readOnlyReason } from '@/core/store/project'
import { useBreakpoints } from './breakpoints'
import { blurCode, focusCode } from './focusedCode'
import { disposeModelsExcept, modelFor, monaco, setupMonaco, THEME } from './monaco'
import styles from './CodeEditor.module.css'

export function CodeEditor({ path, language }: { path: string; language: 'lua' | 'json' | 'sql' }) {
  const { workspace, backend } = useApp()
  const text = useWorkspace((s) => s.docs[path]?.text)
  const focus = useWorkspace((s) => (s.focus?.path === path ? s.focus : null))
  const readOnly = useWorkspace((s) => readOnlyReason(s.readOnly, path) !== null)
  const host = useRef<HTMLDivElement>(null)
  /** This component's editor, once Monaco's services are up. */
  const [editor, setEditor] = useState<monaco.editor.IStandaloneCodeEditor | null>(null)
  /** Set while we push the store's text into Monaco, so the change isn't echoed back. */
  const syncing = useRef(false)

  // One editor per mounted component.
  useEffect(() => {
    let instance: monaco.editor.IStandaloneCodeEditor | null = null
    const listeners: monaco.IDisposable[] = []
    let unmounted = false
    void setupMonaco({ workspace, backend }).then(() => {
      if (unmounted || !host.current) return
      const it = create(host.current, language)
      instance = it
      // The Edit menu's Undo, Cut, Paste and Select All act here while it has focus.
      listeners.push(
        it.onDidFocusEditorText(() => focusCode(it, (id) => it.trigger('menu', id, null))),
        it.onDidBlurEditorText(() => blurCode(it)),
      )
      setEditor(it)
    })
    return () => {
      unmounted = true
      for (const listener of listeners) listener.dispose()
      if (instance) {
        blurCode(instance)
        instance.dispose()
      }
      // Keep models of files that are still open elsewhere.
      disposeModelsExcept(new Set(Object.keys(workspace.getState().docs)))
    }
  }, [workspace, backend, language])

  // Show this file's model; keep it in step with the store.
  useEffect(() => {
    const initial = workspace.getState().docs[path]?.text
    if (!editor || initial === undefined) return
    const model = modelFor(path, initial, language)
    if (editor.getModel() !== model) editor.setModel(model)
    const listener = model.onDidChangeContent(() => {
      if (!syncing.current) workspace.getState().setText(path, model.getValue())
    })
    const save = editor.addAction({
      id: 'netherforge.save',
      label: 'Save',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS],
      run: () => void workspace.getState().save(path),
    })
    return () => {
      listener.dispose()
      save.dispose()
    }
  }, [editor, path, language, workspace])

  // The store changed underneath (reload, take theirs, a canonical save).
  useEffect(() => {
    const model = editor?.getModel()
    if (!model || text === undefined || model.getValue() === text) return
    syncing.current = true
    model.pushEditOperations([], [{ range: model.getFullModelRange(), text }], () => null)
    model.pushStackElement()
    syncing.current = false
  }, [editor, text])

  // A dependency's file shows as it is (a disabled fieldset doesn't reach Monaco's own input).
  useEffect(() => {
    editor?.updateOptions({ readOnly })
  }, [editor, readOnly])

  // Breakpoints and the line the debugger is stopped at; after the model is this file's.
  useBreakpoints(editor, path, language === 'lua')

  useEffect(() => {
    if (!editor || !focus?.line) return
    const position = { lineNumber: focus.line, column: focus.column ?? 1 }
    editor.setPosition(position)
    editor.revealLineInCenter(focus.line)
    editor.focus()
  }, [editor, focus])

  return <div className={styles.editor} ref={host} data-path={path} />
}

/** A Lua file's tab. */
export const LuaEditor = ({ path }: { path: string }) => <CodeEditor path={path} language="lua" />
/** A migration's tab (SQL, highlighted by Monaco's own tokenizer; no SQL language service is loaded). */
export const SqlEditor = ({ path }: { path: string }) => <CodeEditor path={path} language="sql" />
/** A JSON file's tab, as text. */
export const JsonEditor = ({ path }: { path: string }) => <CodeEditor path={path} language="json" />

function create(host: HTMLElement, language: 'lua' | 'json' | 'sql') {
  return monaco.editor.create(host, {
    theme: THEME,
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    tabSize: 2,
    scrollBeyondLastLine: false,
    fixedOverflowWidgets: true,
    ariaLabel: 'Code editor',
    // lua-language-server completes names inside strings too (`this:node("`, `require("`).
    ...(language === 'lua'
      ? { quickSuggestions: { other: true, comments: false, strings: true } }
      : {}),
    wordBasedSuggestions: 'off',
  })
}

/** Read-only side-by-side diff, theirs (disk) on the left and mine on the right. */
export function DiffView({ path, theirs, mine }: { path: string; theirs: string; mine: string }) {
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let disposed = false
    let cleanup = () => {}
    void setupMonaco().then(() => {
      if (disposed || !host.current) return
      cleanup = showDiff(host.current, path, theirs, mine)
    })
    return () => {
      disposed = true
      cleanup()
    }
  }, [path, theirs, mine])
  return <div className={styles.diff} ref={host} />
}

function showDiff(host: HTMLElement, path: string, theirs: string, mine: string) {
  const language = path.endsWith('.lua') ? 'lua' : path.endsWith('.sql') ? 'sql' : 'json'
  const original = monaco.editor.createModel(theirs, language)
  const modified = monaco.editor.createModel(mine, language)
  const diff = monaco.editor.createDiffEditor(host, {
    theme: THEME,
    readOnly: true,
    automaticLayout: true,
    renderSideBySide: true,
    minimap: { enabled: false },
  })
  diff.setModel({ original, modified })
  return () => {
    diff.dispose()
    original.dispose()
    modified.dispose()
  }
}
