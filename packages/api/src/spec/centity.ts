import { buffEvents, statFunctions } from './stats.ts'
import type { EventSpec, Fn, LuaClass } from '../types.ts'
import { DATA_SAVING, DATA_VALUES } from './data.ts'
import { eventFunctions } from './events.ts'
import {
  DEFAULT_MAX_DROP,
  DEFAULT_RANGE,
  DEFAULT_SPEED,
  DEFAULT_STEP_HEIGHT,
  MAX_HEIGHT,
  MAX_RANGE,
  MAX_SPEED,
  MAX_WIDTH,
  NODE_LIMIT,
  PATH_REACH,
  REPATH_TICKS,
  START_DROP,
} from './constants.ts'

/** Where a centity's own space is explained, for docs that take or give a point in it. */
const CENTITY_SPACE =
  "The centity's own space has its origin at the centity's position (`centity:position()`) and is turned by its yaw (`centity:yaw()`): its +Z is the way the centity faces, its Y is the world's. It's the space a root node's translation is in."

/** What a walk is measured by: the bottom middle of the centity's hitboxes. */
const FEET =
  "A walk goes by the centity's **feet**: the middle of the bottom of its hitboxes as they're placed when it sets off (its position, for a centity without any)."

/** A centity walking (or flying) somewhere: where it goes, and `path_end` when it gets there or gives up. */
const pathFunctions: Fn[] = [
  {
    name: 'move_to',
    doc: `Makes the whole centity walk somewhere, finding its way round the blocks in the way: to a place in its own world, or after an entity or another centity, looking for a new path to wherever that is every ${REPATH_TICKS.value} ticks. When it stops, its \`path_end\` handlers hear whether it got there. ${FEET} The way is found over the blocks' collision shapes, sized to its hitboxes (other centities aren't in the way), and walked a little each tick, before physics and scripts: it steps up and drops down as the options allow, falls when nothing holds it up, and finds another way when a block appears across its path. With \`fly = true\` it flies instead, through the air in any direction. A new \`move_to\`, \`stop_pathing\` and \`teleport\` end the walk it was on, and so does a reload of its centity (its script's body can set off again). Walks aren't saved, and a centity whose chunk unloads waits where it is until it loads again. Its physics bodies are carried along with it.`,
    params: [
      {
        name: 'target',
        type: 'Location|Vec3|Entity|Centity',
        doc: 'Where its feet go: a position in its world, a `Location` (in its world: another world is `false`), or an entity or centity to go after.',
      },
      { name: 'options', type: 'CentityPathOptions', doc: '', optional: true },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `Whether it found a way and set off, which may only take it as close as it can get: \`false\` when it can't get any closer than it is (walking, it needs ground under it, within ${START_DROP.value} blocks), for a target further away than \`range\`, in another world or gone, or once it's removed.`,
      },
    ],
    example:
      'this:on("path_end", function(event)\n  log(event.reached and "made it" or "gave up")\nend)\nthis:move_to(vec3(10, 64, 10), { speed = 2 })',
  },
  {
    name: 'stop_pathing',
    doc: 'Stops it where it is, ending its walk without a `path_end`. One that was falling stays in the air: a centity only falls while it walks.',
    params: [],
    returns: [
      {
        type: 'boolean',
        doc: "Whether it was walking: `false` when it wasn't, or once it's removed.",
      },
    ],
  },
  {
    name: 'has_path',
    doc: "Whether it's walking somewhere now (`move_to`). `false` once it's removed.",
    params: [],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'path_target',
    doc: `Where the path it's walking ends, as where its feet will be: where it was sent, or as close as it can get when there's no way all the way there. ${FEET} \`nil\` when it isn't walking, or once it's removed.`,
    params: [],
    returns: [{ type: 'Vec3?' }],
  },
]

const centityEvents: EventSpec[] = [
  {
    name: 'spawn',
    doc: "Once, when the instance is first put into the world, after its script's body has run: not when it comes back after a restart or a reload. A script's body runs every time it starts; this is for what happens only once.",
    payload: 'CentityEvent',
    example: 'this:on("spawn", function()\n  this:play_animation("rise")\nend)',
  },
  {
    name: 'remove',
    doc: 'The instance is being removed for good (`centity:remove()`, `/nf kill`). Its script then unloads (`nf.on("unload")`), and every handler on it and its nodes goes.',
    payload: 'CentityEvent',
  },
  {
    name: 'tick',
    doc: "Every tick while the instance's chunk is loaded, or every `n` ticks with `{ every = n }`. Each instance falls on its own one of those `n` ticks, so a crowd of centities with `every = 4` spreads over four ticks rather than all running on one. A centity nobody listens to this on isn't ticked at all, and one whose handlers all have `every` is only called into on the ticks one is due.",
    payload: 'TickEvent',
    options: ['every'],
    example:
      'local angle = 0\nthis:on("tick", function()\n  angle = (angle + 6) % 360\n  this:node("blade"):set_rotation(vec3(0, angle, 0))\nend)',
  },
  {
    name: 'click',
    doc: 'A player clicked one of its hitboxes: after the clicked node and the nodes above it, and before `nf.on("centity_click")`, unless one of them called `event:stop()`.',
    payload: 'ClickEvent',
    bubbles: true,
    example:
      'this:on("click", function(event)\n  this:play_animation("open")\n  event.player:send_message("<gold>Opened")\nend)',
  },
  {
    name: 'animation_start',
    doc: 'One of its animations started (`play_animation`), from the beginning.',
    payload: 'AnimationEvent',
  },
  {
    name: 'animation_end',
    doc: 'An animation reached its end: a `once` clip has finished, a `hold` clip is holding its last pose. Looping clips never end.',
    payload: 'AnimationEvent',
  },
  {
    name: 'chunk_load',
    doc: "Its chunk came back into memory (someone came near), so it's ticking again.",
    payload: 'CentityEvent',
  },
  {
    name: 'chunk_unload',
    doc: 'Its chunk was unloaded (everyone went away): it stops ticking until `chunk_load`. Its handlers stay.',
    payload: 'CentityEvent',
  },
  {
    name: 'hit',
    doc: "It's flying (`launch`) and its path touched something it can hit: a centity's hitbox, a player or another entity, in the order it meets them, each once (or again after `rehit` ticks). `event:cancel()` lets it pass through as if it weren't there, without using up a `pierce`. Otherwise it goes on through only while it has `pierce` left; past that, this is where it ends (`flight_end`, `\"hit\"`).",
    payload: 'FlightHitEvent',
    cancellable: true,
    example:
      'this:on("hit", function(event)\n  if event.target then\n    event.target:emit("game:struck", { damage = 4 })\n  end\nend)',
  },
  {
    name: 'bounce',
    doc: "It's flying with `bounce` and came off a block: `event.normal` is the face it hit, `event.speed` how fast it was going into it.",
    payload: 'FlightBlockEvent',
  },
  {
    name: 'stick',
    doc: "It's flying with `stick` and stuck in a block: it stays there, its flight over but not ended, until its `lifetime` is up.",
    payload: 'FlightBlockEvent',
  },
  {
    name: 'flight_end',
    doc: 'Its flight ended: it hit something with no `pierce` left (`"hit"`), met a block it neither bounces off nor sticks in (`"block"`), ran out of `lifetime` (`"lifetime"`), reached a chunk that isn\'t loaded (`"unloaded"`), or a script stopped it (`"stopped"`). Then, unless `remove_on_end` is `false`, it\'s removed: its `remove` handlers hear it next.',
    payload: 'FlightEndEvent',
    example:
      'this:on("flight_end", function(event)\n  this:world():explode(event.position, 2)\nend)',
  },
  {
    name: 'damage',
    doc: "It's about to lose health to `centity:damage`: assign `event.amount` to change how much (a defence, a weakness), or `event:cancel()` it (a moment it can't be hurt). Its health goes down by what's left, its bar shows, and at 0 it dies (`death`).",
    payload: 'CentityDamageEvent',
    cancellable: true,
    writable: ['amount'],
    example:
      'this:on("damage", function(event)\n  event.amount = math.max(1, event.amount - 4) -- armour\nend)',
  },
  {
    name: 'death',
    doc: "Its health reached 0. Then, unless its `health` says `removeOnDeath: false`, it's removed: its `remove` handlers hear it next. `event.source` and `event.cause` are what dealt the last blow.",
    payload: 'CentityDeathEvent',
    example:
      'this:on("death", function(event)\n  nf.loot.roll("slime", { location = this:location() })\nend)',
  },
  {
    name: 'land',
    doc: 'Its `body` came down onto something after falling (or jumping): once, as it lands. `event.speed` is how fast it was falling, for fall damage or a thud.',
    payload: 'CentityLandEvent',
    example:
      'this:on("land", function(event)\n  if event.speed > 12 then\n    this:emit("game:hurt", { amount = event.speed - 12 })\n  end\nend)',
  },
  {
    name: 'bump',
    doc: "Its `body` ran into something it didn't land on: a wall, or a ceiling. Once each tick it does, with the face's `normal` (pointing back at it) and how fast it was going into it.",
    payload: 'CentityBumpEvent',
    example:
      'this:on("bump", function(event)\n  if event.normal.y == 0 and this:is_on_ground() then\n    this:jump(1.2)\n  end\nend)',
  },
  {
    name: 'touch',
    doc: "Its hitboxes started touching something's: another centity's, a player's or another entity's (each shown node's boxes, as `world:overlap_box` sees them). Once when they start touching, and again only after they've come apart. Worked out each tick only for centities something listens to this on, so a crowd nobody listens to costs nothing. Both centities hear it when two touch, each with the other in `event.other`.",
    payload: 'TouchEvent',
    example:
      'this:on("touch", function(event)\n  if event.other then\n    event.other:emit("game:bumped", { by = this })\n  end\nend)',
  },
  {
    name: 'path_end',
    doc: `It stopped walking where \`move_to\` sent it: it got there (to the end of its path, or within ${PATH_REACH.value} blocks of what it went after), or gave up (no way further, its way blocked with no other way round, or what it went after has gone or left its world). Not after \`stop_pathing\`, a new \`move_to\`, \`teleport\` or a reload of its centity.`,
    payload: 'CentityPathEndEvent',
    example:
      'this:on("path_end", function(event)\n  if not event.reached then\n    this:move_to(vec3(0, 64, 0))\n  end\nend)',
  },
]

