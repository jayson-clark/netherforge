/**
 * "NetherForge x.y is available": shown after a check finds a newer release,
 * on the welcome screen and in the workbench. Installing always asks first,
 * and stops the dev server before the updater replaces the app.
 */
import { useApp, useRun, useUpdates } from '@/state/providers'
import { Button } from '@/ui/Button'
import { ask } from '@/ui/dialogs'
import { Tone } from '@/ui/text'
import styles from './UpdateBanner.module.css'

export function useInstallUpdate() {
  const { run, updates } = useApp()
  return async () => {
    const update = updates.getState().available
    if (!update) return
    const serverUp = run.getState().server.phase !== 'stopped'
    const ok = await ask.confirm({
      title: `Install NetherForge ${update.version}?`,
      message: `${serverUp ? 'This stops the dev server, then ' : 'This '}downloads the update and restarts NetherForge. Save your work first: unsaved changes are lost.`,
      confirmLabel: 'Install and restart',
    })
    if (ok !== true) return
    await updates.getState().install(async () => {
      if (run.getState().server.phase !== 'stopped') await run.getState().stop()
    })
  }
}

export function UpdateBanner() {
  const { updates } = useApp()
  const available = useUpdates((s) => s.available)
  const dismissed = useUpdates((s) => s.dismissed)
  const installing = useUpdates((s) => s.installing)
  const error = useUpdates((s) => s.error)
  const phase = useRun((s) => s.server.phase)
  const install = useInstallUpdate()
  if (!available || (dismissed === available.version && !installing && !error)) return null
  return (
    <div className={styles.banner} role="status" aria-label="Update available">
      {installing ? (
        <>
          Installing NetherForge {available.version}…
          <progress value={installing.downloaded} max={installing.total ?? undefined} />
        </>
      ) : (
        <>
          <span>
            NetherForge {available.version} is available (you have {available.currentVersion}).
            {phase !== 'stopped' && ' Installing stops the dev server.'}
          </span>
          {error && <Tone tone="error">{error}</Tone>}
          <Button size="small" variant="primary" onClick={() => void install()}>
            Install…
          </Button>
          <Button size="small" onClick={() => updates.getState().dismiss()}>
            Later
          </Button>
        </>
      )}
    </div>
  )
}
