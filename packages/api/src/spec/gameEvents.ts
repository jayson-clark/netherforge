import type { EventSpec, Field, LuaClass, Raised } from '../types.ts'
import { CLICK_DOC, CLICK_KIND, DRAG_CLICK } from './menu.ts'

/**
 * The server's events past the core ones in `events.ts`: a player's state and input, combat
 * and projectiles, the world simulating itself, mounts and vehicles, vanilla inventories, and
 * the server list. Each is on `nf`, and on the handle it's about where there is one (an
 * entity's on `Entity`, `Living` or `Mob`, whichever it applies to, and so on every class
 * below that; a block's or a world's on `World`), heard there first with the same event.
 */

const payload = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  extends: 'Event',
})

const player: Field = { name: 'player', type: 'Player', doc: 'Who it was.' }
const hand: Field = { name: 'hand', type: '"main_hand"|"off_hand"', doc: 'Which hand.' }
const GAME_MODE = '"survival"|"creative"|"adventure"|"spectator"'

/** Options shared by the event builders below. */
interface Options {
  payload: string
  cancellable?: boolean
  writable?: string[]
  example?: string
  /** The server raises it all the time: listened for only while a script does. */
  watched?: boolean
  /** Payload fields the adapter hands over as a function, worked out only when something listens. */
  lazy?: string[]
  /** What writable number fields are kept within. */
  bounds?: Raised['bounds']
}

const flags = (options: Options): Partial<EventSpec> => ({
  payload: options.payload,
  ...(options.cancellable ? { cancellable: true } : {}),
  ...(options.writable ? { writable: options.writable } : {}),
})

/** How the server raises the `nf` event: heard on [first] before `nf`, and the rest of [options]. */
const raised = (options: Options, first?: Raised['first']): Raised => ({
  ...(first ? { first } : {}),
  ...(options.watched ? { watched: true } : {}),
  ...(options.lazy ? { lazy: options.lazy } : {}),
  ...(options.bounds ? { bounds: options.bounds } : {}),
})

/** A `player_*` event on `nf`, heard by the player first; `playerEvents` puts it on `Player` too, without the prefix. */
const playerEvent = (name: string, doc: string, options: Options): EventSpec => ({
  name: `player_${name}`,
  doc,
  ...flags(options),
  ...(options.example ? { example: options.example } : {}),
  raised: raised(options, { class: 'Player', event: name, field: 'player' }),
})

/** An event about an entity: on its handle as `name` (on the class it applies to), then `nf` as `entity_<name>`. */
interface EntityEvent {
  entity: EventSpec
  nf: EventSpec
}

const entityEvent = (
  name: string,
  doc: string,
  options: Options & {
    /** The class it's on: what it can happen to. */
    on: 'Entity' | 'Living' | 'Mob'
    /** The payload field holding the entity it's about, if not `entity`. */
    field?: string
    nfName?: string
  },
): EntityEvent => {
  const nfName = options.nfName ?? `entity_${name}`
  return {
    entity: {
      name,
      doc: `${doc} Heard by the entity before \`nf.on("${nfName}")\`, with the same event, so \`event:stop()\` keeps it from there.`,
      bubbles: true,
      ...flags(options),
      ...(options.example ? { example: options.example } : {}),
    },
    nf: {
      name: nfName,
      doc: `${doc} After the entity's own \`${name}\` handlers.`,
      ...flags(options),
      raised: raised(options, { class: options.on, event: name, field: options.field ?? 'entity' }),
    },
  }
}

/** An event in a world: on `World` and then `nf`, by the same name. */
interface WorldEvent {
  world: EventSpec
  nf: EventSpec
}

const worldEvent = (
  name: string,
  doc: string,
  /** [field]: the payload field whose world it's in, if not `block`. */
  options: Options & { field?: 'block' | 'world' },
): WorldEvent => ({
  world: {
    name,
    doc: `${doc} In this world: heard before \`nf.on("${name}")\`, with the same event, so \`event:stop()\` keeps it from there.`,
    bubbles: true,
    ...flags(options),
    ...(options.example ? { example: options.example } : {}),
  },
  nf: {
    name,
    doc: `${doc} After the world's own \`${name}\` handlers.`,
    ...flags(options),
    raised: raised(options, { class: 'World', event: name, field: options.field ?? 'block' }),
  },
})

/** An event only `nf` hears. */
const nfEvent = (name: string, doc: string, options: Options): EventSpec => ({
  name,
  doc,
  ...flags(options),
  ...(options.example ? { example: options.example } : {}),
  raised: raised(options),
})

// ---- A player's state and input -------------------------------------------------------

const heal = entityEvent(
  'heal',
  'It is getting health back: eating, regenerating, a potion, a beacon. `event:cancel()` stops it; assign `event.amount` to change how much.',
  {
    payload: 'EntityHealEvent',
    cancellable: true,
    writable: ['amount'],
    on: 'Living',
    bounds: { amount: { min: 0 } },
  },
)