const nodeEvents: EventSpec[] = [
  {
    name: 'click',
    doc: 'A player clicked this node\'s hitbox, or the hitbox of a node under it (`event.target` says which). Then its parent hears it, and so on up to the root, then the centity, then `nf.on("centity_click")`, unless a handler calls `event:stop()`.',
    payload: 'ClickEvent',
    bubbles: true,
    example:
      'local crate = assert(this:node("crate"))\ncrate:on("click", function(event)\n  crate:apply_impulse_at(vec3(0, 0, 4), event.hit_position)\n  event:stop()\nend)',
  },
  {
    name: 'collide',
    doc: 'This physics body hit something: a block, another centity, or another body of its own centity. Once per thing touched, when they start touching.',
    payload: 'CollideEvent',
    example:
      'ball:on("collide", function(event)\n  if event.speed > 2 then\n    log("clack")\n  end\nend)',
  },
  {
    name: 'wake',
    doc: 'This physics body started moving again after sleeping: something hit it, what held it up went, or a script pushed it.',
    payload: 'NodeEvent',
  },
  {
    name: 'sleep',
    doc: "This physics body settled: it's still, and isn't simulated until something wakes it.",
    payload: 'NodeEvent',
  },
]

/** What a node a script adds is made of: `NodeDef`, in Lua's spelling. */
const NODE_DEFINITION =
  'A table in the shape of one node of `centity.json`, minus its `parent` (it hangs from the node you call this on), in Lua spelling: `transform` (`{ translation?, rotation?, scale? }`, each a `Vec3`: where it starts, as a rest pose a script can change afterwards), `display` (`{ type = "block", block = "minecraft:stone" }`, `{ type = "item", item = "minecraft:diamond", item_transform? }` or `{ type = "text", text = "<red>Hi", billboard?, alignment?, background?, line_width?, see_through?, shadow? }`), `hitbox` (`{ boxes?, shape?, raycast?, fitted_to? }`, a box being `{ min = Vec3, max = Vec3 }`) and `physics` (`{ gravity?, mass?, bounciness?, friction?, drag?, angular_drag?, max_speed?, max_spin?, collider?, shape?, rotates?, sleeps?, blocks?, entities? }`). Every field is optional: a node with none is a pivot to hang others from. Held to the same rules as `centity.json`, by the same validator: a key it does not take, a value of the wrong type, or a block, item or hitbox the file would be refused for is an error.'

/** What nodes a script adds are, once, for every function that adds or removes one. */
const ADDED_NODES =
  "A node a script adds is **runtime state**, like a rest pose a script sets: it takes part in everything a declared node does (its transform follows its parent, a display and an interaction entity are spawned for it and sent to players, a hitbox takes clicks and `click` bubbles up through its parents, a `physics` body is simulated and collides, `raycast` hits it) except what a file names: an animation clip can't drive it, because clips are written against the file's nodes, though it follows its animated parent. It is **not saved**: after a restart, and when its centity is reloaded, it's gone, and the script's body, which runs again, is where to add it. Only nodes a script added can be removed; the nodes `centity.json` declares can't be."

/** What's true of a body's settings at runtime. */
const BODY_RUNTIME =
  "A body's settings are runtime state, like a node's pose: they last until its centity is reloaded or the server restarts, when it's back to its file's."

