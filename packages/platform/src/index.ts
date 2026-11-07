// The spec, one file per area. Written first by scripts/extract.ts from the Kotlin runtime.
import type { PlatformFile } from './types.ts'
import attributes from './spec/attributes.ts'
import bots from './spec/bots.ts'
import chunkGrid from './spec/chunkGrid.ts'
import commands from './spec/commands.ts'
import datapackOps from './spec/datapackOps.ts'
import displays from './spec/displays.ts'
import entities from './spec/entities.ts'
import interop from './spec/interop.ts'
import items from './spec/items.ts'
import loot from './spec/loot.ts'
import mannequins from './spec/mannequins.ts'
import mobGoalOps from './spec/mobGoalOps.ts'
import pathfindingOps from './spec/pathfindingOps.ts'
import platform from './spec/platform.ts'
import platformEvents from './spec/platformEvents.ts'
import playerAdmin from './spec/playerAdmin.ts'
import playerOps from './spec/playerOps.ts'
import resourcePacks from './spec/resourcePacks.ts'
import screenOps from './spec/screenOps.ts'
import skyOps from './spec/skyOps.ts'
import teams from './spec/teams.ts'
import windows from './spec/windows.ts'
import worldAdmin from './spec/worldAdmin.ts'
import worlds from './spec/worlds.ts'

export * from './types.ts'

/** Every file of the spec. */
export const spec: PlatformFile[] = [
  attributes,
  bots,
  chunkGrid,
  commands,
  datapackOps,
  displays,
  entities,
  interop,
  items,
  loot,
  mannequins,
  mobGoalOps,
  pathfindingOps,
  platform,
  platformEvents,
  playerAdmin,
  playerOps,
  resourcePacks,
  screenOps,
  skyOps,
  teams,
  windows,
  worldAdmin,
  worlds,
]
