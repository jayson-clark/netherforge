/**
 * Items, blocks and resource pack looks as geometry in a display context, and drawn to pictures without a browser:
 * the client's own models (a sprite's layers, or a model's faces through its display transform, as the editor's
 * icons and viewport draw them), a resource pack's model file with its own textures, a pack block's look as a
 * cube, and a block state as a block display holds it. Assets come from an [IconAssets]: the client's files, and
 * whatever else a texture id names (a project's own files).
 */
import * as THREE from 'three'
import { bakeFaces, displayMatrix, placementMatrix, tintFor, type BakedFace } from './geometry.ts'
import {
  cubeModel,
  isSpriteModel,
  itemModel,
  packModel,
  placedModels,
  spriteLayers,
  type AssetLoader,
  type RawModel,
  type ResolvedModel,
} from './model.ts'
import { layered, rasterize, type Picture } from './raster.ts'

/** The client's JSON (`AssetLoader`), and the picture a texture id names: a client texture, or any other the caller's. */
export interface IconAssets extends AssetLoader {
  texture(id: string): Promise<Picture | null>
}

/** Faces in block units, and where a display context puts them (the model's transform, centred on its entity). */
export interface LookGeometry {
  faces: BakedFace[]
  matrix: THREE.Matrix4
  /** A sprite's layers (texture ids) when it's one: an icon draws them flat rather than as a quad. */
  sprite?: string[]
}

/** A sprite item as a flat one-pixel-thick quad, front and back (as the editor's `buildSprite`). */
function spriteFaces(layers: string[]): BakedFace[] {
  return layers.flatMap((texture) =>
    bakeFaces(
      [
        {
          from: [0, 0, 7.5],
          to: [16, 16, 8.5],
          shade: false,
          faces: { south: { texture, uv: [0, 0, 16, 16] }, north: { texture, uv: [16, 0, 0, 16] } },
        },
      ],
      {},
    ),
  )
}

/** [model] as an item in display [context] (`gui`, `fixed`, `none`...). */
export function modelGeometry(
  model: ResolvedModel,
  context: string,
  tint?: THREE.Color,
): LookGeometry {
  const matrix = displayMatrix(model.display[context])
  if (isSpriteModel(model)) {
    const sprite = spriteLayers(model)
    return { faces: spriteFaces(sprite), matrix, sprite }
  }
  return { faces: bakeFaces(model.elements, model.textures, { tint }), matrix }
}

/** The client's own item [kind] in display [context]; null when it has no model. */
export async function itemGeometry(
  assets: IconAssets,
  kind: string,
  context: string,
): Promise<LookGeometry | null> {
  const model = await itemModel(assets, kind)
  return model ? modelGeometry(model, context, tintFor(kind)) : null
}

/** A resource pack's own model file as an item in display [context] ([packModel]). */
export async function packModelGeometry(
  assets: IconAssets,
  model: RawModel,
  bound: Record<string, string>,
  context: string,
): Promise<LookGeometry> {
  return modelGeometry(await packModel(assets, model, bound), context)
}

/** A pack block's look as its item ([cubeModel]). */
export async function cubeGeometry(
  assets: IconAssets,
  faces: Record<string, string>,
  context: string,
): Promise<LookGeometry | null> {
  const cube = await cubeModel(assets, faces)
  return cube ? modelGeometry(cube, context) : null
}

/** A block state as a block display holds it: the block's cube from its corner, rotated as its state says. */
export async function blockStateGeometry(assets: IconAssets, state: string): Promise<LookGeometry> {
  const tint = tintFor(state)
  const faces = (await placedModels(assets, state)).flatMap(({ model, placement }) =>
    bakeFaces(model.elements, model.textures, { tint, transform: placementMatrix(placement) }),
  )
  return { faces, matrix: new THREE.Matrix4() }
}

/** Every texture [faces] (or a sprite's layers) names, loaded once. */
export async function texturesOf(
  assets: IconAssets,
  ids: Iterable<string>,
): Promise<Map<string, Picture | null>> {
  const names = [...new Set(ids)]
  return new Map(
    await Promise.all(names.map(async (name) => [name, await assets.texture(name)] as const)),
  )
}

/** [look] as the GUI draws it, [size] pixels square: a sprite's layers flat, anything else through the GUI's camera. */
export async function lookPicture(
  assets: IconAssets,
  look: LookGeometry | null,
  size: number,
): Promise<Picture | null> {
  if (!look) return null
  if (look.sprite) {
    const layers = [...(await texturesOf(assets, look.sprite)).values()].filter(
      (it): it is Picture => it != null,
    )
    return layers.length > 0 ? layered(layers, size) : null
  }
  if (look.faces.length === 0) return null
  const pictures = await texturesOf(
    assets,
    look.faces.map((it) => it.texture),
  )
  return rasterize(look.faces, look.matrix, (name) => pictures.get(name) ?? null, size)
}
