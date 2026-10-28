/**
 * Minecraft's model format, resolved from the client assets the user
 * imported: block state → blockstate file → model(s) → parent chain → a flat
 * list of cuboid elements with every texture variable bound.
 *
 * Pure apart from the injected [AssetLoader], so it's unit-tested with a map
 * of fake asset files. Vanilla rules implemented here:
 * - a model inherits its parent's elements unless it declares its own;
 * - texture maps merge down the chain, the child winning;
 * - a face's `#variable` resolves against that merged map, possibly through
 *   other variables;
 * - a blockstate's `variants` pick one model by property match; `multipart`
 *   adds every part whose `when` holds.
 */

/** Fetches a JSON asset by its path in the client jar (`assets/minecraft/...`); null if absent. */
export interface AssetLoader {
  json<T>(path: string): Promise<T | null>
}

export type FaceName = 'down' | 'up' | 'north' | 'south' | 'west' | 'east'
export const FACE_NAMES: FaceName[] = ['down', 'up', 'north', 'south', 'west', 'east']

export type V3 = [number, number, number]

export interface RawFace {
  texture: string
  uv?: [number, number, number, number]
  rotation?: number
  tintindex?: number
  cullface?: string
}

/** One axis and an angle, or (since the game takes it, 1.21.11 on) any angles about all three, turned X, Y, then Z. */
export interface ElementRotation {
  origin: V3
  axis?: 'x' | 'y' | 'z'
  angle?: number
  x?: number
  y?: number
  z?: number
  rescale?: boolean
}

export interface RawElement {
  from: V3
  to: V3
  rotation?: ElementRotation
  shade?: boolean
  faces?: Partial<Record<FaceName, RawFace>>
}

export interface DisplayEntry {
  rotation?: V3
  translation?: V3
  scale?: V3
}

export interface RawModel {
  parent?: string
  textures?: Record<string, string>
  elements?: RawElement[]
  display?: Record<string, DisplayEntry>
}

export interface ResolvedModel {
  /** This model and its ancestors, nearest first. */
  chain: string[]
  textures: Record<string, string>
  elements: RawElement[]
  display: Record<string, DisplayEntry>
}

/** One model drawn for a block state, with the blockstate's whole-model rotation. */
export interface Placement {
  model: string
  x: number
  y: number
}

/** Guards against a pack whose parents loop. */
const MAX_DEPTH = 16

/** `minecraft:block/stone` → `{ namespace: 'minecraft', path: 'block/stone' }`. */
export function splitId(id: string): { namespace: string; path: string } {
  const colon = id.indexOf(':')
  return colon < 0
    ? { namespace: 'minecraft', path: id }
    : { namespace: id.slice(0, colon), path: id.slice(colon + 1) }
}

export const modelAssetPath = (ref: string) => {
  const { namespace, path } = splitId(ref)
  return `assets/${namespace}/models/${path}.json`
}

export const textureAssetPath = (ref: string) => {
  const { namespace, path } = splitId(ref)
  return `assets/${namespace}/textures/${path}.png`
}

export async function resolveModel(
  loader: AssetLoader,
  ref: string,
  depth = 0,
): Promise<ResolvedModel | null> {
  if (depth > MAX_DEPTH) return null
  // Builtin parents (`builtin/generated`, `builtin/entity`) have no file.
  if (splitId(ref).path.startsWith('builtin/')) {
    return { chain: [ref], textures: {}, elements: [], display: {} }
  }
  const raw = await loader.json<RawModel>(modelAssetPath(ref))
  if (!raw) return null
  const parent = raw.parent ? await resolveModel(loader, raw.parent, depth + 1) : null
  return {
    chain: [ref, ...(parent?.chain ?? [])],
    textures: { ...parent?.textures, ...raw.textures },
    elements: raw.elements ?? parent?.elements ?? [],
    display: { ...parent?.display, ...raw.display },
  }
}

