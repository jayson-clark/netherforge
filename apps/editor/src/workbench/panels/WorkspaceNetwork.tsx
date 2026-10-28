/**
 * The Network panel for a project in a workspace: the workspace's dev network, as its file
 * (`netherforge-workspace.json`) says it, shared with everyone working on the workspace. A card
 * per server (its name, the project it runs, whether players arrive on it) and the proxy's; the
 * editor is attached to the server running the open project, whose console is the Console's own.
 * Whether the network is on is this computer's choice.
 */
import { useEffect, useState } from 'react'
import type { NetworkMember, ServerPhase, WorkspaceMember } from '@/core/backend/types'
import { isWorkspaceServerName, type WorkspaceNetwork } from '@/core/format'
import { useApp, useNetwork, useWorkspace, useWorkspaceFile } from '@/state/providers'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import { askNewServer } from '@/workbench/workspaceActions'
import { CommitInput, ConsoleButton } from './Network'
import styles from './Network.module.css'
import { ServerStatus, StatusDot } from './ServerStatus'

/** A server name from a project's folder (`servers/chess` → `chess`), or null when it can't be one. */
export function serverNameFor(path: string): string | null {
  const name = (path.split('/').pop() ?? '').toLowerCase().replace(/[^a-z0-9_-]/g, '-')
  return isWorkspaceServerName(name) ? name : null
}

/** A first network for a workspace: a server per project that isn't a package, players arriving on the first. */
export function suggestNetwork(members: WorkspaceMember[]): WorkspaceNetwork | null {
  const servers: Record<string, string> = {}
  for (const member of members.filter((it) => !it.package)) {
    const name = serverNameFor(member.path)
    if (name && !(name in servers) && name !== 'proxy') servers[name] = member.path
  }
  const names = Object.keys(servers)
  if (names.length === 0) return null
  const arrival = names.find((it) => it === 'hub' || it === 'lobby') ?? names[0]!
  return { arrival, servers }
}

export function WorkspaceNetworkPanel() {
  const app = useApp()
  const { workspaceFile, network: networkStore, layout: docks, backend } = app
  const project = useWorkspace((s) => s.project)
  const info = useWorkspaceFile((s) => s.info)
  const file = useWorkspaceFile((s) => s.file)
  const problems = useWorkspaceFile((s) => s.problems)
  const enabled = useWorkspaceFile((s) => s.networkEnabled)
  const error = useWorkspaceFile((s) => s.error)
  const members = useNetwork((s) => s.members)
  const [port, setPort] = useState<number | null>(null)

  useEffect(() => {
    let live = true
    void backend.getSettings().then((settings) => live && setPort(settings.serverPort))
    return () => {
      live = false
    }
  }, [backend])

  if (!project || !info) return <Empty>Reading the workspace…</Empty>
  const network = file?.network ?? null
  const save = (next: WorkspaceNetwork | null) => void workspaceFile.getState().saveNetwork(next)
  const memberOf = (name: string) => members.find((it) => it.name === name)
  const openConsole = (name: string | null) => {
    const member = members.find((it) => it.name === name)
    networkStore.getState().view(member?.attached ? null : name)
    docks.getState().reveal('console')
  }
  const projectOf = (path: string) => info.members.find((it) => it.path === path)
  const phases: ServerPhase[] = members.map((it) => it.state.phase)
  const running = phases.filter((it) => it === 'running').length
  const proxy = memberOf('proxy')

  const rename = (from: string, to: string) => {
    if (!network || from === to) return
    if (!isWorkspaceServerName(to) || to === 'proxy') {
      workspaceFile.setState({
        error: `"${to}" can't name a server: lowercase letters, digits, _ and - (at most 32), and not proxy`,
      })
      return
    }
    if (to in network.servers) {
      workspaceFile.setState({ error: `There's already a server named "${to}"` })
      return
    }
    const servers = Object.fromEntries(
      Object.entries(network.servers).map(([name, path]) => [name === from ? to : name, path]),
    )
    save({ arrival: network.arrival === from ? to : network.arrival, servers })
  }
  const setProject = (name: string, path: string) =>
    network && save({ ...network, servers: { ...network.servers, [name]: path } })
  const remove = (name: string) => {
    if (!network) return
    const { [name]: _gone, ...servers } = network.servers
    const names = Object.keys(servers)
    if (names.length === 0) return save(null)
    save({ arrival: network.arrival === name ? names[0]! : network.arrival, servers })
  }
  const add = () => {
    if (!network) return
    const used = new Set(Object.values(network.servers))
    const next =
      info.members.find((it) => !it.package && !used.has(it.path)) ??
      info.members.find((it) => !it.package) ??
      info.members[0]
    if (!next) return
    let name = serverNameFor(next.path) ?? 'server'
    for (let n = 2; name in network.servers || name === 'proxy'; n += 1) {
      name = `${serverNameFor(next.path) ?? 'server'}-${n}`
    }
    save({ ...network, servers: { ...network.servers, [name]: next.path } })
  }

  return (
    <div className={styles.panel}>
      <header className={styles.head}>
        <Icon name="network" className={styles.headIcon} />
        <div className={styles.headText}>
          <h2 className={styles.title}>{info.name}</h2>
          <p className={styles.subtitle}>
            {network
              ? `Players join localhost${port ? `:${port}` : ''} and arrive on ${network.arrival}.`
              : 'The workspace has no dev network yet.'}
          </p>
        </div>
        {members.length > 0 && (
          <span className={styles.summary} role="status">
            {running} of {members.length} running
          </span>
        )}
        <label
          className={styles.switch}
          title="Whether Start runs the workspace's network, on this computer"
        >
          <input
            type="checkbox"
            role="switch"
            aria-label="Run the workspace's dev network"
            checked={enabled}
            disabled={!network}
            onChange={(e) => void workspaceFile.getState().setNetworkEnabled(e.target.checked)}
          />
          <span className={styles.track} aria-hidden="true" />
        </label>
      </header>

      {error && (
        <p className={styles.error} role="alert">
          <Icon name="error" /> {error}
        </p>
      )}
      {problems.map((problem, i) => (
        <p key={i} className={styles.problem}>
          <Icon name="warning" /> {problem.message}
        </p>
      ))}

      {!network ? (
        <div className={styles.intro}>
          <p>
            A dev network runs a server for each project you choose behind a Velocity proxy, all at
            once, kept in the workspace&apos;s file so everyone working on it runs the same one.
          </p>
          <Button
            variant="primary"
            icon="network"
            disabled={!suggestNetwork(info.members)}
            onClick={() => save(suggestNetwork(info.members))}
          >
            Set up a network
          </Button>
        </div>
      ) : (
        <ul className={styles.grid} aria-label="Network servers">
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
          {Object.entries(network.servers)
            .sort(
              ([a], [b]) =>
                Number(b === network.arrival) - Number(a === network.arrival) || (a < b ? -1 : 1),
            )
            .map(([name, path]) => (
              <ServerCard
                key={name}
                name={name}
                path={path}
                arrival={name === network.arrival}
                runsOpenProject={projectOf(path)?.root === project.root}
                members={info.members}
                member={memberOf(name)}
                onRename={(to) => rename(name, to)}
                onProject={(to) => setProject(name, to)}
                onArrival={() => save({ ...network, arrival: name })}
                onRemove={() => remove(name)}
                onConsole={() => openConsole(name)}
              />
            ))}
          <li className={styles.addCell}>
            <button type="button" className={styles.add} onClick={add}>
              <Icon name="plus" />
              Add server
            </button>
            <button
              type="button"
              className={styles.add}
              onClick={() => void askNewServer(app)}
              title="A new project of the workspace for it, with a module to start from"
            >
              <Icon name="plus" />
              New server project…
            </button>
          </li>
        </ul>
      )}

      <p className={styles.foot}>
        Kept in netherforge-workspace.json, shared with everyone working on the workspace; whether
        it&apos;s on is yours. Each server keeps its world under its name. Changes apply the next
        time the network starts.
      </p>
    </div>
  )
}

