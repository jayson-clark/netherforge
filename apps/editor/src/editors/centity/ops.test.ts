import { describe, expect, it } from 'vitest'
import type { CentityFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  addAnimation,
  addNode,
  childrenOf,
  deleteAnimation,
  deleteKey,
  deleteNode,
  duplicateNode,
  moveKey,
  renameNode,
  reparent,
  setChannel,
  setEasing,
  setKeyframe,
} from './ops'

const tower = () => JSON.parse(exampleFiles['centities/tower/centity.json']!) as CentityFile

// The example's tower: `base` and `keep` stacked, and `turret`, an empty pivot, with `cap` and `flag` under it. `spin`
// turns the turret; `flutter` waves the flag.
describe('centity ops', () => {
  it('adds nodes with fresh names', () => {
    const file = tower()
    expect(addNode(file, 'turret')).toBe('node')
    expect(addNode(file, 'turret')).toBe('node_2')
    expect(childrenOf(file, 'turret')).toEqual(['cap', 'flag', 'node', 'node_2'])
  })

  it('renames a node with its children and tracks', () => {
    const file = tower()
    renameNode(file, 'turret', 'pivot')
    expect(file.nodes!.pivot).toBeDefined()
    expect(file.nodes!.turret).toBeUndefined()
    expect(file.nodes!.cap!.parent).toBe('pivot')
    expect(file.nodes!.flag!.parent).toBe('pivot')
    expect(Object.keys(file.animations!.spin!.tracks!)).toEqual(['pivot'])
  })

  it('deletes a subtree and its tracks', () => {
    const file = tower()
    deleteNode(file, 'turret')
    expect(Object.keys(file.nodes!)).toEqual(['base', 'keep'])
    expect(file.animations!.spin!.tracks).toEqual({})
    expect(file.animations!.flutter!.tracks).toEqual({})
  })

  it('duplicates a node with its subtree beside it, without its tracks', () => {
    const file = tower()
    expect(duplicateNode(file, 'turret')).toBe('turret_2')
    expect(file.nodes!.turret_2).toEqual(file.nodes!.turret)
    expect(file.nodes!.cap_2!.parent).toBe('turret_2')
    expect(file.nodes!.flag_2!.parent).toBe('turret_2')
    expect(childrenOf(file, 'turret_2')).toEqual(['cap_2', 'flag_2'])
    expect(Object.keys(file.animations!.spin!.tracks!)).toEqual(['turret'])
    // A copy of a copy is named from the original, not turret_2_2.
    expect(duplicateNode(file, 'turret_2')).toBe('turret_3')
    expect(duplicateNode(file, 'nothing')).toBeNull()
  })

  it('deletes clips, and the animations key with the last one', () => {
    const file = tower()
    const clip = addAnimation(file)
    deleteAnimation(file, clip)
    expect(Object.keys(file.animations!)).toEqual(['flutter', 'spin'])
    deleteAnimation(file, 'flutter')
    deleteAnimation(file, 'spin')
    expect(file.animations).toBeUndefined()
  })

  it('refuses a cycle', () => {
    const file = tower()
    expect(reparent(file, 'turret', 'flag')).toBe(false)
    expect(reparent(file, 'flag', 'keep')).toBe(true)
    expect(reparent(file, 'flag', null)).toBe(true)
    expect(file.nodes!.flag!.parent).toBeUndefined()
  })

  it('leaves default transform values out', () => {
    const file = tower()
    const cap = file.nodes!.cap!
    setChannel(cap, 'translation', [0, 0, 0])
    expect(cap.transform).toBeUndefined()
    setChannel(cap, 'scale', [2, 2, 2])
    expect(cap.transform).toEqual({ scale: [2, 2, 2] })
  })

  it('edits keyframes', () => {
    const clip = tower().animations!.spin!
    setKeyframe(clip, 'turret', 'rotation', 0.25, [0, 60, 0])
    expect(clip.tracks!.turret!.rotation!.map((k) => k.time)).toEqual([0, 0.25, 0.6, 0.9, 1.5])
    setKeyframe(clip, 'turret', 'rotation', 0.25, [0, 70, 0])
    expect(clip.tracks!.turret!.rotation![1]!.value).toEqual([0, 70, 0])

    const index = moveKey(clip, 'turret', 'rotation', 1, 1.2)
    expect(index).toBe(3)
    expect(clip.tracks!.turret!.rotation![3]!.time).toBe(1.2)

    setEasing(clip, 'turret', 'rotation', 3, 'step')
    expect(clip.tracks!.turret!.rotation![3]!.easing).toBe('step')
    setEasing(clip, 'turret', 'rotation', 3, 'linear')
    expect(clip.tracks!.turret!.rotation![3]!.easing).toBeUndefined()

    for (let i = 4; i >= 0; i -= 1) deleteKey(clip, 'turret', 'rotation', i)
    expect(clip.tracks).toEqual({})
  })
})
