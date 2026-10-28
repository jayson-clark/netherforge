import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const templateEvents: EventSpec[] = [
  {
    name: 'open',
    doc: 'Someone opened a window made from this template: after the window\'s own `open` handlers, before `nf.on("menu_open")`.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'close',
    doc: 'Someone closed a window made from this template, or left the server with it open: after the window\'s own `close` handlers, before `nf.on("menu_close")`.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'click',
    doc: "Someone clicked while a window made from this template was open: after the clicked slot's and the window's handlers, before `nf.on(\"menu_click\")`. `event.menu` is the window, `event.index` the slot. A locked template's windows start every click that would move items already cancelled; `event:uncancel()` lets one through.",
    payload: 'MenuClickEvent',
    cancellable: true,
    bubbles: true,
    example:
      'template:on("click", function(event)\n  if event.in_menu and event.index == 13 then\n    event.player:send_message("<green>Bought!")\n  end\nend)',
  },
  {
    name: 'drag',
    doc: "Someone dragged a stack across slots of a window made from this template: after the window's own `drag` handlers. A locked template's windows start it already cancelled.",
    payload: 'MenuDragEvent',
    cancellable: true,
  },
]

/** A menu made by a script rather than a file. */
export const menuTemplateClass: LuaClass = {
  name: 'MenuTemplate',
  doc: "A menu made by a script with `nf.menus.create` rather than read from `menus/<id>/`: what a window opened from it starts as. Open a window of it with `player:open_menu(template)`, as many as you like, each a `Menu` of its own; handlers on the template hear every one of its windows (after the window's own handlers). It lives as long as the script that created it: when that script unloads, or `remove()` is called, its windows close and its handlers go, and its methods answer `nil` or `false` rather than erroring.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: templateEvents,
  functions: [
    {
      name: 'id',
      doc: "Its id, made up when it was created. It never names a folder under `menus/`, and it's only good while the template lasts.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: "Whether it's still there: until `remove()`, or until the script that created it unloads.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'size',
      doc: 'How many slots its windows have.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'rows',
      doc: "How many rows of slots its windows have: a chest's 1 to 6, a hopper's 1, a dispenser's 3.",
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'title',
      doc: 'The title its windows start with, as MiniMessage, without the skin.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'skin',
      doc: 'The resource pack skin its windows start with, as `<pack>/<key>`, or `nil` for the vanilla look.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'is_locked',
      doc: 'Whether its windows start locked: clicks that would move items into or out of them reach handlers already cancelled.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'item',
      doc: 'What a slot of a new window starts with, or `nil` for an empty one. A slot outside its windows is an error.',
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'windows',
      doc: 'Every window made from it that is still open.',
      params: [],
      returns: [{ type: 'Menu[]' }],
    },
    {
      name: 'remove',
      doc: 'Takes it away before its script unloads: its windows close, and its handlers go.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was already gone.' }],
    },
    ...eventFunctions(
      'MenuTemplate',
      'template',
      'local picker = nf.menus.create({ type = "hopper", title = "Pick one" })\npicker:on("click", function(event)\n  event.player:send_message("You picked slot " .. tostring(event.index))\nend)',
    ),
  ],
}
