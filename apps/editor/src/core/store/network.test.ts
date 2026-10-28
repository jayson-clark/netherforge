import { describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT, LIBRARY_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'

const LOBBY = { enabled: true, name: 'main', servers: [{ name: 'lobby', project: '../library' }] }

async function app(trusted?: string[]) {
  const example = await openExampleApp({
    backend: { eulaAccepted: true, ...(trusted ? { trusted } : {}) },
  })
  await example.app.network.getState().connect()
  await example.app.run.getState().connect()
  await example.app.network.getState().load()
  return example
}

describe('the network store', () => {
  it("loads the open project's layout and keeps a change, or why it was refused", async () => {
    const { app: stores } = await app()
    const network = stores.network
    expect(network.getState().layout).toEqual({ enabled: false, name: 'main', servers: [] })

    await network.getState().setLayout(LOBBY)
    expect(network.getState().layout).toEqual(LOBBY)
    expect(network.getState().error).toBeNull()
    expect(network.getState().checks).toEqual([
      {
        name: 'lobby',
        root: LIBRARY_ROOT,
        projectName: 'Library example',
        minecraft: '26.3',
        trusted: true,
        problem: null,
      },
    ])

    await network.getState().setLayout({ ...LOBBY, name: 'Main' })
    expect(network.getState().layout).toEqual(LOBBY)
    expect(network.getState().error).toMatch(/can't name a server/)
  })

  it("trusts a server's folder, then runs the network and each member's console", async () => {
    const { app: stores, backend } = await app([EXAMPLE_ROOT])
    const network = stores.network
    await network.getState().setLayout(LOBBY)
    expect(network.getState().checks[0]!.trusted).toBe(false)
    await stores.run.getState().start()
    expect(stores.run.getState().console.lines().at(-1)!.text).toMatch(/isn't trusted yet/)

    await network.getState().trust('lobby')
    expect(network.getState().checks[0]!.trusted).toBe(true)
    await stores.run.getState().start()
    await expect
      .poll(() => network.getState().members.map((it) => [it.name, it.state.phase]))
      .toEqual([
        ['proxy', 'running'],
        ['lobby', 'running'],
      ])
    expect(
      network
        .getState()
        .consoles.get('lobby')!
        .lines()
        .map((it) => it.text),
    ).toContain('[00:00:00 INFO]: Done (0.1s)!')

    await network.getState().command('lobby', 'say hi')
    await expect.poll(() => network.getState().consoles.get('lobby')!.last?.text).toBe('> say hi')
    await network.getState().command('nobody', 'x')
    expect(network.getState().consoles.get('nobody')!.last).toMatchObject({
      kind: 'editor',
      level: 'error',
    })
    backend.testNetworkOutput('proxy', '[00:00:00 WARN]: a warning')
    expect(network.getState().consoles.get('proxy')!.last!.level).toBe('warn')

    await stores.run.getState().stop()
    expect(network.getState().members.map((it) => it.state.phase)).toEqual(['stopped', 'stopped'])
  })
})