const playerStateEvents: EventSpec[] = [
  playerEvent(
    'input',
    "A player's movement keys changed: what they hold down now, as their game sends it (forward, back, left, right, jump, sneak, sprint), whether or not they can move. For steering something they ride or controlling a centity. The server only watches input while something listens, so it costs nothing otherwise.",
    {
      payload: 'PlayerInputEvent',
      watched: true,
      example:
        'nf.on("player_input", function(event)\n  if event.jump and event.sneak then\n    event.player:send_message("<gray>dash!")\n  end\nend)',
    },
  ),
  playerEvent(
    'change_slot',
    'A player picked another hotbar slot: a number key or the scroll wheel. `event:cancel()` keeps the slot they had.',
    { payload: 'PlayerChangeSlotEvent', cancellable: true },
  ),
  playerEvent(
    'swing',
    'A player swung an arm: every left click, on a block, an entity or the air, and some right clicks. The one way to hear a left click on the air. `event:cancel()` stops others seeing the swing, but not what the click does.',
    { payload: 'PlayerSwingEvent', cancellable: true },
  ),
  playerEvent('sprint', 'A player started or stopped sprinting.', {
    payload: 'PlayerSprintEvent',
  }),
  playerEvent(
    'fly',
    'A player started or stopped flying (in creative, or where `set_can_fly` lets them). `event:cancel()` keeps them as they were.',
    { payload: 'PlayerFlyEvent', cancellable: true },
  ),
  playerEvent('jump', 'A player jumped. `event:cancel()` puts them back where they jumped from.', {
    payload: 'PlayerJumpEvent',
    cancellable: true,
  }),
  playerEvent(
    'change_game_mode',
    "A player's game mode is changing: a command, a script, or the server. `event:cancel()` keeps the one they have.",
    { payload: 'PlayerChangeGameModeEvent', cancellable: true },
  ),
  playerEvent(
    'change_food',
    "A player's food level is changing: going down as they get hungry, up as they eat. `event:cancel()` keeps it as it is; assign `event.food` to set it to another level.",
    {
      payload: 'PlayerChangeFoodEvent',
      cancellable: true,
      writable: ['food'],
      bounds: { food: { min: 0, max: 20 } },
    },
  ),
  playerEvent(
    'gain_experience',
    'A player is getting experience points: picking up orbs, or from a script or command. Assign `event.amount` to give another amount (0 for none).',
    {
      payload: 'PlayerGainExperienceEvent',
      writable: ['amount'],
      bounds: { amount: { min: 0 } },
    },
  ),
  playerEvent('change_level', "A player's experience level changed, up or down.", {
    payload: 'PlayerChangeLevelEvent',
  }),
  playerEvent(
    'kick',
    'A player is being kicked: by a command, a plugin or a script, or by the server (flying, timing out, a bad packet). `event:cancel()` keeps them on the server; assign `event.text` to change what their screen says, or `event.message` to change what everyone is told (`nil` for nothing). Leaving then goes on to `player_quit`.',
    { payload: 'PlayerKickEvent', cancellable: true, writable: ['text', 'message'] },
  ),
  playerEvent(
    'complete_advancement',
    'A player completed an advancement (or a recipe was unlocked for them: those are advancements too, under `minecraft:recipes/`). Assign `event.message` to change what everyone is told, or `nil` to say nothing.',
    { payload: 'PlayerAdvancementEvent', writable: ['message'] },
  ),
  playerEvent(
    'stop_using_item',
    'A player let go of an item they were using before it finished: a bow or crossbow drawn, a shield raised, a spyglass, food or a potion part-eaten. `event.ticks` says how long they held it.',
    { payload: 'PlayerStopUsingItemEvent' },
  ),
  playerEvent(
    'break_item',
    'A tool, weapon or piece of armour a player was using wore out and broke. `event.item` is what it was, before it went.',
    { payload: 'PlayerItemEvent' },
  ),
]

// ---- Combat and projectiles -------------------------------------------------------------

const launchProjectile = entityEvent(
  'launch_projectile',
  "It launched a projectile: an arrow, a trident, a snowball, an egg, an ender pearl, a potion, a fireball. `event:cancel()` stops the projectile coming into the world. A dispenser's projectile has no shooter, so only `nf` hears it.",
  {
    payload: 'ProjectileLaunchEvent',
    cancellable: true,
    on: 'Living',
    field: 'shooter',
    nfName: 'projectile_launch',
  },
)

const projectileHit = entityEvent(
  'hit',
  "This projectile hit an entity or a block. `event:cancel()` lets it pass through as if nothing were there (an arrow flies on; a snowball doesn't break).",
  {
    payload: 'ProjectileHitEvent',
    cancellable: true,
    on: 'Entity',
    field: 'projectile',
    nfName: 'projectile_hit',
    example:
      'local arrow = assert(nf.worlds.default():spawn_entity("arrow", vec3(0, 80, 0), { velocity = vec3(0, -1, 0) }))\narrow:on("hit", function(event)\n  if event.block then\n    event.block:set_state("minecraft:gold_block")\n  end\nend)',
  },
)

const shootBow = entityEvent(
  'shoot_bow',
  'It shot a bow or a crossbow (a skeleton, a pillager, a player). `event:cancel()` stops the shot: nothing is fired or used up.',
  { payload: 'EntityShootBowEvent', cancellable: true, on: 'Living' },
)

const target = entityEvent(
  'target',
  'This mob is picking a target to go after, or forgetting the one it had (`event.target` is `nil`). `event:cancel()` keeps the target it had. The server only reports targets while something listens, so it costs nothing otherwise.',
  { payload: 'EntityTargetEvent', cancellable: true, on: 'Mob', watched: true },
)

const explode = entityEvent(
  'explode',
  'It exploded: a creeper, TNT, a fireball, an end crystal, a wither skull. `event:cancel()` stops the explosion breaking anything; assign `event.breaks_blocks = false` to keep every block standing but still hurt what is near, or `event.yield` to change how much of what breaks drops.',
  {
    payload: 'ExplodeEvent',
    cancellable: true,
    writable: ['breaks_blocks', 'yield'],
    on: 'Entity',
    lazy: ['blocks'],
    bounds: { yield: { min: 0, max: 1 } },
  },
)

const blockExplode = worldEvent(
  'block_explode',
  'A block exploded: a bed in the nether or the end, a respawn anchor charged away from the overworld. `event:cancel()` stops the explosion breaking anything; assign `event.breaks_blocks = false` to keep every block standing but still hurt what is near, or `event.yield` to change how much of what breaks drops.',
  {
    payload: 'ExplodeEvent',
    cancellable: true,
    writable: ['breaks_blocks', 'yield'],
    lazy: ['blocks'],
    bounds: { yield: { min: 0, max: 1 } },
  },
)

const combust = entityEvent(
  'combust',
  'It is catching fire: from lava or fire, sunlight (a zombie, a skeleton), a flaming arrow or a fire aspect sword. `event:cancel()` keeps it from burning; assign `event.ticks` to make it burn for that long instead.',
  {
    payload: 'EntityCombustEvent',
    cancellable: true,
    writable: ['ticks'],
    on: 'Entity',
    bounds: { ticks: { min: 0 } },
  },
)

const changeEffect = entityEvent(
  'change_effect',
  'One of its status effects is being added, changed, run out or taken away: a potion, a beacon, milk, a command or a script. `event:cancel()` stops the change.',
  { payload: 'EntityChangeEffectEvent', cancellable: true, on: 'Living' },
)

