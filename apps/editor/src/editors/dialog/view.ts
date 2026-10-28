/** The dialog editor's per-document view: the selected entries of its body, inputs and buttons. */
import type { ViewStateSpec } from '@/editors/contributions'
import type { ListName } from './ops'

/** One entry of one of a dialog's lists. */
export interface DialogEntry {
  list: ListName
  index: number
}

/** `buttons:1`: an entry's key, which is also its row's id in the outline. */
export const entryKey = (entry: DialogEntry) => `${entry.list}:${entry.index}`

export const DIALOG_VIEW: ViewStateSpec<object, DialogEntry> = {
  initial: {},
  key: entryKey,
}
