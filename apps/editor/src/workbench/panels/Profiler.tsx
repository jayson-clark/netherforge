/**
 * The Profiler panel: what the dev server's tick spends its time on, measured
 * exactly by the plugin and streamed once a second (see `core/store/profiler`).
 * A timeline of the last ticks by step, and a table of every function scripts
 * run (or every scope) since the last reset, sortable by total, mean, worst
 * and calls; a function's place opens its script at the line.
 */
import { useState } from 'react'
import type { SourceRef } from '@netherforge/format/types'
import { sortRows, type HandlerRow, type ScopeRow, type SortKey } from '@/core/store/profiler'
import { useApp, useProfiler, useRun } from '@/state/providers'
import { Button } from '@/ui/Button'
import { Bar, Spacer } from '@/ui/layout'
import { Empty, Muted } from '@/ui/text'
import { ms, ProfileTimeline } from './ProfileTimeline'
import styles from './Profiler.module.css'

type View = 'functions' | 'scopes'

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'total', label: 'Total ms' },
  { key: 'calls', label: 'Calls' },
  { key: 'mean', label: 'Mean ms' },
  { key: 'max', label: 'Max ms' },
]

/** Rows drawn at most: the rest are the cheapest. */
const SHOWN = 200

export function ProfilerPanel() {
  const { profiler, workspace } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const subscribed = useProfiler((s) => s.subscribed)
  const ticks = useProfiler((s) => s.ticks)
  const handlers = useProfiler((s) => s.handlers)
  const scopes = useProfiler((s) => s.scopes)
  const measured = useProfiler((s) => s.measured)
  const nanos = useProfiler((s) => s.nanos)
  const [view, setView] = useState<View>('functions')
  const [sort, setSort] = useState<SortKey>('total')

  if (!connected && measured === 0)
    return <Empty>Start the dev server to see where its ticks go.</Empty>
  if (connected && !subscribed && measured === 0)
    return <Empty>Waiting for the dev server&apos;s profiler…</Empty>

  const open = (source: SourceRef) =>
    void workspace.getState().openFile(source.file, { line: source.line ?? 1 })

  return (
    <div className={styles.profiler}>
      <Bar>
        <div role="group" aria-label="Show" className={styles.toggle}>
          <Button
            size="small"
            active={view === 'functions'}
            aria-pressed={view === 'functions'}
            onClick={() => setView('functions')}
          >
            Functions
          </Button>
          <Button
            size="small"
            active={view === 'scopes'}
            aria-pressed={view === 'scopes'}
            onClick={() => setView('scopes')}
          >
            Scopes
          </Button>
        </div>
        <Muted>
          {measured} ticks
          {measured > 0 && `, ${ms(nanos / measured)} ms a tick on average`}
        </Muted>
        <Spacer />
        <Button size="small" icon="refresh" onClick={() => profiler.getState().reset()}>
          Reset
        </Button>
      </Bar>
      {ticks.length > 0 && <ProfileTimeline ticks={ticks} />}
      <div className={styles.rows}>
        {view === 'functions' ? (
          <Functions rows={[...handlers.values()]} sort={sort} onSort={setSort} open={open} />
        ) : (
          <Scopes rows={[...scopes.values()]} ticks={measured} sort={sort} onSort={setSort} />
        )}
      </div>
    </div>
  )
}

function Header({ sort, onSort }: { sort: SortKey; onSort: (key: SortKey) => void }) {
  return (
    <>
      {COLUMNS.map((column) => (
        <th
          key={column.key}
          className={styles.numeric}
          aria-sort={sort === column.key ? 'descending' : 'none'}
        >
          <button type="button" className={styles.sort} onClick={() => onSort(column.key)}>
            {column.label}
            {sort === column.key && ' ▾'}
          </button>
        </th>
      ))}
    </>
  )
}

function Numbers({ row }: { row: { nanos: number; calls: number; max: number } }) {
  return (
    <>
      <td className={styles.numeric}>{ms(row.nanos)}</td>
      <td className={styles.numeric}>{row.calls}</td>
      <td className={styles.numeric}>{ms(row.calls === 0 ? 0 : row.nanos / row.calls)}</td>
      <td className={styles.numeric}>{ms(row.max)}</td>
    </>
  )
}

function Functions({
  rows,
  sort,
  onSort,
  open,
}: {
  rows: readonly HandlerRow[]
  sort: SortKey
  onSort: (key: SortKey) => void
  open: (source: SourceRef) => void
}) {
  const sorted = sortRows(rows, sort).slice(0, SHOWN)
  return (
    <table aria-label="Functions">
      <thead>
        <tr>
          <th>Script</th>
          <th>Call</th>
          <th>Where</th>
          <Header sort={sort} onSort={onSort} />
        </tr>
      </thead>
      <tbody>
        {sorted.map((row) => (
          <tr key={row.key}>
            <td>
              {row.script}
              {row.scopes > 1 && <Muted> ×{row.scopes}</Muted>}
            </td>
            <td>{row.kind}</td>
            <td>
              {row.source ? (
                <button
                  type="button"
                  className={styles.source}
                  onClick={() => open(row.source!)}
                  aria-label={`Open ${where(row.source)}`}
                >
                  {where(row.source)}
                </button>
              ) : (
                <Muted>NetherForge</Muted>
              )}
            </td>
            <Numbers row={row} />
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Scopes({
  rows,
  ticks,
  sort,
  onSort,
}: {
  rows: readonly ScopeRow[]
  ticks: number
  sort: SortKey
  onSort: (key: SortKey) => void
}) {
  const sorted = sortRows(rows, sort).slice(0, SHOWN)
  return (
    <table aria-label="Scopes">
      <thead>
        <tr>
          <th>Scope</th>
          <th className={styles.numeric}>Ms a tick</th>
          <Header sort={sort} onSort={onSort} />
        </tr>
      </thead>
      <tbody>
        {sorted.map((row) => (
          <tr key={row.key}>
            <td>{row.scope}</td>
            <td className={styles.numeric}>{ms(ticks === 0 ? 0 : row.nanos / ticks)}</td>
            <Numbers row={row} />
          </tr>
        ))}
      </tbody>
    </table>
  )
}

const where = (source: SourceRef) => (source.line ? `${source.file}:${source.line}` : source.file)
