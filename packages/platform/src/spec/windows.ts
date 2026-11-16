import type { PlatformFile } from '../types.ts'

export default {
  source: 'Windows.kt',
  decls: [
    {
      kind: 'record',
      name: 'WindowSpec',
      doc: 'What a window looks like: its container, its slot count, and its whole title (skin included) as MiniMessage.',
      fields: [
        {
          name: 'type',
          type: 'MenuType',
        },
        {
          name: 'size',
          type: 'Int',
        },
        {
          name: 'title',
          type: 'String',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'MenuOps',
      doc: 'Menu windows, each named by a UUID the runtime chooses.\n\nA window exists from [create] until [destroy], whether or not anyone is\nlooking. Clicks, drags and closes come back through\n[PlatformEvents.menuClicked] and friends.',
      properties: [],
      methods: [
        {
          name: 'create',
          doc: 'Makes a window nobody is looking at yet.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'spec',
              type: 'WindowSpec',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'destroy',
          doc: 'Closes it for everyone and forgets it. No close events are delivered for this.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'retitle',
          doc: "Changes the title. Minecraft can't retitle an open window, so the adapter\nrebuilds it, contents and all, and reopens it for every viewer, without\nreporting those closes and opens as events.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'title',
              type: 'String',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'item',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'slot',
              type: 'Int',
            },
          ],
          returns: 'ItemData?',
        },
        {
          name: 'setItem',
          doc: "Writes a slot; null empties it. False when the item can't be built (an unknown item).",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'slot',
              type: 'Int',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'add',
          doc: "Puts [item] wherever it fits, topping up matching stacks first. Returns how many didn't fit.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Int',
        },
        {
          name: 'room',
          doc: "How many of [item]'s count slot [slot] of [window] takes, by the server's own stacking: what's left of a stack of\nthe same item, the whole stack limit for an empty slot, 0 for another item. Only that count, not the item's.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'slot',
              type: 'Int',
            },
            {
              name: 'item',
              type: 'ItemData',
            },
          ],
          returns: 'Int',
        },
        {
          name: 'open',
          doc: "Shows the window to a player. False when they're offline or the window is gone.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'close',
          doc: 'Closes it for one viewer, or all of them when [player] is null. Close events are delivered.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID?',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'viewers',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
          ],
          returns: 'List<UUID>',
        },
        {
          name: 'closeAny',
          doc: "Closes whatever window a player has open, ours or not. False when they're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'viewing',
          doc: 'The window of ours a player is looking at, or null.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'cursor',
          doc: "What [player] carries on their cursor while looking at [window]; null for nothing, or when they aren't looking at it.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID',
            },
          ],
          returns: 'ItemData?',
        },
        {
          name: 'setCursor',
          doc: "Puts [item] (null empties it) on the cursor of [player], who must be looking at [window]. False when they aren't, or it can't be built.",
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'item',
              type: 'ItemData?',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'refresh',
          doc: 'Sends the whole window to its viewers again.',
          params: [
            {
              name: 'window',
              type: 'UUID',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
    {
      kind: 'record',
      name: 'MenuClick',
      doc: 'One click while one of our windows is open, as the adapter saw it.',
      fields: [
        {
          name: 'player',
          type: 'PlayerRef',
        },
        {
          name: 'slot',
          type: 'Int?',
          doc: "In the window when [top], in the player's own inventory otherwise; null for a click outside both.",
        },
        {
          name: 'top',
          type: 'Boolean',
        },
        {
          name: 'click',
          type: 'String',
          doc: '`left`, `right`, `shift_left`, `shift_right`, `middle`, `number_key`, `double`, `drop`, `control_drop` or `other`.',
        },
        {
          name: 'hotbar',
          type: 'Int?',
        },
        {
          name: 'item',
          type: 'ItemData?',
        },
        {
          name: 'cursor',
          type: 'ItemData?',
        },
        {
          name: 'movesItems',
          type: 'Boolean',
          doc: 'Whether the click would move items into or out of the window: one in it, or a shift-click or collect from below.',
        },
      ],
    },
    {
      kind: 'record',
      name: 'DialogSpec',
      doc: 'A dialog to build and show, with the dialogs a `dialog_list` offers (built the same way).',
      fields: [
        {
          name: 'id',
          type: 'String',
        },
        {
          name: 'file',
          type: 'DialogFile',
        },
        {
          name: 'listed',
          type: 'List<DialogSpec>',
          default: 'emptyList()',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'DialogAnswers',
      doc: "What a player answered in a dialog, by input key; null for a key the answer\nlacks or that isn't that kind of input. Any thread.",
      properties: [],
      methods: [
        {
          name: 'text',
          params: [
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'String?',
        },
        {
          name: 'bool',
          params: [
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'Boolean?',
        },
        {
          name: 'number',
          params: [
            {
              name: 'key',
              type: 'String',
            },
          ],
          returns: 'Double?',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'DialogOps',
      doc: "Dialog screens, built per show and never registered. Every button is a\ncallback that comes back as [PlatformEvents.dialogPressed]; none is a\ncommand. Every dialog type with an exit action gets one whose callback is\n[PlatformEvents.dialogClosed] (a `dialog_list`'s own button becomes it), and\nso does a notice's or confirmation's missing button, so escape is reported\nwherever it isn't a press of the file's button.\n\nThe dialogs of the pause screen and the quick actions key are the one\nexception: they're in the server's dialog registry (the start-up datapack),\nand their buttons are custom click actions the game sends back as\n[PlatformEvents.customClicked].",
      properties: [],
      methods: [
        {
          name: 'show',
          doc: "False when the player is offline or the dialog couldn't be built.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'dialog',
              type: 'DialogSpec',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'close',
          doc: "Takes whatever dialog is on their screen away. False when they're offline.",
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
  ],
} satisfies PlatformFile
