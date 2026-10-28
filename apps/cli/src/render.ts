/**
 * `netherforge render` and `netherforge gallery`: the project's items, blocks and centities drawn to PNGs the way
 * the editor draws them (its icons and its viewport), without a browser. Looks come from the project's resource
 * packs (`items` and `blocks` entries: a model file, a picture, a block's faces) and, for the game's own items and
 * blocks, from the client the editor imported for the project's Minecraft version.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import type { ClassifiedPath } from '@netherforge/format/types'
import { type BakedFace } from '@netherforge/models/geometry'
import {
  blockStateGeometry,
  cubeGeometry,
  itemGeometry,
  lookPicture,
  packModelGeometry,
  texturesOf,
  type IconAssets,
  type LookGeometry,
} from '@netherforge/models/icons'
import type { RawModel } from '@netherforge/models/model'
import { rasterize, type Picture } from '@netherforge/models/raster'
import { decode, encode } from 'fast-png'
import * as THREE from 'three'
import type { Outcome } from './commands.ts'
import { editorDataDir, listFiles } from './project.ts'

/** A texture id that names a project file rather than one of the client's: `file:resource_packs/ui/textures/x.png`. */
const FILE = 'file:'

/** A PNG as RGBA, 8 bits a channel, whatever it was stored as. */
export function readPng(bytes: Uint8Array): Picture {
  const image = decode(bytes)
  const { width, height, channels } = image
  const source = image.data
  const max = image.depth === 16 ? 257 : 1
  const palette = image.palette
  const data = new Uint8ClampedArray(width * height * 4)
  for (let i = 0; i < width * height; i += 1) {
    let rgba: number[]
    if (palette) {
      const entry = palette[source[i]!] ?? [0, 0, 0, 0]
      rgba = [entry[0]!, entry[1]!, entry[2]!, entry[3] ?? 255]
    } else {
      const at = i * channels
      const read = (c: number) => source[at + c]! / max
      rgba =
        channels === 1
          ? [read(0), read(0), read(0), 255]
          : channels === 2
            ? [read(0), read(0), read(0), read(1)]
            : channels === 3
              ? [read(0), read(1), read(2), 255]
              : [read(0), read(1), read(2), read(3)]
    }
    data.set(rgba, i * 4)
  }
  return { width, height, data }
}

export function writePng(file: string, picture: Picture): void {
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(
    file,
    encode({
      width: picture.width,
      height: picture.height,
      data: picture.data,
      channels: 4,
      depth: 8,
    }),
  )
}

/** The client's files from the editor's import for [minecraft] (when it has one), and the project's under [root]. */
export function projectAssets(
  root: string,
  minecraft: string,
  env: NodeJS.ProcessEnv,
): IconAssets & { client: boolean } {
  const client = path.join(editorDataDir(env), 'minecraft', minecraft, 'client')
  const json = new Map<string, unknown>()
  const pictures = new Map<string, Picture | null>()
  const readFile = (file: string) => {
    try {
      return readFileSync(file)
    } catch {
      return null
    }
  }
  return {
    client: existsSync(path.join(client, 'assets')),
    async json<T>(asset: string): Promise<T | null> {
      if (!json.has(asset)) {
        const bytes = readFile(path.join(client, asset))
        json.set(asset, bytes ? (JSON.parse(bytes.toString('utf8')) as unknown) : null)
      }
      return json.get(asset) as T | null
    },
    async texture(id: string): Promise<Picture | null> {
      if (!pictures.has(id)) {
        let file: string
        if (id.startsWith(FILE)) {
          file = path.join(root, id.slice(FILE.length))
        } else {
          const colon = id.indexOf(':')
          const [namespace, name] =
            colon < 0 ? ['minecraft', id] : [id.slice(0, colon), id.slice(colon + 1)]
          file = path.join(client, 'assets', namespace, 'textures', `${name}.png`)
        }
        const bytes = readFile(file)
        pictures.set(id, bytes ? readPng(bytes) : null)
      }
      return pictures.get(id) ?? null
    },
  }
}

interface PackFile {
  items?: Record<string, PackItem>
  blocks?: Record<string, PackBlock>
}
interface PackItem {
  model?: string
  texture?: string
  guiTexture?: string
  block?: string
}
interface PackBlock {
  model?: string
  texture?: string
  top?: string
  bottom?: string
  side?: string
  north?: string
  south?: string
  east?: string
  west?: string
}

/** What draws the project's things: its packs and items, read once. */
export class ProjectLooks {
  private readonly packs = new Map<string, PackFile>()
  readonly items = new Map<string, { path: string; def: { kind?: string; itemModel?: string } }>()
  readonly blocks = new Map<string, { path: string; def: { model?: string } }>()
  readonly centities = new Map<string, string>()

