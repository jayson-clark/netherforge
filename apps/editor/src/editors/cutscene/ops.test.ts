import { describe, expect, it } from 'vitest'
import { canonicalizeModel, cutsceneDirector, loadProject, type CutsceneFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  addCue,
  deleteKey,
  keysOf,
  lengthOf,
  lookDirection,
  topDown,
  moveKey,
  setCue,
  setEasing,
  setLength,
  setPosition,
  setRotation,
} from './ops'

const FLYBY = 'cutscenes/flyby.json'
const flyby = () => JSON.parse(exampleFiles[FLYBY]!) as CutsceneFile

/** The model's canonical text, which is how it would be saved. */
const canonical = (model: CutsceneFile) => canonicalizeModel('cutscene', FLYBY, model)

/** The problems loading the example with [model] in place of the flyby. */
function problems(model: CutsceneFile) {
  const files: Record<string, string | null> = {}
  for (const [path, text] of Object.entries(exampleFiles))
    files[path] = path.endsWith('.json') ? text : null
  files[FLYBY] = JSON.stringify(model)
  return loadProject(files, null).problems.filter((it) => it.file === FLYBY)
}

describe('cutscene ops', () => {
  it('the example is a good cutscene, written canonically', () => {
    expect(canonical(flyby()).text).toBe(exampleFiles[FLYBY])
    expect(problems(flyby())).toEqual([])
  })

  it('knows how long it is: the file says, or the last key or cue does', () => {
    expect(lengthOf(flyby())).toBe(6)
    const model = flyby()
    delete model.length
    expect(lengthOf(model)).toBe(6)
    model.cues!.push({ time: 8, event: 'late' })
    expect(lengthOf(model)).toBe(8)
    expect(lengthOf({})).toBe(0)
  })

  it('keys the camera, replacing a key at the same time and keeping time order', () => {
    const model = flyby()
    expect(setPosition(model, 1.5, [1, 2, 3])).toBe(1)
    expect(model.camera!.position!.map((it) => it.time)).toEqual([0, 1.5, 3, 6])
    expect(setPosition(model, 1.51, [9, 9, 9])).toBe(1)
    expect(model.camera!.position![1]).toEqual({ time: 1.5, value: [9, 9, 9] })
    // Keying a time that has an eased key keeps its easing.
    setPosition(model, 3, [0, 0, 0])
    expect(model.camera!.position![2]).toEqual({ time: 3, value: [0, 0, 0], easing: 'ease_in_out' })
    expect(setRotation(model, 4, 45, -10)).toBe(2)
    expect(model.camera!.rotation![2]).toEqual({ time: 4, yaw: 45, pitch: -10 })
    expect(problems(model)).toEqual([])
  })

  it('starts a camera from nothing', () => {
    const model: CutsceneFile = {}
    setPosition(model, 0, [0, 64, 0])
    setRotation(model, 0, 0, 0)
    expect(problems(model).map((it) => it.code)).toEqual(['cutscene.length'])
    setPosition(model, 2, [0, 64, 8])
    expect(problems(model)).toEqual([])
  })

  it('moves a key and says where it is after the re-sort, never before 0', () => {
    const model = flyby()
    expect(moveKey(model, 'position', 0, 4)).toBe(1)
    expect(model.camera!.position!.map((it) => it.time)).toEqual([3, 4, 6])
    expect(moveKey(model, 'rotation', 1, -2)).toBe(1)
    expect(model.camera!.rotation!.map((it) => it.time)).toEqual([0, 0, 6])
    expect(moveKey(model, 'cues', 0, 2.0004)).toBe(0)
    expect(model.cues![0]!.time).toBe(2)
  })

  it('deletes keys, and leaves nothing empty behind', () => {
    const model = flyby()
    deleteKey(model, 'rotation', 2)
    expect(keysOf(model, 'rotation')).toHaveLength(2)
    for (const index of [1, 0]) deleteKey(model, 'rotation', index)
    expect(model.camera!.rotation).toBeUndefined()
    for (const index of [2, 1, 0]) deleteKey(model, 'position', index)
    expect(model.camera).toBeUndefined()
    deleteKey(model, 'cues', 1)
    deleteKey(model, 'cues', 0)
    expect(model.cues).toBeUndefined()
    expect(canonical(model).text).not.toContain('camera')
  })

  it('sets an easing, linear by leaving it out', () => {
    const model = flyby()
    setEasing(model, 'position', 0, 'ease_in')
    expect(model.camera!.position![0]!.easing).toBe('ease_in')
    setEasing(model, 'position', 0, 'linear')
    expect(model.camera!.position![0]).not.toHaveProperty('easing')
    setEasing(model, 'rotation', 1, 'linear')
    expect(model.camera!.rotation![1]).not.toHaveProperty('easing')
  })

  it('adds and edits cues; a cue with nothing to do is a problem the editor shows', () => {
    const model = flyby()
    const index = addCue(model, 4)
    expect(model.cues![index]).toEqual({ time: 4, event: 'cue' })
    setCue(model, index, { event: 'boom', text: '<red>Boom', duration: 1.5 })
    expect(model.cues![index]).toEqual({ time: 4, event: 'boom', text: '<red>Boom', duration: 1.5 })
    expect(problems(model)).toEqual([])
    setCue(model, index, { event: '', text: undefined, duration: undefined })
    expect(model.cues![index]).toEqual({ time: 4 })
    expect(problems(model).map((it) => it.code)).toEqual(['cutscene.cue-empty'])
  })

  it('sets the length, leaving it to the keys when cleared', () => {
    const model = flyby()
    setLength(model, 10.0004)
    expect(model.length).toBe(10)
    setLength(model, undefined)
    expect(model).not.toHaveProperty('length')
    setLength(model, -1)
    expect(model).not.toHaveProperty('length')
  })

  it('looks the way the game does: yaw 0 south, 90 west, pitch down positive', () => {
    const close = (a: number[], b: number[]) => a.forEach((it, i) => expect(it).toBeCloseTo(b[i]!))
    close(lookDirection(0, 0), [0, 0, 1])
    close(lookDirection(90, 0), [-1, 0, 0])
    close(lookDirection(180, 0), [0, 0, -1])
    close(lookDirection(0, 90), [0, -1, 0])
  })
})

