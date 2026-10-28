/**
 * The NetherForge plugin on the memory backend's fake dev server: what it
 * answers on the bridge (reload, spawn, bots, players and worlds, structures,
 * owner settings), its profiler's stream, and the state those keep. The
 * backend that runs it logs each request (`bridgeLog`) and owns the
 * connection; this reads what it needs of the backend through `PluginHost`.
 */
import type {
  BridgeRequests,
  BotEvent,
  BotInfo,
  GameDataBundle,
  HandlerTime,
  LoadedWorld,
  PlayerPosition,
  ProfileSample,
  ProfileTick,
} from '@netherforge/format/types'
import { writeNbt } from '@netherforge/structures/nbt'
import { bytesToBase64 } from '@/core/backend/base64'
import {
  BackendError,
  type BridgeEventName,
  type BridgeEvents,
  type BridgeMethod,
  type ErrorCode,
} from '@/core/backend/types'
import { classify, readSetting, type ServerSettings, type SettingDef } from '@/core/format'
import { structureRoot } from '@/testing/nbtFixtures'
import type { FileContents } from './memory'

export function fail(code: ErrorCode, message: string): never {
  throw new BackendError(code, message)
}

/** A bridge request as `bridgeLog` records it: its method, and its params when it takes any. */
export type BridgeCall = {
  [M in BridgeMethod]: { method: M; params: BridgeRequests[M]['params'] }
}[BridgeMethod]

/** What the plugin reads of the backend running it. */
export interface PluginHost {
  /** Whether the bridge is up. */
  connected(): boolean
  /** Whether a breakpoint holds the main thread. */
  paused(): boolean
  /** The open project's `netherforge.json`, as text. */
  manifest(): string | null
  /** The game data of the server's Minecraft version (`export_game_data`). */
  gameData(): GameDataBundle | null
  /** The open project's files. */
  files(): Iterable<[string, FileContents]>
  /** Sends a notification to the editor. */
  emit<E extends BridgeEventName>(event: E, params: BridgeEvents[E]): void
}

/** A field of a manifest's text, or null when it has none or doesn't parse. */
export function manifestField(
  manifest: string | null,
  field: 'minecraft' | 'namespace',
): string | null {
  if (manifest === null) return null
  try {
    return (JSON.parse(manifest) as Record<string, string | undefined>)[field] ?? null
  } catch {
    return null
  }
}

export class FakePlugin {
  instances: {
    uuid: string
    centity: string
    world: string
    x: number
    y: number
    z: number
  }[] = []
  /** Players online on the fake server, where they stand. */
  players: PlayerPosition[] = []
  /** Its loaded worlds, the main one first. */
  worlds: LoadedWorld[] = [{ name: 'world', environment: 'normal', main: true }]
  /** Whether a reload says the server needs a restart (what the plugin learns only as it starts changed). */
  reloadRestarts = false
  /** Every console command the plugin was sent. */
  readonly commandLog: string[] = []
  /** The values the server's owner set for the project's settings (`set_setting`), by name. */
  private settingValues = new Map<string, unknown>()
  /** Bots online, with what they were sent. An action records itself; `chat` is heard back as a chat line. */
  private bots = new Map<string, { info: BotInfo; events: BotEvent[] }>()
  private profileTimer: ReturnType<typeof setInterval> | null = null
  private profileTick = 0

  constructor(
    private readonly host: PluginHost,
    private readonly profileEvery: number,
  ) {}

