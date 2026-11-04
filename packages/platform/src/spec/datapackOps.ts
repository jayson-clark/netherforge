import type { PlatformFile } from '../types.ts'

export default {
  source: 'DatapackOps.kt',
  decls: [
    {
      kind: 'interface',
      name: 'DatapackOps',
      doc: "The start-up datapack (format's `StartupDatapack`): what the server loaded\nas the project's while it started, and what building one again needs. The\nadapter builds it before the worlds load, from the project's files alone\n([dev.netherforge.plugin.datapack.StartupDatapackFiles]), and the runtime\nbuilds it again after a reload to tell whether the server must restart.",
      properties: [
        {
          name: 'started',
          doc: "The datapack the server loaded as it started, its files' bytes by path:\nempty when it loaded none (the project put nothing in one, or there was\nno project to read).",
          type: 'Map<String, ByteArray>',
        },
        {
          name: 'refused',
          doc: "The refusal on record the server started without the project's datapacks for\n([dev.netherforge.plugin.datapack.DatapackRefusal]: it refused [started] with them the last time); null when it\nstarted with them, as it does unless they refused it.",
          type: 'dev.netherforge.plugin.datapack.DatapackRefusal?',
        },
        {
          name: 'format',
          doc: "The server's data pack format, `[major, minor]` (from the game's `version.json`); null when it doesn't say.",
          type: 'List<Int>?',
        },
        {
          name: 'mainWorld',
          doc: "The name of the world the server makes as it starts, its main world (`level-name`), as the adapter's start-up\nknew it when it built [started]: whose dimension type the pack replaces when `netherforge.json` names one for\nit. Null when the adapter can't say.",
          type: 'String?',
        },
        {
          name: 'game',
          doc: "The game's own data pack, what the start-up datapack builds on (a sky's twins of its biomes); none by default.",
          type: 'GameDatapack',
          defaulted: true,
        },
      ],
      methods: [
        {
          name: 'textJson',
          params: [
            {
              name: 'text',
              type: 'String',
            },
            {
              name: 'glyph',
              type: '(reference: String) -> String?',
            },
          ],
          returns: 'String',
        },
      ],
    },
  ],
} satisfies PlatformFile
