import type { Fn, LuaClass, Param } from '../types.ts'

const offline = "`false` when they're offline."

/** What an equipment slot is called, the same for every living entity. */
export const EQUIPMENT_SLOT = '"main_hand"|"off_hand"|"head"|"chest"|"legs"|"feet"|"body"'

const advancementParam: Param = {
  name: 'advancement',
  type: 'string',
  doc: 'One of the project\'s advancements (`"first_steps"`, `advancements/first_steps.json`; a bare id is the calling package\'s own), one a package exports (`"acme:quests"`), or one of the game\'s by its key (`"minecraft:story/mine_diamond"`). One the server doesn\'t have is an error; so is a project advancement added since the server started, until it restarts.',
  names: 'advancement',
}

const criterionParam: Param = {
  name: 'criterion',
  type: 'string',
  doc: "Only this criterion, by the name the advancement's file gives it. One it doesn't have is an error. Leave it out for every criterion.",
  optional: true,
}

/** Things only this player sees: blocks, equipment, a camera, a compass, a book. */
export const playerIllusionFunctions: Fn[] = [
  {
    name: 'send_block_change',
    doc: "Shows them a block that isn't there: only their game draws it, and the world doesn't change. It lasts until the real block updates (something changes it, or their game reloads the chunk), or `reset_block`. They can't walk through or stand on it any differently from the real block. A block state the server doesn't have is an error.",
    params: [
      {
        name: 'location_or_position',
        type: 'Location|Vec3',
        doc: 'Any point in the block; a bare vector is in their world.',
      },
      {
        name: 'state',
        type: 'string',
        doc: 'A block state, as `world:set_block` takes it: `"minecraft:gold_block"`.',
      },
    ],
    returns: [{ type: 'boolean', doc: offline }],
    example: 'player:send_block_change(vec3(10, 64, 10), "minecraft:gold_block")',
  },
  {
    name: 'reset_block',
    doc: 'Shows them the real block again, after `send_block_change`.',
    params: [
      {
        name: 'location_or_position',
        type: 'Location|Vec3',
        doc: 'Any point in the block; a bare vector is in their world.',
      },
    ],
    returns: [{ type: 'boolean', doc: offline }],
  },
  {
    name: 'send_equipment_change',
    doc: "Shows them another entity (or player) wearing or holding something it isn't: only their game draws it. It lasts until that entity's equipment really changes, or it goes out of their sight and comes back. An item table with a misspelled field, or an item the server doesn't have, is an error.",
    params: [
      { name: 'entity', type: 'Entity', doc: 'Whose equipment.' },
      { name: 'slot', type: EQUIPMENT_SLOT, doc: 'Which slot.' },
      {
        name: 'item',
        type: 'Item',
        doc: 'What to show there. Leave it out (or `nil`) to show it empty.',
        optional: true,
      },
    ],
    returns: [{ type: 'boolean', doc: "`false` when they're offline or the entity has gone." }],
    example: 'player:send_equipment_change(other, "head", { kind = "minecraft:carved_pumpkin" })',
  },
  {
    name: 'camera',
    doc: "The entity they're watching through, as a spectator (`set_camera`), or `nil` when they're looking through their own eyes (or offline).",
    params: [],
    returns: [{ type: 'Entity?' }],
  },
  {
    name: 'set_camera',
    doc: 'Makes them watch through another entity\'s eyes, as a spectator does when they click one: they see what it sees until they sneak, or this is called without one. They have to be in spectator mode (`set_game_mode("spectator")`).',
    params: [
      {
        name: 'entity',
        type: 'Entity',
        doc: 'Whose eyes. Leave it out (or `nil`) to give them their own back.',
        optional: true,
      },
    ],
    returns: [
      {
        type: 'boolean',
        doc: "`false` when they're offline, not in spectator mode, or the entity has gone.",
      },
    ],
    example:
      'player:set_game_mode("spectator")\nplayer:set_camera(boss)\nnf.after(100, function()\n  player:set_camera()\nend)',
  },
  {
    name: 'compass_target',
    doc: "Where their compasses point (those that aren't tied to a lodestone): the world's spawn unless `set_compass_target` changed it. `nil` when they're offline.",
    params: [],
    returns: [{ type: 'Location?' }],
  },
  {
    name: 'set_compass_target',
    doc: 'Points their compasses somewhere: a quest, a base, a meeting point. It lasts until they leave.',
    params: [{ name: 'location', type: 'Location', doc: 'Where.' }],
    returns: [{ type: 'boolean', doc: offline }],
  },
  {
    name: 'open_book',
    doc: "Opens a book on their screen, as if they'd opened a written book, without giving them one.",
    params: [
      {
        name: 'pages',
        type: 'Text[]',
        doc: 'Each page, MiniMessage, in order. At most 100.',
      },
    ],
    returns: [{ type: 'boolean', doc: offline }],
    example:
      'player:open_book({\n  "<bold>Welcome!</bold>\\n\\nRead on for the rules.",\n  "1. Be kind.\\n2. No griefing.",\n})',
  },
  {
    name: 'view_distance',
    doc: "How many chunks around them the server is sending: the server's own view distance, or what `set_view_distance` asked for, either of them lowered by their own game's setting. A change shows here from the next tick. `nil` when they're offline.",
    params: [],
    returns: [{ type: 'integer?' }],
  },
  {
    name: 'set_view_distance',
    doc: "Changes how many chunks around them the server sends, until they leave, from the next tick. Their own game's setting still limits it: `view_distance()` says what they get.",
    params: [{ name: 'distance', type: 'integer', doc: 'In chunks, 2 to 32.' }],
    returns: [{ type: 'boolean', doc: offline }],
  },
]

