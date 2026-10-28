import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

/** What an effect can follow: anything with a position and a yaw. */
const FOLLOWABLE = 'Centity|Node|Player|Entity'

const followDoc =
  "Each tick, before it spawns anything, the effect moves to the target: its position (a node's `world_position()`), facing the target's yaw (a node's centity's), pitch 0. When the target stops existing (a removed centity, a player who left, an entity that died or was removed, a centity or entity whose chunk unloaded) the effect ends with reason `\"target_gone\"`."

/** `nf.particles`: playing the project's particle effects. */
export const nfParticles: LuaClass = {
  name: 'nf.particles',
  doc: "The project's particle effects (`particles/<id>/effect.json`): timelines of particles authored in the editor, played at a place by scripts.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'play',
      doc: "Starts playing an effect at a place, from its first tick. It plays for its `duration` (or, looping, until it's stopped) and belongs to the script that played it: when that script unloads, the effect ends (reason `\"unloaded\"`). Only players within 32 blocks see it (128 for an emitter with `force`), and of those only `viewers` when given. An effect the project doesn't have, a key `options` doesn't take, or an option out of range is an error, and so is an effect whose file has errors and never had a good version (one that did plays its last good version). More than 1024 effects playing on the server at once is an error too: stop the ones you're done with.",
      params: [
        {
          name: 'effect',
          type: 'string',
          doc: 'Which effect: its folder name under `particles/`.',
          names: 'particle_effect',
        },
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: "Where it plays: a `Location`, whose `yaw` and `pitch` turn the effect (nil is 0), or a position in the calling centity's world (the server's first world, in a module or a menu or dialog script).",
        },
        {
          name: 'options',
          type: 'ParticlePlayOptions',
          doc: 'Who sees it, how big it is, whether it loops, and what it follows.',
          optional: true,
        },
      ],
      returns: [
        { type: 'Effect?', doc: "The playing effect, or `nil` when the world isn't loaded." },
      ],
      example:
        'assert(this:node("lid")):on("click", function(event)\n  nf.task(function()\n    local here = this:location()\n    local effect = here and nf.particles.play("shockwave", here)\n    if effect then\n      nf.wait_for(effect, "end")\n    end\n    this:play_animation("open")\n  end)\n  event:stop()\nend)',
    },
  ],
}

const effectEvents: EventSpec[] = [
  {
    name: 'end',
    doc: 'The effect ended, for any reason (`event.reason` says which): heard once, while the handle still answers (`is_active()` is still true and `location()` says where it was; `stop()` is `false`, as it\'s already ending), so `nf.wait_for(effect, "end")` hands back the event. After it, the effect is gone and so is every handler on it. A looping effect never ends `"finished"`.',
    payload: 'EffectEndEvent',
    example:
      'effect:on("end", function(event)\n  if event.reason == "target_gone" then\n    log("they left before it was over")\n  end\nend)',
  },
]

/** A playing particle effect. */
export const effectClass: LuaClass = {
  name: 'Effect',
  doc: "One playing particle effect, from `nf.particles.play`. Once it has ended (it finished, was stopped, lost what it followed, its file was deleted, or the script that played it unloaded) its methods answer `nil` or `false` rather than erroring, except `kind()`. An effect is ephemeral: it has no `id()` and can't be saved in a `data()` table.",
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'kind', type: 'string' },
    ],
  },
  fields: [],
  events: effectEvents,
  functions: [
    {
      name: 'kind',
      doc: 'Which effect it is: its folder name under `particles/`, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'is_active',
      doc: "Whether it's still playing. `false` once it has ended, for any reason (its `end` handlers still see `true`).",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'stop',
      doc: 'Ends it now, with reason `"stopped"`. Particles already spawned live out their short lives on the players\' screens.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    {
      name: 'location',
      doc: 'Where it is now, facing the way it faces: in world space. `nil` once it has ended.',
      params: [],
      returns: [{ type: 'Location?' }],
    },
    {
      name: 'teleport',
      doc: "Moves it, and stops it following anything. A `Location`'s `yaw` and `pitch` become its facing (nil is 0); a `Vec3` keeps its world and its facing.",
      params: [
        { name: 'location_or_position', type: 'Location|Vec3', doc: 'Where to, in world space.' },
      ],
      returns: [{ type: 'boolean', doc: '`false` once it has ended.' }],
    },
    {
      name: 'follow',
      doc: `Makes it follow something, from the next tick on. ${followDoc} Calling it again switches targets; \`teleport\` stops following.`,
      params: [
        { name: 'target', type: FOLLOWABLE, doc: 'What to follow.' },
        {
          name: 'offset',
          type: 'Vec3',
          doc: "Where the effect sits from the target, in the effect's own space (turned with the target's yaw): `vec3(0, 2, 0)` is above its head, `vec3(0, 0, 1)` in front. Default `vec3.zero`.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once it has ended.' }],
      example:
        'local here = player:location()\nlocal halo = here and nf.particles.play("halo", here, { loop = true })\nif halo then\n  halo:follow(player, vec3(0, 2.2, 0))\nend',
    },
    ...eventFunctions(
      'Effect',
      'effect',
      'local here = this:location()\nlocal effect = here and nf.particles.play("sparkle", here)\nif effect then\n  effect:on("end", function(event)\n    log("sparkle ended:", event.reason)\n  end)\nend',
    ),
  ],
}

const shape = (
  name: string,
  doc: string,
  fields: LuaClass['fields'],
  extend?: string,
): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  ...(extend ? { extends: extend } : {}),
})

/** The plain tables particle effects take and hand out. */
export const particleShapes: LuaClass[] = [
  shape(
    'ParticlePlayOptions',
    "How `nf.particles.play` plays an effect. Every key is optional; a key that isn't one of these is an error.",
    [
      {
        name: 'viewers',
        type: 'Player[]?',
        doc: 'Only these players see it (still only those within range). Players who leave drop out. Default: everyone in range.',
      },
      {
        name: 'scale',
        type: 'number?',
        doc: 'Multiplies its distances: shape sizes, offsets, spreads and speeds (not dust sizes). More than 0, at most 16. Default 1.',
      },
      {
        name: 'loop',
        type: 'boolean?',
        doc: "Whether it starts again after its last tick, until it's stopped. Default: what its file says.",
      },
      {
        name: 'follow',
        type: `${FOLLOWABLE}?`,
        doc: `Follow this from the start, as \`effect:follow\` does. ${followDoc} \`location_or_position\` is still needed: it's where the effect is if the target is already gone, which ends it on its first tick.`,
      },
      {
        name: 'offset',
        type: 'Vec3?',
        doc: "With `follow`: where the effect sits from the target, as for `effect:follow`. Without `follow` it's an error.",
      },
    ],
  ),
  shape(
    'EffectEndEvent',
    'A particle effect ended.',
    [
      { name: 'effect', type: 'Effect', doc: 'The effect.' },
      {
        name: 'reason',
        type: '"finished"|"stopped"|"target_gone"|"removed"|"unloaded"',
        doc: 'Why: it played to its end; `stop()` was called; what it followed went; its file was deleted; or the script that played it unloaded (a reload, its centity removed, the server stopping).',
      },
    ],
    'Event',
  ),
]
