import type { PlatformFile } from '../types.ts'

export default {
  source: 'PlatformEvents.kt',
  decls: [
    {
      kind: 'interface',
      name: 'PlatformEvents',
      doc: "What the adapter tells the runtime. The other half of [Platform]: the\nadapter listens to the server's events, translates them into these calls,\nand applies the answers (a `true` from a cancellable one cancels the\nserver's event). All on the main thread.\n\nThese are the events the runtime does more with than raise them to\nscripts: its services hear them, or a project item hears one first.",
      properties: [
        {
          name: 'game',
          doc: "Every other event the server raises to scripts, generated from the API\nspec (an `nf` event's `raised`): the adapter maps the server's event to\nits payload and applies what comes back.",
          type: 'GameEvents',
        },
      ],
      methods: [
        {
          name: 'tick',
          doc: 'Once per server tick.',
          params: [],
          returns: 'Unit',
        },
        {
          name: 'itemRendered',
          doc: "[stack], a project item's stack the platform just rewrote to its item's current look\n([ProjectItemOps.refresh]), as its item's `render` handlers finish it (a name and lore worked out from\nits data): what to put in its place. [stack] itself when nobody listens.",
          params: [
            {
              name: 'stack',
              type: 'ItemData',
            },
          ],
          returns: 'ItemData',
        },
        {
          name: 'itemViewing',
          doc: "Whether a stack of the project item [reference] names (as its stamp reads, `shop:ruby`; null for a stack\nthat's no project item's) may look different to some player ([ItemViewOps]): asked for every stack sent to\na player's game, so it's cheap, and false while no script listens.",
          params: [
            {
              name: 'reference',
              type: 'String?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'itemViewed',
          doc: "How [stack] looks to [player], whose game it's being sent to, as the `view` handlers leave it; null when\nit looks as it is (nobody changed it, or a handler failed). Asked only when [itemViewing] said it may.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'stack',
              type: 'ItemData',
            },
          ],
          returns: 'ItemView?',
        },
        {
          name: 'playerInteract',
          doc: 'A click on a block or the air with [hand] (`main_hand` or `off_hand`),\nholding [item], on [face] of [block] (both null for the air). True\ncancels it.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'button',
              type: 'ClickButton',
            },
            {
              name: 'block',
              type: 'BlockRef?',
            },
            {
              name: 'face',
              type: 'String?',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
            {
              name: 'hand',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'playerUseItem',
          doc: 'A right click using [item] in [hand], after [playerInteract] let it through. True stops the item being used.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
            {
              name: 'hand',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'drawsWithoutAmmo',
          doc: 'Whether [item] is a bow that draws and shoots without ammo: a project item with `drawWithoutAmmo`. The platform\nthen starts the draw itself when the player has no arrows, and fires the shot when they let go.',
          params: [
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'blockBreak',
          doc: 'A player breaking [block]. [drops] is what it would drop (asked only if\na script listens) and [experience] the experience. Null cancels it.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'block',
              type: 'BlockRef',
            },
            {
              name: 'drops',
              type: '() -> List<ItemData>',
            },
            {
              name: 'experience',
              type: 'Int',
            },
          ],
          returns: 'DropsAnswer?',
        },
        {
          name: 'blockStopBreak',
          doc: 'A player stopped mining [block] before it broke: they let go, or looked away.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'block',
              type: 'BlockRef',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'worldBroke',
          doc: "The world breaks [block] itself, no player doing it: what held it up went, a fluid washed it away, a piston\nbroke it, leaves decayed. Only for the block ids [BlockOps.watch] names. What it drops instead of the game's\ndrops (spawned where it was), or null to leave the game's.",
          params: [
            {
              name: 'block',
              type: 'BlockRef',
            },
          ],
          returns: 'List<ItemData>?',
        },
        {
          name: 'blockUpdated',
          doc: 'Something beside [block] changed, for a state [BlockOps.watch] names as falling.',
          params: [
            {
              name: 'block',
              type: 'BlockRef',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'fallingLanded',
          doc: 'A falling block entity [entity] ([BlockOps.fall]) lands as [block], about to be placed.',
          params: [
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'block',
              type: 'BlockRef',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'pickBlock',
          doc: "A player picking [block] (the middle click), with [inventory] what they hold (36 slots, the hotbar's first).\nNull leaves it to the game; an answer picks what it says instead.",
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'block',
              type: 'BlockRef',
            },
            {
              name: 'inventory',
              type: 'List<ItemData?>',
            },
          ],
          returns: 'PickAnswer?',
        },
        {
          name: 'playerDied',
          doc: 'A player died: [killer] if someone killed them, [cause] the damage\ncause (lowercase), [message] the death message (MiniMessage, null for\nnone), whether they keep their inventory, what they drop and the\nexperience they drop. The answer is what to apply.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'killer',
              type: 'UUID?',
            },
            {
              name: 'cause',
              type: 'String',
            },
            {
              name: 'message',
              type: 'String?',
            },
            {
              name: 'keepInventory',
              type: 'Boolean',
            },
            {
              name: 'drops',
              type: 'List<ItemData>',
            },
            {
              name: 'experience',
              type: 'Int',
            },
          ],
          returns: 'DeathAnswer',
        },
        {
          name: 'playerDropItem',
          doc: 'A player is dropping [item]. True cancels it.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'playerPickupItem',
          doc: 'A player is picking up [item] from the item entity [entity]. True cancels it.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
            {
              name: 'entity',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'playerConsumeItem',
          doc: 'A player finished eating or drinking [item]. True cancels it.',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'worldSaving',
          doc: 'The server is saving [world] (an autosave, `/save-all`, or stopping).',
          params: [
            {
              name: 'world',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'pluginsChanged',
          doc: 'Another plugin was enabled or disabled, or an economy (or any service)\nwas registered with or removed from the server: what a package\n`requires` of other plugins may have changed. The state at the event\nmay be mid-change (a plugin is still enabled while its disable event is\nraised), so the runtime looks again on its next tick.',
          params: [],
          returns: 'Unit',
        },
        {
          name: 'entityClicked',
          doc: "A player clicked an entity. True when it was one of ours: the adapter\ncancels the vanilla interaction even if no script wanted the click, so a\ncentity part never takes damage or opens anything.\n\n[sight] is the player's line of sight at the click, for checking the\nclick against the real hitbox shape.",
          params: [
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'button',
              type: 'ClickButton',
            },
            {
              name: 'sight',
              type: 'Ray?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'entitiesLoaded',
          doc: 'Entities came into memory with their chunk: [tagged] are the ones\ncarrying a NetherForge tag, [untagged] every other one (so what a script\nhid from players with `hide_from`, which the server forgot when the\nchunk unloaded, is hidden again).',
          params: [
            {
              name: 'tagged',
              type: 'Map<UUID, EntityTag>',
            },
            {
              name: 'untagged',
              type: 'List<UUID>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'structureMarkersLoaded',
          doc: 'Entities came into memory with their chunk, and [markers] among them\nare structure markers asking for centities (a structure just\ngenerated, or a saved chunk whose markers were never taken).',
          params: [
            {
              name: 'markers',
              type: 'List<StructureMarker>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'entitiesUnloading',
          doc: "Entities (none of NetherForge's) are about to leave memory with their chunk: their script data can still be written, and their Lua goals go.",
          params: [
            {
              name: 'entities',
              type: 'List<UUID>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'entityDamaged',
          doc: "Something is hurting an entity that isn't one of NetherForge's (a player\nincluded). The answer is the damage to deal, [amount] when unchanged, or\nnull to cancel it.",
          params: [
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'amount',
              type: 'Double',
            },
            {
              name: 'cause',
              type: 'String',
            },
            {
              name: 'attacker',
              type: 'UUID?',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'entityDied',
          doc: "An entity that isn't one of NetherForge's (nor a player: [playerDied])\ndied. [drops] is what it drops (asked only if a script listens); the\nanswer is what it drops instead and the experience.",
          params: [
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'killer',
              type: 'UUID?',
            },
            {
              name: 'drops',
              type: '() -> List<ItemData>',
            },
            {
              name: 'experience',
              type: 'Int',
            },
          ],
          returns: 'DropsAnswer',
        },
        {
          name: 'generatedLoot',
          doc: "A container a project terrain generated (`loot` on a decoration, a script's `chunk:set_loot`) is filled for\nthe first time, at [location]: opened by [player], broken, or emptied by a hopper (no player). The answer is\nwhat one roll of the project's loot table [table] (as the terrain names it) gives; null when no project is\nrunning, which leaves the container as it is.",
          params: [
            {
              name: 'table',
              type: 'String',
            },
            {
              name: 'player',
              type: 'UUID?',
            },
            {
              name: 'location',
              type: 'Location',
            },
          ],
          returns: 'List<ItemData>?',
        },
        {
          name: 'playerInteractEntity',
          doc: "A player right-clicked an entity that isn't one of NetherForge's, with `main_hand` or `off_hand`. True cancels it.",
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'hand',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'resourcePackStatus',
          doc: "A player's game answered about resource pack [pack]: `loaded`,\n`declined`, `failed`, `pending` (accepted or downloaded), or null when it\ndropped the pack.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'pack',
              type: 'UUID',
            },
            {
              name: 'status',
              type: 'String?',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'menuClicked',
          doc: 'A click while [window] (one of ours) was open. True cancels it.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'click',
              type: 'MenuClick',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'menuDragged',
          doc: "A drag of [cursor] across [slots] of [window] (only the window's own slots) and [ownSlots] of the player's own\ninventory below it (numbered as `InventoryRef.Player` is), spreading it evenly ([click] `left`) or one in each\n(`right`). True cancels it.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'slots',
              type: 'List<Int>',
            },
            {
              name: 'cursor',
              type: 'ItemData?',
            },
            {
              name: 'ownSlots',
              type: 'List<Int>',
              default: 'emptyList()',
            },
            {
              name: 'click',
              type: 'String',
              default: '"left"',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'menuClosed',
          doc: 'A player closed [window], or left with it open. Not delivered for [MenuOps.destroy] or a retitle.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'PlayerRef',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'menuChanged',
          doc: "The game changed what's in [slots] of [window] for a viewer's click or drag, heard as soon as it's done, before\nthe server can do anything else (save a player, stop): what a slot bound to a storage moved is saved with it.\nNot for what the runtime itself wrote ([MenuOps.setItem], [MenuOps.add]).",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'slots',
              type: 'Set<Int>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'dialogPressed',
          doc: "A dialog button was pressed. [values] holds every input's answer by key:\na String for text, an option's id, or a checkbox (its `onTrue`/`onFalse`\nstring); a Double for a slider.",
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'dialog',
              type: 'String',
            },
            {
              name: 'button',
              type: 'String',
            },
            {
              name: 'values',
              type: 'Map<String, Any>',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'dialogClosed',
          doc: 'A player left [dialog] through its exit action: escape, or the exit\nbutton NetherForge gives a `multi_action` or `dialog_list` dialog. (Escape\non a notice or confirmation is a press of a button; nothing at all is\nreported for a close the server causes or a disconnect.)',
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'dialog',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'customClicked',
          doc: "A client sent the custom click action [id] (`<namespace>:<path>`), with\n[answers] to the dialog's inputs: what a button of a dialog in the\nserver's registry does. True when it was one of the project's (a press,\nor leaving, of a dialog on the pause screen or the quick actions key),\nwhich is then handled like [dialogPressed] or [dialogClosed].",
          params: [
            {
              name: 'player',
              type: 'PlayerRef',
            },
            {
              name: 'id',
              type: 'String',
            },
            {
              name: 'answers',
              type: 'DialogAnswers',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'PickAnswer',
      doc: 'What a player picking a block gets: [slot] of their inventory to bring to hand (as the game does for a block it\nfinds there), else [item] put in hand (in creative), else nothing.',
      fields: [
        {
          name: 'slot',
          type: 'Int?',
        },
        {
          name: 'item',
          type: 'ItemData?',
        },
      ],
    },
    {
      kind: 'record',
      name: 'DropsAnswer',
      doc: 'What something drops: [drops] instead of what it would have (null when unchanged), and [experience].',
      fields: [
        {
          name: 'drops',
          type: 'List<ItemData>?',
        },
        {
          name: 'experience',
          type: 'Int',
        },
      ],
    },
    {
      kind: 'record',
      name: 'DeathAnswer',
      doc: "What scripts made of a player's death: null [drops] when unchanged.",
      fields: [
        {
          name: 'message',
          type: 'String?',
        },
        {
          name: 'keepInventory',
          type: 'Boolean',
        },
        {
          name: 'drops',
          type: 'List<ItemData>?',
        },
        {
          name: 'experience',
          type: 'Int',
        },
      ],
    },
    {
      kind: 'enum',
      name: 'ClickButton',
      params: [
        {
          name: 'luaName',
          type: 'String',
        },
      ],
      entries: [
        {
          name: 'LEFT',
          args: ['"left"'],
        },
        {
          name: 'RIGHT',
          args: ['"right"'],
        },
      ],
    },
  ],
} satisfies PlatformFile
