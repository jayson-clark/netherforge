import { TooltipComponentValues, UseAnimationValues } from '@netherforge/format/types'
import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const shape = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

/** What kind of click it was: `MenuClickEvent.click`, `PlayerClickInventoryEvent.click`. */
export const CLICK_KIND =
  '"left"|"right"|"shift_left"|"shift_right"|"middle"|"number_key"|"swap_offhand"|"double"|"drop"|"control_drop"|"other"'

/** The doc of a click event's `click` field. */
export const CLICK_DOC =
  'What kind of click it was. `swap_offhand` is the swap-hands key (F) over a slot: what is in it and what is in the off hand change places.'

/** What kind of drag it was: `MenuDragEvent.click`, `PlayerDragInventoryEvent.click`. */
export const DRAG_CLICK: LuaClass['fields'][number] = {
  name: 'click',
  type: '"left"|"right"',
  doc: '`"left"` spreads the stack evenly over the slots, `"right"` puts one in each.',
}

const menuContext = {
  name: 'context',
  type: 'any',
  doc: 'What the script that opened the window passed along (`player:open_menu(menu, { context = ... })`), the same as `menu:context()`: the very value, not a copy. `nil` when nothing was, and always for a shared window.',
}

const dialogContext = {
  name: 'context',
  type: 'any',
  doc: "What the script that opened the dialog passed along for this opening (`player:open_dialog(dialog, { context = ... })`): the very value, not a copy. It's kept on the server, never sent to the player, so it can be anything at all. `nil` when nothing was.",
}

/** A payload: a shape whose table is an event object, with `Event`'s members too. */
const payload = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  ...shape(name, doc, fields),
  extends: 'Event',
})

const menuEvents: EventSpec[] = [
  {
    name: 'open',
    doc: 'Someone opened this window: `player:open_menu`, or `menu:open_for`. Then the `MenuTemplate` it was made from (if it was) hears it, then `nf.on("menu_open")`.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'close',
    doc: 'Someone closed this window, or left the server with it open. Then the `MenuTemplate` it was made from (if it was) hears it, then `nf.on("menu_close")`. When the last viewer of a window that isn\'t shared closes it, the window goes, and with it every handler on it.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'click',
    doc: 'Someone clicked while this window was open, in it or in their own inventory below it. A click on one of its slots is heard by that slot first; then the menu; then the `MenuTemplate` it was made from, if it was; then `nf.on("menu_click")`. `event:cancel()` keeps it from moving anything. A locked window starts every click that would move items in or out already cancelled; `event:uncancel()` lets one through.',
    payload: 'MenuClickEvent',
    cancellable: true,
    bubbles: true,
    example:
      'this:on("click", function(event)\n  if event.in_menu and event.index == 15 then\n    event.player:close_menu()\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'drag',
    doc: 'Someone dragged a stack across slots of this window (spreading it out, or dropping one in each). Then the `MenuTemplate` it was made from (if it was) hears it. A locked window starts it already cancelled.',
    payload: 'MenuDragEvent',
    cancellable: true,
    bubbles: true,
  },
]

const slotEvents: EventSpec[] = [
  {
    name: 'click',
    doc: 'Someone clicked this slot. Then the menu hears it, then `nf.on("menu_click")`, unless a handler calls `event:stop()`.',
    payload: 'MenuClickEvent',
    cancellable: true,
    bubbles: true,
    example:
      'this:slot(13):on("click", function(event)\n  event.player:send_message("<red>Sold out")\n  event:cancel()\n  event:stop()\nend)',
  },
]

const dialogEvents: EventSpec[] = [
  {
    name: 'press',
    doc: 'Someone pressed one of its buttons, with every input\'s answer in `event.values`: heard by the button first, then the dialog, then `nf.on("dialog_press")`. Escape on a notice is a press of its button, and on a confirmation a press of its second ("no") button.',
    payload: 'DialogPressEvent',
    bubbles: true,
  },
  {
    name: 'close',
    doc: "Someone left it without pressing one of its buttons: escape, or the exit button NetherForge gives a dialog (a `multi_action` dialog's \"Back\", a `dialog_list`'s own button, or the one standing in for a notice's or confirmation's missing button); or a script closed it (`player:close_dialog()`) or opened another dialog over it. Not when they leave the server (`player_quit` covers that), and not for escape on a notice or confirmation that has the button escape presses.",
    payload: 'DialogEvent',
  },
]

const buttonEvents: EventSpec[] = [
  {
    name: 'press',
    doc: 'Someone pressed this button. Then the dialog hears it, then `nf.on("dialog_press")`, unless a handler calls `event:stop()`.',
    payload: 'DialogPressEvent',
    bubbles: true,
    example:
      'this:button("done"):on("press", function(event)\n  event.player:send_message("<green>Hello, " .. nf.text.escape(tostring(event.values.nickname)))\nend)',
  },
]

