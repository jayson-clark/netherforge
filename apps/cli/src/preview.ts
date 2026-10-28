import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { encode } from 'fast-png'
import * as nf from '@netherforge/format'
import {
  DIMENSION_TYPE_DEFAULTS,
  WORLD_HEIGHT_OVERWORLD,
  TERRAIN_DEFAULTS,
} from '@netherforge/format/constants'
import type {
  CanonicalResult,
  DimensionTypeFile,
  Problem,
  TerrainMap,
  TerrainScriptError,
  TerrainSlice,
  TerrainStructureInput,
} from '@netherforge/format/types'
import {
  areaColor,
  decorationColor,
  hasScript,
  mapPixels,
  scriptPaths,
  sliceColors,
  slicePixels,
  sliceTop,
  structureInput,
  structuresNamed,
  terrainPreviewer,
  type Rgb,
} from '@netherforge/terrain-preview'
import { loadNodeLua } from '@netherforge/terrain-preview/lua.node'
import { readNbt } from '@netherforge/structures/nbt'
import { parseStructure } from '@netherforge/structures/structure'
import { formatProblem, type Outcome } from './commands.ts'

/** What `netherforge preview` takes besides the file; the defaults are the editor's preview's. */
export interface PreviewOptions {
  /** A whole number, as text. */
  seed?: string
  /** The block at the middle of the map, or of the slice's line. */
  x?: number
  z?: number
  /** The map's width and height in cells. */
  size?: number
  /** Blocks a map cell is across. */
  step?: number
  /** Pixels a map cell, or a block of the slice, is across in the picture. */
  scale?: number
  /** `x`: a slice along the x axis at z [at]; `z`: along the z axis at x [at]. Without it, the map. */
  slice?: string
  /** The slice's line (its z for `x`, its x for `z`). */
  at?: number
  /** Columns in the slice. */
  width?: number
  /** The highest y the slice draws. Default: the highest block that isn't air (or the sea's top). */
  top?: number
  /** The world's lowest block, and one above its highest. */
  minY?: number
  maxY?: number
  /**
   * A dimension type of the project (`deep`, `dimension_types/deep.json`) whose heights the world has, in place of
   * [minY] and [maxY].
   */
  dimensionType?: string
  /** The PNG to write. */
  out?: string
}

/** The editor's view, until it says otherwise. */
const DEFAULTS = { seed: '1337', size: 96, step: 4, mapScale: 4, width: 128, sliceScale: 3 }

/**
 * The heights the preview draws: [options]' if given (`preview` turns `--dimension-type` into them), else a normal
 * overworld's: a generator doesn't know which worlds it will shape.
 */
function worldHeights(options: PreviewOptions): { minY: number; maxY: number } {
  return {
    minY: options.minY ?? WORLD_HEIGHT_OVERWORLD.minY,
    maxY: options.maxY ?? WORLD_HEIGHT_OVERWORLD.maxY,
  }
}

/** A picture, and what its colours mean. */
export interface Rendered {
  width: number
  height: number
  /** Straight RGBA, row by row from the top. */
  pixels: Uint8ClampedArray
  /** One line per colour a person needs named. */
  legend: string[]
  /** What the file's script failed at while it was drawn (the file's own result is drawn there). */
  scriptErrors: TerrainScriptError[]
}

export class PreviewError extends Error {
  constructor(
    message: string,
    readonly problems: Problem[] = [],
  ) {
    super(message)
  }
}

const hex = (rgb: Rgb) => `#${rgb.map((it) => it.toString(16).padStart(2, '0')).join('')}`

/** [pixels] ([width] by [height]) with each pixel [scale] by [scale]. */
function enlarge(
  pixels: Uint8ClampedArray,
  width: number,
  height: number,
  scale: number,
): Uint8ClampedArray {
  if (scale === 1) return pixels
  const out = new Uint8ClampedArray(width * scale * height * scale * 4)
  for (let y = 0; y < height * scale; y += 1) {
    for (let x = 0; x < width * scale; x += 1) {
      const from = (Math.floor(y / scale) * width + Math.floor(x / scale)) * 4
      out.set(pixels.subarray(from, from + 4), (y * width * scale + x) * 4)
    }
  }
  return out
}