/** Follows `#variable` references to a texture id; null when the chain dead-ends. */
export function resolveTexture(reference: string, textures: Record<string, string>): string | null {
  let current: unknown = reference
  for (let step = 0; step <= MAX_DEPTH; step += 1) {
    // A model file may hold anything: a value that isn't a texture's name draws nothing.
    if (typeof current !== 'string') return null
    if (!current.startsWith('#')) return current
    const next = textures[current.slice(1)]
    if (next === undefined) return null
    current = next
  }
  return null
}

/** Splits `minecraft:oak_stairs[facing=east]` into id and properties. Lenient: a viewport input. */
export function parseBlockState(text: string): { id: string; properties: Record<string, string> } {
  const trimmed = text.trim()
  const open = trimmed.indexOf('[')
  const rawId = (open < 0 ? trimmed : trimmed.slice(0, open)).trim()
  const id = rawId.includes(':') ? rawId : `minecraft:${rawId}`
  const properties: Record<string, string> = {}
  if (open >= 0) {
    const close = trimmed.indexOf(']', open)
    for (const pair of trimmed.slice(open + 1, close < 0 ? undefined : close).split(',')) {
      const equals = pair.indexOf('=')
      if (equals > 0) properties[pair.slice(0, equals).trim()] = pair.slice(equals + 1).trim()
    }
  }
  return { id, properties }
}

interface Variant {
  model: string
  x?: number
  y?: number
}

type Condition = Record<string, unknown>

interface BlockStateFile {
  variants?: Record<string, Variant | Variant[]>
  multipart?: { when?: Condition; apply: Variant | Variant[] }[]
}

const valueMatches = (expected: string, actual: string) =>
  expected === actual || expected.split('|').includes(actual)

function conditionMatches(condition: Condition, properties: Record<string, string>): boolean {
  if (Array.isArray(condition.OR))
    return (condition.OR as Condition[]).some((it) => conditionMatches(it, properties))
  if (Array.isArray(condition.AND))
    return (condition.AND as Condition[]).every((it) => conditionMatches(it, properties))
  return Object.entries(condition).every(([name, expected]) => {
    const actual = properties[name]
    return actual !== undefined && valueMatches(String(expected), actual)
  })
}

/**
 * The variant key a state satisfies most specifically. Properties the state
 * doesn't name are ignored, so a bare id lands on the first variant listed.
 */
export function bestVariantKey(
  keys: string[],
  properties: Record<string, string>,
): string | undefined {
  let best: string | undefined
  let bestScore = -1
  for (const key of keys) {
    let score = 0
    let agrees = true
    for (const pair of key ? key.split(',') : []) {
      const equals = pair.indexOf('=')
      if (equals <= 0) continue
      const actual = properties[pair.slice(0, equals)]
      if (actual === undefined) continue
      if (!valueMatches(pair.slice(equals + 1), actual)) {
        agrees = false
        break
      }
      score += 1
    }
    if (agrees && score > bestScore) {
      best = key
      bestScore = score
    }
  }
  return best
}

const first = (variant: Variant | Variant[]): Variant | undefined =>
  Array.isArray(variant) ? variant[0] : variant

/**
 * The models that draw [state]. [defaults] (from game data, when known) fill
 * properties the state leaves out, as the game does when it places a block.
 */
export async function blockPlacements(
  loader: AssetLoader,
  state: string,
  defaults: Record<string, string> = {},
): Promise<Placement[]> {
  const parsed = parseBlockState(state)
  const properties = { ...defaults, ...parsed.properties }
  const { namespace, path } = splitId(parsed.id)
  const file = await loader.json<BlockStateFile>(`assets/${namespace}/blockstates/${path}.json`)
  if (!file) return []
  const toPlacement = (variant: Variant | undefined): Placement | null =>
    variant?.model ? { model: variant.model, x: variant.x ?? 0, y: variant.y ?? 0 } : null

  if (file.variants) {
    const key = bestVariantKey(Object.keys(file.variants), properties)
    const placement = key === undefined ? null : toPlacement(first(file.variants[key]!))
    return placement ? [placement] : []
  }
  if (file.multipart) {
    return file.multipart
      .filter((part) => !part.when || conditionMatches(part.when, properties))
      .map((part) => toPlacement(first(part.apply)))
      .filter((it): it is Placement => it !== null)
  }
  return []
}

