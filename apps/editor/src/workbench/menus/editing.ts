/**
 * The Edit menu's text commands for whatever has focus. A menu click leaves
 * focus where it was (the macOS menu bar's always does, and the in-app one
 * never takes it), so the focused field or code editor is the target.
 */
import { focusedCode } from '@/editors/script/focusedCode'
import { isEditableTarget } from '@/ui/editable'
import { undoesDocument } from '@/workbench/shortcuts'

export type TextAction = 'undo' | 'redo' | 'cut' | 'copy' | 'paste' | 'selectAll'

const MONACO_ACTION: Record<TextAction, string> = {
  undo: 'undo',
  redo: 'redo',
  cut: 'editor.action.clipboardCutAction',
  copy: 'editor.action.clipboardCopyAction',
  paste: 'editor.action.clipboardPasteAction',
  selectAll: 'editor.action.selectAll',
}

/** Does [action] in the focused code editor or text field; false when neither has focus. */
export function editText(action: TextAction): boolean {
  const code = focusedCode()
  if (code) {
    code(MONACO_ACTION[action])
    return true
  }
  const field = document.activeElement
  if (!isEditableTarget(field)) return false
  // A committed inspector field leaves undo to the document.
  if ((action === 'undo' || action === 'redo') && undoesDocument(field)) return false
  if (
    action === 'selectAll' &&
    (field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement)
  ) {
    field.select()
  } else if (action === 'paste') {
    // Browsers refuse execCommand('paste'); reading the clipboard is allowed from a menu click.
    void navigator.clipboard
      ?.readText()
      .then((text) => document.execCommand('insertText', false, text))
      .catch(() => {})
  } else {
    document.execCommand(action)
  }
  return true
}
