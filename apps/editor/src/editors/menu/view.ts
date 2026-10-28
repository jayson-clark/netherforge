/** The menu editor's per-document view: the selected slots, by index. */
import type { ViewStateSpec } from '@/editors/contributions'

export const MENU_VIEW: ViewStateSpec<object, number> = {
  initial: {},
  key: (slot) => String(slot),
}
