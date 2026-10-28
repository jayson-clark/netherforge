import { describe, expect, it } from 'vitest'
import { withReplacedTextures, type ClientAssets } from './assets'

const game: ClientAssets = {
  version: '1.21.11',
  key: '1.21.11',
  json: () => Promise.resolve(null),
  textureUrl: (id) => `game:${id}`,
}

describe('withReplacedTextures', () => {
  it("draws the game's textures a pack redraws with its pictures, and the rest as the game ships them", () => {
    const assets = withReplacedTextures(game, { 'block/stone': 'project:stone.png' })
    expect(assets.textureUrl('minecraft:block/stone')).toBe('project:stone.png')
    expect(assets.textureUrl('block/stone')).toBe('project:stone.png')
    expect(assets.textureUrl('minecraft:block/dirt')).toBe('game:minecraft:block/dirt')
    expect(assets.textureUrl('other:block/stone')).toBe('game:other:block/stone')
  })

  it('is the same asset set while the pictures are, and another when they change', () => {
    const one = withReplacedTextures(game, { 'block/stone': 'a' })
    expect(withReplacedTextures(game, { 'block/stone': 'a' })).toBe(one)
    const two = withReplacedTextures(game, { 'block/stone': 'b' })
    expect(two).not.toBe(one)
    expect(two.key).not.toBe(one.key)
    expect(withReplacedTextures(game, {})).toBe(game)
  })
})
