import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const behaviour =
  "While it plays, the player spectates a camera that follows the cutscene's path, and can't move or interact. Their game mode, position, flying state and anything riding are put back when it ends, however it ends (including when they leave, a reload or the server stopping); someone who was riding something is dismounted and isn't put back on it. The camera's chunk is kept loaded while it's there, so a path may start and go anywhere in the world, however far from the player."

/** `nf.cutscenes`: playing the project's cutscenes. */
export const nfCutscenes: LuaClass = {
  name: 'nf.cutscenes',
  doc: "The project's cutscenes (`cutscenes/<id>.json`): camera paths keyed in the editor, played for one player at a time.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'play',
      doc: `Starts a cutscene for a player, from its first moment. ${behaviour} It belongs to the script that played it: when that script unloads, it ends (reason \`"unloaded"\`). A player who is already watching one has it end (reason \`"replaced"\`) and the new one start without putting them back first, so cutscenes chain without a flash of the world they were in. A cutscene the project doesn't have, a key \`options\` doesn't take, or one whose file has errors and never had a good version (one that did plays its last good version) is an error. A player who is offline, or a dead one, gets \`nil\`.`,
      params: [
        { name: 'player', type: 'Player', doc: 'Who watches.' },
        {
          name: 'cutscene',
          type: 'string',
          doc: 'Which cutscene: its file name under `cutscenes/`, without `.json`.',
          names: 'cutscene',
        },
        {
          name: 'options',
          type: 'CutscenePlayOptions',
          doc: 'Where the path is, and whether the player may skip it.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'Cutscene?',
          doc: "The playing cutscene, or `nil` when the player can't watch one.",
        },
      ],
      example:
        'nf.commands.register("intro", { players_only = true }, function(event)\n  local player = assert(event.player)\n  local intro = nf.cutscenes.play(player, "intro", { origin = player:location() })\n  if intro then\n    intro:on("end", function(done)\n      done.player:send_message("<gold>Welcome to the village!")\n    end)\n  end\nend)',
    },
    {
      name: 'stop',
      doc: 'Ends the cutscene a player is watching now, with reason `"stopped"`, and puts them back.',
      params: [{ name: 'player', type: 'Player', doc: 'Whose cutscene to end.' }],
      returns: [{ type: 'boolean', doc: '`false` when they were not watching one.' }],
    },
    {
      name: 'current',
      doc: 'The cutscene a player is watching now.',
      params: [{ name: 'player', type: 'Player', doc: 'Who.' }],
      returns: [{ type: 'Cutscene?', doc: '`nil` when they are not watching one.' }],
    },
  ],
}

const cutsceneEvents: EventSpec[] = [
  {
    name: 'end',
    doc: 'The cutscene ended, for any reason (`event.reason` says which): heard once, after the player has been put back (so a handler that moves them or changes their game mode has the last word), while the handle still answers (`is_active()` is still true; `stop()` is `false`, as it is already ending), so `nf.wait_for(cutscene, "end")` hands back the event. After it, the cutscene is gone and so is every handler on it. When the player left, `event.player` is them offline.',
    payload: 'CutsceneEndEvent',
    example:
      'local scene = nf.cutscenes.play(player, "intro")\nif scene then\n  scene:on("end", function(event)\n    if event.reason == "skipped" then\n      event.player:send_message("<gray>You skipped the intro.")\n    end\n  end)\nend',
  },
  {
    name: 'cue',
    doc: 'The cutscene reached a cue that names an `event` (`cues` in its file), at that time.',
    payload: 'CutsceneCueEvent',
    example:
      'local scene = nf.cutscenes.play(player, "intro")\nif scene then\n  scene:on("cue", function(event)\n    if event.cue == "boom" then\n      event.player:play_sound("minecraft:entity.generic.explode")\n    end\n  end)\nend',
  },
]

/** A playing cutscene. */
export const cutsceneClass: LuaClass = {
  name: 'Cutscene',
  doc: "One cutscene playing for one player, from `nf.cutscenes.play`. Once it has ended (it finished, was stopped or skipped, was replaced by another, its player left, or the script that played it unloaded) its methods answer `nil` or `false` rather than erroring, except `kind()` and `length()`. A cutscene is ephemeral: it has no `id()` and can't be saved in a `data()` table.",
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'kind', type: 'string' },
    ],
  },
  fields: [],
  events: cutsceneEvents,
  functions: [
    {
      name: 'kind',
      doc: 'Which cutscene it is: its file name under `cutscenes/`, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'length',
      doc: 'How long it lasts, in seconds, even after it has ended.',
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'player',
      doc: 'Who is watching. `nil` once it has ended.',
      params: [],
      returns: [{ type: 'Player?' }],
    },
    {
      name: 'is_active',
      doc: "Whether it's still playing. `false` once it has ended, for any reason (its `end` handlers still see `true`).",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'time',
      doc: 'Seconds played so far. `nil` once it has ended.',
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'stop',
      doc: 'Ends it now, with reason `"stopped"`, and puts the player back.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    ...eventFunctions(
      'Cutscene',
      'cutscene',
      'local intro = nf.cutscenes.play(player, "intro")\nif intro then\n  intro:on("end", function(event)\n    log("intro ended:", event.reason)\n  end)\nend',
    ),
  ],
}

/** The plain tables cutscenes take and hand out. */
export const cutsceneShapes: LuaClass[] = [
  {
    name: 'CutscenePlayOptions',
    doc: "How `nf.cutscenes.play` plays a cutscene. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'origin',
        type: 'Location|Vec3?',
        doc: "Where the path's coordinates are measured from: every camera position is added to it (the path is moved, not turned). A `Location` also says which world the cutscene is in (its `yaw` and `pitch` are not used); a `Vec3` is in the player's world. Default: the path's coordinates are the world's, in the player's world.",
      },
      {
        name: 'skippable',
        type: 'boolean?',
        doc: 'Whether the player may end it early by sneaking (reason `"skipped"`). Default: what the cutscene\'s file says, which defaults to `false`.',
      },
    ],
  },
  {
    name: 'CutsceneEndEvent',
    doc: 'A cutscene ended.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'cutscene', type: 'Cutscene', doc: 'The cutscene.' },
      { name: 'player', type: 'Player', doc: 'Who was watching.' },
      {
        name: 'reason',
        type: '"finished"|"stopped"|"skipped"|"replaced"|"player_left"|"unloaded"',
        doc: 'Why: it played to its end; `stop()` was called; the player sneaked, in a skippable one; another cutscene was played for the player; the player left the server; or the script that played it unloaded (a reload, the server stopping).',
      },
    ],
  },
  {
    name: 'CutsceneCueEvent',
    doc: 'A cutscene reached a named cue.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'cutscene', type: 'Cutscene', doc: 'The cutscene.' },
      { name: 'player', type: 'Player', doc: 'Who is watching.' },
      { name: 'cue', type: 'string', doc: "The cue's `event` name in the cutscene's file." },
    ],
  },
]
