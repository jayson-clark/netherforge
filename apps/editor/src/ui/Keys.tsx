/**
 * A keyboard shortcut, written as the menus write it (`Mod+Shift+S`) and
 * shown as this OS shows it, each key its own glyph with room around it so
 * `⇧⌘S` doesn't run together. `caps` draws each key as a keycap, for hints
 * standing on their own rather than beside a menu label.
 */
import { IS_MAC, parseShortcut, shortcutKeys } from '@/core/shortcut'
import { cx } from './cx'
import styles from './Keys.module.css'

export function Keys({
  shortcut,
  caps,
  className,
}: {
  shortcut: string
  caps?: boolean
  className?: string
}) {
  const keys = shortcutKeys(parseShortcut(shortcut), IS_MAC)
  return (
    <kbd className={cx(styles.keys, caps ? styles.caps : !IS_MAC && styles.plus, className)}>
      {keys.map((key, index) => (
        <kbd key={index} className={styles.key}>
          {key}
        </kbd>
      ))}
    </kbd>
  )
}