/** An open menu window: one per player, or one for the server when the menu is `shared`. */
export const menuClass: LuaClass = {
  name: 'Menu',
  doc: "A live menu window from `menus/<id>/`: `this` in the menu's script, and what `player:open_menu`, `nf.menus.shared` and every menu event hand out. One that isn't `shared` exists from the moment it's opened for someone until its last viewer closes it; after that its methods return `nil` or `false` rather than erroring, and its handlers (and its slots') are gone. A shared one lives as long as the project.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: menuEvents,
  functions: [
    {
      name: 'id',
      doc: "This window's id: the menu's own id for a shared one, a fresh one for each window of a menu that isn't.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'kind',
      doc: 'Which menu it is: its folder name under `menus/`. Nil once the window is gone, and for a window made from a `MenuTemplate` (see `template()`).',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'template',
      doc: 'The `MenuTemplate` this window was made from (`player:open_menu(template)`), or `nil` for a window of a menu file, and once the window or the template is gone.',
      params: [],
      returns: [{ type: 'MenuTemplate?' }],
    },
    {
      name: 'exists',
      doc: "Whether the window is still there. One that isn't shared goes when its last viewer closes it.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_shared',
      doc: 'Whether this is the one window the whole server shares.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'context',
      doc: 'What the script that opened this window passed along: `player:open_menu(menu, { context = value })`. The very value, not a copy, so a table can carry anything (the item being bought, a callback, the centity that was clicked). Every event on the window carries it too, as `event.context`. `nil` when nothing was passed, and always for a shared window, which nobody opens with a context.',
      params: [],
      returns: [{ type: 'any' }],
      example:
        '-- opened with player:open_menu("shop", { context = { discount = 0.1 } })\nlocal discount = (this:context() or {}).discount or 0',
    },
    {
      name: 'size',
      doc: 'How many slots it has. Slots are numbered from 0, left to right, then down.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'rows',
      doc: "How many rows of slots it has: a chest's 1 to 6, a hopper's 1, a dispenser's 3. `size() / rows()` is how many are in a row.",
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'title',
      doc: 'The title, as MiniMessage, without the skin drawn in front of it.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_title',
      doc: "Renames the window. Minecraft can't rename a window that's open, so everyone looking sees it close and open again: fine between clicks, wrong every tick.",
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'skin',
      doc: 'The resource pack skin drawn behind it, as `<pack>/<key>`, or `nil` for the vanilla look.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_skin',
      doc: "Changes the background. A skin is drawn in the title, so this costs the same close-and-open `set_title` does. A skin the server's packages don't have is an error.",
      params: [
        {
          name: 'skin',
          type: 'string',
          doc: "`<pack>/<key>`, another loaded package's as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)), or leave it out for the vanilla look.",
          optional: true,
          names: 'skin',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example: 'this:set_skin("ui/shop_night")',
    },
    {
      name: 'is_locked',
      doc: 'Whether clicks are kept from moving items into or out of it: such a click reaches handlers already cancelled. Scripts hear the clicks either way.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_locked',
      doc: 'Locks or unlocks this window. Not saved: a reload goes back to what the file says.',
      params: [{ name: 'locked', type: 'boolean', doc: '' }],
      returns: [],
    },
    {
      name: 'shift_into',
      doc: "Where a shift-click from the player's own inventory goes: into the slots of this window bound to `storage`, from `first` to `last` (storage slot indexes), in order. Stacks top up partial stacks first, then take empty slots, as vanilla does. Each slot's `click` handlers hear the move as a click on it (`event.item` is the stack moving); `event:cancel()` refuses it there, and the item goes to the next slot. Nothing else is tried: an item that no slot takes stays in the player's inventory, and a window's other slots never get it. Applies whether or not the window is locked, and each call replaces the last. A reload forgets it, so the script's body sets it again. The window must be bound to the storage (`slot:bind`), and not be `shared`.",
      params: [
        { name: 'storage', type: 'Inventory', doc: 'From `player:storage(name, size)`.' },
        {
          name: 'first',
          type: 'integer',
          doc: 'The first storage slot to try, from 0. Default 0.',
          optional: true,
        },
        {
          name: 'last',
          type: 'integer',
          doc: "The last storage slot to try. Default the storage's last slot.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'nf.on("menu_open", function(event)\n  -- nil while they\'re offline, which a player opening a menu never is.\n  local trinkets = assert(event.player:storage("trinkets", 4))\n  this:slot(10):bind(trinkets, 0)\n  this:shift_into(trinkets)\nend)',
    },
    {
      name: 'slot',
      doc: "One of its slots, as a handle. A slot outside the window is an error. A handle is the window's id and the index, so it's fine to keep while the window lasts.",
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Slot' }],
      example:
        'local buy = this:slot(13)\nbuy:set_item({ kind = "minecraft:emerald", name = "<green>Buy" })',
    },
    {
      name: 'item',
      doc: 'What is in a slot, or `nil` for an empty one. A slot outside the window is an error.',
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_item',
      doc: "Puts an item in a slot, replacing what was there, or empties it. An item table with a misspelled field, or an item id the server doesn't have, is an error.",
      params: [
        { name: 'index', type: 'integer', doc: 'From 0.' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'local bread = this:item(11)\nif bread then\n  bread.count = bread.count - 1\n  this:set_item(11, bread)\nend',
    },
    {
      name: 'add_item',
      doc: 'Puts an item wherever it fits, topping up matching stacks first, the way picking something up does.',
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [{ type: 'integer', doc: "How many didn't fit: 0 when all of it went in." }],
    },
    {
      name: 'items',
      doc: 'Every item in it, keyed by slot. Empty slots are left out, so `pairs` visits only what is there.',
      params: [],
      returns: [{ type: 'table<integer, Item>' }],
      example:
        'for index, item in pairs(this:items()) do\n  log(index, item.kind, item.count)\nend',
    },
    {
      name: 'set_items',
      doc: "Puts several items in at once, by slot, replacing what was in those slots; the others are left alone. Every item is checked before any slot changes, so a mistake (a slot outside the window, a misspelled field, an item the server doesn't have) is an error that leaves the window as it was.",
      params: [
        {
          name: 'items',
          type: 'table<integer, Item>',
          doc: 'Items by slot, from 0: `{ [11] = bread, [15] = cake }`.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'this:set_items({\n  [11] = { kind = "minecraft:bread", count = 4 },\n  [15] = { kind = "minecraft:cake" },\n})',
    },
    {
      name: 'fill',
      doc: 'Puts a copy of one item in every empty slot, or in each of the listed slots (empty or not): a border of glass panes, say. A slot outside the window is an error.',
      params: [
        { name: 'item', type: 'Item', doc: '' },
        {
          name: 'indices',
          type: 'integer[]',
          doc: 'The slots to fill, from 0. Leave it out for every empty one.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example: 'this:fill({ kind = "minecraft:gray_stained_glass_pane", hide_tooltip = true })',
    },
    {
      name: 'clear',
      doc: 'Empties every slot. To empty one, `set_item(index, nil)`.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'viewers',
      doc: 'Everyone looking at it now.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'cursor_item',
      doc: "What a viewer is carrying on their cursor while they have this window open, or `nil` for nothing (and when they aren't looking at it).",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_cursor_item',
      doc: "Puts an item on a viewer's cursor, replacing what they were carrying, or empties it. What's on the cursor goes back to their inventory (or drops) when they close the window.",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the cursor.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when they aren't looking at this window or the window is gone.",
        },
      ],
    },
    {
      name: 'refresh',
      doc: "Sends the whole window to everyone looking at it again. Changes are sent by themselves, so this is only for when a viewer's screen may be out of step: after a cancelled click left a ghost item on it, say.",
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'open_for',
      doc: 'Shows this very window to someone too: a second pair of eyes on one menu. Its `open` handlers hear it. To give someone a window of their own, use `player:open_menu`.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when they're offline or the window is gone." }],
    },
    {
      name: 'close_for',
      doc: 'Closes it for one viewer.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [
        { type: 'boolean', doc: "`false` when the window is gone or they weren't looking at it." },
      ],
    },
    {
      name: 'close_all',
      doc: 'Closes it for everyone looking at it.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    ...eventFunctions(
      'Menu',
      'menu',
      'this:on("open", function(event)\n  event.player:send_message("<gold>Welcome to the shop")\nend)',
    ),
  ],
}

/** One slot of one menu window. */
export const slotClass: LuaClass = {
  name: 'Slot',
  doc: "One slot of one menu window, from `menu:slot(index)` (`this:slot(index)` in the menu's script). After the window goes, its methods return `nil` or `false` rather than erroring, and its handlers are gone.",
  methods: true,
  handle: {
    key: [
      { name: 'menu', type: 'string' },
      { name: 'index', type: 'integer' },
    ],
  },
  fields: [],
  events: slotEvents,
  functions: [
    {
      name: 'index',
      doc: 'Its number in the window, from 0.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'menu',
      doc: 'The window it belongs to.',
      params: [],
      returns: [{ type: 'Menu' }],
    },
    {
      name: 'item',
      doc: "What is in it, or `nil` when it's empty or the window is gone.",
      params: [],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_item',
      doc: "Puts an item in it, replacing what was there, or empties it. An item table with a misspelled field, or an item id the server doesn't have, is an error.",
      params: [
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'bind',
      doc: "Makes this slot a view of a slot of a player's `storage`: it shows what's there, and whatever goes in or out of it, by a click, a drag, a shift-click or a script's `set_item` on either, is saved in the storage at once, as the game moves it. So the item is only ever in one place, whatever happens next: the window closing, the player leaving or dying, the server stopping or the project reloading. In a locked window players can still put items in and take them out of a bound slot by hand (double-click gathering stays locked, and a shift-click from their own inventory goes only where `shift_into` says); its `click` handlers can still refuse what doesn't belong (`event:cancel()`). Binding a slot again moves it to the new place. Only a window of a menu that isn't `shared` can be bound, and only to a storage (`player:storage`): another inventory, or a slot outside the storage, is an error.",
      params: [
        { name: 'storage', type: 'Inventory', doc: 'From `player:storage(name, size)`.' },
        {
          name: 'index',
          type: 'integer',
          doc: 'Which of its slots, from 0. Default the same number as this slot.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` once the window is gone, or the storage's player is offline.",
        },
      ],
      example:
        'local trinkets = event.player:storage("trinkets", 4)\nfor i = 0, 3 do\n  this:slot(10 + i):bind(trinkets, i)\nend',
    },
    {
      name: 'bind_equipment',
      doc: "Makes this slot a view of what a player wears in one place, as `bind` does a storage's slot: what goes in or out of it is put on or taken off at once, and when what they wear changes some other way (a right-click with armour, a script's `set_equipment`) the slot shows it by the next tick. Locked windows let players use it by hand, as a bound slot. The game doesn't check what's put on: give the slot a rule (`set_rule({ wearable = \"head\", max = 1 })`). Only a window of a menu that isn't `shared` can be bound.",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'equipment',
          type: 'string',
          doc: '`head`, `chest`, `legs`, `feet` or `off_hand`.',
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` once the window is gone, or they're offline." }],
      example:
        'local slot = this:slot(11)\nslot:bind_equipment(event.player, "head")\nslot:set_rule({ wearable = "head", max = 1 })',
    },
    {
      name: 'set_rule',
      doc: "Says what this slot takes, or with `nil` lets it take anything again. Whatever a player puts in, by a click, a drag, a number key or a shift-click from their own inventory into `shift_into`'s slots, is held to it: a stack it doesn't take stays where it was, and of one it does, only `max` goes in (the rest stays on the cursor). Its `click` handlers still hear what's let through. Scripts' own `set_item` aren't held to it. A shift-click from below that the game moves itself (no `shift_into`) isn't either, in a window that isn't locked.",
      params: [{ name: 'rule', type: 'SlotRule', doc: '', optional: true }],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'local trinkets = event.player:storage("trinkets", 4)\nlocal slot = this:slot(10)\nslot:bind(trinkets, 0)\nslot:set_rule({ property = "accessory", max = 1 })',
    },
    {
      name: 'rule',
      doc: 'What this slot takes, or `nil` when it takes anything (or the window is gone).',
      params: [],
      returns: [{ type: 'SlotRule?' }],
    },
    {
      name: 'accepts',
      doc: "Whether this slot's rule takes this item (any count of it). `true` for a slot without a rule; `false` once the window is gone.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'unbind',
      doc: "Stops showing a storage's slot (or a player's equipment) here. The slot is emptied, since what it showed is still where it was. Its player leaving unbinds every slot bound to their storage or equipment.",
      params: [],
      returns: [{ type: 'boolean', doc: "`false` when it wasn't bound, or the window is gone." }],
    },
    {
      name: 'binding',
      doc: 'Which storage slot this slot shows, or `nil` when it shows none.',
      params: [],
      returns: [{ type: 'SlotBinding?' }],
    },
    ...eventFunctions(
      'Slot',
      'slot',
      'this:slot(13):on("click", function(event)\n  event:cancel()\nend)',
    ),
  ],
}

/** A dialog from `dialogs/<id>/`: one of each, shown to anyone. */
export const dialogClass: LuaClass = {
  name: 'Dialog',
  doc: "A dialog from `dialogs/<id>/`: `this` in the dialog's script, and what `nf.dialogs.get` returns. A dialog holds nothing, so there is one of each for the whole server and showing it is all there is to do with it. Every button runs Lua with the answers to the inputs in hand; none of them is a command. Handlers on it last while the project has the dialog, across reloads of it. One made by `nf.dialogs.create` works the same way, and lasts as long as the script that made it.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: dialogEvents,
  functions: [
    {
      name: 'id',
      doc: 'Its folder name under `dialogs/`, or for one from `nf.dialogs.create`, an id made up for it.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: 'Whether the project still has this dialog (a reload can take it away), or, for one from `nf.dialogs.create`, whether the script that made it is still running.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'title',
      doc: 'Its title, as MiniMessage.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'buttons',
      doc: 'The button keys, in the order they are drawn: what `event.key` can be.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'inputs',
      doc: 'The input keys, in the order they are asked: the keys of `event.values`.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'button',
      doc: 'One of its buttons, as a handle. A key the dialog has no button for is an error.',
      params: [
        {
          name: 'key',
          type: 'string',
          doc: "The button's `key` in `dialog.json`.",
          names: 'button',
        },
      ],
      returns: [{ type: 'Button' }],
    },
    {
      name: 'open_for',
      doc: "Puts it on someone's screen. It's built fresh each time, so what they see is the dialog as it is now, with whatever `options` change for this opening. A project dialog already on their screen is closed (its `close` handlers hear it).",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'options',
          type: 'DialogOpenOptions',
          doc: "What to open it with, for this opening only. A key it doesn't take, an input or body element the dialog doesn't have, or a value of the wrong kind for its input is an error.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when they're offline or the dialog is gone." }],
      example:
        'assert(this:node("sign")):on("click", function(event)\n  nf.dialogs.get("rename"):open_for(event.player, {\n    context = this,\n    values = { name = assert(this:data()).name },\n  })\nend)',
    },
    {
      name: 'ask',
      impl: 'lua',
      waits: true,
      doc: "Opens it for `player` and pauses the task until they answer: returns their press, or `nil` if they close it (see the `close` event: escape through NetherForge's exit button, or a script closing it or opening another dialog over it), leave the server, or it couldn't be opened (they're offline, or the dialog is gone). The press still goes to the dialog's handlers and `nf.on(\"dialog_press\")` as usual; `ask` hears it at the dialog, so a button handler that calls `event:stop()` hides it from `ask` too.",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'options',
          type: 'DialogOpenOptions',
          doc: 'What to open it with, as for `open_for`.',
          optional: true,
        },
      ],
      returns: [{ type: 'DialogPressEvent?' }],
      example:
        'nf.task(function()\n  local answer = nf.dialogs.get("confirm"):ask(player)\n  if answer and answer.key == "yes" then\n    player:send_message("<green>Done.")\n  end\nend)',
    },
    ...eventFunctions(
      'Dialog',
      'dialog',
      'this:on("press", function(event)\n  log(event.player:name(), "pressed", event.key)\nend)',
    ),
  ],
}

/** One button of a dialog. */
export const buttonClass: LuaClass = {
  name: 'Button',
  doc: "One button of a dialog, from `dialog:button(key)` (`this:button(key)` in the dialog's script). If a reload takes the button away, its methods return `nil` rather than erroring, and its handlers are gone.",
  methods: true,
  handle: {
    key: [
      { name: 'dialog', type: 'string' },
      { name: 'key', type: 'string' },
    ],
  },
  fields: [],
  events: buttonEvents,
  functions: [
    {
      name: 'key',
      doc: "Its `key` in `dialog.json`: what `event.key` is when it's pressed.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'dialog',
      doc: 'The dialog it belongs to.',
      params: [],
      returns: [{ type: 'Dialog' }],
    },
    {
      name: 'label',
      doc: 'What it says, as MiniMessage, or `nil` once the dialog no longer has it.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    ...eventFunctions(
      'Button',
      'button',
      'this:button("done"):on("press", function(event)\n  event.player:send_message("<green>Thanks!")\nend)',
    ),
  ],
}

/** The plain tables menus and dialogs hand out and take. */
export const menuShapes: LuaClass[] = [
  shape(
    'SlotBinding',
    'What a menu slot shows: `slot:binding()`. A slot of a storage (`storage` and `index`), or what a player wears (`player` and `equipment`).',
    [
      { name: 'storage', type: 'Inventory?', doc: 'The storage (`player:storage`).' },
      { name: 'index', type: 'integer?', doc: "The storage's slot, from 0." },
      { name: 'player', type: 'Player?', doc: 'Whose equipment.' },
      {
        name: 'equipment',
        type: 'string?',
        doc: '`head`, `chest`, `legs`, `feet` or `off_hand`.',
      },
    ],
  ),
  shape(
    'SlotRule',
    "What a menu slot takes (`slot:set_rule`). A stack goes in when it's any of `items`, `kinds`, `property` or `wearable` (or when none is given), and never more of it than `max`. A key that isn't one of these is an error.",
    [
      { name: 'items', type: 'string[]?', doc: "The project's items it takes, by id." },
      {
        name: 'kinds',
        type: 'string[]?',
        doc: "The game's items it takes, by id or `#tag` (`#minecraft:head_armor`); a project item's stacks never count as their kind.",
      },
      {
        name: 'property',
        type: 'string?',
        doc: 'A project item with this key in its `properties` (and not `false`).',
      },
      {
        name: 'wearable',
        type: 'string?',
        doc: "What's worn there: `head`, `chest`, `legs`, `feet` or `body`, by its `equipment` slot or, for the game's own items, the slot's armour tag (`#minecraft:head_armor`...).",
      },
      {
        name: 'max',
        type: 'integer?',
        doc: 'The most items it holds: 1 for a ring. Default a full stack.',
      },
    ],
  ),
  shape(
    'Item',
    "An item stack as a plain table: what `menu:item` returns and what `menu:set_item` takes. Only `kind` is required, or `item` instead. Read one, change a field and write it back, and nothing else about it changes: whatever the stack carries that these fields can't say (a book's pages, another plugin's data) rides along in `raw`, which you never write yourself. A field that isn't one of these is an error.",
    [
      {
        name: 'kind',
        type: 'string?',
        doc: 'The item type: `"minecraft:diamond_sword"`, or `"diamond_sword"`. Required unless `item` names a project item, whose kind it then is; an item read from the server always has it.',
      },
      {
        name: 'item',
        type: 'string?',
        doc: "The project item (`items/<id>/`) this stack is: `{ item = \"ruby\", count = 3 }`. Its file fills in every field the table leaves out (`nf.items.create` does the same), and the stack carries the item's namespaced id wherever it goes, so it's still a ruby in a chest, on the ground, or after a restart. A project item the project doesn't have is an error, and so is a `kind` that isn't the item's.",
      },
      { name: 'count', type: 'integer?', doc: '1 to 99. Defaults to 1.' },
      {
        name: 'name',
        type: 'Text?',
        doc: 'Display name, MiniMessage. Not italic unless you say so.',
      },
      { name: 'lore', type: 'Text[]?', doc: 'Lines under the name, each MiniMessage.' },
      {
        name: 'enchantments',
        type: 'table<string, integer>?',
        doc: 'Enchantment id → level: `{ ["minecraft:sharpness"] = 5 }`.',
      },
      {
        name: 'glint',
        type: 'boolean?',
        doc: 'The enchanted shimmer without an enchantment, as menus use for "selected".',
      },
      { name: 'hide_tooltip', type: 'boolean?', doc: 'No tooltip at all, for filler panes.' },
      { name: 'unbreakable', type: 'boolean?', doc: '' },
      { name: 'damage', type: 'integer?', doc: 'Durability used: 0 is pristine.' },
      {
        name: 'item_model',
        type: 'string?',
        doc: "`<pack>/<key>`: an entry of one of your package's resource packs' `items`, or another loaded package's as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)). It names a look the server has, or it's an error; read back, it's as your package writes it.",
      },
      {
        name: 'tooltip_style',
        type: 'string?',
        doc: "`<pack>/<key>`: a tooltip frame from one of your package's resource packs, or another loaded package's as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)).",
      },
      {
        name: 'equipment',
        type: 'ItemEquipment?',
        doc: 'Worn as armour or equipment, drawn as one of the project\'s resource packs\' equipment looks: `{ asset = "gear/ruby", slot = "head" }`.',
      },
      { name: 'color', type: 'string?', doc: '`#RRGGBB`, for dyed leather, potions and maps.' },
      { name: 'profile', type: 'string?', doc: 'Whose head, for a player head: a name or a UUID.' },
      {
        name: 'max_stack_size',
        type: 'integer?',
        doc: "How many fit in one stack, 1 to 99, instead of the kind's own. More than 1 on an item that takes damage (a sword, armour) is an error.",
      },
      {
        name: 'map',
        type: 'integer?',
        doc: "The map a filled map shows, by its number: a screen's (`screen:item`), or any of the game's. Only a `minecraft:filled_map` draws it.",
      },
      {
        name: 'rarity',
        type: '"common"|"uncommon"|"rare"|"epic"?',
        doc: 'The colour of its name when it has no colour of its own: white, yellow, aqua or light purple.',
      },
      {
        name: 'attribute_modifiers',
        type: 'ItemAttributeModifier[]?',
        doc: 'What it changes about whoever wears or holds it: `{ { attribute = "minecraft:attack_damage", amount = 6, operation = "add_value", slot = "main_hand" } }`. These replace the kind\'s own (a sword\'s damage) rather than adding to them.',
      },
      {
        name: 'can_break',
        type: 'string[]?',
        doc: 'The blocks a player in adventure mode can break with it: the project\'s by id (`"ruby_ore"`, a package\'s `"acme:gem_ore"`), the game\'s with their namespace (`"minecraft:stone"`), `#tags` of blocks (`"#minecraft:logs"`, or one a project datapack defines), or `"*"` for any block: `{ "ruby_ore", "#minecraft:logs" }`. A project block it names that the project doesn\'t have is an error.',
      },
      {
        name: 'can_place_on',
        type: 'string[]?',
        doc: 'The blocks a player in adventure mode can place it on, written as `can_break`\'s: `{ "*" }` is anywhere.',
      },
      {
        name: 'hidden_tooltip_components',
        type: `(${TooltipComponentValues.map((it) => `"${it}"`).join('|')})[]?`,
        doc: 'Lines of its tooltip to hide, by the component that draws them: `{ "unbreakable", "attribute_modifiers" }` hides "Unbreakable" and the attribute lines ("+2 Attack Damage"). The components still apply: the item stays unbreakable and keeps its modifiers. Left out shows the tooltip as it is; `{}` shows every line. A value that isn\'t a line an item can hide is an error, and so is one the server\'s Minecraft version doesn\'t have.',
      },
      {
        name: 'food',
        type: 'ItemFood?',
        doc: "What eating it does. Any item with `food` can be eaten, even one that isn't food: `{ nutrition = 4, saturation = 2.4 }`.",
      },
      {
        name: 'cooldown',
        type: 'ItemCooldown?',
        doc: "A cooldown after using it (an ender pearl's): `{ seconds = 2 }`. Items with the same `group` share one.",
      },
      {
        name: 'invisible',
        type: 'boolean?',
        doc: "Drawn as nothing wherever it's drawn: in a slot, on the ground, and in a hand, where the player's own arm isn't drawn either (as with anything held). An empty look every built resource pack has, in place of `item_model`. With `hold`, it's a pointer: no arm in the way, the right button pressed heard as `player_use_item` and let go as `player_stop_using_item`.",
      },
      {
        name: 'hold',
        type: 'boolean|ItemHold?',
        doc: "Held down with no end: a right click lasts until the player lets go, never finishes and uses nothing up. Letting go is `player_stop_using_item`, and `event.ticks` says how long it was held: a flail thrown on release, a charge that grows while held. `true` draws nothing (no zoom, no pose); `{ animation = \"trident\" }` poses the player as that use of the game's does, a knife raised to throw. Read back, it's `true` or that table, as it was given (a hold whose animation is `\"none\"` reads back `true`). Unless `use_effects` says otherwise, the player keeps their speed and can sprint, whatever the animation. It can't go with `food`, and only works on a kind with no use of its own (`minecraft:stick`, not a bow or a shield). Left clicks don't attack while it's held.",
      },
      {
        name: 'use_effects',
        type: 'ItemUseEffects?',
        doc: 'How a player moves while using it, for any use: a `hold`, eating, a bow drawn, a shield raised. `{ speed_multiplier = 1, can_sprint = true }` lets them eat at a run.',
      },
      {
        name: 'data',
        type: 'table<string, any>?',
        doc: "Your script's own data, saved on the stack itself (its persistent data container), so it goes wherever the item goes: tag a coin with `data = { coin = true }`. A table keyed by strings, holding what a `data()` table may: strings, numbers, booleans, tables of them, `Vec3`s, `Location`s, `Item` tables, and `Player` and `Centity` handles, which come back as the same types. Absent when the stack carries none. Anything else (a function, a `Menu` window) is an error, naming where it was.",
      },
      {
        name: 'raw',
        type: 'string?',
        doc: "Everything else the stack carries, opaque. Present only when the fields above don't say it all. Leave it alone: it's how a read-change-write keeps what you didn't change.",
      },
    ],
  ),
  shape(
    'ItemAttributeModifier',
    "One of an item's `attribute_modifiers`: how much it changes one attribute of whoever wears or holds it.",
    [
      {
        name: 'id',
        type: 'string?',
        doc: 'A namespaced id telling it apart from the item\'s other modifiers. Default `"<namespace>:<attribute>_<index>"` (the project\'s namespace, and its place in the list from 0), which is what you get back when it has that one.',
      },
      {
        name: 'attribute',
        type: 'string',
        doc: 'The attribute: `"minecraft:attack_damage"`, `"minecraft:movement_speed"`, `"minecraft:max_health"`, …',
      },
      { name: 'amount', type: 'number', doc: 'How much.' },
      {
        name: 'operation',
        type: '"add_value"|"add_multiplied_base"|"add_multiplied_total"',
        doc: 'How `amount` applies: added; added times the base value; or the total multiplied by `1 + amount`.',
      },
      {
        name: 'slot',
        type: '"any"|"hand"|"main_hand"|"off_hand"|"armor"|"head"|"chest"|"legs"|"feet"|"body"|"saddle"?',
        doc: 'Where the item has to be for it to count. Default `"any"`.',
      },
    ],
  ),
  shape('ItemFood', "What eating an item does: an `Item`'s `food`.", [
    { name: 'nutrition', type: 'integer', doc: 'Hunger points it restores (a full bar is 20).' },
    { name: 'saturation', type: 'number', doc: 'Saturation it adds.' },
    {
      name: 'can_always_eat',
      type: 'boolean?',
      doc: 'Whether it can be eaten when full. Default false.',
    },
    { name: 'eat_seconds', type: 'number?', doc: 'How long eating it takes. Default 1.6.' },
  ]),
  shape(
    'ItemEquipment',
    "What an item looks like worn, and where: an `Item`'s `equipment` (the `equippable` component).",
    [
      {
        name: 'asset',
        type: 'string',
        doc: "`<pack>/<key>`: an equipment look from one of your package's resource packs, or another loaded package's as `<namespace>:<pack>/<key>` ([looks are values](packages.md#looks-are-values)).",
      },
      {
        name: 'slot',
        type: '"head"|"chest"|"legs"|"feet"|"body"|"saddle"',
        doc: 'The slot it is worn in: `"body"` is a horse\'s or wolf\'s armour.',
      },
    ],
  ),
  shape('ItemCooldown', "A cooldown after using an item: an `Item`'s `cooldown`.", [
    { name: 'seconds', type: 'number', doc: 'How long, more than 0.' },
    {
      name: 'group',
      type: 'string?',
      doc: "A namespaced id: items in one group share a cooldown (`player:set_item_cooldown` takes it too). Default: the item's kind.",
    },
  ]),
  shape(
    'ItemHold',
    "A `hold` item that poses the player while it's held: an `Item`'s `hold`, as a table. Everything but the pose is a plain `hold = true`'s.",
    [
      {
        name: 'animation',
        type: `(${UseAnimationValues.map((it) => `"${it}"`).join('|')})?`,
        doc: 'The pose, as one of the game\'s uses draws it: `"trident"` (arm raised to throw), `"bow"` (both arms out, drawing), `"crossbow"` (both arms, loading), `"block"`, `"spyglass"` (raised to the eye), `"toot_horn"`, `"brush"`, `"spear"` (held low and forward), `"eat"`, `"drink"`, `"bundle"`, or `"none"`. The zoom of a bow and the spyglass\'s scope are the real items\' only. Default `"none"`. A name that isn\'t one is an error.',
      },
    ],
  ),
  shape(
    'ItemUseEffects',
    "How a player moves while using an item: an `Item`'s `use_effects`. A field left out is the game's default, or on a `hold` item the hold's.",
    [
      {
        name: 'speed_multiplier',
        type: 'number?',
        doc: 'Their speed while using it, from 0 to 1. Default 0.2, or 1 on a `hold` item.',
      },
      {
        name: 'can_sprint',
        type: 'boolean?',
        doc: 'Whether they can sprint while using it. Default false, or true on a `hold` item.',
      },
      {
        name: 'interact_vibrations',
        type: 'boolean?',
        doc: 'Whether starting to use it sets off sculk sensors. Default true, or false on a `hold` item.',
      },
    ],
  ),
  shape('MenuOpenOptions', 'What `player:open_menu` opens a window with.', [
    {
      name: 'context',
      type: 'any',
      doc: "Anything at all, handed to the new window: its script reads it with `this:context()`, and every event on the window carries it as `event.context`. It's the very value, kept on the server, so a table can carry handles and functions. A shared menu has one window for everyone, so giving it a context is an error.",
    },
  ]),
  shape(
    'DialogOpenOptions',
    "What `player:open_dialog`, `dialog:open_for` and `dialog:ask` open a dialog with. Everything here is for this one opening: the dialog's file doesn't change, and the next opening starts from it again.",
    [
      {
        name: 'context',
        type: 'any',
        doc: 'Anything at all, handed back as `event.context` on every press (and on `close`) of this opening. Kept on the server for this player until the dialog leaves their screen, never sent to them, so it can carry handles and functions.',
      },
      {
        name: 'values',
        type: 'table<string, string|number|boolean>?',
        doc: "What its inputs start at, by input key, instead of the file's `initial`: a string for a text input, the option's `id` for a `single_option`, a number within the range for a `number_range`, `true` or `false` for a `boolean`. An input the dialog doesn't have is an error.",
      },
      {
        name: 'title',
        type: 'Text?',
        doc: "The title for this opening, MiniMessage, instead of the file's.",
      },
      {
        name: 'body',
        type: 'table<string, Text>?',
        doc: "Body text for this opening, MiniMessage, by the body element's `key`: a message's text, or an item's description. A key no body element of the dialog has is an error.",
      },
    ],
  ),
  payload('MenuEvent', 'Someone opened or closed a menu window.', [
    { name: 'player', type: 'Player', doc: 'Who.' },
    { name: 'menu', type: 'Menu', doc: 'The window.' },
    menuContext,
  ]),
  payload(
    'MenuClickEvent',
    'Someone clicked while a menu window was open. A click on one of the window\'s slots is heard by the slot, then the menu, then `nf.on("menu_click")`; a click anywhere else starts at the menu.',
    [
      { name: 'player', type: 'Player', doc: 'Who clicked.' },
      { name: 'menu', type: 'Menu', doc: 'The window.' },
      {
        name: 'target',
        type: 'Slot?',
        doc: "The window's slot that was clicked, or `nil` for a click in the player's own inventory or outside both.",
      },
      {
        name: 'index',
        type: 'integer?',
        doc: "The slot clicked, from 0: in the window when `in_menu` is true, and in the player's own inventory below it otherwise. `nil` for a click outside both.",
      },
      {
        name: 'in_menu',
        type: 'boolean',
        doc: "Whether the click landed in the window rather than in the player's own inventory below it.",
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
      menuContext,
    ],
  ),
  payload('MenuDragEvent', 'Someone dragged a stack across slots of a menu window.', [
    { name: 'player', type: 'Player', doc: 'Who dragged.' },
    { name: 'menu', type: 'Menu', doc: 'The window.' },
    {
      name: 'indices',
      type: 'integer[]',
      doc: "The window's slots the stack is spread over, from 0, in order.",
    },
    {
      name: 'own_indices',
      type: 'integer[]',
      doc: "The slots of the player's own inventory below it the stack is spread over, in order, numbered as `player:inventory()` numbers them.",
    },
    DRAG_CLICK,
    { name: 'cursor_item', type: 'Item?', doc: 'The stack being dragged.' },
    menuContext,
  ]),
  payload(
    'DialogPressEvent',
    'Someone pressed a dialog\'s button: heard by the button, then the dialog, then `nf.on("dialog_press")`.',
    [
      { name: 'player', type: 'Player', doc: 'Who pressed it.' },
      { name: 'dialog', type: 'Dialog', doc: 'The dialog.' },
      { name: 'target', type: 'Button', doc: 'The button pressed.' },
      { name: 'key', type: 'string', doc: "The button's `key`." },
      {
        name: 'values',
        type: 'table<string, string|number>',
        doc: 'Every input\'s answer, by its `key`: the text typed, the option\'s `id`, the slider\'s number, or a checkbox\'s `onTrue`/`onFalse` string (`"true"`/`"false"` unless the file says otherwise).',
      },
      dialogContext,
    ],
  ),
  payload(
    'DialogEvent',
    'Someone left a dialog without pressing a button, or a script closed it.',
    [
      { name: 'player', type: 'Player', doc: 'Who.' },
      { name: 'dialog', type: 'Dialog', doc: 'The dialog.' },
      dialogContext,
    ],
  ),
]

const item = menuShapes.find((it) => it.name === 'Item')!

/** A partial `Item` that picks stacks: what `inventory:remove_item` and friends take besides a kind. */
export const itemMatchShape: LuaClass = shape(
  'ItemMatch',
  'A partial `Item` that picks stacks: a stack matches when every field given matches, so `{ kind = "minecraft:paper", name = "<gold>Ticket" }` finds gold tickets and nothing else. `kind` may be left out (any kind), and `data` matches as a subset: `{ data = { coin = true } }` finds coins whatever else they carry. `count` and `raw` have no place in a match; a field `Item` doesn\'t have is an error.',
  item.fields.filter((it) => it.name !== 'count' && it.name !== 'raw'),
)
