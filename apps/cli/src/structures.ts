import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { encode } from 'fast-png'
import * as nf from '@netherforge/format'
import { BUILD_LIMITS, ID_PATTERN } from '@netherforge/format/constants'
import type { ClassifiedPath } from '@netherforge/format/types'
import { readNbt } from '@netherforge/structures/nbt'
import {
  buildParts,
  fitsOneFile,
  gridOf,
  gridOfFiles,
  partFile,
  partsOf,
  structureFile,
  unknownBlocks,
} from '@netherforge/structures/parts'
import { StructurePicture, type View } from '@netherforge/structures/picture'
import { readSchematic } from '@netherforge/structures/schematic'
import { parseStructure } from '@netherforge/structures/structure'
import { loadGameData, type Outcome } from './commands.ts'
import { listFiles } from './project.ts'

/** A structure (`structures/<id>.nbt`) or a build (`builds/<id>/`) of the project, as the project places it. */
export interface Placeable {
  kind: 'structure' | 'build'
  id: string
  /** The structure's file, or the build's folder (project paths). */
  location: string
}

const fail = (message: string, code = 2): Outcome => ({ out: [], err: [message], code })

/** What a project path is, when it's a structure's file or any part of a build. */
export function placeableAt(relative: string): Placeable | null {
  const found = JSON.parse(nf.classify(relative)) as ClassifiedPath | null
  if (!found?.id || found.package) return null
  if (found.kind === 'structure' && found.role === 'main')
    return { kind: 'structure', id: found.id, location: relative }
  if (found.kind !== 'build') return null
  const folder =
    found.role === 'folder'
      ? relative
      : relative.slice(0, relative.length - (found.rest ?? '').length - 1)
  return { kind: 'build', id: found.id, location: folder }
}

/** The project's structure or build [id], wherever its group folders put it. */
export function findPlaceable(root: string, id: string): Placeable | null {
  for (const file of listFiles(root)) {
    const found = placeableAt(file)
    if (found?.id === id) return found
  }
  return null
}

/** What `netherforge preview` takes for a structure or a build. */
export interface StructurePreviewOptions {
  /** `top` (the default) or `iso`. */
  view?: string
  /** `x`, `y` or `z`: a slice through [at] along that axis, counted from the smallest corner. */
  slice?: string
  at?: number
  /** Pixels a block is across. */
  scale?: number
  out?: string
}

/** [placeable]'s size: a structure's, or a build's whole parts and what its last part holds. */
async function sizeOf(
  root: string,
  placeable: Placeable,
  files: string[],
): Promise<[number, number, number]> {
  const read = async (file: string) =>
    parseStructure(await readNbt(readFileSync(path.join(root, file))))
  if (placeable.kind === 'structure') {
    const structure = await read(placeable.location)
    return structure.size
  }
  const names = files
    .filter((it) => it.startsWith(`${placeable.location}/`))
    .map((it) => it.slice(placeable.location.length + 1))
  const grid = gridOfFiles(names)
  const last = await read(
    `${placeable.location}/${partFile(grid[0] - 1, grid[1] - 1, grid[2] - 1)}`,
  )
  return [0, 1, 2].map((i) => (grid[i]! - 1) * BUILD_LIMITS.partSide + last.size[i]!) as [
    number,
    number,
    number,
  ]
}

/**
 * `netherforge preview structures/<id>.nbt` (or `builds/<id>/`, or `--structure <id>`): a picture of it from
 * above (the default), isometric (`--view iso`), or a slice through it (`--slice x|y|z --at <n>`), as the editor's
 * structure and build screens draw it, written as a PNG to [StructurePreviewOptions.out].
 */
