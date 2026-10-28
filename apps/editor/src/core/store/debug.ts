/**
 * The debugger as the editor keeps it. The dev server's plugin is a Debug
 * Adapter Protocol adapter on the bridge's `dap` channel (see the hot-reload
 * skill and `core/debug/client.ts`); this store attaches to it whenever the
 * bridge comes up, sends the breakpoints, and while a breakpoint holds the
 * server it keeps the stop: the call stack, the selected frame's scopes and
 * the variables expanded so far (fetched lazily, by reference; references
 * only mean something until the server runs again).
 *
 * Breakpoints are kept per file (project paths, the ones script errors use)
 * and remembered per project. They follow the file: a rename moves them
 * (`followPaths`), a delete drops them, and an edit moves them with their
 * line (the code editor's decorations track it and report `setBreakpointLines`).
 *
 * While paused the plugin refuses every request that needs the server's main
 * thread, a reload included, so saves wait here (`holdReload`) and are sent
 * once the server runs again.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { DebugProtocol } from '@vscode/debugprotocol'
import type { Backend, Unlisten } from '@/core/backend/types'
import { DapClient } from '@/core/debug/client'
import { basename } from '@/core/paths'
import { browserStorage, type LayoutStorage } from './layout'
import { movedPath, isUnder } from './slice'
import type { WorkspaceStore } from './workspace'

/** The one thread the plugin has: the server's. */
export const SERVER_THREAD = 1

/** The plugin's exception filter: stop where a script's error goes unhandled. */
export const ERRORS_FILTER = 'errors'

/** `detached`: no session (the bridge is down, or stopped). `running`: attached. `paused`: a breakpoint holds the server. */
export type DebugSession = 'detached' | 'running' | 'paused'

export interface DebugStop {
  /** `breakpoint`, `step`, `pause` or `exception`. */
  reason: string
  /** For an exception: the error. */
  description?: string
  text?: string
}

export interface DebugState {
  /** Lines with a breakpoint, by project path, each sorted. */
  breakpoints: Record<string, number[]>
  /** Whether breakpoints stop the server (off: kept, not sent). */
  active: boolean
  /** Whether a script's unhandled error stops the server. */
  breakOnErrors: boolean
  session: DebugSession
  stop: DebugStop | null
  frames: DebugProtocol.StackFrame[]
  /** The frame the variables are of. */
  frameId: number | null
  scopes: DebugProtocol.Scope[]
  /** What each expanded reference holds, by its reference: scopes and tables. */
  variables: Record<number, DebugProtocol.Variable[]>
}

export interface DebugActions {
  /** Hears the server and the adapter, and follows the workspace's project and paths; call once. */
  connect(): Promise<void>
  disconnect(): void
  /** Attaches to the dev server's adapter (when the bridge comes up, or breakpoints are activated again). */
  attach(): Promise<void>
  toggleBreakpoint(path: string, line: number): void
  /** The lines [path]'s breakpoints are on now (after edits moved them). */
  setBreakpointLines(path: string, lines: number[]): void
  removeBreakpoint(path: string, line: number): void
  clearBreakpoints(): void
  /** Breakpoints on or off (kept either way); turning them on attaches again after a stop. */
  setActive(active: boolean): void
  setBreakOnErrors(on: boolean): void
  continue(): Promise<void>
  stepOver(): Promise<void>
  stepInto(): Promise<void>
  stepOut(): Promise<void>
  /** Asks the server to stop at the next line of script it runs. */
  pause(): Promise<void>
  /** Ends the session: the server runs on without breakpoints until they're activated again. */
  stopDebugging(): Promise<void>
  selectFrame(id: number): Promise<void>
  /** Fetches what [reference] holds, once per stop. */
  expand(reference: number): Promise<void>
  /** Whether [paths] wait for the server to run again (it's paused); they're reloaded then. */
  holdReload(paths: string[]): boolean
  /**
   * Lets a paused server go before it's stopped (held at a breakpoint it would
   * never read the `stop` command): detaches, keeping the breakpoints for the
   * next start, which attaches again.
   */
  release(): Promise<void>
}

