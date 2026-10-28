import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type GameBlockFile } from '@/core/format'
import {
  addBlock,
  blockChoices,
  firstBlocks,
  minedByServer,
  previewEntries,
  removeBlock,
  setBlock,
} from './ops'

describe('game block ops', () => {
  it('adds, sets and removes blocks, keeping the list', () => {
    const file: GameBlockFile = { blocks: ['minecraft:deepslate_bricks'] }
    addBlock(file, '')
    setBlock(file, 1, ' #minecraft:logs ')
    expect(file.blocks).toEqual(['minecraft:deepslate_bricks', '#minecraft:logs'])
    removeBlock(file, 0)
    removeBlock(file, 0)
    expect(file.blocks).toEqual([])
    // The canonical writer keeps the (required) empty list.
    expect(canonicalizeModel('game_block', 'game_blocks/b.json', file).text).toContain(
      '"blocks": []',
    )
  })

  it('suggests tags first, then blocks', () => {
    expect(blockChoices(['minecraft:stone', 'minecraft:dirt'], ['minecraft:logs'])).toEqual([
      '#minecraft:logs',
      'minecraft:dirt',
      'minecraft:stone',
    ])
  })

  it('says when the server mines the blocks', () => {
    expect(minedByServer({ blocks: [], drops: 'brick' })).toBe(false)
    expect(minedByServer({ blocks: [], requiresTool: false })).toBe(true)
    expect(minedByServer({ blocks: [], hardness: 0 })).toBe(true)
  })

  it('draws ids as themselves and tags as their blocks', () => {
    const tags = { 'minecraft:logs': ['minecraft:oak_log', 'minecraft:birch_log'] }
    const entries = previewEntries(
      ['stone', '#logs', '#minecraft:nope', '', 'stone', 3, 'minecraft:oak_log'],
      tags,
    )
    expect(entries).toEqual([
      { entry: 'stone', blocks: ['minecraft:stone'] },
      { entry: '#logs', blocks: ['minecraft:oak_log', 'minecraft:birch_log'] },
      { entry: '#minecraft:nope', blocks: null },
      { entry: 'minecraft:oak_log', blocks: ['minecraft:oak_log'] },
    ])
    expect(firstBlocks(entries, 4)).toEqual([
      'minecraft:stone',
      'minecraft:oak_log',
      'minecraft:birch_log',
    ])
    expect(firstBlocks(entries, 2)).toEqual(['minecraft:stone', 'minecraft:oak_log'])
  })
})
