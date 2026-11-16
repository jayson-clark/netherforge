import type { PlatformFile } from '../types.ts'

export default {
  source: 'Items.kt',
  decls: [
    {
      kind: 'record',
      name: 'ItemData',
      doc: "An item as the server holds it: the fields NetherForge can describe (script\ndata, [ItemDef.data], included: the adapter keeps it in the stack's\npersistent data container), plus [raw], whatever else the stack carries (a\nbook's pages, another plugin's data), opaque. [raw] is present only when\n[def] alone would lose something, and it's what lets a script read an item,\nchange one field and write it back without stripping the rest.\n\nA stack of a project item has [ItemDef.item] set, and [look] is the hash of\nthe look it was stamped with (`ProjectItems.hash`), as read from the\nserver: what tells a stale stack from a current one. It's the platform's to\nstamp, so it's ignored when an item is written (the platform stamps the\nitem's current look, or keeps the stamp [raw] carries).",
      fields: [
        {
          name: 'def',
          type: 'ItemDef',
        },
        {
          name: 'raw',
          type: 'String?',
          default: 'null',
        },
        {
          name: 'look',
          type: 'String?',
          default: 'null',
        },
      ],
    },
    {
      kind: 'record',
      name: 'ItemLook',
      doc: "A project item's look: its definition as an item ([dev.netherforge.format.item.ItemFile.look]) and that look's hash.",
      fields: [
        {
          name: 'def',
          type: 'ItemDef',
        },
        {
          name: 'hash',
          type: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ProjectItemOps',
      doc: "Project items on real stacks. A stack stamped with a project item whose\nlook has changed since (its hash isn't the item's current one) is stale;\nthese rewrite stale stacks with `ProjectItems.restyle`, keeping whatever\nelse the stack carries that the item can't say (another plugin's data).\nStacks of items the project no longer has are left alone. Checking a stack\nreads only its stamp, so a whole inventory of current stacks costs little.",
      properties: [],
      methods: [
        {
          name: 'refresh',
          doc: "Rewrites the stale stacks in [inventory]: a project item's from before its look changed, and any stack\nwhose `canBreak` or `canPlaceOn` names a project block held in another state than it was. How many it\nrewrote; 0 for one that isn't there.",
          params: [
            {
              name: 'inventory',
              type: 'InventoryRef',
            },
          ],
          returns: 'Int',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ItemViewOps',
      doc: "How stacks look to each player, apart from what they are.\n\nThe adapter asks the runtime about every stack it sends a player's game (their inventory, armour and offhand\nincluded, the window they have open, their cursor; a whole window or one slot): first, cheaply, whether\nstacks of its project item may look different to anyone ([PlatformEvents.itemViewing]), and only then how it\nlooks to that player ([PlatformEvents.itemViewed]), whose name and lore it sends in the real ones' place. What\nthe server keeps never changes: the stack, how it stacks, what's saved, what anyone else is sent, what\nscripts read. What the game sends back is mapped back to what the server holds, so a game that echoes a stack\nit was shown (a creative-mode click) can't make the shown look real; and the game's idea of a slot it was\nshown a look for still counts as in step, so a click doesn't resend it.",
      properties: [],
      methods: [
        {
          name: 'refresh',
          doc: "Works out every stack [player]'s game shows again ([PlatformEvents.itemViewed]) and sends those that now\nlook different from what it was last sent: their inventory, the window they have open, their cursor.\nFalse when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'ItemView',
      doc: "How a stack looks to one player ([PlatformEvents.itemViewed]): the name (null for the game's own) and lore (null for none) they're shown.",
      fields: [
        {
          name: 'name',
          type: 'String?',
        },
        {
          name: 'lore',
          type: 'List<String>?',
        },
      ],
    },
    {
      kind: 'record',
      name: 'RecipeSpec',
      doc: "A recipe the project adds: [id] is the project's own (`ruby_sword`), which\nthe adapter keys in the project's namespace (`shop:ruby_sword`), and [file] says what it is\n(from `recipes/<id>.json` or a script, validated either way). Its result\nand project-item ingredients name items the adapter resolves through the\nlooks [Platform.bind] gave it.",
      fields: [
        {
          name: 'id',
          type: 'String',
        },
        {
          name: 'file',
          type: 'RecipeFile',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'RecipeOps',
      doc: "The project's recipes on the server.\n\nA project item in a recipe is matched by the id its stacks carry, never by\nlooks: the adapter lets the server match the item's kind, then checks every\nproject-item ingredient against the stack in its place when a station is\nabout to make something (a crafting grid's result, a crafter, a furnace,\nsmoker, blast furnace or campfire cooking, a smithing table's result, a\nstonecutter's choice), and refuses it on a mismatch. A recipe takes a\nproject item only where it names it: a vanilla kind or tag (in the\nproject's recipes or Minecraft's own) never takes a project item's stack.\nRecipe ids the adapter reports in events (`player_craft`) are the\nproject's own for these, and namespaced (`minecraft:bread`) otherwise.",
      properties: [],
      methods: [
        {
          name: 'add',
          doc: "Adds [recipe], replacing one with its id, without telling players ([resend] does). False when it can't be built.",
          params: [
            {
              name: 'recipe',
              type: 'RecipeSpec',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'remove',
          doc: "Takes the project's recipe [id] away, without telling players. False when the server has no such recipe.",
          params: [
            {
              name: 'id',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'resend',
          doc: "Sends every online player the server's recipes again, after adds and removes.",
          params: [],
          returns: 'Unit',
        },
        {
          name: 'discover',
          doc: "Puts a recipe in [player]'s recipe book: the project's by id, or any by its namespaced id. False when it can't (offline, already there, no such recipe).",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'recipe',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'undiscover',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'recipe',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hasDiscovered',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'recipe',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
