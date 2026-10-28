/**
 * Pure edits to `pack.json` (docs/format/resource-pack.md) and the pack's textures.
 * Every entry is keyed (`<pack>/<key>` everywhere else), and textures are
 * paths under the pack's `textures/` folder. The naming rules (which paths
 * can be textures and sounds, which fields name a texture) are format's
 * generated constants.
 */
import { setKey } from '@/core/draft'
import {
  RESOURCE_PACK_SOUND_EXTENSION,
  RESOURCE_PACK_SOUND_FILE_PATTERN,
  RESOURCE_PACK_TEXTURE_EXTENSION,
  RESOURCE_PACK_TEXTURE_FIELDS,
  type BlockModelDef,
  type EquipmentAssetDef,
  type GlyphDef,
  type HudBarDef,
  type HudDef,
  type HudElement,
  type HudIconsDef,
  type HudTextDef,
  type ItemModelDef,
  type ResourcePackFile,
  type RefKind,
  type SkinDef,
  type SoundDef,
  type TooltipDef,
} from '@/core/format'
import { inPackage, resourceOf, resourcePackFilePath, SOUNDS, TEXTURES } from '@/core/paths'

export type EntryKind = 'skins' | 'glyphs' | 'items' | 'tooltips' | 'equipment' | 'blocks'
export const ENTRY_KINDS: EntryKind[] = [
  'skins',
  'glyphs',
  'items',
  'tooltips',
  'equipment',
  'blocks',
]

/**
 * What the pack editor can pick: an entry, a texture (its path under `textures/`), a sound (its event key), a
 * game texture it redraws (`game`, by the game's name: `block/stone`), a game model it redraws (`gameModel`,
 * `block/lantern`) or a HUD element (`hud`, by its key: a bar, a row of icons or a line of text).
 */
export interface ResourcePackPick {
  kind: EntryKind | 'textures' | 'sounds' | 'game' | 'gameModel' | 'hud'
  key: string
}

/** What a reference to an entry of each kind is (format's `RefKind`), for following a renamed key. */
export const ENTRY_REFS: Record<EntryKind, RefKind> = {
  skins: 'skin',
  glyphs: 'glyph',
  items: 'item_model',
  tooltips: 'tooltip',
  equipment: 'equipment',
  blocks: 'block_model',
}

export type EntryDef =
  SkinDef | GlyphDef | ItemModelDef | TooltipDef | EquipmentAssetDef | BlockModelDef

/** The fields of each kind that hold texture paths. */
export const TEXTURE_FIELDS: Record<EntryKind, readonly string[]> = RESOURCE_PACK_TEXTURE_FIELDS

/** Lower case, so a picture or sound with the wrong case still shows up and format can say what's wrong with it. */
const hasExtension = (path: string, extension: string) => path.toLowerCase().endsWith(extension)

/**
 * The pack a file of one is in, as the packs store keys it: its id (`gems`), or for a package's (opened read-only
 * from Dependencies) `ns:id` (`library:gems`), so its pictures and sounds are found in the package's folder.
 */
export function packOf(path: string): string {
  const resource = resourceOf(path)
  return resource ? inPackage(resource.pkg, resource.id) : (path.split('/')[1] ?? '')
}

/**
 * Texture paths (under `textures/`) of a pack, from a file list: the project's, or for a package's pack
 * (`library:gems`) the package's ([filesIn]). Format judges their names.
 */
export function texturesOf(files: string[], pack: string): string[] {
  const prefix = resourcePackFilePath(pack, `${TEXTURES}/`)
  return files
    .filter((it) => it.startsWith(prefix) && hasExtension(it, RESOURCE_PACK_TEXTURE_EXTENSION))
    .map((it) => it.slice(prefix.length))
    .sort()
}

/** Sound files (paths under `sounds/`) of a pack, from a file list as [texturesOf]'s. Format judges their names. */
export function soundFilesOf(files: string[], pack: string): string[] {
  const prefix = resourcePackFilePath(pack, `${SOUNDS}/`)
  return files
    .filter((it) => it.startsWith(prefix) && hasExtension(it, RESOURCE_PACK_SOUND_EXTENSION))
    .map((it) => it.slice(prefix.length))
    .sort()
}

