import type { LuaClass } from '../types.ts'

const CALLBACK_DOC =
  'Runs on the main thread, in the tick that finds the time has come, like an `nf.every` callback. One that errors is logged with its file and line; the schedule carries on.'

const OPTIONS_DOC =
  'A table: `id` (a name that survives restarts, which `catch_up` needs) and `catch_up` (run once on start if a run was missed).'

const ZONE_DOC =
  "Times are in the server owner's time zone (`schedules.time-zone` in `config.yml`, the server's own zone by default), not the player's."

const DST_DOC =
  'Daylight saving follows the zone\'s rules. A daily or weekly time the clocks skip runs the same distance after the gap begins (`"02:30"` runs at 03:30 on the day clocks jump from 02:00 to 03:00), and one that happens twice runs the first time only. A cron expression matches the clock as it reads: a time the clocks skip doesn\'t happen that day, one for a single time of day that happens twice (`"30 1 * * *"`) runs the first time only, and one that matches every half hour keeps matching through an hour that repeats.'

/** `nf.schedule`: things that run at a clock time. */
export const nfSchedule: LuaClass = {
  name: 'nf.schedule',
  doc: `Runs a function at a time of day, every day or week, or by a cron expression: real time, as opposed to ticks (\`nf.after\`, \`nf.every\`). ${ZONE_DOC} ${DST_DOC} A schedule belongs to the script that made it and ends when that script unloads (a reload, the server stopping), so the script's body makes it again. It's checked once a tick, so a callback runs within a tick (50 ms) of its time. A run the server missed (it was off at the time) is skipped, unless the schedule has \`catch_up = true\` and an \`id\`: then it runs once, on start. What a schedule last ran is kept in the server's store by \`id\`, per package.`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'daily',
      doc: `Calls \`callback\` every day at a time of day. ${CALLBACK_DOC}`,
      params: [
        {
          name: 'time',
          type: 'string',
          doc: 'The time of day on a 24-hour clock, `"HH:mm"`: `"18:00"`, `"06:30"`.',
        },
        { name: 'callback', type: 'fun()', doc: '' },
        { name: 'options', type: 'ScheduleOptions', doc: OPTIONS_DOC, optional: true },
      ],
      returns: [{ type: 'Schedule' }],
      example:
        'nf.schedule.daily("18:00", function()\n  nf.server.broadcast("<gold>Daily reward is ready!")\nend, { id = "daily_reward", catch_up = true })',
    },
    {
      name: 'weekly',
      doc: `Calls \`callback\` once a week, on a day at a time of day. ${CALLBACK_DOC}`,
      params: [
        {
          name: 'day',
          type: 'string',
          doc: 'The weekday: `"mon"`, `"tue"`, `"wed"`, `"thu"`, `"fri"`, `"sat"` or `"sun"` (or written out, `"saturday"`), in any case.',
        },
        { name: 'time', type: 'string', doc: 'The time of day, as for `nf.schedule.daily`.' },
        { name: 'callback', type: 'fun()', doc: '' },
        { name: 'options', type: 'ScheduleOptions', doc: OPTIONS_DOC, optional: true },
      ],
      returns: [{ type: 'Schedule' }],
      example:
        'nf.schedule.weekly("sat", "18:00", function()\n  nf.server.broadcast("<aqua>Tournament night!")\nend)',
    },
    {
      name: 'cron',
      doc: `Calls \`callback\` whenever a cron expression matches. ${CALLBACK_DOC}`,
      params: [
        {
          name: 'expression',
          type: 'string',
          doc: 'Five fields, as Unix cron has them: minute, hour, day of month, month and day of week (`0` or `7` is Sunday; `MON`-`SUN` and `JAN`-`DEC` work), each `*`, a number, a list (`1,15`), a range (`9-17`) or a step (`*/10`, `0-30/5`). `"*/30 * * * *"` is every half hour; `"0 4 * * 1-5"` is 04:00 on weekdays. An expression that isn\'t valid is an error.',
        },
        { name: 'callback', type: 'fun()', doc: '' },
        { name: 'options', type: 'ScheduleOptions', doc: OPTIONS_DOC, optional: true },
      ],
      returns: [{ type: 'Schedule' }],
      example:
        'nf.schedule.cron("*/30 * * * *", function()\n  log("half past or on the hour")\nend)',
    },
  ],
}

/** What `nf.schedule` returns. */
export const scheduleClass: LuaClass = {
  name: 'Schedule',
  doc: 'What `nf.schedule.daily`, `weekly` and `cron` return: a schedule that will run at its next time, until it is cancelled or its script unloads.',
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'cancel',
      doc: "Ends it: it doesn't run again. What it last ran is kept, so a schedule made again with the same `id` doesn't catch up a run it already made. Cancelling one that has ended does nothing.",
      params: [],
      returns: [],
    },
    {
      name: 'is_active',
      doc: "Whether it will still run: `false` once it's cancelled or its script has unloaded.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'next_run',
      doc: 'When it runs next, as Unix time in milliseconds (like `nf.server.unix_time()`; `nf.time.format` writes it as a date). `nil` once it has ended.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
  ],
}

/** The table the `nf.schedule` functions take. */
export const scheduleShapes: LuaClass[] = [
  {
    name: 'ScheduleOptions',
    doc: "What a schedule is called and what to do about a run the server missed. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'id',
        type: 'string?',
        doc: "A name for the schedule, which what it last ran is kept under, so it's known across restarts. Lowercase letters, digits and `_` (starting with a letter or digit, at most 64 characters), and no two of a package's live schedules share one. Default none: the schedule is forgotten when it stops.",
      },
      {
        name: 'catch_up',
        type: 'boolean?',
        doc: 'Run once when the script starts if a run was missed since the schedule last ran (the server was off over its time). Needs an `id`; a schedule seen for the first time has missed nothing. Default `false`: a missed run is skipped.',
      },
    ],
  },
]
