/**
 * What every resource editor (centity, menu, dialog, pack) shares: its
 * document's model, how to edit it, and the way out to the raw JSON.
 */
import { useApp, useWorkspace } from '@/state/providers'
import { modelOf, type Doc } from '@/core/store/documents'
import { Button } from '@/ui/Button'

export interface ModelDoc<T> {
  doc: Doc | undefined
  /** Null while the document is edited as JSON or doesn't parse: show `RawDocView` then. */
  model: T | null
  /** One undoable change through the store (an Immer draft; see `Workspace.edit`). */
  edit: (recipe: (draft: T) => void) => void
}

export function useModelDoc<T>(path: string): ModelDoc<T> {
  const { workspace } = useApp()
  const doc = useWorkspace((s) => s.docs[path])
  return {
    doc,
    model: modelOf<T>(doc),
    edit: (recipe) => workspace.getState().edit<T>(path, recipe),
  }
}

/** The toolbar button that switches a document to its JSON. */
export function EditAsJson({ path }: { path: string }) {
  const { workspace } = useApp()
  return (
    <Button size="small" onClick={() => workspace.getState().setRaw(path, true)}>
      Edit as JSON
    </Button>
  )
}