const combatShapes: LuaClass[] = [
  payload('ProjectileLaunchEvent', 'A projectile was launched.', [
    {
      name: 'projectile',
      type: 'Entity',
      doc: "The projectile. It isn't in the world yet, but its methods already work: a handler can tag it, give it data, or change its velocity.",
    },
    { name: 'shooter', type: 'Entity?', doc: 'Who launched it, or `nil` for a dispenser.' },
  ]),
  payload('ProjectileHitEvent', 'A projectile hit something.', [
    { name: 'projectile', type: 'Entity', doc: 'The projectile.' },
    { name: 'shooter', type: 'Entity?', doc: 'Who launched it, when someone did.' },
    { name: 'entity', type: 'Entity?', doc: 'The entity it hit, or `nil` for a block.' },
    { name: 'block', type: 'Block?', doc: 'The block it hit, or `nil` for an entity.' },
    {
      name: 'face',
      type: '"up"|"down"|"north"|"south"|"east"|"west"|nil',
      doc: 'Which face of the block it hit, or `nil` for an entity.',
    },
  ]),
  payload('EntityShootBowEvent', 'A bow or crossbow was shot.', [
    { name: 'entity', type: 'Living', doc: 'Who shot it.' },
    { name: 'bow', type: 'Item?', doc: 'The bow or crossbow, or `nil` for a mob without one.' },
    {
      name: 'projectile',
      type: 'Entity',
      doc: 'What it shot: an arrow, or a firework from a crossbow. Its methods already work.',
    },
    {
      name: 'force',
      type: 'number',
      doc: 'How far the bow was drawn, 0 to 1 (always 1 for a crossbow).',
    },
    hand,
  ]),
  payload('EntityTargetEvent', 'A mob is picking a target.', [
    { name: 'entity', type: 'Mob', doc: 'The mob.' },
    {
      name: 'target',
      type: 'Entity?',
      doc: "What it's going after, or `nil` when it's forgetting its target.",
    },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"closest_player"`, `"target_attacked_entity"`, `"target_attacked_nearby_entity"`, `"forgot_target"`, `"target_died"`, `"temptation"`, `"custom"` (a plugin or a script), …',
    },
  ]),
  payload(
    'ExplodeEvent',
    'Something exploded: an entity (`entity_explode`) or a block (`block_explode`).',
    [
      {
        name: 'entity',
        type: 'Entity?',
        doc: 'What exploded, for `entity_explode`; `nil` for a block.',
      },
      {
        name: 'block',
        type: 'Block?',
        doc: 'Where the block that exploded was, for `block_explode` (it has gone already); `nil` for an entity.',
      },
      {
        name: 'state',
        type: 'string?',
        doc: 'The whole block state of the block that exploded, for `block_explode`; `nil` for an entity.',
      },
      { name: 'location', type: 'Location', doc: 'Where it exploded.' },
      {
        name: 'blocks',
        type: 'Block[]',
        doc: 'The blocks it will break, as their handles: read them to see what would go.',
      },
      {
        name: 'breaks_blocks',
        type: 'boolean',
        doc: 'Whether `blocks` will break. Writable: `false` keeps every block standing, but the explosion still hurts and pushes what is near.',
      },
      {
        name: 'yield',
        type: 'number',
        doc: 'The chance each broken block drops as an item, 0 to 1. Writable: assign another (1 drops everything).',
      },
    ],
  ),
  payload('EntityCombustEvent', 'An entity is catching fire.', [
    { name: 'entity', type: 'Entity', doc: 'What is catching fire.' },
    {
      name: 'ticks',
      type: 'integer',
      doc: 'How long it will burn, in ticks. Writable: assign another.',
    },
    {
      name: 'source',
      type: 'Entity?',
      doc: 'The entity that set it alight (a flaming arrow, whoever swung a fire aspect sword), when one did.',
    },
    {
      name: 'block',
      type: 'Block?',
      doc: 'The block that set it alight (fire, lava), when one did.',
    },
  ]),
  payload('EntityChangeEffectEvent', "One of an entity's status effects is changing.", [
    { name: 'entity', type: 'Living', doc: 'Whose effect it is.' },
    { name: 'effect', type: 'string', doc: 'Which effect: `"minecraft:speed"`.' },
    {
      name: 'action',
      type: '"added"|"changed"|"cleared"|"removed"',
      doc: '`"added"`: it starts; `"changed"`: it gets stronger or longer; `"cleared"`: everything was taken away at once (milk, `/effect clear`); `"removed"`: this one was taken away or ran out.',
    },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"potion_drink"`, `"potion_splash"`, `"beacon"`, `"milk"`, `"expiration"`, `"command"`, `"plugin"` (a plugin or a script), `"food"`, `"attack"`, …',
    },
    { name: 'from', type: 'StatusEffect?', doc: 'The effect as it was, or `nil` when it is new.' },
    { name: 'to', type: 'StatusEffect?', doc: 'The effect as it will be, or `nil` when it goes.' },
  ]),
]

// ---- The world simulating itself --------------------------------------------------------

const FACE = '"up"|"down"|"north"|"south"|"east"|"west"'
const block: Field = { name: 'block', type: 'Block', doc: 'The block.' }
const WATCHED =
  'The server only reports these while something listens, so it costs nothing otherwise.'

