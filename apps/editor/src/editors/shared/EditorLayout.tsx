/**
 * The frame every editor's canvas sits in: `EditorScreen` fills the editor
 * area (and marks it, for focus requests), `EditorBar` is the strip over the
 * canvas (its name on the left, its actions on the right), `Stage` is a
 * centred canvas for 2D previews and `Page` a scrolling page of fields.
 */
import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from '@/ui/cx'
import { Bar, Spacer } from '@/ui/layout'
import styles from './EditorLayout.module.css'

export function EditorScreen({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.screen, className)} data-editor {...rest} />
}

export function EditorBar({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <Bar>
      <span className={styles.crumb}>{title}</span>
      <Spacer />
      {children}
    </Bar>
  )
}

export function Stage({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.stage, className)} {...rest} />
}

export function Page({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.page, className)} {...rest} />
}

/** Inspector text that needs the inspector's own padding (a note above its sections). */
export function InspectorNote({ children }: { children: ReactNode }) {
  return <p className={styles.padded}>{children}</p>
}
