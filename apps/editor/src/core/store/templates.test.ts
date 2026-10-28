import { describe, expect, it, vi } from 'vitest'
import { MemoryBackend } from '@/testing/backend/memory'
import { MANIFEST_FILE, newProjectFiles, type ProjectManifest } from '@/core/format'
import { TEMPLATES, templateById } from '@/core/templates'
import { mergeRequires } from './packages'
import { createWorkspace } from './workspace'

const ROOT = '/memory/fresh'

async function openFresh() {
  const backend = new MemoryBackend({ projects: { [ROOT]: newProjectFiles('Fresh', '26.3') } })
  const store = createWorkspace(backend)
  await store.getState().openProject(ROOT)
  return { backend, store, ws: () => store.getState() }
}

describe('the project templates', () => {
  it('are the projects the repository tests, their manifests left out', () => {
    expect(TEMPLATES.map((it) => it.id)).toEqual(['minigame', 'shop', 'rpg_mob'])
    for (const template of TEMPLATES) {
      expect(Object.keys(template.files)).not.toContain('netherforge.json')
      expect(Object.keys(template.files).some((it) => it.startsWith('tests/'))).toBe(true)
    }
    expect(templateById('shop')?.requires).toEqual({ db: true })
  })

  it.each(TEMPLATES)(
    "$title is copied into a project as the project's own, and validates clean",
    async (template) => {
      const { backend, ws } = await openFresh()
      expect(await ws().applyTemplate(template.id)).toBe(true)

      const files = backend.testFiles()
      for (const path of Object.keys(template.files)) {
        expect(files[path]).toBe(template.files[path])
      }
      const manifest = JSON.parse(files[MANIFEST_FILE] as string) as ProjectManifest
      // Not a dependency: what it requires, the project requires.
      expect(manifest.dependencies).toBeUndefined()
      expect(manifest.requires ?? {}).toEqual(template.requires)
      expect(ws().docs[MANIFEST_FILE]?.dirty).toBe(false)
      await vi.waitFor(() => expect(ws().problems).toEqual([]), { timeout: 5000 })
      expect(ws().outline?.packages ?? {}).toEqual({})
    },
  )

  it('keeps what the project requires already, and refuses to write over its resources', async () => {
    const { backend, ws } = await openFresh()
    expect(await ws().applyTemplate('shop')).toBe(true)
    const before = backend.testFiles()
    expect(await ws().applyTemplate('rpg_mob')).toBe(true)
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).requires).toEqual({ db: true })
    await vi.waitFor(() => expect(ws().outline?.resources.item).toContain('goblin_ear'))

    expect(await ws().applyTemplate('shop')).toBe(false)
    expect(ws().notice?.text).toContain('already has')
    expect(ws().notice?.text).toContain('items/lucky_charm')
    expect(backend.testFiles()['menus/shop/menu.json']).toBe(before['menus/shop/menu.json'])
  })

  it('is taken away again by undo', async () => {
    const { backend, ws } = await openFresh()
    const fresh = backend.testFiles()
    await ws().applyTemplate('shop')
    await ws().undo(MANIFEST_FILE)
    expect(backend.testFiles()).toEqual(fresh)
  })
})

describe('mergeRequires', () => {
  it('asks for what either asks for, lists in order without repeats', () => {
    const into = { http: ['a.com'], plugins: ['vault'] }
    mergeRequires(into, { db: true, http: ['b.com', 'a.com'], plugins: ['vault'] })
    expect(into).toEqual({ db: true, http: ['a.com', 'b.com'], plugins: ['vault'] })
  })
})
