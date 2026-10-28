import { describe, expect, it } from 'vitest'
import { canonicalizeModel, loadProject, type ParticleEffectFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  addCurve,
  addEmitter,
  addKey,
  burstTicks,
  channelsFor,
  duplicateEmitter,
  kindOf,
  moveKey,
  removeCurve,
  removeEmitter,
  removeKey,
  renameEmitter,
  setEmission,
  setKeyEasing,
  setParticle,
  setShapeType,
  setSurface,
} from './ops'

const SHOCKWAVE = 'particles/shockwave/effect.json'
const shockwave = () => JSON.parse(exampleFiles[SHOCKWAVE]!) as ParticleEffectFile

/** The model's canonical text, which is how it would be saved. */
const canonical = (model: ParticleEffectFile) =>
  canonicalizeModel('particle_effect', SHOCKWAVE, model)

/** The problems loading the example with [model] in place of the shockwave. */
function problems(model: ParticleEffectFile) {
  const files: Record<string, string | null> = {}
  for (const [path, text] of Object.entries(exampleFiles))
    files[path] = path.endsWith('.json') ? text : null
  files[SHOCKWAVE] = JSON.stringify(model)
  return loadProject(files, null).problems.filter((it) => it.file === SHOCKWAVE)
}

describe('particle ops', () => {
  it('adds, renames, duplicates and removes emitters', () => {
    const file: ParticleEffectFile = { duration: 10 }
    expect(addEmitter(file, 'minecraft:flame')).toBe('emitter')
    expect(addEmitter(file, 'minecraft:flame')).toBe('emitter_2')
    renameEmitter(file, 'emitter', 'sparks')
    renameEmitter(file, 'sparks', 'emitter_2') // taken: nothing happens
    expect(Object.keys(file.emitters!).sort()).toEqual(['emitter_2', 'sparks'])
    expect(duplicateEmitter(file, 'sparks')).toBe('sparks_2')
    expect(file.emitters!.sparks_2).toEqual(file.emitters!.sparks)
    expect(file.emitters!.sparks_2).not.toBe(file.emitters!.sparks)
    for (const name of ['sparks', 'sparks_2', 'emitter_2']) removeEmitter(file, name)
    expect(file.emitters).toBeUndefined()
  })

  it('drops data fields a new particle does not take, and adds what it needs', () => {
    const file = shockwave()
    const ring = file.emitters!.ring!
    setParticle(ring, 'minecraft:flame', 'none')
    expect(ring.size).toBeUndefined()
    expect(ring.curves?.color).toBeUndefined()
    expect(ring.curves?.radius).toHaveLength(2)

    setParticle(ring, 'minecraft:block', 'block', { blockState: 'minecraft:stone' })
    expect(ring.blockState).toBe('minecraft:stone')
    setParticle(ring, 'minecraft:item', 'item', { item: 'minecraft:apple' })
    expect(ring.blockState).toBeUndefined()
    expect(ring.item).toEqual({ kind: 'minecraft:apple' })
    // Without a known kind, nothing is dropped.
    ring.color = '#ff0000'
    setParticle(ring, 'minecraft:mystery', null)
    expect(ring.color).toBe('#ff0000')
    expect(problems(file)).toEqual([])
  })

  it('switches burst and rate, keeping the file valid', () => {
    const file = shockwave()
    const sparks = file.emitters!.sparks!
    setEmission(sparks, 'burst')
    // The rate curve peaked at 6.
    expect(sparks).toMatchObject({ burst: 6 })
    expect(sparks.rate).toBeUndefined()
    expect(sparks.curves).toBeUndefined()
    const ring = file.emitters!.ring!
    setEmission(ring, 'rate')
    expect(ring).toMatchObject({ rate: 48 })
    expect(ring.every).toBeUndefined()
    expect(ring.burst).toBeUndefined()
    expect(problems(file)).toEqual([])
  })

  it('switches shapes, carrying the radius and dropping what no longer fits', () => {
    const file = shockwave()
    const debris = file.emitters!.debris!
    setShapeType(debris, 'ring')
    expect(debris.shape).toEqual({ type: 'ring', radius: 1.5 })
    debris.distribution = 'even'
    debris.spin = 5
    setShapeType(debris, 'sphere')
    expect(debris.shape).toEqual({ type: 'sphere', radius: 1.5 })
    expect(debris.distribution).toBeUndefined()
    expect(debris.spin).toBeUndefined()
    setSurface(debris, true)
    debris.distribution = 'even'
    setSurface(debris, false)
    expect(debris.distribution).toBeUndefined()
    setShapeType(debris, 'box')
    expect(debris.shape).toEqual({ type: 'box', size: [1, 1, 1] })
    setShapeType(debris, 'point')
    expect(debris.shape).toBeUndefined()

    // A ring driven by a radius curve keeps the curve, and gets no constant beside it.
    const ring = file.emitters!.ring!
    setShapeType(ring, 'disc')
    expect(ring.shape).toEqual({ type: 'disc' })
    expect(ring.distribution).toBeUndefined()
    setShapeType(ring, 'line')
    expect(ring.curves?.radius).toBeUndefined()
    expect(problems(file)).toEqual([])
  })

  it('moves a constant into a new curve and back when its last key goes', () => {
    const file = shockwave()
    const ring = file.emitters!.ring!
    expect(addCurve(ring, 'size', 4)).toBe(0)
    expect(ring.size).toBeUndefined()
    expect(ring.curves!.size).toEqual([{ time: 4, value: 1.5 }])
    expect(problems(file)).toEqual([])

    expect(addKey(ring, 'size', 2, 3)).toBe(0)
    expect(addKey(ring, 'size', 2, 2.5)).toBe(0) // same tick: updated, not added
    expect(ring.curves!.size).toEqual([
      { time: 2, value: 2.5 },
      { time: 4, value: 1.5 },
    ])
    expect(moveKey(ring, 'size', 0, 9.4)).toBe(1)
    setKeyEasing(ring, 'size', 1, 'ease_out')
    expect(ring.curves!.size![1]).toEqual({ time: 9, value: 2.5, easing: 'ease_out' })
    setKeyEasing(ring, 'size', 1, 'linear')
    expect(ring.curves!.size![1]!.easing).toBeUndefined()

    removeKey(ring, 'size', 1)
    removeKey(ring, 'size', 0)
    expect(ring.curves!.size).toBeUndefined()
    expect(ring.size).toBe(1.5)

    // The radius goes back onto the shape; a removed speed curve leaves its first value.
    removeCurve(ring, 'radius')
    expect(ring.shape).toEqual({ type: 'ring', radius: 0.5 })
    expect(problems(file)).toEqual([])
  })

  it('keeps rate beside its curve: rate says the emitter is rate-driven', () => {
    const file = shockwave()
    const sparks = file.emitters!.sparks!
    removeCurve(sparks, 'rate')
    expect(sparks.rate).toBe(3)
    addCurve(sparks, 'rate', 0)
    expect(sparks.rate).toBe(3)
    expect(sparks.curves!.rate).toEqual([{ time: 0, value: 3 }])
    expect(problems(file)).toEqual([])
  })

  it('offers only the channels an emitter allows', () => {
    const file = shockwave()
    expect(channelsFor(file.emitters!.ring!, 'dust')).toEqual(['size', 'speed', 'radius', 'color'])
    expect(channelsFor(file.emitters!.sparks!, 'none')).toEqual(['rate', 'speed', 'radius'])
    expect(channelsFor(file.emitters!.flash!, null)).toEqual(['size', 'speed', 'color'])
    expect(kindOf({ 'minecraft:dust': 'dust' }, 'dust')).toBe('dust')
    expect(kindOf(null, 'dust')).toBeNull()
  })

  it('lists burst ticks inside the window', () => {
    expect(burstTicks({ particle: 'x', burst: 1, every: 5, end: 20 }, 30)).toEqual([0, 5, 10, 15])
    expect(burstTicks({ particle: 'x', burst: 1, start: 3 }, 30)).toEqual([3])
    expect(burstTicks({ particle: 'x', rate: 1 }, 30)).toEqual([])
  })

  it('round-trips an edited effect through the canonical writer', () => {
    const file = shockwave()
    addCurve(file.emitters!.debris!, 'speed', 10)
    addKey(file.emitters!.debris!, 'speed', 0, 0.5)
    const text = canonical(file).text!
    expect(canonical(JSON.parse(text) as ParticleEffectFile).text).toBe(text)
    // Keys are written in time order whatever order they were added in.
    expect(text.indexOf('"time": 0')).toBeLessThan(text.indexOf('"time": 10'))
    expect(canonical(shockwave()).text).toBe(exampleFiles[SHOCKWAVE])
  })
})
