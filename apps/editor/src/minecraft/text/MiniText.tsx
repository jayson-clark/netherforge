/**
 * MiniMessage drawn as styled spans. Lines come from format's `layoutText`
 * when the caller has them (dialogs, wrapped text), so the preview breaks
 * exactly where the game does; otherwise the text is one line.
 *
 * A `<glyph:ui/coin>` tag, or a character that is a pack glyph's
 * (`compileResourcePacks` numbers them), draws as its picture, sized and raised by
 * the glyph's own height and ascent, which is what the client does with the
 * default font's bitmap providers. A tag naming no glyph draws nothing, as
 * on the server.
 *
 * A `<space:N>` moves what follows N pixels; an `<at:N>` draws what it holds
 * N pixels from where it is, taking no width, its `align` edge there. The
 * preview counts N from the cursor whatever its `from` says: the line's
 * width in this font isn't the game's.
 */
import type { CSSProperties, ReactNode } from 'react'
import type { TextLine } from '@/core/format'
import { cx } from '@/ui/cx'
import styles from './MiniText.module.css'
import { runsIn, styledChars, type TextRun, type TextStyle } from './minimessage'

/** A pack glyph as the text renderer needs it. */
export interface GlyphImage {
  url: string
  height: number
  ascent: number
  /** Its advance in text, when its picture was measured. */
  advance: number | null
}

/** Pack glyphs by character and by `<pack>/<key>` reference (`useGlyphMap`). */
export type GlyphMap = Record<string, GlyphImage>

function GlyphPicture({ glyph, scale }: { glyph: GlyphImage; scale: number }) {
  return (
    <img
      className={styles.glyph}
      src={glyph.url}
      alt=""
      style={{
        height: glyph.height * scale,
        // Drawn `ascent` above the baseline; the baseline is the line's.
        verticalAlign: (glyph.ascent - glyph.height) * scale,
        marginRight: scale,
      }}
    />
  )
}

function cssOf(style: TextStyle): CSSProperties {
  const decorations = [
    style.underlined ? 'underline' : '',
    style.strikethrough ? 'line-through' : '',
  ].filter(Boolean)
  return {
    color: style.color,
    fontWeight: style.bold ? 700 : undefined,
    fontStyle: style.italic ? 'italic' : undefined,
    textDecoration: decorations.length ? decorations.join(' ') : undefined,
  }
}

const NOISE = '#%&@?$0123456789ABCDEF'

/** `<obfuscated>` as fixed noise: the shape without the flicker. */
const scramble = (text: string) =>
  [...text].map((c) => (c === ' ' ? ' ' : NOISE[c.charCodeAt(0) % NOISE.length])).join('')

function Run({ run, glyphs, scale }: { run: TextRun; glyphs?: GlyphMap; scale: number }) {
  const { text, style, glyph, move, overlay } = run
  if (move !== undefined)
    return <span className={styles.move} style={{ marginLeft: move * scale }} />
  if (overlay) {
    const shift = { left: undefined, center: 'translateX(-50%)', right: 'translateX(-100%)' }[
      overlay.at.align as 'left' | 'center' | 'right'
    ]
    return (
      <span className={styles.overlay} style={{ left: overlay.at.x * scale }}>
        <span className={styles.held} style={{ transform: shift }}>
          <Runs runs={overlay.runs} glyphs={glyphs} scale={scale} />
        </span>
      </span>
    )
  }
  if (glyph !== undefined) {
    const image = glyphs?.[glyph]
    return image ? <GlyphPicture glyph={image} scale={scale} /> : null
  }
  const shown = style.obfuscated ? scramble(text) : text
  if (!glyphs) return <span style={cssOf(style)}>{shown}</span>
  // Split out glyph characters so each can be its picture.
  const parts: (string | GlyphImage)[] = []
  for (const char of shown) {
    const glyph = glyphs[char]
    if (glyph) parts.push(glyph)
    else if (typeof parts[parts.length - 1] === 'string') parts[parts.length - 1] += char
    else parts.push(char)
  }
  return (
    <span style={cssOf(style)}>
      {parts.map((part, index) =>
        typeof part === 'string' ? part : <GlyphPicture key={index} glyph={part} scale={scale} />,
      )}
    </span>
  )
}

function Runs({ runs, glyphs, scale }: { runs: TextRun[]; glyphs?: GlyphMap; scale: number }) {
  return (
    <>
      {runs.map((run, index) => (
        <Run key={index} run={run} glyphs={glyphs} scale={scale} />
      ))}
    </>
  )
}

export function MiniText({
  text,
  lines,
  glyphs,
  scale = 1,
  base,
  className,
  align,
}: {
  text: string
  /** From `layoutText`; absent draws the text as one line. */
  lines?: TextLine[]
  glyphs?: GlyphMap
  /** CSS pixels per GUI pixel. */
  scale?: number
  /** The style before any tag (an item name's white, a lore line's purple). */
  base?: TextStyle
  className?: string
  align?: 'left' | 'center'
}) {
  const chars = styledChars(text, base)
  const shown = lines ?? [{ width: 0, start: 0, end: text.length }]
  return (
    <span
      className={cx(styles.text, className)}
      style={
        {
          '--mini-scale': scale,
          textAlign: align,
          fontSize: 8 * scale,
          lineHeight: `${9 * scale}px`,
        } as CSSProperties
      }
    >
      {shown.map((line, index) => (
        <span key={index} className={styles.line} style={{ minHeight: 9 * scale }}>
          <Runs runs={runsIn(chars, line.start, line.end)} glyphs={glyphs} scale={scale} />
        </span>
      ))}
    </span>
  )
}

/** Text shown on the background it has in game: a window's grey, or [dark] (chat, a title). */
export function TextPreview({ dark, children }: { dark?: boolean; children: ReactNode }) {
  return <span className={cx(styles.preview, dark && styles.dark)}>{children}</span>
}
