import type { PlatformFile } from '../types.ts'

export default {
  source: 'ScreenOps.kt',
  decls: [
    {
      kind: 'interface',
      name: 'ScreenOps',
      doc: "Maps as screens (`screen/Screens.kt`): sending a player a map's pixels, the item frames a screen hangs in, a\nmap item to hand out, and the game's own map font.\n\nA screen's maps are numbers the server never made: it has no saved data for them, so it never sends a player\nanything of them itself, and what a player's game shows for one is exactly what the runtime sent them.",
      properties: [],
      methods: [
        {
          name: 'sendMap',
          doc: "Sends [player] part of map [map]: the rectangle at ([x], [y]), [width] by [height] pixels (within 128 by\n128), whose map colours [colors] holds row by row. Their game keeps the rest of the map as it was. False when\nthey're offline.",
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'map',
              type: 'Int',
            },
            {
              name: 'x',
              type: 'Int',
            },
            {
              name: 'y',
              type: 'Int',
            },
            {
              name: 'width',
              type: 'Int',
            },
            {
              name: 'height',
              type: 'Int',
            },
            {
              name: 'colors',
              type: 'ByteArray',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'hang',
          doc: "Hangs an invisible, fixed item frame (a glowing one with [glowing]) holding map [map] in the block at [at],\nfacing [facing], stamped with [tag]: nothing can break, turn or empty it, and it needs nothing behind it.\nIt isn't saved with its chunk (the runtime hangs it again when the chunk comes back). Null when it can't\n(the world isn't loaded).",
          params: [
            {
              name: 'at',
              type: 'Location',
            },
            {
              name: 'facing',
              type: 'ScreenFacing',
            },
            {
              name: 'map',
              type: 'Int',
            },
            {
              name: 'glowing',
              type: 'Boolean',
            },
            {
              name: 'tag',
              type: 'EntityTag',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'mapItem',
          doc: 'A filled map item showing map [map].',
          params: [
            {
              name: 'map',
              type: 'Int',
            },
          ],
          returns: 'ItemData',
        },
        {
          name: 'font',
          doc: "The game's own map font: what `default` draws text in.",
          params: [],
          returns: 'MapFont',
        },
      ],
    },
    {
      kind: 'record',
      name: 'MapFont',
      doc: "A bitmap font: [height] pixels a line, each character's picture by code point. A character it has no picture\nfor draws nothing and takes no room.",
      fields: [
        {
          name: 'height',
          type: 'Int',
        },
        {
          name: 'glyphs',
          type: 'Map<Int, MapGlyph>',
        },
      ],
    },
    {
      kind: 'record',
      name: 'MapGlyph',
      doc: 'One character of a [MapFont]: [width] by [height] pixels, set or not, row by row.',
      fields: [
        {
          name: 'width',
          type: 'Int',
        },
        {
          name: 'height',
          type: 'Int',
        },
        {
          name: 'pixels',
          type: 'BooleanArray',
        },
      ],
    },
  ],
} satisfies PlatformFile