/**
 * The picture the editor's preview draws for the terrain [text] (`terrain/<id>.json`) and [options], with
 * [structures] as the project's `.nbt` files read and [sources] the Lua its script runs (by project path; Lua must
 * be loaded for a file with a script, [loadNodeLua]), and [blocks] the project's block ids. Throws a [PreviewError]
 * with the file's problems when it doesn't compile, as it would on a server.
 */
export function renderPreview(
  id: string,
  text: string,
  structures: Record<string, TerrainStructureInput>,
  options: PreviewOptions,
  sources: Record<string, string> = {},
  blocks: readonly string[] = [],
): Rendered {
  const previewer = terrainPreviewer(id, text, structures, sources, blocks)
  try {
    return render(previewer, text, options)
  } finally {
    previewer.close()
  }
}

function render(
  previewer: ReturnType<typeof terrainPreviewer>,
  text: string,
  options: PreviewOptions,
): Rendered {
  const seed = options.seed ?? DEFAULTS.seed
  if (!/^-?\d+$/.test(seed)) throw new PreviewError(`--seed is a whole number, not "${seed}".`)
  const { minY, maxY } = worldHeights(options)
  if (!(minY < maxY)) throw new PreviewError('--min-y must be below --max-y.')
  const x = options.x ?? 0
  const z = options.z ?? 0
  const step = options.step ?? DEFAULTS.step
  if (options.slice === undefined) {
    const cells = options.size ?? DEFAULTS.size
    const x0 = x - Math.floor((cells * step) / 2)
    const z0 = z - Math.floor((cells * step) / 2)
    const result = previewer.map(seed, x0, z0, cells, step, minY, maxY)
    if (result.type === 'failed')
      throw new PreviewError('The terrain has problems.', result.problems)
    if (result.type !== 'map') throw new PreviewError(`The preview answered a ${result.type}.`)
    const map: TerrainMap = result
    const scale = options.scale ?? DEFAULTS.mapScale
    const legend = [
      `map: ${cells}x${cells} cells of ${step} blocks, from x ${x0}, z ${z0}; sea level ${map.seaLevel}`,
    ]
    const used = new Set(map.areas)
    for (const index of [...used].sort((a, b) => a - b)) {
      legend.push(
        `${hex(areaColor(index))} area ${map.areaNames[index]} (${map.biomes[index] ?? 'no biome'})`,
      )
    }
    legend.push('blue: sea, darker where deeper')
    const marks = new Set<number>()
    for (let k = 1; k < map.decorations.length; k += 2) marks.add(map.decorations[k]!)
    for (const index of [...marks].sort((a, b) => a - b)) {
      legend.push(`${hex(decorationColor(index))} decoration ${map.decorationNames[index]} starts`)
    }
    return {
      width: cells * scale,
      height: cells * scale,
      pixels: enlarge(mapPixels(map), cells, cells, scale),
      legend,
      scriptErrors: map.scriptErrors,
    }
  }
  if (options.slice !== 'x' && options.slice !== 'z') {
    throw new PreviewError(`--slice is x or z, not "${options.slice}".`)
  }
  const alongX = options.slice === 'x'
  const columns = options.width ?? DEFAULTS.width
  const at = options.at ?? 0
  const from = (alongX ? x : z) - Math.floor(columns / 2)
  const result = previewer.slice(seed, alongX, at, from, columns, minY, maxY)
  if (result.type === 'failed') throw new PreviewError('The terrain has problems.', result.problems)
  if (result.type !== 'slice') throw new PreviewError(`The preview answered a ${result.type}.`)
  const slice: TerrainSlice = result
  const defaults = JSON.parse(text) as {
    terrain?: { fluid?: string }
    stone?: { block?: string }
  }
  const colors = sliceColors(
    slice,
    defaults.terrain?.fluid ?? TERRAIN_DEFAULTS.fluid,
    defaults.stone?.block ?? TERRAIN_DEFAULTS.stone,
  )
  if (options.top !== undefined && (options.top < minY || options.top >= maxY)) {
    throw new PreviewError(`--top is a height in the world, from ${minY} to ${maxY - 1}.`)
  }
  const top = options.top !== undefined ? options.top + 1 : sliceTop(slice)
  const drawn = slicePixels(slice, colors, top, options.scale ?? DEFAULTS.sliceScale)
  const legend = [
    `slice: ${columns} columns along ${alongX ? `x at z ${at}` : `z at x ${at}`}, from ${alongX ? 'x' : 'z'} ${from}; y ${slice.minY} to ${top - 1}; sea level ${slice.seaLevel}`,
    'air is transparent',
  ]
  const present = new Set<number>()
  for (const runs of slice.columns) for (let i = 0; i < runs.length; i += 2) present.add(runs[i]!)
  for (const index of [...present].sort((a, b) => a - b)) {
    const color = colors[index]
    if (!color) continue
    const entry = slice.palette[index]!
    legend.push(`${hex(color)} ${entry.custom ? `custom block ${entry.label}` : entry.label}`)
  }
  return {
    width: drawn.width,
    height: drawn.height,
    pixels: drawn.pixels,
    legend,
    scriptErrors: slice.scriptErrors,
  }
}

