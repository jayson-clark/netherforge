import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type BiomeFile } from '@/core/format'
import {
  addFeature,
  addSpawn,
  biomeChoices,
  changeSound,
  changeSpawn,
  moveFeature,
  removeFeature,
  removeSpawn,
  setAmbient,
  setClimate,
  setColor,
  setSpawnCost,
  toggleSound,
} from './ops'

const canonical = (biome: BiomeFile) =>
  canonicalizeModel('biome', 'biomes/grove.json', biome).text ?? ''

describe('biome ops', () => {
  it('sets climate and colours, leaving nothing behind when they are emptied', () => {
    const biome: BiomeFile = {}
    setClimate(biome, 'temperature', 0.7)
    setColor(biome, 'sky', '#f2a7c3')
    setColor(biome, 'grassModifier', 'swamp')
    expect(biome).toEqual({
      climate: { temperature: 0.7 },
      colors: { sky: '#f2a7c3', grassModifier: 'swamp' },
    })
    setClimate(biome, 'temperature', undefined)
    setColor(biome, 'sky', undefined)
    setColor(biome, 'grassModifier', undefined)
    expect(biome).toEqual({})
  })

  it('turns sounds on and off and changes their settings', () => {
    const biome: BiomeFile = {}
    setAmbient(biome, 'minecraft:ambient.cave')
    toggleSound(biome, 'mood', 'minecraft:ambient.cave')
    changeSound(biome, 'mood', (mood) => (mood.tickDelay = 3000))
    toggleSound(biome, 'music', 'minecraft:music.game')
    changeSound(biome, 'music', (music) => (music.minDelay = 600))
    expect(biome.sounds).toEqual({
      ambient: 'minecraft:ambient.cave',
      mood: { sound: 'minecraft:ambient.cave', tickDelay: 3000 },
      music: { sound: 'minecraft:music.game', minDelay: 600 },
    })
    // A sound that's off has no settings to change.
    changeSound(biome, 'additions', (additions) => (additions.chance = 1))
    expect(biome.sounds?.additions).toBeUndefined()
    toggleSound(biome, 'mood', undefined)
    toggleSound(biome, 'music', undefined)
    setAmbient(biome, undefined)
    expect(biome.sounds).toBeUndefined()
  })

  it('adds, changes and removes spawns by category, and costs by entity', () => {
    const biome: BiomeFile = {}
    addSpawn(biome, 'animal', 'minecraft:sheep')
    addSpawn(biome, 'animal', 'minecraft:rabbit')
    changeSpawn(biome, 'animal', 0, (spawn) => (spawn.group = { min: 2, max: 4 }))
    changeSpawn(biome, 'animal', 1, (spawn) => (spawn.group = { min: undefined }))
    expect(biome.spawns).toEqual({
      animal: [
        { entity: 'minecraft:sheep', group: { min: 2, max: 4 } },
        { entity: 'minecraft:rabbit' },
      ],
    })
    setSpawnCost(biome, 'minecraft:sheep', { charge: 0.7, energyBudget: 0.15 })
    expect(canonical(biome)).toContain('"energyBudget": 0.15')
    removeSpawn(biome, 'animal', 0)
    removeSpawn(biome, 'animal', 0)
    setSpawnCost(biome, 'minecraft:sheep', undefined)
    expect(biome).toEqual({})
  })

  it('keeps each step its own ordered list of features', () => {
    const biome: BiomeFile = {}
    addFeature(biome, 'vegetal_decoration', 'minecraft:trees_cherry')
    addFeature(biome, 'vegetal_decoration', 'minecraft:flower_cherry')
    addFeature(biome, 'lakes', 'minecraft:lake_lava_surface')
    moveFeature(biome, 'vegetal_decoration', 1, -1)
    moveFeature(biome, 'vegetal_decoration', 0, -1)
    expect(biome.features?.vegetal_decoration).toEqual([
      'minecraft:flower_cherry',
      'minecraft:trees_cherry',
    ])
    // The steps are written in the game's order, whichever was added first.
    const text = canonical(biome)
    expect(text.indexOf('"lakes"')).toBeLessThan(text.indexOf('"vegetal_decoration"'))
    removeFeature(biome, 'lakes', 0)
    removeFeature(biome, 'vegetal_decoration', 0)
    removeFeature(biome, 'vegetal_decoration', 0)
    expect(biome).toEqual({})
  })

  it("suggests the project's biomes by id before the game's", () => {
    expect(biomeChoices(['ruby_grove', 'ash_flats'], ['minecraft:plains'])).toEqual([
      'ash_flats',
      'ruby_grove',
      'minecraft:plains',
    ])
  })
})