  /** What the plugin answers; a method it doesn't have is `unknownMethod`, as the real one's JSON-RPC error is. */
  async answer(request: BridgeCall): Promise<unknown> {
    if (!this.host.connected())
      fail('notConnected', "The NetherForge plugin isn't connected to the editor yet")
    // As the plugin: a breakpoint holds the main thread, so only the bridge thread's requests answer.
    if (this.host.paused() && request.method !== 'ping' && request.method !== 'profiler_subscribe')
      fail('plugin', 'the dev server is paused at a breakpoint; continue it first')
    switch (request.method) {
      case 'reload': {
        // Each path's resource, named as the plugin names it; a project file is the whole package.
        const pkg = manifestField(this.host.manifest(), 'namespace') ?? 'project'
        return {
          resources: request.params.paths.map((path) => {
            const found = classify(path)
            return found?.kind && found.id
              ? { package: pkg, kind: found.kind, id: found.id, ok: true, reattached: 0 }
              : { package: pkg, ok: true, reattached: 0 }
          }),
          ...(this.reloadRestarts ? { restart: true } : {}),
        }
      }
      case 'spawn': {
        const instance = {
          uuid: `00000000-0000-4000-8000-${String(this.instances.length + 1).padStart(12, '0')}`,
          centity: request.params.centity,
          world: 'world',
          x: 0,
          y: 64,
          z: 0,
        }
        this.instances.push(instance)
        return instance
      }
      case 'instances':
        return [...this.instances]
      case 'export_game_data':
        return this.host.gameData()
      case 'command':
        this.commandLog.push(request.params.line)
        return null
      case 'play_particle_effect':
      case 'stop_particle_effects':
        // Nothing to draw: the request itself is what tests look at (`bridgeLog`).
        return null
      case 'bots/join': {
        if (this.bots.has(request.params.name))
          fail('plugin', `${request.params.name} is already online`)
        const info: BotInfo = {
          name: request.params.name,
          uuid: `00000000-0000-3000-8000-${String(this.bots.size + 1).padStart(12, '0')}`,
          world: request.params.at?.world ?? 'world',
          x: request.params.at?.x ?? 0,
          y: request.params.at?.y ?? 64,
          z: request.params.at?.z ?? 0,
          events: 1,
        }
        this.bots.set(request.params.name, {
          info,
          events: [{ type: 'chat', seq: 0, text: `${request.params.name} joined the game` }],
        })
        return info
      }
      case 'bots/list':
        return [...this.bots.values()].map((it) => ({ ...it.info, events: it.events.length }))
      case 'bots/leave':
        this.bot(request.params.name)
        this.bots.delete(request.params.name)
        return null
      case 'bots/act': {
        const bot = this.bot(request.params.name)
        const since = bot.events.length
        if (request.params.action.type === 'chat') {
          bot.events.push({
            type: 'chat',
            seq: since,
            text: `<${request.params.name}> ${request.params.action.message}`,
          })
        }
        const { world, x, y, z } = bot.info
        return { world, x, y, z, since }
      }
      case 'bots/state': {
        const { info, events } = this.bot(request.params.name)
        return {
          ...info,
          yaw: 0,
          pitch: 0,
          onGround: true,
          health: 20,
          food: 20,
          gameMode: 'survival',
          dead: false,
          sneaking: false,
          sprinting: false,
          selectedSlot: 0,
          inventory: [],
          events: events.length,
        }
      }
      case 'bots/events': {
        const { events } = this.bot(request.params.name)
        return {
          events: events.filter((it) => it.seq >= (request.params.since ?? 0)),
          next: events.length,
        }
      }
      case 'player_position': {
        const found = request.params.player
          ? this.players.find((it) => it.player === request.params.player)
          : this.players[0]
        if (!found) {
          fail(
            'plugin',
            request.params.player
              ? `no player "${request.params.player}" online`
              : 'nobody is online',
          )
        }
        return { ...found }
      }
      case 'worlds':
        return this.worlds.map((it) => ({ ...it }))
      case 'save_structure': {
        this.world(request.params.world)
        const lo = (axis: 'x' | 'y' | 'z') =>
          Math.min(request.params.from[axis], request.params.to[axis])
        const side = (axis: 'x' | 'y' | 'z') =>
          Math.abs(request.params.from[axis] - request.params.to[axis]) + 1
        const size: [number, number, number] = [side('x'), side('y'), side('z')]
        // The fake world: stone bricks at y 64 and below, air above.
        const blocks = []
        for (let x = 0; x < size[0]; x += 1)
          for (let y = 0; y < size[1]; y += 1)
            for (let z = 0; z < size[2]; z += 1)
              blocks.push({
                pos: [x, y, z] as [number, number, number],
                state: lo('y') + y <= 64 ? 0 : 1,
              })
        const nbt = await writeNbt(
          structureRoot({
            size,
            palette: [{ Name: 'minecraft:stone_bricks' }, { Name: 'minecraft:air' }],
            blocks,
            entities: request.params.entities ? 1 : 0,
          }),
        )
        return { nbt: bytesToBase64(nbt), size: { x: size[0], y: size[1], z: size[2] } }
      }
      case 'save_world': {
        const world = this.world(request.params.world)
        const dimension = world.main ? 'overworld' : world.name
        return {
          level: 'world/level.dat',
          dimension: `world/dimensions/minecraft/${dimension}`,
          main: world.main,
          spawn: { x: 0, y: 64, z: 0, yaw: 0, pitch: 0 },
        }
      }
      case 'settings':
        return this.serverSettings()
      case 'set_setting': {
        const { namespace, setting, value } = request.params
        const settings = this.serverSettings()
        const definition = settings.packages.find((it) => it.namespace === namespace)?.settings[
          setting
        ]?.definition
        if (!definition) fail('plugin', `there's no setting "${setting}" in ${namespace}`)
        if (value === undefined || value === null) {
          this.settingValues.delete(setting)
        } else {
          const read = readSetting(definition, JSON.stringify(value), false)
          if (read.error !== undefined) fail('plugin', `${setting}: ${read.error}`)
          this.settingValues.set(setting, read.value)
        }
        const now = this.serverSettings()
        this.host.emit('settings_changed', now)
        return now
      }
      case 'ping':
        return null
      case 'profiler_subscribe':
        this.profiling(request.params.on)
        return null
      default:
        fail('unknownMethod', `Unknown method "${(request as { method: string }).method}"`)
    }
  }

