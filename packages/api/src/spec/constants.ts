import type { Constant } from '../types.ts'

/**
 * The runtime's numbers the docs quote: each one is the runtime's to choose (a limit, a default), and the spec only
 * says what it is. They go into `api.json`, and the runtime's `ConformanceTest` holds every one to the Kotlin
 * constants it names, so a change on either side without the other fails there instead of making the docs wrong.
 */
function constant(
  name: string,
  value: number,
  doc: string,
  ...runtime: string[]
): Constant & { value: number } {
  return { name, value, doc, runtime }
}

const CENTITY_PATHS = 'dev.netherforge.plugin.centity.CentityPaths'
const MOB_PATHS = 'dev.netherforge.plugin.mob.MobPaths'

export const TARGET_DISTANCE = constant(
  'TARGET_DISTANCE',
  20,
  'How far `target_block` and `target_entity` look by default, in blocks.',
  'dev.netherforge.plugin.api.EntityImplKt#TARGET_DISTANCE',
)

export const PATH_REACH = constant(
  'PATH_REACH',
  2,
  "How close a mob's or a centity's walk has to come to its target to count as reached, in blocks.",
  `${CENTITY_PATHS}#PATH_REACH`,
  `${MOB_PATHS}#PATH_REACH`,
)

export const REPATH_TICKS = constant(
  'REPATH_TICKS',
  10,
  'How often a walk after an entity or a centity looks for a new path to where it is, in ticks.',
  `${CENTITY_PATHS}#REPATH_TICKS`,
  `${MOB_PATHS}#REPATH_TICKS`,
)

export const DEFAULT_SPEED = constant(
  'DEFAULT_SPEED',
  4.3,
  "`CentityPathOptions.speed`'s default.",
  `${CENTITY_PATHS}#DEFAULT_SPEED`,
)
export const MAX_SPEED = constant(
  'MAX_SPEED',
  20,
  "`CentityPathOptions.speed`'s most.",
  `${CENTITY_PATHS}#MAX_SPEED`,
)
export const DEFAULT_STEP_HEIGHT = constant(
  'DEFAULT_STEP_HEIGHT',
  1,
  "`CentityPathOptions.step_height`'s default.",
  `${CENTITY_PATHS}#DEFAULT_STEP_HEIGHT`,
)
export const DEFAULT_MAX_DROP = constant(
  'DEFAULT_MAX_DROP',
  3,
  "`CentityPathOptions.max_drop`'s default.",
  `${CENTITY_PATHS}#DEFAULT_MAX_DROP`,
)
export const DEFAULT_RANGE = constant(
  'DEFAULT_RANGE',
  48,
  "`CentityPathOptions.range`'s default.",
  `${CENTITY_PATHS}#DEFAULT_RANGE`,
)
export const MAX_RANGE = constant(
  'MAX_RANGE',
  128,
  "`CentityPathOptions.range`'s most.",
  `${CENTITY_PATHS}#MAX_RANGE`,
)
export const MAX_WIDTH = constant(
  'MAX_WIDTH',
  8,
  "`CentityPathOptions.width`'s most.",
  `${CENTITY_PATHS}#MAX_WIDTH`,
)
export const MAX_HEIGHT = constant(
  'MAX_HEIGHT',
  16,
  "`CentityPathOptions.height`'s most.",
  `${CENTITY_PATHS}#MAX_HEIGHT`,
)

export const START_DROP = constant(
  'START_DROP',
  64,
  "How far below a centity's feet a walk looks for ground to start from, in blocks.",
  `${CENTITY_PATHS}#START_DROP`,
)

export const NODE_LIMIT = constant(
  'NODE_LIMIT',
  4096,
  'How many places one path search may look at.',
  'dev.netherforge.plugin.pathing.PathSearchKt#NODE_LIMIT',
)

/** Every constant above, for `api.json`. */
export const constants: Constant[] = [
  TARGET_DISTANCE,
  PATH_REACH,
  REPATH_TICKS,
  DEFAULT_SPEED,
  MAX_SPEED,
  DEFAULT_STEP_HEIGHT,
  DEFAULT_MAX_DROP,
  DEFAULT_RANGE,
  MAX_RANGE,
  MAX_WIDTH,
  MAX_HEIGHT,
  START_DROP,
  NODE_LIMIT,
]
