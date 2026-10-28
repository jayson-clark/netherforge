/**
 * The run loop's state: the dev server's phase, its console, what the
 * plugin tells the editor over the bridge (its console stream, status,
 * problems and server-owner settings, each through `onBridgeEvent`), live
 * instances and the plugin's own problem list.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type {
  ConsoleEntry,
  InstanceInfo,
  Problem,
  ReloadedResource,
  ReloadResult,
  ServerSettings,
  SourceRef,
} from '@netherforge/format/types'
import type {
  Backend,
  EulaStatus,
  PrepareProgress,
  ServerState,
  Unlisten,
} from '@/core/backend/types'
import { ConsoleBuffer } from './consoleBuffer'

export type ConsoleKind = 'stdout' | 'stderr' | 'log' | 'script_error' | 'editor'

export interface ConsoleLine {
  id: number
  kind: ConsoleKind
  level: 'debug' | 'info' | 'warn' | 'error'
  text: string
  /** Where in the project it came from, when the plugin said. */
  source?: SourceRef
  /** A Lua traceback, shown under a script error. */
  detail?: string
}

export interface RunState {
  server: ServerState
  progress: PrepareProgress | null
  /** The console's lines, in a ring the store mutates: read `console.lines()`, and select `consoleVersion` to hear of changes. */
  console: ConsoleBuffer<ConsoleLine>
  /** Bumped on every change to `console`. */
  consoleVersion: number
  instances: InstanceInfo[]
  players: string[]
  liveInstances: number
  /** The plugin's own problems from loading the project; replaced on each report. */
  serverProblems: Problem[]
  /** Every package's server-owner settings with the values the dev server runs with; null while the bridge is down. */
  serverSettings: ServerSettings | null
  eula: EulaStatus | null
}

export interface RunActions {
  connect(): Promise<void>
  disconnect(): void
  start(): Promise<void>
  stop(): Promise<void>
  /** Stops the dev server and starts it again (a reload said the server must restart to take a change in). */
  restart(): Promise<void>
  acceptEula(): Promise<void>
  refreshEula(): Promise<EulaStatus>
  command(line: string): Promise<void>
  spawn(centity: string): Promise<void>
  /** Plays a particle effect in front of the first player online (the plugin's choice), looping or not. */
  playParticleEffect(effect: string, loop: boolean): Promise<void>
  /** Stops every particle effect `playParticleEffect` started. */
  stopParticleEffects(): Promise<void>
  refreshInstances(): Promise<void>
  /** Asks the dev server for its settings again. */
  refreshSettings(): Promise<void>
  /**
   * Sets a server-owner setting on the dev server ([value] as JSON; null puts
   * it back to its default). Rejects with the plugin's reason when the setting
   * can't have that value.
   */
  setSetting(namespace: string, setting: string, value: unknown): Promise<void>
  clearConsole(): void
  append(...lines: Omit<ConsoleLine, 'id'>[]): void
}

export type Run = RunState & RunActions
export type RunStore = StoreApi<Run>

/** The console keeps this many lines; a chatty server would otherwise grow without bound. */
const CONSOLE_LIMIT = 2000

const STOPPED: ServerState = {
  phase: 'stopped',
  minecraft: null,
  port: null,
  bridgeConnected: false,
  message: null,
}

