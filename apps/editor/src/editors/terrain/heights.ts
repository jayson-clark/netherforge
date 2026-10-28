/**
 * Which heights the terrain preview draws a world of. A terrain doesn't know the worlds it shapes; a world
 * in `netherforge.json` that names it and a dimension type does (docs/format/dimension-type.md): its heights are the
 * dimension's. The overworld's is always a choice, and the first.
 */
import {
  DIMENSION_TYPE_DEFAULTS,
  resolveReference,
  WORLD_HEIGHT_OVERWORLD,
  type DimensionTypeFile,
  type ProjectManifest,
} from '@/core/format'

export interface HeightChoice {
  /** The world it's for, or `''` for the overworld's own heights. */
  key: string
  label: string
  minY: number
  /** One above the highest block. */
  maxY: number
}

export const OVERWORLD_CHOICE: HeightChoice = {
  key: '',
  label: `Overworld (y ${WORLD_HEIGHT_OVERWORLD.minY} to ${WORLD_HEIGHT_OVERWORLD.maxY - 1})`,
  minY: WORLD_HEIGHT_OVERWORLD.minY,
  maxY: WORLD_HEIGHT_OVERWORLD.maxY,
}

/**
 * The overworld, then each world of [manifest] (in name order) naming terrain [terrain] (an id of the project in
 * namespace [home]) and a dimension type of the project's that [dimensions] has (by id, as the files are now; one
 * that doesn't read is left out). References resolve as format resolves them.
 */
export function heightChoices(
  manifest: ProjectManifest | null | undefined,
  home: string,
  terrain: string,
  dimensions: Record<string, DimensionTypeFile | null>,
): HeightChoice[] {
  const choices = [OVERWORLD_CHOICE]
  const own = `${home}:`
  for (const [world, config] of Object.entries(manifest?.worlds ?? {}).sort(([a], [b]) =>
    a.localeCompare(b),
  )) {
    if (!config.terrain || !config.dimensionType) continue
    if (resolveReference('terrain', config.terrain, home) !== own + terrain) continue
    const key = resolveReference('dimension_type', config.dimensionType, home)
    if (!key?.startsWith(own)) continue
    const dimension = dimensions[key.slice(own.length)]
    if (!dimension) continue
    const minY = dimension.minY ?? DIMENSION_TYPE_DEFAULTS.minY
    const maxY = minY + (dimension.height ?? DIMENSION_TYPE_DEFAULTS.height)
    choices.push({
      key: world,
      label: `${world}: ${config.dimensionType} (y ${minY} to ${maxY - 1})`,
      minY,
      maxY,
    })
  }
  return choices
}

/**
 * The choice [chosen] names (a world, or `''` for the overworld); with none chosen (null) or one no longer there,
 * the first world's, else the overworld.
 */
export function pickHeights(choices: HeightChoice[], chosen: string | null): HeightChoice {
  return (
    (chosen === null ? undefined : choices.find((choice) => choice.key === chosen)) ??
    choices[1] ??
    OVERWORLD_CHOICE
  )
}
