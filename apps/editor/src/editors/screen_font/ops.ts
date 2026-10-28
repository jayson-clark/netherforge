/**
 * What the screen font editor does to a `font.json` (docs/format/screen-font.md), apart from React. A pixels
 * font's glyphs are rows of `#` (a pixel) and `.` (none), every row of a glyph as wide and every glyph as tall.
 */
import type { ScreenFontFile, ScreenFontType } from '@/core/format'

export const INK = '#'
export const BLANK = '.'
/** The most rows or columns a glyph has (format's `ScreenFontFile.MAX_SIZE`). */
export const MAX_SIDE = 128

/** How tall a pixels font's glyphs are: its first glyph's rows, or 7 for a font with none yet. */
export function heightOf(file: ScreenFontFile): number {
  const first = Object.values(file.glyphs ?? {})[0]
  return first?.length ?? 7
}

/** Glyphs in reading order: by code point. */
export function glyphOrder(file: ScreenFontFile): string[] {
  return Object.keys(file.glyphs ?? {}).sort(
    (a, b) => (a.codePointAt(0) ?? 0) - (b.codePointAt(0) ?? 0),
  )
}

/** Switches to another type of font, dropping what only the old one had. */
export function setType(draft: ScreenFontFile, type: ScreenFontType) {
  if (draft.type === type) return
  draft.type = type
  delete draft.size
  delete draft.antialias
  if (type !== 'bitmap') delete draft.chars
  if (type !== 'pixels') delete draft.glyphs
  if (type === 'pixels') {
    delete draft.file
    draft.glyphs ??= {}
  }
}

/** Adds [char] as an empty glyph as tall as the rest, [width] wide; nothing when it's there or isn't one character. */
export function addGlyph(draft: ScreenFontFile, char: string, width = 5) {
  if ([...char].length !== 1) return
  const glyphs = (draft.glyphs ??= {})
  if (glyphs[char]) return
  glyphs[char] = Array.from({ length: heightOf(draft) }, () => BLANK.repeat(width))
}

export function removeGlyph(draft: ScreenFontFile, char: string) {
  if (draft.glyphs) delete draft.glyphs[char]
}

/** Turns one pixel of [char] on or off. */
export function setPixel(draft: ScreenFontFile, char: string, x: number, y: number, on: boolean) {
  const rows = draft.glyphs?.[char]
  const row = rows?.[y]
  if (!rows || row === undefined || x < 0 || x >= row.length) return
  rows[y] = row.slice(0, x) + (on ? INK : BLANK) + row.slice(x + 1)
}

/** Makes [char] [width] columns wide: new columns blank on the right, or the rightmost cut. */
export function setWidth(draft: ScreenFontFile, char: string, width: number) {
  const rows = draft.glyphs?.[char]
  if (!rows) return
  const w = Math.max(1, Math.min(MAX_SIDE, Math.round(width)))
  for (let i = 0; i < rows.length; i++) rows[i] = (rows[i] ?? '').padEnd(w, BLANK).slice(0, w)
}

/** Makes every glyph [height] rows tall: new rows blank at the bottom, or the bottom ones cut. */
export function setHeight(draft: ScreenFontFile, height: number) {
  const h = Math.max(1, Math.min(MAX_SIDE, Math.round(height)))
  for (const rows of Object.values(draft.glyphs ?? {})) {
    const width = rows[0]?.length ?? 1
    while (rows.length < h) rows.push(BLANK.repeat(width))
    rows.length = h
  }
}