/** Server log lines carry their level in a `[12:00:00 WARN]:`-style prefix. */
export function levelOf(line: string, stream: 'stdout' | 'stderr'): ConsoleLine['level'] {
  if (/\b(ERROR|SEVERE|FATAL)\b/.test(line)) return 'error'
  if (/\bWARN(ING)?\b/.test(line)) return 'warn'
  return stream === 'stderr' ? 'warn' : 'info'
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function createRun(
  backend: Backend,
  options: {
    onBridgeUp?: () => void
    /** Before the dev server stops: the debugger lets a paused one go (held, it would never read `stop`). */
    beforeStop?: () => Promise<void>
  } = {},
): RunStore {
  let unlisteners: Unlisten[] = []
  let lineId = 0

  return createStore<Run>()((set, get) => ({
    server: STOPPED,
    progress: null,
    console: new ConsoleBuffer<ConsoleLine>(CONSOLE_LIMIT),
    consoleVersion: 0,
    instances: [],
    players: [],
    liveInstances: 0,
    serverProblems: [],
    serverSettings: null,
    eula: null,

    append(...lines) {
      if (lines.length === 0) return
      const buffer = get().console
      for (const line of lines) {
        lineId += 1
        buffer.push({ ...line, id: lineId })
      }
      set({ consoleVersion: get().consoleVersion + 1 })
    },

    async connect() {
      get().disconnect()
      unlisteners = await Promise.all([
        backend.onServerState((server) => {
          const wasUp = get().server.bridgeConnected
          set({ server, progress: server.phase === 'preparing' ? get().progress : null })
          if (!wasUp && server.bridgeConnected) {
            options.onBridgeUp?.()
            void get().refreshSettings()
          }
          if (!server.bridgeConnected) set({ instances: [], serverSettings: null })
        }),
        backend.onServerOutput((event) =>
          get().append({
            kind: event.stream,
            level: levelOf(event.line, event.stream),
            text: event.line,
          }),
        ),
        backend.onPrepareProgress((progress) => set({ progress })),
        backend.onBridgeEvent('console', ({ items }) => get().append(...items.map(consoleLine))),
        backend.onBridgeEvent('problems', ({ problems }) => set({ serverProblems: problems })),
        backend.onBridgeEvent('status', ({ players, instances }) =>
          set({ players, liveInstances: instances }),
        ),
        backend.onBridgeEvent('settings_changed', (settings) => set({ serverSettings: settings })),
      ])
      set({ server: await backend.serverState() })
    },

    disconnect() {
      for (const unlisten of unlisteners) unlisten()
      unlisteners = []
    },

    async refreshEula() {
      const eula = await backend.eulaStatus()
      set({ eula })
      return eula
    },

    async acceptEula() {
      await backend.acceptEula()
      await get().refreshEula()
    },

    async start() {
      try {
        await backend.startServer()
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't start the server: ${errorText(error)}`,
        })
      }
    },

    async stop() {
      try {
        await options.beforeStop?.()
        await backend.stopServer()
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't stop the server: ${errorText(error)}`,
        })
      }
    },

    async restart() {
      get().append({ kind: 'editor', level: 'info', text: 'Restarting the dev server' })
      await get().stop()
      await get().start()
    },

    async command(line) {
      if (!line.trim()) return
      try {
        await backend.serverCommand(line)
      } catch (error) {
        get().append({ kind: 'editor', level: 'error', text: errorText(error) })
      }
    },

    async spawn(centity) {
      try {
        const instance = await backend.bridgeRequest('spawn', { centity })
        get().append({
          kind: 'editor',
          level: 'info',
          text: instance
            ? `Spawned ${centity} at ${instance.x.toFixed(1)}, ${instance.y.toFixed(1)}, ${instance.z.toFixed(1)}`
            : `Spawned ${centity}`,
        })
        await get().refreshInstances()
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't spawn ${centity}: ${errorText(error)}`,
        })
      }
    },

    async playParticleEffect(effect, loop) {
      try {
        await backend.bridgeRequest('play_particle_effect', { effect, loop })
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't play ${effect}: ${errorText(error)}`,
        })
      }
    },

    async stopParticleEffects() {
      try {
        await backend.bridgeRequest('stop_particle_effects')
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't stop the particle effects: ${errorText(error)}`,
        })
      }
    },

    async refreshInstances() {
      if (!get().server.bridgeConnected) {
        set({ instances: [] })
        return
      }
      try {
        const instances = await backend.bridgeRequest('instances')
        set({ instances })
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't list instances: ${errorText(error)}`,
        })
      }
    },

    async refreshSettings() {
      if (!get().server.bridgeConnected) return
      try {
        set({ serverSettings: await backend.bridgeRequest('settings') })
      } catch (error) {
        get().append({
          kind: 'editor',
          level: 'error',
          text: `Couldn't read the dev server's settings: ${errorText(error)}`,
        })
      }
    },

    async setSetting(namespace, setting, value) {
      const settings = await backend.bridgeRequest('set_setting', {
        namespace,
        setting,
        ...(value === null ? {} : { value }),
      })
      set({ serverSettings: settings })
    },

    clearConsole() {
      get().console.clear()
      set({ consoleVersion: get().consoleVersion + 1 })
    },
  }))
}

/** A console stream entry as a console line. */
function consoleLine(entry: ConsoleEntry): Omit<ConsoleLine, 'id'> {
  return entry.type === 'log'
    ? { kind: 'log', level: entry.level, text: entry.message, source: entry.source }
    : {
        kind: 'script_error',
        level: 'error',
        text: entry.message,
        source: entry.source,
        detail: entry.traceback,
      }
}

/** How the console names a reloaded resource: `centity:tower`, or the package for the whole of it. */
export function resourceLabel(resource: ReloadedResource): string {
  return resource.kind && resource.id ? `${resource.kind}:${resource.id}` : resource.package
}

/** Describes a reload result for the console. */
export function describeReload(
  paths: string[],
  result: ReloadResult | null,
  error?: string,
): { level: ConsoleLine['level']; text: string } {
  if (error) return { level: 'error', text: `Reload of ${paths.join(', ')} failed: ${error}` }
  const resources = result?.resources
  if (!resources || resources.length === 0)
    return { level: 'info', text: `Reloaded ${paths.join(', ')}` }
  const failed = resources.filter((it) => !it.ok)
  const restart = result?.restart ? '; the dev server must restart to take it in' : ''
  if (failed.length > 0) {
    return {
      level: 'warn',
      text: `Reload kept the old ${failed.map(resourceLabel).join(', ')} (see Problems)${restart}`,
    }
  }
  return {
    level: 'info',
    text: `Reloaded ${resources
      .map((it) =>
        it.reattached ? `${resourceLabel(it)} (${it.reattached} live)` : resourceLabel(it),
      )
      .join(', ')}${restart}`,
  }
}
