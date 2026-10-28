/**
 * The dev network: whether Start runs the project's server alone or behind a proxy beside other
 * servers, each running a project folder of its own. A card per member (the proxy, this project's
 * server, each other server) says how it is (its folder, trust, phase) and opens its console in
 * the Console panel, whose switcher shows any member's.
 */
import { useEffect, useState } from 'react'
import type {
  NetworkLayout,
  NetworkMember,
  NetworkServer,
  NetworkServerCheck,
  ServerPhase,
} from '@/core/backend/types'
import { useApp, useNetwork, useRun, useWorkspace } from '@/state/providers'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import styles from './Network.module.css'
import { ServerStatus, StatusDot } from './ServerStatus'
import { WorkspaceNetworkPanel } from './WorkspaceNetwork'

/** A name for a new server that no other has. */
export function newServerName(layout: NetworkLayout): string {
  const taken = new Set([layout.name, ...layout.servers.map((it) => it.name)])
  for (let n = 1; ; n += 1) {
    const name = n === 1 ? 'lobby' : `server-${n}`
    if (!taken.has(name)) return name
  }
}

export function NetworkPanel() {
  const inWorkspace = useWorkspace((s) => s.project?.workspace != null)
  return inWorkspace ? <WorkspaceNetworkPanel /> : <ProjectNetworkPanel />
}

/** A project on its own: its layout is this computer's, its other servers each a project folder. */
function ProjectNetworkPanel() {
  const { network, backend, layout: docks } = useApp()
  const layout = useNetwork((s) => s.layout)
  const checks = useNetwork((s) => s.checks)
  const members = useNetwork((s) => s.members)
  const error = useNetwork((s) => s.error)
  const own = useRun((s) => s.server)
  const [port, setPort] = useState<number | null>(null)

  useEffect(() => {
    let live = true
    void backend.getSettings().then((settings) => live && setPort(settings.serverPort))
    return () => {
      live = false
    }
  }, [backend])

  if (!layout) return <Empty>Open a project to set up its dev network.</Empty>

  const save = (next: NetworkLayout) => void network.getState().setLayout(next)
  const setServer = (index: number, patch: Partial<NetworkServer>) =>
    save({
      ...layout,
      servers: layout.servers.map((it, i) => (i === index ? { ...it, ...patch } : it)),
    })
  const memberOf = (name: string) => members.find((it) => it.name === name)
  /** Shows the console of [name] (null: this project's server's) in the Console panel. */
  const openConsole = (name: string | null) => {
    network.getState().view(name)
    docks.getState().reveal('console')
  }
  const proxy = memberOf('proxy')
  const phases: ServerPhase[] = [own.phase, ...members.map((it) => it.state.phase)]
  const running = phases.filter((it) => it === 'running').length

  return (
    <div className={styles.panel}>
      <header className={styles.head}>
        <Icon name="network" className={styles.headIcon} />
        <div className={styles.headText}>
          <h2 className={styles.title}>Dev network</h2>
          <p className={styles.subtitle}>
            {layout.enabled
              ? `Players join localhost${port ? `:${port}` : ''} and arrive on ${layout.name}.`
              : "Start runs this project's server on its own."}
          </p>
        </div>
        {layout.enabled && members.length > 0 && (
          <span className={styles.summary} role="status">
            {running} of {phases.length} running
          </span>
        )}
        <label className={styles.switch}>
          <input
            type="checkbox"
            role="switch"
            aria-label="Run the dev server as a network"
            checked={layout.enabled}
            onChange={(e) => save({ ...layout, enabled: e.target.checked })}
          />
          <span className={styles.track} aria-hidden="true" />
        </label>
      </header>

      {error && (
        <p className={styles.error} role="alert">
          <Icon name="error" /> {error}
        </p>
      )}

      {!layout.enabled && (
        <p className={styles.intro}>
          Turn it on to run a Velocity proxy with NetherForge&apos;s hub in front of this
          project&apos;s server, and the servers below beside it, each running a project folder of
          its own. Scripts tell them apart by <code>nf.network.server_name()</code>.
        </p>
      )}

      <ul className={cx(styles.grid, !layout.enabled && styles.off)} aria-label="Network servers">
        {layout.enabled && (
          <li className={styles.card}>
            <div className={styles.cardHead}>
              <StatusDot phase={proxy?.state.phase ?? 'stopped'} />
              <span className={styles.fixedName}>proxy</span>
              <span className={styles.kind}>Velocity</span>
              <span className={styles.spacer} />
              <ConsoleButton name="proxy" member={proxy} onOpen={openConsole} />
            </div>
            <p className={styles.meta}>Players join here{port ? ` on :${port}` : ''}</p>
            <ServerStatus phase={proxy?.state.phase ?? 'stopped'} />
          </li>
        )}
        <li className={cx(styles.card, styles.own)}>
          <div className={styles.cardHead}>
            <StatusDot phase={own.phase} />
            <CommitInput
              className={styles.nameInput}
              label="This project's server name"
              value={layout.name}
              onCommit={(name) => save({ ...layout, name })}
            />
            <span className={styles.kind}>This project</span>
            <span className={styles.spacer} />
            <IconButton
              icon="code"
              label={`Show ${layout.name}'s console`}
              onClick={() => openConsole(null)}
            />
          </div>
          <p className={styles.meta}>
            {layout.enabled ? 'Players arrive here' : 'Runs alone while the network is off'}
          </p>
          <ServerStatus phase={own.phase} connected={own.bridgeConnected} />
        </li>
        {layout.servers.map((server, index) => (
          <ServerCard
            key={index}
            server={server}
            check={checks.find((it) => it.name === server.name)}
            member={memberOf(server.name)}
            onChange={(patch) => setServer(index, patch)}
            onBrowse={async () => {
              const folder = await backend.pickFolder(`The project ${server.name} runs`)
              if (folder) setServer(index, { project: folder })
            }}
            onTrust={() => void network.getState().trust(server.name)}
            onRemove={() =>
              save({ ...layout, servers: layout.servers.filter((_, i) => i !== index) })
            }
            onOpenConsole={openConsole}
          />
        ))}
        <li className={styles.addCell}>
          <button
            type="button"
            className={styles.add}
            onClick={() =>
              save({
                ...layout,
                servers: [...layout.servers, { name: newServerName(layout), project: '.' }],
              })
            }
          >
            <Icon name="plus" />
            Add server
          </button>
        </li>
      </ul>

      <p className={styles.foot}>
        Changes apply the next time the server starts. Each server keeps its own world in the
        editor&apos;s data folder.
      </p>
    </div>
  )
}

