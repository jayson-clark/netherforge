/**
 * The dev server's output and the editor's own lines, filterable, with links to sources and a
 * command line. While a dev network runs, a switcher over the log picks whose console it is: the
 * project's own server, the proxy, or another server (the network store's `viewing`).
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { NetworkMember, ServerPhase } from '@/core/backend/types'
import { findSourceRefs } from '@/core/paths'
import type { ConsoleLine } from '@/core/store/run'
import { useApp, useNetwork, useRun } from '@/state/providers'
import { Button } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Bar } from '@/ui/layout'
import styles from './Console.module.css'
import { PHASE_LABEL, StatusDot } from './ServerStatus'

const NO_LINES: ConsoleLine[] = []

export function ConsolePanel() {
  const { workspace, run, network } = useApp()
  const members = useNetwork((s) => s.members)
  const viewing = useNetwork((s) => s.viewing)
  // The attached server's console is the editor's own (null), never a member's.
  const member = members.find((it) => it.name === viewing && !it.attached) ?? null
  // The buffer changes in place; `lines()` is the same array until the next change, a new one after.
  const ownLines = useRun((s) => s.console.lines())
  const memberLines = useNetwork((s) =>
    member ? (s.consoles.get(member.name)?.lines() ?? NO_LINES) : NO_LINES,
  )
  const lines = member ? memberLines : ownLines
  const ownRunning = useRun((s) => s.server.phase === 'running')
  const connected = member ? member.state.phase === 'running' : ownRunning
  const [filter, setFilter] = useState('')
  const [command, setCommand] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const shown = filter
    ? lines.filter((line) => line.text.toLowerCase().includes(filter.toLowerCase()))
    : lines

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' })
  }, [shown.length])

  const openSource = (file: string, line?: number) =>
    void workspace.getState().openFile(file, { line: line ?? 1 })

  const name = member?.name
  return (
    <div className={styles.console}>
      {members.length > 0 && <ServerSwitcher members={members} viewing={name ?? null} />}
      <Bar>
        <input
          aria-label="Filter console"
          placeholder="Filter"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <Button
          size="small"
          onClick={() =>
            name ? network.getState().clearConsole(name) : run.getState().clearConsole()
          }
        >
          Clear
        </Button>
      </Bar>
      <div className={styles.lines} role="log" aria-label={name ? `Console of ${name}` : 'Console'}>
        {shown.map((line) => (
          <ConsoleRow key={line.id} line={line} openSource={openSource} />
        ))}
        <div ref={end} />
      </div>
      <form
        className={styles.input}
        onSubmit={(event) => {
          event.preventDefault()
          if (name) void network.getState().command(name, command)
          else void run.getState().command(command)
          setCommand('')
        }}
      >
        <span aria-hidden="true">&gt;</span>
        <input
          aria-label={name ? `Command for ${name}` : 'Server command'}
          placeholder={
            connected
              ? name
                ? `Command for ${name}`
                : 'Server command'
              : 'Start the server to run commands'
          }
          disabled={!connected}
          value={command}
          onChange={(e) => setCommand(e.target.value)}
        />
      </form>
    </div>
  )
}

/** Whose console shows: the project's own server, then the proxy and the other servers, each with its state. */
function ServerSwitcher({
  members,
  viewing,
}: {
  members: NetworkMember[]
  viewing: string | null
}) {
  const { network } = useApp()
  const own = useRun((s) => s.server.phase)
  const layoutName = useNetwork((s) => s.layout?.name ?? 'this project')
  // In a workspace's network the editor's own console is the attached server's: listed once, first.
  const attached = members.find((it) => it.attached)
  const ownName = attached?.name ?? layoutName
  const entries: { key: string | null; label: string; phase: ServerPhase; hint: string }[] = [
    { key: null, label: ownName, phase: own, hint: "This project's server" },
    ...members
      .filter((it) => !it.attached)
      .map((it) => ({
        key: it.name,
        label: it.name,
        phase: it.state.phase,
        hint: it.kind === 'proxy' ? 'The proxy (Velocity)' : (it.project ?? it.name),
      })),
  ]
  return (
    <div className={styles.switcher} role="group" aria-label="Console of">
      {entries.map((entry) => (
        <button
          key={entry.key ?? ''}
          type="button"
          className={styles.server}
          aria-pressed={entry.key === viewing}
          title={`${entry.hint}: ${PHASE_LABEL[entry.phase]}`}
          onClick={() => network.getState().view(entry.key)}
        >
          <StatusDot phase={entry.phase} />
          {entry.label}
        </button>
      ))}
    </div>
  )
}

function ConsoleRow({
  line,
  openSource,
}: {
  line: ConsoleLine
  openSource: (file: string, line?: number) => void
}) {
  return (
    <div
      className={cx(
        styles.line,
        line.kind === 'editor' && styles.editor,
        line.level === 'warn' && styles.warn,
        line.level === 'error' && styles.error,
      )}
    >
      {line.source && (
        <button
          type="button"
          className={styles.sourceLink}
          onClick={() => openSource(line.source!.file, line.source!.line)}
          aria-label={`Open ${line.source.file}${line.source.line ? `:${line.source.line}` : ''}`}
        >
          {line.source.file}
          {line.source.line ? `:${line.source.line}` : ''}
        </button>
      )}
      <Linkified text={line.text} openSource={openSource} />
      {line.detail && <pre className={styles.traceback}>{line.detail}</pre>}
    </div>
  )
}

/** Turns `centities/x/y.lua:12` in output into links. */
function Linkified({
  text,
  openSource,
}: {
  text: string
  openSource: (file: string, line?: number) => void
}) {
  const refs = findSourceRefs(text)
  if (refs.length === 0) return <span>{text}</span>
  const parts: ReactNode[] = []
  let at = 0
  refs.forEach((ref, i) => {
    if (ref.start > at) parts.push(text.slice(at, ref.start))
    parts.push(
      <button
        type="button"
        key={i}
        className={styles.sourceLink}
        onClick={() => openSource(ref.file, ref.line)}
      >
        {text.slice(ref.start, ref.end)}
      </button>,
    )
    at = ref.end
  })
  parts.push(text.slice(at))
  return <span>{parts}</span>
}