const blockIgnite = worldEvent(
  'block_ignite',
  'A block is catching fire: flint and steel, a fire charge, lava, lightning, fire spreading, an explosion. `event:cancel()` keeps it from burning.',
  { payload: 'BlockIgniteEvent', cancellable: true },
)
const blockBurn = worldEvent(
  'block_burn',
  'A block is burning away in a fire. `event:cancel()` keeps it there (it may still catch fire again).',
  { payload: 'BlockBurnEvent', cancellable: true },
)
const blockSpread = worldEvent(
  'block_spread',
  `A block is spreading to another: grass, mycelium, moss, vines, mushrooms, fire, sculk. \`event.block\` is where it spreads to, \`event.source\` where from. \`event:cancel()\` stops it. ${WATCHED}`,
  { payload: 'BlockChangeEvent', cancellable: true, watched: true },
)
const blockFlow = worldEvent(
  'block_flow',
  `Water or lava is flowing into another block (or a dragon egg teleporting there). \`event:cancel()\` stops it. ${WATCHED}`,
  { payload: 'BlockFlowEvent', cancellable: true, watched: true },
)
const blockGrow = worldEvent(
  'block_grow',
  `A crop, sapling stage, sugar cane, cactus, bamboo, pumpkin or melon is growing by itself (bone meal included). \`event:cancel()\` stops it. ${WATCHED}`,
  { payload: 'BlockChangeEvent', cancellable: true, watched: true },
)
const blockFade = worldEvent(
  'block_fade',
  `A block is fading by itself: ice or snow melting, coral dying, fire burning out, farmland drying. \`event:cancel()\` keeps it as it is. ${WATCHED}`,
  { payload: 'BlockChangeEvent', cancellable: true, watched: true },
)
const blockForm = worldEvent(
  'block_form',
  `A block is forming by itself: snow falling, ice freezing, concrete setting, obsidian or cobblestone from lava, frost walker ice or a snow golem's trail (\`event.entity\` says which). \`event:cancel()\` stops it. ${WATCHED}`,
  { payload: 'BlockChangeEvent', cancellable: true, watched: true },
)
const leavesDecay = worldEvent(
  'leaves_decay',
  `Leaves are decaying, far from any log. \`event:cancel()\` keeps them. ${WATCHED}`,
  { payload: 'BlockEvent', cancellable: true, watched: true },
)
const pistonExtend = worldEvent(
  'piston_extend',
  'A piston is pushing out. `event:cancel()` keeps it in, and everything it would push where it is.',
  { payload: 'PistonEvent', cancellable: true },
)
const pistonRetract = worldEvent(
  'piston_retract',
  'A piston is pulling back. `event:cancel()` keeps it out, and (for a sticky one) the block it would pull where it is.',
  { payload: 'PistonEvent', cancellable: true },
)
const blockRedstone = worldEvent(
  'block_redstone',
  `A block's redstone power is changing: a lever, a button, a pressure plate, a redstone lamp or wire. Assign \`event.to\` to give it another power. ${WATCHED}`,
  {
    payload: 'BlockRedstoneEvent',
    writable: ['to'],
    watched: true,
    bounds: { to: { min: 0, max: 15 } },
  },
)
const signChange = worldEvent(
  'sign_change',
  'A player finished writing on a sign. `event:cancel()` keeps the sign as it was.',
  { payload: 'SignChangeEvent', cancellable: true },
)
const blockStartBreak = worldEvent(
  'block_start_break',
  'A player started hitting a block to break it. `event:cancel()` stops them breaking it; assign `event.instant = true` to break it at once, or `false` to make them wait for it.',
  { payload: 'BlockStartBreakEvent', cancellable: true, writable: ['instant'] },
)
const blockDropItem = worldEvent(
  'block_drop_item',
  "A block a player broke is dropping its items, its contents included (a chest's, a furnace's): after `block_break`, once it has gone. `event:cancel()` drops nothing.",
  { payload: 'BlockDropItemEvent', cancellable: true, lazy: ['items'] },
)
const weatherChange = worldEvent(
  'weather_change',
  'It is starting or stopping raining (or snowing) in a world. `event:cancel()` keeps the weather it has.',
  { payload: 'WeatherChangeEvent', cancellable: true, field: 'world' },
)
const thunderChange = worldEvent(
  'thunder_change',
  'A thunderstorm is starting or stopping in a world. `event:cancel()` keeps the weather it has.',
  { payload: 'ThunderChangeEvent', cancellable: true, field: 'world' },
)
const lightningStrike = worldEvent(
  'lightning_strike',
  'Lightning is striking: a storm, a trident with channeling, a command or a script. `event:cancel()` stops it.',
  { payload: 'LightningStrikeEvent', cancellable: true, field: 'world' },
)
const chunkLoad = worldEvent(
  'chunk_load',
  `A chunk came into memory: someone came near, or it was generated for the first time (\`event.generated\`). ${WATCHED}`,
  { payload: 'ChunkLoadEvent', field: 'world', watched: true },
)
const chunkGenerated = worldEvent(
  'chunk_generated',
  `A chunk was generated, the first time it was loaded: its terrain, structures and the game's decorations are in place, so a script can add its own (a centity in a grove, a shrine on a hill). Its blocks are \`event.x * 16\` to \`event.x * 16 + 15\` across, the same along \`z\`, and can be read and changed in the handler; the chunks beside it may not be generated yet, so keep what you place inside it. Once per chunk, ever: one generated while no script listened isn't heard later. It happens for every new chunk a player explores, so keep handlers quick and leave most chunks alone. ${WATCHED}`,
  {
    payload: 'ChunkEvent',
    field: 'world',
    watched: true,
    example:
      'nf.on("chunk_generated", function(event)\n  if event.world:name() ~= "wilds" or math.random() > 0.2 then\n    return\n  end\n  local ground = event.world:highest_block(vec3(event.x * 16 + 8, 0, event.z * 16 + 8))\n  if ground and ground:biome() == "minecraft:dark_forest" then\n    nf.centities.spawn("grove_spirit", event.world:location(ground:position() + vec3(0.5, 1, 0.5)))\n  end\nend)',
  },
)
const chunkUnload = worldEvent(
  'chunk_unload',
  'A chunk is leaving memory, as everyone has gone away. Its blocks can still be read and changed in the handler.',
  { payload: 'ChunkEvent', field: 'world' },
)

const worldSimulationNkEvents: EventSpec[] = [
  nfEvent('world_load', 'A world was loaded: at startup, or by a plugin or a script later.', {
    payload: 'WorldEvent',
  }),
  nfEvent(
    'world_unload',
    "A world is being unloaded. Its handle answers `nil` or `false` afterwards; handlers on it stay, for when it's loaded again.",
    { payload: 'WorldEvent' },
  ),
]

