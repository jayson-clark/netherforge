/**
 * The dev server's controls: its phase and prepare progress, Start/Stop
 * (asking for the EULA first), the hot-reload light, and the buttons kinds
 * contribute for the active resource (Spawn at me for a centity).
 */
import { useState } from 'react'
import type { ServerPhase } from '@/core/backend/types'
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Modal } from '@/ui/dialogs'
import { Check } from '@/ui/fields'
import { Spacer } from '@/ui/layout'
import { IS_MAC } from '@/workbench/shortcuts'
import type { CommandContext } from '@/workbench/menus/commands'
import { runCommand, TOOLBAR_COMMANDS } from '@/workbench/menus/runCommand'
import styles from './RunControls.module.css'
import { startServer, useEulaPrompt } from './startServer'

const PHASE_LABEL: Record<ServerPhase, string> = {
  stopped: 'Stopped',
  preparing: 'Preparing',
  starting: 'Starting',
  running: 'Running',
  stopping: 'Stopping',
  crashed: 'Crashed',
}

export function RunControls() {
  const app = useApp()
  const { run } = app
  const server = useRun((s) => s.server)
  const progress = useRun((s) => s.progress)
  const players = useRun((s) => s.players)
  const eulaOpen = useEulaPrompt((s) => s.open)
  const setEulaOpen = (open: boolean) => useEulaPrompt.setState({ open })
  const busy =
    server.phase === 'preparing' || server.phase === 'starting' || server.phase === 'stopping'
  const up = server.phase === 'running' || busy
  // The kinds' buttons act on the active tab's resource: drawn again when it changes (and with the server).
  useWorkspace((s) => s.activeTab)
  // They need only the stores (the menus' own context adds the OS and recent projects).
  const context: CommandContext = { app, mac: false, macKeys: IS_MAC, recent: [] }

  const percent = progress?.total ? Math.round((progress.done / progress.total) * 100) : null

  return (
    <div className={styles.controls} role="group" aria-label="Dev server">
      <span
        className={cx(styles.phase, styles[server.phase])}
        role="status"
        aria-label="Server status"
      >
        {PHASE_LABEL[server.phase]}
        {server.phase === 'preparing' && progress && (
          <>
            {' '}
            · {progress.label}
            {percent !== null && ` ${percent}%`}
          </>
        )}
        {server.phase === 'running' && server.port !== null && ` · :${server.port}`}
      </span>
      {server.phase === 'preparing' && (
        <progress
          className={styles.progress}
          aria-label="Preparing"
          value={percent ?? undefined}
          max={100}
        />
      )}
      {up ? (
        <Button
          size="small"
          icon="stop"
          disabled={server.phase === 'stopping'}
          onClick={() => void run.getState().stop()}
        >
          Stop server
        </Button>
      ) : (
        <Button size="small" variant="primary" icon="play" onClick={() => void startServer(app)}>
          Start server
        </Button>
      )}
      <span
        className={cx(styles.light, server.bridgeConnected && styles.on)}
        role="img"
        aria-label={server.bridgeConnected ? 'Bridge connected' : 'Bridge disconnected'}
        title={
          server.bridgeConnected
            ? `Hot reload connected${players.length ? ` · ${players.join(', ')}` : ''}`
            : 'Hot reload not connected'
        }
      />
      {TOOLBAR_COMMANDS.map((command) => (
        <Button
          key={command.id}
          size="small"
          icon={command.icon}
          disabled={!command.enabled(context)}
          title={command.title(context)}
          onClick={() => runCommand(command.id, context)}
        >
          {command.text}
        </Button>
      ))}
      {server.message && server.phase === 'crashed' && (
        <span className={styles.message} title={server.message}>
          {server.message}
        </span>
      )}
      {eulaOpen && (
        <EulaDialog
          onClose={() => setEulaOpen(false)}
          onAccepted={async () => {
            setEulaOpen(false)
            await run.getState().start()
          }}
        />
      )}
    </div>
  )
}

/** The user accepts the Minecraft EULA themselves; nothing writes eula=true silently. */
export function EulaDialog({
  onClose,
  onAccepted,
}: {
  onClose: () => void
  onAccepted: () => Promise<void>
}) {
  const { backend, run } = useApp()
  const eula = useRun((s) => s.eula)
  const [checked, setChecked] = useState(false)
  const url = eula?.url ?? 'https://aka.ms/MinecraftEULA'
  return (
    <Modal
      title="Minecraft EULA"
      onClose={onClose}
      footer={
        <>
          <Spacer />
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!checked}
            onClick={async () => {
              await run.getState().acceptEula()
              await onAccepted()
            }}
          >
            Accept and start
          </Button>
        </>
      }
    >
      <p>
        Running a Minecraft server means agreeing to Mojang&apos;s End User License Agreement. Read
        it before you accept.
      </p>
      <p>
        <a
          href={url}
          onClick={(event) => {
            event.preventDefault()
            void backend.openExternal(url)
          }}
        >
          Read the Minecraft EULA
        </a>
      </p>
      <Check
        label="I have read and accept the Minecraft EULA"
        checked={checked}
        onChange={setChecked}
      />
    </Modal>
  )
}
