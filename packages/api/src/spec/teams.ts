import type { Field, Fn, LuaClass } from '../types.ts'

/** The colours a team can give its members' names (and their glow): Minecraft's sixteen chat colours. */
export const TEAM_COLOR =
  '"black"|"dark_blue"|"dark_green"|"dark_aqua"|"dark_red"|"dark_purple"|"gold"|"gray"|"dark_gray"|"blue"|"green"|"aqua"|"red"|"light_purple"|"yellow"|"white"'

/** Who sees a team's members' name tags. */
export const TEAM_NAMETAGS = '"always"|"never"|"hide_for_other_teams"|"hide_for_own_team"'

/** Who a team's members push, and are pushed by. */
export const TEAM_COLLISION = '"always"|"never"|"push_other_teams"|"push_own_team"'

const GONE = "`false` once it's gone"

const NAMETAGS_DOC =
  '`"always"` (the default), `"never"`, `"hide_for_other_teams"` (only teammates see them) or `"hide_for_own_team"` (only players outside the team see them).'

const COLLISION_DOC =
  '`"always"` (the default), `"never"`, `"push_other_teams"` (members pass through each other) or `"push_own_team"` (members push only each other).'

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
  returns: [{ type: 'boolean', doc: `${GONE}.` }],
})

const member = { name: 'member', type: 'Entity', doc: 'A player or any other entity.' }

