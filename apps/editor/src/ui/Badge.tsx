/** Small markers: a word badge, a count, the dot for unsaved edits. */
import type { ReactNode } from 'react'
import styles from './Badge.module.css'
import { cx } from './cx'

export function Badge({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <span className={styles.badge} title={title}>
      {children}
    </span>
  )
}

export function Count({
  value,
  tone,
  title,
}: {
  value: number
  tone?: 'error' | 'warning'
  title?: string
}) {
  return (
    <span className={cx(styles.count, tone && styles[tone])} title={title}>
      {value}
    </span>
  )
}

/** Errors in something, as a red count with a sentence for its tooltip. */
export function ErrorCount({ value }: { value: number }) {
  if (value <= 0) return null
  return <Count value={value} tone="error" title={`${value} error${value > 1 ? 's' : ''}`} />
}

export function DirtyDot({ label = 'unsaved' }: { label?: string | null }) {
  return label ? (
    <span className={styles.dirty} aria-label={label} />
  ) : (
    <span className={styles.dirty} aria-hidden="true" />
  )
}
