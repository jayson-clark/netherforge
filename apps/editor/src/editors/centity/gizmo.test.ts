import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { poseCentity, type CentityFile } from '@/core/format'
import { localChannels } from './gizmo'

describe('gizmo decomposition', () => {
  it('inverts the composer for a nested, rotated, scaled node', () => {
    const file: CentityFile = {
      nodes: {
        root: { transform: { translation: [1, 2, 3], rotation: [10, 20, 30], scale: [2, 2, 2] } },
        child: {
          parent: 'root',
          transform: {
            translation: [0.5, 1, -0.25],
            rotation: [15, -40, 70],
            scale: [0.5, 1, 1.5],
          },
        },
      },
    }
    const pose = poseCentity('test', JSON.stringify(file), null, 0)
    if (pose.type !== 'posed') throw new Error('expected a pose')
    const world = (name: string) =>
      new THREE.Matrix4().fromArray(pose.nodes.find((it) => it.name === name)!.matrix)

    const child = localChannels(world('child'), world('root'))
    expect(child.translation).toEqual([0.5, 1, -0.25])
    child.rotation.forEach((value, i) =>
      expect(value).toBeCloseTo(file.nodes!.child!.transform!.rotation![i]!, 3),
    )
    child.scale.forEach((value, i) =>
      expect(value).toBeCloseTo(file.nodes!.child!.transform!.scale![i]!, 3),
    )

    const root = localChannels(world('root'))
    expect(root.translation).toEqual([1, 2, 3])
    root.rotation.forEach((value, i) => expect(value).toBeCloseTo([10, 20, 30][i]!, 3))
  })
})