  /**
   * The fake profiler: while subscribed, a batch every `profileEveryMs` of
   * twenty ticks, timing a handler for each `on("<event>", …)` the project's
   * scripts register, at its line, with numbers that follow from where it is
   * (so tests can count on them).
   */
  profiling(on: boolean) {
    if (this.profileTimer !== null) clearInterval(this.profileTimer)
    this.profileTimer = null
    if (!on) return
    this.profileTimer = setInterval(() => {
      if (!this.host.connected()) return this.profiling(false)
      this.host.emit('profiler', { items: [this.profileSample()] })
    }, this.profileEvery)
  }

  private profileSample(): ProfileSample {
    const handlers: HandlerTime[] = []
    for (const [path, contents] of this.host.files()) {
      // The project's own scripts (not .netherforge/'s docs and stubs).
      if (typeof contents !== 'string' || !path.endsWith('.lua') || path.startsWith('.')) continue
      const [folder, id] = path.split('/')
      const script =
        folder === 'modules'
          ? `module ${id}`
          : `${folder?.replace(/ies$/, 'y').replace(/s$/, '')} ${id}`
      contents.split('\n').forEach((text, i) => {
        const event = /\bon\(\s*"([a-z_:]+)"/.exec(text)?.[1]
        if (!event) return
        const line = i + 1
        const each = 20_000 + 7_000 * ((handlers.length * 3 + line) % 11)
        handlers.push({
          script,
          kind: event,
          source: { file: path, line },
          calls: 20,
          nanos: each * 20,
          max: each * 2,
        })
      })
    }
    const first = this.profileTick + 1
    this.profileTick += 20
    const scripts = handlers.reduce((sum, it) => sum + it.nanos, 0) / 20
    const ticks: ProfileTick[] = Array.from({ length: 20 }, (_, i) => {
      // A spike now and then, so the timeline has something to show.
      const world = 150_000 + ((first + i) % 37 === 0 ? 2_000_000 : 0)
      const phases = {
        timers: 20_000,
        async: 5_000,
        events: scripts,
        world,
        effects: 30_000,
        upkeep: 10_000,
        accounts: 15_000,
        save: 5_000,
      }
      const nanos = Object.values(phases).reduce((sum, it) => sum + it, 0)
      return { tick: first + i, nanos, phases, scripts }
    })
    const scopes = [...new Set(handlers.map((it) => it.script))].map((scope) => {
      const own = handlers.filter((it) => it.script === scope)
      const nanos = own.reduce((sum, it) => sum + it.nanos, 0)
      return { scope, nanos, calls: own.reduce((sum, it) => sum + it.calls, 0), max: nanos / 10 }
    })
    return { ticks, scopes, handlers }
  }

  private world(name: string): LoadedWorld {
    const world = this.worlds.find((it) => it.name === name)
    if (!world) fail('plugin', `no world "${name}" is loaded`)
    return world
  }

  private bot(name: string) {
    const bot = this.bots.get(name)
    if (!bot) fail('plugin', `no bot named "${name}"`)
    return bot
  }

  /**
   * The fake server's settings: the project's own (as its netherforge.json
   * declares them now), each with what `set_setting` set, else its default.
   * Its packages' aren't there: a real dev server has those too.
   */
  serverSettings(): ServerSettings {
    const namespace = manifestField(this.host.manifest(), 'namespace') ?? 'project'
    const manifest = this.host.manifest()
    let declared: Record<string, SettingDef> = {}
    let name = namespace
    try {
      const parsed = JSON.parse(manifest ?? '{}') as {
        name?: string
        settings?: Record<string, SettingDef>
      }
      declared = parsed.settings ?? {}
      name = parsed.name ?? namespace
    } catch {
      // A manifest that doesn't parse declares nothing.
    }
    if (Object.keys(declared).length === 0) return { packages: [] }
    const settings = Object.fromEntries(
      Object.entries(declared).map(([key, definition]) => {
        const set = this.settingValues.has(key)
        return [
          key,
          { definition, value: set ? this.settingValues.get(key) : definition.default, set },
        ]
      }),
    )
    return {
      packages: [
        { namespace, name, file: `plugins/NetherForge/settings/${namespace}.json`, settings },
      ],
    }
  }
}