interface ItemModelEntry {
  type?: string
  model?: string | ItemModelEntry
  models?: ItemModelEntry[]
  fallback?: ItemModelEntry
  on_false?: ItemModelEntry
  on_true?: ItemModelEntry
  cases?: { model: ItemModelEntry }[]
  entries?: { model: ItemModelEntry }[]
}

/** Item definitions branch on state (a bow's pull); one representative model is enough to preview. */
function pickItemModel(entry: ItemModelEntry | undefined, depth = 0): string | null {
  if (!entry || depth > MAX_DEPTH) return null
  if (typeof entry.model === 'string') return entry.model
  return (
    pickItemModel(entry.model, depth + 1) ??
    pickItemModel(entry.fallback, depth + 1) ??
    pickItemModel(entry.on_false, depth + 1) ??
    pickItemModel(entry.on_true, depth + 1) ??
    pickItemModel(entry.models?.[0], depth + 1) ??
    pickItemModel(entry.cases?.[0]?.model, depth + 1) ??
    pickItemModel(entry.entries?.[0]?.model, depth + 1)
  )
}

/** The model that draws an item: from `items/<id>.json` (1.21.4+), else `item/<id>`. */
export async function itemModelRef(loader: AssetLoader, itemId: string): Promise<string> {
  const { namespace, path } = splitId(itemId.includes(':') ? itemId : `minecraft:${itemId}`)
  const definition = await loader.json<{ model?: ItemModelEntry }>(
    `assets/${namespace}/items/${path}.json`,
  )
  return pickItemModel(definition?.model) ?? `${namespace}:item/${path}`
}

/** A flat sprite (generated item model) rather than a cuboid mesh. */
export const isSpriteModel = (model: ResolvedModel) =>
  model.elements.length === 0 && model.textures.layer0 !== undefined

export function spriteLayers(model: ResolvedModel): string[] {
  const layers: string[] = []
  for (let index = 0; ; index += 1) {
    const layer = model.textures[`layer${index}`]
    if (layer === undefined) break
    const resolved = resolveTexture(layer, model.textures)
    if (resolved) layers.push(resolved)
  }
  return layers
}

/** The client's own item [itemId]'s model; null when it has none. */
export async function itemModel(
  loader: AssetLoader,
  itemId: string,
): Promise<ResolvedModel | null> {
  return resolveModel(loader, await itemModelRef(loader, itemId))
}

/**
 * One of a resource pack's own model files (`models/<path>.json`, as written), resolved as the game resolves one:
 * its parent chain from the client's assets, with [bound] (texture variable → a texture the caller draws: the pack's
 * files) on top.
 */
export async function packModel(
  loader: AssetLoader,
  model: RawModel,
  bound: Record<string, string>,
): Promise<ResolvedModel> {
  const parent = model.parent ? await resolveModel(loader, model.parent) : null
  return {
    chain: parent?.chain ?? [],
    textures: { ...parent?.textures, ...model.textures, ...bound },
    elements: model.elements ?? parent?.elements ?? [],
    display: { ...parent?.display, ...model.display },
  }
}

/** A pack block's look as its item: the game's `block/cube` with [faces] (face → texture) bound; null without it. */
export async function cubeModel(
  loader: AssetLoader,
  faces: Record<string, string>,
): Promise<ResolvedModel | null> {
  const cube = await resolveModel(loader, 'minecraft:block/cube')
  return cube ? { ...cube, textures: { ...cube.textures, ...faces } } : null
}

/** Every model that draws [state] ([blockPlacements]), resolved, with where it's placed; one that isn't known is left out. */
export async function placedModels(
  loader: AssetLoader,
  state: string,
  defaults: Record<string, string> = {},
): Promise<{ model: ResolvedModel; placement: Placement }[]> {
  const placed = await Promise.all(
    (await blockPlacements(loader, state, defaults)).map(async (placement) => {
      const model = await resolveModel(loader, placement.model)
      return model ? { model, placement } : null
    }),
  )
  return placed.filter((it) => it !== null)
}
