import type { ApiSpec, Surface } from '../types.ts'
import { centityClass, centityShapes, nodeClass } from './centity.ts'
import { ARGUMENT_TYPES, commandShapes } from './commands.ts'
import { constants } from './constants.ts'
import { shapes } from './events.ts'
import { gameShapes } from './gameEvents.ts'
import {
  buttonClass,
  dialogClass,
  itemMatchShape,
  menuClass,
  menuShapes,
  slotClass,
} from './menu.ts'
import { nfClasses } from './nf.ts'
import { cutsceneClass, cutsceneShapes } from './cutscenes.ts'
import { playbackClass, screenClass, screenShapes } from './screens.ts'
import { effectClass, particleShapes } from './particles.ts'
import { menuTemplateClass } from './templates.ts'
import { aliases, values } from './values.ts'
import { fileClass, senderClass, subscriptionClass, taskClass } from './world.ts'
import {
  droppedItemClass,
  entityClass,
  entityShapes,
  livingClass,
  mannequinClass,
  mobClass,
  playerClass,
} from './entities.ts'
import { bossBarClass, inventoryClass, inventoryShapes, sidebarClass } from './inventories.ts'
import { databaseClass, databaseShapes } from './db.ts'
import { botClass, botShapes } from './bots.ts'
import { connectionShapes, httpShapes, httpStreamClass, webSocketClass } from './http.ts'
import { oauthFlowClass, routeClass, webResponseClass, webShapes } from './web.ts'
import { wasmInstanceClass, wasmProcessClass, wasmShapes } from './wasm.ts'
import { networkChannelClass, networkMapClass, networkShapes } from './network.ts'
import { networkEventShapes } from './networkEvents.ts'
import { blockShapes, customBlockClass, projectBlockClass } from './blocks.ts'
import { lootShapes } from './loot.ts'
import { hudClass, hudShapes } from './hud.ts'
import { spawningShapes } from './spawning.ts'
import { itemShapes, projectItemClass } from './items.ts'
import { recipeShapes } from './recipes.ts'
import { statShapes } from './stats.ts'
import { playerShapes } from './players.ts'
import { blockClass, worldBorderClass, worldClass, worldShapes } from './worlds.ts'
import { teamClass, teamShapes } from './teams.ts'
import { scheduleClass, scheduleShapes } from './schedule.ts'
import { randomClass, utilityShapes } from './utilities.ts'
import { testShapes } from './test.ts'

const centitySurface: Surface = {
  name: 'centity',
  doc: "A centity's script (`centities/<id>/*.lua`, named by `script` in `centity.json`). One copy runs per spawned instance, with its own globals; `this` is the instance. The script's body runs every time it starts (spawning, a restart, a reload): that's where it sets up its nodes and registers its handlers, with `this:on(...)` and `this:node(name):on(...)`.",
  globals: [{ name: 'this', type: 'Centity', doc: 'The centity instance this script runs on.' }],
}

const menuSurface: Surface = {
  name: 'menu',
  doc: 'A menu\'s script (`menus/<id>/*.lua`, named by `script` in `menu.json`). Every window gets its own copy, with its own globals, started when the window is made: when it\'s opened for someone, or at load for a `shared` one. The body runs before anyone sees the window, so it\'s where to fill it in and register handlers (`this:on("click", ...)`, `this:slot(13):on("click", ...)`). Its handlers go with the window.',
  globals: [{ name: 'this', type: 'Menu', doc: 'The window this script runs for.' }],
}

const dialogSurface: Surface = {
  name: 'dialog',
  doc: 'A dialog\'s script (`dialogs/<id>/*.lua`, named by `script` in `dialog.json`). There\'s one of each dialog, so its script runs once, from load until the dialog is reloaded. Its body registers handlers: `this:on("press", ...)`, `this:button(key):on("press", ...)`, `this:on("close", ...)`.',
  globals: [{ name: 'this', type: 'Dialog', doc: 'The dialog this script belongs to.' }],
}

const itemSurface: Surface = {
  name: 'item',
  doc: 'A project item\'s script (`items/<id>/*.lua`, named by `script` in `item.json`). There\'s one of each item, so its script runs once (not once per stack), from load until the item is reloaded. Its body registers handlers for what players do with any stack of the item: `this:on("use", ...)`, `this:on("hit", ...)`, `this:on("pickup", ...)`.',
  globals: [{ name: 'this', type: 'ProjectItem', doc: 'The project item this script belongs to.' }],
}

const blockSurface: Surface = {
  name: 'block',
  doc: 'A project block\'s script (`blocks/<id>/*.lua`, named by `script` in `block.json`). There\'s one of each block, so its script runs once (not once per placed block), from load until the block is reloaded. Its body registers handlers for what players do with any block of it: `this:on("click", ...)`, `this:on("break", ...)`, `this:on("tick", ...)`.',
  globals: [
    { name: 'this', type: 'ProjectBlock', doc: 'The project block this script belongs to.' },
  ],
}

