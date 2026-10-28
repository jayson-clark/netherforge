/**
 * What an item stack looks like in a slot, resolved without drawing it:
 * - a pack item model (`itemModel` naming one of the project's packs): its
 *   GUI picture (`guiTexture`, else `texture`), a project file, for one
 *   drawn as a pack block (`block`), that look's faces as a cube, or for one
 *   with a model file (`model`), that model;
 * - else the client's own model for the item kind: a flat sprite (layers of
 *   textures) or a cuboid model to render (blocks, and anything with elements);
 * - else nothing, and the caller draws a placeholder.
 *
 * A pack reference that names nothing falls back to the vanilla look, which
 * is what the client does too.
 */
import type { ItemDef, ResourcePackFile, ResourcePacksPreview } from '@/core/format'
import { lookOf, type BlockFace } from '@/minecraft/block/look'
import {
  isSpriteModel,
  itemModel,
  spriteLayers,
  type AssetLoader,
  type RawModel,
} from '@netherforge/models/model'
import { resourcePackTexturePath } from '@/core/paths'
import { splitResourcePackRef } from './item'
import type { PackModels } from '@/core/store/resourcePacks'

export type IconSource =
  | { kind: 'project'; path: string }
  | { kind: 'block'; faces: Record<BlockFace, string> }
  /** A pack's own model file: [textures] binds its texture variables that are the pack's to project paths. */
  | { kind: 'shape'; model: RawModel; textures: Record<string, string> }
  | { kind: 'sprite'; textures: string[] }
  | { kind: 'model'; item: string }
  | { kind: 'none' }

/**
 * What an `itemModel` reference draws in a GUI from the project's packs: a
 * picture (a project path, or a package path for a package's pack), a block
 * look's faces, a model file, or null. [namespace] is the one the reference is written in,
 * [home] the project's (the packs' keys); [compiled] gives a block look's faces,
 * [models] each pack's model files.
 */
export function resourcePackItemLook(
  itemModel: string | undefined,
  packs: Record<string, ResourcePackFile>,
  compiled: ResourcePacksPreview,
  namespace: string,
  home: string = namespace,
  models: Record<string, PackModels> = {},
): Extract<IconSource, { kind: 'project' | 'block' | 'shape' }> | null {
  const ref = splitResourcePackRef('item_model', itemModel, namespace, home)
  if (!ref) return null
  const def = packs[ref[0]]?.items?.[ref[1]]
  if (!def) return null
  if (def.model) return packModelLook(ref[0], def.model, models)
  const picture = def.guiTexture ?? def.texture
  if (picture) return { kind: 'project', path: resourcePackTexturePath(ref[0], picture) }
  // The pack's own references are written in its namespace: a package's pack is keyed `ns:id`.
  const colon = ref[0].indexOf(':')
  const faces = lookOf(compiled, def.block, colon < 0 ? home : ref[0].slice(0, colon), home)?.faces
  return faces ? { kind: 'block', faces } : null
}

/**
 * What a block's `model` reference (a pack's block look) draws as its item in a GUI: the
 * look's faces as a cube, or its model file; null when it names no look that's built.
 */
export function resourcePackBlockLook(
  model: string | undefined,
  packs: Record<string, ResourcePackFile>,
  compiled: ResourcePacksPreview,
  namespace: string,
  home: string = namespace,
  models: Record<string, PackModels> = {},
): Extract<IconSource, { kind: 'block' | 'shape' }> | null {
  const faces = lookOf(compiled, model, namespace, home)?.faces
  if (faces) return { kind: 'block', faces }
  const ref = splitResourcePackRef('block_model', model, namespace, home)
  const file = ref && packs[ref[0]]?.blocks?.[ref[1]]?.model
  return ref && file ? packModelLook(ref[0], file, models) : null
}

/**
 * Model file [path] (under `models/`) of pack [pack] as a [IconSource] `shape`: its texture
 * variables that name a file under the pack's `textures/` bound to that file, as the built pack
 * binds them (format's `PackModels`); null when the file isn't there or doesn't parse.
 */
export function packModelLook(
  pack: string,
  path: string,
  models: Record<string, PackModels>,
): Extract<IconSource, { kind: 'shape' }> | null {
  const files = models[pack]
  const model = files?.files[path] as RawModel | undefined
  if (!files || !model) return null
  const textures: Record<string, string> = {}
  for (const [name, value] of Object.entries(model.textures ?? {})) {
    if (typeof value !== 'string' || value.startsWith('#') || value.includes(':')) continue
    if (files.textures.includes(`${value}.png`))
      textures[name] = resourcePackTexturePath(pack, `${value}.png`)
  }
  return { kind: 'shape', model, textures }
}

export async function iconSource(
  item: Pick<ItemDef, 'kind' | 'itemModel'>,
  packs: Record<string, ResourcePackFile>,
  compiled: ResourcePacksPreview,
  namespace: string,
  assets: AssetLoader | null,
  home: string = namespace,
  models: Record<string, PackModels> = {},
): Promise<IconSource> {
  const look = resourcePackItemLook(item.itemModel, packs, compiled, namespace, home, models)
  return look ?? vanillaIconSource(item.kind, assets)
}

/** What the client's own model for item [kind] draws in a GUI, without the project's packs. */
export async function vanillaIconSource(
  kind: string | undefined,
  assets: AssetLoader | null,
): Promise<IconSource> {
  if (!assets || !kind) return { kind: 'none' }
  const model = await itemModel(assets, kind)
  if (!model) return { kind: 'none' }
  if (isSpriteModel(model)) {
    const textures = spriteLayers(model)
    return textures.length > 0 ? { kind: 'sprite', textures } : { kind: 'none' }
  }
  return model.elements.length > 0 ? { kind: 'model', item: kind } : { kind: 'none' }
}
