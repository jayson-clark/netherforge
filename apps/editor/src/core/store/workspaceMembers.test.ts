import { describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'
import {
  moveModuleToPackage,
  namedBy,
  newServer,
  planModuleMove,
  relativeTo,
  serverModule,
} from './workspaceMembers'

/** The showcase and its library as a workspace, with a network when [network] says so. */
async function open(network = false) {
  const example = await openExampleApp({
    backend: {
      workspaces: {
        '/memory': JSON.stringify({
          name: 'Net',
          projects: ['showcase', 'library'],
          network: network ? { arrival: 'hub', servers: { hub: 'showcase' } } : undefined,
        }),
      },
    },
  })
  await example.app.workspace.getState().openProject(EXAMPLE_ROOT)
  await example.app.workspaceFile.getState().refresh()
  await example.app.workspace.getState().validateNow()
  return { ...example, stores: { ...example.app, backend: example.backend } }
}

describe('workspace folders', () => {
  it('are named by a folder or its parent and /*', () => {
    expect(namedBy(['servers/*', './packages/chess/'], 'servers/arena')).toBe(true)
    expect(namedBy(['servers/*'], 'servers/arena/inner')).toBe(false)
    expect(namedBy(['packages/chess'], 'packages/chess')).toBe(true)
    expect(namedBy(['*'], 'arena')).toBe(true)
    expect(namedBy(['servers/*'], 'arena')).toBe(false)
  })

  it('are reached from each other by relative paths', () => {
    expect(relativeTo('servers/chess', 'packages/chess')).toBe('../../packages/chess')
    expect(relativeTo('showcase', 'packages/greeting')).toBe('../packages/greeting')
    expect(relativeTo('a', 'a/b')).toBe('b')
  })
})

describe('a new server', () => {
  it('is a project of the workspace with a module to start from, and one of the network', async () => {
    const { app, backend, stores } = await open(true)
    const member = await newServer(stores, 'arena')
    expect(member.path).toBe('arena')
    const file = app.workspaceFile.getState().file!
    expect(file.projects).toContain('arena')
    expect(file.network).toEqual({ arrival: 'hub', servers: { hub: 'showcase', arena: 'arena' } })
    const manifest = JSON.parse(await backend.workspaceMemberReadText('arena', 'netherforge.json'))
    expect(manifest.requires).toEqual({ network: true })
    const module = await backend.workspaceMemberReadText('arena', 'modules/arena/init.lua')
    expect(module).toContain('nf.commands.register("hub"')
    // Its name is the network's: one taken, or one the proxy can't use, is refused.
    await expect(newServer(stores, 'arena')).rejects.toThrow(/already a server/)
    await expect(newServer(stores, 'Bad Name')).rejects.toThrow(/Lowercase/)
  })

  it('starts the network, arriving on it, when the workspace has none', async () => {
    const { app, stores } = await open(false)
    await newServer(stores, 'lobby')
    expect(app.workspaceFile.getState().file!.network).toEqual({
      arrival: 'lobby',
      servers: { lobby: 'lobby' },
    })
    expect(serverModule('lobby', 'lobby')).not.toContain('nf.commands.register')
  })
})

describe('moving a module into a package', () => {
  it('makes the package, depends on it, and names the module there', async () => {
    const { app, backend, stores } = await open()
    const plan = await planModuleMove(stores, 'greeter', 'modules/greeter', 'packages/greeting')
    expect(plan.problem).toBeNull()
    expect(plan.target).toBeNull()
    expect(plan.namespace).toBe('greeting')
    expect(plan.requiredBy).toEqual(['tests/greeter_test.lua'])
    await moveModuleToPackage(stores, 'greeter', plan)

    const ws = app.workspace.getState()
    expect(ws.files.some((it) => it.startsWith('modules/greeter/'))).toBe(false)
    const manifest = JSON.parse(await backend.readText('netherforge.json'))
    expect(manifest.dependencies.greeting).toEqual({ path: '../packages/greeting' })
    expect(await backend.readText('tests/greeter_test.lua')).toContain(
      'require("greeting:greeter.messages")',
    )
    const theirs = JSON.parse(
      await backend.workspaceMemberReadText('packages/greeting', 'netherforge.json'),
    )
    expect(theirs.exports).toEqual({ modules: ['greeter'] })
    expect(
      await backend.workspaceMemberReadText('packages/greeting', 'modules/greeter/messages.lua'),
    ).toBeTruthy()
    expect(app.workspaceFile.getState().file!.projects).toContain('packages/greeting')
    // What the module names of the library, the package now depends on, from where it is.
    expect(theirs.dependencies).toEqual({ library: { path: '../../library' } })
    expect(plan.dependencies).toEqual({ library: { path: '../../library' } })
    // Once the packages are read again, nothing is missing and the moved module validates there.
    await expect
      .poll(async () => {
        await app.workspace.getState().validateNow()
        return app.workspace
          .getState()
          .problems.filter((it) => it.severity === 'error')
          .map((it) => `${it.code} ${it.file}`)
      })
      .toEqual([])
  })

  it("says what it can't take, and refuses what can't go", async () => {
    const { stores } = await open()
    const intoLibrary = await planModuleMove(stores, 'expo', 'modules/expo', 'library')
    expect(intoLibrary.problem).toBeNull()
    expect(intoLibrary.namespace).toBe('library')
    expect(intoLibrary.requiredBy.length).toBeGreaterThan(1)
    expect(
      (await planModuleMove(stores, 'greeter', 'modules/greeter', 'showcase')).problem,
    ).toMatch(/this project/)
    expect(
      (await planModuleMove(stores, 'greeter', 'modules/greeter', 'packages/Bad')).problem,
    ).toMatch(/namespace/)
    expect(
      (await planModuleMove(stores, 'greeter', 'modules/greeter', 'library/inner')).problem,
    ).toMatch(/inside/)
  })
})