export type Debug = DebugState & DebugActions
export type DebugStore = StoreApi<Debug>

const NO_STOP = {
  stop: null,
  frames: [],
  frameId: null,
  scopes: [],
  variables: {},
} satisfies Partial<DebugState>

const storageKey = (root: string) => `netherforge.debug:${root}`

/** What's remembered per project. */
interface Kept {
  breakpoints: Record<string, number[]>
  active: boolean
  breakOnErrors: boolean
}

function readKept(text: string | null): Kept {
  const empty: Kept = { breakpoints: {}, active: true, breakOnErrors: false }
  let it: unknown
  try {
    it = JSON.parse(text ?? 'null')
  } catch {
    return empty
  }
  if (!it || typeof it !== 'object') return empty
  const value = it as Record<string, unknown>
  const breakpoints: Record<string, number[]> = {}
  if (value.breakpoints && typeof value.breakpoints === 'object') {
    for (const [path, lines] of Object.entries(value.breakpoints as Record<string, unknown>)) {
      if (!Array.isArray(lines)) continue
      const kept = normalLines(lines.filter((n): n is number => Number.isInteger(n)))
      if (kept.length > 0) breakpoints[path] = kept
    }
  }
  return {
    breakpoints,
    active: value.active !== false,
    breakOnErrors: value.breakOnErrors === true,
  }
}

/** Distinct positive lines, in order. */
const normalLines = (lines: number[]) =>
  [...new Set(lines.filter((n) => n > 0))].sort((a, b) => a - b)

const sameLines = (a: number[] | undefined, b: number[]) =>
  (a ?? []).length === b.length && (a ?? []).every((n, i) => n === b[i])

