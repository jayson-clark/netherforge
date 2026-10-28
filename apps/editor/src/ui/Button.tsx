/** Buttons: `Button` for words, `IconButton` for an icon with its name as the accessible label. */
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import styles from './Button.module.css'
import { cx } from './cx'
import { Icon, type IconName } from './Icon'

export type ButtonVariant = 'default' | 'primary' | 'danger' | 'ghost' | 'link'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'normal' | 'small'
  /** Drawn before the label. */
  icon?: IconName
  /** Shown pressed (a toggle that's on). */
  active?: boolean
  children?: ReactNode
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'default', size = 'normal', icon, active, className, children, type, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      className={cx(
        styles.button,
        variant !== 'default' && styles[variant],
        size === 'small' && styles.small,
        active && styles.active,
        className,
      )}
      {...rest}
    >
      {icon && <Icon name={icon} />}
      {children}
    </button>
  )
})

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  icon: IconName
  /** The accessible name, also the tooltip unless `title` says more. */
  label: string
  size?: number
  active?: boolean
  /** Extra content after the icon (a small badge). */
  children?: ReactNode
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, label, size, active, className, title, type, children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      aria-label={label}
      title={title ?? label}
      aria-pressed={active}
      className={cx(styles.iconButton, active && styles.active, className)}
      {...rest}
    >
      <Icon name={icon} size={size} />
      {children}
    </button>
  )
})
