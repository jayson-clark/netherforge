import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend, MemoryBackendOptions } from '@/testing/backend/memory'
import { BackendError } from '@/core/backend/types'
import type { AppStores } from '@/state/providers'
import { advanceUntil, openExampleApp, settle } from '@/testing/workspace'
import type { Tool } from '@modelcontextprotocol/sdk/types.js'

const TOOL_NAMES = [
  'get_status',
  'get_problems',
  'reload',
  'get_console',
  'get_profile',
  'run_command',
  'spawn_centity',
  'list_instances',
  'lookup_game_data',
  'start_server',
  'stop_server',
  'open_in_editor',
  'bot_join',
  'bot_act',
  'bot_state',
  'bot_events',
  'bot_leave',
]

let backend: MemoryBackend
let app: AppStores

async function open(options: MemoryBackendOptions = {}) {
  ;({ backend, app } = await openExampleApp({ backend: { serverDelayMs: 0, ...options } }))
  await app.run.getState().connect()
}

// Tools wait for the server on timers (a reload's settling, a bot's): the fake clock, moved on
// by `call` until the answer comes.
beforeEach(async () => {
  vi.useFakeTimers()
  await open()
})
afterEach(() => vi.useRealTimers())

/** Calls a tool the way an agent does and returns its parsed text, or the error text. */
async function request(name: string, args: Record<string, unknown> = {}) {
  const response = await backend.testMcpCall('tools/call', { name, arguments: args })
  if (response.error) throw new Error(`protocol error: ${response.error.message}`)
  const result = response.result as { content: { text: string }[]; isError?: boolean }
  const text = result.content[0]!.text
  return result.isError ? { error: text } : JSON.parse(text)
}

/** [request], with the clock moving until it's answered. */
const call = (name: string, args: Record<string, unknown> = {}) => advanceUntil(request(name, args))

