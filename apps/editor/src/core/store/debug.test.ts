import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { createApp, type AppStores } from '@/state/providers'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleBackend, openExampleApp, settle } from '@/testing/workspace'
import { createDebug } from './debug'
import type { LayoutStorage } from './layout'

const TOWER = 'centities/tower/script.lua'
const TURNS = 'centities/tower/turns.lua'

let backend: MemoryBackend
let app: AppStores

const commands = () => backend.dapLog.map((it) => it.command)

async function connected() {
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
  await app.debug.getState().connect()
}

describe('the debug store', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    window.localStorage.clear()
  })
  afterEach(() => vi.useRealTimers())

  it('attaches when the bridge comes up, and sends the breakpoints', async () => {
    await connected()
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    // Nothing reaches the server before there's a session.
    expect(backend.dapLog).toEqual([])

    backend.testConnect()
    await settle()
    expect(commands()).toEqual([
      'initialize',
      'attach',
      'setBreakpoints',
      'setExceptionBreakpoints',
      'configurationDone',
    ])
    expect(backend.dapLog[0]?.arguments).toMatchObject({
      adapterID: 'netherforge',
      linesStartAt1: true,
      pathFormat: 'path',
    })
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [9] })
    expect(app.debug.getState().session).toBe('running')

    // A toggle while attached is sent at once; the last one off clears the file.
    app.debug.getState().toggleBreakpoint(TOWER, 12)
    await settle()
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [9, 12] })
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    app.debug.getState().toggleBreakpoint(TOWER, 12)
    await settle()
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [] })
    expect(app.debug.getState().breakpoints).toEqual({})
  })

  it('shows a stop: the stack, the top frame’s scopes, variables on demand; continue runs on', async () => {
    await connected()
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    backend.testConnect()
    await settle()

    backend.testDebugStop()
    await settle()
    let state = app.debug.getState()
    expect(state.session).toBe('paused')
    expect(state.stop).toMatchObject({ reason: 'breakpoint' })
    expect(state.frames.map((it) => [it.name, it.source?.path, it.line])).toEqual([
      ['on_click', TOWER, 9],
      ['main chunk', TOWER, 1],
    ])
    expect(state.frameId).toBe(1)
    expect(state.scopes.map((it) => it.name)).toEqual(['Locals', 'Upvalues', 'Globals'])
    // The locals come at once; a table only when it's expanded.
    const locals = state.variables[state.scopes[0]!.variablesReference]!
    expect(locals.map((it) => it.name)).toEqual(['player', 'count', 'label', 'settings'])
    const settings = locals.find((it) => it.name === 'settings')!
    expect(state.variables[settings.variablesReference]).toBeUndefined()
    void app.debug.getState().expand(settings.variablesReference)
    await settle()
    state = app.debug.getState()
    expect(state.variables[settings.variablesReference]?.map((it) => it.name)).toEqual([
      'speed',
      'colors',
    ])

    // Another frame: its own scopes.
    void app.debug.getState().selectFrame(2)
    await settle()
    expect(app.debug.getState().scopes[0]?.variablesReference).toBe(201)

    void app.debug.getState().continue()
    await settle()
    state = app.debug.getState()
    expect(commands()).toContain('continue')
    expect(state.session).toBe('running')
    expect(state.frames).toEqual([])
    expect(state.variables).toEqual({})
  })

  it('steps with next, stepIn and stepOut', async () => {
    await connected()
    backend.testConnect()
    await settle()
    for (const step of ['stepOver', 'stepInto', 'stepOut'] as const) {
      backend.testDebugStop({ reason: 'step' })
      await settle()
      void app.debug.getState()[step]()
      await settle()
      expect(app.debug.getState().session).toBe('running')
    }
    expect(commands().filter((it) => ['next', 'stepIn', 'stepOut'].includes(it))).toEqual([
      'next',
      'stepIn',
      'stepOut',
    ])
  })

  it('holds saves while paused and reloads them once the server runs', async () => {
    await connected()
    backend.testConnect()
    await settle()
    backend.testDebugStop()
    await settle()

    app.workspace.getState().hotReload([TOWER])
    await settle()
    expect(backend.bridgeLog.filter((it) => it.method === 'reload')).toEqual([])

    void app.debug.getState().continue()
    await settle()
    expect(backend.bridgeLog.filter((it) => it.method === 'reload')).toEqual([
      { method: 'reload', params: { paths: [TOWER] } },
    ])
  })

  it('lets a paused server go before stopping it, keeping the breakpoints for the next start', async () => {
    await connected()
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    backend.testConnect()
    await settle()
    backend.testDebugStop()
    await settle()

    void app.run.getState().stop()
    await settle()
    expect(commands().at(-1)).toBe('disconnect')
    const state = app.debug.getState()
    expect(state.session).toBe('detached')
    expect(state.active).toBe(true)
    expect(state.breakpoints).toEqual({ [TOWER]: [9] })
  })

  it('moves breakpoints with a rename, and drops them with a delete', async () => {
    await connected()
    backend.testConnect()
    await settle()
    app.debug.getState().toggleBreakpoint(TURNS, 3)
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    await settle()

    await app.workspace.getState().renamePath(TURNS, 'centities/tower/spin.lua')
    await settle()
    expect(app.debug.getState().breakpoints).toEqual({
      [TOWER]: [9],
      'centities/tower/spin.lua': [3],
    })
    expect(backend.testBreakpoints()).toMatchObject({
      [TURNS]: [],
      'centities/tower/spin.lua': [3],
    })

    // Undo moves them back.
    await app.workspace.getState().undoRefactor()
    await settle()
    expect(app.debug.getState().breakpoints).toEqual({ [TOWER]: [9], [TURNS]: [3] })

    // A folder's rename moves what's in it.
    await app.workspace.getState().renamePath('centities/tower', 'centities/spire')
    await settle()
    expect(app.debug.getState().breakpoints).toEqual({
      'centities/spire/script.lua': [9],
      'centities/spire/turns.lua': [3],
    })

    await app.workspace.getState().deletePath('centities/spire')
    await settle()
    expect(app.debug.getState().breakpoints).toEqual({})
    expect(backend.testBreakpoints()).toMatchObject({
      'centities/spire/script.lua': [],
      'centities/spire/turns.lua': [],
    })
  })

  it('follows lines the editor moved, deactivates, and stops debugging', async () => {
    await connected()
    backend.testConnect()
    await settle()
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    app.debug.getState().setBreakpointLines(TOWER, [11])
    await settle()
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [11] })

    app.debug.getState().setActive(false)
    await settle()
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [] })
    expect(app.debug.getState().breakpoints).toEqual({ [TOWER]: [11] })
    app.debug.getState().setActive(true)
    await settle()
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [11] })

    app.debug.getState().setBreakOnErrors(true)
    await settle()
    expect(backend.dapLog.at(-1)).toMatchObject({
      command: 'setExceptionBreakpoints',
      arguments: { filters: ['errors'] },
    })

    // Stop: the server runs on without breakpoints until they're activated again, which attaches.
    backend.testDebugStop()
    await settle()
    void app.debug.getState().stopDebugging()
    await settle()
    expect(commands()).toContain('disconnect')
    expect(app.debug.getState()).toMatchObject({ session: 'detached', active: false })
    app.debug.getState().setActive(true)
    await settle()
    expect(app.debug.getState().session).toBe('running')
    expect(backend.testBreakpoints()).toEqual({ [TOWER]: [11] })
  })

  it('forgets the session with the bridge, and attaches again when it comes back', async () => {
    await connected()
    backend.testConnect()
    await settle()
    backend.testDebugStop()
    await settle()
    const stopping = app.run.getState().stop()
    await settle()
    await stopping
    expect(app.debug.getState()).toMatchObject({ session: 'detached', frames: [] })
    backend.testConnect()
    await settle()
    expect(app.debug.getState().session).toBe('running')
    expect(commands().filter((it) => it === 'initialize')).toHaveLength(2)
  })

  it('gives up quietly on a plugin without the debugger', async () => {
    await connected()
    vi.spyOn(backend, 'dapSend').mockResolvedValue()
    backend.testConnect()
    await vi.advanceTimersByTimeAsync(11_000)
    expect(app.debug.getState().session).toBe('detached')
  })

  it('remembers breakpoints per project', async () => {
    const kept = new Map<string, string>()
    const storage: LayoutStorage = {
      get: (key) => kept.get(key) ?? null,
      set: (key, value) => void kept.set(key, value),
    }
    backend = exampleBackend()
    app = createApp(backend)
    const debug = createDebug(backend, app.workspace, storage)
    await debug.getState().connect()
    await app.workspace.getState().openProject(EXAMPLE_ROOT)
    debug.getState().toggleBreakpoint(TOWER, 9)
    debug.getState().setBreakOnErrors(true)

    const again = createDebug(backend, app.workspace, storage)
    await again.getState().connect()
    expect(again.getState()).toMatchObject({
      breakpoints: { [TOWER]: [9] },
      breakOnErrors: true,
      active: true,
    })
  })
})
