import type { Fn, LuaClass } from '../types.ts'

/** What station recipes are, for the functions that make them. */
const STATION_RECIPES =
  "A material matches as a recipe's ingredient does: an item id or a `#tag` takes any stack of that kind but a project item's, and a project item only its own stacks. A station is a block id, a `#tag` of blocks or a project block, near when it's within the recipe's `station_range` (4 by default) on every axis."

/** `nf.recipes`: the recipes the server knows from the project. */
export const nfRecipes: LuaClass = {
  name: 'nf.recipes',
  doc: "The project's recipes: those in `recipes/<id>.json`, and those scripts register. A recipe can take the project's own items as ingredients (`{ item = \"ruby\" }`), which only a stack of that item fits, however it looks; and a recipe takes a project item only where it names it, so a ruby built on paper never crafts as paper. Players see a recipe in their recipe book once they've discovered it (`player:discover_recipe`), but can craft it either way.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'register',
      doc: 'Adds a recipe the server learns at once, sent to everyone online. `definition` says what `recipes/<id>.json` would, in Lua spelling: `type` (`"shaped"`, `"shapeless"`, `"furnace"`, `"blasting"`, `"smoking"`, `"campfire_cooking"`, `"smithing_transform"`, `"stonecutting"` or `"station"`), then what that type takes: `pattern` and `key`, `ingredients`, `ingredient`, `template`, `base` and `addition`, or `materials` (each `{ ingredient = ..., count = n }`), `stations` and `station_range`; `result` (an `Item`), and `experience`, `cooking_time`, `group` and `category`. An ingredient is an item id (`"minecraft:stick"`), a tag (`"#minecraft:planks"`) or a project item (`{ item = "ruby" }`). Registering an id again replaces the recipe. It lasts as long as the script that registered it (so a reloaded script registers it again in its body). An id a `recipes/` file has, or anything that file would have an error for, is an error.',
      params: [
        {
          name: 'id',
          type: 'string',
          doc: "Its id: lowercase letters, digits and `_`, like a file's name. Players discover it by this id.",
        },
        {
          name: 'definition',
          type: 'table',
          doc: 'What the recipe is, as `recipes/<id>.json` would say it, with its keys in snake_case.',
        },
      ],
      returns: [],
      example:
        'nf.recipes.register("ruby_block", {\n  type = "shaped",\n  pattern = { "RRR", "RRR", "RRR" },\n  key = { R = { item = "ruby" } },\n  result = { kind = "minecraft:redstone_block", name = "<red>Ruby block" },\n})',
    },
    {
      name: 'remove',
      doc: "Takes away a recipe a script registered, from the server and from everyone's recipe book. `false` when there's no such recipe; one from `recipes/` is the file's, so removing it is an error (delete the file instead).",
      params: [{ name: 'id', type: 'string', doc: 'Its id.', names: 'recipe' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'get',
      doc: "A `station` recipe, as `StationRecipe` (its `times` left out), or `nil` when there's no such recipe or it isn't a station recipe. A recipe the game makes (a crafting table's) isn't one: the game crafts those itself.",
      params: [{ name: 'id', type: 'string', doc: 'Its id.', names: 'recipe' }],
      returns: [{ type: 'StationRecipe?' }],
    },
    {
      name: 'available',
      doc: `The \`station\` recipes a player can make now: every station each needs is within its \`station_range\` of them (or of \`options.near\`), and their inventory holds its materials, with \`times\` how many times over. ${STATION_RECIPES}`,
      params: [
        {
          name: 'player',
          type: 'Player',
          doc: 'Who makes them: their inventory, and where they stand.',
        },
        { name: 'options', type: 'RecipeAvailableOptions', doc: '', optional: true },
      ],
      returns: [
        { type: 'StationRecipe[]', doc: 'In id order. Empty for a player who is offline.' },
      ],
      example:
        'for _, recipe in ipairs(nf.recipes.available(player)) do\n  log(recipe.id .. " x" .. recipe.times)\nend',
    },
    {
      name: 'craft',
      doc: `Makes a \`station\` recipe for a player: takes its materials from their inventory and gives them its result (what doesn't fit drops at their feet), \`times\` over, or as many times as they can when that's fewer. A station it needs that isn't near makes nothing. ${STATION_RECIPES} A recipe that isn't a station one is an error.`,
      params: [
        { name: 'player', type: 'Player', doc: '' },
        { name: 'id', type: 'string', doc: "The recipe's id.", names: 'recipe' },
        { name: 'options', type: 'RecipeCraftOptions', doc: '', optional: true },
      ],
      returns: [
        {
          type: 'integer',
          doc: "How many times it was made: 0 when it couldn't be, or the player is offline.",
        },
      ],
      example:
        'local made = nf.recipes.craft(player, "copper_pickaxe")\nif made == 0 then\n  player:send_message("<red>You can\'t make that here.")\nend',
    },
    {
      name: 'all',
      doc: "Every recipe's id: the project's files', then those scripts registered.",
      params: [],
      returns: [{ type: 'string[]' }],
    },
  ],
}