export async function previewStructure(
  root: string,
  placeable: Placeable,
  options: StructurePreviewOptions,
): Promise<Outcome> {
  if (!options.out) return fail('Say where to write the picture: --out <file>.png.')
  let view: View
  if (options.slice !== undefined) {
    if (options.view !== undefined) return fail('Give --view or --slice, not both.')
    if (options.slice !== 'x' && options.slice !== 'y' && options.slice !== 'z') {
      return fail(`--slice is x, y or z, not "${options.slice}".`)
    }
    if (options.at === undefined)
      return fail(
        `--slice ${options.slice} needs --at: which layer, counted from 0 at the smallest corner.`,
      )
    view = { kind: 'slice', axis: options.slice, at: options.at }
  } else if (options.view === undefined || options.view === 'top' || options.view === 'iso') {
    view = { kind: options.view === 'iso' ? 'iso' : 'top' }
  } else {
    return fail(`--view is top or iso, not "${options.view}".`)
  }
  const scale = options.scale ?? (view.kind === 'iso' ? 2 : 4)
  if (!Number.isInteger(scale) || scale < 1 || scale > 32)
    return fail('--scale is a whole number from 1 to 32.')

  const files = listFiles(root)
  let size: [number, number, number]
  try {
    size = await sizeOf(root, placeable, files)
  } catch (error) {
    return fail(
      `${placeable.location} can't be read as a structure: ${(error as Error).message}`,
      1,
    )
  }
  let picture: StructurePicture
  try {
    picture = new StructurePicture(size, view)
  } catch (error) {
    return fail(`--at: ${(error as Error).message}.`)
  }
  const pieces: [string, number[]][] =
    placeable.kind === 'structure'
      ? [[placeable.location, [0, 0, 0]]]
      : partsOf(gridOf(size)).map(([x, y, z]) => [
          `${placeable.location}/${partFile(x, y, z)}`,
          [x, y, z].map((it) => it * BUILD_LIMITS.partSide),
        ])
  for (const [file, origin] of pieces) {
    // A slice reads only the parts it cuts through.
    if (view.kind === 'slice') {
      const axis = { x: 0, y: 1, z: 2 }[view.axis]
      if (view.at < origin[axis]! || view.at >= origin[axis]! + BUILD_LIMITS.partSide) continue
    }
    try {
      picture.add(parseStructure(await readNbt(readFileSync(path.join(root, file)))), origin)
    } catch (error) {
      return fail(`${file} can't be read as a structure: ${(error as Error).message}`, 1)
    }
  }
  const drawn = picture.draw(scale)
  const png = encode({
    width: drawn.width,
    height: drawn.height,
    data: drawn.data,
    channels: 4,
    depth: 8,
  })
  mkdirSync(path.dirname(path.resolve(options.out)), { recursive: true })
  writeFileSync(options.out, png)
  const what =
    view.kind === 'slice'
      ? `a slice along ${view.axis} at ${view.at}`
      : view.kind === 'iso'
        ? 'isometric, from the south-east'
        : 'from above'
  const hex = (rgb: readonly number[]) =>
    `#${rgb.map((it) => it.toString(16).padStart(2, '0')).join('')}`
  return {
    out: [
      `${options.out}: ${drawn.width}x${drawn.height}`,
      `${placeable.location}: ${size.join(' × ')} blocks, ${what}; empty blocks are transparent`,
      ...drawn.legend
        .slice(0, LEGEND_LINES)
        .map((it) => `${hex(it.color)} ${it.kind} (${it.count})`),
      ...(drawn.legend.length > LEGEND_LINES
        ? [`and ${drawn.legend.length - LEGEND_LINES} more kinds of block`]
        : []),
    ],
    err: [],
    code: 0,
  }
}

/** How many kinds of block a preview names. */
const LEGEND_LINES = 24

/** What `netherforge import` takes besides the file. */
export interface ImportOptions {
  /** The id it's imported as. */
  as?: string
  /** A build even when it fits in a structure. */
  build?: boolean
  /** Replace what's there under that id. */
  replace?: boolean
  gameData?: string
}

