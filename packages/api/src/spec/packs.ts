import type { LuaClass } from '../types.ts'

/** `nf.packs`: the project's resource packs as the server builds and sends them. */
export const nfPacks: LuaClass = {
  name: 'nf.packs',
  doc: "The project's resource packs as the server builds and sends them: built again from the files on disk, and which of them are `onDemand` (left out of the pack players get as they join, for `player:push_resource_pack`).",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'rebuild',
      doc: "Reads the project's resource packs from disk again and builds them, at the end of this tick: for art that changes without a deploy (a job that writes this week's banners into `resource_packs/featured/`). A pack whose files changed is sent again to every player who has it (the project's to everyone online, an `onDemand` one to whoever it was pushed to); one that didn't change sends nothing. A pack with errors keeps its last good build, as a save in the editor does.",
      params: [],
      returns: [],
      example:
        '-- Every Monday at 9, after the job that downloads the week\'s banners.\nnf.schedule.weekly("mon", "09:00", function()\n  nf.packs.rebuild()\nend)',
    },
    {
      name: 'on_demand',
      doc: "The `onDemand` packs there are now, by name as the calling script's package names them: what `player:push_resource_pack` takes. Empty while the packs have errors and nothing is built.",
      params: [],
      returns: [{ type: 'string[]' }],
    },
  ],
}
