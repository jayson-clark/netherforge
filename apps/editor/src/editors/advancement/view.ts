/**
 * The advancement editor's per-document view: which criterion is selected
 * (the inspector shows its trigger). Nothing else is kept.
 */
import type { ViewStateSpec } from '@/editors/contributions'

export const ADVANCEMENT_VIEW: ViewStateSpec<Record<string, never>, string> = {
  initial: {},
  key: (name) => name,
}
