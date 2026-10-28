import { describe, expect, it } from 'vitest'
import { framing, roundPose, samePose, type CameraPose } from './camera'
import { createViewports, type SceneSpec } from './scenes'

const spec = (label: string): SceneSpec => ({
  content: label,
  home: { position: [3, 2, 4], target: [0, 0, 0] },
  camera: null,
  onCamera: () => {},
})

describe('the viewports store', () => {
  it('keeps a scene while its viewport is off screen, and draws the one attached', () => {
    const store = createViewports()
    const slot = {} as HTMLElement
    store.getState().show('a.json', spec('a'))
    store.getState().attach('a.json', slot)
    expect(store.getState()).toMatchObject({ active: 'a.json', slot })

    // A tab switch: a's viewport goes, b's comes; a's scene stays, undrawn.
    store.getState().detach('a.json')
    store.getState().show('b.json', spec('b'))
    store.getState().attach('b.json', slot)
    expect(Object.keys(store.getState().scenes)).toEqual(['a.json', 'b.json'])
    expect(store.getState().active).toBe('b.json')

    // A late detach of a viewport that isn't on screen changes nothing.
    store.getState().detach('a.json')
    expect(store.getState().active).toBe('b.json')
  })

  it('drops the scenes whose tab closed, and the active one with its slot', () => {
    const store = createViewports()
    store.getState().show('a.json', spec('a'))
    store.getState().show('b.json', spec('b'))
    store.getState().attach('b.json', {} as HTMLElement)
    store.getState().failed('b.json', 'bad mesh')
    const before = store.getState().scenes
    store.getState().retain(() => true)
    expect(store.getState().scenes).toBe(before)

    store.getState().retain((key) => key === 'a.json')
    expect(Object.keys(store.getState().scenes)).toEqual(['a.json'])
    expect(store.getState()).toMatchObject({ active: null, slot: null, errors: {} })
  })

  it('gives a failed scene another try when its viewport comes back, not on every render', () => {
    const store = createViewports()
    store.getState().show('a.json', spec('a'))
    store.getState().failed('a.json', 'bad mesh')
    store.getState().show('a.json', spec('a again'))
    expect(store.getState().errors).toEqual({ 'a.json': 'bad mesh' })
    store.getState().attach('a.json', {} as HTMLElement)
    expect(store.getState().errors).toEqual({})
    expect(store.getState().attempts).toEqual({ 'a.json': 1 })
  })
})

describe('camera poses', () => {
  it('are the same within rounding, and null only matches null', () => {
    const pose: CameraPose = { position: [1, 2, 3], target: [0, 0.5, 0] }
    const read: CameraPose = { position: [1.0000001, 2, 3], target: [0, 0.5, 0] }
    expect(samePose(pose, read)).toBe(true)
    expect(samePose(null, null)).toBe(true)
    expect(samePose(null, read)).toBe(false)
    expect(roundPose({ position: [1.23456, -0.0001, 2], target: [0, 0, 0] })).toEqual({
      position: [1.235, 0, 2],
      target: [0, 0, 0],
    })
  })

  it('frame a box from the front right and above, looking at its middle', () => {
    expect(framing([0, 0, 0], [3, 2, 3])).toEqual({
      position: [1.5 + 3 * 1.1 + 2, 1 + 3 * 0.8 + 2, 1.5 + 3 * 1.4 + 2],
      target: [1.5, 1, 1.5],
    })
  })
})
