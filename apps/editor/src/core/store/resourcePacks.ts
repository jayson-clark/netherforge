/**
 * The project's resource packs as every editor sees them, worked out once per change
 * rather than once per component that shows a skin, glyph or item: each
 * pack.json as it is now (an open document's unsaved edits included), parsed,
 * the pixel facts of its skin and glyph pictures, and format's compilation of
 * them into the characters and placements the server will use.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Backend } from '@/core/backend/types'
import {
  compileResourcePacks,
  FONT_SHEET,
  type ImageInfo,
  type ResourcePackFile,
  type ResourcePacksPreview,
} from '@/core/format'
import { resourcePackTexturePath } from '@/core/paths'
import { loadImageInfo, opaqueColumns } from '@/core/image'
import { isModel } from './documents'
import { fileUrl } from './packages'
import type { Workspace, WorkspaceStore } from './workspace'

export interface ResourcePacks {
  /** The project's namespace (`netherforge.json`'s), which pack references resolve in; empty until it's known. */
  namespace: string
  /** Pack id → pack.json text; a package's pack is keyed `ns:id` (`library:gems`). */
  texts: Record<string, string>
  /** Every pack that parses, by id (a package's as `ns:id`). */
  files: Record<string, ResourcePackFile>
  /**
   * The pixel facts of every skin and glyph picture, by project path. A
   * picture still loading or unreadable is left out; the last answer is kept
   * while a new one loads, so previews don't jump.
   */
  images: Record<string, ImageInfo>
  compiled: ResourcePacksPreview
  /**
   * Each pack's model files (`models/<path>.json`) that parse, by pack id
   * then path under `models/`, and the textures under its `textures/`
   * folder (`gems/ruby.png`), which a model's plain texture paths name.
   */
  models: Record<string, PackModels>
}

/**
 * One pack's model files, parsed (the game's JSON model, which `@netherforge/models/model`'s
 * `RawModel` reads), and its texture files, for drawing the models.
 */
export interface PackModels {
  files: Record<string, { textures?: Record<string, unknown> }>
  textures: string[]
}

export type ResourcePacksStore = StoreApi<ResourcePacks>

const RESOURCE_PACK_FILE = /^resource_packs\/([^/]+)\/pack\.json$/
const RESOURCE_PACK_MODEL = /^resource_packs\/([^/]+)\/models\/(.+\.json)$/
const RESOURCE_PACK_TEXTURE = /^resource_packs\/([^/]+)\/textures\/(.+\.png)$/

/** An open pack's text per model: models are immutable, and this runs on every store change. */
const modelTexts = new WeakMap<object, string>()

function textOf(model: unknown): string {
  if (typeof model !== 'object' || model === null) return JSON.stringify(model)
  let text = modelTexts.get(model)
  if (text === undefined) {
    text = JSON.stringify(model)
    modelTexts.set(model, text)
  }
  return text
}

/** Pack id → pack.json text, from open documents first, then disk; then each package's, as `ns:id`. */
export function resourcePackTexts(s: Workspace): Record<string, string> {
  const out: Record<string, string> = {}
  for (const path of s.files) {
    const match = RESOURCE_PACK_FILE.exec(path)
    if (!match) continue
    const doc = s.docs[path]
    const text =
      doc && !doc.deleted
        ? isModel(doc)
          ? textOf(doc.history.present)
          : doc.text
        : s.diskTexts[path]
    if (text !== undefined) out[match[1]!] = text
  }
  // The packages' packs, keyed `ns:id` as format keys them: built beside the project's, each in its namespace.
  for (const [namespace, pkg] of Object.entries(s.outline?.packages ?? {})) {
    const folder = s.packages.folders[pkg.location]
    for (const [path, text] of Object.entries(folder?.files ?? {})) {
      const match = RESOURCE_PACK_FILE.exec(path)
      if (match && text != null) out[`${namespace}:${match[1]!}`] = text
    }
  }
  return out
}

/** Every pack's model files and textures, from open documents first, then disk; then each package's, as `ns:id`. */
export function resourcePackModels(s: Workspace): Record<string, PackModels> {
  const out: Record<string, PackModels> = {}
  const of = (pack: string) => (out[pack] ??= { files: {}, textures: [] })
  const read = (pack: string, path: string, text: string | undefined) => {
    if (text === undefined) return
    try {
      const json: unknown = JSON.parse(text)
      if (json && typeof json === 'object' && !Array.isArray(json))
        of(pack).files[path] = json as { textures?: Record<string, unknown> }
    } catch {
      // Problems will say what's wrong with it.
    }
  }
  for (const path of s.files) {
    const texture = RESOURCE_PACK_TEXTURE.exec(path)
    if (texture) of(texture[1]!).textures.push(texture[2]!)
    const model = RESOURCE_PACK_MODEL.exec(path)
    if (!model) continue
    const doc = s.docs[path]
    read(model[1]!, model[2]!, doc && !doc.deleted && !isModel(doc) ? doc.text : s.diskTexts[path])
  }
  for (const [namespace, pkg] of Object.entries(s.outline?.packages ?? {})) {
    const folder = s.packages.folders[pkg.location]
    for (const [path, text] of Object.entries(folder?.files ?? {})) {
      const texture = RESOURCE_PACK_TEXTURE.exec(path)
      if (texture) of(`${namespace}:${texture[1]!}`).textures.push(texture[2]!)
      const model = RESOURCE_PACK_MODEL.exec(path)
      if (model && text != null) read(`${namespace}:${model[1]!}`, model[2]!, text)
    }
  }
  for (const pack of Object.values(out)) pack.textures.sort()
  return out
}