/** Every file under [root] (project paths), leaving out `.netherforge/` and dot folders. */
function filesUnder(root: string, at = ''): string[] {
  let names: string[]
  try {
    names = readdirSync(path.join(root, at))
  } catch {
    return []
  }
  return names.flatMap((name) => {
    if (name.startsWith('.')) return []
    const rel = at ? `${at}/${name}` : name
    return statSync(path.join(root, rel)).isDirectory() ? filesUnder(root, rel) : [rel]
  })
}

/** The Lua generator [id]'s script may run, read from the project: its own and the modules'. */
function scriptSources(root: string, id: string): Record<string, string> {
  const files = [`terrain/${id}.lua`, ...filesUnder(root, 'modules')]
  const sources: Record<string, string> = {}
  for (const file of scriptPaths(id, files)) {
    try {
      sources[file] = readFileSync(path.join(root, file), 'utf8')
    } catch {
      // Not there: the preview says the script didn't load, as the server would.
    }
  }
  return sources
}

/** The project's block ids: the folders under `blocks/` (in group folders too) with a `block.json`. */
function projectBlocks(root: string): string[] {
  return filesUnder(root, 'blocks')
    .filter((file) => file.endsWith('/block.json'))
    .map((file) => path.posix.basename(path.posix.dirname(file)))
    .sort()
}

/** A script's failure as `file:line: warning: ...`, the way `check` writes problems. */
function scriptLine(error: TerrainScriptError): string {
  const where = error.line != null ? `${error.file}:${error.line}` : error.file
  const what = error.stage === 'load' ? "the script didn't load" : `its ${error.stage} stage failed`
  return `${where}: warning [terrain.script-failed]: ${what}, so the file's own result is drawn there: ${error.message}`
}

/** A positive whole number option, or the message that says it isn't. */
function positive(name: string, value: number | undefined, max: number): string | null {
  if (value === undefined) return null
  return Number.isInteger(value) && value >= 1 && value <= max
    ? null
    : `--${name} is a whole number from 1 to ${max}.`
}

/**
 * The heights of the project's dimension type [text] (a reference, as `netherforge.json`'s `worlds.<name>.dimensionType`
 * writes one) in the project at [root]: its `minY` and `minY + height`, each the overworld's when absent, as the
 * server makes a world of it. A message when it isn't one; problems when its file doesn't parse.
 */
function dimensionHeights(root: string, text: string): { minY: number; maxY: number } | Outcome {
  const fail = (message: string): Outcome => ({ out: [], err: [message], code: 2 })
  let namespace: string | undefined
  try {
    namespace = (
      JSON.parse(readFileSync(path.join(root, 'netherforge.json'), 'utf8')) as {
        namespace?: string
      }
    ).namespace
  } catch {
    // Said below: without a namespace no reference resolves.
  }
  if (!namespace) return fail("netherforge.json doesn't say the project's namespace.")
  const key = nf.resolveReference('dimension_type', text, namespace)
  if (!key) return fail(`--dimension-type is a dimension type's id, not "${text}".`)
  if (!key.startsWith(`${namespace}:`)) {
    return fail(
      `--dimension-type names the project's own dimension types (dimension_types/<id>.json), not "${text}".`,
    )
  }
  const file = `dimension_types/${key.slice(namespace.length + 1)}.json`
  let source: string
  try {
    source = readFileSync(path.join(root, file), 'utf8')
  } catch {
    return fail(`--dimension-type ${text}: there's no ${file}.`)
  }
  const parsed = JSON.parse(nf.canonicalize('dimension_type', file, source)) as CanonicalResult
  if (parsed.text == null) {
    return {
      out: parsed.problems.map((it) => formatProblem({ ...it, file: it.file ?? file })),
      err: [],
      code: 1,
    }
  }
  const dimension = JSON.parse(parsed.text) as DimensionTypeFile
  const minY = dimension.minY ?? DIMENSION_TYPE_DEFAULTS.minY
  return { minY, maxY: minY + (dimension.height ?? DIMENSION_TYPE_DEFAULTS.height) }
}

