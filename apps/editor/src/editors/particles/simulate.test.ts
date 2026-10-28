import { describe, expect, it } from 'vitest'
import { particleEffectSampler, type EffectSpawn, type EffectStepResult } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  advance,
  DRAG,
  LIFETIME,
  opacity,
  seededRandom,
  Simulator,
  spritesOf,
  type StepSource,
} from './simulate'
import { sizeOf, spriteTexture, tintOf } from './sprites'

const spawn = (patch: Partial<EffectSpawn> = {}): EffectSpawn => ({
  emitter: 'a',
  particle: 'minecraft:flame',
  position: [0, 0, 0],
  count: 0,
  offset: [0, 1, 0],
  speed: 0.5,
  ...patch,
})

/** A source that spawns [perTick] exact particles on every tick and counts its resets. */
function fakeSource(perTick: number) {
  let tick = 0
  const source: StepSource & { resets: number; steps: number } = {
    resets: 0,
    steps: 0,
    step(): EffectStepResult {
      source.steps += 1
      const spawns = Array.from({ length: perTick }, () => spawn())
      return { type: 'stepped', tick: tick++, spawns, radii: {}, finished: false }
    },
    reset() {
      source.resets += 1
      tick = 0
    },
  }
  return source
}

describe('preview simulation', () => {
  it('makes one sprite per exact spawn and count sprites per scattered one', () => {
    const random = seededRandom(1)
    const [exact] = spritesOf(spawn(), random)
    expect(exact!.velocity).toEqual([0, 0.5, 0])
    const scattered = spritesOf(spawn({ count: 5, offset: [0.5, 0, 0], speed: 0.2 }), random)
    expect(scattered).toHaveLength(5)
    for (const sprite of scattered) {
      // Spread only on X; speed in a random direction.
      expect(sprite.position[1]).toBe(0)
      expect(Math.hypot(...sprite.velocity)).toBeCloseTo(0.2)
    }
  })

  it('moves sprites by their velocity with drag, fades them, and drops them at the end of life', () => {
    let sprites = spritesOf(spawn(), seededRandom(1))
    sprites = advance(sprites)
    expect(sprites[0]!.position).toEqual([0, 0.5, 0])
    expect(sprites[0]!.velocity[1]).toBeCloseTo(0.5 * DRAG)
    sprites = advance(sprites)
    expect(sprites[0]!.position[1]).toBeCloseTo(0.5 + 0.5 * DRAG)
    expect(opacity(sprites[0]!)).toBe(1)
    for (let age = 2; age < LIFETIME - 1; age += 1) sprites = advance(sprites)
    expect(sprites[0]!.age).toBe(LIFETIME - 1)
    expect(opacity(sprites[0]!)).toBeCloseTo(1 / 5)
    expect(advance(sprites)).toEqual([])
  })

  it('steps forwards a tick at a time and replays from the start on a seek back', () => {
    const source = fakeSource(2)
    const simulator = new Simulator(source)
    expect(simulator.at(0, false).sprites).toHaveLength(2)
    expect(simulator.at(3, false).sprites).toHaveLength(8)
    expect(source.steps).toBe(4)
    // Sprites live LIFETIME ticks, so the count levels off.
    expect(simulator.at(LIFETIME + 5, false).sprites).toHaveLength(2 * LIFETIME)
    simulator.at(1, false)
    expect(source.resets).toBe(1)
    expect(simulator.at(1, false).sprites).toHaveLength(4)
  })

  it('replays deterministically, scattered sprites included', () => {
    const text = exampleFiles['particles/shockwave/effect.json']!
    const first = new Simulator(particleEffectSampler('shockwave', text, 1)).at(12, false)
    const second = new Simulator(particleEffectSampler('shockwave', text, 1)).at(12, false)
    expect(first.sprites.length).toBeGreaterThan(0)
    expect(second.sprites).toEqual(first.sprites)
    expect(first.radii.ring).toBeGreaterThan(0.5)
  })

  it('says why an effect with errors shows nothing', () => {
    const frame = new Simulator(particleEffectSampler('bad', '{"duration": 0}', 1)).at(0, false)
    expect(frame.sprites).toEqual([])
    expect(frame.problems.length).toBeGreaterThan(0)
  })
})

describe('preview sprites', () => {
  const files: Record<string, unknown> = {
    'assets/minecraft/particles/flame.json': { textures: ['minecraft:flame'] },
    'assets/minecraft/blockstates/stone.json': {
      variants: { '': { model: 'minecraft:block/stone' } },
    },
    'assets/minecraft/models/block/stone.json': {
      parent: 'minecraft:block/cube_all',
      textures: { all: 'minecraft:block/stone' },
    },
    'assets/minecraft/models/block/cube_all.json': { textures: { particle: '#all' } },
    'assets/minecraft/models/item/apple.json': { textures: { layer0: 'minecraft:item/apple' } },
  }
  const loader = { json: <T>(path: string) => Promise.resolve((files[path] as T) ?? null) }

  it('finds the sprite a particle, a block particle and an item particle draw', async () => {
    expect(await spriteTexture(loader, 'flame', null)).toBe('minecraft:particle/flame')
    expect(await spriteTexture(loader, 'minecraft:nope', null)).toBeNull()
    expect(
      await spriteTexture(loader, 'minecraft:block', { type: 'block', state: 'minecraft:stone' }),
    ).toBe('minecraft:block/stone')
    expect(
      await spriteTexture(loader, 'minecraft:item', { type: 'item', item: { kind: 'apple' } }),
    ).toBe('minecraft:item/apple')
  })

  it('tints and sizes dust by its data', () => {
    expect(tintOf({ type: 'dust', color: 0xff8000, size: 2 })).toBe('#ff8000')
    expect(tintOf(null)).toBeNull()
    expect(sizeOf({ type: 'dust', color: 0, size: 2 })).toBe(2 * sizeOf(null))
  })
})
