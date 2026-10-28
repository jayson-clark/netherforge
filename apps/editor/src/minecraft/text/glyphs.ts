/**
 * The project's pack glyphs as text draws and measures them: pictures by
 * character and by reference, and their advances for `layoutText`.
 */
import { useMemo } from 'react'
import { resourcePackTexturePath } from '@/core/paths'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useReferenceNamespace,
  useProjectFileUrl,
} from '@/state/useResourcePacks'
import type { GlyphMap } from './MiniText'

/**
 * Every glyph's picture, by its character and by the ways a `<glyph:…>`
 * tag in the document on show can name it: in full (`shop:ui/coin`,
 * `library:gems/gem`), and bare (`ui/coin`) for its own namespace's packs
 * (the project's, or a package's for a package's document). For drawing
 * glyphs inside text.
 */
export function useGlyphMap(): GlyphMap {
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const url = useProjectFileUrl()
  return useMemo(() => {
    const out: GlyphMap = {}
    for (const [id, pack] of Object.entries(compiled.resourcePacks)) {
      // The packs store keys the project's packs bare and a package's as `ns:id`.
      const full = id.includes(':') ? id : `${home}:${id}`
      const own = full.startsWith(`${namespace}:`) ? full.slice(namespace.length + 1) : null
      for (const [key, glyph] of Object.entries(pack.glyphs)) {
        const image = {
          url: url(resourcePackTexturePath(id, glyph.texture)),
          height: glyph.height,
          ascent: glyph.ascent,
          advance: glyph.advance ?? null,
        }
        out[glyph.char] = image
        out[`${full}/${key}`] = image
        if (own !== null) out[`${own}/${key}`] = image
      }
    }
    return out
  }, [compiled, namespace, home, url])
}

/** Pack glyphs' advances by reference, for measuring `<glyph:…>` tags (`layoutText`). */
export function glyphAdvancesOf(glyphs: GlyphMap): Record<string, number> {
  const out: Record<string, number> = {}
  for (const [ref, glyph] of Object.entries(glyphs)) {
    // Characters are one code point; references have their slash. A glyph
    // whose picture wasn't measured is left out: format decides what that's worth.
    if (ref.includes('/') && glyph.advance !== null) out[ref] = glyph.advance
  }
  return out
}
