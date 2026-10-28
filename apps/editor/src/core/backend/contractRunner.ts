/**
 * Runs the backend contract (`contract.json`) on a `Backend`. The cases are
 * in the UI's own terms: calls to `Backend` methods with the arguments the UI
 * passes, the events it listens to, and what the outside world does (another
 * program changing the project, an agent calling the MCP server). Two worlds
 * run them: `MemoryBackend` (`contract.test.ts`) and `TauriBackend` over the
 * real Rust commands (`contract.tauri.node.test.ts`), so the memory backend
 * every UI test runs on can't promise what the app doesn't do, and the
 * Tauri side's mapping onto commands is checked with them.
 *
 * A case is a fresh backend with `project` (project path → text) at `$ROOT`,
 * opened first unless `open` is false, the `packages` beside it at
 * `../<name>`, and each of `repositories` a git repository at `$GIT/<name>`
 * (a `file://` URL), its files committed on main and tagged v1. Its `setup`
 * can add: `gameData` (version → a game data export, as the dev server
 * caches it), `client` (`{ version, files }`, a client jar at `$CLIENT_JAR`
 * holding those files), `installs` (`{ files, found }`: files in the
 * player's home, and the installs found there), `testReport` (what the
 * script test runner reports) and `serverFiles` (the project's dev server
 * folder, path → text) and `workspace` (a `netherforge-workspace.json` in the
 * folder that holds the project and its packages, so they're a workspace's).
 *
 * Steps:
 * - `{ call, args, ok | error, save, fetch }` calls a method: `ok` is matched
 *   as a subset (every key given must match; `"$any"` matches anything;
 *   arrays match element by element; `{ "$includes": [...] }` is an array
 *   holding each; `{ "$oneOf": [...] }` any of them), `error` is the
 *   `BackendError`'s code. `save` keeps a string answer as `$<save>`. `fetch`
 *   loads the URL the call answered, as the webview would: `{ status, text }`.
 * - `{ listen, args, as }` subscribes to an `on…` method (after its `args`),
 *   recording its events under `as` (the method's name by default).
 * - `{ expect, event, within }` waits (up to `within` ms, 30 s by default) for
 *   an event recorded under `expect` since the last call, outside change or
 *   agent, matching `event`.
 * - `{ outside: "write" | "delete" | "rename", path, text, from, to }`: another
 *   program changes the open project's files.
 * - `{ agent, reply, ok }`: a coding agent sends the JSON-RPC request `agent`
 *   to the MCP server; when it reaches the UI (`onMcpMessage`), the case
 *   answers `reply` (`{ result }` or `{ error }`) through `mcpSend`; `ok` is
 *   matched against the agent's answer.
 *
 * `$ROOT`, `$GIT`, `$CLIENT_JAR`, `$PORT` and `$MCP_PORT` (two free ports) and
 * saved answers are put into every string; a string that is only a number's
 * name becomes the number.
 */
import { expect } from 'vitest'
import contract from './contract.json'
import { BackendError, type Backend } from './types'

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

export interface ContractSetup {
  gameData?: Record<string, Json>
  client?: { version: string; files: Record<string, string> }
  installs?: { files: string[]; found: Json[] }
  testReport?: Json
  serverFiles?: Record<string, string>
  /** A `netherforge-workspace.json` in the folder above `$ROOT`, beside the packages: the project and they become a workspace's. */
  workspace?: string
}

export interface ContractCase {
  name: string
  open?: boolean
  setup?: ContractSetup
  steps: Step[]
}

export type OutsideChange =
  | { outside: 'write'; path: string; text: string }
  | { outside: 'delete'; path: string }
  | { outside: 'rename'; from: string; to: string }

export type Step =
  | {
      call: string
      args?: Json[]
      ok?: Json
      error?: string
      save?: string
      fetch?: { status: number; text?: string }
    }
  | { listen: string; args?: Json[]; as?: string }
  | { expect: string; event: Json; within?: number }
  | OutsideChange
  | { agent: Json; reply: Json; ok: Json }