const recipeParam = {
  name: 'recipe',
  type: 'string',
  doc: 'A project recipe\'s id (`"ruby_sword"`), or any recipe the server has by its namespaced id (`"minecraft:bread"`).',
  names: 'recipe' as const,
}

/** What a player knows of recipes: their recipe book. */
export const playerRecipeFunctions: Fn[] = [
  {
    name: 'discover_recipe',
    doc: "Puts a recipe in their recipe book (with the toast that says so). `false` when they're offline, already had it, or the server has no such recipe; a project recipe id the project doesn't have is an error.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
    example:
      'nf.on("player_join", function(event)\n  event.player:discover_recipe("ruby_sword")\nend)',
  },
  {
    name: 'undiscover_recipe',
    doc: "Takes a recipe out of their recipe book. They can still craft it. `false` when they're offline or didn't have it.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'has_discovered_recipe',
    doc: "Whether a recipe is in their recipe book. `false` when they're offline.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
  },
]

/** The tables `nf.recipes`' station functions take and give. */
export const recipeShapes: LuaClass[] = [
  {
    name: 'RecipeCraftEvent',
    doc: 'One making of a `station` recipe by `nf.recipes.craft`: `recipe_craft`.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'player', type: 'Player', doc: 'Who it was made for.' },
      { name: 'recipe', type: 'string', doc: "The recipe's id." },
      {
        name: 'result',
        type: 'Item',
        doc: "What this making gives them: the recipe's result, until a handler assigns another.",
      },
    ],
  },
  {
    name: 'StationRecipe',
    doc: 'A `station` recipe, as `nf.recipes.available` and `nf.recipes.get` give it.',
    methods: false,
    functions: [],
    fields: [
      { name: 'id', type: 'string', doc: 'Its id.' },
      { name: 'result', type: 'Item', doc: 'What one making of it gives.' },
      { name: 'materials', type: 'RecipeMaterial[]', doc: 'What one making of it takes.' },
      {
        name: 'stations',
        type: 'string[]',
        doc: 'The blocks that must be near, as its file names them.',
      },
      { name: 'station_range', type: 'integer', doc: 'How near, in blocks.' },
      { name: 'group', type: 'string?', doc: 'Its `group`, or `nil`.' },
      {
        name: 'times',
        type: 'integer?',
        doc: '`nf.recipes.available`: how many times over the player can make it now. `nil` from `nf.recipes.get`.',
      },
    ],
  },
  {
    name: 'RecipeMaterial',
    doc: 'One material of a `station` recipe: exactly one of `item`, `kind` and `tag`, and how many.',
    methods: false,
    functions: [],
    fields: [
      { name: 'item', type: 'string?', doc: 'A project item, by id.' },
      { name: 'kind', type: 'string?', doc: 'An item id, `"minecraft:stick"`.' },
      { name: 'tag', type: 'string?', doc: 'An item tag, without its `#`.' },
      { name: 'count', type: 'integer', doc: 'How many one making takes.' },
    ],
  },
  {
    name: 'RecipeAvailableOptions',
    doc: "What `nf.recipes.available` looks at. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'near',
        type: 'Vec3?',
        doc: "Where its stations are looked for, in the player's world. Default: where they stand.",
      },
      {
        name: 'stations',
        type: 'string[]?',
        doc: 'Stations counted as near whatever the blocks are: a menu that is a crafting station, a station item held. Default none.',
      },
      { name: 'group', type: 'string?', doc: 'Only recipes of this `group`. Default: any.' },
      {
        name: 'all',
        type: 'boolean?',
        doc: "Every station recipe, not only those they can make now: one they can't has `times` 0. Default `false`.",
      },
    ],
  },
  {
    name: 'RecipeCraftOptions',
    doc: "How `nf.recipes.craft` makes a recipe. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'times', type: 'integer?', doc: 'How many times over, at least 1. Default 1.' },
      {
        name: 'near',
        type: 'Vec3?',
        doc: 'Where its stations are looked for. Default: where the player stands.',
      },
      {
        name: 'stations',
        type: 'string[]?',
        doc: 'Stations counted as near whatever the blocks are.',
      },
    ],
  },
]
