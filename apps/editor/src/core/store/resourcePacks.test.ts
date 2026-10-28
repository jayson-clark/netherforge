import { beforeEach, describe, expect, it } from 'vitest'
import type { ResourcePackFile } from '@/core/format'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleWorkspace } from '@/testing/workspace'
import { createResourcePacks, measuredTextures, type ResourcePacksStore } from './resourcePacks'
import type { WorkspaceStore } from './workspace'

const PACK = 'resource_packs/ui/pack.json'

let workspace: WorkspaceStore
let packs: ResourcePacksStore

beforeEach(async () => {
  const example = exampleWorkspace()
  workspace = example.workspace
  packs = createResourcePacks(workspace, example.backend)
  await workspace.getState().openProject(EXAMPLE_ROOT)
})

describe('the packs store', () => {
  it("compiles the project's packs", () => {
    expect(Object.keys(packs.getState().compiled.resourcePacks)).toContain('ui')
    expect(packs.getState().files.ui).toBeDefined()
  })

  it('follows unsaved edits, and only changes when a pack does', async () => {
    await workspace.getState().openFile(PACK)
    const before = packs.getState().compiled
    workspace.getState().select(PACK, [{ kind: 'glyphs', key: 'coin' }])
    expect(packs.getState().compiled).toBe(before)

    workspace.getState().edit<ResourcePackFile>(PACK, (draft) => {
      draft.glyphs = { ...draft.glyphs, extra: { texture: 'glyph/coin.png' } }
    })
    expect(Object.keys(packs.getState().compiled.resourcePacks.ui!.glyphs)).toContain('extra')
  })

  it("measures the pictures that replace the game's textures, so their sizes can be checked", () => {
    const files = {
      ui: {
        skins: { shop: { texture: 'gui/shop.png' } },
        textures: { 'block/stone': 'world/stone.png' },
      },
    } as unknown as Record<string, ResourcePackFile>
    expect(measuredTextures(files)).toEqual([
      'resource_packs/ui/textures/gui/shop.png',
      'resource_packs/ui/textures/world/stone.png',
    ])
  })
})