  constructor(
    readonly root: string,
    readonly namespace: string,
    readonly assets: IconAssets,
  ) {
    for (const file of listFiles(root)) {
      const found = JSON.parse(nf.classify(file)) as ClassifiedPath | null
      if (!found || found.package != null || !found.id) continue
      if (found.kind === 'resource_pack' && file.endsWith('/pack.json')) {
        this.packs.set(found.id, this.json(file) as PackFile)
      } else if (found.role !== 'main') {
        continue
      } else if (found.kind === 'item') {
        this.items.set(found.id, {
          path: file,
          def: this.json(file) as { kind?: string; itemModel?: string },
        })
      } else if (found.kind === 'block') {
        this.blocks.set(found.id, { path: file, def: this.json(file) as { model?: string } })
      } else if (found.kind === 'centity') {
        this.centities.set(found.id, file)
      }
    }
  }

  private json(file: string): unknown {
    try {
      return JSON.parse(readFileSync(path.join(this.root, file), 'utf8'))
    } catch {
      return {}
    }
  }

  /** A `<pack>/<key>` reference of [kind] (`item_model`, `block_model`) as the project's own pack and key; null for another's. */
  private split(kind: string, value: string | undefined): [string, string] | null {
    if (!value) return null
    const key = nf.resolveReference(kind, value, this.namespace)
    if (!key) return null
    const [owner, rest] = [key.slice(0, key.indexOf(':')), key.slice(key.indexOf(':') + 1)]
    if (owner !== this.namespace) return null
    const slash = rest.indexOf('/')
    return [rest.slice(0, slash), rest.slice(slash + 1)]
  }

  private texture = (pack: string, name: string) => `${FILE}resource_packs/${pack}/textures/${name}`

  /** A pack's model file, with its texture variables that name the pack's own pictures bound to them. */
  private async packModel(
    pack: string,
    file: string,
    context: string,
  ): Promise<LookGeometry | null> {
    const model = this.json(`resource_packs/${pack}/models/${file}`) as RawModel
    if (!model || Object.keys(model).length === 0) return null
    const bound: Record<string, string> = {}
    for (const [name, value] of Object.entries(model.textures ?? {})) {
      if (typeof value !== 'string' || value.startsWith('#') || value.includes(':')) continue
      if (existsSync(path.join(this.root, 'resource_packs', pack, 'textures', `${value}.png`))) {
        bound[name] = this.texture(pack, `${value}.png`)
      }
    }
    return packModelGeometry(this.assets, model, bound, context)
  }

  /** A pack's block look as a cube, or its model file. */
  private async blockLook(
    reference: string | undefined,
    context: string,
  ): Promise<LookGeometry | null> {
    const ref = this.split('block_model', reference)
    const def = ref && this.packs.get(ref[0])?.blocks?.[ref[1]]
    if (!ref || !def) return null
    if (def.model) return this.packModel(ref[0], def.model, context)
    const at = (...names: (string | undefined)[]) => {
      const name = names.find((it) => it != null)
      return name ? this.texture(ref[0], name) : undefined
    }
    const faces = Object.fromEntries(
      Object.entries({
        up: at(def.top, def.texture),
        down: at(def.bottom, def.texture),
        north: at(def.north, def.side, def.texture),
        south: at(def.south, def.side, def.texture),
        east: at(def.east, def.side, def.texture),
        west: at(def.west, def.side, def.texture),
      }).filter((entry): entry is [string, string] => entry[1] != null),
    )
    return Object.keys(faces).length > 0 ? cubeGeometry(this.assets, faces, context) : null
  }

  /** A pack's item look (`<pack>/<key>` of its `items`) in display [context]. */
  async itemLook(reference: string | undefined, context: string): Promise<LookGeometry | null> {
    const ref = this.split('item_model', reference)
    const def = ref && this.packs.get(ref[0])?.items?.[ref[1]]
    if (!ref || !def) return null
    if (def.model) return this.packModel(ref[0], def.model, context)
    const picture =
      context === 'gui' ? (def.guiTexture ?? def.texture) : (def.texture ?? def.guiTexture)
    if (picture) {
      const id = this.texture(ref[0], picture)
      const flat = await packModelGeometry(
        this.assets,
        { parent: 'minecraft:item/generated', textures: { layer0: id } },
        {},
        context,
      )
      return flat
    }
    return def.block ? this.blockLook(def.block, context) : null
  }