/** A spawned centity instance. `this` in its script. */
export const centityClass: LuaClass = {
  name: 'Centity',
  doc: "One spawned centity: `this` in the centity's script, and what `nf.centities.spawn`, `nf.centities.get` and `nf.centities.all` return. A handle stays valid as long as the instance exists; after it's removed its methods return `nil` or `false` rather than erroring.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  saveable: true,
  fields: [],
  events: [...centityEvents, ...buffEvents],
  customEvents: true,
  functions: [
    {
      name: 'id',
      doc: "The instance's id, unique and stable across restarts. Keep this rather than the handle if you need to find it later.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'kind',
      doc: 'Which centity it is: its folder name under `centities/`. `nil` once removed.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'exists',
      doc: 'Whether the instance is still in the world.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_natural',
      doc: "Whether it appeared by itself, from its `spawning` rules in `centity.json`, and is still temporary: it isn't saved, it's removed when no player is near, and it counts against its cap. `false` for one a script spawned, one `keep` has kept, and once it's removed.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_temporary',
      doc: "Whether a script spawned it temporary (`nf.centities.spawn`'s `temporary` or `lifetime`), and it still is: it isn't saved, and it goes when the script that spawned it unloads, or when its lifetime is up. `false` for one `keep` has kept, and once it's removed.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'lifetime',
      doc: "How many ticks a temporary centity with a `lifetime` has left before it's removed, or `nil` for one without (or once it's removed).",
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_lifetime',
      doc: "Gives it this many ticks more before it's removed, from now. A centity with a lifetime is temporary, so one that wasn't becomes so: it's no longer saved, and goes when its lifetime is up (one spawned temporary still goes with its script too). Leave it out (or `nil`) to take its lifetime away; it stays temporary until `keep`.",
      params: [{ name: 'ticks', type: 'integer', doc: 'At least 1.', optional: true }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
    },
    {
      name: 'keep',
      doc: 'Makes a natural or temporary centity a normal one: it is saved from now on (it comes back after a restart), is never removed for being far from players or because a script unloaded or a lifetime ran out, and no longer counts against a cap. Nothing happens to one that is already normal.',
      params: [],
      returns: [
        {
          type: 'boolean',
          doc: 'Whether it was natural or temporary: `false` for one that was already normal, and once it is removed.',
        },
      ],
      example:
        'this:on("click", function(event)\n  if this:keep() then\n    event.player:send_message("<green>It follows you now")\n  end\nend)',
    },
    {
      name: 'node',
      doc: 'One of its nodes by name, or `nil` if it has none by that name.',
      params: [
        { name: 'name', type: 'string', doc: "The node's name in `centity.json`.", names: 'node' },
      ],
      returns: [{ type: 'Node?' }],
      example: 'this:node("lid"):set_rotation(vec3(-60, 0, 0))',
    },
    {
      name: 'nodes',
      doc: 'Every node, parents before their children.',
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'roots',
      doc: "Its root nodes (those with no parent), in the file's order. Every other node hangs from one of them.",
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'add_node',
      doc: `Adds a root node: one with no parent, whose translation is in the centity's own space, as if \`centity.json\` had one more. ${ADDED_NODES} A name that's already a node's, or isn't a usable node name (letters, digits, \`_\` and \`-\`, at most 64 characters), is an error, and so is a definition the file's rules would refuse. Returns \`nil\` once the centity is removed.`,
      params: [
        { name: 'name', type: 'string', doc: "A new name, unique among the centity's nodes." },
        { name: 'definition', type: 'table', doc: NODE_DEFINITION, optional: true },
      ],
      returns: [{ type: 'Node?' }],
      example:
        'local lamp = assert(this:add_node("lamp", {\n  transform = { translation = vec3(0, 2, 0) },\n  display = { type = "block", block = "minecraft:sea_lantern" },\n  hitbox = {},\n}))\nlamp:on("click", function(event)\n  lamp:remove()\nend)',
    },
    {
      name: 'properties',
      doc: 'Its `properties` from its `centity.json`: the project\'s own facts about it, any JSON (`{ damage = 12, class = "melee" }`), as a table. A new table each call, so keep it rather than ask in a loop. An empty table when the file has none, `nil` once it\'s gone.',
      params: [],
      returns: [{ type: 'table?' }],
      example: 'local stats = assert(this:properties())\nlog(stats.damage)',
    },
    {
      name: 'bounds',
      doc: "The box around all its hitboxes as they're shown now (each shown node's boxes, turned and placed), in the world: what `world:overlap_box` and the other spatial queries test against. `nil` for a centity with no hitboxes showing, or once it's removed.",
      params: [],
      returns: [{ type: 'Bounds?' }],
      example:
        'local box = this:bounds()\nif box then\n  local top = vec3((box.min.x + box.max.x) / 2, box.max.y, (box.min.z + box.max.z) / 2)\n  world:spawn_particle("minecraft:heart", top)\nend',
    },
    {
      name: 'location',
      doc: "Where it stands, in which world, and which way it faces, or `nil` once it's removed. The location's `yaw` is the centity's (`centity:yaw()`); its `pitch` is `nil`, because a centity only turns about the vertical.",
      params: [],
      returns: [{ type: 'Location?' }],
    },
    {
      name: 'position',
      doc: "Where it stands in its world: the anchor every node is placed relative to. `nil` once it's removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'world',
      doc: "The world it's in, or `nil` once it's removed.",
      params: [],
      returns: [{ type: 'World?' }],
    },
    {
      name: 'yaw',
      doc: "Which way it faces, in degrees, or `nil` once it's removed. Minecraft's convention: 0 faces south (+z), 90 west (−x), 180 north, −90 east, always given back between −180 and 180. Its +Z axis points that way, and every node is turned with it about its position.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_yaw',
      doc: "Turns the whole centity to face a yaw, about the vertical through its position: every node, its hitboxes and its physics bodies turn with it (a moving body keeps going, turned by as much). The turn is saved. Pitch and roll aren't a centity's: rotate a root node for those. Does nothing once it's removed.",
      params: [
        {
          name: 'degrees',
          type: 'number',
          doc: "Minecraft's convention (0 south, 90 west); any number, wrapped into −180 to 180.",
        },
      ],
      returns: [],
      example: 'this:set_yaw(this:yaw() + 90)',
    },
    {
      name: 'look_at',
      doc: 'Turns it to face a world position, as `set_yaw` does: only about the vertical, so how far above or below the point is makes no difference. A point straight above or below its position leaves it as it is.',
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: 'local target = player:position()\nif target then\n  this:look_at(target)\nend',
    },
    {
      name: 'to_local',
      doc: `A world position in the centity's own space: what a root node's translation would be to stand there. ${CENTITY_SPACE} \`nil\` once it's removed.`,
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_world',
      doc: `A position in the centity's own space as a world position. ${CENTITY_SPACE} \`nil\` once it's removed.`,
      params: [{ name: 'point', type: 'Vec3', doc: "A position in the centity's space." }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'teleport',
      doc: "Moves the whole centity: to a `Location` (turning to face its `yaw`, when it has one), or to a position in its current world. It keeps its own facing when the destination has none; a location's `pitch` is ignored.",
      params: [
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: 'Where its position (the anchor) goes.',
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it moved: `false` once it's removed, or for a world that doesn't exist.",
        },
      ],
      example: 'this:teleport(this:position() + vec3(0, 5, 0))',
    },
    {
      name: 'follow',
      doc: 'Makes it ride a player: it moves with them and turns with their body, its displays and hitboxes at the anchor coming along, until `stop_following`, a `teleport`, or a `move_to` ends it. Not for a centity with physics bodies.',
      params: [{ name: 'player', type: 'Player', doc: 'Who it follows.' }],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it follows them now: `false` once it's removed, for a player who is offline, and for a centity with physics bodies.",
        },
      ],
      example: 'this:follow(player)',
    },
    {
      name: 'stop_following',
      doc: "Stops it following its player, where it is now. `false` when it wasn't following, or once it's removed.",
      params: [],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
    },
    ...statFunctions('it', 'this'),
    {
      name: 'health',
      doc: "How much health it has left: from its `centity.json`'s `health` (its `max` when it spawns), or what `set_max_health` gave it. `nil` for one without health, or once it's removed. Not saved: after a restart it's whole again.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'max_health',
      doc: "Its health when whole, or `nil` without health (or once it's removed).",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_health',
      doc: "Sets how much health it has, between 0 and its `max_health`: no `damage` or `death` event, and 0 doesn't kill it (`damage` does). Its bar shows the change. `false` without health, or once it's removed.",
      params: [{ name: 'health', type: 'number', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_max_health',
      doc: "Changes its health when whole (more than 0), keeping what it has but no more than the new most. One without `health` in its file gets health this way (full, and no bar). `false` once it's removed.",
      params: [{ name: 'max_health', type: 'number', doc: '' }],
      returns: [{ type: 'boolean' }],
      example:
        '-- A boss with more health for each player near it.\nthis:set_max_health(2000 * #this:world():players())\nthis:set_health(assert(this:max_health()))',
    },
    {
      name: 'damage',
      doc: "Takes health from it: its `damage` handlers may change the amount or cancel it, its health goes down by what's left, its bar shows, and at 0 it dies (`death`, then it's removed unless its file says otherwise). Damage to one already dead does nothing.",
      params: [
        { name: 'amount', type: 'number', doc: 'How much, at least 0.' },
        { name: 'options', type: 'CentityDamageOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'number?',
          doc: "How much it took after its handlers (0 when cancelled), or `nil` for one without health, one already dead, or once it's removed.",
        },
      ],
      example: 'local dealt = target:damage(12, { source = player, cause = "melee" })',
    },
    {
      name: 'heal',
      doc: 'Gives it back health, up to its `max_health`. Its bar shows the change.',
      params: [{ name: 'amount', type: 'number', doc: 'At least 0.' }],
      returns: [
        {
          type: 'number?',
          doc: "How much it gained, or `nil` without health (or once it's removed).",
        },
      ],
    },
    {
      name: 'is_dead',
      doc: 'Whether its health has reached 0 through `damage`. `false` for one without health. A dead one its file keeps (`removeOnDeath: false`) stays dead until `set_health` gives it health again.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'has_body',
      doc: "Whether it has a body: a `body` in its `centity.json`, or one `set_body` gave it. Without one the body functions do nothing (their getters answer `false`, `nil` or `vec3.zero`). `false` once it's removed.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'body',
      doc: "Its body's settings as they are now: what its `centity.json`'s `body` says, every field filled in, with what `set_body` changed. `nil` without a body, or once it's removed.",
      params: [],
      returns: [{ type: 'BodySettings?' }],
    },
    {
      name: 'set_body',
      doc: `Changes its body's settings: only the fields given, the rest as they are. One without a body gets one, the fields left out as \`centity.json\`'s defaults. ${BODY_RUNTIME} A value out of range is clamped as in the file; a width or height that isn't more than 0 is an error.`,
      params: [{ name: 'settings', type: 'BodySettings', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        "-- Wings: it flies until they're clipped.\nthis:set_body({ fly = true, drag = 0.6 })",
    },
    {
      name: 'velocity',
      doc: "How fast its body is moving, in blocks per second along the world's axes: `vec3.zero` without a body, `nil` once it's removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_velocity',
      doc: "Replaces its body's velocity: a knock back, a dash, a launch. The body carries on from it under gravity and drag, and walking eases it back to its walk.",
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` without a body.' }],
      example:
        'this:set_velocity((this:position() - player:position()):normalized() * 8 + vec3(0, 6, 0))',
    },
    {
      name: 'add_velocity',
      doc: "Adds to its body's velocity.",
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` without a body.' }],
    },
    {
      name: 'walk_velocity',
      doc: "The velocity its body is walking at (`set_walk_velocity`), or `nil` when it isn't walking, has no body, or is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_walk_velocity',
      doc: "Walks its body: every tick it speeds up or slows down toward this velocity by its `acceleration` (`air_acceleration` while it's off the ground), so it reaches it smoothly and keeps to it, round whatever pushes it about, until it's changed. Only the sideways part counts, unless it flies, when it steers it in any direction. Leave it out (or `nil`) to stop walking: on the ground it then slows to a stop by its `friction`. Walking doesn't turn it: `set_yaw` or `look_at` does.",
      params: [
        {
          name: 'velocity',
          type: 'Vec3',
          doc: "Blocks per second, along the world's axes: a direction times a speed.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` without a body.' }],
      example:
        'local target = player:position()\nlocal here = this:position()\nif target and here then\n  this:set_walk_velocity((target - here):flat():normalized() * 3)\nend',
    },
    {
      name: 'jump',
      doc: "Sends its body up fast enough to rise `height` blocks, worked out from its gravity and drag: what a jump over a block (1.2) needs. Whether it's on the ground is for the script to check (`is_on_ground`), so a double jump is one more call.",
      params: [{ name: 'height', type: 'number', doc: 'Blocks, at least 0.' }],
      returns: [{ type: 'boolean', doc: '`false` without a body.' }],
      example: 'if this:is_on_ground() and this:wall() then\n  this:jump(1.2)\nend',
    },
    {
      name: 'is_on_ground',
      doc: 'Whether its body is standing on something: a block, as of its last move. `false` without a body.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_in_liquid',
      doc: 'Whether its body is in water or lava (the block at its middle). `false` without a body.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'wall',
      doc: 'The face of what stopped its body sideways on its last move, as the direction straight out of it (`vec3(-1, 0, 0)` for a wall to its east), or `nil` when nothing did, or without a body. What a walker turns round at, or jumps over.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'launch',
      doc: "Makes it a projectile: from now on the server flies the whole centity every tick at its velocity, under its gravity and drag, and sweeps its path (a sphere of `radius`) for what it hits: centities' hitboxes, players and entities (`hit`), and blocks (it ends, bounces or sticks: `flight_end`, `bounce`, `stick`). It flies until it ends, then is removed unless told not to. Nothing about a flight is saved. A launch over a flight replaces it. One with a `body` stops walking while it flies, and walks again after. Every value is in blocks and seconds, as everywhere.",
      params: [{ name: 'options', type: 'LaunchOptions', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        'local eye, facing = assert(player:eye_position()), assert(player:direction())\nlocal arrow = assert(nf.centities.spawn("arrow", eye, { temporary = true }))\narrow:launch({ velocity = facing * 40, gravity = 20, radius = 0.15, owner = player, aim = "aim", pierce = 1 })',
    },
    {
      name: 'is_launched',
      doc: "Whether it's flying as a projectile (`launch`), stuck in a block or lying where it came to rest included: until its flight ends. `false` once it's removed.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'stop_flight',
      doc: 'Ends its flight where it is, as if it had ended by itself: `flight_end` hears `"stopped"`, and it\'s removed unless `remove_on_end` was `false`.',
      params: [],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it was flying: `false` when it wasn't, or once it's removed.",
        },
      ],
    },
    ...pathFunctions,

    {
      name: 'data',
      doc: `This instance's own saved table, kept with the instance across restarts and removed with it. Every script that holds the instance (its own, a module's) gets the same table. ${DATA_VALUES} ${DATA_SAVING} \`nil\` once it's removed.`,
      params: [],
      returns: [{ type: 'table?' }],
      example:
        'local data = assert(this:data())\ndata.opened = (data.opened or 0) + 1\ndata.last_player = event.player',
    },
    {
      name: 'remove',
      doc: 'Takes it out of the world for good. Its `remove` handlers hear it, then its script unloads.',
      params: [],
      returns: [],
    },
    {
      name: 'play_animation',
      doc: "Starts one of its animations, from the beginning (or `from_tick`), restarting it if it's already playing. Its `animation_start` handlers hear it before this returns. An animation it doesn't have is an error.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: "The animation's name in `centity.json`.",
          names: 'animation',
        },
        {
          name: 'options',
          type: 'AnimationOptions',
          doc: "How to play it this time. A key it doesn't take is an error.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        'this:on("click", function()\n  this:play_animation("open", { speed = 2, blend_ticks = 5 })\nend)',
    },
    {
      name: 'stop_animation',
      doc: 'Stops one animation, or all of them. The nodes it was moving go back to rest.',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Leave out to stop everything.',
          optional: true,
          names: 'animation',
        },
      ],
      returns: [],
    },
    {
      name: 'pause_animation',
      doc: "Holds a playing animation where it is: its nodes keep the pose it had reached, until `resume_animation`. An animation it doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'resume_animation',
      doc: "Carries on with a paused animation from where it stopped. An animation it doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'is_animation_playing',
      doc: 'Whether an animation is playing (a paused one still is, and so is a `hold` animation that reached its end).',
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'playing_animations',
      doc: 'The animations playing now, oldest first.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'animations',
      doc: 'Every animation the centity has.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'animation_tick',
      doc: "How far into its clip a playing animation is, in ticks (20 a second): within the clip's length, so a looping one starts again from 0 each time round. Not always whole, at a speed other than 1. `nil` when it isn't playing; an animation the centity doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'seek_animation',
      doc: "Jumps a playing animation to a point in its clip, and poses the nodes there at once. Past the end, a looping clip wraps round, and any other ends on its next tick. An animation the centity doesn't have is an error.",
      params: [
        { name: 'name', type: 'string', doc: '', names: 'animation' },
        { name: 'tick', type: 'number', doc: 'Ticks from the start of the clip, at least 0.' },
      ],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'animation_speed',
      doc: "How fast a playing animation runs: 1 is as authored, 2 twice as fast. `nil` when it isn't playing; an animation the centity doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_animation_speed',
      doc: "Changes how fast a playing animation runs, from where it is now. An animation the centity doesn't have is an error.",
      params: [
        { name: 'name', type: 'string', doc: '', names: 'animation' },
        {
          name: 'multiplier',
          type: 'number',
          doc: '1 is as authored, 0.5 half speed, 0 holds it still (like pausing). Not negative.',
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'is_glowing',
      doc: "Whether every node that draws something glows (`set_glowing`). `false` once it's removed, or when it draws nothing.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_glowing',
      doc: "Outlines every node that draws something, seen through walls, the way the Glowing effect outlines a mob. The colour is `set_glow_color`'s. The same as `set_glowing` on each node.",
      params: [{ name: 'glowing', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: 'this:set_glow_color("#ffaa00")\nthis:set_glowing(true)',
    },
    {
      name: 'glow_color',
      doc: "The colour its block and item displays glow in, as `#RRGGBB`: `nil` when they don't share one, when none was set (they glow white), or once it's removed.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_glow_color',
      doc: "Sets the colour every block and item display node glows in (the same as `set_glow_color` on each). Minecraft draws no glow colour on text displays, so they're left as they are. It doesn't turn the glow on: `set_glowing` does.",
      params: [
        {
          name: 'color',
          type: 'string',
          doc: '`#RRGGBB`. Leave it out for the default, white. Anything else is an error.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` once it's removed, or when it has no block or item display.",
        },
      ],
    },
    {
      name: 'hide_from',
      doc: "Hides the whole centity from one player: they don't see its nodes and can't click its hitboxes. Everyone else still does. It stays hidden from them when they leave and come back, and when its chunk unloads and loads again, until `show_to`, a server restart or the centity's removal. Hiding it from someone offline counts for when they join.",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        'assert(this:node("chest")):on("click", function(event)\n  this:hide_from(event.player)\nend)',
    },
    {
      name: 'show_to',
      doc: 'Shows it to a player it was hidden from.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
    },
    {
      name: 'is_hidden_from',
      doc: 'Whether `hide_from` hid it from a player.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'view_range',
      doc: "How far away its displays are drawn, in blocks: 64 unless `set_view_range` changed it. `nil` once it's removed.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_view_range',
      doc: "How far away its displays are drawn, in blocks. Each player's own entity distance setting scales it further, so it's the distance at 100%. Not saved, like everything a script sets on a node.",
      params: [{ name: 'blocks', type: 'number', doc: 'At least 0.' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: '-- A landmark seen from far off.\nthis:set_view_range(256)',
    },
    ...eventFunctions(
      'Centity',
      'centity',
      'this:on("tick", function()\n  this:node("blade"):rotate(vec3.up, 6)\nend)',
      true,
    ),
  ],
}

/** One node of a centity instance. */
export const nodeClass: LuaClass = {
  name: 'Node',
  doc: "One node of one centity instance. Its transform (`translation`, `rotation`, `scale`) is in its parent's space, in the same units as `centity.json`: blocks, degrees (X then Y then Z) and scale factors. A root node's parent space is the centity's own: origin at the centity's position, turned by its yaw. `world_position`, `to_world`, `to_local` and `world_direction` convert to and from the world; physics (velocities, impulses and the point an impulse lands at) is always in world space. What a script sets is the node's rest pose; an animation driving the same channel overrides it while it plays. Nothing a script sets is saved: the script's body, which runs every time it starts, is where to set it up. A node with `physics` is moved by it: each tick physics writes its translation and rotation, after animations and before scripts, so a script's write has the last word (setting the rotation re-seeds the body's orientation).",
  methods: true,
  handle: {
    key: [
      { name: 'centity', type: 'string' },
      { name: 'name', type: 'string' },
    ],
  },
  fields: [],
  events: nodeEvents,
  functions: [
    {
      name: 'name',
      doc: "The node's name.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'centity',
      doc: 'The instance it belongs to.',
      params: [],
      returns: [{ type: 'Centity' }],
    },
    {
      name: 'parent',
      doc: 'Its parent node, or `nil` for a root.',
      params: [],
      returns: [{ type: 'Node?' }],
    },
    {
      name: 'children',
      doc: 'Its child nodes, by name.',
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'add_child',
      doc: `Adds a node that hangs from this one, so it moves, turns and scales with it. ${ADDED_NODES} The name is unique among the centity's nodes, not only this node's children. A name that's taken, or isn't a usable node name, is an error, and so is a definition the file's rules would refuse. Returns \`nil\` once its centity or this node is gone.`,
      params: [
        { name: 'name', type: 'string', doc: "A new name, unique among the centity's nodes." },
        { name: 'definition', type: 'table', doc: NODE_DEFINITION, optional: true },
      ],
      returns: [{ type: 'Node?' }],
      example:
        'local arm = this:node("body"):add_child("arm", {\n  transform = { translation = vec3(0.5, 1, 0) },\n  display = { type = "item", item = "minecraft:stick" },\n})',
    },
    {
      name: 'remove',
      doc: 'Removes this node and every node below it, with their displays and hitboxes (players see them go at the end of the tick), their physics bodies and every handler on them; the handles answer `nil` and `false` after that. Only a node a script added can be removed: removing one `centity.json` declares is an error, since animations, physics and other scripts are written against it. Returns whether it was removed: `false` if it is already gone, or its centity is.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'translation',
      doc: "Its offset from its parent (for a root node, from the centity's position), as shown now: animation and physics included. `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'rotation',
      doc: 'Its rotation relative to its parent, in degrees, applied X then Y then Z. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'scale',
      doc: 'Its scale on each of its own axes. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_translation',
      doc: "Moves it relative to its parent (a root node, relative to the centity's position).",
      params: [{ name: 'translation', type: 'Vec3', doc: "In its parent's space, in blocks." }],
      returns: [],
    },
    {
      name: 'set_rotation',
      doc: 'Turns it relative to its parent. Displays ease to the new pose over a tick, so setting it every tick looks smooth.',
      params: [{ name: 'rotation', type: 'Vec3', doc: 'Degrees, applied X then Y then Z.' }],
      returns: [],
      example:
        'local angle = 0\nthis:on("tick", function()\n  angle = (angle + 6) % 360\n  this:node("blade"):set_rotation(vec3(0, angle, 0))\nend)',
    },
    {
      name: 'set_scale',
      doc: 'Scales it along its own axes.',
      params: [
        { name: 'scale', type: 'Vec3', doc: 'A factor per axis: `vec3.one` is its natural size.' },
      ],
      returns: [],
    },
    {
      name: 'stretch_between',
      doc: "Lays it from one point of the world to another: its origin halfway, its Z pointing from `from` to `to`, and its Z scale the distance between them. For a node whose display is one block long along Z and centred on its origin (a beam, a string, a link), that's the whole way from one to the other. Its X and Y scale stay as they are, or are `thickness`.",
      params: [
        { name: 'from', type: 'Vec3', doc: 'A point in the world.' },
        { name: 'to', type: 'Vec3', doc: 'A point in the world.' },
        { name: 'options', type: 'StretchOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example:
        'local beam = assert(this:node("beam"))\nlocal here = assert(this:position())\nbeam:stretch_between(here, vec3(10, 70, 4), { thickness = 0.2 })',
    },
    {
      name: 'is_rope',
      doc: 'Whether it\'s a rope node (`"rope"` in `centity.json`).',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'rope_between',
      doc: "Strings a rope node between two ends, and keeps it strung each tick as they move, until `release_rope` or another `rope_between`: its links laid along a line from one to the other, sagging with `length`'s slack, as many shown as the distance needs (up to its `links`; the rest hidden). An end is a point, a node (its origin, as shown), or an entity (half a block above its feet; a player's at their hands' height, 1.1 blocks up). An end that's gone (a removed centity, an entity that left) releases the rope. A node that isn't a rope is an error.",
      params: [
        { name: 'from', type: 'Vec3|Node|Entity', doc: '' },
        { name: 'to', type: 'Vec3|Node|Entity', doc: '' },
        { name: 'options', type: 'RopeOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example:
        '-- A chain from the player\'s hand to the ball.\nlocal player = assert(nf.players.online()[1])\nassert(this:node("chain")):rope_between(player, assert(this:node("ball")), { length = 6 })',
    },
    {
      name: 'release_rope',
      doc: 'Stops keeping a rope strung: its links stay where they were last laid.',
      params: [],
      returns: [],
    },
    {
      name: 'tween',
      doc: "Eases its translation, rotation or scale from where they are to new values over some ticks, as `set_translation`, `set_rotation` and `set_scale` would each tick: and with `back`, eases them back again. A tween of a channel this node is already tweening replaces it, from where it is; `set_*` on a channel doesn't stop its tween. Animations drive what they drive over it, as they do over `set_*`; an `additive` one changes it.",
      params: [{ name: 'options', type: 'TweenOptions', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example:
        '-- Squash flat as it lands, and spring back.\nthis:on("land", function()\n  this:node("root"):tween({ scale = vec3(1.4, 0.6, 1.4), ticks = 3, easing = "ease_out", back = true })\nend)',
    },
    {
      name: 'is_tweening',
      doc: 'Whether a tween is easing any of its channels.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'stop_tween',
      doc: 'Stops its tweens where they are now.',
      params: [],
      returns: [{ type: 'boolean', doc: "`false` when it wasn't tweening." }],
    },
    {
      name: 'world_position',
      doc: 'Where its origin is in the world, as shown now: animation and physics included. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'world_rotation',
      doc: "Its rotation relative to the world, in degrees applied X then Y then Z, as shown now: its own and every ancestor's together. Scale doesn't count. `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_local',
      doc: "A world position in this node's own space: the space its children's translations are in, which moves, turns and scales with it, as shown now. `nil` once its centity is removed, or while it's scaled to nothing on some axis.",
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_world',
      doc: "A position in this node's own space (where a child at that translation would be) as a world position, as shown now. `nil` once its centity is removed.",
      params: [{ name: 'point', type: 'Vec3', doc: "A position in the node's space." }],
      returns: [{ type: 'Vec3?' }],
      example:
        '-- The top centre of a block display, wherever it has rolled to.\nlocal top = crate:to_world(vec3(0.5, 1, 0.5))',
    },
    {
      name: 'world_direction',
      doc: "A direction in this node's own space as a world direction: turned with the node, but not moved or scaled. `node:world_direction(vec3.south)` is where its +Z points. `nil` once its centity is removed.",
      params: [{ name: 'direction', type: 'Vec3', doc: "A direction in the node's space." }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'look_at',
      doc: 'Turns it so its +Z axis points at a world position, keeping its X axis level (no roll), by setting its rotation as `set_rotation` does. Exact unless an ancestor is scaled unevenly. Pointing at its own origin leaves it as it is.',
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [
        {
          type: 'boolean',
          doc: '`false` once its centity is removed, or while its parent is scaled to nothing.',
        },
      ],
      example: 'this:node("head"):look_at(player:position() + vec3(0, 1.6, 0))',
    },
    {
      name: 'rotate',
      doc: "Turns it further, by an angle about an axis in its parent's space, by the right-hand rule (as `Vec3:rotated` does), by setting its rotation as `set_rotation` does.",
      params: [
        { name: 'axis', type: 'Vec3', doc: "In its parent's space; any length but zero." },
        { name: 'degrees', type: 'number', doc: '' },
      ],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example: 'this:on("tick", function()\n  this:node("blade"):rotate(vec3.up, 6)\nend)',
    },
    {
      name: 'is_visible',
      doc: "Whether it's set visible (it can still be hidden by a hidden parent).",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_visible',
      doc: "Shows or hides it and everything under it. A hidden node can't be clicked.",
      params: [{ name: 'visible', type: 'boolean', doc: '' }],
      returns: [],
    },
    {
      name: 'is_clickable',
      doc: 'Whether its hitbox takes clicks (`set_clickable`). `true` for a node without one, which has nothing to click anyway.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_clickable',
      doc: 'Turns its hitbox off or back on without hiding the node: an unclickable node is still drawn, but clicks pass through it as if it had no hitbox. Its children keep their own. Physics still treats the hitbox as solid. Not saved.',
      params: [{ name: 'clickable', type: 'boolean', doc: '' }],
      returns: [],
      example:
        '-- A button that only works once.\nbutton:once("click", function()\n  button:set_clickable(false)\nend)',
    },
    {
      name: 'display_kind',
      doc: 'What it draws: `"block"`, `"item"`, `"model"` (a resource pack look) or `"text"`, or `nil` for a node that draws nothing (a pivot, a hitbox alone) or once its centity is removed.',
      params: [],
      returns: [{ type: '"block"|"item"|"model"|"text"?' }],
    },
    {
      name: 'display_block',
      doc: 'The block state a block display shows, like `"minecraft:oak_slab[type=top]"`, or `nil` when the node isn\'t a block display.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_display_block',
      doc: 'Changes the block a block display shows. A hitbox with `shape: "collision"` follows the new block\'s shape.',
      params: [
        {
          name: 'block',
          type: 'string',
          doc: 'A block state, like `"minecraft:oak_slab[type=top]"`.',
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when the node isn't a block display or the server has no such block state.",
        },
      ],
    },
    {
      name: 'display_text',
      doc: "What a text display says, as MiniMessage, or `nil` when the node isn't a text display.",
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_display_text',
      doc: 'Changes what a text display says.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't a text display." }],
    },
    {
      name: 'display_item',
      doc: "The item an item display shows, or `nil` when the node isn't an item display.",
      params: [],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_display_item',
      doc: "Changes the item an item display shows: any `Item`, so a name, `item_model` or `glint` shows as it would in a hand. Its `count` makes no difference. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't an item display." }],
      example:
        'this:node("sword"):set_display_item({ kind = "minecraft:diamond_sword", glint = true })',
    },
    {
      name: 'model',
      doc: "The resource pack look a model display shows (`<pack>/<key>`, as the calling script names it), or `nil` when the node isn't a model display.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_model',
      doc: "Changes the look a model display shows: another entry of a resource pack's `items`, a model with elements or not. The display entity stays and only its item changes, which costs one small update to each player who sees it, so swapping models every few ticks animates a node frame by frame (a flipbook), alongside the transform tracks. A look the server's packages don't have is an error, and so is a namespace no package on the server has.",
      params: [
        {
          name: 'model',
          type: 'string',
          doc: "`<pack>/<key>`: an entry of one of your package's resource packs' `items`, or another loaded package's as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)): a project can hand a package it uses a look of its own to show (`nf.looks.qualify`).",
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't a model display." }],
      example:
        'local frames = { "mobs/wing_1", "mobs/wing_2", "mobs/wing_3", "mobs/wing_2" }\nlocal wing = assert(this:node("wing"))\nlocal frame = 1\nnf.every(3, function()\n  frame = frame % #frames + 1\n  wing:set_model(frames[frame])\nend)',
    },
    {
      name: 'display_text_style',
      doc: "How a text display's text is drawn, every field filled in (`background` is `nil` for Minecraft's own grey), or `nil` when the node isn't a text display. Change a field and hand it to `set_display_text_style`.",
      params: [],
      returns: [{ type: 'TextStyle?' }],
    },
    {
      name: 'set_display_text_style',
      doc: "Changes how a text display's text is drawn. The whole style is replaced: a field left out goes back to its default, so to change one thing, read `display_text_style()`, change it and write it back. A bad value (an alignment that isn't one, a background that isn't `#AARRGGBB`) is an error.",
      params: [{ name: 'style', type: 'TextStyle', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't a text display." }],
      example:
        'local sign = assert(this:node("sign"))\nlocal style = sign:display_text_style()\nif style then\n  style.background = "#80000000"\n  style.shadow = true\n  sign:set_display_text_style(style)\nend',
    },
    {
      name: 'is_glowing',
      doc: 'Whether its display glows (`set_glowing`). `false` for a node that draws nothing.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_glowing',
      doc: "Outlines its display, seen through walls, the way the Glowing effect outlines a mob. Block and item displays glow in `set_glow_color`'s colour (white by default). Not saved.",
      params: [{ name: 'glowing', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
    },
    {
      name: 'glow_color',
      doc: 'The colour its block or item display glows in, as `#RRGGBB`, or `nil` for the default (white), for a text display, or for a node that draws nothing.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_glow_color',
      doc: "Sets the colour a block or item display glows in. Minecraft draws no glow colour on a text display, so there it does nothing and returns `false`. It doesn't turn the glow on: `set_glowing` does.",
      params: [
        {
          name: 'color',
          type: 'string',
          doc: '`#RRGGBB`. Leave it out for the default, white. Anything else is an error.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`false` for a text display, or a node that draws nothing.',
        },
      ],
      example: 'node:set_glow_color("#55ff55")\nnode:set_glowing(true)',
    },
    {
      name: 'brightness',
      doc: "The light its display is drawn with, when `set_brightness` fixed it; `nil` when it's lit by where it stands (the default), or for a node that draws nothing.",
      params: [],
      returns: [{ type: 'Brightness?' }],
    },
    {
      name: 'set_brightness',
      doc: 'Draws its display as if lit by fixed light levels, whatever the light where it stands: `{ block_light = 15, sky_light = 15 }` is full brightness in a dark cave. Leave it out (or `nil`) to go back to the light around it. A level outside 0 to 15, or a missing one, is an error.',
      params: [
        {
          name: 'brightness',
          type: 'Brightness',
          doc: 'Both levels, 0 to 15.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
      example: 'this:node("lamp"):set_brightness({ block_light = 15, sky_light = 15 })',
    },
    {
      name: 'billboard',
      doc: 'Which way its display turns to face whoever looks at it: `"fixed"` (it doesn\'t), `"vertical"` (about the vertical, like a sign on a post), `"horizontal"` or `"center"` (all the way). `nil` for a node that draws nothing.',
      params: [],
      returns: [{ type: '"fixed"|"vertical"|"horizontal"|"center"?' }],
    },
    {
      name: 'set_billboard',
      doc: 'Makes its display turn to face each viewer (or stop). A turning display ignores its rotation (its parents\' included) about the axes it turns on. A text display starts as its `billboard` in `centity.json` says (`"center"` by default), a block or item display `"fixed"`. Anything else is an error.',
      params: [
        {
          name: 'billboard',
          type: '"fixed"|"vertical"|"horizontal"|"center"',
          doc: '',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
    },
    {
      name: 'interpolation_ticks',
      doc: 'How many ticks its display takes to ease to a new pose: 1 unless `set_interpolation_ticks` changed it. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_interpolation_ticks',
      doc: "How many ticks its display takes to ease to each new pose (its translation, rotation and scale, and its parents'). The client does the easing, so a pose set once with `set_interpolation_ticks(20)` glides there over a second without the script doing anything more. When the whole centity moves (`teleport`, physics carrying it along), its display glides there over as many ticks, up to 59. 0 jumps. Not saved.",
      params: [{ name: 'ticks', type: 'integer', doc: 'At least 0.' }],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example:
        'local door = assert(this:node("door"))\ndoor:set_interpolation_ticks(10)\ndoor:set_rotation(vec3(0, 90, 0)) -- swings open over half a second',
    },
    {
      name: 'velocity',
      doc: "How fast a physics body is moving, in blocks per second along the world's axes: `vec3.zero` for a node without `physics`, `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_velocity',
      doc: "Replaces a physics body's velocity, and wakes it.",
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'add_velocity',
      doc: 'Adds to a physics body\'s velocity whatever it weighs ("go this much faster", which is what a jump means), and wakes it.',
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        'assert(this:node("crate")):on("click", function(event)\n  event.target:add_velocity(vec3(0, 8, 0))\nend)',
    },
    {
      name: 'angular_velocity',
      doc: 'How fast a physics body is turning about each world axis, in degrees per second: `vec3.zero` for a node without `physics`, `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_angular_velocity',
      doc: "Replaces a physics body's angular velocity, and wakes it.",
      params: [
        {
          name: 'angular_velocity',
          type: 'Vec3',
          doc: 'Degrees per second about each world axis.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'mass',
      doc: "A physics body's mass: what its `physics` says, or its collider's volume in blocks. 0 for a node without `physics`. `speed * node:mass()` is the impulse that sends it off at `speed`.",
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'apply_impulse',
      doc: "Pushes a physics body through its centre of mass: mass times a change in velocity, so the same push moves a light body further than a heavy one. Through the centre, so it doesn't turn it. Wakes it.",
      params: [{ name: 'impulse', type: 'Vec3', doc: "Along the world's axes." }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example: 'ball:apply_impulse(direction * 4 * ball:mass())',
    },
    {
      name: 'apply_impulse_at',
      doc: 'The same push, landing at a point instead of through the middle, so it turns the body as well: the further off centre, the more of it becomes angular velocity.',
      params: [
        { name: 'impulse', type: 'Vec3', doc: "Along the world's axes." },
        {
          name: 'point',
          type: 'Vec3',
          doc: 'Where it lands, as a world position: `node:to_world(...)` turns a point on the node into one.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        '-- Shove the top edge: it topples rather than slides.\nlocal top = crate:to_world(vec3(0.5, 1, 0.5))\nif top then\n  crate:apply_impulse_at(vec3(0, 0, 4), top)\nend',
    },
    {
      name: 'is_on_ground',
      doc: 'Whether a physics body is resting on something: `not node:is_on_ground()` reliably means falling.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_asleep',
      doc: 'Whether a physics body has settled and stopped being simulated. It wakes when something hits it, when what holds it up goes (checked about once a second), or when a script pushes it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'wake',
      doc: "Puts a settled physics body back to work, for when a script changed something it can't feel (a block placed beside it).",
      params: [],
      returns: [],
    },
    {
      name: 'apply_force',
      doc: 'Pushes a physics body through its centre of mass for the next tick: a force in mass times blocks per second squared, so calling it every tick is a steady push (and `vec3(0, 32, 0) * node:mass()` holds a body up against default gravity). The same as `apply_impulse(force / 20)`. Wakes it.',
      params: [{ name: 'force', type: 'Vec3', doc: "Along the world's axes." }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        'this:on("tick", function()\n  balloon:apply_force(vec3(0, 40, 0) * balloon:mass())\nend)',
    },
    {
      name: 'has_gravity',
      doc: "Whether gravity is on for a physics body: `true` unless `set_gravity(false)` turned it off. How strong it is is the body's `physics.gravity`, which may be 0. `false` for a node without `physics`.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_gravity',
      doc: "Turns gravity off for a physics body, so it floats where it's put and drifts at the speed it has, or back on. Wakes it. Not saved.",
      params: [{ name: 'gravity', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'is_kinematic',
      doc: 'Whether a physics body is kinematic (`set_kinematic`).',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_kinematic',
      doc: "Makes a physics body kinematic: moved only by scripts, never by gravity or what it hits. It keeps moving at the velocity and angular velocity a script gives it (`set_velocity`), and its centity's other bodies collide with it as if it were a wall: a moving platform, a piston. Back to a normal body with `false`. Not saved.",
      params: [{ name: 'kinematic', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'is_physics_enabled',
      doc: 'Whether a physics body is simulated (`set_physics_enabled`). `false` for a node without `physics`.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_physics_enabled',
      doc: "Freezes a physics body in place, or lets it go again. A frozen body doesn't move at all (scripts can still set its pose), and its centity's other bodies collide with it as with a wall. It keeps its velocity, so it carries on where it left off when it's let go. Not saved.",
      params: [{ name: 'enabled', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    ...eventFunctions(
      'Node',
      'node',
      'assert(this:node("lever")):on("click", function(event)\n  event.player:send_message("<gray>Click.")\nend)',
    ),
  ],
}

/** The tables centities and nodes take and give. */
export const centityShapes: LuaClass[] = [
  {
    name: 'CentityPathOptions',
    doc: "How a centity walks where `centity:move_to` sends it. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'speed',
        type: 'number?',
        doc: `How fast, in blocks a second: more than 0, at most ${MAX_SPEED.value}. Default ${DEFAULT_SPEED.value}, a player's walk.`,
      },
      {
        name: 'fly',
        type: 'boolean?',
        doc: 'Flies there, through the air in any direction, rather than walking on the ground: no steps, drops or falling. Finding a way costs the server more. Default false.',
      },
      {
        name: 'face',
        type: 'boolean?',
        doc: 'Turns it to face the way it goes, about its feet, a little each tick. Default true.',
      },
      {
        name: 'width',
        type: 'number?',
        doc: `How wide a gap it needs, in blocks: more than 0, at most ${MAX_WIDTH.value}. Default: its hitboxes' wider side, as they're placed when it sets off (1 without hitboxes). It's taken as square, whichever way it faces.`,
      },
      {
        name: 'height',
        type: 'number?',
        doc: `How tall a gap it needs, in blocks: more than 0, at most ${MAX_HEIGHT.value}. Default: its hitboxes' height (1 without hitboxes).`,
      },
      {
        name: 'step_height',
        type: 'number?',
        doc: `Walking: how high a step it goes straight up, in blocks: at least 0. Default ${DEFAULT_STEP_HEIGHT.value}, a block, as a mob jumps one.`,
      },
      {
        name: 'max_drop',
        type: 'number?',
        doc: `Walking: how far down it will drop off an edge, in blocks: at least 0. Default ${DEFAULT_MAX_DROP.value}.`,
      },
      {
        name: 'open_doors',
        type: 'boolean?',
        doc: "Walking: goes through the game's wooden doors and fence gates (`#minecraft:wooden_doors`, `#minecraft:fence_gates`), opening each as it comes to it and shutting it again once it's through (one it found open stays open). Iron doors stay in its way. Default `false`.",
      },
      {
        name: 'range',
        type: 'number?',
        doc: `How far from where it sets off it looks for a way, in blocks: more than 0, at most ${MAX_RANGE.value}. A target further away than this is \`false\`. Default ${DEFAULT_RANGE.value}. One search looks at no more than ${NODE_LIMIT.value} places, so a long way round may only take it part of the way.`,
      },
    ],
  },
  {
    name: 'CentityPathEndEvent',
    doc: 'A centity stopped walking where `move_to` sent it.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The instance.' },
      {
        name: 'reached',
        type: 'boolean',
        doc: `Whether it got there: to the end of its path, within ${PATH_REACH.value} blocks of where it was sent (or of what it went after, which it stops short of); \`false\` when it gave up.`,
      },
    ],
  },
  {
    name: 'CentitySpawnOptions',
    doc: "How `nf.centities.spawn` spawns one. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'temporary',
        type: 'boolean?',
        doc: "Not saved: its entities aren't kept with their chunk, the server forgets it on a restart, and it's removed when the script that spawned it unloads (a reload of it, or its centity removed), so nothing it made is left behind. What a shot, a damage number or a summoned helper is. `keep()` makes it a normal one. Default `false`.",
      },
      {
        name: 'lifetime',
        type: 'integer?',
        doc: "Removed this many ticks after it's spawned (its `remove` handlers hear it), loaded or not: at least 1. A centity with a lifetime is temporary too. Default: none.",
      },
    ],
  },
  {
    name: 'BodySettings',
    doc: "A centity's body, as `centity:body()` gives it and `centity:set_body` takes it: the fields of `centity.json`'s `body`, in Lua spelling. Every field is optional to `set_body`. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'width', type: 'number?', doc: 'Blocks across, square: more than 0, at most 16.' },
      { name: 'height', type: 'number?', doc: 'Blocks tall: more than 0, at most 32.' },
      {
        name: 'gravity',
        type: 'number?',
        doc: 'Downward acceleration, blocks per second squared.',
      },
      { name: 'max_fall_speed', type: 'number?', doc: 'The fastest it falls, blocks per second.' },
      {
        name: 'drag',
        type: 'number?',
        doc: 'Fraction of its speed shed per second in the air, 0 to 1.',
      },
      { name: 'liquid_drag', type: 'number?', doc: 'The same in water or lava.' },
      {
        name: 'liquid_gravity',
        type: 'number?',
        doc: 'How much of its gravity pulls it in water or lava: 0 floats, 1 sinks as in air.',
      },
      {
        name: 'acceleration',
        type: 'number?',
        doc: 'How fast it reaches its walk on the ground (or flying), blocks per second squared.',
      },
      { name: 'air_acceleration', type: 'number?', doc: 'The same in the air.' },
      {
        name: 'friction',
        type: 'number?',
        doc: "How fast it slows to a stop on the ground when it isn't walking, blocks per second squared.",
      },
      {
        name: 'step_height',
        type: 'number?',
        doc: 'The tallest step it walks straight up, blocks.',
      },
      {
        name: 'bounce',
        type: 'number?',
        doc: '0 stops dead against what it hits, 1 bounces off at full speed.',
      },
      { name: 'fly', type: 'boolean?', doc: 'No gravity: walking steers it in any direction.' },
      { name: 'blocks', type: 'boolean?', doc: "Collides with the world's blocks." },
    ],
  },
  {
    name: 'LaunchOptions',
    doc: "How `centity:launch` flies it. Everything but `velocity` is optional. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'velocity',
        type: 'Vec3',
        doc: "Blocks per second, along the world's axes. Required.",
      },
      {
        name: 'gravity',
        type: 'number?',
        doc: 'Downward acceleration, blocks per second squared. Default 0: it flies straight.',
      },
      {
        name: 'gravity_delay',
        type: 'integer?',
        doc: "Ticks it flies straight before gravity takes it, as a thrown knife's first stretch. Default 0.",
      },
      {
        name: 'drag',
        type: 'number?',
        doc: 'Fraction of its speed shed per second, 0 to 1. Default 0.',
      },
      {
        name: 'radius',
        type: 'number?',
        doc: "How wide its path is swept for hits, as a sphere's radius in blocks; blocks stop its middle. Default 0.1.",
      },
      {
        name: 'pierce',
        type: 'integer?',
        doc: 'How many hits it goes on through before one ends it. Default 0: the first ends it.',
      },
      {
        name: 'rehit',
        type: 'integer?',
        doc: 'Ticks before it can hit the same thing again. Default: it hits each thing once.',
      },
      {
        name: 'hits',
        type: 'QueryOptions?',
        doc: "What it can hit, as a spatial query's options say (`centities`, `players`, `entities`, `kinds`, `exclude`, `line_of_sight` aside). It never hits itself or its `owner`. Default: everything.",
      },
      {
        name: 'owner',
        type: '(Entity|Centity)?',
        doc: 'Who launched it: never hit, and handed to every event. Default none.',
      },
      {
        name: 'blocks',
        type: 'boolean?',
        doc: 'Whether blocks stop it (their collision shapes). Default `true`.',
      },
      {
        name: 'bounce',
        type: 'number?',
        doc: "Comes off a block instead of ending: the fraction of its speed into the face it keeps, 0 to 1. Default: it doesn't bounce.",
      },
      {
        name: 'friction',
        type: 'number?',
        doc: 'With `bounce`: the fraction of its speed along the face it keeps, 0 to 1. Default 1.',
      },
      {
        name: 'max_bounces',
        type: 'integer?',
        doc: 'With `bounce`: how many it makes before the next block ends it. Default: no end.',
      },
      {
        name: 'rest_speed',
        type: 'number?',
        doc: 'With `bounce`: below this speed, blocks per second, after a bounce off a floor it stops and lies there (a grenade) until its `lifetime` is up. Default 1.',
      },
      {
        name: 'stick',
        type: 'boolean?',
        doc: 'Sticks in the first block it hits (`stick`) instead of ending: it stays there until its `lifetime` is up. Default `false`.',
      },
      {
        name: 'homing',
        type: 'Homing?',
        doc: "Turns toward the nearest thing it can hit within reach. Default: it doesn't.",
      },
      {
        name: 'lifetime',
        type: 'integer?',
        doc: 'Ticks from launch before it ends (`flight_end`, `"lifetime"`), flying, stuck or lying still. At least 1. Default 600: half a minute.',
      },
      {
        name: 'aim',
        type: 'string?',
        doc: "A node that's turned every tick so its +Z points the way it flies (an arrow's nose). Default: none is turned.",
      },
      {
        name: 'remove_on_end',
        type: 'boolean?',
        doc: "Whether it's removed when its flight ends, after `flight_end`. Default `true`.",
      },
    ],
  },
  {
    name: 'Homing',
    doc: 'How a projectile seeks: `LaunchOptions.homing`.',
    methods: false,
    functions: [],
    fields: [
      { name: 'range', type: 'number', doc: 'How far it looks, in blocks. Required.' },
      {
        name: 'turn',
        type: 'number?',
        doc: 'The most it turns toward its target, in degrees per second. Default 180.',
      },
    ],
  },
  {
    name: 'FlightHitEvent',
    doc: 'A projectile touched something it can hit: `hit`. Exactly one of `target` and `entity` is set.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The projectile.' },
      { name: 'target', type: 'Centity?', doc: 'The centity it hit, or `nil`.' },
      { name: 'node', type: 'Node?', doc: "Which of `target`'s nodes (its hitbox), or `nil`." },
      { name: 'entity', type: 'Entity?', doc: 'The player or entity it hit, or `nil`.' },
      { name: 'position', type: 'Vec3', doc: 'Where its middle was when it first touched.' },
      { name: 'velocity', type: 'Vec3', doc: 'How fast it was going, blocks per second.' },
      { name: 'owner', type: '(Entity|Centity)?', doc: 'Who launched it, or `nil`.' },
    ],
  },
  {
    name: 'FlightBlockEvent',
    doc: 'A projectile met a block it bounces off (`bounce`) or sticks in (`stick`).',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The projectile.' },
      { name: 'block', type: 'Block', doc: 'The block.' },
      { name: 'position', type: 'Vec3', doc: 'Where it met it.' },
      { name: 'normal', type: 'Vec3', doc: 'The direction straight out of the face it met.' },
      { name: 'speed', type: 'number', doc: 'How fast it was going into it, blocks per second.' },
      { name: 'owner', type: '(Entity|Centity)?', doc: 'Who launched it, or `nil`.' },
    ],
  },
  {
    name: 'FlightEndEvent',
    doc: "A projectile's flight ended: `flight_end`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The projectile.' },
      {
        name: 'reason',
        type: '"hit"|"block"|"lifetime"|"unloaded"|"stopped"',
        doc: 'Why: see `flight_end`.',
      },
      { name: 'position', type: 'Vec3', doc: 'Where it ended.' },
      { name: 'target', type: 'Centity?', doc: 'For `"hit"`, the centity it ended on, or `nil`.' },
      {
        name: 'entity',
        type: 'Entity?',
        doc: 'For `"hit"`, the player or entity it ended on, or `nil`.',
      },
      { name: 'block', type: 'Block?', doc: 'For `"block"`, the block it ended on, or `nil`.' },
      {
        name: 'normal',
        type: 'Vec3?',
        doc: 'For `"block"`, the direction straight out of the face it met, or `nil`.',
      },
      { name: 'owner', type: '(Entity|Centity)?', doc: 'Who launched it, or `nil`.' },
    ],
  },
  {
    name: 'CentityDamageOptions',
    doc: "Where damage dealt with `centity:damage` comes from, for its handlers. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'source', type: '(Entity|Centity)?', doc: 'Who or what dealt it.' },
      {
        name: 'cause',
        type: 'string?',
        doc: 'What kind of damage, in your own words: `"melee"`, `"fire"`.',
      },
    ],
  },
  {
    name: 'CentityDamageEvent',
    doc: 'A centity is about to lose health: `damage`.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The centity.' },
      { name: 'amount', type: 'number', doc: 'How much it loses: assign to change it.' },
      { name: 'source', type: '(Entity|Centity)?', doc: 'Who or what dealt it, or `nil`.' },
      { name: 'cause', type: 'string?', doc: "The damage's `cause`, or `nil`." },
    ],
  },
  {
    name: 'CentityDeathEvent',
    doc: "A centity's health reached 0: `death`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The centity.' },
      { name: 'source', type: '(Entity|Centity)?', doc: 'What dealt the last blow, or `nil`.' },
      { name: 'cause', type: 'string?', doc: "The last blow's `cause`, or `nil`." },
    ],
  },
  {
    name: 'CentityLandEvent',
    doc: "A centity's body landed: `land`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The centity.' },
      {
        name: 'speed',
        type: 'number',
        doc: 'How fast it was falling as it landed, blocks per second.',
      },
    ],
  },
  {
    name: 'CentityBumpEvent',
    doc: "A centity's body ran into something: `bump`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The centity.' },
      {
        name: 'normal',
        type: 'Vec3',
        doc: 'The direction straight out of the face it hit: `vec3(0, -1, 0)` for a ceiling.',
      },
      { name: 'speed', type: 'number', doc: 'How fast it was going into it, blocks per second.' },
    ],
  },
  {
    name: 'Bounds',
    doc: "A box along the world's axes: `centity:bounds()`.",
    methods: false,
    functions: [],
    fields: [
      { name: 'min', type: 'Vec3', doc: 'Its lowest corner.' },
      { name: 'max', type: 'Vec3', doc: 'Its highest corner.' },
    ],
  },
  {
    name: 'TouchEvent',
    doc: "A centity's hitboxes started touching something's: `touch`.",
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The centity that hears it.' },
      { name: 'node', type: 'Node', doc: 'Which of its nodes touched (its hitbox).' },
      { name: 'other', type: 'Centity?', doc: 'The other centity, or `nil`.' },
      {
        name: 'other_node',
        type: 'Node?',
        doc: "Which of the other centity's nodes it touched, or `nil`.",
      },
      { name: 'entity', type: 'Entity?', doc: 'The player or entity it touched, or `nil`.' },
    ],
  },
  {
    name: 'StretchOptions',
    doc: "How `node:stretch_between` lays a node. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'thickness', type: 'number?', doc: 'Its X and Y scale. Default: as they are.' },
    ],
  },
  {
    name: 'RopeOptions',
    doc: "How `node:rope_between` strings a rope. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'length',
        type: 'number?',
        doc: "How long the rope is, in blocks: shorter than the ends' distance it's taut, longer it sags by its slack. Default: always taut.",
      },
    ],
  },
  {
    name: 'TweenOptions',
    doc: "A tween for `node:tween`: at least one of `translation`, `rotation` and `scale`. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'translation', type: 'Vec3?', doc: 'Where it ends up, as `set_translation` takes.' },
      {
        name: 'rotation',
        type: 'Vec3?',
        doc: 'Degrees, as `set_rotation` takes; not the short way round, so 0 to 360 is a whole turn.',
      },
      { name: 'scale', type: 'Vec3?', doc: 'As `set_scale` takes.' },
      { name: 'ticks', type: 'integer', doc: 'How long, 1 or more (each way, with `back`).' },
      {
        name: 'easing',
        type: 'string?',
        doc: '`linear`, `ease_in`, `ease_out`, `ease_in_out` or `step`, as keyframes take. Default `ease_in_out`.',
      },
      {
        name: 'back',
        type: 'boolean?',
        doc: 'Eases back to where it started after, over as many ticks again. Default `false`.',
      },
    ],
  },
  {
    name: 'AnimationOptions',
    doc: 'How `centity:play_animation` plays an animation this time. The file says how it plays by default.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'speed',
        type: 'number?',
        doc: '1 is as authored (the default), 2 twice as fast, 0.5 half. Not negative.',
      },
      {
        name: 'from_tick',
        type: 'number?',
        doc: 'Where in the clip to start, in ticks from its beginning. Default 0.',
      },
      {
        name: 'loop',
        type: 'boolean?',
        doc: "`true` loops it whatever its file says; `false` plays it through once (a `hold` clip still holds its last pose). Default: the file's `loop`.",
      },
      {
        name: 'blend_ticks',
        type: 'integer?',
        doc: 'Eases from the pose the nodes have now into the animation over this many ticks, instead of jumping to its first frame. Default 0.',
      },
    ],
  },
  {
    name: 'TextStyle',
    doc: 'How a text display draws its text: what `node:display_text_style()` gives and `node:set_display_text_style` takes. Every field is optional; one left out is its default.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'background',
        type: 'string?',
        doc: '`#AARRGGBB`, alpha first: `"#00000000"` is none at all. Default: Minecraft\'s translucent grey.',
      },
      { name: 'opacity', type: 'integer?', doc: "The text's own opacity, 0 to 255. Default 255." },
      { name: 'shadow', type: 'boolean?', doc: 'A drop shadow under the text. Default false.' },
      {
        name: 'alignment',
        type: '"center"|"left"|"right"?',
        doc: 'How lines of different lengths line up. Default `"center"`.',
      },
      {
        name: 'line_width',
        type: 'integer?',
        doc: 'How wide a line gets, in pixels, before it wraps. Default 200.',
      },
      {
        name: 'see_through',
        type: 'boolean?',
        doc: 'Drawn through blocks in front of it. Default false.',
      },
    ],
  },
  {
    name: 'Brightness',
    doc: 'Fixed light levels a display is drawn with: `node:set_brightness`.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'block_light',
        type: 'integer?',
        doc: 'Light from blocks (torches, lamps), 0 to 15. Required.',
      },
      { name: 'sky_light', type: 'integer?', doc: 'Light from the sky, 0 to 15. Required.' },
    ],
  },
]
