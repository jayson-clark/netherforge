/** A boxed note inside a screen, and a banner across the top of an editor. */
import type { HTMLAttributes } from 'react'
import styles from './Callout.module.css'
import { cx } from './cx'

export function Callout({
  tone,
  className,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { tone?: 'ok' | 'warning' }) {
  return <div className={cx(styles.callout, tone && styles[tone], className)} {...rest} />
}

export function Banner({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.banner, className)} role="alert" {...rest} />
}
