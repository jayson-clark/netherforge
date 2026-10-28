/** Text with a role: secondary, a hint under a field, an empty state, a problem. */
import type { HTMLAttributes } from 'react'
import { cx } from './cx'
import styles from './text.module.css'

type Tone = 'muted' | 'warning' | 'error'
type TextProps = HTMLAttributes<HTMLElement> & { tone?: Tone }

/** Inline secondary text. */
export function Muted({ className, ...rest }: HTMLAttributes<HTMLSpanElement>) {
  return <span className={cx(styles.muted, className)} {...rest} />
}

/** Inline text in a tone. */
export function Tone({ tone = 'muted', className, ...rest }: TextProps) {
  return <span className={cx(styles[tone], className)} {...rest} />
}

/** A small note under or beside something. */
export function Hint({ tone, className, ...rest }: TextProps) {
  return (
    <p className={cx(styles.hint, tone && tone !== 'muted' && styles[tone], className)} {...rest} />
  )
}

/** What a list or panel says when there's nothing in it. */
export function Empty({ className, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cx(styles.empty, className)} {...rest} />
}

/** Monospace inline text (ids, paths). */
export function Mono({ className, ...rest }: HTMLAttributes<HTMLSpanElement>) {
  return <span className={cx(styles.mono, className)} {...rest} />
}
