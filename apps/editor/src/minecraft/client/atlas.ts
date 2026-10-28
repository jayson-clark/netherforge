/**
 * Block textures packed into one picture, so a mesh of many textures is one
 * draw call: the map preview draws hundreds of chunks, and a group
 * per texture per chunk (25 or so each in real terrain) would be thousands
 * of draw calls. Each texture gets a 16×16 tile (its first frame, scaled with
 * nearest-neighbour if it isn't 16 wide), drawn as soon as it loads; a baked
 * state's faces are rewritten to point at their tiles (`remap`), so the
 * mesher, which groups by texture, makes one group of them all.
 */
import * as THREE from 'three'
import type { ClientAssets } from './assets'
import type { BakedState } from './blockMesh'

/** The texture name every remapped face carries: the atlas itself. */
export const ATLAS_TEXTURE = 'netherforge:atlas'

const TILE = 16
/** Tiles along each side: 4096 in all, several times the textures the game's blocks use. */
const TILES = 64
const SIZE = TILE * TILES
/** How far inside its tile a face's UVs stay, in texels, so nearest sampling never reads the next tile. */
const INSET = 0.01
/** The last tile: the missing-texture chequer, for textures that fail to load (and any past the end). */
const MISSING = TILES * TILES - 1

export class BlockAtlas {
  readonly texture: THREE.Texture
  readonly material: THREE.Material
  private context: CanvasRenderingContext2D | null
  private slots = new Map<string, number>()
  private remapped = new WeakMap<BakedState, BakedState>()

  constructor(private assets: ClientAssets) {
    const canvas = document.createElement('canvas')
    canvas.width = SIZE
    canvas.height = SIZE
    this.context = canvas.getContext('2d')
    if (this.context) this.context.imageSmoothingEnabled = false
    this.drawMissing(MISSING)
    this.texture = new THREE.CanvasTexture(canvas)
    this.texture.magFilter = THREE.NearestFilter
    this.texture.minFilter = THREE.NearestFilter
    this.texture.generateMipmaps = false
    this.texture.colorSpace = THREE.SRGBColorSpace
    this.material = new THREE.MeshBasicMaterial({
      map: this.texture,
      vertexColors: true,
      alphaTest: 0.1,
      transparent: true,
    })
  }

  /** [state] with its faces pointing at their tiles in the atlas. */
  remap(state: BakedState): BakedState {
    let out = this.remapped.get(state)
    if (!out) {
      out = {
        faces: state.faces.map((face) => {
          const slot = this.slot(face.texture)
          const column = slot % TILES
          const row = Math.floor(slot / TILES)
          const uvs = face.uvs.map((value, i) => {
            const inside = INSET + Math.min(Math.max(value, 0), 1) * (TILE - 2 * INSET)
            // u runs right; v runs up, and the canvas's first row is the texture's top.
            return i % 2 === 0
              ? (column * TILE + inside) / SIZE
              : (SIZE - (row + 1) * TILE + inside) / SIZE
          })
          return { ...face, texture: ATLAS_TEXTURE, uvs }
        }),
      }
      this.remapped.set(state, out)
    }
    return out
  }

  private slot(texture: string): number {
    let slot = this.slots.get(texture)
    if (slot === undefined) {
      slot = Math.min(this.slots.size, MISSING)
      this.slots.set(texture, slot)
      if (slot !== MISSING) this.load(texture, slot)
    }
    return slot
  }

  private load(texture: string, slot: number) {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    const x = (slot % TILES) * TILE
    const y = Math.floor(slot / TILES) * TILE
    image.onload = () => {
      // An animated texture is a strip of square frames: the first one.
      const frame = Math.min(image.naturalWidth, image.naturalHeight)
      this.context?.drawImage(image, 0, 0, image.naturalWidth, frame, x, y, TILE, TILE)
      this.texture.needsUpdate = true
    }
    image.onerror = () => {
      this.drawMissing(slot)
      this.texture.needsUpdate = true
    }
    image.src = this.assets.textureUrl(texture)
  }

  /** Minecraft's magenta and black chequer. */
  private drawMissing(slot: number) {
    const context = this.context
    if (!context) return
    const x = (slot % TILES) * TILE
    const y = Math.floor(slot / TILES) * TILE
    context.fillStyle = '#000'
    context.fillRect(x, y, TILE, TILE)
    context.fillStyle = '#f800f8'
    context.fillRect(x, y, TILE / 2, TILE / 2)
    context.fillRect(x + TILE / 2, y + TILE / 2, TILE / 2, TILE / 2)
  }
}

const atlases = new WeakMap<ClientAssets, BlockAtlas>()

/** The one atlas per asset set. */
export function blockAtlas(assets: ClientAssets): BlockAtlas {
  let atlas = atlases.get(assets)
  if (!atlas) {
    atlas = new BlockAtlas(assets)
    atlases.set(assets, atlas)
  }
  return atlas
}
