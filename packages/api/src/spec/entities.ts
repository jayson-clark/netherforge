import { buffEvents, statFunctions } from './stats.ts'
import type { EventSpec, Field, Fn, LuaClass } from '../types.ts'
import { PATH_REACH, REPATH_TICKS, TARGET_DISTANCE } from './constants.ts'
import { DATA_SAVING, DATA_VALUES } from './data.ts'
import { eventFunctions, nfEvents } from './events.ts'
import { gameEntityEvents, gameLivingEvents, gameMobEvents } from './gameEvents.ts'
import { playerRecipeFunctions } from './recipes.ts'
import {
  EQUIPMENT_SLOT,
  playerAdvancementFunctions,
  playerIllusionFunctions,
  playerPermissionFunctions,
  playerServerFunctions,
} from './players.ts'

const shape = (
  name: string,
  doc: string,
  fields: Field[],
  more: Partial<LuaClass> = {},
): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  ...more,
})

/** A payload: a shape whose table is an event object, with `Event`'s members too. */
const payload = (name: string, doc: string, fields: Field[]): LuaClass =>
  shape(name, doc, fields, { extends: 'Event' })

/** When a handle's methods give up: after "`nil`" or "`false`", or as the last of several cases. */
const ONCE_GONE = 'once it has gone (or while its chunk is unloaded)'
const GONE = `\`nil\` ${ONCE_GONE}`
const GONE_FALSE = `\`false\` ${ONCE_GONE}`

const getter = (name: string, doc: string, type: string): Fn => ({
  name,
  doc,
  params: [],
  returns: [{ type }],
})

const setter = (name: string, doc: string, param: string, type: string, paramDoc = ''): Fn => ({
  name,
  doc,
  params: [{ name: param, type, doc: paramDoc }],
  returns: [{ type: 'boolean', doc: GONE_FALSE + '.' }],
})

/** How far the `target_*` raycasts reach when the script doesn't say. */
/** The poses a mannequin can stand in. */
const MANNEQUIN_POSE = '"standing"|"crouching"|"swimming"|"fall_flying"|"sleeping"'

const targetDistance = {
  name: 'max_distance',
  type: 'number',
  doc: `How far to look, in blocks. Default ${TARGET_DISTANCE.value}.`,
  optional: true,
}

/** An attribute parameter: checked against the server's attributes. */
const attributeParam = {
  name: 'attribute',
  type: 'string',
  doc: 'Its id: `"minecraft:scale"`, or `"scale"` for short.',
}

const UNKNOWN_ATTRIBUTE = "An attribute the server doesn't have is an error."

/** How the docs write the project's namespace (`netherforge.json`'s `namespace`), which a script's modifiers and goals live in. */
const MODIFIER_NAMESPACE = '<namespace>'

/** An entity's attributes: what `max_health`, speed, scale and the rest of its numbers are made of. */
const attributeFunctions: Fn[] = [
  {
    name: 'attribute',
    doc: `The value of one of its attributes now: its base with every modifier applied (its equipment's, its effects', scripts'), within the attribute's range. \`nil\` for an attribute it doesn't have (\`scale\` on a dropped item), or ${ONCE_GONE}. ${UNKNOWN_ATTRIBUTE}`,
    params: [attributeParam],
    returns: [{ type: 'number?' }],
    example: 'local speed = zombie:attribute("movement_speed")',
  },
  {
    name: 'attribute_base',
    doc: `One of its attributes' base value, before modifiers. \`nil\` for an attribute it doesn't have, or ${ONCE_GONE}. ${UNKNOWN_ATTRIBUTE}`,
    params: [attributeParam],
    returns: [{ type: 'number?' }],
  },
  {
    name: 'set_attribute_base',
    doc: `Sets one of its attributes' base value. It's saved with the entity. ${UNKNOWN_ATTRIBUTE}`,
    params: [
      attributeParam,
      {
        name: 'value',
        type: 'number',
        doc: "The new base. The attribute's value stays within the range the game gives it, whatever the base.",
      },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `\`false\` for an attribute it doesn't have, ${ONCE_GONE}.`,
      },
    ],
    example: 'player:set_attribute_base("scale", 2)',
  },
  {
    name: 'attribute_modifiers',
    doc: `Everything changing one of its attributes: the modifiers scripts added, and those from its equipment, effects and the game itself (sprinting), each with its full id. \`nil\` for an attribute it doesn't have, or ${ONCE_GONE}. ${UNKNOWN_ATTRIBUTE}`,
    params: [attributeParam],
    returns: [{ type: 'AttributeModifier[]?' }],
  },
  {
    name: 'add_attribute_modifier',
    doc: `Adds a modifier to one of its attributes, replacing the project's modifier of the same id. Modifiers are saved with the entity unless \`saved\` is \`false\`, so give each a fixed id and remove it when it's done, rather than making new ones. The id is in the project's namespace (\`netherforge.json\`'s \`namespace\`): \`"slow_zone"\` is \`"${MODIFIER_NAMESPACE}:slow_zone"\`, so nothing else's modifiers can be replaced or removed. ${UNKNOWN_ATTRIBUTE}`,
    params: [
      attributeParam,
      {
        name: 'id',
        type: 'string',
        doc: `What it's called, to remove it later: lowercase letters, digits, \`_\`, \`-\`, \`.\` and \`/\`. \`"${MODIFIER_NAMESPACE}:"\` in front is allowed, and the same.`,
      },
      { name: 'amount', type: 'number', doc: 'How much.' },
      {
        name: 'operation',
        type: '"add_value"|"add_multiplied_base"|"add_multiplied_total"',
        doc: 'How `amount` applies, as an item\'s `attribute_modifiers`: added; added times the base value; or the total multiplied by `1 + amount`. Default `"add_value"`.',
        optional: true,
      },
      {
        name: 'saved',
        type: 'boolean',
        doc: "Whether it's saved with the entity. `false` for one that's only while something lasts (a zone's gravity, a stance's speed): it's gone when the entity leaves the world or the server stops, so nothing is left on a player when the project changes. Default `true`.",
        optional: true,
      },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `\`false\` for an attribute it doesn't have, ${ONCE_GONE}.`,
      },
    ],
    example: 'player:add_attribute_modifier("gravity", "space", -0.06, "add_value", false)',
  },
  {
    name: 'remove_attribute_modifier',
    doc: `Takes one of the project's modifiers off one of its attributes. ${UNKNOWN_ATTRIBUTE}`,
    params: [
      attributeParam,
      {
        name: 'id',
        type: 'string',
        doc: `The id \`add_attribute_modifier\` was given, with or without \`"${MODIFIER_NAMESPACE}:"\`. Another namespace is an error: only the project's own modifiers can be removed.`,
      },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `Whether it had it: \`false\` when it didn't, for an attribute it doesn't have, ${ONCE_GONE}.`,
      },
    ],
  },
]

/** A mob's own walking: where it goes, and `path_end` when it gets there or gives up. */
const pathFunctions: Fn[] = [
  {
    name: 'move_to',
    doc: `Makes it walk somewhere, finding its way round what's in the way: to a place in its own world, or after an entity, looking for a new path to wherever that entity is every ${REPATH_TICKS.value} ticks. When it stops, its \`path_end\` handlers hear whether it got there. A new \`move_to\` replaces the walk it was on. Its own AI still runs, and can send it elsewhere (a zombie that sees a player goes for them), which ends the walk as given up; with \`set_ai(false)\` it doesn't walk at all.`,
    params: [
      {
        name: 'target',
        type: 'Location|Vec3|Entity',
        doc: 'Where: a position in its world, a `Location` (in its world: another world is `false`), or an entity to go after.',
      },
      { name: 'options', type: 'PathOptions', doc: '', optional: true },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `Whether it found a path and set off: \`false\` when there's no way there from where it is (in the air, just spawned or falling, it finds none until it lands), for a target in another world or gone, ${ONCE_GONE}.`,
      },
    ],
    example:
      'zombie:on("path_end", function(event)\n  log(event.reached and "made it" or "gave up")\nend)\nzombie:move_to(vec3(10, 64, 10), { speed = 1.2 })',
  },
  {
    name: 'stop_pathing',
    doc: 'Stops it walking where it was going, whoever sent it: a `move_to`, or its own AI. A walk `move_to` started ends without a `path_end`.',
    params: [],
    returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
  },
  getter(
    'has_path',
    `Whether it is walking somewhere now, whoever sent it: a \`move_to\`, or its own AI. ${GONE_FALSE}.`,
    'boolean',
  ),
  getter(
    'path_target',
    `Where the path it is walking ends, whoever sent it: as close as it can get to where it was sent, which is short of it when there's no way all the way there. \`nil\` when it isn't walking, or ${ONCE_GONE}.`,
    'Vec3?',
  ),
]