/** A scoreboard team the project made. */
export const teamClass: LuaClass = {
  name: 'Team',
  doc: "A scoreboard team, made by `nf.teams.create`: what goes before and after its members' names (over their heads and in the player list), the colour of those names and of their glow, whether members can hurt each other, see each other while invisible, see name tags and push each other. Members are players and other entities. Teams live on the server's scoreboard, so every player sees them (while a player's sidebar shows, its scoreboard copies them). A team belongs to the script that made it and goes when that script unloads (a reload, the server stopping), members and all, so the script's body makes it again; from then on its methods answer `nil` or `false`. On the server's scoreboard it's called `nf.<name>` (what `/team` commands name it), so it never collides with a team made with `/team` or by another plugin, and NetherForge never touches those.",
  methods: true,
  handle: { key: [{ name: 'name', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'name',
      doc: 'Its name, as `nf.teams.create` was given it (without the `nf.` the scoreboard adds).',
      params: [],
      returns: [{ type: 'string' }],
    },
    getter(
      'exists',
      "Whether it's still there: `false` once it's removed or its script has unloaded.",
      'boolean',
    ),
    {
      name: 'add_member',
      doc: "Puts a player or entity in it, taking them out of any other team they're in (one team at a time is Minecraft's rule). A player stays in it when they leave and come back, and can be added while offline.",
      params: [member],
      returns: [{ type: 'boolean', doc: `${GONE}, or once the entity has.` }],
      example: 'red:add_member(player)',
    },
    {
      name: 'remove_member',
      doc: 'Takes a player or entity out of it.',
      params: [member],
      returns: [{ type: 'boolean', doc: `Whether they were in it: ${GONE}.` }],
    },
    {
      name: 'has_member',
      doc: "Whether a player or entity is in it. `false` once it's gone.",
      params: [member],
      returns: [{ type: 'boolean' }],
    },
    getter(
      'members',
      'Its members that are here: players online (as `Player`) and entities in loaded chunks. Empty once it has gone.',
      'Entity[]',
    ),
    getter(
      'display_name',
      "What it's called where Minecraft names a team (the `/team list` output), as MiniMessage; `nil` once it's gone.",
      'Text?',
    ),
    setter('set_display_name', "Changes what it's called.", 'text', 'Text', 'MiniMessage.'),
    getter(
      'prefix',
      "What goes before each member's name, over their head and in the player list, as MiniMessage; `nil` once it's gone.",
      'Text?',
    ),
    setter(
      'set_prefix',
      "Changes what goes before each member's name.",
      'text',
      'Text',
      'MiniMessage; `""` for none.',
    ),
    getter(
      'suffix',
      "What goes after each member's name, as MiniMessage; `nil` once it's gone.",
      'Text?',
    ),
    setter(
      'set_suffix',
      "Changes what goes after each member's name.",
      'text',
      'Text',
      'MiniMessage; `""` for none.',
    ),
    getter(
      'color',
      "The colour of its members' names, and of their outline when they glow; `nil` when it has none (or once it's gone).",
      `${TEAM_COLOR}|nil`,
    ),
    {
      name: 'set_color',
      doc: "Colours its members' names, and their outline when they glow (a glowing entity's outline is white otherwise). A prefix's own colours still win over it for the prefix.",
      params: [
        {
          name: 'color',
          type: TEAM_COLOR,
          doc: 'Leave it out for none.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: `${GONE}.` }],
      example: 'red:set_color("red")\nzombie:set_glowing(true)',
    },
    getter(
      'has_friendly_fire',
      "Whether members can hurt each other. `false` once it's gone.",
      'boolean',
    ),
    setter(
      'set_friendly_fire',
      'Lets members hurt each other, or stops them.',
      'enabled',
      'boolean',
    ),
    getter(
      'can_see_invisible_teammates',
      "Whether members see each other, faintly, while invisible. `false` once it's gone.",
      'boolean',
    ),
    setter(
      'set_can_see_invisible_teammates',
      'Lets members see each other while invisible, or stops them.',
      'enabled',
      'boolean',
    ),
    getter(
      'nametags',
      "Who sees its members' name tags; `nil` once it's gone.",
      `${TEAM_NAMETAGS}|nil`,
    ),
    setter(
      'set_nametags',
      "Changes who sees its members' name tags.",
      'visibility',
      TEAM_NAMETAGS,
      NAMETAGS_DOC,
    ),
    getter(
      'collision',
      "Who its members push, and are pushed by; `nil` once it's gone.",
      `${TEAM_COLLISION}|nil`,
    ),
    setter(
      'set_collision',
      'Changes who its members push, and are pushed by.',
      'rule',
      TEAM_COLLISION,
      COLLISION_DOC,
    ),
    {
      name: 'remove',
      doc: 'Takes it off the scoreboard for good; its members are in no team any more.',
      params: [],
      returns: [{ type: 'boolean', doc: `${GONE} already.` }],
    },
  ],
}

/** `nf.teams`. */
export const nfTeams: LuaClass = {
  name: 'nf.teams',
  doc: "The project's scoreboard teams: prefixes and suffixes on names, name colours and glow colours, friendly fire, name tags and collision.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'create',
      doc: "Makes a team on the server's scoreboard, with no members yet (`team:add_member`). It belongs to this script and goes when the script unloads, so make it in the script's body. A name that isn't letters, digits and `_`, `-`, `.` or `+`, a name one of the project's teams already has, or a key the options don't have is an error; to share a team between scripts, find it with `nf.teams.get` in the others.",
      params: [
        { name: 'name', type: 'string', doc: 'What the project calls it: `"red"`.' },
        { name: 'options', type: 'TeamOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'Team' }],
      example:
        'local red = nf.teams.create("red", {\n  prefix = "<red>[Red] ",\n  color = "red",\n  friendly_fire = false,\n  nametags = "hide_for_other_teams",\n})\nnf.on("player_join", function(event)\n  red:add_member(event.player)\nend)',
    },
    {
      name: 'get',
      doc: "One of the project's teams by name, or `nil` when it has none by that name.",
      params: [{ name: 'name', type: 'string', doc: '' }],
      returns: [{ type: 'Team?' }],
    },
    {
      name: 'all',
      doc: "Every team the project has now, in the order they were made. Teams made with `/team` or by other plugins aren't among them.",
      params: [],
      returns: [{ type: 'Team[]' }],
    },
  ],
}

const fields: Field[] = [
  {
    name: 'display_name',
    type: 'Text?',
    doc: "What it's called where Minecraft names a team, MiniMessage. Default its name.",
  },
  {
    name: 'prefix',
    type: 'Text?',
    doc: "What goes before each member's name, MiniMessage. Default none.",
  },
  {
    name: 'suffix',
    type: 'Text?',
    doc: "What goes after each member's name, MiniMessage. Default none.",
  },
  {
    name: 'color',
    type: `${TEAM_COLOR}?`,
    doc: "The colour of members' names and glow. Default none.",
  },
  {
    name: 'friendly_fire',
    type: 'boolean?',
    doc: 'Whether members can hurt each other. Default `true`.',
  },
  {
    name: 'see_invisible_teammates',
    type: 'boolean?',
    doc: 'Whether members see each other while invisible. Default `true`.',
  },
  { name: 'nametags', type: `${TEAM_NAMETAGS}?`, doc: NAMETAGS_DOC },
  { name: 'collision', type: `${TEAM_COLLISION}?`, doc: COLLISION_DOC },
]

/** The plain tables teams take. */
export const teamShapes: LuaClass[] = [
  {
    name: 'TeamOptions',
    doc: "What `nf.teams.create` makes a team with. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields,
  },
]
