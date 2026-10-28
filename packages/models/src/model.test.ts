import { describe, expect, it } from 'vitest'
import {
  bestVariantKey,
  blockPlacements,
  cubeModel,
  itemModel,
  itemModelRef,
  isSpriteModel,
  packModel,
  placedModels,
  resolveModel,
  resolveTexture,
  spriteLayers,
  type AssetLoader,
} from './model.ts'

const files: Record<string, unknown> = {
  'assets/minecraft/models/block/cube_all.json': {
    parent: 'minecraft:block/cube',
    textures: { particle: '#all', north: '#all' },
  },
  'assets/minecraft/models/block/cube.json': {
    elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { north: { texture: '#north' } } }],
  },
  'assets/minecraft/models/block/stone.json': {
    parent: 'minecraft:block/cube_all',
    textures: { all: 'minecraft:block/stone' },
  },
  'assets/minecraft/blockstates/oak_fence.json': {
    multipart: [
      { apply: { model: 'minecraft:block/oak_fence_post' } },
      { when: { north: 'true' }, apply: { model: 'minecraft:block/oak_fence_side', uvlock: true } },
      {
        when: { OR: [{ east: 'true' }, { west: 'true' }] },
        apply: { model: 'minecraft:block/oak_fence_side', y: 90 },
      },
    ],
  },
  'assets/minecraft/items/stick.json': {
    model: { type: 'minecraft:model', model: 'minecraft:item/stick' },
  },
  'assets/minecraft/models/item/stick.json': {
    parent: 'minecraft:item/generated',
    textures: { layer0: 'minecraft:item/stick' },
  },
  'assets/minecraft/models/item/generated.json': { parent: 'builtin/generated' },
}
const loader: AssetLoader = {
  json: async <T>(path: string) => (files[path] as T | undefined) ?? null,
}

describe('model resolution', () => {
  it('walks the parent chain and binds texture variables', async () => {
    const model = (await resolveModel(loader, 'minecraft:block/stone'))!
    expect(model.chain).toEqual([
      'minecraft:block/stone',
      'minecraft:block/cube_all',
      'minecraft:block/cube',
    ])
    expect(model.elements).toHaveLength(1)
    expect(resolveTexture(model.elements[0]!.faces!.north!.texture, model.textures)).toBe(
      'minecraft:block/stone',
    )
    expect(await resolveModel(loader, 'minecraft:block/missing')).toBeNull()
  })

  it('picks the most specific variant', () => {
    const keys = ['facing=east,half=bottom', 'facing=east,half=top', 'facing=west,half=bottom']
    expect(bestVariantKey(keys, { facing: 'east', half: 'top' })).toBe('facing=east,half=top')
    expect(bestVariantKey(keys, { facing: 'west' })).toBe('facing=west,half=bottom')
    expect(bestVariantKey(keys, {})).toBe('facing=east,half=bottom')
    expect(bestVariantKey([''], { anything: 'x' })).toBe('')
  })

  it('collects every multipart part whose condition holds', async () => {
    const post = await blockPlacements(loader, 'minecraft:oak_fence')
    expect(post.map((it) => it.model)).toEqual(['minecraft:block/oak_fence_post'])
    const arms = await blockPlacements(loader, 'oak_fence[north=true,west=true]')
    expect(arms).toEqual([
      { model: 'minecraft:block/oak_fence_post', x: 0, y: 0 },
      { model: 'minecraft:block/oak_fence_side', x: 0, y: 0 },
      { model: 'minecraft:block/oak_fence_side', x: 0, y: 90 },
    ])
  })

  it('finds an item model and recognises sprites', async () => {
    expect(await itemModelRef(loader, 'minecraft:stick')).toBe('minecraft:item/stick')
    expect(await itemModelRef(loader, 'diamond')).toBe('minecraft:item/diamond')
    const stick = (await resolveModel(loader, 'minecraft:item/stick'))!
    expect(isSpriteModel(stick)).toBe(true)
    expect(spriteLayers(stick)).toEqual(['minecraft:item/stick'])
  })

  it('resolves a pack model over the client parent, the bound textures on top', async () => {
    const model = await packModel(
      loader,
      { parent: 'minecraft:block/cube_all', textures: { all: 'acme:block/ore', north: '#side' } },
      { side: 'file:///pack/side.png' },
    )
    expect(model.chain).toEqual(['minecraft:block/cube_all', 'minecraft:block/cube'])
    expect(model.elements).toHaveLength(1)
    expect(resolveTexture('#north', model.textures)).toBe('file:///pack/side.png')
    expect(resolveTexture('#all', model.textures)).toBe('acme:block/ore')
    // A model of its own elements keeps them; one without a parent has only what it says.
    const flat = await packModel(loader, { textures: { layer0: 'acme:item/coin' } }, {})
    expect(flat.chain).toEqual([])
    expect(isSpriteModel(flat)).toBe(true)
  })

  it("binds a block look's faces to the game's cube", async () => {
    const cube = (await cubeModel(loader, { north: 'acme:block/face' }))!
    expect(resolveTexture(cube.elements[0]!.faces!.north!.texture, cube.textures)).toBe(
      'acme:block/face',
    )
    expect(await cubeModel({ json: async () => null }, {})).toBeNull()
  })

  it('resolves the item and every placed model, leaving out the unknown', async () => {
    expect((await itemModel(loader, 'stick'))?.chain[0]).toBe('minecraft:item/stick')
    expect(await itemModel(loader, 'diamond')).toBeNull()
    // The fence's post and side models aren't in the fixture, so nothing resolves.
    expect(await placedModels(loader, 'oak_fence[north=true]')).toEqual([])
    const withPost = await placedModels(
      {
        json: async <T>(path: string) =>
          path.endsWith('oak_fence_post.json')
            ? (files['assets/minecraft/models/block/cube.json'] as T)
            : loader.json<T>(path),
      },
      'oak_fence[north=true,west=true]',
    )
    expect(withPost.map((it) => [it.placement.model, it.model.elements.length])).toEqual([
      ['minecraft:block/oak_fence_post', 1],
    ])
  })
})
