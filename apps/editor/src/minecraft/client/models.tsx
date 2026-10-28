/**
 * Block and item models in a three.js scene, from the client's assets
 * (`render.ts` bakes and caches them): a block state as its model's
 * elements, an item as its model in a display context. Without client
 * assets, or for a model that doesn't resolve, a placeholder box in a
 * colour of its own, never an error. The centity viewport and its
 * thumbnails draw displays with these.
 */
import * as THREE from 'three'
import { Edges } from '@react-three/drei'
import { useAsync } from '@/ui/useAsync'
import type { ClientAssets } from './assets'
import type { BuiltGroup } from '@netherforge/models/geometry'
import { parseBlockState } from '@netherforge/models/model'
import {
  blockGeometry,
  blockLookGeometry,
  itemGeometry,
  loadTexture,
  packModelGeometry,
  pictureGeometry,
  type ItemGeometry,
} from './render'
import type { IconSource } from '@/minecraft/item/icon'

/** A stable colour per id, so placeholders are told apart. */
function placeholderColor(id: string): THREE.Color {
  let hash = 0
  for (const c of id) hash = (hash * 31 + c.charCodeAt(0)) | 0
  return new THREE.Color().setHSL(((hash >>> 0) % 360) / 360, 0.35, 0.5)
}

/** A box standing in for a model that can't be drawn. */
export function Placeholder({
  id,
  size = [1, 1, 1],
  offset = [0.5, 0.5, 0.5],
}: {
  id: string
  size?: [number, number, number]
  offset?: [number, number, number]
}) {
  return (
    <mesh position={offset}>
      <boxGeometry args={size} />
      <meshLambertMaterial color={placeholderColor(id)} />
      <Edges color="#000" threshold={15} />
    </mesh>
  )
}

/** Distinguishes asset sets (versions, redrawn textures) in async keys. */
const assetsKey = (assets: ClientAssets | null) => assets?.key ?? 'none'

function Groups({
  groups,
  assets,
  side,
}: {
  groups: BuiltGroup[]
  assets: ClientAssets
  side?: THREE.Side
}) {
  return (
    <>
      {groups.map((group, index) => (
        <mesh key={index} geometry={group.geometry}>
          <meshBasicMaterial
            map={loadTexture(assets, group.texture)}
            vertexColors
            alphaTest={0.1}
            transparent
            side={side ?? THREE.FrontSide}
          />
        </mesh>
      ))}
    </>
  )
}

/** A block state's model, its corner at the origin. [defaults] fill properties the state leaves out. */
export function BlockModel({
  state,
  assets,
  defaults,
}: {
  state: string
  assets: ClientAssets | null
  defaults: Record<string, string> | undefined
}) {
  const groups = useAsync(`${assetsKey(assets)}|${state}|${JSON.stringify(defaults ?? {})}`, () =>
    assets ? blockGeometry(assets, state, defaults) : null,
  )
  if (!assets || !groups || groups.length === 0)
    return <Placeholder id={parseBlockState(state).id} />
  return <Groups groups={groups} assets={assets} />
}

/** An item's model as the game holds it in display [context] (`none`, `gui`, `head`…), centred. */
export function ItemModel({
  item,
  context,
  assets,
}: {
  item: string
  context: string
  assets: ClientAssets | null
}) {
  const geometry = useAsync<ItemGeometry | null>(`${assetsKey(assets)}|${item}|${context}`, () =>
    assets ? itemGeometry(assets, item, context) : null,
  )
  if (!assets || !geometry)
    return <Placeholder id={item} size={[0.5, 0.5, 1 / 16]} offset={[0, 0, 0]} />
  return (
    <group matrixAutoUpdate={false} matrix={geometry.matrix}>
      <Groups groups={geometry.groups} assets={assets} side={THREE.DoubleSide} />
    </group>
  )
}

/**
 * One of the project's resource pack looks as an item in display [context], centred:
 * a model file, a block look's cube or a flat picture ([look], as `resourcePackItemLook`
 * resolves it; [urlOf] makes a project path a URL). A placeholder for [id] when there's
 * no such look or nothing to draw it with.
 */
export function PackLookModel({
  id,
  look,
  urlOf,
  context,
  assets,
}: {
  id: string
  look: Extract<IconSource, { kind: 'project' | 'block' | 'shape' }> | null
  urlOf: (path: string) => string
  context: string
  assets: ClientAssets | null
}) {
  const urls = (paths: Record<string, string>) =>
    Object.fromEntries(Object.entries(paths).map(([name, path]) => [name, urlOf(path)]))
  const geometry = useAsync<ItemGeometry | null>(
    `${assetsKey(assets)}|pack|${JSON.stringify(look)}|${context}|${look?.kind === 'project' ? urlOf(look.path) : ''}`,
    async () => {
      if (!assets || !look) return null
      switch (look.kind) {
        case 'shape':
          return packModelGeometry(assets, look.model, urls(look.textures), context)
        case 'block':
          return blockLookGeometry(assets, urls(look.faces), context)
        case 'project':
          return pictureGeometry(urlOf(look.path))
      }
    },
  )
  if (!assets || !geometry) return <Placeholder id={id} size={[0.5, 0.5, 0.5]} offset={[0, 0, 0]} />
  return (
    <group matrixAutoUpdate={false} matrix={geometry.matrix}>
      <Groups groups={geometry.groups} assets={assets} side={THREE.DoubleSide} />
    </group>
  )
}