/**
 * `netherforge preview terrain/<id>.json`: draws what the editor's preview draws for the generator and writes it
 * as a PNG to [PreviewOptions.out], so a generator can be checked without the editor. The structures its
 * decorations place are read from the project's `structures/<name>.nbt`, as the editor reads them.
 */
export async function preview(
  root: string,
  file: string,
  options: PreviewOptions,
): Promise<Outcome> {
  const fail = (message: string, code = 2): Outcome => ({ out: [], err: [message], code })
  const relative = path.relative(root, file).split(path.sep).join('/')
  const id = /^terrain\/([^/]+)\.json$/.exec(relative)?.[1]
  if (!id) return fail(`${relative} isn't a terrain: give terrain/<id>.json.`)
  if (!options.out) return fail('Say where to write the picture: --out <file>.png.')
  const bad =
    positive('size', options.size, 512) ??
    positive('step', options.step, 64) ??
    positive('scale', options.scale, 32) ??
    positive('width', options.width, 4096)
  if (bad) return fail(bad)
  let heights = options
  if (options.dimensionType !== undefined) {
    if (options.minY !== undefined || options.maxY !== undefined) {
      return fail('Give --dimension-type or --min-y and --max-y, not both.')
    }
    const found = dimensionHeights(root, options.dimensionType)
    if ('code' in found) return found
    heights = { ...options, ...found }
  }
  let text: string
  try {
    text = readFileSync(file, 'utf8')
  } catch {
    return fail(`${relative}: no such file.`)
  }
  let parsed: { decorations?: Record<string, { structure?: string }> } = {}
  try {
    parsed = JSON.parse(text) as typeof parsed
  } catch {
    // Said by the preview below, as a problem with a line.
  }
  const structures: Record<string, TerrainStructureInput> = {}
  for (const name of structuresNamed(parsed.decorations)) {
    try {
      const bytes = readFileSync(path.join(root, 'structures', `${name}.nbt`))
      structures[name] = structureInput(parseStructure(await readNbt(bytes)))
    } catch {
      // Not there, or not a structure: not placed, as the server places none.
    }
  }
  const scripted = hasScript(text)
  if (scripted) await loadNodeLua()
  let rendered: Rendered
  try {
    rendered = renderPreview(
      id,
      text,
      structures,
      heights,
      scripted ? scriptSources(root, id) : {},
      scripted ? projectBlocks(root) : [],
    )
  } catch (error) {
    if (!(error instanceof PreviewError)) throw error
    if (error.problems.length === 0) return fail(error.message)
    const problems = error.problems.map((it) => ({ ...it, file: it.file ?? relative }))
    const errors = problems.filter((it) => it.severity === 'error').length
    return {
      out: [
        ...problems.map(formatProblem),
        `${errors} error${errors === 1 ? '' : 's'}, ${problems.length - errors} warning${problems.length - errors === 1 ? '' : 's'}.`,
      ],
      err: [],
      code: 1,
    }
  }
  const png = encode({
    width: rendered.width,
    height: rendered.height,
    data: rendered.pixels,
    channels: 4,
    depth: 8,
  })
  mkdirSync(path.dirname(path.resolve(options.out)), { recursive: true })
  writeFileSync(options.out, png)
  return {
    out: [
      `${options.out}: ${rendered.width}x${rendered.height}`,
      ...rendered.legend,
      ...rendered.scriptErrors.map(scriptLine),
    ],
    err: [],
    code: 0,
  }
}
