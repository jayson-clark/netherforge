/**
 * The pack editor's per-document view: the selected gallery entries (a
 * skin, glyph, item model or tooltip by key, a texture by its path under
 * `textures/`, a sound by its event key).
 */
import type { ViewStateSpec } from '@/editors/contributions'
import { selectionForFile, type ResourcePackPick } from './ops'

/** `skins:shop`: a pick's key, which is also its row's id in the outline. */
export const pickKey = (pick: ResourcePackPick) => `${pick.kind}:${pick.key}`

export const RESOURCE_PACK_VIEW: ViewStateSpec<object, ResourcePackPick> = {
  initial: {},
  key: pickKey,
  opensInside: selectionForFile,
}
