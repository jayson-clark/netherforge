/**
 * Up to four pictures in a 2×2 collage, [size] pixels square (one alone fills it), with an optional
 * badge in the corner: a group folder's tile, a loot table's items.
 */
import type { ReactNode } from 'react'
import { cx } from './cx'
import styles from './Collage.module.css'

/** How many pictures a collage shows. */
export const COLLAGE_CELLS = 4

export function Collage<T>({
  items,
  keyOf,
  render,
  size,
  badge,
}: {
  items: readonly T[]
  keyOf: (item: T) => string
  /** One picture, [cell] pixels square. */
  render: (item: T, cell: number) => ReactNode
  size: number
  badge?: ReactNode
}) {
  const shown = items.slice(0, COLLAGE_CELLS)
  const single = shown.length === 1
  const cell = single ? size : Math.floor((size - 6) / 2)
  return (
    <span
      className={cx(styles.collage, single && styles.single)}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      {shown.map((item) => (
        <span key={keyOf(item)} className={styles.cell}>
          {render(item, cell)}
        </span>
      ))}
      {badge && <span className={styles.badge}>{badge}</span>}
    </span>
  )
}
