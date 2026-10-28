import type { Field, LuaClass } from '../types.ts'

/** `nf.spawning`: what natural spawning asks scripts. */
export const nfSpawning: LuaClass = {
  name: 'nf.spawning',
  doc: "Natural spawning (a centity's `spawning` block in `centity.json`): conditions a script decides, which a `spawning` block names with `when`.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'condition',
      doc: "Registers a condition a centity's `spawning.when` can name: the spawner asks `callback` about each place where every other `spawning` rule of that centity fits, and the centity appears there only when it answers `true`. Anything else (`false`, `nil`, an error) is no. It belongs to the script that registered it and goes when that script unloads (a reload, the server stopping), so register it in a module's body. A centity names it bare when it's in the same package as the registering script, `ns:name` from another. A name another live script already registered is an error, and so is one that isn't an id.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'What `spawning.when` names: lowercase letters, digits and `_`, like `"blood_moon"`.',
        },
        {
          name: 'callback',
          type: 'fun(check: SpawnCheck): boolean',
          doc: 'Answers whether the centity may appear at `check.location`. Keep it quick: it runs on the main thread, for every place the spawner tries. An error in it is logged with its file and line and counts as `false`.',
        },
      ],
      returns: [],
      example:
        'local raid = false\nnf.spawning.condition("raid_night", function(check)\n  return raid and check.location.world:moon_phase() == "full_moon"\nend)\nnf.commands.register("raid", function()\n  raid = not raid\nend)',
    },
  ],
}

const field = (name: string, type: string, doc: string): Field => ({ name, type, doc })

/** What a spawn condition is asked with. */
export const spawningShapes: LuaClass[] = [
  {
    name: 'SpawnCheck',
    doc: 'What a spawn condition (`nf.spawning.condition`) is asked about: one centity at one place.',
    methods: false,
    functions: [],
    fields: [
      field(
        'centity',
        'string',
        'Which centity, as the project names it: its folder name under `centities/` (`wisp`), or `acme:wisp` for one a package brings.',
      ),
      field('location', 'Location', 'Where it would stand: the block its feet would be in.'),
      field('player', 'Player', 'The player it would appear near.'),
    ],
  },
]