const worldShapes: LuaClass[] = [
  payload('BlockEvent', 'Something happened to a block.', [block]),
  payload('BlockIgniteEvent', 'A block is catching fire.', [
    block,
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"flint_and_steel"`, `"fireball"`, `"lava"`, `"lightning"`, `"spread"`, `"explosion"`, `"arrow"`, `"ender_crystal"`, …',
    },
    { name: 'player', type: 'Player?', doc: 'The player who set it alight, when one did.' },
    {
      name: 'entity',
      type: 'Entity?',
      doc: 'The entity that set it alight, when one did (a player too).',
    },
    { name: 'source', type: 'Block?', doc: 'The block it caught from (fire, lava), when one did.' },
  ]),
  payload('BlockBurnEvent', 'A block is burning away.', [
    block,
    { name: 'source', type: 'Block?', doc: "The fire it's burning in, when the server knows." },
  ]),
  payload('BlockChangeEvent', 'A block is changing by itself.', [
    { name: 'block', type: 'Block', doc: 'The block that is changing.' },
    {
      name: 'state',
      type: 'string',
      doc: 'The whole block state it is changing to: `"minecraft:wheat[age=7]"`.',
    },
    { name: 'source', type: 'Block?', doc: 'For `block_spread`: the block it spreads from.' },
    {
      name: 'entity',
      type: 'Entity?',
      doc: "For `block_form`: the entity that formed it (a player's frost walker boots, a snow golem), when one did.",
    },
  ]),
  payload('BlockFlowEvent', 'A liquid is flowing into another block.', [
    { name: 'block', type: 'Block', doc: 'Where it flows from.' },
    { name: 'to', type: 'Block', doc: 'Where it flows to.' },
    { name: 'face', type: FACE, doc: 'Which way, from `block` to `to`.' },
  ]),
  payload('PistonEvent', 'A piston is moving.', [
    { name: 'block', type: 'Block', doc: 'The piston.' },
    {
      name: 'direction',
      type: FACE,
      doc: 'Which way the piston faces: blocks move this way as it extends, and back towards it as it retracts.',
    },
    { name: 'sticky', type: 'boolean', doc: 'Whether it is a sticky piston.' },
    { name: 'blocks', type: 'Block[]', doc: 'The blocks it will move, where they are now.' },
  ]),
  payload('BlockRedstoneEvent', "A block's redstone power is changing.", [
    block,
    { name: 'from', type: 'integer', doc: 'The power it had, 0 to 15.' },
    {
      name: 'to',
      type: 'integer',
      doc: 'The power it is changing to, 0 to 15. Writable: assign another.',
    },
  ]),
  payload('SignChangeEvent', 'A player finished writing on a sign.', [
    player,
    block,
    { name: 'side', type: '"front"|"back"', doc: 'Which side they wrote on.' },
    {
      name: 'lines',
      type: 'string[]',
      doc: 'What they wrote, line by line, as plain text (four lines, empty ones included).',
    },
  ]),
  payload('BlockStartBreakEvent', 'A player started hitting a block.', [
    player,
    block,
    { name: 'item', type: 'Item?', doc: "What they're hitting it with, or `nil` for a bare hand." },
    {
      name: 'instant',
      type: 'boolean',
      doc: 'Whether it will break at once (in creative, or a block that breaks in one hit). Writable: `true` breaks it now.',
    },
  ]),
  payload('BlockDropItemEvent', 'A broken block is dropping its items.', [
    player,
    { name: 'block', type: 'Block', doc: 'Where it was: it has gone already.' },
    { name: 'state', type: 'string', doc: 'Its whole block state, as it was.' },
    { name: 'items', type: 'Item[]', doc: 'What it drops, its contents included.' },
  ]),
  payload('WeatherChangeEvent', 'It is starting or stopping raining in a world.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    { name: 'raining', type: 'boolean', doc: 'Whether it will be raining (or snowing).' },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"natural"`, `"command"`, `"sleep"`, `"plugin"` (a plugin or a script), `"unknown"`.',
    },
  ]),
  payload('ThunderChangeEvent', 'A thunderstorm is starting or stopping in a world.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    { name: 'thundering', type: 'boolean', doc: 'Whether there will be a thunderstorm.' },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"natural"`, `"command"`, `"sleep"`, `"plugin"` (a plugin or a script), `"unknown"`.',
    },
  ]),
  payload('LightningStrikeEvent', 'Lightning is striking.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    { name: 'location', type: 'Location', doc: 'Where it strikes.' },
    { name: 'entity', type: 'Entity', doc: 'The lightning bolt.' },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"weather"`, `"trident"`, `"command"`, `"spawner"` (a skeleton horse trap), `"custom"` (a plugin or a script), `"unknown"`.',
    },
  ]),
  payload('ChunkEvent', 'A chunk: one just generated, or one leaving memory.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    { name: 'x', type: 'integer', doc: 'Its x, in chunks: a block x divided by 16, rounded down.' },
    { name: 'z', type: 'integer', doc: 'Its z, in chunks.' },
  ]),
  payload('ChunkLoadEvent', 'A chunk came into memory.', [
    { name: 'world', type: 'World', doc: 'The world.' },
    { name: 'x', type: 'integer', doc: 'Its x, in chunks: a block x divided by 16, rounded down.' },
    { name: 'z', type: 'integer', doc: 'Its z, in chunks.' },
    {
      name: 'generated',
      type: 'boolean',
      doc: 'Whether it was just generated, for the first time.',
    },
  ]),
  payload('WorldEvent', 'A world was loaded or is being unloaded.', [
    { name: 'world', type: 'World', doc: 'The world.' },
  ]),
]

const worldSimulation: WorldEvent[] = [
  blockIgnite,
  blockBurn,
  blockSpread,
  blockFlow,
  blockGrow,
  blockFade,
  blockForm,
  leavesDecay,
  pistonExtend,
  pistonRetract,
  blockRedstone,
  signChange,
  blockStartBreak,
  blockDropItem,
  weatherChange,
  thunderChange,
  lightningStrike,
  chunkLoad,
  chunkGenerated,
  chunkUnload,
]

// ---- Mounts and vehicles ----------------------------------------------------------------

const mount = entityEvent(
  'mount',
  'It is getting on something to ride it: a horse, a pig with a saddle, a boat, a minecart, another mob (a spider jockey). `event:cancel()` keeps it off.',
  { payload: 'MountEvent', cancellable: true, on: 'Entity' },
)

const dismount = entityEvent(
  'dismount',
  "It is getting off what it rides: sneaking, the vehicle breaking, a teleport, dying. `event:cancel()` keeps it on, except where it can't stay (it died, or what it rode has gone).",
  { payload: 'MountEvent', cancellable: true, on: 'Entity' },
)

const vehicleNkEvents: EventSpec[] = [
  nfEvent(
    'vehicle_move',
    'A boat or minecart moved into another block, with or without a rider. Only moves that change block are heard. The server only reports these while something listens, so it costs nothing otherwise.',
    { payload: 'VehicleMoveEvent', watched: true },
  ),
  nfEvent(
    'vehicle_damage',
    'Something is hitting a boat or minecart. `event:cancel()` stops it; assign `event.amount` to change how much it takes.',
    {
      payload: 'VehicleDamageEvent',
      cancellable: true,
      writable: ['amount'],
      bounds: { amount: { min: 0 } },
    },
  ),
  nfEvent(
    'vehicle_destroy',
    'A boat or minecart is being broken: it will drop as an item. `event:cancel()` keeps it there.',
    { payload: 'VehicleDestroyEvent', cancellable: true },
  ),
]

const vehicleShapes: LuaClass[] = [
  payload('MountEvent', 'An entity is getting on or off something it rides.', [
    { name: 'entity', type: 'Entity', doc: 'The rider.' },
    { name: 'vehicle', type: 'Entity', doc: 'What it rides.' },
  ]),
  payload('VehicleMoveEvent', 'A vehicle moved into another block.', [
    { name: 'vehicle', type: 'Entity', doc: 'The boat or minecart.' },
    { name: 'from', type: 'Location', doc: 'Where it was: in the block it is leaving.' },
    { name: 'to', type: 'Location', doc: 'Where it is now: in another block from `from`.' },
  ]),
  payload('VehicleDamageEvent', 'Something is hitting a vehicle.', [
    { name: 'vehicle', type: 'Entity', doc: 'The boat or minecart.' },
    { name: 'attacker', type: 'Entity?', doc: 'Who hit it, when someone did.' },
    {
      name: 'amount',
      type: 'number',
      doc: 'How much damage it takes. Writable: assign another to change it.',
    },
  ]),
  payload('VehicleDestroyEvent', 'A vehicle is being broken.', [
    { name: 'vehicle', type: 'Entity', doc: 'The boat or minecart.' },
    { name: 'attacker', type: 'Entity?', doc: 'Who broke it, when someone did.' },
  ]),
]

// ---- Vanilla inventories ----------------------------------------------------------------

const RESULT_DOC =
  'What the result slot shows, or `nil` for nothing. Writable: assign another item to make that the result, or `nil` for none.'
const result: Field = { name: 'result', type: 'Item?', doc: RESULT_DOC }
const RECIPE_DOC =
  'Its recipe\'s id, like `"minecraft:oak_planks"`, or `nil` when no recipe matches.'

const inventoryEvents: EventSpec[] = [
  playerEvent(
    'open_inventory',
    "A player is opening a vanilla inventory: a chest, a furnace, a crafting table, a villager's trades, a horse's. Not one of the project's menus: those are `menu_open`. `event:cancel()` keeps it closed.",
    { payload: 'PlayerInventoryEvent', cancellable: true },
  ),
  playerEvent(
    'close_inventory',
    "A player closed a vanilla inventory (not one of the project's menus: those are `menu_close`), or left the server with it open.",
    { payload: 'PlayerInventoryEvent' },
  ),
  playerEvent(
    'click_inventory',
    "A player clicked in an inventory that isn't one of the project's menus: their own (with nothing else open, or below a chest, a furnace, …) or the vanilla one they have open. While one of the project's menus is open, clicks in their own inventory below it are the menu's `click` instead. Not on the creative inventory screen, which sends whole stacks rather than clicks. `event:cancel()` stops the click: nothing moves.",
    {
      payload: 'PlayerClickInventoryEvent',
      cancellable: true,
      example:
        'nf.on("player_click_inventory", function(event)\n  if event.own and event.index == 40 then\n    event:cancel() -- keep the off hand as it is\n  end\nend)',
    },
  ),
  playerEvent(
    'drag_inventory',
    "A player dragged a stack across slots (holding a mouse button down) in an inventory that isn't one of the project's menus: their own, or the vanilla one they have open. While one of the project's menus is open, that's the menu's `drag` instead. `event:cancel()` stops it: nothing moves.",
    { payload: 'PlayerDragInventoryEvent', cancellable: true },
  ),
  playerEvent(
    'craft',
    'A player is taking what they crafted out of the result slot (with shift, as many as they can make). `event:cancel()` leaves it there and keeps the ingredients.',
    { payload: 'PlayerCraftEvent', cancellable: true },
  ),
  playerEvent(
    'prepare_craft',
    "What's in a crafting grid changed (a crafting table's, or the 2×2 in a player's inventory), and the game is working out what it makes. Assign `event.result` to make something else, or `nil` for nothing: a recipe of your own.",
    {
      payload: 'PlayerPrepareCraftEvent',
      writable: ['result'],
      example:
        'nf.on("player_prepare_craft", function(event)\n  local center = event.ingredients[4]\n  if center and center.kind == "minecraft:diamond" and not event.recipe then\n    event.result = { kind = "minecraft:nether_star" }\n  end\nend)',
    },
  ),
  playerEvent(
    'prepare_anvil',
    'What is in an anvil changed (or the name typed into it), and the game is working out the result. Assign `event.result` to make something else (or `nil` for nothing), and `event.cost` to change how many levels it takes.',
    {
      payload: 'PlayerPrepareAnvilEvent',
      writable: ['result', 'cost'],
      bounds: { cost: { min: 0 } },
    },
  ),
  playerEvent(
    'prepare_smithing',
    'What is in a smithing table changed, and the game is working out the result. Assign `event.result` to make something else, or `nil` for nothing.',
    { payload: 'PlayerPrepareSmithingEvent', writable: ['result'] },
  ),
  playerEvent(
    'prepare_grindstone',
    'What is in a grindstone changed, and the game is working out the result. Assign `event.result` to make something else, or `nil` for nothing.',
    { payload: 'PlayerPrepareGrindstoneEvent', writable: ['result'] },
  ),
  playerEvent(
    'enchant_item',
    'A player is enchanting an item at an enchanting table. `event:cancel()` stops it: nothing is enchanted or spent. Assign `event.cost` to change how many levels it takes.',
    {
      payload: 'PlayerEnchantItemEvent',
      cancellable: true,
      writable: ['cost'],
      bounds: { cost: { min: 0 } },
    },
  ),
]

const furnaceSmelt = worldEvent(
  'furnace_smelt',
  'A furnace, smoker or blast furnace finished smelting one item. `event:cancel()` stops it: the item stays unsmelted. Assign `event.result` to make something else.',
  { payload: 'FurnaceSmeltEvent', cancellable: true, writable: ['result'] },
)

const inventoryShapes: LuaClass[] = [
  payload('PlayerInventoryEvent', 'A player opened or closed a vanilla inventory.', [
    player,
    {
      name: 'kind',
      type: 'string',
      doc: 'What kind of inventory it is: `"chest"`, `"barrel"`, `"shulker_box"`, `"ender_chest"`, `"furnace"`, `"workbench"` (a crafting table), `"anvil"`, `"enchanting"`, `"merchant"` (a villager), …',
    },
    {
      name: 'inventory',
      type: 'Inventory?',
      doc: "The inventory, when it's a real one (a container block's, an entity's, their ender chest); `nil` for one with nothing kept in it (a crafting table, an anvil).",
    },
    {
      name: 'block',
      type: 'Block?',
      doc: "The block it belongs to (a chest, a crafting table), or `nil` for an entity's.",
    },
  ]),
  payload(
    'PlayerClickInventoryEvent',
    "A player clicked in an inventory that isn't one of the project's menus.",
    [
      player,
      {
        name: 'kind',
        type: 'string',
        doc: 'What kind of inventory they have open: `"crafting"` when it is only their own (opened with the inventory key), else as `PlayerInventoryEvent.kind` says (`"chest"`, `"furnace"`, …).',
      },
      {
        name: 'inventory',
        type: 'Inventory?',
        doc: "The inventory clicked, when it's a real one: their own (`player:inventory()`), a container block's, an entity's, their ender chest. `nil` for a click outside both, and in one with nothing kept in it (the crafting grid, an anvil).",
      },
      {
        name: 'own',
        type: 'boolean',
        doc: 'Whether the click landed in their own inventory (hotbar, main slots, armour, off hand) rather than in the one above it or outside both.',
      },
      {
        name: 'index',
        type: 'integer?',
        doc: 'The slot clicked, from 0, in the inventory clicked. In their own: 0 to 8 the hotbar, 9 to 35 the rest, 36 to 39 armour (boots first), 40 the off hand, as `player:inventory()` numbers them. `nil` for a click outside both.',
      },
      {
        name: 'click',
        type: CLICK_KIND,
        doc: CLICK_DOC,
      },
      {
        name: 'hotbar_index',
        type: 'integer?',
        doc: 'For `number_key`: the hotbar slot, 0 to 8.',
      },
      { name: 'item', type: 'Item?', doc: 'What was in the slot clicked.' },
      { name: 'cursor_item', type: 'Item?', doc: 'What the player was carrying on the cursor.' },
    ],
  ),
  payload(
    'PlayerDragInventoryEvent',
    "A player dragged a stack in an inventory that isn't one of the project's menus.",
    [
      player,
      {
        name: 'kind',
        type: 'string',
        doc: 'What kind of inventory they have open, as `PlayerClickInventoryEvent.kind` says: `"crafting"` when it is only their own.',
      },
      {
        name: 'inventory',
        type: 'Inventory?',
        doc: "The inventory above their own, when it's a real one (a container block's, an entity's, their ender chest); `nil` for one with nothing kept in it (the crafting grid, an anvil) and when only their own is open.",
      },
      {
        name: 'indices',
        type: 'integer[]',
        doc: "The slots of the inventory above their own the stack is spread over, from 0, in order (the crafting grid's 1 to 4 when only their own is open, 0 being its result).",
      },
      {
        name: 'own_indices',
        type: 'integer[]',
        doc: 'The slots of their own inventory the stack is spread over, in order, numbered as `player:inventory()` numbers them.',
      },
      DRAG_CLICK,
      { name: 'cursor_item', type: 'Item?', doc: 'The stack being dragged.' },
    ],
  ),
  payload('PlayerCraftEvent', 'A player is taking what they crafted.', [
    player,
    { name: 'recipe', type: 'string?', doc: RECIPE_DOC },
    { name: 'item', type: 'Item', doc: 'What one craft makes.' },
    {
      name: 'shift',
      type: 'boolean',
      doc: 'Whether they shift-clicked, to make as many as they can.',
    },
  ]),
  payload('PlayerPrepareCraftEvent', "What's in a crafting grid changed.", [
    player,
    { name: 'recipe', type: 'string?', doc: RECIPE_DOC },
    {
      name: 'ingredients',
      type: 'table<integer, Item>',
      doc: "What's in the grid, by slot: 0 to 8 for a crafting table, row by row from the top left, or 0 to 3 for the 2×2. Empty slots are left out.",
    },
    result,
    {
      name: 'repair',
      type: 'boolean',
      doc: 'Whether it is two damaged tools of a kind being combined into one.',
    },
  ]),
  payload('PlayerPrepareAnvilEvent', 'What is in an anvil changed.', [
    player,
    { name: 'left', type: 'Item?', doc: 'What is in the first slot.' },
    { name: 'right', type: 'Item?', doc: 'What is in the second slot.' },
    { name: 'rename', type: 'string?', doc: 'The name typed in, or `nil` for none.' },
    result,
    {
      name: 'cost',
      type: 'integer',
      doc: 'How many experience levels it takes. Writable: assign another.',
    },
  ]),
  payload('PlayerPrepareSmithingEvent', 'What is in a smithing table changed.', [
    player,
    { name: 'template', type: 'Item?', doc: 'The smithing template.' },
    { name: 'base', type: 'Item?', doc: 'What is being upgraded or trimmed.' },
    { name: 'addition', type: 'Item?', doc: 'The ingot or material.' },
    result,
  ]),
  payload('PlayerPrepareGrindstoneEvent', 'What is in a grindstone changed.', [
    player,
    { name: 'top', type: 'Item?', doc: 'What is in the top slot.' },
    { name: 'bottom', type: 'Item?', doc: 'What is in the bottom slot.' },
    result,
  ]),
  payload('PlayerEnchantItemEvent', 'A player is enchanting an item.', [
    player,
    { name: 'block', type: 'Block', doc: 'The enchanting table.' },
    { name: 'item', type: 'Item', doc: 'What is being enchanted.' },
    {
      name: 'enchantments',
      type: 'table<string, integer>',
      doc: 'What it gets, by id with its level: `{ ["minecraft:sharpness"] = 3 }`.',
    },
    {
      name: 'cost',
      type: 'integer',
      doc: 'How many experience levels it takes. Writable: assign another.',
    },
  ]),
  payload('FurnaceSmeltEvent', 'A furnace finished smelting an item.', [
    { name: 'block', type: 'Block', doc: 'The furnace, smoker or blast furnace.' },
    { name: 'source', type: 'Item', doc: 'What was smelted (one of it).' },
    {
      name: 'result',
      type: 'Item',
      doc: 'What it makes. Writable: assign another item to make that instead.',
    },
  ]),
]

// ---- The server list --------------------------------------------------------------------

const serverEvents: EventSpec[] = [
  nfEvent(
    'server_list_ping',
    "Someone's server list is asking about the server: what it says, how many are on and how many fit. `event:cancel()` doesn't answer, so the server shows as offline to them. The ping waits on the main thread for the handlers (at most two seconds, then it's answered as it was), so keep them quick. The server only waits while something listens.",
    {
      payload: 'ServerListPingEvent',
      cancellable: true,
      writable: ['description', 'online', 'max_players', 'hide_players'],
      watched: true,
      bounds: { online: { min: 0 }, max_players: { min: 0 } },
      example:
        'nf.on("server_list_ping", function(event)\n  event.description = "<gold>Arena night</gold>\\n<gray>" .. event.online .. " playing"\n  event.max_players = event.online + 1\nend)',
    },
  ),
]

const serverShapes: LuaClass[] = [
  payload('ServerListPingEvent', 'A server list is asking about the server.', [
    {
      name: 'address',
      type: 'string',
      doc: 'The address it asks from, like `"203.0.113.7"`.',
    },
    {
      name: 'description',
      type: 'Text',
      doc: "The server's description under its name (its MOTD), as MiniMessage: two lines at most. Writable: assign another.",
    },
    {
      name: 'online',
      type: 'integer',
      doc: 'How many players it says are on. Writable: assign another number.',
    },
    {
      name: 'max_players',
      type: 'integer',
      doc: 'How many it says fit. Writable: assign another number.',
    },
    {
      name: 'hide_players',
      type: 'boolean',
      doc: 'Whether it hides the player counts (showing `???`) and who is on. Writable.',
    },
  ]),
]

// ---- Shapes -----------------------------------------------------------------------------

/** The payloads of the events in this file. */
export const gameShapes: LuaClass[] = [
  ...combatShapes,
  ...worldShapes,
  ...vehicleShapes,
  ...inventoryShapes,
  ...serverShapes,
  payload('PlayerInputEvent', 'What movement keys a player holds down now.', [
    player,
    { name: 'forward', type: 'boolean', doc: 'Forward (W).' },
    { name: 'backward', type: 'boolean', doc: 'Back (S).' },
    { name: 'left', type: 'boolean', doc: 'Left (A).' },
    { name: 'right', type: 'boolean', doc: 'Right (D).' },
    { name: 'jump', type: 'boolean', doc: 'Jump (space).' },
    { name: 'sneak', type: 'boolean', doc: 'Sneak (shift).' },
    { name: 'sprint', type: 'boolean', doc: 'Sprint (control).' },
  ]),
  payload('PlayerChangeSlotEvent', 'A player picked another hotbar slot.', [
    player,
    { name: 'from', type: 'integer', doc: 'The slot they had, 0 to 8.' },
    { name: 'to', type: 'integer', doc: 'The slot they picked, 0 to 8.' },
  ]),
  payload('PlayerSwingEvent', 'A player swung an arm.', [player, hand]),
  payload('PlayerSprintEvent', 'A player started or stopped sprinting.', [
    player,
    { name: 'sprinting', type: 'boolean', doc: 'Whether they are sprinting now.' },
  ]),
  payload('PlayerFlyEvent', 'A player started or stopped flying.', [
    player,
    { name: 'flying', type: 'boolean', doc: 'Whether they are flying now.' },
  ]),
  payload('PlayerJumpEvent', 'A player jumped.', [
    player,
    { name: 'from', type: 'Location', doc: 'Where they jumped from, facing included.' },
    { name: 'to', type: 'Location', doc: 'Where the jump has taken them so far.' },
  ]),
  payload('PlayerChangeGameModeEvent', "A player's game mode is changing.", [
    player,
    { name: 'game_mode', type: GAME_MODE, doc: 'The game mode they are changing to.' },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"command"`, `"plugin"` (a plugin or a script), `"default_game_mode"`, `"hardcore_death"`, `"unknown"`, …',
    },
  ]),
  payload('PlayerChangeFoodEvent', "A player's food level is changing.", [
    player,
    {
      name: 'food',
      type: 'integer',
      doc: "The level it's changing to, 0 to 20. Writable: assign another to set that instead.",
    },
    { name: 'item', type: 'Item?', doc: 'What they ate, or `nil` when they are getting hungry.' },
  ]),
  payload(
    'EntityHealEvent',
    'An entity is getting health back: heard by the entity, then `nf.on("entity_heal")`.',
    [
      { name: 'entity', type: 'Living', doc: 'Who is healing.' },
      {
        name: 'amount',
        type: 'number',
        doc: 'How much: 2 is one heart. Writable: assign another to change it.',
      },
      {
        name: 'cause',
        type: 'string',
        doc: 'Why, as Minecraft names it: `"satiated"` (a full food bar), `"regen"`, `"eating"`, `"magic"`, `"magic_regen"`, `"ender_crystal"`, `"custom"` (a plugin or a script), …',
      },
    ],
  ),
  payload('PlayerGainExperienceEvent', 'A player is getting experience points.', [
    player,
    {
      name: 'amount',
      type: 'integer',
      doc: 'How many points. Writable: assign another to give that many instead.',
    },
  ]),
  payload('PlayerChangeLevelEvent', "A player's experience level changed.", [
    player,
    { name: 'from', type: 'integer', doc: 'The level they had.' },
    { name: 'to', type: 'integer', doc: 'The level they have now.' },
  ]),
  payload('PlayerKickEvent', 'A player is being kicked.', [
    player,
    {
      name: 'text',
      type: 'Text',
      doc: 'What their screen will say, as MiniMessage. Writable: assign other text.',
    },
    {
      name: 'message',
      type: 'Text?',
      doc: 'The leave message everyone sees, as MiniMessage. Writable: assign another, or `nil` for none.',
    },
    {
      name: 'cause',
      type: 'string',
      doc: 'Why, as Minecraft names it: `"kick_command"`, `"plugin"` (a plugin or a script), `"flying_player"`, `"timeout"`, `"whitelist"`, `"ban"`, `"idling"`, `"unknown"`, …',
    },
  ]),
  payload('PlayerAdvancementEvent', 'A player completed an advancement.', [
    player,
    {
      name: 'advancement',
      type: 'string',
      doc: 'Its key, always with its namespace: the game\'s `"minecraft:story/mine_diamond"`, or one of the project\'s own as `"<namespace>:<id>"` (`"showcase:treasure_hunter"`).',
    },
    {
      name: 'message',
      type: 'Text?',
      doc: "The message everyone sees, as MiniMessage, or `nil` when there's none (a recipe, or an advancement that doesn't announce itself). Writable: assign another, or `nil` for none.",
    },
  ]),
  payload('PlayerStopUsingItemEvent', 'A player let go of an item before it finished.', [
    player,
    { name: 'item', type: 'Item', doc: 'What they were using.' },
    { name: 'ticks', type: 'integer', doc: 'How long they held it, in ticks.' },
  ]),
]

