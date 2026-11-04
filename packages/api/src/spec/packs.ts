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
      doc: "Reads the project's resource packs from disk again and builds them, at the end of this tick: for art that changes without a deploy. That's the project's and its packages' `resource_packs/` (on a server you can write to), and the resource packs packages' scripts wrote into their own `nf.files` folder: `packs/<name>/`, with a `pack.json` and its pictures and sounds as in `resource_packs/<name>/`, is the package's resource pack `<name>` (its glyphs are `<glyph:<name>/<key>>` to its scripts, and an `onDemand` one is pushed by name), which works on a deployed project too and survives restarts. A pack whose files changed is sent again to every player who has it (the project's to everyone online, an `onDemand` one to whoever it was pushed to); one that didn't change sends nothing. A pack in `resource_packs/` with errors keeps its last good build, as a save in the editor does; one the scripts wrote with errors is built from its last good copy, or left out, so the rest still builds, and the server's log says why (`runtime.written-pack`). A written pack can't take a name the package has in `resource_packs/`.",
      params: [],
      returns: [],
      example:
        '-- This week\'s banner, downloaded into a pack of the package\'s own, sent to whoever has it.\nnf.task(function()\n  local image = assert(nf.images.download({ url = "https://cdn.example.com/banner.png" }))\n  assert(nf.files.get("packs/featured/textures/banner.png")):write_base64(image:encode())\n  assert(nf.files.get("packs/featured/pack.json")):write_json({\n    onDemand = true,\n    glyphs = { banner = { texture = "banner.png", height = 64, ascent = 48 } },\n  })\n  nf.packs.rebuild()\nend)',
    },
    {
      name: 'on_demand',
      doc: "The `onDemand` packs there are now, by name as the calling script's package names them: what `player:push_resource_pack` takes. Empty while the packs have errors and nothing is built.",
      params: [],
      returns: [{ type: 'string[]' }],
    },
  ],
}
