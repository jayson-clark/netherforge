/**
 * What sits over a 3D preview (`Viewport3D`): a small toolbar floating top
 * left, notes at the bottom; and what shows instead of one that can't be
 * drawn. Toolbar buttons show as pressed through `aria-pressed`.
 */
import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from '@/ui/cx'
import styles from './Viewport.module.css'

export function ViewportToolbar({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className={styles.toolbar} role="toolbar" aria-label={label}>
      {children}
    </div>
  )
}

export function ViewportNote({
  warning,
  children,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { warning?: boolean }) {
  return (
    <div className={cx(styles.note, warning && styles.warning)} {...rest}>
      {children}
    </div>
  )
}

/** What shows instead of a preview that can't be drawn. */
export function ViewportFallback({ children }: { children: ReactNode }) {
  return <div className={styles.fallback}>{children}</div>
}