  /** A project item's look, else the game's for its kind (or for [id], a game item's). */
  async item(id: string, context: string): Promise<LookGeometry | null> {
    const project = this.items.get(id)
    if (project) {
      return (
        (await this.itemLook(project.def.itemModel, context)) ??
        (project.def.kind ? itemGeometry(this.assets, project.def.kind, context) : null)
      )
    }
    return itemGeometry(this.assets, id, context)
  }

  async block(id: string, context: string): Promise<LookGeometry | null> {
    return this.blockLook(this.blocks.get(id)?.def.model, context)
  }

  /** Centity [id] in its base pose, every display at its node, as faces in the centity's space. */
  async centity(id: string): Promise<BakedFace[]> {
    const file = this.centities.get(id)
    if (!file) return []
    const text = readFileSync(path.join(this.root, file), 'utf8')
    const posed = JSON.parse(nf.poseCentity(id, text, null, 0)) as {
      type: string
      nodes?: { name: string; matrix: number[] }[]
    }
    if (posed.type !== 'posed' || !posed.nodes) return []
    const nodes =
      (JSON.parse(text) as { nodes?: Record<string, { display?: Display; rope?: unknown }> })
        .nodes ?? {}
    const faces: BakedFace[] = []
    const v = new THREE.Vector3()
    for (const node of posed.nodes) {
      // A rope's links draw the rope node's display.
      const own = nodes[node.name]
      const rope = /^(.+)\[\d+\]$/.exec(node.name)?.[1]
      const display = own?.rope
        ? undefined
        : (own?.display ?? (rope ? nodes[rope]?.display : undefined))
      if (!display) continue
      const look = await this.display(display)
      if (!look) continue
      const world = new THREE.Matrix4().fromArray(node.matrix).multiply(look.matrix)
      for (const face of look.faces) {
        const positions: number[] = []
        for (let corner = 0; corner < 4; corner += 1) {
          v.fromArray(face.positions, corner * 3).applyMatrix4(world)
          positions.push(v.x, v.y, v.z)
        }
        faces.push({ ...face, positions })
      }
    }
    return faces
  }

  private display(display: Display): Promise<LookGeometry | null> {
    const context = display.itemTransform ?? 'none'
    switch (display.type) {
      case 'block':
        return display.block
          ? blockStateGeometry(this.assets, display.block)
          : Promise.resolve(null)
      case 'item':
        return display.item ? this.item(display.item, context) : Promise.resolve(null)
      case 'model':
        return this.itemLook(display.model, context)
      default:
        return Promise.resolve(null)
    }
  }
}

interface Display {
  type: string
  block?: string
  item?: string
  model?: string
  itemTransform?: string
}

/** The view a centity picture is drawn from: turned to show its front, its right side and its top. */
const VIEW = new THREE.Matrix4()
  .makeRotationX(THREE.MathUtils.degToRad(25))
  .multiply(new THREE.Matrix4().makeRotationY(THREE.MathUtils.degToRad(-35)))

/** [faces] seen from [VIEW], fitted to a [size]-pixel square with a little room round them. */
export async function viewPicture(
  assets: IconAssets,
  faces: BakedFace[],
  size: number,
): Promise<Picture | null> {
  if (faces.length === 0) return null
  const v = new THREE.Vector3()
  const min = new THREE.Vector3(Infinity, Infinity, Infinity)
  const max = new THREE.Vector3(-Infinity, -Infinity, -Infinity)
  for (const face of faces) {
    for (let corner = 0; corner < 4; corner += 1) {
      v.fromArray(face.positions, corner * 3).applyMatrix4(VIEW)
      min.min(v)
      max.max(v)
    }
  }
  const extent = Math.max(max.x - min.x, max.y - min.y, 1e-6)
  const centre = min.clone().add(max).multiplyScalar(0.5)
  const camera = new THREE.Matrix4()
    .makeScale(0.9 / extent, 0.9 / extent, 0.9 / extent)
    .multiply(new THREE.Matrix4().makeTranslation(-centre.x, -centre.y, -centre.z))
    .multiply(VIEW)
  const pictures = await texturesOf(
    assets,
    faces.map((it) => it.texture),
  )
  return rasterize(faces, camera, (name) => pictures.get(name) ?? null, size)
}

/** What [file] (an item's, a block's or a centity's main file) draws as, [size] pixels square. */
async function draw(
  looks: ProjectLooks,
  kind: string,
  id: string,
  size: number,
): Promise<Picture | null> {
  switch (kind) {
    case 'item':
      return lookPicture(looks.assets, await looks.item(id, 'gui'), size)
    case 'block':
      return lookPicture(looks.assets, await looks.block(id, 'gui'), size)
    case 'centity':
      return viewPicture(looks.assets, await looks.centity(id), size)
    default:
      return null
  }
}

