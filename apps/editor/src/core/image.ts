/** What the editor reads from a picture's pixels: its size and alpha, loaded once per URL. */
export interface ImageInfo {
  width: number
  height: number
  /** The alpha channel, row by row; null if the pixels can't be read. */
  alpha: Uint8Array | null
  /** [opaqueWidth] of [alpha]; null if the pixels can't be read. */
  opaqueWidth: number | null
}

/**
 * The rightmost column holding any pixel that isn't fully transparent, plus
 * one (0 for an empty picture): the one fact about a picture only its pixels
 * know, which format turns into a bitmap glyph's advance
 * (`PackFonts.bitmapAdvance`). [alpha] is row by row.
 */
export function opaqueWidth(alpha: ArrayLike<number>, width: number, height: number): number {
  for (let x = width - 1; x >= 0; x -= 1) {
    for (let y = 0; y < height; y += 1) {
      if (alpha[y * width + x]! !== 0) return x + 1
    }
  }
  return 0
}

/**
 * Of each column, left to right, whether it holds any pixel that isn't fully
 * transparent: what format measures each piece of a picture too wide for a
 * font sheet from (`ImageInfo.opaqueColumns`). [alpha] is row by row.
 */
export function opaqueColumns(alpha: ArrayLike<number>, width: number, height: number): boolean[] {
  const out = new Array<boolean>(width).fill(false)
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      if (alpha[y * width + x]! !== 0) out[x] = true
    }
  }
  return out
}

const cache = new Map<string, Promise<ImageInfo | null>>()

/** Size and alpha of a picture (a pack texture), loaded once per URL. */
export function loadImageInfo(url: string): Promise<ImageInfo | null> {
  let pending = cache.get(url)
  if (!pending) {
    pending = new Promise((resolve) => {
      const image = new Image()
      // nfproject:// sends CORS headers, so the pixels stay readable.
      image.crossOrigin = 'anonymous'
      image.onload = () => {
        const { naturalWidth: width, naturalHeight: height } = image
        let alpha: Uint8Array | null = null
        try {
          const canvas = document.createElement('canvas')
          canvas.width = width
          canvas.height = height
          const context = canvas.getContext('2d')
          if (context) {
            context.drawImage(image, 0, 0)
            const data = context.getImageData(0, 0, width, height).data
            alpha = new Uint8Array(width * height)
            for (let i = 0; i < alpha.length; i += 1) alpha[i] = data[i * 4 + 3]!
          }
        } catch {
          alpha = null
        }
        resolve({
          width,
          height,
          alpha,
          opaqueWidth: alpha ? opaqueWidth(alpha, width, height) : null,
        })
      }
      image.onerror = () => resolve(null)
      image.src = url
    })
    cache.set(url, pending)
  }
  return pending
}
