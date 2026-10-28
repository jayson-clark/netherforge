import { describe, expect, it } from 'vitest'
import { vanillaLooks, vanillaToWrite, VANILLA_FILE } from './vanilla'

const tripwire = { variants: { 'attached=false': { model: 'minecraft:block/tripwire_ns' } } }
const index = {
  minecraft: '26.3',
  blockstates: [],
  items: [],
  fonts: [],
  sprites: ['hud/heart/full'],
  textures: {
    'block/stone': [16, 16],
    'block/dirt': [16, 16],
    'gui/container/inventory': [256, 256],
    'gui/container/odd': [1, 'x'],
  },
}

describe('the game’s own looks', () => {
  it('reads the client’s blockstate file of each block that needs one and the HUD’s sprites, and nothing without an import', async () => {
    const files: Record<string, unknown> = {
      'assets/minecraft/blockstates/tripwire.json': tripwire,
      'index.json': index,
    }
    const assets = { json: async <T>(path: string) => (files[path] ?? null) as T | null }
    expect(await vanillaLooks(assets)).toEqual({
      blockstates: { 'minecraft:tripwire': tripwire },
      sprites: ['hud/heart/full'],
      textures: { 'gui/container/inventory': [256, 256] },
      unknownTextures: [],
      models: [],
      unknownModels: [],
    })
    expect(await vanillaLooks({ json: async () => null }, [], ['block/red_mushroom'])).toEqual({
      blockstates: {},
      sprites: [],
      textures: {},
      unknownTextures: [],
      models: [],
      unknownModels: [],
    })
  })

  it('keeps every screen’s texture and only the other ones the project redraws, and says which the game lacks', async () => {
    const assets = {
      json: async <T>(path: string) => (path === 'index.json' ? index : null) as T | null,
    }
    const looks = await vanillaLooks(assets, ['item/nothing', 'block/stone'])
    expect(looks.textures).toEqual({
      'block/stone': [16, 16],
      'gui/container/inventory': [256, 256],
    })
    expect(looks.unknownTextures).toEqual(['item/nothing'])
    // An import that listed only the screens' textures can't say what the game lacks.
    const old = { ...index, textures: { 'gui/container/inventory': [256, 256] } }
    const oldAssets = {
      json: async <T>(path: string) => (path === 'index.json' ? old : null) as T | null,
    }
    expect((await vanillaLooks(oldAssets, ['block/stone'])).unknownTextures).toEqual([])
  })

  it('says which of the models the project redraws the client has', async () => {
    const files: Record<string, unknown> = {
      'index.json': index,
      'assets/minecraft/models/block/red_mushroom.json': { parent: 'block/cross' },
    }
    const assets = { json: async <T>(path: string) => (files[path] ?? null) as T | null }
    const looks = await vanillaLooks(assets, [], ['block/red_mushrom', 'block/red_mushroom'])
    expect(looks.models).toEqual(['block/red_mushroom'])
    expect(looks.unknownModels).toEqual(['block/red_mushrom'])
    const text = vanillaToWrite('26.3', looks, undefined)
    expect(text).toContain('"models": ["block/red_mushroom"]')
    expect(text).toContain('"unknownModels": ["block/red_mushrom"]')
  })

  it('writes the file only when it would change, and never without something to write from', () => {
    expect(VANILLA_FILE).toBe('client/vanilla.json')
    const looks = {
      blockstates: { 'minecraft:tripwire': tripwire },
      sprites: ['hud/heart/full'],
      textures: { 'gui/container/inventory': [256, 256] },
      unknownTextures: ['item/nothing'],
      models: [],
      unknownModels: [],
    }
    const text = vanillaToWrite('26.3', looks, undefined)
    expect(text).toContain('"minecraft": "26.3"')
    expect(text).toContain('tripwire_ns')
    expect(text).toContain('hud/heart/full')
    expect(text).toContain('gui/container/inventory')
    expect(text).toContain('"unknownTextures"')
    expect(vanillaToWrite('26.3', looks, text!)).toBeNull()
    expect(
      vanillaToWrite(
        '26.3',
        {
          blockstates: {},
          sprites: [],
          textures: {},
          unknownTextures: [],
          models: [],
          unknownModels: [],
        },
        undefined,
      ),
    ).toBeNull()
    expect(vanillaToWrite(null, looks, undefined)).toBeNull()
  })
})