function manifestOf(root: string): { namespace: string; minecraft: string } {
  return JSON.parse(readFileSync(path.join(root, 'netherforge.json'), 'utf8')) as {
    namespace: string
    minecraft: string
  }
}

const NO_CLIENT = (minecraft: string) =>
  `No Minecraft ${minecraft} client imported (the editor imports it): the game's own items and blocks are left out.`

/** `netherforge render <file> --out <png> [--size n]`. */
export async function render(
  root: string,
  file: string,
  options: { out?: string; size?: number },
  env: NodeJS.ProcessEnv,
): Promise<Outcome> {
  if (!options.out) return { out: [], err: ['render needs --out <file>.png'], code: 2 }
  const relative = path.relative(root, file).split(path.sep).join('/')
  const found = JSON.parse(nf.classify(relative)) as ClassifiedPath | null
  if (!found?.id || !found.kind || !['item', 'block', 'centity'].includes(found.kind)) {
    return {
      out: [],
      err: [`${relative} isn't an item's, a block's or a centity's file.`],
      code: 2,
    }
  }
  const { namespace, minecraft } = manifestOf(root)
  const assets = projectAssets(root, minecraft, env)
  const looks = new ProjectLooks(root, namespace, assets)
  const size = options.size ?? (found.kind === 'centity' ? 256 : 64)
  const picture = await draw(looks, found.kind, found.id, size)
  const notes = assets.client ? [] : [NO_CLIENT(minecraft)]
  if (!picture) return { out: notes, err: [`${relative} draws nothing.`], code: 1 }
  writePng(options.out, picture)
  return { out: [`${options.out}: ${size}x${size}`, ...notes], err: [], code: 0 }
}

/** `netherforge gallery [dir] [--out folder] [--size n]`: every item, block and centity, and a page of them. */
export async function gallery(
  root: string,
  options: { out?: string; size?: number },
  env: NodeJS.ProcessEnv,
): Promise<Outcome> {
  const out = path.resolve(root, options.out ?? 'build/gallery')
  const { namespace, minecraft } = manifestOf(root)
  const assets = projectAssets(root, minecraft, env)
  const looks = new ProjectLooks(root, namespace, assets)
  const sections: { title: string; kind: string; folder: string; ids: string[] }[] = [
    { title: 'Items', kind: 'item', folder: 'items', ids: [...looks.items.keys()].sort() },
    { title: 'Blocks', kind: 'block', folder: 'blocks', ids: [...looks.blocks.keys()].sort() },
    {
      title: 'Centities',
      kind: 'centity',
      folder: 'centities',
      ids: [...looks.centities.keys()].sort(),
    },
  ]
  const html: string[] = []
  const missing: string[] = []
  let drawn = 0
  for (const section of sections) {
    if (section.ids.length === 0) continue
    const size = options.size ?? (section.kind === 'centity' ? 192 : 64)
    html.push(`<h2>${section.title} (${section.ids.length})</h2>`, '<div class="grid">')
    for (const id of section.ids) {
      const picture = await draw(looks, section.kind, id, size)
      const name = `${section.folder}/${id}.png`
      if (picture) {
        writePng(path.join(out, name), picture)
        drawn += 1
      } else {
        missing.push(`${section.kind} ${id}`)
      }
      html.push(
        `<figure>${picture ? `<img src="${name}" width="${size}" height="${size}" alt="">` : '<div class="none">?</div>'}<figcaption>${id}</figcaption></figure>`,
      )
    }
    html.push('</div>')
  }
  writeFileSync(path.join(out, 'index.html'), page(namespace, html.join('\n')))
  return {
    out: [
      `${path.join(out, 'index.html')}: ${drawn} pictures`,
      ...(assets.client ? [] : [NO_CLIENT(minecraft)]),
      ...(missing.length > 0
        ? [
            `Nothing drawn for ${missing.length}: ${missing.slice(0, 20).join(', ')}${missing.length > 20 ? ', …' : ''}`,
          ]
        : []),
    ],
    err: [],
    code: 0,
  }
}

function page(title: string, body: string): string {
  return `<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} gallery</title>
<style>
  body { font: 14px system-ui, sans-serif; background: #1e1f22; color: #ddd; margin: 16px; }
  .grid { display: flex; flex-wrap: wrap; gap: 8px; }
  figure { margin: 0; padding: 6px; background: #2b2d31; border-radius: 6px; text-align: center; }
  img, .none { image-rendering: pixelated; display: block; margin: 0 auto; }
  .none { width: 64px; height: 64px; line-height: 64px; color: #777; }
  figcaption { font-size: 11px; margin-top: 4px; max-width: 128px; overflow-wrap: anywhere; }
</style>
<h1>${title}</h1>
${body}
</html>
`
}