/**
 * Project paths of every skin and glyph picture (whose advance format needs pixels for) and every
 * picture replacing one of the game's textures (whose size format checks against the game's).
 */
export function measuredTextures(files: Record<string, ResourcePackFile>): string[] {
  const out = new Set<string>()
  for (const [namespace, pack] of Object.entries(files)) {
    for (const entry of [...Object.values(pack.skins ?? {}), ...Object.values(pack.glyphs ?? {})]) {
      if (typeof entry?.texture === 'string')
        out.add(resourcePackTexturePath(namespace, entry.texture))
    }
    for (const picture of Object.values(pack.textures ?? {})) {
      if (typeof picture === 'string') out.add(resourcePackTexturePath(namespace, picture))
    }
  }
  return [...out].sort()
}

function parse(texts: Record<string, string>): Record<string, ResourcePackFile> {
  const out: Record<string, ResourcePackFile> = {}
  for (const [namespace, text] of Object.entries(texts)) {
    try {
      out[namespace] = JSON.parse(text) as ResourcePackFile
    } catch {
      // Problems will say what's wrong with it.
    }
  }
  return out
}

function compile(
  namespace: string,
  texts: Record<string, string>,
  images: Record<string, ImageInfo>,
): ResourcePacksPreview {
  try {
    return compileResourcePacks(namespace, texts, images)
  } catch {
    return { resourcePacks: {}, problems: [] }
  }
}

const sameRecord = (a: Record<string, unknown>, b: Record<string, unknown>) => {
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every((key) => a[key] === b[key])
}

export function createResourcePacks(
  workspace: WorkspaceStore,
  backend: Backend,
): ResourcePacksStore {
  const store = createStore<ResourcePacks>(() => ({
    namespace: '',
    texts: {},
    files: {},
    images: {},
    compiled: { resourcePacks: {}, problems: [] },
    models: {},
  }))

  // Which pictures were last measured, at which file stamps; and which load is current.
  let measuring = ''
  let generation = 0

  const measure = (
    paths: string[],
    stamps: Record<string, number>,
    outline: Workspace['outline'],
  ) => {
    const key = JSON.stringify(paths.map((path) => [path, stamps[path] ?? 0]))
    if (key === measuring) return
    measuring = key
    const mine = ++generation
    void Promise.all(
      paths.map(
        async (path) =>
          [path, await loadImageInfo(fileUrl(backend, outline, path, stamps[path]))] as const,
      ),
    ).then((results) => {
      if (mine !== generation) return
      const images: Record<string, ImageInfo> = {}
      for (const [path, info] of results) {
        if (info && info.opaqueWidth !== null) {
          const facts: ImageInfo = {
            width: info.width,
            height: info.height,
            opaqueWidth: info.opaqueWidth,
          }
          // A picture too wide for a font sheet is cut into pieces, each measured on its own.
          if (info.width > FONT_SHEET && info.alpha) {
            facts.opaqueColumns = opaqueColumns(info.alpha, info.width, info.height)
          }
          images[path] = facts
        }
      }
      if (JSON.stringify(images) === JSON.stringify(store.getState().images)) return
      const { namespace, texts } = store.getState()
      store.setState({ images, compiled: compile(namespace, texts, images) })
      workspace
        .getState()
        .setImageSizes(
          Object.fromEntries(
            Object.entries(images).map(([path, it]) => [
              path,
              [it.width, it.height] as [number, number],
            ]),
          ),
        )
    })
  }

  // What the packs are read from: the store's own objects, replaced on change. Most workspace
  // changes (a tab, a selection, a problem) touch none of them, and reading every pack is costly.
  let read: unknown[] = []

  const update = (state: Workspace) => {
    const inputs = [state.files, state.docs, state.diskTexts, state.outline, state.packages]
    if (inputs.every((it, index) => it === read[index])) {
      measure(measuredTextures(store.getState().files), state.fileStamps, state.outline)
      return
    }
    read = inputs
    const models = resourcePackModels(state)
    if (JSON.stringify(models) !== JSON.stringify(store.getState().models))
      store.setState({ models })
    const texts = resourcePackTexts(state)
    const namespace = state.outline?.namespace ?? ''
    const current = store.getState()
    if (!sameRecord(texts, current.texts) || namespace !== current.namespace) {
      const files = parse(texts)
      store.setState({
        namespace,
        texts,
        files,
        compiled: compile(namespace, texts, current.images),
      })
    }
    measure(measuredTextures(store.getState().files), state.fileStamps, state.outline)
  }

  update(workspace.getState())
  workspace.subscribe(update)
  return store
}
