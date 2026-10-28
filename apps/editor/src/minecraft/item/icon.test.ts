import { describe, expect, it } from 'vitest'
import {
  compileResourcePacks,
  type ResourcePackFile,
  type ResourcePacksPreview,
} from '@/core/format'
import type { AssetLoader } from '@netherforge/models/model'
import { iconSource, resourcePackBlockLook, resourcePackItemLook } from './icon'

const loader = (files: Record<string, unknown>): AssetLoader => ({
  json: async <T>(path: string) => (files[path] as T) ?? null,
})

const packs: Record<string, ResourcePackFile> = {
  ui: {
    items: {
      ruby: { texture: 'item/ruby.png' },
      gem: { texture: 'item/gem.png', guiTexture: 'gui/gem.png' },
      ore: { block: 'ui/ore' },
    },
    blocks: { ore: { top: 'block/top.png', bottom: 'block/top.png', side: 'block/side.png' } },
  },
}
const none: ResourcePacksPreview = { resourcePacks: {}, problems: [] }

describe('item icons', () => {
  it("draws a pack item model's GUI picture", () => {
    const picture = (path: string) => ({ kind: 'project', path })
    expect(resourcePackItemLook('ui/ruby', packs, none, 'shop')).toEqual(
      picture('resource_packs/ui/textures/item/ruby.png'),
    )
    expect(resourcePackItemLook('shop:ui/gem', packs, none, 'shop')).toEqual(
      picture('resource_packs/ui/textures/gui/gem.png'),
    )
    expect(resourcePackItemLook('ui/none', packs, none, 'shop')).toBeNull()
    // Another namespace's is a package's, not this project's.
    expect(resourcePackItemLook('acme:ui/ruby', packs, none, 'shop')).toBeNull()
    // A package's pack, which the packs store keys `ns:id`: its texture is a package path.
    expect(resourcePackItemLook('acme:ui/ruby', { 'acme:ui': packs.ui! }, none, 'shop')).toEqual(
      picture('acme:resource_packs/ui/textures/item/ruby.png'),
    )
    expect(resourcePackItemLook(undefined, packs, none, 'shop')).toBeNull()
  })

  it("draws an item drawn as a block as that look's cube, faces by format's fallback", () => {
    const compiled = compileResourcePacks('shop', { ui: JSON.stringify(packs.ui) }, undefined)
    expect(resourcePackItemLook('ui/ore', packs, compiled, 'shop')).toEqual({
      kind: 'block',
      faces: {
        up: 'resource_packs/ui/textures/block/top.png',
        down: 'resource_packs/ui/textures/block/top.png',
        north: 'resource_packs/ui/textures/block/side.png',
        south: 'resource_packs/ui/textures/block/side.png',
        east: 'resource_packs/ui/textures/block/side.png',
        west: 'resource_packs/ui/textures/block/side.png',
      },
    })
    // A block look that isn't built (or a pack that doesn't compile) draws nothing of the pack's.
    expect(resourcePackItemLook('ui/ore', packs, none, 'shop')).toBeNull()
  })

  it("draws a block as its look: the cube's faces, or the look's model file", () => {
    const withModel: Record<string, ResourcePackFile> = {
      ui: { ...packs.ui, blocks: { ...packs.ui!.blocks, crystal: { model: 'crystal.json' } } },
    }
    const compiled = compileResourcePacks('shop', { ui: JSON.stringify(withModel.ui) }, undefined)
    expect(resourcePackBlockLook('ui/ore', withModel, compiled, 'shop')).toMatchObject({
      kind: 'block',
      faces: { up: 'resource_packs/ui/textures/block/top.png' },
    })
    const models = {
      ui: {
        files: { 'crystal.json': { textures: { all: 'block/gem' } } },
        textures: ['block/gem.png'],
      },
    }
    expect(
      resourcePackBlockLook('ui/crystal', withModel, compiled, 'shop', 'shop', models),
    ).toEqual({
      kind: 'shape',
      model: { textures: { all: 'block/gem' } },
      textures: { all: 'resource_packs/ui/textures/block/gem.png' },
    })
    // No look, or one that isn't there, is drawn as the game's note block: nothing of the pack's.
    expect(resourcePackBlockLook(undefined, withModel, compiled, 'shop')).toBeNull()
    expect(resourcePackBlockLook('ui/none', withModel, compiled, 'shop')).toBeNull()
  })

  it('falls back to the client model: sprites, cuboids, or nothing', async () => {
    const assets = loader({
      'assets/minecraft/items/bread.json': {
        model: { type: 'minecraft:model', model: 'minecraft:item/bread' },
      },
      'assets/minecraft/models/item/bread.json': {
        parent: 'minecraft:item/generated',
        textures: { layer0: 'minecraft:item/bread' },
      },
      'assets/minecraft/models/item/generated.json': {},
      'assets/minecraft/models/item/stone.json': { parent: 'minecraft:block/stone' },
      'assets/minecraft/models/block/stone.json': {
        elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: {} }],
      },
    })
    expect(await iconSource({ kind: 'minecraft:bread' }, {}, none, 'shop', assets)).toEqual({
      kind: 'sprite',
      textures: ['minecraft:item/bread'],
    })
    expect(await iconSource({ kind: 'stone' }, {}, none, 'shop', assets)).toEqual({
      kind: 'model',
      item: 'stone',
    })
    expect(await iconSource({ kind: 'minecraft:missing' }, {}, none, 'shop', assets)).toEqual({
      kind: 'none',
    })
    // A pack reference that names nothing falls back to the vanilla look.
    expect(
      await iconSource(
        { kind: 'minecraft:bread', itemModel: 'ui/nope' },
        packs,
        none,
        'shop',
        assets,
      ),
    ).toMatchObject({ kind: 'sprite' })
    expect(
      await iconSource(
        { kind: 'minecraft:paper', itemModel: 'ui/ruby' },
        packs,
        none,
        'shop',
        null,
      ),
    ).toEqual({
      kind: 'project',
      path: 'resource_packs/ui/textures/item/ruby.png',
    })
    expect(await iconSource({ kind: 'minecraft:bread' }, {}, none, 'shop', null)).toEqual({
      kind: 'none',
    })
  })
})