/** When they played, bans and the whitelist. */
export const playerServerFunctions: Fn[] = [
  {
    name: 'first_played',
    doc: 'When they first joined this server, as Unix time in milliseconds (like `nf.server.unix_time()`), or `nil` for someone who never has (a handle from `nf.players.whitelisted()`, say).',
    params: [],
    returns: [{ type: 'integer?' }],
  },
  {
    name: 'last_seen',
    doc: "When they were last on this server, as Unix time in milliseconds (like `nf.server.unix_time()`): now while they're online, `nil` for someone who never has been.",
    params: [],
    returns: [{ type: 'integer?' }],
    example:
      'local seen = player:last_seen()\nif seen then\n  local days = (nf.server.unix_time() - seen) // 86400000\n  log(player:name(), "was here", days, "days ago")\nend',
  },
  {
    name: 'is_banned',
    doc: "Whether they're banned from the server (by `ban`, `/ban` or anything else), and the ban hasn't run out.",
    params: [],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'ban',
    doc: `Bans them from the server: they can't join until it runs out or \`unban\`. Kicks them, with the reason, when they're online. Banning someone already banned replaces their ban.`,
    requires: 'moderation',
    params: [{ name: 'options', type: 'BanOptions', doc: '', optional: true }],
    returns: [],
    example: 'player:ban({ reason = "Griefing", expires = nf.server.unix_time() + 7 * 86400000 })',
  },
  {
    name: 'unban',
    doc: `Lifts their ban.`,
    requires: 'moderation',
    params: [],
    returns: [{ type: 'boolean', doc: "`false` when they weren't banned." }],
  },
  {
    name: 'is_whitelisted',
    doc: "Whether they're on the server's whitelist (which only keeps people out while it's on: `nf.server.is_whitelist_enabled()`).",
    params: [],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'set_whitelisted',
    doc: `Puts them on the server's whitelist, or takes them off. Taking someone online off doesn't kick them.`,
    requires: 'moderation',
    params: [{ name: 'whitelisted', type: 'boolean', doc: '' }],
    returns: [],
  },
]