export const suite = contract as unknown as {
  project: Record<string, string>
  packages: Record<string, Record<string, string>>
  repositories: Record<string, Record<string, string>>
  cases: ContractCase[]
}

/** Whether a case listens to file changes, so the world waits for its watcher before going on. */
export const watches = (testCase: ContractCase) =>
  testCase.steps.some((step) => 'listen' in step && step.listen === 'onFilesChanged')

/** A backend to run one case on, and the outside world around it. */
export interface ContractWorld {
  backend: Backend
  /** `$ROOT`, `$GIT`, `$CLIENT_JAR`, `$PORT`, `$MCP_PORT`. */
  vars: Record<string, string | number>
  outside(change: OutsideChange): Promise<void>
  /** A coding agent's MCP request; resolves with what it's answered. */
  agent(message: Json): Promise<Json>
  /** What the webview gets for one of the backend's URLs. */
  fetch(url: string): Promise<{ status: number; text: string }>
}

/** How long a case waits for an event, unless it says. */
const PATIENCE = 30_000

/** [value] with every `$<name>` put in. */
export function substitute(value: unknown, vars: Record<string, string | number>): unknown {
  if (typeof value === 'string') {
    const whole = value.startsWith('$') ? vars[value.slice(1)] : undefined
    if (whole !== undefined) return whole
    // A path under the root is spelled with the root's own separator, as the backend answers it
    // (`C:\…\project\new` on Windows), so `$ROOT/new` means the same file on every OS.
    const root = vars.ROOT
    if (value.startsWith('$ROOT/') && typeof root === 'string' && root.includes('\\'))
      return root + value.slice('$ROOT'.length).replaceAll('/', '\\')
    // Longest names first, so `$PORT` never eats into `$MCP_PORT`'s value.
    return Object.entries(vars)
      .sort(([a], [b]) => b.length - a.length)
      .reduce((text, [name, it]) => text.replaceAll(`$${name}`, String(it)), value)
  }
  if (Array.isArray(value)) return value.map((it) => substitute(it, vars))
  if (value && typeof value === 'object')
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, vars)]))
  return value
}

/** [actual] has everything [expected] says (see the file's comment). */
export function matches(expected: unknown, actual: unknown): boolean {
  if (expected === '$any') return true
  if (Array.isArray(expected))
    return (
      Array.isArray(actual) &&
      actual.length === expected.length &&
      expected.every((it, i) => matches(it, actual[i]))
    )
  if (expected && typeof expected === 'object') {
    const special = expected as { $includes?: unknown[]; $oneOf?: unknown[] }
    if (special.$includes)
      return (
        Array.isArray(actual) && special.$includes.every((it) => actual.some((a) => matches(it, a)))
      )
    if (special.$oneOf) return special.$oneOf.some((it) => matches(it, actual))
    return (
      !!actual &&
      typeof actual === 'object' &&
      Object.entries(expected).every(([k, v]) => matches(v, (actual as Record<string, unknown>)[k]))
    )
  }
  // Commands answer null where a method returns nothing.
  return expected === actual || (expected === null && actual === undefined)
}

type Method = (...args: unknown[]) => unknown

/** What each subscription heard, for a failure to show what did happen (the server's output with it). */
function heard(events: Map<string, unknown[]>): string {
  return [...events]
    .map(
      ([key, list]) =>
        `  ${key}: ${list
          .slice(-20)
          .map((it) => JSON.stringify(it))
          .join('\n    ')}`,
    )
    .join('\n')
}

