/**
 * The loot editor's per-document view: the selected pool or entry (the
 * inspector shows it), and the seed the preview rolls with.
 */
import type { ViewStateSpec } from '@/editors/contributions'
import type { LootPick } from './ops'

export interface LootView {
  /** The preview's seed: the same seed rolls the same, as on the server. */
  seed: number
}

export const LOOT_VIEW: ViewStateSpec<LootView, LootPick> = {
  initial: { seed: 1 },
  key: (pick) => (pick.entry === undefined ? pick.pool : `${pick.pool}#${pick.entry}`),
}
