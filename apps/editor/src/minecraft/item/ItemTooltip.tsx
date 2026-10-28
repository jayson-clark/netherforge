/**
 * An item's tooltip as the game draws it (`tooltipOf`), and a hover version
 * of it for slots: `useItemTooltip` gives a slot pointer handlers and the
 * floating tooltip to render, which follows the pointer like the game's.
 * Native `title` tooltips aren't used: the macOS webview never shows them.
 */
import { useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { ItemDef } from '@/core/format'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { cx } from '@/ui/cx'
import { MiniText, type GlyphMap } from '@/minecraft/text/MiniText'
import { stackLook } from './item'
import styles from './ItemTooltip.module.css'
import { useProjectItem } from './projectItems'
import { tooltipOf, type TooltipLine } from './tooltip'

export function TooltipFrame({
  lines,
  glyphs,
  scale = 2,
  className,
  label = 'Tooltip preview',
}: {
  lines: TooltipLine[]
  glyphs?: GlyphMap
  scale?: number
  className?: string
  label?: string
}) {
  return (
    <div className={cx(styles.tooltip, className)} aria-label={label}>
      {lines.map((line, index) => (
        <MiniText key={index} text={line.text} base={line.style} glyphs={glyphs} scale={scale} />
      ))}
    </div>
  )
}

/** A stack's tooltip: a project item's through its definition. */
export function ItemTooltip({ item, label }: { item: ItemDef; label?: string }) {
  const look = stackLook(item, useProjectItem(item.item))
  const glyphs = useGlyphMap()
  const lines = tooltipOf(look)
  return lines ? <TooltipFrame lines={lines} glyphs={glyphs} label={label} /> : null
}

/** Where the pointer is over a slot, so the tooltip can sit beside it. */
interface Hover {
  item: ItemDef
  x: number
  y: number
}

const OFFSET = 12

/**
 * Hover tooltips for item slots. Spread `handlers(item)` on each slot (an
 * empty slot passes undefined) and render `tooltip` once; `hide()` takes it
 * away (a drag starting).
 */
export function useItemTooltip() {
  const [hover, setHover] = useState<Hover | null>(null)
  const at = (item: ItemDef | undefined, event: ReactPointerEvent) =>
    setHover(item ? { item, x: event.clientX, y: event.clientY } : null)
  const handlers = (item: ItemDef | undefined) => ({
    onPointerEnter: (event: ReactPointerEvent) => at(item, event),
    onPointerMove: (event: ReactPointerEvent) => at(item, event),
    onPointerLeave: () => setHover(null),
  })
  const tooltip: ReactNode = hover ? <FloatingTooltip {...hover} /> : null
  return { handlers, tooltip, hide: () => setHover(null) }
}

function FloatingTooltip({ item, x, y }: Hover) {
  // Beside the pointer, flipped to its left near the window's right edge.
  const flip = x > window.innerWidth - 300
  return createPortal(
    <div
      className={styles.floating}
      role="tooltip"
      style={{
        top: y + OFFSET,
        ...(flip ? { right: window.innerWidth - x + OFFSET } : { left: x + OFFSET }),
      }}
    >
      <ItemTooltip item={item} label="Item tooltip" />
    </div>,
    document.body,
  )
}