/** The file a sound event's key plays by default: `menu/open` → `menu/open.ogg`. */
export const soundFileOf = (key: string) => `${key}${RESOURCE_PACK_SOUND_EXTENSION}`

/** The event a sound file is, or null when its name can't be one (format reports why). */
export const soundKeyOf = (file: string): string | null =>
  RESOURCE_PACK_SOUND_FILE_PATTERN.test(file)
    ? file.slice(0, -RESOURCE_PACK_SOUND_EXTENSION.length)
    : null

/**
 * Every sound event of a pack, as format builds them: each usable file is one
 * (its path without `.ogg`), and `sounds` in pack.json refines one or adds
 * one. Sorted by key.
 */
export function soundEvents(
  pack: ResourcePackFile,
  files: string[],
): { key: string; files: string[] }[] {
  const events = new Map<string, string[]>()
  for (const file of files) {
    const key = soundKeyOf(file)
    if (key !== null) events.set(key, [file])
  }
  for (const [key, def] of Object.entries(pack.sounds ?? {})) {
    events.set(key, def.files ?? [soundFileOf(key)])
  }
  return [...events.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([key, list]) => ({ key, files: list }))
}

/**
 * Sets (or with undefined, removes) one property of a sound's entry in
 * pack.json; an entry left empty is removed, since the file alone is the event.
 */
export function setSoundValue<K extends keyof SoundDef>(
  pack: ResourcePackFile,
  key: string,
  name: K,
  value: SoundDef[K] | undefined,
): void {
  const sounds = { ...(pack.sounds ?? {}) }
  const def: SoundDef = { ...(sounds[key] ?? {}) }
  setKey(def, name, value)
  if (Object.keys(def).length === 0) delete sounds[key]
  else sounds[key] = def
  if (Object.keys(sounds).length === 0) delete pack.sounds
  else pack.sounds = sounds
}

/** Where an imported sound goes under `sounds/`: `ui/click.ogg`. */
export function soundImportPath(fileName: string, folder: string): string {
  const base = hasExtension(fileName, RESOURCE_PACK_SOUND_EXTENSION)
    ? fileName.slice(0, -RESOURCE_PACK_SOUND_EXTENSION.length)
    : fileName
  const name = soundFileOf(idFrom(base) || 'sound')
  const clean = folder
    .split('/')
    .map((it) => idFrom(it))
    .filter(Boolean)
    .join('/')
  return clean ? `${clean}/${name}` : name
}

/** Whether [bytes] start like an Ogg file (`OggS`). */
export function isOgg(bytes: Uint8Array): boolean {
  return (
    bytes.length >= 4 &&
    bytes[0] === 0x4f &&
    bytes[1] === 0x67 &&
    bytes[2] === 0x67 &&
    bytes[3] === 0x53
  )
}

/** Every texture path an entry refers to. */
export function entryTextures(kind: EntryKind, def: EntryDef): string[] {
  return TEXTURE_FIELDS[kind]
    .map((key) => (def as Record<string, unknown>)[key])
    .filter((it): it is string => typeof it === 'string')
}

/**
 * The textures a pack's model files draw with: each plain path in a model's `textures` (`dart/steel`, the pack's
 * `textures/dart/steel.png`). A `#variable` is another of the model's own, and a namespaced one (`minecraft:block/stone`)
 * the game's, so neither is the pack's.
 */
export function modelTextures(
  models: Record<string, { textures?: Record<string, unknown> }>,
): string[] {
  const out = new Set<string>()
  for (const model of Object.values(models)) {
    for (const value of Object.values(model.textures ?? {})) {
      if (typeof value === 'string' && !value.startsWith('#') && !value.includes(':')) {
        out.add(`${value}.png`)
      }
    }
  }
  return [...out]
}

