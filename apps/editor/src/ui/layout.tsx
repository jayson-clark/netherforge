/** Small layout pieces every screen uses. */
import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from './cx'
import styles from './layout.module.css'

/** Takes the free space in a row, pushing what follows to the end. */
export const Spacer = () => <span className={styles.spacer} />

/** A thin vertical line between groups in a toolbar. */
export const Divider = () => <span className={styles.divider} role="separator" />

/** A toolbar strip: an editor's bar over its canvas, a panel's controls. */
export function Bar({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.bar, className)} {...rest} />
}

/** A panel's small uppercase header with its buttons on the right. */
export function PanelHeader({
  title,
  actions,
  className,
}: {
  title: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <div className={cx(styles.panelHeader, className)}>
      <span className={styles.panelTitle}>{title}</span>
      {actions && <span className={styles.panelActions}>{actions}</span>}
    </div>
  )
}

/** Children in a column (or, `inline`, a wrapping row) with a regular gap. */
export function Stack({
  inline,
  className,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { inline?: boolean }) {
  return <div className={cx(inline ? styles.inline : styles.stack, className)} {...rest} />
}
