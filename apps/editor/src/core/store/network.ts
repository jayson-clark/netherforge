/**
 * The open project's dev network as the editor shows it: its layout (the
 * editor's, kept per project by the backend), each other server's project as
 * a start would find it, the members running (the proxy and those servers),
 * and each member's console. Starting and stopping are the run store's: the
 * backend runs the whole network when the layout is enabled.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type {
  Backend,
  NetworkLayout,
  NetworkMember,
  NetworkServerCheck,
  Unlisten,
} from '@/core/backend/types'
import { ConsoleBuffer } from './consoleBuffer'
import { levelOf, type ConsoleLine } from './run'
import type { WorkspaceStore } from './workspace'

export interface NetworkState {
  /** The open project's layout; null while no project is open. */
  layout: NetworkLayout | null
  /** Each of the layout's other servers, as a start would find it. */
  checks: NetworkServerCheck[]
  /** The proxy and other servers of the network last started. */
  members: NetworkMember[]
  /** Each member's console, by name: read `consoles.get(name)?.lines()`, and select `consoleVersion` to hear of changes. */
  consoles: Map<string, ConsoleBuffer<ConsoleLine>>
  consoleVersion: number
  /** Why the last change to the layout, or trusting a folder, was refused. */
  error: string | null
  /** Whose console the Console panel shows: a member's name, or null for the project's own server. */
  viewing: string | null
}

export interface NetworkActions {
  connect(): Promise<void>
  disconnect(): void
  /** Reads the open project's layout, its checks and the members again. */
  load(): Promise<void>
  /** Saves [layout] for the next start; a refusal goes in `error` and leaves `layout` as it was. */
  setLayout(layout: NetworkLayout): Promise<void>
  /** Trusts the folder the layout's server [name] runs. */
  trust(name: string): Promise<void>
  /** Runs a console command on the member [name]. */
  command(name: string, line: string): Promise<void>
  clearConsole(name: string): void
  /** Shows the console of the member [name] in the Console panel (null: the project's own server's). */
  view(name: string | null): void
}

export type Network = NetworkState & NetworkActions
export type NetworkStore = StoreApi<Network>

/** Lines each member's console keeps. */
const CONSOLE_LIMIT = 2000

const NO_CHECKS: NetworkServerCheck[] = []
const NO_MEMBERS: NetworkMember[] = []

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function createNetwork(backend: Backend, workspace: WorkspaceStore): NetworkStore {
  let unlisteners: Unlisten[] = []
  let lineId = 0

  const store = createStore<Network>()((set, get) => {
    const append = (name: string, line: Omit<ConsoleLine, 'id'>) => {
      const consoles = get().consoles
      let buffer = consoles.get(name)
      if (!buffer) {
        buffer = new ConsoleBuffer<ConsoleLine>(CONSOLE_LIMIT)
        consoles.set(name, buffer)
      }
      lineId += 1
      buffer.push({ ...line, id: lineId })
      set({ consoleVersion: get().consoleVersion + 1 })
    }
    const checks = async () => {
      try {
        set({ checks: await backend.networkCheck() })
      } catch {
        set({ checks: NO_CHECKS })
      }
    }

    return {
      layout: null,
      checks: NO_CHECKS,
      members: NO_MEMBERS,
      consoles: new Map(),
      consoleVersion: 0,
      error: null,
      viewing: null,

      async connect() {
        get().disconnect()
        unlisteners = await Promise.all([
          backend.onNetworkMembers(({ members }) => {
            // A member that's gone has no console to show.
            const viewing = get().viewing
            set({
              members,
              viewing: members.some((it) => it.name === viewing) ? viewing : null,
            })
          }),
          backend.onNetworkMember((member) => {
            const members = get().members
            const at = members.findIndex((it) => it.name === member.name)
            set({
              members:
                at < 0 ? [...members, member] : members.map((it, i) => (i === at ? member : it)),
            })
          }),
          backend.onNetworkOutput(({ name, stream, line }) =>
            append(name, { kind: stream, level: levelOf(line, stream), text: line }),
          ),
        ])
      },

      disconnect() {
        for (const unlisten of unlisteners) unlisten()
        unlisteners = []
      },

      async load() {
        if (!workspace.getState().project) {
          set({ layout: null, checks: NO_CHECKS, members: NO_MEMBERS, error: null })
          return
        }
        try {
          const [layout, members] = await Promise.all([
            backend.networkLayout(),
            backend.networkMembers(),
          ])
          set({ layout, members, error: null })
          await checks()
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async setLayout(layout) {
        try {
          await backend.networkSetLayout(layout)
          set({ layout, error: null })
          await checks()
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async trust(name) {
        try {
          await backend.networkTrust(name)
          set({ error: null })
          await checks()
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async command(name, line) {
        if (!line.trim()) return
        try {
          await backend.networkCommand(name, line)
        } catch (error) {
          append(name, { kind: 'editor', level: 'error', text: errorText(error) })
        }
      },

      view(name) {
        set({ viewing: name })
      },

      clearConsole(name) {
        get().consoles.get(name)?.clear()
        set({ consoleVersion: get().consoleVersion + 1 })
      },
    }
  })

  // Each project has its own layout.
  let root: string | null = null
  workspace.subscribe((state) => {
    const next = state.project?.root ?? null
    if (next === root) return
    root = next
    store.getState().consoles.clear()
    store.setState({ viewing: null })
    void store.getState().load()
  })
  return store
}
