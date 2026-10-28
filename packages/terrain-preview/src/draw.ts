/**
 * What the terrain preview draws (in the editor, and for `netherforge preview`), from format's numbers (`TerrainMap`, `TerrainSlice`):
 * pixels for the top-down map and the vertical slice, colours for blocks and biome areas, and what's
 * under a pointer. Pure: the canvases only put these on the screen.
 *
 * The colours are the editor's own, since it ships no Minecraft pictures: a block is a colour of its
 * place in the palette (stone grey, the sea blue, the project's own blocks pink, so ore of a
 * custom block stands out), and the legend names each.
 */
import type { TerrainMap, TerrainPaletteEntry, TerrainSlice } from '@netherforge/format/types'
import { hsl, shadeOf, type Rgb } from '@netherforge/structures/color'

/** What the colours here are (the structures package's, which its pictures use too). */
export type { Rgb }

/** The hues biome areas take in turn: none of them blue, which is the sea's. */
const AREA_HUES = [38, 110, 330, 12, 280, 160, 68, 350]

/** The colour of biome area [index]: the next hue, a little paler each time round. */
export function areaColor(index: number): Rgb {
  const round = Math.floor(index / AREA_HUES.length)
  return hsl(AREA_HUES[index % AREA_HUES.length]!, 0.5, Math.max(0.3, 0.5 - round * 0.08))
}

/** The hues decorations' marks take in turn: bright, unlike the areas' muted ground. */
const DECORATION_HUES = [0, 52, 285, 190, 22, 120, 320, 240]

/** The colour of decoration [index]'s marks on the map. */
export function decorationColor(index: number): Rgb {
  return hsl(DECORATION_HUES[index % DECORATION_HUES.length]!, 0.9, index < 8 ? 0.6 : 0.75)
}

const SEA: Rgb = [48, 96, 184]
const STONE: Rgb = [126, 126, 130]
const CUSTOM: Rgb = [232, 60, 140]

/** The colour of palette entry [index] ([label] is its block); null for air, which isn't drawn. */
export function blockColor(
  index: number,
  entry: TerrainPaletteEntry,
  fluid: string,
  stone: string,
): Rgb | null {
  if (index === 0) return null
  if (entry.custom) return CUSTOM
  if (entry.label === fluid) return SEA
  if (entry.label === stone) return STONE
  return hsl((index * 137.508) % 360, 0.5, 0.52)
}

/**
 * The colour of each palette entry of [slice], given the generator's [fluid] and [stone] blocks (with or without
 * `minecraft:`; they default as the format says): null for air.
 */
export function sliceColors(slice: TerrainSlice, fluid: string, stone: string): (Rgb | null)[] {
  const full = (id: string) => (id.includes(':') ? id : `minecraft:${id}`)
  return slice.palette.map((entry, index) => blockColor(index, entry, full(fluid), full(stone)))
}

/**
 * RGBA pixels of the map, one per cell: sea where the ground is under it (deeper is darker), else the biome area's
 * colour lit from the north-west; a cell where a decoration on the surface or the sea floor starts is its colour.
 */
export function mapPixels(map: TerrainMap): Uint8ClampedArray {
  const n = map.cells
  const out = new Uint8ClampedArray(n * n * 4)
  const at = (x: number, z: number) =>
    map.heights[Math.max(0, Math.min(n - 1, z)) * n + Math.max(0, Math.min(n - 1, x))]!
  for (let z = 0; z < n; z += 1) {
    for (let x = 0; x < n; x += 1) {
      const height = map.heights[z * n + x]!
      let rgb: Rgb
      if (height < map.seaLevel) {
        rgb = shadeOf(SEA, 1.1 - Math.min(1, (map.seaLevel - height) / 40) * 0.5)
      } else {
        // The ground slopes down away from the light: brighter where it rises towards the north-west.
        const slope = (at(x + 1, z + 1) - at(x - 1, z - 1)) / (2 * map.step)
        const altitude = Math.min(1, (height - map.seaLevel) / 120)
        const factor = 0.85 + altitude * 0.25 - Math.max(-0.35, Math.min(0.35, slope * 0.8))
        rgb = shadeOf(areaColor(map.areas[z * n + x]!), factor)
      }
      const i = (z * n + x) * 4
      out[i] = rgb[0]
      out[i + 1] = rgb[1]
      out[i + 2] = rgb[2]
      out[i + 3] = 255
    }
  }
  for (let k = 0; k + 1 < map.decorations.length; k += 2) {
    const i = map.decorations[k]! * 4
    const [r, g, b] = decorationColor(map.decorations[k + 1]!)
    out[i] = r
    out[i + 1] = g
    out[i + 2] = b
  }
  return out
}

/** The decorations that start in map cell [cell], by index (in [TerrainMap.decorationNames]), each once. */
export function decorationsAt(map: TerrainMap, cell: number): number[] {
  const out = new Set<number>()
  for (let k = 0; k + 1 < map.decorations.length; k += 2) {
    if (map.decorations[k] === cell) out.add(map.decorations[k + 1]!)
  }
  return [...out].sort((a, b) => a - b)
}

/** The palette index at block height [y] of a slice's column [column] (a run-length list), 0 above and below it. */
export function blockAt(slice: TerrainSlice, column: number, y: number): number {
  const runs = slice.columns[column]
  if (!runs || y < slice.minY || y >= slice.maxY) return 0
  let at = slice.minY
  for (let i = 0; i < runs.length; i += 2) {
    at += runs[i + 1]!
    if (y < at) return runs[i]!
  }
  return 0
}

/** One above the highest block of any column that isn't air, so the picture leaves out the empty sky. */
export function sliceTop(slice: TerrainSlice): number {
  let top = slice.minY
  for (const runs of slice.columns) {
    let at = slice.minY
    for (let i = 0; i < runs.length; i += 2) {
      at += runs[i + 1]!
      if (runs[i] !== 0) top = Math.max(top, at)
    }
  }
  return Math.max(top, slice.seaLevel + 1)
}

/**
 * RGBA pixels of a slice: each block is [scale] by [scale] pixels, the lowest block of the picture at the
 * bottom, [top] (from [sliceTop]) just above the picture's top. Air is transparent; the sea is drawn
 * translucent so the ground under it shows.
 */
export function slicePixels(
  slice: TerrainSlice,
  colors: readonly (Rgb | null)[],
  top: number,
  scale: number,
): { width: number; height: number; pixels: Uint8ClampedArray } {
  const width = slice.width * scale
  const height = (top - slice.minY) * scale
  const pixels = new Uint8ClampedArray(width * height * 4)
  for (let column = 0; column < slice.width; column += 1) {
    const runs = slice.columns[column] ?? []
    let at = slice.minY
    for (let i = 0; i < runs.length; i += 2) {
      const index = runs[i]!
      const length = runs[i + 1]!
      const color = colors[index]
      const from = at
      at += length
      if (!color) continue
      for (let y = from; y < Math.min(at, top); y += 1) {
        const row = height - (y - slice.minY + 1) * scale
        for (let dy = 0; dy < scale; dy += 1) {
          for (let dx = 0; dx < scale; dx += 1) {
            const o = ((row + dy) * width + column * scale + dx) * 4
            pixels[o] = color[0]
            pixels[o + 1] = color[1]
            pixels[o + 2] = color[2]
            pixels[o + 3] = color === SEA ? 150 : 255
          }
        }
      }
    }
  }
  return { width, height, pixels }
}

/** A coordinate as the editor says it to a person. */
export const place = (x: number, z: number) => `x ${x}, z ${z}`
