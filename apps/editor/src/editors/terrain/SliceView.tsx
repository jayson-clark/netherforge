/**
 * The ground along the slice's line, as wide as its panel (whole pixels a block once it's two or more; the deep
 * ground scrolls), with
 * the blocks it's made of named beside it. It's drawn again only when the line, the file or the world changes.
 */
import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { TerrainSlice } from '@/core/format'
import { blockAt, sliceColors, slicePixels, sliceTop } from '@netherforge/terrain-preview'
import { Muted } from '@/ui/text'
import styles from './TerrainEditor.module.css'

export function SliceView({
  slice,
  fluid,
  stone,
  tools,
  drawing,
}: {
  slice: TerrainSlice | null
  fluid: string
  stone: string
  /** The slice's own controls, in its header. */
  tools: ReactNode
  drawing: boolean
}) {
  const area = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const [room, setRoom] = useState({ width: 0, height: 0 })
  const [hover, setHover] = useState<{ column: number; y: number } | null>(null)

  useLayoutEffect(() => {
    const element = area.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry!.contentRect
      setRoom({ width: Math.floor(width), height: Math.floor(height) })
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const colors = useMemo(
    () => (slice ? sliceColors(slice, fluid, stone) : []),
    [slice, fluid, stone],
  )
  const top = useMemo(() => (slice ? sliceTop(slice) : 0), [slice])
  const drawn = useMemo(
    () => (slice ? slicePixels(slice, colors, top, 1) : null),
    [slice, colors, top],
  )

  useLayoutEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context || !drawn) return
    element.width = drawn.width
    element.height = drawn.height
    context.putImageData(
      new ImageData(drawn.pixels as Uint8ClampedArray<ArrayBuffer>, drawn.width, drawn.height),
      0,
      0,
    )
  }, [drawn])

  const used = useMemo(() => {
    const seen = new Set<number>()
    if (slice)
      for (const runs of slice.columns) for (let i = 0; i < runs.length; i += 2) seen.add(runs[i]!)
    return [...seen].filter((it) => it !== 0).sort((a, b) => a - b)
  }, [slice])

  // As wide as the panel (whole pixels a block once there's room for two), scrolling down to the deep ground.
  let scale = drawn ? Math.max(0, room.width - 2) / drawn.width : 0
  if (scale >= 2) scale = Math.floor(scale)
  const width = drawn ? Math.max(1, Math.floor(drawn.width * scale)) : 0
  const height = drawn ? Math.max(1, Math.floor(drawn.height * scale)) : 0
  const sea = slice ? ((top - slice.seaLevel - 1) / (top - slice.minY)) * 100 : 0

  return (
    <section
      className={styles.slicePanel}
      aria-label="The slice"
      data-drawing={drawing || undefined}
    >
      <header className={styles.sliceHeader}>
        <strong>Through the ground</strong>
        {slice && (
          <Muted>
            {slice.axis === 'x' ? `along x, at z ${slice.at}` : `along z, at x ${slice.at}`},{' '}
            {slice.width} blocks from {slice.from}
          </Muted>
        )}
        {drawing && <Muted>drawing…</Muted>}
        <span className={styles.grow} />
        {tools}
      </header>
      <div className={styles.sliceBody}>
        <div ref={area} className={styles.sliceArea}>
          {slice && drawn && (
            <div
              className={styles.slice}
              style={{ width, height }}
              role="img"
              aria-label="Slice through the ground"
              data-testid="terrain-slice"
              onPointerMove={(event) => {
                const box = event.currentTarget.getBoundingClientRect()
                const column = Math.floor(((event.clientX - box.left) / box.width) * slice.width)
                const y =
                  top -
                  1 -
                  Math.floor(((event.clientY - box.top) / box.height) * (top - slice.minY))
                setHover({ column, y })
              }}
              onPointerLeave={() => setHover(null)}
            >
              <canvas ref={canvas} className={styles.canvas} />
              <span className={styles.sea} style={{ top: `${sea}%` }} aria-hidden="true" />
            </div>
          )}
        </div>
        {slice && (
          <div className={styles.sliceSide}>
            <p
              className={styles.sliceReadout}
              aria-live="polite"
              data-testid="terrain-slice-readout"
            >
              {hover && hover.column >= 0 && hover.column < slice.width
                ? `${slice.axis} ${slice.from + hover.column}, y ${hover.y}: ${slice.palette[blockAt(slice, hover.column, hover.y)]?.label ?? ''}`
                : `Lowest block ${slice.minY}.`}
            </p>
            <ul className={styles.blockLegend} aria-label="Blocks">
              {used.map((index) => {
                const color = colors[index]
                const entry = slice.palette[index]!
                return (
                  <li key={index}>
                    <span
                      className={styles.swatch}
                      style={{
                        background: color ? `rgb(${color[0]} ${color[1]} ${color[2]})` : 'none',
                      }}
                    />
                    {entry.label} {entry.custom && <Muted>(the project's)</Muted>}
                  </li>
                )
              })}
            </ul>
          </div>
        )}
      </div>
    </section>
  )
}