/** Permissions the project grants, beside `has_permission`. */
export const playerPermissionFunctions: Fn[] = [
  {
    name: 'set_permission',
    doc: "Grants them a permission node (`true`), or denies it (`false`), alongside whatever a permissions plugin gives them. It's kept with the project's other grants and given back every time they join, until `unset_permission`; it works while they're offline too. A package (every project is one) may only grant nodes its own `netherforge.json` lists under `allow.permissions`, held to the package whose code makes the call as `requires` is (each entry allows itself and everything under it: `\"shop\"` allows `shop` and `shop.vip`): anything else is an error, and grants a later edit no longer allows stop applying.",
    params: [
      {
        name: 'permission',
        type: 'string',
        doc: 'The node: lowercase letters, digits, `_`, `-` and `.`, like `"shop.vip"`.',
      },
      { name: 'value', type: 'boolean', doc: '`true` grants it; `false` denies it.' },
    ],
    returns: [],
    example: 'player:set_permission("shop.vip", true)',
  },
  {
    name: 'unset_permission',
    doc: 'Takes back a node `set_permission` granted or denied: they have it again only if something else gives it to them.',
    params: [{ name: 'permission', type: 'string', doc: 'The node.' }],
    returns: [{ type: 'boolean', doc: "`false` when the project hadn't set it." }],
  },
  {
    name: 'permissions',
    doc: 'The nodes the project has set for them with `set_permission`, each to `true` (granted) or `false` (denied). Not every permission they have: `has_permission` answers that.',
    params: [],
    returns: [{ type: 'table<string, boolean>' }],
  },
]

/** Granting, revoking and reading advancements. */
export const playerAdvancementFunctions: Fn[] = [
  {
    name: 'grant_advancement',
    doc: "Meets an advancement's criteria for them, as if they'd earned them: every criterion, or just `criterion`. Once the advancement is complete they get the toast, the chat line and its rewards, as when the game completes it.",
    params: [advancementParam, criterionParam],
    returns: [{ type: 'boolean', doc: "`false` when they're offline or had already met it." }],
    example:
      'player:grant_advancement("minecraft:story/mine_diamond")\n-- One step of a quest of the project\'s own (advancements/treasure_hunter.json):\nplayer:grant_advancement("treasure_hunter", "first")',
  },
  {
    name: 'revoke_advancement',
    doc: 'Takes an advancement back: every criterion of it, or just `criterion`.',
    params: [advancementParam, criterionParam],
    returns: [{ type: 'boolean', doc: "`false` when they're offline or hadn't met any of it." }],
  },
  {
    name: 'has_advancement',
    doc: "Whether they've completed an advancement. `false` when they're offline.",
    params: [advancementParam],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'advancement_progress',
    doc: "How far they are with an advancement: the criteria they've met and those left. `nil` when they're offline.",
    params: [advancementParam],
    returns: [{ type: 'AdvancementProgress?' }],
    example:
      'local progress = player:advancement_progress("minecraft:adventure/adventuring_time")\nif progress then\n  player:send_message(#progress.done .. " biomes, " .. #progress.remaining .. " to go")\nend',
  },
]

export const playerShapes: LuaClass[] = [
  {
    name: 'BanOptions',
    doc: "How `player:ban` bans. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'reason',
        type: 'string?',
        doc: "What they're told when they're kicked and when they try to join, as plain text. Default the server's own message.",
      },
      {
        name: 'expires',
        type: 'integer?',
        doc: 'When it runs out, as Unix time in milliseconds (like `nf.server.unix_time()`): a time already past is an error. Default never.',
      },
      {
        name: 'source',
        type: 'string?',
        doc: 'Who banned them, as the ban list shows it. Default `"NetherForge"`.',
      },
    ],
  },
  {
    name: 'AdvancementProgress',
    doc: "How far a player is with an advancement, by its criteria's names as the advancement's own file calls them.",
    methods: false,
    functions: [],
    fields: [
      { name: 'done', type: 'string[]', doc: "The criteria they've met." },
      { name: 'remaining', type: 'string[]', doc: 'The criteria left.' },
    ],
  },
]