/** Runs [testCase]'s steps on [world]. */
export async function runCase(world: ContractWorld, testCase: ContractCase): Promise<void> {
  const vars: Record<string, string | number> = { ...world.vars }
  const events = new Map<string, unknown[]>()
  /** Where each recording stood at the last call or change: expectations look from there. */
  const marks = new Map<string, number>()
  const act = () => {
    for (const [key, list] of events) marks.set(key, list.length)
  }
  const method = (name: string): Method => {
    const it = (world.backend as unknown as Record<string, unknown>)[name]
    expect(typeof it, `Backend has no method ${name}`).toBe('function')
    return (it as Method).bind(world.backend)
  }

  for (const [index, step] of testCase.steps.entries()) {
    const at = `step ${index + 1} (${JSON.stringify(step).slice(0, 80)})`
    if ('call' in step) {
      act()
      const args = (substitute(step.args ?? [], vars) as unknown[]) ?? []
      const outcome = await Promise.resolve()
        .then(() => method(step.call)(...args))
        .then(
          (value) => ({ value }),
          (error: unknown) => ({ error }),
        )
      if ('error' in outcome) {
        expect(
          outcome.error,
          `${at}: rejected with something other than a BackendError`,
        ).toBeInstanceOf(BackendError)
        const code = (outcome.error as BackendError).code
        expect(
          step.error === undefined ? `succeeds, not ${code}: ${String(outcome.error)}` : code,
          at,
        ).toBe(step.error ?? 'succeeds')
        continue
      }
      expect(step.error, `${at}: should have failed`).toBeUndefined()
      const ok = substitute(step.ok, vars)
      if (step.ok !== undefined)
        expect(
          matches(ok, outcome.value),
          `${at}: expected ${JSON.stringify(ok)}, got ${JSON.stringify(outcome.value)}`,
        ).toBe(true)
      if (step.save) vars[step.save] = outcome.value as string
      if (step.fetch) {
        const got = await world.fetch(outcome.value as string)
        const want = {
          status: step.fetch.status,
          ...(step.fetch.text === undefined ? {} : { text: step.fetch.text }),
        }
        expect(
          matches(want, got),
          `${at}: fetching ${String(outcome.value)} got ${JSON.stringify(got)}`,
        ).toBe(true)
      }
    } else if ('listen' in step) {
      const key = step.as ?? step.listen
      const list: unknown[] = events.get(key) ?? []
      events.set(key, list)
      marks.set(key, list.length)
      const args = substitute(step.args ?? [], vars) as unknown[]
      await method(step.listen)(...args, (event: unknown) => list.push(event))
    } else if ('expect' in step) {
      const list = events.get(step.expect)
      expect(list, `${at}: nothing listens as ${step.expect}`).toBeDefined()
      const want = substitute(step.event, vars)
      const from = marks.get(step.expect) ?? 0
      const deadline = Date.now() + (step.within ?? PATIENCE)
      while (!list!.slice(from).some((event) => matches(want, event))) {
        if (Date.now() > deadline)
          expect.fail(
            `${at}: no event matching ${JSON.stringify(want)}; got ${JSON.stringify(list!.slice(from))}` +
              `\nEvery event the case heard (the last 20 of each):\n${heard(events)}`,
          )
        await new Promise((resolve) => setTimeout(resolve, 10))
      }
    } else if ('outside' in step) {
      act()
      await world.outside(substitute(step, vars) as OutsideChange)
    } else {
      act()
      const request = substitute(step.agent, vars) as { method: string }
      const reply = substitute(step.reply, vars) as Record<string, Json>
      const unlisten = await world.backend.onMcpMessage(({ message }) => {
        const received = message as { id?: Json; method?: string }
        if (received.method === request.method)
          void world.backend.mcpSend({ jsonrpc: '2.0', id: received.id ?? null, ...reply })
      })
      try {
        const answer = await world.agent(request as unknown as Json)
        const ok = substitute(step.ok, vars)
        expect(
          matches(ok, answer),
          `${at}: the agent expected ${JSON.stringify(ok)}, got ${JSON.stringify(answer)}`,
        ).toBe(true)
      } finally {
        unlisten()
      }
    }
  }
}