function ServerCard({
  name,
  path,
  arrival,
  runsOpenProject,
  members,
  member,
  onRename,
  onProject,
  onArrival,
  onRemove,
  onConsole,
}: {
  name: string
  path: string
  arrival: boolean
  runsOpenProject: boolean
  members: WorkspaceMember[]
  member: NetworkMember | undefined
  onRename: (to: string) => void
  onProject: (path: string) => void
  onArrival: () => void
  onRemove: () => void
  onConsole: () => void
}) {
  const phase = member?.state.phase ?? 'stopped'
  const known = members.some((it) => it.path === path)
  return (
    <li className={cx(styles.card, runsOpenProject && styles.own, !known && styles.attention)}>
      <div className={styles.cardHead}>
        <StatusDot phase={phase} />
        <CommitInput
          className={styles.nameInput}
          label={`Name of server ${name}`}
          value={name}
          onCommit={onRename}
        />
        {runsOpenProject && <span className={styles.kind}>This project</span>}
        <span className={styles.spacer} />
        <IconButton
          icon="person"
          label={arrival ? `Players arrive on ${name}` : `Make players arrive on ${name}`}
          active={arrival}
          onClick={onArrival}
        />
        <IconButton
          icon="code"
          label={`Show ${name}'s console`}
          disabled={!member}
          onClick={onConsole}
        />
        <IconButton icon="trash" label={`Remove server ${name}`} onClick={onRemove} />
      </div>
      <div className={styles.project}>
        <Icon name="folder" className={styles.projectIcon} />
        <select
          className={styles.projectInput}
          aria-label={`Project of server ${name}`}
          value={path}
          onChange={(e) => onProject(e.target.value)}
        >
          {!known && <option value={path}>{path} (not a project of the workspace)</option>}
          {members.map((it) => (
            <option key={it.path} value={it.path}>
              {it.name} · {it.path}
            </option>
          ))}
        </select>
      </div>
      <p className={styles.meta}>{arrival ? 'Players arrive here' : ' '}</p>
      <ServerStatus phase={phase} connected={member?.state.bridgeConnected} />
    </li>
  )
}