function ServerCard({
  server,
  check,
  member,
  onChange,
  onBrowse,
  onTrust,
  onRemove,
  onOpenConsole,
}: {
  server: NetworkServer
  check: NetworkServerCheck | undefined
  member: NetworkMember | undefined
  onChange: (patch: Partial<NetworkServer>) => void
  onBrowse: () => void
  onTrust: () => void
  onRemove: () => void
  onOpenConsole: (name: string) => void
}) {
  const phase = member?.state.phase ?? 'stopped'
  const untrusted = check?.root && !check.trusted
  return (
    <li className={cx(styles.card, (check?.problem || untrusted) && styles.attention)}>
      <div className={styles.cardHead}>
        <StatusDot phase={phase} />
        <CommitInput
          className={styles.nameInput}
          label={`Name of server ${server.name}`}
          value={server.name}
          onCommit={(name) => onChange({ name })}
        />
        <span className={styles.spacer} />
        <ConsoleButton name={server.name} member={member} onOpen={onOpenConsole} />
        <IconButton icon="trash" label={`Remove server ${server.name}`} onClick={onRemove} />
      </div>
      <div className={styles.project}>
        <Icon name="folder" className={styles.projectIcon} />
        <CommitInput
          className={styles.projectInput}
          label={`Project of server ${server.name}`}
          value={server.project}
          onCommit={(project) => onChange({ project })}
        />
        <IconButton
          icon="folderOpen"
          label={`Choose the project ${server.name} runs`}
          onClick={onBrowse}
        />
      </div>
      {check?.problem ? (
        <p className={styles.problem}>
          <Icon name="warning" /> {check.problem}
        </p>
      ) : (
        check?.root && (
          <p className={styles.meta}>
            {check.projectName}
            {check.minecraft ? ` · Minecraft ${check.minecraft}` : ''}
          </p>
        )
      )}
      {untrusted ? (
        <div className={styles.trust} title="Its scripts would run on this computer">
          <Icon name="shield" />
          <span>Not trusted</span>
          <button
            type="button"
            className={styles.trustButton}
            onClick={onTrust}
            title={`Trust ${check.root}`}
          >
            Trust folder
          </button>
        </div>
      ) : (
        <ServerStatus phase={phase} connected={member?.state.bridgeConnected} />
      )}
      {member?.state.message && phase === 'stopped' && (
        <p className={styles.problem}>{member.state.message}</p>
      )}
    </li>
  )
}

export function ConsoleButton({
  name,
  member,
  onOpen,
}: {
  name: string
  member: NetworkMember | undefined
  onOpen: (name: string) => void
}) {
  return (
    <IconButton
      icon="code"
      label={`Show ${name}'s console`}
      disabled={!member}
      title={member ? `Show ${name}'s console` : `${name} hasn't run yet`}
      onClick={() => onOpen(name)}
    />
  )
}

/** A text input that commits on Enter or leaving it, as the inspector's fields do. */
export function CommitInput({
  label,
  value,
  onCommit,
  className,
}: {
  label: string
  value: string
  onCommit: (value: string) => void
  className?: string
}) {
  const [text, setText] = useState(value)
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    setText(value)
  }
  const commit = () => {
    if (text !== value) onCommit(text)
  }
  return (
    <input
      className={className}
      aria-label={label}
      value={text}
      spellCheck={false}
      data-commit={text === value ? 'clean' : 'draft'}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit()
        if (e.key === 'Escape') setText(value)
      }}
    />
  )
}