// ---- What each class gets ---------------------------------------------------------------

/** The new events on `nf`, in the order the reference lists them. */
export const gameNkEvents: EventSpec[] = [
  ...playerStateEvents,
  heal.nf,
  launchProjectile.nf,
  projectileHit.nf,
  shootBow.nf,
  target.nf,
  explode.nf,
  blockExplode.nf,
  combust.nf,
  changeEffect.nf,
  ...worldSimulation.map((it) => it.nf),
  ...worldSimulationNkEvents,
  mount.nf,
  dismount.nf,
  ...vehicleNkEvents,
  ...inventoryEvents,
  furnaceSmelt.nf,
  ...serverEvents,
]

/** The new events on `Entity`: what can happen to any entity. */
export const gameEntityEvents: EventSpec[] = [
  projectileHit.entity,
  explode.entity,
  combust.entity,
  mount.entity,
  dismount.entity,
]

/** The new events on `Living`: what only happens to a living entity. */
export const gameLivingEvents: EventSpec[] = [
  heal.entity,
  launchProjectile.entity,
  shootBow.entity,
  changeEffect.entity,
]

/** The new events on `Mob`. */
export const gameMobEvents: EventSpec[] = [target.entity]

/** The new events on `World`. */
export const gameWorldEvents: EventSpec[] = [
  blockExplode.world,
  ...worldSimulation.map((it) => it.world),
  furnaceSmelt.world,
]
