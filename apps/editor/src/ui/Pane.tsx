/**
 * A collapsible pane with a header of actions (shown on hover, as in VS
 * Code's explorer): the outline's Nodes, Files, Emitters. Controlled: the
 * owner keeps whether it's open (usually the layout store, so it's
 * remembered).
 */
import { useId, type ReactNode } from 'react'
import { Icon } from './Icon'
import styles from './Pane.module.css'

export function Pane({
  title,
  open,
  onToggle,
  actions,
  children,
}: {
  title: string
  open: boolean
  onToggle: (open: boolean) => void
  actions?: ReactNode
  children: ReactNode
}) {
  const id = useId()
  return (
    <section className={styles.pane} aria-labelledby={id}>
      <div className={styles.header}>
        <button
          type="button"
          id={id}
          className={styles.toggle}
          aria-expanded={open}
          onClick={() => onToggle(!open)}
        >
          <Icon name={open ? 'chevronDown' : 'chevronRight'} size={12} />
          <span className={styles.title}>{title}</span>
        </button>
        {actions && <span className={styles.actions}>{actions}</span>}
      </div>
      {open && <div className={styles.body}>{children}</div>}
    </section>
  )
}
