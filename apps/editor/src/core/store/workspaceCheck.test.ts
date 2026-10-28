import { describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'
import type { MemberProblems } from './workspaceCheck'

const RUBY = 'items/ruby/item.json'

/** The showcase, its library, and an arena server depending on the library, as one workspace. */
async function open(network?: { arrival: string; servers: Record<string, string> }) {
  const example = await openExampleApp({
    backend: {
      workspaces: {
        '/memory': JSON.stringify({
          name: 'Net',
          projects: ['showcase', 'library', 'arena'],
          network,
        }),
      },
    },
  })
  const { backend, app } = example
  const library = JSON.parse(await backend.packageReadText('../library', 'netherforge.json'))
  await backend.createProject('/memory/arena', {
    'netherforge.json': JSON.stringify({
      formatVersion: 1,
      name: 'Arena',
      namespace: 'arena',
      version: '0.1.0',
      minecraft: library.minecraft,
      dependencies: { library: { path: '../library' } },
    }),
    'netherforge.lock': await backend.readText('netherforge.lock'),
    'items/broken/item.json': '{ "name": ',
    'modules/portal/init.lua': 'nf.network.send_player(p, "lobby")',
  })
  await app.workspace.getState().openProject(EXAMPLE_ROOT)
  await app.workspaceFile.getState().refresh()
  await expect.poll(() => app.workspaceFile.getState().checking).toBe(false)
  return example
}

/** The codes of the problems in arena's item, as last checked. */
const brokenCodes = (state: { others: MemberProblems[] }) =>
  state.others
    .find((it) => it.member.path === 'arena')
    ?.problems.filter((it) => it.file === 'items/broken/item.json')
    .map((it) => it.code) ?? []

describe("the workspace's other projects", () => {
  it('are checked from disk, with their packages read from the workspace', async () => {
    const { app } = await open()
    const others = app.workspaceFile.getState().others
    expect(others.map((it) => it.member.path)).toEqual(['library', 'arena'])
    const arena = others.find((it) => it.member.path === 'arena')!
    expect(arena.error).toBeNull()
    expect(arena.problems.map((it) => it.file)).toContain('items/broken/item.json')
    // The library resolved: nothing says it's missing or changed.
    expect(arena.problems.filter((it) => it.code?.startsWith('package.'))).toEqual([])
    expect(
      app.workspace.getState().problems.some((it) => it.file === 'items/broken/item.json'),
    ).toBe(false)
  })

  it('are checked again after a save', async () => {
    const { app, backend } = await open()
    const before = brokenCodes(app.workspaceFile.getState())
    expect(before.length).toBeGreaterThan(0)
    await backend.workspaceMemberWriteText(
      'arena',
      'items/broken/item.json',
      await backend.readText(RUBY),
    )
    const ws = () => app.workspace.getState()
    await ws().loadDoc(RUBY)
    ws().edit<{ name?: string }>(RUBY, (draft) => {
      draft.name = '<red>Saved ruby'
    })
    expect(await ws().save(RUBY)).toBe(true)
    await expect
      .poll(() => brokenCodes(app.workspaceFile.getState()), { timeout: 4000 })
      .not.toEqual(before)
  })

  it("run their tests one after another, each on its project's", async () => {
    const { app, backend } = await open()
    await app.workspace.getState().trustWorkspace(true)
    const report = (file: string, status: 'passed' | 'failed') => ({
      results: [
        {
          file,
          name: 'works',
          status,
          durationMillis: 1,
          message: null,
          source: null,
          logs: [],
          traceback: null,
        },
      ],
      passed: status === 'passed' ? 1 : 0,
      failed: status === 'failed' ? 1 : 0,
      errored: 0,
      durationMillis: 1,
      error: null,
      problems: [],
    })
    backend.testTestReport(report('showcase_test.lua', 'passed'))
    backend.testTestReport(report('arena_test.lua', 'failed'), 'arena')
    const members = app.workspaceFile.getState().info!.members
    await app.tests.getState().runWorkspace(members.filter((it) => it.root !== EXAMPLE_ROOT))
    const tests = app.tests.getState()
    expect(tests.report?.passed).toBe(1)
    expect(
      tests.others.map((it) => [it.member.path, it.report?.failed ?? null, it.error === null]),
    ).toEqual([
      ['library', null, false],
      ['arena', 1, true],
    ])
  })

  it('warn of players sent to a server the network lacks, in the open project as it is and the others', async () => {
    const { app } = await open({ arrival: 'hub', servers: { hub: 'showcase', arena: 'arena' } })
    const arena = app.workspaceFile.getState().others.find((it) => it.member.path === 'arena')!
    expect(
      arena.problems.filter((it) => it.code === 'workspace.unknown-server').map((it) => it.file),
    ).toEqual(['modules/portal/init.lua'])
    expect(app.workspaceFile.getState().openProblems).toEqual([])

    const ws = () => app.workspace.getState()
    await ws().createFile(
      'modules/gate/init.lua',
      'nf.network.send_player(p, "arena")\nnf.network.send_player(p, "lobby")\n',
    )
    await expect
      .poll(() => app.workspaceFile.getState().openProblems.map((it) => [it.file, it.line]))
      .toEqual([['modules/gate/init.lua', 2]])
  })
})
