/**
 * Baked faces drawn to a picture without a browser: a small software rasteriser, as the editor's WebGL draws them
 * for its icons (nearest-pixel textures, vanilla's shade and tint in the vertex colours, an alpha cut-out, no
 * culling), so the netherforge command draws the same pictures. Orthographic only: what the GUI and a gallery need.
 */
import * as THREE from 'three'
import type { BakedFace } from './geometry.ts'

/** An RGBA picture, rows top to bottom. */
export interface Picture {
  width: number
  height: number
  data: Uint8ClampedArray
}

export function blankPicture(width: number, height: number): Picture {
  return { width, height, data: new Uint8ClampedArray(width * height * 4) }
}

/** The magenta and black chequer the game draws for a texture it can't find. */
export const MISSING: Picture = (() => {
  const picture = blankPicture(16, 16)
  for (let y = 0; y < 16; y += 1) {
    for (let x = 0; x < 16; x += 1) {
      const magenta = x < 8 === y < 8
      picture.data.set(magenta ? [248, 0, 248, 255] : [0, 0, 0, 255], (y * 16 + x) * 4)
    }
  }
  return picture
})()

/** What is below this alpha (of 255) is cut out, as the editor's `alphaTest`. */
const ALPHA_CUT = 26

/**
 * Draws [faces], moved by [matrix], seen along -Z: the square from -[half] to [half] on x and y fills the
 * [size]-pixel picture (y up), and the nearest face wins. [texture] gives each face's picture (the first frame of
 * an animated strip is used); one it can't give is the missing-texture chequer.
 */
export function rasterize(
  faces: BakedFace[],
  matrix: THREE.Matrix4,
  texture: (id: string) => Picture | null,
  size: number,
  half = 0.5,
): Picture {
  const out = blankPicture(size, size)
  const depth = new Float32Array(size * size).fill(-Infinity)
  const v = new THREE.Vector3()
  const scale = size / (2 * half)
  for (const face of faces) {
    const picture = texture(face.texture) ?? MISSING
    // An animated texture is a strip of square frames: the first is drawn.
    const frameHeight =
      picture.height > picture.width && picture.height % picture.width === 0
        ? picture.width
        : picture.height
    const xs: number[] = []
    const ys: number[] = []
    const zs: number[] = []
    for (let corner = 0; corner < 4; corner += 1) {
      v.set(
        face.positions[corner * 3]!,
        face.positions[corner * 3 + 1]!,
        face.positions[corner * 3 + 2]!,
      ).applyMatrix4(matrix)
      xs.push((v.x + half) * scale)
      ys.push((half - v.y) * scale)
      zs.push(v.z)
    }
    for (const [a, b, c] of [
      [0, 1, 2],
      [0, 2, 3],
    ] as const) {
      const area = (xs[b]! - xs[a]!) * (ys[c]! - ys[a]!) - (xs[c]! - xs[a]!) * (ys[b]! - ys[a]!)
      if (Math.abs(area) < 1e-9) continue
      const minX = Math.max(0, Math.floor(Math.min(xs[a]!, xs[b]!, xs[c]!)))
      const maxX = Math.min(size - 1, Math.ceil(Math.max(xs[a]!, xs[b]!, xs[c]!)))
      const minY = Math.max(0, Math.floor(Math.min(ys[a]!, ys[b]!, ys[c]!)))
      const maxY = Math.min(size - 1, Math.ceil(Math.max(ys[a]!, ys[b]!, ys[c]!)))
      for (let py = minY; py <= maxY; py += 1) {
        for (let px = minX; px <= maxX; px += 1) {
          // Barycentric weights at the pixel's centre.
          const x = px + 0.5
          const y = py + 0.5
          const wa = ((xs[b]! - x) * (ys[c]! - y) - (xs[c]! - x) * (ys[b]! - y)) / area
          const wb = ((xs[c]! - x) * (ys[a]! - y) - (xs[a]! - x) * (ys[c]! - y)) / area
          const wc = 1 - wa - wb
          if (wa < -1e-6 || wb < -1e-6 || wc < -1e-6) continue
          const z = wa * zs[a]! + wb * zs[b]! + wc * zs[c]!
          const at = py * size + px
          if (z <= depth[at]!) continue
          const u = wa * face.uvs[a * 2]! + wb * face.uvs[b * 2]! + wc * face.uvs[c * 2]!
          const t =
            wa * face.uvs[a * 2 + 1]! + wb * face.uvs[b * 2 + 1]! + wc * face.uvs[c * 2 + 1]!
          const tx = Math.min(picture.width - 1, Math.max(0, Math.floor(u * picture.width)))
          const ty = Math.min(frameHeight - 1, Math.max(0, Math.floor((1 - t) * frameHeight)))
          const texel = (ty * picture.width + tx) * 4
          const alpha = picture.data[texel + 3]!
          if (alpha < ALPHA_CUT) continue
          depth[at] = z
          for (let channel = 0; channel < 3; channel += 1) {
            const shade =
              wa * face.colors[a * 3 + channel]! +
              wb * face.colors[b * 3 + channel]! +
              wc * face.colors[c * 3 + channel]!
            out.data[at * 4 + channel] = picture.data[texel + channel]! * shade
          }
          out.data[at * 4 + 3] = alpha
        }
      }
    }
  }
  return out
}

/** [layers] drawn over each other, each scaled to [size] by nearest pixel: a sprite item, as its icon shows. */
export function layered(layers: Picture[], size: number): Picture {
  const out = blankPicture(size, size)
  for (const layer of layers) {
    const frameHeight =
      layer.height > layer.width && layer.height % layer.width === 0 ? layer.width : layer.height
    for (let y = 0; y < size; y += 1) {
      for (let x = 0; x < size; x += 1) {
        const from =
          (Math.floor((y * frameHeight) / size) * layer.width +
            Math.floor((x * layer.width) / size)) *
          4
        const alpha = layer.data[from + 3]! / 255
        if (alpha === 0) continue
        const to = (y * size + x) * 4
        for (let channel = 0; channel < 3; channel += 1) {
          out.data[to + channel] =
            layer.data[from + channel]! * alpha + out.data[to + channel]! * (1 - alpha)
        }
        out.data[to + 3] = Math.max(out.data[to + 3]!, layer.data[from + 3]!)
      }
    }
  }
  return out
}