/** The namespace the goals a script adds live in: the project's, as for modifiers. */
const GOAL_NAMESPACE = MODIFIER_NAMESPACE

/** What a goal can claim of a mob: its legs, its head, its jumping, or what it's after. */
const GOAL_CONTROL = '"move"|"look"|"jump"|"target"'

const goalKeyDoc = `A goal's key, as \`goals()\` lists it: \`"minecraft:random_stroll"\`, or \`"random_stroll"\` for short (a key without a namespace is the game's). The project's own goals are \`"${GOAL_NAMESPACE}:<id>"\`.`

/** A mob's goals: what its AI does, and goals written in Lua. */
const goalFunctions: Fn[] = [
  getter(
    'goals',
    `Every goal its AI has, the game's and any added: its regular goals (walking about, attacking, looking at players) and its targeting goals (what it picks a fight with), each with its key, its priority and the controls it claims. In no particular order. ${GONE}.`,
    'MobGoal[]?',
  ),
  {
    name: 'remove_goal',
    doc: "Takes goals off it: every one with the key, the game's or one a script added. Goals aren't saved, so a mob that unloads and loads again has the game's goals back.",
    params: [{ name: 'key', type: 'string', doc: goalKeyDoc }],
    returns: [
      {
        type: 'boolean',
        doc: `Whether it had one: \`false\` when it didn't, ${ONCE_GONE}.`,
      },
    ],
    example: 'zombie:remove_goal("minecraft:random_stroll")',
  },
  {
    name: 'clear_goals',
    doc: "Takes every goal off it, its targeting goals too, but those `options.keep` names. A mob with no goals stands where it is but for what moves it (`move_to`, being pushed); goals added afterwards run as usual. Goals aren't saved, so a mob that unloads and loads again has the game's goals back.",
    params: [{ name: 'options', type: 'GoalClearOptions', doc: '', optional: true }],
    returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
    example: 'zombie:clear_goals({ keep = { "float" } })',
  },
  {
    name: 'add_goal',
    impl: 'lua',
    doc: `Gives it a goal written in Lua, which its AI runs with the goals it has. Every tick or two the AI asks \`should_start\` of each goal that isn't running and whose controls are free, or held by goals of a lower priority (a bigger number), which it then stops; \`start\` runs when it starts, \`tick\` while it runs, and \`stop\` once \`should_continue\` says no or a more important goal takes one of its controls. A goal that claims \`"target"\` is a targeting goal: it runs with the mob's targeting goals (\`hurt_by_target\`, \`nearest_attackable_target\`) rather than its regular goals, so it claims nothing else. The id is in the project's namespace: \`"guard_post"\` is \`"${GOAL_NAMESPACE}:guard_post"\`, and a goal with an id the mob already has replaces it. Goals aren't saved: they go when the mob unloads, and when the script that added them stops (a reload), so set them up again when it spawns or in the script's body. A callback that errors is reported, with its line, and its goal is taken off the mob. Goals added or removed from inside a goal's own callbacks change before the mob's next tick.`,
    params: [
      {
        name: 'id',
        type: 'string',
        doc: `What it's called, to remove it later: lowercase letters, digits, \`_\`, \`-\`, \`.\` and \`/\`. \`"${GOAL_NAMESPACE}:"\` in front is allowed, and the same.`,
      },
      { name: 'definition', type: 'GoalDefinition', doc: 'What it claims and does.' },
    ],
    returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
    example:
      'local post = vec3(0, 64, 0)\nzombie:add_goal("guard_post", {\n  priority = 1,\n  controls = { "move" },\n  should_start = function(mob)\n    local here = mob:position()\n    return here ~= nil and here:distance(post) > 4\n  end,\n  start = function(mob)\n    mob:move_to(post)\n  end,\n})',
  },
]

/** `damage` and `interact`, on every entity; `death` on a living one; `path_end` on a mob. */
const entityDamage: EventSpec = {
  name: 'damage',
  doc: 'Something is hurting it: before `nf.on("entity_damage")`, with the same event, so `event:cancel()` here stops it and `event:stop()` keeps it from `nf`. Assign `event.amount` to change how much it takes.',
  payload: 'EntityDamageEvent',
  cancellable: true,
  bubbles: true,
  writable: ['amount'],
  example:
    'pet:on("damage", function(event)\n  if event.cause == "fall" then\n    event:cancel()\n  end\nend)',
}

const entityEvents: EventSpec[] = [
  entityDamage,
  {
    name: 'interact',
    doc: 'A player right-clicked it: before the player\'s own `interact_entity` and then `nf.on("player_interact_entity")`, with the same event. `event:cancel()` stops what the click would do (a villager trading, a cow being milked). A `Player`\'s own `interact` is them clicking a block instead; a click on a player is heard by the clicker and `nf`.',
    payload: 'PlayerInteractEntityEvent',
    cancellable: true,
    bubbles: true,
  },
  ...gameEntityEvents,
]

const livingEvents: EventSpec[] = [
  {
    name: 'death',
    doc: 'It died: before `nf.on("entity_death")`, with the same event. Its handlers go with it afterwards. A `Player`\'s own `death` is `player_death` instead, then `nf.on("entity_death")`.',
    payload: 'EntityDeathEvent',
    bubbles: true,
    writable: ['drops', 'experience'],
    example: 'zombie:on("death", function(event)\n  nf.server.broadcast("The zombie fell")\nend)',
  },
  ...gameLivingEvents,
]

const mobEvents: EventSpec[] = [
  {
    name: 'path_end',
    doc: `It stopped walking where \`move_to\` sent it: it got there (within ${PATH_REACH.value} blocks), or gave up (no way further, its own AI sent it elsewhere, or the entity it went after has gone or left its world). Not after \`stop_pathing\` or a new \`move_to\`.`,
    payload: 'EntityPathEndEvent',
    example:
      'zombie:on("path_end", function(event)\n  if not event.reached then\n    zombie:teleport(vec3(10, 64, 10))\n  end\nend)',
  },
  ...gameMobEvents,
]

/**
 * A player's own events: the `player_*` server events, named without the prefix. Built from
 * `nfEvents`, so a new `player_*` event is on `Player` too. They have every `Living` and
 * `Entity` event besides (`damage`, `heal`, `mount`), but for `interact` and `death`, which
 * these replace.
 */
const playerEvents: EventSpec[] = nfEvents
  .filter((it) => it.name.startsWith('player_'))
  .map((it): EventSpec => ({
    name: it.name.slice('player_'.length),
    doc: `${it.doc} Only this player's: heard before \`nf.on("${it.name}")\`, with the same event, so \`event:stop()\` keeps it from there.`,
    bubbles: true,
    ...(it.payload ? { payload: it.payload } : {}),
    ...(it.cancellable ? { cancellable: true } : {}),
    ...(it.writable ? { writable: it.writable } : {}),
    ...(it.since ? { since: it.since } : {}),
  }))