export function createDebug(
  backend: Backend,
  workspace: WorkspaceStore,
  storage: LayoutStorage = browserStorage,
): DebugStore {
  const client = new DapClient({
    send: (message) => backend.dapSend(message),
    listen: (listener) => backend.onBridgeEvent('dap', listener),
  })
  let unlisteners: Unlisten[] = []
  let root: string | null = null
  /** Whether the bridge is up: what the adapter can hear. */
  let bridgeUp = false
  /** The files the adapter has breakpoints for, so clearing one reaches it. */
  let sent = new Set<string>()
  /** Paths saved while paused, reloaded once the server runs. */
  let held: string[] = []
  /** Counts attaches, so an old one that finishes late does nothing. */
  let generation = 0
  /** Counts stops, so the answer to resuming one doesn't end the next. */
  let stops = 0

  return createStore<Debug>()((set, get) => {
    const save = () => {
      if (!root) return
      const { breakpoints, active, breakOnErrors } = get()
      storage.set(storageKey(root), JSON.stringify({ breakpoints, active, breakOnErrors }))
    }

    /** Tells the adapter [path]'s breakpoints (none while deactivated). */
    const send = async (path: string) => {
      if (get().session === 'detached') return
      const lines = get().active ? (get().breakpoints[path] ?? []) : []
      if (lines.length === 0 && !sent.has(path)) return
      if (lines.length > 0) sent.add(path)
      else sent.delete(path)
      try {
        await client.request('setBreakpoints', {
          source: { path, name: basename(path) },
          breakpoints: lines.map((line) => ({ line })),
        })
      } catch {
        // The session went meanwhile; the next attach sends them all.
      }
    }

    const sendExceptions = async () => {
      if (get().session === 'detached') return
      try {
        await client.request('setExceptionBreakpoints', {
          filters: get().breakOnErrors ? [ERRORS_FILTER] : [],
        })
      } catch {
        // As above.
      }
    }

    const changeBreakpoints = (next: Record<string, number[]>, paths: string[]) => {
      set({ breakpoints: next })
      save()
      for (const path of paths) void send(path)
    }

    /** The server runs again: what was held reloads. */
    const resumed = () => {
      set({ session: get().session === 'detached' ? 'detached' : 'running', ...NO_STOP })
      const paths = held
      held = []
      if (paths.length > 0) workspace.getState().hotReload(paths)
    }

    const stopped = async (body: DebugProtocol.StoppedEvent['body']) => {
      stops += 1
      set({
        session: 'paused',
        ...NO_STOP,
        stop: { reason: body.reason, description: body.description, text: body.text },
      })
      try {
        const trace = await client.request('stackTrace', { threadId: SERVER_THREAD })
        if (get().session !== 'paused') return
        set({ frames: trace.stackFrames })
        const top = trace.stackFrames[0]
        if (top) await get().selectFrame(top.id)
      } catch {
        // The server ran on (or went) before it answered.
      }
    }

    /** Resumes with [command]; the `continued` event (or the answer) says it runs. */
    const resume = async (command: 'continue' | 'next' | 'stepIn' | 'stepOut') => {
      if (get().session !== 'paused') return
      const stop = stops
      try {
        await client.request(command, { threadId: SERVER_THREAD })
        // Usually the `continued` event said so already; a step may even have stopped again.
        if (stop === stops && get().session === 'paused') resumed()
      } catch {
        // Gone meanwhile: `bridgeDown` already put it right.
      }
    }

    const bridgeDown = () => {
      bridgeUp = false
      generation += 1
      client.reset()
      sent = new Set()
      // A server that went away reloads everything when it's back; nothing to hold for it.
      held = []
      set({ session: 'detached', ...NO_STOP })
    }

    const follow = (moves: { from: string; to: string }[]) => {
      let breakpoints = get().breakpoints
      const touched = new Set<string>()
      for (const { from, to } of moves) {
        const move = movedPath(from, to)
        const next: Record<string, number[]> = {}
        for (const [path, lines] of Object.entries(breakpoints)) {
          const there = move(path)
          if (there === null) {
            next[path] = lines
            continue
          }
          next[there] = normalLines([...(next[there] ?? []), ...lines])
          touched.add(path)
          touched.add(there)
        }
        breakpoints = next
      }
      if (touched.size > 0) changeBreakpoints(breakpoints, [...touched])
    }

    const gone = (path: string) => {
      const dropped = Object.keys(get().breakpoints).filter((it) => isUnder(it, path))
      if (dropped.length === 0) return
      const next = { ...get().breakpoints }
      for (const it of dropped) delete next[it]
      changeBreakpoints(next, dropped)
    }

    return {
      breakpoints: {},
      active: true,
      breakOnErrors: false,
      session: 'detached',
      ...NO_STOP,

      async connect() {
        get().disconnect()
        const followProject = () => {
          const next = workspace.getState().project?.root ?? null
          if (next === root) return
          root = next
          set({ ...readKept(root ? storage.get(storageKey(root)) : null) })
        }
        followProject()
        unlisteners = [
          workspace.subscribe(followProject),
          workspace
            .getState()
            .followPaths((change) =>
              'moves' in change ? follow(change.moves) : gone(change.gone),
            ),
          client.on('stopped', (body) => void stopped(body)),
          client.on('continued', () => {
            if (get().session === 'paused') resumed()
          }),
          client.on('terminated', () => set({ session: 'detached', ...NO_STOP })),
          await backend.onServerState((state) => {
            if (state.bridgeConnected && !bridgeUp) {
              bridgeUp = true
              void get().attach()
            } else if (!state.bridgeConnected && bridgeUp) bridgeDown()
          }),
        ]
        const state = await backend.serverState()
        if (state.bridgeConnected && !bridgeUp) {
          bridgeUp = true
          void get().attach()
        }
      },

      disconnect() {
        for (const unlisten of unlisteners) unlisten()
        unlisteners = []
      },

      async attach() {
        if (!bridgeUp || !get().active || get().session !== 'detached') return
        generation += 1
        const mine = generation
        try {
          await client.request('initialize', {
            clientID: 'netherforge',
            clientName: 'NetherForge',
            adapterID: 'netherforge',
            linesStartAt1: true,
            columnsStartAt1: true,
            pathFormat: 'path',
          })
          await client.request('attach', {})
          if (mine !== generation) return
          set({ session: 'running' })
          sent = new Set()
          await Promise.all(Object.keys(get().breakpoints).map(send))
          await sendExceptions()
          await client.request('configurationDone', {})
        } catch {
          // A plugin without the debugger never answers; a bridge that went down is bridgeDown's.
          if (mine === generation && get().session !== 'paused') set({ session: 'detached' })
        }
      },

      toggleBreakpoint(path, line) {
        const lines = get().breakpoints[path] ?? []
        const next = lines.includes(line)
          ? lines.filter((it) => it !== line)
          : normalLines([...lines, line])
        const breakpoints = { ...get().breakpoints }
        if (next.length > 0) breakpoints[path] = next
        else delete breakpoints[path]
        changeBreakpoints(breakpoints, [path])
      },

      setBreakpointLines(path, lines) {
        const next = normalLines(lines)
        if (sameLines(get().breakpoints[path], next)) return
        const breakpoints = { ...get().breakpoints }
        if (next.length > 0) breakpoints[path] = next
        else delete breakpoints[path]
        changeBreakpoints(breakpoints, [path])
      },

      removeBreakpoint(path, line) {
        const lines = get().breakpoints[path]
        if (lines?.includes(line))
          get().setBreakpointLines(
            path,
            lines.filter((it) => it !== line),
          )
      },

      clearBreakpoints() {
        const paths = Object.keys(get().breakpoints)
        changeBreakpoints({}, paths)
      },

      setActive(active) {
        if (get().active === active) return
        set({ active })
        save()
        if (active && get().session === 'detached') void get().attach()
        else for (const path of Object.keys(get().breakpoints)) void send(path)
      },

      setBreakOnErrors(on) {
        set({ breakOnErrors: on })
        save()
        void sendExceptions()
      },

      continue: () => resume('continue'),
      stepOver: () => resume('next'),
      stepInto: () => resume('stepIn'),
      stepOut: () => resume('stepOut'),

      async pause() {
        if (get().session !== 'running') return
        try {
          await client.request('pause', { threadId: SERVER_THREAD })
        } catch {
          // Gone meanwhile.
        }
      },

      async stopDebugging() {
        if (get().session === 'detached') return
        set({ active: false })
        save()
        try {
          await client.request('disconnect', {})
        } catch {
          // Gone anyway.
        }
        sent = new Set()
        set({ session: 'detached' })
        resumed()
      },

      async selectFrame(id) {
        set({ frameId: id, scopes: [] })
        try {
          const { scopes } = await client.request('scopes', { frameId: id })
          if (get().frameId !== id) return
          set({ scopes })
          // The first scope (the locals) shows at once.
          const first = scopes[0]
          if (first && first.variablesReference > 0) await get().expand(first.variablesReference)
        } catch {
          // The server ran on.
        }
      },

      async expand(reference) {
        if (reference <= 0 || get().variables[reference] || get().session !== 'paused') return
        try {
          const { variables } = await client.request('variables', {
            variablesReference: reference,
          })
          if (get().session !== 'paused') return
          set({ variables: { ...get().variables, [reference]: variables } })
        } catch {
          // The server ran on.
        }
      },

      holdReload(paths) {
        if (get().session !== 'paused') return false
        held = [...new Set([...held, ...paths])]
        return true
      },

      async release() {
        if (get().session !== 'paused') return
        // The server is going: what was held has nothing to reload into.
        held = []
        try {
          await client.request('disconnect', {})
        } catch {
          // Gone anyway.
        }
        sent = new Set()
        set({ session: 'detached', ...NO_STOP })
      },
    }
  })
}