/** Textures nothing in the pack refers to: no entry, no HUD element, no model file ([models], by path under `models/`). */
export function unusedTextures(
  pack: ResourcePackFile,
  textures: string[],
  models: Record<string, { textures?: Record<string, unknown> }> = {},
): string[] {
  const used = new Set<string>(modelTextures(models))
  for (const kind of ENTRY_KINDS) {
    for (const def of Object.values(pack[kind] ?? {})) {
      for (const texture of entryTextures(kind, def)) used.add(texture)
    }
  }
  for (const texture of hudTextures(pack)) used.add(texture)
  for (const texture of Object.values(pack.hud?.replace ?? {})) used.add(texture)
  for (const texture of Object.values(pack.textures ?? {})) used.add(texture)
  return textures.filter((it) => !used.has(it))
}

/** Draws the game's texture [name] (`block/stone`) with the pack's [texture], or with undefined, the game's own again. */
export function setGameTexture(
  pack: ResourcePackFile,
  name: string,
  texture: string | undefined,
): void {
  const textures = { ...(pack.textures ?? {}) }
  if (texture === undefined) delete textures[name]
  else textures[name] = texture
  setKey(pack, 'textures', Object.keys(textures).length > 0 ? textures : undefined)
}

/** Where a copy of the game's texture [name] goes under `textures/`, to draw over: `vanilla/block/stone.png`. */
export const gameTextureCopyPath = (name: string) =>
  `vanilla/${name}${RESOURCE_PACK_TEXTURE_EXTENSION}`

/** The pictures of the pack's HUD elements: its bars' and rows of icons'. */
export function hudTextures(pack: ResourcePackFile): string[] {
  const hud = pack.hud
  if (!hud) return []
  return [
    ...Object.values(hud.bars ?? {}).flatMap((bar) => [
      bar.full,
      bar.empty,
      ...(bar.half ? [bar.half] : []),
    ]),
    ...Object.values(hud.icons ?? {}).flatMap((row) => Object.values(row.icons)),
  ]
}

/** A new entry of [kind] drawn from [texture]. */
export function newEntry(kind: EntryKind, texture: string): EntryDef {
  if (kind === 'equipment') return { humanoid: texture }
  return kind === 'tooltips' ? { background: texture } : { texture }
}

const entries = (pack: ResourcePackFile, kind: EntryKind) =>
  ({ ...(pack[kind] ?? {}) }) as Record<string, EntryDef>

function setEntries(pack: ResourcePackFile, kind: EntryKind, next: Record<string, EntryDef>) {
  if (Object.keys(next).length === 0) delete pack[kind]
  else (pack as Record<EntryKind, Record<string, EntryDef>>)[kind] = next
}

export function addEntry(
  pack: ResourcePackFile,
  kind: EntryKind,
  key: string,
  def: EntryDef,
): void {
  setEntries(pack, kind, { ...entries(pack, kind), [key]: def })
}

export function removeEntry(pack: ResourcePackFile, kind: EntryKind, key: string): void {
  const next = entries(pack, kind)
  delete next[key]
  setEntries(pack, kind, next)
}

/** Renames an entry (the canonical writer sorts keys, so order doesn't matter). */
export function renameEntry(
  pack: ResourcePackFile,
  kind: EntryKind,
  from: string,
  to: string,
): void {
  const next = entries(pack, kind)
  const def = next[from]
  if (!def || from === to || next[to]) return
  delete next[from]
  next[to] = def
  setEntries(pack, kind, next)
}

/** Copies an entry under the next free key (`shop_2`) and returns it; null when there's no such entry. */
export function duplicateEntry(
  pack: ResourcePackFile,
  kind: EntryKind,
  key: string,
): string | null {
  const def = entries(pack, kind)[key]
  if (!def) return null
  const copy = freeEntryKey(pack, kind, key)
  addEntry(pack, kind, copy, structuredClone(def))
  return copy
}