describe('MCP tools on an untrusted project', () => {
  it('refuses every call until the project is trusted, and nothing reaches the tools', async () => {
    await open({ trusted: [] })
    const refused = await advanceUntil(
      backend.testMcpCall('tools/call', { name: 'get_status', arguments: {} }),
    )
    expect(refused.error?.code).toBe(-32001)
    expect(refused.error?.message).toMatch(/isn't trusted/)
    await app.workspace.getState().trustProject(true)
    expect(await call('get_status')).toMatchObject({ project: expect.anything() })
  })
})

describe('MCP tools', () => {
  it('lists every tool with the JSON Schema of its zod arguments', async () => {
    const response = await backend.testMcpCall('tools/list')
    const tools = (response.result as { tools: Tool[] }).tools
    expect(tools.map((it) => it.name)).toEqual(TOOL_NAMES)
    for (const tool of tools) {
      expect(tool.inputSchema.type).toBe('object')
      expect(tool.annotations?.openWorldHint).toBe(false)
    }
    const console = tools.find((it) => it.name === 'get_console')!.inputSchema
    expect(console.properties).toMatchObject({
      since: { type: 'integer', minimum: 0 },
      level: { enum: ['debug', 'info', 'warn', 'error'] },
      limit: { type: 'integer', minimum: 1, maximum: 500 },
    })
    expect(console.required ?? []).toEqual([])
    const spawn = tools.find((it) => it.name === 'spawn_centity')!.inputSchema
    expect(spawn.required).toEqual(['centity'])
  })

  it("checks arguments against the tool's schema before it runs", async () => {
    backend.testConnect()
    const invalid = [
      ['get_console', { limit: 0 }],
      ['get_console', { level: 'loud' }],
      ['get_profile', { sort: 'slowest' }],
      ['get_profile', { limit: 0 }],
      ['spawn_centity', {}],
      ['run_command', { command: '   ' }],
      ['lookup_game_data', { registry: 'biome' }],
      ['bot_join', { name: 'x' }],
      ['bot_join', { name: 'Other', x: 1 }],
      ['bot_act', { name: 'Tester', action: 'jump' }],
      ['bot_act', { name: 'Tester', action: { type: 'flop' } }],
    ] as const
    for (const [name, args] of invalid) {
      const result = await call(name, args)
      expect(result.error, `${name} ${JSON.stringify(args)}`).toMatch(/Input validation error/)
    }
    expect((await call('bot_join', { name: 'Other', x: 1 })).error).toMatch(/x, y and z/)
    expect((await call('bot_join', { name: 'x' })).error).toMatch(/3 to 16/)
    expect(
      backend.bridgeLog.filter(
        (it) => it.method !== 'profiler_subscribe' && it.method !== 'settings',
      ),
    ).toEqual([])
    expect(backend.commandLog).toEqual([])
  })

  it('refuses unknown tools and arguments that are no object', async () => {
    expect((await call('nope')).error).toMatch(/nope not found/)
    const bad = await backend.testMcpCall('tools/call', { name: 'get_status', arguments: [1] })
    expect(bad.error?.code).toBeDefined()
  })

  it('reports status, including files with unsaved edits', async () => {
    await app.workspace.getState().openFile('modules/greeter/init.lua')
    app.workspace.getState().setText('modules/greeter/init.lua', '-- changed\n')
    const status = await call('get_status')
    expect(status.project.minecraft).toBe('26.3')
    expect(status.server.phase).toBe('stopped')
    expect(status.unsavedFiles).toEqual(['modules/greeter/init.lua'])
  })

  it('sees problems in a file the agent just wrote, before the watcher says so', async () => {
    expect((await call('get_problems')).errors).toBe(0)
    backend.testWriteMissed(
      'centities/tower/centity.json',
      JSON.stringify({ nodes: { top: { bogus: 1 } } }),
    )
    const problems = await call('get_problems', { file: 'centities/tower' })
    expect(problems.errors).toBeGreaterThan(0)
    expect(problems.editor[0]).toMatchObject({ file: 'centities/tower/centity.json' })
    expect((await call('get_problems', { file: 'modules' })).errors).toBe(0)
  })

  it("says what to do when the dev server isn't running", async () => {
    const result = await call('reload')
    expect(result.error).toMatch(/start_server/)
    expect((await call('run_command', { command: 'nf list' })).error).toMatch(/not running/)
  })

  it('reloads, and returns the errors the server logged meanwhile', async () => {
    backend.testConnect()
    const pending = request('reload', { paths: ['modules/greeter/init.lua'] })
    // The server logs the error while it reloads, before the tool's settling time is up.
    await settle()
    expect(backend.bridgeLog).toContainEqual({
      method: 'reload',
      params: { paths: ['modules/greeter/init.lua'] },
    })
    backend.testBridgeEvent('console', {
      items: [
        {
          type: 'script_error',
          message: 'attempt to call a nil value',
          source: { file: 'modules/greeter/init.lua', line: 3 },
        },
      ],
    })
    const result = await advanceUntil(pending)
    expect(result.resources).toEqual([
      { package: 'showcase', kind: 'module', id: 'greeter', ok: true, reattached: 0 },
    ])
    expect(result.errors).toEqual([
      expect.objectContaining({
        kind: 'script_error',
        source: { file: 'modules/greeter/init.lua', line: 3 },
      }),
    ])
  })

  it('restarts the dev server when a reload says it must, as a save does', async () => {
    backend.testConnect()
    backend.testReloadRestarts()
    const result = await call('reload', { paths: ['advancements/treasure_hunter.json'] })
    expect(result.restarted).toBe(true)
    expect(backend.bridgeLog).toContainEqual({
      method: 'reload',
      params: { paths: ['advancements/treasure_hunter.json'] },
    })
    // It was stopped and started again.
    await settle()
    expect((await backend.serverState()).phase).toBe('running')
    const lines = app.run
      .getState()
      .console.lines()
      .map((it) => it.text)
    expect(lines).toContain('Restarting the dev server')
    expect(lines).toContain('[Server] Stopping server')
  })

  it('reloads the whole project when no paths are given', async () => {
    backend.testConnect()
    await call('reload')
    expect(backend.bridgeLog.at(-1)).toEqual({
      method: 'reload',
      params: { paths: ['netherforge.json'] },
    })
  })

  it('reads the console from a cursor and by level', async () => {
    backend.testServerOutput('[12:00:00 INFO]: one')
    backend.testServerOutput('[12:00:00 WARN]: two')
    const all = await call('get_console')
    expect(all.lines.map((it: { text: string }) => it.text)).toEqual([
      '[12:00:00 INFO]: one',
      '[12:00:00 WARN]: two',
    ])
    backend.testServerOutput('[12:00:01 INFO]: three')
    const fresh = await call('get_console', { since: all.lastId })
    expect(fresh.lines.map((it: { text: string }) => it.text)).toEqual(['[12:00:01 INFO]: three'])
    const warnings = await call('get_console', { level: 'warn' })
    expect(warnings.lines).toHaveLength(1)
  })

  it('reads the profile, sorted and limited, and resets it', async () => {
    expect((await call('get_profile')).error).toMatch(/isn't running/)
    await app.profiler.getState().connect()
    backend.testConnect()
    backend.testBridgeEvent('profiler', {
      items: [
        {
          ticks: [
            {
              tick: 1,
              nanos: 2_000_000,
              phases: { timers: 500_000, world: 1_500_000 },
              scripts: 1_000_000,
            },
            {
              tick: 2,
              nanos: 4_000_000,
              phases: { timers: 1_000_000, world: 3_000_000 },
              scripts: 3_000_000,
              top: [{ scope: 'module shop', nanos: 3_000_000, calls: 1 }],
            },
          ],
          handlers: [
            {
              script: 'centity tower',
              kind: 'tick',
              source: { file: 'centities/tower/script.lua', line: 9 },
              calls: 4,
              nanos: 4_000_000,
              max: 1_500_000,
              scopes: 2,
            },
            { script: 'module shop', kind: 'command', calls: 1, nanos: 3_000_000, max: 3_000_000 },
          ],
          scopes: [{ scope: 'module shop', nanos: 3_000_000, calls: 1, max: 3_000_000 }],
        },
      ],
    })
    const profile = await call('get_profile')
    expect(profile).toMatchObject({
      ticks: 2,
      meanTickMs: 3,
      recent: {
        ticks: 2,
        stepsMeanMs: { timers: 0.75, world: 2.25 },
        scriptsMeanMs: 2,
        worstTick: { tick: 2, ms: 4, topScopes: [{ scope: 'module shop', ms: 3 }] },
      },
      functions: [
        {
          script: 'centity tower',
          kind: 'tick',
          where: 'centities/tower/script.lua:9',
          scopes: 2,
          calls: 4,
          totalMs: 4,
          meanMs: 1,
          maxMs: 1.5,
        },
        { script: 'module shop', kind: 'command', where: null, calls: 1, totalMs: 3 },
      ],
      functionsTotal: 2,
      scopes: [{ scope: 'module shop', calls: 1, totalMs: 3, perTickMs: 1.5, maxTickMs: 3 }],
      reset: false,
    })
    const byWorst = await call('get_profile', { sort: 'max', limit: 1, reset: true })
    expect(byWorst.functions.map((it: { script: string }) => it.script)).toEqual(['module shop'])
    expect(byWorst.functionsTotal).toBe(2)
    expect(await call('get_profile')).toMatchObject({ ticks: 0, functions: [], scopes: [] })
  })

  it('runs commands through the bridge and spawns centities', async () => {
    backend.testConnect()
    await call('run_command', { command: '/time set day' })
    expect(backend.commandLog).toEqual(['/time set day'])
    const instance = await call('spawn_centity', { centity: 'tower' })
    expect(instance.centity).toBe('tower')
    expect((await call('list_instances')).instances).toHaveLength(1)
  })

  it('joins a bot, has it act, and reports what it was sent', async () => {
    backend.testConnect()
    const joined = await call('bot_join', { name: 'Tester', x: 1, y: 64, z: 2 })
    expect(joined.bot).toMatchObject({ name: 'Tester', x: 1, z: 2 })
    expect(joined.events).toEqual([{ type: 'chat', seq: 0, text: 'Tester joined the game' }])
    expect(backend.bridgeLog.at(-2)).toMatchObject({
      method: 'bots/join',
      params: { at: { x: 1, y: 64, z: 2 }, resourcePack: 'accept' },
    })

    const said = await call('bot_act', {
      name: 'Tester',
      action: { type: 'chat', message: 'hi' },
      settleMs: 0,
    })
    expect(said.events).toEqual([{ type: 'chat', seq: 1, text: '<Tester> hi' }])
    expect((await call('bot_state', { name: 'Tester' })).events).toBe(2)
    expect((await call('bot_events', { name: 'Tester', since: 1 })).events).toHaveLength(1)
    expect((await call('bot_events', { name: 'Tester', types: ['dialog'] })).events).toEqual([])

    expect((await call('bot_act', { name: 'Nobody', action: { type: 'jump' } })).error).toMatch(
      /no bot named/,
    )
    expect(await call('bot_leave', { name: 'Tester' })).toEqual({ left: 'Tester' })
    expect((await call('bot_state', { name: 'Tester' })).error).toMatch(/no bot named/)
  })

  it('looks up ids in the game data, exact match first', async () => {
    await open({
      gameData: {
        '26.3': {
          minecraft: '26.3',
          blocks: {
            'minecraft:stone_bricks': {},
            'minecraft:stone': {},
            'minecraft:oak_stairs': { properties: { half: ['top', 'bottom'] } },
            'minecraft:dark_oak_stairs': {},
          },
          registries: {
            'minecraft:block': [
              'minecraft:dark_oak_stairs',
              'minecraft:oak_stairs',
              'minecraft:stone',
              'minecraft:stone_bricks',
            ],
            'minecraft:item': ['minecraft:oak_stairs', 'minecraft:stick'],
          },
          tags: { 'minecraft:item': { 'minecraft:stairs': ['minecraft:oak_stairs'] } },
        },
      },
    })
    const stone = await call('lookup_game_data', {
      registry: 'minecraft:block',
      query: 'minecraft:stone',
    })
    expect(stone.results.map((it: { id: string }) => it.id)).toEqual([
      'minecraft:stone',
      'minecraft:stone_bricks',
    ])
    const oak = await call('lookup_game_data', { registry: 'minecraft:block', query: 'oak_stairs' })
    expect(oak.results.map((it: { id: string }) => it.id)).toEqual([
      'minecraft:oak_stairs',
      'minecraft:dark_oak_stairs',
    ])
    expect(oak.results[0].properties).toEqual({ half: ['top', 'bottom'] })
    const stairs = await call('lookup_game_data', { registry: 'minecraft:block', query: 'STAIRS' })
    expect(stairs.total).toBe(2)
    // Only the block registry carries block states.
    const items = await call('lookup_game_data', { registry: 'minecraft:item', query: 'stairs' })
    expect(items.results).toEqual([{ id: 'minecraft:oak_stairs' }])
    expect(
      (await call('lookup_game_data', { registry: 'minecraft:item', tags: true })).results,
    ).toEqual([{ id: '#minecraft:stairs', values: ['minecraft:oak_stairs'] }])
    expect(
      (await call('lookup_game_data', { registry: 'minecraft:worldgen/biome' })).error,
    ).toMatch(/no registry "minecraft:worldgen\/biome"\. It has: minecraft:block, minecraft:item\./)
  })

  it('explains that game data comes from the first server start', async () => {
    expect((await call('lookup_game_data', { registry: 'minecraft:block' })).error).toMatch(
      /start_server/,
    )
  })

  it('starts the server and waits for the plugin, but never accepts the EULA', async () => {
    expect((await call('start_server')).error).toMatch(/EULA/)
    expect(app.run.getState().server.phase).toBe('stopped')
    await open({ eulaAccepted: true })
    const started = await call('start_server')
    expect(started.server).toMatchObject({ phase: 'running', bridgeConnected: true })
    expect((await call('stop_server')).server.phase).toBe('stopped')
  })

  it('opens a file in the editor for the user', async () => {
    await call('open_in_editor', { path: 'centities/tower/centity.json' })
    expect(app.workspace.getState().activeTab).toBe('centity:centities/tower/centity.json')
    expect((await call('open_in_editor', { path: 'nope.json' })).error).toMatch(/no nope\.json/)
  })

  it('needs an open project', async () => {
    await app.workspace.getState().closeProject()
    expect((await call('get_problems')).error).toMatch(/No project/)
    expect((await call('get_status')).project).toBeNull()
  })
})

describe('MCP tools: what an agent sees of the server meanwhile, and of failures', () => {
  it('gives the newest lines of the console up to a limit, saying it cut the rest', async () => {
    for (const n of [1, 2, 3]) backend.testServerOutput(`[12:00:0${n} INFO]: line ${n}`)
    const two = await call('get_console', { limit: 2 })
    expect(two.lines.map((it: { text: string }) => it.text)).toEqual([
      '[12:00:02 INFO]: line 2',
      '[12:00:03 INFO]: line 3',
    ])
    expect(two.truncated).toBe(true)
    expect((await call('get_console', { limit: 3 })).truncated).toBe(false)
  })

  it("returns what the console printed while a command ran, not the editor's own echo", async () => {
    backend.testConnect()
    const pending = request('run_command', { command: 'nf list' })
    await settle()
    backend.testServerOutput('[12:00:00 INFO]: 1 centity alive')
    const { output } = await advanceUntil(pending)
    expect(output.map((it: { text: string }) => it.text)).toEqual([
      '[12:00:00 INFO]: 1 centity alive',
    ])
    // The editor's console shows the agent ran it.
    expect(
      app.run
        .getState()
        .console.lines()
        .map((it) => it.text),
    ).toContain('> nf list (from a coding agent)')
  })

  it("returns a bot's events and the script errors its action caused", async () => {
    backend.testConnect()
    await call('bot_join', { name: 'Tester' })
    const pending = request('bot_act', { name: 'Tester', action: { type: 'chat', message: 'hi' } })
    // Within the default settling time.
    await settle()
    backend.testBridgeEvent('console', {
      items: [
        {
          type: 'script_error',
          message: 'attempt to index a nil value',
          source: { file: 'modules/greeter/init.lua', line: 5 },
        },
      ],
    })
    const acted = await advanceUntil(pending)
    expect(acted.events).toEqual([{ type: 'chat', seq: 1, text: '<Tester> hi' }])
    expect(acted.errors).toEqual([
      expect.objectContaining({ source: { file: 'modules/greeter/init.lua', line: 5 } }),
    ])
  })

  it('says a dev server already up is running, without starting another', async () => {
    backend.testConnect()
    const started = await call('start_server')
    expect(started).toMatchObject({ alreadyRunning: true, server: { bridgeConnected: true } })
  })

  it('says why the server refused, and that a server without bots has none', async () => {
    backend.testConnect()
    const bridge = vi.spyOn(backend, 'bridgeRequest')
    bridge.mockRejectedValueOnce(new BackendError('plugin', 'reload is busy'))
    expect((await call('reload')).error).toBe('Reload failed: reload is busy')
    bridge.mockRejectedValueOnce(new BackendError('plugin', 'no centity "nope"'))
    expect((await call('spawn_centity', { centity: 'nope' })).error).toBe(
      'Couldn\'t spawn nope: no centity "nope"',
    )
    bridge.mockRejectedValueOnce(new BackendError('unknownMethod', 'Unknown method "bots/join"'))
    expect((await call('bot_join', { name: 'Tester' })).error).toMatch(/has no bots/)
  })

  it('opens a file the agent just wrote, before the watcher says so', async () => {
    backend.testWriteMissed('modules/greeter/notes.lua', '-- new\n')
    expect(await call('open_in_editor', { path: 'modules/greeter/notes.lua', line: 1 })).toEqual({
      opened: 'modules/greeter/notes.lua',
    })
    expect(app.workspace.getState().activeTab).toBe('script:modules/greeter/notes.lua')
  })
})
