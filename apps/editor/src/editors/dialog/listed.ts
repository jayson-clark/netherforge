import type { DialogFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import { useWorkspace } from '@/state/providers'

/** The dialogs a dialog_list names, read from disk or open documents, for their labels. */
export function useListedDialogs(ids: string[]): Record<string, DialogFile | undefined> {
  const texts = useWorkspace((s) => s.diskTexts)
  const files = useWorkspace((s) => s.files)
  const out: Record<string, DialogFile | undefined> = {}
  for (const id of ids) {
    try {
      out[id] = JSON.parse(texts[mainFileIn(files, 'dialog', id)] ?? 'null') ?? undefined
    } catch {
      out[id] = undefined
    }
  }
  return out
}
