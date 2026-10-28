/**
 * A picture of a structure or a build (`@netherforge/structures/picture`, the pixels `netherforge preview`
 * writes), as big as its panel allows in whole pixels a block once there's room for two, with the blocks that
 * show named beside it.
 */
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import type { StructureImage } from '@netherforge/structures/picture'
import { Muted } from '@/ui/text'
import styles from './BuildEditor.module.css'

/** How many kinds of block the legend names. */
const LEGEND = 16

const hex = (rgb: readonly number[]) =>
  `#${rgb.map((it) => it.toString(16).padStart(2, '0')).join('')}`

export function PictureView({
  picture,
  label,
  status,
}: {
  picture: StructureImage | null
  label: string
  /** What's going on when there's no picture yet (reading, or why not). */
  status: ReactNode
}) {
  const area = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const [room, setRoom] = useState({ width: 0, height: 0 })

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

  useLayoutEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context || !picture) return
    element.width = picture.width
    element.height = picture.height
    context.putImageData(
      new ImageData(picture.data as Uint8ClampedArray<ArrayBuffer>, picture.width, picture.height),
      0,
      0,
    )
  }, [picture])

  let scale = picture
    ? Math.min((room.width - 16) / picture.width, (room.height - 16) / picture.height)
    : 0
  if (scale >= 2) scale = Math.floor(scale)
  const width = picture ? Math.max(1, Math.floor(picture.width * scale)) : 0
  const height = picture ? Math.max(1, Math.floor(picture.height * scale)) : 0

  return (
    <div className={styles.stage}>
      <div ref={area} className={styles.pictureArea}>
        {picture ? (
          <canvas
            ref={canvas}
            className={styles.picture}
            style={{ width, height }}
            role="img"
            aria-label={label}
          />
        ) : (
          <span role="status">{status}</span>
        )}
      </div>
      {picture && picture.legend.length > 0 && (
        <ul className={styles.legend} aria-label="Blocks that show">
          {picture.legend.slice(0, LEGEND).map((it) => (
            <li key={it.kind}>
              <span className={styles.swatch} style={{ background: hex(it.color) }} />
              <span className={styles.kind}>{it.kind}</span>
              <Muted>{it.count}</Muted>
            </li>
          ))}
          {picture.legend.length > LEGEND && (
            <li>
              <Muted>and {picture.legend.length - LEGEND} more</Muted>
            </li>
          )}
        </ul>
      )}
    </div>
  )
}
