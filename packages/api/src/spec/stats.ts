import type { EventSpec, Fn, LuaClass } from '../types.ts'

/** How stats are made up, for every function that reads or changes one. */
const STATS =
  'A stat is a number by name (`"defense"`, `"move"`, `"damage_melee"`): the sum of what every buff it has adds (its file\'s `stats`) and every modifier a script set (`set_modifiers`), 0 when nothing gives it any. What a stat means is the scripts\': the engine only adds them up.'

/** Buffs aren't saved, for the functions that give them. */
const NOT_SAVED =
  "Buffs and modifiers are runtime state: a restart clears them; a player's last while the server runs, offline too, and count down only while they're online."

/**
 * The stat and buff functions of a holder (a `Player`, a `Centity`), with [who] the word its docs use for it
 * and [example] a call in the kind of script it's usually in.
 */
export function statFunctions(who: string, self: string): Fn[] {
  return [
    {
      name: 'stat',
      doc: `One of ${who}'s stats. ${STATS}`,
      params: [
        { name: 'name', type: 'string', doc: 'The stat: lowercase letters, digits and `_`.' },
      ],
      returns: [{ type: 'number' }],
      example: `local damage = 20\nlocal taken = math.max(1, damage - ${self}:stat("defense") / 2)`,
    },
    {
      name: 'stats',
      doc: `Every stat ${who} has that isn't 0, by name. ${STATS}`,
      params: [],
      returns: [{ type: 'table<string, number>' }],
    },
    {
      name: 'modifiers',
      doc: `What a script's \`source\` adds to ${who}'s stats (\`set_modifiers\`), or \`nil\` when it adds nothing.`,
      params: [{ name: 'source', type: 'string', doc: 'Whose: `"armour"`, `"accessories"`.' }],
      returns: [{ type: 'table<string, number>?' }],
    },
    {
      name: 'set_modifiers',
      doc: `Sets what \`source\` adds to ${who}'s stats, replacing what it added before: one call per source, each time it changes (a piece of armour put on), and \`nil\` takes it all away. ${NOT_SAVED}`,
      params: [
        { name: 'source', type: 'string', doc: 'Whose: any name of your own.' },
        {
          name: 'modifiers',
          type: 'table<string, number>',
          doc: 'A number per stat name. Leave it out (or `nil`) to take the source away.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: `\`false\` once ${who} has gone.` }],
      example: `${self}:set_modifiers("armour", { defense = 12, move = 0.05 })`,
    },
    {
      name: 'add_buff',
      doc: `Gives ${who} a buff (\`buffs/<id>.json\`) for a number of ticks: it takes away the buffs it \`cures\`, and doesn't take hold while one it's \`blockedBy\` lasts. Given again while it lasts, its file's \`stacking\` says what happens to the time. Its \`buff_add\` handlers may change the ticks or cancel it. While it lasts its \`stats\` are added to ${who}'s; when it runs out, \`buff_end\` hears it. ${NOT_SAVED} A buff the project doesn't have is an error.`,
      params: [
        { name: 'buff', type: 'string', doc: 'Its id: `buffs/<id>.json`.', names: 'buff' },
        { name: 'ticks', type: 'integer', doc: 'How long, at least 1.' },
      ],
      returns: [
        {
          type: 'boolean',
          doc: `Whether it took hold: \`false\` when one it's \`blockedBy\` is on ${who}, a handler cancelled it, or ${who} has gone.`,
        },
      ],
      example: `${self}:add_buff("ironskin", 20 * 60 * 4)`,
    },
    {
      name: 'remove_buff',
      doc: `Takes a buff away from ${who} (\`buff_end\` hears \`"removed"\`).`,
      params: [{ name: 'buff', type: 'string', doc: 'Its id.', names: 'buff' }],
      returns: [{ type: 'boolean', doc: 'Whether they had it.' }],
    },
    {
      name: 'has_buff',
      doc: `Whether ${who} has a buff now.`,
      params: [{ name: 'buff', type: 'string', doc: 'Its id.', names: 'buff' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'buff_ticks',
      doc: `How many ticks a buff ${who} has has left, or \`nil\` when they don't have it.`,
      params: [{ name: 'buff', type: 'string', doc: 'Its id.', names: 'buff' }],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'buffs',
      doc: `Every buff ${who} has now, with what a HUD shows them by, the first given first.`,
      params: [],
      returns: [{ type: 'ActiveBuff[]' }],
      example: `for _, buff in ipairs(${self}:buffs()) do\n  log(buff.id, buff.ticks)\nend`,
    },
  ]
}

/** A holder's buff events. */
export const buffEvents: EventSpec[] = [
  {
    name: 'buff_add',
    doc: "A buff is about to take hold (`add_buff`): assign `event.ticks` to change how long, or `event:cancel()` it (one it can't take). Not heard when it's `blockedBy` another.",
    payload: 'BuffEvent',
    cancellable: true,
    writable: ['ticks'],
  },
  {
    name: 'buff_end',
    doc: 'A buff ended: it ran out (`"expired"`), a script took it away (`"removed"`), or another buff cured it (`"cured"`).',
    payload: 'BuffEndEvent',
    example:
      'player:on("buff_end", function(event)\n  if event.buff == "ironskin" then\n    event.player:send_message("<gray>Your skin softens.")\n  end\nend)',
  },
]

/** The tables stats and buffs take and give. */
export const statShapes: LuaClass[] = [
  {
    name: 'ActiveBuff',
    doc: 'A buff someone has now: `buffs()`.',
    methods: false,
    functions: [],
    fields: [
      { name: 'id', type: 'string', doc: 'Its id.' },
      { name: 'ticks', type: 'integer', doc: 'Ticks it has left.' },
      { name: 'name', type: 'Text?', doc: "Its file's `name`, or `nil`." },
      {
        name: 'icon',
        type: 'string?',
        doc: "Its file's `icon`, a glyph (`<pack>/<key>`), or `nil`.",
      },
      { name: 'color', type: 'string?', doc: "Its file's `color`, or `nil`." },
      { name: 'debuff', type: 'boolean', doc: 'Whether it is one.' },
    ],
  },
  {
    name: 'BuffEvent',
    doc: 'A buff is about to take hold: `buff_add`. Exactly one of `player` and `centity` is set.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'buff', type: 'string', doc: 'Its id.' },
      {
        name: 'ticks',
        type: 'integer',
        doc: 'How long it will last: assign to change it (at least 1).',
      },
      { name: 'player', type: 'Player?', doc: 'Who gets it, or `nil`.' },
      { name: 'centity', type: 'Centity?', doc: 'The centity that gets it, or `nil`.' },
    ],
  },
  {
    name: 'BuffEndEvent',
    doc: 'A buff ended: `buff_end`. Exactly one of `player` and `centity` is set.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'buff', type: 'string', doc: 'Its id.' },
      { name: 'reason', type: '"expired"|"removed"|"cured"', doc: 'Why: see `buff_end`.' },
      { name: 'player', type: 'Player?', doc: 'Who had it, or `nil`.' },
      { name: 'centity', type: 'Centity?', doc: 'The centity that had it, or `nil`.' },
    ],
  },
]