/** A vanilla entity, by UUID. */
export const entityClass: LuaClass = {
  name: 'Entity',
  doc: "Something in a world that isn't a block: an arrow, a minecart, a dropped item, a mob, a player. A handle is its UUID, so keeping one is fine: once the entity has gone (killed, removed) or while its chunk is unloaded, its methods answer `nil` or `false` rather than erroring. Its handle is the class it is: a `Living` (health, effects, equipment), a `Mob` (a target, AI, walking, goals), a `Player` or a `DroppedItem`, each with every `Entity` method too, and taken wherever an `Entity` is. A handle to an entity that wasn't in the world when it was handed out becomes its class once it's back. The entities NetherForge draws centities with are never handed out as `Entity`. Positions are world space.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  saveable: true,
  fields: [],
  events: entityEvents,
  functions: [
    {
      name: 'id',
      doc: "Its UUID, as a string: the thing to store. A player's gives the same handle back from `nf.players.get(id)`.",
      params: [],
      returns: [{ type: 'string' }],
    },
    getter(
      'kind',
      `What it is, like \`"minecraft:zombie"\` or \`"minecraft:player"\`, or ${GONE}.`,
      'string?',
    ),
    getter(
      'exists',
      "Whether it's in the world now: alive, and in a loaded chunk. For a player, whether they're online.",
      'boolean',
    ),
    {
      name: 'is_player',
      impl: 'lua',
      doc: "Whether it's a player: then it's a `Player` handle.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_living',
      impl: 'lua',
      doc: `Whether it's a living entity (a mob, an animal, an armour stand, a player): then it's a \`Living\` handle, with health, effects and equipment. \`false\` while it isn't in the world, unless its handle was handed out as a \`Living\` already.`,
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_mob',
      impl: 'lua',
      doc: `Whether it's a mob, something with AI (a zombie, a cow, a bat): then it's a \`Mob\` handle, with a target, walking and goals. \`false\` while it isn't in the world, unless its handle was handed out as a \`Mob\` already.`,
      params: [],
      returns: [{ type: 'boolean' }],
      example:
        'local entity = player:target_entity()\nif entity and entity:is_mob() then\n  local mob = entity --[[@as Mob]]\n  mob:set_target(player)\nend',
    },
    getter(
      'location',
      `Where it is, in which world, and which way it faces, or ${GONE}.`,
      'Location?',
    ),
    getter('position', `Where it is (its feet), or ${GONE}.`, 'Vec3?'),
    getter('world', `The world it's in, or ${GONE}.`, 'World?'),
    getter(
      'eye_position',
      `Where its eyes are, which is where it looks from, or ${GONE}.`,
      'Vec3?',
    ),
    getter(
      'yaw',
      `Which way it faces horizontally, in degrees, or ${GONE}. Minecraft's convention: 0 faces south (+z), 90 west (−x), 180 north, −90 east.`,
      'number?',
    ),
    getter(
      'pitch',
      `How far up or down it looks, in degrees, or ${GONE}: −90 is straight up, 90 straight down.`,
      'number?',
    ),
    getter('direction', `The unit direction it looks in, or ${GONE}.`, 'Vec3?'),
    setter(
      'set_yaw',
      'Turns it to face a horizontal direction, keeping its pitch.',
      'degrees',
      'number',
      '0 south, 90 west.',
    ),
    setter(
      'set_pitch',
      'Tilts it to look up or down, keeping its yaw.',
      'degrees',
      'number',
      '−90 up, 90 down.',
    ),
    {
      name: 'look_at',
      doc: 'Turns it to look at a point: its yaw and pitch both.',
      params: [{ name: 'point', type: 'Vec3', doc: 'A world position.' }],
      returns: [{ type: 'boolean', doc: GONE_FALSE + '.' }],
    },
    {
      name: 'teleport',
      doc: 'Moves it: to a `Location` (facing its way, when it has a facing), or to a position in its current world. It keeps its own facing when the destination has none, and its passengers come along.',
      params: [{ name: 'location_or_position', type: 'Location|Vec3', doc: 'Where it goes.' }],
      returns: [
        {
          type: 'boolean',
          doc: `Whether it moved: ${GONE_FALSE}, and for a world that doesn't exist.`,
        },
      ],
    },
    getter('velocity', `How fast it moves, in blocks per tick, or ${GONE}.`, 'Vec3?'),
    setter(
      'set_velocity',
      'Sets how fast it moves, in blocks per tick: a push. Friction and gravity carry on from there.',
      'velocity',
      'Vec3',
    ),
    setter(
      'add_velocity',
      'Adds to how fast it moves, in blocks per tick: a knock.',
      'velocity',
      'Vec3',
    ),
    getter('is_on_ground', `Whether it stands on something. ${GONE_FALSE}.`, 'boolean'),
    {
      name: 'remove',
      doc: "Takes it out of the world for good, without dying: no drops, no death event. Its handlers and its saved table go with it. A player can't be removed: they leave through `kick`.",
      params: [],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}, and for a player.` }],
    },
    getter(
      'name',
      `What players see it called, as MiniMessage: its custom name, or its kind's name (\`"Zombie"\`). ${GONE}.`,
      'Text?',
    ),
    getter(
      'custom_name',
      `Its custom name, as MiniMessage, or \`nil\` when it has none, and ${ONCE_GONE}.`,
      'Text?',
    ),
    {
      name: 'set_custom_name',
      doc: 'Names it, or takes its custom name away. Whether players see the name above it without looking at it is `set_custom_name_visible`.',
      params: [
        {
          name: 'text',
          type: 'Text',
          doc: 'MiniMessage. Leave it out (or `nil`) to remove the name.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: GONE_FALSE + '.' }],
    },
    getter(
      'is_custom_name_visible',
      `Whether its custom name shows above it all the time, not only when a player looks at it. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter(
      'set_custom_name_visible',
      'Shows its custom name above it all the time, or only when a player looks at it.',
      'visible',
      'boolean',
    ),
    getter(
      'tags',
      'Its scoreboard tags (what `/tag` adds), sorted. Empty once it has gone.',
      'string[]',
    ),
    {
      name: 'has_tag',
      doc: `Whether it has a scoreboard tag. ${GONE_FALSE}.`,
      params: [{ name: 'tag', type: 'string', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'add_tag',
      doc: 'Adds a scoreboard tag, as `/tag <entity> add` does. Tags are saved with the entity and selectors can find them (`@e[tag=boss]`).',
      params: [{ name: 'tag', type: 'string', doc: 'Letters, digits, `_`, `-`, `.` and `+`.' }],
      returns: [
        {
          type: 'boolean',
          doc: `Whether it was added: \`false\` when it already had it, ${ONCE_GONE}.`,
        },
      ],
    },
    {
      name: 'remove_tag',
      doc: 'Takes a scoreboard tag away.',
      params: [{ name: 'tag', type: 'string', doc: '' }],
      returns: [
        {
          type: 'boolean',
          doc: `Whether it had it: \`false\` when it didn't, ${ONCE_GONE}.`,
        },
      ],
    },
    {
      name: 'data',
      doc: `Its own table for scripts to keep things in, saved on the entity itself (its persistent data container), so it goes where the entity goes and is gone when the entity is: the same table every time, shared by every script. Change it in place; it's saved when the server autosaves, when the entity's chunk unloads and when the server stops. ${DATA_VALUES} \`nil\` while the entity isn't in the world.`,
      params: [],
      returns: [{ type: 'table?' }],
      example:
        'local data = zombie:data()\nif data then\n  data.kills = (data.kills or 0) + 1\nend',
    },
    getter(
      'is_glowing',
      `Whether it glows: an outline seen through walls. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter(
      'set_glowing',
      "Makes it glow, or stops it. The outline is white, or its team's colour (`team:set_color`).",
      'glowing',
      'boolean',
    ),
    getter(
      'is_visible',
      `Whether players can see it at all (\`set_visible\`), whoever they are. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter(
      'set_visible',
      'Hides it from every player, or shows it again. Saved with the entity. To hide it from some players only, use `hide_from`.',
      'visible',
      'boolean',
    ),
    {
      name: 'hide_from',
      doc: "Hides it from one player: they stop seeing it (and can't click it), while everyone else still does. It stays hidden from them when they leave and come back, and when the entity's chunk unloads and loads again, until `show_to`.",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}, or when the player is offline.` }],
    },
    {
      name: 'show_to',
      doc: 'Shows it to a player it was hidden from with `hide_from`.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}, or when the player is offline.` }],
    },
    {
      name: 'is_hidden_from',
      doc: 'Whether `hide_from` hid it from this player.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'team',
      doc: "The project's team it's in (`team:add_member`), or `nil` when it's in none, or in a team the project didn't make (one made with `/team` or by another plugin). For a player it answers while they're offline too; for any other entity, `nil` once it has gone.",
      params: [],
      returns: [{ type: 'Team?' }],
      example:
        'local team = player:team()\nif team and team:name() == "red" then\n  player:send_message("<red>Go, red!")\nend',
    },
    getter('is_silent', `Whether it makes no sounds. ${GONE_FALSE}.`, 'boolean'),
    setter('set_silent', 'Silences it, or lets it make sounds again.', 'silent', 'boolean'),
    getter('has_gravity', `Whether it falls. ${GONE_FALSE}.`, 'boolean'),
    setter('set_gravity', 'Lets it fall, or keeps it floating where it is.', 'gravity', 'boolean'),
    getter(
      'is_invulnerable',
      `Whether nothing but creative players and the void can hurt it. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter('set_invulnerable', 'Makes it invulnerable, or not.', 'invulnerable', 'boolean'),
    getter(
      'passengers',
      'What rides on it, in order. Empty when nothing does, or once it has gone.',
      'Entity[]',
    ),
    {
      name: 'add_passenger',
      doc: "Puts something on it to ride: a player on a horse, a zombie on a chicken. It can't ride itself, nor something riding it.",
      params: [{ name: 'entity', type: 'Entity', doc: 'The rider.' }],
      returns: [
        {
          type: 'boolean',
          doc: `Whether it got on: \`false\` when either has gone, or the game refused.`,
        },
      ],
    },
    {
      name: 'remove_passenger',
      doc: 'Takes a rider off it.',
      params: [{ name: 'entity', type: 'Entity', doc: 'The rider.' }],
      returns: [{ type: 'boolean', doc: 'Whether it was riding it.' }],
    },
    getter(
      'vehicle',
      `What it rides, or \`nil\` when it rides nothing, and ${ONCE_GONE}.`,
      'Entity?',
    ),
    getter(
      'inventory',
      "What it carries, as an `Inventory`: a player's own inventory, a chest boat's or a donkey's chest, a villager's. `nil` for an entity without one, or once it has gone.",
      'Inventory?',
    ),
    {
      name: 'target_block',
      doc: "The block it's looking at: a ray from its eyes along its direction, the first block whose shape it hits (fluids don't stop it). `nil` when it looks at nothing within reach, or once it has gone.",
      params: [targetDistance],
      returns: [{ type: 'Block?' }],
      example:
        'local block = player:target_block(5)\nif block then\n  block:break_naturally()\nend',
    },
    {
      name: 'target_entity',
      doc: "The entity it's looking at: a ray from its eyes along its direction, the first entity it hits, unless a block is in the way. Never itself. `nil` when there's none within reach, or once it has gone.",
      params: [targetDistance],
      returns: [{ type: 'Entity?' }],
    },
    {
      name: 'target_centity',
      doc: "The centity it's looking at: a ray from its eyes along its direction, the first centity hitbox it hits (tested with the same shapes clicks are), unless a block is in the way. `nil` when there's none within reach, or once it has gone.",
      params: [targetDistance],
      returns: [{ type: 'Centity?' }],
    },
    ...eventFunctions(
      'Entity',
      'entity',
      'local cart = world:spawn_entity("minecraft:minecart", vec3(0, 64, 0))\nif cart then\n  cart:on("damage", function(event)\n    event:cancel()\n  end)\nend',
    ),
  ],
}

