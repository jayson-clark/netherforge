/**
 * The run store against the memory backend's fake dev server (its phases
 * step on timers: fake ones here). How a reload asking for a restart reaches
 * it is restart.test.ts's; the MCP tools on top of it are tools.test.ts's.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend, MemoryBackendOptions } from '@/testing/backend/memory'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleBackend, settle } from '@/testing/workspace'
import { createRun, describeReload, type RunStore } from './run'

let backend: MemoryBackend
let run: RunStore
const onBridgeUp = vi.fn()
const beforeStop = vi.fn(async () => {})

async function connected(options: Omit<MemoryBackendOptions, 'projects'> = {}) {
  backend = exampleBackend({ backend: { serverDelayMs: 0, eulaAccepted: true, ...options } })
  await backend.openProject(EXAMPLE_ROOT)
  run = createRun(backend, { onBridgeUp, beforeStop })
  await run.getState().connect()
}

const texts = () =>
  run
    .getState()
    .console.lines()
    .map((it) => it.text)

beforeEach(async () => {
  vi.useFakeTimers()
  onBridgeUp.mockClear()
  beforeStop.mockClear()
  await connected()
})
afterEach(() => {
  run.getState().disconnect()
  vi.useRealTimers()
})

describe('the dev server', () => {
  it('starts through its phases to running, and stops after letting the debugger go', async () => {
    expect(run.getState().server.phase).toBe('stopped')
    const phases: string[] = []
    run.subscribe((state, previous) => {
      if (state.server.phase !== previous.server.phase) phases.push(state.server.phase)
    })
    await run.getState().start()
    await settle()
    expect(phases).toEqual(['preparing', 'starting', 'running'])
    expect(run.getState().server.bridgeConnected).toBe(true)
    // What preparing reported is gone once it's past.
    expect(run.getState().progress).toBeNull()

    const stopping = run.getState().stop()
    await settle()
    await stopping
    expect(beforeStop).toHaveBeenCalledOnce()
    expect(run.getState().server.phase).toBe('stopped')
    expect(texts()).toContain('[Server] Stopping server')
  })

  it('says why it could not start, in the console', async () => {
    await connected({ eulaAccepted: false })
    await run.getState().start()
    expect(run.getState().server.phase).toBe('stopped')
    expect(texts().at(-1)).toMatch(/^Couldn't start the server: .*EULA/)
  })

  it('asks the backend about the EULA, and accepting it is remembered', async () => {
    await connected({ eulaAccepted: false })
    expect(await run.getState().refreshEula()).toMatchObject({ accepted: false })
    await run.getState().acceptEula()
    expect(run.getState().eula?.accepted).toBe(true)
  })

  it('restarts: says so, stops and starts again', async () => {
    await run.getState().start()
    await settle()
    const restarting = run.getState().restart()
    await settle()
    await restarting
    await settle()
    expect(texts()).toContain('Restarting the dev server')
    expect(run.getState().server.phase).toBe('running')
  })
})

describe('the bridge', () => {
  it('calls onBridgeUp once each time it comes up, and fetches the settings', async () => {
    backend.testConnect()
    await settle()
    expect(onBridgeUp).toHaveBeenCalledOnce()
    expect(backend.bridgeLog).toContainEqual({ method: 'settings' })
    expect(run.getState().serverSettings).not.toBeNull()
    // Another state change while it's up isn't another coming up.
    backend.testServerOutput('[12:00:00 INFO]: still here')
    await settle()
    expect(onBridgeUp).toHaveBeenCalledOnce()
  })

  it('forgets the instances and settings when it goes down', async () => {
    await run.getState().start()
    await settle()
    await run.getState().spawn('tower')
    expect(run.getState().instances).toHaveLength(1)
    const stopping = run.getState().stop()
    await settle()
    await stopping
    expect(run.getState().instances).toEqual([])
    expect(run.getState().serverSettings).toBeNull()
  })

  it("keeps the plugin's problems and status as it reports them", () => {
    backend.testBridgeEvent('status', { players: ['Steve'], instances: 3 })
    backend.testBridgeEvent('problems', {
      problems: [{ severity: 'error', code: 'x', message: 'bad', file: 'a.json' }],
    })
    expect(run.getState()).toMatchObject({ players: ['Steve'], liveInstances: 3 })
    expect(run.getState().serverProblems.map((it) => it.message)).toEqual(['bad'])
  })
})

describe('the console', () => {
  it("reads a server line's level from its prefix, and stderr as a warning", () => {
    backend.testServerOutput('[12:00:00 INFO]: fine')
    backend.testServerOutput('[12:00:00 WARN]: careful')
    backend.testServerOutput('[12:00:00 ERROR]: broken')
    backend.testServerOutput('plain', 'stderr')
    expect(
      run
        .getState()
        .console.lines()
        .map((it) => [it.kind, it.level]),
    ).toEqual([
      ['stdout', 'info'],
      ['stdout', 'warn'],
      ['stdout', 'error'],
      ['stderr', 'warn'],
    ])
  })

  it("shows the plugin's log and script errors with where they came from", () => {
    const source = { file: 'centities/tower/script.lua', line: 3 }
    backend.testBridgeEvent('console', {
      items: [
        { type: 'log', level: 'debug', message: 'hello', source },
        { type: 'script_error', message: 'boom', source, traceback: 'stack traceback:' },
      ],
    })
    expect(run.getState().console.lines()).toMatchObject([
      { kind: 'log', level: 'debug', text: 'hello', source },
      { kind: 'script_error', level: 'error', text: 'boom', source, detail: 'stack traceback:' },
    ])
  })

  it('keeps the last 2000 lines, and is cleared on demand', () => {
    const lines = Array.from({ length: 2005 }, (_, i) => ({
      kind: 'editor' as const,
      level: 'info' as const,
      text: `line ${i}`,
    }))
    const version = run.getState().consoleVersion
    run.getState().append(...lines)
    // One change for the batch, so the console draws once.
    expect(run.getState().consoleVersion).toBe(version + 1)
    expect(texts()).toHaveLength(2000)
    expect(texts()[0]).toBe('line 5')
    run.getState().clearConsole()
    expect(texts()).toEqual([])
  })
})

describe('acting on the dev server', () => {
  it('ignores a blank command, and says when one could not run', async () => {
    await run.getState().command('   ')
    expect(backend.commandLog).toEqual([])
    await run.getState().command('time set day')
    expect(texts().at(-1)).toBe("The dev server isn't running")
  })

  it('spawns a centity, saying where, and lists it', async () => {
    await run.getState().start()
    await settle()
    await run.getState().spawn('tower')
    expect(texts().at(-1)).toBe('Spawned tower at 0.0, 64.0, 0.0')
    expect(run.getState().instances.map((it) => it.centity)).toEqual(['tower'])
  })

  it('says why a spawn failed', async () => {
    await run.getState().spawn('tower')
    expect(texts().at(-1)).toMatch(/^Couldn't spawn tower: /)
  })

  it('lists no instances while the bridge is down, without asking', async () => {
    await run.getState().refreshInstances()
    expect(run.getState().instances).toEqual([])
    expect(backend.bridgeLog).toEqual([])
  })
})

describe('describeReload', () => {
  const tower = { package: 'basic', kind: 'centity', id: 'tower', ok: true, reattached: 0 }

  it('names what reloaded, and how many live centities came along', () => {
    expect(describeReload(['a.lua'], null)).toEqual({ level: 'info', text: 'Reloaded a.lua' })
    expect(describeReload(['x'], { resources: [{ ...tower, reattached: 2 }] }).text).toBe(
      'Reloaded centity:tower (2 live)',
    )
  })

  it('warns about what kept its old version, and a restart it needs', () => {
    expect(describeReload(['x'], { resources: [{ ...tower, ok: false }], restart: true })).toEqual({
      level: 'warn',
      text: 'Reload kept the old centity:tower (see Problems); the dev server must restart to take it in',
    })
  })

  it('reports a failed reload as an error', () => {
    expect(describeReload(['a.lua', 'b.lua'], null, 'timeout')).toEqual({
      level: 'error',
      text: 'Reload of a.lua, b.lua failed: timeout',
    })
  })
})
