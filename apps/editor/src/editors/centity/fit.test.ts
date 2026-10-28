import { describe, expect, it } from 'vitest'
import { loadProject, type CentityFile } from '@/core/format'
import { isProjectJson } from '@/core/paths'
import type { AssetLoader } from '@netherforge/models/model'
import { exampleFiles, exampleProject, examplePackages } from '@/testing/fixtures'
import { blockModelBoxes, canonicalBlockState, fitKey, fittedHitbox } from './fit'

/** A tiny client asset tree: a stair whose blockstate picks a rotated model. */
const assets: Record<string, unknown> = {
  'assets/minecraft/blockstates/oak_stairs.json': {
    variants: {
      'facing=east,half=bottom': { model: 'minecraft:block/oak_stairs' },
      'facing=south,half=bottom': { model: 'minecraft:block/oak_stairs', y: 90 },
      'facing=east,half=top': { model: 'minecraft:block/oak_stairs', x: 180 },
    },
  },
  'assets/minecraft/models/block/oak_stairs.json': {
    parent: 'minecraft:block/stairs',
    textures: {},
  },
  'assets/minecraft/models/block/stairs.json': {
    elements: [
      { from: [0, 0, 0], to: [16, 8, 16], faces: {} },
      { from: [8, 8, 0], to: [16, 16, 16], faces: {} },
    ],
  },
}
const loader: AssetLoader = {
  json: async <T>(path: string) => (assets[path] as T | undefined) ?? null,
}

describe('fit to display', () => {
  it('canonicalises block states like format does', () => {
    expect(canonicalBlockState('oak_stairs[half=top, facing=east]')).toBe(
      'minecraft:oak_stairs[facing=east,half=top]',
    )
    expect(canonicalBlockState('minecraft:stone')).toBe('minecraft:stone')
    expect(canonicalBlockState('stone[')).toBeNull()
    expect(fitKey({ type: 'text', text: 'Hi' })).toBe('text:Hi')
    expect(fitKey({ type: 'item', item: 'minecraft:stick' })).toBeNull()
  })

  it('computes a stair from the client assets, with defaults from game data', async () => {
    const boxes = await blockModelBoxes('minecraft:oak_stairs[facing=south]', {
      assets: loader,
      gameData: {
        minecraft: '26.3',
        blocks: { 'minecraft:oak_stairs': { defaults: { facing: 'north', half: 'bottom' } } },
      },
    })
    expect(boxes).toEqual([
      { min: [0, 0, 0], max: [1, 0.5, 1] },
      { min: [0, 0.5, 0.5], max: [1, 1, 1] },
    ])
  })

  it('prefers model boxes the cache already has', async () => {
    const boxes = await blockModelBoxes('minecraft:stone', {
      assets: null,
      gameData: {
        minecraft: '26.3',
        models: { 'minecraft:stone': [{ min: [0, 0, 0], max: [1, 1, 1] }] },
      },
    })
    expect(boxes).toEqual([{ min: [0, 0, 0], max: [1, 1, 1] }])
    expect(await blockModelBoxes('minecraft:stone', { assets: null, gameData: null })).toBeNull()
  })

  it('writes a hitbox the validator agrees is fresh', async () => {
    const path = 'centities/tower/centity.json'
    const tower = JSON.parse(exampleFiles[path]!) as CentityFile
    const top = tower.nodes!.cap!
    const display = { type: 'block' as const, block: 'oak_stairs[half=top,facing=east]' }
    const boxes = (await blockModelBoxes(display.block, { assets: loader, gameData: null }))!
    top.display = display
    top.hitbox = fittedHitbox({ raycast: true, shape: 'collision' }, display, boxes)
    expect(top.hitbox).toEqual({
      boxes: [
        { min: [0, 0.5, 0], max: [1, 1, 1] },
        { min: [0.5, 0, 0], max: [1, 0.5, 1] },
      ],
      fittedTo: 'minecraft:oak_stairs[facing=east,half=top]',
      raycast: true,
    })

    const files: Record<string, string | null> = {}
    for (const [file, text] of Object.entries(exampleProject))
      files[file] = isProjectJson(file) ? (text as string) : null
    files[path] = JSON.stringify(tower)
    expect(loadProject(files, null, examplePackages).problems).toEqual([])

    // Change the display afterwards and the fit goes stale.
    top.display = { type: 'block', block: 'minecraft:stone' }
    files[path] = JSON.stringify(tower)
    expect(loadProject(files, null, examplePackages).problems.map((it) => it.code)).toEqual([
      'centity.hitbox-stale',
    ])
  })
})