describe('the preview is format’s own camera path', () => {
  it('reads the camera at a time, held outside the keys, easing as the file says', () => {
    const director = cutsceneDirector('flyby', exampleFiles[FLYBY]!)
    const at = (time: number) => {
      const shot = director.shot(time)
      if (shot.type !== 'shot') throw new Error('no shot')
      return shot
    }
    expect(at(0)).toMatchObject({ length: 6, position: [0, 6, -12], yaw: 0, pitch: 25 })
    expect(at(3)).toMatchObject({ position: [10, 8, 0], yaw: 90, pitch: 20 })
    expect(at(99).position).toEqual([0, 10, 12])
    // Ease-in-out is halfway at the middle of its segment.
    expect(at(1.5).position[0]).toBeCloseTo(5)
    expect(at(1.5).yaw).toBeCloseTo(45)
  })

  it('draws the whole path, first and last point on its end keys', () => {
    const trail = cutsceneDirector('flyby', exampleFiles[FLYBY]!).trail(5)
    if (trail.type !== 'trail') throw new Error('no trail')
    expect(trail.points).toHaveLength(5)
    expect(trail.points[0]).toEqual([0, 6, -12])
    expect(trail.points[4]).toEqual([0, 10, 12])
  })

  it('says why it has no camera when the cutscene has errors', () => {
    const director = cutsceneDirector('broken', '{ "camera": { "position": [] } }')
    const shot = director.shot(0)
    expect(shot.type).toBe('failed')
    if (shot.type === 'failed')
      expect(shot.problems.map((it) => it.code)).toContain('cutscene.track-empty')
    expect(director.trail(4).type).toBe('failed')
    expect(cutsceneDirector('x', 'not json').shot(0).type).toBe('failed')
  })
})

describe('topDown', () => {
  it('fits the path seen from above into the square, keeping its shape', () => {
    expect(
      topDown(
        [
          [0, 64, 0],
          [10, 70, 0],
          [10, 64, 5],
        ],
        100,
        10,
      ),
    ).toEqual([
      [10, 30],
      [90, 30],
      [90, 70],
    ])
  })

  it('puts a camera that stays put in the middle', () => {
    expect(topDown([[3, 64, 3]], 50, 5)).toEqual([[25, 25]])
  })
})
