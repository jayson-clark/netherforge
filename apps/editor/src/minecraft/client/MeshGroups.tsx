/**
 * Draws a block mesh (`blockMesh.ts`): a `BufferGeometry` per texture group,
 * disposed with the component. Materials are shared per texture (one
 * material however many meshes use the texture); the map preview
 * passes its atlas's instead (`atlas.ts`).
 */
import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import type { ClientAssets } from './assets'
import type { MeshGroup } from './blockMesh'
import { loadTexture } from './render'

const materials = new WeakMap<ClientAssets, Map<string, THREE.Material>>()

/** The one material for a block texture: the client's picture, nearest-filtered, cut-out where transparent. */
export function blockMaterial(assets: ClientAssets, texture: string): THREE.Material {
  let cache = materials.get(assets)
  if (!cache) {
    cache = new Map()
    materials.set(assets, cache)
  }
  let material = cache.get(texture)
  if (!material) {
    material = new THREE.MeshBasicMaterial({
      map: loadTexture(assets, texture),
      vertexColors: true,
      alphaTest: 0.1,
      transparent: true,
    })
    cache.set(texture, material)
  }
  return material
}

export function MeshGroups({
  groups,
  assets,
  position,
  material,
}: {
  groups: MeshGroup[]
  assets: ClientAssets
  position?: [number, number, number]
  /** The material for a group's texture; `blockMaterial` when left out. */
  material?: (texture: string) => THREE.Material
}) {
  const geometries = useMemo(
    () =>
      groups.map((group) => {
        const geometry = new THREE.BufferGeometry()
        geometry.setAttribute('position', new THREE.BufferAttribute(group.positions, 3))
        geometry.setAttribute('uv', new THREE.BufferAttribute(group.uvs, 2))
        geometry.setAttribute('color', new THREE.BufferAttribute(group.colors, 3, true))
        geometry.setIndex(new THREE.BufferAttribute(group.indices, 1))
        geometry.computeBoundingSphere()
        return { texture: group.texture, geometry }
      }),
    [groups],
  )
  useEffect(() => () => geometries.forEach((it) => it.geometry.dispose()), [geometries])
  return (
    <group position={position}>
      {geometries.map((group) => (
        <mesh
          key={group.texture}
          geometry={group.geometry}
          material={material?.(group.texture) ?? blockMaterial(assets, group.texture)}
        />
      ))}
    </group>
  )
}
