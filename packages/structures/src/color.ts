/** Colours as 0 to 255 channels, shared by the structure pictures here and the terrain preview's. */

export type Rgb = readonly [number, number, number]

/** HSL (h in degrees, s and l 0 to 1) as 0 to 255 channels. */
export function hsl(h: number, s: number, l: number): Rgb {
  const a = s * Math.min(l, 1 - l)
  const channel = (n: number) => {
    const k = (n + h / 30) % 12
    return Math.round((l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))) * 255)
  }
  return [channel(0), channel(8), channel(4)]
}

/** [rgb] darker (below 1) or lighter (above), each channel kept in 0 to 255. */
export const shadeOf = (rgb: Rgb, factor: number): Rgb => [
  Math.max(0, Math.min(255, Math.round(rgb[0] * factor))),
  Math.max(0, Math.min(255, Math.round(rgb[1] * factor))),
  Math.max(0, Math.min(255, Math.round(rgb[2] * factor))),
]
