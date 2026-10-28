/**
 * The Debug panel: the debugger's controls (continue, the steps, pause,
 * stop; breakpoints on or off, breaking on script errors), where the server
 * is stopped and why, the call stack, the selected frame's variables
 * (locals, upvalues, the script's globals; tables and handles open lazily),
 * and every breakpoint. See `core/store/debug.ts`.
 */
import { useMemo, useState } from 'react'
import type { DebugProtocol } from '@vscode/debugprotocol'
import type { Debug, DebugStop } from '@/core/store/debug'
import { useApp, useDebug, useRun } from '@/state/providers'
import { Count } from '@/ui/Badge'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Check } from '@/ui/fields'
import { Bar, Divider, PanelHeader, Spacer } from '@/ui/layout'
import { Empty, Muted } from '@/ui/text'
import { Tree, type TreeNode } from '@/ui/Tree'
import styles from './Debug.module.css'

/** What the stop line says. */
export function describeStop(stop: DebugStop, frame: DebugProtocol.StackFrame | undefined): string {
  const at = frame?.source?.path ? ` at ${frame.source.path}:${frame.line}` : ''
  switch (stop.reason) {
    case 'breakpoint':
      return `Paused on a breakpoint${at}`
    case 'step':
      return `Paused after a step${at}`
    case 'pause':
      return `Paused${at}`
    case 'exception':
      return `Paused on a script error${at}: ${stop.description ?? stop.text ?? 'error'}`
    default:
      return `Paused (${stop.reason})${at}`
  }
}

export function DebugPanel() {
  const { debug } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const session = useDebug((s) => s.session)
  const stop = useDebug((s) => s.stop)
  const frames = useDebug((s) => s.frames)
  const active = useDebug((s) => s.active)
  const breakOnErrors = useDebug((s) => s.breakOnErrors)
  const frameId = useDebug((s) => s.frameId)
  const paused = session === 'paused'
  const d = () => debug.getState()

  return (
    <div className={styles.debug}>
      <Bar>
        <div role="toolbar" aria-label="Debugger" className={styles.toolbar}>
          <IconButton
            icon="resume"
            label="Continue (F5)"
            disabled={!paused}
            onClick={() => void d().continue()}
          />
          <IconButton
            icon="stepOver"
            label="Step Over (F10)"
            disabled={!paused}
            onClick={() => void d().stepOver()}
          />
          <IconButton
            icon="stepInto"
            label="Step Into (F11)"
            disabled={!paused}
            onClick={() => void d().stepInto()}
          />
          <IconButton
            icon="stepOut"
            label="Step Out (Shift+F11)"
            disabled={!paused}
            onClick={() => void d().stepOut()}
          />
          <IconButton
            icon="pause"
            label="Pause"
            disabled={session !== 'running'}
            onClick={() => void d().pause()}
          />
          <IconButton
            icon="stop"
            label="Stop Debugging"
            disabled={session === 'detached'}
            onClick={() => void d().stopDebugging()}
          />
        </div>
        <Divider />
        <Check
          label="Breakpoints"
          title="Breakpoints stop the server (off: they're kept, but not used)"
          checked={active}
          onChange={(on) => d().setActive(on)}
        />
        <Check
          label="Break on script errors"
          checked={breakOnErrors}
          onChange={(on) => d().setBreakOnErrors(on)}
        />
        <Spacer />
        <Muted role="status" aria-label="Debugger status">
          {!connected
            ? 'The dev server isn’t running'
            : session === 'detached'
              ? 'Not debugging'
              : paused && stop
                ? describeStop(stop, frames[0])
                : 'Running'}
        </Muted>
      </Bar>
      <div className={styles.columns}>
        <div className={styles.column}>
          <PanelHeader title="Call Stack" />
          <CallStack />
          <PanelHeader title="Breakpoints" />
          <Breakpoints />
        </div>
        <div className={styles.column}>
          <PanelHeader title="Variables" />
          <Variables key={frameId ?? 0} />
        </div>
      </div>
    </div>
  )
}

function CallStack() {
  const { debug, workspace } = useApp()
  const frames = useDebug((s) => s.frames)
  const frameId = useDebug((s) => s.frameId)
  if (frames.length === 0) return <Empty>Not paused.</Empty>
  return (
    <ol className={styles.list} aria-label="Call stack">
      {frames.map((frame) => (
        <li key={frame.id}>
          <button
            type="button"
            className={cx(styles.row, frame.id === frameId && styles.selected)}
            aria-current={frame.id === frameId ? 'true' : undefined}
            onClick={() => {
              void debug.getState().selectFrame(frame.id)
              if (frame.source?.path)
                void workspace.getState().openFile(frame.source.path, { line: frame.line })
            }}
          >
            <span className={styles.name}>{frame.name}</span>
            {frame.source?.path && (
              <span className={styles.place}>
                {frame.source.path}:{frame.line}
              </span>
            )}
          </button>
        </li>
      ))}
    </ol>
  )
}

