/**
 * Renders an item's 3D model (a block item, anything with elements, or a
 * pack item drawn as a pack's block) to a small picture for slot icons, using the same baked geometry as the
 * viewport and the model's own `gui` display transform, the way the game
 * draws items in inventories. One shared offscreen renderer; results are
 * cached per asset set and item, and renders run one at a time. Textures are
 * loaded once per URL and kept on the renderer (a gallery of block items draws
 * the same few pictures hundreds of times); losing the WebGL context drops the
 * renderer and its textures, and the next render makes them again.
 */
import * as THREE from 'three'
import type { ClientAssets } from './assets'
import type { RawModel } from '@netherforge/models/model'
import {
  blockLookGeometry,
  isUrl,
  itemGeometry,
  packModelGeometry,
  type ItemGeometry,
} from './render'

const SIZE = 64

let renderer: THREE.WebGLRenderer | null | undefined
/** The renderer's textures by URL; null for one that didn't load. */
let textures = new Map<string, Promise<THREE.Texture | null>>()
const cache = new WeakMap<ClientAssets, Map<string, Promise<string | null>>>()
let queue: Promise<unknown> = Promise.resolve()

function dropRenderer() {
  for (const pending of textures.values()) void pending.then((texture) => texture?.dispose())
  textures = new Map()
  renderer?.dispose()
  renderer = undefined
}

function sharedRenderer(): THREE.WebGLRenderer | null {
  if (renderer !== undefined) return renderer
  try {
    const canvas = document.createElement('canvas')
    canvas.width = SIZE
    canvas.height = SIZE
    // A lost context draws nothing again: start over with a new one.
    canvas.addEventListener('webglcontextlost', dropRenderer)
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, preserveDrawingBuffer: true })
    renderer.setSize(SIZE, SIZE, false)
    renderer.outputColorSpace = THREE.SRGBColorSpace
  } catch {
    renderer = null
  }
  return renderer
}

function loadTexture(url: string): Promise<THREE.Texture | null> {
  let pending = textures.get(url)
  if (!pending) {
    pending = newTexture(url)
    textures.set(url, pending)
  }
  return pending
}

function newTexture(url: string): Promise<THREE.Texture | null> {
  return new THREE.TextureLoader().loadAsync(url).then(
    (texture) => {
      texture.magFilter = THREE.NearestFilter
      texture.minFilter = THREE.NearestFilter
      texture.generateMipmaps = false
      texture.colorSpace = THREE.SRGBColorSpace
      const image = texture.image as HTMLImageElement
      // Animated strips: the first frame.
      if (image.height > image.width && image.height % image.width === 0) {
        texture.repeat.set(1, image.width / image.height)
        texture.offset.set(0, 1 - image.width / image.height)
      }
      return texture
    },
    () => null,
  )
}

/** Draws [geometry]; [urlOf] is where a group's `texture` is loaded from. */
async function draw(
  geometry: ItemGeometry | null,
  urlOf: (texture: string) => string,
): Promise<string | null> {
  const gl = sharedRenderer()
  if (!gl) return null
  if (!geometry) return null
  const scene = new THREE.Scene()
  const root = new THREE.Group()
  root.matrixAutoUpdate = false
  root.matrix.copy(geometry.matrix)
  scene.add(root)
  const maps = await Promise.all(geometry.groups.map((group) => loadTexture(urlOf(group.texture))))
  if (gl !== renderer) return null
  geometry.groups.forEach((group, index) => {
    const material = new THREE.MeshBasicMaterial({
      map: maps[index] ?? null,
      vertexColors: true,
      transparent: true,
      alphaTest: 0.1,
      side: THREE.DoubleSide,
    })
    root.add(new THREE.Mesh(group.geometry, material))
  })
  // The GUI draws a 16-pixel square around the model's centre.
  const camera = new THREE.OrthographicCamera(-0.5, 0.5, 0.5, -0.5, -10, 10)
  camera.position.set(0, 0, 5)
  gl.setClearColor(0x000000, 0)
  gl.clear()
  gl.render(scene, camera)
  const url = gl.getContext().isContextLost() ? null : gl.domElement.toDataURL('image/png')
  for (const child of root.children) {
    const material = (child as THREE.Mesh).material as THREE.MeshBasicMaterial
    material.dispose()
  }
  return url
}

function cached(
  assets: ClientAssets,
  key: string,
  render: () => Promise<string | null>,
): Promise<string | null> {
  let perAssets = cache.get(assets)
  if (!perAssets) {
    perAssets = new Map()
    cache.set(assets, perAssets)
  }
  let pending = perAssets.get(key)
  if (!pending) {
    const mine = queue.then(render).catch(() => null)
    queue = mine
    perAssets.set(key, mine)
    // Nothing drawn (a lost context, a texture still being written) is tried again next time.
    void mine.then((url) => {
      if (url === null && perAssets.get(key) === mine) perAssets.delete(key)
    })
    pending = mine
  }
  return pending
}

/** A picture of [item]'s model as the inventory draws it, or null when it can't be drawn. */
export function itemPicture(assets: ClientAssets, item: string): Promise<string | null> {
  return cached(assets, item, async () =>
    draw(await itemGeometry(assets, item, 'gui'), (texture) => assets.textureUrl(texture)),
  )
}

/**
 * A picture of a pack block look as the inventory draws its item: a cube with
 * [faces] (face → texture URL), or null when it can't be drawn.
 */
export function blockLookPicture(
  assets: ClientAssets,
  faces: Record<string, string>,
): Promise<string | null> {
  return cached(assets, `look:${JSON.stringify(faces)}`, async () =>
    draw(await blockLookGeometry(assets, faces, 'gui'), (url) => url),
  )
}

/**
 * A picture of a pack's own model file as the inventory draws its item: [bound] binds its
 * texture variables that are the pack's to URLs; null when it can't be drawn.
 */
export function packModelPicture(
  assets: ClientAssets,
  model: RawModel,
  bound: Record<string, string>,
): Promise<string | null> {
  return cached(assets, `pack:${JSON.stringify(model)}|${JSON.stringify(bound)}`, async () =>
    draw(await packModelGeometry(assets, model, bound, 'gui'), (texture) =>
      isUrl(texture) ? texture : assets.textureUrl(texture),
    ),
  )
}
