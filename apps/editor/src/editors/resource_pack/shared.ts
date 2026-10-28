/** What the pack editor and its inspectors share: the gallery's labels and what a pick is. */
import { type HudKind, type EntryKind, type ResourcePackPick } from './ops'

export const KIND_LABELS: Record<EntryKind, { title: string; one: string }> = {
  skins: { title: 'Skins', one: 'skin' },
  glyphs: { title: 'Glyphs', one: 'glyph' },
  items: { title: 'Item models', one: 'item model' },
  tooltips: { title: 'Tooltips', one: 'tooltip' },
  equipment: { title: 'Equipment', one: 'equipment look' },
  blocks: { title: 'Blocks', one: 'block look' },
}

export type Pick = ResourcePackPick | null

export const HUD_LABELS: Record<HudKind, string> = { bars: 'bar', icons: 'icons', texts: 'text' }
