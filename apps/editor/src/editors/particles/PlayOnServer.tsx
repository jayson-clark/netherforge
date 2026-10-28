/**
 * The particle editor's "Play on server" and "Stop":
 * the effect played for real on the dev server over the bridge.
 */
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'

/**
 * Plays the effect on the dev server, in front of the first player online,
 * with the timeline's loop toggle; Stop ends every effect played this way.
 * A dirty document is saved first, so the server plays what's on screen (the
 * save's reload reaches the plugin before the play does: one connection, in order).
 */
export function PlayOnServer({ path, id, loop }: { path: string; id: string; loop: boolean }) {
  const { workspace, run } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const dirty = useWorkspace((s) => s.docs[path]?.dirty ?? false)
  const reason = connected ? undefined : 'Start the dev server to play effects on it'
  const play = async () => {
    if (dirty && !(await workspace.getState().save(path))) return
    await run.getState().playParticleEffect(id, loop)
  }
  return (
    <>
      <Button
        size="small"
        icon="play"
        disabled={!connected}
        title={reason ?? `Play ${id} in front of you${loop ? ', looping' : ''}`}
        onClick={() => void play()}
      >
        Play on server
      </Button>
      <Button
        size="small"
        icon="stop"
        disabled={!connected}
        title={reason ?? 'Stop the effects played from here'}
        onClick={() => void run.getState().stopParticleEffects()}
      >
        Stop
      </Button>
    </>
  )
}
