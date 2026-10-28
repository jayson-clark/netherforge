/** Typing in a field or Monaco: leave the key to it (the app's and the editors' shortcuts skip it). */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  if (target.closest('.monaco-editor')) return true
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}
