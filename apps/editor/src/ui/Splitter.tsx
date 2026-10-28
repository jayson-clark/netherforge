/**
 * The line between two resizable panels: react-resizable-panels' `Separator`
 * (inside its `Group`, between `Panel`s), drawn as a 1px rule that lights up
 * while hovered, focused or dragged. The library does the dragging, the
 * keyboard (arrows, Home/End, Enter to collapse a collapsible panel, F6 to
 * the next splitter) and the separator's ARIA; double-click asks for the
 * default size back.
 */
import { Separator } from 'react-resizable-panels'
import { cx } from './cx'
import styles from './Splitter.module.css'

export function Splitter({
  label,
  onReset,
  hidden,
}: {
  label: string
  onReset?: () => void
  /** Kept in place but unusable and unseen (beside a collapsed panel). */
  hidden?: boolean
}) {
  return (
    <Separator
      className={cx(styles.splitter, hidden && styles.hidden)}
      aria-label={label}
      disabled={hidden}
      disableDoubleClick
      onDoubleClick={onReset}
    />
  )
}