function Breakpoints() {
  const { debug, workspace } = useApp()
  const breakpoints = useDebug((s) => s.breakpoints)
  const rows = Object.entries(breakpoints)
    .sort(([a], [b]) => a.localeCompare(b))
    .flatMap(([path, lines]) => lines.map((line) => ({ path, line })))
  if (rows.length === 0)
    return <Empty>No breakpoints. Click beside a line number, or press F9.</Empty>
  return (
    <ul className={styles.list} aria-label="Breakpoints">
      {rows.map(({ path, line }) => (
        <li key={`${path}:${line}`} className={styles.breakpoint}>
          <button
            type="button"
            className={styles.row}
            onClick={() => void workspace.getState().openFile(path, { line })}
          >
            <span className={styles.place}>
              {path}:{line}
            </span>
          </button>
          <IconButton
            icon="close"
            size={12}
            label={`Remove the breakpoint at ${path}:${line}`}
            onClick={() => debug.getState().removeBreakpoint(path, line)}
          />
        </li>
      ))}
    </ul>
  )
}

/** A tree id for each variable: its parent's id and its name, so the same row keeps its place. */
interface Row {
  id: string
  reference: number
}

/** What an expandable row holds until it's fetched: one row saying so, so it has a twisty. */
const loading = (parent: string): TreeNode[] => [{ id: `${parent}\u0000…`, label: 'Loading…' }]

function variableNodes(
  parent: string,
  variables: DebugProtocol.Variable[],
  loaded: Debug['variables'],
  rows: Map<string, Row>,
): TreeNode[] {
  return variables.map((variable) => {
    const id = `${parent}\u0000${variable.name}`
    rows.set(id, { id, reference: variable.variablesReference })
    const children = loaded[variable.variablesReference]
    return {
      id,
      label: variable.name,
      title: variable.type ? `${variable.name}: ${variable.type}` : variable.name,
      detail: (
        <span className={styles.value}>
          {variable.value}
          {variable.type && <Muted className={styles.type}> {variable.type}</Muted>}
        </span>
      ),
      children:
        variable.variablesReference > 0
          ? children
            ? variableNodes(id, children, loaded, rows)
            : loading(id)
          : undefined,
    }
  })
}

function Variables() {
  const { debug } = useApp()
  const scopes = useDebug((s) => s.scopes)
  const loaded = useDebug((s) => s.variables)
  // The scopes start open (the locals are fetched with them); everything else closed.
  const [open, setOpen] = useState<Set<string>>(new Set())
  const [closed, setClosed] = useState<Set<string>>(new Set())
  const [selected, setSelected] = useState<string | null>(null)

  const { nodes, rows } = useMemo(() => {
    const rows = new Map<string, Row>()
    const nodes: TreeNode[] = scopes.map((scope) => {
      const id = `scope:${scope.name}`
      rows.set(id, { id, reference: scope.variablesReference })
      const children = loaded[scope.variablesReference]
      return {
        id,
        label: scope.name,
        children: children ? variableNodes(id, children, loaded, rows) : loading(id),
      }
    })
    return { nodes, rows }
  }, [scopes, loaded])

  if (scopes.length === 0) return <Empty>Not paused.</Empty>
  const isScope = (id: string) => !id.includes('\u0000')
  return (
    <div className={styles.variables}>
      <Tree
        label="Variables"
        nodes={nodes}
        selected={selected}
        onSelect={setSelected}
        isExpanded={(node) => (isScope(node.id) ? !closed.has(node.id) : open.has(node.id))}
        onExpand={(id, expand) => {
          const row = rows.get(id)
          if (expand && row) void debug.getState().expand(row.reference)
          if (isScope(id)) {
            const next = new Set(closed)
            if (expand) next.delete(id)
            else next.add(id)
            setClosed(next)
          } else {
            const next = new Set(open)
            if (expand) next.add(id)
            else next.delete(id)
            setOpen(next)
          }
        }}
      />
    </div>
  )
}

/** The Debug tab's badge: shown while paused. */
export function DebugBadge() {
  const paused = useDebug((s) => s.session === 'paused')
  return paused ? <Count value={1} tone="warning" /> : null
}
