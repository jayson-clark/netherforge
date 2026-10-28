/**
 * The code editor with keyboard focus, for the Edit menu: a menu click
 * leaves focus where it was, and Undo, Cut or Select All there have to go to
 * Monaco's own actions (its text isn't a field the browser can edit). No
 * Monaco import here, so the menus don't load it.
 */

/** Runs one of Monaco's actions (`undo`, `editor.action.clipboardCutAction`) in that editor. */
export type CodeAction = (id: string) => void

let focused: { owner: object; run: CodeAction } | null = null

export function focusCode(owner: object, run: CodeAction) {
  focused = { owner, run }
}

export function blurCode(owner: object) {
  if (focused?.owner === owner) focused = null
}

export const focusedCode = (): CodeAction | null => focused?.run ?? null
