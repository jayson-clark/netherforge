/** A server's phase as a coloured dot, with or without its word: what the Console's switcher and the Network panel show. */
import type { ServerPhase } from '@/core/backend/types'
import { cx } from '@/ui/cx'
import styles from './ServerStatus.module.css'

export const PHASE_LABEL: Record<ServerPhase, string> = {
  stopped: 'Stopped',
  preparing: 'Preparing',
  starting: 'Starting',
  running: 'Running',
  stopping: 'Stopping',
  crashed: 'Crashed',
}

/** Running is green, on its way up or down amber (pulsing), crashed red, stopped grey. */
export function StatusDot({ phase }: { phase: ServerPhase }) {
  return <span className={cx(styles.dot, styles[phase])} aria-hidden="true" />
}

/** The dot and the phase's word (and, running without its plugin, that it isn't connected). */
export function ServerStatus({ phase, connected }: { phase: ServerPhase; connected?: boolean }) {
  const waiting = phase === 'running' && connected === false
  return (
    <span className={cx(styles.status, styles[phase])}>
      <StatusDot phase={waiting ? 'starting' : phase} />
      {waiting ? 'Running, plugin connecting' : PHASE_LABEL[phase]}
    </span>
  )
}
