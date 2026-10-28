/**
 * What most of the editor's tests start from: `examples/showcase` (and
 * `examples/library` beside it) on a `MemoryBackend`, open in a workspace
 * store or in the whole app; and `settle`, the one way a test lets timers run.
 *
 * Tests never wait on the wall clock. A test whose code under test uses
 * timers (the memory backend's file events, debounced validation or layout
 * saves, the fake server) runs on fake timers and moves them on with
 * `settle(ms)`; anything else awaits what it's waiting for (a promise, or
 * `vi.waitFor` / Testing Library's `findBy…` on the state).
 */
import { vi } from 'vitest'
import {
  MemoryBackend,
  type FileContents,
  type MemoryBackendOptions,
} from '@/testing/backend/memory'
import {
  createWorkspace,
  type Workspace,
  type WorkspaceOptions,
  type WorkspaceStore,
} from '@/core/store/workspace'
import { createApp, type AppStores } from '@/state/providers'
import { EXAMPLE_ROOT, exampleProjects } from './fixtures'

export interface ExampleOptions {
  /** The example's files, if not `examples/showcase` as the repo has it. */
  project?: Record<string, FileContents>
  /** The memory backend's other options (its projects are the example and the library). */
  backend?: Omit<MemoryBackendOptions, 'projects'>
}

/** A memory backend holding the example at `EXAMPLE_ROOT` and the library beside it. */
export function exampleBackend(options: ExampleOptions = {}): MemoryBackend {
  return new MemoryBackend({
    ...options.backend,
    projects: exampleProjects(EXAMPLE_ROOT, options.project),
  })
}

export interface ExampleWorkspace {
  backend: MemoryBackend
  workspace: WorkspaceStore
  /** The workspace's state now. */
  ws: () => Workspace
}

/** A workspace store on the example's backend, not opened yet (to attach something first). */
export function exampleWorkspace(
  options: ExampleOptions & WorkspaceOptions = {},
): ExampleWorkspace {
  const backend = exampleBackend(options)
  const workspace = createWorkspace(backend, { validation: options.validation })
  return { backend, workspace, ws: () => workspace.getState() }
}

/** A workspace store with the example open: listed, read, validated. */
export async function openExampleWorkspace(
  options: ExampleOptions & WorkspaceOptions = {},
): Promise<ExampleWorkspace> {
  const example = exampleWorkspace(options)
  await example.ws().openProject(EXAMPLE_ROOT)
  return example
}

export interface ExampleApp {
  backend: MemoryBackend
  app: AppStores
}

/** Every store of the app (as `createApp` wires them) with the example open. */
export async function openExampleApp(options: ExampleOptions = {}): Promise<ExampleApp> {
  const backend = exampleBackend(options)
  const app = createApp(backend)
  await app.workspace.getState().openProject(EXAMPLE_ROOT)
  return { backend, app }
}

/**
 * Fake time [settle] adds for chains of 0 ms timers. A 0 ms timer set while
 * another runs is due 1 ms later on the fake clock (so a chain can't loop
 * forever), and the memory backend's events, its debug adapter's messages and
 * the bridge's JSON-RPC each hop that way: a DAP handshake is a few hops.
 * Well under the store's own delays (validation's 250 ms, the layout's 300 ms).
 */
export const HOPS_MS = 20

/**
 * Moves the fake clock on by [ms], plus `HOPS_MS` for what those timers set
 * off, letting each timer and the promises it starts finish. With no [ms]:
 * whatever is due now, and what that sets off. Needs `vi.useFakeTimers()`.
 * A test about a delay's exact end advances the clock itself
 * (`vi.advanceTimersByTimeAsync`).
 */
export async function settle(ms = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms + HOPS_MS)
}

/**
 * Awaits [promise], moving the fake clock on [step] ms at a time until it
 * settles: for code that sleeps or polls on timers (an MCP tool waiting for
 * the server's log). Fails once [limit] ms of fake time pass without it.
 */
export async function advanceUntil<T>(
  promise: Promise<T>,
  { step = 50, limit = 60_000 }: { step?: number; limit?: number } = {},
): Promise<T> {
  let done = false
  const result = promise.finally(() => {
    done = true
  })
  // Rejected before it's awaited below: handled there, not reported as unhandled.
  result.catch(() => {})
  for (let elapsed = 0; !done && elapsed < limit; elapsed += step) {
    await vi.advanceTimersByTimeAsync(step)
  }
  if (!done) throw new Error(`Still waiting after ${limit} ms of fake time`)
  return result
}
