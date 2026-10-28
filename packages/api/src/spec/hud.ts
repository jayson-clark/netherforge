import type { Field, LuaClass } from '../types.ts'

const element = (what: string) => ({
  name: 'name',
  type: 'string',
  doc: `The ${what}, as \`<pack>/<key>\`: a key of a resource pack's \`hud.${what === 'bar' ? 'bars' : what === 'row of icons' ? 'icons' : 'texts'}\` (a package's as \`ns:<pack>/<key>\`).`,
})

const OFFLINE = "`false` when they're offline."

/** `player:hud()`: one player's HUD, of the resource packs' elements. */
export const hudClass: LuaClass = {
  name: 'Hud',
  doc: "One player's HUD: the elements of the project's resource packs (`hud` in `pack.json`: bars, rows of icons, lines of text, each at its place on the screen), shown with what a script sets here. An element nobody set isn't drawn. It's drawn in their action bar, sent again whenever something changes and every second, so `player:send_actionbar` shows its line alongside it while the HUD is up (on its own for a moment, when the server can't measure it). What it shows is the server's to remember, not saved: set it again when they join. A name that isn't one of the packs' elements, or is another kind of element, is an error.",
  methods: true,
  handle: { key: [{ name: 'player', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'player',
      doc: 'Whose HUD it is.',
      params: [],
      returns: [{ type: 'Player' }],
    },
    {
      name: 'fill_bar',
      doc: "Fills a bar: `value` out of `max`, shown as that much of its segments (to the nearest half segment, for a bar with a half picture). A `value` past `max` or below 0 is shown full or empty; a `max` of 0 or less is an error. With `segments`, only that many of the bar's segments are drawn (1 to the bar's own), placed by the bar's `align`.",
      params: [
        element('bar'),
        { name: 'value', type: 'number', doc: '' },
        { name: 'max', type: 'number', doc: '' },
        { name: 'options', type: 'HudFillOptions', doc: '', optional: true },
      ],
      returns: [{ type: 'boolean', doc: OFFLINE }],
      example:
        'local hud = player:hud()\nhud:fill_bar("ui/health", player:health() or 0, player:max_health() or 20)\nhud:fill_bar("ui/mana", 40, 100)',
    },
    {
      name: 'fill_bars',
      doc: "Fills several bars as one long bar, each `per_segment` of `max` a segment: as many segments are drawn as `max` needs, filling the first bar's, then the next's (rows of hearts, ten to a row). A bar no segment reaches is removed. Every name must be a bar; `per_segment` and `max` more than 0.",
      params: [
        { name: 'names', type: 'string[]', doc: 'Bars, `<pack>/<key>`, in the order they fill.' },
        { name: 'value', type: 'number', doc: '' },
        { name: 'max', type: 'number', doc: '' },
        { name: 'per_segment', type: 'number', doc: 'How much of `max` a segment stands for.' },
      ],
      returns: [
        { type: 'integer', doc: "How many segments were drawn in all; 0 when they're offline." },
      ],
      example:
        '-- 20 life a heart, ten hearts a row, up to three rows.\nlocal hud = player:hud()\nhud:fill_bars({ "ui/life1", "ui/life2", "ui/life3" }, 230, 400, 20)',
    },
    {
      name: 'bar',
      doc: "What a bar shows, or `nil` when nothing set it (or they're offline).",
      params: [element('bar')],
      returns: [{ type: 'HudBar?' }],
    },
    {
      name: 'set_icons',
      doc: "Shows these of a row's icons, in this order: status effects, buffs, keys held. An empty list shows none. An icon the row doesn't have is an error.",
      params: [
        element('row of icons'),
        { name: 'icons', type: 'string[]', doc: "Keys of the row's `icons`." },
      ],
      returns: [{ type: 'boolean', doc: OFFLINE }],
      example: 'player:hud():set_icons("ui/effects", { "poison", "regeneration" })',
    },
    {
      name: 'icons',
      doc: "The icons a row shows, in order, or `nil` when nothing set it (or they're offline).",
      params: [element('row of icons')],
      returns: [{ type: 'string[]?' }],
    },
    {
      name: 'set_text',
      doc: "Sets a line of text, on the action bar's own line where the line's `x` and `align` put it. The server measures it with the game's font (`fonts/default.json`) to put it there; one it can't measure isn't drawn.",
      params: [
        element('line of text'),
        { name: 'text', type: 'Text', doc: 'MiniMessage; glyphs are fine.' },
      ],
      returns: [{ type: 'boolean', doc: OFFLINE }],
      example:
        'local mana = 40\nplayer:hud():set_text("ui/mana_label", "<aqua>" .. mana .. "/100")',
    },
    {
      name: 'text',
      doc: "A line's text, or `nil` when nothing set it (or they're offline).",
      params: [element('line of text')],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'remove',
      doc: 'Stops drawing one element (any kind), until something sets it again.',
      params: [{ name: 'name', type: 'string', doc: 'A HUD element, `<pack>/<key>`.' }],
      returns: [{ type: 'boolean', doc: `${OFFLINE.slice(0, -1)}, or nothing was drawn for it.` }],
    },
    {
      name: 'clear',
      doc: "Stops drawing every element: their action bar is the game's again.",
      params: [],
      returns: [],
    },
  ],
}

const field = (name: string, type: string, doc: string): Field => ({ name, type, doc })

export const hudShapes: LuaClass[] = [
  {
    name: 'HudBar',
    doc: 'What a HUD bar shows: `hud:bar(name)`.',
    methods: false,
    functions: [],
    fields: [
      field('value', 'number', 'How much of it is full, as set.'),
      field('max', 'number', 'Out of how much.'),
      {
        name: 'segments',
        type: 'integer',
        doc: 'How many of its segments are drawn.',
      },
    ],
  },
  {
    name: 'HudFillOptions',
    doc: 'How `hud:fill_bar` draws a bar.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'segments',
        type: 'integer?',
        doc: "Draw only this many of the bar's segments, 1 to its own. Default all of them.",
      },
    ],
  },
]