/** A living entity: a mob, an animal, an armour stand, a player. */
export const livingClass: LuaClass = {
  name: 'Living',
  doc: 'A living entity: a mob, an animal, an armour stand, a player. An `Entity` with health, attributes, status effects and equipment.',
  methods: true,
  extends: 'Entity',
  fields: [],
  events: livingEvents,
  functions: [
    getter(
      'health',
      `How much health it has, from 0 to \`max_health()\` (2 is one heart), or ${GONE}.`,
      'number?',
    ),
    setter(
      'set_health',
      'Sets its health, up to `max_health()`. 0 kills it.',
      'health',
      'number',
      'From 0 to `max_health()`.',
    ),
    getter('max_health', `The most health it can have, or ${GONE}.`, 'number?'),
    {
      name: 'damage',
      doc: 'Hurts it, as an attack would: armour and effects count, it flinches, and its `damage` handlers hear it.',
      params: [
        { name: 'amount', type: 'number', doc: 'How much, before armour: 2 is one heart.' },
        {
          name: 'source',
          type: 'Entity',
          doc: 'Who did it, for knockback, kill credit and angering a mob.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
    },
    {
      name: 'heal',
      doc: 'Gives it health back, up to `max_health()`.',
      params: [{ name: 'amount', type: 'number', doc: '2 is one heart.' }],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
    },
    ...attributeFunctions,
    getter(
      'effects',
      'Its status effects, like speed or poison. Empty once it has gone.',
      'StatusEffect[]',
    ),
    {
      name: 'has_effect',
      doc: `Whether it has a status effect. An effect the server doesn't have is an error. ${GONE_FALSE}.`,
      params: [{ name: 'effect', type: 'string', doc: 'Its id: `"minecraft:speed"`.' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'add_effect',
      doc: "Gives it a status effect, replacing one of the same kind it has. An effect the server doesn't have is an error.",
      params: [
        { name: 'effect', type: 'string', doc: 'Its id: `"minecraft:speed"`.' },
        { name: 'ticks', type: 'integer', doc: 'How long it lasts. -1 is for ever.' },
        { name: 'options', type: 'EffectOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
      example: 'player:add_effect("minecraft:speed", 20 * 10, { amplifier = 1 })',
    },
    {
      name: 'remove_effect',
      doc: "Takes a status effect away. An effect the server doesn't have is an error.",
      params: [{ name: 'effect', type: 'string', doc: 'Its id.' }],
      returns: [{ type: 'boolean', doc: 'Whether it had it.' }],
    },
    {
      name: 'equipment',
      doc: `What it holds or wears in a slot, or \`nil\` for nothing, and ${ONCE_GONE}.`,
      params: [
        { name: 'slot', type: EQUIPMENT_SLOT, doc: '`"body"` is a horse\'s or a wolf\'s armour.' },
      ],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_equipment',
      doc: "Puts an item in one of its slots, or empties it. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [
        { name: 'slot', type: EQUIPMENT_SLOT, doc: '' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
    },
    ...eventFunctions(
      'Living',
      'living',
      'local stand = world:spawn_entity("minecraft:armor_stand", vec3(0, 64, 0)) --[[@as Living?]]\nif stand then\n  stand:on("death", function(event)\n    event.drops = {}\n  end)\nend',
    ),
  ],
}

/** A mob: something living with AI. */
export const mobClass: LuaClass = {
  name: 'Mob',
  doc: "A mob: a `Living` with AI, like a zombie, a cow or a bat. It has a target, walks where it's sent (`move_to`), and its AI runs goals, the game's and any written in Lua.",
  methods: true,
  extends: 'Living',
  fields: [],
  events: mobEvents,
  functions: [
    getter(
      'target',
      `What it is after (to attack it, or follow it), or \`nil\` for nothing, and ${ONCE_GONE}.`,
      'Entity?',
    ),
    {
      name: 'set_target',
      doc: 'Sets what it is after, or calms it.',
      params: [
        {
          name: 'entity',
          type: 'Entity',
          doc: 'Leave it out (or `nil`) to clear it.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
    },
    getter(
      'has_ai',
      `Whether it thinks for itself: moves, looks around, attacks. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter(
      'set_ai',
      'Lets it think for itself, or freezes it in place (it can still be pushed and hurt).',
      'ai',
      'boolean',
    ),
    ...pathFunctions,
    ...goalFunctions,
    ...eventFunctions(
      'Mob',
      'mob',
      'zombie:on("target", function(event)\n  if event.target and event.target:is_player() then\n    event:cancel()\n  end\nend)',
    ),
  ],
}

/** An item lying on the ground. */
export const droppedItemClass: LuaClass = {
  name: 'DroppedItem',
  doc: 'An item lying on the ground, which players pick up by walking over it: an `Entity` holding one stack.',
  methods: true,
  extends: 'Entity',
  fields: [],
  functions: [
    getter('item', `The stack it is, or ${GONE}.`, 'Item?'),
    {
      name: 'set_item',
      doc: "Changes the stack it is. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [
        {
          type: 'boolean',
          doc: `${GONE_FALSE}.`,
        },
      ],
    },
    getter('pickup_delay', `How many ticks until it can be picked up, or ${GONE}.`, 'integer?'),
    setter(
      'set_pickup_delay',
      'Sets how many ticks until it can be picked up.',
      'ticks',
      'integer',
      '0 for at once; 32767 for never.',
    ),
  ],
}

/** A figure that looks like a player. */
export const mannequinClass: LuaClass = {
  name: 'Mannequin',
  doc: "A figure that looks like a player, for shopkeepers, quest givers and statues: a `Living` with a player's skin, a line of text under its name, and a pose, that isn't a player (it's on no player list, counts towards nothing and loads no chunks) and costs no more than an armour stand. Spawn one with `world:spawn_entity(\"minecraft:mannequin\", …)`, and hear players click it with its `interact` event. It stands where it's put unless pushed or hurt (`set_immovable` keeps it there), holds and wears what it's given (`set_equipment`), and turns with `look_at`. For a figure that walks, fights and plays as a player does, join a `Bot`.",
  methods: true,
  extends: 'Living',
  fields: [],
  functions: [
    getter(
      'skin',
      `Its skin: a player's name (their skin, which the server looks up), a \`Skin\` table, or \`nil\` for the default skin, and ${ONCE_GONE}.`,
      'string|Skin|nil',
    ),
    {
      name: 'set_skin',
      doc: "Gives it a skin. Players' clients draw it as soon as the server has it.",
      params: [
        {
          name: 'skin',
          type: 'string|Skin',
          doc: 'A player\'s name (`"Notch"`: their skin, looked up by the server), or a `Skin`. Leave it out (or `nil`) for the default skin.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
      example:
        'local guide = world:spawn_entity("minecraft:mannequin", vec3(0.5, 64, 0.5)) --[[@as Mannequin?]]\nif guide then\n  guide:set_skin("Notch")\n  guide:set_description("<gray>Right-click to talk")\n  guide:set_immovable(true)\nend',
    },
    getter(
      'description',
      `The line of text under its name, as MiniMessage, or \`nil\` when it shows none (a new one shows "NPC"), and ${ONCE_GONE}.`,
      'Text?',
    ),
    {
      name: 'set_description',
      doc: 'Sets the line of text under its name.',
      params: [
        {
          name: 'text',
          type: 'Text',
          doc: 'MiniMessage. Leave it out (or `nil`) to show none.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
    },
    getter(
      'is_immovable',
      `Whether nothing pushes it: players, mobs, water, explosions. ${GONE_FALSE}.`,
      'boolean',
    ),
    setter(
      'set_immovable',
      'Keeps it where it is whatever pushes it, or lets it be pushed again.',
      'immovable',
      'boolean',
    ),
    getter('main_hand', `Which hand it holds things in, or ${GONE}.`, '"left"|"right"?'),
    setter('set_main_hand', 'Sets which hand it holds things in.', 'side', '"left"|"right"'),
    getter('pose', `How it stands, or ${GONE}.`, `${MANNEQUIN_POSE}?`),
    setter('set_pose', 'Sets how it stands.', 'pose', MANNEQUIN_POSE),
    getter('skin_parts', `Which parts of its skin's outer layer show, or ${GONE}.`, 'SkinParts?'),
    {
      name: 'set_skin_parts',
      doc: "Shows or hides parts of its skin's outer layer: a part left out of the table keeps how it is.",
      params: [{ name: 'parts', type: 'SkinParts', doc: '' }],
      returns: [{ type: 'boolean', doc: `${GONE_FALSE}.` }],
    },
  ],
}

const offline = "`false` when they're offline."

/** Someone on the server. */
export const playerClass: LuaClass = {
  name: 'Player',
  doc: 'Someone on the server: a `Living`, with everything living entities have, and more. A handle is a UUID, so keeping one is fine: if they leave, its methods answer `nil` or `false` rather than erroring, and work again when they come back. `nf.players.get` hands out handles to people who are offline too. Their real inventory is numbered: the hotbar 0 to 8, the rest of the main inventory 9 to 35, armour 36 (feet), 37 (legs), 38 (chest) and 39 (head), the offhand 40, then body armour 41 and a saddle 42 (what the game keeps there for any entity).',
  methods: true,
  extends: 'Living',
  fields: [],
  events: [...playerEvents, ...buffEvents],
  functions: [
    ...statFunctions('they', 'player'),
    {
      name: 'name',
      doc: 'Their name, as they have it now (a player can change theirs between visits).',
      params: [],
      returns: [{ type: 'string' }],
    },
    getter(
      'display_name',
      "What chat and other plugins call them, as MiniMessage: their name unless something changed it. `nil` when they're offline.",
      'Text?',
    ),
    {
      name: 'set_display_name',
      doc: "Changes what chat calls them, until they leave. Their real name, and what's above their head, stay.",
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'send_message',
      doc: 'Sends them a chat line only they see.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage, like `"<green>Done!"`.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'send_actionbar',
      doc: 'Shows a line above their hotbar for a couple of seconds.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'send_title',
      doc: 'Shows a big title in the middle of their screen, with a smaller line under it.',
      params: [
        { name: 'title', type: 'Text', doc: 'MiniMessage.' },
        { name: 'subtitle', type: 'Text', doc: 'MiniMessage. Default none.', optional: true },
        { name: 'options', type: 'TitleOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example: 'player:send_title("<gold>Round 2", "<gray>Fight!", { stay = 40 })',
    },
    {
      name: 'clear_title',
      doc: 'Takes a title off their screen.',
      params: [],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'play_sound',
      doc: 'Plays a sound only they hear, where they are, following them. The same sounds `world:play_sound` takes: an unknown one is an error.',
      params: [
        {
          name: 'sound',
          type: 'string',
          doc: 'A Minecraft sound (`"minecraft:ui.button.click"`) or one of the project\'s resource pack sounds (`"<pack>/<key>"`, like `"ui/click"`).',
        },
        { name: 'options', type: 'SoundOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example: 'player:play_sound("minecraft:ui.button.click", { pitch = 2 })',
    },
    {
      name: 'stop_sound',
      doc: "Stops a sound playing for them, or every sound when it's left out. An unknown sound is an error.",
      params: [{ name: 'sound', type: 'string', doc: 'The sound to stop.', optional: true }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'spawn_particle',
      doc: "Spawns particles only they see, in their world: `world:spawn_particle` with `viewers = { player }`, so `viewers` isn't an option here.",
      params: [
        { name: 'particle', type: 'string', doc: 'A particle id: `"minecraft:happy_villager"`.' },
        { name: 'position', type: 'Vec3', doc: 'Where, in their world.' },
        { name: 'options', type: 'ParticleOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'run',
      doc: 'Runs a command as them, with their permissions. The leading slash is optional.',
      params: [{ name: 'command', type: 'string', doc: '' }],
      returns: [{ type: 'boolean', doc: 'Whether it ran.' }],
    },
    {
      name: 'has_permission',
      doc: "Whether they have a permission node. `false` when they're offline.",
      params: [{ name: 'permission', type: 'string', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    ...playerPermissionFunctions,
    getter('is_operator', "Whether they're a server operator, online or not.", 'boolean'),
    getter(
      'game_mode',
      "Their game mode, or `nil` when they're offline.",
      '"survival"|"creative"|"adventure"|"spectator"|nil',
    ),
    setter(
      'set_game_mode',
      'Changes their game mode.',
      'game_mode',
      '"survival"|"creative"|"adventure"|"spectator"',
    ),
    getter(
      'food',
      "How full they are, 0 to 20 (20 is a full bar), or `nil` when they're offline.",
      'integer?',
    ),
    setter('set_food', 'Sets how full they are.', 'food', 'integer', '0 to 20.'),
    getter(
      'saturation',
      "How long until they get hungry again, 0 up to their food level, or `nil` when they're offline.",
      'number?',
    ),
    setter(
      'set_saturation',
      'Sets their saturation, up to their food level.',
      'saturation',
      'number',
    ),
    getter('level', "Their experience level, or `nil` when they're offline.", 'integer?'),
    setter('set_level', 'Sets their experience level.', 'level', 'integer', 'At least 0.'),
    getter(
      'experience_progress',
      "How far the experience bar is towards the next level, 0 to 1, or `nil` when they're offline.",
      'number?',
    ),
    setter(
      'set_experience_progress',
      'Moves the experience bar, keeping their level.',
      'progress',
      'number',
      '0 to 1.',
    ),
    {
      name: 'give_experience',
      doc: 'Gives them experience points, as orbs would: their level goes up when the bar fills.',
      params: [{ name: 'points', type: 'integer', doc: 'At least 0.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    getter('is_flying', "Whether they're flying. `false` when they're offline.", 'boolean'),
    setter(
      'set_flying',
      'Starts or stops them flying. They have to be able to fly (`set_can_fly`) to start.',
      'flying',
      'boolean',
    ),
    getter(
      'can_fly',
      "Whether they may fly (double-tapping jump), as in creative. `false` when they're offline.",
      'boolean',
    ),
    setter('set_can_fly', 'Lets them fly, or not.', 'can_fly', 'boolean'),
    getter(
      'walk_speed',
      "How fast they walk, 0 to 1 (0.2 is normal), or `nil` when they're offline.",
      'number?',
    ),
    setter(
      'set_walk_speed',
      'Sets how fast they walk.',
      'speed',
      'number',
      '−1 to 1; 0.2 is normal.',
    ),
    getter(
      'fly_speed',
      "How fast they fly, 0 to 1 (0.1 is normal), or `nil` when they're offline.",
      'number?',
    ),
    setter(
      'set_fly_speed',
      'Sets how fast they fly.',
      'speed',
      'number',
      '−1 to 1; 0.1 is normal.',
    ),
    getter('is_sneaking', "Whether they're sneaking. `false` when they're offline.", 'boolean'),
    getter('is_sprinting', "Whether they're sprinting. `false` when they're offline.", 'boolean'),
    getter(
      'locale',
      'The language their game is set to, like `"en_us"`, or `nil` when they\'re offline. Right after they join it can still be the default until their game says.',
      'string?',
    ),
    getter(
      'ping',
      "How long a message takes to reach them and come back, in milliseconds, as the server estimates it; `nil` when they're offline.",
      'integer?',
    ),
    {
      name: 'kick',
      doc: 'Disconnects them from the server.',
      params: [
        {
          name: 'reason',
          type: 'Text',
          doc: "What their screen says, MiniMessage. Default the server's own message.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: offline }],
    },
    ...playerServerFunctions,
    getter(
      'ender_chest',
      "Their ender chest, as an `Inventory`, or `nil` when they're offline.",
      'Inventory?',
    ),
    {
      name: 'sky',
      doc: "Their own sky (`player:set_sky`), as the calling script names it, or `nil` for none: then they see their world's (`world:sky()`).",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_sky',
      doc: "Shows them a sky of the project's, whatever world they're in, over their world's own (`world:set_sky`), until it's cleared: as `world:set_sky` does for everyone in a world, and the same errors. Nothing for someone offline, and it ends when they leave.",
      params: [
        {
          name: 'sky',
          type: 'string',
          doc: "A sky of the project's (its file's name under `skies/`, or `namespace:id` for one a package it depends on exports).",
        },
        { name: 'options', type: 'SkyOptions', doc: '', optional: true },
      ],
      returns: [],
      example: 'player:set_sky("eclipse", { fade_ticks = 40 })',
    },
    {
      name: 'clear_sky',
      doc: "Takes their own sky away (`player:set_sky`): they see their world's again, fading over `options.fade_ticks`.",
      params: [{ name: 'options', type: 'SkyOptions', doc: '', optional: true }],
      returns: [],
    },
    {
      name: 'storage',
      doc: "A set of item slots of the project's own that they keep: extra equipment, a backpack, a bank. It's saved in the player's own save data, with their inventory, in the same file and the same write, so a crash or a restart can't duplicate an item between the two or lose one. Shown in a menu by binding slots to it (`slot:bind`), where what players move in and out is saved at once. Dying doesn't drop it. An `Inventory` of kind `\"storage\"`: its item methods work as on any other, and it answers `nil` and `false` while they're offline (it can't be changed then). `nil` when they're offline. A name that isn't an id is an error, and so is a size outside 1 to 256.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: "Lowercase letters, digits and `_`: the project's own (a package's scripts keep theirs apart, under their namespace). Scripts that use one name share one storage.",
        },
        {
          name: 'size',
          type: 'integer',
          doc: "How many slots it has, numbered from 0. Changing it later keeps every item: a slot past the size isn't shown, but its item is kept, and comes back with a bigger size.",
        },
      ],
      returns: [{ type: 'Inventory?' }],
      example:
        'local trinkets = player:storage("trinkets", 4)\nif trinkets and trinkets:has_item({ kind = "ruby_ring" }) then\n  player:send_message("Your ring glows.")\nend',
    },
    {
      name: 'hud',
      doc: "Their HUD: the resource packs' HUD elements, filled in for them (bars, icons, text). A handle like this one: fine to keep, and it answers `false` or `nil` while they're offline.",
      params: [],
      returns: [{ type: 'Hud' }],
      example: 'player:hud():fill_bar("ui/mana", 40, 100)',
    },
    getter(
      'held_item',
      "What's in their main hand (the selected hotbar slot), or `nil` for nothing or when they're offline.",
      'Item?',
    ),
    {
      name: 'set_held_item',
      doc: "Puts an item in their main hand, or empties it. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [
        { name: 'item', type: 'Item', doc: 'Leave it out (or `nil`) to empty it.', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
    },
    getter(
      'off_hand_item',
      "What's in their offhand, or `nil` for nothing or when they're offline.",
      'Item?',
    ),
    {
      name: 'set_off_hand_item',
      doc: 'Puts an item in their offhand, or empties it.',
      params: [
        { name: 'item', type: 'Item', doc: 'Leave it out (or `nil`) to empty it.', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
    },
    getter(
      'cursor_item',
      "What they carry on their cursor with an inventory screen open (their own, a vanilla one or one of the project's menus), or `nil` for nothing or when they're offline.",
      'Item?',
    ),
    {
      name: 'set_cursor_item',
      doc: "Puts an item on their cursor, replacing what they carry, or empties it: for `click_inventory` and `drag_inventory` handlers, say, taking what they hold. What's on the cursor goes back to their inventory (or drops) when they close the screen. The server can't tell whether their own inventory is open, so with no screen open it's carried out of sight until they next open and close it.",
      params: [
        { name: 'item', type: 'Item', doc: 'Leave it out (or `nil`) to empty it.', optional: true },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example:
        'player:on("click_inventory", function(event)\n  local gem = event.cursor_item\n  if event.own and gem and gem.data and gem.data.gem and event.item then\n    event:cancel()\n    local item = event.item\n    item.lore = { "<aqua>Socketed" }\n    event.inventory:set_item(event.index, item)\n    player:set_cursor_item(nil)\n  end\nend)',
    },
    getter(
      'held_slot',
      "Which hotbar slot they have selected, 0 to 8, or `nil` when they're offline.",
      'integer?',
    ),
    setter('set_held_slot', 'Selects a hotbar slot for them.', 'index', 'integer', '0 to 8.'),
    {
      name: 'give_item',
      doc: "Puts an item into their inventory, topping up matching stacks first, and drops what doesn't fit at their feet. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [
        {
          type: 'integer?',
          doc: "How many were dropped at their feet: 0 when everything fitted. `nil` when they're offline.",
        },
      ],
      example:
        'player:give_item({ kind = "minecraft:gold_nugget", count = 5, data = { coin = true } })',
    },
    ...playerRecipeFunctions,
    ...playerAdvancementFunctions,
    {
      name: 'item_cooldown',
      doc: "How many ticks are left before they can use an item again, or 0. `nil` when they're offline.",
      params: [
        {
          name: 'key',
          type: 'string',
          doc: 'An item kind (`"minecraft:ender_pearl"`), or a cooldown group an item\'s `cooldown` names.',
        },
      ],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_item_cooldown',
      doc: "Makes them wait before using an item again, as an ender pearl does: every item of that kind, or every item in that cooldown group. Items whose own `cooldown` names another group aren't affected by their kind's.",
      params: [
        {
          name: 'key',
          type: 'string',
          doc: 'An item kind, or a cooldown group: `"<namespace>:<name>"`.',
        },
        { name: 'ticks', type: 'integer', doc: 'How long; 0 ends it.' },
      ],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'open_menu',
      doc: "Opens a menu for them: the shared window for a `shared` menu, otherwise a new window of their own, filled from the file (or from the template). Its script's body has run (for a new window) and its `open` handlers have heard it by the time this returns. A menu the project doesn't have is an error; a template that's gone opens nothing (`nil`).",
      params: [
        {
          name: 'menu',
          type: 'string|MenuTemplate',
          doc: 'Its folder name under `menus/`, or a `MenuTemplate` from `nf.menus.create`.',
          names: 'menu',
        },
        {
          name: 'options',
          type: 'MenuOpenOptions',
          doc: "What to open it with: `context` for the new window. A key it doesn't take is an error.",
          optional: true,
        },
      ],
      returns: [{ type: 'Menu?', doc: "The window, or `nil` when they're offline." }],
      example:
        'nf.commands.register("shop", { players_only = true }, function(event)\n  event.player:open_menu("shop", { context = { discount = 0.1 } })\nend)',
    },
    {
      name: 'menu',
      doc: "The project menu window they're looking at, or `nil` (nothing open, or a chest that isn't one of ours).",
      params: [],
      returns: [{ type: 'Menu?' }],
    },
    {
      name: 'refresh_items',
      doc: 'Sends their game every stack it shows again where it would now look different to them: their inventory, the window they have open and their cursor, each worked out again by the `view` handlers (and `nf.on("item_view")`). Stacks are sent by themselves when they change, but not when only what a `view` handler works its look out from does, so call this then: when a shop menu opens or closes, say. It\'s done once at the end of the tick however often it\'s called, and only stacks that look different from what their game was last sent are sent.',
      params: [],
      returns: [{ type: 'boolean', doc: "`false` when they're offline." }],
      example:
        'nf.on("menu_open", function(event)\n  event.player:refresh_items()\nend)\nnf.on("menu_close", function(event)\n  event.player:refresh_items()\nend)',
    },
    {
      name: 'close_menu',
      doc: 'Closes the project menu they have open. Anything else on their screen (a chest, their own inventory) stays open.',
      params: [],
      returns: [
        { type: 'boolean', doc: "`false` when they're offline or have no menu of ours open." },
      ],
    },
    {
      name: 'open_inventory',
      doc: "Shows them a real inventory, as if they'd opened it: a chest's, another player's, their ender chest. Whatever was on their screen closes. A storage (`player:storage`) is shown in a menu instead, by binding slots to it (`slot:bind`): opening one is `false`.",
      params: [{ name: 'inventory', type: 'Inventory', doc: '' }],
      returns: [
        { type: 'boolean', doc: "`false` when they're offline, or the inventory has gone." },
      ],
      example:
        'local chest = player:ender_chest()\nif chest then\n  player:open_inventory(chest)\nend',
    },
    {
      name: 'close_inventory',
      doc: 'Closes whatever container screen they have open: a chest, a project menu, their own inventory.',
      params: [],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'open_dialog',
      doc: "Puts a dialog on their screen: the same as `dialog:open_for(player, options)`. One of the project's dialogs already on their screen is closed (its `close` handlers hear it). A dialog the project doesn't have is an error; a `Dialog` from `nf.dialogs.create` that's gone opens nothing (`false`).",
      params: [
        {
          name: 'dialog',
          type: 'string|Dialog',
          doc: 'Its folder name under `dialogs/`, or a `Dialog` (one from `nf.dialogs.create`, say).',
          names: 'dialog',
        },
        {
          name: 'options',
          type: 'DialogOpenOptions',
          doc: 'What to open it with, for this opening only: `context`, input `values`, a `title` or `body` text.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example: 'player:open_dialog("welcome", { values = { nickname = player:name() } })',
    },
    {
      name: 'close_dialog',
      doc: "Takes whatever dialog is on their screen away. When it's one of the project's, its `close` handlers hear it.",
      params: [],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'sidebar',
      doc: 'Their sidebar: the panel of lines at the right of their screen. Always the same handle for the same player.',
      params: [],
      returns: [{ type: 'Sidebar' }],
      example: 'player:sidebar():set_lines({ "<gold>Coins: " .. coins, "<gray>Round 2" })',
    },
    {
      name: 'border',
      doc: "A border of their own, which only they see: the first call gives them one, a copy of their world's, and they see it instead of their world's until `reset_border()` or they leave (it follows them from world to world). The server draws it for them but doesn't enforce it: it doesn't hurt them. `nil` when they're offline.",
      params: [],
      returns: [{ type: 'WorldBorder?' }],
      example:
        'local border = player:border()\nif border then\n  border:set_center(vec3(100, 0, 100))\n  border:set_size(16)\nend',
    },
    {
      name: 'has_own_border',
      doc: "Whether they see a border of their own (`player:border()`) rather than their world's. `false` when they're offline.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'reset_border',
      doc: "Takes their own border away: they see their world's again, and the handle `border()` gave answers `nil`.",
      params: [],
      returns: [
        { type: 'boolean', doc: "`false` when they're offline or had no border of their own." },
      ],
    },
    getter(
      'tab_header',
      "The text above the player list (what tab shows), as MiniMessage, or `nil` when there's none or they're offline.",
      'Text?',
    ),
    {
      name: 'set_tab_header',
      doc: 'Sets the text above the player list.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage; `""` for none.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    getter(
      'tab_footer',
      "The text below the player list, as MiniMessage, or `nil` when there's none or they're offline.",
      'Text?',
    ),
    {
      name: 'set_tab_footer',
      doc: 'Sets the text below the player list.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage; `""` for none.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    getter(
      'tab_name',
      "What the player list (tab) calls them, as MiniMessage: their name unless `set_tab_name` changed it. Their team's prefix and suffix go around it. `nil` when they're offline.",
      'Text?',
    ),
    {
      name: 'set_tab_name',
      doc: "Changes what the player list calls them, until they leave. Their real name, what chat calls them and what's over their head stay.",
      params: [
        {
          name: 'text',
          type: 'Text',
          doc: 'MiniMessage. Leave it out to go back to their name.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example: 'player:set_tab_name("<gold>★ " .. player:name())',
    },
    getter(
      'tab_order',
      "Where they come in the player list: higher comes first, and equal ones are sorted as Minecraft sorts them (by team, then name). 0 unless `set_tab_order` changed it; `nil` when they're offline.",
      'integer?',
    ),
    {
      name: 'set_tab_order',
      doc: 'Moves them up or down the player list, until they leave.',
      params: [{ name: 'order', type: 'integer', doc: 'Higher comes first; default 0.' }],
      returns: [{ type: 'boolean', doc: offline }],
    },
    {
      name: 'is_listed_for',
      doc: "Whether they're in another player's player list (`set_listed_for`). `false` when either is offline.",
      params: [{ name: 'viewer', type: 'Player', doc: 'Whose list.' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_listed_for',
      doc: "Takes them out of another player's player list, or puts them back. Only the list's entry goes: the viewer still sees them in the world and hears them in chat (to hide them altogether, `hide_from`). It stays that way when either of them leaves and comes back, until it's set back.",
      params: [
        { name: 'viewer', type: 'Player', doc: 'Whose list.' },
        { name: 'listed', type: 'boolean', doc: '' },
      ],
      returns: [{ type: 'boolean', doc: '`false` when either is offline.' }],
      example:
        'for _, other in ipairs(nf.players.online()) do\n  staff:set_listed_for(other, false)\nend',
    },
    getter(
      'below_name',
      "The line under their name tag (`set_below_name`), as MiniMessage, or `nil` when they have none (or they're offline).",
      'Text?',
    ),
    {
      name: 'set_below_name',
      doc: "Puts a line of text under their name tag, which every player sees: a rank, their health, a level. It goes when they leave. Minecraft draws this line for every player as soon as anyone has one, so while someone does, players without one show an empty line. It's shown through the scoreboard's below-name slot, which it takes over from anything else showing there.",
      params: [
        {
          name: 'text',
          type: 'Text',
          doc: 'MiniMessage. Leave it out to take the line away.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: offline }],
      example: 'player:set_below_name("<gold>VIP")',
    },
    getter(
      'bossbars',
      "The project's boss bars they're among the viewers of (`bossbar:show_to`), offline or not.",
      'BossBar[]',
    ),
    getter(
      'resource_pack_status',
      'What became of the project\'s resource pack on their game: `"loaded"`, `"declined"`, `"failed"`, `"pending"` (accepted, still downloading), or `nil` before their game has answered (and when they\'re offline, or there\'s no resource pack).',
      '"loaded"|"declined"|"failed"|"pending"|nil',
    ),
    ...playerIllusionFunctions,
    {
      name: 'data',
      doc: `Their saved table: one per player, by id, kept across restarts and shared by every script. By convention each module keeps its own part of it under its name (\`player:data().shop\`), so modules don't trip over each other. It works for someone offline too (\`nf.players.get\` finds anyone who has joined). ${DATA_VALUES} ${DATA_SAVING} It's also saved when they leave.`,
      params: [],
      returns: [{ type: 'table' }],
      example:
        'local shop = player:data().shop or {}\nplayer:data().shop = shop\nshop.coins = (shop.coins or 0) + 5',
    },
    {
      name: 'session',
      doc: "Their table for this time online: one per player, the same table every time, shared by every script, and never saved. It's made empty the first time a script asks after they join, kept while they stay (through a module's reload too: it's the player's, not the script's), and dropped once they've left, after their `player_quit` handlers, so the next time they join it's empty again. A whole-project reload (`netherforge.json` changing, or `/nf reload` with no files) empties it, as it does every script's state. Anything a script holds goes in it (handles, functions, a `Task`), so a module needn't keep a map keyed by player and clear it in `player_quit`. By convention each module keeps its own part of it under its name, as with `data()`. `nil` for a player who isn't online.",
      params: [],
      returns: [{ type: 'table?' }],
      example:
        'local session = assert(player:session())\nsession.combo = (session.combo or 0) + 1\nif session.combo >= 3 then\n  player:send_message("Combo!")\nend',
    },
    ...eventFunctions(
      'Player',
      'player',
      'player:on("chat", function(event)\n  log(player:name(), "said", event.message)\nend)',
    ),
  ],
}

/** The plain tables entities and players take and give, and their events' payloads. */
export const entityShapes: LuaClass[] = [
  shape(
    'SkinParts',
    "The parts of a skin's outer layer a mannequin shows, each `true` when shown. `mannequin:skin_parts()` gives every one; `set_skin_parts` changes those given.",
    [
      { name: 'cape', type: 'boolean?', doc: 'Its cape, when its skin has one.' },
      { name: 'jacket', type: 'boolean?', doc: '' },
      { name: 'left_sleeve', type: 'boolean?', doc: '' },
      { name: 'right_sleeve', type: 'boolean?', doc: '' },
      { name: 'left_pants', type: 'boolean?', doc: '' },
      { name: 'right_pants', type: 'boolean?', doc: '' },
      { name: 'hat', type: 'boolean?', doc: '' },
    ],
  ),
  shape(
    'EntityFilter',
    "Which entities `world:entities` lists. Every key is optional; an entity is listed when it matches all of those given. A key that isn't one of these is an error.",
    [
      { name: 'kind', type: 'string?', doc: 'Only this kind: `"minecraft:zombie"`.' },
      { name: 'tag', type: 'string?', doc: 'Only entities with this scoreboard tag.' },
      {
        name: 'near',
        type: 'Vec3?',
        doc: 'Only entities within `radius` of this point. Needs `radius`.',
      },
      { name: 'radius', type: 'number?', doc: 'How far from `near`, in blocks. Needs `near`.' },
      {
        name: 'living',
        type: 'boolean?',
        doc: '`true`: only living entities (mobs, animals, players); `false`: only the rest.',
      },
    ],
  ),
  shape(
    'EntitySpawnOptions',
    "How `world:spawn_entity` sets up what it spawns, before anything (an `entity_spawn` handler included) sees it. A key that isn't one of these is an error.",
    [
      { name: 'custom_name', type: 'Text?', doc: 'Its custom name, MiniMessage.' },
      { name: 'tags', type: 'string[]?', doc: 'Scoreboard tags to give it.' },
      {
        name: 'data',
        type: 'any',
        doc: "A table its `data()` table starts with: the same values a `data()` table may hold. Something that can't be saved is an error.",
      },
      { name: 'velocity', type: 'Vec3?', doc: 'How fast it starts moving, in blocks per tick.' },
    ],
  ),
  shape(
    'EffectOptions',
    "How a status effect is given. A key that isn't one of these is an error.",
    [
      {
        name: 'amplifier',
        type: 'integer?',
        doc: 'Its strength above the first: 0 is speed I, 1 speed II. Default 0.',
      },
      {
        name: 'ambient',
        type: 'boolean?',
        doc: 'Fainter particles, as from a beacon. Default `false`.',
      },
      { name: 'particles', type: 'boolean?', doc: 'Whether it shows particles. Default `true`.' },
      {
        name: 'icon',
        type: 'boolean?',
        doc: 'Whether it shows an icon on their screen. Default `true`.',
      },
    ],
  ),
  shape('StatusEffect', 'One status effect on a living entity: what `living:effects()` lists.', [
    { name: 'effect', type: 'string', doc: 'Its id: `"minecraft:speed"`.' },
    { name: 'ticks', type: 'integer', doc: 'How long it has left; -1 for ever.' },
    { name: 'amplifier', type: 'integer', doc: 'Its strength above the first: 0 is level I.' },
    { name: 'ambient', type: 'boolean', doc: 'Whether its particles are faint, as from a beacon.' },
    { name: 'particles', type: 'boolean', doc: 'Whether it shows particles.' },
    { name: 'icon', type: 'boolean', doc: 'Whether it shows an icon.' },
  ]),
  shape(
    'PathOptions',
    "How a mob walks where `move_to` sends it. A key that isn't one of these is an error.",
    [
      {
        name: 'speed',
        type: 'number?',
        doc: 'How fast, times its own walking speed (its `movement_speed` attribute): 1 walks, 1.5 hurries. More than 0. Default 1.',
      },
    ],
  ),
  shape('MobGoal', "One of a mob's goals: what `mob:goals` lists.", [
    {
      name: 'key',
      type: 'string',
      doc: `Its key: \`"minecraft:random_stroll"\` for one of the game's, \`"${GOAL_NAMESPACE}:guard_post"\` for one the project added, or another plugin's.`,
    },
    {
      name: 'priority',
      type: 'integer',
      doc: "Its priority, the game's goals' too: smaller is more important. A goal takes its controls from running goals with a bigger number.",
    },
    {
      name: 'controls',
      type: `(${GOAL_CONTROL})[]`,
      doc: 'What it claims while it runs, so no less important goal claiming the same runs with it: `"move"`, `"look"`, `"jump"`, `"target"` (a targeting goal). Empty for a goal that claims none of these.',
    },
    { name: 'running', type: 'boolean', doc: 'Whether it runs now.' },
  ]),
  shape(
    'GoalDefinition',
    "A goal written in Lua, for `mob:add_goal`. Each callback gets the mob. A key that isn't one of these is an error.",
    [
      {
        name: 'priority',
        type: 'integer',
        doc: "How important it is: smaller goes first. A goal takes its controls from running goals with a bigger number, and waits for those with a smaller one or the same. The game's goals use about 0 (swimming up in water) to 8 (looking about).",
      },
      {
        name: 'controls',
        type: `(${GOAL_CONTROL})[]?`,
        doc: 'What it claims while it runs, so no less important goal claiming the same runs with it: `"move"` (it walks the mob), `"look"`, `"jump"`, or `"target"` alone (it picks what the mob fights). Default none: it runs alongside the goals that claim something, and one at a time with the others that claim nothing.',
      },
      {
        name: 'should_start',
        type: '(fun(mob: Mob): boolean)?',
        doc: "Whether to start now, asked every tick or two while it isn't running. Default: always.",
      },
      {
        name: 'should_continue',
        type: '(fun(mob: Mob): boolean)?',
        doc: 'Whether to keep running, asked every tick or two while it runs. Default: what `should_start` says.',
      },
      { name: 'start', type: '(fun(mob: Mob))?', doc: 'It starts running.' },
      {
        name: 'tick',
        type: '(fun(mob: Mob))?',
        doc: 'While it runs: every tick or two, as the game updates its goals.',
      },
      {
        name: 'stop',
        type: '(fun(mob: Mob))?',
        doc: 'It stopped: `should_continue` said no, a more important goal took one of its controls, or it was removed while running.',
      },
    ],
  ),
  shape(
    'GoalClearOptions',
    "Which goals `mob:clear_goals` leaves. A key that isn't one of these is an error.",
    [
      {
        name: 'keep',
        type: 'string[]?',
        doc: `The keys of goals to keep, as \`remove_goal\` takes them: \`{ "float" }\` keeps \`"minecraft:float"\`, so a mob still swims.`,
      },
    ],
  ),
  shape(
    'AttributeModifier',
    "One modifier on an entity's attribute: what `living:attribute_modifiers` lists.",
    [
      {
        name: 'id',
        type: 'string',
        doc: `Its full id: \`"${MODIFIER_NAMESPACE}:slow_zone"\` for one a script added, or the game's own (\`"minecraft:sprinting"\`), an item's, another plugin's.`,
      },
      { name: 'amount', type: 'number', doc: 'How much.' },
      {
        name: 'operation',
        type: '"add_value"|"add_multiplied_base"|"add_multiplied_total"',
        doc: 'How `amount` applies: added; added times the base value; or the total multiplied by `1 + amount`.',
      },
    ],
  ),
  shape('TitleOptions', "How long a title takes. A key that isn't one of these is an error.", [
    { name: 'fade_in', type: 'integer?', doc: 'Ticks it takes to appear. Default 10.' },
    { name: 'stay', type: 'integer?', doc: 'Ticks it stays. Default 70.' },
    { name: 'fade_out', type: 'integer?', doc: 'Ticks it takes to go. Default 20.' },
  ]),
  payload(
    'EntityDamageEvent',
    'Something is hurting an entity: heard by the entity, then `nf.on("entity_damage")`.',
    [
      { name: 'entity', type: 'Entity', doc: 'Who is hurt.' },
      {
        name: 'amount',
        type: 'number',
        doc: 'How much, before armour: 2 is one heart. Writable: assign another to change it.',
      },
      {
        name: 'cause',
        type: 'string',
        doc: 'What hurt it, as Minecraft names it: `"entity_attack"`, `"projectile"`, `"fall"`, `"fire"`, `"lava"`, `"drowning"`, `"void"`, …',
      },
      {
        name: 'attacker',
        type: 'Entity?',
        doc: 'Who did it, when someone did: for an arrow, whoever shot it.',
      },
    ],
  ),
  payload('EntityDeathEvent', 'A living entity died: heard by it, then `nf.on("entity_death")`.', [
    { name: 'entity', type: 'Living', doc: 'Who died. Its handle answers `nil` from now on.' },
    { name: 'killer', type: 'Entity?', doc: 'Who killed it, when someone did.' },
    {
      name: 'drops',
      type: 'Item[]',
      doc: 'What it drops. Writable: assign another list of items (or change this one) to drop those instead.',
    },
    {
      name: 'experience',
      type: 'integer',
      doc: 'The experience it drops. Writable: assign another to change it.',
    },
  ]),
  payload('EntityPathEndEvent', 'A mob stopped walking where `move_to` sent it.', [
    { name: 'entity', type: 'Mob', doc: 'The mob.' },
    {
      name: 'reached',
      type: 'boolean',
      doc: `Whether it got there, within ${PATH_REACH.value} blocks; \`false\` when it gave up.`,
    },
  ]),
  payload(
    'PlayerInteractEntityEvent',
    'A player right-clicked an entity: heard by the entity, then the player, then `nf.on("player_interact_entity")`.',
    [
      { name: 'player', type: 'Player', doc: 'Who clicked.' },
      { name: 'entity', type: 'Entity', doc: 'What they clicked.' },
      {
        name: 'hand',
        type: '"main_hand"|"off_hand"',
        doc: 'Which hand the click was with: the game asks for each hand in turn.',
      },
    ],
  ),
  payload(
    'EntitySpawnEvent',
    'An entity is coming into a world: heard by the world, then `nf.on("entity_spawn")`.',
    [
      {
        name: 'entity',
        type: 'Entity',
        doc: "What's coming. Its methods already work, so a handler can name it or tag it.",
      },
      { name: 'world', type: 'World', doc: "The world it's coming into." },
      {
        name: 'cause',
        type: 'string',
        doc: 'What caused it, as Minecraft names it: `"natural"`, `"spawner"`, `"breeding"`, `"command"`, `"custom"` (a plugin or a script), …',
      },
    ],
  ),
]