const screenSurface: Surface = {
  name: 'screen',
  doc: 'A screen\'s script (`screens/<id>/*.lua`, named by `script` in `screen.json`). There\'s one of each screen, so its script runs once, from load until the screen is reloaded. Its body draws the screen (what a screen shows isn\'t saved, so this is where it\'s drawn after every restart) and registers handlers: `this:on("click", ...)`, `this:on("hover", ...)`.',
  globals: [{ name: 'this', type: 'Screen', doc: 'The screen this script belongs to.' }],
}

const moduleSurface: Surface = {
  name: 'module',
  doc: "A module's files (`modules/<id>/**/*.lua`). `init.lua` runs when the server loads the module; other files run when something requires them. All of a module's files share one set of globals. Modules listen with `nf.on` (and `:on` on any handle they hold) and add commands with `nf.commands.register`.",
  globals: [],
}

/** Every class but `nf` and its namespaces, which say things about these. */
const classes = [
  centityClass,
  nodeClass,
  entityClass,
  livingClass,
  mobClass,
  droppedItemClass,
  mannequinClass,
  playerClass,
  botClass,
  senderClass,
  worldClass,
  worldBorderClass,
  blockClass,
  customBlockClass,
  projectBlockClass,
  inventoryClass,
  bossBarClass,
  sidebarClass,
  teamClass,
  databaseClass,
  menuClass,
  slotClass,
  menuTemplateClass,
  dialogClass,
  buttonClass,
  projectItemClass,
  effectClass,
  httpStreamClass,
  webSocketClass,
  routeClass,
  webResponseClass,
  oauthFlowClass,
  wasmInstanceClass,
  wasmProcessClass,
  networkChannelClass,
  networkMapClass,
  cutsceneClass,
  screenClass,
  playbackClass,
  fileClass,
  subscriptionClass,
  taskClass,
  scheduleClass,
  randomClass,
  hudClass,
]

/** The NetherForge Lua API. The runtime's conformance test holds the plugin to exactly this; see the lua-api skill. */
export const api: ApiSpec = {
  globals: [
    {
      name: 'nf',
      type: 'nf',
      doc: 'The NetherForge API: events, timers, tasks and saved data, and a namespace under it for each part of the server (`nf.players`, `nf.worlds`, …; see `nf` for them all).',
    },
    {
      name: 'require',
      type: 'fun(name: string): any',
      doc: "Loads a project module or one of its files, or a file beside a resource's script, and returns what it returned. `require(\"greeter\")` is `modules/greeter/init.lua`; `require(\"greeter.messages\")` is `modules/greeter/messages.lua`. The caller's own files come first: inside a module, its own (`require(\"util\")`); in a centity's, menu's, dialog's or item's script, the `.lua` files in the script's folder (`require(\"helpers\")` in `centities/tower/script.lua` is `centities/tower/helpers.lua`, or `helpers/init.lua`). A module runs once: everyone who requires it gets the running one, state and all. A file beside a script runs as part of it, once per copy of the script, with its globals and its `this`: each centity instance, menu window and dialog has its own. Requiring the script's own file, a circular require, or anything outside the project's Lua is an error.",
    },
    {
      name: 'log',
      type: 'fun(...: any)',
      doc: "Writes a line to the server console (and the editor's, with the file and line it came from). Arguments are converted with `tostring` and joined with tabs.",
    },
    { name: 'print', type: 'fun(...: any)', doc: 'The same as `log`.' },
    {
      name: 'vec3',
      type: 'vec3',
      doc: 'Builds a `Vec3`: `vec3(x, y, z)`. Also holds the common vectors (`vec3.zero`, `vec3.up`, `vec3.north`, …) and `vec3.from_yaw_pitch`.',
    },
  ],
  classes: [...nfClasses(classes), ...classes],
  values,
  aliases,
  surfaces: [
    centitySurface,
    moduleSurface,
    menuSurface,
    dialogSurface,
    itemSurface,
    blockSurface,
    screenSurface,
  ],
  shapes: [
    ...shapes,
    ...gameShapes,
    ...centityShapes,
    ...statShapes,
    ...recipeShapes,
    ...itemShapes,
    ...entityShapes,
    ...playerShapes,
    ...botShapes,
    ...menuShapes,
    itemMatchShape,
    ...commandShapes,
    ...worldShapes,
    ...inventoryShapes,
    ...lootShapes,
    ...hudShapes,
    ...spawningShapes,
    ...blockShapes,
    ...teamShapes,
    ...scheduleShapes,
    ...databaseShapes,
    ...httpShapes,
    ...connectionShapes,
    ...webShapes,
    ...wasmShapes,
    ...networkShapes,
    ...networkEventShapes,
    ...particleShapes,
    ...cutsceneShapes,
    ...screenShapes,
    ...utilityShapes,
    ...testShapes,
  ],
  removed: [
    'io',
    'os',
    'package',
    'load',
    'loadstring',
    'dofile',
    'loadfile',
    'debug',
    'collectgarbage',
    'warn',
    'java',
  ],
  commandArguments: ARGUMENT_TYPES,
  constants,
}
