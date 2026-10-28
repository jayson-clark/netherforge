/**
 * The profiler as the editor keeps it: the dev server measures every tick
 * exactly and sends a batch about once a second on the bridge's `profiler`
 * stream (see the hot-reload skill), which this store subscribes to whenever
 * the bridge comes up. It keeps the last ticks for the timeline and every
 * function's and scope's totals since the last reset, for the Profiler panel.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { ProfileSample, ProfileTick, SourceRef } from '@netherforge/format/types'
import { isBackendError, type Backend, type Unlisten } from '@/core/backend/types'

/** One function's totals: a handler, a timer, a task, a command… */
export interface HandlerRow {
  key: string
  script: string
  /** The event a handler is for (`tick`), or `load`, `timer`, `task`, `command`, `complete`, `goal`, `callback`. */
  kind: string
  source?: SourceRef
  calls: number
  /** Nanoseconds of its own time, in all. */
  nanos: number
  /** Its slowest call, nanoseconds. */
  max: number
  /** The most scopes that ran it in one batch (each centity instance is one). */
  scopes: number
}

/** One scope's totals: a module, or one instance's, window's or dialog's script. */
export interface ScopeRow {
  key: string
  scope: string
  calls: number
  nanos: number
  /** Its most expensive tick, nanoseconds. */
  max: number
}

export interface ProfilerData {
  /** The last ticks, oldest first, at most `TIMELINE_TICKS`. */
  ticks: ProfileTick[]
  handlers: Map<string, HandlerRow>
  scopes: Map<string, ScopeRow>
  /** Ticks measured since the last reset. */
  measured: number
  /** NetherForge's own time over those ticks, nanoseconds. */
  nanos: number
}

export interface ProfilerState extends ProfilerData {
  /** Whether the dev server streams to us (false on a server that doesn't have the profiler). */
  subscribed: boolean
}

export interface ProfilerActions {
  /** Hears the stream; call once. */
  connect(): Promise<void>
  disconnect(): void
  /** Asks the dev server for the stream (when the bridge comes up). */
  subscribe(): Promise<void>
  /** Forgets the totals and the timeline. */
  reset(): void
}

export type Profiler = ProfilerState & ProfilerActions
export type ProfilerStore = StoreApi<Profiler>

/** Ticks the timeline keeps: six seconds. */
export const TIMELINE_TICKS = 120

export const emptyProfile = (): ProfilerData => ({
  ticks: [],
  handlers: new Map(),
  scopes: new Map(),
  measured: 0,
  nanos: 0,
})

/** A function's row key: the same function of the same script, whichever scope ran it. */
export const handlerKey = (script: string, kind: string, source?: SourceRef) =>
  `${script}\u0000${kind}\u0000${source?.file ?? ''}\u0000${source?.line ?? ''}`

/** [data] with one more batch in it; nothing in [data] is changed. */
export function addSample(data: ProfilerData, sample: ProfileSample): ProfilerData {
  const handlers = new Map(data.handlers)
  for (const it of sample.handlers ?? []) {
    const key = handlerKey(it.script, it.kind, it.source)
    const row = handlers.get(key)
    handlers.set(key, {
      key,
      script: it.script,
      kind: it.kind,
      source: it.source,
      calls: (row?.calls ?? 0) + it.calls,
      nanos: (row?.nanos ?? 0) + it.nanos,
      max: Math.max(row?.max ?? 0, it.max),
      scopes: Math.max(row?.scopes ?? 0, it.scopes ?? 1),
    })
  }
  const scopes = new Map(data.scopes)
  for (const it of sample.scopes ?? []) {
    const row = scopes.get(it.scope)
    scopes.set(it.scope, {
      key: it.scope,
      scope: it.scope,
      calls: (row?.calls ?? 0) + it.calls,
      nanos: (row?.nanos ?? 0) + it.nanos,
      max: Math.max(row?.max ?? 0, it.max ?? 0),
    })
  }
  return {
    ticks: [...data.ticks, ...sample.ticks].slice(-TIMELINE_TICKS),
    handlers,
    scopes,
    measured: data.measured + sample.ticks.length,
    nanos: data.nanos + sample.ticks.reduce((sum, tick) => sum + tick.nanos, 0),
  }
}

export type SortKey = 'total' | 'mean' | 'max' | 'calls'

/** What a sort reads from a row: total and calls as they are, mean per call, max its worst. */
export function sortValue(
  row: { nanos: number; calls: number; max: number },
  key: SortKey,
): number {
  switch (key) {
    case 'total':
      return row.nanos
    case 'calls':
      return row.calls
    case 'mean':
      return row.calls === 0 ? 0 : row.nanos / row.calls
    case 'max':
      return row.max
  }
}

/** [rows] by [key], the largest first (ties by their key, so the order is stable between batches). */
export function sortRows<R extends { key: string; nanos: number; calls: number; max: number }>(
  rows: Iterable<R>,
  key: SortKey,
  descending = true,
): R[] {
  const sign = descending ? -1 : 1
  return [...rows].sort(
    (a, b) => sign * (sortValue(a, key) - sortValue(b, key)) || a.key.localeCompare(b.key),
  )
}

export function createProfiler(backend: Backend): ProfilerStore {
  let unlisten: Unlisten | null = null
  return createStore<Profiler>()((set, get) => ({
    ...emptyProfile(),
    subscribed: false,

    async connect() {
      get().disconnect()
      unlisten = await backend.onBridgeEvent('profiler', ({ items }) => {
        let data: ProfilerData = get()
        for (const sample of items) data = addSample(data, sample)
        set(data)
      })
    },

    disconnect() {
      unlisten?.()
      unlisten = null
    },

    async subscribe() {
      try {
        await backend.bridgeRequest('profiler_subscribe', { on: true })
        set({ subscribed: true })
      } catch (error) {
        // A plugin without the profiler (`unknownMethod`), or the bridge went down meanwhile: nothing streams.
        if (!isBackendError(error)) throw error
        set({ subscribed: false })
      }
    },

    reset() {
      set(emptyProfile())
    },
  }))
}
