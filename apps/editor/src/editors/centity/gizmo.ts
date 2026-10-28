/**
 * The one place the editor turns a matrix back into file units: a gizmo
 * drag. Composition itself is never done here (that's `poseCentity`); this
 * only inverts it for one node, and its test checks that it agrees with the
 * composer's convention (`T · Rx · Ry · Rz · S`, three's 'XYZ' Euler order).
 */
import * as THREE from 'three'

const round = (value: number) => Math.round(value * 10000) / 10000 + 0

/** The local TRS a world matrix means under [parentWorld], in the file's units and Euler order. */
export function localChannels(world: THREE.Matrix4, parentWorld?: THREE.Matrix4) {
  const local = world.clone()
  if (parentWorld) local.premultiply(parentWorld.clone().invert())
  const position = new THREE.Vector3()
  const quaternion = new THREE.Quaternion()
  const scale = new THREE.Vector3()
  local.decompose(position, quaternion, scale)
  const euler = new THREE.Euler().setFromQuaternion(quaternion, 'XYZ')
  return {
    translation: [round(position.x), round(position.y), round(position.z)] as [
      number,
      number,
      number,
    ],
    rotation: [
      round(THREE.MathUtils.radToDeg(euler.x)),
      round(THREE.MathUtils.radToDeg(euler.y)),
      round(THREE.MathUtils.radToDeg(euler.z)),
    ] as [number, number, number],
    scale: [round(scale.x), round(scale.y), round(scale.z)] as [number, number, number],
  }
}
