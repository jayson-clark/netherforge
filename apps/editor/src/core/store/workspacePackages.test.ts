import { describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT, LIBRARY_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'
import { readOnlyReason } from './project'
import { workspaceMemberAt } from './packages'

const GEM = 'library:items/gem/item.json'

/** The showcase, the library it depends on, and another server depending on the library, as one workspace. */
async function open(workspace = true) {
  const example = await openExampleApp({
    backend: {
      workspaces: workspace
        ? { '/memory': JSON.stringify({ name: 'Net', projects: ['showcase', 'library', 'arena'] }) }
        : {},
    },
  })
  const { backend, app } = example
  const library = JSON.parse(await backend.packageReadText('../library', 'netherforge.json'))
  // Another project of the workspace that depends on the library, locked at its hash now.
  const lock = JSON.parse(await backend.readText('netherforge.lock'))
  await backend.createProject('/memory/arena', {
    'netherforge.json': JSON.stringify({
      formatVersion: 1,
      name: 'Arena',
      namespace: 'arena',
      version: '0.1.0',
      minecraft: library.minecraft,
      dependencies: { library: { path: '../library' } },
    }),
    'netherforge.lock': JSON.stringify(lock),
  })
  await app.workspace.getState().openProject(EXAMPLE_ROOT)
  await app.workspace.getState().validateNow()
  return { ...example, lock }
}

describe("a workspace's packages", () => {
  it('finds which project of the workspace a package location is', async () => {
    const { app } = await open()
    const project = app.workspace.getState().project
    expect(workspaceMemberAt(project, '../library')?.path).toBe('library')
    expect(workspaceMemberAt(project, './../library/')?.root).toBe(LIBRARY_ROOT)
    expect(workspaceMemberAt(project, '../elsewhere')).toBeNull()
    expect(workspaceMemberAt(project, 'git:0123456789012345678901234567890123456789')).toBeNull()
    expect(workspaceMemberAt(null, '../library')).toBeNull()
  })

  it('stay read-only outside a workspace', async () => {
    const { app } = await open(false)
    expect(readOnlyReason(app.workspace.getState().readOnly, GEM)).toMatch(/uses as it is/)
  })

  it('are edited in place, and every project depending on one is locked to its new content', async () => {
    const { app, backend, lock } = await open()
    const ws = () => app.workspace.getState()
    expect(readOnlyReason(ws().readOnly, GEM)).toBeNull()
    const before = lock.packages.library.hash as string

    await ws().loadDoc(GEM)
    ws().edit<{ name?: string }>(GEM, (draft) => {
      draft.name = '<aqua>Polished gem'
    })
    expect(await ws().save(GEM)).toBe(true)
    expect(await backend.packageReadText('../library', 'items/gem/item.json')).toContain(
      'Polished gem',
    )

    // The other project's lock names the library's new hash.
    await expect
      .poll(
        async () =>
          JSON.parse(await backend.workspaceMemberReadText('arena', 'netherforge.lock')).packages
            .library.hash,
      )
      .not.toBe(before)
    // And so does this one's, once its packages are read again.
    await expect
      .poll(
        async () => JSON.parse(await backend.readText('netherforge.lock')).packages.library.hash,
      )
      .not.toBe(before)
    const arena = JSON.parse(await backend.workspaceMemberReadText('arena', 'netherforge.lock'))
    const showcase = JSON.parse(await backend.readText('netherforge.lock'))
    expect(arena.packages.library.hash).toBe(showcase.packages.library.hash)
  })
})