/** A key not yet used by [kind]: `base`, `base_2`, … */
export function freeEntryKey(pack: ResourcePackFile, kind: EntryKind, base: string): string {
  const taken = new Set(Object.keys(pack[kind] ?? {}))
  const clean = idFrom(base) || kind.slice(0, -1)
  if (!taken.has(clean)) return clean
  for (let n = 2; ; n += 1) if (!taken.has(`${clean}_${n}`)) return `${clean}_${n}`
}

/** A file name as a key or path segment (format's `ID_PATTERN`): `My Banner.PNG` → `my_banner`. */
export function idFrom(name: string): string {
  const base = hasExtension(name, RESOURCE_PACK_TEXTURE_EXTENSION)
    ? name.slice(0, -RESOURCE_PACK_TEXTURE_EXTENSION.length)
    : name
  return base
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 64)
}

/** Where an imported PNG goes under `textures/`: `gui/my_banner.png`. */
export function importPath(fileName: string, folder: string): string {
  const name = `${idFrom(fileName) || 'texture'}${RESOURCE_PACK_TEXTURE_EXTENSION}`
  const clean = folder
    .split('/')
    .map((it) => idFrom(it))
    .filter(Boolean)
    .join('/')
  return clean ? `${clean}/${name}` : name
}

/** The folder a kind's pictures usually live in, for imports. */
export const DEFAULT_FOLDER: Record<EntryKind, string> = {
  skins: 'gui',
  glyphs: 'glyph',
  items: 'item',
  tooltips: 'tooltip',
  equipment: 'armor',
  blocks: 'block',
}

/**
 * What the pack editor selects for a file in the pack's folder ([rest] is
 * its path inside it): a picture under `textures/` or a sound under
 * `sounds/` that can be a sound event selects its gallery entry; anything
 * else is nothing the editor shows.
 */
export function selectionForFile(rest: string): ResourcePackPick | null {
  if (rest.startsWith(`${TEXTURES}/`))
    return { kind: 'textures', key: rest.slice(TEXTURES.length + 1) }
  const key = rest.startsWith(`${SOUNDS}/`) ? soundKeyOf(rest.slice(SOUNDS.length + 1)) : null
  return key === null ? null : { kind: 'sounds', key }
}

/** Width and height from a PNG's header, or null if [bytes] isn't a PNG. */
export function pngSize(bytes: Uint8Array): { width: number; height: number } | null {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length < 24 || signature.some((b, i) => bytes[i] !== b)) return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  return { width: view.getUint32(16), height: view.getUint32(20) }
}

/** Draws the game's model [name] (`block/lantern`) with the pack's model file [model], or with undefined, the game's own again. */
export function setGameModel(
  pack: ResourcePackFile,
  name: string,
  model: string | undefined,
): void {
  const models = { ...(pack.models ?? {}) }
  if (model === undefined) delete models[name]
  else models[name] = model
  setKey(pack, 'models', Object.keys(models).length > 0 ? models : undefined)
}

/** Removes a sound event's own entry in pack.json (its file, if any, stays the event it is). */
export function removeSoundEntry(pack: ResourcePackFile, key: string): void {
  if (!pack.sounds?.[key]) return
  const sounds = { ...pack.sounds }
  delete sounds[key]
  setKey(pack, 'sounds', Object.keys(sounds).length > 0 ? sounds : undefined)
}

/** Adds a sound event entry [key] that plays [files] (a key in the game's namespace replaces the game's event). */
export function addSoundEntry(pack: ResourcePackFile, key: string, files: string[]): void {
  pack.sounds = { ...(pack.sounds ?? {}), [key]: files.length > 0 ? { files } : {} }
}

/** A HUD element's kind: a bar, a row of icons or a line of text. */
export type HudKind = 'bars' | 'icons' | 'texts'
export const HUD_KINDS: HudKind[] = ['bars', 'icons', 'texts']
export type HudElementDef = HudBarDef | HudIconsDef | HudTextDef

