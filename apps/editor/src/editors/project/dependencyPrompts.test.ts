import { describe, expect, it, vi } from 'vitest'
import { MemoryBackend } from '@/testing/backend/memory'
import { MANIFEST_FILE, newProjectFiles } from '@/core/format'
import { createWorkspace } from '@/core/store/workspace'
import { LIBRARY_ROOT, libraryProject } from '@/testing/fixtures'
import { askFolderDependency, namespaceFromUrl } from './dependencyPrompts'

const ROOT = '/memory/fresh'

async function openFresh(picked: (string | null)[]) {
  const backend = new MemoryBackend({
    projects: { [ROOT]: newProjectFiles('Fresh', '26.3'), [LIBRARY_ROOT]: libraryProject },
    pickFolderResults: picked,
  })
  const workspace = createWorkspace(backend)
  await workspace.getState().openProject(ROOT)
  return { backend, workspace, ws: () => workspace.getState() }
}

describe('adding a dependency', () => {
  it('depends on a picked folder by its path from the project, under the namespace it says', async () => {
    const { backend, workspace, ws } = await openFresh([LIBRARY_ROOT])
    const choice = await askFolderDependency(backend, workspace)
    expect(choice).toEqual({ namespace: 'library', source: { path: '../library' } })

    expect(await ws().addDependency(choice!.namespace, choice!.source)).toBe(true)
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toEqual({
      library: { path: '../library' },
    })
    await vi.waitFor(() => expect(ws().outline?.packages?.library?.location).toBe('../library'), {
      timeout: 5000,
    })

    // Once is enough.
    expect(await ws().addDependency('library', { path: '../library' })).toBe(false)
  })

  it("refuses a folder that isn't a project, or is this one", async () => {
    const { backend, workspace, ws } = await openFresh(['/memory', ROOT])
    expect(await askFolderDependency(backend, workspace)).toBeNull()
    expect(ws().notice?.text).toContain("isn't a NetherForge project")
    expect(await askFolderDependency(backend, workspace)).toBeNull()
    expect(ws().notice?.text).toContain("project's own folder")
  })

  it("guesses a git repository's namespace from its URL", () => {
    expect(namespaceFromUrl('https://github.com/acme/acme-economy.git')).toBe('acme_economy')
    expect(namespaceFromUrl('git@github.com:acme/Shop.git/')).toBe('shop')
  })
})