/**
 * `netherforge import <file> --as <id>`: a WorldEdit (`.schem`) or Litematica (`.litematic`) schematic into the
 * project, as `structures/<id>.nbt` when it fits in one (48 a side) and `builds/<id>/` when it doesn't (or with
 * `--build`). Says what it skipped (entities) and which blocks the game data doesn't know.
 */
export async function importSchematic(
  root: string,
  file: string,
  options: ImportOptions,
  env: NodeJS.ProcessEnv = process.env,
): Promise<Outcome> {
  const id = options.as
  if (!id) return fail('Say what to call it: --as <id>.')
  if (!ID_PATTERN.test(id))
    return fail(
      `"${id}" can't be an id: lowercase letters, digits and _, starting with a letter or digit.`,
    )
  let bytes: Buffer
  try {
    bytes = readFileSync(file)
  } catch {
    return fail(`${file}: no such file.`)
  }
  let volume
  try {
    volume = readSchematic(await readNbt(bytes))
  } catch (error) {
    return fail(`${file} can't be imported: ${(error as Error).message}`, 1)
  }
  const asBuild = options.build === true || !fitsOneFile(volume.size)
  const target = asBuild ? `builds/${id}` : `structures/${id}.nbt`
  const taken = findPlaceable(root, id)
  if (taken && !options.replace) {
    return fail(
      `${taken.location} is already called "${id}": give another --as, or --replace to replace it.`,
    )
  }
  const grid = gridOf(volume.size)
  if (asBuild && grid.some((it) => it > BUILD_LIMITS.maxParts)) {
    return fail(
      `${file} is ${volume.size.join(' × ')} blocks; a build is at most ${BUILD_LIMITS.maxParts * BUILD_LIMITS.partSide} along each side.`,
      1,
    )
  }
  if (taken) rmSync(path.join(root, taken.location), { recursive: true, force: true })
  if (asBuild) {
    const folder = path.join(root, target)
    mkdirSync(folder, { recursive: true })
    for await (const part of buildParts(volume))
      writeFileSync(path.join(folder, part.file), part.bytes)
  } else {
    mkdirSync(path.join(root, 'structures'), { recursive: true })
    writeFileSync(path.join(root, target), await structureFile(volume, [0, 0, 0], volume.size))
  }

  const out = [
    `${target}${asBuild ? '/' : ''}: ${volume.size.join(' × ')} blocks${asBuild ? ` in ${grid[0] * grid[1] * grid[2]} parts` : ''}, ` +
      `from a ${volume.format === 'sponge' ? 'WorldEdit' : 'Litematica'} schematic` +
      (volume.dataVersion !== null ? ` saved in data version ${volume.dataVersion}` : ''),
    `${volume.blockEntities.size} block entities (chests, signs…) kept with their blocks`,
  ]
  if (volume.entities > 0)
    out.push(`${volume.entities} entities left out: an import carries blocks only`)
  const manifest = existsSync(path.join(root, 'netherforge.json'))
    ? readFileSync(path.join(root, 'netherforge.json'), 'utf8')
    : null
  const game = loadGameData({ 'netherforge.json': manifest }, options.gameData, env)
  if (game.json === null) {
    out.push(
      "No game data for the project's Minecraft version, so its blocks weren't checked against the game " +
        '(start the dev server from the NetherForge editor once, or pass --game-data <file>).',
    )
  } else {
    const known = new Set(Object.keys((JSON.parse(game.json) as { blocks?: object }).blocks ?? {}))
    const unknown = unknownBlocks(volume, (it) => known.has(it))
    if (unknown.length > 0) {
      out.push(
        `${unknown.length} kind${unknown.length === 1 ? '' : 's'} of block the game doesn't have (an older version's, which the server renames as it reads the parts, or a mod's, which it leaves as air):`,
        ...unknown.map((it) => `  ${it.id} (${it.count})`),
      )
    }
  }
  return { out, err: [], code: 0 }
}
