import type { PlatformFile } from '../types.ts'

export default {
  source: 'ChunkGrid.kt',
  decls: [
    {
      kind: 'record',
      name: 'ChunkGrid',
      doc: "A small number (1 to 65535) for some of one chunk's positions, kept a\nsection at a time: a chunk full of them costs a few kilobytes a section,\nnot an object a block. x and z are the chunk's own (0 to 15), y the world's.",
      fields: [],
    },
  ],
} satisfies PlatformFile