/** Every key of the pack's HUD elements, of any kind (scripts name them all `<pack>/<key>`), sorted. */
export function hudKeys(pack: ResourcePackFile): string[] {
  const hud = pack.hud
  return HUD_KINDS.flatMap((kind) => Object.keys(hud?.[kind] ?? {})).sort()
}

/** Which kind HUD element [key] is, or null when the pack has none by that key. */
export function hudKindOf(pack: ResourcePackFile, key: string): HudKind | null {
  return HUD_KINDS.find((kind) => pack.hud?.[kind]?.[key] !== undefined) ?? null
}

/** A HUD left with nothing in it goes, and so does each empty part of it. */
function tidyHud(pack: ResourcePackFile): void {
  const hud = pack.hud
  if (!hud) return
  for (const key of ['bars', 'icons', 'texts', 'replace'] as const) {
    if (hud[key] && Object.keys(hud[key]).length === 0) delete hud[key]
  }
  if (hud.hide?.length === 0) delete hud.hide
  if (Object.keys(hud).length === 0) delete pack.hud
}

/** A new element of [kind]: a bar or row drawn with [texture], a line of text, at the middle of the screen. */
export function newHudElement(kind: HudKind, texture: string): HudElementDef {
  if (kind === 'bars') return { full: texture, empty: texture, segments: 10, x: 0, y: 30 }
  if (kind === 'icons') return { icons: { icon: texture }, x: 0, y: 42 }
  return { x: 0 }
}

export function addHudElement(
  pack: ResourcePackFile,
  kind: HudKind,
  key: string,
  def: HudElementDef,
): void {
  pack.hud ??= {}
  ;(pack.hud as Record<HudKind, Record<string, HudElementDef>>)[kind] = {
    ...(pack.hud[kind] ?? {}),
    [key]: def,
  }
}

export function removeHudElement(pack: ResourcePackFile, key: string): void {
  const kind = hudKindOf(pack, key)
  if (!kind) return
  delete pack.hud![kind]![key]
  tidyHud(pack)
}

export function renameHudElement(pack: ResourcePackFile, from: string, to: string): void {
  const kind = hudKindOf(pack, from)
  if (!kind || from === to || hudKindOf(pack, to)) return
  const elements = pack.hud![kind] as Record<string, HudElementDef>
  elements[to] = elements[from]!
  delete elements[from]
}

/** Hides the game's own HUD element (or shows it again), keeping `hide` in the game's order. */
export function setHudHidden(
  pack: ResourcePackFile,
  element: HudElement,
  hidden: boolean,
  order: readonly HudElement[],
): void {
  const now = new Set(pack.hud?.hide ?? [])
  if (hidden) now.add(element)
  else now.delete(element)
  pack.hud ??= {}
  pack.hud.hide = order.filter((it) => now.has(it))
  tidyHud(pack)
}

/** Redraws the game's HUD sprite [sprite] (`hud/hotbar`) with [texture], or with undefined, the game's again. */
export function setHudReplace(
  pack: ResourcePackFile,
  sprite: string,
  texture: string | undefined,
): void {
  pack.hud ??= {}
  const replace: HudDef['replace'] = { ...(pack.hud.replace ?? {}) }
  if (texture === undefined) delete replace[sprite]
  else replace[sprite] = texture
  pack.hud.replace = replace
  tidyHud(pack)
}

/** Sets an icon of a row ([texture] undefined removes it; a row keeps at least one, as format asks). */
export function setHudIcon(
  pack: ResourcePackFile,
  row: string,
  icon: string,
  texture: string | undefined,
): void {
  const def = pack.hud?.icons?.[row]
  if (!def) return
  if (texture !== undefined) def.icons[icon] = texture
  else if (Object.keys(def.icons).length > 1) delete def.icons[icon]
}

/** Renames icon [from] of a row to [to] (scripts show icons by these keys). */
export function renameHudIcon(pack: ResourcePackFile, row: string, from: string, to: string): void {
  const def = pack.hud?.icons?.[row]
  if (!def?.icons[from] || from === to || def.icons[to]) return
  def.icons[to] = def.icons[from]!
  delete def.icons[from]
}
